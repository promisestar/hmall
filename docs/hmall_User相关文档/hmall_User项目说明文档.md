# hmall C 端微服务（User 端）项目说明文档

> 本文档面向项目理解与快速上手，描述各功能的**设计动机**、**实现思路**和**最终效果**，不涉及具体代码引用与实现细节。

---

## 目录

1. [项目定位](#1-项目定位)
2. [总体架构](#2-总体架构)
3. [用户认证与账户](#3-用户认证与账户)
4. [商品浏览与搜索](#4-商品浏览与搜索)
5. [个性化推荐](#5-个性化推荐)
6. [购物车管理](#6-购物车管理)
7. [订单与交易](#7-订单与交易)
8. [秒杀抢购](#8-秒杀抢购)
9. [支付体系](#9-支付体系)
10. [前端设计](#10-前端设计)
11. [配置体系](#11-配置体系)
12. [启动流程](#12-启动流程)

---

## 1. 项目定位

hmall C 端微服务体系是枫叶商城面向消费者的**后端服务集群**，基于 Spring Cloud Alibaba 微服务架构，支撑从"搜索浏览 → 加购 → 下单 → 支付"的完整购物链路。

它能够：

- 让消费者浏览、搜索海量商品，按品牌、分类、价格等维度筛选
- 将心仪商品加入购物车，随时随地修改数量和查看最新价格
- 一键下单，系统自动扣减库存、生成订单、清理购物车
- 用账户余额支付订单，支付成功后订单状态自动流转
- 参与限时秒杀活动，在毫秒级响应时间内抢占限量商品
- 根据购买偏好和浏览行为，获得个性化的商品推荐

C 端微服务体系与 B 端管理后台（admin-service）共用同一基础设施（Nacos、Redis、RabbitMQ、MySQL），但数据完全隔离（C 端用户表 `hm-user.user` vs 管理员表 `hm-admin.admin_user`），认证完全隔离（`hmall.jks` vs `admin.jks`）。

---

## 2. 总体架构

### 部署拓扑

```
消费者（浏览器）
  │
  ▼
hmall-frontend (Vue 3 + Vite 5)
  │  /api/** 请求
  ▼
hm-gateway (:8080)  —  统一入口、JWT 认证、限流保护
  │
  ├── user-service (:8083)  —  登录注册、地址管理、余额管理
  │
  ├── item-service (:8081)  —  商品浏览、库存管理、个性化推荐
  │
  ├── cart-service (:8082)  —  购物车（Redis 主存储 + MySQL 异步持久化）
  │
  ├── trade-service (:8084) —  订单创建与查询、秒杀活动与下单
  │
  ├── pay-service (:8085)   —  余额支付、支付单管理
  │
  └── search-service (:8089) —  全文搜索、聚合过滤、推荐召回

基础设施：Nacos（注册 + 配置）/ Redis（缓存 + 分布式锁）/ RabbitMQ（异步消息）/ Elasticsearch（搜索引擎）/ MySQL（持久化）/ Seata（分布式事务）
```

### 模块边界

C 端微服务严格遵循"**数据自治**"原则——每个服务拥有自己的数据库，服务间通过 Feign（同步调用）和 RabbitMQ（异步消息）协作：

| 数据域 | 所有权 | 其他服务如何访问 |
|--------|--------|-----------------|
| 用户/地址 | user-service → `hm-user` | Feign 调用（trade/pay 查询用户信息） |
| 商品/库存 | item-service → `hm-item` | Feign 调用（trade 扣库存、cart 查价格） |
| 购物车 | cart-service → `hm-cart` + Redis | MQ 通知（trade 下单后清理购物车） |
| 订单/秒杀 | trade-service → `hm-trade` | Feign 调用（pay 查询订单） |
| 支付单 | pay-service → `hm-pay` | MQ 通知（支付成功后通知 trade） |
| 搜索索引 | search-service → ES | MQ 同步（item 变更时更新 ES 索引） |

### 关键设计决策

**为什么需要这么多微服务？不能用一个单体服务吗？**

单体服务在初期开发简单，但问题是：
- **耦合**：修改购物车逻辑需要整体部署，可能影响支付功能
- **扩展**：大促期间只有秒杀流量高，单体服务必须全部扩容，浪费资源
- **故障隔离**：单体中一个模块 OOM，整个服务崩溃；微服务中只有那个服务不可用，其他正常

hmall 的 C 端服务拆分遵循"**高内聚、低耦合**"原则，每个服务职责单一明确，通过标准化协议协作。

---

## 3. 用户认证与账户

### 设计动机

商城需要对用户进行身份识别——谁加了购物车、谁下了订单、支付时扣谁的余额。同时需要保护用户的密码和支付密码安全。

### 实现思路

**双重登录方式**：
- **密码登录**：用户名 + 密码 → BCrypt 校验 → 签发 RSA-256 JWT Token
- **验证码登录**：手机号 → 发送 6 位验证码（存 Redis，5 分钟有效） → 输入验证码 → 签发 JWT

**JWT 无状态认证**：
- Token 包含用户 ID 和过期时间，使用 RSA-256 非对称签名
- 网关读取 Token → 验签 → 提取 userId → 透传给下游服务
- 下游服务通过 `UserContext`（ThreadLocal）获取当前用户 ID

**Token 安全管理**：
- 有效期 30 分钟，活跃用户自动续期（15 分钟冷却窗口，防止高频刷新）
- 登出时 JTI 加入 Redis 黑名单，Token 立即失效（即使没过期）

**支付密码保护**：
- 支付密码同样使用 BCrypt 加密存储
- 余额操作（支付、提现）需二次密码验证

### 最终效果

- 用户注册后可通过用户名密码或手机验证码登录
- 登录后 Token 自动在请求头携带，用户无感知
- 退出登录后 Token 立即失效，无法被复用
- 账户余额受支付密码保护，安全扣款

### 核心数据表

| 表 | 说明 |
|----|------|
| `user` | 用户表（username / password(BCrypt) / phone / status / balance） |
| `address` | 收货地址表（关联 userId，支持多地址和默认地址） |

---

## 4. 商品浏览与搜索

### 设计动机

消费者需要浏览商品列表、查看商品详情、按关键字和条件搜索商品。单靠 MySQL 的 `LIKE` 查询在数据量大时性能极差，需要 Elasticsearch 搜索引擎。

### 实现思路

**商品查询链路**：

```
用户请求
  → Gateway (认证放行)
    → item-service 或 search-service

搜索场景 → search-service (Elasticsearch):
  - 全文搜索：IK 中文分词 → 倒排索引匹配
  - 多维度过滤：品牌/分类/价格范围
  - 聚合统计：返回当前条件下的品牌分布和分类分布
  - 广告加权：isAD=true 的商品额外获得 100 权重分
  - 排序：相关性得分 + 更新时间

浏览/详情场景 → item-service (MySQL + Redis 缓存):
  - 先查 Redis (item:info:{id}, TTL 30min)
  - miss → MySQL → 回写 Redis
```

**ES 索引同步**：
- 商品新增 → MQ `search.create` → search-service 创建 ES 文档
- 商品更新 → MQ `search.update` → search-service 增量更新 ES 文档
- 商品删除 → MQ `search.remove` → search-service 删除 ES 文档

**缓存一致性三层保障**：
1. 写操作时直接删除 Redis 缓存
2. MQ 异步二次确认删除
3. 定时任务（每 5 分钟）遍历脏数据，补充删除遗漏缓存

### 最终效果

- 商品列表页按更新时间排序，支持品牌/分类/价格筛选
- 搜索"手机"→ 毫秒级返回包含该关键词的商品，按相关性排列
- 搜索结果页左侧显示品牌和分类聚合分布
- 商品详情页实时展示库存和价格
- 运营上架/下架商品后，搜索结果立即同步（MQ 延迟 < 1 秒）

### 核心数据表

| 表 | 说明 |
|----|------|
| `item` | 商品表（name / price / stock / category / brand / sold / status） |

---

## 5. 个性化推荐

### 设计动机

用户希望看到"可能感兴趣"的商品，而不是千篇一律的热销商品。好的推荐能提升点击率、转化率和客单价。

### 实现思路

**三步推荐管线**：

**Step 1 - 确定用户偏好（召回参数）**：
- 已登录用户 → 读 Redis 画像 `profile:{userId}:categories`（用户偏好类目 Top3）
  - 画像数据由加购行为（cart-service）和购买行为（trade-service 支付回调）共同写入，Pipelined 批量操作
  - 类目偏好权重：浏览×1 + 加购×3 + 复购×5
- 画像 miss → Feign 调 trade-service 查已购商品 → MySQL 补全 category/brand → 加权聚合

**Step 2 - ES 召回**：
- Feign 调 search-service → ES Bool Query：`category IN (偏好类目)` + `排除已购商品` → 按销量降序

**Step 3 - MySQL 兜底 + 打标**：
- ES 结果不足 → 全局热销商品补充
- 为每个推荐商品打标签："相似推荐"、"同类目热销"、"您常买的品牌"、"热销推荐"

### 最终效果

- 首页"猜你喜欢"展示个性化推荐商品
- 商品详情页"看了又看"基于当前商品类目推荐
- 购物车页"常买搭配"基于历史购买品牌推荐
- 推荐标签让用户知道为什么推荐这个商品（增加可信度）

---

## 6. 购物车管理

### 设计动机

购物车是电商平台中**读写最频繁**的模块——用户每次浏览都可能加购、修改数量、删除。传统 MySQL 方案在高并发下面临连接池耗尽和行锁竞争问题。

### 实现思路

**Redis 为主存储、MySQL 为持久化存储的双写架构**：

```
读流程（纯 Redis）:
  HGETALL cart:user:{userId} + HGETALL num
  → 构建 CartVO 列表
  → Feign 调 item-service 补充最新价/库存/状态
  → 返回

写流程（Redis + MQ → MySQL）:
  Lua 脚本原子操作 Redis
  → MQ 发送 CartSyncMessage
  → CartSyncReceiver 消费 → upsert MySQL
  → 定时补偿任务 (5分钟) 比对版本号修复不一致
```

**Redis 数据结构**（每个用户 3 个 Key，TTL 30 天）：

| Key | 类型 | 内容 |
|-----|------|------|
| `cart:user:{userId}` | Hash | 商品元数据 JSON（field=itemId） |
| `cart:user:{userId}:num` | Hash | 商品数量（field=itemId） |
| `cart:user:{userId}:v` | String | 版本号（时间戳） |

**Lua 原子操作解决并发问题**：

- 加购：`HINCRBY`（原子递增）+ `HLEN` 检查上限 → 单次 Redis 往返
- 删除：遍历 itemId，同时 `HDEL` 两个 Hash → 保证元数据和数量同步删除

**降级与容错**：
- Redis 不可用 → 自动降级纯 MySQL → 下次查询时 lazy sync 回 Redis
- MQ 失败 → 不阻断主流程 → 补偿任务兜底
- 版本号机制 → 补偿任务自动判断同步方向（Redis→MySQL 或 MySQL→Redis）

### 最终效果

- 加购操作毫秒级响应（纯 Redis，不走 MySQL）
- 即使在高并发大促期间，购物车读写也不卡顿
- 多个设备登录同一账号，购物车自动同步（共享同一 Redis Key）
- 下单后已购商品自动从购物车移除（MQ 消息触发）
- Redis 意外重启后，补偿任务自动从 MySQL 恢复数据

### 核心数据表

| 表 | 说明 |
|----|------|
| `cart` | 购物车表（user_id / item_id / num / version(时间戳)） |

---

## 7. 订单与交易

### 设计动机

下单是电商的核心流程——需要在**一个事务中**完成"查询商品 → 计算总价 → 创建订单 → 扣减库存 → 清理购物车"多个步骤。任何一个步骤失败，整个订单都不应创建。

### 实现思路

**Seata AT 分布式事务保证一致性**：

```
@GlobalTransactional
createOrder():
  1. Feign → item-service: 查询商品信息
  2. 计算总金额
  3. INSERT order (status=1 未付款)
  4. INSERT order_detail × N
  5. INSERT t_local_message (购物车清理，最终一致性)
  6. Feign → item-service: 扣减库存 (可能抛异常→Seata回滚)
  7. MQ → 30分钟延迟消息 (超时未付自动取消)
  8. 返回 orderId
```

**订单状态流转**：

```
创建 → 1(未付款)
  ├─ 支付成功 → 2(已付款,未发货)
  │   └─ 后台发货 → 3(已发货,未确认)
  │       └─ 确认收货 → 4(交易成功)
  │           └─ 已评价 → 6(交易结束)
  └─ 超时30分钟/主动取消 → 5(已关闭)
      └─ 恢复库存 (Feign → item-service)
```

**延迟取消机制**：
- 下单后通过 RabbitMQ 的 TTL + 死信队列发送 30 分钟延迟消息
- 30 分钟后消费 → 查支付流水（Feign 调 pay-service）→ 确认未支付 → 关单 + 恢复库存
- 兜底：即使 MQ 延迟消息丢失，支付超时（120 分钟）后支付单自动失效

### 最终效果

- 用户下单后 30 分钟内完成支付，超时自动取消释放库存
- 订单列表展示全部历史订单，支持按状态筛选
- 已付款的订单等待后台发货，已发货的订单可确认收货
- 管理后台可批量发货、批量关闭、修改订单备注

### 核心数据表

| 表 | 说明 |
|----|------|
| `order` | 订单主表（total_fee / payment_type / user_id / status / 各时间节点） |
| `order_detail` | 订单详情表（order_id / item_id / num / name / spec / price / image） |
| `order_logistics` | 订单物流表（order_id / logistics_number / logistics_company / 收货信息） |
| `t_local_message` | 本地消息表（购物车清理等非关键操作的最终一致性保障） |

---

## 8. 秒杀抢购

### 设计动机

秒杀是电商中最具挑战性的场景——瞬时流量是平时的 10-100 倍。如果直接用 MySQL 扣库存，行锁竞争会导致大量请求超时或失败。需要一套高并发架构来扛住流量。

### 实现思路

**三层防超卖架构，层层削峰**：

```
第 1 层 — 用户锁（防重复提交）:
  Redis 分布式锁 tryLock(userId, 5s)
  → 同一用户 5 秒内只能发起一次秒杀请求
  → 锁获取失败直接返回"操作频繁"

第 2 层 — Redis Lua 原子预减（扛流量）:
  seckill_deduct.lua (< 1ms):
    → 检查 Redis 库存 → 检查限购额度 → 扣库存 → 增已购数
  → 返回: 1=成功, 0=售罄, -1=未开始, -2=超限购
  → 成功后发 MQ 异步下单 → 前端轮询结果
  → 失败直接返回

第 3 层 — MySQL 行锁兜底（保数据）:
  SeckillOrderListener 消费 MQ:
    → SELECT ... FOR UPDATE 锁定 seckill_daily_stock
    → UPDATE WHERE stock >= quantity
    → 创建 order + order_detail + seckill_order
    → 写 Redis 结果缓存 → 前端轮询获取
```

**库存预热**：每分钟扫描未来 5 分钟内开始的场次，自动将 MySQL 库存预热到 Redis。

**超时回补**：每 5 分钟扫描超时 30 分钟的未支付秒杀订单，自动关单并回补 Redis 和 MySQL 库存。

**网关限流**：对 `/seckill/**` 路径使用 Redis 滑动窗口算法，每人 5 秒最多 1 次请求。

### 最终效果

- 秒杀页展示活动场次、倒计时、实时库存
- 点击"立即秒杀"后毫秒级返回结果（成功/售罄/排队中）
- 排队中的订单自动异步处理，前端轮询获取最终结果
- 每人每商品限购 N 件（强校验，无法绕过）
- 即使秒杀 Redis 库存数据丢失，MySQL 的每日库存快照保证不超卖

### 核心数据表

| 表 | 说明 |
|----|------|
| `seckill_promotion` | 秒杀活动表（title / start_date / end_date / status） |
| `seckill_session` | 秒杀场次表（promotion_id / name / start_time / end_time） |
| `seckill_product_relation` | 秒杀商品关联表（session_id / product_id / seckill_price / stock / limit_num） |
| `seckill_daily_stock` | 每日库存快照表（relation_id / batch_date / stock / sold）— 防超卖行锁目标 |
| `seckill_order` | 秒杀订单关联表（order_id / relation_id / user_id / quantity / status） |

---

## 9. 支付体系

### 设计动机

用户下单后需要付款，系统要安全地处理资金流转——"扣了钱就要标记已支付，不能扣了钱订单还显示未付款"。

### 实现思路

**余额支付是目前唯一支持的支付方式**，微信/支付宝支付已预留接口但尚未实现。

```
支付流程:
  用户选择余额支付 → 输入支付密码
    → POST /pay-orders {id, pw}
      → @GlobalTransactional (Seata)
        → 查询支付单 (status=1 待支付)
        → Feign → user-service: BCrypt 校验支付密码 → UPDATE balance - amount
        → UPDATE pay_order: status = 3 (乐观锁: WHERE status IN (0,1))
        → INSERT t_local_message (messageId=orderNo_pay_success)
        → LocalMessageSender (每10s扫描) → MQ → trade-service 标记订单已支付
```

**本地消息表模式保证"支付成功 → 订单状态更新"的最终一致性**：
- 支付成功和消息插入在同一事务
- 异步定时任务扫描未发送消息 → 发送 RabbitMQ → 标记成功
- 重试 5 次后不再重试（永久失败，人工介入）

### 最终效果

- 支付时需输入支付密码，双重确认
- 余额不足时提示并终止支付
- 支付成功后订单状态变为"已付款"
- 支付超时（120 分钟）后支付单自动失效

### 核心数据表

| 表 | 说明 |
|----|------|
| `pay_order` | 支付单表（id(雪花) / biz_order_no / pay_order_no / amount / status(0未提交→1待支付→2关闭→3成功) / pay_channel_code） |
| `t_local_message` | 本地消息表（message_id / exchange / routing_key / message_body / status / try_count） |

---

## 10. 前端设计

### 设计动机

C 端商城需要给消费者流畅的购物体验——简洁美观的界面、流畅的商品浏览、便捷的加购和下单流程。

### 实现思路

**技术栈**：Vue 3（Composition API + `<script setup>`）+ TypeScript + Tailwind CSS + Pinia + Vite

**路由区分**：通过 Vue Router 的 Hash 模式区分 C 端和管理后台：
- `#/portal/**` → C 端商城页面
- `#/admin/**` → 管理后台页面

**状态管理**：Pinia store 模块化：
- `userStore`：C 端用户登录/信息/退出
- `cartStore`：购物车状态
- `itemStore`：商品浏览状态
- `orderStore`：订单状态

**请求代理**：Vite 开发服务器配置 `/api` 代理到后端网关（localhost:8080），所有 API 请求统一走 `/api` 前缀。

### 核心 C 端页面

| 路由 | 页面 | 功能 |
|------|------|------|
| `/portal` | `PortalLayout.vue` | C 端主框架（顶栏 + 内容区 + 底栏） |
| `/portal/home` | `Home.vue` | 首页（轮播 + 分类入口 + 推荐商品） |
| `/portal/items` | `Items.vue` | 商品列表（搜索/筛选/分页） |
| `/portal/item/:id` | `ItemDetail.vue` | 商品详情（图片/价格/规格/加入购物车） |
| `/portal/cart` | `Cart.vue` | 购物车页（商品列表/修改数量/结算） |
| `/portal/orders` | `Orders.vue` | 订单列表（按状态筛选） |
| `/portal/pay` | `Pay.vue` | 支付页（支付方式选择/密码输入/余额支付） |
| `/portal/login` | `Login.vue` | 登录注册页（密码登录/验证码登录） |
| `/portal/seckill` | `Seckill.vue` | 秒杀页（场次/倒计时/秒杀商品） |

---

## 11. 配置体系

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| hm-gateway | **8080** | API 网关 |
| user-service | **8083** | 用户服务 |
| item-service | **8081** | 商品服务 |
| cart-service | **8082** | 购物车服务 |
| trade-service | **8084** | 订单/秒杀服务 |
| pay-service | **8085** | 支付服务 |
| search-service | **8089** | 搜索服务 |

### 数据库

| 数据库 | 使用者 | 核心表数量 |
|--------|--------|-----------|
| `hm-user` | user-service | 2（user, address） |
| `hm-item` | item-service, search-service(只读) | 1（item） |
| `hm-cart` | cart-service | 1（cart） |
| `hm-trade` | trade-service | 9（order + 秒杀相关） |
| `hm-pay` | pay-service | 2（pay_order, t_local_message） |

### Nacos 共享配置

所有微服务通过以下共享配置统一管理公共参数：

- `shared-jdbc.yaml` — MySQL 数据源配置
- `shared-log.yaml` — 日志级别和格式配置
- `shared-swagger.yaml` — API 文档配置
- `shared-seata.yaml` — 分布式事务配置（仅涉及跨服务写操作的 item/trade/pay/cart）
- `shared-rabbitmq.yaml` — 消息队列连接配置（仅使用 MQ 的服务）

### 关键环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `NACOS_ADDR` | `192.168.100.128:8848` | Nacos 服务地址 |
| `hm.db.host` | `192.168.100.128` | MySQL 地址 |
| `hm.db.password` | `123` | MySQL 密码 |
| `REDIS_HOST` | `192.168.100.128` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | `123456` | Redis 密码 |
| `JWT_KEYSTORE_PASSWORD` | `hmall123` | JWT 密钥库密码 |

---

## 12. 启动流程

### 环境要求

- Java 11+
- MySQL 8.0+
- Redis 6.x+
- RabbitMQ 3.x+
- Elasticsearch 7.x+
- Nacos 2.x+
- Seata Server 1.x+
- Node.js 18+（前端）

### 初始化数据库

```sql
-- 依次创建各服务数据库
CREATE DATABASE IF NOT EXISTS `hm-user` DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS `hm-item` DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS `hm-cart` DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS `hm-trade` DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS `hm-pay` DEFAULT CHARACTER SET utf8mb4;
```

各服务的表结构由 MyBatis Plus 自动建表或执行对应的 SQL 脚本创建。

### 启动后端微服务

```bash
# 1. 确保基础设施已启动
#    Nacos / Redis / MySQL / RabbitMQ / Elasticsearch / Seata

# 2. 构建项目（hmall 根目录）
mvn clean install -DskipTests

# 3. 按依赖顺序启动（或全部同时启动，Nacos 自动发现）
#    基础服务先启动：user-service → item-service
#    业务服务次之：cart-service → trade-service → pay-service → search-service
#    网关最后：hm-gateway

# 4. 验证：访问 Nacos 控制台 http://192.168.100.128:8848/nacos
#    检查所有服务状态为 UP
```

### 启动前端

```bash
cd hmall-frontend
npm install
npm run dev
```

访问 `http://localhost:5173/#/portal/home`，使用测试账号 `test` / `123456` 登录体验。

### API 文档

各服务 Swagger 文档通过 Knife4j 提供，可直接在线调试：

| 服务 | 地址 |
|------|------|
| 网关聚合 | `http://localhost:8080/doc.html` |
| user-service | `http://localhost:8083/doc.html` |
| item-service | `http://localhost:8081/doc.html` |
| cart-service | `http://localhost:8082/doc.html` |
| trade-service | `http://localhost:8084/doc.html` |
| pay-service | `http://localhost:8085/doc.html` |
| search-service | `http://localhost:8089/doc.html` |

### 快速体验核心链路

```bash
# 1. 登录获取 Token
curl -X POST http://localhost:8080/users/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456"}'

# 2. 搜索商品
curl http://localhost:8080/search/list?key=手机&pageNo=1&pageSize=10

# 3. 加购物车
curl -X POST http://localhost:8080/carts \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"itemId":1,"name":"iPhone 15","spec":"128GB/黑色","price":699900,"image":"..."}'

# 4. 下单
curl -X POST http://localhost:8080/orders \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"paymentType":5,"details":[{"itemId":1,"num":1}]}'

# 5. 余额支付
curl -X POST http://localhost:8080/pay-orders/<payOrderId> \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"id":"<payOrderId>","pw":"<支付密码>"}'
```

---

*本文档基于 hmall C 端微服务 v1.0 编写，各功能的最新状态以实际代码为准。*
