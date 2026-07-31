# hmall 秒杀项目说明文档

> 本文档面向项目理解与快速上手，描述秒杀功能各环节的**设计动机**、**实现思路**和**最终效果**，不涉及具体代码引用与实现细节。

---

## 目录

1. [项目定位](#1-项目定位)
2. [总体架构（三层防超卖）](#2-总体架构三层防超卖)
3. [C 端秒杀流程](#3-c-端秒杀流程)
4. [Redis 缓存与 Lua 原子操作](#4-redis-缓存与-lua-原子操作)
5. [MQ 异步削峰](#5-mq-异步削峰)
6. [库存预热与超时兜底](#6-库存预热与超时兜底)
7. [管理后台秒杀管理](#7-管理后台秒杀管理)
8. [Agent 秒杀工具](#8-agent-秒杀工具)
9. [前端秒杀页面](#9-前端秒杀页面)
10. [配置体系](#10-配置体系)

---

## 1. 项目定位

秒杀是 hmall 枫叶商城的核心营销功能，让运营人员创建限时限量的促销活动，用户在规定时间内以超低价抢购限量商品。

秒杀不同于普通下单——不是"先到先得"，而是"高并发下的库存精确控制"。一场秒杀可能涌入数千用户的并发请求，但库存只有 100 件。如何在毫秒级响应、不超卖、不超购的三重约束下完成业务流程，是秒杀模块的核心挑战。

---

## 2. 总体架构（三层防超卖）

### 设计动机

秒杀面临四个核心难题：

1. **高并发**：热点商品瞬时流量远超市面普通的商品查询
2. **超卖**：库存 100 件不能卖出 101 件——这是资损，是绝对红线
3. **超购**：每人限购 2 件不能买到 3 件——用户体验和公平性问题
4. **请求排队**：所有人在同一秒点"抢购"，但秒杀资格只有 100 个，余下的请求如何优雅排队等待

如果只用 MySQL 行锁解决问题 2，在高并发下数据库负载会瞬间飙升到不可用级别（连接池耗尽、锁等待超时）。必须把"秒杀资格判断"前置到 Redis 层，MySQL 只做"落库确认"。

### 实现思路

设计**三层防线**，逐层削峰，只有通过所有关口的请求才能最终落库：

```
用户点击"秒杀"
  │  数千并发请求
  ▼
第一层：Gateway 滑动窗口限流
  └── sliding_window_rate_limit.lua : 每用户 5 秒最多 1 次请求
  │  拒绝 90%+ 的重复刷新，只剩合理请求
  ▼
第二层：Redis Lua 原子预减
  └── seckill_deduct.lua : 限购检查 + 库存扣减合一
  │  0ms 返回结果，不依赖数据库
  │  判断：有库存且未超购 → 资格拿到 → 发 MQ，库存为 0 → 售罄
  │
  ▼  拿到资格的请求（≤ 库存数）
第三层：MySQL 行锁最终扣减
  └── SeckillOrderListener : SELECT ... FOR UPDATE
  │  创建订单 + 扣减数据库库存，保证最终一致
  ▼
结果：用户看到"排队中" → 前端轮询 → "抢购成功/失败"
```

### 部署位置

秒杀功能**没有独立微服务**，而是承载在现有的 `trade-service`（订单服务）中：

```
trade-service (:8084)
  ├── SeckillController  —  C 端 + 管理端 API
  ├── SeckillServiceImpl —  核心秒杀逻辑（Lua 执行 + MQ 发送）
  ├── SeckillOrderListener  —  MQ 消费，MySQL 最终落库
  ├── SeckillPreheatTask —  库存预热定时任务
  └── SeckillTimeoutTask  —  超时订单兜底扫描

admin-service (:8090)
  └── SeckillAdminController  —  纯代理 → Feign → trade-service
```

### 最终效果

- 秒杀场景下库存精确不超卖（三层防线保证）
- 每人限购严格执行（Lua 层原子校验）
- 未抢到的用户立即看到"已售罄"（Redis 0ms 判定），不会进入数据库排队
- 抢到的用户异步落库（MQ 削峰），体验流畅

---

## 3. C 端秒杀流程

### 设计动机

用户进入秒杀有五个关键步骤：看活动列表 → 挑场次 → 看详情 → 点抢购 → 等结果。每一步都需要在"响应速度"和"数据准确性"之间权衡。

### 实现思路

**活动列表**（`GET /seckill/activities`）：从 MySQL 查活动+场次+商品关联 → 从 Redis 读实时库存 → 组装返回。秒杀价格从商品关联表获取，实时库存从 Redis 读（预热后写入）。

**商品详情**（`GET /seckill/products/{relationId}`）：查 MySQL 商品关联数据 → 拼 Redis 实时库存 + 限购计数 → 返回含库存进度条的详情。

**秒杀下单**（`POST /seckill/order/{relationId}`）——核心流程：

```
1. 用户级分布式锁（seckill:lock:user:{uid}, TTL=5s）
   └── 防同一用户并发重复提交
2. Lua 脚本原子预减
   └── seckill_deduct.lua：库存检查 + 限购 + 扣减
   └── 返回 1(成功) / 0(售罄) / -1(未预热) / -2(超购)
3. 预减成功 → 发送 SeckillOrderMessage 到 MQ
4. 写入 seckill:result:{uid}:{relationId} = 0（排队中）
5. 返回 pending（"正在排队，请稍候"）

MQ 消费 → SeckillOrderListener 创建订单
6. 更新 seckill:result:{uid}:{relationId} = orderId（成功）
```

**结果查询**（`GET /seckill/result/{relationId}`）：前端每隔 1 秒轮询 → Redis 读结果 → 订单号=成功 / "0"=排队 / null=失败或超时。

### 最终效果

- 用户点击抢购 → 立即返回"排队中"（Redis 0ms 判定）
- 前端每 1 秒自动查询结果，获得订单号后跳转支付页
- 未抢到的用户立即看到"已售罄"，不会等待 MQ 消费
- 每人限购数严格执行，不会出现"抢到 3 件但限购 2 件"

---

## 4. Redis 缓存与 Lua 原子操作

### 设计动机

秒杀的库存扣减必须**快且准确**。如果用 MySQL 行锁：
- 每次扣库存需要网络往返 + 磁盘 I/O → 10ms+
- 高并发下行锁等待超时 → 连接池耗尽

必须是内存操作，且必须是原子操作——如果"读库存→判断→减库存"分三步执行，在并发下必然出现超卖。

### 实现思路

**Redis Key 设计**：

| Key | 类型 | 说明 |
|-----|------|------|
| `seckill:stock:{relationId}` | String | 实时剩余库存（预热时 SETNX 写入初始值） |
| `seckill:limit:{relationId}` | Hash | 用户限购计数（field=userId, value=已购数） |
| `seckill:lock:user:{userId}` | String | 用户级分布式锁（TTL=5s，防重复提交） |
| `seckill:result:{userId}:{relationId}` | String | 秒杀结果（0=排队中，orderId=成功，TTL=120s） |

**Lua 脚本 `seckill_deduct.lua`**——限购检查 + 库存扣减合一：

```
KEYS[1] = seckill:stock:{id}   → GET stock → 不足 → 返回 0（售罄）
KEYS[2] = seckill:limit:{id}   → HGET count → 超购 → 返回 -2（超购）
ARGV[1] = userId               → 库存未初始化 → 返回 -1（未预热）
ARGV[2] = quantity             → 全部通过 → DECRBY stock + HINCRBY limit → 返回 1
ARGV[3] = limitNum
```

**为什么用 Lua 而不是 Redis 事务？** Redis 的 MULTI/EXEC 事务无法做条件判断——"如果库存 > 0 才扣减"这种逻辑在事务中无法实现。Lua 脚本在 Redis 服务端原子执行，支持完整的分支逻辑，是秒杀场景的标准方案。

**用户级分布式锁**：在 Lua 脚本之外，先加锁再执行脚本。目的是防止同一用户在前端连点两次——第一次请求在 Lua 返回之前，第二次请求也进入了同样的流程。分布式锁 TTL 设为 5 秒（远大于 Lua 执行时间），过期自动释放。

### 最终效果

- 库存扣减在 Redis 中原子完成，0ms 判定资格
- 即使 1000 人同时抢 100 件商品，也不会有第 101 件被卖出
- 如果有用户绕过前端限制（如抓取 API 直接调用），分布式锁 + 限购 Lua 双重保护

---

## 5. MQ 异步削峰

### 设计动机

Lua 脚本已经判定了"谁有资格买"，但 Redis 扣减只是预减——真正的订单落库、库存最终扣减还需要走 MySQL。如果直接在秒杀请求中同步做这些，热点商品的数据库连接会瞬间被打满。

### 实现思路

拿到秒杀资格后，不是直接创建订单，而是发一条 MQ 消息：

```
SeckillServiceImpl.doSeckill()
  → Lua 预减成功
  → rabbitTemplate.convertAndSend("seckill.topic", "seckill.order", message)
  → 返回 pending

SeckillOrderListener.onSeckillOrder()
  → SELECT ... FOR UPDATE（行锁）
  → 检查 WHERE stock >= quantity
  → 扣减 MySQL 库存
  → 创建 order + order_detail + seckill_order（事务）
  → 写 Redis 秒杀结果（orderId）
```

**交换机/队列：**

| 组件 | 名称 | 说明 |
|------|------|------|
| Exchange | `seckill.topic` | Topic 交换机 |
| Queue | `seckill.order.queue` | 持久化队列 |
| Routing Key | `seckill.order` | 路由键 |

**为什么还要 MySQL 最终扣减？** Redis 只是预扣，已经拿到资格的请求仍可能因多种原因失败（MQ 消息丢失超时重试、库存快照不一致）。MySQL 的 `SELECT ... FOR UPDATE` 行锁 + `WHERE stock >= quantity` 作为最终防线，保证数据绝对一致。

### 最终效果

- 秒杀请求瞬间返回（Lua ~0ms），不阻塞在数据库
- MQ 异步消费削峰填谷，数据库负载平滑
- Redis 预减 + MySQL 最终确认 = 双写保证最终一致
- 消息发送失败时预减不回滚（保守策略：宁可少卖，不可超卖）

---

## 6. 库存预热与超时兜底

### 设计动机

秒杀开始前，库存数据在 MySQL 的 `seckill_product_relation` 和 `seckill_daily_stock` 表中。如果等用户请求了才去读 MySQL → 写 Redis，高峰期的第一次请求会有不可接受的延迟。必须在活动开始前就"预热"到 Redis。

同样，如果用户抢到资格但 30 分钟内未支付，需要自动释放库存，让商品可以重新被抢。

### 实现思路

**库存预热**（`SeckillPreheatTask`）：

```
每分钟执行一次
  → 扫描 seckill_session 表（start_time 在未来 5 分钟内）
  → 对每个场次的商品：
    → SETNX seckill:stock:{relationId} = 初始库存
    → 初始化 seckill_daily_stock 快照
```

`SETNX`（SET if Not eXists）保证预热不会覆盖已有的实时库存。管理端也可手动触发预热：`POST /admin/seckill/relation/preheat/{id}`。

**超时兜底**（双路径保证）：

| 路径 | 机制 | 频率 |
|------|------|------|
| 正常路径 | MQ 延迟消息（30 分钟）→ 关单 → INCRBY 回补 Redis 库存 | 每订单触发一次 |
| 兜底路径 | `SeckillTimeoutTask` 定时扫描 → `status=1 AND create_time < NOW()-30min` | 每 5 分钟 |

双路径设计的原因：延迟消息可能因 MQ 重启或网络故障丢失，定时扫描作为兜底保证库存最终被回收。

### 最终效果

- 秒杀活动开始前 5 分钟内自动预热，用户打开页面即可看到实时库存
- 用户抢到后不支付 → 30 分钟后自动释放库存（含定时兜底）
- 运营设置 100 件库存，卖完 100 件自动售罄，超时未付的库存回补后可继续卖

---

## 7. 管理后台秒杀管理

### 设计动机

运营人员需要创建秒杀活动、配置场次和商品、查看秒杀订单和库存数据。管理后台需要提供完整的秒杀 CRUD 能力。

### 实现思路

管理后台的秒杀功能分散在两个微服务中：

| 服务 | 模块 | 职责 |
|------|------|------|
| `admin-service` | `SeckillAdminController` | 路由代理，Feign 转发到 `trade-service` |
| `trade-service` | `SeckillController`（管理端接口） | 实际的 CRUD 逻辑 |

```
前端管理页面 → /api/admin/seckill/** → Gateway → admin-service
  → SeckillAdminController → Feign → trade-service /seckill/admin/**
  → SeckillController（管理端方法）→ MySQL CRUD
```

管理端提供 13 个接口：促销活动 CRUD、场次 CRUD、商品关联 CRUD、订单查询、库存快照查询、手动预热。

**4 个管理 Tab：**

| Tab | 功能 |
|-----|------|
| 活动管理 | 创建/编辑/删除秒杀活动，设置活动时间段 |
| 场次管理 | 在活动下创建场次（如"上午场 10:00-12:00"），设置竞拍价的原始价格和秒杀折后价 |
| 商品管理 | 为场次关联秒杀商品，设置秒杀价、库存、限购数 |
| 秒杀订单 | 查看秒杀订单列表，按活动/场次/状态筛选 |

### 最终效果

- 运营人员创建秒杀活动 → 添加场次 → 关联商品 → 活动开始前自动预热
- 秒杀中可实时查看剩余库存和已售数量
- 秒杀后可查看秒杀订单数据和参与用户

---

## 8. Agent 秒杀工具

### 设计动机

C 端用户想参与秒杀时，传统流程是：打开 App → 找到秒杀入口 → 筛选场次 → 找到商品 → 点抢购。如果通过 Agent 自然语言完成，体验更流畅："现在有什么秒杀活动"→ Agent 列出活动和商品 → "抢第一个"→ Agent 秒杀下单。

### 实现思路

**CustomerAgent 提供 3 个秒杀工具**：

| 工具 | 功能 | 调用 API |
|------|------|----------|
| `get_seckill_activities_api` | 获取秒杀活动列表（含场次、商品、实时库存） | `GET /seckill/activities` |
| `get_seckill_product_api` | 查看秒杀商品详情 | `GET /seckill/products/{id}` |
| `do_seckill_api` | 执行秒杀下单（需 **interrupt 二次确认**） | `POST /seckill/order/{id}` |

**AdminAgent 提供 4 个秒杀工具**：活动分页查询、商品关联查询（含实时库存）、订单分页查询、库存快照查询。`generate_daily_report` 也并发调用秒杀数据聚合到日报中。

### 最终效果

- 用户："现在有什么秒杀"→ Agent 列出活动 + 商品 + 倒计时 + 库存进度
- 用户："抢第一个"→ Agent 展示二次确认卡片（秒杀价格、限购数）→ 确认后秒杀
- 运营："生成今天秒杀活动的日报"→ Agent 返回秒杀订单统计 + 库存数据

---

## 9. 前端秒杀页面

### C 端秒杀页

| 页面 | 路由 | 功能 |
|------|------|------|
| `SeckillList.vue` | `/portal/seckill` | 秒杀活动列表：顶部横幅 + 场次 Tab 切换 + 商品网格 + 倒计时 + 库存进度条 |
| `SeckillDetail.vue` | `/portal/seckill/:id` | 秒杀详情：商品大图 + 秒杀价格面板 + 倒计时 + 库存进度 + 立即抢购按钮 |

**倒计时**：前端基于服务端返回的活动/场次时间计算，展示"距开始还有 XX:XX:XX"或"距结束还有 XX:XX:XX"。

**库存进度条**：`已售/总库存` 百分比，实时呈现抢购热度。

**抢购按钮交互**：未开始 → 灰色禁用"即将开始"；进行中 → 红色"立即抢购"；已售罄 → "已售罄"；已结束 → "已结束"。

### 管理后台秒杀页

| 页面 | 路由 | 功能 |
|------|------|------|
| `SeckillManage.vue` | `/admin/seckill` | 4 个 Tab：活动管理 / 场次管理 / 商品管理 / 秒杀订单 |

### 最终效果

- C 端秒杀列表页可以实时看到库存进度和倒计时
- 点击商品进入详情页，抢购按钮状态动态变化
- 抢购成功后轮询结果 → 跳转支付
- 管理后台 4 个 Tab 覆盖完整的秒杀运营流程

---

## 10. 配置体系

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| `trade-service` | 8084 | 承载秒杀逻辑 + 秒杀数据库 |
| `admin-service` | 8090 | 管理后台（Feign 代理秒杀管理接口） |

### 秒杀数据库表

| 表 | 所属服务 | 说明 |
|----|---------|------|
| `seckill_promotion` | trade-service | 秒杀活动表 |
| `seckill_session` | trade-service | 秒杀场次表 |
| `seckill_product_relation` | trade-service | 活动-商品关联（秒杀价/库存/限购） |
| `seckill_daily_stock` | trade-service | 每日库存快照（UNIQUE on relation_id + date） |
| `seckill_order` | trade-service | 秒杀订单关联 |

### MQ 配置

| 组件 | 值 |
|------|-----|
| Exchange | `seckill.topic`（Topic 类型） |
| Queue | `seckill.order.queue`（持久化） |
| Routing Key | `seckill.order` |
| 延迟队列 | `trade.delay.direct`（复用 trade-service 延迟交换机，30 分钟超时） |

### Redis Key 前缀

| 前缀 | 类型 | TTL |
|------|------|-----|
| `seckill:stock:` | String | 无 TTL（手动预热写入，售完自动 DECRBY） |
| `seckill:limit:` | Hash | 无 TTL（秒杀结束后手动清理） |
| `seckill:lock:user:` | String | 5 秒 |
| `seckill:result:` | String | 120 秒 |

### 定时任务

| 任务 | 频率 | 功能 |
|------|------|------|
| `SeckillPreheatTask` | 每 1 分钟 | 预热未来 5 分钟内的场次库存到 Redis |
| `SeckillTimeoutTask` | 每 5 分钟 | 扫描超时 30 分钟的秒杀订单，兜底回补库存 |

### Lua 脚本

| 脚本 | 位置 | 功能 |
|------|------|------|
| `seckill_deduct.lua` | `hm-common` | Redis 原子库存预减 + 限购检查 |
| `sliding_window_rate_limit.lua` | `hm-common` | Gateway 滑动窗口限流 |

---

*本文档基于 hmall 秒杀功能当前版本编写，各功能的最新状态以实际代码为准。*
