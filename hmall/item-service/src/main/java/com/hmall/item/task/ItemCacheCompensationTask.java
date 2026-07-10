package com.hmall.item.task;

import com.hmall.common.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 商品缓存补偿任务（每 5 分钟执行）
 * <p>
 * 维护 Redis Set `item:cache:dirty`：写操作时由 ItemController 添加变更商品 ID。
 * 定期遍历 Set，逐条确认 Redis 缓存已失效，未失效则补充删除。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemCacheCompensationTask {

    private final RedisService redisService;

    private static final String ITEM_CACHE_DIRTY_KEY = "item:cache:dirty";
    private static final String ITEM_CACHE_PREFIX = "item:info:";
    private static final long DIRTY_SET_TTL_MINUTES = 15;

    /**
     * 写操作后记录脏商品 ID（由 ItemController 等调用方注入到 dirty set）
     */
    public void markDirty(Long itemId) {
        try {
            redisService.sAdd(ITEM_CACHE_DIRTY_KEY, itemId.toString());
            redisService.expire(ITEM_CACHE_DIRTY_KEY, DIRTY_SET_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("标记商品缓存脏数据失败，itemId={}", itemId, e);
        }
    }

    /**
     * 每 5 分钟执行补偿检查
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void compensate() {
        log.info("商品缓存补偿任务开始");
        try {
            Set<Object> dirtyIds = redisService.sMembers(ITEM_CACHE_DIRTY_KEY);
            if (dirtyIds == null || dirtyIds.isEmpty()) {
                log.info("无商品缓存脏数据，补偿任务跳过");
                return;
            }

            int checkedCount = 0;
            int deletedCount = 0;
            for (Object idObj : dirtyIds) {
                try {
                    Long itemId = Long.valueOf(idObj.toString());
                    String cacheKey = ITEM_CACHE_PREFIX + itemId;

                    // 检查缓存是否已失效，若未失效则补充删除
                    if (redisService.hasKey(cacheKey)) {
                        redisService.delete(cacheKey);
                        deletedCount++;
                    }
                    checkedCount++;
                } catch (Exception e) {
                    log.warn("补偿检查商品缓存失败，itemId={}", idObj, e);
                }
            }

            // 清理已检查的 dirty set
            redisService.delete(ITEM_CACHE_DIRTY_KEY);
            log.info("商品缓存补偿任务完成，检查 {} 条，补充删除 {} 条", checkedCount, deletedCount);
        } catch (Exception e) {
            log.error("商品缓存补偿任务异常", e);
        }
    }
}
