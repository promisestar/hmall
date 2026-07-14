package com.hmall.trade.Listener;

import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.common.service.RedisService;
import com.hmall.trade.constants.MQConstants;
import com.hmall.trade.domain.dto.SeckillOrderMessage;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.domain.po.OrderDetail;
import com.hmall.trade.domain.po.SeckillDailyStock;
import com.hmall.trade.domain.po.SeckillOrder;
import com.hmall.trade.mapper.SeckillDailyStockMapper;
import com.hmall.trade.mapper.SeckillOrderMapper;
import com.hmall.trade.service.IOrderDetailService;
import com.hmall.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单 MQ 消费者
 * <p>
 * 三层防超卖架构的第三层：MySQL 行锁最终扣减。
 * <p>
 * 流程：
 * 1. SELECT ... FOR UPDATE 行锁锁定 seckill_daily_stock
 * 2. 检查 stock >= quantity，不足则回补 Redis 库存+限购，设置失败结果
 * 3. 扣减 MySQL 库存（UPDATE WHERE stock >= quantity）
 * 4. 创建订单（order + order_detail + seckill_order）
 * 5. 发送延迟消息（30 分钟超时取消）
 * 6. 设置 Redis 结果 key（前端轮询用）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderListener {

    private final SeckillDailyStockMapper dailyStockMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final IOrderService orderService;
    private final IOrderDetailService detailService;
    private final ItemClient itemClient;
    private final RedisService redisService;
    private final RabbitTemplate rabbitTemplate;

    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String LIMIT_KEY_PREFIX = "seckill:limit:";
    private static final String RESULT_KEY_PREFIX = "seckill:result:";
    private static final long RESULT_TTL_SECONDS = 120;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MQConstants.SECKILL_ORDER_QUEUE_NAME, durable = "true"),
            exchange = @Exchange(name = MQConstants.SECKILL_EXCHANGE_NAME, type = "topic"),
            key = MQConstants.SECKILL_ORDER_KEY
    ))
    @Transactional
    public void onSeckillOrder(SeckillOrderMessage message) {
        Long relationId = message.getRelationId();
        Long userId = message.getUserId();
        int quantity = message.getQuantity();

        log.info("秒杀MQ消费开始, relationId={}, userId={}, quantity={}", relationId, userId, quantity);

        // 1. 查询商品信息（用于订单详情）
        ItemDTO item = null;
        try {
            item = itemClient.queryItemById(message.getProductId());
        } catch (Exception e) {
            log.error("查询商品信息失败, productId={}", message.getProductId(), e);
        }

        // 2. FOR UPDATE 行锁查询库存
        LocalDate today = LocalDate.now();
        SeckillDailyStock dailyStock = dailyStockMapper.selectForUpdate(relationId, today);

        if (dailyStock == null || dailyStock.getStock() < quantity) {
            // MySQL 库存不足 → 回补 Redis 库存和限购额度
            log.warn("秒杀MQ扣减失败(MySQL库存不足), relationId={}, userId={}, dbStock={}",
                    relationId, userId, dailyStock == null ? null : dailyStock.getStock());
            rollbackRedis(relationId, userId, quantity);
            setResult(userId, relationId, "0");
            return;
        }

        // 3. 扣减 MySQL 库存（WHERE stock >= quantity 双重保证）
        int updated = dailyStockMapper.deductStock(relationId, today, quantity);
        if (updated == 0) {
            // 并发情况下另一个线程先扣减了 → 回补 Redis
            log.warn("秒杀MQ扣减失败(并发竞争), relationId={}, userId={}", relationId, userId);
            rollbackRedis(relationId, userId, quantity);
            setResult(userId, relationId, "0");
            return;
        }

        // 4. 创建订单
        Order order = new Order();
        order.setTotalFee(message.getSeckillPrice() * quantity);
        order.setPaymentType(1);
        order.setUserId(userId);
        order.setStatus(1);
        orderService.save(order);

        // 5. 创建订单详情（秒杀价）
        OrderDetail detail = new OrderDetail();
        detail.setOrderId(order.getId());
        detail.setItemId(message.getProductId());
        detail.setNum(quantity);
        detail.setPrice(message.getSeckillPrice());
        if (item != null) {
            detail.setName(item.getName());
            detail.setSpec(item.getSpec());
            detail.setImage(item.getImage());
        }
        detailService.save(detail);

        // 6. 创建秒杀订单关联记录
        SeckillOrder seckillOrder = new SeckillOrder();
        seckillOrder.setOrderId(order.getId());
        seckillOrder.setRelationId(relationId);
        seckillOrder.setUserId(userId);
        seckillOrder.setQuantity(quantity);
        seckillOrder.setStatus(1);
        seckillOrderMapper.insert(seckillOrder);

        // 7. 发送延迟消息（30 分钟超时取消，复用现有机制）
        try {
            rabbitTemplate.convertAndSend(
                    MQConstants.DELAY_EXCHANGE_NAME,
                    MQConstants.DELAY_ORDER_KEY,
                    order.getId(),
                    msg -> {
                        msg.getMessageProperties().setDelay(1800000);
                        return msg;
                    }
            );
        } catch (Exception e) {
            log.error("发送秒杀订单延迟消息失败, orderId={}", order.getId(), e);
        }

        // 8. 设置 Redis 结果（前端轮询用）
        setResult(userId, relationId, String.valueOf(order.getId()));

        log.info("秒杀MQ消费成功, relationId={}, userId={}, orderId={}", relationId, userId, order.getId());
    }

    /**
     * 回补 Redis 库存和限购额度（MySQL 库存不足时调用）
     */
    private void rollbackRedis(Long relationId, Long userId, int quantity) {
        String stockKey = STOCK_KEY_PREFIX + relationId;
        String limitKey = LIMIT_KEY_PREFIX + relationId;
        try {
            redisService.incrBy(stockKey, quantity);
            redisService.hIncrBy(limitKey, String.valueOf(userId), -quantity);
        } catch (Exception e) {
            log.error("回补Redis库存失败, relationId={}, userId={}, quantity={}", relationId, userId, quantity, e);
        }
    }

    /**
     * 设置 Redis 结果 key
     */
    private void setResult(Long userId, Long relationId, String value) {
        String resultKey = RESULT_KEY_PREFIX + userId + ":" + relationId;
        try {
            redisService.set(resultKey, value, RESULT_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("设置秒杀结果key失败, key={}", resultKey, e);
        }
    }
}
