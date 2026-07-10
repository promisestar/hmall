package com.hmall.common.service;

import com.hmall.common.utils.LuaScriptLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis 操作封装工具类
 */
@Component
@ConditionalOnProperty(prefix = "spring.redis", name = "host")
public class RedisService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // ==================== String 操作 ====================

    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    public void set(String key, Object value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        return (T) value;
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void delete(String key) {
        redisTemplate.delete(key);
    }

    public void expire(String key, long timeout, TimeUnit unit) {
        redisTemplate.expire(key, timeout, unit);
    }

    public Long getExpire(String key) {
        return redisTemplate.getExpire(key);
    }

    // ==================== Hash 操作 ====================

    public void hSet(String key, String field, Object value) {
        redisTemplate.opsForHash().put(key, field, value);
    }

    public Object hGet(String key, String field) {
        return redisTemplate.opsForHash().get(key, field);
    }

    public Map<Object, Object> hGetAll(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    public void hDel(String key, Object... fields) {
        redisTemplate.opsForHash().delete(key, fields);
    }

    public boolean hHasKey(String key, String field) {
        return redisTemplate.opsForHash().hasKey(key, field);
    }

    /**
     * Hash 字段原子递增（HINCRBY）
     */
    public Long hIncrBy(String key, String field, long delta) {
        return redisTemplate.opsForHash().increment(key, field, delta);
    }

    /**
     * Hash 字段数量（HLEN）
     */
    public Long hLen(String key) {
        return redisTemplate.opsForHash().size(key);
    }

    // ==================== Lua 脚本执行 ====================

    /**
     * 执行 Lua 脚本，保证多条 Redis 命令的原子性
     *
     * @param script Lua 脚本内容
     * @param keys   KEYS 数组
     * @param args   ARGV 数组
     * @return 脚本返回值
     */
    public <T> T executeScript(String script, Class<T> resultType, List<String> keys, Object... args) {
        DefaultRedisScript<T> redisScript = new DefaultRedisScript<>(script, resultType);
        return redisTemplate.execute(redisScript, keys, args);
    }

    /**
     * 执行 Lua 脚本（通用版本，返回 Object）
     */
    public Object executeScript(String script, List<String> keys, Object... args) {
        return executeScript(script, Object.class, keys, args);
    }

    /**
     * 批量删除 Hash 字段，使用 Lua 保证原子性
     */
    public void hDelAtomic(String key, Object... fields) {
        executeScript(HDEL_ATOMIC_LUA, Collections.singletonList(key), fields);
    }

    /**
     * SET NX EX：仅当 key 不存在时设置，带过期时间（原子操作）
     * 用于缓存回写时避免覆盖已被刷新的数据
     *
     * @return true 设置成功，false key 已存在
     */
    public boolean setIfAbsent(String key, Object value, long timeout, TimeUnit unit) {
        String result = executeScript(SET_IF_ABSENT_LUA, String.class,
                Collections.singletonList(key), value, String.valueOf(unit.toSeconds(timeout)));
        return "OK".equals(result);
    }

    // ==================== Lua 脚本常量 ====================

    private static final String HDEL_ATOMIC_LUA = LuaScriptLoader.load("lua/hdel_atomic.lua");
    private static final String SET_IF_ABSENT_LUA = LuaScriptLoader.load("lua/set_if_absent.lua");

    // ==================== 预留 P1/P2 扩展场景 ====================

    // ---------- P1: 验证码存储 ----------

    private static final String SMS_CODE_PREFIX = "sms:code:";
    private static final long SMS_CODE_TTL_MINUTES = 5;

    /**
     * 存储手机验证码（TTL 5 分钟自动过期）
     */
    public void saveSmsCode(String phone, String code) {
        set(SMS_CODE_PREFIX + phone, code, SMS_CODE_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 校验手机验证码
     */
    public String getSmsCode(String phone) {
        return get(SMS_CODE_PREFIX + phone, String.class);
    }

    /**
     * 删除手机验证码（校验通过后清除）
     */
    public void deleteSmsCode(String phone) {
        delete(SMS_CODE_PREFIX + phone);
    }

    // ---------- P1: Token 黑名单 ----------

    private static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";

    /**
     * 将 token 加入黑名单（登出时调用，TTL = token 剩余有效期）
     */
    public void addTokenToBlacklist(String jti, long ttlSeconds) {
        set(TOKEN_BLACKLIST_PREFIX + jti, "1", ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 检查 token 是否在黑名单中（Gateway 校验时调用）
     */
    public boolean isTokenBlacklisted(String jti) {
        return hasKey(TOKEN_BLACKLIST_PREFIX + jti);
    }

    // ---------- P2: 秒杀库存 ----------

    /**
     * 字符串递增（可用于秒杀库存计数）
     */
    public Long incrBy(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }

    /**
     * 字符串递减（可用于秒杀库存扣减）
     */
    public Long decrBy(String key, long delta) {
        return redisTemplate.opsForValue().decrement(key, delta);
    }
}
