# hmall 秒杀模块设计文档

> 版本：v1.0  
> 日期：2026-07-14  
> 参考文档：`docs/redis功能相关文档/redis-application-analysis.md` 3.7 节（秒杀库存 Lua 原子预减）+ 3.8 节（滑动窗口限流）

---

## 1. 概述

### 1.1 背景与目标

hmall 当前是一套面向 C 端消费者的 Spring Cloud 微服务商城，包含网关（hm-gateway）、商品（item-service）、购物车（cart-service）、交易（trade-service）等微服务。现有 Redis 基础设施（`RedisService`、`RedisLockUtil`、`LuaScriptLoader`）已就绪，但尚未实现高并发秒杀场景。

本设计按照 `redis-application-analysis.md` 3.7 节规划，在 hmall 中实现**秒杀功能模块**，包含三层防超卖架构（Gateway 限流 → Redis Lua 原子预减 → MySQL 行锁兜底），以及活动管理、库存预热、异步下单、超时回补等完整链路。

### 1.2 设计原则

| 原则 | 说明 |
|------|------|
| **三层兜底** | Gateway 滑动窗口限流（第一道）→ Redis Lua 原子预减（第二道）→ MySQL `FOR UPDATE` 行锁（最终兜底） |
| **预减不穿透** | 99% 无效请求在 Redis 层返回"售罄"，不穿透到数据库 |
| **异步削峰** | Lua 预减成功后通过 MQ 异步创建订单，削峰填谷 |
| **复用现有基建** | 复用 hm-common 的 `RedisService`/`RedisLockUtil`/`LuaScriptLoader`/`RedisCacheAspect`，复用 trade-service 的 Order/OrderDetail/MQ 体系 |
| **fail-open 降级** | Redis 不可用时限流器降级放行，由后端 Lua 预减和 MySQL 行锁兜底，不阻塞用户请求 |
| **限购+库存合一** | Lua 脚本将限购检查（HINCRBY）和库存预减（DECRBY）合并为单次原子操作，减少 Redis 往返 |

### 1.3 与 redis-application-analysis.md 的对照

| 文档章节 | 核心内容 | 本设计实现 |
|---------|---------|-----------|
| 3.7.1 | 4 张数据库表 | ✅ 5 张表（含 `seckill_order` 订单追踪表） |
| 3.7.2 | 三层防超卖架构 | ✅ Gateway 限流 + Lua 预减 + MySQL 行锁 |
| 3.7.3 | 核心 Lua 脚本 | ✅ `seckill_deduct.lua`（增强版：合并限购检查） |
| 3.7.4 | 秒杀下单完整流程 | ✅ 预热 → 分布式锁 → 限购检查 → Lua 预减 → MQ → MySQL 行锁 → 超时回补 |
| 3.7.5 | Redis 数据结构 | ✅ stock(String) + limit(Hash) + lock(String) + result(String) |
| 3.7.6 | 6 个设计要点 | ✅ 全部遵循 |
| 3.8 | 滑动窗口限流 | ✅ ZSET + Lua 原子操作，Gateway GlobalFilter |

---

## 2. 整体架构

### 2.1 三层防超卖架构

```
用户请求
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│  第一层：Gateway 滑动窗口限流（RateLimitFilter, order=1）            │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  Redis ZSET 滑动窗口 Lua 脚本                                │  │
│  │  ZREMRANGEBYSCORE → ZCARD → ZADD → PEXPIRE（原子）           │  │
│  │  每用户 5 秒内仅允许 1 次请求（/seckill/**）                   │  │
│  │  Redis 不可用 → fail-open 放行                               │  │
│  │  超限 → HTTP 429                                             │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────┬───────────────────────────────────────┘
                           │ 放行的请求
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  第二层：Redis Lua 原子预减（SeckillServiceImpl）                     │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  1. per-user 分布式锁（SET NX EX，防重复提交）                 │  │
│  │  2. seckill_deduct.lua 原子执行：                             │  │
│  │     - HGET 限购计数 → 检查限购（超限返回 -2）                   │  │
│  │     - GET 库存 → 检查库存（不足返回 0，未初始化返回 -1）          │  │
│  │     - DECRBY 库存 + HINCRBY 限购（成功返回 1）                  │  │
│  │  3. 预减成功 → 发送 MQ 消息（异步下单）                         │  │
│  │  4. 预减失败 → 直接返回（不穿透 DB）                           │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────┬───────────────────────────────────────┘
                           │ MQ 消息
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  第三层：MySQL 行锁兜底（SeckillOrderListener）                       │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  1. SELECT ... FOR UPDATE 行锁锁定 seckill_daily_stock        │  │
│  │  2. 检查 stock >= quantity（不足回补 Redis，设失败结果）         │  │
│  │  3. UPDATE ... WHERE stock >= quantity（原子扣减）             │  │
│  │  4. 创建订单（order + order_detail + seckill_order）           │  │
│  │  5. 发送延迟消息（30 分钟超时取消）                             │  │
│  │  6. 设置 Redis 结果 key（前端轮询用）                          │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 技术栈

| 类别 | 技术选型 | 说明 |
|------|---------|------|
| 基础框架 | Spring Boot 2.7.12 + Spring Cloud 2021 | 与 hmall 父 POM 一致 |
| 缓存 | Redis (Lettuce) + StringRedisTemplate | Lua 脚本用 StringRedisTemplate 执行，避免 Jackson 引号问题 |
| 消息队列 | RabbitMQ | 异步下单 + 延迟消息超时取消 |
| ORM | MyBatis Plus 3.4.3 | `FOR UPDATE` 行锁 + 原子 `UPDATE WHERE stock >= quantity` |
| 网关 | Spring Cloud Gateway (WebFlux) | GlobalFilter 滑动窗口限流 |
| 分布式锁 | Redis SET NX EX + Lua 原子释放 | 复用 `RedisLockUtil` |
| 前端 | Vue 3 + TypeScript + Vite | 秒杀列表页 + 详情页 + 轮询 |

### 2.3 模块划分

秒杀模块主要放在 `trade-service`（复用现有订单/MQ/Redis 基础设施），限流模块放在 `hm-gateway` + `hm-common`：

```
hmall/
├── hm-common/                          # 公共模块
│   ├── src/main/java/com/hmall/common/
│   │   ├── utils/
│   │   │   ├── RateLimitUtil.java          # ★ 新增：滑动窗口限流工具
│   │   │   ├── RedisLockUtil.java          # 已有：分布式锁
│   │   │   └── LuaScriptLoader.java        # 已有：Lua 脚本加载器
│   │   └── config/
│   │       └── RedisConfig.java            # ★ 修改：@Import 增加 RateLimitUtil
│   └── src/main/resources/lua/
│       ├── seckill_deduct.lua              # ★ 新增：秒杀原子预减脚本
│       ├── sliding_window_rate_limit.lua   # ★ 新增：滑动窗口限流脚本
│       ├── release_lock.lua                # 已有
│       └── ...
│
├── hm-gateway/                         # 网关
│   └── src/main/java/com/hmall/gateway/
│       ├── config/
│       │   └── RateLimitProperties.java    # ★ 新增：限流配置属性
│       └── filters/
│           ├── AuthGlobalFilter.java       # 已有：认证过滤器 (order=0)
│           └── RateLimitFilter.java        # ★ 新增：限流过滤器 (order=1)
│
├── trade-service/                      # 交易服务（秒杀核心）
│   └── src/main/
│       ├── java/com/hmall/trade/
│       │   ├── controller/
│       │   │   └── SeckillController.java         # ★ 新增：秒杀 REST API
│       │   ├── service/
│       │   │   ├── SeckillService.java             # ★ 新增：秒杀服务接口
│       │   │   └── impl/
│       │   │       └── SeckillServiceImpl.java     # ★ 新增：秒杀服务实现
│       │   ├── Listener/
│       │   │   └── SeckillOrderListener.java       # ★ 新增：MQ 消费者
│       │   ├── task/
│       │   │   ├── SeckillPreheatTask.java         # ★ 新增：定时预热
│       │   │   └── SeckillTimeoutTask.java         # ★ 新增：超时兜底
│       │   ├── domain/
│       │   │   ├── po/    (5 个 PO)                # ★ 新增
│       │   │   ├── dto/   (1 个 DTO)               # ★ 新增
│       │   │   └── vo/    (3 个 VO)                # ★ 新增
│       │   ├── mapper/    (5 个 Mapper)            # ★ 新增
│       │   └── constants/
│       │       └── MQConstants.java                # ★ 修改：增加秒杀 MQ 常量
│       └── resources/db/migration/
│           └── V2__seckill_tables.sql              # ★ 新增：建表 SQL
│
└── hmall-frontend/                     # 前端
    └── src/
        ├── api/seckill.ts                       # ★ 新增：秒杀 API + 轮询工具
        ├── views/portal/
        │   ├── SeckillList.vue                  # ★ 新增：秒杀活动列表页
        │   └── SeckillDetail.vue                # ★ 新增：秒杀商品详情页
        ├── router/index.ts                      # ★ 修改：增加路由
        └── views/portal/PortalLayout.vue        # ★ 修改：增加导航入口
```

---

## 3. 数据库设计

### 3.1 表结构规划

秒杀模块使用 5 张表，存储在 trade-service 的 `hm-trade` 库中：

| 表名 | 说明 | 关键字段 |
|------|------|---------|
| `seckill_promotion` | 秒杀活动表 | title, start_date, end_date, status |
| `seckill_session` | 秒杀场次表 | promotion_id, name, start_time, end_time |
| `seckill_product_relation` | 活动-商品关联表 | promotion_id, session_id, product_id, seckill_price, stock, limit_num |
| `seckill_daily_stock` | 每日库存快照表 | relation_id, batch_date, stock, sold；UNIQUE(relation_id, batch_date) |
| `seckill_order` | 秒杀订单关联表 | order_id, relation_id, user_id, quantity, status |

### 3.2 ER 关系

```
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────────────┐
│ seckill_promotion │     │ seckill_session  │     │ seckill_product_relation │
├──────────────────┤     ├──────────────────┤     ├──────────────────────────┤
│ id            PK │◄──┐ │ id            PK │◄──┐ │ id                    PK │
│ title             │   └─┤ promotion_id  FK │   │ │ promotion_id          FK │
│ start_date        │     │ name             │   │ │ session_id            FK │──┐
│ end_date          │     │ start_time       │   │ │ product_id            FK │  │
│ status            │     │ end_time         │   │ │ seckill_price            │  │
└──────────────────┘     └──────────────────┘   │ │ stock                    │  │
                                                │ │ limit_num                │  │
                                                │ └──────────────────────────┘  │
                                                │           │                   │
                                                │           │ id                │
                                                │           ▼                   │
┌──────────────────┐     ┌──────────────────┐   │ ┌──────────────────────────┐  │
│  seckill_order   │     │ seckill_daily_   │   │ │ (复用) order + order_     │  │
├──────────────────┤     │ stock            │   │ │        detail             │  │
│ id            PK │     ├──────────────────┤   │ └──────────────────────────┘  │
│ order_id      FK │──┐  │ id            PK │◄──┘                               │
│ relation_id   FK │  │  │ relation_id   FK │──┘                                │
│ user_id          │  │  │ batch_date       │                                   │
│ quantity         │  │  │ stock            │                                   │
│ status           │  │  │ sold             │                                   │
└──────────────────┘  │  └──────────────────┘                                   │
                      └─────────────────────────────────────────────────────────┘
                      (seckill_product_relation.id = relation_id)
```

### 3.3 建表 SQL

```sql
-- 秒杀活动表（eg: "618 专场"）
CREATE TABLE IF NOT EXISTS `seckill_promotion` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `title`      VARCHAR(128) NOT NULL COMMENT '活动标题',
  `start_date` DATE         NOT NULL COMMENT '活动开始日期',
  `end_date`   DATE         NOT NULL COMMENT '活动结束日期',
  `status`     INT          DEFAULT 0 COMMENT '状态: 0未开始 1进行中 2已结束',
  `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动表';

-- 秒杀场次表（eg: 10:00-12:00）
CREATE TABLE IF NOT EXISTS `seckill_session` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `promotion_id` BIGINT      NOT NULL COMMENT '关联活动ID',
  `name`        VARCHAR(64)  NOT NULL COMMENT '场次名称',
  `start_time`  DATETIME     NOT NULL COMMENT '场次开始时间',
  `end_time`    DATETIME     NOT NULL COMMENT '场次结束时间',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_promotion` (`promotion_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀场次表';

-- 活动-商品关联表（含秒杀价+库存+限购数）
CREATE TABLE IF NOT EXISTS `seckill_product_relation` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT,
  `promotion_id`  BIGINT      NOT NULL COMMENT '活动ID',
  `session_id`    BIGINT      NOT NULL COMMENT '场次ID',
  `product_id`    BIGINT      NOT NULL COMMENT '商品ID（item表）',
  `seckill_price` INT         NOT NULL COMMENT '秒杀价（分）',
  `stock`         INT         NOT NULL COMMENT '秒杀总库存',
  `limit_num`     INT         DEFAULT 1 COMMENT '每人限购数量',
  `create_time`   DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动-商品关联表';

-- 每日库存快照表（底层防超卖行锁目标）
CREATE TABLE IF NOT EXISTS `seckill_daily_stock` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `relation_id` BIGINT   NOT NULL COMMENT '关联seckill_product_relation.id',
  `batch_date`  DATE     NOT NULL COMMENT '批次日期',
  `stock`       INT      NOT NULL COMMENT '当日剩余库存',
  `sold`        INT      DEFAULT 0 COMMENT '已售数量',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_relation_date` (`relation_id`, `batch_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀每日库存快照表';

-- 秒杀订单关联表（追踪秒杀订单，用于超时回补）
CREATE TABLE IF NOT EXISTS `seckill_order` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `order_id`    BIGINT   NOT NULL COMMENT '订单ID（order表）',
  `relation_id` BIGINT   NOT NULL COMMENT '关联seckill_product_relation.id',
  `user_id`     BIGINT   NOT NULL COMMENT '用户ID',
  `quantity`    INT      NOT NULL COMMENT '购买数量',
  `status`      INT      DEFAULT 1 COMMENT '状态: 1待支付 2已支付 3已关闭',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order` (`order_id`),
  KEY `idx_relation` (`relation_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单关联表';
```

> **设计要点**：`seckill_daily_stock` 的 `UNIQUE(relation_id, batch_date)` 约束确保同一商品同一天只有一条库存记录，`FOR UPDATE` 行锁精确锁定该行，避免锁升级。

---

## 4. Redis 数据结构设计

### 4.1 Key 规划

| Key | 类型 | TTL | 说明 |
|-----|------|-----|------|
| `seckill:stock:{relationId}` | String | 无过期（活动结束手动清除） | 当前剩余库存 |
| `seckill:limit:{relationId}` | Hash | 无过期 | `{userId → 已购数量}` 限购计数器 |
| `seckill:lock:user:{userId}` | String | 5 秒 | per-user 分布式锁（防重复提交） |
| `seckill:result:{userId}:{relationId}` | String | 120 秒 | 下单结果（`orderId` 或 `0`），前端轮询用 |
| `ratelimit:{path}:{userId}` | ZSET | 窗口大小 + 1 秒 | 滑动窗口限流计数器 |

### 4.2 限流 Key 示例

```
ratelimit:/seckill/order/123:456
           └────────┬───────┘ └┬┘
                  请求路径    userId
```

---

## 5. 核心模块设计

### 5.1 第一层：Gateway 滑动窗口限流

#### 5.1.1 限流算法

采用 Redis ZSET 实现精确滑动窗口：

```
窗口大小 = 5000ms, 最大请求数 = 1

时间轴 ──────────────────────────────────────►
         │←── 窗口 (5s) ──→│
         │                  │
    [请求A t=0]         [请求B t=3s]  [请求C t=6s]
         │                  │              │
    ZADD score=0        ZADD score=3000  ZREMRANGEBYSCORE(-inf, 1000)
    ZCARD=1 ✓           ZCARD=2 ✗(>1)   ZCARD=1 ✓
```

#### 5.1.2 Lua 脚本（sliding_window_rate_limit.lua）

```lua
-- KEYS[1] = 限流 key
-- ARGV[1] = 当前时间戳（毫秒）
-- ARGV[2] = 窗口大小（毫秒）
-- ARGV[3] = 窗口内最大请求数
-- ARGV[4] = 唯一请求 ID（UUID）

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local maxRequests = tonumber(ARGV[3])

-- 1. 移除窗口外的过期记录
redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)

-- 2. 统计当前窗口内的请求数
local count = redis.call('ZCARD', key)

if count < maxRequests then
    -- 3. 未超限：添加本次请求记录
    redis.call('ZADD', key, now, ARGV[4])
    redis.call('PEXPIRE', key, window + 1000)
    return 1
else
    return 0
end
```

#### 5.1.3 过滤器设计（RateLimitFilter）

| 属性 | 值 | 说明 |
|------|-----|------|
| 类型 | `GlobalFilter` | 全局过滤器 |
| order | 1 | 在 `AuthGlobalFilter`（order=0）之后执行 |
| 限流维度 | `userId` | 从 `user-info` 请求头获取（由 AuthGlobalFilter 设置） |
| 限流粒度 | `ratelimit:{path}:{userId}` | 按路径 + 用户 |
| 降级策略 | fail-open | Redis 不可用或 `RateLimitUtil` 为 null 时放行 |
| 拒绝响应 | HTTP 429 + JSON | `{"message":"请求过于频繁，请稍后再试"}` |

#### 5.1.4 配置设计

```yaml
hm:
  ratelimit:
    enabled: true
    rules:
      - paths: ["/seckill/**"]
        max-requests: 1
        window-ms: 5000
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | boolean | true | 是否启用限流 |
| `rules` | List | — | 限流规则列表 |
| `rules[].paths` | List\<String\ | — | Ant 路径模式 |
| `rules[].maxRequests` | int | 1 | 窗口内最大请求数 |
| `rules[].windowMs` | long | 5000 | 窗口大小（毫秒） |

### 5.2 第二层：Redis Lua 原子预减

#### 5.2.1 Lua 脚本（seckill_deduct.lua）

将限购检查和库存预减合并为单次原子操作：

```lua
-- KEYS[1] = seckill:stock:{relationId}     String  当前剩余库存
-- KEYS[2] = seckill:limit:{relationId}     Hash    {userId → 已购数量}
-- ARGV[1] = userId
-- ARGV[2] = quantity
-- ARGV[3] = limitNum
--
-- 返回值：1=成功, 0=售罄, -1=未初始化, -2=超限购

local stock = redis.call('GET', KEYS[1])
if stock == false then return -1 end          -- 未预热

local purchased = redis.call('HGET', KEYS[2], ARGV[1])
purchased = purchased == false and 0 or tonumber(purchased)

if purchased + tonumber(ARGV[2]) > tonumber(ARGV[3]) then
    return -2                                   -- 超限购
end

if tonumber(stock) < tonumber(ARGV[2]) then
    return 0                                    -- 售罄
end

redis.call('DECRBY', KEYS[1], ARGV[2])
redis.call('HINCRBY', KEYS[2], ARGV[1], ARGV[2])
return 1
```

#### 5.2.2 下单流程（SeckillServiceImpl.doSeckill）

```
doSeckill(relationId, quantity)
  │
  ├─ 1. 获取 userId（UserContext）
  ├─ 2. 查询 SeckillProductRelation（秒杀价、限购数）
  ├─ 3. per-user 分布式锁（seckill:lock:user:{userId}, TTL=5s）
  │     └─ 锁定失败 → 返回 "请勿重复提交"
  │
  ├─ 4. 执行 seckill_deduct.lua（原子操作）
  │     ├─ 返回 1  → 预减成功
  │     ├─ 返回 0  → 返回 "已售罄"
  │     ├─ 返回 -1 → 返回 "活动未开始"
  │     └─ 返回 -2 → 返回 "超过限购数量"
  │
  ├─ 5. 预减成功 → 发送 MQ 消息（SeckillOrderMessage）
  │     └─ MQ 发送失败 → 回补 Redis 库存和限购 → 返回 "系统繁忙"
  │
  ├─ 6. 返回 pending（排队中）
  │
  └─ finally: 释放分布式锁
```

### 5.3 第三层：MySQL 行锁兜底

#### 5.3.1 MQ 消费者（SeckillOrderListener）

```
onSeckillOrder(message)  @Transactional
  │
  ├─ 1. Feign 调用 item-service 查询商品信息（订单详情用）
  ├─ 2. SELECT ... FOR UPDATE 行锁查询 seckill_daily_stock
  │     └─ 库存不足 → 回补 Redis → 设结果 "0" → return
  │
  ├─ 3. UPDATE ... WHERE stock >= quantity（原子扣减）
  │     └─ 影响行数=0 → 并发竞争失败 → 回补 Redis → 设结果 "0" → return
  │
  ├─ 4. 创建订单（order 表，status=1 待支付）
  ├─ 5. 创建订单详情（order_detail，price=秒杀价）
  ├─ 6. 创建秒杀订单关联（seckill_order，status=1）
  ├─ 7. 发送延迟消息（30 分钟超时取消，复用现有 DELAY 机制）
  └─ 8. 设 Redis 结果 key = orderId（前端轮询用）
```

#### 5.3.2 行锁 SQL（SeckillDailyStockMapper）

```java
// 行锁查询（必须在事务内）
@Select("SELECT * FROM seckill_daily_stock WHERE relation_id = #{relationId} " +
        "AND batch_date = #{batchDate} FOR UPDATE")
SeckillDailyStock selectForUpdate(Long relationId, LocalDate batchDate);

// 原子扣减（WHERE stock >= quantity 双重保证）
@Update("UPDATE seckill_daily_stock SET stock = stock - #{quantity}, sold = sold + #{quantity} " +
        "WHERE relation_id = #{relationId} AND batch_date = #{batchDate} AND stock >= #{quantity}")
int deductStock(Long relationId, LocalDate batchDate, int quantity);

// 回补库存（超时关单时调用）
@Update("UPDATE seckill_daily_stock SET stock = stock + #{quantity}, sold = sold - #{quantity} " +
        "WHERE relation_id = #{relationId} AND batch_date = #{batchDate}")
int recoverStock(Long relationId, LocalDate batchDate, int quantity);
```

### 5.4 超时回补机制

#### 5.4.1 延迟消息回补（主路径）

复用 trade-service 现有的 30 分钟延迟消息机制：

```
秒杀订单创建 → 发送延迟消息（delay=30min）
  → 30 分钟后 orderDelayMessageListener 消费
  → 调用 cancelOrder(orderId)
  → cancelOrder 检测到 seckill_order 关联记录
  → recoverSeckillStock(): 回补 Redis + MySQL + 限购额度
```

#### 5.4.2 定时任务兜底（SeckillTimeoutTask）

```
@Scheduled(fixedDelay = 5min, initialDelay = 2min)
  → 查询 seckill_order WHERE status=1 AND create_time < now()-30min
  → 逐条调用 cancelOrder(orderId)
```

#### 5.4.3 cancelOrder 修改

`OrderServiceImpl.cancelOrder()` 增加秒杀订单判断：

```
cancelOrder(orderId)
  ├─ 查询 order（status != 1 → return）
  ├─ 查询 seckill_order（by order_id）
  │
  ├─ seckill_order 存在 → recoverSeckillStock()
  │    ├─ Redis: incrBy(stock) + hIncrBy(limit, -quantity)
  │    ├─ MySQL: recoverStock(relationId, today, quantity)
  │    └─ 更新 seckill_order.status = 3（已关闭）
  │
  └─ seckill_order 不存在 → 普通订单：itemClient.recoverStock()
  │
  └─ 删除订单
```

### 5.5 活动预热机制

#### 5.5.1 定时预热任务（SeckillPreheatTask）

```
@Scheduled(fixedDelay = 60s)
  → 查询未来 5 分钟内开始的场次
  → 遍历场次下的商品关联
  → seckillService.preheat(relationId)
      ├─ Redis: SET seckill:stock:{relationId} = stock
      └─ MySQL: INSERT seckill_daily_stock（当天不存在时）
```

#### 5.5.2 预热时序

```
T-5min   预热任务扫描到即将开始的场次
         → 库存写入 Redis + 初始化每日快照
T-0      场次开始，用户可下单
         → Lua 脚本读取 Redis 库存（已预热，返回 1/0）
T+30min  超时未支付 → 延迟消息触发 cancelOrder → 回补库存
```

---

## 6. 接口设计

### 6.1 秒杀 REST API

```
GET    /seckill/activities              查询秒杀活动列表（含场次、商品）
GET    /seckill/products/{relationId}   查询秒杀商品详情
POST   /seckill/order/{relationId}      秒杀下单（?quantity=1）
GET    /seckill/result/{relationId}     轮询秒杀订单结果
```

### 6.2 响应格式

#### 活动列表响应

```json
[
  {
    "id": 1,
    "title": "618 专场",
    "status": 1,
    "sessions": [
      {
        "id": 1,
        "name": "10:00场",
        "startTime": "2026-07-14T10:00:00",
        "endTime": "2026-07-14T12:00:00",
        "status": 1,
        "products": [
          {
            "relationId": 1,
            "productId": 100,
            "name": "iPhone 15",
            "image": "xxx.png",
            "spec": "128G",
            "originalPrice": 699900,
            "seckillPrice": 599900,
            "totalStock": 100,
            "remainingStock": 45,
            "soldCount": 55,
            "limitNum": 1,
            "status": 1,
            "startTime": "2026-07-14T10:00:00",
            "endTime": "2026-07-14T12:00:00"
          }
        ]
      }
    ]
  }
]
```

#### 秒杀下单响应

```json
// 排队中
{ "status": "pending", "message": "排队中，请稍候", "orderId": null }

// 成功
{ "status": "success", "message": "秒杀成功", "orderId": 123456 }

// 失败
{ "status": "failed", "message": "已售罄", "orderId": null }
{ "status": "failed", "message": "超过限购数量", "orderId": null }
{ "status": "failed", "message": "活动未开始", "orderId": null }
{ "status": "failed", "message": "请勿重复提交", "orderId": null }
```

#### 限流响应（HTTP 429）

```json
{ "message": "请求过于频繁，请稍后再试" }
```

### 6.3 MQ 消息格式

#### 秒杀下单消息（SeckillOrderMessage）

```json
{
  "relationId": 1,
  "userId": 456,
  "productId": 100,
  "quantity": 1,
  "seckillPrice": 599900,
  "limitNum": 1
}
```

| MQ 常量 | 值 |
|---------|-----|
| Exchange | `seckill.topic` |
| Queue | `seckill.order.queue` |
| Routing Key | `seckill.order` |

---

## 7. 前端设计

### 7.1 页面规划

| 页面 | 路由 | 功能 |
|------|------|------|
| `SeckillList.vue` | `/portal/seckill` | 秒杀活动列表（场次切换 + 商品卡片 + 库存进度条） |
| `SeckillDetail.vue` | `/portal/seckill/:relationId` | 秒杀商品详情（倒计时 + 抢购 + 排队轮询 + 429 处理） |

### 7.2 秒杀列表页（SeckillList.vue）

```
┌──────────────────────────────────────────────────────────────────┐
│  ⚡ 限时秒杀                                                       │
│  超低折扣 限量抢购 手慢无                                            │
├──────────────────────────────────────────────────────────────────┤
│ [10:00 抢购中] [12:00 即将开始] [14:00 已结束]    ← 场次切换栏       │
├──────────────────────────────────────────────────────────────────┤
│ ┌──────────┬───────────────────────────┐ ┌──────────┬─────────┐ │
│ │          │ iPhone 15                  │ │          │ MacBook  │ │
│ │  [图片]   │ 128G                      │ │  [图片]   │ 512G    │ │
│ │          │ 已抢 55%  剩余 45 件       │ │          │ 已抢 80%│ │
│ │          │ ¥5999  ¥6999  [立即抢购]   │ │          │ ¥8999  │ │
│ └──────────┴───────────────────────────┘ └──────────┴─────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### 7.3 秒杀详情页（SeckillDetail.vue）

```
┌──────────────────────────────────────────────────────────────────┐
│ ⚡ 限时秒杀                           距结束 [02]:[15]:[30]        │
├──────────────────────────────────────────────────────────────────┤
│ ┌────────────┐  iPhone 15                                   │
│ │            │  128G                                        │
│ │   [大图]    │  ┌──────────────────────────────────────┐    │
│ │            │  │ 秒杀价 ¥5999  原价 ¥6999              │    │
│ │            │  │ 限购 1 件  剩余 45 件                 │    │
│ │            │  └──────────────────────────────────────┘    │
│ │            │  已抢 55%                    总量 100 件      │
│ │            │  ████████████████░░░░░░░░                    │
│ │            │  购买数量 [-] [1] [+]  每人限购 1 件          │
│ │            │  ┌──────────────────────────────────────┐    │
│ │            │  │          立即抢购                      │    │
│ │            │  └──────────────────────────────────────┘    │
│ └────────────┘                                                │
└──────────────────────────────────────────────────────────────────┘
```

### 7.4 前端轮询机制

```typescript
// pollSeckillResult: 最多重试 30 次（间隔 1.5s，总计 45s）
// 遇到 429 限流时延长间隔
for (let i = 0; i < 30; i++) {
  const result = await getSeckillResult(relationId)
  if (result.status === 'success' || result.status === 'failed') {
    return result
  }
  await sleep(1500)
}
```

### 7.5 API 模块（seckill.ts）

| 函数 | 说明 |
|------|------|
| `getSeckillActivities()` | 查询活动列表 |
| `getSeckillProduct(relationId)` | 查询商品详情 |
| `doSeckill(relationId, quantity)` | 秒杀下单 |
| `getSeckillResult(relationId)` | 轮询结果 |
| `pollSeckillResult(relationId, onProgress)` | 轮询工具（含 429 处理） |

---

## 8. 安全与可靠性设计

### 8.1 防超卖三层保障

| 层级 | 机制 | 覆盖场景 |
|------|------|---------|
| 第一层 | Gateway 滑动窗口限流 | 恶意刷单、高频请求 |
| 第二层 | Redis Lua 原子预减 | 高并发库存竞争、限购超限 |
| 第三层 | MySQL `FOR UPDATE` + `WHERE stock >= quantity` | Redis 与 MySQL 数据不一致的最终兜底 |

### 8.2 防重复提交

- **per-user 分布式锁**：`seckill:lock:user:{userId}`，TTL=5s，同一用户 5 秒内只能提交一次
- **MQ 幂等**：`seckill_order` 表 `UNIQUE(order_id)` 约束防止重复创建

### 8.3 Redis 不可用降级

| 组件 | 降级行为 |
|------|---------|
| RateLimitFilter | `RateLimitUtil` 为 null → 放行（fail-open） |
| RateLimitUtil | Redis 异常 → 返回 true（放行） |
| SeckillServiceImpl | Lua 返回 null → 返回"系统繁忙"（不穿透 DB） |
| RedisCacheAspect | Redis 异常被切面捕获，返回 null |

### 8.4 超时回补可靠性

| 机制 | 说明 |
|------|------|
| 延迟消息（主） | 30 分钟后自动触发 `cancelOrder`，回补 Redis + MySQL |
| 定时任务（兜底） | 每 5 分钟扫描超时订单，防止延迟消息遗漏 |
| MQ 发送失败回补 | Lua 预减成功但 MQ 发送失败 → 立即回补 Redis 库存和限购 |

---

## 9. 配置设计

### 9.1 Gateway 限流配置（application.yml）

```yaml
hm:
  ratelimit:
    enabled: true
    rules:
      - paths: ["/seckill/**"]
        max-requests: 1
        window-ms: 5000
```

### 9.2 MQ 常量（MQConstants.java）

```java
// 秒杀 MQ 常量
String SECKILL_EXCHANGE_NAME = "seckill.topic";
String SECKILL_ORDER_QUEUE_NAME = "seckill.order.queue";
String SECKILL_ORDER_KEY = "seckill.order";
```

### 9.3 RedisConfig 自动装配

`hm-common` 的 `RedisConfig` 通过 `@Import` 注册 `RateLimitUtil`：

```java
@Import({RedisService.class, RedisLockUtil.class, RateLimitUtil.class})
public class RedisConfig { ... }
```

`RateLimitUtil` 使用 `@ConditionalOnProperty(prefix = "spring.redis", name = "host")` 条件注册，无 Redis 时不创建。

---

## 10. 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| Redis 与 MySQL 库存不一致 | 超卖或少卖 | MySQL `FOR UPDATE` + `WHERE stock >= quantity` 兜底；MQ 消费失败回补 Redis |
| 活动未预热用户下单 | 返回"活动未开始" | 定时预热任务提前 5 分钟预热；Key 不存在返回 -1 不穿透 DB |
| MQ 消费积压 | 用户等待时间长 | 前端轮询最多 45 秒超时；RabbitMQ 消费者并发可调 |
| per-user 锁过期后重复提交 | 短暂窗口内可重复提交 | 锁 TTL=5s 足够覆盖 Lua 执行 + MQ 发送；Lua 原子性保证库存不超扣 |
| 网关 Redis 不可用 | 限流失效 | fail-open 降级，由后端 Lua 预减兜底 |
| 秒杀订单复用 order 表 | 普通订单逻辑受影响 | `cancelOrder` 通过 `seckill_order` 关联表区分秒杀/普通订单，走不同回补路径 |

---

## 附录 A：设计要点对照表（3.7.6 节）

| 设计要点 | 实现位置 |
|---------|---------|
| 三层兜底：Lua→MQ→MySQL | `RateLimitFilter` → `SeckillServiceImpl` → `SeckillOrderListener` |
| 预减失败不穿透 DB | Lua 返回 0/-1/-2 时直接返回，不查 DB |
| per-user 锁 + Hash 限购 | `RedisLockUtil` + `seckill:limit:{relationId}` Hash |
| 超时回补 | `cancelOrder` + `SeckillTimeoutTask` |
| 活动预热 | `SeckillPreheatTask` + `preheat()` |
| Lua 参数用 StringRedisTemplate | `RateLimitUtil` 和 `SeckillServiceImpl` 均用 StringRedisTemplate |
