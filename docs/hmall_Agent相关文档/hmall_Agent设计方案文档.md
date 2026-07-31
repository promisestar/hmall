# hmall Agent 设计方案文档

> 整合 hmall 枫叶商城 AI 智能助手的全部设计方案，涵盖系统架构、个性化推荐、用户画像持久化、RAG 知识库集成四大模块。

---

## 目录

- [第一部分：hmall Agent 系统设计](#第一部分hmall-agent-系统设计) — 整体架构 / 技术选型 / Agent 定义 / 中间件 / 交互流程 / 部署方案
- [第二部分：个性化推荐设计](#第二部分个性化推荐设计) — 推荐管线 / 偏好分析 / 召回排序 / API 设计 / Redis 画像 / 分步实施
- [第三部分：用户画像与主动通知设计](#第三部分用户画像与主动通知设计) — 画像存储策略 / 增量聚合 / 对话记忆 / 后端集成 / 主动通知方案
- [第四部分：RAG 知识库集成](#第四部分rag-知识库集成) — LightRAG + MCP 桥接 / Agent 集成 / 运维指南

---

# 第一部分：hmall Agent 系统设计
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
│  │  ├── POST /api/v1/batch-report（自定义路由：批量运营报告）    │   │
│  │  └── GET  /api/v1/llm/health（自定义路由：LLM 连通性检查）     │   │
│  └────────────────────────────────────────────────────────────┘   │
│                          │                                         │
│  ┌───────────────────────▼────────────────────────────────────┐   │
│  │  中间件层（DeepAgent Middleware Chain）                      │   │
│  │  ├── AuthMiddleware（双 JWT 认证：C端 / 管理端）              │   │
│  │  ├── PermissionMiddleware（工具权限拦截：AdminAgent 纯只读）  │   │
│  │  ├── RegexShortcutMiddleware（L1 正则快捷路由：<5ms）        │   │
│  │  ├── SkillsMiddleware（SKILL.md 规范加载）                   │   │
│  │  └── RAGMiddleware（RAG 动态工具注入：enable_rag=true 时生效）│   │
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
| **MCP 协议** | FastMCP + `langchain-mcp-adapters` | RAG 桥接（LightRAG → MCP Server → Agent） |
| **RAG 引擎** | LightRAG（git submodule） | 知识图谱 + 向量检索，REST API 调用 |
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
    enable_rag: bool = False         # RAG 开关（前端控制，True 时注入 RAG 工具）


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

#### 7.2.1 中间件触发机制

**触发时机**：所有中间件钩在同一个入口点 `awrap_model_call(request, handler)`，由 LangGraph Agent 执行循环统一调度。当图执行到 `model_call` 节点时（即 Agent 准备调用 LLM 推理），中间件链按 `middleware` 列表的顺序依次执行。

```
Agent 图执行 → model_call 节点 → 中间件链（洋葱模型）→ LLM
```

**洋葱模型**：中间件像洋葱层一样包裹 LLM 调用。每一层通过调用 `handler(request)` 把控制权传给内层，最内层是 LLM 本身。如果一个中间件**不调用** `handler(request)` 而直接返回，就会"短路"——内层中间件和 LLM 都不会被调用。

```
        ┌────────────────────────────────┐
        │  1. AuthMiddleware             │ — 认证 token，注入 user_id
        │  ┌──────────────────────────┐  │
        │  │ 2. PermissionMiddleware  │  │ — admin 过滤写工具（9→1）
        │  │ ┌──────────────────────┐ │  │
        │  │ │ 3. RegexShortcutMid  │ │  │ — 命中正则 → 直接 return（跳过 LLM）
        │  │ │  ┌────────────────┐  │ │  │
        │  │ │  │ 4. SkillsMid   │  │ │  │ — 追加 SKILL.md 到 prompt
        │  │ │  │  ┌──────────┐  │  │ │  │
        │  │ │  │  │ LLM 推理  │  │  │ │  │ — 仅当前 4 层都 handler() 后到达
        │  │ │  │  └──────────┘  │  │  │ │
        │  │ │  └────────────────┘  │  │  │
        │  │ └──────────────────────┘  │  │
        │  └──────────────────────────┘  │
        └────────────────────────────────┘
```

**核心代码**：每个中间件通过重写 `AgentMiddleware.awrap_model_call` 控制行为：

```python
class XxxMiddleware(AgentMiddleware):
    async def awrap_model_call(self, request: ModelRequest, handler):
        # 1. 前置处理：修改 request（认证/过滤工具/匹配正则）
        modified_request = self._do_preprocessing(request)

        # 2. 传给下一层中间件（或 LLM）
        return await handler(modified_request)

        # 或者短路：不调 handler，直接返回 AIMessage（Regex 命中时）
        # return AIMessage(content="...结果...")
```

**各层详细行为**：

| 层序 | 中间件 | 触发条件 | 是否可能跳过 LLM | 行为 |
|:---:|--------|----------|:---:|------|
| 1 | **AuthMiddleware** | 始终执行 | ❌ | 从 `request.runtime.context` 读取 `user_token`；验证 JWT（`JWT_VERIFY_LOCAL=false` 时透传）；注入 `user_id` 到 context |
| 2 | **PermissionMiddleware** | 始终执行 | ❌ | 读取 `context.agent_type`；若为 `admin`，从工具列表中移除 9 个写操作工具（`add_to_cart_api`/`do_seckill_api` 等）；若为 `customer`，透传全部工具 |
| 3 | **RegexShortcutMiddleware** | 始终执行 | ✅ | 检查最后一条 human 消息内容；匹配 L1 正则规则（如 `查看秒杀`/`运营日报`）→ 直接 `ainvoke` 对应工具 → 返回 `AIMessage`；**不匹配时** → `handler(request)` 传给下一层 |
| 4 | **SkillsMiddleware** | 仅 Regex 未命中时到达 | ❌ | 从虚拟文件系统读取 SKILL.md（如 `shopping-guide`/`daily-report`）；追加到 `system_message` |
| — | **LLM (qwen-turbo)** | 仅前 4 层都 `handler()` 后到达 | — | 接收 messages + tools + system_prompt；自主选择工具调用（L3 兜底）；返回 AIMessage（可能含 `tool_calls`） |

**关键区别**：
- Auth / Permission / Skills：**始终透传**，只做修改不拦截，LLM 一定会被调用
- RegexShortcut：**可能短路**，正则命中时直接返回结果，LLM 不会被调用（省去 ~2s 推理时间 + API 成本）
- 执行顺序由 `middleware` 列表位置决定，顺序不能随意调换（如 Regex 必须在 Skills 之前，否则 Skills 加载工作白做）

**触发时机与 Agent 图执行的关系**：

```
Agent 图执行循环（每次迭代）:
  │
  ├─ 1. 进入 model_call 节点
  │     └── 中间件链 awrap_model_call(request, handler)
  │          ├── Auth → 认证
  │          ├── Permission → 过滤工具
  │          ├── Regex → 匹配？（Y: 返回结果 / N: handler(request)）
  │          ├── Skills → 追加规范
  │          └── LLM → 推理 → 返回 AIMessage（可能含 tool_calls）
  │
  ├─ 2. 如果有 tool_calls:
  │     ├── 执行工具（httpx → Gateway → 微服务）
  │     ├── 工具内可能触发 interrupt() → 图暂停 → 等待用户回复
  │     └── 结果作为 ToolMessage 加入 messages → 回到步骤 1
  │
  └─ 3. 如果无 tool_calls:
        └── 图结束 → SSE 返回最终 AIMessage 给前端
```

> **总结**：中间件的触发时机不由各自独立决定，而是被 LangGraph Agent 执行循环中的 `model_call` 节点统一触发。`middleware` 列表的顺序就是执行顺序，`handler(request)` 是否被调用决定了请求是否继续流向内层（或 LLM）。

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
│   │   ├── batch_report.py            # POST /api/v1/batch-report 自定义路由
│   │   └── health.py                  # GET /api/v1/llm/health LLM 连通性检查
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

# ==================== RAG（LightRAG + MCP） ====================
RAG_BASE_URL=http://localhost:9621       # LightRAG Server 地址
RAG_USERNAME=admin                       # LightRAG 登录用户名
RAG_PASSWORD=admin123                    # LightRAG 登录密码
RAG_SPACE_ID=hmall_space                 # LightRAG 工作空间隔离标识
RAG_API_KEY=                             # LightRAG API Key（可选，优先于账号密码）
RAG_AUTH_ENABLED=true                    # 是否启用 LightRAG 认证
RAG_MCP_PORT=8008                        # RAG MCP Server 监听端口
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
| ~~RAG 知识库~~ | ~~运营/商品/秒杀策略知识库，通过 MCP 桥接 LightRAG~~ **已实现（见第 16 章）** | ~~P1~~ |
| 商品推荐 | 基于用户浏览/购买历史的个性化推荐 | P2 |
| 优惠券系统 | hmall 实现优惠券后，新增 5 个工具 | P2 |
| 售后系统 | hmall 实现售后后，新增 4 个工具 | P2 |
| 多模态 | 支持图片输入（商品图片识别、截图报错），动态模型切换中间件 | P3 |
| LangSmith 可观测性 | 接入 LangSmith 追踪，监控 LLM 调用链和工具命中率 | P3 |
| 正则规则动态加载 | 从 Nacos 配置中心加载正则路由规则，无需重启 | P3 |
| 对话分析 | 对话日志分析，挖掘用户高频问题和痛点，优化 L1 正则规则 | P3 |

---

## 16. RAG 知识库集成（LightRAG + MCP）

> 版本：v2.2 补充  
> 日期：2026-07-20  
> LightRAG 作为 git submodule 集成，通过 MCP 协议桥接到 Agent

### 16.1 架构概览

```
前端 ChatPanel.vue
  │ 用户点击「知识库」开关 → ragEnabled → sessionStorage 持久化
  │ sendMessage(text, {agent_type, user_token, enable_rag})
  ▼
LangGraph Agent（context 透传 enable_rag）
  │
  ├─ RAGMiddleware.awrap_model_call()
  │    │ context.enable_rag = true
  │    │ → rag_loader.get_rag_tools()  [模块级缓存]
  │    │ → request.override(tools=[...业务工具, ...RAG 工具])
  │    │ context.enable_rag = false → 直接放行
  │    ▼
  │  LLM 选择 RAG 工具 → MCP Protocol → MCP Server (:8008)
  │                                       → LightRAGClient → LightRAG API (:9621)
  │
  └─ 业务工具（Gateway → Java 微服务）
```

**三层架构**：
1. **LightRAG（:9621）**：知识图谱 + 向量检索引擎，作为 git submodule 独立部署
2. **MCP Server（:8008）**：FastMCP HTTP 服务，封装 LightRAG REST API 为 3 个 MCP 工具
3. **RAGMiddleware**：Agent 中间件，根据 `enable_rag` 动态注入 MCP 工具

### 16.2 MCP Server 设计（`src/mcp_servers/rag_server.py`）

独立的 FastMCP HTTP 进程，通过 `langchain-mcp-adapters` 的 `MultiServerMCPClient` 连接。

**LightRAGClient**：封装 LightRAG REST API，特性：
- OAuth2 登录获取 JWT（POST /login），token 缓存 + 401 自动重登录
- 支持可选 API Key 认证（`RAG_API_KEY`，优先于账号密码）
- `httpx.AsyncClient` 单例复用连接

**3 个 MCP 工具**：

| 工具 | LightRAG 端点 | 用途 |
|------|--------------|------|
| `rag_query(query, mode)` | POST /query | 语义检索，返回答案 + 参考来源 |
| `rag_query_data(query, mode)` | POST /query/data | 结构化查询，返回 entities/relationships/chunks |
| `rag_graph_search(query)` | POST /query/data | 图谱搜索，聚合实体关系 |

查询模式（mode）：`mix`（默认，图谱+向量融合）、`hybrid`、`local`、`global`、`naive`、`bypass`

### 16.3 RAGMiddleware 动态工具注入（`src/middleware/rag_context.py`）

```python
class RAGMiddleware(AgentMiddleware):
    async def awrap_model_call(self, request, handler):
        # 1. 检查 context.enable_rag
        # 2. enable_rag=true → rag_loader.get_rag_tools() 获取 MCP 工具
        # 3. 追加到 request.tools（避免重复注入同名工具）
        # 4. MCP Server 不可达 → log warning，不阻塞（降级为无 RAG）
```

**工具加载器**（`src/tools/rag_loader.py`）：
- `MultiServerMCPClient` 连接 `http://localhost:8008/mcp`（streamable_http 传输）
- 模块级缓存工具列表，首次加载后复用，避免每次 model_call 都连接
- `is_available()` 健康检查，`refresh()` 强制刷新

**降级策略**：MCP Server 不可达时只 log warning 不阻塞，Agent 仍可使用业务工具。与现有降级模式一致（如推荐接口失败时降级提示）。

### 16.4 前端 RAG 开关（`ChatPanel.vue`）

头部右侧新增「知识库」开关按钮：
- 书本图标 + 状态指示灯（绿色=开启，灰色=关闭）
- 状态持久化到 `sessionStorage`（key: `rag_enabled`），刷新不丢失
- `handleSend` / `handleQuickAction` 调用 `sendMessage` 时传入 `enable_rag: ragEnabled.value`
- 利用 `useLangGraph.ts` 已预留的 `AgentContext.enable_rag` 字段，无需改 composable

### 16.5 Skills 规范

- `src/workspace/admin/skills/rag-query/SKILL.md`：管理端 RAG 技能（运营策略、库存管理指南等）
- `src/workspace/customer/skills/rag-query/SKILL.md`：C 端 RAG 技能（退换货政策、支付方式等）

两个 Agent 的 SkillsMiddleware sources 均配置 `/skills/rag-query/`。

### 16.6 启动顺序

```bash
# 1. 启动 LightRAG Server（端口 9621）
cd LightRAG && lightrag-server

# 2. 启动 RAG MCP Server（端口 8008）
cd hmall-agent && uv run python start_rag_server.py

# 3. 启动 Agent Server（端口 8090）
uv run python start_server.py

# 4. 启动前端
cd hmall-frontend && npm run dev
```

### 16.7 配置项

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `RAG_BASE_URL` | `http://localhost:9621` | LightRAG Server 地址 |
| `RAG_USERNAME` | `admin` | LightRAG 登录用户名 |
| `RAG_PASSWORD` | `admin123` | LightRAG 登录密码 |
| `RAG_API_KEY` | （空） | LightRAG API Key（可选，优先于账号密码） |
| `RAG_AUTH_ENABLED` | `true` | 是否启用 LightRAG 认证 |
| `RAG_MCP_PORT` | `8008` | RAG MCP Server 监听端口 |

### 16.8 知识库管理

知识库文档由运营人员通过 LightRAG WebUI（`http://localhost:9621/webui`）上传维护：
- 支持 PDF / DOCX / TXT / Markdown 等格式
- LightRAG 自动构建知识图谱 + 向量索引
- Agent 不负责文档管理，只负责检索

详细部署和使用说明见 `hmall-agent-rag-integration.md`。

---

## 17. LLM 健康检查（前端动态在线状态）

> 版本：v2.3 补充
> 日期：2026-07-20
> 前端"Agent 在线"状态从写死改为根据 LLM API 远程调用连通性动态显示

### 17.1 问题背景

此前 `ChatPanel.vue` 头部的 `{{ isLoading ? '正在回复...' : '在线' }}` 和
`AdminLayout.vue` 的 `<el-tag type="success">在线</el-tag>` 均为写死值，
无论 LLM API（DashScope）是否可达都显示"在线"，无法反映真实服务状态。

### 17.2 架构设计

```
前端组件（ChatPanel / AdminLayout）
  │ onMounted → useLlmHealth.start()
  │ 每 30s 轮询 GET /api/v1/llm/health
  ▼
LangGraph Server (:8090)
  │ 自定义路由层（LANGGRAPH_HTTP 注入）
  │ src/api/health.py → _ping_llm()
  ▼
DashScope API (dashscope.aliyuncs.com)
  │ POST /compatible-mode/v1/chat/completions
  │ {model: "qwen-turbo", messages:[{role:"user",content:"ping"}], max_tokens:1}
  ▼
返回 {llm_reachable, latency_ms, detail}
  → 前端 llmStatus = 'online' | 'offline' | 'checking'
```

### 17.3 后端实现（`src/api/health.py`）

**端点**：`GET /api/v1/llm/health`

**探测方式**：向 DashScope OpenAI 兼容接口发送 `max_tokens=1` 的最小 chat completions 请求，
验证三项：
1. API Key 有效性（`DASHSCOPE_API_KEY`）
2. LLM 服务可达性（网络连通）
3. 模型可用性（`LLM_MODEL_NAME` 配置正确）

**缓存策略**：模块级缓存 10 秒（`_CACHE_TTL = 10`），避免高频轮询消耗 token。
缓存命中时返回 `cached: true`。

**响应结构**：
```json
{
  "status": "ok",
  "llm_reachable": true,
  "latency_ms": 342,
  "model": "qwen-turbo",
  "detail": null,
  "cached": false,
  "checked_at": 1721472000.0
}
```

**异常兜底**：超时（8s）、连接错误、HTTP 错误码（401/429/5xx）、未配置 API Key 均返回
`llm_reachable: false` + `detail` 错误说明，不抛异常。

**路由挂载**：在 `src/api/batch_report.py` 中 `app.include_router(health_router)`，
随 `LANGGRAPH_HTTP` 一起注入 LangGraph Server。

### 17.4 前端实现

#### Composable（`src/composables/useLlmHealth.ts`）

| 特性 | 说明 |
|------|------|
| 轮询间隔 | 30 秒（`intervalMs` 可配置） |
| 状态枚举 | `online` / `offline` / `checking` |
| 派生属性 | `statusText`（在线/离线/检测中）、`statusType`（success/danger/info） |
| 生命周期 | `onMounted` 自动开始，`onUnmounted` 自动停止 |
| 手动刷新 | `refresh()` 方法 |
| API URL | 取 `VITE_AGENT_URL` 环境变量，默认 `http://localhost:8090` |

#### ChatPanel.vue 状态展示

```vue
<p class="text-[12px] opacity-80 mt-0.5" :class="agentStatusClass">
  {{ agentStatusText }}
</p>
```

- `isLoading=true` → "正在回复..."（不受 LLM 状态影响）
- `llmStatus=online` → "在线"（白色）
- `llmStatus=offline` → "离线"（`text-red-200`）
- `llmStatus=checking` → "检测中"（`text-yellow-200`）

#### AdminLayout.vue 状态标签

```vue
<el-tag size="small" :type="llmStatusType">{{ llmStatusText }}</el-tag>
```

- online → `<el-tag type="success">在线</el-tag>`
- offline → `<el-tag type="danger">离线</el-tag>`
- checking → `<el-tag type="info">检测中</el-tag>`

### 17.5 设计考量

| 决策 | 理由 |
|------|------|
| 用 `max_tokens=1` 的 chat 请求而非 `/models` | OpenAI 兼容接口的 `/models` 在某些提供商不返回认证错误，chat 请求最稳 |
| 10 秒模块级缓存 | 前端 30 秒轮询 + 可能多组件同时检查，缓存避免重复消耗 token |
| 前端 30 秒轮询间隔 | 平衡实时性与请求量；LLM 状态不会频繁变化 |
| `onUnmounted` 自动停止 | 避免组件卸载后继续轮询造成内存泄漏 |
| 端点不可达也视为离线 | `fetch` 异常时 `llmStatus='offline'`，覆盖 Agent Server 本身宕机的情况 |



---

# 第二部分：个性化推荐设计

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

### 6.4 Redis 画像结构（Phase 2 已实现）

> **Phase 2 实现说明**：原设计使用 `up:` 前缀 + ZSet 结构，实际实现改为 `profile:` 前缀 + Hash/List 结构（详见 [hmall-agent-profile-and-notification-design.md](./hmall-agent-profile-and-notification-design.md) §3.1）。画像由后端 `paySuccessListener`（purchase）和 `CartServiceImpl`（cart）共同写入，Agent 侧仅 `analyze_user_preferences` miss 后回写画像，使用 HINCRBY 原子增量更新。

| Key | 结构 | 说明 | 实现状态 |
|-----|------|------|---------|
| `profile:{userId}:events` | List（LPUSH+LTRIM 50，TTL 7d） | 行为流（加购/下单/收货事件） | ✅ Phase 2 已实现 |
| `profile:{userId}:categories` | Hash（field=category, value=累计得分，TTL 30d） | 用户类目偏好 | ✅ Phase 2 已实现 |
| `profile:{userId}:brands` | Hash（field=brand, value=累计得分，TTL 30d） | 用户品牌偏好 | ✅ Phase 2 已实现 |
| `profile:{userId}:prices` | List（LPUSH+LTRIM 20，TTL 30d） | 最近购买价格 | ✅ Phase 2 已实现 |
| `profile:{userId}:stats` | Hash（purchase_count/cart_count/last_update，TTL 30d） | 统计信息 | ✅ Phase 2 已实现 |
| `cf:{itemId}` | Hash（field=itemId, value=共现次数） | Item-CF 共现矩阵 | ⏸ Phase 3 |
| `rec:{userId}:{scene}` | String（JSON 商品列表） | 推荐结果缓存，TTL 5-10min | ⏸ Phase 3 |

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

| 步骤 | 改动点 | 工作量 | 价值 | 阶段 | 状态 |
|------|--------|--------|------|------|------|
| 1 | Agent 新增 `get_recommendations_api` 工具 | 小 | 核心 | Phase 1 | ✅ 已完成 |
| 2 | Agent 新增 `analyze_user_preferences` 工具 | 中 | 差异化 | Phase 1 | ✅ 已完成 |
| 3 | Agent 新增 `format_recommendations` + `format_preferences` | 小 | 输出规范 | Phase 1 | ✅ 已完成 |
| 4 | Agent 新增 `personalized-recommendation` Skill | 小 | 工作流 | Phase 1 | ✅ 已完成 |
| 5 | Agent 修改 Prompt + 正则路由 + 工具注册 | 小 | 触发 | Phase 1 | ✅ 已完成 |
| 6 | 后端 `GET /recommend` 接口（Phase 1: SQL+ES） | 中 | 数据供给 | Phase 1 | ✅ 已完成 |
| 7 | 行为采集写入画像（后端 `paySuccessListener` 写 purchase + `CartServiceImpl` 写 cart） | 中 | 数据采集 | Phase 2 | ✅ 已完成（实现方式调整：后端直接写 Redis 而非 POST /behaviors + MQ，覆盖 Agent + 前端 UI 全路径） |
| 8 | 前端 `ProductDetail.vue` 浏览埋点 | 小 | 数据积累 | Phase 2 | ⏸ 待实施 |
| 9 | Redis 画像计算（`ProfileStore` HINCRBY 增量更新 + `RecommendServiceImpl` 共享读取） | 中 | 画像精度 | Phase 2 | ✅ 已完成 |
| 10 | 后端 Item-CF 共现矩阵（定时任务） | 中 | 召回质量 | Phase 2 | ⏸ 待实施 |

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
| `hmall/trade-service/src/main/java/com/hmall/trade/Listener/paySuccessListener.java` | **修改**：支付成功后 `StringRedisTemplate` HINCRBY 写入 purchase 画像 |
| `hmall/cart-service/src/main/java/com/hmall/cart/service/impl/CartServiceImpl.java` | **修改**：加购成功后 `StringRedisTemplate` HINCRBY 写入 cart 画像（覆盖 Agent + 前端 UI 全路径） |

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


---

# 第三部分：用户画像与主动通知设计

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


---

# 第四部分：RAG 知识库集成

> 版本：v1.0  
> 日期：2026-07-20  
> LightRAG + MCP 桥接集成方案

---

## 1. 概述

hmall Agent 通过 MCP（Model Context Protocol）协议集成 LightRAG 知识检索引擎，为 CustomerAgent 和 AdminAgent 提供基于知识库的问答能力。

**核心能力**：
- AdminAgent：回答运营策略、库存管理指南、订单分析方法等专业知识问题
- CustomerAgent：回答退换货政策、支付方式、配送说明等商城常见问题

**技术栈**：
- LightRAG（git submodule）：知识图谱 + 向量检索引擎
- FastMCP：MCP Server 框架
- langchain-mcp-adapters：MCP Client，连接 MCP Server 加载工具
- httpx：异步 HTTP 客户端，调用 LightRAG REST API

---

## 2. 架构设计

### 2.1 整体架构

```
前端 ChatPanel.vue
  │ 用户点击「知识库」开关 → enable_rag 字段
  ▼
LangGraph Agent (:8090)
  │
  ├─ RAGMiddleware（检查 context.enable_rag）
  │    │ enable_rag=true
  │    ▼
  │  rag_loader.py → MultiServerMCPClient
  │    │ MCP Protocol (HTTP)
  │    ▼
  │  RAG MCP Server (:8008) — rag_server.py
  │    │ httpx
  │    ▼
  │  LightRAG Server (:9621)
  │    ├─ POST /query       → rag_query 工具
  │    ├─ POST /query/data  → rag_query_data 工具
  │    └─ POST /query/data  → rag_graph_search 工具
  │
  └─ 业务工具（Gateway → Java 微服务）
```

### 2.2 三层架构

| 层 | 组件 | 端口 | 职责 |
|----|------|------|------|
| 知识引擎 | LightRAG Server | 9621 | 知识图谱构建 + 向量检索 + LLM 生成 |
| MCP 桥接 | RAG MCP Server | 8008 | 封装 LightRAG API 为 MCP 工具 |
| Agent | hmall Agent | 8090 | RAGMiddleware 动态注入 RAG 工具 |

### 2.3 数据流

1. 用户在前端点击「知识库」开关，`ragEnabled` 状态持久化到 sessionStorage
2. 用户发送消息，`sendMessage` 传入 `enable_rag: ragEnabled.value`
3. LangGraph Agent 接收 context，RAGMiddleware 检查 `enable_rag`
4. `enable_rag=true` 时，`rag_loader` 连接 MCP Server 加载 RAG 工具
5. RAG 工具追加到 Agent 的工具列表
6. LLM 根据问题选择 RAG 工具，通过 MCP 协议调用 MCP Server
7. MCP Server 的 LightRAGClient 调用 LightRAG REST API
8. 检索结果返回给 LLM，LLM 整合后回复用户

---

## 3. 部署指南

### 3.1 前置条件

- Python >= 3.12
- uv（Python 包管理工具）
- LightRAG 已作为 git submodule 拉取（`d:/Code/hmall/LightRAG`）

### 3.2 启动 LightRAG Server

```bash
# 1. 配置 LightRAG 环境
cd LightRAG
cp env.example .env
# 编辑 .env，配置 LLM 和 Embedding 模型：
#   LLM_BINDING=openai
#   LLM_MODEL=qwen-turbo
#   LLM_BINDING_HOST=https://dashscope.aliyuncs.com/compatible-mode/v1
#   LLM_BINDING_API_KEY=your_dashscope_key
#   EMBEDDING_BINDING=ollama  # 或 openai
#   EMBEDDING_MODEL=bge-m3:latest
#   EMBEDDING_DIM=1024

# 2. 安装依赖（推荐 uv）
uv sync --extra api

# 3. 启动 LightRAG Server（端口 9621）
lightrag-server
```

验证：访问 `http://localhost:9621/health` 返回 200。

### 3.3 启动 RAG MCP Server

```bash
cd hmall-agent

# 确保 .env 中 RAG 配置正确
# RAG_BASE_URL=http://localhost:9621
# RAG_USERNAME=admin
# RAG_PASSWORD=admin123
# RAG_MCP_PORT=8008

# 启动 MCP Server（端口 8008）
uv run python start_rag_server.py
```

验证：查看日志输出 `🚀 Starting RAG MCP Server on port 8008`。

### 3.4 启动 Agent Server

```bash
cd hmall-agent
uv run python start_server.py
```

### 3.5 启动前端

```bash
cd hmall-frontend
npm run dev
```

### 3.6 完整启动顺序

```bash
# 1. 启动 LightRAG Server（端口 9621）
cd LightRAG && lightrag-server

# 2. 启动 RAG MCP Server（端口 8008）
cd hmall-agent && uv run python start_rag_server.py

# 3. 启动 Agent Server（端口 8090）
uv run python start_server.py

# 4. 启动前端
cd hmall-frontend && npm run dev
```

> LightRAG Server 和 RAG MCP Server 是可选组件。未启动时 Agent 仍可正常工作，只是不提供 RAG 检索能力。

---

## 4. 使用指南

### 4.1 前端 RAG 开关

在 ChatPanel.vue 头部右侧有「知识库」按钮：
- **灰色指示灯**：RAG 关闭（默认），Agent 只使用业务工具
- **绿色指示灯**：RAG 开启，Agent 可使用 RAG 工具检索知识库

点击按钮切换开关状态，状态持久化到 sessionStorage（刷新不丢失，关闭浏览器标签页后失效）。

### 4.2 知识库管理

通过 LightRAG WebUI 管理知识库文档：

1. 访问 `http://localhost:9621/webui`
2. 登录（账号密码见 LightRAG 的 `.env` 中 `AUTH_ACCOUNTS`）
3. 上传文档（支持 PDF / DOCX / TXT / Markdown）
4. LightRAG 自动构建知识图谱 + 向量索引
5. 索引完成后即可在 Agent 对话中使用

### 4.3 查询模式

RAG 工具支持多种查询模式（`mode` 参数）：

| 模式 | 说明 | 适用场景 |
|------|------|---------|
| `mix`（默认） | 融合知识图谱 + 向量检索 | 大多数问题（效果最佳） |
| `hybrid` | 混合 local + global 检索 | 需要兼顾细节和全局 |
| `local` | 聚焦具体实体及其关系 | 查询特定实体的信息 |
| `global` | 提供更宽泛的上下文 | 查询宏观趋势/关系 |
| `naive` | 简单向量相似度搜索 | 快速原型验证 |
| `bypass` | 绕过 RAG 直接用 LLM | 对比测试 |

---

## 5. 配置说明

### 5.1 hmall-agent .env 配置

```bash
# RAG（LightRAG + MCP）
RAG_BASE_URL=http://localhost:9621       # LightRAG Server 地址
RAG_USERNAME=admin                       # LightRAG 登录用户名
RAG_PASSWORD=admin123                    # LightRAG 登录密码
RAG_SPACE_ID=hmall_space                 # LightRAG 工作空间隔离标识
RAG_API_KEY=                             # LightRAG API Key（可选，优先于账号密码）
RAG_AUTH_ENABLED=true                    # 是否启用 LightRAG 认证
RAG_MCP_PORT=8008                        # RAG MCP Server 监听端口
```

### 5.2 LightRAG .env 配置（关键项）

```bash
# Server
PORT=9621
AUTH_ACCOUNTS='admin:admin123'           # 与 hmall-agent 的 RAG_USERNAME/RAG_PASSWORD 一致
TOKEN_SECRET=your-token-secret

# LLM
LLM_BINDING=openai
LLM_MODEL=qwen-turbo
LLM_BINDING_HOST=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_BINDING_API_KEY=your_dashscope_key

# Embedding（注意：embedding 模型确定后不可更改，需重新索引）
EMBEDDING_BINDING=ollama
EMBEDDING_MODEL=bge-m3:latest
EMBEDDING_DIM=1024

# Storage（开发环境用 JSON，生产环境建议 PostgreSQL）
LIGHTRAG_KV_STORAGE=JsonKVStorage
LIGHTRAG_DOC_STATUS_STORAGE=JsonDocStatusStorage
LIGHTRAG_GRAPH_STORAGE=NetworkXStorage
LIGHTRAG_VECTOR_STORAGE=NanoVectorDBStorage
```

### 5.3 认证方式

LightRAG 支持两种认证方式（二选一）：

1. **账号密码（JWT）**：配置 `RAG_USERNAME` / `RAG_PASSWORD`，LightRAGClient 调用 `/login` 获取 JWT token
2. **API Key**：配置 `RAG_API_KEY`，请求头带 `X-API-Key`

> 如果 LightRAG 未配置认证（`AUTH_ACCOUNTS` 为空），LightRAGClient 会收到 guest token，仍可正常工作。

---

## 6. MCP 工具说明

### 6.1 rag_query

```python
rag_query(query: str, mode: str = "mix") -> str
```

语义检索知识库，返回基于知识库生成的答案和参考来源。

**适用场景**：运营策略咨询、商品知识问答、退换货政策咨询

**返回格式**：
```
知识库生成的答案内容...

**参考来源：**
1. /documents/seckill_strategy.md
2. /documents/inventory_guide.md
```

### 6.2 rag_query_data

```python
rag_query_data(query: str, mode: str = "mix") -> str
```

结构化数据查询，返回知识图谱中的实体、关系和文本块（不生成最终答案）。

**适用场景**：查看知识库中具体实体定义、实体间关系、原始文本片段

**返回格式**：
```
**检索结果**（3 实体 / 2 关系 / 5 文本块）

**相关实体：**
1. [策略] 秒杀活动：限时限量折扣促销...
2. [商品] 库存：商品可用数量...

**实体关系：**
1. 秒杀活动 → 库存（权重 0.8）：秒杀活动消耗库存...

**相关文本块：**
1. [seckill_strategy.md] 秒杀活动策划指南...
```

### 6.3 rag_graph_search

```python
rag_graph_search(query: str) -> str
```

知识图谱搜索，基于查询提取相关实体和关系。

**适用场景**：探索知识库中实体间的关联关系

---

## 7. 故障排查

### 7.1 RAG 工具不可用

**现象**：前端开启「知识库」开关后，Agent 回复"知识库检索暂不可用"或未使用 RAG 工具。

**排查步骤**：

1. **检查 LightRAG Server**：
   ```bash
   curl http://localhost:9621/health
   ```
   预期返回 200。如果失败，启动 LightRAG Server。

2. **检查 RAG MCP Server**：
   ```bash
   # 查看 MCP Server 日志是否有错误
   # 确认端口 8008 已监听
   ```
   如果未启动，运行 `uv run python start_rag_server.py`。

3. **检查 Agent 日志**：
   - 搜索 `RAG MCP 工具加载失败` — MCP Server 不可达
   - 搜索 `RAG 已启用但无可用工具` — 工具加载失败
   - 搜索 `RAG 工具注入成功` — 正常工作

4. **检查 .env 配置**：
   - `RAG_BASE_URL` 指向正确的 LightRAG 地址
   - `RAG_USERNAME` / `RAG_PASSWORD` 与 LightRAG 的 `AUTH_ACCOUNTS` 一致

### 7.2 知识库无检索结果

**现象**：RAG 工具可用但返回空结果或"未找到相关信息"。

**排查步骤**：

1. **检查知识库是否已导入文档**：访问 LightRAG WebUI 查看文档列表
2. **检查文档索引状态**：文档状态应为 `processed`，非 `pending` 或 `failed`
3. **尝试直接查询 LightRAG**：
   ```bash
   curl -X POST http://localhost:9621/query \
     -H "Content-Type: application/json" \
     -d '{"query": "测试查询", "mode": "mix"}'
   ```

### 7.3 LightRAG 登录失败

**现象**：MCP Server 日志报 `LightRAG 登录失败`。

**排查步骤**：

1. 检查 LightRAG `.env` 中 `AUTH_ACCOUNTS` 配置格式：`admin:admin123`（明文密码）
2. 确认 hmall-agent `.env` 中 `RAG_USERNAME` / `RAG_PASSWORD` 与之一致
3. 如果 LightRAG 未配置认证，确保 `AUTH_ACCOUNTS` 为空或注释掉

### 7.4 降级行为

当 RAG MCP Server 不可达时：
- RAGMiddleware 只 log warning，不阻塞 Agent
- Agent 正常使用业务工具，用户无感知
- 用户开启「知识库」开关但 RAG 不可用时，Agent 回复降级提示

---

## 8. 文件清单

### 新增文件
| 文件 | 说明 |
|------|------|
| `hmall-agent/src/tools/rag_loader.py` | MCP 工具加载器（MultiServerMCPClient 封装 + 缓存） |
| `hmall-agent/start_rag_server.py` | RAG MCP Server 独立启动入口 |
| `hmall-agent/src/workspace/customer/skills/rag-query/SKILL.md` | C 端 RAG 技能规范 |
| `docs/Agent功能相关文档/hmall-agent-rag-integration.md` | 本文档 |

### 修改文件
| 文件 | 说明 |
|------|------|
| `hmall-agent/src/mcp_servers/rag_server.py` | 从预留桩替换为完整 FastMCP Server 实现 |
| `hmall-agent/src/middleware/rag_context.py` | 从预留桩替换为完整 RAGMiddleware 实现 |
| `hmall-agent/src/core/config.py` | 新增 RAG_API_KEY / RAG_AUTH_ENABLED / RAG_MCP_PORT 配置 |
| `hmall-agent/src/agents/admin/agent.py` | 中间件链加入 RAGMiddleware |
| `hmall-agent/src/agents/customer/agent.py` | 中间件链加入 RAGMiddleware + Skills sources 加 rag-query |
| `hmall-agent/src/agents/admin/prompts.py` | system prompt 补充 RAG 能力说明 |
| `hmall-agent/src/agents/customer/prompts.py` | system prompt 补充 RAG 能力说明 |
| `hmall-agent/src/workspace/admin/skills/rag-query/SKILL.md` | 移除预留标记，描述实际工具用法 |
| `hmall-agent/.env.example` | 补充 RAG 配置项 |
| `hmall-frontend/src/components/chat/ChatPanel.vue` | 头部新增 RAG 开关按钮 + sendMessage 传入 enable_rag |


