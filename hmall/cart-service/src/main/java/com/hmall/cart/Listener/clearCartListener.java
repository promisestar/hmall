package com.hmall.cart.Listener;

import com.hmall.cart.service.ICartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * ClassName: clearCartListener
 * Package: com.hmall.cart.Listener
 * Description: 清理购物车消息监听器
 *
 * @Author Raiden
 * @Create 2025/12/31 13:45
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class clearCartListener {

    private final ICartService cartService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "cart.clear.queue", durable = "true"),
            exchange = @Exchange(name = "trade.topic", type = "topic"),
            key = "order.create"
    ))
    public void listenClearCart(Collection<Long> itemIds, @Header(value = "USER-ID", required = false) Number userIdObj) {
        try {
            Long userId = userIdObj != null ? userIdObj.longValue() : null;
            if (userId != null && !itemIds.isEmpty()) {
                cartService.removeByItemIds(itemIds, userId);
            }
        } catch (Exception e) {
            log.error("清理购物车失败，userId={}, itemIds={}", userIdObj, itemIds, e);
        }
    }
}
