# hmall Agent 用户画像持久化 & 主动通知 设计文档

> 版本：v2.0
> 日期：2026-07-29
>
> **前置文档**：[agent-personalized-recommendation-design.md](./agent-personalized-recommendation-design.md)（推荐设计）
> [agent-personalized-recommendation-implementation-report.md](./agent-personalized-recommendation-implementation-report.md)（Phase 1 实现报告）
>
> 本文档定义两个 Agent 核心能力扩展的详细设计方案，承接个性化推荐 Phase 1 实现报告中"第十章 后续优化方向"的内容：
> - **用户画像持久化**：Phase 2 核心项，落地"Redis 画像计算 + 行为采集 + 推荐结果缓存"，将 Phase 1 的"两端重复计算"统一为"共享增量画像"
> - **主动通知**：差异化竞争力，从"被动应答"升级为"事件驱动主动推送"

---

## 第一部分：用户画像持久化

### 1. 概述

#### 1.1 背景：Phase 1 实现现状与遗留问题

个性化推荐 Phase 1 已落地两套独立的偏好计算链路，彼此互不知晓，每次均全量重算：

```
Phase 1 现状（双重计算）

用户说 "有什么推荐"
  │
  ├─ L1 正则命中 → get_recommendations_api(scene="home")
  │     │
  │     └─ Gateway → item-service RecommendController
  │           │
  │           └─ RecommendServiceImpl.recommend()          ① 后端侧重算
  │                ├─ Feign → trade-service 取已购商品
  │                ├─ 查 item 表补充 category/brand
  │                ├─ 按购买数量加权聚合偏好 Top3
  │                └─ Feign → search-service ES 召回
  │
  └─ 用户追问 "为什么推荐这些？" → LLM 可能调 analyze_user_preferences
        │
        └─ analyze_user_preferences()                     ② Agent 侧重算
             ├─ 并发 Gateway 查 /orders/page + /carts
             ├─ 批量 Gateway 查 /items?ids= 补充 category/brand
             └─ 购买权重 5 + 购物车权重 3 聚合
```

**核心问题**：

| 位置 | 计算方式 | 触发频率 | 问题 |
|------|---------|---------|------|
| 后端 `RecommendServiceImpl.recommend()` | Feign 取已购→查 item 表补 category/brand→加权 Top3 | **每次推荐请求** | 每次 ~2-3 次 Feign 调用 |
| Agent `analyze_user_preferences` 工具 | 并发 Gateway 查订单(50条)+购物车+批量查商品→加权聚合 | **LLM 自主决定**（低频） | 每次 ~3-5 次 Gateway 往返；与后端逻辑重复 |
| Agent `Context` dataclass | 单次 run 不持久化 | 每次对话 | 跨会话无记忆 |

**遗留的 Phase 2 待办项**（来源：[agent-personalized-recommendation-implementation-report.md](./agent-personalized-recommendation-implementation-report.md) 第十章）：

| 优化项 | 说明 | 本设计对应 |
|--------|------|-----------|
| 行为采集 `POST /behaviors` | 浏览/收藏/加购行为上报，MQ 异步落库 | → Layer 1 行为流（Agent 侧直写 Redis，零后端改动） |
| Redis 画像计算 | Consumer 更新 `up:{uid}:cat` / `up:{uid}:brand` ZSet | → Layer 2 聚合画像（HINCRBY 增量更新） |
| 推荐结果缓存 | `rec:{userId}:{scene}` String，TTL 5-10min | → 方案 5 工具结果缓存（见前序文档） |
| 购买行为旁路采集 | `paySuccessListener` 发 purchase 行为消息 | → Agent 写操作工具直接 `record_event`（不走 MQ，更直接） |

#### 1.2 目标

- **消除重复计算**：`analyze_user_preferences` 优先读 Redis 画像（命中时 0 次 Gateway 调用），`RecommendServiceImpl` 后续也可共享同一份画像
- **增量更新**：Agent 写操作（加购/下单/确认收货）成功后直接写入画像，不触发全量重算
- **承接 Phase 1**：不删除 Phase 1 的任何代码，ProfileStore 作为缓存加速层叠加在现有接口之上
- **降级容错**：画像 miss 时降级为 Phase 1 实时计算逻辑，计算完成后异步回写画像

### 2. 设计：Phase 1 → Phase 2 演进

#### 2.1 演进架构

```
Phase 1（已实现）                        Phase 2（本次扩展）
─────────────────────                   ─────────────────────
get_recommendations_api                get_recommendations_api
  │                                      │
  └─ Gateway → /recommend               └─ Gateway → /recommend ─── 不变，后端仍全量算
       │                                                        （后端改造可选）
       └─ RecommendServiceImpl                                    │
            recomment() ← 每次重算          profile_store ←───────┤ Phase 2 新增
                                                         HINCRBY │ 增量写入
analyze_user_preferences               analyze_user_preferences
  │                                      │
  ├─ /orders/page (Gateway #1)           ├─ profile_store.get_profile() ← Phase 2 优先路径
  ├─ /carts       (Gateway #2)           │   ├─ 命中 → 直接返回（0 次 Gateway）
  ├─ /items?ids=  (Gateway #3)           │   └─ miss → 降级 Phase 1 原有逻辑
  └─ 聚合后返回                           │        └─ 聚合完 → 异步回写 profile_store
                                         │
 add_to_cart_api / create_order_api      add_to_cart_api / create_order_api
  │                                      │
  └─ 仅写业务数据（无画像感知）            └─ 写业务数据 + record_event() ← Phase 2 新增
```

#### 2.2 三层画像体系

```
┌──────────────────────────────────────────────────────────────┐
│  Layer 1: 实时行为流（Redis List，最近 50 条）               │
│  加购/下单/确认收货事件实时追加，TTL 7 天                    │
│  用途：回溯分析、画像修正                                    │
│  来源：Agent 写操作工具成功后直接写入（不依赖后端 MQ）        │
├──────────────────────────────────────────────────────────────┤
│  Layer 2: 聚合画像（Redis Hash，增量更新）                   │
│  category_scores / brand_scores / price_stats                │
│  由事件流 HINCRBY 增量聚合，TTL 30 天                        │
│  用途：analyze_user_preferences 优先读取（Agent 侧）         │
│         RecommendServiceImpl 后续可共享（后端侧，可选）      │
├──────────────────────────────────────────────────────────────┤
│  Layer 3: 对话记忆（LangGraph Store，跨会话）                │
│  "上次想买 iPhone 但没下单"、"偏好数码类" 等语义记忆        │
│  用途：Agent 跨会话个性化对话上下文                          │
│  与 Layer 1/2 互补：结构化画像回答"是什么"，语义记忆回答     │
│  "为什么"——二者共同支撑推荐闭环                              │
└──────────────────────────────────────────────────────────────┘
```

Layer 1+2 是结构化画像（Agent 直接读写，后端可选共享），Layer 3 是语义记忆（仅 Agent 使用）。

### 3. Layer 1+2：Redis 画像存储

#### 3.1 Redis Key 设计

复用 hmall Redis（db=1，与 Checkpoint 同库），通过 `profile:` 前缀隔离：

```
Key: profile:{user_id}:events       → List   （行为流，LPUSH + LTRIM 50，TTL 7d）
Key: profile:{user_id}:categories   → Hash   （category → 累计得分，TTL 30d）
Key: profile:{user_id}:brands       → Hash   （brand → 累计得分，TTL 30d）
Key: profile:{user_id}:prices       → List   （最近购买价格，LTRIM 20，TTL 30d）
Key: profile:{user_id}:stats        → Hash   （order_count, cart_count, avg_price, last_update，TTL 30d）
```

#### 3.2 行为权重（与 Phase 1 `_accumulate_preference` 保持一致）

Phase 1 已在 `tools.py:576-584` 定义了偏好聚合权重：购买权重 5、购物车权重 3。Phase 2 的画像增量更新使用相同的权重体系：

| 行为 | 事件类型 | 权重 | 来源（Phase 2 新增） |
|------|---------|------|---------------------|
| 购买 | `purchase` | 5 | `create_order_api`（方案 1 新增）、`confirm_receive_api`（Phase 1 已有）成功后触发 |
| 加购 | `cart` | 3 | `add_to_cart_api`（Phase 1 已有）成功后触发 |
| 浏览 | `view` | 1 | `get_item_detail_api`（Phase 1 已有）触发，可选开关控制 |

> **设计说明**：权重值与 `tools.py:701,713` 中 `_accumulate_preference` 的参数 `weight=5`/`weight=3` 保持一致。这意味着画像 miss 时实时计算的聚合结果，与画像命中时增量累加的结果，用同一套权重体系——保证"画像命中"和"降级实时计算"两条路径返回的偏好结果**数学等价**。

#### 3.3 与 Phase 1 现有工具的集成关系

下表描述 ProfileStore 与 Phase 1 每个已有工具的具体集成方式（**只扩展不替换**）：

| Phase 1 工具 | 当前行为 | Phase 2 扩展 | 变更方式 |
|-------------|---------|-------------|---------|
| `analyze_user_preferences` | 每次全量重算（3-5 次 Gateway 调用） | **优先读 ProfileStore**，miss 降级原逻辑；写完画像后异步回写 | 在工具函数开头插入 `profile_store.get_profile()` 检查 |
| `get_recommendations_api` | 每次调后端 `/recommend`，后端全量重算聚合 | **不变**（Phase 2 不改造此工具）。后端聚合优化在后端 `RecommendServiceImpl` 中独立进行（可选） | 无变更 |
| `add_to_cart_api` | 仅调 `POST /carts` | 成功后追加 `profile_store.record_event("cart", ...)` | 在 `return` 前追加 5 行 |
| `update_cart_quantity_api` | 仅调 `PUT /carts/{itemId}` | 成功后追加 `profile_store.record_event("cart", ...)` | 同上 |
| `confirm_receive_api` | 仅调 `PUT /orders/{id}` | 成功后追加 `profile_store.record_event("purchase", ...)` | 同上 |
| `cancel_order_api` | 仅取消订单 | 可追加负向信号（扣减偏好权重），属于后续优化 | 暂不实施 |
| `get_item_detail_api` | 查商品详情 | 可选追加 `profile_store.record_event("view", ...)`，通过环境变量开关控制 | 可在配置中加 `PROFILE_TRACK_VIEW=false` |

> **关键设计原则**：Phase 1 的所有工具**内部逻辑完全不变**，仅在其成功返回前追加一行 `record_event` 调用。`record_event` 失败时 catch 异常不抛，保证画像写入失败不影响业务主流程。这意味着即使 ProfileStore 不可用（Redis 宕机），Agent 的核心购物功能完全不受影响。

#### 3.4 画像服务核心实现

新增 `src/profile/store.py`，提供 `ProfileStore` 类：

```python
class ProfileStore:
    """用户画像存储，支持增量更新和读取降级。"""

    async def record_event(
        self, user_id: str,
        event_type: str,             # purchase / cart / view
        item_id: int,
        category: str = "",
        brand: str = "",
        price: int = 0,             # 分
        num: int = 1,
    ) -> None:
        """记录行为事件并增量更新聚合画像。"""
        weight = WEIGHTS.get(event_type, 1)
        pipe = self._redis.pipeline()

        # Layer 1: 行为流
        event = json.dumps({"type": event_type, "itemId": item_id,
            "category": category, "brand": brand, "price": price,
            "num": num, "ts": int(time.time())})
        pipe.lpush(f"{_PREFIX}:{user_id}:events", event)
        pipe.ltrim(f"{_PREFIX}:{user_id}:events", 0, 49)
        pipe.expire(f"{_PREFIX}:{user_id}:events", _EVENT_TTL)

        # Layer 2: 增量聚合 —— HINCRBY 而非全量重算
        score = weight * num
        if category:
            pipe.hincrby(f"{_PREFIX}:{user_id}:categories", category, score)
            pipe.expire(f"{_PREFIX}:{user_id}:categories", _PROFILE_TTL)
        if brand:
            pipe.hincrby(f"{_PREFIX}:{user_id}:brands", brand, score)
            pipe.expire(f"{_PREFIX}:{user_id}:brands", _PROFILE_TTL)
        if price:
            pipe.lpush(f"{_PREFIX}:{user_id}:prices", price)
            pipe.ltrim(f"{_PREFIX}:{user_id}:prices", 0, 19)
            pipe.expire(f"{_PREFIX}:{user_id}:prices", _PROFILE_TTL)

        pipe.hincrby(f"{_PREFIX}:{user_id}:stats", f"{event_type}_count", 1)
        pipe.hset(f"{_PREFIX}:{user_id}:stats", "last_update", int(time.time()))
        pipe.expire(f"{_PREFIX}:{user_id}:stats", _PROFILE_TTL)

        await pipe.execute()

    async def get_profile(self, user_id: str) -> dict:
        """读取聚合画像。不存在时返回空 dict（调用方降级到实时计算）。"""

    async def top_categories(self, user_id: str, n: int = 3) -> list[str]:
        """Top N 偏好类目（后端推荐服务可共享调用）。"""

    async def top_brands(self, user_id: str, n: int = 3) -> list[str]:
        """Top N 偏好品牌。"""

    async def invalidate(self, user_id: str) -> None:
        """清除用户画像（用户请求或数据修正时调用）。"""
```

#### 3.5 降级策略（承接 Phase 1 实时计算兜底）

```
用户请求 analyze_user_preferences
  │
  ├─ ProfileStore.get_profile(user_id) 命中？
  │   ├─ 是 → format_preferences 直接格式化返回
  │   │       （0 次 Gateway 调用，~2ms Redis 读取）
  │   │
  │   └─ 否 → 降级 Phase 1 实时计算逻辑（tools.py:645-714 原逻辑复用）
  │           ├─ asyncio.gather → /orders/page + /carts（Phase 1 已有）
  │           ├─ 批量查 /items?ids= 补充 category/brand（Phase 1 已有）
  │           ├─ _accumulate_preference(weight=5) + (weight=3)（Phase 1 已有）
  │           └─ 聚合完成后，异步回写 ProfileStore（不阻塞响应）
  │               ↓
  │           format_preferences 返回
```

实现上，将现有 `analyze_user_preferences` 重构为三步：

```python
@tool
async def analyze_user_preferences(config: RunnableConfig) -> str:
    token = extract_token_from_config(config)
    if not token:
        return "❌ 偏好分析需要先登录"

    user_id = _extract_user_id(config)

    # ====== Phase 2 新增：画像优先 ======
    if user_id:
        profile = await profile_store.get_profile(user_id)
        if profile:
            return format_preferences(
                profile["categories"], profile["brands"], profile["prices"],
                orders=[], cart=[],  # 画像模式不展示原始列表
            )
    # ====== Phase 2 结束 ======

    # ====== Phase 1 原有逻辑：降级实时计算 ======
    try:
        orders_page, cart_items = await asyncio.gather(
            gateway_client.get("/orders/page", token=token, params={"pageNo": 1, "pageSize": 50}),
            gateway_client.get("/carts", token=token),
        )
    except GatewayError as e:
        return f"❌ 获取用户数据失败: {e}"

    # ...（Phase 1 现有聚合逻辑保持不变，tools.py:656-714）...

    # ====== Phase 2 新增：异步回写画像 ======
    if user_id and category_scores:
        asyncio.create_task(_backfill_profile(user_id, category_scores, brand_scores, price_points))
    # ====== Phase 2 结束 ======

    return format_preferences(category_scores, brand_scores, price_points, orders, cart)
```

> **设计说明**：Phase 1 代码（`tools.py:645-714`）完整保留作为降级路径。新增的 `if profile:` 检查在最前面，画像命中时跳过后续所有 Gateway 调用。`_backfill_profile` 用 `asyncio.create_task` 异步执行，不阻塞当前请求的响应返回。

#### 3.5 实现示例：`add_to_cart_api` 的画像集成

Phase 1 的 `add_to_cart_api`（`tools.py:200-217`）仅调 `POST /carts`。Phase 2 在成功后追加画像写入，改动极小：

```python
# tools.py 原代码 + Phase 2 追加（标 ★ 部分）

@tool
async def add_to_cart_api(item_id: int, config: RunnableConfig) -> str:
    token = extract_token_from_config(config)
    if not token:
        return "❌ 加入购物车需要先登录"
    try:
        await gateway_client.post(                       # Phase 1 原逻辑
            "/carts", token=token,
            json={"itemId": item_id, "num": 1},
        )

        # ★ Phase 2 新增：增量更新画像
        user_id = _extract_user_id(config)
        if user_id:
            try:
                item = await gateway_client.get(f"/items/{item_id}")
                await profile_store.record_event(
                    user_id, "cart", item_id,
                    category=item.get("category", ""),
                    brand=item.get("brand", ""),
                    price=item.get("price", 0),
                )
            except Exception:
                pass  # 画像写入失败不影响主流程

        return f"✅ 商品 {item_id} 已加入购物车"         # Phase 1 原返回
    except GatewayError as e:
        return f"❌ 加入购物车失败: {e}"
```

其他工具（`confirm_receive_api`、`update_cart_quantity_api` 等）遵循相同的追加模式。完整集成关系见 §3.3 表格。

> **`_extract_user_id(config)` 实现**：从 `config.configurable.user_id` 或 `config.configurable.context.user_id` 提取（与 `extract_token_from_config` 同源，`http_client.py:150-187` 的 `user_token` 提取逻辑可直接复用，仅改为取 `user_id`）。如果当前 config 中没有 `user_id` 字段，可在 `AuthMiddleware` 解析 JWT 时写入 `context.user_id`。

### 4. Layer 3：对话记忆（LangGraph Store）

LangGraph Store 提供跨会话的持久化 KV 存储。与 Layer 1/2 的 Redis 结构化画像互补：Layer 1/2 存储"用户喜欢什么类目/品牌"（数值型得分），Layer 3 存储"用户说过什么"（语义记忆），二者共同支撑推荐闭环。

#### 4.1 记忆服务（作为 Agent 工具暴露）

新增 `src/profile/memory.py`，将记忆读写封装为 Agent 工具（使用 `@tool` 装饰器，LLM 可在对话中自主调用）：

```python
from langgraph.store.base import BaseStore

MEMORY_NAMESPACE = "user_memory"

@tool
async def save_memory(key: str, value: str, config: RunnableConfig) -> str:
    """保存一条对话记忆到长期存储。

    当用户表达购物意图但未完成时调用。例如：
    - 用户说"想买手机但再看看" → key="shopping_intent", value="正在挑选手机，预算约 5000"
    - 用户频繁看某个品牌 → key="brand_preference", value="对 Apple 产品感兴趣"

    Args:
        key: 记忆标识（如 shopping_intent / price_sensitivity / last_viewed）
        value: 记忆内容
    """
    store = config.get("configurable", {}).get("store")
    if not store:
        return "记忆服务未启用"
    user_id = _extract_user_id(config)
    await store.aput(
        namespace=(MEMORY_NAMESPACE, user_id),
        key=key,
        value={"content": value, "ts": int(time.time())},
    )
    return f"已记住: {key}"


@tool
async def get_memories(config: RunnableConfig) -> str:
    """读取当前用户的历史对话记忆。

    在对话开始时或推荐商品前调用，了解用户之前的购物意图和偏好。
    返回 JSON 格式的记忆列表。
    """
    store = config.get("configurable", {}).get("store")
    if not store:
        return "记忆服务未启用"
    user_id = _extract_user_id(config)
    items = await store.asearch(
        namespace=(MEMORY_NAMESPACE, user_id),
        limit=20,
    )
    if not items:
        return "暂无历史记忆"
    memories = [{"key": item.key, "content": item.value.get("content", "")}
                for item in items]
    return json.dumps(memories, ensure_ascii=False)
```

这两个工具注册到 `get_all_tools()`，与 Phase 1 的 18 个已有工具并列（当前总计 20 个，新增后变为 22 个）。

#### 4.2 System Prompt 引导（在 Phase 1 基础上扩展）

Phase 1 的 `prompts.py` 第 13 行已声明"个性化推荐"能力。Phase 2 在现有 SYSTEM_PROMPT 末尾追加用户记忆相关的行为准则：

```
## 用户记忆（Phase 2 新增）
- 每次对话开始时，自动调用 get_memories 读取历史记忆，在首次回复中自然融入
  （如"欢迎回来！上次您在看手机类商品，今天新到了一些热门款"）
- 当用户明确表达购物意图但未完成（如"想买手机但再看看""先收藏改天再说"），
  调用 save_memory 保存意图
- 当用户完成购买或明确表示不再感兴趣，调用 delete_memory 清理过时记忆
- 不要生硬复述记忆内容给用户，要自然地融入对话（像老朋友记住你的喜好一样）
```

### 5. 后端推荐服务共享画像（可选项，Phase 2 第二阶段）

#### 5.1 改造背景

Phase 1 的 `RecommendServiceImpl.recommend()`（实施报告 §3.4.4）每次推荐请求都走完整三步管线：Feign 取已购 → item 表补 category/brand → 加权聚合 Top3。Agent 侧 `ProfileStore` 落地后，后端的偏好聚合逻辑可**直接读同一份 Redis 画像**，将三步管线缩减为两步。

> **注意**：此项为可选优化。Agent 侧的画像持久化独立生效（`analyze_user_preferences` 优先读画像），后端不改造不影响核心功能。此项对应实施报告第十章"推荐结果缓存"项的具体落地。

后端 `RecommendServiceImpl.recommend()` 的偏好聚合逻辑改为**优先读 Redis 画像**：

```java
// recomment() 方法中偏好聚合部分

// 原逻辑：每次 Feign 取已购 + 查 item 表 + 聚合 Top3
// 新逻辑：优先读 Redis 画像

if (userId != null) {
    String catKey = "profile:" + userId + ":categories";
    Map<Object, Object> catScores = redisService.hgetall(catKey);

    if (catScores != null && !catScores.isEmpty()) {
        // 画像命中：0 次 Feign 调用
        categories = topNFromStringMap(catScores, 3);
        String brandKey = "profile:" + userId + ":brands";
        Map<Object, Object> brandScores = redisService.hgetall(brandKey);
        topBrands = topNFromStringMap(brandScores, 3);
        // excludeIds 仍需 Feign（已购列表不存画像）
        excludeIds = safeQueryPurchasedItemIds();
    } else {
        // 画像 miss：降级原全量聚合逻辑（现有代码不变）
        List<OrderDetailDTO> purchasedItems = safeQueryPurchasedItems();
        // ...（现有聚合逻辑保持不变）...
    }
}
```

#### 5.2 画像写入链路

后端同样通过行为事件写入画像——在订单创建/支付成功的业务逻辑中追加 Redis 写入：

```
用户下单 → trade-service.createOrder()
         → 异步写 profile:{userId}:categories/brands/prices（Redis）
         → Agent / 推荐服务下次查询时直接命中
```

### 6. 改动清单

> **约定**：表中标注"Phase 1 已有"的文件已在推荐实现报告中落地，Phase 2 仅在其基础上追加内容（不删除不重写）。

| 文件 | 改动 | 阶段 | 说明 |
|------|------|------|------|
| `src/profile/store.py` | **新增** | Phase 2 | `ProfileStore` — Redis 画像 CRUD + 增量更新 |
| `src/profile/memory.py` | **新增** | Phase 2 | `save_memory` / `get_memories`（`@tool` 装饰，注册到 `get_all_tools()`） |
| `src/agents/customer/tools.py` | **修改** | Phase 1 已有 → Phase 2 扩展 | `analyze_user_preferences`（第 634 行）前插画像优先路径；写操作工具成功后追加 `record_event`；注册 2 个新记忆工具 |
| `src/agents/customer/prompts.py` | **修改** | Phase 1 已有 → Phase 2 扩展 | `SYSTEM_PROMPT`（第 3 行）末尾追加"用户记忆"行为准则 |
| `src/agents/customer/agent.py` | **修改** | Phase 1 已有 → Phase 2 扩展 | `create_agent()` 注入 `store` 参数 |
| `src/core/config.py` | **修改** | Phase 1 已有 → Phase 2 扩展 | 新增 `LANGGRAPH_STORE_URI`（复用 hmall Redis db=1） |
| `src/tools/formatters.py` | **修改** | Phase 1 已有 → Phase 2 扩展 | `format_preferences`（第 408 行）适配画像直读模式（无原始列表时仅展示聚合数据） |
| `src/gateway/http_client.py` | **修改** | Phase 1 已有 → Phase 2 扩展 | 新增 `_extract_user_id()` 函数（与现有 `extract_token_from_config` 同源） |
| `pyproject.toml` | **修改** | — | 加 `redis[hiredis]` 异步依赖 |
| `RecommendServiceImpl.java` | **修改**（可选） | Phase 1 已有 → Phase 2 扩展 | `recomment()` 优先读 Redis 画像，miss 降级原逻辑（即实施报告第十章"推荐结果缓存"项） |

### 7. 注意事项

1. **Phase 1 代码完整保留**：`tools.py` 中 `analyze_user_preferences` 的 `asyncio.gather` + `_accumulate_preference` 聚合逻辑（第 645-714 行）完整保留作为降级路径。新增的 `if profile:` 检查在最前面，画像命中时跳过后续所有 Gateway 调用。
2. **增量 vs 全量**：Layer 2 用 `HINCRBY` 增量更新，避免全量重算。首次冷启动降级全量计算并异步回写。
3. **画像一致性**：Agent 侧（`record_event`）和后端侧（订单创建后异步写 Redis）共享同一份画像。双方均写入事件驱动的增量数据，保证最终一致。两端写入操作均使用 `HINCRBY` 原子操作，并发安全无覆盖风险。
3. **冷启动**：新用户画像不存在 → `get_profile` 返回空 dict → 降级实时计算 → 异步回写画像 → 下次命中。
4. **隐私**：画像仅存聚合数据（类目/品牌得分），不存原始订单明细。用户可请求 `invalidate` 清除画像。
5. **Redis 连接复用**：`ProfileStore` 用独立连接池（`max_connections=20`），与 `RedisSaver`（Checkpoint）隔离。
6. **后端改造优先级**：即使后端不改，Agent 侧画像持久化也独立生效。后端改造为锦上添花，消除推荐服务的重复计算。

### 8. 实现状态（2026-07-29 更新）

> 第一部分"用户画像持久化"已全部实现，以下为实际实现与设计文档的偏差说明。

**已实现清单**：

| 设计项 | 实现状态 | 说明 |
|--------|---------|------|
| Layer 1+2 Redis 画像存储 | ✅ 已实现 | `src/profile/store.py` — `ProfileStore` 类，`profile:` 前缀 + Hash/List 结构 |
| `analyze_user_preferences` 画像优先 | ✅ 已实现 | 命中时 0 次 Gateway，miss 降级 Phase 1 + 异步回写 |
| 写操作工具画像集成 | ✅ 已实现 | 加购画像由后端 `CartServiceImpl` 写入（覆盖 Agent + 前端 UI 全路径）；`update_cart_quantity_api`/`confirm_receive_api` 不再重复记录（避免双重记录） |
| Layer 3 对话记忆 | ✅ 已实现 | `src/profile/memory.py` — `save_memory`/`get_memories`，graph.json 配置 InMemoryStore |
| 后端行为事件写入画像 | ✅ 已实现 | `paySuccessListener` 支付成功写 purchase 画像；`CartServiceImpl` 加购成功写 cart 画像（均用 `StringRedisTemplate` HINCRBY） |
| 后端推荐服务共享画像 | ✅ 已实现 | `RecommendServiceImpl.recommend()` 优先读 Redis 画像，miss 降级 |
| `_extract_user_id` | ✅ 已实现 | 复用 `extract_token_from_config` 三级优先级 + JWT 兜底解码 |
| `format_preferences` 适配 | ✅ 已实现 | orders/cart 参数改为可选，画像直读模式跳过统计行 |

**关键实现偏差**：

1. **后端 Redis 序列化兼容性**：现有 `RedisService` 的 Hash 操作使用 `redisTemplate`（Jackson 序列化），会将 hash field/value 序列化为 JSON（如 `"手机"` → `"\"手机\""`），与 Agent 侧 `redis.asyncio`（plain string `"手机"`）不兼容。**解决方案**：后端 `paySuccessListener`、`CartServiceImpl` 和 `RecommendServiceImpl` 直接注入 `StringRedisTemplate` 进行画像读写，不使用 `RedisService`。
2. **行为采集方式**：设计文档 §1.1 提到"Layer 1 行为流（Agent 侧直写 Redis，零后端改动）"，实际实现中 Agent 侧仅 `analyze_user_preferences` miss 后回写画像（`backfill_profile`），行为事件画像写入全部移至后端（`paySuccessListener` 写 purchase + `CartServiceImpl` 写 cart），覆盖 Agent + 前端 UI 全部行为路径，无需新增 `POST /behaviors` API 或 MQ Consumer。
3. **LangGraph Store 配置**：设计文档 §6 提到 `agent.py` 注入 store 参数，实际通过 `graph.json` 的 `"store": {"type": "in_memory"}` 配置，LangGraph Platform 自动注入到 `config.configurable.store`，无需修改 `create_agent()` 调用。
4. **画像写入归属调整**：加购画像从 Agent 侧 `add_to_cart_api` 移至后端 `CartServiceImpl`（覆盖前端 UI 直接加购路径）；确认收货画像从 `confirm_receive_api` 移至 `paySuccessListener`（覆盖所有支付路径）；`update_cart_quantity_api` 不再记录 cart 事件（修改数量不改变偏好方向）。调整目的：避免双重记录 + 覆盖非 Agent 入口的行为。

---

## 第二部分：主动通知

### 8. 概述

#### 8.1 背景

当前 hmall Agent 纯被动应答——用户不发消息，Agent 永远沉默。但电商有大量"该主动找用户"的场景：

| 场景 | 事件源 | 当前状态 | 用户价值 |
|------|--------|---------|---------|
| 支付成功 | `pay.direct` / `pay.success` | ❌ 无通知 | 用户安心感，减少"我付款了吗"的焦虑 |
| 订单超时取消 | `trade.delay.direct` / `delay.order` | ❌ 无通知 | 避免用户不知道订单已取消 |
| 秒杀开抢提醒 | 定时：场次 `startTime` | ❌ 无通知 | 提升秒杀参与率和转化率 |
| 物流状态变更 | 需新增物流事件源 | ❌ 无通知 | 电商用户最高频诉求 |
| 收藏商品降价 | 需新增价格监控 | ❌ 无通知 | 促进转化 |

后端已有 RabbitMQ 事件基础设施（`pay.direct`、`trade.delay.direct`、`seckill.topic` 等），Agent 侧只需新增**事件监听 + 推送通道**。

#### 8.2 设计原则

| 原则 | 说明 |
|------|------|
| **独立通道** | 通知走独立 SSE 通道，不往 Agent 对话 Thread 里塞系统消息，避免污染对话状态 |
| **双模式通知** | 模式 A（模板化轻量通知）用于支付/物流等确定性场景；模式 B（LLM 智能通知）用于秒杀/降价等营销场景 |
| **离线可恢复** | 用户不在线时通知暂存 Redis，上线后 SSE 连接建立即补发 |
| **幂等去重** | MQ 重投递场景下，`SETNX` 幂等去重防止重复推送 |
| **频率限制** | 每用户每小时通知上限，避免轰炸 |

### 9. 架构设计

```
┌──────────────┐         ┌───────────────────────┐         ┌──────────────┐
│  Java 后端   │── MQ ──►│ Agent 通知服务         │── SSE ─►│  前端通知UI  │
│ (事件生产者) │         │                       │         │              │
└──────────────┘         │ 1. EventConsumer      │         │ 1. 铃铛角标  │
                         │    监听 RabbitMQ      │         │ 2. 通知面板  │
                         │ 2. EVENT_HANDLERS     │         │ 3. 点击动作  │
                         │    事件→通知映射      │         │              │
                         │ 3. Notification       │         └──────────────┘
                         │    Dispatcher         │
                         │    SSE 推送+离线暂存  │
                         ├───────────────────────┤
                         │ Redis                 │
                         │ notify:offline:{uid}  │
                         │ notify:sent:{id}      │
                         └───────────────────────┘
```

### 10. 双模式通知机制

#### 10.1 模式 A：轻量通知（模板化，不走 LLM）

适用场景：支付成功、订单超时取消、物流状态变更等**确定性高、文案模板化**的通知。

- **延迟**：MQ 消费 + Redis 查询 + SSE 推送，总耗时 < 200ms
- **成本**：0 LLM Token
- **示例**："您的订单 `12345` 已支付成功（¥5999.00），商家正在备货"

```python
async def handle_pay_success(event: dict, dispatcher) -> None:
    """支付成功 → 轻量通知。"""
    order_id = event.get("orderId")
    order = await gateway_client.get(f"/orders/{order_id}")
    user_id = str(order.get("userId", ""))
    amount = f"{float(order.get('totalFee', 0)) / 100:.2f}"

    notification = Notification(
        user_id=user_id,
        type="payment_success",
        title="💳 支付成功",
        body=f"您的订单 `{order_id}` 已支付成功（¥{amount}），商家正在备货，请耐心等待发货。",
        action={"label": "查看订单", "order_id": order_id},
        priority="normal",
    )
    await dispatcher.dispatch(notification)
```

#### 10.2 模式 B：智能通知（LLM 个性化文案）

适用场景：秒杀开抢提醒、降价提醒等**需要结合用户画像生成个性化文案**的营销通知。

- **延迟**：MQ 消费 + LLM 推理 + SSE 推送，总耗时 ~2-3s
- **成本**：每次 ~200-500 Token（qwen-turbo）
- **示例**："您关注的数码类秒杀即将开始！iPhone 15 秒杀价 ¥4999（比您上次买的价格低 ¥1000）"

```python
async def _generate_and_dispatch(
    self, user_id: str, notification_type: str, context: dict
) -> None:
    """模式 B：结合用户画像生成个性化通知文案。"""
    from src.profile.store import profile_store
    profile = await profile_store.get_profile(user_id)
    top_cats = list(profile.get("categories", {}).keys())[:3]

    prompt = f"""你是一个友好的商城助手，请为用户生成一条简洁的通知。
用户偏好类目: {", ".join(top_cats) if top_cats else "暂无数据"}
通知类型: {notification_type}
上下文: {json.dumps(context, ensure_ascii=False)}

要求：1. 不超过 80 字 2. 语气亲切 3. 结合用户偏好自然融入"""

    try:
        response = await qwen_model.ainvoke(prompt)
        body = response.content.strip()
    except Exception:
        body = self._fallback_text(notification_type, context)  # LLM 失败降级模板
```

#### 10.3 模式选择规则

| 通知类型 | 模式 | 原因 |
|---------|------|------|
| 支付成功 | A | 模板化，确定性高 |
| 订单超时取消 | A | 模板化，确定性高 |
| 物流状态变更 | A | 模板化，确定性高 |
| 秒杀开抢提醒 | B | 需结合用户偏好，个性化提升点击率 |
| 收藏降价 | B | 需结合用户历史价格感知 |
| 运营推送 | B | 营销场景，A/B 测试文案效果 |

### 11. 事件消费者

新增 `src/notification/consumer.py`，基于 `aio-pika` 监听 RabbitMQ：

```python
class EventConsumer:
    """RabbitMQ 事件消费者。

    监听后端业务事件，按事件类型分发到对应处理器。
    """

    async def start(self) -> None:
        """启动消费者，为每个已注册事件声明队列并绑定。"""
        self._connection = await aio_pika.connect_robust(_RABBIT_URL)
        self._channel = await self._connection.channel()
        await self._channel.set_qos(prefetch_count=10)

        for event_type, handler in EVENT_HANDLERS.items():
            queue = await self._channel.declare_queue(
                handler["queue"], durable=True
            )
            await queue.bind(
                exchange=handler["exchange"],
                routing_key=handler["routing_key"],
            )
            await queue.consume(self._make_callback(event_type, handler))

        logger.info("通知事件消费服务启动完成，监听 %d 类事件", len(EVENT_HANDLERS))

    async def stop(self) -> None:
        """优雅停止：关闭 channel → connection。"""
```

#### 11.1 事件注册表

| 事件类型 | 对应 RabbitMQ | 处理函数 | 模式 |
|---------|--------------|---------|------|
| `pay_success` | `pay.direct` / `pay.success` | `handle_pay_success` | A |
| `order_timeout` | `trade.delay.direct` / `delay.order` | `handle_order_timeout` | A |
| `seckill_start` | `seckill.topic` / `seckill.start`（需后端新增） | `handle_seckill_start` | B |
| `logistics_update` | 需后端新增 | `handle_logistics_update` | A |
| `price_drop` | 需后端新增 | `handle_price_drop` | B |

### 12. SSE 推送通道

#### 12.1 通知分发器

新增 `src/notification/dispatcher.py`：

```python
class NotificationDispatcher:
    """通知分发器。

    维护 per-user 的 SSE 连接队列。
    在线用户实时推送，离线用户暂存 Redis。
    """

    def __init__(self):
        self._queues: dict[str, asyncio.Queue] = defaultdict(asyncio.Queue)
        self._connected: set[str] = set()

    def register(self, user_id: str) -> asyncio.Queue:
        """用户建立 SSE 连接时注册。"""
        self._connected.add(user_id)
        return self._queues[user_id]

    def unregister(self, user_id: str) -> None:
        """用户断开时注销。"""
        self._connected.discard(user_id)
        self._queues.pop(user_id, None)

    async def dispatch(self, notification: Notification) -> None:
        """推送通知。在线实时推送，离线暂存 Redis。"""
        if notification.user_id in self._connected:
            await self._queues[notification.user_id].put(notification)
        else:
            await self._store_offline(notification)

    async def flush_offline(self, user_id: str) -> list[dict]:
        """用户上线时补发离线通知并清理。"""

    async def dispatch_batch_smart(
        self, user_ids: list[str],
        notification_type: str, context: dict,
    ) -> None:
        """批量生成智能通知（模式 B — 走 LLM 个性化文案）。"""
        tasks = [
            self._generate_and_dispatch(uid, notification_type, context)
            for uid in user_ids
        ]
        await asyncio.gather(*tasks, return_exceptions=True)
```

#### 12.2 SSE API 端点

新增 `src/notification/api.py`，注册到 LangGraph Server 的 FastAPI app：

```python
router = APIRouter(prefix="/api/v1/notifications", tags=["notifications"])

@router.get("/stream")
async def notification_stream(user_id: str = Query(...)):
    """SSE 长连接 — 用户上线后建立，接收实时通知推送。

    建立连接时自动补发离线期间通知。
    """
    offline = await dispatcher.flush_offline(user_id)
    queue = dispatcher.register(user_id)

    async def event_generator():
        # 先补发离线通知
        for item in offline:
            yield {"event": "notification", "data": json.dumps(item, ...)}

        # 持续推送实时通知
        try:
            while True:
                notification = await queue.get()
                yield {"event": "notification",
                       "data": json.dumps(notification.to_dict(), ...)}
        except asyncio.CancelledError:
            pass
        finally:
            dispatcher.unregister(user_id)

    return EventSourceResponse(event_generator())
```

#### 12.3 启动集成

```python
# start_server.py 改动

# 注册通知 API 路由
from src.notification.api import router as notification_router
langgraph_app.include_router(notification_router)

# 启动通知事件消费者（后台任务）
from src.notification.consumer import EventConsumer
from src.notification.dispatcher import dispatcher

consumer = EventConsumer(dispatcher)

@langgraph_app.on_event("startup")
async def _start_consumer():
    await consumer.start()

@langgraph_app.on_event("shutdown")
async def _stop_consumer():
    await consumer.stop()
```

### 13. 前端集成

#### 13.1 通知 Composable

新增 `hmall-frontend/src/composables/useNotifications.ts`：

```typescript
export function useNotifications(userId: Ref<string>) {
  const notifications = ref<AppNotification[]>([])
  const unreadCount = ref(0)
  let _eventSource: EventSource | null = null

  function connect() {
    if (!userId.value) return
    const apiUrl = import.meta.env.VITE_AGENT_URL || 'http://localhost:8090'
    _eventSource = new EventSource(
      `${apiUrl}/api/v1/notifications/stream?user_id=${userId.value}`
    )

    _eventSource.addEventListener('notification', (e: MessageEvent) => {
      const notification = JSON.parse(e.data)
      notifications.value.unshift(notification)
      unreadCount.value++
    })

    // 断线自动重连（5s 退避）
    _eventSource.onerror = () => {
      _eventSource?.close()
      setTimeout(connect, 5000)
    }
  }

  return { notifications, unreadCount, connect, disconnect, markAllRead }
}
```

#### 13.2 通知 UI

前端在对话页面**顶部导航栏**增加通知铃铛组件：

- 未读红点角标（`unreadCount`）
- 点击弹出通知面板（最近 20 条）
- 每条通知可点击 `action` 执行跳转（如"查看订单"→ 跳转订单详情页）
- 消息卡片形式：icon + title + body + 时间

### 14. 改动清单

| 文件 | 改动 | 说明 |
|------|------|------|
| `src/notification/models.py` | **新增** | `Notification` dataclass |
| `src/notification/consumer.py` | **新增** | RabbitMQ 事件消费者（aio-pika） |
| `src/notification/rules.py` | **新增** | 事件→通知映射规则 + 处理器函数 |
| `src/notification/dispatcher.py` | **新增** | SSE 推送 + 离线暂存 + 批量智能通知 |
| `src/notification/api.py` | **新增** | SSE 端点 `/api/v1/notifications/stream` |
| `start_server.py` | **修改** | 注册路由 + 启动事件消费者 |
| `src/core/config.py` | **修改** | 新增 `RABBIT_HOST/PORT/USER/PASSWORD` |
| `pyproject.toml` | **修改** | 加 `aio-pika`、`sse-starlette` |
| `src/composables/useNotifications.ts` | **新增** | 前端通知 SSE composable |
| 前端通知 UI 组件 | **新增** | 铃铛角标 + 通知下拉面板 |
| 后端 `trade-service` | **新增**（可选） | 秒杀场次开始时发送 `seckill.start` 事件 |
| 后端物流服务 | **新增**（可选） | 物流状态变更时发送 `logistics.update` 事件 |

### 15. 注意事项

1. **独立通道 vs Agent Thread**：通知走独立 SSE 通道，不往 Agent 对话 Thread 里塞消息。原因：Agent Thread 是用户主动发起的对话上下文，塞系统通知会污染对话状态、影响 LLM 推理。通知是独立信息流，前端在对话面板外展示（铃铛角标 + 通知面板）。

2. **幂等去重**：同一事件可能因 MQ 重投递被消费多次。在 `dispatch` 前用 Redis `SETNX notify:sent:{event_type}:{event_id}` 做幂等去重（24h TTL）。

3. **通知频率控制**：per-user 每小时通知上限（默认 10 条），超限丢弃低优先级通知（priority=normal）。秒杀开抢等高优先级（priority=high）不受限。防止 MQ 异常时消息轰炸。

4. **离线通知**：用户不在线时通知暂存 Redis 列表（TTL 7 天），上线时 SSE 连接建立即补发。避免"用户没开 App 就错过支付成功通知"。

5. **SSE 连接管理**：`dispatcher` 维护 `user_id → Queue` 映射。断线由前端重连（5s 退避），服务端 `CancelledError` 捕获断开并 `unregister`。

6. **安全性**：生产环境应从 JWT 提取 `user_id` 而非前端传参，防止横向越权监听他人通知。

7. **后端事件补充**：当前后端有 `pay.success`（支付成功）和 `delay.order`（订单超时取消）。秒杀开抢需后端新增定时任务发 `seckill.start`；物流变更需对接物流 API 后发事件。这两项为渐进式扩展，Agent 侧事件注册表已预留接口。

8. **与方案 4（画像）的依赖**：模式 B 智能通知需读取用户画像生成个性化文案。因此方案 4 应先于方案 9 中的模式 B 功能落地。模式 A 轻量通知可独立先行。

---

## 推进计划

| 阶段 | 内容 | 依赖 | 预估工作量 |
|------|------|------|-----------|
| 阶段 1 | 方案 4 — Layer 1+2 Redis 画像存储 + Agent 侧读写 | 无 | 3-5 人天 |
| 阶段 2 | 方案 4 — Layer 3 LangGraph Store 语义记忆 | 阶段 1 | 1-2 人天 |
| 阶段 3 | 方案 4 — 后端 `RecommendServiceImpl` 共享画像（可选） | 阶段 1 | 1-2 人天 |
| 阶段 4 | 方案 9 — 模式 A 轻量通知（支付成功 + 超时取消） | 无 | 2-3 人天 |
| 阶段 5 | 方案 9 — 模式 B 智能通知（秒杀开抢提醒） | 阶段 1 + 阶段 4 | 2-3 人天 |
| 阶段 6 | 方案 9 — 前端通知 UI 组件 | 阶段 4 | 1 人天 |
| 阶段 7 | 方案 9 — 后端新增事件（秒杀/物流） | 阶段 4 | 待定（后端团队） |

总预估纯 Agent 侧工作量：**10-16 人天**（不含后端协作部分）。
