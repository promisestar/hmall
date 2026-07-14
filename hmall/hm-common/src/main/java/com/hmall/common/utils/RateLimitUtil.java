package com.hmall.common.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;

/**
 * 滑动窗口限流工具（基于 Redis ZSET + Lua 原子操作）
 * <p>
 * 通过 {@code LuaScriptLoader} 加载 {@code sliding_window_rate_limit.lua} 脚本，
 * 将 ZREMRANGEBYSCORE + ZCARD + ZADD + PEXPIRE 合并为原子操作。
 * <p>
 * 被 {@link com.hmall.common.aspect.RedisCacheAspect} 包裹，
 * Redis 不可用时 executeScript 返回 null，本工具返回 true（fail-open 降级）。
 * <p>
 * 使用 {@link StringRedisTemplate} 执行 Lua 脚本，避免 Jackson 序列化引号
 * 导致 Lua tonumber() 失败（遵循项目双 Template 约定）。
 */
@Component
@ConditionalOnProperty(prefix = "spring.redis", name = "host")
public class RateLimitUtil {

    private static final Logger log = LoggerFactory.getLogger(RateLimitUtil.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String SLIDING_WINDOW_LUA = LuaScriptLoader.load("lua/sliding_window_rate_limit.lua");

    /**
     * 检查请求是否被允许通过（滑动窗口限流）
     * <p>
     * Redis 不可用时返回 true（fail-open），不阻塞请求，依赖后端兜底。
     *
     * @param key          限流 key（如 "ratelimit:/seckill/order:123"）
     * @param maxRequests  窗口内最大请求数
     * @param windowMs     窗口大小（毫秒）
     * @return true 允许通过，false 被限流
     */
    public boolean allowRequest(String key, int maxRequests, long windowMs) {
        try {
            String requestId = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();

            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(SLIDING_WINDOW_LUA, Long.class);
            Long result = stringRedisTemplate.execute(
                    redisScript,
                    Collections.singletonList(key),
                    String.valueOf(now),
                    String.valueOf(windowMs),
                    String.valueOf(maxRequests),
                    requestId
            );

            if (result == null) {
                // Redis 异常被 RedisCacheAspect 捕获后可能返回 null
                log.warn("限流脚本返回 null，降级放行, key={}", key);
                return true;
            }

            return result == 1L;
        } catch (Exception e) {
            log.warn("限流检查异常，降级放行, key={}", key, e);
            return true;
        }
    }
}
