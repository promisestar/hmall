# Agent 个性化推荐能力扩展设计方案

> 版本：v1.0
> 日期：2026-07-16
> 关联文档：`docs/Agent功能相关文档/hmall-agent-design.md`（v2.1）
>
> 对应 `hmall-agent-design.md` 第 15 节"后续优化方向"中 P2 项：基于用户浏览/购买历史的个性化推荐。

---

## 1. 概述

### 1.1 背景与目标

当前 CustomerAgent 已具备商品浏览、搜索、秒杀、购物车、订单、地址等全链路交互能力（18 个工具），但**商品发现仍依赖用户主动搜索**。用户不会问"有什么推荐"，因为 Agent 没有推荐能力。

本设计为 CustomerAgent 扩展**对话式个性化推荐**能力，核心目标：

- 让 Agent 能基于用户购买/浏览历史主动推荐商品
- 推荐结果附带**可解释的推荐理由**（"因为您买过 X，所以推荐 Y"）
- 支持"猜你喜欢""看了又看""购物车凑单"三种对话场景
- Agent 能自主分析用户偏好并组合工具形成推荐策略

### 1.2 Agent 推荐与传统推荐的本质差异

| 维度 | 传统推荐系统 | Agent 对话式推荐 |
|------|-------------|-----------------|
| 触发方式 | 页面加载自动渲染 | 对话中主动触发或用户询问 |
| 可解释性 | 黑盒算法 | ✅ LLM 生成自然语言推荐理由 |
| 上下文 | 仅用户行为画像 | ✅ 对话上下文 + 用户偏好 + 实时意图 |
| 灵活性 | 固定召回-排序管线 | ✅ LLM 可自主组合多工具动态决策 |
| 冷启动 | 算法层兜底 | ✅ Agent 可降级搜索、主动询问偏好 |
| 交互闭环 | 单向推送 | ✅ 推荐→用户反馈→再推荐 |

**核心定位**：推荐算法在后端，**推荐策略和交互在 Agent**。Agent 不是推荐算法的薄封装，而是能"理解用户、解释推荐、动态调整"的推荐对话体。

### 1.3 设计原则

| 原则 | 说明 |
|------|------|
| **Agent 主导** | 推荐触发、策略选择、理由生成都由 Agent 层完成，后端只提供数据 |
| **复用现有基建** | 不引入推荐框架/训练平台，复用 `gateway_client`/ES/Redis/RabbitMQ |
| **演进式落地** | 先用现有 `order_detail` + `item` 表 SQL 聚合，再逐步加入浏览行为 |
| **优雅降级** | 推荐接口失败→热销兜底；画像为空→Agent 主动询问偏好 |
| **零侵入交易链路** | 购买行为从 `paySuccessListener` 旁路采集，不侵入订单写库事务 |
| **可解释性优先** | 每条推荐都附理由，理由由 LLM 结合偏好生成，非后端模板拼接 |

---

## 2. 整体架构

### 2.1 系统架构

```
用户（C端对话）
  │
  │  "有什么推荐" / "帮我选个手机" / 查看商品后
  ▼
┌──────────────────────────────────────────────────────────────────┐
│              Agent Service (LangGraph Server :8090)              │
│                                                                    │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │  中间件层（DeepAgent Middleware Chain）                      │   │
│  │  ├── AuthMiddleware（JWT 透传，注入 user_id）                 │   │
│  │  ├── PermissionMiddleware（推荐工具需登录）                    │   │
│  │  ├── RegexShortcutMiddleware（L1: "推荐/猜你喜欢" 快捷路由）  │   │
│  │  └── SkillsMiddleware（加载 personalized-recommendation）    │   │
│  └───────────────────────┬────────────────────────────────────┘   │
│                          │                                         │
│  ┌───────────────────────▼────────────────────────────────────┐   │
│  │  CustomerAgent（扩展后 20 个工具）                            │   │
│  │                                                               │   │
│  │  新增工具：                                                    │   │
│  │  ├── get_recommendations_api(scene, size, item_id)           │   │
│  │  │     → 调用后端推荐接口，返回商品列表                         │   │
│  │  └── analyze_user_preferences()                               │   │
│  │        → 聚合购买历史+购物车，返回偏好画像                       │   │
│  │                                                               │   │
│  │  复用工具：                                                    │   │
│  │  ├── search_items_api（降级搜索 / 偏好驱动搜索）              │   │
│  │  ├── get_item_detail_api（推荐后查看详情）                     │   │
│  │  └── add_to_cart_api（推荐后加购）                             │   │
│  └───────────────────────┬────────────────────────────────────┘   │
│                          │                                         │
│  │  LLM 推理层（qwen-turbo）                                    │   │
│  │  ├── 理解用户推荐意图                                         │   │
│  │  ├── 选择推荐策略（直接推荐 / 偏好分析再推荐 / 搜索补充）     │   │
│  │  ├── 结合偏好生成推荐理由                                     │   │
│  │  └── 主动追问推荐反馈                                         │   │
│                          │                                         │
└──────────────────────────┬───────────────────────────────────────┘
                           │ httpx (异步 HTTP)
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│              hm-gateway (:8080)                                    │
│  ├── AuthGlobalFilter（JWT → userId 透传）                          │
│  └── 新增路由：/recommend/**, /behaviors/**                         │
└──┬────────────────┬────────────────────────────────────────────────┘
   │                │
   ▼                ▼
 item-service     trade-service
 :8081            :8085
 ├── /recommend   ├── order_detail（购买历史）
 │   (新增)       └── paySuccessListener（行为旁路）
 ├── /behaviors
 │   (新增)
 └── search-service :8089
     └── ES 召回（已有）
```

### 2.2 推荐数据流

```
                    ┌─ 数据采集（异步） ─────────────────────────┐
                    │                                            │
  前端 ProductDetail ─► POST /behaviors ─► RabbitMQ ─► Consumer  │
  (浏览埋点)            (type=view)                     │         │
                                                       ▼         │
  trade-service paySuccessListener ─► RabbitMQ ─► Consumer        │
  (购买旁路)            (type=purchase)      │         │           │
                                              ▼         ▼         │
                                    ┌── Redis 画像 ──┐             │
                                    │  up:{uid}:cat  │  (ZSet)    │
                                    │  up:{uid}:brand│  (ZSet)    │
                                    │  bh:{uid}:recent│ (ZSet)   │
                                    └────────────────┘             │
                    └────────────────────────────────────────────────┘
                                    │
                    ┌─ 推荐召回 ────▼──────────────────────────────┐
                    │                                              │
  Agent 调用 GET /recommend?scene=home&size=10                     │
                    │                                              │
  后端 RecommendService                                             │
  ├── 1. 取用户偏好（Redis 画像）                                  │
  ├── 2. Content-Based 召回（ES: 按 category/brand 过滤）         │
  ├── 3. Item-CF 召回（Redis: 看了又看共现矩阵）                   │
  ├── 4. 热门兜底（item.sold 倒序，冷启动）                        │
  ├── 5. 过滤已购 / 已下架                                        │
  └── 6. 返回商品列表 + 推荐标签                                   │
                    │                                              │
  Agent Formatter 格式化                                            │
                    │                                              │
  LLM 结合偏好生成推荐理由                                         │
                    │                                              │
  返回带理由的推荐话术给用户                                       │
                    └──────────────────────────────────────────────┘
```

### 2.3 与现有三级路由的关系

推荐能力接入现有三级路由体系，不改变路由架构：

```
用户消息
  │
  ├─ L1: RegexShortcutMiddleware
  │   ├── "推荐" / "猜你喜欢" → get_recommendations_api(scene=home)  ← 新增正则
  │   └── 其他正则规则不变
  │
  ├─ L2: interrupt
  │   └── 推荐不涉及 interrupt（只读操作）
  │
  └─ L3: LLM 兜底
      ├── "帮我选个手机" → LLM 先调 analyze_user_preferences
      │                    → 再调 search_items_api / get_recommendations_api
      │                    → 结合偏好生成推荐
      └── "看了又看" → LLM 从对话上下文提取 item_id
                       → 调 get_recommendations_api(scene=detail, item_id=xxx)
```

---

## 3. Agent 侧实现（核心）

### 3.1 新增工具：`get_recommendations_api`

封装后端推荐接口，Agent 只负责决定何时调用、如何解释结果。

```python
# src/agents/customer/tools.py（新增）

@tool
async def get_recommendations_api(
    config: RunnableConfig,
    scene: str = "home",
    size: int = 10,
    item_id: int = 0,
) -> str:
    """基于用户浏览/购买历史获取个性化商品推荐。

    Args:
        scene: 推荐场景
            - home: 猜你喜欢（首页推荐，基于用户整体偏好）
            - detail: 看了又看（基于指定商品找相似）
            - cart: 购物车凑单推荐
        size: 返回数量，默认 10
        item_id: 当前商品 ID（scene=detail 时必填）
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 个性化推荐需要先登录，登录后我可以根据您的偏好推荐商品"

    params = {"scene": scene, "size": size}
    if item_id:
        params["itemId"] = item_id

    try:
        result = await gateway_client.get("/recommend", token=token, params=params)
        return format_recommendations(result, scene)
    except GatewayError as e:
        if e.status_code == 401:
            return "❌ 登录已过期，请重新登录后获取推荐"
        # 降级：推荐接口失败时提示用户可手动搜索
        return f"推荐服务暂时不可用，您可以尝试搜索商品。错误: {e}"
```

**设计要点**：

| 决策 | 理由 |
|------|------|
| `scene` 参数化 | 让 LLM 根据对话上下文判断场景，而非后端猜测 |
| `item_id` 可选 | "看了又看"需要种子商品，其他场景不需要 |
| 复用 `extract_token_from_config` | 与现有 18 个工具的认证模式一致（`tools.py:127`） |
| 降级返回提示 | 推荐非核心链路，失败时引导用户搜索，不阻断对话 |
| 不含推荐理由 | 理由由 LLM 结合偏好生成，后端只给商品列表 + 标签 |

**后端响应格式**（`GET /recommend` 返回）：

```json
{
  "list": [
    {
      "id": 1001,
      "name": "iPhone 15 Pro",
      "price": 799900,
      "stock": 45,
      "brand": "Apple",
      "category": "手机",
      "sold": 1200,
      "recommendTags": ["同类目热销", "您常买的品牌"]
    }
  ],
  "total": 10,
  "basedOn": {
    "topCategories": ["手机", "耳机"],
    "topBrands": ["Apple", "Sony"]
  }
}
```

`recommendTags` 是后端给的商品级标签（如"同类目热销""您常买的品牌"），`basedOn` 是推荐依据摘要——这些都传给 LLM，由 LLM 组织成自然语言推荐理由。

### 3.2 新增工具：`analyze_user_preferences`

这是 Agent 推荐的**差异化工具**——不依赖后端画像服务，直接用现有工具的数据聚合出用户偏好。让 Agent 拥有"看懂用户"的能力。

```python
# src/agents/customer/tools.py（新增）

@tool
async def analyze_user_preferences(config: RunnableConfig) -> str:
    """分析当前用户的购物偏好（基于购买历史和购物车）。

    返回偏好的类目、品牌、价格区间，供推荐和搜索参考。
    需要登录。
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 偏好分析需要先登录"

    try:
        # 并发获取购买历史和购物车
        orders_page, cart_items = await asyncio.gather(
            gateway_client.get("/orders/page", token=token,
                               params={"pageNo": 1, "pageSize": 50}),
            gateway_client.get("/carts", token=token),
        )
    except GatewayError as e:
        return f"❌ 获取用户数据失败: {e}"

    # 聚合分析
    orders = orders_page.get("list", []) or orders_page.get("records", [])
    cart = cart_items or []

    category_scores = {}  # category -> 累计分
    brand_scores = {}     # brand -> 累计分
    price_points = []     # 所有购买价格

    # 购买历史（权重 5）
    for order in orders:
        details = order.get("orderDetails", []) or order.get("details", [])
        for d in details:
            _accumulate_preference(
                category_scores, brand_scores, price_points, d, weight=5
            )

    # 购物车（权重 3）
    for item in cart:
        _accumulate_preference(
            category_scores, brand_scores, price_points, item, weight=3
        )

    return format_preferences(category_scores, brand_scores, price_points, orders, cart)
```

**聚合辅助函数**：

```python
# src/agents/customer/tools.py（新增，模块级私有函数）

def _accumulate_preference(cat_scores, brand_scores, prices, item, weight):
    """累加单个商品的偏好分数到聚合字典。"""
    category = item.get("category", "")
    brand = item.get("brand", "")
    price = item.get("price")
    num = item.get("num", 1)

    if category:
        cat_scores[category] = cat_scores.get(category, 0) + weight * num
    if brand:
        brand_scores[brand] = brand_scores.get(brand, 0) + weight * num
    if price:
        prices.append(price)
```

**设计要点**：

| 决策 | 理由 |
|------|------|
| 并发获取订单+购物车 | `asyncio.gather` 并发，复用现有 `get_order_list_api`/`get_cart_list_api` 的 Gateway 路径 |
| 购买权重 5，购物车权重 3 | 购买是更强信号，购物车是意向信号 |
| 不依赖后端画像服务 | 直接用现有 `/orders/page` + `/carts` 接口，零后端改动即可启用 |
| 返回结构化偏好文本 | LLM 拿到偏好后可自主决定后续策略（搜索/推荐/追问） |

**偏好输出格式**：

```
📊 您的购物偏好分析
─────────────────────
基于 5 笔订单 + 3 件购物车商品

偏好类目 Top 3:
  1. 手机（得分 25）
  2. 耳机（得分 10）
  3. 手机壳（得分 5）

偏好品牌 Top 3:
  1. Apple（得分 30）
  2. Sony（得分 10）

价格区间:
  常购价格：¥3,000 ~ ¥8,000
  平均客单价：¥4,599
```

### 3.3 新增 Formatter 函数

在 `src/tools/formatters.py` 中新增两个格式化函数，复用现有 `_yuan()`（`formatters.py:10-17`）和 `_status_text()`（`formatters.py:20-24`）辅助函数。

```python
# src/tools/formatters.py（新增）

# ==================== 个性化推荐 ====================


def format_recommendations(page_dto: dict, scene: str = "home") -> str:
    """格式化推荐商品列表。

    Args:
        page_dto: 后端 /recommend 返回的数据
        scene: 推荐场景（home/detail/cart）
    """
    if not page_dto:
        return "暂无推荐商品，您可以尝试搜索看看"

    items = page_dto.get("list", [])
    total = page_dto.get("total", len(items))
    based_on = page_dto.get("basedOn", {})

    if not items:
        return "暂无推荐商品，您可以尝试搜索看看"

    scene_titles = {
        "home": "猜你喜欢",
        "detail": "看了又看",
        "cart": "凑单推荐",
    }
    title = scene_titles.get(scene, "为你推荐")

    lines = [f"🎯 {title}（共 {total} 件）", "─" * 30]

    for i, item in enumerate(items, 1):
        name = item.get("name", "未知商品")
        price = _yuan(item.get("price"))
        stock = item.get("stock", 0)
        item_id = item.get("id", "")
        tags = item.get("recommendTags", [])

        tag_str = f" [{', '.join(tags)}]" if tags else ""
        lines.append(f"{i}. {name} | ¥{price} | 库存 {stock} 件 [ID:{item_id}]{tag_str}")

    # 推荐依据摘要
    if based_on:
        cats = based_on.get("topCategories", [])
        brands = based_on.get("topBrands", [])
        if cats or brands:
            lines.append("─" * 30)
            parts = []
            if cats:
                parts.append(f"偏好类目: {', '.join(cats[:3])}")
            if brands:
                parts.append(f"偏好品牌: {', '.join(brands[:3])}")
            lines.append("推荐依据: " + " | ".join(parts))

    return "\n".join(lines)


def format_preferences(
    cat_scores: dict,
    brand_scores: dict,
    prices: list,
    orders: list,
    cart: list,
) -> str:
    """格式化用户偏好分析结果。"""
    order_count = len(orders)
    cart_count = len(cart)

    if order_count == 0 and cart_count == 0:
        return "暂无足够的购买/购物车数据来分析偏好，推荐时将使用热门商品兜底"

    lines = [
        "📊 您的购物偏好分析",
        "─" * 30,
        f"基于 {order_count} 笔订单 + {cart_count} 件购物车商品",
    ]

    # 类目 Top 3
    if cat_scores:
        sorted_cats = sorted(cat_scores.items(), key=lambda x: x[1], reverse=True)
        lines.append("\n偏好类目 Top 3:")
        for i, (cat, score) in enumerate(sorted_cats[:3], 1):
            lines.append(f"  {i}. {cat}（得分 {score}）")

    # 品牌 Top 3
    if brand_scores:
        sorted_brands = sorted(brand_scores.items(), key=lambda x: x[1], reverse=True)
        lines.append("\n偏好品牌 Top 3:")
        for i, (brand, score) in enumerate(sorted_brands[:3], 1):
            lines.append(f"  {i}. {brand}（得分 {score}）")

    # 价格区间
    if prices:
        min_price = _yuan(min(prices))
        max_price = _yuan(max(prices))
        avg_price = _yuan(sum(prices) / len(prices))
        lines.append("\n价格区间:")
        lines.append(f"  常购价格：¥{min_price} ~ ¥{max_price}")
        lines.append(f"  平均客单价：¥{avg_price}")

    return "\n".join(lines)
```

### 3.4 新增 Skill：`personalized-recommendation`

```markdown
# src/workspace/customer/skills/personalized-recommendation/SKILL.md

# 个性化推荐技能

## 适用场景
用户想要商品推荐、想看猜你喜欢、浏览商品后想看相关推荐、
购物车凑单，或表达模糊购物意图（"帮我选""随便看看"）时激活。

## 工作流程

### 场景 1：首页推荐 / 猜你喜欢
用户说："有什么推荐" / "猜我喜欢什么" / "帮我选个商品"
1. 调用 get_recommendations_api(scene="home", size=10)
2. 结合返回的 basedOn 信息，生成推荐理由
3. 主动询问用户对哪些推荐感兴趣

### 场景 2：看了又看（商品详情后推荐）
用户查看某商品后，或说："还有类似的吗" / "看了又看"
1. 从对话上下文提取当前 item_id
2. 调用 get_recommendations_api(scene="detail", item_id=xxx, size=5)
3. 说明"与您刚看的 X 相似"并推荐

### 场景 3：购物车凑单
用户说："购物车还能加点什么" / "凑单推荐"
1. 调用 get_recommendations_api(scene="cart", size=5)
2. 说明推荐商品与购物车商品的搭配关系

### 场景 4：偏好驱动推荐（Agent 自主推理）
用户说："我想换个手机" / "推荐点苹果生态的产品"
1. 调用 analyze_user_preferences() 获取用户偏好
2. 根据偏好用 search_items_api 搜索匹配商品
3. 结合偏好解释推荐理由

## 可用工具
- `get_recommendations_api(scene, size, item_id)` — 后端推荐召回
- `analyze_user_preferences()` — 用户偏好分析
- `search_items_api(keyword)` — 偏好驱动搜索（推荐不足时补充）
- `get_item_detail_api(item_id)` — 用户对推荐商品感兴趣时查看详情
- `add_to_cart_api(item_id)` — 推荐后加购

## 推荐理由生成规则
- 结合 analyze_user_preferences 返回的偏好类目/品牌
- 结合 get_recommendations_api 返回的 recommendTags 和 basedOn
- 生成自然语言理由，如："您之前购买过 iPhone 14，可能对这款 iPhone 15 感兴趣"
- 如无偏好数据，说明是热销推荐："这款是近期热销商品，评价不错"

## 输出格式
```
🎯 为你推荐（基于你的购买偏好）
─────────────────────
1. iPhone 15 Pro | ¥7999.00 | 库存 45 件 [ID:2001]
   推荐理由：您常买 Apple 品牌产品，这款是同品类热销款
2. AirPods Pro | ¥1899.00 | 库存 120 件 [ID:2002]
   推荐理由：搭配您购物车中的 iPhone 使用，耳机很合适

需要查看详情或加入购物车吗？
```

## 注意事项
- 推荐需要登录，未登录时提示用户先登录
- 推荐接口失败时降级为 search_items_api 搜索热销
- 新用户无购买历史时，后端返回热销榜，不强行解释推荐理由
- 推荐后主动询问用户反馈，形成推荐→反馈→再推荐的闭环
```

**Skill 注册**：在 `agent.py` 的 `sources` 列表中新增：

```python
# src/agents/customer/agent.py（修改 skills_middleware 配置）

skills_middleware = SkillsMiddleware(
    backend=skills_backend,
    sources=[
        "/skills/shopping-guide/",
        "/skills/seckill-order/",
        "/skills/cart-management/",
        "/skills/order-management/",
        "/skills/address-management/",
        "/skills/personalized-recommendation/",  # ← 新增
    ],
)
```

### 3.5 Prompt 增强

在 `src/agents/customer/prompts.py` 的 `SYSTEM_PROMPT` 中扩展能力声明和行为准则：

```python
# src/agents/customer/prompts.py（修改 SYSTEM_PROMPT）

SYSTEM_PROMPT = """你是枫叶商城（hmall）的 AI 客服助手，帮助用户完成购物全流程操作。

## 你的能力
1. **商品浏览**：搜索商品、查看商品详情、分页浏览商品列表
2. **秒杀活动**：查看秒杀活动列表、查看秒杀商品详情、秒杀下单（需二次确认）
3. **购物车管理**：查看购物车、加入购物车、修改数量、删除商品（需确认）、清空购物车（需确认）
4. **订单管理**：查看订单列表、查看订单详情、取消订单（需确认）、确认收货（需确认）
5. **收货地址**：查看地址列表、新增地址（多轮收集）、修改地址（多轮收集）
6. **个性化推荐**：基于用户购买/浏览历史推荐商品，支持猜你喜欢、看了又看、
   购物车凑单等场景，推荐时附带推荐理由

## 行为准则
- 始终使用中文回复，语气友好亲切
- 查询操作优先使用工具获取实时数据，不编造信息
- 危险操作（取消订单、删除商品、清空购物车、秒杀下单）必须通过 interrupt 二次确认
- 修改地址时通过 interrupt 多轮收集要修改的字段和新值
- 空数据时直接返回固定提示，不生成虚假数据
- 价格以「元」为单位显示（后端返回的是「分」）
- 如果用户未登录但请求需要登录的操作，提示用户先登录
- **当用户表达"随便看看""有什么推荐""帮我选"等模糊购物意图时，主动调用推荐工具**
- **用户购买/浏览某商品后，可顺势推荐相关商品（看了又看）**
- **推荐时务必说明推荐理由，让用户理解为何被推荐，理由基于用户偏好生成**
- **推荐后主动询问用户是否查看详情或加入购物车，形成推荐闭环**

## 输出格式
- 商品列表：编号. 商品名 | 价格 | 库存 [ID:xxx]
- 秒杀活动：活动名 [状态] → 场次 → 商品（秒杀价/原价/剩余）
- 购物车：编号. 商品名 | 单价 × 数量 | 小计
- 订单：编号. 订单号 | 金额 | 状态 | 日期
- 地址：编号. 姓名 手机号 [默认] → 完整地址
- **推荐：编号. 商品名 | 价格 | 库存 [ID:xxx] [推荐标签]**
- **推荐理由：基于用户偏好的自然语言说明**

## 注意事项
- 商品 ID、订单 ID、地址 ID 等标识符用 [ID:xxx] 标注，方便用户引用
- 秒杀下单前必须先展示商品详情和确认提示
- 不要向用户暴露技术细节（如 API 路径、Token 等）
"""
```

### 3.6 L1 正则路由规则

在 `src/agents/customer/regex_rules.py` 中新增推荐意图的快捷路由：

```python
# src/agents/customer/regex_rules.py（新增规则）


def _extract_recommend_scene(m: re.Match) -> dict:
    """从正则匹配中提取推荐场景。"""
    keyword = m.group(1) if m.groups() else ""
    if "凑单" in keyword or "购物车" in keyword:
        return {"scene": "cart"}
    return {"scene": "home"}


# 在 REGEX_RULES 列表中新增（插入在商品搜索规则之前）：

REGEX_RULES = [
    # ... 现有规则 ...

    # === 个性化推荐（只读） ===
    # 首页推荐 / 猜你喜欢
    (
        r"(?:推荐|猜你喜欢|有什么好|帮我选|随便看看|给我推荐)",
        "get_recommendations_api",
        _extract_recommend_scene,
    ),
    # 购物车凑单推荐
    (
        r"(?:购物车|凑单).{0,5}(?:推荐|加|添|凑)",
        "get_recommendations_api",
        lambda m: {"scene": "cart"},
    ),

    # ... 现有商品搜索规则 ...
]
```

**正则路由规则表（新增部分）**：

| 用户输入示例 | 匹配正则 | 路由工具 | 参数 |
|-------------|---------|---------|------|
| `有什么推荐` / `猜你喜欢` | `(?:推荐\|猜你喜欢\|有什么好\|帮我选\|随便看看\|给我推荐)` | `get_recommendations_api` | `scene=home` |
| `购物车推荐` / `凑单推荐` | `(?:购物车\|凑单).{0,5}(?:推荐\|加\|添\|凑)` | `get_recommendations_api` | `scene=cart` |
| `看了又看` / `相似商品` | （不拦截，走 L3 LLM） | `get_recommendations_api` | LLM 从上下文提取 `item_id` |

**"看了又看"不走 L1 的原因**：需要从对话上下文提取当前商品 ID，正则无法做到。由 L3 LLM 处理，LLM 能从最近对话历史中推断 `item_id` 并调用 `get_recommendations_api(scene="detail", item_id=xxx)`。

**工具注册更新**：

```python
# src/agents/customer/tools.py（修改 get_all_tools）

def get_all_tools():
    """返回 CustomerAgent 所需的全部工具列表。"""
    return [
        # 商品浏览
        search_items_api,
        get_item_detail_api,
        get_item_page_api,
        # 秒杀
        get_seckill_activities_api,
        get_seckill_product_api,
        do_seckill_api,
        # 购物车
        get_cart_list_api,
        add_to_cart_api,
        update_cart_quantity_api,
        delete_cart_item_api,
        clear_cart_api,
        # 订单
        get_order_list_api,
        get_order_detail_api,
        cancel_order_api,
        confirm_receive_api,
        # 地址
        get_address_list_api,
        add_address_api,
        update_address_api,
        # 个性化推荐（新增）
        get_recommendations_api,
        analyze_user_preferences,
    ]
```

工具总数从 18 → 20。

### 3.7 工具变更总览

| 变更类型 | 文件 | 改动 |
|---------|------|------|
| 新增工具 | `src/agents/customer/tools.py` | `get_recommendations_api` + `analyze_user_preferences` + `_accumulate_preference` |
| 修改注册 | `src/agents/customer/tools.py` | `get_all_tools()` 新增 2 个工具 |
| 新增 Formatter | `src/tools/formatters.py` | `format_recommendations` + `format_preferences` |
| 新增 Skill | `src/workspace/customer/skills/personalized-recommendation/SKILL.md` | 推荐工作流规范 |
| 修改注册 | `src/agents/customer/agent.py` | `sources` 新增 `/skills/personalized-recommendation/` |
| 修改 Prompt | `src/agents/customer/prompts.py` | 能力声明 + 行为准则 + 输出格式 |
| 修改正则 | `src/agents/customer/regex_rules.py` | 新增 2 条推荐匹配规则 + `_extract_recommend_scene` |

---

## 4. Agent 推荐的三种触发模式

这是 Agent 推荐区别于传统推荐的核心设计——Agent 能在不同对话时机，以不同策略触发推荐。

### 4.1 模式 A：用户主动请求推荐

```
用户: "有什么好物推荐吗？"
  │
  ├─ L1 正则命中 "(?:推荐|猜你喜欢|有什么好|帮我选|随便看看)"
  │   → get_recommendations_api(scene="home", size=10)
  │   → 后端返回商品列表 + basedOn
  │   → format_recommendations 格式化
  │
  └─ 直接返回（跳过 LLM，<5ms）:
      🎯 猜你喜欢（共 10 件）
      ─────────────────────
      1. iPhone 15 Pro | ¥7999.00 | 库存 45 件 [ID:2001] [同类目热销, 您常买的品牌]
      2. AirPods Pro | ¥1899.00 | 库存 120 件 [ID:2002] [搭配推荐]
      ...
      推荐依据: 偏好类目: 手机, 耳机 | 偏好品牌: Apple, Sony
```

**特点**：L1 正则快捷路由，零 LLM 成本，响应 <5ms。适用于高频的"猜你喜欢"场景。

### 4.2 模式 B：Agent 主动推荐（对话式 Upsell）

```
用户: "帮我看看 iPhone 15"
  │
  ├─ LLM 调用 get_item_detail_api(item_id=2001)
  ├─ 展示商品详情:
  │   📦 商品详情 [ID:2001]
  │   名称: iPhone 15
  │   价格: ¥5999.00
  │   ...
  │
  ├─ LLM 判断用户有购买兴趣 → 主动调 get_recommendations_api(scene="detail", item_id=2001)
  │   → 后端返回相似/搭配商品
  │
  └─ 追加推荐:
      您可能还对这些感兴趣：
      1. AirPods Pro | ¥1899.00 [ID:2002] — 搭配 iPhone 使用
      2. iPhone 15 手机壳 | ¥99.00 [ID:2003] — 配件推荐
      需要查看详情或加入购物车吗？
```

**特点**：LLM 在展示商品后**主动触发**推荐——这是传统推荐系统做不到的对话式 upsell。Prompt 中引导 Agent "用户购买/浏览某商品后，可顺势推荐相关商品"。

### 4.3 模式 C：偏好驱动推荐（Agent 自主推理）

```
用户: "我最近想换个手机，预算 5000 左右"
  │
  ├─ LLM 推理：用户有明确需求但需要个性化建议
  │
  ├─ LLM 调用 analyze_user_preferences()
  │   → 返回偏好: 类目=手机, 品牌=Apple, 价格区间 ¥3000-¥8000
  │
  ├─ LLM 结合偏好 + 用户预算 → 调用 search_items_api("iPhone")
  │   → 搜索结果中筛选符合预算的
  │
  └─ 生成带偏好解释的推荐:
      根据您的购买记录，您偏好 Apple 品牌，之前买过 iPhone 14。
      在 ¥5000 预算内为您推荐：
      1. iPhone 15 | ¥4999.00 [ID:2001] — 新款，比您之前用的性能提升 30%
      2. iPhone 14 Plus | ¥4299.00 [ID:2005] — 大屏体验
      需要查看详细对比吗？
```

**特点**：Agent **不完全依赖后端推荐接口**，而是 LLM 基于偏好分析自主决策——先用 `analyze_user_preferences` 理解用户，再用 `search_items_api` 精准搜索，最后结合偏好生成推荐理由。这是 Agent 最灵活的推荐模式。

### 4.4 三种模式对比

| 维度 | 模式 A（主动请求） | 模式 B（主动 Upsell） | 模式 C（偏好驱动） |
|------|-------------------|---------------------|-------------------|
| 触发方 | 用户 | Agent（LLM 主动） | Agent（LLM 推理） |
| 路由层 | L1 正则（<5ms） | L3 LLM（~2s） | L3 LLM（~3s，多工具） |
| 工具调用 | `get_recommendations_api` | `get_item_detail_api` → `get_recommendations_api` | `analyze_user_preferences` → `search_items_api` |
| 推荐理由来源 | 后端 `basedOn` + tags | LLM 结合商品 + 相似性 | LLM 结合偏好 + 预算 |
| 适用场景 | 高频"猜你喜欢" | 商品详情后"看了又看" | 复杂购物咨询 |
| LLM 成本 | 零 | 1 次推理 | 1-2 次推理 |

---

## 5. 冷启动处理（Agent 侧）

| 场景 | Agent 行为 | 后端行为 |
|------|-----------|---------|
| 未登录用户 | Prompt 提示"登录后可获个性化推荐" | 接口返回 401 |
| 新用户（无购买历史） | `analyze_user_preferences` 返回"暂无足够数据" | `get_recommendations_api` 返回热销榜 |
| 推荐接口失败 | Agent 降级为 `search_items_api` 按热门关键词搜索 | 返回错误 |
| 画像为空 | LLM 主动询问："您平时喜欢什么类型的商品？我可以帮您推荐" | — |

**冷启动对话示例**：

```
用户: "有什么推荐"
  │
  ├─ get_recommendations_api(scene="home")
  │   → 后端发现无购买历史 → 返回热销榜 + basedOn=null
  │
  ├─ format_recommendations: "🎯 热销推荐（共 10 件）..."
  │
  └─ LLM 追加（Prompt 引导）:
      以上是近期热销商品。登录后我可以根据您的购买历史做更精准的推荐，
      您也可以告诉我您喜欢什么类型的商品，我来帮您找找。
```

**Agent 冷启动的优势**：传统推荐系统对新用户只能返回冷冰冰的热销榜。Agent 能**主动追问偏好**，引导用户表达兴趣，快速建立初始画像。

---

## 6. 后端 API 需求（最小改动）

Agent 工具通过 Gateway 调用，后端需新增 2 个接口。**推荐策略在后端实现，Agent 不关心算法细节**。

### 6.1 接口 1：`GET /recommend`（推荐召回）

```
GET /recommend?scene=home&size=10&itemId=1001
Authorization: <user_jwt>
```

**请求参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `scene` | String | 是 | `home` / `detail` / `cart` |
| `size` | Integer | 否 | 返回数量，默认 10 |
| `itemId` | Long | 否 | `scene=detail` 时必填，种子商品 ID |

**响应格式**：

```json
{
  "list": [
    {
      "id": 1001,
      "name": "iPhone 15 Pro",
      "price": 799900,
      "stock": 45,
      "brand": "Apple",
      "category": "手机",
      "sold": 1200,
      "recommendTags": ["同类目热销", "您常买的品牌"]
    }
  ],
  "total": 10,
  "basedOn": {
    "topCategories": ["手机", "耳机"],
    "topBrands": ["Apple", "Sony"]
  }
}
```

**后端实现策略（演进式）**：

| Phase | 数据源 | 算法 | 基础设施 |
|-------|--------|------|---------|
| Phase 1（先跑通） | `order_detail` + `item` 表 | SQL 聚合用户已购类目/品牌 → ES 按类目品牌召回 → 按销量排序 | 仅 SQL + ES（已有） |
| Phase 2（行为丰富后） | + `user_behavior` 表 | 加入浏览行为权重 + Item-CF 共现矩阵 | + Redis 画像 + 共现矩阵 |
| Phase 3（可选） | + 向量召回 | ES `dense_vector` 语义相似 | + Embedding 模型 |

**Phase 1 核心逻辑**（纯 SQL + ES，无需新基础设施）：

```sql
-- 1. 获取用户已购类目偏好（Top 3）
SELECT i.category, SUM(od.num) AS score
FROM order_detail od
JOIN `order` o ON od.order_id = o.id
JOIN item i ON od.item_id = i.id
WHERE o.user_id = #{userId} AND o.status IN (2,3,4,6)
GROUP BY i.category
ORDER BY score DESC
LIMIT 3;

-- 2. ES 按偏好类目召回（复用 SearchServiceImpl 的 BoolQuery 模式）
--    filter: category IN (偏好类目) AND status = 1
--    sort: sold DESC
--    排除用户已购商品
```

### 6.2 接口 2：`POST /behaviors`（行为采集）

```
POST /behaviors
Authorization: <user_jwt>
Content-Type: application/json

{
  "itemId": 1001,
  "type": "view"
}
```

**请求参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `itemId` | Long | 是 | 商品 ID |
| `type` | String | 是 | `view` / `cart` / `purchase` / `favorite` |

**行为权重**（后端画像计算时使用）：

| 行为类型 | 权重 | 说明 |
|---------|------|------|
| `view` | 1 | 浏览（弱信号） |
| `favorite` | 4 | 收藏（中强信号） |
| `cart` | 3 | 加购（中信号） |
| `purchase` | 5 | 购买（强信号） |

**实现要点**：

- 接口只写 MQ，Consumer 异步落库 + 更新画像，保证主流程 <10ms
- **购买行为不需要前端埋点**：复用 `trade-service` 的 `paySuccessListener`（已有），支付成功时发一条 `purchase` 行为消息
- 浏览埋点在前端 `ProductDetail.vue` 的 `onMounted` 中上报

### 6.3 新增数据表

```sql
-- 用户行为表
CREATE TABLE user_behavior (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL COMMENT '用户ID',
    item_id     BIGINT NOT NULL COMMENT '商品ID',
    behavior_type VARCHAR(20) NOT NULL COMMENT 'view/cart/purchase/favorite',
    score       INT DEFAULT 1 COMMENT '行为权重分',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_time (user_id, create_time),
    INDEX idx_item_type (item_id, behavior_type)
) COMMENT '用户行为记录表';
```

### 6.4 Redis 画像结构（Phase 2 使用）

| Key | 结构 | 说明 |
|-----|------|------|
| `up:{userId}:cat` | ZSet（member=category, score=累计分） | 用户类目偏好 |
| `up:{userId}:brand` | ZSet（member=brand, score=累计分） | 用户品牌偏好 |
| `bh:{userId}:recent` | ZSet（member=itemId, score=时间戳权重） | 最近浏览（时间衰减） |
| `cf:{itemId}` | Hash（field=itemId, value=共现次数） | Item-CF 共现矩阵 |
| `rec:{userId}:{scene}` | String（JSON 商品列表） | 推荐结果缓存，TTL 5-10min |

---

## 7. 前端集成

### 7.1 浏览埋点

在 `ProductDetail.vue` 的 `onMounted` 中上报浏览行为：

```typescript
// hmall-frontend/src/views/portal/ProductDetail.vue（新增）

onMounted(async () => {
  // ... 现有逻辑 ...

  // 上报浏览行为（异步，不阻塞页面）
  if (route.params.id && sessionStorage.getItem('token')) {
    try {
      await api.post('/behaviors', {
        itemId: Number(route.params.id),
        type: 'view',
      }, {
        headers: { authorization: sessionStorage.getItem('token') }
      })
    } catch {
      // 埋点失败静默忽略，不影响页面正常使用
    }
  }
})
```

### 7.2 对话快捷操作

在 `ChatPanel.vue` 的 `shortcuts` 中新增推荐快捷入口：

```typescript
// C 端快捷操作（新增推荐相关）
const shortcuts = [
  '有什么推荐',
  '猜你喜欢',
  '查看秒杀活动',
  '我的购物车',
  '我的订单',
]
```

### 7.3 推荐商品卡片（可选增强）

`MessageBubble.vue` 的 Markdown 渲染已支持列表格式（`hmall-agent-implementation-report.md` 第 4.4 节）。推荐结果以标准列表格式输出，前端无需特殊处理。

如后续需要商品卡片样式，可在 `MessageBubble.vue` 中解析 `[ID:xxx]` 标记并渲染为可点击链接，点击跳转商品详情页。

---

## 8. Agent 推荐闭环设计

推荐不是一次性的，Agent 能形成"推荐→反馈→再推荐"的对话闭环：

```
推荐商品
  │
  ├─ 用户: "第一个不错，详情看看"
  │   → get_item_detail_api(item_id=2001)
  │   → 展示详情 → 顺势再推荐搭配（模式 B）
  │
  ├─ 用户: "有没有更便宜的同款"
  │   → LLM 理解意图 → search_items_api(同品牌同类型 + 价格更低)
  │   → 结合偏好生成新推荐
  │
  ├─ 用户: "加购物车吧"
  │   → add_to_cart_api(item_id=2001)
  │   → 顺势推荐凑单（scene=cart）
  │
  └─ 用户: "不喜欢这个品牌"
      → LLM 记录用户反馈 → 排除该品牌重新推荐
      → 或调 search_items_api 搜索其他品牌
```

**闭环设计要点**：

- Prompt 引导 Agent "推荐后主动询问用户是否查看详情或加入购物车"
- Agent 能理解用户的偏好反馈（"不喜欢""太贵了""有别的品牌吗"）并调整推荐策略
- LangGraph Thread 保存对话历史，Agent 能引用之前推荐的商品

---

## 9. 技术决策

### 9.1 决策：推荐理由由 LLM 生成而非后端模板拼接

**决策**：后端只返回 `recommendTags`（商品级标签）和 `basedOn`（推荐依据摘要），推荐理由由 LLM 结合用户偏好生成。

**理由**：
- 后端模板拼接的理由生硬（"推荐理由：同类目热销"），缺乏个性化
- LLM 能结合偏好生成自然语言（"您常买 Apple 品牌产品，这款是同品类热销款"）
- LLM 能结合对话上下文调整话术（用户刚看了 iPhone → "与您刚看的 iPhone 相似"）
- 后端标签作为结构化数据给 LLM 参考，LLM 负责组织成自然语言

### 9.2 决策：`analyze_user_preferences` 不依赖后端画像服务

**决策**：偏好分析工具直接调用现有 `/orders/page` + `/carts` 接口，在 Agent 侧聚合。

**理由**：
- 零后端改动即可启用偏好分析能力
- 现有 `get_order_list_api` 和 `get_cart_list_api` 已验证可用
- Phase 1 无需后端画像服务，Phase 2 画像丰富后可切换为调用画像接口
- Agent 侧聚合逻辑简单（类目/品牌分数累加），无需复杂算法

### 9.3 决策：推荐工具需登录，商品浏览工具不需要

**决策**：`get_recommendations_api` 和 `analyze_user_preferences` 都需要登录（`token` 检查），而 `search_items_api`/`get_item_page_api` 不需要。

**理由**：
- 推荐基于用户个人数据（购买/浏览历史），必须认证
- 未登录用户访问推荐时，提示"登录后可获个性化推荐"，降级为搜索
- 与现有 `get_cart_list_api`/`get_order_list_api` 的认证模式一致

### 9.4 决策："猜你喜欢"走 L1 正则，"看了又看"走 L3 LLM

**决策**：首页"猜你喜欢"通过 L1 正则快捷路由（<5ms），"看了又看"由 L3 LLM 处理。

**理由**：
- "猜你喜欢"是高频场景，参数固定（scene=home），适合正则拦截
- "看了又看"需要从对话上下文提取 `item_id`，正则无法做到
- LLM 能从最近对话历史中推断当前商品 ID，虽然慢 ~2s 但更准确

### 9.5 决策：Phase 1 不引入 Item-CF 和向量召回

**决策**：Phase 1 仅用 SQL 聚合 + ES 按类目品牌召回 + 销量排序。

**理由**：
- P2 优先级，投入产出需平衡
- 商品/用户规模小时，Item-CF 共现矩阵稀疏，效果不佳
- Content-Based（类目/品牌匹配）+ 热销兜底已能覆盖基本场景
- Phase 2 行为数据积累后，再引入 Item-CF 和向量召回

### 9.6 决策：购买行为从 paySuccessListener 旁路采集

**决策**：不在 Agent 层采集购买行为，而是在 `trade-service` 的 `paySuccessListener` 中旁路发送行为消息。

**理由**：
- 支付成功是确定的购买信号，Agent 层无法感知
- `paySuccessListener` 已有（`trade-service` 现有代码），只需新增一行发消息逻辑
- 不侵入交易主链路，消息发送失败不影响支付流程
- 浏览行为由前端埋点采集，加购行为可从购物车变更推断

---

## 10. 实现优先级

### 10.1 分步实施

| 步骤 | 改动点 | 工作量 | 价值 | 阶段 |
|------|--------|--------|------|------|
| 1 | Agent 新增 `get_recommendations_api` 工具 | 小 | 核心 | Phase 1 |
| 2 | Agent 新增 `analyze_user_preferences` 工具 | 中 | 差异化 | Phase 1 |
| 3 | Agent 新增 `format_recommendations` + `format_preferences` | 小 | 输出规范 | Phase 1 |
| 4 | Agent 新增 `personalized-recommendation` Skill | 小 | 工作流 | Phase 1 |
| 5 | Agent 修改 Prompt + 正则路由 + 工具注册 | 小 | 触发 | Phase 1 |
| 6 | 后端 `GET /recommend` 接口（Phase 1: SQL+ES） | 中 | 数据供给 | Phase 1 |
| 7 | 后端 `POST /behaviors` 接口 + MQ Consumer | 中 | 数据采集 | Phase 2 |
| 8 | 前端 `ProductDetail.vue` 浏览埋点 | 小 | 数据积累 | Phase 2 |
| 9 | 后端 Redis 画像计算（Consumer） | 中 | 画像精度 | Phase 2 |
| 10 | 后端 Item-CF 共现矩阵（定时任务） | 中 | 召回质量 | Phase 2 |

### 10.2 Phase 1 交付物（Agent 侧优先）

Phase 1 只需完成步骤 1-6，即可让 Agent 具备完整推荐对话能力：

- **Agent 侧**（步骤 1-5）：2 个新工具 + 1 个 Skill + Prompt/正则增强
- **后端侧**（步骤 6）：1 个 `/recommend` 接口，纯 SQL + ES 实现

Phase 1 不需要行为采集（步骤 7-8）和画像服务（步骤 9-10），因为 `analyze_user_preferences` 直接用现有订单/购物车接口聚合，`get_recommendations_api` 后端 Phase 1 也用 `order_detail` 表聚合。

### 10.3 测试验证

| 测试场景 | 验证点 |
|---------|--------|
| L1 正则命中"推荐" | <5ms 返回推荐列表，不走 LLM |
| 未登录访问推荐 | 返回"需要先登录"提示 |
| 新用户推荐（无订单） | 后端返回热销榜，Agent 不强行解释理由 |
| 模式 A：用户说"猜你喜欢" | L1 正则 → 推荐列表 + 推荐依据 |
| 模式 B：查看商品后主动推荐 | LLM 在详情后追加"看了又看" |
| 模式 C：偏好驱动推荐 | LLM 先调 analyze_user_preferences 再搜索 |
| 推荐接口失败 | Agent 降级为搜索提示 |
| 推荐后加购 | 形成推荐→加购→凑单推荐闭环 |
| Formatter 空数据 | 返回友好提示，不报错 |
| analyze_user_preferences 并发 | 订单+购物车并发获取无阻塞 |

---

## 11. 风险与规避

| 风险 | 影响 | 规避 |
|------|------|------|
| 数据稀疏（用户/商品少） | Content-Based 召回不足 | 热销榜兜底 + Agent 主动询问偏好 |
| 推荐接口延迟 | 对话体验差 | 后端 Redis 缓存（5-10min TTL）+ Agent 降级搜索 |
| LLM 生成推荐理由不准 | 用户信任度下降 | 后端 `recommendTags` 作为结构化约束，LLM 基于标签生成 |
| 埋点性能影响页面 | 用户体验差 | 埋点接口只写 MQ，前端 `catch` 静默忽略错误 |
| 画像更新延迟 | 推荐不够实时 | 购买行为实时更新画像，浏览行为近线更新（秒级延迟可接受） |

---

## 12. 与现有文档的关联

| 文档 | 关系 |
|------|------|
| `docs/Agent功能相关文档/hmall-agent-design.md` | **父文档**：Agent 整体设计，第 15 节列出"商品推荐 P2"为本设计来源 |
| `docs/Agent功能相关文档/hmall-agent-implementation-report.md` | **关联**：实现说明文档，本文档的实现将更新该报告 |
| `hmall-agent/src/agents/customer/tools.py` | **修改**：新增 2 个工具 + 修改 `get_all_tools()` |
| `hmall-agent/src/agents/customer/prompts.py` | **修改**：SYSTEM_PROMPT 新增推荐能力声明 |
| `hmall-agent/src/agents/customer/regex_rules.py` | **修改**：新增 2 条推荐正则规则 |
| `hmall-agent/src/agents/customer/agent.py` | **修改**：Skills sources 新增 `personalized-recommendation` |
| `hmall-agent/src/tools/formatters.py` | **修改**：新增 2 个格式化函数 |
| `hmall-agent/src/workspace/customer/skills/personalized-recommendation/SKILL.md` | **新增**：推荐工作流规范 |
| `hmall/item-service/src/main/java/com/hmall/item/controller/ItemController.java` | **修改**：新增 `/recommend` + `/behaviors` 接口 |
| `hmall/search-service/src/main/java/com/hmall/search/service/impl/SearchServiceImpl.java` | **参考**：ES 召回逻辑复用其 BoolQuery 模式 |
| `hmall/trade-service/src/main/java/com/hmall/trade/Listener/paySuccessListener.java` | **修改**：旁路发送 purchase 行为消息 |

---

## 13. 后续优化方向

| 方向 | 说明 | 优先级 |
|------|------|--------|
| 向量召回 | ES `dense_vector` 语义相似商品推荐，提升"看了又看"质量 | P3（等 P1 语义搜索一起上） |
| 实时推荐反馈 | 用户对推荐的点击/加购行为反馈到画像，调整推荐策略 | P3 |
| 多模态推荐 | 支持图片输入推荐（"推荐类似这张图的商品"） | P3（等多模态支持后） |
| A/B 测试 | 推荐算法效果对比，不同召回策略转化率分析 | P3 |
| AdminAgent 推荐分析 | 管理助手查看推荐效果数据（点击率/转化率/收入贡献） | P3 |
| 正则规则动态加载 | 推荐相关正则从 Nacos 动态加载，无需重启 | P3（与整体正则动态加载一起做） |

---

> **设计完成度**：本文档覆盖了 Agent 个性化推荐能力的完整设计，包括 2 个新工具、1 个 Skill、Prompt/正则/Formatter 增强、3 种推荐触发模式、冷启动处理、后端最小接口需求、前端埋点、推荐闭环设计、6 项技术决策和分步实施计划。Phase 1（步骤 1-6）可在不引入新基础设施的前提下，让 Agent 具备完整的对话式推荐能力。
