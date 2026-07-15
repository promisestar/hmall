# hmall 管理后台秒杀管理设计文档

> 版本：v1.0  
> 日期：2026-07-15  
> 参考文档：`docs/秒杀功能实现/seckill-design.md`（C 端秒杀设计）、`docs/管理后台相关文档/admin-service-design.md`（管理后台设计）

---

## 1. 概述

### 1.1 背景与目标

hmall 已完成 C 端秒杀功能（活动展示、秒杀下单、结果轮询），三层防超卖架构（Gateway 限流 → Redis Lua 预减 → MySQL 行锁）已上线。但秒杀数据（活动、场次、商品关联）目前只能通过手动 SQL 插入，运营人员无法通过管理界面管理秒杀活动。

本设计在现有管理后台（admin-service + Vue 管理前端）基础上，新增**秒杀管理功能**，覆盖活动/场次/商品关联的完整 CRUD、手动预热、秒杀订单查询和库存状态查看。

### 1.2 设计原则

| 原则 | 说明 |
|------|------|
| **复用现有模式** | 后端遵循 trade-service `/orders/admin/**` 模式暴露管理接口，admin-service 通过 Feign 代理；前端遵循 ItemManage.vue 的搜索+表格+对话框模式 |
| **不侵入 C 端** | 管理接口路径 `/seckill/admin/**`，与 C 端 `/seckill/**` 隔离，Gateway 限流仅作用于 C 端 |
| **级联保护** | 删除活动级联删除场次和商品关联，删除场次级联删除商品关联；进行中的活动/场次禁止删除和修改关键属性 |
| **批量查询优化** | 商品关联和订单列表使用 `ItemClient.queryItemsByIds` 批量查询商品信息，避免 N+1 |
| **状态自动计算** | 活动 status 根据日期自动计算，场次 status 根据时间自动计算，无需手动维护 |

---

## 2. 整体架构

### 2.1 调用链路

```
管理前端 (SeckillManage.vue)
  │
  │  /admin/seckill/** (admin-token)
  ▼
hm-gateway
  │  校验 admin JWT → 传递 admin-id
  ▼
admin-service (SeckillAdminController)
  │  Feign 代理转发
  ▼
trade-service (SeckillController /seckill/admin/**)
  │  SeckillServiceImpl
  ├──→ MySQL (5 张秒杀表 CRUD)
  ├──→ Redis (库存缓存读写/清除)
  └──→ item-service (Feign: 商品信息批量查询)
```

### 2.2 前端 Tab 布局

```
SeckillManage.vue
├── Tab 1: 活动管理（列表 + 增删改查）
├── Tab 2: 场次管理（列表 + 增删改查，按活动筛选）
├── Tab 3: 商品管理（列表 + 增删改查 + 预热 + 库存详情）
└── Tab 4: 秒杀订单（列表，多条件筛选）
```

---

## 3. 数据模型

复用 C 端秒杀已有的 5 张表，不新增表：

| 表名 | 说明 | 管理操作 |
|------|------|---------|
| `seckill_promotion` | 秒杀活动 | CRUD |
| `seckill_session` | 秒杀场次（关联活动） | CRUD |
| `seckill_product_relation` | 商品关联（含秒杀价/库存/限购） | CRUD + 预热 |
| `seckill_daily_stock` | 每日库存快照 | 查询 + 级联删除 |
| `seckill_order` | 秒杀订单关联 | 查询 |

---

## 4. 后端接口设计

### 4.1 trade-service 管理接口（/seckill/admin/**）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/seckill/admin/promotion/page` | 分页查询活动（title/status 筛选） |
| GET | `/seckill/admin/promotion/{id}` | 活动详情 |
| POST | `/seckill/admin/promotion` | 创建活动 |
| PUT | `/seckill/admin/promotion` | 修改活动 |
| DELETE | `/seckill/admin/promotion/{id}` | 删除活动（级联） |
| GET | `/seckill/admin/session/page` | 分页查询场次（promotionId 筛选） |
| GET | `/seckill/admin/session/{id}` | 场次详情 |
| POST | `/seckill/admin/session` | 创建场次 |
| PUT | `/seckill/admin/session` | 修改场次 |
| DELETE | `/seckill/admin/session/{id}` | 删除场次（级联） |
| GET | `/seckill/admin/relation/page` | 分页查询商品关联（sessionId/promotionId 筛选） |
| GET | `/seckill/admin/relation/{id}` | 商品关联详情 |
| POST | `/seckill/admin/relation` | 创建商品关联 |
| PUT | `/seckill/admin/relation` | 修改商品关联 |
| DELETE | `/seckill/admin/relation/{id}` | 删除商品关联 |
| POST | `/seckill/admin/relation/preheat/{id}` | 手动预热 |
| GET | `/seckill/admin/order/page` | 分页查询秒杀订单（status/relationId/userId 筛选） |
| GET | `/seckill/admin/stock/{relationId}` | 查询每日库存快照 |

### 4.2 admin-service Feign 代理（/admin/seckill/**）

`TradeFeignClient` 扩展 17 个秒杀管理 Feign 方法，`SeckillAdminController`（`/admin/seckill/**`）逐一代理转发，用 `R<T>` 包装返回前端。

### 4.3 状态保护规则

| 实体 | 进行中(status=1)时的限制 |
|------|------------------------|
| 活动 | 禁止删除、禁止修改开始/结束日期 |
| 场次 | 禁止删除、禁止修改开始/结束时间 |
| 商品关联 | 禁止修改库存和秒杀价 |

---

## 5. 前端页面设计

### 5.1 活动管理 Tab

- **搜索栏**：活动标题输入 + 状态下拉筛选
- **表格列**：ID / 标题 / 开始日期 / 结束日期 / 状态(Tag) / 场次数 / 商品数 / 创建时间 / 操作
- **对话框**：标题 + 开始日期(DatePicker) + 结束日期(DatePicker)

### 5.2 场次管理 Tab

- **搜索栏**：活动下拉筛选
- **表格列**：ID / 所属活动 / 场次名称 / 开始时间 / 结束时间 / 状态(Tag) / 商品数 / 操作
- **对话框**：所属活动(Select) + 场次名称 + 开始时间(DateTimePicker) + 结束时间(DateTimePicker)

### 5.3 商品管理 Tab

- **搜索栏**：活动下拉 + 场次下拉（级联）
- **表格列**：ID / 图片 / 商品名称 / 秒杀价 / 总库存 / 剩余 / 已售 / 限购 / 预热状态(Tag) / 操作(编辑/预热/库存/删除)
- **对话框**：所属活动(Select) + 所属场次(Select,级联) + 商品ID(InputNumber) + 秒杀价(元) + 库存 + 限购数
- **库存详情对话框**：每日库存快照列表（批次日期 / 当日库存 / 已售 / 剩余）

### 5.4 秒杀订单 Tab

- **搜索栏**：状态下拉 + 商品关联ID + 用户ID
- **表格列**：ID / 订单ID / 商品名称 / 用户ID / 数量 / 秒杀价 / 状态(Tag) / 下单时间

---

## 6. 关键设计决策

### 6.1 级联删除策略

删除活动时，先查询所有子场次，对每个场次执行 `deleteSessionCascade`：
1. 查询该场次下的商品关联
2. 逐个清除 Redis 库存/限购缓存
3. 批量删除商品关联记录
4. 批量删除每日库存快照
5. 删除场次记录

整个过程在 `@Transactional` 事务内执行。

### 6.2 Redis 缓存清除

删除商品关联时，同步清除 Redis 中的 `seckill:stock:{relationId}` 和 `seckill:limit:{relationId}`，避免残留缓存导致后续创建的新关联复用同一 ID 时读到旧数据。

### 6.3 手动预热

管理端可手动触发指定商品关联的 Redis 库存预热，复用 `SeckillService.preheat()` 方法（与定时预热任务调用同一方法），将 MySQL 中的库存写入 Redis 并初始化当日库存快照。

### 6.4 商品信息批量查询

商品关联列表和秒杀订单列表需要展示商品名称，使用 `ItemClient.queryItemsByIds(Collection<Long>)` 批量查询，构建 `Map<Long, ItemDTO>` 后填充到 VO 中，避免 N+1 查询问题。

### 6.5 价格处理

后端秒杀价以「分」存储（Integer），前端展示时转为「元」（除以100），输入时转为「分」（乘以100取整），与 `ItemManage.vue` 的 `formatPrice` 模式一致。
