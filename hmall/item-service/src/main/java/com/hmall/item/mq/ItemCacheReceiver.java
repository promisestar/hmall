package com.hmall.item.mq;

import com.hmall.common.service.RedisService;
import com.hmall.item.domain.dto.ItemCacheMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 商品缓存失效消息消费者：MQ 二次确认删除 Redis 缓存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemCacheReceiver {

    private final RedisService redisService;

    private static final String ITEM_CACHE_PREFIX = "item:info:";

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "item.cache.invalidate.queue", durable = "true"),
            exchange = @Exchange(name = "item.cache.topic", type = "topic"),
            key = "item.cache.invalidate"
    ))
    public void onCacheInvalidate(ItemCacheMessage message) {
        try {
            String cacheKey = ITEM_CACHE_PREFIX + message.getItemId();
            redisService.delete(cacheKey);
            log.debug("商品缓存二次确认删除成功，itemId={}", message.getItemId());
        } catch (Exception e) {
            log.error("商品缓存二次确认删除失败，itemId={}", message.getItemId(), e);
        }
    }
}
