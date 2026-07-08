package com.hmall.trade.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.api.client.CartClient;
import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.api.dto.OrderDetailDTO;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.UserContext;

import com.hmall.trade.constants.MQConstants;
import com.hmall.trade.domain.dto.OrderFormDTO;
import com.hmall.trade.domain.po.LocalMessage;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.domain.po.OrderDetail;
import com.hmall.common.utils.CollUtils;
import com.hmall.trade.domain.vo.OrderDetailVO;
import com.hmall.trade.domain.vo.OrderVO;
import com.hmall.trade.mapper.LocalMessageMapper;
import com.hmall.trade.mapper.OrderMapper;
import com.hmall.trade.service.IOrderDetailService;
import com.hmall.trade.service.IOrderService;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private final ItemClient itemClient;
    private final IOrderDetailService detailService;
    private final CartClient cartClient;
    private final RabbitTemplate rabbitTemplate;
    private final LocalMessageMapper localMessageMapper;

    @Override
    @GlobalTransactional
    public Long createOrder(OrderFormDTO orderFormDTO) {
        // 1.订单数据
        Order order = new Order();
        // 1.1.查询商品
        List<OrderDetailDTO> detailDTOS = orderFormDTO.getDetails();
        // 1.2.获取商品id和数量的Map
        Map<Long, Integer> itemNumMap = detailDTOS.stream()
                .collect(Collectors.toMap(OrderDetailDTO::getItemId, OrderDetailDTO::getNum));
        Set<Long> itemIds = itemNumMap.keySet();
        // 1.3.查询商品
        List<ItemDTO> items = itemClient.queryItemsByIds(itemIds);
        if (items == null || items.size() < itemIds.size()) {
            throw new BadRequestException("商品不存在");
        }
        // 1.4.基于商品价格、购买数量计算商品总价：totalFee
        int total = 0;
        for (ItemDTO item : items) {
            total += item.getPrice() * itemNumMap.get(item.getId());
        }
        order.setTotalFee(total);
        // 1.5.其它属性
        order.setPaymentType(orderFormDTO.getPaymentType());
        order.setUserId(UserContext.getUser());
        order.setStatus(1);
        // 1.6.将Order写入数据库order表中
        save(order);

        // 2.保存订单详情
        List<OrderDetail> details = buildDetails(order.getId(), items, itemNumMap);
        detailService.saveBatch(details);

        // 3.清理购物车商品
        // cartClient.deleteCartItemByIds(itemIds);
        // 使用rabbitmq异步实现
//        try{
//            rabbitTemplate.convertAndSend(MQConstants.CLEAR_CART_EXCHANGE_NAME, MQConstants.CLEAR_CART_KEY, itemIds);
//        }catch(Exception e){
//            log.error("发送清理购物车失败，用户id：{}, 商品id：{}", UserContext.getUser(), itemIds, e);
//        }
        // 使用本地消息表，保证分布式事务的一致性
        // 4. 插入本地消息表（与上述操作在同一事务中）
        String messageId = order.getId() + "_pay_success"; // 保证唯一
        LocalMessage localMessage = new LocalMessage();
        localMessage.setMessageId(messageId);
        localMessage.setExchange(MQConstants.CLEAR_CART_EXCHANGE_NAME);
        localMessage.setRoutingKey(MQConstants.CLEAR_CART_KEY);
        localMessage.setUserId(UserContext.getUser());
        localMessage.setMessageBody(new ArrayList<>(itemIds)); // 简单字符串，也可用 JSON
        localMessage.setStatus(0); // 待发送
        localMessage.setTryCount(0);

        localMessageMapper.insert(localMessage);

        // 4.扣减库存
        try {
            itemClient.deductStock(detailDTOS);
        } catch (Exception e) {
            throw new RuntimeException("库存不足！");
        }
        // 5. 发送延迟消息
        rabbitTemplate.convertAndSend(
                MQConstants.DELAY_EXCHANGE_NAME,
                MQConstants.DELAY_ORDER_KEY,
                order.getId(),
                message -> {
                    message.getMessageProperties().setDelay(1800000);
                    return message;
                }
        );
        return order.getId();
    }

    @Override
    public void markOrderPaySuccess(Long orderId) {
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(2);
        order.setPayTime(LocalDateTime.now());
        updateById(order);
    }

    @Override
    public void cancelOrder(Long orderId) {
        // 1. 检查该订单是否存在且仍未支付
        Order order = this.getById(orderId);
        if (order == null || order.getStatus() != 1) {
           return;
        }
        // 1. 恢复库存
        List<OrderDetail> orderDetails = detailService.lambdaQuery().eq(OrderDetail::getOrderId, orderId).list();
        List<OrderDetailDTO> orderDetailDTOS = BeanUtils.copyList(orderDetails, OrderDetailDTO.class);
        itemClient.recoverStock(orderDetailDTOS);
        // 2. 删除订单
        this.removeById(orderId);

    }

    @Override
    public PageDTO<OrderVO> queryOrderPage(PageQuery pageQuery) {
        // 1. 分页查询订单
        Page<Order> page = lambdaQuery()
                .eq(Order::getUserId, UserContext.getUser())
                .orderByDesc(Order::getCreateTime)
                .page(pageQuery.toMpPageDefaultSortByCreateTimeDesc());

        List<Order> orders = page.getRecords();
        if (CollUtils.isEmpty(orders)) {
            return PageDTO.empty(page);
        }

        // 2. 批量查询所有订单的详情
        List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
        List<OrderDetail> allDetails = detailService.lambdaQuery()
                .in(OrderDetail::getOrderId, orderIds)
                .list();

        // 3. 按订单ID分组
        Map<Long, List<OrderDetail>> detailMap = allDetails.stream()
                .collect(Collectors.groupingBy(OrderDetail::getOrderId));

        // 4. 转换为 OrderVO 并填充详情
        List<OrderVO> voList = BeanUtils.copyList(orders, OrderVO.class);
        for (OrderVO vo : voList) {
            List<OrderDetail> details = detailMap.get(vo.getId());
            if (details != null) {
                vo.setDetailVOs(BeanUtils.copyList(details, OrderDetailVO.class));
            }
        }

        return PageDTO.of(page, voList);
    }

    private List<OrderDetail> buildDetails(Long orderId, List<ItemDTO> items, Map<Long, Integer> numMap) {
        List<OrderDetail> details = new ArrayList<>(items.size());
        for (ItemDTO item : items) {
            OrderDetail detail = new OrderDetail();
            detail.setName(item.getName());
            detail.setSpec(item.getSpec());
            detail.setPrice(item.getPrice());
            detail.setNum(numMap.get(item.getId()));
            detail.setItemId(item.getId());
            detail.setImage(item.getImage());
            detail.setOrderId(orderId);
            details.add(detail);
        }
        return details;
    }
}
