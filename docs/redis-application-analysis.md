# Redis 在 hmall 中的应用分析

> 分析日期：2026-07-10  
> 参考项目：nova-mall（Spring Boot 3.5 + Spring Data Redis + Lettuce）

---

## 一、现状

| 维度 | 结果 |
|------|------|
| Redis 依赖 | 仅 `hm-service` 引入了 `spring-boot-starter-data-redis` |
| Redis 配置 | **无** — 没有任何 `spring.redis.host` 配置 |
| Redis 使用代码 | **无** — 全项目零 `RedisTemplate` 引用 |
| 数据结构 | **无** — 购物车、缓存、锁全部走 MySQL |

---

## 二、nova-mall 的 Redis 使用全景

nova-mall 通过 `BaseRedisConfig` + `RedisService` + `RedisCacheAspect` 三层架构，覆盖了以下场景：

| 场景 | 核心机制 | 数据结构 | Key 示例 |
|------|---------|---------|---------|
| **秒杀库存** | Lua 原子预减 + MySQL 行锁兜底 | String | `seckill:stock:{relationId}` |
| **优惠券库存** | Lua 原子预减 | String | `coupon:stock:{couponId}` |
| **分布式锁** | `SET NX EX` + Lua 原子释放 | String | 业务自定义 key |
| **首页缓存** | `@Cacheable` 注解 + 1 天 TTL | 方法缓存 | `home:content` |
| **商品详情** | CacheService 手动缓存 | Value JSON | `pms:product:{id}` |
| **验证码** | `@CacheException` 关键词隔离 | String + TTL | `sms:code:{phone}` |
| **用户会话** | CacheService | Value JSON | `ums:member:{id}` |
| **浏览历史** | 降级到 MongoDB | — | — |
| **Redis 异常隔离** | `RedisCacheAspect` 切面自动降级 | — | — |

---

## 三、hmall 可落地的 Redis 场景（按优先级排序）

### P0 — 高价值，低成本，立即可做

#### 3.1 购物车从 MySQL 迁 Redis

**hmall 现状**：购物车全量存在 `cart` 表中，每次查购物车都走 MySQL。
```
cart-service 每次查询: SELECT * FROM cart WHERE user_id = ? 
                  → 再 Feign 调 item-service 补商品信息
```

**nova-mall 参考**：用 `RedisService.hSet/hGetAll` 存储。

**改造方案（Redis+MySQL 双写架构）：**

```
# 数据结构：商品元数据与数量拆分存储 + 全局版本号
cart:user:{userId}       → Hash  { itemId → '{"itemId":1,"name":"iPhone","ver":1700123456}' }
cart:user:{userId}:num   → Hash  { itemId → "3" }
cart:user:{userId}:v     → String "1700123456"  ← 全局版本号，补偿任务比对用

Key:   cart:user:{userId}  +  cart:user:{userId}:num  +  cart:user:{userId}:v
Type:  Hash + Hash + String
Field: itemId
Value: JSON 商品数据（含 ver） / 数量数字 / 版本时间戳
TTL:   30 天
```

**三路策略：**

```
写入：Redis Lua 同步（含 version）→ MQ 异步 → CartSyncReceiver → MySQL UPSERT
      └── MQ 失败 → CartSyncCompensationTask @5min 版本比对补偿

删除：Redis Lua 双 Hash HDEL + SET version → MySQL 同步 DELETE（双删同步）
      └── 不走 MQ，防止补偿任务将 MySQL 残留回填 Redis

读取：Redis HGETALL → miss → MySQL 全量加载 → HSET 回填 Redis（lazy sync）
```

**MQ 拓扑：**
```
Exchange: "cart.sync.topic" (topic)
  └── Queue: "cart.sync.queue"  routingKey: "cart.sync"
       └── CartSyncReceiver → MySQL UPSERT
```

**补偿任务（@Scheduled 5min）：**
1. `SELECT DISTINCT user_id FROM cart` 获取活跃用户
2. 对每个 user：对比 Redis `cart:user:{userId}:v` vs MySQL `MAX(version)`
3. Redis 版本 > MySQL → 全量 Redis → MySQL 覆盖（先 DELETE 旧行再 INSERT）
4. MySQL 有数据 Redis 空 → 全量 MySQL → Redis HSET 回填
5. 版本一致 → 跳过

**收益**：
- 购物车查询从 MySQL + Feign 两次网络调用 → Redis 单次操作
- Redis 权威保证用户本端立即可见
- MQ 异步落 MySQL，几秒内完成持久化
- **Lua 脚本保证并发安全** + 5min 补偿任务兜底 MQ 失败
- **删除双删同步**防止补偿任务回填残留数据

---

#### 3.2 分布式锁 — 保护扣款/扣库存

**hmall 现状**：

```java
// user-service: 扣款无并发保护
baseMapper.updateMoney(userId, totalFee);  // 可能超扣

// item-service: 扣库存无乐观锁
@Update("update item set stock = stock - #{num} where id = #{itemId}")
void updateStock(itemId, num);  // where 条件无 version/stock 检查
```

**nova-mall 参考**：`RedisServiceImpl.tryLock/releaseLock`（`SET NX EX` + Lua 原子释放）。

**改造方案**（复制 nova-mall 的锁实现到 hm-common）：

```java
// hm-common 新增 RedisLockUtil
public boolean tryLock(String key, String value, long expireSeconds) {
    return redisTemplate.opsForValue()
        .setIfAbsent(key, value, Duration.ofSeconds(expireSeconds));
}

public void releaseLock(String key, String value) {
    String lua = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                 "return redis.call('del', KEYS[1]) else return 0 end";
    redisTemplate.execute(
        new DefaultRedisScript<>(lua, Long.class),
        Collections.singletonList(key), value
    );
}
```

**应用在扣款**：
```java
String lockKey = "lock:deduct:" + userId;
String lockValue = UUID.randomUUID().toString();
if (!redisLock.tryLock(lockKey, lockValue, 5)) {
    throw new BizIllegalException("系统繁忙，请稍后重试");
}
try {
    baseMapper.updateMoney(userId, totalFee);
} finally {
    redisLock.releaseLock(lockKey, lockValue);
}
```

**收益**：防止同一用户并发扣款导致余额超扣。

---

#### 3.3 商品信息缓存

**hmall 现状**：每次 Feign 调用 `itemClient.queryItemsByIds(ids)` 都走 MySQL，在高频访问场景（首页、购物车补商品信息、订单确页）重复查库。

**nova-mall 参考**：`ProductCacheService`（`@Cacheable` + CacheService 手动管理）。

**改造方案**：

```java
// item-service 新增缓存层
public ItemDTO getItemById(Long id) {
    // 1. 查 Redis
    String key = "item:info:" + id;
    ItemDTO cached = (ItemDTO) redisService.get(key);
    if (cached != null) return cached;
    // 2. 查 DB
    Item item = getById(id);
    ItemDTO dto = BeanUtils.copyBean(item, ItemDTO.class);
    // 3. 写 Redis（SET NX EX，避免覆盖已被刷新的缓存）
    redisService.setIfAbsent(key, dto, 30, TimeUnit.MINUTES);
    return dto;
}
```

**缓存失效策略（三层保障）：**

| 层级 | 机制 | 说明 |
|------|------|------|
| 第一层 | `redisService.delete("item:info:{id}")` | 同步删除，写操作立即执行 |
| 第二层 | `itemCacheSender.sendInvalidate(id)` → ItemCacheReceiver | MQ 异步二次确认删除 |
| 第三层 | ItemCacheCompensationTask @5min | dirty Set 遍历 + hasKey 检查 + 补充删除 |

**MQ 拓扑：**
```
Exchange: "item.cache.topic" (topic)
  └── Queue: "item.cache.invalidate.queue"  routingKey: "item.cache.invalidate"
       └── ItemCacheReceiver → 二次 deleteItemCache
```

**补偿任务：**
- 写操作时 `sAdd("item:cache:dirty", itemId)` + 15min TTL
- @Scheduled 5min → `sMembers` 遍历 → 确认 cache 已失效 → 清理 Set

**收益**：热门商品查询从 MySQL → Redis，QPS 提升 10-100 倍；三层失效保障缓存一致性。

---

### P1 — 中等价值，依赖已有基础设施

#### 3.4 Redis 异常隔离切面（参考 nova-mall `RedisCacheAspect`）

**场景**：Redis 宕机时，所有依赖 Redis 的业务不应集体崩溃。

**实现**：在 hm-common 新增切面，拦截 `RedisService` 所有方法：

```java
@Aspect
@Order(2)
public class RedisCacheAspect {
    @Around("execution(* com.hmall.common.service.RedisService.*(..))")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (Exception e) {
            log.error("Redis 操作异常，降级处理", e);
            return null; // 返回 null，上层逻辑走 DB
        }
    }
}
```

**区分关键业务**：通过 `@CacheException` 注解标记不可降级的方法（如分布式锁、秒杀库存），异常时仍然抛出。

---

#### 3.5 验证码存储

**场景**：如果后续增加用户注册、手机验证码登录、支付密码验证。

**nova-mall 做法**：
```
Key:  sms:code:{phone}
Type: String
TTL:  5 分钟
```
- 发送验证码：`redisService.set("sms:code:" + phone, code, 5, TimeUnit.MINUTES)`
- 校验验证码：`redisService.get("sms:code:" + phone)`

**为什么用 Redis**：验证码需要自动过期（TTL），MySQL 需要额外定时任务清理。

---

#### 3.6 Token 黑名单（登出失效）

**hmall 现状**：登出只删除前端 `sessionStorage`，JWT 在有效期内仍可使用（无状态 token 的固有问题）。

**改造方案**：
```java
// 登出时
String tokenId = extractJti(token);
redisService.set("token:blacklist:" + tokenId, "1", tokenTTL, TimeUnit.SECONDS);

// Gateway 校验时
if (redisService.hasKey("token:blacklist:" + tokenId)) {
    throw new UnauthorizedException("token 已失效");
}
```

---

### P2 — 未来扩展

#### 3.7 秒杀库存 Lua 原子预减

依赖先实现秒杀功能（参考 nova-mall 的三层防超卖架构），核心 Lua 脚本可直接复用：

```lua
local stock = tonumber(redis.call('get', KEYS[1]))
if stock == nil then return -1 end
if stock <= 0 then return 0 end
redis.call('decrby', KEYS[1], ARGV[1])
return 1
```

#### 3.8 接口限流

在 gateway 层用 Redis 实现滑动窗口限流，保护后端服务。

---

## 四、实施路线图

```
第一步（本周）：Redis 基础设施搭建
├── hm-common 引入 spring-boot-starter-data-redis
├── 各微服务配置 spring.redis.host
├── 新增 RedisConfig（序列化 Jackson2Json + RedisTemplate）
└── 新增 RedisService 工具类（借鉴 nova-mall，先只封装常用操作）

第二步（本周）：购物车迁 Redis
├── CartServiceImpl 改为 Redis Hash 存取
├── 清理原 MySQL cart 表相关代码（保留 Mapper 不动，平滑过渡）
└── 验证：加购 → 查购物车 → 下单清空

第三步（下周）：分布式锁 + 商品缓存
├── hm-common 新增 RedisLockUtil
├── 扣款/扣库存加分布式锁保护
├── item-service 新增商品缓存层
└── 商品更新时主动失效缓存

第四步（按需）：验证码 / Token 黑名单 / 异常隔离切面
```

---

## 五、Redis 基础设施代码骨架

### 5.1 pom.xml（hm-common）

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<!-- Redis 连接池（Lettuce 默认需要） -->
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
```

> hm-common 已有 `commons-pool2` 依赖。

### 5.2 RedisConfig（hm-common）

```java
@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        // Key: String
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Value: JSON（不携带类型信息，减少存储空间）
        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(Object.class);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        return template;
    }
}
```

### 5.3 application.yml（各微服务）

```yaml
spring:
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
    database: 0
    timeout: 5000ms
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0
```

### 5.4 spring.factories 注册

```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
  ...,
  com.hmall.common.config.RedisConfig
```

---

## 六、注意事项

1. **购物车迁 Redis 后**：原 MySQL `cart` 表和 `CartMapper` 不要立即删除，保留作为回滚方案。`clearCartListener` 的 MQ 消息也需改为调 Redis 接口。

2. **购物车并发安全**：`num` 字段必须拆分到独立 Hash 并通过 `HINCRBY` 原子递增，不可在应用层做 read-then-write。加购/删除整体流程需用 **Lua 脚本包裹**，确保 HLEN 检查 → HSET/HINCRBY → EXPIRE 在同一原子事务内完成。

3. **缓存穿透**：对于不存在的数据，可缓存空值 1 分钟（如 `item:info:99999 → "NULL"`）。

4. **缓存一致性**：商品信息写操作后删除缓存（Cache-Aside），回写时使用 `SET NX EX` 避免覆盖已被刷新的缓存。极端情况下仍存在短时间不一致，30 分钟 TTL 自动修复。

5. **Redis 配置不要写死 IP**：用环境变量 `${REDIS_HOST}`，本地开发默认 `localhost`。

6. **gateway 不需要 Redis**：Gateway 只做认证路由，不需要引入 Redis 依赖。

7. **序列化选型**：建议用 `Jackson2JsonRedisSerializer(Object.class)` 而非带 `DefaultTyping` 的版本（安全性更好，存储更小）。如果需要多态，单独创建带类型的 Serializer。

8. **Lua 脚本可用性**：当前实现使用原生 Redis 命令（HINCRBY、HLEN、HEXISTS），不依赖 `cjson` 等扩展库，兼容所有 Redis 版本。

9. **降级行为验证**：Redis 宕机时购物车回退 MySQL、商品查询回退直查 DB、分布式锁不可用时扣款直接拒绝（fail-fast），三者均需定期演练。

10. **MQ 消息可靠性**：购物车 MQ 发送失败时 catch 异常不阻断主流程，依赖补偿任务（5min 版本比对）做最终一致性。需监控 `cart.sync.queue` 消息积压，防止大量积压导致 MySQL 批量插入延迟。

11. **version 字段一致性**：version 在 Lua 脚本内通过 `SET KEYS[3] ARGV[5]` 原子更新，与 HSET/HINCRBY/EXPIRE 在同一事务内。补偿任务只做最终兜底，不是主路径。

12. **冷启动同步幂等性**：`ensureCartRedisSynced` 使用 HSET 回填，重复调用无副作用。并发冷启动时多个请求可能同时触发全量 MySQL 加载，但 Redis HSET 覆盖写入是幂等的。

13. **Cart 表 version 索引**：`ALTER TABLE cart ADD INDEX idx_cart_user_version(user_id, version)` 加速补偿任务的 `SELECT MAX(version) GROUP BY user_id` 查询。
