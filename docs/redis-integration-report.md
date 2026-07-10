# hmall Redis 集成修改说明文档

> 更新日期：2026-07-10  
> 参考文档：`docs/redis-application-analysis.md`

---

## —、Redis+MySQL 双写架构总览

本次改造（第二阶段）实现了购物车和商品缓存的 Redis+MySQL 双写架构，核心策略如下：

```
购物车写入：Redis Lua 同步 → MQ 异步落 MySQL → 5min 版本补偿
购物车删除：Redis Lua 同步 + MySQL 同步 DELETE（双删同步，不走 MQ）
购物车读取：Redis → miss → MySQL → lazy sync 回填

商品缓存：MySQL 同步写 → Redis 同步删缓存 → MQ 二次确认删除 → 5min 补偿
商品读取：Cache-Aside（Redis → miss → MySQL → SET NX EX 回填）
```

### 1.1 hm-common（公共模块）

| 文件 | 操作 | 说明 |
|------|------|------|
| `pom.xml` | 修改 | 新增 `spring-boot-starter-data-redis` 依赖 |
| `META-INF/spring.factories` | 修改 | 注册 `RedisConfig` 和 `RedisCacheAspect` |
| `config/RedisConfig.java` | 新增 | RedisTemplate Bean 配置（Jackson2Json 序列化 + `@ConditionalOnProperty` 按需加载 + `@Import` RedisService/RedisLockUtil） |
| `service/RedisService.java` | 新增 | Redis 操作封装 + Lua 脚本执行（String/Hash/Set/Lua/验证码/Token 黑名单/秒杀预留） |
| `utils/RedisLockUtil.java` | 新增 | 分布式锁工具（SET NX EX + Lua 原子释放） |
| `utils/LuaScriptLoader.java` | 新增 | Lua 脚本加载工具（从 classpath 读取 .lua 文件） |
| `aspect/RedisCacheAspect.java` | 新增 | Redis 异常隔离切面（拦截异常 → 返回 null 降级） |
| `resources/lua/set_if_absent.lua` | 新增 | SET NX EX 原子缓存写入 |
| `resources/lua/hdel_atomic.lua` | 新增 | 批量 HDEL 原子删除 |
| `resources/lua/release_lock.lua` | 新增 | 分布式锁 Lua 原子释放 |

### 1.2 cart-service（购物车服务）— Redis+MySQL 双写架构

| 文件 | 操作 | 说明 |
|------|------|------|
| `application.yaml` | 修改 | 新增 `spring.redis` 连接配置（环境变量驱动） |
| `application-local.yaml` | 修改 | 本地环境 Redis 指向 `192.168.100.128` |
| `CartApplication.java` | 修改 | 新增 `@EnableScheduling` |
| `domain/po/Cart.java` | 修改 | 新增 `version BIGINT` 字段 |
| `service/impl/CartServiceImpl.java` | **重写** | Redis 权威 + MQ 异步 + 5min 版本补偿三路策略 |
| `domain/dto/CartSyncMessage.java` | 新增 | 购物车 MQ 同步消息 DTO |
| `mq/CartSyncSender.java` | 新增 | MQ 生产者（异步通知 MySQL 落库） |
| `mq/CartSyncReceiver.java` | 新增 | MQ 消费者（内联 @QueueBinding，消费后 MySQL UPSERT） |
| `task/CartSyncCompensationTask.java` | 新增 | @Scheduled 5min 版本比对补偿同步 |
| `resources/lua/add_cart.lua` | 修改 | KEYS[3] 全局版本 key + `or 0` 防御 + ARGV[5] 版本参数 |
| `resources/lua/remove_cart.lua` | 新增 | 原子删除双 Hash |
| `resources/db/migration/V1__add_cart_version.sql` | 新增 | ALTER TABLE cart ADD COLUMN version |

### 1.3 item-service（商品服务）— MQ 异步刷新 + 补偿

| 文件 | 操作 | 说明 |
|------|------|------|
| `application.yaml` | 修改 | 新增 `spring.redis` 连接配置 |
| `application-local.yaml` | 修改 | 本地环境 Redis 指向 `192.168.100.128` |
| `ItemApplication.java` | 修改 | 新增 `@EnableScheduling` |
| `service/impl/ItemServiceImpl.java` | 修改 | `queryItemByIds` 新增 Redis 缓存层（Redis → miss → MySQL → SET NX EX 回写） |
| `controller/ItemController.java` | 修改 | 写操作后：deleteItemCache + MQ 二次确认 + dirty Set 标记 |
| `domain/dto/ItemCacheMessage.java` | 新增 | 缓存失效 MQ 消息 DTO |
| `mq/ItemCacheSender.java` | 新增 | MQ 生产者（发送缓存失效消息） |
| `mq/ItemCacheReceiver.java` | 新增 | MQ 消费者（二次确认 deleteItemCache） |
| `task/ItemCacheCompensationTask.java` | 新增 | @Scheduled 5min dirty Set 遍历补偿 |

### 1.4 user-service（用户服务）

| 文件 | 操作 | 说明 |
|------|------|------|
| `application.yaml` | 修改 | 新增 `spring.redis` 连接配置 |
| `application-local.yaml` | 修改 | 本地环境 Redis 指向 `192.168.100.128` |
| `service/impl/UserServiceImpl.java` | 修改 | `deductMoney` 增加分布式锁（`lock:deduct:{userId}`），防止并发超扣 |

### 1.5 其他服务（配置层）

| 服务 | 操作 | 说明 |
|------|------|------|
| `hm-service` | 修改 `application.yaml` + `application-local.yaml` | 已有 Redis 依赖，仅补连接配置 |
| `trade-service` | 修改 `application.yaml` + `application-local.yaml` | 预留 Redis 配置（后续缓存订单信息） |
| `pay-service` | 修改 `application.yaml` + `application-local.yaml` | 预留 Redis 配置（后续幂等性缓存） |
| `search-service` | 无变更 | 直接查 ES，暂不引入 Redis |

---

## 二、架构说明

### 2.1 新增基础设施

```
hm-common/
├── config/RedisConfig          → RedisTemplate Bean（JSON 序列化 + @ConditionalOnProperty）
├── service/RedisService        → 通用 Redis 操作封装（含 Lua/Set 执行）
├── utils/RedisLockUtil         → 分布式锁（SET NX EX + Lua 释放）
├── utils/LuaScriptLoader       → .lua 文件加载工具
├── aspect/RedisCacheAspect     → 异常隔离切面（降级回 null）
└── resources/lua/
    ├── set_if_absent.lua       → SET NX EX 原子写入
    ├── hdel_atomic.lua         → 批量 HDEL
    └── release_lock.lua        → 锁释放

cart-service/
├── domain/dto/CartSyncMessage  → MQ 消息 DTO
├── mq/CartSyncSender           → MQ 生产者
├── mq/CartSyncReceiver         → MQ 消费者
├── task/CartSyncCompensation   → 5min 补偿定时任务
└── resources/
    ├── lua/add_cart.lua        → 原子加购（含版本号）
    ├── lua/remove_cart.lua     → 原子删除
    └── db/migration/V1__...    → 表迁移

item-service/
├── domain/dto/ItemCacheMessage → MQ 消息 DTO
├── mq/ItemCacheSender          → MQ 生产者
├── mq/ItemCacheReceiver        → MQ 消费者
└── task/ItemCacheCompensation  → 5min 补偿定时任务
```

### 2.3 MQ 拓扑（新增 Exchange/Queue）

```
Exchange: "cart.sync.topic" (topic)
  └── Queue: "cart.sync.queue"          routingKey: "cart.sync"
       └── 消费者: CartSyncReceiver（cart-service）

Exchange: "item.cache.topic" (topic)
  └── Queue: "item.cache.invalidate.queue"  routingKey: "item.cache.invalidate"
       └── 消费者: ItemCacheReceiver（item-service）
```

### 2.2 Key 设计规范总览

| 场景 | Key 模板 | 类型 | TTL | 关键命令 |
|------|----------|------|-----|----------|
| 购物车-商品 | `cart:user:{userId}` | Hash | 30 天 | Lua 脚本 (HEXISTS/HLEN/HSET) |
| 购物车-数量 | `cart:user:{userId}:num` | Hash | 30 天 | HINCRBY（原子递增） |
| 购物车-版本 | `cart:user:{userId}:v` | String | 30 天 | SET（Lua 内原子更新，补偿比对用） |
| 商品信息 | `item:info:{id}` | Value (JSON) | 30 分钟 | SET NX EX / GET |
| 商品脏数据 | `item:cache:dirty` | Set | 15 分钟 | SADD / SMEMBERS（补偿任务遍历） |
| 分布式锁 | `lock:deduct:{userId}` | String | 5 秒 | SET NX EX + Lua 释放 |
| 验证码 | `sms:code:{phone}` | String | 5 分钟 | SET EX（预留） |
| Token 黑名单 | `token:blacklist:{jti}` | String | 动态 | SET EX（预留） |

---

## 三、改造详情

### 3.1 购物车 — Redis+MySQL 双写架构（Redis 权威 + MQ 异步 + 版本补偿）

#### 改造前

```
用户加购 → INSERT INTO cart (...)
查询购物车 → SELECT * FROM cart WHERE user_id = ? → Feign 调 item-service 补商品信息
下单清购物车 → DELETE FROM cart WHERE user_id = ? AND item_id IN (...)
```

#### 改造后 — 三路策略

```
写入：Redis Lua 同步（用户本端立即可见）
     → MQ 异步 → CartSyncReceiver → MySQL UPSERT（几秒内完成）
     → MQ 失败 → CartSyncCompensationTask @5min 版本比对补偿

删除：Redis Lua 同步 + MySQL 同步 DELETE（双删同步，不走 MQ，防止补偿回填）

读取：Redis → miss → MySQL → lazy sync 回填 Redis
```

**Redis Key 结构（含版本号）：**
```
cart:user:{userId}       → Hash  { itemId → '{"itemId":1,"name":"iPhone","ver":1700123456}' }
cart:user:{userId}:num   → Hash  { itemId → "3" }
cart:user:{userId}:v     → String "1700123456"  ← 全局版本号，Lua 内原子 SET
```

#### 写入流程 addItem2Cart

1. `ensureCartRedisSynced(userId)` — 冷启动兜底：HLEN 检查 Redis → 空则从 MySQL 全量回填
2. `add_cart.lua` — Lua 原子执行：HEXISTS/HLEN → HINCRBY/HSET → SET version → EXPIRE
3. Lua 返回实际数量 → 构建 `CartSyncMessage(actualNum, version)` → `cartSyncSender.sendSync()`
4. MQ 消费者 `CartSyncReceiver` → MySQL UPSERT（INSERT or UPDATE）

#### 删除流程 removeByItemIds

1. `remove_cart.lua` — 原子 HDEL 两个 Hash
2. `redisService.set(versionKey, newVersion)` — 更新版本号
3. `removeByItemIdsMysql()` — MySQL 同步 DELETE（同一事务，不走 MQ）

#### 补偿任务 CartSyncCompensationTask

@Scheduled 每 5 分钟：

1. `SELECT DISTINCT user_id FROM cart` 获取活跃用户
2. 对每个 user：对比 `GET cart:user:{userId}:v` vs `SELECT MAX(version) FROM cart WHERE user_id=?`
3. Redis 版本 > MySQL 版本 → 全量 Redis → MySQL 覆盖
4. MySQL 有数据 Redis 空 → 全量 MySQL → Redis 回填
5. 版本一致 → 跳过

#### 冷启动 Lazy Sync

`queryMyCarts`: Redis 空 → `queryMyCartsMysql()` → 若有数据 → `syncCartsToRedis()` HSET 回填 + 设 TTL

#### Lua 脚本增强

**add_cart.lua 新增参数：**
```
KEYS[3] = cart:user:{userId}:v   ← 全局版本 key
ARGV[5] = version                ← 时间戳
```
脚本末尾追加 `redis.call('SET', KEYS[3], ARGV[5])` + `EXPIRE`，保证版本号与数据在同一原子事务内更新。

---

### 3.2 商品信息缓存

#### 改造前

每次 Feign 调用 `itemClient.queryItemsByIds(ids)` → `ItemServiceImpl.queryItemByIds()` → `listByIds(ids)` 直查 MySQL。

高频调用场景：购物车补商品信息、订单确认页显示商品。

#### 改造后

```java
public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
    for (Long id : ids) {
        ItemDTO cached = redisService.get("item:info:" + id, ItemDTO.class);
        if (cached != null) result.add(cached);    // 命中缓存
        else missedIds.add(id);                     // 未命中
    }
    // 批量查 MySQL 未命中的
    List<ItemDTO> dbDTOs = listByIds(missedIds);
    // 回写 Redis — 使用 SET NX EX 避免覆盖已被刷新的缓存
    for (ItemDTO dto : dbDTOs) {
        redisService.setIfAbsent("item:info:" + dto.getId(), dto, 30, MINUTES);
    }
}
```

#### 缓存失效机制（三层保障）

| 操作 | 第一层（同步） | 第二层（MQ 异步） | 第三层（补偿） |
|------|---------------|-------------------|---------------|
| 更新商品 | `redisService.delete("item:info:{id}")` | `itemCacheSender.sendInvalidate(id)` → ItemCacheReceiver 二次确认删除 | ItemCacheCompensationTask @5min |
| 更新状态 | 同上 | 同上 | 同上 |
| 删除商品 | 同上 | 同上 | 同上 |

**ItemCacheCompensationTask（@Scheduled 5min）：**
1. 写操作时 `redisService.sAdd("item:cache:dirty", itemId)` 标记脏数据
2. 补偿任务：`sMembers("item:cache:dirty")` 遍历 → `hasKey("item:info:{id}")` → 若未失效则补充 `delete()`
3. 清理 dirty Set

---

### 3.3 扣款分布式锁

#### 改造前

```java
baseMapper.updateMoney(userId, totalFee); // 无并发保护，可能超扣
```

#### 改造后

```java
String lockKey = "lock:deduct:" + userId;
String lockValue = UUID.randomUUID().toString();

if (!redisLockUtil.tryLock(lockKey, lockValue, 5)) {
    throw new BizIllegalException("系统繁忙，请稍后重试");
}
try {
    baseMapper.updateMoney(userId, totalFee);
} finally {
    redisLockUtil.releaseLock(lockKey, lockValue);
}
```

**锁机制**：
- 加锁：`SET lock:deduct:{userId} {uuid} NX EX 5` — 原子操作，5 秒自动过期
- 释放：Lua 脚本 `if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end` — 只有持有者能释放

---

### 3.4 Redis 异常隔离切面

```java
@Aspect
@Order(2)
public class RedisCacheAspect {
    @Around("execution(* com.hmall.common.service.RedisService.*(..))")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (Exception e) {
            log.error("Redis 操作异常，降级处理 - method: {}", joinPoint.getSignature().toShortString(), e);
            return null; // 返回 null → 上层业务回退 MySQL
        }
    }
}
```

**降级行为（各服务）**：

| 服务 | Redis 返回 null 时的行为 |
|------|--------------------------|
| cart-service | `queryMyCarts` → 走 `queryMyCartsMysql` 查 MySQL |
| cart-service | `addItem2Cart` → 走 `addItem2CartMysql` 查 MySQL |
| item-service | `queryItemByIds` → 缓存未命中 → 全量查 MySQL |

---

## 四、配置说明

### 4.1 通用配置（application.yaml）

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

### 4.2 本地开发配置（application-local.yaml）

```yaml
spring:
  redis:
    host: 192.168.100.128
```

### 4.3 环境变量说明

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `REDIS_HOST` | `localhost` | Redis 服务器地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | 空 | Redis 密码（无密码不填） |

### 4.4 连接池参数

| 参数 | 值 | 说明 |
|------|-----|------|
| `max-active` | 8 | 最大活跃连接数 |
| `max-idle` | 8 | 最大空闲连接数 |
| `min-idle` | 0 | 最小空闲连接数 |
| `timeout` | 5000ms | 连接超时时间 |

---

## 五、回滚方案

### 5.1 购物车切回 MySQL

购物车迁移采用**渐进式策略**，原 `cart` 表和 `CartMapper` 完整保留：

1. **Redis 故障**：`RedisCacheAspect` 自动降级，`CartServiceImpl` 检测到 null 后走 `*Mysql` 方法
2. **手动回滚**：删除 `CartServiceImpl` 中的 Redis 路径，保留 `*Mysql` 方法名改回原名
3. **数据恢复**：恢复时 MySQL 表中仍有完整数据（`removeByItemIds` 同时删 Redis + MySQL）

### 5.2 Redis 宕机时的降级行为

| 功能 | 降级行为 | 影响 |
|------|----------|------|
| 购物车查询 | 查 MySQL + Feign | 延迟增加，功能正常 |
| 购物车加购 | 写 MySQL | 功能正常 |
| 商品查询 | 查 MySQL | 延迟增加，功能正常 |
| 扣款分布式锁 | `tryLock` 抛异常 → 降级无锁执行 | 失去并发保护，有超扣风险 |
| 商品更新 | 缓存删除失败 → 用户看到旧数据 | 最长 30 分钟后自动过期 |

---

## 六、运维指引

### 6.1 Redis 部署要求

- **版本**：Redis 6.x+
- **地址**：`192.168.100.128:6379`（与 MySQL 同机部署）
- **持久化**：建议开启 AOF（`appendonly yes`），防止重启丢数据
- **内存**：建议分配 512MB+ 用于缓存数据

### 6.2 Key 清理策略

| Key 前缀 | 清理方式 |
|----------|----------|
| `cart:user:*:num` | TTL 30 天自动过期 |
| `cart:user:*:v` | TTL 30 天自动过期 |
| `cart:user:*` | TTL 30 天自动过期 |
| `item:info:*` | TTL 30 分钟自动过期 + 写操作主动删除 + MQ 二次确认 + 补偿任务 |
| `item:cache:dirty` | TTL 15 分钟自动过期 + 补偿任务主动清理 |
| `lock:deduct:*` | TTL 5 秒自动过期 + finally 主动释放 |
| `sms:code:*` | TTL 5 分钟自动过期（预留） |
| `token:blacklist:*` | TTL = token 剩余有效期（预留） |

### 6.3 监控建议

1. **Redis 可用性**：PING 心跳监控，延迟 > 10ms 告警
2. **内存使用率**：> 80% 告警，考虑增加实例或调整 TTL
3. **连接数**：实际连接数应 < `max-active` * 服务数（6 × 8 = 48）
4. **缓存命中率**：`item:info:*` 命中率应 > 80%
5. **日志关键字**：关注 `"降级处理"`（Redis 异常）和 `"降级到 MySQL"`（业务回退）

### 6.4 启动检查清单

- [ ] Redis 服务已启动（`redis-cli PING` → `PONG`）
- [ ] 各微服务 `application-local.yaml` 中 `spring.redis.host` 正确配置
- [ ] 启动无报错：控制台无 `RedisConnectionFailureException`
- [ ] 购物车功能正常：加购 → 查购物车 → 删商品
- [ ] 商品查询正常：首页商品列表、商品详情
- [ ] 下单扣款正常：创建订单 → 支付 → 扣款成功

---

## 七、后续优化建议

1. **商品批量查询 Pipeline 优化**：`queryItemByIds` 中逐个 `get` 可改为 Redis Pipeline 批量操作，减少网络往返
2. **查询购物车的一致性快照**：`queryMyCarts` 分两次 `HGETALL`（cart + num），极端情况下可能读到不一致的快照。可合并为一次 Lua 脚本，在 Redis 内完成合并返回
3. **秒杀场景**：`RedisService` 已预留 `incrBy/decrBy` 和 Lua 脚本执行能力，可直接实现秒杀 Lua 脚本 + MySQL 行锁兜底
4. **MQ 消息积压监控**：补偿任务是最终兜底，需监控 `cart.sync.queue` 和 `item.cache.invalidate.queue` 的消息积压量
5. **监控面板**：接入 Redis 监控大盘（如 Grafana + Redis Exporter）

> **已完成**：购物车 Redis+MySQL 双写架构（Lua 原子操作 + MQ 异步 + 5min 版本补偿）、购物车删除双删同步、商品缓存三层失效保障（同步删除 + MQ 二次确认 + 补偿任务）、扣款分布式锁的 Lua 释放、SET NX EX 原子缓存回写。
