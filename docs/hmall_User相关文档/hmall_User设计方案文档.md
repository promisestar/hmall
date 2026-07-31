# hmall C 端微服务（User 端）设计文档

> 版本：v1.0
> 日期：2026-07-31
> 参考架构：Spring Cloud 微服务电商系统

---

## 1. 概述

### 1.1 背景与目标

hmall 是一套面向 C 端消费者的 Spring Cloud 微服务商城系统，基于黑马程序员教学项目演变而来。系统包含网关（hm-gateway）、商品（item-service）、购物车（cart-service）、用户（user-service）、交易（trade-service）、支付（pay-service）、搜索（search-service）共七个子服务，外加公共模块（hm-common、hm-api）和开发期单体服务（hm-service）。

本设计文档系统性地梳理 C 端微服务的整体架构、各服务职责、核心流程、数据设计与技术选型，为项目理解和后续迭代提供完整参考。

### 1.2 设计原则

| 原则 | 说明 |
|------|------|
| **微服务自治** | 每个服务拥有独立数据库，通过 Feign/MQ 协作，不跨库直连 |
| **高性能** | Redis 作为主缓存层，Elasticsearch 支撑搜索，RabbitMQ 异步解耦 |
| **最终一致性** | 核心交易链路（下单扣库存）采用 Seata 分布式事务；非关键链路（缓存同步、ES 索引同步）采用本地消息表/MQ 最终一致性 |
| **高可用** | 关键流程（购物车读写、秒杀下单）Redis 不可用时自动降级 MySQL；限流保护网关 |
| **渐进扩展** | 预留支付宝/微信支付渠道、用户画像推荐等扩展点 |

### 1.3 服务全景

| 服务 | 端口 | 数据库 | 核心职责 |
|------|------|--------|---------|
| **hm-gateway** | 8080 | — | 统一入口、JWT 认证、限流、路由转发 |
| **user-service** | 8083 | hm-user | 用户注册/登录、JWT 签发、地址管理、余额管理 |
| **item-service** | 8081 | hm-item | 商品 CRUD、库存管理、ES 同步触发、个性化推荐 |
| **cart-service** | 8082 | hm-cart | 购物车增删改查（Redis 为主、MySQL 异步落库） |
| **trade-service** | 8084 | hm-trade | 订单创建与管理、秒杀活动/下单 |
| **pay-service** | 8085 | hm-pay | 支付单管理、余额支付 |
| **search-service** | 8089 | hm-item（读） | 商品全文搜索、聚合过滤、推荐召回 |

---

## 2. 整体架构

### 2.1 系统拓扑

```
                          ┌─────────────────────────────────────────────────┐
                          │              前端（Vue 3 消费端商城）              │
                          └──────────────────────┬──────────────────────────┘
                                                 │ /api/**
                          ┌──────────────────────▼──────────────────────────┐
                          │              hm-gateway (:8080)                  │
                          │  ┌────────────────────────────────────────────┐  │
                          │  │ AuthGlobalFilter                             │  │
                          │  │  - 白名单放行: /search, /items, /users/login │  │
                          │  │  - JWT 校验: 解析 user-id → 透传下游          │  │
                          │  │  - Token 续期: 冷却窗口内自动刷新              │  │
                          │  │  - 黑名单检查: 登出即失效                      │  │
                          │  └────────────────────────────────────────────┘  │
                          │  RateLimitFilter (滑动窗口限流: /seckill 5s/1次)  │
                          │  DynamicRouteLoader (Nacos 路由热更新)            │
                          └──┬────┬──────┬──────┬──────┬──────┬──────────────┘
                             │    │      │      │      │      │
              ┌──────────────▼┐ ┌─▼──────▼──┐ ┌──▼────────▼──┐ ┌────────▼──┐
              │  user-service │ │item-service│ │ cart-service  │ │trade-svc  │
              │    (:8083)    │ │  (:8081)  │ │   (:8082)    │ │ (:8084)   │
              │               │ │           │ │               │ │           │
              │ • 登录/注册    │ │ • 商品CRUD │ │ • Redis主存储  │ │ • 订单CRUD │
              │ • JWT签发     │ │ • 库存管理  │ │ • Lua原子操作 │ │ • 秒杀下单  │
              │ • 地址管理    │ │ • ES同步触发│ │ • MQ异步落库  │ │ • 库存预热  │
              │ • 余额管理    │ │ • 个性化推荐│ │ • 补偿任务    │ │ • 超时取消  │
              │ • 分布式锁    │ │ • 缓存三层  │ │ • 用户画像    │ │ • 本地消息表│
              └───┬───────────┘ └────┬───────┘ └──────┬───────┘ └─────┬──────┘
                  │                  │                  │               │
        ┌─────────▼──────┐  ┌───────▼──────┐  ┌───────▼──────┐  ┌────▼──────┐
        │  pay-service   │  │search-service│  │    Redis      │  │  RabbitMQ  │
        │   (:8085)      │  │   (:8089)    │  │               │  │           │
        │                │  │              │  │ • 购物车缓存   │  │ • 异步落库 │
        │ • 余额支付      │  │ • 全文搜索    │  │ • Token黑名单  │  │ • ES同步   │
        │ • 本地消息表    │  │ • 聚合过滤    │  │ • 秒杀库存     │  │ • 延迟取消 │
        │ • 支付状态同步  │  │ • 推荐召回    │  │ • 验证码       │  │ • 支付通知 │
        └────────────────┘  └──────────────┘  │ • 分布式锁     │  │ • 清理购物车│
                                              └───────────────┘  └────────────┘
                          ┌──────────────────┐
                          │      Nacos       │
                          │  (注册发现+配置)   │
                          └──────────────────┘
                          ┌──────────────────┐
                          │   Elasticsearch  │
                          │   (商品搜索索引)   │
                          └──────────────────┘
```

### 2.2 技术栈

| 类别 | 技术选型 | 说明 |
|------|---------|------|
| 基础框架 | Spring Boot 2.7.12 | 统一版本，hmall 父 POM 管理 |
| 微服务 | Spring Cloud 2021.0.3 + Spring Cloud Alibaba 2021.0.4.0 | Nacos 注册发现 + 配置中心 |
| 网关 | Spring Cloud Gateway | 统一入口，动态路由 |
| ORM | MyBatis Plus 3.4.3 | 通用 CRUD + 自定义 SQL |
| 认证 | JWT (RSA-256, Hutool JWT) | 非对称签名，hmall.jks 密钥库 |
| 缓存 | Redis (Lettuce 连接池) | 购物车、秒杀库存、Token 黑名单、验证码 |
| 搜索引擎 | Elasticsearch (RestHighLevelClient) | 商品全文搜索 |
| 消息队列 | RabbitMQ (Spring AMQP) | 异步解耦、延迟消息、索引同步 |
| 远程调用 | OpenFeign + OkHttp + LoadBalancer | 声明式 HTTP 客户端 |
| 分布式事务 | Seata (AT 模式) | 下单→扣库存→创建支付单 |
| 流量控制 | Sentinel | Feign 熔断降级 |
| API 文档 | Knife4j / Swagger | 各服务独立文档 |
| 密码加密 | BCrypt (spring-security-crypto) | 用户密码 + 支付密码哈希 |

### 2.3 服务间通信矩阵

| 调用方 | 被调用方 | 方式 | 场景 |
|--------|---------|------|------|
| 前端→全部 | hm-gateway | HTTP | 所有请求统一入口 |
| trade-service | item-service | Feign | 查询商品信息、扣减/恢复库存 |
| trade-service | cart-service | MQ (order.create) | 下单后清理购物车 |
| trade-service | pay-service | Feign | 查询支付流水 |
| trade-service | 自身 | MQ (延迟30min) | 超时未支付关单 |
| trade-service | 自身 | MQ (seckill.order) | 秒杀异步下单 |
| cart-service | item-service | Feign | 补充商品最新价格/状态 |
| cart-service | 自身 | MQ (cart.sync) | Redis→MySQL 异步同步 |
| pay-service | user-service | Feign | 余额扣减 |
| pay-service | trade-service | MQ (pay.success) | 支付成功通知 |
| item-service | search-service | MQ (search.create/update/remove) | 商品变更同步 ES |
| item-service | trade-service | Feign | 推荐场景查询已购商品 |
| item-service | search-service | Feign | 推荐商品 ES 召回 |

---

## 3. 网关设计（hm-gateway）

### 3.1 核心职责

hm-gateway 是整个系统的唯一流量入口，承担以下职责：

- **统一认证**：在网关层集中校验 JWT Token，未登录请求直接拦截
- **路由转发**：根据 Nacos 动态路由配置，将请求转发到不同微服务
- **限流保护**：基于 Redis 滑动窗口算法（Lua 脚本），对高并发接口（秒杀）限流
- **Token 续期**：在冷却窗口（15 分钟）外自动刷新即将过期的 Token
- **用户信息透传**：将解析出的 userId 写入 HTTP 头 `user-info`，下游服务通过 `UserContext` 获取

### 3.2 过滤器链

| Order | 过滤器 | 职责 |
|-------|--------|------|
| 0 | AuthGlobalFilter | JWT 认证、黑名单检查、Token 续期、用户信息透传 |
| 1 | RateLimitFilter | 滑动窗口限流（依赖 AuthGlobalFilter 设置的 user-info 头） |

### 3.3 认证白名单

| 路径模式 | 说明 |
|----------|------|
| `/search/**` | 搜索服务 — 公开访问 |
| `/users/login` | 用户密码登录 |
| `/users/login/code` | 验证码登录 |
| `/users/code` | 发送验证码 |
| `/items/**` | 商品浏览 — 公开访问 |
| `/hi` | 健康检查 |
| `/admin/**` | 管理后台（由 admin-service 独立认证） |
| `/seckill/activities` | 秒杀活动查询（公开） |
| `/seckill/products/**` | 秒杀商品详情（公开） |

### 3.4 限流规则

| 路径模式 | 窗口大小 | 最大请求数 | 说明 |
|----------|----------|------------|------|
| `/seckill/**` | 5 秒 | 1 次 | 防止秒杀接口被刷 |

### 3.5 关键设计决策

**Fail-Open 降级策略**：当 Redis 不可用时，AuthGlobalFilter 和 RateLimitFilter 均采用 fail-open（放行）策略，保证可用性优先。Token 黑名单检查降级跳过，限流检查降级放行，依赖下游服务 MQ 限流和 MySQL 行锁兜底。

**Token 冷却窗口续期**：不是每次请求都刷新 Token，而是设置 15 分钟冷却窗口。只有距上次签发超过 15 分钟后才下发新 Token，防止高频刷新带来的性能开销和安全风险。

**动态路由热更新**：路由配置存储在 Nacos Config 的 `gateway-routes.json` 中，通过 `DynamicRouteLoader` 监听配置变更，实时生效，无需重启网关。

---

## 4. 用户服务设计（user-service）

### 4.1 核心职责

- **用户认证**：支持密码登录和验证码登录两种方式
- **JWT 签发**：使用 RSA-256 非对称签名生成 Token（有效期 30 分钟）
- **账户管理**：用户信息管理、地址管理（CRUD + 默认地址）
- **余额管理**：通过分布式锁（Redis）保护余额扣减的并发安全
- **Token 失效**：登出时将 JTI 加入 Redis 黑名单

### 4.2 认证流程

```
密码登录:
  POST /users/login → 查 user 表 → BCrypt 校验密码 → 签发 JWT → 返回 UserLoginVO

验证码登录:
  POST /users/code → 生成6位数字 → 存入 Redis (sms:code:{phone}, TTL 5min)
  POST /users/login/code → 校验 Redis 验证码 → 删除验证码 → 签发 JWT

登出:
  POST /users/logout → 提取 JTI → 写入 Redis 黑名单 (TTL=剩余有效期)
```

### 4.3 数据库设计

**hm-user 库**，包含 2 张表：

| 表名 | 说明 | 关键字段 |
|------|------|---------|
| `user` | 用户表 | id, username, password(BCrypt), phone, status(NORMAL/FROZEN), balance(分) |
| `address` | 收货地址表 | id, user_id, province, city, town, mobile, street, contact, is_default |

---

## 5. 商品服务设计（item-service）

### 5.1 核心职责

- **商品 CRUD**：商品的增删改查和状态管理（上下架/删除）
- **库存管理**：提供库存扣减/恢复接口（被 trade-service 调用）
- **缓存策略**：商品详情读 Redis → miss 则读 MySQL → 回写 Redis
- **ES 同步触发**：商品变更时通过 RabbitMQ 通知 search-service 更新索引
- **个性化推荐**：聚合用户偏好 → 调用 search-service 的 ES 召回 → 返回推荐商品

### 5.2 缓存一致性三层保障

| 层级 | 时机 | 机制 |
|------|------|------|
| 第 1 层 | 写操作当时 | Controller 层直接删除 Redis 缓存 `item:info:{id}` |
| 第 2 层 | 写操作之后 | 发送 MQ 消息 `ItemCacheMessage`，异步二次确认删除 |
| 第 3 层 | 每 5 分钟 | `ItemCacheCompensationTask` 定时遍历 dirty set，补充删除遗漏缓存 |

### 5.3 商品查询缓存策略

```
读: 先 Redis (item:info:{id}, TTL 30min)
     → miss → MySQL → SET NX EX 回写 Redis
     → Redis 异常 → 降级 MySQL

写: 删除 Redis → MQ 通知二次删除 → 定时补偿删除
```

### 5.4 个性化推荐管线

```
Step 1 - 确定召回参数:
  有 userId → 读 Redis 画像 (profile:{userId}:categories/brands Hash)
    miss → Feign 调 trade-service 查已购商品 → MySQL 补全 category/brand → 加权聚合 Top3
  detail 场景 → 取种子商品类目

Step 2 - ES 召回:
  Feign 调 search-service → category 过滤 + 排除已购 → 按销量降序

Step 3 - MySQL 兜底:
  ES 无结果 → MySQL 热销商品 (status=1, ORDER BY sold DESC)
```

### 5.5 数据库设计

**hm-item 库**，仅 1 张核心表：

| 表名 | 说明 | 关键字段 |
|------|------|---------|
| `item` | 商品表 | id, name, price(分), stock, image, category, brand, spec, sold, commentCount, isAD, status(1正常/2下架/3删除) |

---

## 6. 购物车服务设计（cart-service）

### 6.1 核心职责

- **购物车增删改查**：添加商品、更新数量、删除商品、查询列表
- **双写架构**：Redis 作为主存储（低延迟），MySQL 作为持久化存储
- **异步同步**：通过 RabbitMQ 将 Redis 变更异步同步到 MySQL
- **定时补偿**：通过 `CartSyncCompensationTask` 每 5 分钟比对版本号，修复不一致
- **用户画像**：加购行为写入 Redis 用户画像（category/brand/price/stats）

### 6.2 Redis 数据结构

| Key 模式 | 类型 | 说明 |
|----------|------|------|
| `cart:user:{userId}` | Hash | 商品元数据（field=itemId, value=JSON） |
| `cart:user:{userId}:num` | Hash | 商品数量（field=itemId, value=数量） |
| `cart:user:{userId}:v` | String | 全局版本号（时间戳），用于补偿比对 |

所有 Key 的 TTL 为 30 天。

### 6.3 Lua 原子操作

**add_cart.lua**：原子加购
- 已存在 → HINCRBY num +1
- 不存在 → 检查 HLEN >= maxItems → HSET 新条目
- 统一 SET version + EXPIRE

**remove_cart.lua**：原子删除
- 遍历 itemId 列表，同时 HDEL 两个 Hash 的对应 field

### 6.4 降级与容错

| 场景 | 策略 |
|------|------|
| Redis 不可用（加购） | 降级纯 MySQL 写入 |
| Redis 不可用（查询） | 降级纯 MySQL 查询 |
| Redis 不可用（删除） | MySQL 同步删除 + pendingInvalidationUsers 标记 |
| MQ 发送失败 | 不阻断主流程，补偿任务兜底 |
| MQ 消费失败 | 不抛异常，补偿任务兜底 |

### 6.5 版本号驱动的一致性

通过 `version` 字段（时间戳）比对 Redis 和 MySQL：
- Redis version > MySQL max version → Redis 覆盖 MySQL
- MySQL max version > Redis version → MySQL 回填 Redis
- Redis 为空 + MySQL 有数据 → MySQL 回填 Redis

### 6.6 数据库设计

**hm-cart 库**，仅 1 张核心表：

| 表名 | 说明 | 关键字段 |
|------|------|---------|
| `cart` | 购物车表 | id, user_id, item_id, num, name, spec, price(分), image, version(时间戳) |

联合索引：`idx_cart_user_version(user_id, version)`

---

## 7. 交易服务设计（trade-service）

### 7.1 核心职责

- **普通订单管理**：订单创建、查询、支付标记、发货、关闭
- **秒杀业务**：秒杀活动/场次/商品的 CRUD、高并发秒杀下单、库存预热、超时回补
- **延迟取消**：订单创建后发送 30 分钟延迟消息，超时未付自动取消
- **本地消息表**：保证购物车清理等非关键操作的最终一致性

### 7.2 普通订单创建流程

```
@GlobalTransactional (Seata AT 分布式事务):
  1. Feign → item-service 查询商品信息
  2. 计算总价
  3. INSERT order (status=1 未付款)
  4. INSERT order_detail × N
  5. INSERT t_local_message (购物车清理消息，最终一致性)
  6. Feign → item-service 扣减库存
  7. MQ → 发送 30 分钟延迟消息 (超时取消用)
  8. 返回 orderId
```

### 7.3 订单状态机

```
创建 → 1(未付款)
  │
  ├── 支付成功 → 2(已付款,未发货)
  │     │
  │     └── 发货 → 3(已发货,未确认)
  │           │
  │           └── 确认收货 → 4(交易成功)
  │                 │
  │                 └── 已评价 → 6(交易结束)
  │
  └── 超时30分钟 / 管理关闭 → 5(已关闭)
       └── 恢复库存
```

### 7.4 秒杀下单三层防超卖架构

```
第一层 - 用户级分布式锁:
  RedisLockUtil.tryLock(userId, 5s) → 防止重复提交

第二层 - Redis Lua 原子预减:
  seckill_deduct.lua 原子操作:
    检查限购额度 → 扣减 Redis 库存 → 发送 MQ 消息
    返回: 1=成功, 0=已售罄, -1=未初始化, -2=超限购
  前端轮询 GET /seckill/result/{relationId} 获取结果

第三层 - MySQL 行锁扣减 (SeckillOrderListener):
  SELECT ... FOR UPDATE 锁定 seckill_daily_stock
  检查库存 → UPDATE WHERE stock >= quantity
  创建 order + order_detail + seckill_order
  设置 Redis 结果 key → 前端轮询获取
```

### 7.5 秒杀库存预热

`SeckillPreheatTask` 每分钟扫描未来 5 分钟内开始的场次：

```
预热逻辑:
  1. 从 MySQL seckill_product_relation 读取秒杀商品库存
  2. 写入 Redis seckill:stock:{relationId} (String)
  3. 初始化限制额度 seckill:limit:{relationId}:{userId} (Hash)
  4. 标记预热状态
```

超时回补：`SeckillTimeoutTask` 每 5 分钟扫描超时 30 分钟的秒杀订单，自动关单并回补 Redis 和 MySQL 库存。

### 7.6 数据库设计

**hm-trade 库**，包含 8 张核心表：

| 表名 | 说明 |
|------|------|
| `order` | 订单主表 (id, total_fee, payment_type, user_id, status) |
| `order_detail` | 订单详情表 (order_id, item_id, num, name, spec, price, image) |
| `order_logistics` | 订单物流表 (order_id, logistics_number, contact, mobile, address) |
| `t_local_message` | 本地消息表 (message_id, exchange, routing_key, message_body, status) |
| `seckill_promotion` | 秒杀活动表 (title, start_date, end_date, status) |
| `seckill_session` | 秒杀场次表 (promotion_id, name, start_time, end_time) |
| `seckill_product_relation` | 秒杀商品关联表 (session_id, product_id, seckill_price, stock, limit_num) |
| `seckill_daily_stock` | 每日库存快照 (relation_id, batch_date, stock, sold) — 防超卖行锁目标 |
| `seckill_order` | 秒杀订单关联表 (order_id, relation_id, user_id, quantity, status) |

---

## 8. 支付服务设计（pay-service）

### 8.1 核心职责

- **支付单管理**：生成支付单、查询支付单、按业务订单号查询
- **余额支付**：通过 Feign 调用 user-service 扣减余额（需支付密码）
- **支付状态同步**：支付成功后通过本地消息表 + MQ 通知 trade-service

### 8.2 支付单状态机

| 状态值 | 含义 | 说明 |
|--------|------|------|
| 0 | NOT_COMMIT | 未提交 |
| 1 | WAIT_BUYER_PAY | 待支付 |
| 2 | CLOSED | 已关闭 |
| 3 | TRADE_SUCCESS | 支付成功 |

### 8.3 余额支付流程

```
@GlobalTransactional (Seata):
  1. 查询支付单，校验状态=1 (WAIT_BUYER_PAY)
  2. Feign → user-service 扣减余额 (需支付密码 pw + 金额 amount)
  3. 乐观锁更新支付单状态: status IN (0,1) → 3
  4. INSERT t_local_message (messageId=orderNo_pay_success, exchange=pay.direct, routingKey=pay.success)
  5. LocalMessageSender 定时扫描 status=0 的消息 → 发送到 MQ → trade-service 标记订单已支付
```

### 8.4 支付渠道预留

| 渠道 | 枚举值 | 当前状态 |
|------|--------|---------|
| 微信支付 | `wxPay` | 枚举已定义，未实现 |
| 支付宝 | `aliPay` | 枚举已定义，未实现 |
| 余额支付 | `balance` | **已实现** |

Controller 层当前硬编码校验仅允许 `payType=5`（余额），未来接入其他渠道只需移除限制并实现渠道逻辑。

### 8.5 数据库设计

**hm-pay 库**，包含 2 张核心表：

| 表名 | 说明 |
|------|------|
| `pay_order` | 支付单表 (id(雪花ID), biz_order_no, pay_order_no, amount(分), status, pay_channel_code, pay_type) |
| `t_local_message` | 本地消息表 (message_id, exchange, routing_key, message_body, status, try_count) |

---

## 9. 搜索服务设计（search-service）

### 9.1 核心职责

- **商品全文搜索**：基于 Elasticsearch 提供商品名称的全文检索
- **多维度过滤**：品牌、分类、价格范围的精确过滤
- **聚合统计**：返回当前搜索条件下的品牌分布和分类分布
- **推荐商品召回**：为 item-service 推荐引擎提供基于 ES 的商品召回

### 9.2 ES 索引同步机制

```
item-service 商品变更 → RabbitMQ Topic Exchange (search.topic) →
  ├── search.create → search.create.queue → SaveItemListener → createDocument()
  ├── search.update → search.update.queue → UpdateItemListener → updateDocument()
  └── search.remove → search.remove.queue → RemoveItemListener → removeDocumentById()
```

### 9.3 ES 查询构建

**搜索查询**：
```
Bool Query:
  must: matchQuery("name", key)        -- 全文匹配
  filter:
    termQuery("brand", brand)           -- 品牌精确匹配
    termQuery("category", category)     -- 分类精确匹配
    rangeQuery("price").gte().lte()     -- 价格范围
  Function Score Query:
    weightFactorFunction(100f)          -- isAD=true 广告加权 100 分
    合并模式: MULTIPLY
排序: _score DESC → update_time DESC
```

**聚合过滤**：
```
Bool Query (同上，但 size=0 不返回文档)
Terms Aggregation:
  brandAgg: field("brand")
  categoryAgg: field("category")
```

**推荐召回**：
```
Bool Query:
  filter: termsQuery("category", categories)
  mustNot: termsQuery("_id", excludeIds)
排序: sold DESC
```

### 9.4 ES 索引映射

| 索引名 | 字段 | ES 类型 | 说明 |
|--------|------|---------|------|
| `items` | `id` | keyword | 商品ID (文档 _id) |
| `items` | `name` | text (ik_smart) | 商品名称，IK 中文分词 |
| `items` | `price` | integer | 价格（分） |
| `items` | `category` | keyword | 类目，精确匹配 |
| `items` | `brand` | keyword | 品牌，精确匹配 |
| `items` | `sold` | integer | 销量，排序用 |
| `items` | `isAD` | boolean | 是否广告，加权用 |
| `items` | `update_time` | date | 更新时间，排序用 |

---

## 10. 公共模块设计

### 10.1 hm-common（通用基础模块）

| 组件 | 说明 |
|------|------|
| `R<T>` | 统一响应体（code, msg, data） |
| `PageDTO<T>` / `PageQuery` | 分页请求/响应 |
| `UserContext` | ThreadLocal 获取当前用户 ID（由 Gateway 透传） |
| `RedisService` | Redis 操作封装（Jackson 序列化） |
| `StringRedisTemplate` / `RedisTemplate` | 双 Template：StringTemplate 用于 Lua 脚本，RedisTemplate 用于缓存 |
| `LuaScriptLoader` | Lua 脚本加载工具 |
| `RateLimitUtil` | 滑动窗口限流（Redis ZSET + Lua） |
| `RedisLockUtil` | Redis 分布式锁 |
| 异常体系 | `BizIllegalException`、`DbException` 等业务异常 |
| Web 工具 | `WebUtils`、`MvcConfig` |

### 10.2 hm-api（Feign 接口 + 跨服务 DTO）

| 组件 | 说明 |
|------|------|
| `ItemClient` | 商品服务 Feign 接口（queryItemsByIds, deductStock, recoverStock 等） |
| `UserClient` | 用户服务 Feign 接口（deductMoney） |
| `TradeClient` | 交易服务 Feign 接口（queryPurchasedItems） |
| `CartClient` | 购物车服务 Feign 接口（deleteCartItemByIds） |
| `PayClient` | 支付服务 Feign 接口（queryPayOrderByBizOrderNo） |
| `SearchClient` | 搜索服务 Feign 接口（recommend） |
| `DefaultFeignConfig` | 统一 Feign 配置（请求拦截器传递 user-info） |
| 跨服务 DTO | `PayOrderDTO`、`OrderDetailDTO`、`DeductMoneyDTO` 等 |

---

## 11. 消息队列设计

### 11.1 全局 Topic/Queue 拓扑

| Exchange | Queue | Routing Key | 消费者 | 消息体 | 说明 |
|----------|-------|-------------|--------|--------|------|
| `search.topic` | `search.create.queue` | `search.create` | search-service | `ItemDTO` | 商品新增→ES 索引 |
| `search.topic` | `search.update.queue` | `search.update` | search-service | `ItemDTO` | 商品更新→ES 索引 |
| `search.topic` | `search.remove.queue` | `search.remove` | search-service | `Long` (id) | 商品删除→ES 索引 |
| `cart.sync.topic` | `cart.sync.queue` | `cart.sync` | cart-service | `CartSyncMessage` | Redis→MySQL 同步 |
| `trade.topic` | `cart.clear.queue` | `order.create` | cart-service | `List<Long>` | 下单后清理购物车 |
| `trade.topic` | `trade.pay.success.queue` | `pay.success` | trade-service | `Long` (orderId) | 支付成功→标记已支付 |
| `pay.direct` | `pay.success.queue` | `pay.success` | trade-service | `Long` (orderId) | 支付服务→支付成功通知 |
| `trade.delay.direct` | `trade.delay.order.queue` | `delay.order` | trade-service | `Long` (orderId) | 30分钟延迟取消 |
| `seckill.order.queue` | (`default` 绑定) | `seckill.order` | trade-service | `SeckillOrderMessage` | 秒杀异步下单 |
| `item.cache.topic` | `item.cache.invalidate.queue` | `item.cache.invalidate` | item-service | `ItemCacheMessage` | 缓存二次确认删除 |

### 11.2 本地消息表模式

pay-service 和 trade-service 均使用本地消息表 `t_local_message` 保证消息的最终一致性：

```
写 DB 时同时写 t_local_message (同一事务)
  → LocalMessageSender 每 10s 扫描 status=0 的消息
    → 发送 RabbitMQ
      → 成功: status=1
      → 失败: tryCount++, 超过 5 次 status=2 (永久失败)
```

---

## 12. 安全设计

### 12.1 认证与授权

| 层级 | 机制 | 说明 |
|------|------|------|
| 网关层 | JWT 校验 | hm-gateway 对非白名单路径校验 JWT，解析 userId 透传 |
| 服务层 | UserContext | ThreadLocal 获取当前用户 ID，服务内通过它校验数据归属 |
| 地址管理 | 归属权校验 | 每次地址操作都会校验 `address.userId == currentUserId` |

### 12.2 密码安全

- 用户登录密码使用 **BCrypt** 加盐哈希存储
- 支付密码使用 **BCrypt** 加盐哈希存储（同算法，用于余额扣减鉴权）
- 余额操作需二次密码验证

### 12.3 Token 安全

- JWT 使用 **RSA-256 非对称签名**（私钥签发、公钥验签），密钥存储在 `hmall.jks`
- Token 有效期 **30 分钟**，15 分钟冷却窗口续期
- 登出时 JTI 加入 Redis 黑名单（TTL = Token 剩余有效期）

### 12.4 秒杀防刷

| 层 | 机制 |
|----|------|
| 网关限流 | Redis 滑动窗口：`/seckill/**` 每人 5 秒 1 次 |
| 用户锁 | Redis 分布式锁：`seckill:lock:{userId}` 阻止重复提交 |
| 限购 | Redis Hash：`seckill:limit:{relationId}:{userId}` 限制每人购买数量 |
| 行锁 | MySQL SELECT ... FOR UPDATE 兜底防止超卖 |

---

## 13. 配置体系

### 13.1 Nacos 共享配置

所有微服务通过 `bootstrap.yml` 引用以下 Nacos 共享配置：

| Data ID | 内容 | 使用者 |
|---------|------|--------|
| `shared-jdbc.yaml` | MySQL 数据源、MyBatis Plus 配置 | 全部数据库服务 |
| `shared-log.yaml` | 日志级别和格式 | 全部服务 |
| `shared-swagger.yaml` | Swagger/Knife4j 通用配置 | 全部服务 |
| `shared-seata.yaml` | Seata 分布式事务配置 | 涉及分布式事务的服务 |
| `shared-rabbitmq.yaml` | RabbitMQ 连接配置 | 使用 MQ 的服务 |

### 13.2 各服务独有配置

| 配置项 | user-service | item-service | cart-service | trade-service | pay-service | search-service |
|--------|-------------|-------------|-------------|-------------|-------------|---------------|
| 端口 | 8083 | 8081 | 8082 | 8084 | 8085 | 8089 |
| 数据库 | hm-user | hm-item | hm-cart | hm-trade | hm-pay | hm-item (只读) |
| 独立密钥库 | hmall.jks | — | — | — | — | — |
| 独立组件 | — | — | Lua 脚本加载 | Seckill Lua 脚本 | — | ES RestHighLevelClient |

### 13.3 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `NACOS_ADDR` | `192.168.100.128:8848` | Nacos 地址 |
| `hm.db.host` | `192.168.100.128` | MySQL 地址 |
| `hm.db.password` | `123` | MySQL 密码 |
| `REDIS_HOST` | `192.168.100.128` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | `123456` | Redis 密码 |
| `JWT_KEYSTORE_PASSWORD` | `hmall123` | JKS 密钥库密码 |

---

## 14. 关键技术决策

### 14.1 为什么购物车主存储用 Redis 而不是 MySQL？

购物车是高频读写场景（用户每次浏览都可能操作购物车），MySQL 作为关系型数据库，在读多写多场景下性能瓶颈明显。Redis 作为内存数据库，读写延迟在毫秒级，配合 Lua 脚本（原子操作）可以安全地处理并发加购。MySQL 通过 MQ 异步同步 + 补偿任务保证最终一致性，不参与主链路，对用户体验零影响。

### 14.2 为什么秒杀下单异步化？

秒杀场景下如果同步写入 MySQL（下单 → 扣库存 → 创建订单），数据库写入平均 10-50ms，假设 1000 QPS 的并发量，MySQL 连接池很快耗尽。采用异步架构：Redis Lua 预减（<1ms）→ MQ 削峰 → MySQL 消费者逐条处理，将峰值流量削平成稳定吞吐。

### 14.3 为什么商品搜索用 Elasticsearch 而不是 MySQL LIKE？

MySQL `LIKE '%keyword%'` 无法使用索引，对百万级商品表意味着全表扫描。Elasticsearch 的倒排索引 + IK 中文分词能力，可以在毫秒级完成中文全文搜索。此外 ES 的聚合查询（品牌分布、分类统计）比 MySQL 的 GROUP BY + COUNT 在数据量大时效率高 1-2 个数量级。

### 14.4 为什么支付服务使用本地消息表而不是直接发 MQ？

如果在支付成功的同一事务中直接发送 MQ，MQ 发送失败会导致数据不一致（DB 已更新但 MQ 未发出）。本地消息表方案：DB 更新 + 消息插入在同一个事务中（原子操作），异步定时任务扫描未发送消息并重试，保证支付成功通知一定送达 trade-service。

---

## 15. 数据库全景

| 数据库 | 所属服务 | 核心表 | 说明 |
|--------|---------|--------|------|
| `hm-user` | user-service | user, address | 用户账号 + 收货地址 |
| `hm-item` | item-service, search-service(只读) | item | 商品核心表 |
| `hm-cart` | cart-service | cart | 购物车持久化 |
| `hm-trade` | trade-service | order, order_detail, order_logistics, t_local_message, seckill_promotion, seckill_session, seckill_product_relation, seckill_daily_stock, seckill_order | 订单 + 秒杀 |
| `hm-pay` | pay-service | pay_order, t_local_message | 支付单 |
| `hm-admin` | admin-service | admin_user, role, menu, resource, resource_category, *_rel | RBAC 管理 |

---

## 附录 A：服务间调用关系图

```
                             Frontend (Vue 3)
                                  │
                           hm-gateway (:8080)
                            /    |    |    |    |    \
                           /     |    |    |    |     \
              user-service  item-service  cart-service  trade-service  search-service
              (:8083)       (:8081)       (:8082)       (:8084)        (:8089)
                  │             │  \         /  │         /  \              │
                  │             │   \       /   │        /    \             │
                  │             │    \     /    │       /      \            │
                  │             │  RabbitMQ     │  RabbitMQ    │           │
                  │             │               │              │           │
                  │             │        cart-service    pay-service       │
                  │             │        (clear cart)    (:8085)           │
                  │             │                            │             │
                  │             │                            │             │
              pay-service ←─────┘                            │             │
              (deduct money)                            user-service       │
                                                       (deduct money)      │
                                                                          │
                                                                   Elasticsearch
                                                                   (items index)
```

---

## 附录 B：下单主链路时序图

```
用户 → 前端 → Gateway → trade-service → 1. @GlobalTransactional
                        │
                        ├─→ item-service (Feign): 查询商品信息
                        ├─→ INSERT order + order_detail (MySQL)
                        ├─→ INSERT t_local_message (购物车清理)
                        ├─→ item-service (Feign): 扣减库存 ──→ MySQL UPDATE item SET stock = stock - num
                        ├─→ RabbitMQ: 发送 30分钟延迟消息 (超时取消)
                        └─→ 返回 orderId

用户 → 支付 → pay-service → 2. @GlobalTransactional
                        │
                        ├─→ user-service (Feign): BCrypt 校验支付密码 → UPDATE user SET balance = balance - amount
                        ├─→ UPDATE pay_order SET status = 3 (乐观锁)
                        ├─→ INSERT t_local_message (pay.success)
                        └─→ LocalMessageSender → RabbitMQ → trade-service 标记已支付
```

---

*本文档基于 hmall C 端微服务 v1.0 编写，各服务的具体实现细节请参见《hmall_User 实现说明文档》。*
