# hmall Agent 设计方案文档

> **文档定位（Why / What）**：本文件是 hmall Agent 的**权威设计契约**——记录原则、架构、接口约定、模式选型、风险与 Phase 演进。  
> **不写什么**：大段实现源码、完整前端 Composable、完整 `.env` 全文、逐步 bash 部署手册。上述内容分别见实现说明与项目说明。

---

## 0. 文档导航与版本说明

### 0.1 三份文档分工

| 文档 | 定位 | 读者 |
|------|------|------|
| [hmall_Agent项目说明文档.md](./hmall_Agent项目说明文档.md) | **入门叙事**：动机、心智模型、最小上手路径 | 首次接触、快速建立整体图景 |
| [hmall_Agent设计方案文档.md](./hmall_Agent设计方案文档.md)（本文） | **Why / What 与契约**：架构、规格、模式、风险、Phase | 方案评审、排期、对照实现 |
| [hmall_Agent实现说明文档.md](./hmall_Agent实现说明文档.md) | **How**：配置全文、文件级细节、部署验收、与设计的偏差 | 联调、排障、核对落地状态 |

**交叉引用约定**：

- 实现细节（工具函数体、Formatter、SKILL 全文、`.env` 样例、逐步启动命令）→ `hmall_Agent实现说明文档.md`
- 入门叙事与效果说明 → `hmall_Agent项目说明文档.md`
- 本文若与代码冲突，以**当前代码 + 实现说明**为准，并在演进状态表中标注偏差

### 0.2 本文结构

| 部分 | 内容 |
|------|------|
| **Part A** | 系统核心设计（架构、双 Agent、记忆、安全、中间件、API、前端契约、RAG、部署、演进） |
| **Part B** | 个性化推荐设计（触发模式、冷启动、后端 API、技术决策、Phase） |
| **Part C** | 用户画像（Layer 1–3，权威）+ 主动通知（规划中） |

> **说明**：原「第四部分：RAG」已合并入 Part A §12，不再保留第二套 RAG 全文副本。

### 0.3 版本说明

| 版本 | 日期 | 要点 |
|------|------|------|
| v2.0 | 2026-06 | DeepAgents + LangGraph；三级路由；双 JWT |
| v2.1 | 2026-07 | SDK 1.x；前端独立对话页；context-only（禁用 configurable 并存） |
| v2.2 | 2026-09 | 多租户 `metadata.owner`；Gateway introspect；Checkpoint 确认为 **inmem + `.langgraph_api` 落盘**（废止 Redis Checkpoint db=1 表述） |
| **v2.3** | **2026-09** | **文档职责收敛**：去重；RAG 合并为单一章；权威数量/状态对齐代码（推荐/画像/RAG 已实现；主动通知规划中） |

### 0.4 权威事实速查（务必对齐代码）

| 项 | 现行事实 |
|----|----------|
| CustomerAgent | 约 **20** 业务工具 + **记忆工具**；**Skills 7** |
| AdminAgent | **11** 工具（10 只读 + 运营日报编排）；**Skills 3** |
| Checkpoint | `langgraph-runtime-inmem`，冷路径落盘 **`.langgraph_api/`**；**不是** Redis db=1 |
| 画像 Redis | **db=0**，Key：`profile:{uid}:*` |
| introspect | C 端 `GET /users/me`；管理端 `GET /admin/info` |
| RAG | **已实现**（LightRAG `:9621` + MCP `:8008` + `RAGMiddleware`） |
| 个性化推荐 | **已实现**（Phase 1 + 画像共享） |
| 画像 Phase 2 | **已落地**；写入端为后端 `CartServiceImpl` + `paySuccessListener` |
| 主动通知 | **规划中** |

---

# Part A　系统核心设计

---

## 1. 概述

> 压缩定位叙事；完整「项目是什么 / 为什么做」见 [项目说明文档](./hmall_Agent项目说明文档.md)。本章只保留设计原则与 v1 对比，供架构评审使用。

### 1.1 背景与目标（摘要）

hmall（枫叶商城）已具备商品、购物车、订单、支付、用户、搜索、秒杀、管理后台等微服务链路，缺少面向自然语言的 AI 助手。本设计采用 **DeepAgents + LangGraph**，提供：

- **CustomerAgent（客服助手）**：C 端全链路对话（浏览 / 秒杀 / 购物车 / 订单 / 地址 / 推荐 / 可选 RAG / 跨会话记忆）
- **AdminAgent（管理助手）**：运营只读查询 + 运营日报；可选 RAG；**禁止写操作**

### 1.2 设计原则

| 原则 | 说明 |
|------|------|
| **三级路由** | L1 正则中间件（毫秒级，拦截高频指令）→ L2 `interrupt`（多轮 / 二次确认）→ L3 LLM 兜底 |
| **Agent 零业务库** | 数据操作一律 Gateway → 微服务 API；Agent 不直连 MySQL |
| **双 Token 隔离** | C 端 JWT 与管理端 JWT 独立；经 `context` + `Authorization` 传递 |
| **身份权威对齐** | Agent 侧 `userId` 以 Gateway introspect 为准，不以本地 JWT base64 解码为权威 |
| **多租户会话** | `metadata.owner = {agent_type}:{user_id}`，Auth 强制过滤 threads/store |
| **二次确认** | 危险写操作通过 `interrupt()` 实现 Human-in-the-loop |
| **空数据 / 降级** | 查询空结果在代码层固定提示；外部依赖失败有降级路径（如推荐失败 → 搜索提示；RAG 不可达 → 仅业务工具） |
| **DeepAgent 原生** | `create_agent()` 声明式定义；Skills 管理规范；LangGraph 负责图执行与持久化 |

### 1.3 与 v1.0（LangChain 自定义调度）对比

| 维度 | v1.0 | v2.x（现行） |
|------|------|-------------|
| Agent 框架 | 自定义 `base_agent.py` 循环 | DeepAgents `create_agent` + LangGraph |
| 图 / API | FastAPI + 自建 WS/SSE | LangGraph Server（`langgraph-cli[inmem]`） |
| L1 路由 | 自定义 `intent_router` | `RegexShortcutMiddleware` |
| 二次确认 | Redis 确认键 + 文本匹配 | 原生 `interrupt()` + Checkpoint |
| 对话记忆 | Redis List ChatMemory | Thread Checkpointer（inmem 落盘）+ Store 语义记忆 |
| 前端 | 自建协议 | LangGraph SDK 1.x（`Client` + SSE） |
| 身份 | 本地解码为主 | Gateway introspect + 可选本地 jks |
| Skills / RAG | 无 | SkillsMiddleware + RAGMiddleware（动态 MCP 工具） |

---

## 2. 整体架构

### 2.1 系统架构（权威）

```
用户（C端 / 管理后台）
  │
  │  LangGraph SDK (HTTP + SSE)
  │  Authorization + X-Hmall-Agent-Type
  ▼
┌──────────────────────────────────────────────────────────────────┐
│              Agent Service (LangGraph Server :8090)                │
│                                                                    │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │  LangGraph API 层                                            │   │
│  │  ├── POST /threads/{id}/runs/stream（SSE 流式）              │   │
│  │  ├── POST /assistants/{id}/runs/stream                       │   │
│  │  ├── GET/POST /assistants/*、/threads/*（含 search / state） │   │
│  │  ├── POST /api/v1/batch-report（自定义：批量运营报告）         │   │
│  │  └── GET  /api/v1/llm/health（自定义：LLM 连通性）            │   │
│  └────────────────────────────────────────────────────────────┘   │
│                          │                                         │
│  ┌───────────────────────▼────────────────────────────────────┐   │
│  │  中间件链                                                     │   │
│  │  Auth → Permission → RegexShortcut → RAG → Skills            │   │
│  └───────────────────────┬────────────────────────────────────┘   │
│                          │                                         │
│  ┌───────────────────────▼────────────────────────────────────┐   │
│  │  Agent 层                                                    │   │
│  │  CustomerAgent                         AdminAgent            │   │
│  │  · ~20 业务 + 记忆工具                 · 11 工具（只读+日报） │   │
│  │  · Skills 7                            · Skills 3            │   │
│  │  · interrupt 二次确认/多轮             · interrupt 预留       │   │
│  └───────────────────────┬────────────────────────────────────┘   │
│                          │                                         │
│  ┌───────────────────────▼────────────────────────────────────┐   │
│  │  基础设施                                                    │   │
│  │  · Checkpointer：inmem + .langgraph_api 落盘（多租户 owner） │   │
│  │  · Store：in_memory（Layer 3 语义记忆）                      │   │
│  │  · Redis db=0：用户画像 profile:{uid}:*（与后端共享）        │   │
│  │  · DashScope qwen-turbo（OpenAI 兼容）                       │   │
│  │  · RAG：MCP :8008 → LightRAG :9621（可选，enable_rag）       │   │
│  └────────────────────────────────────────────────────────────┘   │
└──────────────────────────┬───────────────────────────────────────┘
                           │ HTTP (httpx)：业务 API + introspect
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│              hm-gateway (:8080)                                    │
│  AuthGlobalFilter / 限流 / Nacos 动态路由                           │
└──┬──────┬────────┬────────┬────────┬────────┬───────────────────┘
   ▼      ▼        ▼        ▼        ▼        ▼
 item   cart    user    trade    pay    admin    search
:8081   :8082   :8084   :8085   :8083  (管理)   :8089
```

### 2.2 三级路由架构

```
用户消息（runs.stream / submit）
  │
  ├─ L1: RegexShortcutMiddleware（毫秒级）
  │   · 匹配「查看订单 / 购物车 / 秒杀 / 猜你喜欢 / 运营日报」等
  │   · 直接调用对应 @tool + 代码格式化 → AIMessage（无 tool_call）→ END
  │   · 目标：拦截高频只读查询，零 LLM 成本
  │
  ├─ L2: interrupt()（多轮 / 二次确认）
  │   · 秒杀下单、取消订单、清空购物车、地址增改等
  │   · Checkpoint 保存挂起状态；前端 resume 后继续
  │
  └─ L3: LLM 兜底（~秒级）
      · 闲聊、复杂意图、需从上下文抽参的场景
      · Agent Loop 自主选工具；Skills 提供流程规范
```

**设计意图**：把「确定性强、参数可抽取」的指令压到 L1；把「危险写操作」固定到 L2；把「语义模糊」留给 L3。顺序不可颠倒——Regex 必须在 Skills 之前，否则 Skills 加载在短路路径上白做。

### 2.3 技术栈

| 类别 | 技术 | 说明 |
|------|------|------|
| 语言 / 包管理 | Python ≥ 3.12 / uv | |
| Agent | DeepAgents（`create_agent`） | 声明式 Agent + 中间件 |
| 运行时 | LangGraph + `langgraph-cli[inmem]` | 图执行、API Server、开发态 Checkpoint |
| LLM | 通义千问 qwen-turbo（DashScope） | `langchain-openai` ChatOpenAI 兼容接口 |
| HTTP | httpx | 异步调 Gateway |
| MCP / RAG | FastMCP + `langchain-mcp-adapters` + LightRAG | 知识库桥接 |
| 画像 | Redis db=0 | 与 Java 后端共享 `profile:` |
| 前端 SDK | `@langchain/langgraph-sdk` 1.x | 正确转发 `context` / `command` |
| 可观测 | LangGraph Studio / LangSmith（可选） | |

> **已废止**：`langgraph-checkpoint-redis` 作为开发态默认 Checkpoint（曾规划 Redis db=1）。现行以 inmem 落盘为准；生产可换 Postgres 等，**owner 过滤模型不变**。

### 2.4 Agent 注册与路由

**`graph.json` 语义（权威）**：

| 字段 | 语义 |
|------|------|
| `graphs.customer_agent` / `admin_agent` | 注册图名 → Python 导出 `agent` 对象路径 |
| `store.type = in_memory` | LangGraph Store（Layer 3 记忆）；由平台注入 `config.configurable.store` |
| `auth.path` | 自定义 Auth（introspect + owner 多租户）；`disable_studio_auth: true` 便于 Studio |
| `env` | 加载 `.env` |
| （不在 graph.json）`LANGGRAPH_HTTP` | 由 `start_server.py` 注入，挂载自定义 FastAPI（batch-report / llm health） |

前端通过助手 ID（`customer_agent` / `admin_agent`）选择图；同一次请求的 `context.agent_type` 必须与之匹配，供 Permission / introspect 路径选择。

实现映射与启动环境变量表见 [实现说明 · 服务启动与配置](./hmall_Agent实现说明文档.md)。

---

## 3. CustomerAgent 设计（C 端客服助手）

### 3.1 职责与 Context

| 字段 | 含义 |
|------|------|
| `agent_type` | 固定 `"customer"` |
| `user_id` | 由 AuthMiddleware / introspect 注入；工具侧可读 |
| `user_token` | C 端 JWT；工具调 Gateway 携带 |
| `enable_rag` | 前端「知识库」开关；为 true 时 RAGMiddleware 注入 MCP 工具 |

中间件顺序（与代码一致）：**Auth → Permission → RegexShortcut → RAG → Skills**。

### 3.2 工具规格（约 20 业务 + 记忆）

> 完整参数、返回格式、空数据文案见实现说明 Part I。此处仅列**契约级规格**。

#### 商品浏览（3）

| 工具 | API | 登录 | 备注 |
|------|-----|------|------|
| `search_items_api` | `GET /search` | 否 | ES 全文；推荐降级可复用 |
| `get_item_detail_api` | `GET /items/{id}` | 否 | |
| `get_item_page_api` | `GET /items/page` | 否 | |

#### 秒杀（3）

| 工具 | API | 登录 | 备注 |
|------|-----|------|------|
| `get_seckill_activities_api` | `GET /seckill/activities` | 否 | L1 高频 |
| `get_seckill_product_api` | `GET /seckill/products/{relationId}` | 否 | |
| `do_seckill_api` | `POST /seckill/order/{relationId}` | 是 | **L2 interrupt 确认** |

#### 购物车（5）

| 工具 | API | 登录 | 备注 |
|------|-----|------|------|
| `get_cart_list_api` | `GET /carts` | 是 | L1 |
| `add_to_cart_api` | `POST /carts` | 是 | 画像由后端 CartService 写 |
| `update_cart_quantity_api` | `PUT /carts/{itemId}` | 是 | 不记 cart 画像事件 |
| `delete_cart_item_api` | `DELETE /carts/{itemId}` | 是 | **interrupt** |
| `clear_cart_api` | `DELETE /carts` | 是 | **interrupt**；不走 L1 |

#### 订单（4）

| 工具 | API | 登录 | 备注 |
|------|-----|------|------|
| `get_order_list_api` | `GET /orders/page` | 是 | L1；支持状态筛选 |
| `get_order_detail_api` | `GET /orders/{id}` | 是 | |
| `cancel_order_api` | `POST /orders/batch/close` | 是 | **interrupt** |
| `confirm_receive_api` | `PUT /orders/{orderId}` | 是 | **interrupt**；购买画像由支付监听写 |

#### 地址（3）

| 工具 | API | 登录 | 备注 |
|------|-----|------|------|
| `get_address_list_api` | `GET /addresses` | 是 | L1 |
| `add_address_api` | `POST /addresses` | 是 | **interrupt 多轮收集** |
| `update_address_api` | `PUT /addresses/{id}` | 是 | **interrupt 多轮** |

#### 个性化推荐（2）

| 工具 | API / 行为 | 登录 | 备注 |
|------|------------|------|------|
| `get_recommendations_api` | `GET /recommend` | 是 | scene=`home`/`detail`/`cart` |
| `analyze_user_preferences` | 画像优先 / miss 降级聚合 | 是 | 详见 Part B / Part C |

#### 跨会话记忆（Layer 3）

| 工具 | 存储 | 备注 |
|------|------|------|
| `save_memory` | LangGraph Store `user_memory` | 未完成购物意图等 |
| `get_memories` | 同上 | 对话开始 / 推荐前读取 |

> hmall 暂无优惠券 / 售后工具（相对部分对标项目更少）。工具实现体见实现说明，不在本文粘贴。

### 3.3 L1 正则路由（规格）

| 用户输入示例 | 路由工具 | 层 |
|-------------|---------|-----|
| 查看秒杀 / 秒杀活动 | `get_seckill_activities_api` | L1 |
| 搜索手机 / 查找… | `search_items_api` | L1 |
| 查看购物车 | `get_cart_list_api` | L1 |
| 查看订单 / 待付款订单 | `get_order_list_api` | L1 |
| 查看订单 100 | `get_order_detail_api` | L1 |
| 查看地址 | `get_address_list_api` | L1 |
| 有什么推荐 / 猜你喜欢 | `get_recommendations_api` | L1 |
| 取消订单 / 清空购物车 / 确认收货 / 改地址 | 对应写工具 | **L2**（不拦截） |

规则：危险写与多轮收集**禁止** L1 短路；参数需语义抽取的（如「秒杀 iPhone」）走 L3。

### 3.4 L2 interrupt 状态机（设计）

**二次确认类**（秒杀下单、取消订单、删购物车项、清空购物车、确认收货）：

```
工具入口 → 展示摘要 → interrupt(confirmation)
  → 前端 InterruptActions（批准 / 拒绝）
  → resume("确认") → 调 Gateway 写接口
  → 否则取消
```

**多轮收集类**（新增 / 修改地址）：

```
interrupt(field_selection) → resume(字段)
  → interrupt(value_input) → resume(新值)
  → PUT/POST Gateway
```

Checkpoint 在每次 interrupt 时保存挂起写入；恢复时**不重新跑**已完成节点。前端契约见 §10。

### 3.4.1 interrupt 与 Checkpoint 一致性

interrupt 的正确性依赖 Checkpointer：工具执行到 `interrupt()` 时，图必须把「已完成节点 + 挂起写入」落成快照，否则用户 resume 后会重复扣库存或重复下单。开发态落盘到 `.langgraph_api/` 后，即使 uvicorn worker 短暂重启，未完成的确认流仍可恢复——前提是前端持有同一 `thread_id` 且 Auth 的 `owner` 匹配。

**幂等建议（设计层）**：

| 场景 | 建议 |
|------|------|
| 用户重复点击「确认」 | 后端秒杀/取消接口自身幂等；Agent 侧 resume 只投递一次 |
| 网络重试导致二次 stream | 前端禁用按钮直至 SSE 结束；thread 级 busy 标志 |
| 超时未确认 | 产品可定义 TTL；过期后 goto end 并提示重新发起 |

### 3.4.2 危险操作清单与文案原则

| 操作 | 确认文案必须包含 | 默认预期回复 |
|------|------------------|--------------|
| 秒杀下单 | 品名、秒杀价、限购、剩余库存 | 「确认」 |
| 取消订单 | 订单号、金额摘要 | 「确认取消」类 |
| 清空购物车 | 当前件数提示 | 「确认」 |
| 确认收货 | 订单号 | 「确认」 |

文案由工具内拼装，**不经 LLM 改写确认关键字段**，避免价格被模型幻觉篡改。LLM 仅在进入工具前负责选对工具与参数。

### 3.4.3 空数据与错误返回约定

所有只读工具在代码层区分：

1. **业务空**：合法但无数据 → 固定中文提示（如「暂无进行中的秒杀」），不调用 LLM 二次润色。  
2. **认证失败**：缺 token / 401 → 「请先登录」类提示。  
3. **网关/超时**：返回可理解错误，Regex 短路失败时应降级到 L3 而非空白。  
4. **部分字段缺失**：Formatter 显示「—」，禁止抛 KeyError 中断 SSE。

### 3.5 Skills（7）

| Skill 目录 | 用途 | 与路由关系 |
|------------|------|------------|
| `shopping-guide` | 浏览 / 搜索引导 | L3 为主；搜索亦可 L1 |
| `seckill-order` | 秒杀选品与下单 | 查活动可 L1；下单 L3+L2 |
| `cart-management` | 购物车话术与确认 | 查看 L1；写操作 L2 |
| `order-management` | 订单查询 / 取消 / 收货 | 列表 L1；写 L2 |
| `address-management` | 地址增改多轮 | 列表 L1；增改 L2 多轮 |
| `personalized-recommendation` | 推荐触发与理由 | home 可 L1；其余 L3 |
| `rag-query` | 政策 / FAQ | 仅 `enable_rag` 时有工具 |

**Skills 设计原则**：

1. **流程型而非百科**：写清「先调哪个工具、何时 interrupt、如何解释结果」。  
2. **与 Prompt 分工**：SYSTEM_PROMPT 管人格与全局红线；SKILL 管场景步骤。  
3. **可演进**：新业务域优先加 Skill，而不是把 Prompt 无限拉长。  

SKILL.md 全文 → 实现说明 / `src/workspace/customer/skills/`。

### 3.6 CustomerAgent 非功能需求

| 类别 | 要求 |
|------|------|
| 延迟 | L1 P99 应远低于 LLM 路径；不在 L1 路径做 RAG 加载 |
| 安全 | 写操作必须登录；危险写必须 interrupt |
| 可观测 | 关键工具调用应打结构化日志（tool名、耗时、user_id 哈希） |
| 兼容 | context-only；SDK 1.x |

### 3.7 与推荐 / 画像 / RAG 的交界

| 能力 | 交界点 |
|------|--------|
| 推荐 | 工具 + Skill + L1 规则，见 Part B |
| 画像 | `analyze_user_preferences` 读 Layer2；记忆工具写 Layer3，见 Part C1 |
| RAG | 同链 RAGMiddleware；政策类问题优先于臆造，见 §12 |

---

## 4. AdminAgent 设计（管理助手）

### 4.1 职责与约束

- **纯只读**：业务写工具由 `PermissionMiddleware` 从工具列表剔除；即使 LLM 想调也无法选中。
- Context：`agent_type="admin"` + 管理端 JWT；introspect 走 `GET /admin/info`。
- 中间件：Auth → Permission → Regex → RAG → Skills（与 Customer 同构，Permission 行为不同）。

### 4.2 工具规格（11）

#### 商品（2）/ 订单（2）/ 用户（2）

| 工具 | API（经 admin-service） |
|------|-------------------------|
| `admin_get_product_page_api` | `GET /admin/product/list` |
| `admin_get_product_detail_api` | `GET /admin/product/{id}` |
| `admin_get_order_page_api` | `GET /admin/order/list` |
| `admin_get_order_detail_api` | `GET /admin/order/{id}` |
| `admin_get_user_page_api` | `GET /admin/member/list` |
| `admin_get_user_detail_api` | `GET /admin/member/{id}` |

#### 秒杀管理（4）

| 工具 | API |
|------|-----|
| `admin_get_seckill_promotion_page_api` | `GET /admin/seckill/promotion/list` |
| `admin_get_seckill_relation_page_api` | `GET /admin/seckill/relation/list` |
| `admin_get_seckill_order_page_api` | `GET /admin/seckill/order/list` |
| `admin_get_seckill_stock_api` | `GET /admin/seckill/stock/{relationId}` |

#### 编排（1）

| 工具 | 行为 |
|------|------|
| `generate_daily_report` | `asyncio.gather` 并发拉订单 / 秒杀 / 关联库存 / 商品 / 用户摘要 → 格式化为运营日报 |

管理端响应常为 `R<T>` 包装；Gateway 客户端需自动解包（技术决策见实现说明）。

### 4.3 运营日报编排设计

**触发**：L1 匹配「运营日报 / 生成日报 / 帮我做一份日报」等 → 直接调用 `generate_daily_report`（跳过 LLM）。

**编排原则**：

1. **并发**：五个只读查询并行，降低尾延迟。  
2. **容错**：单路失败不影响整报；缺项显示「暂无数据」。  
3. **固定模板**：标题日期 + 订单 / 秒杀 / 商品 / 用户分区，便于运营扫读。  
4. **只读**：日报工具本身不写库、不改活动。

示意输出结构：

```
📅 {date} 枫叶商城运营日报
【订单概览】笔数 / 金额 / 待发货 …
【秒杀活动】进行中场次 / 库存预警 / 秒杀订单 …
【商品概况】在售 / 下架 …
【用户概况】总量 / 今日新增 …
```

自定义 HTTP `POST /api/v1/batch-report` 可内部触发同一编排，供定时或外部系统调用（§8）。

### 4.4 Skills（3）

| Skill | 用途 |
|-------|------|
| `daily-report` | 日报话术与字段说明 |
| `data-query` | 只读查询规范 |
| `rag-query` | 运营策略 / 库存指南等知识库问答 |

### 4.5 与 RAG 的关系

Admin 与 Customer 共用 MCP RAG 工具集；开启 `enable_rag` 后可回答「秒杀库存怎么定」等策略问题。知识文档由 LightRAG WebUI 维护，Agent 只检索。详见 §12。

### 4.6 AdminAgent 威胁模型（只读保障）

| 威胁 | 缓解 |
|------|------|
| LLM 被诱导「帮我改库存」 | Permission 剔除写工具；无工具则无法执行 |
| 管理 JWT 泄露到 C 端页面 | 双入口、双 Token；owner 含 agent_type 前缀 |
| 日报接口被未登录调用 | Auth + introspect；自定义路由亦应校验 |
| 通过 RAG 套取未授权内部文档 | LightRAG 账号权限与文档分级由运营控制；Agent 侧仅开关 |

### 4.7 运营日报字段语义（契约）

| 分区 | 最小字段 | 数据来源工具 |
|------|----------|--------------|
| 订单 | 笔数、金额、待发货 | `admin_get_order_page_api` |
| 秒杀 | 进行中场次、预警件数、秒杀订单 | promotion/relation/order |
| 商品 | 在售、下架 | product page |
| 用户 | 总量、今日新增（若 API 提供） | member page |

若某管理 API 暂无「今日」维度，日报应标注「当前快照」而非伪造时间序列。后续可扩展「周报」同一编排换时间窗参数。

### 4.8 Admin L1 规则设计要点

- 「运营日报」类短语**只**路由到编排工具，避免误走单表查询。  
- 「查看订单」等与 C 端话术相似，但工具名完全不同，依赖 `agent_type=admin` 的独立规则表。  
- 管理端不开放「取消订单」等写意图的正则，即使话术相似也不注册。

---

## 5. 对话记忆设计

### 5.1 Thread + Checkpointer（开发态 inmem 落盘）

```
会话历史：Checkpointer（按 thread_id）
长期语义记忆：Store（namespace + key，跨 thread）
用户隔离：Auth 写入 metadata.owner = {agent_type}:{user_id}
```

**现行持久化（权威）**：

| 项 | 说明 |
|----|------|
| Runtime | `LANGGRAPH_RUNTIME_EDITION=inmem` |
| 热路径 | 内存 |
| 冷路径 | pickle 至工作目录 **`.langgraph_api/`**（含 checkpoint / ops / store 相关 `.pckl`） |
| 重启 | 进程重启后可从落盘恢复 |
| 生产 | 可替换 Postgres 等托管后端；**owner 过滤逻辑不变** |

> **废止表述**：早期「Redis Checkpoint（db=1）」。若旧材料仍写 Redis 会话库，以本节与 `start_server.py` 为准。  
> Redis **db=0** 仅用于**用户画像**（Part C），与 Checkpoint 无关。

| 概念 | 说明 |
|------|------|
| Thread | 唯一 `thread_id`；创建时打 `metadata.owner` |
| Checkpoint | 节点后快照（messages、pending writes / interrupt） |
| 恢复 | 同 thread 续聊；`command.resume` 继续 interrupt |
| 清理 | `DELETE /threads/{id}` |
| 多租户 | **不是**「每用户一个 Redis db」，而是 Auth 强制 owner 过滤 |

### 5.2 交互流程

1. 前端带 `Authorization` + `X-Hmall-Agent-Type` → Auth introspect → `owner`  
2. `threads.create`（服务端强制写入 owner）  
3. `runs.stream` → 加载 Checkpoint → 中间件 → 工具 / interrupt  
4. interrupt → 落盘挂起 → `resume`  
5. `threads.search({ metadata: { owner } })` 仅本人会话  

### 5.3 与 v1 ChatMemory 对比

| 维度 | v1 Redis List | 现行 |
|------|---------------|------|
| 存储 | 手动 List + TTL | inmem + `.langgraph_api` |
| interrupt | 自建确认键 | 原生 pending writes |
| 隔离 | Key 含 userId | `metadata.owner` + Auth |
| 跨会话意图 | 弱 | Store Layer 3 + Redis 画像 |

### 5.4 Thread 生命周期（摘要）

创建 → 多轮 stream（自动 checkpoint）→ 可选 interrupt/resume → search 列表 → delete。  
详细 API 表见 §8；前端绑定见 §10。

### 5.5 多租户隔离详细规则

| 操作 | Auth 行为 |
|------|-----------|
| create thread | 强制 `metadata.owner = {agent_type}:{user_id}` |
| search threads | 过滤器仅本人 owner |
| read / stream | 校验资源 owner；不匹配则拒绝 |
| delete | 同上 |
| store 读写 | 按 user 维度 namespace；禁止跨用户 key |

**为何 owner 含 agent_type**：C 端用户 id=7 与管理员 id=7 不得共享会话空间。

### 5.6 Checkpoint 运维注意（设计）

| 项 | 说明 |
|----|------|
| 目录 | `.langgraph_api/` 含会话隐私，备份与权限按生产密钥标准管理 |
| 清理 | 定期删除过期 thread；开发态可整目录重建（丢会话） |
| 扩展 | 生产 Postgres 时迁移策略另案；API 契约不变 |
| 与画像 | 删除 thread **不**自动清除 `profile:*`；隐私清除需独立 invalidate |

### 5.7 会话与画像 / Store 的分工再强调

| 存储 | 存什么 | 不存什么 |
|------|--------|----------|
| Checkpoint | 对话消息、interrupt 挂起 | 类目偏好得分 |
| Redis 画像 | 聚合偏好与事件 | 完整对话原文 |
| Store | 短语义记忆 | 订单明细 |

---

## 6. 安全设计（权威）

### 6.1 双 JWT + Gateway introspect

hmall-agent **不挂在 Gateway 后方**，收不到 `user-info` 头，故采用「探查对齐」：

```
前端 Authorization: JWT
        │
        ├─ LangGraph Auth.authenticate
        │     └─ introspect：
        │           C 端  GET /users/me      （Gateway 验签 → UserContext）
        │           管理端 GET /admin/info   （admin-service 自验；Gateway 对 /admin/** 放行）
        │     └─ identity.owner = {agent_type}:{user_id}
        │
        └─ context.user_token → 工具调 Gateway（业务路径再次验签）
```

| Agent | Token 来源 | introspect | 验证方 |
|-------|-----------|------------|--------|
| Customer | `POST /users/login`（hmall.jks） | `GET /users/me` | Gateway |
| Admin | `POST /admin/login`（admin.jks） | `GET /admin/info` | admin-service |

**JWT claim 约定**（与 Java 签发一致）：

| 端 | claim | 用途 |
|----|-------|------|
| C 端 | `user` | 选 introspect 路径 / 可选 fallback |
| 管理端 | `sub` + `type=ADMIN` | 同上 |

**AuthMiddleware**：默认 introspect 写入 `context.user_id`；`JWT_VERIFY_LOCAL=true` 时可优先本地 jks；仅 `INTROSPECT_FALLBACK_JWT=true` 时才回退 payload 解码（运维应急，削弱与 Gateway 强一致）。

**多租户 hooks（示意）**：`threads.create` / `search` / `read` 等均强制 `owner` 过滤器，防止枚举 `thread_id` 越权读他人会话。

### 6.2 PermissionMiddleware

| Agent | 读 | 写 |
|-------|----|----|
| Customer | 全部 C 端工具 | 允许（需 Token + 危险操作 interrupt） |
| Admin | 管理只读 + 日报 | **全部写工具从 request.tools 剔除** |

写工具集合包括购物车 / 订单取消收货 / 地址写 / 秒杀下单等（与实现 `WRITE_TOOLS` 对齐）。推荐与记忆工具需登录，但不是「管理写」。

### 6.3 参数校验（设计要求）

在 `@tool` 内做类型与业务校验：数量 ≥ 1、手机号格式、ID 为正整数等；失败返回固定错误文案，不抛未处理异常污染 SSE。

### 6.4 Token 传递链（端到端）

```
前端 Client({ defaultHeaders: { Authorization, X-Hmall-Agent-Type } })
  → runs.stream(..., { context: { user_token, agent_type, enable_rag, ... } })
  → Auth.authenticate → introspect → owner
  → AuthMiddleware → context.user_id
  → 工具 gateway_client 携带同一 JWT → Gateway / admin-service
```

> LangGraph 0.6+：**禁止**同时传 `configurable` 与 `context` 做认证；统一 **context-only**。

### 6.5 introspect 缓存与失败策略

| 配置语义 | 建议默认 | 含义 |
|----------|----------|------|
| `INTROSPECT_CACHE_TTL` | 60s | 同 Token 短时复用探查结果 |
| `INTROSPECT_FALLBACK_JWT` | false | 失败不回退解码，保持与 Gateway 一致 |
| `JWT_VERIFY_LOCAL` | false | 默认不优先本地 jks |

失败时：拒绝建立/继续受保护会话，前端提示重新登录。缓存击穿与后端 5xx 应可观测。

### 6.6 信任边界图

```
[浏览器] --JWT--> [LangGraph Auth] --introspect--> [Gateway/Admin]
                                      |
                                      v
                                 owner / user_id
                                      |
[工具层] --同一 JWT--> [Gateway] --验签--> [微服务]
```

Agent 进程**不是**身份权威；它是「携带用户票证的编排者」。伪造 payload 的假 JWT 在 introspect 被拒。

### 6.7 安全设计检查清单

- [ ] C / Admin Token 永不混用  
- [ ] threads 均带 owner 过滤  
- [ ] Admin 工具列表无写操作  
- [ ] 危险写均 interrupt  
- [ ] RAG / 推荐失败可降级  
- [ ] 日志不打印完整 JWT  

---

## 7. 中间件体系

### 7.1 中间件链（权威顺序）

| 序 | 中间件 | 功能 | 可否短路 LLM |
|----|--------|------|:------------:|
| 1 | AuthMiddleware | introspect / 可选 jks → `user_id` | 否 |
| 2 | PermissionMiddleware | Admin 剔写工具 | 否 |
| 3 | RegexShortcutMiddleware | L1 工具直调 | **是** |
| 4 | RAGMiddleware | `enable_rag` 时注入 MCP 工具 | 否（失败仅降级） |
| 5 | SkillsMiddleware | 追加 SKILL.md | 否 |

> 旧文档曾写「RAG 预留」且顺序把 Skills 写在 RAG 前——以**现行 agent.py** 为准：Customer/Admin 均为 Auth → Permission → Regex → **RAG** → Skills。

### 7.2 洋葱模型与触发时机

所有中间件钩在 `awrap_model_call`：Agent 图每次进入 `model_call` 节点时统一触发。

```
        ┌─ Auth ──────────────────────────────┐
        │  ┌─ Permission ───────────────────┐ │
        │  │  ┌─ Regex（可 return 短路）──┐ │ │
        │  │  │  ┌─ RAG 注入工具 ───────┐ │ │ │
        │  │  │  │  ┌─ Skills ────────┐ │ │ │ │
        │  │  │  │  │  LLM            │ │ │ │ │
        │  │  │  │  └─────────────────┘ │ │ │ │
        │  │  │  └──────────────────────┘ │ │ │
        │  │  └───────────────────────────┘ │ │
        │  └────────────────────────────────┘ │
        └─────────────────────────────────────┘
```

有 `tool_calls` → 执行工具（可能 interrupt）→ 回到 `model_call`；无则 END → SSE 最终消息。

### 7.3 context_schema vs state

| | context | state（Checkpoint） |
|---|---------|---------------------|
| 生命周期 | 单次 run | 跨 turn 持久 |
| 用途 | agent_type / token / enable_rag | messages / interrupt |
| 注入 | SDK `context` | `input.messages` / 图更新 |

### 7.4 顺序约束与反例

| 错误顺序 | 后果 |
|----------|------|
| Skills 在 Regex 前 | L1 命中仍加载 Skills，浪费 I/O |
| RAG 在 Permission 前且无过滤 | 一般无写风险，但不利统一鉴权日志 |
| Auth 过晚 | 后续中间件读不到权威 user_id |

### 7.5 Regex 中间件设计细则

1. 只检查**最后一条** human 消息。  
2. 工具 invoke 异常 → 返回 None 走 LLM，避免硬失败。  
3. 不匹配写操作正则（或匹配后故意不注册）。  
4. 管理端与 C 端**分表**，禁止共用一张规则表导致串工具。  

### 7.6 RAG 中间件与 L1 的交互

L1 短路发生时，内层 RAG/Skills/LLM 均不执行——因此「查看秒杀」不会附带知识库调用。若产品希望「带政策的秒杀说明」，应走 L3 或单独话术，而不是期望 L1 自动 RAG。

---

## 8. API 设计

### 8.1 LangGraph SDK 端点

| 前端操作 | SDK | HTTP | 说明 |
|---------|-----|------|------|
| 助手列表 | `assistants.search` | `POST /assistants/search` | customer / admin |
| 创建线程 | `threads.create` | `POST /threads` | 写入 owner |
| 线程列表 | `threads.search` | `POST /threads/search` | 按 owner 过滤 |
| 线程状态 | `threads.getState` | `GET /threads/{id}/state` | messages 等 |
| 删除线程 | `threads.delete` | `DELETE /threads/{id}` | |
| **流式对话** | `runs.stream` | `POST /threads/{id}/runs/stream` | SSE |
| **resume** | `command.resume` | 同上 | interrupt 恢复 |
| 强制结束 | `command.goto=__end__` | 同上 | |

### 8.2 submit / stream 请求体契约

```json
{
  "input": { "messages": [{ "type": "human", "content": "查看秒杀活动" }] },
  "config": { "recursion_limit": 100 },
  "context": {
    "agent_type": "customer",
    "user_token": "<JWT>",
    "enable_rag": false
  }
}
```

| 字段 | 说明 |
|------|------|
| `context.agent_type` | `customer` \| `admin` |
| `context.user_token` | 业务 API 用 JWT |
| `context.enable_rag` | RAG 动态注入开关 |
| `command.resume` | interrupt 恢复值 |
| Headers `Authorization` | Auth + introspect |

### 8.3 自定义路由

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/v1/batch-report` | 触发运营日报编排 |
| GET | `/api/v1/llm/health` | DashScope 最小 chat 探测（§13） |

挂载方式：`start_server.py` 设置 `LANGGRAPH_HTTP` → FastAPI app（实现见实现说明）。规划中的通知 SSE（Part C）未来亦挂此层。

路由分层示意：

```
langgraph_api.server:app (:8090)
├─ /ok /docs /ui
├─ 自定义：/api/v1/*
└─ 标准：/assistants /threads /runs
```

### 8.4 SSE 事件语义（设计）

| 事件类型 | 含义 | 前端动作 |
|----------|------|----------|
| messages/partial | 流式增量 | 更新同 id 气泡 |
| messages/complete | 消息完成 | 定稿 |
| values / 状态快照 | 可选 | 调试或同步 files |
| interrupt | 人机挂起 | 展示 InterruptActions |
| error | 失败 | Toast + 结束 loading |

具体 chunk 字段以实现 SDK 为准；本文只定产品语义。

### 8.5 错误与限流（设计期望）

| 情况 | 期望行为 |
|------|----------|
| 未认证访问他人 thread | 401/403，无数据泄露 |
| recursion_limit 耗尽 | 友好提示「步骤过多，请简化问题」 |
| Gateway 429 | 工具返回限流文案 |
| LLM 超时 | 健康检查变离线；对话返回降级（规划） |

### 8.6 与外部系统集成的 API 面

| 调用方 | 使用的面 |
|--------|----------|
| 浏览器 | SDK threads/runs + llm/health +（规划）notifications |
| 内部定时任务 | batch-report |
| 运维 | /ok、Studio /ui |

---

## 9. 项目结构与配置语义

### 9.1 目录结构（设计视图）

```
hmall-agent/
├── start_server.py              # LangGraph Server 启动；注入 runtime / HTTP / graphs
├── start_rag_server.py          # RAG MCP 独立进程
├── graph.json                   # graphs / store / auth / env
├── pyproject.toml
├── .env / .env.example          # 全文见实现说明
├── src/
│   ├── agents/customer|admin/   # agent / prompts / tools / regex_rules
│   ├── api/                     # batch_report / health
│   ├── middleware/              # auth / permission / regex / rag_context
│   ├── security/auth.py         # LangGraph Auth（owner）
│   ├── gateway/                 # http_client / introspect / jwt 辅助
│   ├── mcp_servers/rag_server.py
│   ├── tools/                   # formatters / rag_loader
│   ├── user_profile/            # ProfileStore + memory tools
│   ├── core/                    # config / llms
│   └── workspace/*/skills/      # SKILL.md
├── LightRAG/                    # git submodule（独立配置）
└── .langgraph_api/              # 开发态 Checkpoint/Store 落盘（勿提交密钥）
```

> 历史文件名 `src/profile/`、`redis_checkpoint.py` 等以仓库现状为准；实现说明含准确路径。

### 9.2 环境变量语义表（仅语义）

| 变量族 | 语义 | 备注 |
|--------|------|------|
| `DASHSCOPE_*` / `LLM_*` | LLM 接入 | |
| `JAVA_GATEWAY_URL` | 业务 + introspect 入口 | |
| `JWT_*` / `INTROSPECT_*` | 本地验签开关、缓存 TTL、fallback | 默认走 introspect |
| `REDIS_*` / `PROFILE_REDIS_DB` | **画像** Redis（db=0） | **不是** Checkpoint |
| `RAG_*` | LightRAG URL / 账号或 API Key / MCP 端口 | §12 |
| `LANGGRAPH_RUNTIME_EDITION` | `inmem` | 与落盘目录配合 |
| `VITE_AGENT_URL`（前端） | Agent Server 基址 | |

完整键值与示例 → [实现说明 · 配置](./hmall_Agent实现说明文档.md)。

### 9.3 graph.json ↔ 启动映射

| graph.json | 启动注入 |
|------------|----------|
| `graphs` | `LANGSERVE_GRAPHS` |
| `auth` | `LANGGRAPH_AUTH` |
| `store` | `LANGGRAPH_STORE` |
| （无） | `LANGGRAPH_HTTP` ← start_server |

---

## 10. 前端集成设计（仅通信契约）

> **禁止**在本文粘贴完整 `useLangGraph.ts` / 组件实现。实现细节 → 实现说明 Part I「前端」。

### 10.1 技术选型

- Vue 3 + Element Plus；`@langchain/langgraph-sdk` **1.x**（0.x 会丢弃 `context`/`command`）。
- C 端 `/portal/chat`、管理端 `/admin/chat` 独立全页；入口分别为浮动按钮 / Header「AI 助手」。

### 10.2 组件职责

| 组件 | 职责 |
|------|------|
| `ChatPanel` | 对话壳：消息列表、输入、快捷语、RAG 开关、打断展示 |
| `MessageBubble` | AI Markdown / 人类纯文本 |
| `InterruptActions` | interrupt 批准 / 编辑 / 拒绝 → `resume` |
| `ChatWidget` / `AdminChat` | 路由入口 |
| `useLangGraph` | Client、thread、SSE、interrupt 状态 |
| `useLlmHealth` | 轮询 `/api/v1/llm/health` |

### 10.3 SSE 与消息契约

- `streamMode` 建议含 `messages`（及所需 `values`）。
- `messages/partial` → 更新同 `id` 的 AI 气泡；`complete` → 定稿。
- 出现 interrupt 事件 → 填充 `interruptData`，暂停输入或展示确认卡。
- 停止：断开 SSE / `stop`；勿丢弃本地已渲染部分除非产品要求回滚。

### 10.4 context / Authorization

| 通道 | 内容 |
|------|------|
| Headers | `Authorization: Bearer <JWT>`；`X-Hmall-Agent-Type: customer\|admin` |
| context | `agent_type`、`user_token`、`enable_rag`（及服务端注入的 `user_id`） |
| 禁止 | 同时传认证用 `configurable` + `context` |

### 10.5 interrupt 前端协议

| 步骤 | 行为 |
|------|------|
| 收到 interrupt | 展示 `value.message`（确认文案 / 字段提示） |
| 用户确认 | `runs.stream(..., { command: { resume: <值> } })`，**input 可为 null** |
| 用户拒绝 | resume 非确认值或 goto end（产品约定） |

### 10.6 RAG 开关契约

- UI 开关 → `sessionStorage.rag_enabled`（刷新保持）。
- `sendMessage` 时传入 `enable_rag: boolean`。
- 与业务工具并存；MCP 不可达时后端降级，前端无需阻断发送。

### 10.7 主题与双端复用

`ChatPanel` 通过 props 区分：

| Prop 语义 | C 端 | 管理端 |
|-----------|------|--------|
| assistantId | customer_agent | admin_agent |
| agent_type | customer | admin |
| 快捷语 | 购物 / 推荐 / 订单 | 日报 / 订单 / 秒杀 |
| 主题色 | 商城品牌色 | 后台中性色 |

禁止在单一页面混用两套 Token。

### 10.8 会话列表 UX 契约

- 列表数据来自 `threads.search`，服务端已按 owner 过滤。  
- 切换会话 = 切换 `thread_id` 并 `getState` 渲染历史。  
- 删除需二次确认（前端），调用 `threads.delete`。  

### 10.9 Markdown 与溢出

AI 消息允许 Markdown（列表、粗体、代码块）；人类消息纯文本防注入。气泡容器需处理长 URL / 表格溢出（实现已修历史 bug，设计要求保留）。

### 10.10 前端非目标

本文不规定 CSS 细节、组件库版本锁定策略、e2e 用例；该部分属实现与工程规范。

---

## 11. 工具调用示例（场景级）

### 11.1 查看秒杀（L1）

```
用户「查看秒杀活动」
  → Auth（只读可无 token）→ Permission
  → Regex 命中 → get_seckill_activities_api → 格式化列表
  → 跳过 RAG/Skills/LLM → SSE 返回活动场次与库存摘要
```

### 11.2 秒杀下单（L3 + L2）

```
用户「秒杀 iPhone 15」
  → Regex 未命中（需抽 relationId）
  → Skills(seckill-order) + LLM
  → 查活动 → do_seckill_api → interrupt 确认
  → 前端 resume「确认」→ POST 下单 → 返回排队/订单提示
```

Checkpoint 保存挂起状态于 **`.langgraph_api`**（非 Redis）。

### 11.3 地址修改（多轮 interrupt）

```
「修改地址1」→ update_address_api
  → interrupt 选字段 → resume
  → interrupt 输入新值 → resume
  → PUT /addresses/{id}
```

### 11.4 运营日报（L1 编排）

```
「运营日报」→ generate_daily_report
  → gather 五路只读查询 → 模板输出
```

### 11.5 猜你喜欢（L1 推荐）

```
「有什么推荐」→ get_recommendations_api(scene=home)
  → Gateway /recommend → 列表 + basedOn
```

---

## 12. RAG 知识库（合并原 §16 与原第四部分）

> **状态：已实现。** 本章为 RAG 的**唯一权威设计章**；不再另设「第四部分」副本。  
> 联调命令、验收清单、与设计偏差 → [实现说明](./hmall_Agent实现说明文档.md)。

### 12.1 目标与边界

| 端 | 典型问题 |
|----|----------|
| Customer | 退换货政策、支付方式、配送说明等 FAQ |
| Admin | 秒杀策略、库存管理指南、订单分析、指标解读 |

**边界**：Agent **不负责**文档上传与索引维护；运营通过 LightRAG WebUI 管理知识。Agent 仅在 `enable_rag=true` 时动态获得检索工具。

### 12.2 架构（单一权威图）

```
前端 ChatPanel「知识库」开关
  → sessionStorage.rag_enabled
  → sendMessage(..., enable_rag)
        │
        ▼
LangGraph Agent (:8090)
  RAGMiddleware.awrap_model_call
    enable_rag=true  → rag_loader.get_rag_tools() 追加 MCP 工具
    enable_rag=false → 放行（仅业务工具）
        │
        │ MCP (streamable_http)
        ▼
RAG MCP Server (:8008)  FastMCP + LightRAGClient
        │ httpx + OAuth2/API Key
        ▼
LightRAG Server (:9621)  知识图谱 + 向量检索
```

**三层职责**：

| 层 | 组件 | 端口 | 职责 |
|----|------|------|------|
| 知识引擎 | LightRAG | 9621 | 建图谱 / 向量索引 / query API |
| MCP 桥接 | `rag_server.py` | 8008 | 将 REST 封装为 3 个 MCP 工具 |
| Agent | `RAGMiddleware` + Skills `rag-query` | 8090 | 按开关注入；LLM 选题调用 |

数据流：开关 → context → 中间件注入 → LLM 选 `rag_*` → MCP → LightRAG → 片段回灌 LLM → 用户可见回答。

### 12.3 MCP Server 设计

独立进程（`start_rag_server.py`），与 Agent Server 解耦，避免拖垮对话主进程。

**LightRAGClient 要点**：

- 认证：`RAG_API_KEY` 优先；否则用户名密码 `POST /login`，JWT 缓存，401 自动重登
- 连接：`httpx.AsyncClient` 单例
- 失败：向上抛给工具层；Middleware 层对「整站不可达」只 warning 不阻断业务工具

**工具规格**：

| MCP 工具 | LightRAG 端点 | 用途 |
|----------|---------------|------|
| `rag_query(query, mode)` | `POST /query` | 语义问答 + 参考来源 |
| `rag_query_data(query, mode)` | `POST /query/data` | 结构化：entities / relationships / chunks |
| `rag_graph_search(query)` | `POST /query/data` | 图谱向聚合 |

查询模式 `mode`：`mix`（默认，图谱+向量）、`hybrid`、`local`、`global`、`naive`、`bypass`。

### 12.4 RAGMiddleware 与加载器

| 行为 | 设计 |
|------|------|
| 注入条件 | `context.enable_rag is True` |
| 去重 | 按工具名避免重复 append |
| 缓存 | `rag_loader` 模块级缓存工具列表；`refresh()` 强制重连 |
| 降级 | MCP 不可达 → 日志 warning，本轮无 RAG 工具，业务继续 |

与推荐降级同哲学：**可选能力失败不得拖垮主购物链路**。

### 12.5 前端开关与 Skills

- UI：书本图标 + 指示灯；状态进 `sessionStorage`
- Skills：`workspace/customer|admin/skills/rag-query/SKILL.md` 均已挂入 sources
- Prompt：引导「政策/指南类优先 rag_query，勿臆造」

### 12.6 配置项语义

| 变量 | 默认语义 |
|------|----------|
| `RAG_BASE_URL` | LightRAG，如 `http://localhost:9621` |
| `RAG_USERNAME` / `RAG_PASSWORD` | 账号密码登录 |
| `RAG_API_KEY` | 可选，优先于账号密码 |
| `RAG_AUTH_ENABLED` | 是否启用 LightRAG 认证 |
| `RAG_MCP_PORT` | MCP 监听，默认 8008 |

全文样例 → 实现说明。

### 12.7 LightRAG 独立配置要点

LightRAG 子模块自有 `.env`（与 hmall-agent 分离）：

| 类别 | 要点 |
|------|------|
| Server | 端口 9621、WebUI 路径 |
| LLM | 建议与 Agent 同用 DashScope 兼容接口（模型名可不同） |
| Embedding | **一经选定不可随意更换**；更换需全量重建索引 |
| Storage | 开发可用 JSON/本地；生产建议 PostgreSQL 等 |

知识管理：WebUI 上传 PDF/DOCX/TXT/Markdown；自动构图+向量化。

### 12.8 部署顺序（简）

1. LightRAG `:9621`  
2. RAG MCP `:8008`（`uv run python start_rag_server.py`）  
3. Agent `:8090`  
4. 前端  

逐步命令与检查清单 → 实现说明。勿在本文复制长 bash。

### 12.9 使用与查询模式建议

| 场景 | 建议 mode | 说明 |
|------|-----------|------|
| 综合 FAQ | `mix` | 默认，覆盖面最好 |
| 明确段落检索 | `naive` / `local` | 偏向量局部 |
| 需要实体关系 | `rag_graph_search` 或 `global` | 运营策略类 |

### 12.10 故障排查（设计层）

| 现象 | 排查方向 |
|------|----------|
| 工具列表无 rag_* | 开关是否 true；MCP 是否监听；loader 缓存是否脏 |
| 有工具无结果 | LightRAG 是否已索引文档；mode 是否过窄 |
| 登录失败 | `RAG_*` 凭证 / API Key；LightRAG 认证开关 |
| 仅业务可用 | **预期降级**：查 MCP/LightRAG 日志，不必先重启 Agent |

### 12.11 文件清单（设计视图）

| 路径 | 角色 |
|------|------|
| `src/mcp_servers/rag_server.py` | MCP 工具实现 |
| `src/tools/rag_loader.py` | Agent 侧 MCP Client |
| `src/middleware/rag_context.py` | 动态注入 |
| `start_rag_server.py` | 进程入口 |
| `workspace/*/skills/rag-query/` | 技能规范 |

### 12.12 知识分层与安全（设计）

| 层级 | 示例 | 可见范围建议 |
|------|------|--------------|
| 对客 FAQ | 退换货、运费 | Customer + 可对 Admin |
| 运营内部 | 选品策略、指标口径 | **仅 Admin 知识库或权限** |
| 敏感 | 成本、供应商条款 | 默认不进 RAG |

若 LightRAG 单库混布，需运营流程保证「不对客文档」不入库，或分实例。Agent 开关不能替代文档 ACL。

### 12.13 RAG 与业务工具冲突消解

| 用户问题 | 优先 |
|----------|------|
| 「我的订单呢」 | 业务工具（实时数据） |
| 「七天无理由怎么算」 | RAG（政策） |
| 「推荐手机」 | 推荐工具，而非 RAG |
| 「库存预警怎么定」 | Admin RAG + 可读业务只读查询 |

Skill 应写明优先级，避免 LLM 用过期文档回答实时订单。

### 12.14 可用性目标

| 指标 | 目标（设计） |
|------|--------------|
| MCP 挂掉 | 主对话可用 |
| LightRAG 慢 | 工具超时返回错误文案，不卡死整图 |
| 开关默认 | false，降低无意耗时与成本 |

---

## 13. LLM 健康检查（设计层）

### 13.1 问题

前端「在线」若写死，无法反映 DashScope / API Key / 模型配置真实可达性。

### 13.2 架构

```
ChatPanel / AdminLayout
  → useLlmHealth：约 30s 轮询 GET /api/v1/llm/health
        │
        ▼
Agent 自定义路由 health.py
  → 最小 chat completions（max_tokens=1）探测 DashScope
  → 模块级缓存约 10s，减少重复耗 token
        │
        ▼
返回 { llm_reachable, latency_ms, model, detail, cached, ... }
  → 前端 online | offline | checking
```

### 13.3 设计决策

| 决策 | 理由 |
|------|------|
| 用 chat 而非仅 `/models` | 部分兼容实现 `/models` 不验 Key |
| 服务端短缓存 | 多组件同时轮询时合并探测 |
| 前端 30s | 状态低频变化，平衡流量 |
| 端点不可达也算离线 | 覆盖 Agent 进程宕机 |
| 异常不抛 500 | 始终 JSON，便于 UI 绑定 |

实现与组件绑定 → 实现说明。

---

## 14. 部署设计

### 14.1 原则

| 原则 | 说明 |
|------|------|
| Agent 独立进程 | 与 Java 微服务解耦；经 Gateway 访问业务 |
| 可选能力可关 | RAG / 画像 Redis 故障时主对话仍可用（降级） |
| 配置外置 | 密钥只在 `.env`，不进镜像层明文文档 |
| 开发态落盘 | `.langgraph_api/` 本地可恢复；生产换托管 Checkpoint |

### 14.2 逻辑启动顺序

1. 基础设施：MySQL / Redis / Nacos / RabbitMQ  
2. Java 微服务（含 Gateway、`GET /users/me`）  
3. （可选）LightRAG → RAG MCP  
4. Agent Server  
5. 前端  

逐步命令、健康检查 URL、验收表 → 实现说明「部署指引」。

### 14.3 端口总览

| 服务 | 端口 | 备注 |
|------|------|------|
| hm-gateway | 8080 | 业务 + introspect |
| item / cart / user / pay / trade / search / … | 8081+ | 以仓库配置为准 |
| Agent（LangGraph） | 8090 | |
| LightRAG | 9621 | 可选 |
| RAG MCP | 8008 | 可选 |
| 前端 Vite | 5173（常见） | 以前端配置为准 |

### 14.4 环境分级建议

| 环境 | Checkpoint | RAG | 画像 Redis |
|------|------------|-----|------------|
| 本地开发 | inmem 落盘 | 可选 | 共用开发 Redis db=0 |
| 联调 | 同上 | 建议开 | 与后端同实例 |
| 生产 | 托管 DB（规划） | 独立资源 | 高可用 Redis；注意前缀 |

### 14.5 依赖健康依赖链

```
前端在线 ≠ LLM 在线 ≠ Gateway 在线 ≠ RAG 在线
```

产品展示可分层：Agent 进程存活、LLM 可达、知识库可达。当前实现以 LLM health 为主信号；RAG 失败静默降级。

### 14.6 回滚与兼容

- Agent 版本应相对 Java API **向后兼容**（只增工具不改旧语义）。  
- `graph` 名 `customer_agent` / `admin_agent` 视为外部契约，前端写死依赖。  
- 废止 Redis Checkpoint 后，勿再要求运维开通 db=1 会话库。

---

## 15. 与 nova-mall-agent 的差异适配

| 维度 | nova-mall-agent（对标） | hmall 适配 |
|------|-------------------------|------------|
| 商城 API | nova 自有网关与路径 | 一律映射 hmall Gateway 路径 |
| 优惠券 / 售后 | 常有完整工具集 | **暂无**；工具表更短 |
| 管理端 | 可能含写操作 | **强制只读** + PermissionMiddleware |
| 认证 | 单 Token 或不同 claim | **双 JWT** + introspect 双路径 |
| 推荐 / 画像 | 视版本 | hmall 已落推荐 + Redis 画像共享 |
| Checkpoint | 视部署 | hmall 开发态 inmem 落盘 |

适配策略：**保留 DeepAgent 三级路由与 Skills 模式**，替换工具 API 与鉴权，删除不存在的业务域工具，而不是 fork 整套调度器。

---

## 16. 演进路线与状态表

| 能力 | 状态 | 说明 |
|------|------|------|
| 三级路由 + 双 Agent | ✅ | Customer / Admin |
| 双 JWT + introspect + owner 多租户 | ✅ | |
| Checkpoint inmem + `.langgraph_api` | ✅ | 废止 Redis Checkpoint 表述 |
| 个性化推荐 Phase 1 | ✅ | 工具 + Skill + `/recommend` |
| 用户画像 Phase 2 | ✅ | Redis `profile:`；后端写入端对齐 |
| RAG（LightRAG + MCP） | ✅ | §12 |
| LLM 健康检查 | ✅ | §13 |
| Layer 3 语义记忆工具 | ✅ | Store |
| 前端浏览埋点 `POST /behaviors` | ⏸ | 规划 |
| Item-CF / 向量召回 | ⏸ | Phase 3 向 |
| **主动通知** | ⏸ **规划中** | Part C2 |
| 优惠券 / 售后工具 | ⏸ | 依赖业务上线 |
| 正则规则 Nacos 动态加载 | ⏸ | |
| LangSmith 全链路 | ⏸ 可选 | |
| LLM 降级固定文案 | ⏸ | 实现说明已知问题 |

---

# Part B　个性化推荐设计

> 技术决策与模式以本文为权威。工具函数体、Formatter、SKILL 全文 → [实现说明 Part II](./hmall_Agent实现说明文档.md)。

## B1. 目标与差异定位

当前 Customer 已具备交易链路工具，但商品发现仍偏「用户会搜才找得到」。对话式推荐目标：

- 基于购买 / 加购等信号做个性化列表  
- **可解释理由**（LLM 组织，后端给 tags / basedOn）  
- 覆盖「猜你喜欢 / 看了又看 / 凑单」等对话场景  
- Agent 可组合偏好分析 + 搜索，而不仅是推荐 API 薄封装  

| 维度 | 传统推荐 | Agent 对话式推荐 |
|------|----------|------------------|
| 触发 | 页面自动 | 用户问 / Agent 顺势 / 偏好推理 |
| 解释 | 常黑盒 | 自然语言理由 |
| 上下文 | 行为画像 | 对话 + 画像 + 实时意图 |
| 冷启动 | 热销兜底 | 热销 + **主动追问偏好** |
| 闭环 | 单向 | 推荐→反馈→再推荐 |

**原则**：算法在后端，**策略与交互在 Agent**；优雅降级；购买信号旁路采集不侵入支付事务。

## B2. 架构与数据流

```
对话「有什么推荐」/ 详情后 upsell / 预算咨询
        │
CustomerAgent
  · get_recommendations_api → GET /recommend
  · analyze_user_preferences → 画像优先，miss 则订单+购物车聚合
  · search_items_api（降级 / 偏好驱动）
        │
hm-gateway
        ├─ item-service /recommend（偏好→ES 召回→销量排序 / 热销兜底）
        └─（画像）Redis db=0 profile:{uid}:*
              ▲
              │ HINCRBY
        CartServiceImpl（cart） / paySuccessListener（purchase）
```

与三级路由关系：高频「猜你喜欢」走 **L1**；需上下文 `item_id` 的「看了又看」走 **L3**；复杂预算咨询走 **偏好工具 + 搜索**。

### B2.1 后端推荐管线（逻辑步骤）

1. 解析 `userId`（Gateway 已认证）与 scene。  
2. 取偏好：Redis 画像 TopN → miss 则 Feign 已购聚合。  
3. 召回：ES 按类目/品牌过滤，status 上架，排除已购。  
4. 排序：销量等业务分。  
5. 附 `recommendTags` / `basedOn`。  
6. 失败：MySQL/ES 热销兜底或错误码给 Agent 降级。

### B2.2 Agent 不做什么

- 不自己算 Item-CF 矩阵。  
- 不直连 ES。  
- 不在 Prompt 里写死商品 ID 列表冒充推荐。  

## B3. Agent 侧规格（非源码）

| 项 | 规格 |
|----|------|
| `get_recommendations_api` | 参数 scene / size / item_id；需登录；格式化列表 + basedOn |
| `analyze_user_preferences` | 需登录；命中画像 0 次 Gateway；miss 降级实时聚合并回写 |
| Formatter | `format_recommendations` / `format_preferences` |
| Skill | `personalized-recommendation`：四类场景工作流 + 理由规则 |
| Prompt | 能力声明、upsell 引导、冷启动追问 |
| 正则 | 「推荐\|猜你喜欢\|有什么好\|帮我选」等 → scene=home |

实现与注册表 → 实现说明 Part II。

## B4. 三种触发模式

### 模式 A：用户主动请求

L1 命中 → `get_recommendations_api(home)` → 列表（可含后端 tags）。零 LLM 成本。

### 模式 B：Agent 主动 Upsell

用户看详情后，LLM 按 Skill/Prompt **顺势**调 `get_recommendations_api(detail, item_id)`，附加搭配话术。

### 模式 C：偏好驱动

LLM 先 `analyze_user_preferences`，再结合预算等调 `search_items_api`，生成解释性推荐——**不完全依赖** `/recommend`。

| 维度 | A | B | C |
|------|---|---|---|
| 触发 | 用户 | Agent | Agent 推理 |
| 层 | L1 | L3 | L3 多工具 |
| 成本 | 零 LLM | 1 次推理 | 1–2 次 |

## B5. 冷启动

| 场景 | Agent | 后端 |
|------|-------|------|
| 未登录 | 提示登录后个性化 | 401 |
| 无历史 | 展示热销 + 追问喜好 | 热销榜 / basedOn 空 |
| 接口失败 | 降级搜索提示 | 错误 |
| 画像空 | 同无历史 | miss 路径 |

## B6. 后端 API 需求

### `GET /recommend`

| 参数 | 说明 |
|------|------|
| scene | home / detail / cart |
| size | 默认 10 |
| itemId | detail 时种子商品 |

响应需含商品列表字段（id/name/price/stock/brand/category/sold/recommendTags）及可选 `basedOn.topCategories/topBrands`。

**演进**：Phase1 Feign 聚合已购 → ES 召回 → 销量排序；Phase2 优先读 Redis 画像；Phase3 Item-CF / 向量。

> 实现偏差：跨库不可 SQL JOIN，改为 trade Feign `purchased-items` + item 侧补全。权威偏差说明见实现说明。

### `POST /behaviors`（规划）

原设计：浏览埋点写 MQ。现行 **加购/购买已由后端直写画像**；浏览埋点仍可选。不必再为 cart/purchase 强制 MQ。

### Redis 画像（与 Part C 一致）

`profile:{uid}:events|categories|brands|prices|stats`；`cf:` / `rec:` 缓存仍为后续。

## B7. 前端（契约级）

| 项 | 设计 |
|----|------|
| 快捷语 | 「有什么推荐」「猜你喜欢」 |
| 浏览埋点 | ProductDetail onMounted → 规划 |
| 卡片 | 列表 Markdown 即可；`[ID:xxx]` 可后续做跳转增强 |

## B8. 推荐闭环

推荐 → 看详情 → 再 upsell；或「更便宜」→ 搜索；或加购 → scene=cart 凑单；或「不喜欢该品牌」→ 排除重推。Thread 历史支撑指代消解。

## B9. 技术决策（权威，保留）

### B9.1 理由由 LLM 生成而非后端模板

后端模板「同类目热销」生硬；LLM 可结合对话（刚看过的型号、预算）组织句子。后端 tags 作为硬约束，减少幻觉。

### B9.2 偏好分析不强制独立画像 HTTP

Agent 读 Redis / 降级聚合即可；减少服务个数。后端推荐服务直接读同一 Redis，而不是再暴露 `/profile`（可后续加）。

### B9.3 推荐需登录

个性化依赖用户数据；未登录引导登录或走搜索，避免「匿名伪个性化」。

### B9.4 L1 vs L3 分流

home 场景参数稳定适合正则；detail 依赖上下文 item_id，正则脆弱，交给 LLM。

### B9.5 Phase1 算法克制

SKU/用户少时 CF 稀疏；Content-Based + 热销已够验证对话闭环。

### B9.6 行为写入后端化

支付与加购是确定性业务事件，放在 Java 旁路最稳，且覆盖非 Agent 入口；Agent 双写会导致得分翻倍。

### B9.7 决策一览表

| # | 决策 | 状态 |
|---|------|------|
| 1 | LLM 生成理由 | ✅ 采纳 |
| 2 | 画像优先分析 | ✅ 采纳 |
| 3 | 推荐需登录 | ✅ 采纳 |
| 4 | home→L1 / detail→L3 | ✅ 采纳 |
| 5 | Phase1 无 CF/向量 | ✅ 采纳 |
| 6 | 后端写 purchase/cart | ✅ 采纳（相对早期 MQ 方案的实现对齐） |

## B10. Phase 与风险

| 步骤 | 内容 | 状态 |
|------|------|------|
| 1–6 | Agent 工具/Skill/Prompt/正则 + GET /recommend | ✅ |
| 7 | 后端写 purchase/cart 画像 | ✅（直写 Redis，非 MQ） |
| 8 | 前端 view 埋点 | ⏸ |
| 9 | 画像共享读 | ✅ |
| 10 | Item-CF | ⏸ |

| 风险 | 规避 |
|------|------|
| 数据稀疏 | 热销 + 追问 |
| 接口延迟 | 缓存 / 降级搜索 |
| 理由不准 | tags 约束 |
| 埋点影响页 | 异步 + 静默失败 |

---

# Part C　用户画像与主动通知

## C1. 用户画像 Layer 1–3（权威设计）

> **状态：Phase 2 已落地。** 早期「Agent 写操作直写 + MQ 旁路」已与实现对齐为：**后端 CartService + paySuccessListener 写入**；Agent 负责读加速与 miss 回写。

### C1.1 背景：Phase 1 双重计算问题

Phase 1 存在两端互不知晓的全量重算：

- 后端 `RecommendServiceImpl`：每次推荐 Feign 取已购再聚合  
- Agent `analyze_user_preferences`：每次拉订单+购物车再聚合  

目标：共享增量画像，命中时偏好分析 **0 次 Gateway**；推荐服务可选同读。

### C1.2 三层体系

```
Layer 1  实时行为流   Redis List   最近 50 条，TTL 7d     回溯 / 修正
Layer 2  聚合画像     Redis Hash   类目/品牌得分等 TTL 30d  推荐与偏好读取
Layer 3  语义记忆     LangGraph Store  跨会话意图文案       对话个性化
```

结构化画像回答「喜欢什么」；语义记忆回答「说过要买什么」。

### C1.3 Redis Key（db=0，前缀 profile:）

| Key | 结构 | 说明 |
|-----|------|------|
| `profile:{uid}:events` | List | 行为流 |
| `profile:{uid}:categories` | Hash | 类目 → 得分 |
| `profile:{uid}:brands` | Hash | 品牌 → 得分 |
| `profile:{uid}:prices` | List | 最近价格 |
| `profile:{uid}:stats` | Hash | 计数与 last_update |

> **注意**：画像在 **db=0**，与 Checkpoint **无关**。旧文「与 Checkpoint 同库 db=1」作废。

### C1.4 权重（与 Phase 1 聚合一致）

| 行为 | 权重 | 现行写入端 |
|------|------|------------|
| purchase | 5 | `paySuccessListener`（支付成功） |
| cart | 3 | `CartServiceImpl.addItem2Cart` |
| view | 1 | 规划（前端埋点） |

数学要求：画像命中路径与 miss 实时聚合路径权重一致，保证结果可对齐。

### C1.5 读写路径（对齐实现）

```
analyze_user_preferences
  → get_profile 命中？ → 直接 format
  → miss → Phase1 聚合 → 异步 backfill_profile

加购（任意入口）→ CartService → HINCRBY cart
支付成功 → paySuccessListener → HINCRBY purchase

Agent 工具不再对 cart/purchase 重复 record_event（防双写）
```

**Java 侧约束**：使用 `StringRedisTemplate` 明文 field，避免与 Agent `redis.asyncio` 的 Jackson 序列化不兼容。

### C1.6 ProfileStore API（规格）

| 方法 | 语义 |
|------|------|
| `record_event(...)` | Layer1+2 增量（管道 + TTL） |
| `get_profile` | 聚合读取；空则调用方降级 |
| `top_categories` / `top_brands` | TopN |
| `invalidate` | 用户清除 / 修正 |
| `backfill_profile` | miss 后回写 |

### C1.7 Layer 3 记忆工具

| 工具 | 语义 |
|------|------|
| `save_memory(key, value)` | Store namespace `user_memory` + user_id |
| `get_memories` | 检索近期记忆供 LLM 自然融入 |

Store 类型由 `graph.json` `in_memory` 配置；开发态随 `.langgraph_api` 落盘策略由运行时管理。Prompt 要求：开场可读记忆、未完成意图要存、完成或放弃要清理、禁止生硬复述。

### C1.8 后端推荐共享

`RecommendServiceImpl.recommend()`：**优先 HGETALL 画像**，miss 降级原 Feign 聚合。排除已购列表仍可走 Feign（画像不存明细）。

### C1.9 实现偏差摘要（设计已知）

1. 写入端从「Agent 直写」改为「后端双入口」，覆盖 UI 加购。  
2. 取消强制 `POST /behaviors` + MQ 作为 purchase/cart 主路径。  
3. Store 经 graph.json 注入，不必改 `create_agent(store=)`。  
4. `update_cart_quantity` / 确认收货不写偏好（防噪声与双记）。  

细节 → 实现说明。

### C1.10 一致性与并发

- 使用 `HINCRBY` 保证并发加购/支付下得分不互相覆盖。  
- Agent backfill 与后端增量可能短暂并存：可接受最终近似；必要时 `invalidate` 后重建。  
- TTL 滑动刷新：有写入即续期，避免活跃用户画像突然消失。

### C1.11 隐私与合规设计

| 要求 | 做法 |
|------|------|
| 最小化 | 只存聚合，不存完整地址/支付账号 |
| 可清除 | `invalidate` + 产品入口（规划） |
| 隔离 | Key 含 userId；禁止管理端工具直接扫全库画像 |
| 日志 | 不对齐输出完整偏好 JSON 到公开日志 |

### C1.12 画像命中率与容量

| 议题 | 设计看法 |
|------|----------|
| 冷用户 | miss 降级，不阻塞 |
| 大 Hash | TopN 读取即可；不必一次拉全历史事件做推荐 |
| Redis 内存 | TTL + LTRIM 限制 List 长度 |

### C1.13 端到端时序：加购后推荐

```
用户加购（UI 或 Agent）
  → CartService 写 profile cart 权重
  → 用户：「根据购物车推荐」
  → get_recommendations_api(scene=cart) 或 analyze + search
  → 后端读 categories/brands → ES 召回
  → LLM（若 L3）解释理由
```

### C1.14 端到端时序：支付后偏好变化

```
支付成功 MQ → paySuccessListener
  → HINCRBY purchase 权重 5
  → 下次 analyze_user_preferences 命中即反映新类目/品牌
```

无需等 Agent 会话仍在线。

---

## C2. 主动通知（规划中）

> **状态：规划中，尚未落地。** 以下保留设计，供后续排期；实现时以独立 SSE 通道为准，并回写状态表。

### C2.1 背景与原则

纯被动对话无法覆盖支付成功安心感、超时取消告知、秒杀提醒等。后端已有 RabbitMQ 事件时，Agent 侧补 **消费 + 推送**。

| 原则 | 说明 |
|------|------|
| 独立通道 | **不**写入对话 Thread，避免污染 LLM 上下文 |
| 双模式 | A 模板轻量；B LLM 个性化（依赖画像） |
| 离线可恢复 | Redis 暂存，上线补发 |
| 幂等 | `SETNX notify:sent:{type}:{id}` |
| 频控 | 每用户每小时上限；high 优先 |

### C2.2 架构

```
Java 业务事件 ──MQ──► EventConsumer ──► NotificationDispatcher
                                            │ 在线 SSE
                                            │ 离线 Redis list
                                            ▼
                                       前端铃铛 / 面板
```

### C2.3 双模式

| 模式 | 场景 | 延迟 / 成本 |
|------|------|-------------|
| A 模板 | 支付成功、超时取消、物流 | <200ms / 0 token |
| B LLM | 秒杀提醒、降价、运营推送 | ~2–3s / 少量 token；失败降级模板 |

### C2.4 事件注册表（规划）

| 事件 | MQ（示例） | 模式 |
|------|------------|------|
| pay_success | pay.direct / pay.success | A |
| order_timeout | trade.delay / delay.order | A |
| seckill_start | 需后端新增 | B |
| logistics_update | 需新增 | A |
| price_drop | 需新增 | B |

### C2.5 SSE 契约（规划）

- `GET /api/v1/notifications/stream`（生产应从 JWT 解析 user，禁止仅 query 传 uid）  
- 连接时 `flush_offline` → 持续推送 `event: notification`  
- 前端 `EventSource` + 断线退避重连；铃铛未读数 + 面板 action 跳转  

### C2.6 模块规划清单

`notification/{models,consumer,rules,dispatcher,api}.py`；`start_server` 注册路由与 consumer 生命周期；依赖 `aio-pika` / `sse-starlette`；前端 `useNotifications`。

### C2.7 注意事项

1. 与 Thread 严格分离。  
2. 幂等与频控必备。  
3. 模式 B 依赖 Part C1 画像，可先交模式 A。  
4. 安全：禁止横向订阅他人通知流。  

### C2.8 推进计划（相对）

| 阶段 | 内容 | 依赖 |
|------|------|------|
| 已完成 | 画像 Layer1–3 + 推荐共享 | — |
| 规划 | 通知模式 A（支付/超时） | MQ |
| 规划 | SSE + 前端铃铛 | 模式 A |
| 规划 | 模式 B + 新事件 | 画像 + 后端事件 |

### C2.9 通知数据模型（规划）

| 字段 | 含义 |
|------|------|
| id | 全局唯一，幂等键组成部分 |
| user_id | 接收者 |
| type | payment_success / order_timeout / … |
| title / body | 展示文案 |
| action | 可选跳转（order_id 等） |
| priority | normal / high |
| created_at | 排序与过期 |

### C2.10 Redis 键规划（通知）

| Key | 用途 |
|-----|------|
| `notify:offline:{uid}` | 离线列表 |
| `notify:sent:{type}:{id}` | 幂等 |
| `notify:rate:{uid}` | 小时频控计数 |

与 `profile:` 前缀隔离；同属 db=0 时务必前缀分开。

### C2.11 为何不把通知写入 Thread

1. 系统消息会改变 LLM 上下文，导致「答非所问」或泄露模板口吻。  
2. interrupt 状态机与通知异步到达交织，难测。  
3. 用户可能在无会话时也需要支付成功提醒。  

### C2.12 模式 B 提示词约束（规划）

- 不超过约定字数。  
- 不得编造未在 context 中的价格/库存。  
- 画像缺失时退回模板。  
- 营销合规：避免绝对化承诺。  

### C2.13 测试场景（规划验收）

| 场景 | 期望 |
|------|------|
| 在线支付成功 | <1s 内铃铛+1 |
| 离线后上线 | 补发且不重复 |
| MQ 重投 | 仅一条通知 |
| 超频 | normal 丢弃，high 保留 |
| 伪造 user_id 订阅 | 拒绝 |

### C2.14 与现有 batch-report / health 的关系

通知 API 与 health、batch-report 同属自定义 HTTP 层，但**生命周期**更重（常驻 consumer）。启动失败策略需明确：通知模块挂掉是否阻断 Agent——建议 **可降级启动**（对话优先）。

---


---

#
## 附录 A. 文档修订说明（v2.3）

| 动作 | 说明 |
|------|------|
| 合并 RAG | 删除原「第四部分」全文副本，并入 Part A §12 |
| 压缩实现粘贴 | 去掉大段 tools/SKILL/Composable/.env/bash |
| 修正 Checkpoint | inmem + `.langgraph_api`；废止 Redis db=1 会话说 |
| 修正数量 | Customer ~20 业务+记忆 / Skills 7；Admin 11 / Skills 3 |
| 修正状态 | 推荐、RAG、画像已实现；主动通知规划中 |
| 交叉引用 | How → 实现说明；入门 → 项目说明 |

## 附录 B. 相关路径速查

| 主题 | 设计（本文） | 实现 |
|------|--------------|------|
| 系统架构 | Part A §2 | 实现说明 Part I |
| 推荐 | Part B | 实现说明 Part II |
| 画像 | Part C1 | 实现说明画像章节 |
| 通知 | Part C2（规划） | — |
| RAG | Part A §12 | 实现说明 RAG 节 |
| 入门 | — | 项目说明 |

---

## 附录 C. 横切设计专题（补充）

> 本章补充 Part A–C 未展开但对评审有用的横切议题，仍保持「设计层」表述。

### C-1. 超时、重试与幂等矩阵

| 调用 | 超时建议 | 重试 | 幂等关键 |
|------|----------|------|----------|
| Gateway 只读 | 短（数秒） | 可有限重试 | 是 |
| Gateway 写 | 短 | **默认不自动重试** | 依赖业务幂等 + interrupt 单次确认 |
| introspect | 更短 | 可重试 + 缓存 | 是 |
| LightRAG query | 中 | 有限 | 是 |
| LLM | 中长 | 框架层 | N/A |
| MQ 消费（规划） | — | 重投 | 必须 SETNX |

### C-2. 日志与隐私字段

| 可记 | 不可记 |
|------|--------|
| tool 名、耗时、status code | JWT 全文 |
| user_id（或哈希） | 完整收货地址 |
| thread_id 前缀 | 支付敏感号 |
| enable_rag 布尔 | RAG 原文若含内部策略（管理端日志分级） |

### C-3. 配置变更热更新边界

| 可变（期望） | 需重启 |
|--------------|--------|
| 正则规则进 Nacos（规划） | graph 注册名变更 |
| 部分 TTL | LLM 模型名（视加载方式） |
| RAG 开关（每请求 context） | MCP 端口 |

### C-4. 多实例部署含义

开发态 inmem Checkpoint **不能**跨多 worker 共享内存；多副本时必须换共享 Checkpointer（Postgres 等），否则 interrupt 会丢。画像 Redis 天然可共享。MCP/LightRAG 可水平扩展，Agent 侧 loader 缓存需考虑失效。

### C-5. 国际化与文案

当前产品文案以中文为主；设计要求错误提示在工具层固定语言，避免 LLM 切换语言导致前端正则/确认词失效（如确认词「确认」）。若未来 i18n，interrupt `expected_response` 需同步本地化。

### C-6. 测试金字塔（设计期望）

| 层 | 内容 |
|----|------|
| 单位 | Formatter、正则、权重聚合 |
| 契约 | Gateway 路径与 R\<T\> 解包 |
| 集成 | introspect、推荐 miss/hit、RAG 降级 |
| 端到端 | L1 秒杀列表、L2 确认、推荐闭环 |

具体用例表见实现说明测试节。

### C-7. 性能预算（经验目标）

| 路径 | 预算 |
|------|------|
| L1 只读 | 毫秒～数十毫秒级（不含下游 Java） |
| 含 Gateway | 视 Java P99 |
| L3 单轮 | 秒级（模型） |
| 推荐 L1 | 接近只读 + /recommend |
| RAG | 高于纯业务，故默认关 |

### C-8. 降级总表

| 依赖失败 | 降级 |
|----------|------|
| LLM | 健康检查离线；固定文案（规划） |
| Gateway | 工具错误提示 |
| 画像 Redis | miss 路径实时聚合 |
| `/recommend` | 搜索提示 / 热销 |
| MCP/LightRAG | 去掉 RAG 工具 |
| Checkpointer 盘满 | 运维告警；拒绝新会话优于静默丢确认 |

### C-9. 版本兼容策略

- 前端 SDK 大版本升级需回归 context/command。  
- 新增 context 字段必须带默认值。  
- 废弃工具先 Skill/Prompt 停止引导，再移除注册。  

### C-10. 文档自身维护规则

1. 改架构 / 契约 → 改本文。  
2. 改文件路径 / 命令 / 偏差 → 改实现说明。  
3. 改「项目是什么」叙事 → 改项目说明。  
4. 状态变更 → 更新 Part A §16。  

---

## 附录 D. 场景设计册（扩展）

### D1. 新用户首购

1. 未登录浏览 → 搜索/详情无需 token。  
2. 询问推荐 → 提示登录。  
3. 登录后猜你喜欢 → 热销 + 追问偏好。  
4. 加购 → CartService 写画像。  
5. 支付 → purchase 权重写入。  
6. 再次推荐 → 个性化增强。  

### D2. 老用户指代消解

用户：「把上次那个地址的电话改了」。依赖 Thread 历史 + 地址列表工具；必要时 Store 中的意图记忆辅助。设计上不要求模型一次猜对 address_id，应列表确认。

### D3. 秒杀高峰

- L1 查活动减轻 LLM 压力。  
- 下单必须 interrupt，防止误触。  
- Gateway 限流错误应原样可读返回。  
- 不做 Agent 侧库存预扣。  

### D4. 运营早会

管理员打开管理端对话 → 「运营日报」L1 编排 → 对异常库存追问 → 可选 RAG 查「预警阈值建议」→ 全程无写。

### D5. 政策咨询与下单穿插

开启知识库 → 问退货政策（RAG）→ 再「查看订单」应走业务工具。Skill 优先级保证实时数据不被文档覆盖。

### D6. 推荐闭环驳回

用户连续「不要 Apple」。设计期望：LLM 在本 thread 内排除品牌；不必立刻改 Redis 画像（避免负反馈误伤），除非后续做显式负向事件。

### D7. interrupt 中途离开

用户确认框卡住离开。线程保持挂起；下次进入同 thread 应仍可见待确认，或产品选择超时取消。前端需能渲染历史 interrupt 状态（依赖 getState）。

### D8. Token 过期

introspect 失败 → 会话写操作拒绝；前端跳转登录；旧 thread 仍在但需新 Token 才能 run。

### D9. 管理端误用 C 端话术

即使用户说「帮我下单」，Admin Permission 下无下单工具，模型应拒绝并说明只读。

### D10. RAG 未建索引

工具可调用但空结果 → 提示「知识库暂无资料」，运营去 WebUI 上传，而不是 Agent 编造。

---

## 附录 E. 接口契约速查（设计）

### E1. introspect 响应（逻辑字段）

| 端 | 关键字段 |
|----|----------|
| `/users/me` | userId，agentType=customer |
| `/admin/info` | id 或 userId，管理员身份 |

### E2. `/recommend` 逻辑字段

list[]：id, name, price, stock, brand, category, sold, recommendTags  
basedOn：topCategories, topBrands  

价格单位与商城一致（分或元）——前后端约定以现网为准，Formatter 负责展示。

### E3. context 字段全集（现行）

| 字段 | 必填 | 说明 |
|------|------|------|
| agent_type | 是 | customer/admin |
| user_token | 业务需要时 | JWT |
| user_id | 常由中间件填 | 权威来自 introspect |
| enable_rag | 否 | 默认 false |

### E4. interrupt payload 逻辑字段

| 字段 | 说明 |
|------|------|
| type | confirmation / field_selection / value_input … |
| message | 展示文案 |
| expected_response | 可选，辅助前端 |

---

## 附录 F. 风险登记册（总册）

| ID | 风险 | 影响 | 缓解 | 状态 |
|----|------|------|------|------|
| R1 | 会话越权 | 高 | owner Auth | ✅ |
| R2 | Admin 写穿透 | 高 | Permission | ✅ |
| R3 | 画像双写加倍 | 中 | 后端唯一写入 | ✅ |
| R4 | Checkpoint 多副本内存分裂 | 高 | 单工人开发 / 生产换存储 | 设计已知 |
| R5 | RAG 幻觉政策 | 中 | Skill+来源；关键以官网为准 | 持续 |
| R6 | 推荐稀疏 | 中 | 热销+追问 | ✅ |
| R7 | 通知轰炸（规划） | 中 | 频控+幂等 | 规划 |
| R8 | 日志泄密 | 中 | 字段红线 | 持续 |
| R9 | SDK 0.x 丢 context | 高 | 锁定 1.x | ✅ |
| R10 | Redis 序列化不兼容 | 高 | StringRedisTemplate | ✅ |

---

## 附录 G. Phase 总图

```
已完成
  ├─ 核心 Agent / 安全 / 前端契约
  ├─ 推荐 Phase1
  ├─ 画像 Phase2（后端写入对齐）
  ├─ RAG
  └─ LLM Health

进行中 / 近顶
  └─（无强制）

规划
  ├─ 主动通知
  ├─ 浏览埋点
  ├─ Item-CF / 向量
  ├─ 优惠券/售后工具
  ├─ 正则 Nacos 化
  └─ 生产级 Checkpoint
```

---

## 附录 H. 术语表

| 术语 | 含义 |
|------|------|
| L1/L2/L3 | 正则 / interrupt / LLM 三级路由 |
| owner | `{agent_type}:{user_id}` 会话归属 |
| introspect | 经 Gateway/Admin 权威身份探查 |
| Checkpointer | 图状态持久化组件 |
| Store | 跨线程 KV（语义记忆） |
| Skill | SKILL.md 场景规范 |
| MCP | 模型上下文协议，此处桥接 LightRAG |
| basedOn | 推荐依据摘要 |
| interrupt | 图挂起等待人类输入 |
| profile: | 画像 Redis 前缀 |

---

## 附录 I. 评审常见问题（FAQ）

**Q1：为什么不把 Agent 挂到 Gateway 后面？**  
A：LangGraph Server 有独立鉴权与 SSE 模型；业务仍经 Gateway。introspect 对齐身份。

**Q2：为什么开发态不用 Redis Checkpoint？**  
A：inmem+落盘更简单；画像才用 Redis。生产再选托管存储。

**Q3：Customer 到底多少工具？**  
A：约 20 业务 + 记忆工具；以 `get_all_tools()` 为准。Skills 7。

**Q4：画像为什么必须后端写加购？**  
A：覆盖商城 UI 加购；避免仅 Agent 加购才有画像；并防双写。

**Q5：RAG 默认为何关闭？**  
A：降低延迟与费用；政策场景由用户显式打开。

**Q6：主动通知为何独立 SSE？**  
A：避免污染 Thread 与 LLM 上下文；支持无会话推送。

**Q7：设计文档和实现说明冲突听谁？**  
A：短期听代码+实现说明；并回写设计状态表。

**Q8：Admin 能否「仅 Prompt 禁止写入」？**  
A：不能。必须 Permission 剔工具，Defense in depth。

---

## 附录 J. 变更记录（文档）

| 日期 | 版本 | 摘要 |
|------|------|------|
| 2026-06 | v2.0 | DeepAgent 体系首版设计合并 |
| 2026-07 | v2.1–v2.2 | SDK1.x、推荐、画像、RAG、introspect |
| 2026-09 | v2.3 | 职责收敛、去重、RAG 单章、权威事实校正、废止第四部分副本 |

---

## 附录 K. 设计原则再声明（结语）

hmall Agent 的设计收敛为四句话：

1. **编排在 Python，业务在 Java**——Agent 零业务库。  
2. **快路正则，险路 interrupt，难路 LLM**——三级路由。  
3. **身份看 Gateway，会话看 owner，偏好看 profile**——三源各司其职。  
4. **可选能力可降级**——推荐、RAG、通知（规划）均不得绑架主交易对话。  

实现细节、命令与偏差清单，请移步 [hmall_Agent实现说明文档.md](./hmall_Agent实现说明文档.md)；入门叙事请见 [hmall_Agent项目说明文档.md](./hmall_Agent项目说明文档.md)。

---


## 附录 L. 对照表：旧表述 → 现行权威

| 旧文档常见表述 | 现行权威 |
|----------------|----------|
| Redis Checkpoint db=1 | inmem + `.langgraph_api/` 落盘 |
| Customer 18 工具 / Skills 5 | ~20 业务 + 记忆；Skills 7 |
| Admin 10 工具 | 11（含日报编排） |
| RAG 规划中 / 预留 | **已实现** |
| 商品推荐 P2 未做 | **已实现**（Phase1+画像） |
| 画像 Agent 直写 + MQ | 后端 CartService + paySuccessListener |
| 画像与 Checkpoint 同库 | 画像 **db=0**；Checkpoint 非 Redis |
| 第四部分 RAG 独立长文 | 仅 Part A §12 |
| 完整 .env / bash 手册在设计文档 | 迁出至实现说明 |

---

## 附录 M. 组件职责矩阵

| 组件 | 负责 | 不负责 |
|------|------|--------|
| CustomerAgent | 对话编排、工具选择、interrupt | 库存扣减真相源 |
| AdminAgent | 只读查询、日报 | 任何写库 |
| Gateway | 验签、路由、限流 | LLM 推理 |
| item-service | 商品与推荐召回 | 对话状态 |
| cart-service | 购物车 + cart 画像写入 | 会话 Thread |
| trade-service | 订单 + purchase 画像写入 | RAG 索引 |
| LightRAG | 知识检索 | 实时订单 |
| 前端 | SSE 展示、开关、确认 UI | 业务校验终局 |

---

## 附录 N. 中间件 × 场景矩阵

| 场景 | Auth | Permission | Regex | RAG | Skills | LLM |
|------|:----:|:----------:|:-----:|:---:|:------:|:---:|
| 未登录看秒杀 | ○ | ○ | 短路 | — | — | — |
| 登录猜你喜欢 | ○ | ○ | 短路 | — | — | — |
| 秒杀下单确认 | ○ | ○ | — | ○? | ○ | ○ |
| 政策问答（开库） | ○ | ○ | — | 注入 | ○ | ○ |
| 运营日报 | ○ | 剔写 | 短路 | — | — | — |
| Admin 被诱导下单 | ○ | 剔写 | — | ○? | ○ | ○（无工具） |

○=经过；短路=Regex 直接返回；—=未到达或无关。

---

## 附录 O. 数据归属一览

| 数据 | 主存 | 权威写入者 | 读者 |
|------|------|------------|------|
| 订单 | MySQL(trade) | trade-service | Agent 工具 |
| 购物车 | MySQL/Redis(cart) | cart-service | Agent 工具 |
| 会话消息 | Checkpointer | LangGraph | 前端/Agent |
| 语义记忆 | Store | Agent memory 工具 | Agent |
| 偏好得分 | Redis profile | Java 监听/服务 | Agent+Recommend |
| 知识切片 | LightRAG 存储 | 运营 WebUI | RAG 工具 |
| JWT | 客户端持有 | user/admin 登录 | Auth+Gateway |

---

## 附录 P. 设计约束清单（不可轻易打破）

1. Agent 不直连业务 MySQL。  
2. Admin 工具集不含写。  
3. 危险写必须 interrupt。  
4. userId 不以本地 JWT 解码为权威（默认）。  
5. threads 必须 owner 隔离。  
6. context-only 认证，禁用 configurable 并存。  
7. 画像 purchase/cart 不在 Agent 重复计分。  
8. RAG 失败不得阻断业务工具。  
9. 通知（规划）不得写入对话 Thread。  
10. 设计文档不承载完整部署命令与 .env 全文。  

---

## 附录 Q. 与项目说明 / 实现说明的边界示例

| 问题 | 该查 |
|------|------|
| 为什么用三级路由？ | 项目说明 / 本文 §2 |
| Regex 某条 pattern 原文？ | 实现说明 / 代码 |
| introspect 路径？ | 本文 §6（权威）+ 实现 |
| `.env` 全部键？ | 实现说明 |
| 如何 uv run 启动？ | 实现说明 |
| 推荐三种模式？ | 本文 Part B |
| Formatter 函数体？ | 实现说明 |
| 主动通知是否已做？ | 本文 §16 / C2（规划中） |

---

## 附录 R. Customer 工具分组与登录矩阵（完整规格）

### R.1 商品

| 工具 | 登录 | L1 候选 | interrupt |
|------|:----:|:-------:|:---------:|
| search_items_api | 否 | 是 | 否 |
| get_item_detail_api | 否 | 否 | 否 |
| get_item_page_api | 否 | 是 | 否 |

### R.2 秒杀

| 工具 | 登录 | L1 候选 | interrupt |
|------|:----:|:-------:|:---------:|
| get_seckill_activities_api | 否 | 是 | 否 |
| get_seckill_product_api | 否 | 否 | 否 |
| do_seckill_api | 是 | 否 | **是** |

### R.3 购物车

| 工具 | 登录 | L1 候选 | interrupt | 画像副作用 |
|------|:----:|:-------:|:---------:|------------|
| get_cart_list_api | 是 | 是 | 否 | 无 |
| add_to_cart_api | 是 | 否 | 否 | 后端 cart |
| update_cart_quantity_api | 是 | 否 | 否 | 无 |
| delete_cart_item_api | 是 | 否 | **是** | 无 |
| clear_cart_api | 是 | 否 | **是** | 无 |

### R.4 订单

| 工具 | 登录 | L1 候选 | interrupt | 画像副作用 |
|------|:----:|:-------:|:---------:|------------|
| get_order_list_api | 是 | 是 | 否 | 无 |
| get_order_detail_api | 是 | 条件 | 否 | 无 |
| cancel_order_api | 是 | 否 | **是** | 无 |
| confirm_receive_api | 是 | 否 | **是** | 购买记在支付监听 |

### R.5 地址

| 工具 | 登录 | L1 候选 | interrupt |
|------|:----:|:-------:|:---------:|
| get_address_list_api | 是 | 是 | 否 |
| add_address_api | 是 | 否 | **多轮** |
| update_address_api | 是 | 否 | **多轮** |

### R.6 推荐与记忆

| 工具 | 登录 | L1 候选 | 说明 |
|------|:----:|:-------:|------|
| get_recommendations_api | 是 | home 是 | Part B |
| analyze_user_preferences | 是 | 否 | 画像优先 |
| save_memory | 是 | 否 | Store |
| get_memories | 是 | 否 | Store |

### R.7 动态 RAG（非 get_all_tools 静态表）

| 工具 | 注入条件 |
|------|----------|
| rag_query / rag_query_data / rag_graph_search | enable_rag=true 且 MCP 可用 |

---

## 附录 S. Admin 工具完整规格

| 工具 | 只读 | L1 候选 | 备注 |
|------|:----:|:-------:|------|
| admin_get_product_page_api | 是 | 是 | |
| admin_get_product_detail_api | 是 | 否 | |
| admin_get_order_page_api | 是 | 是 | |
| admin_get_order_detail_api | 是 | 否 | |
| admin_get_seckill_promotion_page_api | 是 | 是 | |
| admin_get_seckill_relation_page_api | 是 | 否 | |
| admin_get_seckill_order_page_api | 是 | 否 | |
| admin_get_seckill_stock_api | 是 | 否 | |
| admin_get_user_page_api | 是 | 否 | |
| admin_get_user_detail_api | 是 | 否 | |
| generate_daily_report | 是 | **是** | 编排五路并发 |

---

## 附录 T. 推荐 scene 语义

| scene | 含义 | 典型触发 | 是否需 itemId |
|-------|------|----------|:-------------:|
| home | 猜你喜欢 / 首页式 | L1「推荐」 | 否 |
| detail | 看了又看 | 详情后 Upsell | 是 |
| cart | 凑单 | 加购后 | 否（可参考车内） |

后端可对未知 scene 回退 home 或 400——实现需明确；设计建议回退 home 并打日志。

---

## 附录 U. 画像事件字典

| event_type | 权重 | 触发源（现行） | 备注 |
|------------|------|----------------|------|
| purchase | 5 | paySuccessListener | 强信号 |
| cart | 3 | CartServiceImpl | 覆盖 UI+Agent |
| view | 1 | 规划埋点 | 弱信号 |
| favorite | 4 | 未实现 | 预留 |
| negative | — | 未实现 | 口头「不喜欢」暂仅 thread 内 |

---

## 附录 V. LightRAG 查询模式选用指南（设计）

| mode | 适用 | 不适用 |
|------|------|--------|
| mix | 默认综合问答 | 需要纯图谱遍历时 |
| local | 局部实体邻域 | 宏观总结 |
| global | 主题级总结 | 精确条款 |
| hybrid | 折中 | — |
| naive | 近似向量检索 | 关系推理 |
| bypass | 调试/直通 | 生产默认 |

运营文档应在 Skill 中给出「退换货用 mix、策略综述可用 global」等提示，避免每次由模型随机选 mode。

---

## 附录 W. 前端状态机（对话页）

```
Idle
  ├─ sendMessage → Streaming
  │     ├─ partial 更新 → Streaming
  │     ├─ complete → Idle
  │     ├─ interrupt → AwaitingHuman
  │     └─ error → Idle（可重试）
  ├─ AwaitingHuman
  │     ├─ resume → Streaming
  │     └─ cancel/goto end → Idle
  └─ switchThread → 加载 state → Idle
```

与 LLM health 正交：`checking/offline` 只影响状态文案，不自动禁发（产品可另定）。

---

## 附录 X. 安全滥用用例

| 用例 | 期望 |
|------|------|
| 枚举 thread_id | search/read 403 |
| C 端 Token 调 admin_agent | introspect/类型不匹配失败 |
| Admin Token 调 do_seckill | 工具不存在 |
| enable_rag 探测内部文档 | 依赖知识库 ACL；开关≠授权 |
| 改 resume 篡改价格 | 价格以工具内已查详情为准，resume 只作确认词 |
| 伪造 Authorization | introspect 拒绝 |

---

## 附录 Y. 可观测性指标（建议）

| 指标 | 用途 |
|------|------|
| L1 命中率 | 优化正则 |
| interrupt 完成率/取消率 | 确认文案是否清晰 |
| 推荐点击后加购率（需埋点） | 推荐质量 |
| 画像命中率 | Redis 价值 |
| RAG 调用占比与空结果率 | 知识运营 |
| introspect 延迟/失败率 | 身份链路 |
| LLM health 失败次数 | 模型可用性 |

LangSmith 可选接入后，按 tool 名与 agent_type 切片。

---

## 附录 Z. 后续设计开放问题（已知未决）

1. 生产 Checkpoint 选 Postgres 还是其他？TTL 策略？  
2. 浏览埋点是否仍走 `/behaviors` 还是直接写 Redis？  
3. 负反馈是否落入画像？  
4. 通知 SSE 鉴权是否复用 LangGraph Auth 中间件？  
5. 多模态（图搜同款）的工具边界？  
6. 优惠券上线后 L2 确认模板？  

开放问题不阻塞现行已实现能力；立项时回写本文对应章。

---

## 附录 AA. 架构决策记录（ADR 摘要）

### ADR-001 采用 DeepAgents + LangGraph

- **上下文**：自建 Agent 循环与状态机成本高。  
- **决策**：DeepAgents create_agent + LangGraph Server。  
- **后果**：获得 interrupt/Checkpoint/Studio；需遵循 SDK context 契约。  

### ADR-002 Gateway introspect 为 userId 权威

- **上下文**：Agent 不在 Gateway 后，收不到 user-info。  
- **决策**：`/users/me` 与 `/admin/info` 探查。  
- **后果**：多一次 RTT；可用短缓存；默认不 fallback 解码。  

### ADR-003 开发态 inmem Checkpoint

- **上下文**：曾规划 Redis db=1。  
- **决策**：inmem + `.langgraph_api`。  
- **后果**：单机友好；多副本需另案。  

### ADR-004 画像后端写入

- **上下文**：仅 Agent 写会漏 UI 加购并易双写。  
- **决策**：CartService + paySuccessListener。  
- **后果**：Java 与 Python 需共享 Key/权重约定。  

### ADR-005 RAG 经 MCP 动态注入

- **上下文**：常驻 RAG 工具污染工具表、增加误调。  
- **决策**：enable_rag 开关 + Middleware 注入。  
- **后果**：需独立 MCP 进程；失败可降级。  

### ADR-006 Admin 纯只读

- **上下文**：运营误操作风险。  
- **决策**：Permission 剔除写工具 + 产品定位只读。  
- **后果**：改价/上下架仍走原后台页面。  

### ADR-007 通知独立 SSE（规划）

- **上下文**：事件推送与对话混会污染状态。  
- **决策**：独立通道。  
- **后果**：前端多一条连接；鉴权需单独设计。  

---

## 附录 AB. 容量与扩展粗算（设计级）

| 资源 | 粗算关注点 |
|------|------------|
| Checkpoint 磁盘 | 每 thread 消息数 × 用户活跃会话 |
| 画像 Redis | 用户数 × Hash 字段；TTL 控制 |
| LLM Token | L1 命中率越高越省；RAG/模式B 通知最费 |
| MCP | 连接数与 LightRAG 并发 query |
| Gateway | Agent 工具放大系数（日报五路并发） |

正式容量规划需压测，不在本文给出绝对值。

---

## 附录 AC. 失败注入测试建议（设计）

| 注入 | 期望观测 |
|------|----------|
| 关掉 Redis 画像 | 推荐/偏好仍可用（降级） |
| 关掉 MCP | 对话可用，无 rag 工具 |
| 关掉 DashScope | health 离线；对话失败或降级 |
| Gateway 5xx | 工具错误文案 |
| 错误 JWT | 无法建受保护会话 |
| 磁盘满（.langgraph_api） | 告警；拒绝优于静默 |

---

## 附录 AD. 文档内导航（Part 速览）

| 你想了解 | 去 |
|----------|----|
| 总架构图 | A§2 |
| 工具规格 | A§3–4、附录 R/S |
| 记忆与多租户 | A§5 |
| 安全 | A§6 |
| 前端契约 | A§10 |
| RAG | A§12 |
| 推荐模式与决策 | Part B |
| 画像三层 | Part C1 |
| 通知规划 | Part C2 |
| 状态是否已做 | A§16 |

---

## 附录 AE. 完整性自检

- [x] 无「第四部分 RAG」第二副本  
- [x] Checkpoint 表述为 inmem 落盘  
- [x] 画像 db=0 + profile:  
- [x] introspect 双路径  
- [x] 数量与 Skills 对齐权威事实  
- [x] 推荐/RAG/画像已实现；通知规划中  
- [x] 实现代码大段已移除，改为规格表与交叉引用  
- [x] 写入端对齐 CartService + paySuccessListener  

---

## 附录 AF. 端到端时序：登录到首购（设计）

```
1. 用户登录 C 端 → 获 JWT
2. 打开 /portal/chat → Client 带 Authorization
3. Auth introspect GET /users/me → owner=customer:{uid}
4. threads.create（metadata.owner）
5. 「查看秒杀」→ L1 → 活动列表
6. 「秒杀某商品」→ L3 抽参 → interrupt → resume → 下单
7. 支付成功 → paySuccessListener 写 purchase 画像
8. 「有什么推荐」→ L1 → /recommend 读画像 → 个性化列表
```

---

## 附录 AG. 端到端时序：管理端日报 + RAG

```
1. 管理员登录 → admin JWT
2. /admin/chat → introspect GET /admin/info → owner=admin:{id}
3. 「运营日报」→ L1 → generate_daily_report 并发五查询
4. 打开知识库开关 → enable_rag=true
5. 「库存预警阈值怎么定？」→ RAGMiddleware 注入 → rag_query
6. 全程无写工具可选
```

---

## 附录 AH. Prompt 分层设计（契约）

| 层 | 内容 | 变更频率 |
|----|------|----------|
| SYSTEM_PROMPT | 人格、红线、能力清单 | 低 |
| Skills | 场景步骤 | 中 |
| 工具 description | 参数与何时调用 | 中 |
| Formatter 输出 | 结构化展示 | 中 |
| 前端快捷语 | 引导高频意图 | 高 |

禁止把整份运营手册塞进 SYSTEM_PROMPT；长知识走 RAG。

---

## 附录 AI. Formatter 设计原则

1. 空列表 → 固定友好句，不抛异常。  
2. 价格展示与商城单位约定一致。  
3. 推荐输出保留 `[ID:xxx]` 便于指代与后续跳转。  
4. 管理端解包后的字段缺失显示「—」。  
5. 不在 Formatter 内二次请求网络（除明确设计的补充查询）。  

实现函数体 → 实现说明。

---

## 附录 AJ. GatewayClient 设计约束

| 约束 | 说明 |
|------|------|
| 基址 | `JAVA_GATEWAY_URL` |
| Header | 透传用户 JWT |
| 管理端 | 自动解包 `R<T>` |
| 错误 | 转为工具可读字符串 |
| 禁止 | 在 Client 内写死 userId 绕过鉴权 |

---

## 附录 AK. 正则规则治理

| 规则 | 说明 |
|------|------|
| 分端维护 | customer/regex_rules 与 admin 分离 |
| 写操作不进 L1 | 强制 |
| 冲突时 | 先匹配先生效；应用单测锁序 |
| 动态化 | 规划进 Nacos，变更需热加载设计 |

---

## 附录 AL. 与 hmall 微服务版本耦合

Agent 工具是**适配层**：Java API 变更时优先改工具与 Formatter，尽量不改前端协议。弃用 API 应保留一版本窗口。推荐 Feign 路径属后端内部，对 Agent 只暴露 `/recommend`。

---

## 附录 AM. 本地开发最小拓扑

| 必须 | 可选 |
|------|------|
| Gateway + 相关微服务 | LightRAG + MCP |
| Redis（画像） | LangSmith |
| Agent Server | Studio UI |
| 前端 | 通知模块（未实现） |

无 RAG 时勿开知识库开关；无画像 Redis 时推荐走降级。

---

## 附录 AN. 发布检查（设计视角）

- [ ] graph 名未变或前端同步  
- [ ] introspect 路径可用  
- [ ] 危险写仍 interrupt  
- [ ] Admin 无写工具  
- [ ] .env 语义表已更新实现说明  
- [ ] §16 状态表已更新  

---

## 附录 AO. 反模式清单

| 反模式 | 为何禁止 |
|--------|----------|
| Agent 拼 SQL 查订单库 | 破坏服务边界 |
| Prompt 禁止写入替代 Permission | 可被越狱 |
| 把通知塞进 Thread | 污染推理 |
| 设计文档粘贴整份 .env | 密钥与重复维护 |
| 用 Redis db=1 当现行 Checkpoint | 与代码不符 |
| 加购两边都 HINCRBY | 得分加倍 |
| L1 拦截清空购物车 | 跳过确认 |

---

## 附录 AP. 词汇：owner 示例

| agent_type | user_id | owner |
|------------|---------|-------|
| customer | 42 | `customer:42` |
| admin | 7 | `admin:7` |

二者不得互相 search 到对方 threads。

---

## 附录 AQ. 二次确认文案模板要素

1. 动作名称（秒杀/取消/清空…）  
2. 对象标识（订单号/商品名）  
3. 关键金额或数量（若有）  
4. 明确指示回复词  
5. 取消方式说明  

---

## 附录 AR. 知识库运营流程（设计）

1. 运营准备 Markdown/PDF。  
2. WebUI 上传至对应库。  
3. 等待索引完成。  
4. 管理端开 RAG 抽检问答。  
5. 对客库再开放 Customer。  
6. 定期回顾空结果率，补文档。  

Agent 发布节奏与知识运营可解耦。

---

## 附录 AS. 推荐理由生成约束（设计）

| 允许 | 禁止 |
|------|------|
| 基于 basedOn/tags 组织语言 | 编造未返回的折扣 |
| 结合本 thread 刚看过的商品 | 声称「系统保证最低价」 |
| 引导查看详情/加购 | 伪造库存数字 |

---

## 附录 AT. 记忆工具使用约束（设计）

| 应保存 | 不应保存 |
|--------|----------|
| 未完成购物意图 | 完整身份证号 |
| 明确品牌偏好陈述 | 他人隐私 |
| 预算区间 | 原始 JWT |

过期意图应清理，避免「三年前想买的手机」误导。

---

## 附录 AU. 文档阅读路径推荐

1. 新同学：项目说明 → 本文 §0–2 → §16  
2. 后端：本文 §6、Part B6、Part C1  
3. 前端：本文 §8、§10  
4. 算法/推荐：Part B 全文  
5. 运维：§12、§14 + 实现说明部署章  

---

## 附录 AV. 最终声明

本文（v2.3）为 hmall Agent **设计契约**。若行文与仓库冲突，以代码与 [实现说明](./hmall_Agent实现说明文档.md) 为准，并应回写 §16 与附录 L。

*—— 设计方案文档正文结束 ——*


## 附录 AW. 双 Agent 能力对照总表

| 能力域 | Customer | Admin |
|--------|----------|-------|
| 商品浏览/搜索 | ✅ | ✅（管理商品列表） |
| 秒杀查询 | ✅ | ✅ |
| 秒杀下单 | ✅ + interrupt | ❌ |
| 购物车 | ✅ | ❌ |
| 订单读写 | 读+取消/收货 | 只读 |
| 地址 | ✅ | ❌ |
| 推荐 | ✅ | ❌ |
| 画像/记忆 | ✅ | ❌（不读写 C 端画像） |
| 运营日报 | ❌ | ✅ |
| RAG | ✅ 可选 | ✅ 可选 |
| 写操作 | 受限 + 确认 | 无 |

---

## 附录 AX. 请求头与 context 字段规范

### 请求头

| Header | 必填场景 | 说明 |
|--------|----------|------|
| Authorization | 受保护会话 | Bearer JWT |
| X-Hmall-Agent-Type | 建议始终 | customer / admin，辅助选 introspect |
| Content-Type | JSON 请求 | application/json |

### context 字段

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| agent_type | str | — | 与助手一致 |
| user_token | str | 空 | 业务调 Gateway |
| user_id | str | 空 | 中间件注入 |
| enable_rag | bool | false | RAG 注入 |

新增字段必须向后兼容默认值。

---

## 附录 AY. 空数据文案原则（各域）

| 域 | 原则示例 |
|----|----------|
| 秒杀 | 「当前没有进行中的秒杀活动」 |
| 购物车 | 「购物车是空的」 |
| 订单 | 「暂无订单」 |
| 推荐 | 「暂无个性化结果，为您展示热销」 |
| 偏好 | 「暂无足够数据，告诉我您的兴趣」 |
| RAG | 「知识库暂无相关资料」 |
| 记忆 | 「暂无历史记忆」 |

文案固定在工具/Formatter，避免 LLM 每次重写导致前端无法稳定展示。

---

## 附录 AZ. 版本演进兼容矩阵

| 变更类型 | 兼容策略 |
|----------|----------|
| 新增工具 | 旧前端忽略即可 |
| 删除工具 | 先停 Skill 引导再删 |
| 改 tool 名 | 视为破坏性，需双注册过渡 |
| 改 graph 名 | 破坏性，前后端同步发版 |
| 改 interrupt payload | 增加字段兼容；删字段需前端同步 |
| 改 owner 格式 | 破坏性，需迁移脚本 |

---

## 附录 BA. 设计评审检查单（可打印）

### 架构

- [ ] 三级路由边界清晰  
- [ ] 权威架构图与端口正确  
- [ ] RAG 仅一处权威描述  

### 安全

- [ ] introspect 路径正确  
- [ ] owner 含 agent_type  
- [ ] Admin 只读可证明  

### 数据

- [ ] Checkpoint ≠ Redis 画像  
- [ ] profile 前缀与权重一致  
- [ ] 写入端无双计  

### 文档

- [ ] 无大段实现粘贴  
- [ ] 交叉引用有效  
- [ ] §16 状态表真实  

---

## 附录 BB. 与「实现偏差」相关的设计态度

设计文档允许演进，但必须：

1. **标出偏差**（如写入端从 Agent 改为后端）。  
2. **更新权威事实表**（§0.4 / §16）。  
3. **不把过时方案当现行**（Redis Checkpoint）。  
4. **把 How 留在实现说明**，避免两处粘贴命令分叉。  

---

## 附录 BC. 关闭语（维护者）

维护本设计文档时，优先改表格与架构图，而不是追加第三份 RAG 长文。若发现与代码不符，先改 §0.4 与 §16，再改正文细节。

---

## 附录 BD. 关键路径索引（仓库）

| 主题 | 典型路径 |
|------|----------|
| Customer Agent | `hmall-agent/src/agents/customer/` |
| Admin Agent | `hmall-agent/src/agents/admin/` |
| Auth | `hmall-agent/src/security/auth.py` |
| introspect | `hmall-agent/src/gateway/introspect.py` |
| RAG MCP | `hmall-agent/src/mcp_servers/rag_server.py` |
| RAG MW | `hmall-agent/src/middleware/rag_context.py` |
| 画像 | `hmall-agent/src/user_profile/` |
| 加购画像写入 | `hmall/.../CartServiceImpl.java` |
| 购买画像写入 | `hmall/.../paySuccessListener.java` |
| 推荐 | `hmall/.../RecommendServiceImpl.java` |
| graph 注册 | `hmall-agent/graph.json` |
| 启动 | `hmall-agent/start_server.py` |

（完整实现说明见另一文档。）

---

## 附录 BE. 术语英文对照

| 中文 | English |
|------|---------|
| 三级路由 | three-tier routing |
| 二次确认 | human-in-the-loop confirmation |
| 多租户隔离 | multi-tenant isolation via owner |
| 身份探查 | identity introspection |
| 画像 | user profile |
| 语义记忆 | semantic memory (store) |
| 运营日报 | daily ops report |
| 知识库开关 | RAG enable flag |
| 降级 | graceful degradation |

---

## 附录 BF. 一页纸摘要（给评审）

**系统**：DeepAgents + LangGraph；Customer / Admin 双助手；业务经 Gateway。  
**路由**：L1 正则 → L2 interrupt → L3 LLM。  
**安全**：双 JWT；introspect `/users/me` & `/admin/info`；owner 隔离；Admin 只读。  
**记忆**：Checkpoint=inmem 落盘；画像=Redis db0 `profile:`；Store=语义记忆。  
**已交付**：推荐、画像 Phase2、RAG、LLM health。  
**规划**：主动通知、浏览埋点、CF/向量。  
**文档**：本文=契约；实现说明=How；项目说明=入门。

---

## 附录 BG. 行文约定（本文）

1. 用表格承载契约，用示意流程图承载交互，不用大段可运行源码冒充设计。  
2. 「已实现 / 规划中」只写在状态表与章首提示，避免正文时态混乱。  
3. 提到端口与数量时与 §0.4 对齐。  
4. 引用实现时写文档名，不复制命令块。  
5. 废止方案明确标「废止」，避免读者按旧路部署。  

---

## 附录 BH. 感谢与范围外

本文不覆盖：hmall 非 Agent 微服务的内部表设计全文、前端视觉规范、云厂商账单优化。范围外议题请单独立项。

---
