# hmall Agent 实现说明文档

> **文档定位（How）**：文件路径、关键实现行为、配置全文、部署验收、测试、降级回滚、与设计偏差、变更记录。  
> **不写**：长篇设计动机、「为什么三级路由」等 Why；技术决策原理见设计方案，本文仅保留实现侧补充与偏差。

---

## 文档导航

| 文档 | 路径 | 职责 |
|------|------|------|
| **项目说明** | [`hmall_Agent项目说明文档.md`](./hmall_Agent项目说明文档.md) | What / Why 概览、快速上手 |
| **设计方案** | [`hmall_Agent设计方案文档.md`](./hmall_Agent设计方案文档.md) | 架构动机、技术决策全文、接口规划 |
| **本文（实现说明）** | `hmall_Agent实现说明文档.md` | How：落地路径、配置、验收、偏差、变更 |

**版本**：v2.0（2026-09）  
**相对 v1.x 主要更新**：多租户会话隔离（`metadata.owner`）+ Gateway introspect；Checkpoint 确认为 inmem + `.langgraph_api`；CustomerAgent 22 工具 / 7 Skills、AdminAgent 11 工具 / 3 Skills；RAG / Auth / 画像 Phase2 状态对齐代码。

---

## 目录

- [Part I 系统实现](#part-i-系统实现)
  - [1 实现概况](#1-实现概况)
  - [2 架构与请求链路](#2-架构与请求链路)
  - [3 Token / introspect 传递链](#3-token--introspect-传递链)
  - [4 后端实现](#4-后端实现)
  - [5 前端实现](#5-前端实现)
  - [6 关键技术决策（实现侧）](#6-关键技术决策实现侧)
  - [7 部署与验收](#7-部署与验收)
  - [8 已知问题与后续优化](#8-已知问题与后续优化)
  - [9 变更记录：多租户 + Gateway introspect（2026-09）](#9-变更记录多租户--gateway-introspect2026-09)
- [Part II 推荐与画像实现](#part-ii-推荐与画像实现)
  - [1 概况与 Phase 对照](#1-概况与-phase-对照)
  - [2 简图 + 链接设计 Part B](#2-简图--链接设计-part-b)
  - [3 Java 后端实现](#3-java-后端实现)
  - [4 Agent 侧实现细节](#4-agent-侧实现细节)
  - [5 Gateway / Nacos 配置与检查清单](#5-gateway--nacos-配置与检查清单)
  - [6 与设计文档的偏差说明](#6-与设计文档的偏差说明)
  - [7 降级、回滚、测试场景](#7-降级回滚测试场景)
  - [8 Phase2 画像落地说明](#8-phase2-画像落地说明)
  - [9 文档关联索引](#9-文档关联索引)

---

# Part I 系统实现

---

## 1 实现概况

按设计方案落地 **hmall Agent**（Python DeepAgents + LangGraph）：CustomerAgent（C 端）与 AdminAgent（管理端）双图，Vue 3 前端集成，经 `hm-gateway` 调用 Java 微服务。会话 Checkpoint 为 **inmem + `.langgraph_api` 落盘**（非 Redis Checkpoint）；身份以 **Gateway introspect** 为准；画像存 Redis db=0（与后端共享）。

### 1.1 当前能力快照（以仓库代码为准）

| 维度 | 现状 |
|------|------|
| CustomerAgent 工具 | **22**：商品 3 + 秒杀 3 + 购物车 5 + 订单 4 + 地址 3 + 推荐 2 + 记忆 2 |
| CustomerAgent Skills | **7**：购物引导 / 秒杀 / 购物车 / 订单 / 地址 / 个性化推荐 / RAG 查询 |
| AdminAgent 工具 | **11**：10 只读 + `generate_daily_report` |
| AdminAgent Skills | **3**：运营日报 / 数据查询 / RAG 查询 |
| 三级路由 | L1 `RegexShortcutMiddleware` + L2 `interrupt` + L3 LLM（规则文件见 §2） |
| RAG | **已实现**：`RAGMiddleware` + MCP Server + LightRAG |
| Auth / introspect | **已实现**：LangGraph Auth + `GET /users/me` / `GET /admin/info` |
| Checkpoint | **inmem** + `.langgraph_api/*.pckl`；多租户靠 `owner` 过滤 |
| 画像 | Redis `profile:{uid}:*`（db=`PROFILE_REDIS_DB`，默认 0） |

### 1.2 文件变更统计（历史累计，已按现状校正）

| 类别 | 约数 | 说明 |
|------|------|------|
| Agent 后端 Python | ~35+ | core / gateway（含 introspect）/ security / middleware / agents / tools / api / mcp / user_profile |
| Skills | **10** | C 端 7 + 管理端 3（`SKILL.md`） |
| 配置 | `pyproject.toml` + `graph.json` + `.env.example` | 含 `auth`、introspect、RAG |
| 启动脚本 | `start_server.py` + `start_rag_server.py` | Agent :8090（env）/ RAG MCP :8008 |
| 测试 | `tests/test_regex_rules.py`、`tests/test_formatters.py` | 单元测试 |
| 前端 | composables 2 + chat 组件/页面若干 | SDK 1.x、context-only、Markdown、健康检查、RAG 开关、owner |

> 早期文档写「18 工具 / 5 Skills / Redis Checkpoint」已过时，以上表为准。

---

## 2 架构与请求链路

> **详细架构、三级路由动机、中间件设计全文** → 设计方案 [第一部分 §2 整体架构](./hmall_Agent设计方案文档.md#2-整体架构)、[§2.2 三级路由](./hmall_Agent设计方案文档.md)、[§7 中间件体系](./hmall_Agent设计方案文档.md#7-中间件体系)。

### 2.1 部署拓扑（简图）

```
前端 Vue3  ──LangGraph SDK (HTTP+SSE)──►  Agent :8090
                                              │ middleware 链
                                              │ tools → httpx
                                              ▼
                                         hm-gateway :8080
                                              │
                    item / cart / trade / user / search / seckill / admin
```

### 2.2 三级路由实现要点（How）

```
消息 → L1 RegexShortcutMiddleware → 命中则 tool.ainvoke，跳过 LLM
         ↓ 未命中
       L2 工具内 interrupt（确认 / 多轮）
         ↓
       L3 LLM + Skills +（可选）RAG 工具
```

| 层级 | 实现位置 | 说明 |
|------|----------|------|
| L1 | `src/middleware/regex_shortcut.py` | 拦截 `awrap_model_call`；规则来自各 Agent 的 `regex_rules.py` |
| L1 规则（C） | **`src/agents/customer/regex_rules.py`** | 含秒杀/购物车/订单/地址/推荐/搜索等只读规则 |
| L1 规则（管） | **`src/agents/admin/regex_rules.py`** | 日报、商品列表、订单、秒杀活动 |
| L2 | 各 `@tool` 内 `langgraph.types.interrupt` | 写操作确认、地址多轮；状态由 Checkpointer 保存 |
| L3 | DeepAgents Agent Loop + SkillsMiddleware | 复杂意图 / L1 未命中；可组合多工具 |

**中间件全链（与路由叠加）**：

```
AuthMiddleware → PermissionMiddleware → RegexShortcutMiddleware
  → RAGMiddleware → SkillsMiddleware →（未短路则）Model
```

L1 命中时仍经过 Auth/Permission（保证身份与工具可见性），但不再进入 Model 节点。

**实现约束**：

- L1 仅拦截只读；写操作一律走 L2/L3。  
- L1 `ainvoke` 失败会尝试同步 `invoke`，再失败返回 `None` 交 LLM。  
- 多模态 human message：从 content 列表抽文本再匹配。  
- 推荐 detail / 模糊购物意图：留给 L3 + Skill，避免正则误伤。

---

## 3 Token / introspect 传递链

> 本节为 **实现权威**。设计动机见设计方案 [§6.1 双 JWT + Gateway introspect](./hmall_Agent设计方案文档.md#61-双-jwt--gateway-introspectuserid-权威对齐)。

```
前端 sessionStorage
  token / admin-token
       │
       ▼
useLangGraph.ts
  Client headers:
    Authorization: Bearer <jwt>
    X-Hmall-Agent-Type: customer | admin
  runs.stream(..., {
    context: { agent_type, user_token, enable_rag?, user_id? },
    thread metadata.owner = "{agent_type}:{userId}"  // 创建时
  })
       │
       ├─► LangGraph Auth（src/security/auth.py）
       │     authenticate → introspect(token) → identity = owner
       │     threads.* 按 metadata.owner 过滤
       │
       └─► AuthMiddleware（src/middleware/auth.py）
             JWT_VERIFY_LOCAL=true → 本地 jks 优先
             否则 → await introspect → 写入 context.user_id
             INTROSPECT_FALLBACK_JWT=false（默认）→ introspect 失败不注入本地解码 ID
       │
       ▼
@tool：extract_token_from_config(config)
       → gateway_client.*(path, token=...) → Authorization
       │
       ▼
hm-gateway AuthGlobalFilter → user-info → 下游微服务
```

### 3.1 introspect 行为（代码：`src/gateway/introspect.py`）

| 项 | 行为 |
|----|------|
| C 端 | `GET /users/me` → `userId`（Gateway 验签后的 UserContext） |
| 管理端 | `GET /admin/info` → `id`（`R<T>` 由 GatewayClient 解包） |
| 返回 | `{ user_id, agent_type, owner }`，`owner = "{agent_type}:{user_id}"` |
| 缓存 | `INTROSPECT_CACHE_TTL`（默认 60s）；`0` 禁用；进程内 dict + 上限清理 |
| 失败 | 401/403 → `IntrospectError`；其它 → 503 文案；**默认无 JWT 解码回退** |
| 路径猜测 | 无显式 agent_type 时可用 JWT payload **仅选** introspect URL，不作权威 ID |

### 3.2 LangGraph Auth 公开路径与资源过滤

**免登录探测前缀**（`src/security/auth.py`）：

`/ok`、`/docs`、`/openapi.json`、`/redoc`、`/info`、`/metrics`、`/api/v1/health`、`/api/v1/llm` → `identity=system:public`，`is_authenticated=False`。

**认证成功返回**：

| 字段 | 值 |
|------|-----|
| `identity` | `owner` = `{agent_type}:{user_id}` |
| `is_authenticated` | True |
| `permissions` | `threads:read` / `threads:write` |
| `user_id` / `agent_type` | 额外挂到 user dict，便于调试 |

**threads 钩子（实现）**：

| 钩子 | 行为 |
|------|------|
| `threads.create` | `metadata.owner/agent_type/user_id` 写入；返回 owner filter |
| `threads.read/update/delete/search/create_run` | `_owner_filter` → `{"owner": identity}` |
| 无有效 identity | HTTP 401 |

**Store 钩子**：`@auth.on.store` 校验 namespace 第二段等于当前 `user_id`（与 `memory.py` 的 `(user_memory, user_id)` 约定一致）；否则 403。

**Agent-Type 头**：`X-Hmall-Agent-Type` 等；非法值忽略，改由 JWT payload **仅用于**选择 introspect URL。

**回退**：仅 `INTROSPECT_FALLBACK_JWT=true` 时 introspect 失败才本地解码；默认关闭。

---

## 4 后端实现

### 4.1 项目结构树

```
hmall-agent/
├── start_server.py                 # LangGraph Server 入口（inmem + Auth）
├── start_rag_server.py             # RAG MCP HTTP Server :8008
├── graph.json                      # 双图 + store + auth
├── pyproject.toml
├── .env.example                    # 环境变量权威模板
├── README.md
├── src/
│   ├── core/
│   │   ├── config.py               # Settings（含 INTROSPECT_* / RAG / PROFILE_REDIS_DB）
│   │   └── llms.py                 # ChatOpenAI → qwen-turbo
│   ├── security/
│   │   ├── auth.py                 # LangGraph Auth：introspect + owner 隔离
│   │   └── jwt_payload.py          # claim：C 端 user / 管理端 sub
│   ├── gateway/
│   │   ├── http_client.py          # httpx；/admin 解包 R<T>；token / user_id 提取
│   │   ├── introspect.py           # Gateway 权威身份探查
│   │   └── auth.py                 # 本地 jks 验签（JWT_VERIFY_LOCAL）
│   ├── middleware/
│   │   ├── auth.py                 # AuthMiddleware
│   │   ├── permission.py           # Admin 过滤写工具
│   │   ├── regex_shortcut.py       # L1
│   │   └── rag_context.py          # RAGMiddleware
│   ├── user_profile/
│   │   ├── store.py                # Redis 画像 Layer 1/2
│   │   └── memory.py               # save_memory / get_memories（Layer 3）
│   ├── agents/customer|admin/
│   │   ├── agent.py / prompts.py / tools.py / regex_rules.py
│   ├── tools/
│   │   ├── formatters.py           # Markdown 格式化（含推荐/偏好）
│   │   └── rag_loader.py           # MCP 工具加载（模块级缓存）
│   ├── api/
│   │   ├── batch_report.py         # 自定义 FastAPI 挂载
│   │   └── health.py               # GET /api/v1/llm/health
│   ├── mcp_servers/rag_server.py   # LightRAG 桥接：3 工具
│   └── workspace/{customer,admin}/skills/.../SKILL.md
└── tests/
    ├── test_regex_rules.py
    └── test_formatters.py
```

### 4.2 配置与 LLM、Checkpoint

#### 4.2.1 Settings（`src/core/config.py`）

- `pydantic-settings` + `@lru_cache` 单例。
- 画像 DB：`PROFILE_REDIS_DB`（默认 0），`redis_url` 指向该库；**会话 Checkpoint 不走 Redis**。
- introspect：`INTROSPECT_CACHE_TTL`、`INTROSPECT_FALLBACK_JWT`（默认 `false`）。

#### 4.2.2 LLM（`src/core/llms.py`）

`ChatOpenAI` + DashScope 兼容 base_url；模型默认 `qwen-turbo`。

#### 4.2.3 Checkpoint / Store（实现现状）

| 项 | 值 |
|----|-----|
| 运行时 | `start_server.py` → `LANGGRAPH_RUNTIME_EDITION=inmem` |
| Checkpoint | `langgraph-runtime-inmem`；冷路径 pickle |
| 落盘 | `.langgraph_api/.langgraph_checkpoint.*.pckl`、`.langgraph_ops.pckl` |
| Store | `graph.json` → `"store": { "type": "in_memory" }`；落盘 `store.pckl`（Layer 3） |
| 多租户 | Auth 按 `owner` 过滤；**不是**按 Redis db 分用户 |

早期「RedisSaver db=1」仅为历史方案，**现行代码以 inmem + `.langgraph_api` 为准**。

### 4.3 Gateway：`http_client` + introspect

#### 4.3.1 `GatewayClient`（`src/gateway/http_client.py`）

| 特性 | 行为 |
|------|------|
| 异步 | `httpx.AsyncClient`；每次请求新建 client（当前实现） |
| Base URL | `JAVA_GATEWAY_URL`（默认 `http://localhost:8080`） |
| Token | 请求头键名为 `authorization`（与 Gateway 过滤器约定一致） |
| C 端路径 | `resp.json()` 直接作为业务数据返回 |
| `/admin*` | 若 body 含 `code`：`code!=200` 抛错，否则返回 `data` |
| 错误映射 | 429→秒杀限流文案；401→登录过期；≥400→截断 body 前 200 字 |
| 超时 | `httpx.Timeout(connect=5.0, read=30.0, write=10.0, pool=5.0)` |
| 方法 | `get` / `post` / `put` / `delete` |

**Token / userId 辅助（实现约定）**：

| 函数 | 行为 |
|------|------|
| `extract_token_from_config(config)` | 从 LangGraph 注入的 `RunnableConfig` 取 `user_token`（多路径：configurable / context / runtime） |
| `_extract_user_id(config)` | 优先 context 已注入的权威 `user_id`；必要时再从 JWT claim 解字符串（**仅用于画像 key 等**，不替代 introspect） |
| `_decode_user_id_from_jwt` | claim：C 端 `user`，管理端 `sub`（与 Java 签发对齐；2026-09 修正） |

#### 4.3.2 introspect

见 [§3](#3-token--introspect-传递链)；模块 `src/gateway/introspect.py`。

补充实现细节：

- 缓存 key：`sha256(token)` + `agent_type`；进程内 dict；条目 >4096 时清过期或整表清空。
- Admin 响应字段优先 `id`，其次 `userId`；C 端优先 `userId`。
- `clear_introspect_cache()` 供测试/登出场景。

### 4.4 中间件（各文件职责）

中间件顺序（customer / admin 的 `agent.py` 一致）：

1. `AuthMiddleware` → 2. `PermissionMiddleware` → 3. `RegexShortcutMiddleware` → 4. `RAGMiddleware` → 5. `SkillsMiddleware`

| 文件 | 职责 |
|------|------|
| `middleware/auth.py` | 读 `context.user_token`；本地 jks 或 introspect 写 `user_id`；无 token 仅放行只读 |
| `middleware/permission.py` | `agent_type=admin` 时从 `request.tools` 剔除 `WRITE_TOOLS` |
| `middleware/regex_shortcut.py` | L1：规则匹配 → `tool.ainvoke` → `AIMessage` |
| `middleware/rag_context.py` | `enable_rag=true` 时经 `rag_loader` 注入 MCP 工具；失败降级为不注入 |
| DeepAgents `SkillsMiddleware` | 加载 `workspace/*/skills/**/SKILL.md` |

#### 4.4.1 AuthMiddleware 细节

| 路径 | 行为 |
|------|------|
| `awrap_model_call`（主路径） | `_authenticate_async`：先 `verify_jwt`（仅 `JWT_VERIFY_LOCAL` 生效时成功）；否则 `await introspect`；失败且 `INTROSPECT_FALLBACK_JWT=false` 则**不写入**本地解码 ID |
| `wrap_model_call`（同步） | `_authenticate_sync_fallback`：尽量本地解码兜底（DeepAgents 主路径为异步） |
| 无 token | debug 日志「仅允许只读」；写工具自行检查 token |

#### 4.4.2 PermissionMiddleware

**Admin 不可用的写工具集**（`WRITE_TOOLS`）：

```python
WRITE_TOOLS = {
    "add_to_cart_api", "update_cart_quantity_api",
    "delete_cart_item_api", "clear_cart_api",
    "cancel_order_api", "confirm_receive_api",
    "add_address_api", "update_address_api",
    "do_seckill_api",
}
```

CustomerAgent：不剔除；写操作仍依赖 Token + L2 interrupt。

#### 4.4.3 RegexShortcutMiddleware

| 属性 | 值 |
|------|-----|
| 钩子 | `awrap_model_call`，在真正调 LLM 前检查最后一条 human message |
| 命中 | `tool.ainvoke(params)` → 构造无 `tool_calls` 的 `AIMessage` → 图可直接结束 |
| 未命中 | `return await handler(request)` |
| 降级 | async 失败 → sync `invoke` → 再失败返回 `None`（走 LLM） |
| 多模态 | `content` 为 `list[dict]` 时抽取文本字段再匹配 |

规则元组格式：`(pattern: str, tool_name: str, param_extractor: Callable|None)`。

#### 4.4.4 RAGMiddleware

| 条件 | 行为 |
|------|------|
| `context.enable_rag is True` | `await load_rag_tools()`，追加到 `request.tools` |
| MCP 不可达 | warning 日志，不注入，不抛到用户主流程 |
| `enable_rag is False` | 跳过 |

工具名：`rag_query`、`rag_query_data`、`rag_graph_search`（与 MCP Server 一致）。

### 4.5 CustomerAgent / AdminAgent（工具表权威）

#### 4.5.1 CustomerAgent（`src/agents/customer/agent.py`）

```python
@dataclass
class Context:
    agent_type: str = "customer"
    user_id: str = ""
    user_token: str = ""
    enable_rag: bool = False

# skills sources: shopping-guide / seckill-order / cart-management /
#   order-management / address-management / personalized-recommendation / rag-query
# middleware: Auth → Permission → Regex → RAG → Skills
# tools: get_all_tools()  # 22
```

#### 4.5.2 Customer 工具完整表（22）— 权威

| 分类 | 工具 | API | interrupt |
|------|------|-----|-----------|
| 商品×3 | `search_items_api` | `GET /search/list` | — |
| | `get_item_detail_api` | `GET /items/{id}` | — |
| | `get_item_page_api` | `GET /items/page` | — |
| 秒杀×3 | `get_seckill_activities_api` | `GET /seckill/activities` | — |
| | `get_seckill_product_api` | `GET /seckill/products/{relationId}` | — |
| | `do_seckill_api` | `POST /seckill/order/{relationId}` | ✅ 确认 |
| 购物车×5 | `get_cart_list_api` | `GET /carts` | — |
| | `add_to_cart_api` | `POST /carts` | — |
| | `update_cart_quantity_api` | `PUT /carts/{itemId}` | — |
| | `delete_cart_item_api` | `DELETE /carts/{itemId}` | ✅ 确认 |
| | `clear_cart_api` | `DELETE /carts` | ✅ 确认 |
| 订单×4 | `get_order_list_api` | `GET /orders/page` | — |
| | `get_order_detail_api` | `GET /orders/{id}` | — |
| | `cancel_order_api` | `POST /orders/batch/close` | ✅ 确认 |
| | `confirm_receive_api` | `PUT /orders/{orderId}` | ✅ 确认 |
| 地址×3 | `get_address_list_api` | `GET /addresses` | — |
| | `add_address_api` | `POST /addresses` | ✅ 多轮 |
| | `update_address_api` | `PUT /addresses/{id}` | ✅ 两轮 |
| 推荐×2 | `get_recommendations_api` | `GET /recommend` | — |
| | `analyze_user_preferences` | `/orders/page` + `/carts`（或画像命中） | — |
| 记忆×2 | `save_memory` / `get_memories` | LangGraph Store | — |

登录类工具用 `extract_token_from_config`；`/items/**`、`/search/**` 在 Gateway 免认证。

#### 4.5.3 Customer L1 规则摘要（`regex_rules.py`）

规则为 `(pattern, tool_name, extractor)` 列表，**顺序敏感**（靠前优先）。当前实现要点：

| 示例输入 | 模式要点 | 工具 | 参数 |
|----------|----------|------|------|
| 查看/查询/当前秒杀 | `(?:查看\|查询\|当前).{0,3}秒杀` | `get_seckill_activities_api` | — |
| 查看购物车 | `(?:查看\|查询\|我的).{0,5}购物车` | `get_cart_list_api` | — |
| 查看订单 / 待付款订单 | 含可选状态词的订单查询 | `get_order_list_api` | — |
| 查看订单100 | 捕获数字 | `get_order_detail_api` | `order_id` |
| 查看地址 | 地址只读 | `get_address_list_api` | — |
| 推荐 / 猜你喜欢 / 帮我选 / 随便看看 | 推荐关键词 | `get_recommendations_api` | `_extract_recommend_scene` → 默认 home |
| 购物车推荐 / 凑单推荐 | `(?:购物车\|凑单).{0,5}(?:推荐\|…)` | `get_recommendations_api` | `scene=cart` |
| 搜索手机 | `(?:搜索\|查找\|找)\s*(.+)` | `search_items_api` | `keyword` |
| 商品列表 | 浏览类关键词 | `get_item_page_api` | — |

「看了又看」需 `item_id`，**不走 L1**，由 L3 从上下文提取。写操作（取消订单、秒杀下单等）**故意不配置** L1，交给 L2 interrupt。

测试：`tests/test_regex_rules.py` 覆盖命中与 scene 提取。

#### 4.5.4 L2 interrupt 一览

| 操作 | 类型 | 恢复条件 |
|------|------|----------|
| 秒杀下单 | confirmation | 确认 |
| 删购物车项 / 清空购物车 | confirmation | 确认删除 |
| 取消订单 | confirmation | 确认取消 |
| 确认收货 | confirmation | 确认收货 |
| 新增地址 | address_input | 6 字段逗号分隔 |
| 修改地址 | field_selection → value_input | 先字段后新值；手机号 `^1\d{10}$` |

**秒杀确认（实现顺序）**：工具内先拉秒杀商品详情做展示 → `interrupt({type:"confirmation", ...})` → 用户确认后再 `POST /seckill/order/{relationId}` → `format_seckill_result`。未接前端 pending 轮询（见 §8.6）。

**地址修改两轮状态机**：

```
用户: "修改地址1"
  ├─ interrupt #1 field_selection → 展示当前地址，询问字段
  ├─ 用户: "手机号" → 映射 field_en=phone
  ├─ interrupt #2 value_input → 「请输入新的手机号」
  ├─ 用户: "13900139000" → re.match(r"^1\d{10}$")
  └─ PUT /addresses/1 → 成功文案
```

前端恢复：`useLangGraph.resume(value)` 把用户回复作为 interrupt 恢复值；拒绝走 `rejectInterrupt()`（`command: { goto: "__end__" }`）。

#### 4.5.5 AdminAgent（`src/agents/admin/agent.py`）

`Context.agent_type="admin"`；Skills：`daily-report` / `data-query` / `rag-query`；中间件同 Customer（Permission 强制只读）。

#### 4.5.6 Admin 工具完整表（11）— 权威

| 分类 | 工具 | API |
|------|------|-----|
| 商品 | `admin_get_product_page_api` | `GET /admin/product/list` |
| | `admin_get_product_detail_api` | `GET /admin/product/{id}` |
| 订单 | `admin_get_order_page_api` | `GET /admin/order/list` |
| | `admin_get_order_detail_api` | `GET /admin/order/{id}` |
| 秒杀 | `admin_get_seckill_promotion_page_api` | `GET /admin/seckill/promotion/list` |
| | `admin_get_seckill_relation_page_api` | `GET /admin/seckill/relation/list` |
| | `admin_get_seckill_order_page_api` | `GET /admin/seckill/order/list` |
| | `admin_get_seckill_stock_api` | `GET /admin/seckill/stock/{relationId}` |
| 用户 | `admin_get_user_page_api` | `GET /admin/member/list` |
| | `admin_get_user_detail_api` | `GET /admin/member/{id}` |
| 编排 | `generate_daily_report` | 并发 5 路 admin 列表 API |

`generate_daily_report`：`asyncio.gather` + `_safe_get`（单路失败 → `None`，分区显示「数据获取失败」）。

实现示意（取各列表第 1 页 size=1 以拿 total）：

```python
orders, seckill_promotions, seckill_relations, products, users = await asyncio.gather(
    _safe_get("/admin/order/list"),
    _safe_get("/admin/seckill/promotion/list"),
    _safe_get("/admin/seckill/relation/list"),
    _safe_get("/admin/product/list"),
    _safe_get("/admin/member/list"),
)
return format_daily_report(orders, seckill_promotions, seckill_relations, products, users)
```

日报分区输出（Formatter）：订单总数 / 秒杀活动数 / 秒杀关联数 / 商品总数 / 用户总数；单分区失败不影响其它分区。

Admin L1：运营日报 → `generate_daily_report`；商品列表 / 订单 / 秒杀活动 → 对应只读工具。

| 用户输入 | 路由工具 |
|---------|---------|
| 运营日报 / 生成日报 / 帮我做日报 | `generate_daily_report` |
| 查看商品列表 | `admin_get_product_page_api` |
| 查看订单 | `admin_get_order_page_api` |
| 秒杀活动 / 查看活动 | `admin_get_seckill_promotion_page_api` |

### 4.6 formatters / RAG loader

#### 4.6.1 `src/tools/formatters.py`

价格「分→元」统一 `_yuan()`。函数一览（实现权威）：

| 函数 | 用途 |
|------|------|
| `format_seckill_activities` | 活动→场次→商品三级嵌套 |
| `format_seckill_product` | 秒杀价/原价/库存/限购/状态 |
| `format_seckill_result` | success / pending / failed |
| `format_search_results` | 搜索 PageDTO |
| `format_item_detail` / `format_item_page` | 详情与分页列表 |
| `format_cart_list` | 单价×数量小计 |
| `format_order_list` / `format_order_detail` | 订单列表与明细 |
| `format_address_list` | 含默认地址标记 |
| `format_recommendations` / `format_preferences` | 推荐表 + 偏好分析 |
| `format_admin_product_page` / `format_admin_order_page` | 管理端商品/订单 |
| `format_admin_seckill_page` / `format_admin_user_page` | 管理端秒杀/用户 |
| `format_daily_report` | 运营日报分区 |

状态映射（Formatter 内）：

| 业务 | 码 → 文案 |
|------|-----------|
| 订单 | 1 待付款 / 2 已付款 / 3 已发货 / 4 确认收货 / 5 交易取消 |
| 商品 | 1 在售 / 2 已下架 / 3 已删除 |
| 秒杀活动 | 1 未开始 / 2 进行中 / 3 已结束 |
| 秒杀商品 | 0 未开始 / 1 抢购中 / 2 已售罄 / 3 已结束 |

辅助：`_yuan(fen)`、`_status_text`、`_table_row` / `_table_sep`。分页兼容 `list` 与 `records`。空列表须返回可读中文提示，避免把 `None`/`[]` 原样丢给 LLM。

#### 4.6.2 RAG loader + MCP

| 组件 | 路径 | 行为 |
|------|------|------|
| Loader | `src/tools/rag_loader.py` | `langchain-mcp-adapters` 连 MCP；模块级缓存 |
| MCP Server | `src/mcp_servers/rag_server.py` | `LightRAGClient` + 3 工具桥接 REST |
| 启动 | `start_rag_server.py` | `mcp.run(transport="http", port=RAG_MCP_PORT)` |
| 注入 | `RAGMiddleware` | `context.enable_rag`；MCP 不可达则 warning 并跳过 |

**MCP 工具职责**：

| 工具 | LightRAG 侧 | 用途 |
|------|-------------|------|
| `rag_query` | `POST /query` | 语义检索自然语言答案 |
| `rag_query_data` | `POST /query/data` | 结构化检索 |
| `rag_graph_search` | 图谱相关 API | 实体/关系检索 |

**启动前置**：LightRAG `:9621` 已起；`.env` 中 `RAG_*` 正确；知识库已通过 LightRAG WebUI 导入。MCP endpoint：`http://localhost:8008/mcp`。认证优先 `RAG_API_KEY`（X-API-Key），否则用户名密码换 JWT。

**前端**：`ChatPanel` RAG 开关 → `context.enable_rag`；关闭时不注入工具，省 MCP 连接。

### 4.7 memory / 画像读取

| 层 | 模块 | Key / 命名空间 | 说明 |
|----|------|----------------|------|
| L1/L2 画像 | `user_profile/store.py` | `profile:{uid}:categories\|brands\|prices\|stats\|events` | HINCRBY；权重 purchase=5 / cart=3 / view=1；事件 List TTL 7d，画像 Hash TTL 30d |
| L3 记忆 | `user_profile/memory.py` | Store namespace `(user_memory, user_id)` | `save_memory` / `get_memories` |
| 偏好工具 | `analyze_user_preferences` | 先 `profile_store.get_profile`；命中则 0 次 Gateway；miss 则 `asyncio.gather` 订单+购物车并 `backfill_profile` |

画像与后端共用 **String 序列化** Redis（见 Part II §8）。加购/支付画像写入在 **Java 侧**，避免与 Agent 双重计数。

**Layer3 工具行为**：

| 工具 | 行为 |
|------|------|
| `save_memory(key, value)` | `store.aput(namespace=(user_memory, user_id), key=..., value={content, ts})`；无 store / 无 user_id → 明确失败文案 |
| `get_memories` | 按 namespace 列出历史记忆，供 LLM 融入回复 |

Store 实例来自 `config["configurable"]["store"]`（平台注入）；`graph.json` 当前为 in_memory，与 Checkpoint 一并落在 `.langgraph_api/store.pckl`。Auth 的 store 钩子阻止跨用户 namespace 访问。

**ProfileStore 关键方法**：

| 方法 | 说明 |
|------|------|
| `record_event(...)` | 追加 events + HINCRBY 聚合；失败吞异常 |
| `get_profile(uid)` | 组装 categories/brands/prices dict；miss → `{}` |
| `backfill_profile(...)` | 用实时聚合结果批量写入，供下次命中 |

单例：`profile_store` 模块级实例，连接池 `max_connections` 默认 20。

### 4.8 启动与 `.env` 全文权威

#### 4.8.1 `graph.json`（现行）

```json
{
    "dependencies": ["."],
    "graphs": {
        "customer_agent": {
            "path": "./src/agents/customer/agent.py:agent",
            "description": "客服助手 Agent：商品浏览、秒杀、购物车、订单、地址全链路自然语言交互"
        },
        "admin_agent": {
            "path": "./src/agents/admin/agent.py:agent",
            "description": "管理助手 Agent：秒杀管理、订单查询、商品管理、库存查看、运营日报"
        }
    },
    "store": {
        "type": "in_memory"
    },
    "auth": {
        "path": "./src/security/auth.py:auth",
        "disable_studio_auth": true
    },
    "env": ".env"
}
```

#### 4.8.2 `start_server.py` 关键环境

| 变量 | 作用 |
|------|------|
| `LANGGRAPH_RUNTIME_EDITION=inmem` | 开发态内存运行时 |
| `LANGSERVE_GRAPHS` | 来自 graph.json graphs |
| `LANGGRAPH_AUTH` | 来自 graph.json auth |
| `LANGGRAPH_STORE` | 来自 graph.json store |
| `LANGGRAPH_HTTP` | `api.batch_report:app` 自定义路由 |
| `AGENT_PORT` | 默认读取 env；脚本内 fallback 为 `8091`，**.env.example 推荐 8090** |

CORS：开发态 `allow_origins=["*"]`。

#### 4.8.3 `.env.example` 全文（权威）

```bash
# ==================== LLM ====================
DASHSCOPE_API_KEY=your_api_key
LLM_MODEL_NAME=qwen-turbo
LLM_API_BASE=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_TEMPERATURE=0.7
LLM_MAX_TOKENS=2048

# ==================== Redis ====================
REDIS_HOST=192.168.100.128
REDIS_PORT=6379
REDIS_PASSWORD=
PROFILE_REDIS_DB=0                   # 画像 Redis DB，必须与后端 spring.redis.database 一致

# ==================== Java 后端 ====================
JAVA_GATEWAY_URL=http://localhost:8080

# ==================== Agent 服务 ====================
AGENT_HOST=0.0.0.0
AGENT_PORT=8090
LOG_LEVEL=INFO

# ==================== JWT（双 Token 验证） ====================
JWT_VERIFY_LOCAL=false              # false 时依赖 Gateway 验证
CUSTOMER_JKS_PATH=keys/hmall.jks   # C 端 RSA 密钥
ADMIN_JKS_PATH=keys/admin.jks      # 管理端 RSA 密钥（独立）

# ==================== 身份探查（方案 3：Gateway introspect） ====================
INTROSPECT_CACHE_TTL=60             # 秒；0 禁用缓存
INTROSPECT_FALLBACK_JWT=false       # true 时 introspect 失败才回退本地解码（默认关闭）

# ==================== RAG（LightRAG + MCP） ====================
RAG_BASE_URL=http://localhost:9621       # LightRAG Server 地址（默认端口 9621）
RAG_USERNAME=admin                       # LightRAG 登录用户名
RAG_PASSWORD=admin123                    # LightRAG 登录密码
RAG_SPACE_ID=hmall_space                 # LightRAG 工作空间隔离标识
RAG_API_KEY=                             # LightRAG API Key（可选，设置后优先用 X-API-Key 认证）
RAG_AUTH_ENABLED=true                    # 是否启用 LightRAG 认证
RAG_MCP_PORT=8008                        # RAG MCP Server 监听端口
```

#### 4.8.4 自定义路由

| 路由 | 文件 | 说明 |
|------|------|------|
| 批量运营报告等 | `src/api/batch_report.py` | 经 `LANGGRAPH_HTTP` 挂到 LangGraph FastAPI app |
| `GET /api/v1/llm/health` | `src/api/health.py` | 真实 ping DashScope；返回在线/离线结构化状态 |

#### 4.8.5 依赖声明（`pyproject.toml` 摘要）

| 依赖 | 用途 |
|------|------|
| `deepagents` | Agent 框架 / SkillsMiddleware / FilesystemBackend |
| `langchain` / `langchain-openai` | 工具与通义兼容接口 |
| `langgraph-cli[inmem]` | 本地 inmem Server |
| `langchain-mcp-adapters` / `fastmcp` | RAG MCP |
| `httpx` | Gateway 客户端 |
| `pydantic-settings` / `python-dotenv` | 配置 |
| `redis[hiredis]` | 画像 asyncio 客户端 |
| `fastapi` / `uvicorn` | 自定义路由与进程托管 |
| dev: `pytest` / `pytest-asyncio` | 单测 |

`requires-python >= 3.12`；包名 `hmall-agent` version `2.0.0`。

### 4.9 Skills 文件清单（路径列表）

**Customer（7）**

- `src/workspace/customer/skills/shopping-guide/SKILL.md`
- `src/workspace/customer/skills/seckill-order/SKILL.md`
- `src/workspace/customer/skills/cart-management/SKILL.md`
- `src/workspace/customer/skills/order-management/SKILL.md`
- `src/workspace/customer/skills/address-management/SKILL.md`
- `src/workspace/customer/skills/personalized-recommendation/SKILL.md`
- `src/workspace/customer/skills/rag-query/SKILL.md`

**Admin（3）**

- `src/workspace/admin/skills/daily-report/SKILL.md`
- `src/workspace/admin/skills/data-query/SKILL.md`
- `src/workspace/admin/skills/rag-query/SKILL.md`

加载方式：`FilesystemBackend(..., virtual_mode=True)` + `SkillsMiddleware(sources=[...])`。

### 4.10 测试

| 文件 | 覆盖 |
|------|------|
| `tests/test_regex_rules.py` | C/Admin 正则命中、参数提取、推荐 scene（home/cart）、不误伤写操作句式 |
| `tests/test_formatters.py` | 各 `format_*` 空数据 / 典型 payload / 分转元 / 推荐与偏好输出 |

运行（在 `hmall-agent` 目录）：

```bash
uv run pytest tests/ -q
```

建议补充的手工/集成项（未全部自动化）：

| 项 | 方法 |
|----|------|
| introspect | 有效/过期 token 调 `/users/me`；观察 Agent Auth 401 |
| owner 隔离 | 两用户交叉 search threads |
| interrupt | 取消订单确认/拒绝各一次 |
| RAG | MCP down 时对话仍可用；up 时 enable_rag 注入 |
| 推荐降级 | 停 search-service 看热销兜底 |

---

## 5 前端实现

> 本节为前端 **How 权威**。产品交互动机见项目说明 / 设计方案 [§12 前端集成](./hmall_Agent设计方案文档.md#12-前端集成)。

### 5.1 文件与路由

| 路径 | 作用 |
|------|------|
| `hmall-frontend/src/composables/useLangGraph.ts` | SDK 1.x Client；SSE；context-only；Authorization + agent-type；owner |
| `hmall-frontend/src/composables/useLlmHealth.ts` | 30s 轮询 `/api/v1/llm/health` |
| `components/chat/ChatPanel.vue` | 全页对话；RAG 开关；健康状态 |
| `components/chat/MessageBubble.vue` | marked Markdown；溢出修复 |
| `components/chat/InterruptActions.vue` | L2 确认 / 输入恢复 |
| `components/chat/ChatWidget.vue` | 浮动入口 → `/portal/chat` |
| `components/chat/AdminChat.vue` | header 入口 → `/admin/chat` |
| `views/portal/ChatPage.vue` / `views/admin/ChatPage.vue` | 页面壳 |
| `router/index.ts` | `/portal/chat`、`/admin/chat` |

依赖：`@langchain/langgraph-sdk` ^1.x、`marked`。

### 5.2 页面结构（简图）

```
/portal/chat → ChatPanel(assistantId=customer_agent)
/admin/chat  → ChatPanel(assistantId=admin_agent) + 快捷操作
```

### 5.3 `useLangGraph` 实现要点

| 项 | 实现 |
|----|------|
| 状态 | `messages` / `isLoading` / `interruptData` / `threadId` / `error` |
| 方法 | `sendMessage` / `resume` / `rejectInterrupt` / `clearHistory` / 会话列表 search |
| Headers | 每次用当前 token 新建 Client：`Authorization`、`X-Hmall-Agent-Type` |
| context | `{ agent_type, user_token, enable_rag }`；**禁止**再传 `config.configurable`（与 context 互斥） |
| owner | 前端从 JWT payload 解出 `userId`，`owner = agentType:userId`；create/search thread 带 `metadata.owner`；换账号清本地绑定 |
| SSE | `messages/partial` 增量；`messages/complete` 终态；`values` → `__interrupt__`；`error` → `❌` 气泡 |
| 响应式 | 必须 `messages.value[idx].content = ...`，禁止改局部对象引用绕过 Proxy |

Token 来源：C 端 `sessionStorage.token`；管理端 `sessionStorage['admin-token']`。

**SSE 事件处理表**：

| 事件 | 处理 |
|------|------|
| `messages/partial` | 流式 token 追加到当前 AI 消息 |
| `messages/complete` | 写入最终全文（避免非流式丢失） |
| `values` | 解析 `__interrupt__` → `interruptData`，展示 `InterruptActions` |
| `error` | 推入 `❌ {message}` 气泡并中断 loading |
| `messages/metadata` | 忽略 |

**owner 绑定防串号**：

```typescript
// 伪代码：换账号时清空 threadId / messages
function _ensureOwnerBound(owner: string) {
  if (_boundOwner && _boundOwner !== owner) {
    // 重置本地会话状态
  }
  _boundOwner = owner
}
```

创建 thread 时 metadata：`{ owner: identity.owner }`；search：`metadata: { owner }`，与服务端 Auth 过滤器对齐。

**JWT 解 owner（前端）**：C 端 claim `user`；管理端 `sub`；格式化为 `customer:{id}` / `admin:{id}`。注意：前端解 ID **仅用于** metadata 与 UI，服务端仍以 introspect 为准。

### 5.4 `MessageBubble` / `ChatPanel`

| 组件 | 要点 |
|------|------|
| MessageBubble | AI：marked GFM（标题/列表/表格/代码块/引用/链接）；人类：`whitespace-pre-wrap`；流式三点动画；`messageAppear` 0.3s |
| ChatPanel | props：主题/标题/快捷操作/取 token；`enable_rag` → context；`useLlmHealth` 状态点 |
| InterruptActions | 确认类按钮 / 文本输入提交 `resume`；取消走 `rejectInterrupt` |

**溢出三层 CSS（实现备忘）**：

| 层级 | CSS | 作用 |
|------|-----|------|
| 气泡容器 | `overflow-hidden; overflow-wrap: break-word` | 边界裁剪 |
| AI 内容区 | `flex-1 min-w-0 overflow-hidden` | flex 子项可收缩 |
| Markdown body | `word-wrap: break-word; overflow-wrap: anywhere` | 长 URL/ID |

Markdown 样式约定：暗色代码块、表格边框、引用竖线、链接蓝色（与现有主题一致即可）。

### 5.5 `useLlmHealth`

| 项 | 值 |
|----|-----|
| 轮询 | 约 30s → `GET {agentBase}/api/v1/llm/health` |
| 暴露 | `llmStatus` / `statusText` / `statusType` |
| 使用处 | `ChatPanel`、管理端 `AdminLayout` 在线标签（不再写死「在线」） |

### 5.6 布局与路由集成

| 文件 | 改动 |
|------|------|
| `PortalLayout.vue` | 导航增加「AI 客服」→ `/portal/chat` |
| `AdminLayout.vue` | 面包屑映射 `/admin/chat`；健康标签绑定 |
| `router/index.ts` | 注册 portal/admin chat 路由 |
| `ChatWidget` / `AdminChat` | 简化为 `router-link` 入口（全页对话替代浮层长聊） |

### 5.7 历史修复（v1.1，实现备忘）

| 问题 | 修复 |
|------|------|
| SDK 0.0.10 不转发 context/command | 升至 1.x |
| 流式 UI 不更新 | 经 `messages.value[idx]` 写回 |
| 长文本撑破气泡 | flex `min-w-0` + overflow-wrap |
| configurable + context 冲突 | context-only |
| 非流式丢失 | 同时处理 complete |
| error 无 UI | 推入消息列表 |

---

## 6 关键技术决策（实现侧）

> **原理性决策链** → 设计方案对应章节。本节只写落地时「怎么做 / 与代码绑定」的补充。

| # | 实现侧决策 | 代码落点 | 设计文档 |
|---|------------|----------|----------|
| 1 | 三级路由：L1 规则文件分 Agent、写操作不进 L1 | `regex_rules.py` + `regex_shortcut.py` | Part I §2.2 / §7 |
| 2 | userId 权威 = Gateway introspect；默认 **无** JWT 回退 | `introspect.py`、`INTROSPECT_FALLBACK_JWT=false` | Part I §6.1 |
| 3 | 多租户 = `metadata.owner`，非 Redis 分库 | `security/auth.py` + 前端 headers | Part I §5 / §6 |
| 4 | Checkpoint = inmem + `.langgraph_api` | `start_server.py` | Part I §5（修正 Redis Checkpoint） |
| 5 | `/admin` 路径自动解包 `R<T>` | `http_client.py` | —（实现约定） |
| 6 | Token 经 RunnableConfig / context 注入，不暴露给 LLM | tools + `extract_token_from_config` | Part I §6 |
| 7 | Admin 纯只读靠 Permission 剔工具 | `permission.py` `WRITE_TOOLS` | Part I §4 / §7 |
| 8 | 日报 / 偏好并发用 `asyncio.gather` | `generate_daily_report`、`analyze_user_preferences` | — |
| 9 | RAG 动态注入，失败不挡主链路 | `rag_context.py` + `rag_loader.py` | Part I §16 |
| 10 | 画像 Redis `profile:` + StringRedisTemplate 对齐 | Part II §8 | 设计 Part C |

---

## 7 部署与验收

### 7.1 启动流程（权威）

```text
1. 基础设施：MySQL / Redis / Nacos / RabbitMQ /（可选）ES
2. Java：gateway → item / user / cart / trade / pay / search / seckill / admin …
3. 复制 hmall-agent/.env.example → .env，填 DASHSCOPE_API_KEY、REDIS_HOST、JAVA_GATEWAY_URL
4. uv sync（或项目约定安装方式）
5. （可选 RAG）先启 LightRAG :9621，再 `uv run python start_rag_server.py`
6. `uv run python start_server.py`  → Agent（.env 中 AGENT_PORT，推荐 8090）
7. 前端 npm/pnpm dev，指向 Agent base URL
```

### 7.2 端口总览

| 服务 | 端口（默认） |
|------|----------------|
| hm-gateway | 8080 |
| Agent LangGraph | 8090（.env）；脚本 fallback 8091 |
| RAG MCP | 8008 |
| LightRAG | 9621 |
| 前端开发 | 依项目（常见 5173） |
| Nacos | 8848 |

微服务 item/cart/user/trade/search/admin 等端口以各服务配置为准。

### 7.3 启动检查清单

**基础**

- [ ] Gateway 可访问；C 端/管理端登录正常
- [ ] Agent `/ok` 200；`/docs` 可开
- [ ] `.env` 中 `JAVA_GATEWAY_URL`、`DASHSCOPE_API_KEY` 正确
- [ ] `INTROSPECT_FALLBACK_JWT=false`（生产建议保持）
- [ ] Redis 可达且 `PROFILE_REDIS_DB` 与后端一致
- [ ] `.langgraph_api/` 目录可写（Checkpoint/Store 落盘）

**身份与多租户**

- [ ] 登录后对话：`GET /users/me` 或 `/admin/info` 经 Gateway 成功
- [ ] 用户 A 的 thread 列表看不到用户 B
- [ ] 请求带 `Authorization` + `X-Hmall-Agent-Type`
- [ ] 换账号后本地会话被重置（owner 绑定）

**功能冒烟**

- [ ] 「查看购物车 / 查看订单」走 L1 快速返回
- [ ] 取消订单等触发 interrupt，确认后成功；拒绝可结束
- [ ] 管理端「运营日报」返回分区 Markdown
- [ ] 管理端无法调用加购等写工具（Permission）
- [ ] （可选）打开 RAG 开关后可检索知识库；关闭无 MCP 工具
- [ ] LLM health：前端在线状态随 API 变化
- [ ] Layer3：对话中可触发记忆保存/读取（Store 启用时）

**推荐相关清单见 Part II §5。**

### 7.4 常见启动失败对照

| 现象 | 排查 |
|------|------|
| Agent 起不来 | `.env` / `DASHSCOPE`；端口占用；先 `setup_environment` 再 import |
| 401 满屏 | Gateway 未起；token 过期；未带 Authorization |
| introspect 503 | `/users/me` 路由或 user-service 异常 |
| 管理端空数据 | `/admin` R\<T\> 解包失败；admin-token 误用 C 端 token |
| 画像一直 miss | Redis DB 不一致；用了 Jackson RedisTemplate 写入 |
| RAG 无工具 | 未开开关；MCP 未起；LightRAG 认证失败 |

---

## 8 已知问题与后续优化

| ID | 项 | 状态 | 说明 |
|----|-----|------|------|
| 8.1 | JWT 本地 jks | 可选 | `JWT_VERIFY_LOCAL`；默认走 introspect |
| 8.2 | RAG | **已完成** | MCP + Middleware + Skill；需独立启 LightRAG/MCP |
| 8.3 | L1 仅模式匹配 | 开放 | 口语变体仍靠 L3 |
| 8.4 | Checkpoint TTL | 开放 | inmem/本地 pickle **无**自动 7 天清理；长期需运维或换托管后端 |
| 8.5 | LLM 降级文案 | 未做 | 超时依赖平台默认错误 |
| 8.6 | 秒杀 pending 轮询 | 未集成 | 工具直接返回 pending/success/failed |
| 8.7 | 前端 v1.1 历史 bug | **已修复** | 见 §5.5 |

> 旧文「对话历史在 Redis db=1」已作废，以 §4.2.3 为准。

---

## 9 变更记录：多租户 + Gateway introspect（2026-09）

### 9.1 背景（实现问题）

1. 仅按 `thread_id` 存会话时，缺少用户级硬隔离，存在越权读风险。  
2. Agent 收不到 Gateway `user-info`；本地解码 JWT 无法与「Gateway 验签 → UserContext」对齐。

### 9.2 代码变更

| 位置 | 变更 |
|------|------|
| `hmall/.../UserController.java`（user-service） | **新增** `GET /users/me` → `{ userId, agentType }`，只读 UserContext |
| `hmall-agent/src/gateway/introspect.py` | **新增** Gateway 探查 + TTL 缓存 |
| `hmall-agent/src/security/auth.py` | **新增** Auth：introspect → owner；threads/store 过滤 |
| `hmall-agent/src/security/jwt_payload.py` | **新增** claim 对齐（C：`user` / 管：`sub`） |
| `hmall-agent/src/middleware/auth.py` | **修改** 异步路径 introspect 注入 `context.user_id` |
| `hmall-agent/src/core/config.py` | **新增** `INTROSPECT_CACHE_TTL`、`INTROSPECT_FALLBACK_JWT` |
| `hmall-agent/graph.json` + `start_server.py` | **新增** `auth`；注入 `LANGGRAPH_AUTH` |
| `hmall-agent/src/gateway/http_client.py` | **修改** JWT claim 与 Java 一致 |
| `hmall-frontend/.../useLangGraph.ts` | **修改** Authorization + `X-Hmall-Agent-Type`；create/search 带 owner |

### 9.3 行为约定（验收口径）

- `owner = {agent_type}:{user_id}`
- C 端 introspect：`GET /users/me`（经 Gateway）
- 管理端：`GET /admin/info`（admin-service；Gateway 对 `/admin/**` 放行策略按现网）
- 开发态 Checkpoint/Store：`.langgraph_api/`
- **默认不回退**本地 JWT 解码（`INTROSPECT_FALLBACK_JWT=false`）
- 换账号：前端清 thread 绑定，避免串会话

### 9.4 验收用例建议

| 用例 | 期望 |
|------|------|
| 无 Authorization 访问 threads | 401 |
| 用户 A search threads | 仅 `owner=customer:A` |
| 用户 A create_run 到 B 的 thread | 被 owner 过滤拒绝 |
| introspect 401 | Auth/中间件不注入错误 user_id；提示重新登录 |
| TTL 缓存 | 同 token 短时重复请求不打爆 `/users/me` |
| Store 跨用户 namespace | 403 |
| 管理端与 C 端同数字 ID | owner 前缀不同，会话不串 |

### 9.5 与 Checkpoint 落盘的关系

多租户隔离在 **Auth 过滤层**完成，与 Checkpoint 后端无关：即便开发态共用 `.langgraph_api` 目录，缺少匹配 `owner` 的客户端也无法 list/read 他人 thread。生产替换 Postgres 等后端时，保留 Auth 钩子即可，无需按用户分库。

---

# Part II 推荐与画像实现

---

## 1 概况与 Phase 对照

为 CustomerAgent 增加对话式推荐：后端 `GET /recommend` 三步管线（已购偏好 → ES 召回 → 销量/补充），Agent 侧 2 工具 + Formatter + Skill + 正则；Phase 2 落地 Redis 画像共享。

### 1.1 文件变更统计（Phase 1 累计）

| 类别 | 新增 | 修改 | 说明 |
|------|------|------|------|
| hm-api | 2 | 2 | SearchClient + Fallback；TradeClient 方法；Feign 注册 |
| search-service | 0 | 3 | `/search/recommend` ES |
| trade-service | 0 | 3 | `/orders/purchased-items` |
| item-service | 5 | 2 | RecommendController/Service/DTO/VO + Feign 启用 |
| Agent | 1 Skill | tools/formatters/prompts/regex/agent | 推荐能力 |
| Gateway | 0 | Nacos 手工 | `/recommend/**` → item-service |

### 1.2 Phase 对照

| 设计步骤 | 内容 | 状态 |
|----------|------|------|
| 1–5 | Agent 工具 / Formatter / Skill / Prompt / 正则 | ✅ |
| 6 | `GET /recommend` SQL/ES 管线 | ✅（Feign，非跨库 JOIN） |
| 7–8 | `POST /behaviors` + 前端埋点 | ⏸ 浏览埋点未做；写入改走后端旁路（见 §8） |
| 9–10 | Redis 画像 + Item-CF | 画像 ✅；Item-CF ⏸ |

---

## 2 简图 + 链接设计 Part B

> **完整推荐架构、触发模式、冷启动、技术决策全文** → 设计方案 [第二部分：个性化推荐设计](./hmall_Agent设计方案文档.md#第二部分个性化推荐设计)（下文称 Part B）。  
> **画像与记忆设计** → [第三部分：用户画像与主动通知设计](./hmall_Agent设计方案文档.md#第三部分用户画像与主动通知设计)。

```
对话「推荐」→ L1 get_recommendations_api 或 L3 选工具
       → Gateway GET /recommend（需登录）
       → item-service RecommendServiceImpl
            ├─（画像命中）读 Redis profile:*
            ├─（miss）Feign trade purchased-items → 聚合类目/品牌
            ├─ Feign search /search/recommend（ES）
            └─ 空则 MySQL 热销兜底 → RecommendVO
       → format_recommendations → 用户
```

---

## 3 Java 后端实现

### 3.1 hm-api（Feign）

| 文件 | 操作 | 说明 |
|------|------|------|
| `.../client/SearchClient.java` | 新增 | `@FeignClient("search-service")` → `GET /search/recommend` |
| `.../client/fallback/SearchClientFallbackFactory.java` | 新增 | search 不可用返回空列表 |
| `.../config/DefaultFeignConfig.java` | 修改 | 注册 Fallback Bean；`RequestInterceptor` 透传 `user-info` |
| `.../client/TradeClient.java` | 修改 | 新增 `queryPurchasedItems()` |

**SearchClient**：

```java
@FeignClient(value = "search-service", fallbackFactory = SearchClientFallbackFactory.class)
public interface SearchClient {
    @GetMapping("/search/recommend")
    List<ItemDTO> recommend(
            @RequestParam(value = "categories", required = false) List<String> categories,
            @RequestParam(value = "excludeIds", required = false) List<Long> excludeIds,
            @RequestParam("size") Integer size);
}
```

**Fallback（实现要点）**：`create(Throwable)` 返回空 `List`，让 `RecommendServiceImpl` 走 MySQL 热销，而不是直接 500。

**TradeClient 增量**：

```java
@GetMapping("/orders/purchased-items")
List<OrderDetailDTO> queryPurchasedItems();
```

`userId` 不显式传参，依赖 Feign 拦截器透传 Gateway/`user-info`（服务间直连不经 Gateway，但 header 仍由 DefaultFeignConfig 写入）。

### 3.2 search-service（ES 召回）

| 文件 | 操作 |
|------|------|
| `SearchController.java` | 新增推荐端点 |
| `ISearchService.java` | `recommendSearch(...)` |
| `SearchServiceImpl.java` | RestHighLevelClient BoolQuery |

**Controller 形态**（路径以实际 `@RequestMapping("/search")` + `@GetMapping("/recommend")` 为准）：

```java
@GetMapping("/recommend")
public List<ItemDTO> recommend(
        @RequestParam(value = "categories", required = false) List<String> categories,
        @RequestParam(value = "excludeIds", required = false) List<Long> excludeIds,
        @RequestParam("size") Integer size) throws IOException {
    return searchService.recommendSearch(categories, excludeIds, size);
}
```

**ES 查询构造（实现）**：

| 条件 | Query |
|------|-------|
| 有类目 | `filter(termsQuery("category", categories))` |
| 排除 ID | `mustNot(termsQuery("_id", excludeIds))` |
| 无偏好 | 空 Bool ≈ match_all |
| 排序 | `FieldSortBuilder("sold").order(DESC)` |
| size / timeout | 请求 size；`TimeValue(10, SECONDS)` |
| 索引名 | `items` |
| 转换 | `ItemDoc` → `ItemDTO` |

### 3.3 trade-service（已购聚合）

| 文件 | 操作 |
|------|------|
| `OrderController.java` | `GET /orders/purchased-items` |
| `IOrderService` / `OrderServiceImpl` | 聚合实现 |

**实现步骤（权威）**：

1. `UserContext.getUser()`；空则返回空列表。  
2. 查 `order`：`userId` 匹配且 `status IN (2,3,4,6)`。  
3. 批量查 `order_detail`。  
4. `Map<itemId, num>` merge 求和。  
5. 转为 `OrderDetailDTO(itemId, num)` 列表（**不含** category/brand）。

### 3.4 item-service：启用 Feign + RecommendController

**pom 增量依赖**：`spring-cloud-starter-openfeign`、`spring-cloud-starter-loadbalancer`、`feign-okhttp`、`hm-api`（`com.heima:hm-api:1.0.0`）。

**Application**：

```java
@EnableFeignClients(
    basePackages = "com.hmall.api.client",
    defaultConfiguration = DefaultFeignConfig.class)
public class ItemApplication { ... }
```

**RecommendController**：

```java
@RestController
@RequestMapping("/recommend")
@RequiredArgsConstructor
public class RecommendController {
    private final IRecommendService recommendService;

    @GetMapping
    public RecommendVO recommend(
            @RequestParam(defaultValue = "home") String scene,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long itemId) {
        Long userId = UserContext.getUser();
        return recommendService.recommend(userId, scene, size, itemId);
    }
}
```

新增类型：`IRecommendService`、`RecommendServiceImpl`、`RecommendItemDTO`、`RecommendVO`（含内部类 `BasedOn`）。

### 3.5 `RecommendServiceImpl` 管线（实现权威）

伪代码级流程（与源码一致的顺序）：

```java
public RecommendVO recommend(Long userId, String scene, Integer size, Long itemId) {
    // 1) 召回参数
    if ("detail".equals(scene) && itemId != null) {
        // 种子类目 + excludeIds=[itemId]
    } else if (userId != null) {
        // Phase2: 先读 Redis profile:{uid}:*
        // miss: safeQueryPurchasedItems() → listByIds 补类目品牌 → topN(3)
        // excludeIds = 已购 itemId
    }

    // 2) searchClient.recommend(categories, excludeIds, size)
    // 3) 空 → mysqlHotFallback；isFallback=true
    // 4) listByIds 补 stock/status；过滤 status!=1
    // 5) generateTags；组装 RecommendVO（冷启动 basedOn=null）
}
```

**容错**：

```java
private List<OrderDetailDTO> safeQueryPurchasedItems() {
    try {
        return tradeClient.queryPurchasedItems();
    } catch (Exception e) {
        log.error("Feign trade 失败，降级空列表", e);
        return new ArrayList<>();
    }
}
```

**mysqlHotFallback**：`status=1`，`notIn` 排除 ID，`ORDER BY sold DESC LIMIT size`。

**标签规则（实现）**

| 条件 | 标签 |
|------|------|
| `isFallback=true` | `["热销推荐"]` |
| scene=detail | `["相似推荐"]` + 品牌命中时 `"您常买的品牌"` |
| scene=home/cart | `"同类目热销"` / `"您常买的品牌"`；全无则热销兜底标签 |

**DTO/VO 字段**

| 类型 | 字段 |
|------|------|
| `RecommendItemDTO` | id, name, price(分), stock, brand, category, sold, recommendTags |
| `RecommendVO` | list, total, basedOn |
| `BasedOn` | topCategories, topBrands |

### 3.6 与三级路由的衔接（实现侧）

推荐不改 L2（无 interrupt）。L1 新增 home/cart 正则；detail 走 L3 抽 `item_id`。见 Part I §4.5.3 与设计 Part B「触发模式」链接。

---

## 4 Agent 侧实现细节

### 4.1 工具

文件：`hmall-agent/src/agents/customer/tools.py`（计入总表 22 中的推荐 2 个）。

#### `get_recommendations_api`

```python
@tool
async def get_recommendations_api(
    config: RunnableConfig,
    scene: str = "home",
    size: int = 10,
    item_id: int = 0,
) -> str:
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
        return f"推荐服务暂时不可用，您可以尝试搜索商品。错误: {e}"
```

#### `analyze_user_preferences`（含 Phase2）

1. 无 token → 登录提示。  
2. `_extract_user_id` → `profile_store.get_profile`；有 categories/brands → `format_preferences`（**0 次 Gateway**）。  
3. miss：`asyncio.gather(/orders/page, /carts)`。  
4. 收集 itemId → `GET /items?ids=` 补 category/brand。  
5. `_accumulate_preference`：购买 weight=5，购物车 weight=3。  
6. `backfill_profile`（失败吞掉）。  
7. `format_preferences(...)`。

```python
def _accumulate_preference(cat_scores, brand_scores, prices, item, weight):
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

### 4.2 Formatter

**`format_recommendations`**：标题随 scene（猜你喜欢 / 看了又看 / 凑单）；表头 `#|商品|价格|库存|标签|ID`；`basedOn` 以引用块输出偏好类目/品牌。

**输出示例**：

```markdown
## 🎯 猜你喜欢（共 10 件）

| # | 商品 | 价格 | 库存 | 标签 | ID |
|---|------|------|------|------|-----|
| 1 | iPhone 15 Pro | ¥7999.00 | 45 件 | 同类目热销, 您常买的品牌 | `2001` |

> **推荐依据**: 偏好类目: 手机, 耳机 | 偏好品牌: Apple, Sony
```

**`format_preferences`**：画像直读时可无 orders/cart 计数；实时模式展示「基于 N 笔订单 + M 件购物车」+ Top3 类目/品牌 + 价格区间。空数据友好文案。

### 4.3 Skill / Prompt / 正则

| 项 | 路径 / 内容 |
|----|-------------|
| Skill | `workspace/customer/skills/personalized-recommendation/SKILL.md` |
| Prompt | `prompts.py`：能力第 6 条 + 主动推荐准则 + 表格/引用输出 |
| 正则 | Part I §4.5.3；`_extract_recommend_scene` |

**Skill 场景表（实现）**：

| 场景 | 触发语例 | 工具链 |
|------|----------|--------|
| 首页 | 有什么推荐 / 猜你喜欢 | `get_recommendations_api(home)` |
| 看了又看 | 还有类似的吗 | `get_recommendations_api(detail, item_id)` |
| 凑单 | 购物车还能加点什么 | `get_recommendations_api(cart)` |
| 偏好驱动 | 想换个手机 | `analyze_user_preferences` → `search_items_api` / recommend |

`agent.py` 的 `sources` 须包含 `/skills/personalized-recommendation/`（及 `/skills/rag-query/`）。

**正则补充**：

```python
def _extract_recommend_scene(m: re.Match) -> dict:
    keyword = m.group(1) if m.groups() else ""
    if "凑单" in keyword or "购物车" in keyword:
        return {"scene": "cart"}
    return {"scene": "home"}
```

「看了又看」不进 L1：正则无法取上下文 `item_id`。

---

## 5 Gateway / Nacos 配置与检查清单

### 5.1 Nacos 路由（手工）

dataId：`gateway-routes.json`（示例环境 `192.168.100.128:8848`）新增：

```json
{
  "id": "item-recommend",
  "predicates": [{ "name": "Path", "args": { "pattern": "/recommend/**" } }],
  "uri": "lb://item-service"
}
```

### 5.2 认证

`/recommend/**` **不得**加入 `hm.auth.excludePaths`。Gateway 验签后写入 `user-info`，item-service `UserContext.getUser()` 读取。

典型免认证列表（**参考**，以现网 `application.yml` / Nacos 为准；注意 `/recommend/**` 不在其中）：

```yaml
hm:
  auth:
    excludePaths:
      - /search/**
      - /users/login
      - /users/login/code
      - /users/code
      - /items/**
      - /admin/**
```

说明：`/admin/**` 在 Gateway 侧可能放行并由 admin-service 自验；C 端业务除 exclude 外均需 JWT。`GET /users/me` **需要**认证（introspect 依赖此点）。

### 5.3 检查清单

- [ ] Nacos 已配 `/recommend/**` → `lb://item-service`
- [ ] excludePaths **不含** `/recommend/**`
- [ ] item-service Feign + `@EnableFeignClients` 已启用
- [ ] trade-service / search-service / ES `items` 有数据
- [ ] Agent 已加载推荐工具与 Skill
- [ ] 登录后「有什么推荐」有列表；未登录有登录提示
- [ ] 新用户无订单 → 热销兜底
- [ ] 推荐接口失败 → Agent「推荐服务暂时不可用…」

---

## 6 与设计文档的偏差说明

> **本节为偏差权威**。设计原文见 Part B；下列为落地修正，勿再按设计假设跨库 JOIN。

| # | 设计假设 | 实际 | 修正 |
|---|----------|------|------|
| 1 | item-service 直接查 ES | ES 能力在 search-service；item 内 ES 依赖为拆分残留 | Feign `SearchClient` |
| 2 | Formatter 纯文本 `─` 分隔 | 全站 Markdown + 前端 marked | Markdown 表/引用，复用 `_table_row` / `_yuan` |
| 3 | `/recommend` 挂 ItemController | `/items/**` 在 excludePaths 免认证 | 独立 `RecommendController` @ `/recommend` |
| 4 | RecommendMapper 跨表 JOIN `order_detail`+`order`+`item` | 微服务数据库隔离 | Feign `purchased-items` + item 表聚合 |

**偏差 4 详细背景**：设计 Part B 曾假设 item 与 trade 共享同一 MySQL（如 `shared-jdbc.yaml`），可直接跨表 JOIN。探索确认库独立后，改为 trade 只返回 `itemId+num`，item-service 查自有 `item` 表补类目/品牌再加权 TopN。Feign 失败时空列表 → 热销兜底，保证可用性。

**实现侧补充（非偏差，但易误解）**：

| 点 | 说明 |
|----|------|
| 推荐理由 | 后端只给 `recommendTags` + `basedOn`；自然语言理由由 LLM 组织（与设计一致） |
| scene 参数 | 由 Agent/LLM 选择，后端不猜对话意图 |
| Markdown | 与「偏差 2」同一决策，保证 ChatPanel 渲染一致 |

Phase2 追加偏差见 [§8.7](#87-phase2-与设计的偏差权威补充)。

---

## 7 降级、回滚、测试场景

### 7.1 降级链路

```
推荐请求
  ├─ 正常：偏好（画像或 Feign）→ ES → MySQL 补充 → VO
  ├─ trade 不可用：空偏好 → ES 全局热销
  ├─ search 不可用 / ES 空：MySQL 热销兜底（isFallback）
  ├─ MySQL 也空：空列表 → Formatter「暂无推荐」
  └─ Agent HTTP 错：搜索引导文案；401：重新登录
```

| 层级 | 场景 | 行为 | 用户感知 |
|------|------|------|----------|
| trade | 宕机 | `safeQueryPurchasedItems` → [] | 仍有热销，个性化弱 |
| search | 宕机 | Fallback → [] → MySQL | 热销标签 |
| item | ES 无命中 | mysqlHotFallback | 同上 |
| item | MySQL 空 | 空 list | 「暂无推荐商品…」 |
| Agent | GatewayError | 捕获 | 「推荐服务暂时不可用…」 |
| Agent | 未登录 | token 检查 | 「需要先登录」 |
| 偏好分析 | `/items` 失败 | catch | 类目/品牌可能缺失 |
| 画像写 | Redis 异常 | 吞异常 | 下单/加购主流程不受影响 |

### 7.2 回滚

推荐为增量能力，回滚不伤主交易链路：

1. **Agent**：`get_all_tools()` 去掉 2 工具；`sources` 去掉 Skill；注释推荐正则（文件可留）。  
2. **后端**：下线 `RecommendController`；Nacos 删 `/recommend/**`；Feign 依赖可留。  
3. **数据**：Phase1 无新表；Phase2 可按需 `DEL profile:{uid}:*`。  
4. **画像旁路**：注释 `paySuccessListener` / `CartServiceImpl` 中 HINCRBY 即可关闭写画像，主业务不变。

### 7.3 测试场景表

| 场景 | 验证点 |
|------|--------|
| L1「推荐」 | 快路径；scene=home |
| L1「购物车推荐」/「凑单」 | scene=cart |
| 未登录 | 登录提示，无 500 |
| 新用户 | 热销；basedOn 空 |
| scene=detail | 传 itemId |
| search/trade 故障 | 对应降级 |
| Formatter 空 | 友好空态 |
| 画像命中 | analyze 少打 Gateway |
| 推荐→加购 | 对话闭环 |
| 多租户 | A 不可见 B 的 thread/推荐会话 |

### 7.4 手工联调顺序建议

1. Gateway 直连：`GET /recommend?scene=home`（带 JWT）看 VO。  
2. 停 search → MySQL 兜底；停 trade → 全局热销。  
3. Agent：正则句 + 自然语言句各测一组。  
4. Redis：支付/加购后 `HGETALL profile:{uid}:categories` 有增量。

---

## 8 Phase2 画像落地说明

> 设计对照：设计方案第三部分。下列为 **实现归属与序列化权威**。

### 8.1 写入归属

| 行为 | 写入方 | 说明 |
|------|--------|------|
| 支付成功 purchase | Java `paySuccessListener` | 查订单详情 + ItemClient 补商品信息后 HINCRBY；覆盖所有支付入口 |
| 加购 cart | Java `CartServiceImpl.addItem2Cart` | 覆盖 Agent + 前端 UI；**Agent `add_to_cart_api` 不再写画像** |
| 改数量 | 不写 | `update_cart_quantity_api` 不记 cart 事件 |
| 确认收货 | 不在 Agent 写 | 已由支付旁路覆盖购买信号，避免双重计数 |
| 偏好 miss 回写 | Agent `backfill_profile` | 分析工具旁路补齐 |
| 浏览 view | 未做前端埋点 | 权重预留 view=1 |

### 8.2 Redis Key 与结构

前缀：**`profile:`**（非设计稿 `up:`；非 ZSet 主结构）。

| Key | 类型 | 用途 |
|-----|------|------|
| `profile:{uid}:categories` | Hash | 类目分（HINCRBY） |
| `profile:{uid}:brands` | Hash | 品牌分 |
| `profile:{uid}:prices` | List | 价格样本（有上限，约 20） |
| `profile:{uid}:stats` | Hash | 统计计数 |
| `profile:{uid}:events` | List | 行为流，最多约 50，TTL 7 天 |

聚合画像 TTL 约 30 天。权重与 Phase1 一致：purchase=5，cart=3，view=1。

Agent `ProfileStore.record_event`：pipeline 批量；异常吞掉，不影响主业务。

### 8.3 序列化兼容（关键）

| 客户端 | 序列化 | 画像是否可用 |
|--------|--------|--------------|
| 后端业务 `RedisTemplate` + Jackson | 二进制/JSON 包装 | **不可**与 Agent 混用同一 key |
| 后端 `StringRedisTemplate` | 纯字符串 | ✅ 画像专用 |
| Agent `redis.asyncio` + `decode_responses=True` | 纯字符串 | ✅ |

`PROFILE_REDIS_DB` 必须等于后端 `spring.redis.database`（默认 0）。

### 8.4 读取路径

| 调用方 | 行为 |
|--------|------|
| Agent `analyze_user_preferences` | `get_profile` 命中 → 直接 Formatter；miss → Phase1 实时 + backfill |
| Java `RecommendServiceImpl` | 优先读同一套 Hash；miss → Feign purchased-items 聚合 |

### 8.5 Agent 侧文件清单（校正）

| 文件 | 说明 |
|------|------|
| `hmall-agent/src/user_profile/store.py` | `ProfileStore`（旧笔记 `src/profile/` 路径作废） |
| `hmall-agent/src/user_profile/memory.py` | Layer3 `save_memory` / `get_memories` |
| `tools.py` | 画像优先 + backfill；加购/确认收货画像写入已移除 |
| `formatters.py` | `format_preferences` 兼容直读 |
| `config.py` | `PROFILE_REDIS_DB`、`LANGGRAPH_STORE_URI`（可空） |

### 8.6 后端侧文件清单

| 文件 | 说明 |
|------|------|
| `paySuccessListener`（pay/trade 侧） | 支付成功写 purchase 画像 |
| `CartServiceImpl` | 加购写 cart 画像 |
| `RecommendServiceImpl` | 读画像加速偏好 |

### 8.7 Phase2 与设计的偏差（权威补充）

| # | 设计 | 实现 |
|---|------|------|
| A | `POST /behaviors` + MQ Consumer | 后端旁路直接写 Redis；无新增行为 API |
| B | `up:` + ZSet | `profile:` + Hash/List + HINCRBY |
| C | Agent 与后端各自写可能双计 | 明确写入归属（§8.1） |
| D | Jackson RedisTemplate 读写画像 | 强制 StringRedisTemplate |

### 8.8 仍待项

| 项 | 状态 |
|----|------|
| 前端浏览埋点（如 ProductDetail onMounted） | 未做 |
| Item-CF 共现矩阵 `cf:{itemId}` | 未做 |
| 向量召回 / 实时反馈闭环 | 未做 |
| 推荐正则 Nacos 动态加载 | 未做 |

---

## 9 文档关联索引

| 资源 | 关系 |
|------|------|
| [`hmall_Agent设计方案文档.md`](./hmall_Agent设计方案文档.md) | 设计权威（Why / 决策全文）；系统篇 + 推荐 Part B + 画像 Part C |
| [`hmall_Agent项目说明文档.md`](./hmall_Agent项目说明文档.md) | 产品向说明（动机与效果） |
| `hmall-agent/graph.json` | 双图注册 + in_memory store + auth |
| `hmall-agent/start_server.py` | inmem Server、CORS、`LANGGRAPH_*` 注入 |
| `hmall-agent/start_rag_server.py` | RAG MCP 默认 :8008 |
| `hmall-agent/.env.example` | 环境变量权威全文（见 Part I §4.8.3） |
| `hmall-agent/src/agents/customer/tools.py` | C 端 22 工具（含推荐/记忆） |
| `hmall-agent/src/agents/customer/regex_rules.py` | C 端 L1 规则权威 |
| `hmall-agent/src/agents/admin/tools.py` | 管理端 11 工具 |
| `hmall-agent/src/gateway/http_client.py` | Gateway 客户端 + `/admin` R\<T\> 解包 |
| `hmall-agent/src/gateway/introspect.py` | Gateway 身份探查 |
| `hmall-agent/src/security/auth.py` | 多租户 Auth（threads + store） |
| `hmall-agent/src/middleware/*` | Auth / Permission / Regex / RAG |
| `hmall-agent/src/user_profile/*` | Redis 画像 + Layer3 记忆工具 |
| `hmall-agent/src/tools/formatters.py` | Markdown Formatter 权威 |
| `hmall-agent/src/mcp_servers/rag_server.py` | LightRAG MCP 桥接 |
| `hmall-frontend/src/composables/useLangGraph.ts` | SDK 1.x / owner / context-only |
| `hmall-frontend/src/composables/useLlmHealth.ts` | LLM 健康轮询 |
| `hmall-frontend/src/components/chat/*` | ChatPanel / MessageBubble / InterruptActions |
| `hmall/.../RecommendController.java` | `GET /recommend` |
| `hmall/.../RecommendServiceImpl.java` | 三步管线 + 画像优先 |
| `hmall/.../SearchClient.java` / `TradeClient.java` | Feign 基础设施 |
| `hmall/.../OrderController` `purchased-items` | 已购聚合 |
| `hmall/.../UserController` `GET /users/me` | introspect 依赖端点 |
| `docs/秒杀功能实现/*`（若仓库仍保留） | 秒杀域关联设计 |

> **路径纠正**：旧文档中的 `docs/Agent功能相关文档/`、`hmall-agent-design.md`、`agent-personalized-recommendation-design.md`、`hmall-agent-implementation-report.md` 等已合并/迁移至 **`docs/hmall_Agent相关文档/`** 下三份文档，请勿再引用旧路径。

---

**实现完成度（摘要）**：双 Agent、三级路由、22+11 工具、10 Skills、Gateway introspect + owner 多租户、inmem Checkpoint、推荐 Phase1+画像 Phase2、RAG MCP、前端 SDK 1.x 全页对话均已落地；Item-CF、浏览埋点、LLM 友好降级文案、秒杀结果轮询等仍为后续项。

**本文维护约定**：只更新 How（路径、行为、配置、验收、偏差、变更）。动机与决策链请改设计方案；产品表述请改项目说明。三份文档交叉链接时统一使用 `docs/hmall_Agent相关文档/` 路径。

---

*文档结束。*
