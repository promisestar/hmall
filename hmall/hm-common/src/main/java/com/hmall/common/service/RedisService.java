package com.hmall.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmall.common.utils.LuaScriptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 操作封装工具类
 * <p>
 * 双 Tempalte 设计：
 * <ul>
 *   <li>{@code redisTemplate}（Jackson 序列化）：常规 String/Hash/Set 操作，
 *       自动完成 Object ↔ JSON 转换</li>
 *   <li>{@code stringRedisTemplate}（String 序列化）：Lua 脚本执行专用，
 *       脚本返回值是 Redis 原生类型（"OK"、数字、nil），不是 JSON，
 *       用 String 序列化避免 Jackson 反序列化非 JSON 结果异常</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "spring.redis", name = "host")
public class RedisService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final Logger log = LoggerFactory.getLogger(RedisService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

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
     * 执行 Lua 脚本，保证多条 Redis 命令的原子性。
     * <p>
     * 使用 {@link StringRedisTemplate} 执行，原因：
     * <ul>
     *   <li>args 通过 StringRedisSerializer 序列化为纯字符串（Long→"1800"，String→原始字节），
     *       不会出现 Jackson 给 String 加引号导致 Lua tonumber() 失败的问题</li>
     *   <li>脚本返回值是 Redis 原生类型（"OK"、数字、nil），不是 JSON，
     *       用 StringRedisTemplate 反序列化结果不会抛 Jackson 解析异常</li>
     * </ul>
     * <p>
     * <b>调用方注意</b>：复杂对象必须预先序列化为 JSON String 再传入（如 ObjectMapper.writeValueAsString），
     * 否则 StringRedisSerializer.toString() 只会输出类名@哈希码。
     *
     * @param script     Lua 脚本内容
     * @param resultType 返回值类型（常用 String.class / Long.class / Object.class）
     * @param keys       KEYS 数组
     * @param args       ARGV 数组（复杂对象需预序列化为 String）
     * @return 脚本返回值
     */
    public <T> T executeScript(String script, Class<T> resultType, List<String> keys, Object... args) {
        DefaultRedisScript<T> redisScript = new DefaultRedisScript<>(script, resultType);
        return stringRedisTemplate.execute(redisScript, keys, args);
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
     * @param key     cache key
     * @param value   缓存值（任意对象，内部通过 Jackson 序列化为 JSON String 后传入 Lua）
     * @param timeout 过期时间数值
     * @param unit    时间单位
     * @return true 设置成功，false key 已存在或序列化失败
     */
    public boolean setIfAbsent(String key, Object value, long timeout, TimeUnit unit) {
        try {
            // 预序列化 value 为 JSON 字符串，因为 StringRedisTemplate 不会自动做 Jackson 转换
            String valueJson = objectMapper.writeValueAsString(value);
            // executeScript 使用 StringRedisTemplate，args 以原始字符串形式传入 Lua
            // Lua 中 tonumber(ARGV[2]) 可正常解析（无 Jackson 引号包裹问题）
            String result = executeScript(SET_IF_ABSENT_LUA, String.class,
                    Collections.singletonList(key), valueJson, String.valueOf(unit.toSeconds(timeout)));
            return "OK".equals(result);
        } catch (Exception e) {
            log.warn("SET NX EX 失败, key={}", key, e);
            return false;
        }
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

    // ==================== Set 操作（补偿任务使用） ====================

    /**
     * Set 添加成员
     */
    public void sAdd(String key, String... values) {
        redisTemplate.opsForSet().add(key, (Object[]) values);
    }

    /**
     * Set 获取所有成员
     */
    @SuppressWarnings("unchecked")
    public <T> Set<T> sMembers(String key) {
        return (Set<T>) redisTemplate.opsForSet().members(key);
    }
}
