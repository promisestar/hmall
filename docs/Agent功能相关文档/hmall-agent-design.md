# hmall Agent 智能助手设计文档

> 版本：v2.1  
> 日期：2026-07-16  
> 
> v2.1 变更：SDK 升级至 1.x、前端重构为独立页面 + Markdown 渲染、移除 configurable 改用 context-only

---

## 1. 概述

### 1.1 背景与目标

hmall（枫叶商城）已完成微服务架构搭建，包含商品、购物车、订单、支付、用户、搜索、秒杀、管理后台等完整链路。当前缺少 AI 智能助手，用户需要手动浏览页面完成购物流程，运营人员需要逐个页面查看数据。

本设计采用 DeepAgent 架构（LangGraph + DeepAgents），为 hmall 构建两个 AI Agent：

- **客服助手（CustomerAgent）**：面向 C 端用户，支持商品浏览、秒杀、购物车、订单、地址等全链路自然语言交互
- **管理助手（AdminAgent）**：面向运营人员，支持秒杀管理、订单查询、商品管理、库存状态查看等只读操作 + 运营日报

### 1.2 设计原则

| 原则 | 说明 |
|------|------|
| **三级路由** | L1 正则中间件（<5ms，拦截 80%+ 高频指令）→ L2 interrupt 状态机（多轮交互/二次确认）→ L3 LLM 兜底（~2s） |
| **Agent 零数据库** | 所有数据操作通过 Gateway → 微服务 API 完成，Agent 不直连数据库 |
| **双 Token 隔离** | C 端用户 JWT 和管理后台 JWT 独立验证，互不干扰，通过 `context_schema` 传递 |
| **二次确认** | 危险操作（取消订单、删除地址、清空购物车）通过 LangGraph `interrupt()` 实现 Human-in-the-loop |
| **空数据兜底** | 所有查询在代码层检测空数据，直接返回固定提示，不走 LLM |
| **降级策略** | LLM API 超时/异常时自动切换固定兜底文案 |
| **复用现有基建** | 复用 hmall 的 Redis（Checkpoint 后端）、Gateway（路由+认证）、Nacos（配置） |
| **DeepAgent 原生** | 使用 `create_agent()` 定义 Agent，Skills 中间件管理规范文件，LangGraph 负责图执行与状态持久化 |

### 1.3 与原 v1.0 设计（LangChain 版）的对比

| 维度 | v1.0（LangChain Core） | v2.0（DeepAgent） |
|------|----------------------|-------------------|
| Agent 框架 | LangChain Core（自定义 base_agent.py） | DeepAgents (`create_agent`) + LangGraph |
| 图执行引擎 | 无（自定义 Agent 调度器） | LangGraph (`langgraph-cli[inmem]`) |
| Web 框架 | FastAPI + WebSocket | LangGraph Server (uvicorn + langgraph_api) |
| Agent 定义 | 自定义 `base_agent.py`（多轮工具调用循环） | `create_agent()` 声明式定义 |
| 正则路由 | `intent_router.py`（自定义调度层） | `RegexShortcutMiddleware`（中间件拦截 model_call） |
| 状态机 | Redis 存储自定义状态（`agent:state:*`） | LangGraph `interrupt()` + Checkpoint 持久化 |
| 二次确认 | 自定义 `agent:confirm:*` Redis Key + 文本匹配 | LangGraph `interrupt()` 原生 Human-in-the-loop |
| 对话记忆 | 自定义 `ChatMemory`（Redis List, 20条/30min） | LangGraph Thread + Redis Checkpoint（自动管理） |
| 前端通信 | WebSocket / SSE / HTTP（自定义协议） | LangGraph SDK (`useStream` / `Client`) |
| 工具注册 | 函数列表传给 Agent | `get_all_tools()` + DeepAgent 中间件动态注入 |
| Skills | 无 | `SkillsMiddleware` + SKILL.md 规范文件 |
| 可观测性 | Loguru + Prometheus（可选） | LangGraph Studio + LangSmith（可选） |

---

## 2. 整体架构

### 2.1 系统架构

```
用户（C端 / 管理后台）
  │
  │  LangGraph SDK (HTTP + SSE)
  ▼
┌──────────────────────────────────────────────────────────────────┐
│              Agent Service (LangGraph Server :8090)                │
│                                                                    │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │  LangGraph API 层                                            │   │
│  │  ├── POST /threads/{id}/runs/stream（SSE 流式）              │   │
│  │  ├── POST /assistants/{id}/runs/stream（专用端点）           │   │
│  │  ├── GET  /assistants/search（助手列表）                     │   │
│  │  ├── POST /threads（创建线程）                               │   │
│  │  ├── POST /threads/search（线程列表）                        │   │
│  │  ├── POST /threads/{id}/state（线程状态/文件同步）           │   │
│  │  ├── DELETE /threads/{id}（删除线程）                        │   │
│  │  └── POST /api/v1/batch-report（自定义路由：批量运营报告）    │   │
│  └────────────────────────────────────────────────────────────┘   │
│                          │                                         │
│  ┌───────────────────────▼────────────────────────────────────┐   │
│  │  中间件层（DeepAgent Middleware Chain）                      │   │
│  │  ├── AuthMiddleware（双 JWT 认证：C端 / 管理端）              │   │
│  │  ├── PermissionMiddleware（工具权限拦截：AdminAgent 纯只读）  │   │
│  │  ├── RegexShortcutMiddleware（L1 正则快捷路由：<5ms）        │   │
│  │  ├── SkillsMiddleware（SKILL.md 规范加载）                   │   │
│  │  └── RAGMiddleware（RAG 动态控制，预留）                     │   │
│  └───────────────────────┬────────────────────────────────────┘   │
│                          │                                         │
│  ┌───────────────────────▼────────────────────────────────────┐   │
│  │  Agent 层（DeepAgents create_agent）                         │   │
│  │                                                               │   │
│  │  CustomerAgent                      AdminAgent                │   │
│  │  ├── model: qwen-turbo              ├── model: qwen-turbo     │   │
│  │  ├── tools: 18 个 C 端工具           ├── tools: 10 个管理工具  │   │
│  │  ├── middleware: 5 个                ├── middleware: 4 个     │   │
│  │  ├── skills: 5 个 SKILL.md           ├── skills: 3 个 SKILL.md│   │
│  │  ├── context_schema: Context         ├── context_schema: Context│   │
│  │  └── interrupt: 二次确认/状态机      └── interrupt: （预留）  │   │
│  └───────────────────────┬────────────────────────────────────┘   │
│                          │                                         │
│  ┌───────────────────────▼────────────────────────────────────┐   │
│  │  工具层（LangChain @tool → httpx → Java API Proxy）          │   │
│  │  ├── customer_api.py（18 个 C 端工具）                        │   │
│  │  └── admin_api.py（10 个管理端工具）                          │   │
│  └───────────────────────┬────────────────────────────────────┘   │
│                          │                                         │
│  ┌───────────────────────▼────────────────────────────────────┐   │
│  │  基础设施                                                    │   │
│  │  ├── Redis（LangGraph Checkpoint 后端，db=1 隔离）           │   │
│  │  ├── DeepAgents（Agent 框架）                                │   │
│  │  ├── LangGraph（图执行引擎 + API Server）                    │   │
│  │  └── 通义千问 DashScope（LLM 推理，OpenAI 兼容）              │   │
│  └────────────────────────────────────────────────────────────┘   │
└──────────────────────────┬───────────────────────────────────────┘
                           │ HTTP (httpx)
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│              hm-gateway (:8080)                                    │
│  ├── AuthGlobalFilter（JWT 认证 + user-id 透传）                    │
│  ├── RateLimitFilter（秒杀限流）                                    │
│  └── DynamicRouteLoader（Nacos 动态路由）                           │
└──┬──────┬────────┬────────┬────────┬────────┬───────────────────┘
   │      │        │        │        │        │
   ▼      ▼        ▼        ▼        ▼        ▼
 item   cart    user    trade    pay    admin    search
:8081   :8082   :8084   :8085   :8083   :8090   :8089
```

### 2.2 三级路由架构（DeepAgent 中间件实现）

```
用户消息（通过 LangGraph SDK stream.submit）
  │
  ├─ L1: RegexShortcutMiddleware (<5ms)
  │   ├── 拦截 wrap_model_call，在 LLM 调用前检查用户消息
  │   ├── 匹配 "查看订单" / "查看购物车" / "秒杀活动" 等高频指令
  │   ├── 直接调用对应 @tool + 代码格式化输出
  │   ├── 返回 AIMessage（无 tool_call）→ Agent 图直接到 END
  │   └── 拦截 80%+ 请求，零 LLM 成本
  │
  ├─ L2: LangGraph interrupt() (多轮交互 / 二次确认)
  │   ├── 地址修改：interrupt 请求字段 → 用户回复 → interrupt 请求新值 → 执行
  │   ├── 秒杀下单：查活动 → interrupt 确认 → 用户回复"确认" → 下单
  │   ├── 二次确认：取消订单/删除地址/清空购物车 → interrupt 等待"确认取消"
  │   └── 前端 InterruptActions 组件处理批准/编辑/拒绝
  │
  └─ L3: LLM 兜底 (~2s，DeepAgent 默认行为)
      ├── 闲聊 / 复杂问题 / L1/L2 未命中
      ├── LLM 自主选择工具调用（DeepAgent Agent Loop）
      └── 结果格式化输出
```

### 2.3 技术栈

| 类别 | 技术 | 说明 |
|------|------|------|
| **语言** | Python ≥ 3.12 | |
| **包管理** | uv | pip 替代，快速依赖解析 |
| **Agent 框架** | DeepAgents (`deepagents>=0.5.9`) | `create_agent()` 声明式定义 |
| **图执行引擎** | LangGraph (`langgraph-cli[inmem]>=0.4.26`) | 图执行 + API Server + Checkpoint |
| **LLM** | 通义千问 qwen-turbo（DashScope，OpenAI 兼容接口） | 通过 `langchain-openai` ChatOpenAI 接入 |
| **LLM 框架** | LangChain 1.x | 消息管理 + 工具调用 |
| **Checkpoint 后端** | Redis（复用 hmall Redis，db=1） | `langgraph-checkpoint-redis` |
| **HTTP 客户端** | httpx | 异步调用 Java 后端 API |
| **MCP 协议** | FastMCP + `langchain-mcp-adapters` | RAG 桥接（预留） |
| **可观测性** | LangGraph Studio + LangSmith（可选） | 图可视化 + 追踪 |
| **日志** | logging（uvicorn 内置） | 结构化日志 |

### 2.4 Agent 注册与路由

**第 1 步：`graph.json` 定义 Agent 注册名**

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

**第 2 步：`start_server.py` 注入环境变量**

```python
os.environ["LANGSERVE_GRAPHS"] = json.dumps(graphs)
```

**第 3 步：LangGraph 自动注册路由**

| 专用端点（推荐） | 通用端点 |
|-----------------|---------|
| `POST /assistants/customer_agent/runs/stream` | `POST /runs/stream` + `{"assistant_id": "customer_agent", ...}` |
| `POST /assistants/admin_agent/runs/stream` | `POST /runs/stream` + `{"assistant_id": "admin_agent", ...}` |

**前端 Agent 选择**：通过 `ConfigDialog` 选择 `assistantId`（`customer_agent` 或 `admin_agent`）→ `localStorage` 持久化 → `useStream` 建立到对应 Agent 的流式连接。

---

## 3. CustomerAgent 设计（C 端客服助手）

### 3.1 Agent 定义

```python
# src/agents/customer/agent.py
from dataclasses import dataclass
from pathlib import Path

from deepagents import create_deep_agent as create_agent
from deepagents.backends import FilesystemBackend
from deepagents.middleware import SkillsMiddleware
from langchain.agents.middleware import AgentMiddleware, ModelRequest, ModelResponse, wrap_model_call

from src.core.llms import qwen_model
from src.middleware.auth import AuthMiddleware
from src.middleware.permission import PermissionMiddleware
from src.middleware.regex_shortcut import RegexShortcutMiddleware

from src.agents.customer.prompts import SYSTEM_PROMPT
from src.agents.customer.tools import get_all_tools


@dataclass
class Context:
    """CustomerAgent 运行时上下文（通过 LangGraph SDK context 传入）"""
    agent_type: str = "customer"     # "customer" or "admin"
    user_id: str = ""                # 当前 C 端用户 ID
    user_token: str = ""             # C 端用户 JWT Token
    enable_rag: bool = False         # RAG 开关（预留）


# ============================================================================
# Skills 配置
# ============================================================================
skills_root = str((Path(__file__).parent.parent.parent / "workspace" / "customer").resolve())
skills_backend = FilesystemBackend(root_dir=skills_root, virtual_mode=True)

skills_middleware = SkillsMiddleware(
    backend=skills_backend,
    sources=[
        "/skills/shopping-guide/",
        "/skills/seckill-order/",
        "/skills/cart-management/",
        "/skills/order-management/",
        "/skills/address-management/",
    ]
)

# L1 正则快捷路由中间件
regex_middleware = RegexShortcutMiddleware(
    tool_registry=get_all_tools(),
    rules=REGEX_RULES,  # 见 3.3 节
)

# ============================================================================
# Agent 创建
# ============================================================================
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

### 3.2 工具清单（18 个）

基于 hmall C 端 API 设计，所有工具通过 Gateway `:8080` 调用，使用 `@tool` 装饰器注册：

#### 商品浏览（3 个）

| 工具名 | API | 说明 |
|--------|-----|------|
| `search_items_api` | `GET /search` | 搜索商品（ES 全文检索） |
| `get_item_detail_api` | `GET /items/{id}` | 商品详情 |
| `get_item_page_api` | `GET /items/page` | 分页浏览商品 |

#### 秒杀（3 个）

| 工具名 | API | 说明 |
|--------|-----|------|
| `get_seckill_activities_api` | `GET /seckill/activities` | 秒杀活动列表（含场次+商品） |
| `get_seckill_product_api` | `GET /seckill/products/{relationId}` | 秒杀商品详情（含实时库存） |
| `do_seckill_api` | `POST /seckill/order/{relationId}` | 秒杀下单（需登录，**interrupt 二次确认**） |

#### 购物车（5 个）

| 工具名 | API | 说明 |
|--------|-----|------|
| `get_cart_list_api` | `GET /carts` | 购物车列表（需登录） |
| `add_to_cart_api` | `POST /carts` | 加入购物车（需登录） |
| `update_cart_quantity_api` | `PUT /carts/{itemId}` | 修改数量（需登录） |
| `delete_cart_item_api` | `DELETE /carts/{itemId}` | 删除商品（需登录，**interrupt 二次确认**） |
| `clear_cart_api` | `DELETE /carts` | 清空购物车（需登录，**interrupt 二次确认**） |

#### 订单（4 个）

| 工具名 | API | 说明 |
|--------|-----|------|
| `get_order_list_api` | `GET /orders/page` | 订单列表（需登录，支持状态筛选） |
| `get_order_detail_api` | `GET /orders/{id}` | 订单详情（需登录） |
| `cancel_order_api` | `POST /orders/batch/close` | 取消订单（需登录，**interrupt 二次确认**） |
| `confirm_receive_api` | `PUT /orders/{orderId}` | 确认收货（需登录，**interrupt 二次确认**） |

#### 收货地址（3 个）

| 工具名 | API | 说明 |
|--------|-----|------|
| `get_address_list_api` | `GET /addresses` | 地址列表（需登录） |
| `add_address_api` | `POST /addresses` | 新增地址（需登录，**interrupt 多轮收集**） |
| `update_address_api` | `PUT /addresses/{addressId}` | 修改地址（需登录，**interrupt 多轮收集**） |

> **注**：hmall 暂无优惠券和售后功能，相比 nova-mall-agent 减少 5 个工具。

#### 工具实现示例

```python
# src/agents/customer/tools.py
from langchain_core.tools import tool
from langgraph.types import interrupt
from src.gateway.http_client import gateway_client


@tool
async def get_seckill_activities_api() -> str:
    """获取当前所有秒杀活动列表，含场次、商品和实时库存信息。"""
    result = await gateway_client.get("/seckill/activities")
    if not result:
        return "当前没有进行中的秒杀活动"
    return format_seckill_activities(result)


@tool
async def do_seckill_api(relation_id: int) -> str:
    """秒杀下单。需要二次确认。
    
    Args:
        relation_id: 秒杀商品关联 ID
    """
    # 先查询商品详情
    product = await gateway_client.get(f"/seckill/products/{relation_id}")
    if not product:
        return f"未找到秒杀商品 relationId={relation_id}"

    # L2: interrupt 请求用户确认
    approval = interrupt({
        "type": "confirmation",
        "message": (
            f"确认秒杀以下商品？\n"
            f"商品名: {product['itemName']}\n"
            f"秒杀价: ¥{product['seckillPrice']}\n"
            f"限购: {product['limit']}件\n"
            f"剩余: {product['stock']}件\n"
            f"回复\"确认\"下单"
        ),
        "expected_response": "确认",
    })

    if approval.strip() == "确认":
        result = await gateway_client.post(f"/seckill/order/{relation_id}")
        return f"✅ 秒杀请求已提交，正在排队... → {result}"
    else:
        return "❌ 已取消秒杀"


@tool
async def update_address_api(address_id: int) -> str:
    """修改收货地址。通过多轮交互收集修改字段和新值。
    
    Args:
        address_id: 地址 ID（序号）
    """
    # L2: interrupt 请求修改字段
    field_response = interrupt({
        "type": "field_selection",
        "message": f"请问要修改地址{address_id}的哪个字段？(姓名/手机号/省份/城市/区/详细地址)",
    })

    field = field_response.strip()

    # L2: interrupt 请求新值
    new_value = interrupt({
        "type": "value_input",
        "message": f"请输入新的{field}",
    })

    result = await gateway_client.put(
        f"/addresses/{address_id}",
        json={"field": field, "value": new_value}
    )
    return f"✅ 地址{address_id}的{field}已修改为{new_value}"


def get_all_tools():
    """返回 CustomerAgent 所需的全部工具列表。"""
    return [
        # 商品浏览
        search_items_api, get_item_detail_api, get_item_page_api,
        # 秒杀
        get_seckill_activities_api, get_seckill_product_api, do_seckill_api,
        # 购物车
        get_cart_list_api, add_to_cart_api, update_cart_quantity_api,
        delete_cart_item_api, clear_cart_api,
        # 订单
        get_order_list_api, get_order_detail_api, cancel_order_api, confirm_receive_api,
        # 地址
        get_address_list_api, add_address_api, update_address_api,
    ]
```

### 3.3 L1 正则快捷路由中间件

```python
# src/middleware/regex_shortcut.py
import re
from langchain.agents.middleware import AgentMiddleware, ModelRequest, ModelResponse
from langchain_core.messages import AIMessage


# 正则路由规则：(pattern, tool_name, param_extractor)
REGEX_RULES = [
    # 秒杀
    (r'(?:查看|查询|当前).{0,3}秒杀', 'get_seckill_activities_api', None),
    # 购物车
    (r'(?:查看|查询).{0,5}购物车', 'get_cart_list_api', None),
    (r'清空\s*购物车', 'clear_cart_api', None),  # 注：clear_cart 需二次确认，不拦截
    # 订单
    (r'(?:查询|查看).{0,5}(?:待付款|待发货|待收货|已完成)?订单', 'get_order_list_api', None),
    (r'(?:查看|看)\s*(?:订单\s*)?(\d+)', 'get_order_detail_api', lambda m: {"order_id": int(m.group(1))}),
    # 地址
    (r'(?:查询|查看).{0,5}地址', 'get_address_list_api', None),
    # 商品
    (r'(?:搜索|查找|找)\s*(.+)', 'search_items_api', lambda m: {"keyword": m.group(1)}),
    (r'(?:商品|商品列表)', 'get_item_page_api', None),
]


class RegexShortcutMiddleware(AgentMiddleware):
    """L1 正则快捷路由中间件。
    
    在 LLM 调用前检查用户消息，匹配高频指令时直接调用对应工具并返回结果，
    跳过 LLM 推理，实现 <5ms 响应。
    
    不匹配的消息正常传递给 LLM（L3 兜底）。
    二次确认类操作（取消订单/删除/清空）不在 L1 拦截，由 L2 interrupt 处理。
    """
    
    def __init__(self, tool_registry, rules):
        super().__init__()
        self._tools = {t.name: t for t in tool_registry}
        self._rules = rules
    
    def _try_shortcut(self, request: ModelRequest) -> AIMessage | None:
        """尝试正则匹配，命中则直接调用工具返回结果。"""
        last_msg = request.messages[-1]
        if last_msg.type != "human":
            return None
        
        text = last_msg.content if isinstance(last_msg.content, str) else ""
        
        for pattern, tool_name, extractor in self._rules:
            match = re.search(pattern, text)
            if match and tool_name in self._tools:
                tool = self._tools[tool_name]
                params = extractor(match) if extractor else {}
                try:
                    result = tool.invoke(params)
                    return AIMessage(content=str(result))
                except Exception as e:
                    # 工具调用失败，降级到 LLM
                    return None
        return None
    
    def wrap_model_call(self, request, handler):
        shortcut = self._try_shortcut(request)
        if shortcut is not None:
            return shortcut  # 直接返回，跳过 LLM
        return handler(request)  # 正常走 LLM
    
    async def awrap_model_call(self, request, handler):
        shortcut = self._try_shortcut(request)
        if shortcut is not None:
            return shortcut
        return await handler(request)
```

**正则路由规则表**：

| 用户输入示例 | 匹配规则 | 路由工具 | 是否拦截 |
|-------------|---------|---------|---------|
| `查看秒杀` / `秒杀活动` | `(?:查看\|查询\|当前).{0,3}秒杀` | `get_seckill_activities_api` | ✅ L1 |
| `搜索手机` / `查找商品` | `(?:搜索\|查找\|找)\s*(.+)` | `search_items_api` | ✅ L1 |
| `查看购物车` / `我的购物车` | `(?:查看\|查询).{0,5}购物车` | `get_cart_list_api` | ✅ L1 |
| `查看订单` / `待付款订单` | `(?:查询\|查看).{0,5}(?:待付款\|...)?订单` | `get_order_list_api` | ✅ L1 |
| `查看订单100` | `(?:查看\|看)\s*(?:订单\s*)?(\d+)` | `get_order_detail_api` | ✅ L1 |
| `查看地址` / `我的地址` | `(?:查询\|查看).{0,5}地址` | `get_address_list_api` | ✅ L1 |
| `取消订单100` | `取消\s*(?:订单\s*)?(\d+)` | `cancel_order_api` | ❌ L2（需确认） |
| `确认收货100` | `确认\s*(?:收货\s*)?(\d+)` | `confirm_receive_api` | ❌ L2（需确认） |
| `清空购物车` | `清空\s*购物车` | `clear_cart_api` | ❌ L2（需确认） |
| `修改地址1` | `修改\s*(\d+)` | `update_address_api` | ❌ L2（多轮） |
| `新增地址` | `新增地址\|添加地址` | `add_address_api` | ❌ L2（多轮） |

### 3.4 L2 interrupt 状态机设计

#### 地址修改状态机（interrupt 实现）

```
用户: "修改地址1"
  │
  ├─ LLM 调用 update_address_api(address_id=1)
  │
  ├─ 工具内 interrupt #1（field_selection）:
  │   → Agent 暂停执行，返回 interrupt 给前端
  │   → 前端 InterruptActions 展示: "请问要修改哪个字段？"
  │
  ├─ 用户回复: "姓名"
  │   → stream.submit(null, {command: {resume: "姓名"}})
  │   → Agent 从断点恢复，field = "姓名"
  │
  ├─ 工具内 interrupt #2（value_input）:
  │   → Agent 再次暂停
  │   → 前端展示: "请输入新的姓名"
  │
  ├─ 用户回复: "张三"
  │   → stream.submit(null, {command: {resume: "张三"}})
  │   → Agent 恢复，new_value = "张三"
  │
  └─ 执行 PUT /addresses/1 → ✅ 地址1的姓名已修改为张三
```

#### 秒杀下单流程（interrupt 实现）

```
用户: "秒杀商品100"
  │
  ├─ LLM 调用 do_seckill_api(relation_id=100)
  │
  ├─ 工具内查询商品详情 → 获取 iPhone 15, ¥5999, 限购1件, 剩余45件
  │
  ├─ 工具内 interrupt（confirmation）:
  │   → Agent 暂停，返回商品信息和确认提示
  │   → 前端 InterruptActions 展示确认卡片
  │
  ├─ 用户: "确认"
  │   → stream.submit(null, {command: {resume: "确认"}})
  │   → Agent 恢复，执行 POST /seckill/order/100
  │
  └─ ✅ 秒杀成功！订单号: 123456，请尽快支付
```

#### 二次确认机制

| 操作 | interrupt 消息 | 恢复条件 |
|------|---------------|---------|
| 取消订单 | `确定要取消订单「{orderId}」？总金额 ¥{totalFee}。回复"确认取消"执行` | 用户回复"确认取消" |
| 确认收货 | `确定已收到订单「{orderId}」的商品？回复"确认收货"执行` | 用户回复"确认收货" |
| 删除购物车 | `确定要删除购物车中的「{itemName}」？回复"确认删除"执行` | 用户回复"确认删除" |
| 清空购物车 | `确定要清空购物车中的所有商品？回复"确认删除"执行` | 用户回复"确认删除" |
| 秒杀下单 | `确认秒杀商品「{itemName}」秒杀价 ¥{price}？回复"确认"下单` | 用户回复"确认" |

> **与 v1.0 的区别**：v1.0 使用 Redis Key `agent:confirm:*` 存储待确认操作 + 文本匹配恢复；v2.0 使用 LangGraph `interrupt()` 原生暂停图执行，Checkpoint 自动持久化中断状态，前端通过 `stream.submit(null, {command: {resume: value}})` 恢复。

### 3.5 Skills 设计

```markdown
# src/workspace/customer/skills/shopping-guide/SKILL.md

# 购物引导技能

## 适用场景
用户想要浏览、搜索、查看商品详情时激活此技能。

## 工作流程
1. 确认用户的搜索意图（关键词/分类/价格区间）
2. 调用 search_items_api 或 get_item_page_api 获取商品列表
3. 如用户追问某商品，调用 get_item_detail_api 获取详情
4. 格式化输出：商品名、价格、库存、图片链接

## 输出格式
📦 搜索结果（共 N 件）
─────────────────────
1. iPhone 15 | ¥5999 | 库存 45 件
2. MacBook Air | ¥8999 | 库存 12 件
...
```

Skills 文件清单：

| Skill | 路径 | 说明 |
|-------|------|------|
| `shopping-guide` | `/skills/shopping-guide/SKILL.md` | 商品浏览引导 |
| `seckill-order` | `/skills/seckill-order/SKILL.md` | 秒杀下单流程 |
| `cart-management` | `/skills/cart-management/SKILL.md` | 购物车管理 |
| `order-management` | `/skills/order-management/SKILL.md` | 订单查询与操作 |
| `address-management` | `/skills/address-management/SKILL.md` | 地址管理（含状态机） |

---

## 4. AdminAgent 设计（管理助手）

### 4.1 Agent 定义

```python
# src/agents/admin/agent.py
from dataclasses import dataclass
from pathlib import Path

from deepagents import create_deep_agent as create_agent
from deepagents.backends import FilesystemBackend
from deepagents.middleware import SkillsMiddleware

from src.core.llms import qwen_model
from src.middleware.auth import AuthMiddleware
from src.middleware.permission import PermissionMiddleware
from src.middleware.regex_shortcut import RegexShortcutMiddleware

from src.agents.admin.prompts import SYSTEM_PROMPT
from src.agents.admin.tools import get_all_tools, REGEX_RULES


@dataclass
class Context:
    """AdminAgent 运行时上下文"""
    agent_type: str = "admin"
    user_id: str = ""                # 管理员 ID
    user_token: str = ""             # 管理后台 JWT Token
    enable_rag: bool = False         # RAG 开关


# ============================================================================
# Skills 配置
# ============================================================================
skills_root = str((Path(__file__).parent.parent.parent / "workspace" / "admin").resolve())
skills_backend = FilesystemBackend(root_dir=skills_root, virtual_mode=True)

skills_middleware = SkillsMiddleware(
    backend=skills_backend,
    sources=[
        "/skills/daily-report/",
        "/skills/data-query/",
        "/skills/rag-query/",
    ]
)

regex_middleware = RegexShortcutMiddleware(
    tool_registry=get_all_tools(),
    rules=REGEX_RULES,
)

# ============================================================================
# Agent 创建
# ============================================================================
agent = create_agent(
    model=qwen_model,
    tools=get_all_tools(),
    backend=skills_backend,
    middleware=[
        AuthMiddleware(),
        PermissionMiddleware(),        # AdminAgent 纯只读，拦截所有写操作
        regex_middleware,              # L1 正则快捷路由（运营日报等）
        skills_middleware,
    ],
    system_prompt=SYSTEM_PROMPT,
    context_schema=Context,
)
```

### 4.2 工具清单（10 个，纯只读）

基于 hmall admin-service + trade-service 管理端 API：

#### 商品管理（2 个）

| 工具名 | API（经 admin-service 代理） | 说明 |
|--------|-----|------|
| `admin_get_product_page_api` | `GET /admin/product/list` | 分页查询商品 |
| `admin_get_product_detail_api` | `GET /admin/product/{id}` | 商品详情 |

#### 订单管理（2 个）

| 工具名 | API | 说明 |
|--------|-----|------|
| `admin_get_order_page_api` | `GET /admin/order/list` | 分页查询订单（状态/时间筛选） |
| `admin_get_order_detail_api` | `GET /admin/order/{id}` | 订单详情 |

#### 秒杀管理（4 个）

| 工具名 | API | 说明 |
|--------|-----|------|
| `admin_get_seckill_promotion_page_api` | `GET /admin/seckill/promotion/list` | 秒杀活动列表 |
| `admin_get_seckill_relation_page_api` | `GET /admin/seckill/relation/list` | 秒杀商品关联列表（含实时库存） |
| `admin_get_seckill_order_page_api` | `GET /admin/seckill/order/list` | 秒杀订单列表 |
| `admin_get_seckill_stock_api` | `GET /admin/seckill/stock/{relationId}` | 每日库存快照 |

#### 用户管理（2 个）

| 工具名 | API | 说明 |
|--------|-----|------|
| `admin_get_user_page_api` | `GET /admin/member/list` | C 端用户列表 |
| `admin_get_user_detail_api` | `GET /admin/member/{id}` | 用户详情 |

> **注**：AdminAgent 纯只读，所有写操作（创建/修改/删除/发货/预热）均不可用，由 PermissionMiddleware 阻止。

### 4.3 运营日报（多工具编排）

自然语言触发（`运营日报` / `帮我做一份日报` / `生成周报`），L1 正则匹配后直接编排 5 个工具调用：

```python
# src/agents/admin/tools.py（运营日报工具）
@tool
async def generate_daily_report() -> str:
    """生成运营日报。自动编排 5 个查询工具，格式化输出运营摘要。"""
    # 并发调用 5 个查询工具
    orders, seckill, stock, products, users = await asyncio.gather(
        admin_get_order_page_api.invoke({"page": 1, "size": 1}),
        admin_get_seckill_promotion_page_api.invoke({"page": 1, "size": 1}),
        admin_get_seckill_relation_page_api.invoke({"page": 1, "size": 1}),
        admin_get_product_page_api.invoke({"page": 1, "size": 1}),
        admin_get_user_page_api.invoke({"page": 1, "size": 1}),
    )
    
    return format_daily_report(orders, seckill, stock, products, users)
```

**日报输出格式**：

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📅 2026-07-15 枫叶商城运营日报
━━━━━━━━━━━━━━━━━━━━━━━━━━━━

【订单概览】
- 今日订单: 156 笔
- 订单金额: ¥89,200
- 待发货: 23 笔

【秒杀活动】
- 进行中活动: 3 场
- 库存预警商品: 12 件
- 秒杀订单: 89 笔

【商品概况】
- 在售商品: 248 件
- 已下架: 15 件

【用户概况】
- 总用户数: 1,230
- 今日新增: 12
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**AdminAgent 正则路由规则**：

| 用户输入 | 匹配规则 | 路由工具 |
|---------|---------|---------|
| `运营日报` / `生成日报` | `(?:运营\|生成\|帮我做).{0,3}日报` | `generate_daily_report` |
| `查看商品列表` | `(?:查看\|查询\|商品).{0,3}列表` | `admin_get_product_page_api` |
| `查看订单` | `(?:查看\|查询).{0,5}订单` | `admin_get_order_page_api` |
| `秒杀活动列表` | `(?:秒杀\|查看).{0,3}活动` | `admin_get_seckill_promotion_page_api` |

### 4.4 RAG 知识增强（规划中）

AdminAgent 可集成 RAG 知识库，通过 MCP 协议桥接 LightRAG 服务：

```
AdminAgent                        RAG MCP Server              LightRAG
──────────────────────────────────────────────────────────────────────
  admin_agent                         rag_server.py           lightrag-server
       │                              (FastMCP, :8008)        (:9621)
       ├─ rag_query() ──── MCP ────►       │
       ├─ rag_query_data()                  ├─ /query
       └─ rag_graph_search()                ├─ /query/data
              │                             └─ /graph/*
              └─ httpx.AsyncClient ────────►│
```

**知识库文档**：

```
knowledge_base/
└── admin_knowledge/
    ├── seckill_strategy.md        # 秒杀运营策略
    ├── inventory_management.md    # 库存管理指南
    ├── order_analysis.md          # 订单分析指南
    ├── user_segmentation.md       # 用户分群方法
    └── data_interpretation.md     # 数据指标解读
```

用户提问 `秒杀库存怎么设置合理？` → RAGMiddleware 注入 RAG 工具 + 检索知识库 → LLM 专业回答。

---

## 5. 对话记忆设计

### 5.1 LangGraph Thread + Redis Checkpoint

v2.0 使用 LangGraph 的 Thread 机制替代自定义 Redis 对话记忆。每个对话线程（Thread）对应一个 `thread_id`，LangGraph 自动管理消息历史和状态持久化。

```
v1.0: 自定义 ChatMemory（Redis List, 20条/30min TTL, 手动管理）
v2.0: LangGraph Thread + Redis Checkpoint（自动管理, 支持 interrupt 恢复）
```

**Redis Checkpoint 配置**：

```python
# src/core/redis_checkpoint.py
from langgraph.checkpoint.redis import RedisSaver
from src.core.config import settings

# 复用 hmall Redis，使用 db=1 隔离（hmall 业务用 db=0）
checkpointer = RedisSaver(
    redis_url=f"redis://{settings.REDIS_HOST}:{settings.REDIS_PORT}/{settings.REDIS_DB}"
)
```

**Checkpoint 机制说明**：

| 机制 | 说明 |
|------|------|
| Thread | 每个对话线程有唯一 `thread_id`，前端通过 SDK 创建和管理 |
| Checkpoint | 每次图节点执行后自动保存状态（messages, todos, files 等）到 Redis |
| 恢复 | 通过 `thread_id` 自动加载历史消息，支持中断恢复（interrupt） |
| 清理 | 删除 Thread 时自动清理 Checkpoint 数据（`DELETE /threads/{id}`） |

**与 v1.0 的对比**：

| 维度 | v1.0 ChatMemory | v2.0 LangGraph Checkpoint |
|------|----------------|--------------------------|
| 存储 | Redis List（手动 rpush/ltrim） | Redis Checkpoint（自动 kv 存储） |
| 消息限制 | 固定 20 条（ltrim 裁剪） | 无硬限制（可配置 recursion_limit） |
| TTL | 30 分钟手动 expire | 线程删除时清理（或配置 TTL） |
| 状态持久化 | 仅消息文本 | 完整图状态（messages + interrupt 状态 + todos + files） |
| 中断恢复 | 不支持（自定义 confirm Key 模拟） | 原生支持（interrupt + resume） |
| 隔离 | `agent:chat:{userId}:{conversationId}` | `thread_id`（LangGraph 管理） |

### 5.2 Thread 生命周期

```
1. 前端 client.threads.create() → 创建 Thread，返回 thread_id
2. 用户发送消息 → stream.submit({messages: [...]})
   → LangGraph 从 Redis 加载该 thread 的 Checkpoint
   → 执行 Agent 图（中间件 → LLM/正则 → 工具 → interrupt/end）
   → 每步自动保存 Checkpoint 到 Redis
3. interrupt 暂停 → Checkpoint 保存中断状态
   → 用户回复 → stream.submit(null, {command: {resume: value}})
   → 从 Checkpoint 恢复，继续执行
4. 用户切换对话 → 新 thread_id → 加载另一个 Thread 的 Checkpoint
5. 删除对话 → client.threads.delete(thread_id) → 清理 Redis Checkpoint
```

---

## 6. 安全设计

### 6.1 双 JWT 认证中间件

```python
# src/middleware/auth.py
from langchain.agents.middleware import AgentMiddleware, ModelRequest
from src.gateway.auth import verify_jwt


class AuthMiddleware(AgentMiddleware):
    """双 JWT 认证中间件。
    
    从 context_schema 读取 user_token，验证 JWT 有效性，
    提取 user_id 用于数据隔离。
    
    Token 来源：
    - C 端：用户登录 POST /users/login → hmall.jks（RSA）
    - 管理端：管理后台登录 POST /admin/login → admin.jks（RSA，独立）
    """
    
    def wrap_model_call(self, request, handler):
        context = request.runtime.context if request.runtime else None
        if not context or not context.user_token:
            # 无 Token，仅允许只读操作（如查看商品）
            return handler(request)
        
        # 验证 JWT
        user_info = verify_jwt(context.user_token, context.agent_type)
        if user_info:
            # 注入 user_id 到 context
            context.user_id = user_info["user_id"]
        
        return handler(request)
    
    async def awrap_model_call(self, request, handler):
        return self.wrap_model_call(request, handler)
```

| Agent | Token 来源 | 密钥 | 验证方式 |
|-------|-----------|------|---------|
| CustomerAgent | C 端用户登录 `POST /users/login` | `hmall.jks`（RSA） | AuthMiddleware 本地验证 / Gateway 验证 |
| AdminAgent | 管理后台登录 `POST /admin/login` | `admin.jks`（RSA，独立） | AuthMiddleware 本地验证 / Gateway 验证 |

**Token 传递链**：

```
前端 → LangGraph SDK stream.submit({messages: [...]}, {config: {...}, context: {user_token: "xxx", agent_type: "customer"}})
     → LangGraph Runtime 创建 Context(user_token="xxx", agent_type="customer")
     → AuthMiddleware 读取 request.runtime.context.user_token
     → 验证 JWT → 注入 user_id
     → 工具调用时 gateway_client 携带 user_token 到 Gateway
```

### 6.2 工具权限拦截中间件

```python
# src/middleware/permission.py
from langchain.agents.middleware import AgentMiddleware, ModelRequest


# 写操作工具集（危险操作）
WRITE_TOOLS = {
    "add_to_cart_api", "update_cart_quantity_api", "delete_cart_item_api",
    "clear_cart_api", "cancel_order_api", "confirm_receive_api",
    "add_address_api", "update_address_api", "do_seckill_api",
}


class PermissionMiddleware(AgentMiddleware):
    """工具权限拦截中间件。
    
    AdminAgent 纯只读：过滤掉所有写操作工具，LLM 无法选择它们。
    CustomerAgent：允许所有工具（写操作需 Token + 二次确认）。
    """
    
    def wrap_model_call(self, request, handler):
        context = request.runtime.context if request.runtime else None
        agent_type = getattr(context, "agent_type", "customer") if context else "customer"
        
        if agent_type == "admin":
            # 过滤掉写操作工具
            filtered_tools = [
                tool for tool in request.tools 
                if tool.name not in WRITE_TOOLS
            ]
            return handler(request.override(tools=filtered_tools))
        
        return handler(request)
    
    async def awrap_model_call(self, request, handler):
        return self.wrap_model_call(request, handler)
```

| Agent | 读操作 | 写操作 |
|-------|--------|--------|
| CustomerAgent | ✅ 全部 C 端读工具 | ✅ 购物车/订单/地址写操作（需 Token + interrupt 确认） |
| AdminAgent | ✅ 全部管理端读工具 | ❌ 所有写操作被 PermissionMiddleware 过滤 |

### 6.3 参数校验

代码层正则 + 类型检查（在 `@tool` 函数内校验）：
- 数量 `quantity >= 1`
- 手机号 11 位数字
- 地址序号为正整数
- 商品 ID 为正整数

---

## 7. 中间件体系

### 7.1 中间件链

| 顺序 | 中间件 | 位置 | 功能 |
|------|--------|------|------|
| 1 | `AuthMiddleware` | `src/middleware/auth.py` | 双 JWT 认证，注入 user_id |
| 2 | `PermissionMiddleware` | `src/middleware/permission.py` | 工具权限拦截（AdminAgent 纯只读） |
| 3 | `RegexShortcutMiddleware` | `src/middleware/regex_shortcut.py` | L1 正则快捷路由（<5ms 拦截高频指令） |
| 4 | `SkillsMiddleware` | `deepagents.middleware` | 加载 SKILL.md 规范文件 |
| 5 | `RAGMiddleware`（预留） | `src/middleware/rag_context.py` | 根据 `enable_rag` 动态注入 RAG 工具/提示词 |

### 7.2 中间件执行流

```
用户消息（stream.submit）
  │
  ▼
LangGraph 加载 Thread Checkpoint
  │
  ▼
Agent 图入口 → model_call 被中间件链包裹
  │
  ├─ 1. AuthMiddleware.awrap_model_call
  │   ├── 读取 context.user_token
  │   ├── 验证 JWT → 注入 user_id
  │   └── 传递给下一层
  │
  ├─ 2. PermissionMiddleware.awrap_model_call
  │   ├── 读取 context.agent_type
  │   ├── admin → 过滤写工具
  │   └── 传递给下一层
  │
  ├─ 3. RegexShortcutMiddleware.awrap_model_call
  │   ├── 检查最后一条 human message
  │   ├── 匹配正则 → 直接调用工具，返回 AIMessage（跳过 LLM）
  │   └── 不匹配 → 传递给下一层
  │
  ├─ 4. SkillsMiddleware.awrap_model_call
  │   ├── 读取 SKILL.md 规范
  │   ├── 追加到 system_message
  │   └── 传递给下一层
  │
  └─ 5. LLM 调用（qwen-turbo）
      ├── 接收 messages + tools + system_prompt
      ├── 自主选择工具调用（L3 兜底）
      └── 返回 AIMessage（可能含 tool_calls）
  │
  ▼
如有 tool_calls → 执行工具（可能触发 interrupt）→ 回到 Agent 图入口
如无 tool_calls → 图结束 → 返回最终消息
```

### 7.3 Context Schema 机制

`context_schema=Context` 定义了 Agent 运行时的不可变上下文，通过 LangGraph SDK 的 `context` 参数传入：

```python
@dataclass
class Context:
    """Agent 运行时上下文"""
    agent_type: str = "customer"     # "customer" or "admin"
    user_id: str = ""                # 当前用户 ID
    user_token: str = ""             # JWT Token
    enable_rag: bool = False         # RAG 开关
```

**传递链**：

```
前端 stream.submit(
    {messages: [...]},                           # input
    {config: {...}, context: {                   # context
        agent_type: "customer",
        user_token: "eyJhbGci...",
        enable_rag: false
    }}
)
  → LangGraph Runtime 创建 Context(agent_type="customer", user_token="...", ...)
  → 中间件通过 request.runtime.context 读取
  → 工具内可通过 context 获取 user_id（AuthMiddleware 注入）
```

**与 `state_schema` 的区别**：

| | context_schema | state_schema |
|---|---|---|
| 生命周期 | 单次 run，不跨调用持久化 | 每次 node 更新，可 checkpoint |
| 典型用途 | 配置/开关/运行标识（agent_type, user_token） | 对话消息、工具结果、业务数据 |
| 外部注入 | `stream.submit({}, context=Context(...))` | `stream.submit({messages: [...]})` |

---

## 8. API 设计

### 8.1 LangGraph SDK 端点（前端通信）

前端通过 `@langchain/langgraph-sdk` 的 `Client` + `useStream`（Vue 中使用 `Client` + 手动 SSE）与后端通信，不使用传统 REST API。

**SDK 内部实际调用的后端端点**：

| 前端操作 | SDK 方法 | HTTP 端点 | 方法 | 说明 |
|---------|---------|----------|------|------|
| 获取助手列表 | `client.assistants.search()` | `/assistants/search` | `POST` | 返回 customer_agent / admin_agent |
| 获取单个助手 | `client.assistants.get(id)` | `/assistants/{id}` | `GET` | 返回 assistant 配置 |
| 创建线程 | `client.threads.create()` | `/threads` | `POST` | 创建新对话线程 |
| 获取线程列表 | `client.threads.search()` | `/threads/search` | `POST` | 分页返回线程列表 |
| 获取线程状态 | `client.threads.getState(id)` | `/threads/{id}/state` | `GET` | 返回 messages/todos/files |
| 删除线程 | `client.threads.delete(id)` | `/threads/{id}` | `DELETE` | 删除线程+Checkpoint |
| **发送消息（流式）** | `stream.submit(input, opts)` | `/threads/{id}/runs/stream` | `POST` | SSE 流式返回 |
| 停止流式 | `stream.stop()` | （断开 SSE） | — | 中断当前流 |
| **恢复中断** | `stream.submit(null, {command:{resume}})` | `/threads/{id}/runs/stream` | `POST` | 传入确认值，Agent 恢复 |
| 标记结束 | `stream.submit(null, {command:{goto:"__end__"}})` | `/threads/{id}/runs/stream` | `POST` | 强制结束 |

### 8.2 submit() 请求体结构

> **注意**：LangGraph 0.6.0+ 禁止同时传递 `configurable` 和 `context`，统一使用 `context` 传递认证信息。`user_token` 仅放在 `context` 中，后端工具通过 `config.runtime.context.user_token` 获取。

```json
{
  "input": {
    "messages": [
      {
        "id": "uuid",
        "type": "human",
        "content": "查看秒杀活动"
      }
    ]
  },
  "config": {
    "recursion_limit": 100
  },
  "context": {
    "agent_type": "customer",
    "user_token": "eyJhbGciOiJSUzI1NiJ9...",
    "enable_rag": false
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `input.messages` | `Message[]` | 用户消息列表 |
| `config.recursion_limit` | `number` | 图执行最大步数（默认 100） |
| `context.agent_type` | `string` | `"customer"` 或 `"admin"` |
| `context.user_token` | `string` | JWT Token（双 Token 体系） |
| `context.enable_rag` | `boolean` | RAG 开关 |
| `command.resume` | `any` | 中断恢复时传入的确认值 |
| `command.goto` | `"__end__"` | 强制跳转到图结束 |

> **Token 传递链**（context-only 模式）：前端 `context.user_token` → LangGraph Runtime 创建 `Context(user_token=...)` → `AuthMiddleware` 从 `request.runtime.context` 读取 → 工具内通过 `config.runtime.context.user_token` 获取（`extract_token_from_config` 三层 fallback 的路径 3）。

### 8.3 自定义路由

通过 `LANGGRAPH_HTTP` 环境变量挂载自定义 FastAPI 路由：

```python
# src/api/batch_report.py
from fastapi import FastAPI

app = FastAPI()

@app.post("/api/v1/batch-report")
async def batch_report(request: dict):
    """批量运营报告（内部调用 AdminAgent）"""
    # 通过 LangGraph 通用端点调用 admin_agent
    payload = {
        "assistant_id": "admin_agent",
        "input": {"messages": [{"type": "human", "content": "运营日报"}]},
    }
    async with httpx.AsyncClient() as client:
        resp = await client.post(
            "http://localhost:8090/runs/wait",
            json=payload
        )
        return resp.json()
```

**路由体系**：

```
uvicorn 启动 langgraph_api.server:app (port 8090)
│
├─ 元路由层
│   ├─ /ok              → 健康检查
│   ├─ /docs            → OpenAPI 文档
│   └─ /ui              → LangGraph Studio 可视化
│
├─ 自定义路由层 (LANGGRAPH_HTTP 注入)
│   └─ POST /api/v1/batch-report  → 批量运营报告
│
└─ LangGraph 标准路由层
    ├─ /assistants/*    → 助手管理
    ├─ /threads/*       → 线程管理
    └─ /runs/*          → 图执行
```

---

## 9. 项目结构

```
hmall-agent/
├── start_server.py                    # 服务启动入口（uvicorn + langgraph_api）
├── graph.json                         # LangGraph 图注册配置（customer_agent / admin_agent）
├── pyproject.toml                     # uv 项目配置 + 依赖声明
├── .env.example                       # 环境变量模板
│
├── src/
│   ├── agents/
│   │   ├── customer/                  # 客服 Agent
│   │   │   ├── agent.py               #   Agent 定义 (create_agent + Context + middleware)
│   │   │   ├── prompts.py             #   系统提示词 (SYSTEM_PROMPT)
│   │   │   ├── tools.py               #   工具注册 (get_all_tools, 18 个 @tool)
│   │   │   └── regex_rules.py         #   L1 正则路由规则 (REGEX_RULES)
│   │   │
│   │   └── admin/                     # 管理 Agent
│   │       ├── agent.py               #   Agent 定义
│   │       ├── prompts.py             #   系统提示词
│   │       ├── tools.py               #   工具注册 (10 个 @tool + generate_daily_report)
│   │       └── regex_rules.py         #   L1 正则路由规则
│   │
│   ├── api/
│   │   └── batch_report.py            # POST /api/v1/batch-report 自定义路由
│   │
│   ├── middleware/
│   │   ├── auth.py                    # AuthMiddleware：双 JWT 认证
│   │   ├── permission.py              # PermissionMiddleware：工具权限拦截
│   │   ├── regex_shortcut.py          # RegexShortcutMiddleware：L1 正则快捷路由
│   │   └── rag_context.py             # RAGMiddleware：RAG 动态控制（预留）
│   │
│   ├── mcp_servers/
│   │   └── rag_server.py              # RAG MCP Server (FastMCP，预留)
│   │
│   ├── core/
│   │   ├── config.py                  # Pydantic Settings (环境变量集中管理)
│   │   ├── llms.py                    # LLM 实例工厂 (通义千问 qwen-turbo)
│   │   └── redis_checkpoint.py        # Redis Checkpoint 后端配置
│   │
│   ├── tools/
│   │   ├── customer_api.py            # C 端 18 个 API 代理工具 (@tool 实现)
│   │   ├── admin_api.py               # 管理端 10 个 API 代理工具 (@tool 实现)
│   │   └── formatters.py              # 格式化函数（秒杀列表/订单/购物车等）
│   │
│   ├── gateway/
│   │   ├── http_client.py             # 公共 HTTP 客户端（httpx → Gateway :8080）
│   │   └── auth.py                    # JWT 验证（双 keystore）
│   │
│   └── workspace/                     # Agent 工作空间（Skills 文件）
│       ├── customer/skills/
│       │   ├── shopping-guide/SKILL.md
│       │   ├── seckill-order/SKILL.md
│       │   ├── cart-management/SKILL.md
│       │   ├── order-management/SKILL.md
│       │   └── address-management/SKILL.md
│       └── admin/skills/
│           ├── daily-report/SKILL.md
│           ├── data-query/SKILL.md
│           └── rag-query/SKILL.md
│
├── knowledge_base/                    # RAG 知识库文档（预留）
│   └── admin_knowledge/
│       ├── seckill_strategy.md
│       ├── inventory_management.md
│       ├── order_analysis.md
│       ├── user_segmentation.md
│       └── data_interpretation.md
│
├── tests/                             # 自动化测试
├── .env.example
└── README.md
```

### 9.1 关键文件说明

| 文件 | 职责 | 对应 v1.0 |
|------|------|-----------|
| `start_server.py` | 启动 LangGraph Server | `app/main.py` |
| `graph.json` | Agent 注册配置 | 无（v1.0 无图注册） |
| `src/agents/customer/agent.py` | CustomerAgent 定义 | `agents/customer_agent.py` + `base_agent.py` |
| `src/agents/customer/tools.py` | 18 个 @tool 工具 | `tools/customer_api.py` |
| `src/middleware/regex_shortcut.py` | L1 正则路由中间件 | `agents/intent_router.py` |
| `src/middleware/auth.py` | JWT 认证中间件 | `gateway/auth.py` |
| `src/middleware/permission.py` | 权限拦截中间件 | `gateway/permissions.py` |
| `src/core/redis_checkpoint.py` | Redis Checkpoint | `memory/chat_memory.py` |
| `src/gateway/http_client.py` | HTTP 客户端 | `utils/http_client.py` |

---

## 10. 配置设计

### 10.1 graph.json

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

### 10.2 pyproject.toml

```toml
[project]
name = "hmall-agent"
version = "2.0.0"
description = "hmall 枫叶商城 AI 智能助手（DeepAgent 架构）"
requires-python = ">=3.12"
dependencies = [
    "deepagents>=0.5.9",
    "langchain>=1.2.12",
    "langchain-openai>=0.3.0",
    "langchain-community>=0.3.0",
    "langgraph-cli[inmem]>=0.4.26",
    "langgraph-checkpoint-redis>=1.0.0",
    "langchain-mcp-adapters>=0.2.1",
    "fastmcp>=3.2.3",
    "httpx>=0.27.0",
    "python-dotenv>=1.0.0",
    "pydantic>=2.0.0",
    "redis>=5.0.0",
]

[dependency-groups]
dev = [
    "pytest>=9.0.0",
]
```

### 10.3 环境变量

```ini
# ==================== LLM ====================
DASHSCOPE_API_KEY=your_api_key
LLM_MODEL_NAME=qwen-turbo
LLM_API_BASE=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_TEMPERATURE=0.7
LLM_MAX_TOKENS=2048

# ==================== Redis（Checkpoint 后端） ====================
REDIS_HOST=192.168.100.128
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DB=1                          # db=1 与 hmall 业务数据（db=0）隔离

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

# ==================== RAG（预留） ====================
RAG_BASE_URL=http://localhost:9621
RAG_USERNAME=admin
RAG_PASSWORD=admin123
RAG_SPACE_ID=hmall_space
```

### 10.4 关键配置说明

| 配置 | 说明 |
|------|------|
| `JAVA_GATEWAY_URL` | hmall Gateway 地址，所有 API 调用经此路由 |
| `REDIS_DB=1` | 使用 db=1 与 hmall 业务数据（db=0）隔离，作为 LangGraph Checkpoint 后端 |
| `JWT_VERIFY_LOCAL` | 是否在 Agent 本地验证 JWT（true 时需配置 keystore），false 时依赖 Gateway 验证 |
| `LLM_API_BASE` | 通义千问 OpenAI 兼容接口地址 |

### 10.5 start_server.py

```python
#!/usr/bin/env python3
"""hmall Agent LangGraph Server 启动入口"""

import os
import sys
import json
from pathlib import Path


def setup_environment():
    """配置 LangGraph 运行环境"""
    # 确保必要目录存在
    Path(".langgraph_api/ui/public").mkdir(parents=True, exist_ok=True)

    # 添加 src 到 Python 路径
    src_path = Path(__file__).parent / "src"
    sys.path.insert(0, str(src_path))

    # 读取 graph.json
    config_path = Path(__file__).parent / "graph.json"
    graphs = {}
    if config_path.exists():
        with open(config_path, 'r', encoding='utf-8') as f:
            config = json.load(f)
            graphs = config.get("graphs", {})

    # 设置环境变量
    os.environ.update({
        "DATABASE_URI": ":memory:",
        "REDIS_URI": os.getenv("REDIS_CHECKPOINT_URI", "fake"),
        "MIGRATIONS_PATH": "__inmem",
        "ALLOW_PRIVATE_NETWORK": "true",
        "LANGGRAPH_UI_BUNDLER": "true",
        "LANGGRAPH_RUNTIME_EDITION": "inmem",
        "LANGSMITH_LANGGRAPH_API_VARIANT": "local_dev",
        "LANGGRAPH_ALLOW_BLOCKING": "true",
        "LANGGRAPH_API_URL": f"http://localhost:{os.getenv('AGENT_PORT', '8090')}",
        # Agent 图注册
        "LANGSERVE_GRAPHS": json.dumps(graphs) if graphs else "{}",
        # 自定义路由
        "LANGGRAPH_HTTP": json.dumps({"app": "api.batch_report:app"}),
        "N_JOBS_PER_WORKER": "3",
    })

    # 加载 .env
    env_file = Path(__file__).parent / ".env"
    if env_file.exists():
        from dotenv import load_dotenv
        load_dotenv(env_file)


def main():
    setup_environment()
    
    port = int(os.getenv("AGENT_PORT", "8090"))
    
    print(f"🚀 Starting hmall Agent Server on port {port}")
    print(f"📍 API:      http://localhost:{port}")
    print(f"📚 Docs:     http://localhost:{port}/docs")
    print(f"🎨 Studio:   http://localhost:{port}/ui")
    print(f"💚 Health:   http://localhost:{port}/ok")

    import uvicorn
    uvicorn.run(
        "langgraph_api.server:app",
        host="0.0.0.0",
        port=port,
        reload=False,
    )


if __name__ == "__main__":
    main()
```

---

## 11. 工具调用示例

### 11.1 查看秒杀活动（L1 正则路由）

```
用户: "查看秒杀活动"
  │
  ├─ stream.submit({messages: [{type: "human", content: "查看秒杀活动"}]})
  │
  ├─ LangGraph 加载 Thread Checkpoint → Agent 图入口
  │
  ├─ 中间件链:
  │   ├─ AuthMiddleware → 验证 JWT（无 Token，允许只读）
  │   ├─ PermissionMiddleware → customer，不过滤
  │   ├─ RegexShortcutMiddleware → 匹配 "(?:查看|查询|当前).{0,3}秒杀"
  │   │   ├── 直接调用 get_seckill_activities_api
  │   │   ├── httpx GET http://localhost:8080/seckill/activities
  │   │   ├── 代码格式化输出
  │   │   └── 返回 AIMessage（无 tool_call）→ 跳过 LLM
  │   └─ SkillsMiddleware → 未到达（L1 已短路）
  │
  └─ 图结束 → SSE 返回:
      ⚡ 当前秒杀活动
      ─────────────────────
      📢 618 专场 [进行中]
      🕐 10:00场 (10:00-12:00) [抢购中]
      ├── iPhone 15 | ¥5999 (原价¥6999) | 剩余 45 件
      └── MacBook Air | ¥8999 (原价¥9999) | 剩余 12 件
```

### 11.2 秒杀下单（L2 interrupt + 二次确认）

```
用户: "秒杀iPhone 15"
  │
  ├─ stream.submit({messages: [{type: "human", content: "秒杀iPhone 15"}]})
  │
  ├─ 中间件链:
  │   ├─ AuthMiddleware → 验证 JWT → user_id=1001
  │   ├─ PermissionMiddleware → customer，允许 do_seckill_api
  │   ├─ RegexShortcutMiddleware → 不匹配（需 LLM 理解"iPhone 15"→relationId）
  │   └─ SkillsMiddleware → 加载 seckill-order SKILL.md
  │
  ├─ LLM 调用（L3 兜底）:
  │   ├── LLM 理解意图 → 调用 get_seckill_activities_api 查找 iPhone 15
  │   ├── 找到 relationId=1
  │   └── LLM 调用 do_seckill_api(relation_id=1)
  │
  ├─ 工具内:
  │   ├── 查询商品详情: iPhone 15, ¥5999, 限购1件, 剩余45件
  │   ├── interrupt({type: "confirmation", message: "确认秒杀..."})
  │   └── 图暂停 → Checkpoint 保存到 Redis
  │
  ├─ SSE 返回 interrupt 事件:
  │   {type: "interrupt", value: {message: "确认秒杀以下商品？..."}}
  │
  ├─ 前端 InterruptActions 展示确认卡片
  │
  ├─ 用户: "确认"
  │   stream.submit(null, {command: {resume: "确认"}})
  │
  ├─ Agent 从 Checkpoint 恢复:
  │   ├── approval = "确认"
  │   ├── httpx POST http://localhost:8080/seckill/order/1?quantity=1
  │   └── 返回结果
  │
  └─ 图结束 → SSE 返回:
      ✅ 秒杀成功！订单号: 123456，请尽快支付。
```

### 11.3 地址修改（L2 interrupt 多轮）

```
用户: "修改地址1"
  │
  ├─ LLM 调用 update_address_api(address_id=1)
  │
  ├─ interrupt #1 (field_selection):
  │   → "请问要修改地址1的哪个字段？(姓名/手机号/省份/城市/区/详细地址)"
  │   → 图暂停 → Checkpoint 保存
  │
  ├─ 用户: "姓名"
  │   stream.submit(null, {command: {resume: "姓名"}})
  │   → Agent 恢复, field = "姓名"
  │
  ├─ interrupt #2 (value_input):
  │   → "请输入新的姓名"
  │   → 图暂停 → Checkpoint 保存
  │
  ├─ 用户: "张三"
  │   stream.submit(null, {command: {resume: "张三"}})
  │   → Agent 恢复, new_value = "张三"
  │
  ├─ httpx PUT http://localhost:8080/addresses/1
  │
  └─ ✅ 地址1的姓名已修改为张三
```

### 11.4 运营日报（L1 正则 + 多工具编排）

```
用户: "运营日报"
  │
  ├─ RegexShortcutMiddleware 匹配 "(?:运营|生成|帮我做).{0,3}日报"
  │   ├── 直接调用 generate_daily_report
  │   ├── 并发调用 5 个查询工具:
  │   │   ├─ admin_get_order_page_api → 156单/¥89,200
  │   │   ├─ admin_get_seckill_promotion_page_api → 3场进行中
  │   │   ├─ admin_get_seckill_relation_page_api → 12件预警
  │   │   ├─ admin_get_product_page_api → 248件在售
  │   │   └─ admin_get_user_page_api → 总1,230人
  │   └── 格式化日报输出
  │
  └─ 图结束 → SSE 返回日报
```

---

## 12. 前端集成

### 12.1 技术方案

hmall 前端基于 Vue 3 + Element Plus + Vite，通过 `@langchain/langgraph-sdk` 1.x JavaScript 客户端与 LangGraph Server 通信。基于 `Client` 类封装 Vue Composable。

> **SDK 版本**：`@langchain/langgraph-sdk@^1.0.3`（实际安装 1.9.27）。SDK 1.x 的 `client.runs.stream()` 正确转发 `context` 和 `command` 字段，无需 fetch 绕过。SDK 0.0.10 的 `runs.stream()` 会丢弃这两个字段。

### 12.2 前端组件架构

```
前端 (Vue 3 + Element Plus)
│
├── 路由
│   ├── /portal/chat  → ChatPage.vue (portal) → ChatPanel (customer_agent)
│   └── /admin/chat   → ChatPage.vue (admin)  → ChatPanel (admin_agent)
│
├── 导航入口
│   ├── ChatWidget.vue    → C 端浮动按钮 → router-link → /portal/chat
│   └── AdminChat.vue     → 管理端 header 按钮 → router-link → /admin/chat
│
├── 核心组件
│   ├── ChatPanel.vue     → 可复用全页对话组件（props 配置主题/标题/快捷操作/token）
│   ├── MessageBubble.vue → 消息气泡（AI 消息 Markdown 渲染 + 人类消息纯文本）
│   └── InterruptActions.vue → interrupt 确认卡片
│
└── Composable
    └── useLangGraph.ts   → SDK 1.x Client 封装（线程管理 + SSE 流式 + interrupt 恢复）
```

| 位置 | 组件 | 说明 |
|------|------|------|
| C 端独立页面 | `portal/ChatPage.vue` | 全屏对话页，`/portal/chat` 路由，带返回按钮 |
| 管理端独立页面 | `admin/ChatPage.vue` | 嵌入 AdminLayout 的对话页，`/admin/chat` 路由，含快捷操作 |
| C 端浮动入口 | `ChatWidget.vue` | 右下角浮动按钮 → `router-link` 跳转 `/portal/chat` |
| 管理端入口 | `AdminChat.vue` | header "AI助手" 按钮 → `router-link` 跳转 `/admin/chat` |
| 可复用对话面板 | `ChatPanel.vue` | 全页对话组件，通过 props 配置 agent 类型/主题色/标题/快捷操作 |
| 消息气泡 | `MessageBubble.vue` | AI 消息用 `marked` 渲染 Markdown；人类消息纯文本；修复溢出 bug |

### 12.3 Vue Composable 封装

```typescript
// src/composables/useLangGraph.ts
import { Client } from '@langchain/langgraph-sdk'
import { ref, type Ref } from 'vue'

export function useLangGraph(options: UseLangGraphOptions) {
  const client = new Client({ apiUrl: options.apiUrl || 'http://localhost:8090' })

  const messages: Ref<ChatMessage[]> = ref([])
  const isLoading = ref(false)
  const interruptData: Ref<InterruptData | null> = ref(null)

  // 发送消息（流式）—— SDK 1.x 正确转发 context 字段
  async function sendMessage(text: string, context: AgentContext) {
    // 创建或复用 Thread
    // 添加用户消息到 UI
    const streamResponse = client.runs.stream(threadId.value, assistantId, {
      input: { messages: [{ type: 'human', content: text }] },
      config: { recursion_limit: 100 },  // 不含 configurable（LangGraph 0.6.0+ 禁止）
      context,                            // user_token 统一走 context
      streamMode: ['messages', 'values'],
    })
    await _processStream(streamResponse)
  }

  // 处理 SSE 流
  async function _processStream(streamResponse: AsyncGenerator<any>) {
    for await (const chunk of streamResponse) {
      // messages/partial + messages/complete → 创建/更新 AI 消息
      if (chunk.event === 'messages/partial' || chunk.event === 'messages/complete') {
        for (const msg of chunk.data || []) {
          if (msg.type !== 'ai') continue
          const content = _extractContent(msg)
          if (!content) continue
          // 通过响应式数组索引更新，确保 Vue 检测到变化
          const idx = messages.value.findIndex(m => m.id === msg.id)
          if (idx !== -1) {
            messages.value[idx].content = content  // 经过 Proxy，Vue 检测到
          } else {
            messages.value.push({ id: msg.id, type: 'ai', content, timestamp: Date.now() })
          }
        }
      }
      // values → 检测 __interrupt__
      if (chunk.event === 'values' && chunk.data?.__interrupt__) {
        interruptData.value = parseInterrupt(chunk.data.__interrupt__)
      }
      // error → 在 UI 中展示错误消息
      if (chunk.event === 'error') {
        messages.value.push({ type: 'ai', content: `❌ ${chunk.data?.message}`, ... })
        break
      }
    }
  }

  // 恢复中断 —— SDK 1.x 正确转发 command + context 字段
  async function resume(value: string) {
    client.runs.stream(threadId.value, assistantId, {
      command: { resume: value },
      config: { recursion_limit: 100 },
      context: _currentContext,
      streamMode: ['messages', 'values'],
    })
  }

  return { messages, isLoading, interruptData, threadId, error, sendMessage, resume, rejectInterrupt, clearHistory }
}
```

**SSE 事件处理**：

| 事件 | 处理逻辑 |
|------|---------|
| `messages/partial` | AI 消息增量更新（流式 token 追加） |
| `messages/complete` | AI 消息最终完整内容（非流式或流式结束） |
| `values` | 检测 `__interrupt__`，解析为 `InterruptData` |
| `error` | 在 UI 中展示错误消息气泡，中断流 |
| `messages/metadata` | 忽略（消息元数据，不影响显示） |

**Vue 响应式注意事项**：增量更新必须通过 `messages.value[idx].content = content` 修改（经过 Vue Proxy），而非直接修改局部变量 `aiMessage.content`（绕过 Proxy，Vue 检测不到变化）。

### 12.4 MessageBubble.vue（Markdown 渲染 + 溢出修复）

| 特性 | 说明 |
|------|------|
| AI 消息渲染 | 使用 `marked` 库渲染 Markdown（GFM + breaks） |
| 人类消息 | 纯文本（`whitespace-pre-wrap`） |
| 溢出修复 | `min-w-0 overflow-hidden overflow-wrap:anywhere`（flex 子项 + 长文本） |
| Markdown 样式 | 标题/列表/表格/代码块/引用/链接/粗体/斜体/删除线/分割线 |
| 流式效果 | 最后一条 AI 消息 + `isLoading` 时显示三点跳动动画 |
| 消息动画 | `messageAppear` 过渡（0.3s） |

---

## 13. 部署设计

### 13.1 独立部署（推荐）

Agent 作为独立 Python 服务部署，与 Java 微服务解耦：

```
hmall-agent (Python :8090, LangGraph Server)
  ↓ HTTP (httpx)
hm-gateway (Java :8080)
  ↓ Feign / 路由
各微服务 (Java :8081-8090)
```

### 13.2 启动顺序

```bash
# 1. 启动基础设施: MySQL / Redis / Nacos / RabbitMQ

# 2. 启动 Java 微服务: item → user → cart → trade → pay → search → admin → gateway

# 3. 启动 Agent 服务
cd hmall-agent
uv sync                              # 安装依赖
uv run python start_server.py        # LangGraph Server (:8090)

# 4. 启动前端
cd hmall-frontend
npm install
npm run dev                          # Vite dev server
```

### 13.3 服务端口总览

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
| `lightrag-server`（预留） | 9621 | RAG API 后端 |
| `rag_server.py`（预留） | 8008 | RAG MCP 桥接 |

---

## 14. 与 nova-mall-agent 的差异适配

| 差异点 | nova-mall-agent | hmall Agent 适配方案 |
|--------|----------------|---------------------|
| **Agent 框架** | LangChain Core（自定义 Agent 调度） | DeepAgents (`create_agent`) + LangGraph |
| **Web 框架** | FastAPI + WebSocket | LangGraph Server (uvicorn + langgraph_api) |
| **对话记忆** | 自定义 Redis ChatMemory | LangGraph Thread + Redis Checkpoint |
| **状态机** | 自定义 Redis Key 存储 | LangGraph `interrupt()` 原生支持 |
| API 调用 | 直连 Portal(8085) + Admin(8080) | 统一经 Gateway(8080) 路由 |
| 认证 | 单 JWT 共享密钥 | 双 JWT（C 端 RSA + 管理端独立 keystore），通过 `context_schema` 传递 |
| 优惠券工具 | 5 个（领取/查询/历史） | 移除（hmall 无优惠券系统） |
| 售后工具 | 4 个（查询/申请/状态/退款） | 移除（hmall 无售后系统） |
| 秒杀工具 | 2 个（查活动/加购） | 3 个（查活动/查详情/秒杀下单），适配三层防超卖架构，下单用 interrupt 确认 |
| 管理后台 | 独立 Java Admin | admin-service 微服务 + RBAC，Agent 调 `/admin/**` 代理接口 |
| 商品搜索 | 数据库 LIKE | ES 全文检索（`/search`），支持品牌/分类/价格多维筛选 |
| 地址状态机 | 6 字段 | 6 字段（name/phone/province/city/region/detailAddress），用 interrupt 多轮收集 |
| 订单取消 | `POST /order/cancel` | `POST /orders/batch/close`（批量关闭接口），interrupt 二次确认 |
| ID 精度 | JS Number | hmall 已有 Long→String 序列化保护，Agent 无需特殊处理 |
| **正则路由** | 自定义 IntentRouter 调度层 | `RegexShortcutMiddleware` 中间件拦截 model_call |
| **前端通信** | WebSocket 自定义协议 | LangGraph SDK（SSE 流式 + interrupt 恢复） |
| **Skills** | 无 | `SkillsMiddleware` + SKILL.md 规范文件 |

---

## 15. 后续优化方向

| 方向 | 说明 | 优先级 |
|------|------|--------|
| RAG 知识库 | 运营/商品/秒杀策略知识库，AdminAgent 专业知识问答，通过 MCP 桥接 LightRAG | P1 |
| 商品推荐 | 基于用户浏览/购买历史的个性化推荐 | P2 |
| 优惠券系统 | hmall 实现优惠券后，新增 5 个工具 | P2 |
| 售后系统 | hmall 实现售后后，新增 4 个工具 | P2 |
| 多模态 | 支持图片输入（商品图片识别、截图报错），动态模型切换中间件 | P3 |
| LangSmith 可观测性 | 接入 LangSmith 追踪，监控 LLM 调用链和工具命中率 | P3 |
| 正则规则动态加载 | 从 Nacos 配置中心加载正则路由规则，无需重启 | P3 |
| 对话分析 | 对话日志分析，挖掘用户高频问题和痛点，优化 L1 正则规则 | P3 |
