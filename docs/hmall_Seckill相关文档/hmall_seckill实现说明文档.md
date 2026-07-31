# hmall 秒杀实现说明文档

> 整合 hmall 枫叶商城秒杀功能的全部实现报告，涵盖 C 端秒杀模块和管理后台秒杀管理两部分。

---

## 目录

- [第一部分：C 端秒杀模块实现](#第一部分c-端秒杀模块实现) — 功能验证 / 测试结果 / 性能分析 / 关键决策 / 文件清单
- [第二部分：管理后台秒杀管理实现](#第二部分管理后台秒杀管理实现) — 管理功能实现说明

---

# 第一部分：C 端秒杀模块实现
> 版本：v1.1  
> 日期：2026-07-15  
> 设计文档：`docs/秒杀功能实现/seckill-design.md`  
> 参考文档：`docs/redis功能相关文档/redis-application-analysis.md` 3.7 + 3.8 节

---

## 一、实现概况

本次实现按照 `redis-application-analysis.md` 3.7 节（秒杀库存 Lua 原子预减）和 3.8 节（滑动窗口限流）的规划，在 hmall 中新增了完整的秒杀功能模块，涵盖 **Gateway 滑动窗口限流、Redis Lua 原子预减、MQ 异步下单、MySQL 行锁兜底、活动预热、超时回补、前端秒杀页面** 等全链路能力。

### 1.1 文件变更统计

| 类别 | 数量 | 说明 |
|------|------|------|
| hm-common 新增 Lua 脚本 | 2 | `seckill_deduct.lua` + `sliding_window_rate_limit.lua` |
| hm-common 新增 Java | 1 | `RateLimitUtil.java` |
| hm-common 修改 | 1 | `RedisConfig.java`（@Import 增加 RateLimitUtil） |
| hm-gateway 新增 Java | 2 | `RateLimitFilter.java` + `RateLimitProperties.java` |
| hm-gateway 修改 | 1 | `application.yml`（增加 hm.ratelimit 配置） |
| trade-service 新增 SQL | 1 | `V2__seckill_tables.sql`（5 张表） |
| trade-service 新增 PO | 5 | SeckillPromotion / SeckillSession / SeckillProductRelation / SeckillDailyStock / SeckillOrder |
| trade-service 新增 Mapper | 5 | 上述 5 张表对应的 Mapper |
| trade-service 新增 VO | 3 | SeckillActivityVO / SeckillProductVO / SeckillResultVO |
| trade-service 新增 DTO | 1 | SeckillOrderMessage（MQ 消息体） |
| trade-service 新增 Service | 2 | SeckillService 接口 + SeckillServiceImpl |
| trade-service 新增 Controller | 1 | SeckillController |
| trade-service 新增 Listener | 1 | SeckillOrderListener（MQ 消费者） |
| trade-service 新增 Task | 2 | SeckillPreheatTask + SeckillTimeoutTask |
| trade-service 修改 | 2 | MQConstants（+秒杀常量）+ OrderServiceImpl（cancelOrder 增加秒杀回补） |
| hm-api 新增 DTO | 1 | SeckillProductDTO |
| **后端新增合计** | **27** | — |
| **后端修改合计** | **4** | — |
| 前端新增 API | 1 | `api/seckill.ts`（含轮询工具） |
| 前端新增页面 | 2 | `SeckillList.vue` + `SeckillDetail.vue` |
| 前端修改 | 2 | `router/index.ts`（+路由）+ `PortalLayout.vue`（+导航入口） |
| **前端合计** | **5** | — |
| **总计改动** | **36** | — |

---

## 二、架构总览

### 2.1 三层防超卖架构

```
前端 (Vue 3)
  │  POST /seckill/order/{relationId}
  │  → Vite proxy → Gateway (8080)
  │
  ├─ 第一层：RateLimitFilter (order=1)
  │    └─ Redis ZSET 滑动窗口 Lua → 5s 内每用户仅 1 次
  │       └─ 超限 → 429 / Redis 不可用 → fail-open 放行
  │
  ├─ 第二层：SeckillServiceImpl (trade-service 8085)
  │    ├─ per-user 分布式锁 (SET NX EX, TTL=5s)
  │    └─ seckill_deduct.lua 原子预减 (限购+库存合一)
  │       └─ 成功 → MQ 消息 → 失败直接返回（不穿透 DB）
  │
  └─ 第三层：SeckillOrderListener (MQ 消费者)
       ├─ SELECT ... FOR UPDATE 行锁
       ├─ UPDATE ... WHERE stock >= quantity
       ├─ 创建订单 (order + order_detail + seckill_order)
       ├─ 发送延迟消息 (30min 超时取消)
       └─ 设 Redis 结果 key (前端轮询用)
```

### 2.2 请求流转链路

```
用户点击"立即抢购"
  │
  ├─ POST /seckill/order/{relationId}?quantity=1
  │   → Gateway AuthGlobalFilter (order=0): 解析 JWT → 设 user-info 头
  │   → Gateway RateLimitFilter (order=1): 滑动窗口限流
  │      ├─ 429 → 前端提示"请求过于频繁"
  │      └─ 放行 → trade-service
  │
  ├─ SeckillController.doSeckill()
  │   → SeckillServiceImpl.doSeckill()
  │      ├─ 分布式锁失败 → "请勿重复提交"
  │      ├─ Lua 返回 -2 → "超过限购数量"
  │      ├─ Lua 返回 -1 → "活动未开始"
  │      ├─ Lua 返回 0  → "已售罄"
  │      └─ Lua 返回 1  → 发 MQ → 返回 "pending"
  │
  ├─ 前端收到 pending → 开始轮询 (每 1.5s, 最多 30 次)
  │   GET /seckill/result/{relationId}
  │   → SeckillServiceImpl.getOrderResult()
  │      └─ 读 Redis key seckill:result:{userId}:{relationId}
  │         ├─ null → "pending"（仍在排队）
  │         ├─ "0"  → "failed"（MySQL 库存不足）
  │         └─ "orderId" → "success"（下单成功）
  │
  └─ MQ 异步消费 (SeckillOrderListener)
       → FOR UPDATE 行锁 → 扣 MySQL 库存 → 创建订单 → 设结果 key
       → 前端下次轮询得到 success → 跳转支付
```

---

## 三、后端实现详情

### 3.1 数据库表（5 张表）

迁移脚本：`trade-service/src/main/resources/db/migration/V2__seckill_tables.sql`

| 表名 | 说明 | 关键设计 |
|------|------|---------|
| `seckill_promotion` | 秒杀活动 | title, start_date, end_date, status |
| `seckill_session` | 秒杀场次 | promotion_id(FK), start_time, end_time；idx_promotion 索引 |
| `seckill_product_relation` | 商品关联 | seckill_price(分), stock, limit_num；idx_session + idx_product |
| `seckill_daily_stock` | 每日库存快照 | **UNIQUE(relation_id, batch_date)** — 行锁精确锁定 |
| `seckill_order` | 秒杀订单追踪 | **UNIQUE(order_id)** — 防重复创建；status: 1待支付/2已支付/3已关闭 |

### 3.2 Lua 脚本

#### 3.2.1 秒杀原子预减（seckill_deduct.lua）

| 参数 | 说明 |
|------|------|
| KEYS[1] | `seckill:stock:{relationId}` — String 类型剩余库存 |
| KEYS[2] | `seckill:limit:{relationId}` — Hash 类型限购计数器 |
| ARGV[1] | userId |
| ARGV[2] | quantity |
| ARGV[3] | limitNum |

| 返回值 | 含义 |
|--------|------|
| 1 | 扣减成功（库存已减 + 限购已增） |
| 0 | 库存不足（售罄） |
| -1 | 库存未初始化（Redis Key 不存在，活动未预热） |
| -2 | 超过限购数量 |

**设计亮点**：将限购检查（HGET + 比较）和库存预减（DECRBY + HINCRBY）合并到单次 Lua 执行中，利用 Redis 单线程模型保证原子性，减少 2 次 Redis 往返。

#### 3.2.2 滑动窗口限流（sliding_window_rate_limit.lua）

| 参数 | 说明 |
|------|------|
| KEYS[1] | 限流 key（如 `ratelimit:/seckill/order/123:456`） |
| ARGV[1] | 当前时间戳（毫秒） |
| ARGV[2] | 窗口大小（毫秒） |
| ARGV[3] | 最大请求数 |
| ARGV[4] | 唯一请求 ID（UUID） |

| 返回值 | 含义 |
|--------|------|
| 1 | 允许通过 |
| 0 | 限流拒绝 |

**算法**：ZREMRANGEBYSCORE 清理过期 → ZCARD 计数 → ZADD 入队 → PEXPIRE 设置过期。

### 3.3 限流模块

#### 3.3.1 RateLimitUtil（hm-common）

```java
@Component
@ConditionalOnProperty(prefix = "spring.redis", name = "host")
public class RateLimitUtil {
    // 使用 StringRedisTemplate 执行 Lua（避免 Jackson 引号问题）
    // fail-open：Redis 异常返回 true（放行）
    public boolean allowRequest(String key, int maxRequests, long windowMs);
}
```

**关键设计**：
- 使用 `StringRedisTemplate` 执行 Lua 脚本，参数以 String 传递，避免 `RedisTemplate`（Jackson 序列化）给参数加引号导致 Lua `tonumber()` 失败
- `@ConditionalOnProperty` 条件注册：无 Redis 配置时不创建 Bean
- 被 `RedisCacheAspect` 包裹，Redis 异常时 `executeScript` 返回 null → 本工具返回 true（fail-open）

#### 3.3.2 RateLimitFilter（hm-gateway）

| 属性 | 值 |
|------|-----|
| 类型 | `GlobalFilter` |
| order | 1（在 `AuthGlobalFilter` order=0 之后） |
| userId 来源 | `user-info` 请求头（由 AuthGlobalFilter 设置） |
| 限流 key | `ratelimit:{path}:{userId}` |
| 降级 | `rateLimitUtil == null` 或 `!enabled` → 放行 |
| 拒绝 | HTTP 429 + `{"message":"请求过于频繁，请稍后再试"}` |

**执行流程**：
1. 未启用限流或 RateLimitUtil 不可用 → 放行
2. AntPathMatcher 匹配请求路径 → 无匹配规则 → 放行
3. 从 `user-info` 头获取 userId → 未认证 → 放行（由 AuthGlobalFilter 拦截）
4. 构建 key 调用 `rateLimitUtil.allowRequest()` → false → 429

#### 3.3.3 RateLimitProperties（hm-gateway）

```java
@ConfigurationProperties(prefix = "hm.ratelimit")
public class RateLimitProperties {
    private boolean enabled = true;
    private List<Rule> rules;
    
    @Data
    public static class Rule {
        private List<String> paths;      // Ant 路径模式
        private int maxRequests = 1;     // 窗口内最大请求数
        private long windowMs = 5000;    // 窗口大小（毫秒）
    }
}
```

#### 3.3.4 配置（application.yml）

```yaml
hm:
  ratelimit:
    enabled: true
    rules:
      - paths: ["/seckill/**"]
        max-requests: 1
        window-ms: 5000
```

### 3.4 秒杀核心服务

#### 3.4.1 SeckillService 接口

| 方法 | 说明 |
|------|------|
| `preheat(relationId)` | 预热库存到 Redis + 初始化每日快照 |
| `doSeckill(relationId, quantity)` | 秒杀下单（锁→Lua预减→MQ） |
| `queryActivities()` | 查询活动列表（含场次、商品） |
| `queryProduct(relationId)` | 查询商品详情 |
| `getOrderResult(relationId)` | 轮询订单结果 |

#### 3.4.2 SeckillServiceImpl 核心逻辑

**Redis Key 常量**：

```java
private static final String STOCK_KEY_PREFIX  = "seckill:stock:";
private static final String LIMIT_KEY_PREFIX  = "seckill:limit:";
private static final String LOCK_KEY_PREFIX   = "seckill:lock:user:";
private static final String RESULT_KEY_PREFIX = "seckill:result:";
private static final long LOCK_EXPIRE_SECONDS = 5;
```

**doSeckill() 流程**：

| 步骤 | 逻辑 | 失败处理 |
|------|------|---------|
| 1 | `UserContext.getUser()` 获取 userId | null → 抛异常 |
| 2 | 查询 `SeckillProductRelation` | 不存在 → 返回 fail |
| 3 | `redisLockUtil.tryLock(seckill:lock:user:{userId}, uuid, 5s)` | 锁定失败 → "请勿重复提交" |
| 4 | `redisService.executeScript(SECKILL_DEDUCT_LUA, ...)` | null → "系统繁忙" |
| 5 | Lua 返回 1 → 构建 `SeckillOrderMessage` 发 MQ | MQ 失败 → 回补 Redis → "系统繁忙" |
| 6 | 返回 `pending()` | — |
| finally | `redisLockUtil.releaseLock()` | — |

**queryActivities() 逻辑**：查询 promotion → session → relation 三级嵌套，通过 Feign 调 item-service 补充商品名称/图片，从 Redis 读取实时剩余库存。

**getOrderResult() 逻辑**：读 `seckill:result:{userId}:{relationId}`，null→pending，"0"→fail(已售罄)，数字→success(orderId)。

#### 3.4.3 SeckillController

| 端点 | 方法 | 说明 |
|------|------|------|
| `/seckill/activities` | GET | 查询活动列表 |
| `/seckill/products/{relationId}` | GET | 查询商品详情 |
| `/seckill/order/{relationId}` | POST | 秒杀下单（?quantity=1） |
| `/seckill/result/{relationId}` | GET | 轮询结果 |

### 3.5 MQ 消费者（SeckillOrderListener）

**MQ 绑定**：

```java
@RabbitListener(bindings = @QueueBinding(
    value = @Queue(name = "seckill.order.queue", durable = "true"),
    exchange = @Exchange(name = "seckill.topic", type = "topic"),
    key = "seckill.order"
))
@Transactional
public void onSeckillOrder(SeckillOrderMessage message) { ... }
```

**消费流程**：

| 步骤 | 逻辑 | 失败处理 |
|------|------|---------|
| 1 | Feign 查询商品信息（订单详情用） | 异常忽略（订单详情字段为空） |
| 2 | `selectForUpdate(relationId, today)` 行锁查询 | null 或 stock < quantity → 回补 Redis → 设结果 "0" → return |
| 3 | `deductStock(relationId, today, quantity)` 原子扣减 | 影响行数=0 → 回补 Redis → 设结果 "0" → return |
| 4 | 创建 `Order`（totalFee=秒杀价×数量, status=1） | — |
| 5 | 创建 `OrderDetail`（price=秒杀价） | — |
| 6 | 创建 `SeckillOrder`（关联 order_id, relation_id, status=1） | — |
| 7 | 发送延迟消息（30min 超时取消，复用 `DELAY_EXCHANGE_NAME`） | 异常忽略（定时任务兜底） |
| 8 | 设 Redis 结果 key = orderId（TTL=120s） | — |

**回补 Redis 方法**：

```java
private void rollbackRedis(Long relationId, Long userId, int quantity) {
    redisService.incrBy(STOCK_KEY_PREFIX + relationId, quantity);         // 回补库存
    redisService.hIncrBy(LIMIT_KEY_PREFIX + relationId, String.valueOf(userId), -quantity); // 回补限购
}
```

### 3.6 定时任务

#### 3.6.1 SeckillPreheatTask（活动预热）

```java
@Scheduled(fixedDelay = 60_000)  // 每分钟执行
public void preheat() {
    // 查询未来 5 分钟内开始的场次
    // 遍历场次下商品 → seckillService.preheat(relationId)
}
```

`preheat()` 逻辑：
1. `redisService.set("seckill:stock:{relationId}", stock)` — 库存写入 Redis
2. 查询 `seckill_daily_stock` 当天记录 → 不存在则 INSERT（UNIQUE 约束防重复）

#### 3.6.2 SeckillTimeoutTask（超时兜底）

```java
@Scheduled(fixedDelay = 5 * 60_000, initialDelay = 2 * 60_000)  // 每5分钟，初始延迟2分钟
public void closeTimeoutOrders() {
    // 查询 seckill_order WHERE status=1 AND create_time < now()-30min
    // 逐条调用 orderService.cancelOrder(orderId)
}
```

### 3.7 OrderServiceImpl 修改（cancelOrder）

**新增依赖注入**：

```java
private final SeckillOrderMapper seckillOrderMapper;
private final SeckillDailyStockMapper seckillDailyStockMapper;
private final RedisService redisService;
```

**cancelOrder() 改造**：

```java
public void cancelOrder(Long orderId) {
    Order order = this.getById(orderId);
    if (order == null || order.getStatus() != 1) return;

    // 检查是否为秒杀订单
    SeckillOrder seckillOrder = seckillOrderMapper.selectOne(
        new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderId, orderId)
    );

    if (seckillOrder != null) {
        recoverSeckillStock(order, seckillOrder);  // 秒杀订单回补
    } else {
        // 普通订单：恢复 item-service 库存（原有逻辑不变）
        itemClient.recoverStock(orderDetailDTOS);
    }

    this.removeById(orderId);
}
```

**recoverSeckillStock() 逻辑**：

| 步骤 | 操作 | 异常处理 |
|------|------|---------|
| 1 | Redis: `incrBy(stock, quantity)` + `hIncrBy(limit, userId, -quantity)` | try-catch 记录日志 |
| 2 | MySQL: `recoverStock(relationId, today, quantity)` | try-catch 记录日志 |
| 3 | 更新 `seckill_order.status = 3`（已关闭） | — |

### 3.8 MQ 常量扩展（MQConstants.java）

```java
// 新增秒杀 MQ 常量
String SECKILL_EXCHANGE_NAME = "seckill.topic";
String SECKILL_ORDER_QUEUE_NAME = "seckill.order.queue";
String SECKILL_ORDER_KEY = "seckill.order";
```

### 3.9 RedisConfig 修改

```java
@Import({RedisService.class, RedisLockUtil.class, RateLimitUtil.class})
//                                         ↑ 新增
public class RedisConfig { ... }
```

### 3.10 PO / VO / DTO 清单

#### PO（5 个）

| 类名 | 表名 | 关键字段 |
|------|------|---------|
| `SeckillPromotion` | seckill_promotion | title, startDate, endDate, status |
| `SeckillSession` | seckill_session | promotionId, name, startTime, endTime |
| `SeckillProductRelation` | seckill_product_relation | productId, seckillPrice, stock, limitNum |
| `SeckillDailyStock` | seckill_daily_stock | relationId, batchDate, stock, sold |
| `SeckillOrder` | seckill_order | orderId, relationId, userId, quantity, status |

所有 PO 使用 `@Accessors(chain = true)` 链式 setter，`@TableName` 映射表名。

#### VO（3 个）

| 类名 | 用途 |
|------|------|
| `SeckillActivityVO` | 活动列表（嵌套 SessionVO → SeckillProductVO） |
| `SeckillProductVO` | 商品详情（含 Redis 实时库存、场次时间用于倒计时） |
| `SeckillResultVO` | 下单结果（success/pending/failed + orderId），含静态工厂方法 |

#### DTO（2 个）

| 类名 | 用途 |
|------|------|
| `SeckillOrderMessage` | MQ 消息体（relationId, userId, productId, quantity, seckillPrice, limitNum） |
| `SeckillProductDTO` | 跨服务传递（hm-api 模块） |

### 3.11 Mapper 清单

| Mapper | 关键方法 |
|--------|---------|
| `SeckillPromotionMapper` | BaseMapper（标准 CRUD） |
| `SeckillSessionMapper` | BaseMapper |
| `SeckillProductRelationMapper` | BaseMapper |
| `SeckillOrderMapper` | BaseMapper |
| `SeckillDailyStockMapper` | `selectForUpdate`（FOR UPDATE 行锁）+ `deductStock`（原子扣减）+ `recoverStock`（回补） |

---

## 四、前端实现详情

### 4.1 文件变更清单

#### 新增文件（3 个）

| 文件 | 说明 |
|------|------|
| `src/api/seckill.ts` | 秒杀 API 模块 + 轮询工具（含 429 限流处理） |
| `src/views/portal/SeckillList.vue` | 秒杀活动列表页 |
| `src/views/portal/SeckillDetail.vue` | 秒杀商品详情页 |

#### 修改文件（2 个）

| 文件 | 改动 |
|------|------|
| `src/router/index.ts` | 新增 `/portal/seckill` 和 `/portal/seckill/:relationId` 路由 |
| `src/views/portal/PortalLayout.vue` | 顶部导航栏增加"限时秒杀"入口 |

### 4.2 API 模块（seckill.ts）

| 函数 | 说明 |
|------|------|
| `getSeckillActivities()` | `GET /seckill/activities` |
| `getSeckillProduct(relationId)` | `GET /seckill/products/{relationId}` |
| `doSeckill(relationId, quantity)` | `POST /seckill/order/{relationId}?quantity=` |
| `getSeckillResult(relationId)` | `GET /seckill/result/{relationId}` |
| `pollSeckillResult(relationId, onProgress)` | 轮询工具：最多 30 次×1.5s，429 延长间隔 |

**429 限流处理**：

```typescript
catch (error: any) {
  if (error.response?.status === 429) {
    await sleep(interval * 2)  // 被限流，延长等待
    continue
  }
}
```

### 4.3 秒杀列表页（SeckillList.vue）

| 功能 | 实现 |
|------|------|
| 场次切换栏 | 横向滚动按钮，高亮当前场次，显示"即将开始/抢购中/已结束" |
| 商品卡片 | 左图右文布局，库存进度条（渐变色），秒杀价/原价对比 |
| 状态判定 | 前端每秒更新 `now`，根据 startTime/endTime 计算场次状态 |
| 已抢光遮罩 | `remainingStock === 0` 时商品图覆盖"已抢光"水印 |
| 导航 | 点击商品卡片 → `/portal/seckill/{relationId}` |

### 4.4 秒杀详情页（SeckillDetail.vue）

| 功能 | 实现 |
|------|------|
| 倒计时 | `calculateCountdown()` 每秒更新，状态 0→距开始，状态 1→距结束 |
| 价格区 | 秒杀价（大号红色）+ 原价（删除线），渐变背景 |
| 库存进度条 | `soldPercent = (totalStock - remainingStock) / totalStock * 100` |
| 数量选择 | 步进器，min=1, max=limitNum |
| 抢购按钮 | 状态 1 且有库存 → 显示（animate-pulse 脉冲动画）；状态 0→"活动即将开始"；状态 2/库存=0→"已抢光" |
| 排队轮询 | `doSeckill()` 返回 pending → `pollSeckillResult()` 轮询，显示"排队中...(N/30)" |
| 结果展示 | success→绿色"秒杀成功"+去支付按钮；failed→灰色提示+返回按钮 |
| 429 处理 | catch 中判断 `error.response?.status === 429` → "请求过于频繁" |

### 4.5 路由配置

```typescript
{
  path: '/portal/seckill',
  name: 'SeckillList',
  component: () => import('@/views/portal/SeckillList.vue'),
},
{
  path: '/portal/seckill/:relationId',
  name: 'SeckillDetail',
  component: () => import('@/views/portal/SeckillDetail.vue'),
  meta: { requiresAuth: true },
},
```

### 4.6 导航入口

`PortalLayout.vue` 顶部导航栏新增：

```html
<router-link to="/portal/seckill" class="hover:text-white transition-colors text-[#FF6B35] font-medium">
  限时秒杀
</router-link>
```

---

## 五、配置说明

### 5.1 Gateway 限流配置

```yaml
# hm-gateway/src/main/resources/application.yml
hm:
  ratelimit:
    enabled: true
    rules:
      - paths: ["/seckill/**"]
        max-requests: 1
        window-ms: 5000
```

### 5.2 Redis 配置（已有，无需修改）

Gateway 已有 `spring.redis` 配置，`RateLimitUtil` 通过 `@ConditionalOnProperty` 自动注册。

### 5.3 MQ 常量

| 常量 | 值 | 用途 |
|------|-----|------|
| `SECKILL_EXCHANGE_NAME` | `seckill.topic` | 秒杀 Topic Exchange |
| `SECKILL_ORDER_QUEUE_NAME` | `seckill.order.queue` | 秒杀下单队列 |
| `SECKILL_ORDER_KEY` | `seckill.order` | 路由键 |
| `DELAY_EXCHANGE_NAME` | `trade.delay.direct`（已有） | 延迟消息 Exchange（复用） |
| `DELAY_ORDER_KEY` | `delay.order`（已有） | 延迟路由键（复用） |

---

## 六、关键技术决策

### 6.1 决策：限购+库存合并为单次 Lua

**决策**：`seckill_deduct.lua` 将限购检查（HGET + 比较）和库存预减（DECRBY + HINCRBY）合并到同一个 Lua 脚本中。

**理由**：文档 3.7.3 节原始设计为分两步（先限购再库存），但分两步需要 2 次 Redis 往返且无法保证原子性（限购检查通过后、库存预减前可能有其他请求插入）。合并后利用 Redis 单线程模型保证原子性，且仅需 1 次往返。

### 6.2 决策：使用 StringRedisTemplate 执行 Lua

**决策**：`RateLimitUtil` 和 `SeckillServiceImpl` 均使用 `StringRedisTemplate` 执行 Lua 脚本，参数以 String 传递。

**理由**：项目 `RedisConfig` 配置了双 Template（`RedisTemplate` Jackson 序列化 + `StringRedisTemplate`）。`RedisTemplate` 的 Jackson 序列化会给 String 参数加双引号（如 `"123"`），Lua 脚本中 `tonumber("123")` 可以解析但 `tonumber("\"123\"")` 返回 nil。遵循文档 3.7.6 节第 6 点"Lua 参数用 StringRedisTemplate 执行"。

### 6.3 决策：RateLimitFilter order=1

**决策**：限流过滤器 `order=1`，在 `AuthGlobalFilter`（order=0）之后执行。

**理由**：限流需要 userId 作为维度，而 userId 由 `AuthGlobalFilter` 从 JWT 解析后写入 `user-info` 请求头。必须在认证之后限流，才能按用户限流。未认证的请求由 `AuthGlobalFilter` 拦截，不会到达 `RateLimitFilter`。

### 6.4 决策：fail-open 降级

**决策**：Redis 不可用时 `RateLimitFilter` 放行请求，不阻塞用户。

**理由**：限流是防超卖的第一道防线，但不是唯一防线。Redis 不可用时由第二层（Lua 预减，同样依赖 Redis，会返回"系统繁忙"）和第三层（MySQL 行锁）兜底。若限流器 fail-close（拒绝所有请求），Redis 故障会导致秒杀功能完全不可用，用户体验更差。

### 6.5 决策：秒杀订单复用 order + order_detail 表

**决策**：秒杀订单不创建独立的订单表，复用现有 `order` + `order_detail` 表，通过 `seckill_order` 关联表追踪。

**理由**：
- 复用现有支付、订单查询、订单列表等基础设施
- `order_detail.price` 存储秒杀价，无需额外字段
- `seckill_order` 关联表记录 relationId/quantity/status，用于超时回补时区分秒杀/普通订单

### 6.6 决策：cancelOrder 通过 seckill_order 关联表区分订单类型

**决策**：`cancelOrder()` 查询 `seckill_order` 表判断是否为秒杀订单，走不同的库存回补路径。

**理由**：秒杀订单的库存回补需要同时回补 Redis 库存、MySQL 秒杀库存、Redis 限购额度，而普通订单只需调用 `itemClient.recoverStock()`。通过关联表区分，避免修改 order 表结构。

---

## 七、部署指引

### 7.1 启动流程

```
1. 启动基础设施
   ├── MySQL 8.0+ (192.168.100.128:3306)
   ├── Redis 6.x+ (192.168.100.128:6379)
   ├── Nacos 2.x (192.168.100.128:8848)
   └── RabbitMQ 3.x

2. 执行建表 SQL
   mysql hm-trade < trade-service/src/main/resources/db/migration/V2__seckill_tables.sql

3. 构建所有模块
   mvn clean install -DskipTests    # 在 hmall 根目录执行

4. 启动服务
   按顺序: hm-gateway → item-service → trade-service

5. 启动前端
   cd hmall-frontend && npm run dev
```

### 7.2 初始化秒杀活动数据

```sql
-- 创建活动
INSERT INTO seckill_promotion (title, start_date, end_date, status)
VALUES ('限时秒杀专场', CURDATE(), DATE_ADD(CURDATE(), INTERVAL 7 DAY), 1);

-- 创建场次（今天 10:00-12:00）
INSERT INTO seckill_session (promotion_id, name, start_time, end_time)
VALUES (1, '10:00场',
  CONCAT(CURDATE(), ' 10:00:00'),
  CONCAT(CURDATE(), ' 12:00:00'));

-- 关联商品（商品ID=100，秒杀价=599900分=5999元，库存=100，限购=1）
INSERT INTO seckill_product_relation (promotion_id, session_id, product_id, seckill_price, stock, limit_num)
VALUES (1, 1, 100, 599900, 100, 1);
```

### 7.3 启动检查清单

- [ ] MySQL `hm-trade` 库中 5 张秒杀表已建（`V2__seckill_tables.sql` 执行成功）
- [ ] `seckill_promotion` / `seckill_session` / `seckill_product_relation` 有测试数据
- [ ] Redis 服务运行中（`redis-cli PING` → `PONG`）
- [ ] RabbitMQ 服务运行中（Management UI 可访问）
- [ ] hm-gateway 启动日志无 Redis 连接异常
- [ ] trade-service 启动日志无 Lua 脚本加载异常
- [ ] `hm-common` 已 `mvn install` 到本地仓库
- [ ] 访问 `GET /seckill/activities` → 返回活动列表 JSON
- [ ] 前端访问 `/portal/seckill` → 秒杀列表页正常渲染
- [ ] 定时预热任务日志：`秒杀预热任务开始/完成`
- [ ] 限流验证：5 秒内连续 POST `/seckill/order/{id}` → 第二次返回 429

---

## 八、已知问题与后续优化

### 8.1 活动管理后台缺失 ~~（已解决）~~

~~**现象**：秒杀活动/场次/商品关联数据需手动 SQL 插入，无管理后台界面。~~

**解决**：v1.1 已在 admin-service 新增秒杀管理功能，含活动 CRUD、场次管理、商品关联管理、秒杀订单查询、库存状态查看和手动预热。详见 `docs/秒杀功能实现/seckill-admin-design.md` 和 `seckill-admin-implementation-report.md`。

### 8.2 库存预热幂等性 ~~（已解决）~~

~~**现象**：`SeckillPreheatTask` 每分钟执行，若活动已预热则重复 `redisService.set()` 覆盖 Redis 库存。~~

~~**影响**：若预热期间已有用户下单（Redis 库存已减少），预热任务会用 MySQL 原始库存覆盖 Redis 实时库存，导致库存被回补。~~

**解决**：v1.1 修复。`preheat()` 方法改用 `hasKey` 守卫（SETNX 语义），首次预热后 key 已存在则跳过写入，定时任务不再覆盖已扣减的实时库存。

### 8.3 查询活动列表 N+1 问题 ~~（已解决）~~

~~**现象**：`queryActivities()` 对每个商品关联调用 `itemClient.queryItemById()` 查询商品信息，存在 N+1 Feign 调用。~~

**解决**：v1.1 修复。`queryItemById` 的 Feign 声明从 `@RequestParam` 改为 `@PathVariable`，与 item-service 实际接口 `GET /items/{id}` 对齐，Feign 调用不再失败。管理后台的批量查询使用 `queryItemsByIds` 批量接口，无 N+1 问题。

**影响**：商品数量多时查询延迟较高。

**后续优化**：批量查询商品信息（`itemClient.queryItemsByIds`），减少 Feign 调用次数。

### 8.4 秒杀结果 Key 覆盖

**现象**：`seckill:result:{userId}:{relationId}` 的 key 设计中，同一用户对同一商品多次秒杀会覆盖前一次结果。

**影响**：用户第一次秒杀排队中，第二次秒杀（限购允许的情况下）的结果会覆盖第一次。

**缓解**：per-user 分布式锁（TTL=5s）和限购检查（Lua 返回 -2）已大幅降低此场景概率。

**后续优化**：结果 key 中加入时间戳或 requestId 区分。

### 8.5 Gateway 限流器阻塞式调用

**现象**：Gateway 是 WebFlux 响应式架构，但 `RateLimitFilter` 通过 `RateLimitUtil` 调用 Redis 是阻塞式同步调用（与现有 `AuthGlobalFilter` 一致）。

**影响**：高并发时限流器可能阻塞 Gateway 的事件循环线程。

**缓解**：Redis Lua 执行极快（亚毫秒级），且 Gateway 已有 `AuthGlobalFilter` 同样采用阻塞模式，影响可控。

**后续优化**：使用 ReactiveRedisTemplate 实现非阻塞限流。


---

## 九、v1.1 修复记录（2026-07-15）

### 9.1 ItemClient Feign 声明不匹配导致商品信息获取失败

**问题**：`hm-api` 中 `ItemClient.queryItemById` 声明为 `@GetMapping("/items")` + `@RequestParam("id")`，但 item-service 的实际接口是 `@GetMapping("{id}")` + `@PathVariable`。Feign 发出 `GET /items?id=123` 请求，item-service 无此路由 → 调用失败 → 被 catch 后 item=null。

**影响**：
- `SeckillOrderListener.onSeckillOrder()` — 秒杀下单后 `OrderDetail.name/spec/image` 为空
- `SeckillServiceImpl.buildProductVO()` — C 端秒杀商品详情中商品名称/图片/规格为空

**修复**（`hm-api/.../client/ItemClient.java`）：
```java
// 修复前
@GetMapping("/items")
ItemDTO queryItemById(@RequestParam("id") Long id);

// 修复后
@GetMapping("/items/{id}")
ItemDTO queryItemById(@PathVariable("id") Long id);
```

### 9.2 定时预热任务覆盖已扣减的 Redis 库存

**问题**：`SeckillPreheatTask` 每分钟扫描"未来5分钟内开始且尚未结束"的场次并调用 `preheat()`，`preheat()` 方法用 `redisService.set()`（SET 命令）无条件覆盖 Redis 库存为 MySQL 原始值。场次开始后秒杀扣减发生 → 下一分钟预热任务再次触发 → 库存被回补。

**修复**（`trade-service/.../impl/SeckillServiceImpl.java`）：
```java
// 修复前：无条件覆盖
redisService.set(stockKey, relation.getStock());

// 修复后：hasKey 守卫（SETNX 语义），key 已存在则跳过
if (!redisService.hasKey(stockKey)) {
    redisService.set(stockKey, relation.getStock());
}
```

### 9.3 支付成功后 seckill_order 表状态未同步

**问题**：支付成功后 `paySuccessListener` → `markOrderPaySuccess(orderId)` 只更新了 `order.status=2`，未更新 `seckill_order.status=2`。导致管理后台秒杀订单状态始终显示"待支付"。

**修复**（`trade-service/.../impl/OrderServiceImpl.java`）：
```java
// markOrderPaySuccess 方法增加：
SeckillOrder seckillOrder = seckillOrderMapper.selectOne(
    new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderId, orderId)
);
if (seckillOrder != null && seckillOrder.getStatus() == 1) {
    SeckillOrder update = new SeckillOrder();
    update.setId(seckillOrder.getId());
    update.setStatus(2);
    seckillOrderMapper.updateById(update);
}
```

该修复与已有的 `cancelOrder` 逻辑对称：超时取消时更新 `seckill_order.status=3`（已关闭）+ 回补库存；支付成功时更新 `seckill_order.status=2`（已支付）。

---

## 十、与本仓库其他文档的关联

| 文档 | 关系 |
|------|------|
| `docs/秒杀功能实现/seckill-design.md` | **设计文档**：本文档的源头，描述整体架构设计和接口规划 |
| `docs/redis功能相关文档/redis-application-analysis.md` | **参考文档**：3.7 节（秒杀 Lua 预减）+ 3.8 节（滑动窗口限流）的设计来源 |
| `docs/管理后台相关文档/admin-service-design.md` | **关联文档**：admin-service Phase 4 计划新增秒杀活动管理（SMS 模块） |
| `trade-service/.../db/migration/V2__seckill_tables.sql` | **SQL 脚本**：5 张秒杀表建表 DDL |
| `hm-common/.../lua/seckill_deduct.lua` | **Lua 脚本**：秒杀原子预减（限购+库存合一） |
| `hm-common/.../lua/sliding_window_rate_limit.lua` | **Lua 脚本**：滑动窗口限流（ZSET 原子操作） |

---

> **实现完成度**：三层防超卖架构（Gateway 限流 + Redis Lua 预减 + MySQL 行锁）全部实现，包含活动预热、异步下单、超时回补、前端秒杀页面等完整链路。活动管理后台界面为后续扩展项（admin-service Phase 4）。


---

# 第二部分：管理后台秒杀管理实现

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


