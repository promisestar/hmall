# hmall 管理后台秒杀管理实现报告

> 版本：v1.0  
> 日期：2026-07-15  
> 设计文档：`docs/秒杀功能实现/seckill-admin-design.md`

---

## 1. 实现概述

在现有管理后台基础上新增秒杀管理功能，覆盖活动/场次/商品关联的完整 CRUD、手动预热、秒杀订单查询和每日库存查看。后端在 trade-service 暴露 17 个管理接口（`/seckill/admin/**`），admin-service 通过 Feign 代理转发；前端新增 4-Tab 管理页面（`SeckillManage.vue`）。

---

## 2. 文件清单

### 2.1 后端 — trade-service

| 文件 | 类型 | 说明 |
|------|------|------|
| `domain/dto/SeckillPromotionDTO.java` | 新增 | 活动创建/修改 DTO |
| `domain/dto/SeckillSessionDTO.java` | 新增 | 场次创建/修改 DTO |
| `domain/dto/SeckillProductRelationDTO.java` | 新增 | 商品关联创建/修改 DTO |
| `domain/vo/SeckillPromotionAdminVO.java` | 新增 | 活动管理 VO |
| `domain/vo/SeckillSessionAdminVO.java` | 新增 | 场次管理 VO |
| `domain/vo/SeckillProductRelationAdminVO.java` | 新增 | 商品关联管理 VO |
| `domain/vo/SeckillOrderAdminVO.java` | 新增 | 秒杀订单管理 VO |
| `domain/vo/SeckillStockAdminVO.java` | 新增 | 每日库存快照 VO |
| `service/SeckillService.java` | 修改 | 新增 17 个管理端方法声明 |
| `service/impl/SeckillServiceImpl.java` | 修改 | 实现管理端方法 + 级联删除 + 缓存清除 |
| `controller/SeckillController.java` | 修改 | 新增 `/seckill/admin/**` 接口 |

### 2.2 后端 — admin-service

| 文件 | 类型 | 说明 |
|------|------|------|
| `feign/TradeFeignClient.java` | 修改 | 新增 17 个秒杀管理 Feign 方法 |
| `feign/fallback/TradeFeignFallbackFactory.java` | 修改 | 新增对应 Fallback 实现 |
| `controller/SeckillAdminController.java` | 新增 | `/admin/seckill/**` 代理控制器 |
| `resources/seckill-admin-menu.sql` | 新增 | 菜单 + 资源初始化 SQL |

### 2.3 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `api/admin/seckill.ts` | 新增 | 秒杀管理 API 模块 |
| `types/admin.ts` | 修改 | 新增秒杀管理类型定义（8 个 interface） |
| `views/admin/SeckillManage.vue` | 新增 | 4-Tab 管理页面 |
| `router/index.ts` | 修改 | 新增 `/admin/seckill` 路由 |
| `views/admin/AdminLayout.vue` | 修改 | 面包屑映射 + AlarmClock 图标 |

---

## 3. 后端实现要点

### 3.1 SeckillServiceImpl 管理方法

**活动管理**：
- `queryPromotionPage`：分页查询，额外统计每个活动的场次数和商品数（`selectCount`）
- `createPromotion`：根据日期自动计算 status（0未开始/1进行中/2已结束）
- `updatePromotion`：进行中的活动禁止修改日期
- `deletePromotion`：`@Transactional` 级联删除场次（含商品关联和库存快照），进行中禁止删除

**场次管理**：
- `querySessionPage`：批量查询活动标题（`selectBatchIds` + `Map` 映射），避免 N+1
- `deleteSession`：级联删除商品关联 + 清除 Redis 缓存 + 删除库存快照

**商品关联管理**：
- `queryRelationPage`：批量查询商品信息（`ItemClient.queryItemsByIds`），构建 `Map<Long, ItemDTO>` 填充到 VO
- `buildRelationAdminVO`：从 Redis 读取实时库存，计算剩余量和已售量，判断预热状态
- `deleteRelation`：清除 Redis `seckill:stock:` 和 `seckill:limit:` 缓存
- `manualPreheat`：复用 `preheat()` 方法，与定时任务调用同一逻辑

**秒杀订单管理**：
- `querySeckillOrderPage`：批量查询商品关联（`selectBatchIds`）获取 productId 和 seckillPrice，再批量查询商品名称

**库存查询**：
- `queryStockStatus`：查询指定商品关联的每日库存快照列表，按日期倒序

### 3.2 关键辅助方法

| 方法 | 说明 |
|------|------|
| `computePromotionStatus` | 根据日期计算活动状态 |
| `loadPromotionTitleMap` | 批量查询活动标题，返回 `Map<Long, String>` |
| `loadItemMap` | 批量查询商品信息，返回 `Map<Long, ItemDTO>`（Feign 调用失败时返回空 Map） |
| `loadRelationMap` | 批量查询商品关联，返回 `Map<Long, SeckillProductRelation>` |
| `buildRelationAdminVO` | 构建 商品关联管理 VO（含商品信息和 Redis 实时库存） |
| `deleteSessionCascade` | 级联删除场次下的商品关联和库存快照 |
| `clearRelationCache` | 清除 Redis 中的库存和限购缓存 |

### 3.3 Feign 代理模式

`TradeFeignClient` 使用泛化类型（`PageDTO<Object>`、`Object`、`Long`、`void`、`List<Object>`）传递和接收数据，因为 admin-service 不依赖 trade-service 的 DTO/VO 类。`SeckillAdminController` 用 `R.ok()` 包装返回给前端，前端响应拦截器自动解包 `R<T>`。

---

## 4. 前端实现要点

### 4.1 Tab 懒加载

`handleTabChange` 在首次切换到某 Tab 时触发数据加载，避免一次性加载全部数据。`loadedTabs` Set 记录已加载的 Tab。

### 4.2 级联下拉

商品管理 Tab 中：
- 搜索栏：活动下拉变更时，清空场次下拉并重新加载场次选项
- 对话框：活动下拉变更时，清空场次下拉并重新加载该活动的场次列表

### 4.3 价格转换

前端输入用「元」（`el-input-number` precision=2），提交时转为「分」（`Math.round(yuan * 100)`），显示时转为「元」（`(cents / 100).toFixed(2)`）。

### 4.4 日期时间格式

后端返回 ISO 格式（`2026-07-15T10:00:00`），前端 `formatDateTime` 函数将 `T` 替换为空格并截取到秒。

---

## 5. 菜单 SQL

`admin-service/src/main/resources/seckill-admin-menu.sql` 包含：

1. **菜单**：插入 `id=10` 的秒杀管理菜单（`/admin/seckill`，图标 `AlarmClock`），系统管理 sort 后移
2. **角色-菜单关联**：超级管理员角色（role_id=1）分配秒杀管理菜单
3. **资源分类**：新增「秒杀管理」分类
4. **资源（权限点）**：18 个 API 资源点，覆盖全部管理接口
5. **角色-资源关联**：超级管理员分配全部秒杀管理资源

SQL 脚本幂等设计，可安全重复执行。

执行方式：
```bash
mysql -u root -p hm-admin < admin-service/src/main/resources/seckill-admin-menu.sql
```

---

## 6. 测试要点

### 6.1 功能测试

| 场景 | 验证点 |
|------|--------|
| 创建活动 | 日期为未来 → status=0；日期包含今天 → status=1 |
| 创建场次 | 场次时间在活动日期范围内 |
| 创建商品关联 | 场次属于指定活动；秒杀价/库存/限购正确保存 |
| 手动预热 | Redis `seckill:stock:{id}` 写入；`seckill_daily_stock` 初始化当日快照 |
| 删除活动 | 级联删除场次、商品关联、库存快照；Redis 缓存清除 |
| 删除场次 | 级联删除商品关联和库存快照 |
| 进行中保护 | 活动/场次 status=1 时删除和修改日期被拒绝 |
| 秒杀订单查询 | 按 status/relationId/userId 筛选 |
| 库存查询 | 返回每日库存快照列表 |

### 6.2 边界测试

- 删除不存在的活动 → 返回"秒杀活动不存在"
- 创建商品关联时指定不属于该活动的场次 → 返回"场次不属于该活动"
- Feign 调用 item-service 失败 → 商品名称为空但不影响列表展示

---

## 7. 遗留问题与后续优化

| 问题 | 说明 | 优先级 |
|------|------|--------|
| Feign 错误传递 | `void` 返回的 Feign 方法在 trade-service 抛 `BizIllegalException` 时，错误信息可能丢失 | P2 |
| 菜单路径配置 | 菜单 `path` 字段需要与前端路由 `/admin/seckill` 一致，前端从 adminStore.menus 动态加载侧边栏 | — |
| 批量操作 | 当前仅支持单条 CRUD，未实现批量上下架/删除 | P2 |
| 活动状态自动更新 | 活动 status 在创建/修改时计算，无定时任务自动更新过期活动状态 | P3 |
