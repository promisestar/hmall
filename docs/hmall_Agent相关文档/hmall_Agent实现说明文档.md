# hmall Agent 实现说明文档

> 整合 hmall 枫叶商城 AI 智能助手的全部实现报告，涵盖系统实现与个性化推荐实现两部分。

---

## 目录

- [第一部分：hmall Agent 系统实现](#第一部分hmall-agent-系统实现) — 项目概况 / 功能实现 / 文件清单 / 关键技术决策 / 前端适配 / 后续优化
- [第二部分：个性化推荐实现](#第二部分个性化推荐实现) — 功能验证 / 测试结果 / 性能分析 / Phase 2 用户画像持久化 / 后续优化

---

# 第一部分：hmall Agent 系统实现
# hmall Agent 智能助手实现说明文档

> 版本：v1.1  
> 日期：2026-07-16  
> 设计文档：`docs/Agent功能相关文档/hmall-agent-design.md`
>
> v1.1 变更：SDK 升级至 1.x、前端重构为独立页面 + Markdown 渲染、修复 Vue 响应式断链和文字溢出 bug、移除 configurable 改用 context-only

---

## 一、实现概况

本次实现按照 `hmall-agent-design.md` v2.0 设计文档，从零构建了 hmall Agent 智能助手系统（Python DeepAgents + LangGraph），包含 CustomerAgent（C 端客服助手）和 AdminAgent（管理助手）两个 Agent，以及 Vue 3 前端集成。涵盖三级路由（正则→interrupt→LLM）、双 JWT 认证、28 个工具、Skills 规范、Redis Checkpoint 对话记忆等完整链路。

### 1.1 文件变更统计

| 类别 | 数量 | 说明 |
|------|------|------|
| Agent 后端 Python 新增 | 30 | 核心配置 3 + Gateway 2 + 中间件 4 + CustomerAgent 4 + AdminAgent 4 + 格式化 1 + 服务配置 3 + MCP 1 + `__init__` 8 |
| Agent 后端 Skills 新增 | 8 | C 端 5 个 SKILL.md + 管理端 3 个 SKILL.md |
| Agent 后端配置新增 | 3 | `pyproject.toml` + `graph.json` + `.env.example` |
| Agent 后端启动脚本新增 | 1 | `start_server.py` |
| Agent 后端测试新增 | 2 | 正则路由测试 + 格式化函数测试 |
| Agent 后端文档新增 | 1 | `README.md` |
| **Agent 后端合计** | **45** | — |
| 前端 Composable | 2 | `useLangGraph.ts`（SDK 1.x 原生 API + context-only + 响应式修复）+ `useLlmHealth.ts`（LLM 健康状态 30 秒轮询） |
| 前端组件新增 | 3 | `ChatPanel.vue`（可复用全页对话） + `portal/ChatPage.vue` + `admin/ChatPage.vue` |
| 前端组件修改 | 3 | `MessageBubble.vue`（Markdown 渲染 + 溢出修复） + `ChatWidget.vue`（简化为路由入口） + `AdminChat.vue`（简化为路由入口） |
| 前端修改 | 4 | `package.json`（SDK ^1.0.3 + marked） + `router/index.ts`（+2 路由） + `PortalLayout.vue`（+AI客服链接） + `AdminLayout.vue`（+面包屑） |
| **前端合计** | **11** | — |
| **总计改动** | **53** | — |

---

## 二、架构总览

### 2.1 系统架构

```
前端 (Vue 3)
  │  @langchain/langgraph-sdk (HTTP + SSE)
  ▼
┌──────────────────────────────────────────────────────────────────┐
│              Agent Service (LangGraph Server :8090)                │
│                                                                    │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │  LangGraph API 层                                            │   │
│  │  ├── /threads/{id}/runs/stream（SSE 流式）                   │   │
│  │  ├── /assistants/{id}/runs/stream（专用端点）                │   │
│  │  ├── /threads（线程管理）                                     │   │
│  │  ├── /api/v1/batch-report（自定义路由：批量运营报告）          │   │
│  │  └── /api/v1/llm/health（自定义路由：LLM 连通性检查）           │   │
│  └───────────────────────┬────────────────────────────────────┘   │
│                          │                                         │
│  ┌───────────────────────▼────────────────────────────────────┐   │
│  │  中间件层（DeepAgent Middleware Chain）                      │   │
│  │  ├── AuthMiddleware（双 JWT 透传）                            │   │
│  │  ├── PermissionMiddleware（AdminAgent 纯只读过滤）             │   │
│  │  ├── RegexShortcutMiddleware（L1 正则快捷路由 <5ms）          │   │
│  │  ├── SkillsMiddleware（SKILL.md 规范加载）                    │   │
│  │  └── RAGMiddleware（RAG 动态工具注入，已实现）                │   │
│  └───────────────────────┬────────────────────────────────────┘   │
│                          │                                         │
│  ┌───────────────────────▼────────────────────────────────────┐   │
│  │  Agent 层                                                     │   │
│  │  ├── CustomerAgent（18 工具, 5 Skills, interrupt 二次确认）   │   │
│  │  └── AdminAgent（10 只读工具 + 日报编排, 3 Skills）           │   │
│  └───────────────────────┬────────────────────────────────────┘   │
│                          │                                         │
│  ┌───────────────────────▼────────────────────────────────────┐   │
│  │  基础设施                                                     │   │
│  │  ├── Redis Checkpoint（db=1 隔离，对话记忆 + interrupt 恢复）  │   │
│  │  ├── DeepAgents（Agent 框架）                                 │   │
│  │  └── 通义千问 qwen-turbo（LLM，OpenAI 兼容接口）              │   │
│  └────────────────────────────────────────────────────────────┘   │
└──────────────────────────┬───────────────────────────────────────┘
                           │ httpx (异步 HTTP)
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│              hm-gateway (:8080)                                    │
│  ├── AuthGlobalFilter（JWT 认证 + user-info 透传）                    │
│  └── RateLimitFilter（秒杀限流）                                    │
└──┬──────┬────────┬────────┬────────┬────────┬───────────────────┘
   ▼      ▼        ▼        ▼        ▼        ▼
 item   cart    user    trade    search    admin
:8081   :8082   :8084   :8085   :8089     :8090
```

### 2.2 三级路由请求流转

```
用户消息（stream.submit）
  │
  ├─ L1: RegexShortcutMiddleware (<5ms)
  │   ├── 拦截 awrap_model_call，在 LLM 调用前检查最后一条 human message
  │   ├── 匹配 "查看订单" / "秒杀活动" / "运营日报" 等高频指令
  │   ├── 直接 ainvoke 对应 @tool + 代码格式化输出
  │   ├── 返回 AIMessage（无 tool_call）→ Agent 图直接到 END
  │   └── 不匹配 → 传递给下一层
  │
  ├─ L2: LangGraph interrupt() (多轮交互 / 二次确认)
  │   ├── 地址新增/修改：interrupt 收集字段 → 用户回复 → 执行
  │   ├── 秒杀下单：查商品 → interrupt 确认 → 用户回复"确认" → 下单
  │   └── 二次确认：取消订单/确认收货/删除购物车/清空购物车
  │
  └─ L3: LLM 兜底 (~2s)
      ├── 闲聊 / 复杂问题 / L1/L2 未命中
      └── LLM 自主选择工具调用（DeepAgent Agent Loop）
```

### 2.3 Token 传递链

```
前端 ChatWidget.vue
  │  sessionStorage.getItem('token')  → C 端 JWT
  │  sessionStorage.getItem('admin-token')  → 管理端 JWT
  ▼
useLangGraph.ts → client.runs.stream(threadId, assistantId, {
    input: {messages: [...]},
    context: {agent_type: "customer", user_token: "eyJhbG..."}
  })
  │
  ▼
LangGraph Runtime 创建 Context(user_token="...", agent_type="customer")
  │
  ▼
AuthMiddleware.awrap_model_call
  ├── 读取 request.runtime.context.user_token
  ├── JWT_VERIFY_LOCAL=false → 透传 token（依赖 Gateway 验证）
  └── 注入 user_id 到 context（本地验证时）
  │
  ▼
工具调用（@tool 函数）
  ├── extract_token_from_config(config) 从 RunnableConfig 提取 token
  └── gateway_client.get("/carts", token=token) → Authorization 头携带 JWT
  │
  ▼
hm-gateway AuthGlobalFilter
  ├── 解析 JWT → 提取 userId
  └── 写入 user-info 头传递下游微服务
```

---

## 三、Python Agent 后端实现详情

### 3.1 项目结构

```
hmall-agent/
├── start_server.py                    # 服务启动入口
├── graph.json                         # Agent 图注册配置
├── pyproject.toml                     # 依赖声明
├── .env.example                       # 环境变量模板
├── README.md                          # 项目说明
│
├── src/
│   ├── core/
│   │   ├── config.py                  # Pydantic Settings 配置
│   │   ├── llms.py                    # LLM 实例（qwen-turbo）
│   │   └── redis_checkpoint.py        # Redis Checkpoint 后端
│   │
│   ├── gateway/
│   │   ├── http_client.py             # 异步 HTTP 客户端（httpx）
│   │   └── auth.py                    # JWT 验证（预留桩）
│   │
│   ├── middleware/
│   │   ├── auth.py                    # AuthMiddleware 双 JWT 透传
│   │   ├── permission.py              # PermissionMiddleware 工具权限
│   │   ├── regex_shortcut.py          # RegexShortcutMiddleware L1 路由
│   │   └── rag_context.py             # RAGMiddleware（已实现：动态注入 RAG 工具）
│   │
│   ├── agents/
│   │   ├── customer/
│   │   │   ├── agent.py               # CustomerAgent 定义
│   │   │   ├── prompts.py             # 系统提示词
│   │   │   ├── tools.py               # 18 个 @tool 工具
│   │   │   └── regex_rules.py         # L1 正则规则
│   │   │
│   │   └── admin/
│   │       ├── agent.py               # AdminAgent 定义
│   │       ├── prompts.py             # 系统提示词
│   │       ├── tools.py               # 10 个只读工具 + 日报编排
│   │       └── regex_rules.py         # L1 正则规则
│   │
│   ├── tools/
│   │   └── formatters.py              # 格式化函数（15 个）
│   │
│   ├── api/
│   │   ├── batch_report.py            # 自定义 FastAPI 路由（批量报告 + 健康检查）
│   │   └── health.py                  # LLM 连通性检查路由（GET /api/v1/llm/health）
│   │
│   ├── mcp_servers/
│   │   └── rag_server.py              # RAG MCP Server（已实现：LightRAGClient + 3 工具）
│   │
│   └── workspace/                     # Skills 文件
│       ├── customer/skills/           # 5 个 C 端 SKILL.md
│       └── admin/skills/              # 3 个管理端 SKILL.md
│
└── tests/
    ├── test_regex_rules.py            # 正则路由匹配测试
    └── test_formatters.py             # 格式化函数测试
```

### 3.2 核心基础设施

#### 3.2.1 配置管理（`src/core/config.py`）

使用 `pydantic-settings` 集中管理所有环境变量，`@lru_cache` 实现单例：

```python
class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # LLM
    DASHSCOPE_API_KEY: str = ""
    LLM_MODEL_NAME: str = "qwen-turbo"
    LLM_API_BASE: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"

    # Redis（Checkpoint 后端）
    REDIS_DB: int = 1  # db=1 与 hmall 业务数据（db=0）隔离

    # JWT
    JWT_VERIFY_LOCAL: bool = False  # false 时依赖 Gateway 验证

    @property
    def redis_url(self) -> str:
        auth = f":{self.REDIS_PASSWORD}@" if self.REDIS_PASSWORD else ""
        return f"redis://{auth}{self.REDIS_HOST}:{self.REDIS_PORT}/{self.REDIS_DB}"
```

#### 3.2.2 LLM 实例（`src/core/llms.py`）

通过 `langchain-openai` 的 `ChatOpenAI` 接入通义千问（OpenAI 兼容接口）：

```python
qwen_model = ChatOpenAI(
    model=_settings.LLM_MODEL_NAME,        # qwen-turbo
    api_key=_settings.DASHSCOPE_API_KEY,
    base_url=_settings.LLM_API_BASE,       # DashScope 兼容地址
    temperature=_settings.LLM_TEMPERATURE, # 0.7
    max_tokens=_settings.LLM_MAX_TOKENS,   # 2048
)
```

#### 3.2.3 Redis Checkpoint（`src/core/redis_checkpoint.py`）

复用 hmall Redis，使用 `db=1` 隔离，`langgraph-checkpoint-redis` 自动管理对话状态：

```python
checkpointer = RedisSaver(redis_url=_settings.redis_url)
```

| 机制 | 说明 |
|------|------|
| Thread | 每个对话线程有唯一 `thread_id`，前端通过 SDK 创建和管理 |
| Checkpoint | 每次图节点执行后自动保存状态（messages, interrupt 状态等）到 Redis |
| 恢复 | 通过 `thread_id` 自动加载历史消息，支持 interrupt 中断恢复 |
| 清理 | 删除 Thread 时自动清理 Checkpoint 数据 |

### 3.3 Gateway HTTP 客户端（`src/gateway/http_client.py`）

封装 httpx 异步调用 hmall Gateway，自动处理 C 端/管理端响应格式差异：

| 特性 | 说明 |
|------|------|
| 异步 | 所有方法为 `async`，使用 `httpx.AsyncClient` |
| Token 注入 | 所有请求自动携带 `authorization` 头（JWT Token） |
| C 端响应 | 直接返回业务数据（Gateway 不包装） |
| 管理端响应 | 自动解包 `R<T>`（路径以 `/admin` 开头时检查 `{code, msg, data}`） |
| 错误处理 | 429→秒杀限流、401→登录过期、≥400→Gateway 错误，抛出 `GatewayError` |
| 超时 | connect=5s, read=30s, write=10s, pool=5s |

**Token 提取辅助函数**：

```python
def extract_token_from_config(config) -> str:
    """从 LangGraph RunnableConfig 中提取 user_token。

    LangGraph 在调用工具时自动注入 config 参数（不展示给 LLM）。
    context_schema 中的 user_token 可通过 config 获取。
    """
    # 尝试从 configurable.context 获取
    # 尝试从 runtime.context 获取
```

### 3.4 中间件层

#### 3.4.1 AuthMiddleware（`src/middleware/auth.py`）

| 属性 | 值 |
|------|-----|
| 位置 | 中间件链第 1 层 |
| 功能 | 双 JWT 认证，从 context 读取 token 透传/验证 |
| 本地验证 | `JWT_VERIFY_LOCAL=true` 时用 keystore 验证 JWT 提取 user_id |
| Gateway 验证 | `JWT_VERIFY_LOCAL=false`（默认）时透传 token，依赖 Gateway 验证 |
| 无 Token 行为 | 允许只读操作（商品浏览），写操作由工具层检查 |

#### 3.4.2 PermissionMiddleware（`src/middleware/permission.py`）

| 属性 | 值 |
|------|-----|
| 位置 | 中间件链第 2 层 |
| 功能 | 根据 `agent_type` 过滤工具 |
| AdminAgent | 过滤掉所有写操作工具（9 个），LLM 无法选择它们 |
| CustomerAgent | 允许所有工具（写操作需 Token + interrupt 确认） |

**写操作工具集（AdminAgent 不可用）**：

```python
WRITE_TOOLS = {
    "add_to_cart_api", "update_cart_quantity_api",
    "delete_cart_item_api", "clear_cart_api",
    "cancel_order_api", "confirm_receive_api",
    "add_address_api", "update_address_api",
    "do_seckill_api",
}
```

#### 3.4.3 RegexShortcutMiddleware（`src/middleware/regex_shortcut.py`）

| 属性 | 值 |
|------|-----|
| 位置 | 中间件链第 3 层 |
| 功能 | L1 正则快捷路由，拦截 `awrap_model_call` |
| 响应时间 | <5ms（正则匹配 + 工具调用，跳过 LLM） |
| 拦截范围 | 仅只读指令（无 interrupt），写操作由 L2 处理 |
| 命中行为 | `tool.ainvoke(params)` → 返回 `AIMessage`（跳过 LLM） |
| 未命中 | 传递给下一层（SkillsMiddleware → LLM） |
| 降级 | `ainvoke` 失败时尝试同步 `invoke`，再失败返回 None（走 LLM） |

**多模态消息处理**：支持 content 为 `list[dict]`（多模态）和 `str` 两种格式，自动提取文本部分。

#### 3.4.4 RAGMiddleware（`src/middleware/rag_context.py`）

**已实现**。根据 `context.enable_rag` 动态注入 RAG 工具：
- `enable_rag=true` → 通过 `rag_loader.py` 加载 MCP RAG 工具（rag_query / rag_query_data / rag_graph_search）并追加到 request.tools
- `enable_rag=false` → 不注入 RAG 工具
- MCP Server 不可达时降级为不注入（log warning，不阻塞 Agent 核心功能）
- 工具加载器模块级缓存，避免每次 model_call 重复连接 MCP Server

### 3.5 CustomerAgent 实现

#### 3.5.1 Agent 定义（`src/agents/customer/agent.py`）

```python
@dataclass
class Context:
    agent_type: str = "customer"
    user_id: str = ""
    user_token: str = ""
    enable_rag: bool = False

agent = create_agent(
    model=qwen_model,                      # 通义千问 qwen-turbo
    tools=get_all_tools(),                 # 18 个 C 端工具
    backend=skills_backend,               # 虚拟文件系统
    middleware=[
        AuthMiddleware(),                  # 双 JWT 认证
        PermissionMiddleware(),            # 工具权限拦截
        regex_middleware,                  # L1 正则快捷路由
        skills_middleware,                 # Skills 规范加载
    ],
    system_prompt=SYSTEM_PROMPT,
    context_schema=Context,
)
```

**Skills 配置**：`FilesystemBackend` 加载 5 个 SKILL.md（虚拟模式），`SkillsMiddleware` 自动追加到 system_message。

#### 3.5.2 工具清单（18 个）

| 分类 | 工具数 | 工具名 | API 路径 | interrupt |
|------|--------|--------|---------|-----------|
| 商品浏览 | 3 | `search_items_api` | `GET /search/list` | — |
| | | `get_item_detail_api` | `GET /items/{id}` | — |
| | | `get_item_page_api` | `GET /items/page` | — |
| 秒杀 | 3 | `get_seckill_activities_api` | `GET /seckill/activities` | — |
| | | `get_seckill_product_api` | `GET /seckill/products/{relationId}` | — |
| | | `do_seckill_api` | `POST /seckill/order/{relationId}` | ✅ 确认 |
| 购物车 | 5 | `get_cart_list_api` | `GET /carts` | — |
| | | `add_to_cart_api` | `POST /carts` | — |
| | | `update_cart_quantity_api` | `PUT /carts/{itemId}` | — |
| | | `delete_cart_item_api` | `DELETE /carts/{itemId}` | ✅ 确认 |
| | | `clear_cart_api` | `DELETE /carts` | ✅ 确认 |
| 订单 | 4 | `get_order_list_api` | `GET /orders/page` | — |
| | | `get_order_detail_api` | `GET /orders/{id}` | — |
| | | `cancel_order_api` | `POST /orders/batch/close` | ✅ 确认 |
| | | `confirm_receive_api` | `PUT /orders/{orderId}` | ✅ 确认 |
| 地址 | 3 | `get_address_list_api` | `GET /addresses` | — |
| | | `add_address_api` | `POST /addresses` | ✅ 多轮 |
| | | `update_address_api` | `PUT /addresses/{addressId}` | ✅ 多轮 |

**认证机制**：需要登录的工具通过 `RunnableConfig` 自动注入 token（`extract_token_from_config(config)`），不展示给 LLM。商品浏览工具（`/items/**`、`/search/**`）Gateway 排除认证，无需 token。

#### 3.5.3 L1 正则路由规则（`src/agents/customer/regex_rules.py`）

7 条只读指令匹配规则：

| 用户输入示例 | 匹配正则 | 路由工具 | 参数提取 |
|-------------|---------|---------|---------|
| `查看秒杀` / `查询秒杀` | `(?:查看\|查询\|当前).{0,3}秒杀` | `get_seckill_activities_api` | — |
| `查看购物车` / `我的购物车` | `(?:查看\|查询\|我的).{0,5}购物车` | `get_cart_list_api` | — |
| `查看订单` / `待付款订单` | `(?:查询\|查看).{0,5}(?:待付款\|...)?订单` | `get_order_list_api` | — |
| `查看订单100` | `(?:查看\|看)\s*(?:订单\s*)?(\d+)` | `get_order_detail_api` | `order_id` |
| `查看地址` / `我的地址` | `(?:查询\|查看\|我的).{0,5}地址` | `get_address_list_api` | — |
| `搜索手机` / `查找商品` | `(?:搜索\|查找\|找)\s*(.+)` | `search_items_api` | `keyword` |
| `商品列表` / `浏览商品` | `(?:商品列表\|浏览商品\|看看商品\|商品)` | `get_item_page_api` | — |

**不拦截的写操作**（由 L2 interrupt 处理）：取消订单、确认收货、清空购物车、修改地址、新增地址、秒杀下单。

#### 3.5.4 L2 interrupt 二次确认

| 操作 | interrupt 类型 | 恢复条件 |
|------|---------------|---------|
| 秒杀下单 | `confirmation` | 用户回复"确认" |
| 删除购物车商品 | `confirmation` | 用户回复"确认删除" |
| 清空购物车 | `confirmation` | 用户回复"确认删除" |
| 取消订单 | `confirmation` | 用户回复"确认取消" |
| 确认收货 | `confirmation` | 用户回复"确认收货" |
| 新增地址 | `address_input` | 用户回复完整地址（6 字段逗号分隔） |
| 修改地址 | `field_selection` + `value_input` | 两轮 interrupt：先选字段，再输新值 |

**地址修改状态机**（两轮 interrupt）：

```
用户: "修改地址1"
  │
  ├─ interrupt #1 (field_selection):
  │   → "当前地址：张三... 请问要修改哪个字段？(姓名/手机号/省份/城市/区/详细地址)"
  │
  ├─ 用户回复: "手机号"
  │   → field_en = "phone"
  │
  ├─ interrupt #2 (value_input):
  │   → "请输入新的手机号"
  │
  ├─ 用户回复: "13900139000"
  │   → 校验手机号格式 → PUT /addresses/1
  │
  └─ ✅ 地址 1 的手机号已修改为「13900139000」
```

**手机号校验**：`re.match(r"^1\d{10}$", phone)`，11 位数字以 1 开头。

### 3.6 AdminAgent 实现

#### 3.6.1 Agent 定义（`src/agents/admin/agent.py`）

```python
@dataclass
class Context:
    agent_type: str = "admin"
    user_id: str = ""
    user_token: str = ""
    enable_rag: bool = False

agent = create_agent(
    model=qwen_model,
    tools=get_all_tools(),                 # 10 个只读工具 + generate_daily_report
    backend=skills_backend,
    middleware=[
        AuthMiddleware(),
        PermissionMiddleware(),            # AdminAgent 纯只读，拦截所有写操作
        regex_middleware,                  # L1 正则快捷路由（运营日报等）
        skills_middleware,
    ],
    system_prompt=SYSTEM_PROMPT,
    context_schema=Context,
)
```

#### 3.6.2 工具清单（10 个只读 + 1 个编排）

| 分类 | 工具名 | API 路径 | 说明 |
|------|--------|---------|------|
| 商品管理 | `admin_get_product_page_api` | `GET /admin/product/list` | 分页查询商品 |
| | `admin_get_product_detail_api` | `GET /admin/product/{id}` | 商品详情 |
| 订单管理 | `admin_get_order_page_api` | `GET /admin/order/list` | 分页查询订单（状态/时间筛选） |
| | `admin_get_order_detail_api` | `GET /admin/order/{id}` | 订单详情 |
| 秒杀管理 | `admin_get_seckill_promotion_page_api` | `GET /admin/seckill/promotion/list` | 秒杀活动列表 |
| | `admin_get_seckill_relation_page_api` | `GET /admin/seckill/relation/list` | 秒杀商品关联列表 |
| | `admin_get_seckill_order_page_api` | `GET /admin/seckill/order/list` | 秒杀订单列表 |
| | `admin_get_seckill_stock_api` | `GET /admin/seckill/stock/{relationId}` | 每日库存快照 |
| 用户管理 | `admin_get_user_page_api` | `GET /admin/member/list` | C 端用户列表 |
| | `admin_get_user_detail_api` | `GET /admin/member/{id}` | 用户详情 |
| 运营日报 | `generate_daily_report` | （编排 5 个 API） | 并发查询 + 格式化日报 |

**所有管理端工具**：路径以 `/admin` 开头，`GatewayClient` 自动解包 `R<T>.data`。需要管理端 JWT Token（`sessionStorage('admin-token')`）。

#### 3.6.3 运营日报编排（`generate_daily_report`）

使用 `asyncio.gather` 并发调用 5 个查询 API，各取第 1 页 1 条以获取总数：

```python
async def generate_daily_report(config: RunnableConfig) -> str:
    token = extract_token_from_config(config)
    # 并发调用 5 个查询 API
    orders, seckill_promotions, seckill_relations, products, users = (
        await asyncio.gather(
            _safe_get("/admin/order/list"),
            _safe_get("/admin/seckill/promotion/list"),
            _safe_get("/admin/seckill/relation/list"),
            _safe_get("/admin/product/list"),
            _safe_get("/admin/member/list"),
        )
    )
    return format_daily_report(orders, seckill_promotions, seckill_relations, products, users)
```

**容错设计**：`_safe_get` 包装函数捕获 `GatewayError`，单个 API 失败返回 `None`，日报对应分区显示"数据获取失败"，不影响其他分区。

**日报输出格式**：

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📅 2026-07-15 枫叶商城运营日报
━━━━━━━━━━━━━━━━━━━━━━━━━━━━

【订单概览】
- 订单总数: 156 笔

【秒杀活动】
- 秒杀活动: 3 场
- 秒杀商品关联: 12 条

【商品概况】
- 商品总数: 248 件

【用户概况】
- 用户总数: 1230 人
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

#### 3.6.4 L1 正则路由规则（`src/agents/admin/regex_rules.py`）

4 条高频指令匹配规则：

| 用户输入 | 匹配正则 | 路由工具 |
|---------|---------|---------|
| `运营日报` / `生成日报` / `帮我做日报` | `(?:运营\|生成\|帮我做).{0,3}日报` | `generate_daily_report` |
| `查看商品列表` | `(?:查看\|查询\|商品).{0,3}列表` | `admin_get_product_page_api` |
| `查看订单` | `(?:查看\|查询).{0,5}订单` | `admin_get_order_page_api` |
| `秒杀活动` / `查看活动` | `(?:秒杀\|查看).{0,3}活动` | `admin_get_seckill_promotion_page_api` |

### 3.7 格式化工具（`src/tools/formatters.py`）

15 个格式化函数，将 API 返回数据格式化为用户友好的文本输出。所有价格字段（后端以「分」存储）自动转换为「元」：

| 函数 | 用途 | 关键格式化 |
|------|------|-----------|
| `format_seckill_activities` | 秒杀活动列表 | 活动→场次→商品三级嵌套 |
| `format_seckill_product` | 秒杀商品详情 | 秒杀价/原价/库存/限购/状态 |
| `format_seckill_result` | 秒杀下单结果 | success/pending/failed |
| `format_search_results` | 商品搜索结果 | `PageDTO` 兼容 `list`/`records` |
| `format_item_detail` | 商品详情 | 品牌/分类/规格/价格/库存 |
| `format_item_page` | 商品分页列表 | 页码/总数 |
| `format_cart_list` | 购物车列表 | 单价×数量=小计，总计 |
| `format_order_list` | 订单列表 | 订单号/金额/状态/日期 |
| `format_order_detail` | 订单详情 | 含商品明细 |
| `format_address_list` | 地址列表 | 默认标记 |
| `format_admin_product_page` | 管理端商品列表 | 含状态标签 |
| `format_admin_order_page` | 管理端订单列表 | 含用户 ID |
| `format_admin_seckill_page` | 管理端秒杀列表 | 通用分页格式 |
| `format_admin_user_page` | 管理端用户列表 | 余额/状态 |
| `format_daily_report` | 运营日报 | 分区展示 |

**辅助函数**：

```python
def _yuan(fen) -> str:
    """分 → 元（保留 2 位小数）。"""
    return f"{float(fen) / 100:.2f}"

def _status_text(status, mapping) -> str:
    """状态码 → 文本。"""
    return mapping.get(status, f"状态{status}")
```

**状态码映射**：

| 业务 | 状态码 → 文本 |
|------|-------------|
| 订单 | 1=待付款, 2=已付款, 3=已发货, 4=确认收货, 5=交易取消 |
| 商品 | 1=在售, 2=已下架, 3=已删除 |
| 秒杀活动 | 1=未开始, 2=进行中, 3=已结束 |
| 秒杀商品 | 0=未开始, 1=抢购中, 2=已售罄, 3=已结束 |

### 3.8 服务启动与配置

#### 3.8.1 graph.json

注册 `customer_agent` 和 `admin_agent` 两个图：

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
    "env": ".env"
}
```

#### 3.8.2 start_server.py

配置环境变量 + 启动 `uvicorn` + `langgraph_api.server`：

| 环境变量 | 值 | 说明 |
|---------|-----|------|
| `LANGSERVE_GRAPHS` | graph.json 内容 | Agent 图注册 |
| `LANGGRAPH_HTTP` | `{"app": "api.batch_report:app"}` | 自定义路由挂载 |
| `LANGGRAPH_RUNTIME_EDITION` | `inmem` | 内存运行时 |
| `LANGGRAPH_API_URL` | `http://localhost:8090` | API 地址 |
| `DATABASE_URI` | `:memory:` | 内存数据库 |
| `ALLOW_PRIVATE_NETWORK` | `true` | 允许内网访问 |

启动后提供：
- **API**：`http://localhost:8090`
- **OpenAPI 文档**：`http://localhost:8090/docs`
- **LangGraph Studio**：`http://localhost:8090/ui`
- **健康检查**：`http://localhost:8090/ok`（LangGraph 内置，服务存活）
- **LLM 连通性检查**：`http://localhost:8090/api/v1/llm/health`（真实 ping DashScope API）

#### 3.8.3 自定义路由（`src/api/batch_report.py`）

通过 `LANGGRAPH_HTTP` 环境变量挂载到 LangGraph Server：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/batch-report` | POST | 批量运营报告（内部调用 AdminAgent） |
| `/api/v1/health` | GET | 服务存活健康检查 |
| `/api/v1/llm/health` | GET | LLM API 连通性检查（真实 ping DashScope，10 秒缓存） |

#### 3.8.4 RAG MCP Server（`src/mcp_servers/rag_server.py`）

**已实现**。独立的 FastMCP HTTP 服务（端口 8008），桥接 LightRAG REST API：
- `LightRAGClient`：封装 OAuth2 登录、JWT token 缓存、401 自动重登录、httpx 单例
- 3 个 MCP 工具：`rag_query`（POST /query 语义检索）、`rag_query_data`（POST /query/data 结构化查询）、`rag_graph_search`（图谱搜索）
- 独立启动入口 `start_rag_server.py`，与 Agent Server 解耦

#### 3.8.5 LLM 健康检查（`src/api/health.py`）

**已实现**。通过 `GET /api/v1/llm/health` 端点真实 ping DashScope API 判断 LLM 连通性：
- 向 `{LLM_API_BASE}/chat/completions` 发送 `max_tokens=1` 的最小请求，验证 API Key + 服务可达 + 模型可用
- 模块级缓存 10 秒（`_CACHE_TTL`），避免高频轮询消耗 token
- 完整异常兜底：超时（8s）、连接错误、HTTP 错误码、未配置 API Key
- 返回 `{status, llm_reachable, latency_ms, model, detail, cached, checked_at}`
- 在 `batch_report.py` 中通过 `app.include_router(health_router)` 挂载
- 前端 `useLlmHealth` composable 30 秒轮询此端点，动态显示在线/离线状态

### 3.9 Skills 规范文件

#### 3.9.1 CustomerAgent Skills（5 个）

| Skill | 路径 | 说明 |
|-------|------|------|
| `shopping-guide` | `/skills/shopping-guide/SKILL.md` | 商品浏览引导（搜索/详情/分页） |
| `seckill-order` | `/skills/seckill-order/SKILL.md` | 秒杀下单流程（查活动→查详情→确认→下单） |
| `cart-management` | `/skills/cart-management/SKILL.md` | 购物车管理（查看/加购/改量/删除/清空） |
| `order-management` | `/skills/order-management/SKILL.md` | 订单查询与操作（列表/详情/取消/确认收货） |
| `address-management` | `/skills/address-management/SKILL.md` | 地址管理（含多轮 interrupt 状态机） |

#### 3.9.2 AdminAgent Skills（3 个）

| Skill | 路径 | 说明 |
|-------|------|------|
| `daily-report` | `/skills/daily-report/SKILL.md` | 运营日报生成流程 |
| `data-query` | `/skills/data-query/SKILL.md` | 数据查询规范（商品/订单/秒杀/用户） |
| `rag-query` | `/skills/rag-query/SKILL.md` | RAG 知识库检索（已实现） |

### 3.10 测试

#### 3.10.1 正则路由测试（`tests/test_regex_rules.py`）

| 测试类 | 用例数 | 覆盖范围 |
|--------|--------|---------|
| `TestCustomerRegexRules` | 10 | 秒杀/购物车/订单/地址/搜索/商品列表匹配 + 闲聊不匹配 + 写操作不拦截 |
| `TestAdminRegexRules` | 5 | 运营日报/商品列表/订单/秒杀活动匹配 |

#### 3.10.2 格式化函数测试（`tests/test_formatters.py`）

| 测试类 | 用例数 | 覆盖范围 |
|--------|--------|---------|
| `TestFormatSeckillActivities` | 2 | 空列表 + 含活动数据 |
| `TestFormatSearchResults` | 2 | 空结果 + 含商品 |
| `TestFormatCartList` | 2 | 空购物车 + 含商品（含小计计算） |
| `TestFormatOrderList` | 2 | 空订单 + 含订单（含状态映射） |
| `TestFormatAddressList` | 2 | 空地址 + 含地址（含默认标记） |
| `TestFormatDailyReport` | 2 | 含数据 + 全 None（容错） |

---

## 四、前端实现详情

### 4.1 文件变更清单

#### 新增文件（4 个）

| 文件 | 说明 |
|------|------|
| `src/components/chat/ChatPanel.vue` | 可复用全页对话组件（props 配置主题/标题/快捷操作/token） |
| `src/views/portal/ChatPage.vue` | C 端独立聊天页（全屏，带返回按钮） |
| `src/views/admin/ChatPage.vue` | 管理端聊天页（嵌入 AdminLayout，含快捷操作） |
| `src/components/chat/MessageBubble.vue` | 消息气泡组件（重写：Markdown 渲染 + 溢出修复） |

> `InterruptActions.vue` 保持不变。

#### 修改文件（5 个）

| 文件 | 改动 |
|------|------|
| `src/composables/useLangGraph.ts` | SDK 1.x 原生 API；移除 fetch 绕过；context-only（移除 configurable）；处理 messages/complete；error 事件展示错误消息；Vue 响应式修复 |
| `src/composables/useLlmHealth.ts` | **新增**：LLM 健康状态轮询 Composable（30 秒轮询 `/api/v1/llm/health`，暴露 llmStatus/statusText/statusType） |
| `src/components/chat/ChatPanel.vue` | RAG 知识库开关按钮 + 在线状态从写死改为 `useLlmHealth` 动态显示（在线/离线/检测中） |
| `src/views/admin/AdminLayout.vue` | 在线标签从写死 `<el-tag>在线</el-tag>` 改为 `:type="llmStatusType"` 动态绑定 |
| `src/components/chat/ChatWidget.vue` | 简化为浮动按钮 `router-link` → `/portal/chat` |
| `src/components/chat/AdminChat.vue` | 简化为 header 按钮 `router-link` → `/admin/chat` |
| `src/views/portal/PortalLayout.vue` | 导航栏增加 "AI 客服" 链接 |
| `src/views/admin/AdminLayout.vue` | 面包屑标题映射增加 `/admin/chat` |
| `src/router/index.ts` | 添加 `/portal/chat` 和 `/admin/chat` 路由 |
| `package.json` | `@langchain/langgraph-sdk` ^0.0.10 → ^1.0.3；新增 `marked` |

### 4.2 前端架构

```
/portal/chat  →  ChatPage.vue (portal)  →  ChatPanel (customer_agent)
                                          ├── MessageBubble (Markdown)
                                          └── InterruptActions

/admin/chat   →  ChatPage.vue (admin)   →  ChatPanel (admin_agent)
                                          ├── MessageBubble (Markdown)
                                          ├── InterruptActions
                                          └── 快捷操作按钮

浮动入口：
  ChatWidget.vue (右下角浮动按钮) → router-link → /portal/chat
  AdminChat.vue (header 按钮)     → router-link → /admin/chat
```

### 4.3 useLangGraph Composable（`src/composables/useLangGraph.ts`）

封装 `@langchain/langgraph-sdk` 1.x 的 `Client` 类，管理对话状态：

| 响应式状态 | 类型 | 说明 |
|-----------|------|------|
| `messages` | `Ref<ChatMessage[]>` | 消息列表 |
| `isLoading` | `Ref<boolean>` | 加载状态 |
| `interruptData` | `Ref<InterruptData \| null>` | interrupt 数据 |
| `threadId` | `Ref<string \| null>` | 对话线程 ID |
| `error` | `Ref<string \| null>` | 错误信息 |

| 方法 | 说明 |
|------|------|
| `sendMessage(text, context)` | 发送消息（SSE 流式），自动创建/复用 Thread |
| `resume(value)` | 恢复中断（二次确认/多轮交互） |
| `rejectInterrupt()` | 拒绝中断（`command: {goto: "__end__"}`） |
| `clearHistory()` | 清除对话（删除 Thread + 清空消息） |

**SSE 事件处理**：

| 事件 | 处理逻辑 |
|------|---------|
| `messages/partial` | AI 消息增量更新（流式 token 追加效果） |
| `messages/complete` | AI 消息最终完整内容（非流式或流式结束后的完整消息） |
| `values` | 检测 `__interrupt__`，解析为 `InterruptData` |
| `error` | 在 UI 中展示错误消息气泡（`❌ {message}`），中断流 |
| `messages/metadata` | 忽略（消息元数据，不影响显示） |

**SDK 1.x 关键改进**：

| 改进点 | 说明 |
|--------|------|
| `context` 转发 | SDK 1.x `runs.stream()` 正确转发 `context` 字段到请求体 |
| `command` 转发 | SDK 1.x `runs.stream()` 正确转发 `command` 字段（interrupt 恢复） |
| context-only | 移除 `config.configurable`（LangGraph 0.6.0+ 禁止 configurable 和 context 共存） |
| `messages/complete` | 同时处理 `messages/partial` 和 `messages/complete`，避免非流式消息丢失 |

**Vue 响应式修复**：

增量更新通过响应式数组索引修改，而非直接修改局部变量：

```typescript
// ❌ 错误：直接修改局部变量，绕过 Vue Proxy，UI 不更新
aiMessage.content = content

// ✅ 正确：通过响应式数组索引修改，经过 Vue Proxy
const idx = messages.value.findIndex(m => m.id === msgId)
if (idx !== -1) {
  messages.value[idx].content = content
}
```

**Agent 选择**：通过 `options.assistantId` 指定 `customer_agent` 或 `admin_agent`。

### 4.4 MessageBubble 组件（`src/components/chat/MessageBubble.vue`）

| 特性 | 说明 |
|------|------|
| AI 消息渲染 | 使用 `marked` 库渲染 Markdown（GFM + breaks），支持标题/列表/表格/代码块/引用/链接 |
| 人类消息 | 纯文本（`whitespace-pre-wrap`） |
| 溢出修复 | `min-w-0 overflow-hidden overflow-wrap:anywhere`（三层修复） |
| Markdown 样式 | 暗色代码块（`#1e1e2e`）、表格边框、引用竖线、链接蓝色 |
| 流式效果 | 最后一条 AI 消息 + `isLoading` 时显示三点跳动动画 |
| 消息动画 | `messageAppear` 过渡（0.3s） |

**溢出 bug 修复说明**：

| 层级 | CSS | 作用 |
|------|-----|------|
| 气泡容器 | `overflow-hidden; overflow-wrap: break-word` | 防止内容超出气泡边界 |
| AI 内容区 | `flex-1 min-w-0 overflow-hidden` | flex 子项必须 `min-w-0` 否则不会收缩 |
| Markdown body | `word-wrap: break-word; overflow-wrap: anywhere` | 处理长 URL/商品 ID 等无空格长文本 |

### 4.5 ChatPanel 组件（`src/components/chat/ChatPanel.vue`）

可复用全页对话组件，通过 props 配置不同 Agent：

| Prop | 类型 | 说明 |
|------|------|------|
| `assistantId` | `'customer_agent' \| 'admin_agent'` | Agent ID |
| `title` | `string` | 对话标题 |
| `welcomeText` | `string` | 欢迎引导文字 |
| `inputPlaceholder` | `string` | 输入框占位文字 |
| `shortcuts` | `string[]` | 快捷操作按钮文本列表 |
| `tokenKey` | `string` | sessionStorage 中的 token key |
| `agentType` | `'customer' \| 'admin'` | Agent 类型（影响主题色） |
| `showBack` | `boolean` | 是否显示返回按钮 |

| 特性 | 说明 |
|------|------|
| 布局 | 全屏 flex-col（header + 消息区 + 输入区） |
| 主题色 | customer：红色 `#E4393C`；admin：深蓝 `#304156` |
| 消息区 | `max-w-4xl mx-auto` 居中，`overflow-y-auto` 滚动 |
| 自动滚动 | `watch` messages 长度和最后一条消息 content 变化 |
| 清空确认 | `ElMessageBox.confirm` 二次确认 |
| 快捷操作 | 空对话时展示快捷按钮，点击自动发送 |

### 4.6 ChatWidget 组件（C 端入口，`src/components/chat/ChatWidget.vue`）

| 特性 | 说明 |
|------|------|
| 触发方式 | 右下角浮动按钮（玻璃拟态 + 在线指示器） |
| 在线状态 | 由 `useLlmHealth` composable 30 秒轮询 `/api/v1/llm/health` 动态更新 |
| 行为 | `router-link` 跳转 `/portal/chat`（不再展开抽屉） |

### 4.7 AdminChat 组件（管理端入口，`src/components/chat/AdminChat.vue`）

| 特性 | 说明 |
|------|------|
| 触发方式 | header 区域 "AI助手" 按钮 |
| 行为 | `router-link` 跳转 `/admin/chat`（不再展开抽屉） |

### 4.8 路由配置

```typescript
// src/router/index.ts
// C 端聊天页（独立全屏，不嵌套在 PortalLayout 中）
{
  path: '/portal/chat',
  name: 'Chat',
  component: () => import('@/views/portal/ChatPage.vue'),
},

// 管理端聊天页（嵌套在 AdminLayout 中）
{
  path: '/admin',
  component: () => import('@/views/admin/AdminLayout.vue'),
  children: [
    {
      path: 'chat',
      name: 'AdminChat',
      component: () => import('@/views/admin/ChatPage.vue'),
    },
    // ... 其他 admin 子路由
  ],
}
```

### 4.9 布局集成

#### PortalLayout.vue

导航栏增加 "AI 客服" 链接，底部保留浮动按钮入口：

```vue
<router-link to="/portal/chat" class="hover:text-white transition-colors text-[#FF6B35] font-medium">
  AI 客服
</router-link>

<!-- 底部浮动按钮 -->
<ChatWidget />
```

#### AdminLayout.vue

header 区域保留 "AI助手" 按钮入口，面包屑增加 `/admin/chat` 标题：

```vue
<div class="flex items-center gap-3">
  <AdminChat />
  <el-tag size="small" :type="llmStatusType">{{ llmStatusText }}</el-tag>
  ...
</div>
```

> **注**：`llmStatusType` 和 `llmStatusText` 由 `useLlmHealth` composable 提供，
> 30 秒轮询 `/api/v1/llm/health` 动态更新（在线=success / 离线=danger / 检测中=info）。
> `ChatPanel.vue` 头部状态文字同样改为动态：`isLoading` 时显示"正在回复..."，
> 否则根据 LLM 健康状态显示"在线"/"离线"/"检测中"。

---

## 五、配置说明

### 5.1 环境变量（`.env.example`）

```ini
# LLM
DASHSCOPE_API_KEY=your_api_key
LLM_MODEL_NAME=qwen-turbo
LLM_API_BASE=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_TEMPERATURE=0.7
LLM_MAX_TOKENS=2048

# Redis（Checkpoint 后端）
REDIS_HOST=192.168.100.128
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DB=1                          # db=1 与 hmall 业务数据（db=0）隔离

# Java 后端
JAVA_GATEWAY_URL=http://localhost:8080

# Agent 服务
AGENT_HOST=0.0.0.0
AGENT_PORT=8090
LOG_LEVEL=INFO

# JWT（双 Token 验证）
JWT_VERIFY_LOCAL=false              # false 时依赖 Gateway 验证
CUSTOMER_JKS_PATH=keys/hmall.jks   # C 端 RSA 密钥
ADMIN_JKS_PATH=keys/admin.jks      # 管理端 RSA 密钥（独立）

# RAG（LightRAG + MCP）
RAG_BASE_URL=http://localhost:9621
RAG_USERNAME=admin
RAG_PASSWORD=admin123
RAG_SPACE_ID=hmall_space
RAG_API_KEY=
RAG_AUTH_ENABLED=true
RAG_MCP_PORT=8008
```

### 5.2 依赖声明（`pyproject.toml`）

| 依赖 | 版本 | 用途 |
|------|------|------|
| `deepagents` | ≥0.5.9 | Agent 框架（`create_agent`） |
| `langchain` | ≥1.2.12 | 消息管理 + 工具调用 |
| `langchain-openai` | ≥0.3.0 | 通义千问 OpenAI 兼容接口 |
| `langgraph-cli[inmem]` | ≥0.4.26 | 图执行 + API Server + Checkpoint |
| `langgraph-checkpoint-redis` | ≥1.0.0 | Redis Checkpoint 后端 |
| `httpx` | ≥0.27.0 | 异步 HTTP 客户端 |
| `pydantic-settings` | ≥2.0.0 | 环境变量配置管理 |
| `fastapi` | ≥0.115.0 | 自定义路由 |
| `uvicorn` | ≥0.30.0 | ASGI 服务器 |

### 5.3 前端依赖

```json
"@langchain/langgraph-sdk": "^1.0.3",
"marked": "^15.0.0"
```

> **SDK 升级说明**：v1.0 使用 SDK 0.0.10，其 `runs.stream()` 不转发 `context`/`command` 字段，曾用 `fetch` 绕过。v1.1 升级至 SDK 1.x（实际安装 1.9.27），原生 `runs.stream()` 正确转发所有字段，移除了 fetch 绕过代码。同时移除了 `config.configurable`（LangGraph 0.6.0+ 禁止 configurable 和 context 共存），`user_token` 统一通过 `context` 传递。

### 5.4 关键配置说明

| 配置 | 说明 |
|------|------|
| `REDIS_DB=1` | 使用 db=1 与 hmall 业务数据（db=0）隔离，作为 LangGraph Checkpoint 后端 |
| `JWT_VERIFY_LOCAL=false` | 依赖 Gateway 验证 JWT，Agent 层仅透传 token，简化部署 |
| `JAVA_GATEWAY_URL` | hmall Gateway 地址，所有 API 调用经此路由 |

---

## 六、关键技术决策

### 6.1 决策：三级路由架构（正则→interrupt→LLM）

**决策**：使用 DeepAgent 中间件实现三级路由，L1 正则拦截 80%+ 高频只读指令。

**理由**：L1 正则匹配 + 工具调用响应 <5ms，零 LLM 成本；L2 interrupt 原生支持多轮交互和二次确认；L3 LLM 兜底处理复杂问题。相比纯 LLM 方案，大幅降低延迟和 API 调用成本。

### 6.2 决策：JWT_VERIFY_LOCAL=false 依赖 Gateway 验证

**决策**：Agent 层不做 JWT 本地验证，仅透传 token 到 Gateway。

**理由**：
- hmall Gateway 已有完善的 JWT 验证（`AuthGlobalFilter`）、Token 黑名单检查、自动续期
- Agent 层本地验证需要维护双 keystore（`hmall.jks` + `admin.jks`），增加部署复杂度
- Gateway 验证后通过 `user-info` 头传递 userId，Agent 工具调用时携带 token 即可

### 6.3 决策：Redis db=1 隔离 Checkpoint

**决策**：复用 hmall Redis 实例，使用 `db=1` 作为 LangGraph Checkpoint 后端。

**理由**：
- hmall 业务数据使用 `db=0`，Checkpoint 使用 `db=1` 互不干扰
- 无需额外部署 Redis 实例，降低运维成本
- LangGraph Checkpoint 自动管理读写，支持 interrupt 中断恢复

### 6.4 决策：管理端 API 自动解包 R<T>

**决策**：`GatewayClient` 根据 API 路径前缀（`/admin`）自动判断是否解包 `R<T>` 包装。

**理由**：hmall C 端 API 直接返回业务数据，管理端 API（admin-service）统一用 `R<T>` 包装（`{code, msg, data}`）。在 HTTP 客户端层统一处理，工具层无需关心响应格式差异，代码更简洁。

### 6.5 决策：Token 通过 RunnableConfig 自动注入

**决策**：需要认证的工具函数声明 `config: RunnableConfig` 参数，通过 `extract_token_from_config(config)` 提取 token。

**理由**：
- LangGraph 在调用工具时自动注入 `config` 参数，不展示给 LLM（LLM 不会尝试伪造 token）
- `context_schema` 中的 `user_token` 通过 `config` 可获取，实现认证信息的安全传递
- 工具层无需手动传入 token，调用方（LLM / 正则中间件）无需关心认证

### 6.6 决策：AdminAgent 纯只读 + PermissionMiddleware 过滤

**决策**：AdminAgent 只提供只读工具，`PermissionMiddleware` 在中间件层过滤掉所有写操作工具。

**理由**：
- 管理助手面向运营人员，定位为数据查询和日报生成，不执行写操作
- 中间件层过滤确保 LLM 无法选择写工具，即使提示词被绕过也安全
- 写操作（创建/修改/删除/发货/预热）保留在管理后台界面，Agent 不越权

### 6.7 决策：运营日报用 asyncio.gather 并发编排

**决策**：`generate_daily_report` 使用 `asyncio.gather` 并发调用 5 个查询 API。

**理由**：
- 串行调用 5 个 API 需要约 5×30ms=150ms，并发调用仅需约 30ms
- `_safe_get` 包装函数捕获单个 API 失败，不影响其他分区数据
- 各取第 1 页 1 条以获取 `total` 总数，数据量小响应快

---

## 七、部署指引

### 7.1 启动流程

```bash
# 1. 启动基础设施
#    MySQL / Redis / Nacos / RabbitMQ

# 2. 启动 Java 微服务
#    按顺序: hm-gateway → item-service → user-service → cart-service
#            → trade-service → pay-service → search-service → admin-service

# 3. 配置 Agent 环境
cd hmall-agent
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY 和 REDIS_HOST

# 4. 安装 Python 依赖
uv sync                              # 或 pip install -e .

# 5. 启动 Agent 服务
uv run python start_server.py        # LangGraph Server (:8090)

# 6. 启动前端
cd hmall-frontend
npm install                          # 安装 @langchain/langgraph-sdk
npm run dev                          # Vite dev server
```

### 7.2 服务端口总览

| 服务 | 端口 | 说明 |
|------|------|------|
| `start_server.py` | 8090 | LangGraph Agent API + Studio UI |
| `hm-gateway` | 8080 | Java Gateway（路由+认证） |
| `item-service` | 8081 | 商品微服务 |
| `cart-service` | 8082 | 购物车微服务 |
| `pay-service` | 8083 | 支付微服务 |
| `user-service` | 8084 | 用户微服务 |
| `trade-service` | 8085 | 交易微服务 |
| `search-service` | 8089 | 搜索微服务 |
| `admin-service` | 8090 | 管理微服务 |

### 7.3 启动检查清单

- [ ] Redis 服务运行中（`redis-cli -n 1 PING` → `PONG`）
- [ ] hmall Java 微服务全部启动（Gateway `http://localhost:8080/hi` 可访问）
- [ ] `.env` 中 `DASHSCOPE_API_KEY` 已填入有效密钥
- [ ] `.env` 中 `REDIS_HOST` / `REDIS_PORT` 指向 hmall Redis
- [ ] `uv sync` 安装依赖无错误
- [ ] `start_server.py` 启动日志无异常
- [ ] 访问 `http://localhost:8090/ok` → 返回 `{"ok": true}`
- [ ] 访问 `http://localhost:8090/api/v1/llm/health` → 返回 `{"llm_reachable": true, "status": "ok"}`
- [ ] 访问 `http://localhost:8090/docs` → OpenAPI 文档可加载
- [ ] 访问 `http://localhost:8090/ui` → LangGraph Studio 可加载
- [ ] `GET /assistants/search` → 返回 customer_agent 和 admin_agent
- [ ] 前端 `npm run dev` 启动无错误
- [ ] 前端页面右下角出现 AI 客服浮动按钮，点击跳转 `/portal/chat` 全屏对话页
- [ ] 管理后台 header 出现 "AI助手" 按钮，点击跳转 `/admin/chat` 对话页
- [ ] AI 消息以 Markdown 格式渲染（标题/列表/表格/代码块正常显示）
- [ ] 长文本/商品 ID 不超出消息气泡边界

---

## 八、已知问题与后续优化

### 8.1 JWT 本地验证未实现

**现状**：`JWT_VERIFY_LOCAL=false`，Agent 层不做 JWT 本地验证，依赖 Gateway 验证。

**影响**：Agent 层无法提前拦截无效 token，所有无效请求会到达 Gateway 才被拒绝。

**后续优化**：实现 `src/gateway/auth.py` 中的双 keystore JWT 本地验证（使用 `cryptography` 库解析 `hmall.jks` / `admin.jks`），`JWT_VERIFY_LOCAL=true` 时在 Agent 层验证并提取 `user_id`。

### 8.2 RAG 知识库集成（已实现）

**现状**：RAG 知识库集成已完成。`RAGMiddleware` 根据 `context.enable_rag` 动态注入 RAG 工具，`rag_server.py` 作为 FastMCP HTTP 服务桥接 LightRAG REST API，Skills 中 `rag-query` 规范已完善，前端 ChatPanel.vue 新增「知识库」开关按钮。

**影响**：AdminAgent 可回答专业知识问题（如"秒杀库存怎么设置合理？"），CustomerAgent 可回答退换货政策等商城知识。RAG 功能默认关闭，需用户在前端点击「知识库」开关开启。

**依赖**：需独立启动 LightRAG Server（:9621）和 RAG MCP Server（:8008），详见 `hmall-agent-rag-integration.md`。

### 8.3 正则路由仅支持精确匹配

**现状**：L1 正则规则为固定列表，需修改代码才能新增规则。

**影响**：新增高频指令需重启服务。

**后续优化**：从 Nacos 配置中心动态加载正则路由规则，无需重启。

### 8.4 对话历史无 TTL 自动清理

**现状**：LangGraph Thread 的 Checkpoint 数据存储在 Redis db=1，无自动过期机制。

**影响**：长期运行后 Redis db=1 数据持续增长。

**后续优化**：配置 Checkpoint TTL 或增加定时清理任务，自动清理超过 7 天的 Thread。

### 8.5 LLM 降级文案未实现

**现状**：LLM API 超时/异常时由 LangGraph 默认错误处理机制返回错误。

**影响**：用户可能看到技术性错误信息。

**后续优化**：在中间件层捕获 LLM 异常，返回固定兜底文案（如"抱歉，我暂时无法处理，请稍后再试"）。

### 8.6 秒杀结果轮询未集成

**现状**：`do_seckill_api` 调用 `POST /seckill/order/{relationId}` 后直接返回结果（pending/success/failed），未实现前端轮询。

**影响**：秒杀返回 pending 时用户需手动再次询问结果。

**后续优化**：工具内实现轮询逻辑（类似前端 `pollSeckillResult`），或前端收到 pending 后自动轮询 `GET /seckill/result/{relationId}`。

### 8.7 前端历史版本问题已修复（v1.1）

以下问题在 v1.1 中已修复，记录于此供参考：

| 问题 | 根因 | 修复方案 |
|------|------|---------|
| Agent 消息无法显示 | SDK 0.0.10 的 `runs.stream()` 不转发 `context`/`command` 字段 | 升级至 SDK 1.x，原生 API 正确转发 |
| 流式增量内容不更新 | Vue 响应式断链：`aiMessage.content = content` 绕过 Proxy | 通过 `messages.value[idx].content` 修改，经过 Vue Proxy |
| 文字超出消息气泡 | flex 子项缺少 `min-w-0`，长文本无 `overflow-wrap: anywhere` | 三层 CSS 修复（容器 + flex 子项 + markdown body） |
| `configurable` 与 `context` 冲突 | LangGraph 0.6.0+ 禁止同时传递 | 移除 `config.configurable`，`user_token` 统一走 `context` |
| 非流式消息丢失 | 只处理 `messages/partial`，不处理 `messages/complete` | 同时处理两种事件 |
| error 事件无 UI 反馈 | error 事件仅设置 `error.value`，不推送消息 | 在 `messages` 中展示 `❌ {错误信息}` 气泡 |

---

## 九、与本仓库其他文档的关联

| 文档 | 关系 |
|------|------|
| `docs/Agent功能相关文档/hmall-agent-design.md` | **设计文档**：本文档的源头，描述整体架构设计和接口规划 |
| `docs/秒杀功能实现/seckill-design.md` | **关联文档**：秒杀功能设计，Agent 的 `do_seckill_api` 工具调用此功能 |
| `docs/秒杀功能实现/seckill-admin-design.md` | **关联文档**：秒杀管理设计，AdminAgent 的 4 个秒杀管理工具调用此功能 |
| `hmall-agent/graph.json` | **配置文件**：Agent 图注册配置 |
| `hmall-agent/start_server.py` | **启动脚本**：服务启动入口 |
| `hmall-agent/src/agents/customer/tools.py` | **工具实现**：CustomerAgent 18 个 @tool 工具 |
| `hmall-agent/src/agents/admin/tools.py` | **工具实现**：AdminAgent 10 个只读工具 + 运营日报编排 |
| `hmall-agent/src/middleware/regex_shortcut.py` | **中间件**：L1 正则快捷路由 |
| `hmall-frontend/src/composables/useLangGraph.ts` | **前端封装**：LangGraph SDK 1.x Composable（context-only + 响应式修复） |
| `hmall-frontend/src/composables/useLlmHealth.ts` | **前端封装**：LLM 健康状态轮询 Composable（30 秒轮询，动态在线/离线状态） |
| `hmall-frontend/src/components/chat/ChatPanel.vue` | **前端组件**：可复用全页对话面板 |
| `hmall-frontend/src/components/chat/MessageBubble.vue` | **前端组件**：消息气泡（Markdown 渲染 + 溢出修复） |
| `hmall-frontend/src/components/chat/ChatWidget.vue` | **前端组件**：C 端浮动入口（路由跳转） |
| `hmall-frontend/src/components/chat/AdminChat.vue` | **前端组件**：管理端入口（路由跳转） |
| `hmall-frontend/src/views/portal/ChatPage.vue` | **前端页面**：C 端独立聊天页 |
| `hmall-frontend/src/views/admin/ChatPage.vue` | **前端页面**：管理端聊天页 |

---

> **实现完成度**：三级路由架构（正则→interrupt→LLM）全部实现，包含 CustomerAgent（18 工具）、AdminAgent（10 只读工具 + 日报编排）、双 JWT 认证、Redis Checkpoint 对话记忆、Vue 3 前端集成（独立全页对话 + Markdown 渲染 + SDK 1.x 原生 API）、RAG 知识库集成（LightRAG + MCP 桥接 + 前端开关）、LLM 健康检查（真实 ping DashScope API + 前端动态在线状态）等完整链路。JWT 本地验证、LLM 降级文案为后续优化项。


---

# 第二部分：个性化推荐实现

# Agent 个性化推荐能力实现说明文档

> 版本：v1.0
> 日期：2026-07-17
> 设计文档：`docs/Agent功能相关文档/agent-personalized-recommendation-design.md`
>
> 本文对应设计文档 Phase 1（步骤 1-6）：Agent 侧 2 个新工具 + 2 个 Formatter + 1 个 Skill + Prompt/正则/注册增强；后端 `GET /recommend` 接口严格遵循设计文档"SQL 聚合用户已购类目/品牌 → ES 按类目品牌召回 → 按销量排序"三步管线。

---

## 一、实现概况

本次实现为 CustomerAgent 扩展了对话式个性化推荐能力，覆盖 4 个后端模块（hm-api、search-service、trade-service、item-service）和 Agent 全链路（工具、Formatter、Skill、Prompt、正则、注册）。推荐管线遵循设计文档的三步流程：聚合用户已购类目/品牌偏好 → ES 按类目召回 → 销量排序。其中偏好聚合通过 Feign 调用 trade-service 获取已购商品（微服务数据库隔离），ES 召回通过 Feign 调用 search-service（search 已从 item 拆分为独立微服务）。

### 1.1 文件变更统计

| 类别 | 新增 | 修改 | 说明 |
|------|------|------|------|
| hm-api（Feign 基础设施） | 2 | 2 | SearchClient + FallbackFactory + DefaultFeignConfig 注册 + TradeClient 新增方法 |
| search-service（ES 召回） | 0 | 3 | Controller 端点 + Service 接口 + ServiceImpl ES 查询 |
| trade-service（已购商品） | 0 | 3 | OrderController 端点 + IOrderService 接口 + OrderServiceImpl 实现 |
| item-service（推荐接口） | 5 | 2 | Controller/Service/DTO/VO + pom.xml + Application |
| Agent 工具层 | 0 | 1 | tools.py 新增 2 工具 + 辅助函数 + 注册 |
| Agent Formatter | 0 | 1 | formatters.py 新增 2 个格式化函数 |
| Agent Skill | 1 | 0 | personalized-recommendation/SKILL.md |
| Agent Prompt/正则/注册 | 0 | 3 | prompts.py + regex_rules.py + agent.py |
| Gateway 路由 | 0 | 0 | Nacos 动态配置（手动） |
| **合计** | **8** | **15** | — |

### 1.2 Phase 1 范围对照

| 设计文档步骤 | 内容 | 本次实现状态 |
|-------------|------|-------------|
| 步骤 1 | Agent 新增 `get_recommendations_api` 工具 | ✅ 已实现 |
| 步骤 2 | Agent 新增 `analyze_user_preferences` 工具 | ✅ 已实现 |
| 步骤 3 | Agent 新增 `format_recommendations` + `format_preferences` | ✅ 已实现 |
| 步骤 4 | Agent 新增 `personalized-recommendation` Skill | ✅ 已实现 |
| 步骤 5 | Agent 修改 Prompt + 正则路由 + 工具注册 | ✅ 已实现 |
| 步骤 6 | 后端 `GET /recommend` 接口（SQL + ES） | ✅ 已实现 |
| 步骤 7-8 | 后端 `POST /behaviors` + 前端埋点 | ⏸ Phase 2 |
| 步骤 9-10 | Redis 画像 + Item-CF | ⏸ Phase 2 |

---

## 二、架构总览

### 2.1 系统架构

```
用户（C端对话）
  │  "有什么推荐" / "帮我选个手机" / 查看商品后
  ▼
┌──────────────────────────────────────────────────────────────────┐
│              Agent Service (LangGraph Server :8090)              │
│                                                                  │
│  中间件层                                                         │
│  ├── RegexShortcutMiddleware（L1: "推荐/猜你喜欢" 快捷路由 <5ms）  │
│  └── SkillsMiddleware（加载 personalized-recommendation）         │
│                          │                                       │
│  CustomerAgent（扩展后 20 个工具）                                │
│  ├── get_recommendations_api(scene, size, item_id)              │
│  │     → 调用后端 GET /recommend，返回商品列表                    │
│  └── analyze_user_preferences()                                  │
│        → 并发获取订单+购物车，Agent 侧聚合偏好画像                  │
│                          │                                       │
│  LLM 推理层（qwen-turbo）                                        │
│  ├── 理解推荐意图、选择策略、生成推荐理由                          │
│  └── "看了又看"场景从对话上下文提取 item_id                       │
└──────────────────────────┬───────────────────────────────────────┘
                           │ httpx (异步 HTTP, 携带 JWT)
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│              hm-gateway (:8080)                                  │
│  ├── AuthGlobalFilter（JWT → userId，写入 user-info header）      │
│  └── 路由：/recommend/** → item-service（需认证）                 │
└──┬───────────────────────────────────────────────────────────────┘
   │
   ▼
┌──────────────────────────────────────────────────────────────────┐
│  item-service (:8081) — RecommendController GET /recommend       │
│                                                                  │
│  RecommendServiceImpl 三步管线：                                  │
│  ① TradeClient（Feign）→ trade-service 获取已购商品              │
│     → 查 item 表补充 category/brand                              │
│     → 按购买数量加权聚合偏好类目 Top3 + 品牌 Top3 + 已购 ID       │
│                                                                  │
│  ② SearchClient（Feign）→ search-service ES 召回                  │
│     BoolQuery: termsQuery("category") + mustNot("_id")           │
│     sort: sold DESC                                              │
│                                                                  │
│  ③ MySQL 补充 stock/status + 过滤已下架 + 生成推荐标签            │
│                                                                  │
│  降级：ES 无结果 → MySQL 热销兜底（ORDER BY sold DESC）           │
└──────┬───────────────────────────┬───────────────────────────────┘
       │ Feign                     │ Feign（loadbalancer，不走 Gateway）
       ▼                           ▼
┌──────────────────────────┐ ┌──────────────────────────────────────┐
│ trade-service (:8085)    │ │ search-service (:8089)               │
│ OrderController          │ │ SearchController                     │
│ GET /orders/purchased-   │ │ GET /search/recommend                │
│   items                  │ │ SearchServiceImpl.recommendSearch()  │
│ → 已购 itemId + num 列表 │ │ → RestHighLevelClient 查 ES "items"  │
│   (按 itemId 聚合数量)    │ │ → 返回 List<ItemDTO>                 │
└──────────────────────────┘ └──────────────────────────────────────┘
```

### 2.2 推荐数据流

```
Agent 调用 GET /recommend?scene=home&size=10
  │
  ▼
Gateway: JWT → userId → user-info header → item-service
  │
  ▼
RecommendController: UserContext.getUser() 提取 userId
  │
  ▼
RecommendServiceImpl.recommend(userId, scene, size, itemId)
  │
  ├─ scene=detail && itemId≠null
  │   → itemService.getById(itemId) 获取种子商品类目
  │   → excludeIds = [itemId]
  │
  ├─ scene=home/cart && userId≠null
  │   → tradeClient.queryPurchasedItems()  ← Feign → trade-service
  │     → trade-service 查 order + order_detail 聚合已购 itemId+num
  │   → itemService.listByIds(itemIds) ← 查 item 表补充 category/brand
  │   → 按购买数量加权聚合类目偏好 Top3 + 品牌偏好 Top3
  │   → excludeIds = 已购 itemId 列表
  │
  ▼
searchClient.recommend(categories, excludeIds, size)  ← Feign → search-service
  │
  ├─ search-service: ES BoolQuery termsQuery("category") + mustNot("_id")
  │                  + sort sold DESC → List<ItemDTO>
  │
  ├─ Feign 降级（search-service 不可用）→ 返回空列表
  │
  ▼
ES 结果为空？ → MySQL 热销兜底（item 表 ORDER BY sold DESC）
  │
  ▼
itemService.listByIds(resultIds) → 批量补充 stock/status
  │
  ▼
过滤 status≠1（已下架）→ 生成 recommendTags → 组装 RecommendVO
  │
  ▼
返回 JSON → Agent format_recommendations → LLM 生成推荐理由 → 用户
```

### 2.3 与现有三级路由的关系

推荐能力接入现有三级路由体系，不改变路由架构：

```
用户消息
  │
  ├─ L1: RegexShortcutMiddleware（<5ms）
  │   ├── "推荐" / "猜你喜欢" / "帮我选" / "随便看看"
  │   │   → get_recommendations_api(scene=home)  ← 新增正则
  │   ├── "购物车推荐" / "凑单推荐"
  │   │   → get_recommendations_api(scene=cart)  ← 新增正则
  │   └── 其他正则规则不变
  │
  ├─ L2: interrupt
  │   └── 推荐不涉及 interrupt（只读操作）
  │
  └─ L3: LLM 兜底（~2s）
      ├── "帮我选个手机" → LLM 先调 analyze_user_preferences
      │                    → 再调 search_items_api / get_recommendations_api
      │                    → 结合偏好生成推荐
      └── "看了又看" → LLM 从对话上下文提取 item_id
                       → 调 get_recommendations_api(scene=detail, item_id=xxx)
```

---

## 三、后端实现详情

### 3.1 hm-api 模块（Feign 基础设施）

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/main/java/com/hmall/api/client/SearchClient.java` | 新增 | `@FeignClient("search-service")`，调用 `GET /search/recommend` |
| `src/main/java/com/hmall/api/client/fallback/SearchClientFallbackFactory.java` | 新增 | 降级工厂，search-service 不可用时返回空列表 |
| `src/main/java/com/hmall/api/config/DefaultFeignConfig.java` | 修改 | 注册 `SearchClientFallbackFactory` Bean |

**SearchClient 接口**：

```java
@FeignClient(value = "search-service", fallbackFactory = SearchClientFallbackFactory.class)
public interface SearchClient {

    @GetMapping("/search/recommend")
    List<ItemDTO> recommend(
            @RequestParam(value = "categories", required = false) List<String> categories,
            @RequestParam(value = "excludeIds", required = false) List<Long> excludeIds,
            @RequestParam("size") Integer size
    );
}
```

**降级工厂**：

```java
public class SearchClientFallbackFactory implements FallbackFactory<SearchClient> {
    @Override
    public SearchClient create(Throwable cause) {
        return new SearchClient() {
            @Override
            public List<ItemDTO> recommend(List<String> categories, List<Long> excludeIds, Integer size) {
                log.error("ES 推荐召回失败，降级返回空列表", cause);
                return CollUtils.emptyList();
            }
        };
    }
}
```

**设计要点**：

| 决策 | 理由 |
|------|------|
| Feign 而非直接查 ES | search-service 已从 item 拆分为独立微服务，item-service 中的 ES 依赖（pom.xml + ItemDoc.java）是拆分前残留 |
| `DefaultFeignConfig` 透传 `user-info` | Feign 调用走服务间直连（loadbalancer），不经过 Gateway，`RequestInterceptor` 自动透传 userId |
| 降级返回空列表而非抛异常 | 让 `RecommendServiceImpl` 能感知失败并降级为 MySQL 热销兜底，而非直接报错 |
| 复用 hm-api 的 `ItemDTO` | 与 ItemClient/TradeClient 共享同一 DTO，避免类型转换 |

### 3.2 search-service（ES 召回端点）

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/main/java/com/hmall/search/controller/SearchController.java` | 修改 | 新增 `GET /search/recommend` 端点 |
| `src/main/java/com/hmall/search/service/ISearchService.java` | 修改 | 新增 `recommendSearch` 方法签名 |
| `src/main/java/com/hmall/search/service/impl/SearchServiceImpl.java` | 修改 | 实现 ES BoolQuery 推荐召回 |

**Controller 端点**：

```java
@ApiOperation("推荐商品召回")
@GetMapping("/search/recommend")  // 注：实际为 @GetMapping("/recommend") 挂在 /search 前缀下
public List<ItemDTO> recommend(
        @RequestParam(value = "categories", required = false) List<String> categories,
        @RequestParam(value = "excludeIds", required = false) List<Long> excludeIds,
        @RequestParam("size") Integer size
) throws IOException {
    return searchService.recommendSearch(categories, excludeIds, size);
}
```

**ES 查询实现**：

```java
@Override
public List<ItemDTO> recommendSearch(List<String> categories, List<Long> excludeIds, Integer size) throws IOException {
    SearchRequest searchRequest = new SearchRequest("items");
    SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
    BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();

    // 按偏好类目过滤（多类目 terms 查询）
    if (categories != null && !categories.isEmpty()) {
        boolQueryBuilder.filter(QueryBuilders.termsQuery("category", categories));
    }

    // 排除已购商品
    if (excludeIds != null && !excludeIds.isEmpty()) {
        boolQueryBuilder.mustNot(QueryBuilders.termsQuery("_id", excludeIds));
    }

    searchSourceBuilder.query(boolQueryBuilder);
    searchSourceBuilder.size(size);
    searchSourceBuilder.sort(new FieldSortBuilder("sold").order(SortOrder.DESC));
    searchSourceBuilder.timeout(new TimeValue(10, TimeUnit.SECONDS));
    searchRequest.source(searchSourceBuilder);

    // 执行查询 + ItemDoc → ItemDTO 转换
    ...
}
```

**ES 查询策略**：

| 场景 | BoolQuery 构造 | 说明 |
|------|---------------|------|
| 有偏好类目 | `filter termsQuery("category", [类目列表])` | 多类目 OR 匹配 |
| 排除已购 | `mustNot termsQuery("_id", [已购ID列表])` | 避免重复推荐 |
| 无偏好（冷启动） | 空 BoolQuery（matchAll） | 返回全局热销 |
| 排序 | `FieldSortBuilder("sold").order(DESC)` | 按销量倒序 |
| 超时 | 10 秒 | 防止 ES 慢查询阻塞推荐 |

### 3.3 trade-service（已购商品聚合端点）

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/main/java/com/hmall/trade/controller/OrderController.java` | 修改 | 新增 `GET /orders/purchased-items` 端点 |
| `src/main/java/com/hmall/trade/service/IOrderService.java` | 修改 | 新增 `queryPurchasedItems()` 方法签名 |
| `src/main/java/com/hmall/trade/service/impl/OrderServiceImpl.java` | 修改 | 实现已购商品按 itemId 聚合 num |

**hm-api TradeClient 新增方法**：

```java
@FeignClient(value = "trade-service")
public interface TradeClient {
    @PutMapping("/orders/{orderId}")
    void markOrderPaySuccess(@PathVariable("orderId") Long orderId);

    /**
     * 获取当前用户已购商品 ID 及数量（有效订单：已付款/已发货/已收货/已评价）
     * userId 通过 DefaultFeignConfig 透传的 user-info header 获取。
     */
    @GetMapping("/orders/purchased-items")
    List<OrderDetailDTO> queryPurchasedItems();
}
```

**OrderServiceImpl 实现**：

```java
@Override
public List<OrderDetailDTO> queryPurchasedItems() {
    Long userId = UserContext.getUser();
    if (userId == null) return CollUtils.emptyList();

    // 1. 查询当前用户有效订单（status IN 2,3,4,6）
    List<Order> orders = lambdaQuery()
            .eq(Order::getUserId, userId)
            .in(Order::getStatus, 2, 3, 4, 6)
            .list();
    if (CollUtils.isEmpty(orders)) return CollUtils.emptyList();

    // 2. 查询这些订单的商品详情
    List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
    List<OrderDetail> details = detailService.lambdaQuery()
            .in(OrderDetail::getOrderId, orderIds)
            .list();
    if (CollUtils.isEmpty(details)) return CollUtils.emptyList();

    // 3. 按 itemId 聚合购买数量
    Map<Long, Integer> itemNumMap = new HashMap<>();
    for (OrderDetail detail : details) {
        itemNumMap.merge(detail.getItemId(), detail.getNum(), Integer::sum);
    }

    // 4. 转换为 DTO 列表（复用 hm-api 的 OrderDetailDTO：itemId + num）
    return itemNumMap.entrySet().stream()
            .map(e -> new OrderDetailDTO().setItemId(e.getKey()).setNum(e.getValue()))
            .collect(Collectors.toList());
}
```

**设计要点**：

| 决策 | 理由 |
|------|------|
| Feign 而非跨表 SQL | 微服务数据库隔离，item-service 无法访问 trade-service 的 `order` / `order_detail` 表 |
| 返回 itemId+num 而非完整订单 | 轻量级接口，仅返回推荐所需的最小数据（商品 ID + 购买数量），避免传输完整 OrderVO |
| 按 itemId 聚合 num | 同一商品可能在不同订单中购买多次，聚合后反映总购买量 |
| 复用 hm-api 的 `OrderDetailDTO` | 已有 DTO（itemId + num），无需新建 |
| `OrderDetail` 无 category/brand | trade-service 只返回 itemId+num，category/brand 由 item-service 查 item 表补充 |

### 3.4 item-service（推荐接口 + Feign 启用）

| 文件 | 操作 | 说明 |
|------|------|------|
| `pom.xml` | 修改 | 新增 hm-api + openfeign + loadbalancer + feign-okhttp 依赖 |
| `src/main/java/com/hmall/item/ItemApplication.java` | 修改 | 新增 `@EnableFeignClients` |
| `src/main/java/com/hmall/item/controller/RecommendController.java` | 新增 | `GET /recommend` 接口 |
| `src/main/java/com/hmall/item/service/IRecommendService.java` | 新增 | 推荐服务接口 |
| `src/main/java/com/hmall/item/service/impl/RecommendServiceImpl.java` | 新增 | 三步管线核心实现（Feign 聚合偏好 + ES 召回 + MySQL 补充） |
| `src/main/java/com/hmall/item/domain/dto/RecommendItemDTO.java` | 新增 | 推荐商品 DTO（含 recommendTags） |
| `src/main/java/com/hmall/item/domain/dto/RecommendVO.java` | 新增 | 响应 VO（含 basedOn 内部类） |

#### 3.3.1 pom.xml 依赖新增

```xml
<!--openFeign-->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
<!--负载均衡器-->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
<!--OK http 的依赖 -->
<dependency>
    <groupId>io.github.openfeign</groupId>
    <artifactId>feign-okhttp</artifactId>
</dependency>
<!--hm-api 共享 Feign 客户端-->
<dependency>
    <groupId>com.heima</groupId>
    <artifactId>hm-api</artifactId>
    <version>1.0.0</version>
</dependency>
```

#### 3.3.2 Application 启用 Feign

```java
@MapperScan("com.hmall.item.mapper")
@SpringBootApplication
@EnableScheduling
@EnableFeignClients(basePackages = "com.hmall.api.client", defaultConfiguration = DefaultFeignConfig.class)
public class ItemApplication {
```

#### 3.4.3 RecommendController

```java
@Api(tags = "个性化推荐接口")
@RestController
@RequestMapping("/recommend")
@RequiredArgsConstructor
public class RecommendController {

    private final IRecommendService recommendService;

    @ApiOperation("获取个性化推荐商品")
    @GetMapping
    public RecommendVO recommend(
            @RequestParam(defaultValue = "home") String scene,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long itemId
    ) {
        Long userId = UserContext.getUser();
        return recommendService.recommend(userId, scene, size, itemId);
    }
}
```

#### 3.4.4 RecommendServiceImpl 三步管线

核心实现遵循"Feign 聚合偏好 → ES 召回 → 销量排序"三步流程。偏好聚合通过 Feign 调用 trade-service 获取已购商品，再查 item 表补充 category/brand 后按购买数量加权聚合：

```java
@Override
public RecommendVO recommend(Long userId, String scene, Integer size, Long itemId) {
    // 1. 确定召回参数
    if ("detail".equals(scene) && itemId != null) {
        // scene=detail: 获取种子商品的类目
        Item seedItem = itemService.getById(itemId);
        if (seedItem != null) categories.add(seedItem.getCategory());
        excludeIds.add(itemId);
    } else if (userId != null) {
        // scene=home/cart: Feign 调用 trade-service 获取已购商品
        List<OrderDetailDTO> purchasedItems = safeQueryPurchasedItems();
        if (purchasedItems != null && !purchasedItems.isEmpty()) {
            excludeIds = purchasedItems.stream().map(OrderDetailDTO::getItemId).collect(...);
            // 查 item 表补充 category/brand
            List<Item> purchasedItemList = itemService.listByIds(itemIds);
            // 按购买数量加权聚合类目/品牌偏好 Top3
            Map<String, Integer> catScores = new HashMap<>();
            for (Item item : purchasedItemList) {
                Integer num = numMap.getOrDefault(item.getId(), 1);
                catScores.merge(item.getCategory(), num, Integer::sum);
            }
            categories = topN(catScores, 3);
            topBrands = topN(brandScores, 3);
        }
    }

    // 2. Feign 调用 search-service ES 召回
    List<ItemDTO> searchResults = searchClient.recommend(categories, excludeIds, size);

    // 3. ES 无结果时 MySQL 热销兜底
    if (searchResults.isEmpty()) {
        searchResults = mysqlHotFallback(excludeIds, size);
        isFallback = true;
    }

    // 4. MySQL 批量补充 stock/status
    Map<Long, Item> dbItemMap = itemService.listByIds(resultIds)...;

    // 5. 组装推荐商品，过滤已下架 + 生成推荐标签
    for (ItemDTO searchItem : searchResults) {
        if (status != 1) continue;  // 过滤非在售
        ri.setRecommendTags(generateTags(...));
        recommendItems.add(ri);
    }

    // 6. 组装响应（冷启动兜底时不设 basedOn）
    return vo;
}
```

**Feign 调用容错**：

```java
private List<OrderDetailDTO> safeQueryPurchasedItems() {
    try {
        return tradeClient.queryPurchasedItems();
    } catch (Exception e) {
        log.error("Feign 调用 trade-service 获取已购商品失败，降级为空列表", e);
        return new ArrayList<>();
    }
}
```

**偏好聚合辅助方法**：

```java
private List<String> topN(Map<String, Integer> scores, int n) {
    return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(n)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
}
```

**MySQL 热销兜底**：

```java
private List<ItemDTO> mysqlHotFallback(List<Long> excludeIds, int size) {
    log.warn("ES 召回无结果，降级为 MySQL 热销兜底");
    List<Item> items = itemService.lambdaQuery()
            .eq(Item::getStatus, 1)
            .notIn(!excludeIds.isEmpty(), Item::getId, excludeIds)
            .orderByDesc(Item::getSold)
            .last("LIMIT " + size)
            .list();
    return BeanUtils.copyList(items, ItemDTO.class);
}
```

**推荐标签生成规则**：

| 场景 | 标签 |
|------|------|
| 热销兜底（isFallback=true） | `["热销推荐"]` |
| scene=detail | `["相似推荐"]` + 品牌匹配时追加 `"您常买的品牌"` |
| scene=home/cart | 类目匹配时 `"同类目热销"` + 品牌匹配时 `"您常买的品牌"` |
| 无匹配标签 | `["热销推荐"]`（兜底） |

#### 3.4.5 DTO/VO 结构

**RecommendItemDTO**（推荐商品）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 商品 ID |
| `name` | String | 商品名称 |
| `price` | Integer | 价格（分） |
| `stock` | Integer | 库存（MySQL 补充） |
| `brand` | String | 品牌 |
| `category` | String | 类目 |
| `sold` | Integer | 销量 |
| `recommendTags` | List\<String\> | 推荐标签（后端生成，LLM 参考） |

**RecommendVO**（响应）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `list` | List\<RecommendItemDTO\> | 推荐商品列表 |
| `total` | Integer | 总数 |
| `basedOn` | BasedOn | 推荐依据（冷启动兜底时为 null） |

`BasedOn` 内部类：`topCategories` + `topBrands`（List\<String\>）

---

## 四、Agent 侧实现详情

### 4.1 工具层（2 个新工具）

文件：`hmall-agent/src/agents/customer/tools.py`

工具总数 18 → 20。新增工具均需登录（通过 `extract_token_from_config` 检查 token）。

#### 4.1.1 get_recommendations_api

```python
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
        return f"推荐服务暂时不可用，您可以尝试搜索商品。错误: {e}"
```

#### 4.1.2 analyze_user_preferences

```python
@tool
async def analyze_user_preferences(config: RunnableConfig) -> str:
    """分析当前用户的购物偏好（基于购买历史和购物车）。

    返回偏好的类目、品牌、价格区间，供推荐和搜索参考。
    需要登录。
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 偏好分析需要先登录"

    # 并发获取购买历史和购物车
    orders_page, cart_items = await asyncio.gather(
        gateway_client.get("/orders/page", token=token, params={"pageNo": 1, "pageSize": 50}),
        gateway_client.get("/carts", token=token),
    )
    ...
```

**偏好聚合辅助函数**：

```python
def _accumulate_preference(cat_scores, brand_scores, prices, item, weight):
    """累加单个商品的偏好分数到聚合字典。

    Args:
        weight: 权重（购买=5，购物车=3）
    """
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
| `scene` 参数化 | 让 LLM 根据对话上下文判断场景，而非后端猜测 |
| `asyncio.gather` 并发 | 订单 + 购物车并发获取，减少等待 |
| 购买权重 5，购物车权重 3 | 购买是更强信号，购物车是意向信号 |
| 批量查询商品详情 | `OrderDetail` 无 category/brand 字段，需额外调 `/items?ids=` 补充 |
| 不依赖后端画像服务 | 直接用现有 `/orders/page` + `/carts` 接口，零后端改动即可启用 |
| 降级返回提示 | 推荐非核心链路，失败时引导用户搜索，不阻断对话 |

#### 4.1.3 工具注册更新

```python
def get_all_tools():
    return [
        # 商品浏览（3）
        ...
        # 秒杀（3）
        ...
        # 购物车（5）
        ...
        # 订单（4）
        ...
        # 地址（3）
        ...
        # 个性化推荐（新增 2）
        get_recommendations_api,
        analyze_user_preferences,
    ]
```

### 4.2 Formatter 层

文件：`hmall-agent/src/tools/formatters.py`

新增 2 个格式化函数，复用现有 `_yuan()`（分转元）、`_table_row()`、`_table_sep()` 辅助函数。

#### 4.2.1 format_recommendations

将后端 `/recommend` 响应格式化为 Markdown 表格 + 推荐依据引用块：

```python
def format_recommendations(page_dto: dict, scene: str = "home") -> str:
    ...
    lines = [f"## 🎯 {title}（共 {total} 件）", ""]
    lines.append(_table_row("#", "商品", "价格", "库存", "标签", "ID"))
    lines.append(_table_sep(6))
    for i, item in enumerate(items, 1):
        ...
        lines.append(_table_row(i, name, f"¥{price}", f"{stock} 件", tag_str, f"`{item_id}`"))

    # 推荐依据摘要
    if based_on:
        ...
        lines.append(f"> **推荐依据**: {' | '.join(parts)}")

    return "\n".join(lines)
```

**输出示例**：

```markdown
## 🎯 猜你喜欢（共 10 件）

| # | 商品 | 价格 | 库存 | 标签 | ID |
|---|------|------|------|------|-----|
| 1 | iPhone 15 Pro | ¥7999.00 | 45 件 | 同类目热销, 您常买的品牌 | `2001` |
| 2 | AirPods Pro | ¥1899.00 | 120 件 | 搭配推荐 | `2002` |

> **推荐依据**: 偏好类目: 手机, 耳机 | 偏好品牌: Apple, Sony
```

#### 4.2.2 format_preferences

将 Agent 侧聚合的偏好数据格式化为 Markdown 列表：

```python
def format_preferences(cat_scores, brand_scores, prices, orders, cart) -> str:
    ...
    lines = [
        "## 📊 您的购物偏好分析",
        "",
        f"基于 {order_count} 笔订单 + {cart_count} 件购物车商品",
    ]
    # 类目 Top 3
    ...
    # 品牌 Top 3
    ...
    # 价格区间
    ...
```

### 4.3 Skill 规范

文件：`hmall-agent/src/workspace/customer/skills/personalized-recommendation/SKILL.md`

覆盖 4 种推荐场景的工作流规范：

| 场景 | 触发条件 | 工具调用链 |
|------|---------|-----------|
| 首页推荐 / 猜你喜欢 | "有什么推荐" / "猜我喜欢什么" | `get_recommendations_api(scene="home")` |
| 看了又看 | 查看商品后 / "还有类似的吗" | `get_recommendations_api(scene="detail", item_id=xxx)` |
| 购物车凑单 | "购物车还能加点什么" / "凑单推荐" | `get_recommendations_api(scene="cart")` |
| 偏好驱动推荐 | "我想换个手机" / "推荐点苹果生态的产品" | `analyze_user_preferences()` → `search_items_api()` |

**Skill 注册**（`agent.py`）：

```python
sources=[
    "/skills/shopping-guide/",
    "/skills/seckill-order/",
    "/skills/cart-management/",
    "/skills/order-management/",
    "/skills/address-management/",
    "/skills/personalized-recommendation/",  # ← 新增
],
```

### 4.4 Prompt 增强

文件：`hmall-agent/src/agents/customer/prompts.py`

SYSTEM_PROMPT 新增内容：

| 增强项 | 内容 |
|--------|------|
| 能力声明第 6 项 | `6. **个性化推荐**：基于用户购买/浏览历史推荐商品，支持猜你喜欢、看了又看、购物车凑单等场景，推荐时附带推荐理由` |
| 行为准则（4 条） | 模糊购物意图主动调用推荐工具；购买/浏览后顺势推荐；推荐务必说明理由；推荐后主动询问反馈形成闭环 |
| 输出格式 | `推荐：表格（含推荐标签列），推荐依据用 > 引用块说明` |

### 4.5 L1 正则路由

文件：`hmall-agent/src/agents/customer/regex_rules.py`

新增 2 条推荐正则规则 + `_extract_recommend_scene` 参数提取器：

```python
def _extract_recommend_scene(m: re.Match) -> dict:
    """从正则匹配中提取推荐场景。"""
    keyword = m.group(1) if m.groups() else ""
    if "凑单" in keyword or "购物车" in keyword:
        return {"scene": "cart"}
    return {"scene": "home"}
```

| 用户输入示例 | 匹配正则 | 路由工具 | 参数 |
|-------------|---------|---------|------|
| `有什么推荐` / `猜你喜欢` / `帮我选` / `随便看看` | `(?:推荐\|猜你喜欢\|有什么好\|帮我选\|随便看看\|给我推荐)` | `get_recommendations_api` | `scene=home` |
| `购物车推荐` / `凑单推荐` | `(?:购物车\|凑单).{0,5}(?:推荐\|加\|添\|凑)` | `get_recommendations_api` | `scene=cart` |
| `看了又看` / `相似商品` | （不拦截，走 L3 LLM） | `get_recommendations_api` | LLM 从上下文提取 `item_id` |

**"看了又看"不走 L1 的原因**：需要从对话上下文提取当前商品 ID，正则无法做到。由 L3 LLM 处理，LLM 能从最近对话历史中推断 `item_id` 并调用 `get_recommendations_api(scene="detail", item_id=xxx)`。

---

## 五、Gateway 路由配置

Gateway 路由配置存储在 Nacos 配置中心（`192.168.100.128:8848`），dataId 为 `gateway-routes.json`，不在本地代码库中。需在 Nacos 控制台手动新增路由条目：

```json
{
  "id": "item-recommend",
  "predicates": [{ "name": "Path", "args": { "pattern": "/recommend/**" } }],
  "uri": "lb://item-service"
}
```

**认证要求**：`/recommend/**` **不加入** `hm.auth.excludePaths`（免认证路径列表），确保走认证流程：

```
Gateway AuthGlobalFilter
  → 解析 JWT → 提取 userId
  → 写入 user-info header
  → 传递给 item-service
  → RecommendController 用 UserContext.getUser() 读取 userId
```

**免认证路径列表**（`application.yml`，`/recommend/**` 不在其中）：

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

---

## 六、关键技术决策

### 6.1 决策：ES 召回通过 Feign 调用 search-service

**决策**：item-service 不直接查 ES，而是通过 Feign 调用 search-service 的 `/search/recommend` 端点。

**理由**：
- search-service 已从 item 拆分为独立微服务，item-service 中的 ES 依赖（pom.xml 中的 `elasticsearch-rest-high-level-client` 和 `ItemDoc.java`）是拆分前残留
- hm-api 模块已有完整的 Feign 基础设施（ItemClient、TradeClient 等 + DefaultFeignConfig + Fallback 工厂）
- Feign 调用走服务间直连（loadbalancer 服务发现），不经过 Gateway，`DefaultFeignConfig` 的 `RequestInterceptor` 自动透传 `user-info` header
- 符合微服务架构的职责分离：ES 查询能力归属 search-service

### 6.2 决策：新建 RecommendController 而非加到 ItemController

**决策**：新建独立的 `RecommendController`（`@RequestMapping("/recommend")`），而非加到 `ItemController`。

**理由**：
- `ItemController` 的路径是 `/items`，已被 Gateway 免认证排除（`/items/**`）
- `/recommend` 需要认证（用户维度推荐），不能放入免认证路径
- 独立 Controller 职责更清晰，推荐逻辑与商品 CRUD 解耦

### 6.3 决策：Formatter 用 Markdown 而非设计文档的纯文本

**决策**：`format_recommendations` 和 `format_preferences` 使用 Markdown 格式（表格、粗体、引用块），而非设计文档中的纯文本格式（`─` 分隔符）。

**理由**：
- 现有 15 个 Formatter 全部使用 Markdown 格式（`_table_row()` / `_table_sep()` / `_yuan()` 辅助函数）
- 前端 `MessageBubble.vue` 已集成 `marked` 库渲染 Markdown（标题/列表/表格/代码块/引用）
- 保持风格一致，避免推荐输出与其他工具的格式割裂

### 6.4 决策：推荐理由由 LLM 生成而非后端模板拼接

**决策**：后端只返回 `recommendTags`（商品级标签）和 `basedOn`（推荐依据摘要），推荐理由由 LLM 结合用户偏好生成。

**理由**：
- 后端模板拼接的理由生硬（"推荐理由：同类目热销"），缺乏个性化
- LLM 能结合偏好生成自然语言（"您常买 Apple 品牌产品，这款是同品类热销款"）
- LLM 能结合对话上下文调整话术（用户刚看了 iPhone → "与您刚看的 iPhone 相似"）
- 后端标签作为结构化数据给 LLM 参考，LLM 负责组织成自然语言

### 6.5 决策：analyze_user_preferences 不依赖后端画像服务

**决策**：偏好分析工具直接调用现有 `/orders/page` + `/carts` 接口，在 Agent 侧聚合。

**理由**：
- 零后端改动即可启用偏好分析能力
- 现有 `get_order_list_api` 和 `get_cart_list_api` 已验证可用
- Phase 1 无需后端画像服务，Phase 2 画像丰富后可切换为调用画像接口
- Agent 侧聚合逻辑简单（类目/品牌分数累加），无需复杂算法

### 6.6 决策："猜你喜欢"走 L1 正则，"看了又看"走 L3 LLM

**决策**：首页"猜你喜欢"通过 L1 正则快捷路由（<5ms），"看了又看"由 L3 LLM 处理。

**理由**：
- "猜你喜欢"是高频场景，参数固定（scene=home），适合正则拦截
- "看了又看"需要从对话上下文提取 `item_id`，正则无法做到
- LLM 能从最近对话历史中推断当前商品 ID，虽然慢 ~2s 但更准确

### 6.7 决策：偏好聚合通过 Feign + item 表而非跨表 SQL

**决策**：不在 item-service 中直接 JOIN `order_detail` + `order` 表聚合偏好，而是通过 Feign 调用 trade-service 获取已购商品 itemId+num，再查 item 表补充 category/brand 后在 item-service 侧聚合。

**理由**：
- 微服务数据库隔离：不同微服务的数据库是独立的，item-service 无法访问 trade-service 的 `order` / `order_detail` 表
- 设计文档原假设 item-service 和 trade-service 共享同一 MySQL（`shared-jdbc.yaml`），实际并非如此
- 微服务架构最佳实践：一个微服务不应直接访问另一个微服务的数据库表，即使物理上是同一数据库实例
- trade-service 只返回 itemId+num（轻量数据），category/brand 由 item-service 查自己的 item 表补充，职责分离清晰
- Feign 调用失败时 `safeQueryPurchasedItems` 降级返回空列表，推荐走热销兜底

---

## 七、配置说明与部署指引

### 7.1 新增依赖

**item-service pom.xml**：

| 依赖 | 用途 |
|------|------|
| `spring-cloud-starter-openfeign` | Feign 声明式 HTTP 客户端 |
| `spring-cloud-starter-loadbalancer` | 服务发现 + 负载均衡 |
| `feign-okhttp` | OkHttp 作为 Feign 底层 HTTP 客户端 |
| `hm-api`（com.heima:1.0.0） | 共享 Feign client + DTO + DefaultFeignConfig |

### 7.2 Nacos 配置

| 配置项 | dataId | 操作 |
|--------|--------|------|
| Gateway 动态路由 | `gateway-routes.json` | 新增 `/recommend/**` → `lb://item-service` 路由条目 |

### 7.3 启动检查清单

- [ ] Nacos 中 `gateway-routes.json` 已新增 `/recommend/**` 路由
- [ ] `/recommend/**` **未加入** `hm.auth.excludePaths`（确保需认证）
- [ ] item-service pom.xml 已引入 hm-api + openfeign 依赖
- [ ] item-service `ItemApplication` 已加 `@EnableFeignClients`
- [ ] trade-service 已启动（已购商品聚合依赖）
- [ ] search-service 已启动（ES 召回依赖）
- [ ] ES 索引 `items` 中有商品数据
- [ ] Agent 服务已重启加载新工具和 Skill
- [ ] 登录后对话输入"有什么推荐" → 返回推荐列表
- [ ] 未登录对话输入"有什么推荐" → 提示"需要先登录"
- [ ] 新用户（无订单）推荐 → 后端返回热销榜
- [ ] 推荐接口失败 → Agent 降级提示"推荐服务暂时不可用，您可以尝试搜索商品"

### 7.4 测试验证场景

| 测试场景 | 验证点 |
|---------|--------|
| L1 正则命中"推荐" | <5ms 返回推荐列表，不走 LLM |
| L1 正则命中"购物车推荐" | scene=cart 参数正确传递 |
| 未登录访问推荐 | 返回"需要先登录"提示 |
| 新用户推荐（无订单） | 后端返回热销榜，basedOn=null |
| scene=detail | 传 itemId，返回同类目商品 |
| search-service 不可用 | Feign 降级 → MySQL 热销兜底 |
| trade-service 不可用 | Feign 降级返回空列表 → 无偏好 → ES 全局热销兜底 |
| ES 无结果 | MySQL 热销兜底（`isFallback=true`，标签为"热销推荐"） |
| 推荐后加购 | 形成推荐→加购→凑单推荐闭环 |
| Formatter 空数据 | 返回"暂无推荐商品，您可以尝试搜索看看" |
| analyze_user_preferences 并发 | 订单+购物车并发获取无阻塞 |

---

## 八、降级与回滚

### 8.1 降级链路

```
推荐请求
  │
  ├─ 正常路径：Feign 获取已购商品 → item 表聚合偏好 → Feign ES 召回 → MySQL 补充 → 返回
  │
  ├─ Feign 降级（trade-service 不可用）
  │   → safeQueryPurchasedItems catch 返回空列表
  │   → 无偏好数据 → ES 全局热销召回
  │
  ├─ Feign 降级（search-service 不可用）
  │   → SearchClientFallbackFactory 返回空列表
  │   → RecommendServiceImpl 检测空结果 → MySQL 热销兜底
  │
  ├─ ES 无结果（偏好类目下无商品）
  │   → MySQL 热销兜底（item 表 ORDER BY sold DESC）
  │   → isFallback=true → basedOn=null → 标签为"热销推荐"
  │
  └─ Agent 降级（推荐接口 HTTP 错误）
      → GatewayError 捕获 → 返回"推荐服务暂时不可用，您可以尝试搜索商品"
      → 401 时返回"登录已过期，请重新登录后获取推荐"
```

### 8.2 各层降级行为

| 层级 | 故障场景 | 降级行为 | 影响 |
|------|---------|---------|------|
| trade-service | 服务不可用 | `safeQueryPurchasedItems` catch 返回空列表 | 无偏好数据，走 ES 全局热销兜底 |
| search-service | 服务不可用 / ES 宕机 | Feign Fallback 返回空列表 | 推荐 MySQL 热销兜底 |
| item-service | ES 无结果 | MySQL `ORDER BY sold DESC` | 返回全局热销，无个性化 |
| item-service | MySQL 也无数据 | 返回空列表 | Agent 提示"暂无推荐商品" |
| Agent | `/recommend` 接口返回错误 | `GatewayError` 捕获 | 提示用户手动搜索 |
| Agent | 用户未登录 | token 检查失败 | 提示"需要先登录" |
| Agent | `analyze_user_preferences` 中 `/items` 查询失败 | catch 忽略 | 仅用已有数据聚合（无 category/brand） |

### 8.3 回滚方案

推荐功能为增量新增，不修改任何现有功能，回滚安全：

1. **Agent 侧回滚**：从 `get_all_tools()` 移除 2 个推荐工具 + 从 `sources` 移除 Skill + 注释正则规则。无需删除文件。
2. **后端回滚**：删除 `RecommendController` + 从 Nacos 移除 `/recommend/**` 路由。item-service 的 Feign 依赖可保留（不影响其他功能）。
3. **数据无变更**：Phase 1 不新增数据库表，不修改现有表结构，无需数据回滚。

---

## 九、与设计文档的偏差说明

实现过程中因代码现状与设计文档假设不符，产生 4 处偏差，均已修正：

| # | 设计文档假设 | 实际代码现状 | 修正方案 |
|---|-------------|-------------|---------|
| 1 | item-service 直接查 ES（"ES 已有"） | item-service 的 ES 依赖是 search 拆分前的残留 | 改为 Feign 调用 search-service |
| 2 | Formatter 用纯文本格式（`─` 分隔符） | 现有 15 个 Formatter 全用 Markdown | 适配为 Markdown 风格 |
| 3 | `/recommend` 加到 ItemController | ItemController 是 `/items` 路径（已免认证） | 新建独立 RecommendController |
| 4 | RecommendMapper 跨表 JOIN `order_detail` + `order` + `item` | 微服务数据库隔离，item-service 无法访问 order 表 | 改为 Feign 调用 trade-service 获取已购商品 + item 表聚合偏好 |

**偏差 4 的详细背景**：设计文档第 6.1 节的 Phase 1 核心逻辑假设 item-service 和 trade-service 共享同一 MySQL 数据库（`shared-jdbc.yaml`），可直接跨表 JOIN `order_detail` + `order` + `item`。实际探索发现不同微服务的数据库是独立的，item-service 无法访问 trade-service 的 `order` / `order_detail` 表。正确做法是通过 Feign 调用 trade-service 新增的 `GET /orders/purchased-items` 端点获取已购商品 itemId+num，再在 item-service 侧查 item 表补充 category/brand 后聚合偏好。

---

## 十、后续优化方向

### 10.1 Phase 2 已完成项（用户画像持久化）

以下 Phase 2 项已通过 [hmall-agent-profile-and-notification-design.md](./hmall-agent-profile-and-notification-design.md) 第一部分落地：

| 方向 | 说明 | 阶段 | 状态 |
|------|------|------|------|
| 行为采集写入画像 | Agent 写操作工具成功后直接写 Redis 画像（`ProfileStore.record_event`），非 POST /behaviors + MQ | Phase 2 | ✅ 已完成 |
| Redis 画像计算 | `ProfileStore` HINCRBY 增量更新 `profile:{uid}:categories/brands/prices/stats`（非 Consumer + ZSet） | Phase 2 | ✅ 已完成 |
| 购买行为旁路采集 | `paySuccessListener` 支付成功后用 `StringRedisTemplate` HINCRBY 写入画像（非发 MQ 消息） | Phase 2 | ✅ 已完成 |
| 加购行为旁路采集 | `CartServiceImpl.addItem2Cart` 加购成功后用 `StringRedisTemplate` HINCRBY 写入画像（覆盖 Agent + 前端 UI 全路径） | Phase 2 | ✅ 已完成 |
| 推荐服务共享画像 | `RecommendServiceImpl.recommend()` 优先读 Redis 画像，miss 降级原 Feign 聚合 | Phase 2 | ✅ 已完成 |

### 10.2 待实施项

| 方向 | 说明 | 阶段 |
|------|------|------|
| 前端浏览埋点 | `ProductDetail.vue` 的 `onMounted` 上报 view 行为 | Phase 2 |
| Item-CF 共现矩阵 | `cf:{itemId}` Hash，定时任务计算 | Phase 2 |
| 向量召回 | ES `dense_vector` 语义相似，提升"看了又看"质量 | Phase 3 |
| 实时推荐反馈 | 用户点击/加购行为反馈到画像 | Phase 3 |
| 正则规则动态加载 | 推荐正则从 Nacos 动态加载 | Phase 3 |

### 10.3 Phase 2 实现说明

Phase 2 用户画像持久化已落地，详见 [hmall-agent-profile-and-notification-design.md](./hmall-agent-profile-and-notification-design.md) 第一部分。核心变更：

**Agent 侧（`hmall-agent/`）**：
- 新增 `src/profile/store.py` — `ProfileStore` 类：Redis 画像 CRUD + HINCRBY 增量更新，权重 purchase=5/cart=3/view=1（与 Phase 1 `_accumulate_preference` 一致）
- 新增 `src/profile/memory.py` — `save_memory` / `get_memories` 两个 @tool 工具，基于 LangGraph Store 跨会话语义记忆
- 修改 `tools.py` — `analyze_user_preferences` 画像优先（命中 0 次 Gateway）+ miss 降级 Phase 1 + 同步回写；加购/确认收货画像写入移至后端（避免双重记录），`update_cart_quantity_api` 不再记录 cart 事件
- 修改 `http_client.py` — 新增 `_extract_user_id` 函数（复用 `extract_token_from_config` 三级优先级 + JWT 兜底解码）
- 修改 `formatters.py` — `format_preferences` 适配画像直读模式（orders/cart 可选）
- 修改 `config.py` — 新增 `LANGGRAPH_STORE_URI`；`pyproject.toml` — `redis` 改为 `redis[hiredis]`

**后端侧（`hmall/`）**：
- 修改 `paySuccessListener.java` — 注入 `StringRedisTemplate` + `IOrderDetailService` + `ItemClient`，支付成功后查订单详情+商品信息，HINCRBY 写入 Redis 画像
- 修改 `CartServiceImpl.java` — 注入 `StringRedisTemplate`，加购成功后查商品信息，HINCRBY 写入 Redis 画像（覆盖 Agent + 前端 UI 全路径）
- 修改 `RecommendServiceImpl.java` — 注入 `StringRedisTemplate`，偏好聚合优先读 Redis 画像，miss 降级原 Feign 聚合

**关键实现偏差**：
1. **行为采集方式**：原设计为 `POST /behaviors` + MQ Consumer 异步落库，实际改为后端直接写 Redis（`paySuccessListener` 写 purchase 画像 + `CartServiceImpl` 写 cart 画像），覆盖 Agent + 前端 UI 全部行为路径，无需新增 API 和 MQ Consumer。Agent 侧 `analyze_user_preferences` miss 后同步回写画像
2. **Redis 画像结构**：原设计为 `up:` 前缀 + ZSet，实际改为 `profile:` 前缀 + Hash/List（HINCRBY 增量更新）
3. **序列化兼容性**：后端 `RedisService` 的 Hash 操作用 `redisTemplate`（Jackson 序列化），与 Agent 侧 `redis.asyncio`（plain string）不兼容。解决方案：后端画像读写均使用 `StringRedisTemplate`
4. **画像写入归属调整**：加购画像从 Agent 侧 `add_to_cart_api` 移至后端 `CartServiceImpl`（覆盖前端 UI 直接加购路径）；确认收货画像从 `confirm_receive_api` 移至 `paySuccessListener`（覆盖所有支付路径）；`update_cart_quantity_api` 不再记录 cart 事件（修改数量不改变偏好方向）。调整目的：避免双重记录 + 覆盖非 Agent 入口的行为

---

## 十一、与现有文档的关联

| 文档 | 关系 |
|------|------|
| `docs/Agent功能相关文档/agent-personalized-recommendation-design.md` | **设计文档**：本文档的实现源头，描述整体设计和 Phase 划分 |
| `docs/Agent功能相关文档/hmall-agent-implementation-report.md` | **关联**：Agent 整体实现说明，本文档在其基础上扩展推荐能力（工具 18→20） |
| `docs/redis功能相关文档/redis-integration-report.md` | **参考**：实现文档风格参照（分模块文件变更表 + 改造详情 + 降级回滚） |
| `hmall-agent/src/agents/customer/tools.py` | **修改**：新增 2 工具 + 辅助函数 + 注册 |
| `hmall-agent/src/agents/customer/prompts.py` | **修改**：SYSTEM_PROMPT 新增推荐能力声明 |
| `hmall-agent/src/agents/customer/regex_rules.py` | **修改**：新增 2 条推荐正则 + `_extract_recommend_scene` |
| `hmall-agent/src/agents/customer/agent.py` | **修改**：sources 新增 `/skills/personalized-recommendation/` |
| `hmall-agent/src/tools/formatters.py` | **修改**：新增 `format_recommendations` + `format_preferences` |
| `hmall-agent/src/workspace/customer/skills/personalized-recommendation/SKILL.md` | **新增**：推荐工作流规范 |
| `hmall/hm-api/src/main/java/com/hmall/api/client/SearchClient.java` | **新增**：search-service Feign 客户端 |
| `hmall/search-service/.../SearchController.java` | **修改**：新增 `/search/recommend` ES 召回端点 |
| `hmall/item-service/.../RecommendController.java` | **新增**：`GET /recommend` 接口 |
| `hmall/item-service/.../RecommendServiceImpl.java` | **新增**：三步管线核心实现 |
| `hmall/hm-api/.../client/TradeClient.java` | **修改**：新增 `queryPurchasedItems` Feign 方法 |
| `hmall/trade-service/.../OrderController.java` | **修改**：新增 `GET /orders/purchased-items` 端点 |
| `hmall/trade-service/.../OrderServiceImpl.java` | **修改**：实现已购商品按 itemId 聚合 num |
| `hmall/item-service/.../RecommendServiceImpl.java` | **新增**：三步管线核心实现（Feign 聚合偏好 + ES 召回 + MySQL 补充） |
| `hmall/hm-gateway/src/main/resources/application.yml` | **参考**：免认证路径配置（`/recommend/**` 不在排除列表） |

---

> **实现完成度**：Phase 1（步骤 1-6）全部实现，Agent 侧 2 个新工具 + 2 个 Formatter + 1 个 Skill + Prompt/正则/注册增强就绪，后端 `GET /recommend` 接口遵循"Feign 聚合偏好 → ES 召回 → 销量排序"三步管线（Feign 调用 trade-service 获取已购商品 + Feign 调用 search-service ES 召回），Gateway 路由需手动配置 Nacos。推荐具备完整降级链路（trade-service Feign 降级 → ES Fallback → MySQL 热销兜底 → Agent 搜索提示）。Phase 2（行为采集 + Redis 画像 + Item-CF）为后续优化项。


