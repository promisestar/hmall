# hmall C 端微服务（User 端）实现说明文档

> 版本：v1.0
> 日期：2026-07-31
> 设计文档：`docs/hmall_User设计方案文档.md`

---

## 一、实现概况

hmall C 端微服务体系包含 7 个核心子服务和 2 个公共模块，基于 Spring Cloud Alibaba 生态构建，实现了完整的电商核心链路（搜索浏览 → 加购 → 下单 → 支付）。

### 1.1 文件变更统计

| 类别 | 数量 | 说明 |
|------|------|------|
| hm-gateway Java 文件 | 11 | 含启动类、4 个配置类、2 个全局过滤器、1 个 GatewayFilter Factory、1 个路由加载器、1 个 JWT 工具类 |
| user-service Java 文件 | 20 | 含 Controller 2 个、Service 4 个、Mapper 2 个、PO/DTO/VO 10 个、Config 2 个、Utils 1 个 |
| user-service 配置文件 | 4 | pom.xml + bootstrap.yml + application.yaml + dev/local yaml |
| user-service 密钥库 | 1 | hmall.jks（RSA 2048，JKS 格式） |
| item-service Java 文件 | 23 | 含 Controller 3 个（Item/Recommend/Search-已注释）、Service 4 个、Mapper 1 个、PO/DTO/VO/Query 10 个等 |
| cart-service Java 文件 | 14 | 含 Controller/Service/Mapper/PO/DTO/VO/MQ/Listener/CompensationTask |
| cart-service Lua 脚本 | 2 | add_cart.lua、remove_cart.lua |
| trade-service Java 文件 | 53 | 含 OrderController、SeckillController、Service 层、多个 MQ Listener、定时任务、Lua 脚本等 |
| trade-service SQL | 1 | V2__seckill_tables.sql（秒杀相关 5 张表 DDL） |
| pay-service Java 文件 | 15 | 含 Controller/Service/Mapper/PO/DTO/VO/LocalMessageSender |
| search-service Java 文件 | 15 | 含 Controller/Service/Mapper/PO/DTO/VO/Query + 3 个 MQ Listener |
| hm-common Java 文件 | 30 | 含 R/PageDTO/PageQuery、RedisService、UserContext、异常体系、LuaScriptLoader、RateLimitUtil、RedisLockUtil 等 |
| hm-api Java 文件 | 15 | 含 6 个 Feign Client + 跨服务 DTO + DefaultFeignConfig |
| 配置文件合计 | ~30 | 各服务 bootstrap + application + dev/local yaml |
| **C 端总计** | **~230** | — |

---

## 二、架构总览

### 2.1 服务调用拓扑

```
hm-gateway (:8080)
  │  AuthGlobalFilter (JWT认证 → 透传 userId)
  │  RateLimitFilter (Redis滑动窗口限流)
  │  DynamicRouteLoader (Nacos路由热更新)
  │
  ├── user-service (:8083)  →  hm-user MySQL  →  Redis (Token黑名单/验证码/分布式锁)
  │     ├── UserController       (/users)    登录/注册/扣款/登出/管理后台
  │     └── AddressController    (/addresses) 地址CRUD
  │
  ├── item-service (:8081)  →  hm-item MySQL  →  Redis (商品缓存)  →  RabbitMQ (ES同步/缓存失效)
  │     ├── ItemController       (/items)         商品CRUD/库存扣减恢复/批量管理
  │     └── RecommendController  (/recommend)     个性化推荐
  │
  ├── cart-service (:8082)  →  Redis (主存储)  →  RabbitMQ (异步落库)  →  hm-cart MySQL
  │     └── CartController       (/carts)         购物车增删改查
  │
  ├── trade-service (:8084) →  hm-trade MySQL  →  Redis (秒杀库存/分布式锁)  →  RabbitMQ (延迟消息/异步下单)
  │     ├── OrderController      (/orders)        订单CRUD/管理后台
  │     └── SeckillController    (/seckill)       秒杀活动/秒杀下单
  │
  ├── pay-service (:8085)   →  hm-pay MySQL  →  Redis  →  RabbitMQ (支付成功通知)
  │     └── PayController        (/pay-orders)    支付单管理/余额支付
  │
  └── search-service (:8089)  →  Elasticsearch  →  hm-item MySQL (只读)  →  RabbitMQ (索引同步)
        └── SearchController     (/search)        全文搜索/聚合过滤/推荐召回
```

### 2.2 认证链路

```
登录:
  POST /users/login (白名单)
    → UserController.login()
      → 查 hm-user.user 表
      → BCryptPasswordEncoder.matches() 校验密码
      → JwtTool.createToken(userId, 30m) 签发 JWT
        (payload: jti=UUID, user=userId, iat, exp)
      → 返回 {token, userId, username, balance}

后续请求:
  Authorization: Bearer xxx
    → hm-gateway AuthGlobalFilter
      → JwtTool.parseToken() 解析 JWT (RSA-256 验签)
      → Redis 黑名单检查 (token:blacklist:{jti})
      → 续期窗口检查 (15分钟冷却) → 自动刷新
      → 写入请求头 user-info: userId
      → 路由转发到下游微服务

登出:
  POST /users/logout
    → 提取 JTI → Redis SET token:blacklist:{jti}, TTL=剩余有效期
```

### 2.3 C/B 端认证隔离

| 维度 | C 端（消费者） | B 端（管理后台） |
|------|---------------|-----------------|
| 密钥库 | classpath:hmall.jks | classpath:admin.jks |
| JWT 类型标记 | 无 | `type: ADMIN` |
| 登录接口 | `POST /users/login` | `POST /admin/login` |
| 用户表 | `hm-user.user` | `hm-admin.admin_user` |
| Token 有效期 | 30 分钟 | 2 小时 |

---

## 三、各服务实现详情

### 3.1 hm-gateway（API 网关）

#### 3.1.1 模块结构

```
hm-gateway/
├── pom.xml
└── src/main/java/com/hmall/gateway/
    ├── GateWayApplication.java              # 启动类
    ├── config/
    │   ├── AuthProperties.java              # 认证白名单配置
    │   ├── JwtProperties.java               # JWT 配置属性
    │   ├── RateLimitProperties.java         # 限流规则配置
    │   └── SecurityConfig.java              # BCrypt + RSA KeyPair Bean
    ├── filters/
    │   ├── AuthGlobalFilter.java            # ★ 核心认证过滤器 (order=0)
    │   ├── RateLimitFilter.java             # 滑动窗口限流 (order=1, fail-open)
    │   └── PrintAnyGatewayFilterFactory.java # 调试用 GatewayFilter
    ├── routers/
    │   └── DynamicRouteLoader.java          # Nacos 动态路由加载器
    └── utils/
        └── JwtTool.java                     # JWT 工具 (RSA-256, Hutool)
```

#### 3.1.2 AuthGlobalFilter 核心逻辑

```java
public Mono<Void> filter(exchange, chain) {
    // 1. 白名单放行
    if (isExcludePath(path)) return chain.filter(exchange);

    // 2. 提取 JWT
    String token = extractToken(request);
    if (token == null) return unauthorized("未登录");

    // 3. 解析校验 JWT (RSA验签 + 过期检查)
    Long userId = jwtTool.parseToken(token);  // 失败抛异常→401

    // 4. 黑名单检查 (Redis, fail-open)
    redisService.isTokenBlacklisted(jti) → 命中返回401

    // 5. Token 续期 (冷却窗口15分钟)
    String newToken = jwtTool.refreshToken(token);
    if (newToken != null) response.addHeader("Authorization", newToken);

    // 6. 透传 userId
    request.mutate().header("user-info", userId.toString());

    // 7. 放行
    return chain.filter(exchange);
}
```

#### 3.1.3 RateLimitFilter 核心逻辑

```java
public Mono<Void> filter(exchange, chain) {
    // 1. 未启用或 RateLimitUtil 不可用 → 放行
    // 2. Ant 路径匹配限流规则
    // 3. 构建 key: "ratelimit:{path}:{userId}"
    // 4. Lua 脚本滑动窗口检查 → 超限返回 429
    //    - Redis ZSET: key=ratelimit:{path}:{userId}, score=时间戳, member=UUID
    //    - 移除窗口外的记录 → 统计窗口内数量 → 与 maxRequests 对比
    // 5. fail-open: Redis 异常不阻塞
}
```

#### 3.1.4 DynamicRouteLoader 核心逻辑

```java
@PostConstruct
public void init() {
    // 1. 从 Nacos Config 加载 gateway-routes.json
    // 2. 解析为 List<RouteDefinition>
    // 3. 遍历写入 RouteDefinitionWriter
    // 4. 注册 Nacos Listener → 配置变更时清空旧路由 + 写入新路由
}
```

---

### 3.2 user-service（用户服务）

#### 3.2.1 模块结构

```
user-service/
├── pom.xml
└── src/main/java/com/hmall/user/
    ├── UserApplication.java
    ├── config/
    │   ├── JwtProperties.java               # hm.jwt.* 配置绑定
    │   └── SecurityConfig.java              # BCrypt + RSA KeyPair
    ├── controller/
    │   ├── UserController.java              # 9 个端点
    │   └── AddressController.java           # 6 个端点
    ├── domain/
    │   ├── po/User.java, Address.java
    │   ├── dto/LoginFormDTO, LoginByCodeDTO, SendCodeDTO, AddressDTO
    │   └── vo/UserLoginVO.java
    ├── enums/UserStatus.java
    ├── mapper/UserMapper.java, AddressMapper.java
    ├── service/IUserService, IAddressService, impl/
    └── utils/JwtTool.java                   # JWT 生成/解析
```

#### 3.2.2 核心接口实现

**登录与注册**：

| 接口 | 实现要点 |
|------|---------|
| `POST /users/login` | BCrypt 校验密码 → 检查账户状态(FROZEN→拒登) → 签发 JWT(30min) |
| `POST /users/code` | 生成 6 位随机验证码 → Redis `sms:code:{phone}` (TTL 5min) |
| `POST /users/login/code` | Redis 校验验证码 → 校验后立即删除 → 签发 JWT |
| `POST /users/logout` | 提取 JTI → Redis `token:blacklist:{jti}` (TTL=剩余有效期) |

**余额扣减**：

```java
@PostMapping("/money/deduct")
public void deductMoney(@RequestBody DeductMoneyDTO deductDTO) {
    // 1. BCrypt 校验支付密码
    // 2. Redis 分布式锁: lock:deduct:{userId} (5s 超时)
    // 3. SQL: UPDATE user SET balance = balance - #{amount} WHERE id = #{userId}
    // 4. 释放分布式锁
    // 5. 异常降级（Redis 不可用跳过锁，直接 SQL 扣减）
}
```

**管理后台接口**（给 admin-service 使用）：

| 接口 | 实现要点 |
|------|---------|
| `GET /users/page` | 分页查询，支持 keyword/status 筛选，密码字段清除 |
| `GET /users/{id}` | 用户详情，密码清除 |
| `POST /users/status/{id}` | 切换用户状态 NORMAL ↔ FROZEN |
| `POST /users/balance/{id}` | 调整余额（正数充值 / 负数扣减，防负余额） |

#### 3.2.3 地址管理安全控制

所有地址操作通过 `UserContext.getUser()` 获取当前用户，并校验 `address.userId == currentUserId`：

```java
// AddressController 中每个方法的前置校验
if (!address.getUserId().equals(UserContext.getUser())) {
    throw new BizIllegalException("无权操作该地址");
}
```

---

### 3.3 item-service（商品服务）

#### 3.3.1 模块结构

```
item-service/
├── pom.xml
└── src/main/java/com/hmall/item/
    ├── ItemApplication.java
    ├── config/
    │   ├── ElasticsearchConfig.java         # RestHighLevelClient Bean (暂未直接使用)
    │   └── ItemCacheCompensationTask.java   # 缓存补偿定时任务
    ├── controller/
    │   ├── ItemController.java              # 12 个端点
    │   └── RecommendController.java         # 推荐接口
    ├── domain/
    │   ├── po/Item.java                     # 商品 PO (17 个字段)
    │   ├── dto/ItemDTO, ItemDoc, ItemCacheMessage, OrderDetailDTO, RecommendItemDTO
    │   └── vo/RecommendVO.java
    ├── query/ItemPageQuery.java
    ├── mapper/ItemMapper.java               # 含 @Update SQL (扣减/恢复库存)
    ├── mq/
    │   ├── ItemCacheSender.java             # 缓存失效 MQ 生产者
    │   ├── ItemCacheReceiver.java           # 缓存失效 MQ 消费者 (二次确认)
    │   └── payment/
    │       └── paySuccessListener.java       # 支付成功→写藏品画像 (Phase 2)
    └── service/IItemService, IRecommendService, impl/
```

#### 3.3.2 缓存一致性三层保障

```java
// 第 1 层 - Controller 层直接删除 (写操作当时)
@PutMapping
public void updateItem(@RequestBody @Valid ItemDTO item) {
    itemService.updateById(item);                // MySQL 更新
    redisService.delete("item:info:" + item.getId());  // 删 Redis
    itemCacheSender.send(new ItemCacheMessage(item.getId()));  // 发 MQ
}

// 第 2 层 - MQ 异步二次确认 (写操作之后)
@RabbitListener
public void handleItemCacheInvalidate(ItemCacheMessage msg) {
    // 加入 dirty set
    redisService.sAdd("item:cache:dirty", msg.getItemId().toString());
    // 再次删除
    redisService.delete("item:info:" + msg.getItemId());
}

// 第 3 层 - 定时补偿删除 (每 5 分钟)
@Scheduled(fixedDelay = 5 * 60 * 1000)
public void compensateItemCache() {
    Set<String> dirtySet = redisService.sMembers("item:cache:dirty");
    for (String itemId : dirtySet) {
        if (redisService.exists("item:info:" + itemId)) {
            redisService.delete("item:info:" + itemId);  // 补充删除
        }
    }
    redisService.delete("item:cache:dirty");  // 清理
}
```

#### 3.3.3 库存扣减实现

```java
// ItemMapper.java - 使用 @Update 注解直接写 SQL
@Update("UPDATE item SET stock = stock - #{num} WHERE id = #{itemId}")
void updateStockDeduct(@Param("itemId") Long itemId, @Param("num") Integer num);

@Update("UPDATE item SET stock = stock + #{num} WHERE id = #{itemId}")
void updateStockRecover(@Param("itemId") Long itemId, @Param("num") Integer num);

// ItemServiceImpl.java
@Transactional
public void deductStock(List<OrderDetailDTO> details) {
    for (OrderDetailDTO detail : details) {
        int rows = itemMapper.updateStockDeduct(detail.getItemId(), detail.getNum());
        if (rows == 0) throw new BizIllegalException("库存不足！");
    }
}
```

#### 3.3.4 ES 同步触发

```java
// ItemController 中每个写操作后
// 新增 → RabbitTemplate.convertAndSend("search.topic", "search.create", itemDTO)
// 更新 → RabbitTemplate.convertAndSend("search.topic", "search.update", itemDTO)
// 删除 → RabbitTemplate.convertAndSend("search.topic", "search.remove", itemId)
```

#### 3.3.5 个性化推荐实现

```java
// RecommendController
@GetMapping("/recommend")
public RecommendVO recommend(@RequestParam(defaultValue = "home") String scene,
                              @RequestParam(defaultValue = "10") int size,
                              @RequestParam(required = false) Long itemId) {
    // Step 1: 确定召回参数
    List<String> prefCategories = getPreferenceCategories(userId, scene, itemId);

    // Step 2: ES 召回 (通过 Feign 调用 search-service)
    List<ItemDTO> esResults = searchClient.recommend(prefCategories, excludeIds, size);

    // Step 3: MySQL 兜底
    if (esResults.isEmpty() || esResults.size() < size) {
        esResults = itemService.getHotItems(size);  // 热销商品
    }

    // Step 4: 补充 stock/status → 生成推荐标签
    return buildRecommendVO(esResults, prefCategories);
}
```

---

### 3.4 cart-service（购物车服务）

#### 3.4.1 模块结构

```
cart-service/
├── pom.xml
└── src/main/
    ├── java/com/hmall/cart/
    │   ├── CartApplication.java
    │   ├── config/CartProperties.java
    │   ├── controller/CartController.java    # 5 个端点
    │   ├── domain/
    │   │   ├── po/Cart.java                  # version 字段 (时间戳)
    │   │   ├── dto/CartFormDTO, CartSyncMessage
    │   │   └── vo/CartVO.java
    │   ├── mapper/CartMapper.java
    │   ├── mq/
    │   │   ├── CartSyncSender.java           # 异步同步生产者
    │   │   └── CartSyncReceiver.java         # 异步同步消费者
    │   ├── Listener/clearCartListener.java   # 订单创建→清购物车
    │   ├── task/CartSyncCompensationTask.java # 定时补偿任务
    │   └── service/ICartService, impl/CartServiceImpl
    └── resources/
        └── lua/
            ├── add_cart.lua                  # 原子加购
            └── remove_cart.lua               # 原子删除
```

#### 3.4.2 Redis 优先架构实现

```java
// CartServiceImpl.java

// 查询 - 纯 Redis 读取
public List<CartVO> queryMyCarts() {
    Long userId = UserContext.getUser();

    // 1. Redis HGETALL cart:user:{userId} + HGETALL num
    Map<String, String> items = redisService.hgetAll("cart:user:" + userId);
    Map<String, String> nums = redisService.hgetAll("cart:user:" + userId + ":num");

    if (items == null || items.isEmpty()) {
        // 2. Redis 为空 → 冷启动 lazy sync: MySQL → Redis
        ensureCartRedisSynced(userId);
    }

    // 3. 构建 CartVO 列表
    List<CartVO> carts = buildCartVOList(items, nums);

    // 4. Feign → item-service 补充最新价格/状态/库存
    List<Long> itemIds = carts.stream().map(CartVO::getItemId).collect(toList());
    Map<Long, ItemDTO> itemMap = itemClient.queryItemsByIds(itemIds);

    // 5. 填充 newPrice, status, stock
    carts.forEach(cart -> {
        ItemDTO item = itemMap.get(cart.getItemId());
        if (item != null) {
            cart.setNewPrice(item.getPrice());
            cart.setStatus(item.getStatus());
            cart.setStock(item.getStock());
        }
    });

    return carts;
}
```

#### 3.4.3 Lua 原子加购

```lua
-- add_cart.lua
local cartKey = KEYS[1]   -- cart:user:{userId}
local numKey = KEYS[2]    -- cart:user:{userId}:num
local verKey = KEYS[3]    -- cart:user:{userId}:v

local itemId = ARGV[1]
local itemData = ARGV[2]
local ttl = tonumber(ARGV[3])
local maxItems = tonumber(ARGV[4])
local version = ARGV[5]

-- 已存在 → 原子递增数量
local exists = redis.call('HEXISTS', cartKey, itemId)
if exists == 1 then
    redis.call('HINCRBY', numKey, itemId, 1)
else
    -- 不存在 → 检查上限
    local currentCount = redis.call('HLEN', cartKey)
    if currentCount >= maxItems then
        return -1  -- 购物车已满
    end
    -- 新增条目
    redis.call('HSET', cartKey, itemId, itemData)
    redis.call('HSET', numKey, itemId, 1)
end

-- 统一设置版本号和过期时间
redis.call('SET', verKey, version)
redis.call('EXPIRE', cartKey, ttl)
redis.call('EXPIRE', numKey, ttl)
redis.call('EXPIRE', verKey, ttl)

-- 返回当前数量
return redis.call('HGET', numKey, itemId)
```

#### 3.4.4 补偿任务实现

```java
@Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
public void compensateCartSync() {
    // 1. 获取活跃用户
    List<Long> userIds = cartMapper.selectActiveUserIds();

    for (Long userId : userIds) {
        // 2. 比对 Redis version vs MySQL max version
        String redisVer = redisService.get("cart:user:" + userId + ":v");
        Long mysqlMaxVer = cartMapper.selectMaxVersionByUserId(userId);

        // 3. 决定同步方向
        if (redisVersion > mysqlVersion) {
            // Redis → MySQL: 全量覆盖
            syncRedisToMySQL(userId);
        } else if (mysqlVersion > redisVersion) {
            // MySQL → Redis: 回填
            syncMySQLToRedis(userId);
        }
        // Redis 为空 + MySQL 有数据 → MySQL 回填 Redis
    }
}
```

#### 3.4.5 降级实现

```java
// 添加购物车 - Redis 不可用时降级 MySQL
private void addItem2CartMysql(CartFormDTO form) {
    Cart cart = new Cart();
    BeanUtils.copyProperties(form, cart);
    cart.setUserId(UserContext.getUser());
    cart.setNum(1);
    cart.setVersion(System.currentTimeMillis());
    cartMapper.insert(cart);

    // 标记 Redis 缓存待失效
    pendingInvalidationUsers.put(UserContext.getUser(), true);
}
```

---

### 3.5 trade-service（交易服务）

#### 3.5.1 模块结构

```
trade-service/
├── pom.xml
└── src/main/
    ├── java/com/hmall/trade/
    │   ├── TradeApplication.java
    │   ├── config/
    │   │   └── RabbitMQConfig.java          # 延迟消息交换机
    │   ├── controller/
    │   │   ├── OrderController.java         # 9 个端点
    │   │   └── SeckillController.java       # C端4个 + 管理后台10个 = 14个端点
    │   ├── domain/
    │   │   ├── po/Order, OrderDetail, OrderLogistics, LocalMessage,
    │   │   │     SeckillPromotion, SeckillSession, SeckillProductRelation,
    │   │   │     SeckillDailyStock, SeckillOrder
    │   │   ├── dto/OrderFormDTO, SeckillOrderMessage, ...
    │   │   ├── vo/OrderVO, SeckillActivityVO, SeckillProductVO, SeckillResultVO, ...
    │   │   └── query/SeckillPromotionQuery, ...
    │   ├── mapper/OrderMapper, OrderDetailMapper, SeckillPromotionMapper, ...
    │   ├── mq/
    │   │   ├── CartMessageSender.java       # 清购物车消息
    │   │   ├── OrderDelayMessageSender.java # 延迟取消消息
    │   │   ├── paySuccessListener.java      # 支付成功→标记已支付
    │   │   ├── orderDelayMessageListener.java # 延迟取消消费者
    │   │   └── SeckillOrderListener.java    # 秒杀异步下单消费者
    │   ├── task/
    │   │   ├── SeckillPreheatTask.java      # 库存预热 (每分钟)
    │   │   ├── SeckillTimeoutTask.java      # 超时回补 (每5分钟)
    │   │   └── LocalMessageSender.java      # 本地消息重发 (每10秒)
    │   └── service/...impl/
    └── resources/
        ├── lua/seckill_deduct.lua
        └── db/migration/V2__seckill_tables.sql
```

#### 3.5.2 普通订单创建实现

```java
@GlobalTransactional  // Seata AT 分布式事务
public Long createOrder(OrderFormDTO orderFormDTO) {
    Long userId = UserContext.getUser();

    // 1. 查询商品信息
    List<Long> itemIds = orderFormDTO.getDetails().stream()
        .map(OrderDetailDTO::getItemId).collect(toList());
    List<ItemDTO> items = itemClient.queryItemsByIds(itemIds);  // Feign

    // 2. 计算总价
    int totalFee = calculateTotalFee(items, orderFormDTO.getDetails());

    // 3. 写 order 主表 (status=1 未付款)
    Order order = new Order();
    order.setUserId(userId);
    order.setTotalFee(totalFee);
    order.setStatus(1);
    orderMapper.insert(order);

    // 4. 批量写 order_detail
    List<OrderDetail> details = buildOrderDetails(order.getId(), orderFormDTO);
    orderDetailMapper.insertBatch(details);

    // 5. 写本地消息表 (购物车清理)
    LocalMessage cartMsg = new LocalMessage();
    cartMsg.setExchange("trade.topic");
    cartMsg.setRoutingKey("order.create");
    cartMsg.setMessageBody(itemIds);  // 已购商品ID列表
    localMessageMapper.insert(cartMsg);

    // 6. 扣减库存 (Feign → item-service)
    itemClient.deductStock(orderFormDTO.getDetails());  // 可能抛异常回滚

    // 7. 发送30分钟延迟消息 (超时取消)
    rabbitTemplate.convertAndSend("trade.delay.direct", "delay.order", order.getId());

    return order.getId();
}
```

#### 3.5.3 秒杀下单实现

```java
// SeckillServiceImpl.doSeckill
public SeckillResultVO doSeckill(Long relationId, Integer quantity) {
    Long userId = UserContext.getUser();

    // 第 1 层：用户级分布式锁
    boolean locked = redisLockUtil.tryLock("seckill:lock:" + userId, 5, TimeUnit.SECONDS);
    if (!locked) return SeckillResultVO.fail("操作过于频繁");

    try {
        // 第 2 层：Redis Lua 原子预减
        int result = executeSeckillLua(relationId, userId, quantity);
        switch (result) {
            case 1: break;   // 成功
            case 0: return SeckillResultVO.fail("已售罄");
            case -1: return SeckillResultVO.fail("活动未开始");
            case -2: return SeckillResultVO.fail("超出限购");
        }

        // 发送 MQ 异步处理 (SQL 行锁扣减 + 创建订单)
        SeckillOrderMessage msg = new SeckillOrderMessage(/* ... */);
        rabbitTemplate.convertAndSend("seckill.order.queue", msg);

        // 返回 pending 状态，前端轮询获取结果
        return SeckillResultVO.pending();
    } finally {
        redisLockUtil.unlock("seckill:lock:" + userId);
    }
}
```

```lua
-- seckill_deduct.lua (Redis 原子操作)
-- KEYS[1] = seckill:stock:{relationId}   库存
-- KEYS[2] = seckill:limit:{relationId}:{userId}  限购额度
-- ARGV[1] = quantity                     购买数量
-- ARGV[2] = limitNum                     限购数量

local stock = tonumber(redis.call('GET', KEYS[1]) or "0")
if stock <= 0 then return 0 end           -- 已售罄

if stock == nil then return -1 end         -- 未预热

local bought = tonumber(redis.call('HGET', KEYS[2], 'count') or "0")
if bought + tonumber(ARGV[1]) > tonumber(ARGV[2]) then
    return -2                              -- 超限购
end

redis.call('DECRBY', KEYS[1], ARGV[1])     -- 扣库存
redis.call('HINCRBY', KEYS[2], 'count', ARGV[1])  -- 增已购

return 1  -- 成功
```

```java
// 第 3 层：MySQL 行锁扣减 (SeckillOrderListener)
@RabbitListener(queues = "seckill.order.queue")
public void onSeckillOrder(SeckillOrderMessage msg) {
    // SELECT ... FOR UPDATE 行锁
    SeckillDailyStock stock = seckillDailyStockMapper.selectForUpdate(msg.getRelationId());

    if (stock.getStock() >= msg.getQuantity()) {
        // 原子扣减
        seckillDailyStockMapper.deductStock(msg.getRelationId(), msg.getQuantity());

        // 创建订单
        Order order = createSeckillOrder(msg);

        // 写结果缓存 → 前端轮询获取
        redisService.set("seckill:result:" + msg.getUserId() + ":" + msg.getRelationId(),
                         order.getId().toString(), 30, TimeUnit.MINUTES);
    } else {
        // 库存不足 → 回补 Redis
        redisService.incrBy("seckill:stock:" + msg.getRelationId(), msg.getQuantity());
        // 回补限制额度
        redisService.hincrBy("seckill:limit:" + msg.getRelationId() + ":" + msg.getUserId(),
                             "count", -msg.getQuantity());
        // 写失败结果
        redisService.set("seckill:result:" + msg.getUserId() + ":" + msg.getRelationId(),
                         "FAILED", 30, TimeUnit.MINUTES);
    }
}
```

---

### 3.6 pay-service（支付服务）

#### 3.6.1 模块结构

```
pay-service/
├── pom.xml
└── src/main/java/com/hmall/pay/
    ├── PayApplication.java
    ├── controller/PayController.java       # 4 个端点
    ├── domain/
    │   ├── po/PayOrder.java (15个字段), LocalMessage.java
    │   ├── dto/PayApplyDTO, PayOrderFormDTO
    │   └── vo/PayOrderVO.java
    ├── enums/PayChannel, PayStatus, PayType
    ├── mapper/PayOrderMapper, LocalMessageMapper
    ├── service/IPayOrderService, impl/PayOrderServiceImpl
    └── utils/LocalMessageSender.java       # 本地消息重发
```

#### 3.6.2 生成支付单实现

```java
public String applyPayOrder(PayApplyDTO applyDTO) {
    // 1. 幂等性校验
    PayOrder existOrder = payOrderMapper.selectByBizOrderNo(applyDTO.getBizOrderNo());
    if (existOrder != null) {
        if (existOrder.getStatus() == PayStatus.TRADE_SUCCESS.getCode()) {
            throw new BizIllegalException("订单已经支付！");
        }
        // 未支付 → 复用
        return existOrder.getPayOrderNo().toString();
    }

    // 2. 创建支付单
    PayOrder payOrder = new PayOrder();
    payOrder.setPayOrderNo(IdUtil.getSnowflakeNextId());  // 雪花ID
    payOrder.setBizOrderNo(applyDTO.getBizOrderNo());
    payOrder.setAmount(applyDTO.getAmount());
    payOrder.setStatus(PayStatus.WAIT_BUYER_PAY.getCode());  // 1=待支付
    payOrder.setPayOverTime(LocalDateTime.now().plusMinutes(120));  // 120分钟超时
    payOrderMapper.insert(payOrder);

    return payOrder.getPayOrderNo().toString();
}
```

#### 3.6.3 余额支付实现

```java
@GlobalTransactional  // Seata
public void tryPayOrderByBalance(PayOrderFormDTO payOrderFormDTO) {
    // 1. 查询并校验
    PayOrder payOrder = payOrderMapper.selectById(payOrderFormDTO.getId());
    if (payOrder.getStatus() != PayStatus.WAIT_BUYER_PAY.getCode()) {
        throw new BizIllegalException("订单状态不正确");
    }

    // 2. Feign → user-service 扣减余额
    DeductMoneyDTO deductDTO = new DeductMoneyDTO();
    deductDTO.setPw(payOrderFormDTO.getPw());      // 支付密码
    deductDTO.setAmount(payOrder.getAmount());       // 金额(分)
    userClient.deductMoney(deductDTO);

    // 3. 乐观锁更新支付单 (防止并发)
    int rows = payOrderMapper.updateStatus(
        payOrder.getId(),
        PayStatus.TRADE_SUCCESS.getCode(),
        List.of(PayStatus.NOT_COMMIT.getCode(), PayStatus.WAIT_BUYER_PAY.getCode())
    );
    if (rows == 0) throw new BizIllegalException("支付状态已变更");

    // 4. 写入本地消息表 (At-Least-Once)
    LocalMessage msg = new LocalMessage();
    msg.setMessageId(payOrder.getBizOrderNo() + "_pay_success");
    msg.setExchange("pay.direct");
    msg.setRoutingKey("pay.success");
    msg.setMessageBody(payOrder.getBizOrderNo());
    localMessageMapper.insert(msg);
}
```

---

### 3.7 search-service（搜索服务）

#### 3.7.1 模块结构

```
search-service/
├── pom.xml
└── src/main/java/com/hmall/search/
    ├── SearchApplication.java
    ├── config/ElasticsearchConfig.java     # RestHighLevelClient (192.168.100.128:9200)
    ├── controller/SearchController.java    # 4 个端点
    ├── domain/
    │   ├── po/Item.java                    # MySQL item 表映射 (只读)
    │   ├── dto/ItemDoc.java (ES文档), ItemDTO, OrderDetailDTO
    │   ├── vo/CategoryBrandVO.java
    │   └── query/ItemPageQuery.java
    ├── Listener/
    │   ├── SaveItemListener.java           # 消费 search.create
    │   ├── UpdateItemListener.java         # 消费 search.update
    │   └── RemoveItemListener.java         # 消费 search.remove
    ├── mapper/SearchMapper.java
    └── service/ISearchService, impl/SearchServiceImpl
```

#### 3.7.2 ES 搜索实现

```java
// SearchServiceImpl.search
public PageDTO<ItemDTO> search(ItemPageQuery query) {
    // 1. 构建 Bool Query
    BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

    // 关键字匹配 (must，影响评分)
    if (StringUtils.isNotBlank(query.getKey())) {
        boolQuery.must(QueryBuilders.matchQuery("name", query.getKey()));
    }

    // 过滤器 (filter，不影响评分)
    if (StringUtils.isNotBlank(query.getBrand())) {
        boolQuery.filter(QueryBuilders.termQuery("brand", query.getBrand()));
    }
    if (StringUtils.isNotBlank(query.getCategory())) {
        boolQuery.filter(QueryBuilders.termQuery("category", query.getCategory()));
    }
    if (query.getMinPrice() != null || query.getMaxPrice() != null) {
        boolQuery.filter(QueryBuilders.rangeQuery("price")
            .gte(query.getMinPrice()).lte(query.getMaxPrice()));
    }

    // 2. 广告加权 (Function Score Query)
    ScoreFunctionBuilder<?> adWeight = ScoreFunctionBuilders
        .weightFactorFunction(100f);
    FunctionScoreQueryBuilder.FilterFunctionBuilder filterBuilder =
        new FunctionScoreQueryBuilder.FilterFunctionBuilder(
            QueryBuilders.termQuery("isAD", true), adWeight);
    FunctionScoreQueryBuilder fsQuery = QueryBuilders.functionScoreQuery(
        boolQuery, new FunctionScoreQueryBuilder.FilterFunctionBuilder[]{filterBuilder})
        .scoreMode("MULTIPLY")       // 函数分 × 查询分
        .boostMode(CombineFunction.SUM);  // 多函数求和

    // 3. 构建 SearchRequest
    SearchRequest request = new SearchRequest("items");
    SearchSourceBuilder source = new SearchSourceBuilder()
        .query(fsQuery)
        .from((query.getPageNo() - 1) * query.getPageSize())
        .size(query.getPageSize())
        .sort("_score", SortOrder.DESC)
        .sort("update_time", SortOrder.DESC)
        .timeout(new TimeValue(60, TimeUnit.SECONDS));

    request.source(source);

    // 4. 执行搜索
    SearchResponse response = restHighLevelClient.search(request, RequestOptions.DEFAULT);

    // 5. 解析结果
    List<ItemDTO> list = new ArrayList<>();
    for (SearchHit hit : response.getHits()) {
        ItemDoc doc = JSONUtil.toBean(hit.getSourceAsString(), ItemDoc.class);
        ItemDTO dto = new ItemDTO();
        BeanUtils.copyProperties(doc, dto);
        list.add(dto);
    }

    return PageDTO.of(response.getHits().getTotalHits().value, list);
}
```

#### 3.7.3 MQ 索引同步实现

```java
// SaveItemListener - 商品新增 → 创建 ES 文档
@RabbitListener(bindings = @QueueBinding(
    value = @Queue(name = "search.create.queue", durable = "true"),
    exchange = @Exchange(name = "search.topic", type = ExchangeTypes.TOPIC),
    key = "search.create"))
public void listenCreate(ItemDTO itemDTO) {
    ItemDoc itemDoc = BeanUtils.copyProperties(itemDTO, ItemDoc.class);
    IndexRequest request = new IndexRequest("items")
        .id(itemDoc.getId())
        .source(JSONUtil.toJsonStr(itemDoc), XContentType.JSON);
    restHighLevelClient.index(request, RequestOptions.DEFAULT);
}

// UpdateItemListener - 商品更新 → 增量更新 ES 文档
@RabbitListener(bindings = @QueueBinding(
    value = @Queue(name = "search.update.queue", durable = "true"),
    exchange = @Exchange(name = "search.topic", type = ExchangeTypes.TOPIC),
    key = "search.update"))
public void listenUpdate(ItemDTO itemDTO) {
    ItemDoc itemDoc = BeanUtils.copyProperties(itemDTO, ItemDoc.class);
    // 手动构建更新字段 Map (仅非 null 字段)
    Map<String, Object> updateFields = new HashMap<>();
    if (itemDoc.getName() != null) updateFields.put("name", itemDoc.getName());
    if (itemDoc.getPrice() != null) updateFields.put("price", itemDoc.getPrice());
    // ... 逐个字段判断
    UpdateRequest request = new UpdateRequest("items", itemDoc.getId())
        .doc(updateFields)
        .docAsUpsert(true)          // 不存在则创建
        .timeout("10s");
    restHighLevelClient.update(request, RequestOptions.DEFAULT);
}

// RemoveItemListener - 商品删除 → 删除 ES 文档
@RabbitListener(bindings = @QueueBinding(
    value = @Queue(name = "search.remove.queue", durable = "true"),
    exchange = @Exchange(name = "search.topic", type = ExchangeTypes.TOPIC),
    key = "search.remove"))
public void listenRemove(Long itemId) {
    DeleteRequest request = new DeleteRequest("items", itemId.toString());
    restHighLevelClient.delete(request, RequestOptions.DEFAULT);
}
```

---

## 四、配置说明

### 4.1 Nacos 共享配置清单

| Data ID | 内容 |
|---------|------|
| `shared-jdbc.yaml` | MySQL 数据源 URL、用户名密码、HikariCP 连接池、MyBatis Plus 配置 |
| `shared-log.yaml` | 日志级别（默认 INFO，dev 为 DEBUG）和输出格式 |
| `shared-swagger.yaml` | Swagger/Knife4j 标题、描述、版本、扫描包路径 |
| `shared-seata.yaml` | Seata TC 地址、事务组名、AT 模式配置 |
| `shared-rabbitmq.yaml` | RabbitMQ host、port、username、password、virtual-host |

### 4.2 各服务端口与启动参数

| 服务 | 端口 | JVM 参数建议 | 启动类 |
|------|------|-------------|--------|
| hm-gateway | 8080 | — | `GateWayApplication` |
| user-service | 8083 | — | `UserApplication` |
| item-service | 8081 | — | `ItemApplication` |
| cart-service | 8082 | — | `CartApplication` |
| trade-service | 8084 | — | `TradeApplication` |
| pay-service | 8085 | — | `PayApplication` |
| search-service | 8089 | — | `SearchApplication` |

### 4.3 Redis Key 设计汇总

| Key 模式 | 类型 | TTL | 使用者 | 说明 |
|----------|------|-----|--------|------|
| `token:blacklist:{jti}` | String | 动态(token剩余TTL) | gateway + user-service | 登出 Token 黑名单 |
| `sms:code:{phone}` | String | 5 分钟 | user-service | 短信验证码 |
| `lock:deduct:{userId}` | String | 5 秒 | user-service | 余额扣减分布式锁 |
| `item:info:{itemId}` | String (JSON) | 30 分钟 | item-service | 商品详情缓存 |
| `item:cache:dirty` | Set | 15 分钟 | item-service | 待补偿删除的缓存脏数据 |
| `cart:user:{userId}` | Hash | 30 天 | cart-service | 购物车商品元数据 |
| `cart:user:{userId}:num` | Hash | 30 天 | cart-service | 购物车商品数量 |
| `cart:user:{userId}:v` | String | 30 天 | cart-service | 购物车版本号 |
| `seckill:stock:{relationId}` | String | 动态 | trade-service | 秒杀商品 Redis 库存 |
| `seckill:limit:{relationId}:{userId}` | Hash | 动态 | trade-service | 秒杀限购额度 |
| `seckill:lock:{userId}` | String | 5 秒 | trade-service | 秒杀用户锁 |
| `seckill:result:{userId}:{relationId}` | String | 30 分钟 | trade-service | 秒杀结果轮询 |
| `profile:{userId}:categories` | Hash | 30 天 | item-service | 用户偏好类目画像 |
| `profile:{userId}:brands` | Hash | 30 天 | item-service | 用户偏好品牌画像 |
| `ratelimit:{path}:{userId}` | ZSET | 动态 | gateway | 滑动窗口限流 |

---

## 五、关键技术决策与修复记录

### 5.1 决策：购物车双写（Redis + MQ → MySQL）

**决策**：购物车读写走 Redis + MQ 异步落库 MySQL，定时补偿保证最终一致性。

**理由**：购物车是最高频的读写场景（浏览→加购→更新数量→结算），MySQL 的读写在 QPS 上去后面临瓶颈。Redis 的单线程 + Lua 原子操作（<1ms）天然适合处理购物车的并发操作。MQ 异步同步 + 5 分钟补偿任务保证即使 Redis 宕机也能恢复数据。

### 5.2 决策：秒杀三层防超卖

**决策**：Redis Lua 预减 → MQ 削峰 → MySQL FOR UPDATE 行锁兜底。

**理由**：
- 纯 MySQL 行锁在高并发下吞吐量有限（约 500-1000 QPS）
- 纯 Redis 方案如果 Redis 崩溃 → 数据丢失无法恢复
- 三层架构各司其职：Redis 扛瞬时流量 → MQ 平滑写入 → MySQL 保证数据不丢

### 5.3 决策：商品缓存三层一致性保障

**决策**：直接删除 + MQ 二次确认 + 定时补偿删除。

**理由**：
- 直接删除：99% 场景下足够
- MQ 二次确认：防止直接删除失败（网络抖动）
- 定时补偿：防止 MQ 消费失败或丢失（小概率事件）

### 5.4 决策：local_message 本地消息表

**决策**：支付成功和订单创建后，不直接发 MQ，先写本地消息表（同一事务），由定时任务扫描发送。

**理由**：在 Seata AT 事务模式下，MQ 发送操作不在事务管控范围内。如果 DB 写入成功但 MQ 发送失败，下游服务无法收到通知（trade-service 不知道已支付、cart-service 不知道要清理购物车）。本地消息表方案将 MQ 发送变为数据库操作（事务内），确保 At-Least-Once 语义。

### 5.5 修复：Java 11 兼容性 - Stream.toList()

**问题**：`Stream.toList()` 是 Java 16+ API，在 Java 11 项目中编译报错。

**修复**：全部替换为 `.collect(Collectors.toList())`。

### 5.6 修复：hm-common 包扫描

**问题**：部分微服务（尤其是 admin-service）的 `hm-common` Bean 无法被自动扫描到。

**修复**：在启动类添加 `@ComponentScan({"com.hmall.xxx", "com.hmall.common"})` 显式扫描公共模块。

### 5.7 修复：Redis 序列化与 Lua 脚本兼容

**问题**：`RedisTemplate`（Jackson 序列化）写入的值带有 JSON 引号，Lua 脚本中 `tonumber()` 无法解析。

**修复**：使用 `StringRedisTemplate` 执行 Lua 脚本，`RedisTemplate` 用于常规 JSON 缓存操作。双 Template 模式在 hm-common 中统一封装。

---

## 六、部署指引

### 6.1 启动流程

```
1. 启动基础设施
   ├── MySQL 8.0+ (192.168.100.128:3306)
   ├── Redis 6.x+ (192.168.100.128:6379)
   ├── Nacos 2.x (192.168.100.128:8848)
   ├── RabbitMQ 3.x (192.168.100.128:5672)
   ├── Elasticsearch 7.x (192.168.100.128:9200)
   └── Seata Server (192.168.100.128:8091)

2. 创建数据库
   ├── hm-user (user/address 表)
   ├── hm-item (item 表)
   ├── hm-cart (cart 表 + V1__add_cart_version.sql)
   ├── hm-trade (order/order_detail/order_logistics 表 + V2__seckill_tables.sql)
   └── hm-pay (pay_order 表)

3. 构建所有模块
   mvn clean install -DskipTests    # 在 hmall 根目录执行

4. Nacos 配置
   ├── shared-jdbc.yaml (MySQL 连接)
   ├── shared-log.yaml (日志配置)
   ├── shared-swagger.yaml (API 文档)
   ├── shared-seata.yaml (分布式事务)
   ├── shared-rabbitmq.yaml (消息队列)
   └── gateway-routes.json (网关路由)

5. 启动服务（按依赖顺序）
   user-service → item-service → cart-service → trade-service → pay-service → search-service → hm-gateway

6. 验证
   ├── Nacos 注册中心：检查所有服务状态为 UP
   ├── Gateway: POST /users/login → 返回 token
   ├── 商品查询: GET /items/page → 返回分页数据
   ├── 搜索: GET /search/list?key=手机 → 返回 ES 搜索结果
   └── 全链路: 登录 → 加购 → 下单 → 支付
```

### 6.2 默认测试账号

| 字段 | 值 |
|------|-----|
| 用户名 | `test` |
| 密码 | `123456` |

### 6.3 API 文档地址

| 服务 | Swagger 地址 |
|------|-------------|
| hm-gateway | `http://localhost:8080/doc.html` |
| user-service | `http://localhost:8083/doc.html` |
| item-service | `http://localhost:8081/doc.html` |
| cart-service | `http://localhost:8082/doc.html` |
| trade-service | `http://localhost:8084/doc.html` |
| pay-service | `http://localhost:8085/doc.html` |
| search-service | `http://localhost:8089/doc.html` |

---

## 七、已知问题与后续优化

### 7.1 支付渠道单一

**现状**：仅实现余额支付，微信/支付宝支付枚举已定义但未接入。

**影响**：消费者只能使用余额支付，限制了支付方式的灵活性。

**计划**：Phase 2 接入微信 JSAPI 支付和支付宝网页支付。

### 7.2 订单物流信息不完整

**现状**：`order_logistics` 表已定义但 C 端无物流查询接口。

**影响**：用户无法查看订单物流轨迹。

**计划**：后续对接快递鸟/快递100 API。

### 7.3 搜索高亮未实现

**现状**：ES 搜索返回结果无高亮标记（`HighlightField` 已导入但未使用）。

**影响**：搜索结果中关键字未高亮展示。

**计划**：增加 `highlight` 查询并返回高亮片段。

### 7.4 推荐系统冷启动

**现状**：新用户无购买历史时，推荐退化为全局热销商品。

**缓解**：已通过购物车行为补充用户画像（category/brand 偏好），但数据积累需要时间。

---

## 八、与本仓库其他文档的关联

| 文档 | 关系 |
|------|------|
| `docs/hmall_User设计方案文档.md` | **设计文档**：本文档的源头，描述整体架构设计和接口规划 |
| `docs/hmall_User项目说明文档.md` | **项目说明**：面向快速上手的概况和业务逻辑描述 |
| `docs/hmall_Admin相关文档/` | **B 端文档**：管理后台的设计与实现 |
| `docs/redis功能相关文档/` | **Redis 文档**：Redis 功能模块详细说明 |
| `docs/hmall_seckill相关文档/` | **秒杀文档**：秒杀架构专题文档 |
| `docs/hmall_Agent相关文档/` | **Agent 文档**：AI Agent 集成说明 |

---

> **实现完成度**：用户认证、商品浏览搜索、购物车管理、订单创建与支付、秒杀下单等核心 C 端链路全部实现。扩展功能（微信/支付宝支付、物流查询、搜索高亮、推荐冷启动优化）为后续迭代项。
