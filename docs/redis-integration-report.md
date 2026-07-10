# hmall Redis 集成修改说明文档

> 生成日期：2026-07-10  
> 参考文档：`docs/redis-application-analysis.md`

---

## 一、修改清单

### 1.1 hm-common（公共模块）

| 文件 | 操作 | 说明 |
|------|------|------|
| `pom.xml` | 修改 | 新增 `spring-boot-starter-data-redis` 依赖 |
| `META-INF/spring.factories` | 修改 | 注册 `RedisConfig` 和 `RedisCacheAspect` |
| `config/RedisConfig.java` | 新增 | RedisTemplate Bean 配置（Jackson2Json 序列化 + Lettuce 连接池） |
| `service/RedisService.java` | 新增 | Redis 操作封装 + Lua 脚本执行（String/Hash/Lua/验证码/Token 黑名单/秒杀预留） |
| `utils/RedisLockUtil.java` | 新增 | 分布式锁工具（SET NX EX + Lua 原子释放） |
| `utils/LuaScriptLoader.java` | 新增 | Lua 脚本加载工具（从 classpath 读取 .lua 文件） |
| `aspect/RedisCacheAspect.java` | 新增 | Redis 异常隔离切面（拦截异常 → 返回 null 降级） |
| `resources/lua/set_if_absent.lua` | 新增 | SET NX EX 原子缓存写入 |
| `resources/lua/hdel_atomic.lua` | 新增 | 批量 HDEL 原子删除 |
| `resources/lua/release_lock.lua` | 新增 | 分布式锁 Lua 原子释放 |

### 1.2 cart-service（购物车服务）

| 文件 | 操作 | 说明 |
|------|------|------|
| `application.yaml` | 修改 | 新增 `spring.redis` 连接配置（环境变量驱动） |
| `application-local.yaml` | 修改 | 本地环境 Redis 指向 `192.168.100.128` |
| `service/impl/CartServiceImpl.java` | 修改 | 读写路径切换至 Redis Hash，使用 Lua 脚本保证原子性 |
| `resources/lua/add_cart.lua` | 新增 | 原子加购脚本（HEXISTS + HLEN + HINCRBY + HSET + EXPIRE） |
| `resources/lua/remove_cart.lua` | 新增 | 原子删除脚本（双 Hash HDEL） |

### 1.3 item-service（商品服务）

| 文件 | 操作 | 说明 |
|------|------|------|
| `application.yaml` | 修改 | 新增 `spring.redis` 连接配置 |
| `application-local.yaml` | 修改 | 本地环境 Redis 指向 `192.168.100.128` |
| `service/impl/ItemServiceImpl.java` | 修改 | `queryItemByIds` 新增 Redis 缓存层（先查 Redis → 未命中查 MySQL → SET NX EX 回写） |
| `controller/ItemController.java` | 修改 | 写操作后主动删除 `item:info:{id}` 缓存 |

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
├── config/RedisConfig          → RedisTemplate Bean（JSON 序列化）
├── service/RedisService        → 通用 Redis 操作封装（含 Lua 执行）
├── utils/RedisLockUtil         → 分布式锁（SET NX EX + Lua 释放）
├── utils/LuaScriptLoader       → .lua 文件加载工具
├── aspect/RedisCacheAspect     → 异常隔离切面（降级回 null）
└── resources/lua/
    ├── set_if_absent.lua       → SET NX EX 原子写入
    ├── hdel_atomic.lua         → 批量 HDEL
    └── release_lock.lua        → 锁释放
```

**依赖关系**：

```
cart-service  ─┐
item-service  ─┤
user-service  ─┼─ RedisService / RedisLockUtil
trade-service ─┤
pay-service   ─┤
hm-service    ─┘

RedisCacheAspect ──► RedisService（切面拦截异常）
```

### 2.2 Key 设计规范总览

| 场景 | Key 模板 | 类型 | TTL | 关键命令 |
|------|----------|------|-----|----------|
| 购物车-商品 | `cart:user:{userId}` | Hash | 30 天 | Lua 脚本 (HEXISTS/HLEN/HSET) |
| 购物车-数量 | `cart:user:{userId}:num` | Hash | 30 天 | HINCRBY（原子递增） |
| 商品信息 | `item:info:{id}` | Value (JSON) | 30 分钟 | SET NX EX / GET |
| 分布式锁 | `lock:deduct:{userId}` | String | 5 秒 | SET NX EX + Lua 释放 |
| 验证码 | `sms:code:{phone}` | String | 5 分钟 | SET EX（预留） |
| Token 黑名单 | `token:blacklist:{jti}` | String | 动态 | SET EX（预留） |

---

## 三、改造详情

### 3.1 购物车 — MySQL → Redis Hash（Lua 原子操作）

#### 改造前

```
用户加购 → INSERT INTO cart (...)
查询购物车 → SELECT * FROM cart WHERE user_id = ? → Feign 调 item-service 补商品信息
下单清购物车 → DELETE FROM cart WHERE user_id = ? AND item_id IN (...)
```

两次网络调用（MySQL + Feign），延迟约 50ms。

#### 改造后

```
# 数据结构：商品元数据与数量拆分存储，保证并发安全
cart:user:{userId}     → Hash  { itemId → '{name, price, image, ...}' }   # 商品元数据（不含 num）
cart:user:{userId}:num → Hash  { itemId → "3" }                            # 数量（HINCRBY 原子管理）

# 加购：Lua 脚本原子执行（检查 → HINCRBY / HSET → EXPIRE）
# 查询：分别 HGETALL 两个 Hash，Java 层合并 num
# 删除：Lua 脚本原子删除两个 Hash 的同名字段
```

单次 Lua 脚本执行，延迟约 2ms。

#### 原子性保障（Lua 脚本）

脚本存放在 `resources/lua/` 目录，由 `LuaScriptLoader.load()` 加载：

- `cart-service/src/main/resources/lua/add_cart.lua` — 原子加购
- `cart-service/src/main/resources/lua/remove_cart.lua` — 原子删除
- `hm-common/src/main/resources/lua/release_lock.lua` — 锁释放
- `hm-common/src/main/resources/lua/set_if_absent.lua` — 缓存回写
- `hm-common/src/main/resources/lua/hdel_atomic.lua` — 批量 HDEL

**add_cart.lua**（加购）：
```lua
-- KEYS[1] = cart:user:{userId}    KEYS[2] = cart:user:{userId}:num
-- ARGV[1] = itemId    ARGV[2] = itemDataJson    ARGV[3] = ttl    ARGV[4] = maxItems

local exists = redis.call('HEXISTS', KEYS[1], ARGV[1])
if exists == 1 then
    -- 已存在：HINCRBY 原子递增数量（无竞态）
    local newNum = redis.call('HINCRBY', KEYS[2], ARGV[1], 1)
    redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
    redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
    return newNum
else
    -- 新商品：检查上限 → 写入
    local size = redis.call('HLEN', KEYS[1])
    if size >= tonumber(ARGV[4]) then return -1 end
    redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
    redis.call('HSET', KEYS[2], ARGV[1], 1)
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
    redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
    return 1
end
```

**REMOVE_CART_LUA**（删除）：
```lua
-- 原子删除 item 数据和 num 两个 Hash 的同名 field
for i, field in ipairs(ARGV) do
    redis.call('HDEL', KEYS[1], field)
    redis.call('HDEL', KEYS[2], field)
end
return 1
```

#### 降级机制

当 Redis 不可用时（RedisService 抛异常），自动回退到原有 MySQL 操作。`CartMapper` 和 `cart` 表完整保留。

#### 核心代码（CartServiceImpl）

```java
// 加购（Lua 原子执行）
public void addItem2Cart(CartFormDTO dto) {
    String cartKey = "cart:user:" + UserContext.getUser();
    String numKey = cartKey + ":num";
    String itemDataJson = objectMapper.writeValueAsString(buildCartItemData(dto));
    Long result = redisService.executeScript(ADD_CART_LUA, Long.class,
            Arrays.asList(cartKey, numKey),
            String.valueOf(dto.getItemId()), itemDataJson,
            String.valueOf(ttlSeconds), String.valueOf(maxItems));
    if (result != null && result == -1) {
        throw new BizIllegalException("购物车已满");
    }
}

// 查询（合并两个 Hash）
public List<CartVO> queryMyCarts() {
    Map<Object, Object> cartMap = redisService.hGetAll(cartKey);
    Map<Object, Object> numMap = redisService.hGetAll(numKey);
    return convertRedisMapToCartVOList(cartMap, numMap); // Java 层合并 num
}

// 删除（Lua 原子执行）
public void removeByItemIds(Collection<Long> itemIds) {
    redisService.executeScript(REMOVE_CART_LUA, Arrays.asList(cartKey, numKey), fields);
    removeByItemIdsMysql(itemIds, userId); // 同时清理 MySQL
}
```

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

#### 缓存失效机制

| 操作 | 控制器 | 失效操作 |
|------|--------|----------|
| 新增商品 | `POST /items` | 无需失效（新增的 id 无缓存） |
| 更新商品 | `PUT /items` | `delete("item:info:{id}")` |
| 更新状态 | `PUT /items/status/{id}/{status}` | `delete("item:info:{id}")` |
| 删除商品 | `DELETE /items/{id}` | `delete("item:info:{id}")` |

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
| `cart:user:*:num` | TTL 30 天自动过期（与 `cart:user:*` 同步） |
| `cart:user:*` | TTL 30 天自动过期 |
| `item:info:*` | TTL 30 分钟自动过期 + 写操作主动删除 |
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
3. **秒杀场景**：`RedisService` 已预留 `incrBy/decrBy` 和 Lua 脚本执行能力，可直接实现秒杀 Lua 脚本 + MySQL 行锁兜底（参考 nova-mall 的三层防超卖架构）
4. **接口限流**：Gateway 层滑动窗口限流，搭配 `incrBy` + `expire` 实现
5. **监控面板**：接入 Redis 监控大盘（如 Grafana + Redis Exporter）

> **已完成**：购物车加购/删除的 Lua 原子操作（ADD_CART_LUA / REMOVE_CART_LUA）、商品缓存回写的 SET NX EX、扣款分布式锁的 Lua 释放。
