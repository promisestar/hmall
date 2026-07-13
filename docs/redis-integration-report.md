# hmall Redis 集成修改说明文档

> 更新日期：2026-07-13  
> 参考文档：`docs/redis-application-analysis.md`

---

## —、Redis+MySQL 双写架构总览

本次改造（第三阶段）在已有基础上实现了验证码存储和 Token 黑名单登出失效功能，核心策略如下：

```
购物车写入：Redis Lua 同步 → MQ 异步落 MySQL → 5min 版本补偿
购物车删除：Redis Lua 同步 + MySQL 同步 DELETE（双删同步，不走 MQ）
购物车读取：Redis → miss → MySQL → lazy sync 回填

商品缓存：MySQL 同步写 → Redis 同步删缓存 → MQ 二次确认删除 → 5min 补偿
商品读取：Cache-Aside（Redis → miss → MySQL → SET NX EX 回填）

验证码：Redis SET EX 5min → 校验通过后 DELETE（一次性）
Token 黑名单：登出 → Redis SET EX（TTL = 剩余有效期）→ Gateway 每次请求检查
```

### 1.1 hm-common（公共模块）

| 文件 | 操作 | 说明 |
|------|------|------|
| `pom.xml` | 修改 | 新增 `spring-boot-starter-data-redis` 依赖 |
| `META-INF/spring.factories` | 修改 | 注册 `RedisConfig`、`RedisCacheAspect`、`LogDirectoryInitializer` |
| `config/RedisConfig.java` | 新增 | RedisTemplate Bean 配置（`@ConditionalOnProperty` 按需加载；StringRedisTemplate 由 Spring Boot 自动配置） |
| `config/LogDirectoryInitializer.java` | 新增 | `@Configuration` + `ApplicationRunner`：启动时创建 `./logs/` 目录，解决 Logback 1.2.x `RollingFileAppender` 不会自动创建父目录导致日志文件静默丢失的问题 |
| `resources/logback-spring.xml` | 新增 | 双文件日志：`hmall.log`（全量）+ `api.log`（WebLogAspect 专用，`additivity="false"` 隔离） |
| `service/RedisService.java` | **重写** | **双 Template 设计**：`StringRedisTemplate`（Lua + String 读写，手动 ObjectMapper 序列化）+ `RedisTemplate`（Jackson，Hash/Set 操作） |
| `utils/RedisLockUtil.java` | 新增 | 分布式锁工具（SET NX EX + Lua 原子释放） |
| `utils/LuaScriptLoader.java` | 新增 | Lua 脚本加载工具（从 classpath 读取 .lua 文件） |
| `aspect/RedisCacheAspect.java` | 修改 | Redis 异常隔离切面（根据返回类型返回安全默认值：`boolean`→false、`long`→0L、`int`→0，引用类型→null，避免基本类型 null 拆箱 NPE） |
| `resources/lua/set_if_absent.lua` | 修改 | SET NX EX 原子缓存写入（`tonumber(ARGV[2])` 防御 Jackson 序列化导致的数值类型转换异常） |
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
| `resources/lua/add_cart.lua` | 修改 | KEYS[3] 全局版本 key + `or 0` 防御 HLEN nil + `tonumber()` 显式转换 + ARGV[5] 版本参数 |
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
| `service/impl/UserServiceImpl.java` | 修改 | `deductMoney` 增加分布式锁；**新增 `sendCode`、`loginByCode`、`logout` 方法** |
| `controller/UserController.java` | 修改 | 新增 `POST /users/code`、`POST /users/login/code`、`POST /users/logout` |
| `domain/dto/SendCodeDTO.java` | 新增 | 发送验证码请求 DTO（phone） |
| `domain/dto/LoginByCodeDTO.java` | 新增 | 验证码登录请求 DTO（phone + code） |
| `utils/JwtTool.java` | 修改 | `createToken` 增加 `setJWTId(UUID)`；新增 `getJti()`、`getRemainingTTL()`；提取 `parseAndVerify()` 私有方法 |

### 1.5 hm-gateway（网关层）— Token 黑名单检查

| 文件 | 操作 | 说明 |
|------|------|------|
| `application.yml` | 修改 | 新增 `spring.redis` 连接配置；白名单新增 `/users/login/code` 和 `/users/code` |
| `filters/AuthGlobalFilter.java` | 修改 | 步骤 5 新增黑名单检查：`getJti()` → `isTokenBlacklisted()` → 命中返回 401；Redis 不可用时降级放行 |
| `utils/JwtTool.java` | 修改 | `createToken` 增加 `setJWTId(UUID)`；新增 `getJti()` 方法 |

### 1.6 其他服务（配置层）

| 服务 | 操作 | 说明 |
|------|------|------|
| `hm-service` | 修改 `application.yaml` + `application-local.yaml` | 已有 Redis 依赖，仅补连接配置 |
| `trade-service` | 修改 `application.yaml` + `application-local.yaml` | 预留 Redis 配置（后续缓存订单信息） |
| `pay-service` | 修改 `application.yaml` + `application-local.yaml` | 预留 Redis 配置（后续幂等性缓存） |
| `search-service` | 无变更 | 直接查 ES，暂不引入 Redis |

### 1.7 hmall-frontend（前端）— 验证码登录 + 登出联动

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/types/index.ts` | 修改 | 新增 `SendCodeDTO`、`LoginByCodeDTO` 类型 |
| `src/api/user.ts` | 修改 | 新增 `sendCode`、`loginByCode`、`logoutApi` 三个 API 函数 |
| `src/views/portal/LoginPage.vue` | **重写** | 新增"密码登录/验证码登录"双 Tab；验证码表单含手机号输入 + 验证码输入 + 60s 倒计时发送按钮 |
| `src/stores/user.ts` | 修改 | `logout()` 改为 async（先调 backend logout 再清除本地）；新增 `loginByCode()` 方法 |
| `src/stores/admin.ts` | 修改 | `logout()` 改为 async（先调 backend logout 再清除本地） |
| `src/views/portal/PortalLayout.vue` | 修改 | "退出"改为 `async handleLogout()`，登出后自动跳转首页 |
| `src/views/admin/AdminLayout.vue` | 修改 | `handleLogout()` 改为 async，await 登出后再跳转 |

---

## 二、架构说明

### 2.1 新增基础设施

```
hm-common/
├── config/RedisConfig          → RedisTemplate + StringRedisTemplate Bean
│                                 （Jackson2Json + StringRedisSerializer 双序列化器）
├── config/LogDirectoryInitializer → ApplicationRunner：启动时 mkdir logs/
├── resources/
│   ├── logback-spring.xml      → 双文件日志（hmall.log + api.log 隔离输出）
│   └── META-INF/spring.factories → 注册 3 个配置类（含 LogDirectoryInitializer）
├── service/RedisService        → 双 Template 设计：
│   ├── stringRedisTemplate     → Lua 脚本执行 + String 读写（手动 Jackson 序列化）
│   └── redisTemplate           → Hash/Set 操作（Jackson 自动序列化，hGetAll 返回 Map）
├── utils/RedisLockUtil         → 分布式锁（SET NX EX + Lua 释放）
├── utils/LuaScriptLoader       → .lua 文件加载工具
├── aspect/RedisCacheAspect     → 异常隔离切面（基本类型安全降级）
└── resources/lua/
    ├── set_if_absent.lua       → SET NX EX 原子写入（tonumber 防御）
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

user-service/
├── domain/dto/SendCodeDTO      → 发送验证码 DTO
├── domain/dto/LoginByCodeDTO   → 验证码登录 DTO
├── controller/UserController   → 新增 /code + /login/code + /logout
└── utils/JwtTool               → 新增 jti 支持 + getJti/getRemainingTTL

hm-gateway/
├── filters/AuthGlobalFilter    → 新增 Token 黑名单检查（步骤 5）
└── utils/JwtTool               → 新增 jti 支持 + getJti

hmall-frontend/
├── src/views/portal/LoginPage  → 新增验证码登录 Tab
├── src/stores/user.ts          → async logout + loginByCode
├── src/stores/admin.ts         → async logout
├── src/api/user.ts             → 新增 sendCode/loginByCode/logoutApi
└── src/types/index.ts          → 新增 SendCodeDTO/LoginByCodeDTO
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
| 验证码 | `sms:code:{phone}` | String | 5 分钟 | SET EX → 校验后 DELETE（一次性） |
| Token 黑名单 | `token:blacklist:{jti}` | String | 动态 | SET EX（TTL = token 剩余有效期） |

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
            // 根据返回类型返回安全的默认值，避免 null 拆箱为基本类型时 NPE
            Class<?> returnType = ((MethodSignature) joinPoint.getSignature()).getReturnType();
            if (returnType == boolean.class) return false;
            if (returnType == long.class)   return 0L;
            if (returnType == int.class)    return 0;
            return null; // void / Object / Long / Boolean 等引用类型安全
        }
    }
}
```

**为什么不能统一 return null**：`setIfAbsent` / `hasKey` / `hHasKey` 返回 `boolean`（基本类型），`return null` 会触发 Java 自动拆箱 `null → boolean` 时的 `NullPointerException`。

**降级行为（各服务）**：

| 服务 | Redis 返回默认值时的行为 |
|------|--------------------------|
| cart-service | `queryMyCarts` → 走 `queryMyCartsMysql` 查 MySQL |
| cart-service | `addItem2Cart` → 走 `addItem2CartMysql` 查 MySQL |
| item-service | `queryItemByIds` → 缓存未命中（`get` 返回 null）→ 全量查 MySQL |
| item-service | `setIfAbsent` 返回 false → 视为缓存写入失败，MySQL 数据已返回给上游 |

---

### 3.5 验证码存储

#### 改造前

项目无验证码功能，`RedisService` 中预置了 `saveSmsCode`/`getSmsCode`/`deleteSmsCode` 方法但无调用者。

#### 改造后

**Redis Key 设计：**
```
Key:  sms:code:{phone}
Type: String
TTL:  5 分钟
```

**API 端点（user-service）：**

| 端点 | 方法 | 说明 | 白名单 |
|------|------|------|--------|
| `/users/code` | POST | 发送短信验证码（phone → 6 位随机码 → Redis SET EX 5min） | ✅ |
| `/users/login/code` | POST | 验证码登录（比对 Redis code → 查 MySQL → 生成 JWT） | ✅ |

**发送验证码流程（`UserServiceImpl.sendCode`）：**
1. 生成 6 位随机数字验证码
2. 调用 `redisService.saveSmsCode(phone, code)` 存入 Redis（TTL 5 分钟）
3. 通过 `log.info` 模拟短信发送（生产环境对接短信 SDK）

**验证码登录流程（`UserServiceImpl.loginByCode`）：**
1. `redisService.getSmsCode(phone)` 获取缓存的验证码
2. 校验输入 code → 若不匹配抛 `BadRequestException`
3. `redisService.deleteSmsCode(phone)` 验证后立即删除（一次性使用）
4. `lambdaQuery().eq(User::getPhone, phone)` 查 MySQL 用户
5. 生成 JWT token（含 jti）→ 返回 `UserLoginVO`

**前端改造（LoginPage.vue）：**
- 顶部"密码登录 / 验证码登录"双 Tab 切换
- 验证码 Tab：手机号输入 + 验证码输入 + "发送验证码"按钮（60s 倒计时禁用）
- 手机号格式校验 `1[3-9]\d{9}`

---

### 3.6 Token 黑名单（登出失效）

#### 改造前

用户登出仅清除前端 `sessionStorage`，JWT 在有效期内仍可用于 API 请求（无状态 token 固有问题）。项目完全没有 logout 接口。

#### 改造后 — 全链路设计

```
登出流程:   前端 POST /users/logout
              → user-service: extract jti → redisService.addTokenToBlacklist(jti, ttl)
              → 前端清除 sessionStorage

后续请求:   Gateway AuthGlobalFilter
              → parse JWT → getJti() → redisService.isTokenBlacklisted(jti)
              → 命中 → 401 (token 已失效)
              → 未命中 / Redis 不可用 → 放行
```

**JWT 结构变更：**

每个 JWT 创建时自动注入唯一 `jti`（UUID）：
```java
// user-service & gateway JwtTool 均已修改
JWT.create()
    .setJWTId(UUID.randomUUID().toString())  // ← 新增
    .setPayload("user", userId)
    .setIssuedAt(new Date())
    .setExpiresAt(...)
```

**新增方法：**
- `JwtTool.getJti(token)` — 提取 JWT ID
- `JwtTool.getRemainingTTL(token)` — 计算剩余有效期（秒）

**登出接口（`POST /users/logout`）：**
```java
public void logout(String token) {
    String jti = jwtTool.getJti(token);
    long remainingTTL = jwtTool.getRemainingTTL(token);
    if (remainingTTL > 0) {
        redisService.addTokenToBlacklist(jti, remainingTTL);
    }
}
```

**Redis Key：**
```
Key:  token:blacklist:{jti}
Type: String
Value: "1"
TTL:  token 剩余有效期（过期自动清理，不浪费内存）
```

**Gateway 黑名单检查（AuthGlobalFilter 新增步骤 5）：**
```java
if (redisService != null) {
    String jti = jwtTool.getJti(token);
    if (jti != null && redisService.isTokenBlacklisted(jti)) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }
}
```

**降级策略：**
- `RedisService` 通过 `@Autowired(required = false)` 注入 — Gateway 无 Redis 时不检查黑名单
- Redis 查询异常 → catch 后 `log.warn` 降级放行（不阻塞正常业务）
- 登出 API 失败 → 前端仍清除本地 `sessionStorage`（try-catch 包裹）

**前端改造：**
- `userStore.logout()` / `adminStore.logout()` 改为 async：先调 `POST /users/logout` 再清本地
- `PortalLayout.vue`：登出后自动跳转 `/portal/home`
- `AdminLayout.vue`：登出后自动跳转 `/admin/login`

---

### 3.7 双 Template 序列化设计（技术决策记录）

#### 问题背景

三个连续发现的 Jackson 序列化问题导致架构重新设计：

| # | 问题 | 根因 |
|---|------|------|
| 1 | Lua `tonumber(ARGV[n])` 返回 nil | `String.valueOf(num)` 经 Jackson 序列化为带引号的 `"\"1800\""`，tonumber 解析失败 |
| 2 | `set_if_absent` 返回 "OK" 但切面捕获异常 | "OK" 不是合法 JSON，Jackson 反序列化结果时抛异常 |
| 3 | `get(key, ItemDTO.class)` 返回 LinkedHashMap | Jackson 反序列化无类型信息，默认退化为 LinkedHashMap |

#### 解决方案：双 Template 分离

```
stringRedisTemplate（StringRedisSerializer）     redisTemplate（Jackson2Json）
├── Lua 脚本执行（executeScript）                ├── Hash 操作（hSet/hGetAll/hDel/...）
├── String 读写（set/get/setIfAbsent）           ├── Set 操作（sAdd/sMembers）
│   + 手动 ObjectMapper 序列化/反序列化          │   这些操作返回值类型固定（Map/Set），
│   args: Long→"1800" 无引号包裹 ✓               │   不存在类型精确还原需求，Jackson 够用
│   result: "OK" 直接作为 String ✓               └── expire/delete/hasKey 等通用操作
│   get(key,clazz): readValue(json, clazz) ✓
```

#### 序列化数据流

```
写入 String:  set(key, dto) → objectMapper.writeValueAsString(dto) → stringRedisTemplate.set()
写入 Lua:    setIfAbsent("item:info:{id}", dto, ttl) → objectMapper.writeValueAsString(dto) → Lua SET NX EX

读取 String:  get(key, ItemDTO.class) → stringRedisTemplate.get(key) → objectMapper.readValue(json, ItemDTO.class)
读取 String:  get(key) → stringRedisTemplate.get(key) → objectMapper.readValue(json, Object.class)
```

**兼容性**：`objectMapper.writeValueAsString()` 与 `Jackson2JsonMessageConverter` 对同一对象的序列化结果字节级一致，新旧存储数据可互读。

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
| 验证码发送 | Redis 不可用 → 验证码发送失败 | 用户无法通过验证码登录，密码登录不受影响 |
| Gateway 黑名单 | Redis 不可用 → 降级放行 | 登出的 token 仍可继续使用（退化到改造前行为） |

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
| `sms:code:*` | TTL 5 分钟自动过期 + 校验后主动 DELETE |
| `token:blacklist:*` | TTL = token 剩余有效期自动过期 |

### 6.3 监控建议

1. **Redis 可用性**：PING 心跳监控，延迟 > 10ms 告警
2. **内存使用率**：> 80% 告警，考虑增加实例或调整 TTL
3. **连接数**：实际连接数应 < `max-active` * 服务数（7 × 8 = 56，含 Gateway）
4. **缓存命中率**：`item:info:*` 命中率应 > 80%
5. **日志关键字**：关注 `"降级处理"`（Redis 异常）和 `"降级到 MySQL"`（业务回退）

### 6.4 启动检查清单

- [ ] Redis 服务已启动（`redis-cli PING` → `PONG`）
- [ ] 各微服务 + Gateway `application-local.yaml` 中 `spring.redis.host` 正确配置
- [ ] 启动无报错：控制台无 `RedisConnectionFailureException`
- [ ] 购物车功能正常：加购 → 查购物车 → 删商品
- [ ] 商品查询正常：首页商品列表、商品详情
- [ ] 下单扣款正常：创建订单 → 支付 → 扣款成功
- [ ] 验证码登录正常：发送验证码 → 验证码登录 → 跳转首页
- [ ] 登出失效正常：登录 → 登出 → 用旧 token 请求受保护页面 → 返回 401

---

## 七、后续优化建议

1. **商品批量查询 Pipeline 优化**：`queryItemByIds` 中逐个 `get` 可改为 Redis Pipeline 批量操作，减少网络往返
2. **查询购物车的一致性快照**：`queryMyCarts` 分两次 `HGETALL`（cart + num），极端情况下可能读到不一致的快照。可合并为一次 Lua 脚本，在 Redis 内完成合并返回
3. **秒杀场景**：`RedisService` 已预留 `incrBy/decrBy` 和 Lua 脚本执行能力，可直接实现秒杀 Lua 脚本 + MySQL 行锁兜底
4. **MQ 消息积压监控**：补偿任务是最终兜底，需监控 `cart.sync.queue` 和 `item.cache.invalidate.queue` 的消息积压量
5. **监控面板**：接入 Redis 监控大盘（如 Grafana + Redis Exporter）

> **已完成**：购物车 Redis+MySQL 双写架构（Lua 原子操作 + MQ 异步 + 5min 版本补偿）、购物车删除双删同步、商品缓存三层失效保障（同步删除 + MQ 二次确认 + 补偿任务）、扣款分布式锁的 Lua 释放、SET NX EX 原子缓存回写、**验证码存储（sms:code: 5min TTL + 一次性校验）**、**Token 黑名单登出失效（jti + Gateway 黑名单检查 + 前端联动登出）**。
>
> **已修复的序列化问题**：
> - `add_cart.lua`：`HLEN or 0` 防御 nil + Lua 端所有数值参数使用 `tonumber()` 显式转换
> - `set_if_absent.lua`：`tonumber(ARGV[2])` 显式转换
> - 双 Template 设计：Lua 脚本 + String 读写使用 `StringRedisTemplate`，消除 Jackson 对 Lua 返回值 "OK" 的非 JSON 解析异常
> - `RedisService.get(key, clazz)`：使用 `objectMapper.readValue()` 精确还原类型，解决 LinkedHashMap 强转 ClassCastException
> - `RedisService.set/get`：String 读写统一走 `stringRedisTemplate` + 手动 `ObjectMapper`，消除 Jackson 序列化器无类型信息退化为 LinkedHashMap 的隐患
> - `RedisCacheAspect`：根据返回类型返回安全默认值（`boolean`→false、`long`→0L、`int`→0），避免基本类型 null 拆箱 NPE
> - Java 端 Lua args：数值参数直接传 Long/Integer，禁止 `String.valueOf()`（`StringRedisTemplate` 中 `Long.toString()` 无引号包裹）
> - `LogDirectoryInitializer`：启动时自动创建 `./logs/` 目录，防止 Logback 1.2.x 父目录缺失导致文件日志静默丢失
> - `api.log` 排查：Windows 下手动创建的 `api.log` 可能被独占锁或权限阻止写入，删除后让 Logback 自建即可

---

## 八、已知问题与修复记录（2026-07-13 会话）

### 8.1 Gateway 中 RedisTemplate Bean 重名冲突

**问题**：`RedisConfig` 和 Spring Boot 内置 `RedisAutoConfiguration` 都注册名为 `redisTemplate` 的 Bean。在 Servlet 微服务中因自配置数量多、排序结果恰好让 `RedisConfig` 先处理而"碰巧正常"；在 Gateway（WebFlux）中自配置链极短，`RedisAutoConfiguration` 先于 `RedisConfig` 处理 → 同名 Bean 冲突。

**修复**（`RedisConfig.java`）：
- 添加 `@AutoConfigureBefore(RedisAutoConfiguration.class)` 显式声明处理顺序
- 保证 `RedisConfig` 的自定义 `redisTemplate`（Jackson 序列化器）始终先注册
- `RedisAutoConfiguration` 的 `@ConditionalOnMissingBean(name = "redisTemplate")` 检测到已有 Bean 自动跳过

**影响范围**：`hm-common/config/RedisConfig.java`

---

### 8.2 executeScript 传入 Long/Integer 导致 ClassCastException

**问题**：`RedisService.executeScript()` 底层使用 `StringRedisTemplate`，其 `StringRedisSerializer` 只接受 `String` 类型的 args。传入 `Long`/`Integer` 时，序列化器的桥接方法 `serialize(Object)` → `(String)` 强转 → `ClassCastException`。

旧代码中的注释"传 Long 避免 Jackson 引号"是针对 `RedisTemplate`（Jackson 序列化器）的防御措施，切到 `StringRedisTemplate` 后该防御已过时且有害。

**修复**（`CartServiceImpl.java`）：
- `add_cart.lua` 的三个数值参数改为 `String.valueOf()` 传入：
  - `ttlSeconds` (long) → `String.valueOf(ttlSeconds)`
  - `maxItems` (Integer) → `String.valueOf(cartProperties.getMaxItems())`
  - `version` (long) → `String.valueOf(version)`
- Lua 端已使用 `tonumber()` 将字符串转回数字，传 String 不影响解析
- 更新注释说明新的序列化机制

**影响范围**：`cart-service/service/impl/CartServiceImpl.java`（行 110-116）

---

### 8.3 降级写 MySQL 后 Redis 旧缓存不失效

**问题链**：
```
加购: executeScript() 异常 → catch → addItem2CartMysql() 写 MySQL
      → Redis 仍有旧数据（不含新商品）
查询: hGetAll(cartKey) → 非空（旧数据）→ 直接返回 → 用户看不到新商品
```
`queryMyCarts()` 只在 Redis **为空**时才回退 MySQL，非空时有旧数据就永远返回旧数据。

**修复分两层**：

#### 8.3.1 Redis 可用但 Lua 异常 → invalidateRedisCart（即时）

`addItem2Cart()` catch 块中新增 `invalidateRedisCart(userId)`：
- Redis Lua 写入失败 → 写 MySQL → **清除 Redis 旧缓存**
- 下次查询：Redis 为空 → 回退 MySQL（含新商品）→ lazy sync 回填 Redis

`removeByItemIds()` 同理：Redis 删除失败 + MySQL 删除成功 → 清除 Redis 旧缓存。

#### 8.3.2 Redis 宕机（不可达）→ pendingInvalidationUsers 内存标记

Redis 宕机时 `invalidateRedisCart()` 也会失败。用 `ConcurrentHashMap.newKeySet()` 记录"待失效"用户：

| 场景 | 行为 |
|------|------|
| `invalidateRedisCart()` 失败 | `pendingInvalidationUsers.add(userId)` |
| `queryMyCarts()` Redis 命中 | `contains(userId)` → O(1) 检查 → 命中则从 MySQL 重新加载 |
| 重新加载成功 | `pendingInvalidationUsers.remove(userId)` → 后续查询恢复零开销 |

**性能**：正常 `contains()` 不产生任何 I/O；只有曾因 Redis 宕机降级过的用户才触发一次 MySQL 重新加载。

**局限**：内存标记在服务重启或多实例间丢失，由补偿任务（@5min）兜底。

**影响范围**：`cart-service/service/impl/CartServiceImpl.java`

---

### 8.4 降级写 MySQL 不设置 version 字段

**问题**：`addItem2CartMysql()` 中：
- `updateNum()` 只做 `UPDATE cart SET num = num + 1` — 不更新 version
- `save(cart)` 创建新条目 — `cart.version` 为 null

导致 MySQL 没有版本号或版本号为旧值，补偿任务的版本比对完全失效。

**修复**：
- `CartMapper.updateNum()` SQL 增加 `version = #{version}`
- `addItem2CartMysql()` 两条路径都设 `version = System.currentTimeMillis()`

**影响范围**：`cart-service/mapper/CartMapper.java`、`cart-service/service/impl/CartServiceImpl.java`

---

### 8.5 补偿任务版本解析 ClassCastException + 缺失同步分支

**问题①**：`CartSyncCompensationTask.syncUserCartIfNeeded()` 中：
```java
String redisVersionStr = (String) redisService.get(versionKey);
```
`redisService.get()` 返回类型取决于存储方式：
- Lua `redis.call('SET', ...)` 存裸数字 → `ObjectMapper.readValue("1700123456789")` → `Long`
- `redisService.set()` 存 JSON 字符串 → `ObjectMapper.readValue("\"1700123456789\"")` → `String`

对前者 `(String)` 强转直接 `ClassCastException`，导致该用户的补偿同步失败。

**修复**（`CartSyncCompensationTask.java`）：
- `parseVersion(String)` → `parseRedisVersion(Object)`
- 内部处理：`instanceof Number` → `longValue()`，否则 `Long.parseLong(value.toString())`

**问题②**：补偿任务只处理了 `redisVersion > mysqlMaxVersion` 和 `redisVersion == 0` 两种情况，缺失 `redisVersion < mysqlMaxVersion`（Redis 宕机期间降级写 MySQL 后，MySQL 版本 > Redis 旧版本）。

**修复**（`CartSyncCompensationTask.java`）：
- 新增分支：`mysqlMaxVersion != null && mysqlMaxVersion > redisVersion` → MySQL → Redis 回填

**影响范围**：`cart-service/task/CartSyncCompensationTask.java`

---

> **已完成**：购物车 Redis+MySQL 双写架构（Lua 原子操作 + MQ 异步 + 5min 版本补偿）、购物车删除双删同步、商品缓存三层失效保障（同步删除 + MQ 二次确认 + 补偿任务）、扣款分布式锁的 Lua 释放、SET NX EX 原子缓存回写、**验证码存储（sms:code: 5min TTL + 一次性校验）**、**Token 黑名单登出失效（jti + Gateway 黑名单检查 + 前端联动登出）**。
