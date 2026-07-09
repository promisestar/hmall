package com.hmall.trade.Listener;

import com.hmall.api.client.PayClient;
import com.hmall.api.dto.PayOrderDTO;
import com.hmall.trade.constants.MQConstants;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * ClassName: orderDelayMessageListener
 * Package: com.hmall.trade.Listener
 * Description: 订单延迟消息监听器（30分钟未支付自动取消）
 *
 * @Author Raiden
 * @Create 2025/11/26 20:35
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class orderDelayMessageListener {

    private final IOrderService orderService;

    private final PayClient payClient;

    // 监听器
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MQConstants.DELAY_ORDER_QUEUE_NAME),
            exchange = @Exchange(name = MQConstants.DELAY_EXCHANGE_NAME, delayed = "true"),
            key = MQConstants.DELAY_ORDER_KEY
    ))
    public void listenOrderDelayMessage(Long orderId) {
        try {
            // 1. 查询订单
            Order order = orderService.getById(orderId);
            // 2. 查询订单状态，判断是否已经支付
            if (order == null || order.getStatus() != 1) {
                return;
            }
            // 3. 未支付，远程调用，查询支付流水状态
            PayOrderDTO payOrder = payClient.queryPayOrderByBizOrderNo(orderId);
            // 4. 判断是否支付
            if (payOrder != null && payOrder.getStatus() == 3) {
                // 5. 已支付，标记订单状态
                orderService.markOrderPaySuccess(orderId);
            } else {
                // 6. 未支付，取消订单，恢复库存
                orderService.cancelOrder(orderId);
            }
        } catch (Exception e) {
            log.error("处理订单延迟消息失败，orderId={}", orderId, e);
        }
    }
}
