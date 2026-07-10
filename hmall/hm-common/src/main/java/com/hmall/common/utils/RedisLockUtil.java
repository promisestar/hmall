package com.hmall.common.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

/**
 * 分布式锁工具（基于 Redis SET NX EX + Lua 原子释放）
 */
@Component
@ConditionalOnProperty(prefix = "spring.redis", name = "host")
public class RedisLockUtil {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 原子释放锁 Lua 脚本（从 classpath 加载）
     */
    private static final String RELEASE_LOCK_LUA = LuaScriptLoader.load("lua/release_lock.lua");

    /**
     * 尝试获取分布式锁
     *
     * @param key           锁的 key
     * @param value         锁的值（建议使用 UUID，用于释放时校验持有者身份）
     * @param expireSeconds 锁过期时间（秒）
     * @return true 获取成功，false 获取失败
     */
    public boolean tryLock(String key, String value, long expireSeconds) {
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(key, value, Duration.ofSeconds(expireSeconds));
        return Boolean.TRUE.equals(result);
    }

    /**
     * 释放分布式锁（Lua 原子释放：只有持有者才能释放）
     *
     * @param key   锁的 key
     * @param value 锁的值（需与获取时一致）
     */
    public void releaseLock(String key, String value) {
        redisTemplate.execute(
                new DefaultRedisScript<>(RELEASE_LOCK_LUA, Long.class),
                Collections.singletonList(key),
                value
        );
    }
}
