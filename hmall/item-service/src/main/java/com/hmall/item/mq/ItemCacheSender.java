package com.hmall.item.mq;

import com.hmall.item.domain.dto.ItemCacheMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 商品缓存失效消息生产者：写操作后通知 MQ 二次确认删除缓存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemCacheSender {

    private final RabbitTemplate rabbitTemplate;

    private static final String ITEM_CACHE_EXCHANGE = "item.cache.topic";
    private static final String ITEM_CACHE_ROUTING_KEY = "item.cache.invalidate";

    /**
     * 发送缓存失效消息（用于 MQ 二次确认删除），发送失败不阻断主流程
     */
    public void sendInvalidate(Long itemId) {
        try {
            ItemCacheMessage message = new ItemCacheMessage(itemId, System.currentTimeMillis());
            rabbitTemplate.convertAndSend(ITEM_CACHE_EXCHANGE, ITEM_CACHE_ROUTING_KEY, message);
            log.debug("商品缓存失效消息已发送，itemId={}", itemId);
        } catch (Exception e) {
            log.warn("商品缓存失效消息发送失败，itemId={}", itemId, e);
        }
    }
}
