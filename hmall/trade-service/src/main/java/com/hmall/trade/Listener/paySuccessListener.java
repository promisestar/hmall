package com.hmall.trade.Listener;

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
 * ClassName: paySuccessListener
 * Package: com.hmall.trade.Listener
 * Description: 支付成功消息监听器
 *
 * @Author Raiden
 * @Create 2025/11/20 21:16
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class paySuccessListener {

    private final IOrderService orderService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "trade.pay.success.queue", durable = "true"),
            exchange = @Exchange(name = "pay.direct"),
            key = "pay.success"
    ))
    public void listenPaySuccess(Long orderId) {
        try {
            // 1. 查询订单
            Order order = orderService.getById(orderId);
            // 2. 若订单不为未支付状态，则忽略
            if (order == null || order.getStatus() != 1)
                return;
            orderService.markOrderPaySuccess(orderId);
        } catch (Exception e) {
            log.error("处理支付成功消息失败，orderId={}", orderId, e);
        }
    }
}
