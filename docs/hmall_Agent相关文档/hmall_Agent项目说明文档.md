# hmall Agent 项目说明文档

> 本文档面向项目理解与快速上手，描述各功能的**设计动机**、**实现思路**和**最终效果**，不涉及具体代码引用与实现细节。

---

## 目录

1. [项目定位](#1-项目定位)
2. [总体架构](#2-总体架构)
3. [CustomerAgent（C 端客服助手）](#3-customeragentc-端客服助手)
4. [AdminAgent（管理助手）](#4-adminagent管理助手)
5. [中间件体系（三级路由）](#5-中间件体系三级路由)
6. [工具系统](#6-工具系统)
7. [Skills 技能系统](#7-skills-技能系统)
8. [用户画像持久化](#8-用户画像持久化)
9. [个性化推荐](#9-个性化推荐)
10. [语义记忆（Layer 3）](#10-语义记忆layer-3)
11. [RAG 知识库集成](#11-rag-知识库集成)
12. [Human-in-the-loop 二次确认](#12-human-in-the-loop-二次确认)
13. [前端集成](#13-前端集成)
14. [后端服务集成](#14-后端服务集成)
15. [配置体系](#15-配置体系)
16. [启动流程](#16-启动流程)

---

## 1. 项目定位

hmall Agent 是**枫叶商城**（hmall）的 AI 智能助手，为电商平台提供自然语言交互的购物和管理体验。它能够：

- 作为 C 端客服，帮用户搜索商品、参与秒杀、管理购物车、跟踪订单、添加收货地址——全程自然语言对话
- 作为管理后台助手，帮运营人员查询订单、管理商品库存、查看秒杀活动数据、生成运营日报
- 记住用户的购物偏好，对话越久越懂用户喜欢什么
- 通过 RAG 知识库回答商城政策、运营策略等专业问题

hmall Agent 基于 DeepAgents + LangGraph 构建，后端使用 Python，通过 HTTP 调用 Java 微服务集群完成数据操作。

---

## 2. 总体架构

### 部署拓扑

```
用户（H5/小程序/管理后台）
  │  LangGraph SDK (HTTP + SSE)
  ▼
Agent Service (LangGraph Server :8090)
  ├── CustomerAgent — 22 个工具，7 个 Skills
  ├── AdminAgent — 11 个工具，3 个 Skills
  ├── 中间件层 — 5 层中间件
  └── 用户画像 — Redis db=0，与后端共享
  │
  │ HTTP (httpx)
  ▼
hm-gateway (:8080) → item/cart/trade/user/seckill/search 微服务
```

### 核心特点

| 特点 | 说明 |
|------|------|
| **三级路由** | L1 正则中间件（<5ms）→ L2 interrupt 状态机 → L3 LLM 兜底 |
| **双 JWT 认证** | C 端用户 JWT 和管理后台 JWT 独立验证，安全隔离 |
| **Agent 零数据库** | 不直连 MySQL，所有数据操作通过 Gateway → 微服务 API 完成 |
| **画像加速** | Redis 增量聚合画像，命中时偏好分析和推荐召回 0 次后端调用 |

### 关键设计决策

**为什么不直连数据库？**
hmall 是标准的微服务架构，item/cart/trade/user 各有独立数据库。如果 Agent 直连各服务数据库，意味着需要理解每个微服务的表结构和分布式事务——耦合过高。通过 Gateway API 调用，Agent 只需理解业务接口（如 `GET /carts`、`POST /items/search`），微服务的数据库变更对 Agent 完全透明。

---

## 3. CustomerAgent（C 端客服助手）

### 设计动机

电商用户在日常购物中常有大量"用自然语言完成操作"的需求：
- "帮我搜一下 2000 以内的蓝牙耳机"
- "把我购物车里的 Nike 跑鞋数量改成 2"
- "最近有什么秒杀活动"
- "帮我推荐适合我的商品"

传统电商 App 需要用户在搜索框、分类页、购物车页之间来回跳转。Agent 可以将这些操作融合为自然语言对话，用户一句话完成原本需要 3-5 次页面跳转的操作。

### 实现思路

CustomerAgent 基于 DeepAgents `create_agent` 声明式定义，通过 5 层中间件链串联处理流程。22 个工具按功能分为 7 组（商品浏览、秒杀、购物车、订单、地址、个性化推荐、用户记忆），每个工具封装一个后端 API 调用。

**工具设计原则**：
- **一工具一 API**：每个工具精确封装一个后端接口，参数和返回格式与 API 对齐
- **格式化响应**：工具返回的不是裸 JSON，而是通过 `formatters.py` 转为用户可读的中文 Markdown 文本
- **预处理降级**：秒杀场景下的版本号黑名单校验、商品库存/状态过滤在工具层完成，减少 LLM 推理负担

### 最终效果

- 用户说"帮我搜索 2000 以内的蓝牙耳机"→ Agent 调用 `search_items_api` → 返回商品卡片列表
- 用户说"把第一个加入购物车"→ Agent 调用 `add_to_cart_api` → 返回确认消息
- 用户说"查看我最近的订单"→ Agent 调用 `get_order_list_api` → 返回订单列表
- 用户说"帮我推荐适合我的"→ Agent 先分析画像（`analyze_user_preferences`）→ 再查推荐（`get_recommendations_api`）→ 附上推荐理由

---

## 4. AdminAgent（管理助手）

### 设计动机

运营人员在管理后台需要频繁查看订单状态、商品库存、秒杀活动效果，每次都要在不同页面之间切换、手动筛选条件。如果能用自然语言查询，效率会大大提升。

### 实现思路

AdminAgent 与 CustomerAgent 使用**同一套中间件链**，区别在于：
- **全只读模式**：`PermissionMiddleware` 拦截所有写操作工具（下单、取消订单等），AdminAgent 只能查询不能操作
- **运营日报**：`generate_daily_report` 是一个组合工具——单次调用自动并发查询秒杀活动、订单统计、商品库存，聚合为 Markdown 日报
- **独立 JWT**：使用 `admin.jks` 验证，与 C 端用户身份体系隔离

### 最终效果

- "查看秒杀活动 RPM00171 的参与情况"→ Agent 返回秒杀订单数据和参与用户统计
- "生成今天的运营日报"→ Agent 并发查询多维度数据，返回结构化日报
- "查看商品 1002 的库存状态"→ Agent 返回商品详情和库存预警

---

## 5. 中间件体系（三级路由）

### 设计动机

电商 Agent 面临一个核心问题：用户输入千变万化——有些是精确的操作指令（"查看我的订单"），有些是模糊的意图（"想买个东西送人"），有些是闲聊（"今天天气怎么样"）。如果所有输入都交给 LLM 处理，简单操作会有 1-3 秒的延迟，用户体验差；如果只用正则匹配，灵活度不够。

### 实现思路

设计**三级路由**架构，在"速度快"和"灵活度高"之间取最优解：

```
用户消息
  │
  ▼
L1 RegexShortcutMiddleware（正则匹配，<5ms）
  ├── 命中 → 直接路由到工具（跳过 LLM）
  └── 未命中 ▼
  L2 interrupt 状态机（Human-in-the-loop）
      ├── 命中 → 二次确认 → 执行
      └── 未命中 ▼
      L3 LLM 推理（1-3s）
          └── 完整 ReAct 循环
```

**L1 正则快捷路由**：维护一个"意图 → 工具"的映射表（如"我的订单" → `get_order_list_api`）。正则匹配到意图后，直接从消息中提取参数（用 `interrupt` 补充缺失参数），跳过 LLM 直接调用工具。适合"查看订单"、"加入购物车"等高频场景。

**L2 interrupt 状态机**：危险操作（取消订单、确认收货、秒杀下单）不直接执行，而是通过 LangGraph 的 `interrupt()` 机制暂停执行，在前端弹出确认弹窗。用户确认后恢复执行。

**L3 LLM 推理**：当 L1/L2 都无法匹配时（如"帮我挑个合适的礼物送女朋友"），交给 LLM 完成完整的理解→规划→执行循环。

### 最终效果

- 用户说"查看我的订单"→ L1 正则命中，<5ms 响应，秒级返回结果
- 用户点"取消订单 1005"→ L2 interrupt → 弹窗"确定取消订单 1005？"→ 确认后执行
- 用户说"帮我推荐一个适合送女朋友的礼物，预算 500 以内"→ L3 LLM 分析偏好 → 搜索商品 → 格式化推荐

---

## 6. 工具系统

### CustomerAgent 工具（22 个）

| 类别 | 工具 | 说明 |
|------|------|------|
| 商品浏览 | `search_items_api` | 多条件搜索（关键词/类目/品牌/价格区间/排序/分页） |
| | `get_item_detail_api` | 商品详情（含库存/状态） |
| | `get_item_page_api` | 商品分页浏览 |
| 秒杀 | `get_seckill_activities_api` | 秒杀活动列表 |
| | `get_seckill_product_api` | 秒杀商品详情 |
| | `do_seckill_api` | 参与秒杀（需 L2 interrupt 确认） |
| 购物车 | `get_cart_list_api` | 查看购物车 |
| | `add_to_cart_api` | 加入购物车 |
| | `update_cart_quantity_api` | 修改商品数量 |
| | `delete_cart_item_api` | 删除商品（需 L2 interrupt 确认） |
| | `clear_cart_api` | 清空购物车（需 L2 interrupt 确认） |
| 订单 | `get_order_list_api` | 订单列表查询 |
| | `get_order_detail_api` | 订单详情 |
| | `cancel_order_api` | 取消订单（需 L2 interrupt 确认） |
| | `confirm_receive_api` | 确认收货（需 L2 interrupt 确认） |
| 地址 | `get_address_list_api` | 收货地址列表 |
| | `add_address_api` | 新增地址（多轮交互收集信息） |
| | `update_address_api` | 修改地址 |
| 个性化推荐 | `get_recommendations_api` | 获取个性化推荐商品 |
| | `analyze_user_preferences` | 分析用户购物偏好 |
| 用户记忆 | `save_memory` | 保存对话记忆 |
| | `get_memories` | 读取历史记忆 |

### AdminAgent 工具（11 个）

| 类别 | 工具 | 说明 |
|------|------|------|
| 商品管理 | `admin_get_product_page_api` | 商品分页管理 |
| | `admin_get_product_detail_api` | 商品详情管理 |
| 订单管理 | `admin_get_order_page_api` | 订单分页查询 |
| | `admin_get_order_detail_api` | 订单详情查询 |
| 秒杀管理 | `admin_get_seckill_promotion_page_api` | 秒杀活动分页 |
| | `admin_get_seckill_relation_page_api` | 秒杀商品关联 |
| | `admin_get_seckill_order_page_api` | 秒杀订单查询 |
| | `admin_get_seckill_stock_api` | 秒杀库存查询 |
| 用户管理 | `admin_get_user_page_api` | 用户分页查询 |
| | `admin_get_user_detail_api` | 用户详情查询 |
| 运营日报 | `generate_daily_report` | 生成运营日报（组合工具） |

### 工具设计原则

**格式化响应**：每个工具返回的不是裸 JSON，而是通过 `formatters.py` 中的专用格式化函数转为用户可读的中文 Markdown。例如购物车列表返回表格而非原始数组，商品推荐返回带 emoji 和理由的卡片而非 JSON。

**预处理降级**：秒杀工具的版本号校验、黑名单过滤在工具层完成——工具调用后先校验秒杀商品的 version 号是否在黑名单（`degraded_versions`）中，是则直接返回降级消息，不进入 LLM 推理环节。这减少了 LLM 的 Token 消耗和推理延迟。

**画像联动**：加购工具调用成功后，后端 `CartServiceImpl` 自动将 cart 行为写入用户画像；支付成功后 `paySuccessListener` 写入 purchase 画像。Agent 在推荐前先读画像，命中时无需查订单/购物车即可完成偏好分析。

---

## 7. Skills 技能系统

### 设计动机

通用 Agent 在面对具体业务场景时，需要明确的"操作指南"来规范化行为——比如秒杀下单前必须展示商品详情、推荐商品时必须附上理由、退货查询前必须先确认订单号。这些业务规范如果全部写入 Prompt，会迅速撑爆上下文窗口。

### 实现思路

Skill 是 Markdown 格式的业务操作规范文档（`SKILL.md`），存储在 `src/workspace/{agent_type}/skills/` 目录下。每个 Skill 描述一个具体业务场景的标准操作流程（SOP）。

Skills 通过 `FilesystemBackend`（虚拟文件系统）加载，在 Agent 初始化时以**动态提示词**的形式注入。Agent 在对话中根据意图自动选择相关 Skill 作为参考。

**CustomerAgent 的 7 个 Skills**：
| Skill | 内容 |
|-------|------|
| `shopping-guide` | 商品搜索与浏览的 SOP——关键词提取、多条件组合、结果排序 |
| `seckill-order` | 秒杀下单 SOP——活动校验、商品详情确认、下单确认 |
| `cart-management` | 购物车管理 SOP——增删改查、清空前确认 |
| `order-management` | 订单管理 SOP——状态查询、取消/收货确认 |
| `address-management` | 地址管理 SOP——多轮交互收集、格式验证 |
| `personalized-recommendation` | 推荐 SOP——偏好分析→推荐→附理由 |
| `rag-query` | 知识库查询 SOP——政策类问题的检索流程 |

**AdminAgent 的 3 个 Skills**：
| Skill | 内容 |
|-------|------|
| `daily-report` | 运营日报生成 SOP——多维度数据并发查询与聚合 |
| `data-query` | 数据查询 SOP——分页查询、条件筛选、排序 |
| `rag-query` | 知识库查询 SOP |

### 最终效果

- Agent 在秒杀场景下会严格遵循 `seckill-order` Skill：先展示商品详情 → 展示秒杀价格 → 让用户确认 → 下单
- Agent 在推荐场景下会遵循 `personalized-recommendation` Skill：先分析偏好 → 查推荐 → 每条附理由 → 提醒"数据基于您的历史行为"
- Skill 作为外部知识注入，不占 Prompt 主窗口空间

---

## 8. 用户画像持久化

### 设计动机

传统推荐系统在每次请求时都要查数据库做偏好聚合——查订单详情的 category/brand、查购物车的商品信息、按购买数量加权。每次请求 3-5 次 Feign 跨服务调用，延迟约 200ms。

更严重的是，Agent 和新独立的推荐服务（`RecommendServiceImpl`）需要**各自做一遍**同样的聚合计算——两端的重复计算完全冗余。

### 实现思路

设计**画像缓存层**：行为发生时增量更新 Redis 画像，读取时直接取聚合结果。

**写入端（行为发生时）**：

| 行为 | 写入方 | 权重 |
|------|--------|:---:|
| 加购 | `CartServiceImpl.addItem2Cart`（后端） | 3 |
| 支付成功 | `paySuccessListener`（后端 MQ 消费者） | 5 |

后端两处均使用 `StringRedisTemplate` + `executePipelined` 批量执行，HINCRBY 原子增量更新，1 次网络往返完成所有写入。

**读取端（分析/推荐时）**：

```
analyze_user_preferences / RecommendServiceImpl.recommend()
  ↓
1. 优先读 Redis 画像 profile:{uid}:categories/brands/prices
   ✅ 命中 → 直接使用（0 次后端调用，<5ms）
   ❌ miss → 降级 Phase 1 实时计算 → 回写画像
```

**Redis 存储结构**（db=0，与后端 `spring.redis.database=0` 共享）：

| Key | 类型 | 内容 | TTL |
|-----|------|------|-----|
| `profile:{uid}:events` | List | 行为流（最近 50 条） | 7 天 |
| `profile:{uid}:categories` | Hash | 类目 → 累计得分 | 30 天 |
| `profile:{uid}:brands` | Hash | 品牌 → 累计得分 | 30 天 |
| `profile:{uid}:prices` | List | 最近购买价格（20 条） | 30 天 |
| `profile:{uid}:stats` | Hash | purchase_count / cart_count / last_update | 30 天 |

### 最终效果

- **Agent 偏好分析**：画像命中时 0 次 Gateway 调用（原需 3-5 次），延迟 <5ms
- **后端推荐召回**：画像命中时跳过 Feign 聚合（原需查 trade-service + item-service），直接 ES 检索
- **覆盖全路径**：后端写入覆盖 Agent 对话 + 前端 UI 两种入口（用户无论通过哪种方式加购/下单，画像都会更新）
- **高并发安全**：HINCRBY 原子操作，无需分布式锁

---

## 9. 个性化推荐

### 设计动机

电商的"猜你喜欢"通常依赖专业推荐引擎（如协同过滤、深度学习排序模型）。但对于一个教学级电商项目，核心目标不是 CTR 最优，而是让 Agent 能"像懂用户的导购一样推荐商品"。

### 实现思路

三步管线，平衡效果与复杂度：

```
用户："帮我推荐"
  ↓
Step 1: 偏好分析
  ├── 画像命中 → 秒级返回 Top3 类目 + Top3 品牌 + 价格区间
  └── 画像 miss → 查订单/购物车 → 按购买数量加权聚合

Step 2: 召回排序
  └── ES 多字段检索（类目 + 品牌 + 价格区间 + 已购排除）
      + MySQL 补充库存/状态

Step 3: 理由生成
  └── LLM 结合偏好和商品信息生成推荐理由（非后端模板拼接）
```

**推荐理由的可解释性**：每件推荐商品都有理由——"你最近买过 Nike 跑鞋，这款是 Nike 最新款"或"根据你的价格偏好（200-500 元），这款性价比很高"。理由由 LLM 根据用户偏好和商品信息实时生成，而不是后端写死的模板。

### 最终效果

- 用户说"帮我推荐"→ Agent 展示 5 件推荐商品，每件附带个性化的推荐理由
- 偏好数据透明：用户问"为什么推荐这些"→ Agent 展示偏好分析（"基于你 3 笔订单 + 5 件购物车商品"）
- 点击商品卡片可查看详情，无缝衔接后续的"加入购物车"、"查看详情"等操作

---

## 10. 语义记忆（Layer 3）

### 设计动机

用户画像只能记住"偏好"（类目、品牌、价格），但记不住"意图"——用户说"想买手机但再看看"、"先收藏改天再说"。这些未完成的购物意图如果在下一次对话中被遗忘，Agent 就像一个每次都从零开始的"失忆导购"。

### 实现思路

在 Redis 结构化画像之上增加第三层——LangGraph Store 语义记忆。Agent 提供两个专用工具：

- **`save_memory`**：当用户表达明确但未完成的意图时调用，存储为键值对（如 `{"key": "shopping_intent", "value": "想买 3000 以内的手机，偏好华为"}`）
- **`get_memories`**：每次对话开始时自动调用，读取历史记忆

记忆通过 LangGraph Store API（`aput` / `asearch`）持久化，支持语义检索。当前使用内存存储（`graph.json` 配置 `"store": {"type": "in_memory"}`），可无缝扩展为持久化后端。

### 最终效果

- 用户第一次对话："想买手机，但再看看，改天再说"→ Agent 调用 `save_memory` 保存意图
- 用户第二次打开对话：Agent 自动读取记忆 → "欢迎回来！上次您在看手机，今天新到了一批华为新款"
- Agent 不会生硬复述记忆内容，而是自然地融入对话

---

## 11. RAG 知识库集成

### 设计动机

电商有大量"政策类问题"——退换货规则、配送时效、支付方式、售后服务——这些答案不在商品数据库里，而是在运营文档中。如果让 LLM 凭空编造，会出现"幻觉"（如虚构退货期限）。

### 实现思路

hmall Agent 通过 MCP（Model Context Protocol）协议集成 LightRAG 知识检索引擎：

```
Agent
  │  MCP Client (langchain-mcp-adapters)
  ▼
RAG MCP Server (FastMCP, :8008)
  │  接受 search_documents / list_documents 调用
  ▼
LightRAG Server (:9621)
  └── 知识图谱 + 向量检索
```

**工作原理**：
1. 运营文档（退换货政策、配送说明、运营指南）提前索引入 LightRAG 知识库
2. 用户提问时，Agent 通过 RAG 中间件动态注入 `search_documents` 工具
3. LightRAG 使用知识图谱 + 向量双重检索，返回最相关的文档片段
4. LLM 基于检索结果回答，零幻觉

**RAG 中间件**：`Enable_RAG=true` 时（前端开关控制），动态连接 MCP Server 并注入检索工具。这样 RAG 功能按需启用，不使用时零开销。

### 最终效果

- AdminAgent："退货流程怎么处理"→ RAG 检索退换货政策文档 → 返回准确流程
- CustomerAgent："能货到付款吗"→ RAG 检索支付方式说明 → 返回准确答案
- 前端对话面板头部有"知识库"开关按钮，一键启用/禁用

---

## 12. Human-in-the-loop 二次确认

### 设计动机

Agent 不能像"自动驾驶"一样替用户做决策——尤其是取消订单、确认收货、清空购物车这类不可逆操作。必须让用户亲眼确认后再执行。

### 实现思路

利用 LangGraph 的 `interrupt()` 机制实现人机协作。危险操作的工具不直接执行，而是：

1. 工具调用时，Agent 先通过 `interrupt()` 暂停执行
2. 前端弹出确认弹窗（"确定取消订单 1005？"）
3. 用户点击"确认"→ 后端恢复 Agent 执行 → 调用 API 完成操作
4. 用户点击"取消"→ 返回"已取消操作"

**哪些操作需要二次确认**：
- 取消订单、删除购物车商品、清空购物车
- 确认收货
- 秒杀下单
- 新增收货地址（需两轮交互收集信息）

### 最终效果

- 用户说"取消订单 1005"→ 弹窗"确定取消订单 1005？"→ 确认后执行
- 用户说"确认收货"→ 弹窗"确定已收到订单「1005」的商品？回复'确认收货'执行"→ 确认后执行
- 不可逆操作都有明确的确认步骤，避免 LLM "脑子一热"误判

---

## 13. 前端集成

### 设计动机

Agent 不仅需要一个聊天界面，还需要认证注入、工具调用可视化、Skill 开关切换。

### 实现思路

hmall 前端通过 `@langchain/langgraph-sdk` 连接 Agent 服务，使用 SSE（Server-Sent Events）流式接收 Agent 回复。

**核心流程**：
1. 用户发送消息 → `POST /threads/{id}/runs/stream` 建立 SSE 连接
2. Agent 流式返回：文本片段（chunk）、工具调用开始/结束、interrupt 确认弹窗
3. 前端实时渲染：Markdown 文本 + 工具结果卡片交替展示
4. 前端通过 `context` 参数注入 JWT Token，Agent 的 `AuthMiddleware` 自动验证

**关键集成点**：
- 登录后获取 JWT → 所有 Agent 请求自动附带 Token（由 SDK 的 `context` 参数传递）
- Agent 返回的 Markdown 中包含商品卡片链接 → 前端解析渲染为可点击的跳转链接
- `interrupt` 确认弹窗与前端 UI 深度集成 → 用户确认/取消的操作直接驱动 Agent 继续/中断

### 最终效果

- 用户在前端聊天框输入"帮我搜索蓝牙耳机"→ Agent 流式返回搜索结果
- 工具调用实时可见（如"正在搜索商品..."→ 搜索结果卡片）
- 商品详情链接可点击，跳转到商详页
- 二次确认弹窗原生展示，无需额外开发

---

## 14. 后端服务集成

### 设计动机

hmall Agent 自身不存储业务数据，所有数据操作必须通过 Java 微服务集群完成。如何高效、安全地调用微服务接口是核心挑战。

### 实现思路

Agent 通过 `gateway/http_client.py` 中的 `GatewayClient` 统一调用后端 API：

```
Agent 工具
  │  httpx.AsyncClient (连接池复用)
  ▼
hm-gateway (:8080)
  ├── /items/search/:8081    item-service
  ├── /carts                  cart-service
  ├── /orders                 trade-service
  ├── /users                  user-service
  ├── /seckill                seckill-service
  └── /search                 search-service
```

**认证传递**：Agent 从 LangGraph `RunnableConfig` 中提取 JWT Token，通过 HTTP Header `Authorization: Bearer <token>` 透明传递给 Gateway。

**连接池管理**：使用 `httpx.AsyncClient` 单例，连接池大小 20，TCP Keep-Alive 复用，避免每次调用建连开销。

**画像联写**：Agent 的加购/下单工具调用后端 API 成功后，**后端**自动将行为写入 Redis 画像（而非 Agent 再写一次）。这确保了不管用户通过 Agent 还是前端 UI 操作，画像都能更新。

### 最终效果

- Agent 工具调用后端 API 的平均延迟 ~50ms（含 Gateway 转发 + 微服务处理）
- Token 透传对业务代码完全透明——Agent 工具只需关心业务参数
- 后端画像写入覆盖 Agent + 前端 UI 两种入口

---

## 15. 配置体系

### 配置层级

```
优先级：.env 环境变量 > 代码默认值

环境变量 (.env / .env.example)
├── LLM 配置：DASHSCOPE_API_KEY、LLM_MODEL_NAME、LLM_API_BASE
├── Redis 配置：REDIS_HOST、REDIS_PORT、REDIS_PASSWORD、PROFILE_REDIS_DB
├── 后端配置：JAVA_GATEWAY_URL
├── 服务配置：AGENT_PORT、LOG_LEVEL
├── JWT 配置：JWT_VERIFY_LOCAL、CUSTOMER_JKS_PATH、ADMIN_JKS_PATH
└── RAG 配置：RAG_BASE_URL、RAG_USERNAME、RAG_PASSWORD、RAG_MCP_PORT

graph.json
├── graphs — Agent 注册（customer_agent / admin_agent 的 .py 路径）
├── store  — LangGraph Store 配置（当前 in_memory）
└── env    — .env 文件路径
```

### 关键配置说明

| 配置项 | 作用 | 默认值 |
|--------|------|--------|
| `DASHSCOPE_API_KEY` | 通义千问 API 密钥（必填） | — |
| `LLM_MODEL_NAME` | 使用的 LLM 模型 | `qwen-turbo` |
| `PROFILE_REDIS_DB` | 画像数据存储的 Redis 数据库编号 | `0`（须与后端 spring.redis.database 一致） |
| `JWT_VERIFY_LOCAL` | JWT 验证方式 | `false`（依赖 Gateway 验证） |
| `JAVA_GATEWAY_URL` | hmall Gateway 地址 | `http://localhost:8080` |
| `AGENT_PORT` | Agent 服务监听端口 | `8090` |

---

## 16. 启动流程

### 环境要求

- Python >= 3.12
- uv（Python 包管理工具）
- Redis 运行中（用于用户画像存储）
- hmall Java 微服务集群运行中（item / cart / trade / user / seckill / search / gateway）

### 启动步骤

```bash
# 1. 安装依赖
cd hmall-agent
uv sync

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY 等

# 3. 启动 Agent 服务
uv run python start_server.py
```

### 服务地址

| 端点 | 地址 | 说明 |
|------|------|------|
| API | `http://localhost:8090` | LangGraph API 入口 |
| Docs | `http://localhost:8090/docs` | Swagger API 文档 |
| Studio | `http://localhost:8090/ui` | LangGraph Studio 调试界面 |
| Health | `http://localhost:8090/ok` | 健康检查 |

### 启动顺序

```
1. 基础设施：MySQL / Redis / Nacos / RabbitMQ
2. Java 微服务：item → user → cart → trade → pay → search → seckill → gateway
3. Agent 服务：uv run python start_server.py
4. 前端：npm run dev
```

### RAG 知识库（可选）

如需启用 RAG 知识库检索：

```bash
# 额外启动两个服务
cd LightRAG && lightrag-server        # LightRAG Server（:9621）
uv run python start_rag_server.py     # RAG MCP Server（:8008）
```

启动后在前端对话面板头部点击"知识库"开关按钮启用。

### API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/assistants/search` | POST | 获取可用 Agent 列表 |
| `/threads` | POST | 创建对话线程 |
| `/threads/{id}/runs/stream` | POST | 流式执行（SSE） |
| `/threads/{id}` | DELETE | 删除对话线程 |
| `/api/v1/batch-report` | POST | 批量运营报告 |
| `/api/v1/health` | GET | 健康检查 |

---

*本文档基于 hmall Agent v2.0 编写，各功能的最新状态以实际代码为准。*
