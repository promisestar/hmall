package com.hmall.cart.mq;

import com.hmall.cart.domain.dto.CartSyncMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 购物车同步消息生产者：加购后异步通知 MySQL 落库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartSyncSender {

    private final RabbitTemplate rabbitTemplate;

    private static final String CART_SYNC_EXCHANGE = "cart.sync.topic";
    private static final String CART_SYNC_ROUTING_KEY = "cart.sync";

    /**
     * 发送购物车同步消息（异步落 MySQL），发送失败不阻断主流程，由补偿任务兜底
     */
    public void sendSync(CartSyncMessage message) {
        try {
            rabbitTemplate.convertAndSend(CART_SYNC_EXCHANGE, CART_SYNC_ROUTING_KEY, message);
            log.debug("购物车同步消息已发送，userId={}, itemId={}", message.getUserId(), message.getItemId());
        } catch (Exception e) {
            log.warn("购物车同步消息发送失败（将由补偿任务兜底），userId={}, itemId={}",
                    message.getUserId(), message.getItemId(), e);
        }
    }
}
