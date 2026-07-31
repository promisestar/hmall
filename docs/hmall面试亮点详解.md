# hmall 工程设计文档

> 本文档从"实现一个生产级电商平台 + AI Agent 助手"的工程视角，梳理 hmall（枫叶商城）在架构设计上的核心亮点。每个亮点包含**设计动机**、**实现方案**和**对比分析**。

---

## 目录

1. [整体架构分层全景](#1-整体架构分层全景)
2. [Agent 三级路由：正则→中断→LLM](#2-agent-三级路由正则中断llm)
3. [双 JWT 认证体系](#3-双-jwt-认证体系)
4. [秒杀核心：Redis + RabbitMQ 异步削峰](#4-秒杀核心redis--rabbitmq-异步削峰)
5. [Agent 中间件链设计](#5-agent-中间件链设计)
6. [RAG 知识库：LightRAG + MCP 三层桥接](#6-rag-知识库lightrag--mcp-三层桥接)
7. [Gateway 全局认证 + Lua 滑动窗口限流](#7-gateway-全局认证--lua-滑动窗口限流)
8. [个性化推荐：ES 召回 + LLM 理由生成](#8-个性化推荐es-召回--llm-理由生成)
9. [本地消息表 + 定时重发：分布式最终一致性](#9-本地消息表--定时重发分布式最终一致性)
10. [Seata 全局事务：跨服务强一致性](#10-seata-全局事务跨服务强一致性)
11. [Long → String 序列化：防 JS 精度丢失](#11-long--string-序列化防-js-精度丢失)
12. [Feign 自动传递用户上下文](#12-feign-自动传递用户上下文)
13. [Sentinel 降级熔断](#13-sentinel-降级熔断)
14. [Nacos 动态路由热更新](#14-nacos-动态路由热更新)
15. [RabbitMQ 延迟消息：订单超时取消](#15-rabbitmq-延迟消息订单超时取消)
16. [RBAC 动态权限：三层权限控制体系](#16-rbac-动态权限三层权限控制体系)
17. [级联管理：DB 事务 + Redis 缓存同步清除](#17-级联管理db-事务--redis-缓存同步清除)
18. [服务启动顺序与端口分配](#18-服务启动顺序与端口分配)
19. [项目文件规模与统计](#19-项目文件规模与统计)
20. [面试专题：项目最难部分与解决方案](#20-面试专题项目最难部分与解决方案)
21. [Agent 用户画像构建与记忆机制](#21-agent-用户画像构建与记忆机制)

---

## 1. 整体架构分层全景

### 设计动机

hmall 是一个同时面向 **C 端消费者**（商品浏览、购物车、秒杀、下单）和 **B 端运营者**（商品管理、订单管理、AI 日报）的电商平台，并在其中嵌入了 AI Agent 智能助手。需要一套能同时支撑"高并发交易"和"AI 对话推理"的分层架构。

### 实现方案

```
                        用户（浏览器）
                             │
              ┌──────────────┴──────────────┐
              │          前端 SPA            │
              │  Vue 3 + TypeScript +        │
              │  Element Plus + Tailwind     │
              │  ├─ /portal/*  C 端 (14页)  │
              │  └─ /admin/*   管理端 (12页) │
              └──────┬──────────┬───────────┘
                     │ HTTP     │ SSE (stream)
              ┌──────▼──────┐ ┌─▼──────────────────┐
              │  Java 后端   │ │   Agent 服务        │
              │  (8080)      │ │   (8090)           │
              │              │ │                    │
              │ ┌──────────┐ │ │ ┌────────────────┐ │
              │ │ Gateway  │ │ │ │ LangGraph       │ │
              │ │ jwt+限流 │ │ │ │ Server          │ │
              │ └────┬─────┘ │ │ │  ├─ Customer    │ │
              │      │       │ │ │  │   Agent      │ │
              │ ┌────▼─────┐ │ │ │  │   (20 tools) │ │
              │ │ BFF      │ │ │ │  └─ Admin       │ │
              │ │ Service  │ │ │ │     Agent       │ │
              │ └────┬─────┘ │ │ │     (11 tools)  │ │
              │      │       │ │ └───────┬─────────┘ │
              │ ┌────▼─────┐ │ │         │           │
              │ │ Micro-   │ │ │ ┌───────▼─────────┐ │
              │ │ services │ │ │ │ RAG MCP Server  │ │
              │ │ (9个)    │ │ │ │ (:8008)        │ │
              │ └────┬─────┘ │ │ └───────┬─────────┘ │
              │      │       │ │         │           │
              └──────┼───────┘ └─────────┼───────────┘
                     │                   │
         ┌───────────┼───────────────────┼───────────┐
         │    基础服务层                               │
         │  ┌────────┐  ┌────────┐  ┌──────────────┐ │
         │  │ MySQL  │  │ Redis  │  │ LightRAG      │ │
         │  │ (hmall)│  │ (缓存   │  │ (:9621)       │ │
         │  │ 9个库) │  │ +限流) │  │ RAG 知识库    │ │
         │  └────────┘  └────────┘  └──────────────┘ │
         └────────────────────────────────────────────┘
```

**Java 微服务矩阵**：

| 服务 | 端口 | 数据库 | 职责 |
|------|:---:|------|------|
| **hm-gateway** | 8080 | — | API 网关：JWT 认证、滑动窗口限流（Redis ZSET + Lua） |
| **hm-service** | 8080 | `hmall` | 聚合 BFF 服务，直接面向 C 端请求 |
| **item-service** | 8081 | `hm_item` | 商品微服务：CRUD、库存、个性化推荐 |
| **cart-service** | 8082 | `hm_cart` | 购物车微服务 |
| **pay-service** | 8083 | `hm_pay` | 支付微服务 |
| **user-service** | 8084 | `hm_user` | 用户微服务：登录、余额、地址 |
| **trade-service** | 8085 | `hm_trade` | 交易微服务 + 秒杀核心引擎 |
| **search-service** | 8089 | `hm_es` | 搜索微服务：Elasticsearch 全文检索 |
| **admin-service** | 8091 | `hm_admin` | 管理后台微服务：RBAC 权限体系 |

**Agent 服务矩阵**：

| 服务 | 端口 | 技术栈 | 职责 |
|------|:---:|------|------|
| **Agent Server** | 8090 | LangGraph Server + DeepAgents | 双 Agent 运行时（Customer + Admin） |
| **RAG MCP Server** | 8008 | FastMCP | LightRAG API → MCP 工具桥接 |
| **LightRAG Server** | 9621 | LightRAG (HKU) | 知识图谱 + 向量检索引擎 |
| **LLM API** | 云端 | DashScope | 通义千问 qwen-turbo |

### 对比分析

| 维度 | 传统单体电商 | hmall 分层架构 |
|------|------------|---------------|
| C 端 + B 端 | 同一应用内区分 | Gateway 统一入口 → 路由分流 |
| AI 能力 | 无或外部 API 直调 | Agent 服务独立部署，通过 LangGraph SDK 流式通信 |
| RAG 知识库 | 无 | LightRAG submodule + MCP 桥接，开箱即用 |
| 前端架构 | 单一页面体系 | Vue 3 单页应用，/portal 和 /admin 双路由体系 |
| 秒杀 | 数据库行级锁 | Redis 预减库存 + RabbitMQ 异步下单 + Lua 原子扣减 |

### 面试展示要点

> "这个项目最核心的架构特点在于三端分离：Java 微服务负责高并发交易、Vue 负责双端 UI、Agent 服务通过 LangGraph 独立部署。Gateway 做统一入口的认证和限流，微服务只需专注业务。Agent 通过 MCP 协议桥接 LightRAG 知识库，前端开关一键控制——任意一层都可以独立替换而不影响其他层。"

---

## 2. Agent 三级路由：正则→中断→LLM

### 设计动机

Agent 的 ReAct 循环中，LLM 每轮调用都有延迟（通常 1-3 秒）和成本。如果用户问了简单问题（如"运营日报"、"猜你喜欢"），也要走完整的 LLM 推理 → 工具选择 → 结果格式化的链路，既不经济也不快速。

另外，某些操作（取消订单、秒杀下单、清空购物车）具有破坏性，如果 LLM 直接在工具调用中执行，用户没有二次确认的机会。需要一个"在执行前暂停"的机制。

### 实现方案

hmall 的 CustomerAgent 和 AdminAgent 都实现了三级路由：

```
用户消息
  │
  ├─ L1 正则快捷路由（RegexShortcutMiddleware）
  │   │ 命中关键词 → 直接调用工具 + 格式化返回（不经过 LLM）
  │   │
  │   ├─ 运营日报（AdminAgent）："生成日报""运营日报""今日数据" → generate_daily_report
  │   └─ 猜你喜欢（CustomerAgent）："猜你喜欢""有什么推荐""帮我选" → get_recommendations_api
  │
  ├─ L2 中断确认（Interrupt）
  │   │ 危险操作 → 触发 LangGraph interrupt → 前端弹窗 → 等待用户确认
  │   │
  │   ├─ 取消订单（CustomerAgent）：cancel_order_api → interrupt
  │   ├─ 删除商品（CustomerAgent）：delete_cart_item_api → interrupt
  │   ├─ 清空购物车（CustomerAgent）：clear_cart_api → interrupt
  │   └─ 秒杀下单（CustomerAgent）：do_seckill_api → interrupt
  │
  └─ L3 LLM 推理（DeepAgent）
       │ 复杂多工具编排、自由对话 → qwen-turbo ReAct 循环
       │ 经过 AuthMiddleware → PermissionMiddleware → RAGMiddleware → SkillsMiddleware
```

**L1 正则路由关键代码**（`src/middleware/regex_shortcut.py`）：

```python
# AdminAgent 正则规则
REGEX_RULES = [
    (r"(运营日报|生成日报|今日数据|报告|汇总|日报)", generate_daily_report),
]

# CustomerAgent 正则规则
REGEX_RULES = [
    (r"(猜你喜欢|有什么推荐|帮我选|推荐一下|随便看看)", get_recommendations_api),
]
```

**L2 中断确认机制**：工具函数内部调用 `interrupt("确认取消订单？")` 触发 LangGraph 的中断机制。前端 `InterruptActions.vue` 组件渲染确认/取消按钮，用户确认后 `stream.submit(null, {command: {resume: value}})` 恢复执行。

### 设计亮点

**1. L1 正则免除 LLM 调用**：运营日报是 AdminAgent 最高频操作之一，走正则路由无需 LLM 推理（延迟从 2-3 秒降至 <1 秒，零 token 消耗）。

**2. L2 中断保护关键操作**：破坏性操作（取消、删除、清空）在执行前由前端二次确认，LLM 无法绕过。

**3. 中间件链顺序保证优先级**：`RegexShortcutMiddleware` 排在中间件链前面，L1 命中即返回，不经过后续 LLM 调用。

### 对比分析

| 方案 | 简单问题延迟 | 破坏性操作保护 | Token 消耗 |
|------|:---:|:---:|:---:|
| 全部走 LLM ReAct | 2-3s | 依赖 LLM 判断（不可靠） | 高 |
| 全部规则路由 | <1s | ✅ | 零 |
| **三级路由（hmall）** | **<1s（L1 命中）/ 2-3s（L3）** | **✅ 强制执行** | **按需** |

### 面试展示要点

> "我们没有让 LLM 处理所有请求——最高频的'运营日报'走正则路由，延迟从 2 秒降到毫秒级，零 token 消耗。L2 中断机制在工具执行前强制暂停，用户在弹窗里确认后 Agent 才从 LangGraph checkpoint 恢复——LLM 无法绕过。这三级路由的优先级由中间件链顺序保证，正则在前、中断在中间、LLM 在最后。"

---

## 3. 双 JWT 认证体系

### 设计动机

hmall 同时面向两种用户：C 端消费者（通过 portal 访问）和管理端运营者（通过 admin 访问）。两者的认证体系不同：

- C 端用户通过 `POST /api/users/login` 登录，JWT 中包含 `userId`、`role="user"`
- 管理端用户通过 `POST /api/admin/login` 登录，JWT 中包含 `adminId`、`role="admin"`

Gateway 需要区分两种 Token 并做不同的权限校验。同时，Agent 服务在调用 Java 微服务时也需要透传用户的 JWT Token 以保持权限上下文。

### 实现方案

```
前端 (ChatPanel.vue)
  │ sessionStorage.getItem(tokenKey) ──→ context.user_token
  ▼
Agent Server (:8090)
  │ AuthMiddleware
  │ │ 从 request.runtime.context 读取 user_token
  │ │ 解析 JWT → 判断 role (user / admin)
  │ │ 注入到 config.runtime.context.user_token
  │ ▼
  │ PermissionMiddleware
  │ │ role=user  → 允许 C 端工具（购物车、订单、秒杀）
  │ │ role=admin → 仅允许只读工具（拦截所有写操作）
  │ ▼
Gateway (:8080)
  │ AuthGlobalFilter
  │ │ 解析 Authorization Header
  │ │ 判断 path 前缀：/api/users/** → C 端 JWT，/api/admin/** → 管理端 JWT
  │ │ 校验失败 → 401
  │ ▼
Java 微服务
  从 Gateway 透传的 Header 中获取 userId / adminId
```

**AuthMiddleware 关键逻辑**（`src/middleware/auth.py`）：

```python
class AuthMiddleware(AgentMiddleware):
    async def awrap_model_call(self, request, handler):
        # 从 runtime context 读取前端传入的 token
        context = request.runtime.context
        user_token = context.user_token

        if not user_token:
            # 未登录 → 放行（Agent 提示用户登录）
            return await handler(request)

        # 解析 JWT（不验证签名，仅提取 payload）
        claims = _decode_jwt(user_token)
        role = claims.get("role", "user")

        # 注入到 runtime context 供后续中间件和工具使用
        new_context = context.override(user_token=user_token, role=role)
        new_request = request.override(runtime=new_context.runtime)
        return await handler(new_request)
```

**PermissionMiddleware**（`src/middleware/permission.py`）：

```python
# AdminAgent：纯只读，拦截所有写操作
_ADMIN_DENIED_TOOLS = {
    "add_to_cart_api", "update_cart_quantity_api", "delete_cart_item_api",
    "clear_cart_api", "cancel_order_api", "confirm_receive_api",
    "do_seckill_api", "add_address_api", "update_address_api",
}

class PermissionMiddleware(AgentMiddleware):
    def modify_request(self, request):
        context = request.runtime.context
        role = getattr(context, "role", "user")

        if role == "admin":
            # 过滤掉所有写操作工具
            allowed_tools = [t for t in request.tools
                           if t.name not in _ADMIN_DENIED_TOOLS]
            return request.override(tools=allowed_tools)

        return request
```

### 设计亮点

**1. Token 透传链**：前端 → Agent → Gateway → 微服务，Token 在整条链路中完整透传，Agent 调用微服务 API 时自带所属用户身份。

**2. Admin 只读保护**：`PermissionMiddleware` 在工具注册层面拦截——AdminAgent 的工具列表中直接不包含写操作工具，LLM 即使"想"调用也无法执行。

**3. 三层 fallback 提取**：工具内部通过 `extract_token_from_config` 函数实现三层 token 获取：`configurable.user_token` → `runtime.context.user_token` → 抛出异常，兼容不同的 LangGraph 调用路径。

### 对比分析

| 方案 | C/B 端隔离 | 权限控制层级 | 工具层保护 |
|------|:---:|:---:|:---:|
| 单一 JWT + 前端判断 | ❌ 弱隔离 | 仅前端 | ❌ |
| 双 JWT + Gateway 校验 | ✅ | Gateway | ❌（LLM 可绕过） |
| **双 JWT + Gateway + Agent 中间件** | **✅** | **Gateway + Agent 双重** | **✅ 工具层过滤** |

### 面试展示要点

> "认证不只是 Gateway 的事——Gateway 做完 JWT 校验后，Agent 的 AuthMiddleware 从 context 里提取 role 字段，PermissionMiddleware 在工具注册层直接过滤掉写操作工具。AdminAgent 的工具列表里根本没有 do_seckill_api、cancel_order_api——LLM 想调用也没法调用。三层防护：Gateway 拦、Agent 过滤、工具列表裁剪。"

---

## 4. 秒杀核心：Redis + RabbitMQ 异步削峰

### 设计动机

秒杀是电商平台最典型的高并发场景——短时间内大量用户同时抢购少量商品。如果直接操作数据库（扣减库存 + 创建订单），数据库连接池会被迅速耗尽，导致系统崩溃。需要一个"前置库存预减 + 异步下单"的架构来削峰填谷。

### 实现方案

```
用户秒杀请求
  │
  ▼
trade-service (SeckillController)
  │
  ├─① Redis Lua 脚本原子操作：
  │   │ 检查库存 (GET seckill:stock:{id})
  │   │ 检查重复购买 (SISMEMBER seckill:order:{id} {userId})
  │   │ 扣减库存 (DECR)
  │   │ 标记已购买 (SADD)
  │   │ 返回结果
  │
  ├─② 库存扣减成功 → 发送 RabbitMQ 消息到 seckill.order.queue
  │
  └─③ 返回给用户："秒杀下单成功，订单生成中..."
        │
        ▼
  RabbitMQ Consumer（异步消费）
        │
        ├─ 扣减数据库库存
        ├─ 创建订单记录
        └─ 清除 Redis 库存标记
```

**Redis Lua 脚本**（核心）：

```lua
-- seckill.lua
local stockKey = KEYS[1]      -- seckill:stock:{id}
local orderKey = KEYS[2]      -- seckill:order:{id}
local userId = ARGV[1]

-- 1. 检查库存
local stock = tonumber(redis.call('GET', stockKey) or '0')
if stock <= 0 then
    return -1  -- 库存不足
end

-- 2. 检查重复购买
local exists = redis.call('SISMEMBER', orderKey, userId)
if exists == 1 then
    return -2  -- 重复购买
end

-- 3. 扣减库存 + 标记已购买
redis.call('DECR', stockKey)
redis.call('SADD', orderKey, userId)

return 1  -- 成功
```

**Java 调用链**（`trade-service`）：

```java
// SeckillController → SeckillService → Redis Lua 执行
Long result = redisTemplate.execute(
    seckillScript,
    Arrays.asList("seckill:stock:" + id, "seckill:order:" + id),
    userId.toString()
);

if (result == 1) {
    // 发送异步下单消息
    rabbitTemplate.convertAndSend("seckill.exchange", "seckill.order", orderDTO);
}
```

### 设计亮点

**1. Lua 原子性**：库存检查 + 扣减 + 去重三个操作在一个 Lua 脚本中原子执行，杜绝超卖。

**2. 异步削峰**：用户请求只到 Redis 就返回，后续的数据库操作由 RabbitMQ 消费者异步处理。即使 1 万 QPS 秒杀请求，数据库承受的实际写入压力被 RabbitMQ 平滑分散到秒级。

**3. Redis 预热**：秒杀开始前，通过管理后台将库存数量加载到 Redis（`SET seckill:stock:{id} {count}`），商品信息也缓存到 Redis，秒杀期间不查数据库。

**4. 防重复购买**：用 Redis SET 记录已购买用户 ID，同一用户同一秒杀商品只能成功一次。

### 对比分析

| 方案 | QPS 上限 | 超卖风险 | 数据库压力 |
|------|:---:|:---:|:---:|
| 数据库行级锁 | ~500 | 低 | 极高 |
| Redis 分布式锁 | ~2000 | 中 | 高 |
| **Redis Lua + RabbitMQ（hmall）** | **~10000** | **零（原子操作）** | **低（异步削峰）** |

### 面试展示要点

> "秒杀的核心矛盾是数据库扛不住瞬间 QPS。我们用 Redis Lua 脚本把库存检查、扣减、去重三个操作变成一次原子调用——用户请求到达 Redis 就返回结果，后续的数据库创建订单由 RabbitMQ 消费者异步消化。Redis 里用 SET 防重复购买，预热阶段把库存和商品信息灌进去，秒杀期间完全不碰数据库。这是一个典型的'前置缓冲 + 异步削峰'模式。"

---

## 5. Agent 中间件链设计

### 设计动机

Agent 的请求处理不是一条直线——在进入 LLM 之前，需要完成 JWT 认证、权限过滤、正则快捷路由、RAG 工具动态注入、Skills 规范加载等多个横切关注点。如果把这些逻辑硬编码在 Agent 定义中，会导致耦合严重、难以独立测试。

### 实现方案

hmall 利用 DeepAgents 框架的 `AgentMiddleware` 机制，在两个 Agent 中各组装了一条中间件链：

```
CustomerAgent 中间件链：
  CacheMiddleware  →  AuthMiddleware  →  PermissionMiddleware
       →  RegexShortcutMiddleware  →  RAGMiddleware  →  SkillsMiddleware

AdminAgent 中间件链：
  AuthMiddleware  →  PermissionMiddleware  →  RegexShortcutMiddleware
       →  RAGMiddleware  →  SkillsMiddleware
```

**中间件接口**（DeepAgents 框架定义）：

```python
class AgentMiddleware:
    def modify_request(self, request: ModelRequest) -> ModelRequest:
        """请求进入前修改（非阻塞）"""
        return request

    async def awrap_model_call(self, request, handler):
        """包裹 LLM 调用（可异步拦截）"""
        return await handler(request)
```

**各中间件职责**：

| 中间件 | 文件 | 接口 | 职责 |
|------|------|:---:|------|
| **AuthMiddleware** | `middleware/auth.py` | `awrap_model_call` | 解析 context.user_token → 提取 role → 注入 runtime context |
| **PermissionMiddleware** | `middleware/permission.py` | `modify_request` | role=admin 时过滤写操作工具 |
| **RegexShortcutMiddleware** | `middleware/regex_shortcut.py` | `awrap_model_call` | 正则匹配 → 直接调用工具 + 格式化返回（跳过 LLM） |
| **RAGMiddleware** | `middleware/rag_context.py` | `awrap_model_call` | enable_rag=true → 通过 MCP 加载 RAG 工具并注入 |
| **SkillsMiddleware** | `deepagents.middleware.skills` | `modify_request` | 读取 /skills/*/SKILL.md → 注入到 system prompt |
| **CacheMiddleware** | `deepagents.middleware.cache` | `modify_request` | 会话级缓存（CustomerAgent 专属） |

### 设计亮点

**1. 优先级有序**：正则路由在 RAG 之前——高频固定模式优先命中，不需 LLM 推理。权限过滤在工具注入之前——AdminAgent 的工具列表中本就不含写操作工具。

**2. RAGMiddleware 优雅降级**：MCP Server 不可达时，`RAGMiddleware` 只 log warning 并放行，不阻塞 Agent 核心功能。

```python
# RAGMiddleware._maybe_inject_rag 的核心逻辑
try:
    rag_tools = rag_loader.get_rag_tools()
    all_tools = request.tools + rag_tools
    return handler(request.override(tools=all_tools))
except Exception as e:
    logger.warning(f"RAG tools unavailable: {e}")
    return await handler(request)  # 降级：不使用 RAG 工具
```

**3. Skills 解耦**：Agent 行为规范与代码分离。修改购物引导流程只需编辑 `SKILL.md`，无需改代码、重启服务。

### 对比分析

| 方案 | 横切关注点管理 | 降级能力 | 可测试性 |
|------|:---:|:---:|:---:|
| 硬编码在 Agent 定义中 | ❌ 耦合 | ❌ | 低 |
| 简单装饰器链 | ⚠️ 顺序不清晰 | ⚠️ | 中 |
| **DeepAgent Middleware 链（hmall）** | **✅ 显式有序** | **✅ 逐级降级** | **高（独立测试）** |

### 面试展示要点

> "Agent 的六个横切关注点——认证、权限、正则路由、RAG 注入、Skills 加载——没有硬编码在 Agent 定义中，而是组装成一条优先级有序的中间件链。每个中间件只做一件事，可以独立测试和替换。最关键的降级设计是 RAGMiddleware——MCP Server 不可达时只打 warning 日志然后放行，Agent 仍然能用业务工具正常对话。正则路由在 RAG 之前，高频固定模式零 LLM 消耗。"

---

## 6. RAG 知识库：LightRAG + MCP 三层桥接

### 设计动机

Agent 需要回答"退换货政策是什么？""秒杀库存怎么设置合理？"这类**知识型问题**。这些答案不在任何微服务的 API 中，而是存在于运营文档（PDF/Markdown/Excel）里。需要一个知识库系统来存储和检索这些非结构化知识，并通过标准化协议桥接到 Agent。

### 实现方案

```
前端 ChatPanel.vue
  │ 「知识库」开关 → enable_rag → sendMessage
  ▼
Agent Server (:8090)
  │ RAGMiddleware
  │   context.enable_rag=true → rag_loader.get_rag_tools()
  │   MultiServerMCPClient 连接 RAG MCP Server
  ▼
RAG MCP Server (:8008)
  │ FastMCP HTTP 服务（start_rag_server.py）
  │ 3 个 MCP 工具：
  │   rag_query(query, mode)        → POST /query      语义检索＋答案
  │   rag_query_data(query, mode)   → POST /query/data 结构化实体/关系
  │   rag_graph_search(query)       → POST /query/data 图谱搜索
  │
  │ 内部 LightRAGClient（httpx 异步客户端）
  │   ├─ JWT token 缓存 + 401 自动重登录
  │   └─ 可选 API Key 认证
  ▼
LightRAG Server (:9621)
  │ POST /query       → 知识图谱 + 向量混合检索 → LLM 生成答案
  │ POST /query/data  → 结构化检索（entities/relationships/chunks）
  │ WebUI (:9621/webui) → 文档上传与管理
  │
  └─ 存储层
       ├─ NetworkX（内存图，开发环境）
       ├─ NanoVectorDB（内存向量，开发环境）
       └─ JSON 文件（KV 存储，开发环境）
```

**RAG MCP Server 关键代码**（`src/mcp_servers/rag_server.py`）：

```python
# LightRAGClient 核心设计
class LightRAGClient:
    def __init__(self, base_url, username, password, api_key=""):
        self._base_url = base_url
        self._access_token = None
        self._token_expires_at = 0

    async def _ensure_token(self):
        """每次请求前检查 token，401 时自动重新登录"""
        if self._token_expires_at - time.time() < 1800:
            await self._login()

    async def _login(self):
        """POST /login → form-encoded username/password → JWT"""
        resp = await self._client.post("/login", data={
            "username": self._username,
            "password": self._password,
        })
        data = resp.json()
        self._access_token = data["access_token"]

# 3 个 MCP 工具
@mcp.tool()
async def rag_query(query: str, mode: str = "mix") -> str:
    result = await client.query(query, mode)
    return f"{result['response']}\n\n参考来源：\n" + ...

@mcp.tool()
async def rag_query_data(query: str, mode: str = "mix") -> str:
    result = await client.query_data(query, mode)
    return format_structured(result)

@mcp.tool()
async def rag_graph_search(query: str) -> str:
    result = await client.query_data(query, "mix")
    return format_graph_entities(result)
```

### 设计亮点

**1. 三层解耦**：LightRAG 独立管理知识库文档，MCP Server 封装为标准化工具，Agent 通过 MCP 协议动态集成。任何一层可独立替换。

**2. Token 自动刷新**：`_ensure_token()` 在每次 API 调用前检查 JWT 过期时间（提前 30 分钟刷新），401 时自动重新登录。对上层完全透明。

**3. 模块级缓存**：`rag_loader.py` 中的 MCP 工具列表模块级缓存，避免每次 `model_call` 都重复连接 MCP Server。

**4. 优雅降级**：RAG MCP Server 不可达时只 log warning，Agent 降级为"不使用 RAG 工具"模式，不阻塞核心对话功能。

### 对比分析

| 方案 | 知识库集成方式 | 工具暴露粒度 | 降级能力 |
|------|-------------|:---:|:---:|
| 直接 HTTP 调用 LightRAG | 代码耦合 | — | ❌ |
| 内嵌 RAG 库 | 部署耦合 | — | ❌ |
| **MCP 三层桥接（hmall）** | **协议标准化，替换任一节点无侵入** | **3 个语义化工具** | **✅ 逐级降级** |

### 面试展示要点

> "知识库没有内嵌到 Agent 代码里，而是通过 MCP 协议做了三层解耦：LightRAG 管理文档和知识图谱，MCP Server 封装为三个标准化工具，Agent 通过 RAGMiddleware 动态注入。核心亮点是 JWT 自动刷新——LightRAGClient 在每次 API 调用前检查 token 有效期，提前 30 分钟自动刷新，401 时立即重新登录，对上层完全透明。前端加了一个'知识库'开关，开则注入 RAG 工具，关则只用业务工具——用户自主控制，不浪费上下文。"

---

## 7. Gateway 全局认证 + Lua 滑动窗口限流

### 设计动机

在微服务架构中，如果每个微服务各自做认证和限流，会导致代码重复、逻辑不一致。Gateway 是统一入口，应该在这里完成"认证 + 限流"，让下游微服务专注于业务逻辑。

### 实现方案

```
用户请求
  │
  ▼
Spring Cloud Gateway (:8080)
  │
  ├─ AuthGlobalFilter（全局认证过滤器）
  │   │ 解析 Authorization Header
  │   │ 按路径前缀匹配 C 端 JWT 或 管理端 JWT
  │   │ 解析用户信息写入 Header（X-User-Id / X-User-Role）
  │   │ 校验失败 → 401
  │
  ├─ RateLimitGatewayFilter（滑动窗口限流）
  │   │ Redis ZSET 实现滑动窗口计数器
  │   │ Lua 脚本原子操作：ZADD + EXPIRE + ZCOUNT
  │   │ 超出限制 → HTTP 429
  │
  └─ 路由转发 → 下游微服务
```

**滑动窗口限流 Lua 脚本**：

```lua
-- rate_limit.lua
local key = KEYS[1]           -- rate:user:{userId}:api:{path}
local limit = tonumber(ARGV[1])  -- 限流阈值
local window = tonumber(ARGV[2]) -- 窗口大小（秒）
local now = tonumber(ARGV[3])    -- 当前时间戳（毫秒）

-- 移除窗口外的记录
redis.call('ZREMRANGEBYSCORE', key, 0, now - window * 1000)

-- 统计窗口内请求数
local count = redis.call('ZCARD', key)

if count >= limit then
    return 0  -- 限流触发
end

-- 记录本次请求
redis.call('ZADD', key, now, now .. ':' .. math.random())
redis.call('EXPIRE', key, window + 1)

return 1  -- 放行
```

**Nacos 动态路由配置**：

```yaml
# Gateway 路由规则（存储在 Nacos 配置中心）
spring:
  cloud:
    gateway:
      routes:
        - id: item-service
          uri: lb://item-service
          predicates:
            - Path=/api/items/**
        - id: trade-service
          uri: lb://trade-service
          predicates:
            - Path=/api/orders/**, /api/seckill/**
```

### 设计亮点

**1. 认证与业务解耦**：微服务代码中完全不需要处理 Token 解析和校验逻辑，从 Header 直接获取用户 ID。

**2. 滑动窗口 vs 固定窗口**：固定窗口（如每分钟限 100 次）在两个窗口交界处可能出现"瞬间 200 次"的限流失效。滑动窗口用 Redis ZSET 按时间流动计算，精确平滑。

**3. Nacos 动态路由**：新增微服务或修改路由规则只需更新 Nacos 配置，Gateway 通过 `@RefreshScope` 热加载，无需重启。

### 对比分析

| 方案 | 实现方式 | 精确度 | 动态调整 |
|------|------|:---:|:---:|
| Gateway 固定窗口 | 内存计数器 | 低（边界问题） | 需重启 |
| Sentinel 限流 | 内置算法 | 中 | ✅ Dashboard 调整 |
| **Redis ZSET 滑动窗口（hmall）** | **Lua 原子** | **高（时间维度精确）** | **Nacos 热更新** |

### 面试展示要点

> "限流没有用简单的固定窗口——那在两个窗口交界处会瞬间翻倍。我用 Redis ZSET 实现了一个精确的滑动窗口：ZREMRANGEBYSCORE 清理过期记录 → ZCARD 统计窗口内请求数 → ZADD 追加新请求——三步在一个 Lua 脚本里原子执行。路由规则存在 Nacos 配置中心，新增微服务或改限流阈值都是热更新，Gateway 通过 RefreshScope 自动感知，不停机。"

---

## 8. 个性化推荐：ES 召回 + LLM 理由生成

### 设计动机

传统电商推荐系统通常返回"猜你喜欢：商品 A、商品 B"，用户看到冷冰冰的商品列表缺乏点击动力。Agent 可以利用 LLM 的文本生成能力为每件推荐商品生成**个性化推荐理由**（如"因为你最近浏览过运动鞋，这款跑步袜很适合搭配…"），提升推荐说服力。

### 实现方案

```
C 端用户消息："有什么推荐？"
  │
  ▼
CustomerAgent (L1 正则命中)
  │ → 调用 get_recommendations_api(scenario="home")
  │
  ▼
搜索引擎层 (search-service)
  │ 基于 Elasticsearch 查询：
  │   ├─ 用户购买历史商品类别 → ES terms query 召回同类目热门商品
  │   ├─ 用户浏览历史的品牌 → ES match query 召回同品牌新品
  │   └─ 协同过滤：购买过同类商品的用户还买了什么
  │ 返回 top-20 候选商品
  ▼
Agent 层
  │ → analyze_user_preferences()
  │   ├─ 提取用户购买历史关键词（类目/品牌/价格区间）
  │   ├─ 提取用户浏览历史特征
  │   └─ 返回偏好画像文本
  │
  │ → Formatter 生成 Markdown 表格
  │   ├─ 商品名称、价格、图片
  │   └─ 每件商品附带 LLM 生成的推荐理由
  │
  ▼
前端 ChatPanel.vue
  渲染 Markdown 推荐表格 + 推荐理由 > 引用块
```

**推荐场景矩阵**：

| 场景 | 触发方式 | ES 召回策略 | 推荐理由侧重 |
|------|---------|-----------|------------|
| 猜你喜欢（首页） | L1 正则："猜你喜欢""有什么推荐" | 类目 + 价格区间 | 基于购买偏好 |
| 看了又看（详情页） | LLM 判断：用户浏览/购买某商品后 | 同品牌 + 竞品 | 基于当前商品关联 |
| 购物车凑单 | LLM 判断：用户查看购物车时 | 低价 + 同店铺 | 凑满减/包邮 |

### 设计亮点

**1. ES 召回层与 LLM 分离**：ES 负责高并发的商品召回（毫秒级），LLM 负责低频的推荐理由生成，各司其职。

**2. 推荐理由个性化**：`analyze_user_preferences` 从用户历史中提取关键词（如"运动鞋""红色""200-300元"），LLM 将此信息融入推荐理由，比固定模板的"您可能喜欢"有说服力得多。

**3. 三段式推荐闭环**：推荐展示 → 说明理由 → 主动询问（"要查看详情或加入购物车吗？"），形成完整转化链路。

### 对比分析

| 方案 | 召回能力 | 推荐理由 | 延迟 |
|------|:---:|:---:|:---:|
| 纯规则推荐 | 中 | ❌ 无 | 低 |
| 纯 LLM 推荐 | LLM 编造（不可靠） | ✅ | 高 |
| **ES 召回 + LLM 理由（hmall）** | **✅ 实时精确** | **✅ 个性化** | **中（ES 毫秒 + LLM 2-3s）** |

### 面试展示要点

> "推荐不是 LLM 自己编造商品——那样既不实时也不可靠。召回层用 Elasticsearch 按用户购买类目、品牌、价格区间做精确匹配，毫秒级返回 20 个候选。LLM 只负责为每件商品生成个性化推荐理由——analyze_user_preferences 从购买和浏览历史里提取用户画像关键词，嵌入推荐理由中，比固定模板有说服力得多。三个场景用不同的召回策略：首页猜你喜欢按类目、详情页看了又看按品牌关联、购物车凑单按价格和店铺。"

---

## 9. 本地消息表 + 定时重发：分布式最终一致性

### 设计动机

在微服务架构中，一个业务操作往往涉及"数据库写入 + 消息发送"两步。比如创建订单后需要发送 MQ 消息清空购物车——如果数据库写入成功但 MQ 发送失败，就会产生数据不一致。传统做法无法保证这两个异构操作的原子性。

### 实现方案

hmall 的 `trade-service` 和 `pay-service` 均实现了**事务发件箱模式（Transactional Outbox）**：

```
业务操作（如创建订单）
  │
  ├─① 开启数据库事务
  │    ├─ INSERT INTO orders (...)
  │    ├─ INSERT INTO t_local_message (message_id, exchange, routing_key, body, status=0)
  │    └─ 提交事务（两条 INSERT 同时成功或同时失败）
  │
  └─② 事务提交后，定时任务异步扫描 t_local_message 表
       @Scheduled(fixedDelay = 10_000) → 每 10 秒扫描 status=0 的记录
       │
       ├─ RabbitMQ 发送成功 → status=1（完成）
       └─ RabbitMQ 发送失败 → tryCount++
            ├─ tryCount < 5 → 保留 status=0，下轮重试
            └─ tryCount ≥ 5 → status=2（永久失败，告警）
```

**`t_local_message` 表结构**：

```java
@TableName("t_local_message")
public class LocalMessage {
    private Long id;
    private String messageId;       // 业务关联 ID（如订单号+suffix）
    private String exchange;        // RabbitMQ Exchange
    private String routingKey;      // RabbitMQ Routing Key
    @TableField(typeHandler = LongListJsonTypeHandler.class)
    private List<Long> messageBody; // JSON 消息体
    private Integer status;         // 0:pending  1:success  2:permanent_fail
    private Integer tryCount;       // 已重试次数（上限 5）
    private Long userId;            // 消息产生时的用户 ID
}
```

**创建订单时写入消息表**（`OrderServiceImpl.createOrder()`）：

```java
// 与订单 INSERT 在同一事务中
LocalMessage msg = new LocalMessage();
msg.setMessageId(order.getId() + "_pay_success");
msg.setExchange(MQConstants.CLEAR_CART_EXCHANGE_NAME);
msg.setRoutingKey(MQConstants.CLEAR_CART_KEY);
msg.setMessageBody(new ArrayList<>(itemIds));
msg.setStatus(0);
msg.setTryCount(0);
localMessageMapper.insert(msg);
// → 事务提交：订单 + 消息同时落库
```

**定时重发器**（`LocalMessageSender`）：

```java
@Scheduled(fixedDelay = 10_000)
public void sendPendingMessages() {
    List<LocalMessage> pending = localMessageMapper.selectList(
        new LambdaQueryWrapper<LocalMessage>()
            .eq(LocalMessage::getStatus, 0)  // pending
            .or().eq(LocalMessage::getStatus, 2) // 上次重试失败的
            .last("LIMIT 100")
    );

    for (LocalMessage msg : pending) {
        try {
            rabbitTemplate.convertAndSend(
                msg.getExchange(), msg.getRoutingKey(), msg.getMessageBody()
            );
            msg.setStatus(1); // 成功
        } catch (Exception e) {
            msg.setTryCount(msg.getTryCount() + 1);
            if (msg.getTryCount() >= 5) {
                msg.setStatus(2); // 永久失败（需人工介入）
            }
        }
        localMessageMapper.updateById(msg);
    }
}
```

### 设计亮点

**1. 同事务原子性**：消息插入与业务数据插入在同一数据库事务中，利用 ACID 保证两者同时成功或同时失败，不依赖分布式事务。

**2. 最大努力通知**：定时任务逐条扫描 + 重试（上限 5 次），确保消息最终送达。`LIMIT 100` 限制单次扫描量，防止大量积压时阻塞。

**3. 永久失败告警**：超过 5 次重试仍失败的消息标记为 `status=2`，运营人员可通过后台查看并手动补偿。

**4. 实现简洁**：不需要额外的消息中间件或协调器——一张 MySQL 表 + Spring `@Scheduled` 注解即可实现。

### 对比分析

| 方案 | 原子性保证 | 额外依赖 | 实现复杂度 |
|------|:---:|:---:|:---:|
| 先发 MQ 再写 DB | ❌ DB 失败回滚不了 MQ | 无 | 低 |
| 先写 DB 再发 MQ | ❌ MQ 失败回滚不了 DB | 无 | 低 |
| **本地消息表 + 定时重发** | **✅ 同事务原子 + 最终一致** | **仅 MySQL** | **中** |
| RocketMQ 事务消息 | ✅ | RocketMQ | 中 |
| Seata TCC | ✅ | Seata Server | 高 |

### 面试展示要点

> "微服务里最头疼的是'DB 写成功但 MQ 发失败'这种部分失败。我用了事务发件箱模式——创建订单时把需要发送的 MQ 消息作为一行记录和订单一起写入数据库，同一个事务要么都成功要么都失败。后台一个 @Scheduled 定时任务每 10 秒扫未发送的记录，逐条重发，最多重试 5 次。不需要 RocketMQ 的事务消息，一张 MySQL 表搞定。超过 5 次仍失败的标记为永久失败，走人工补偿。"

---

## 10. Seata 全局事务：跨服务强一致性

### 设计动机

本地消息表解决的是"DB + MQ"的最终一致性，但有些场景需要**跨多个微服务的数据库操作强一致**。比如下单流程：`trade-service` 创建订单 → `item-service` 扣减库存 → `user-service` 扣减余额。三个操作分布在三个独立的 MySQL 数据库上，任何一个失败都需要全部回滚。

### 实现方案

hmall 接入 **Seata AT 模式**（Automatic Transaction），通过"一阶段自动提交 + 二阶段回滚（undo_log 反向补偿）"实现全局事务：

```
@GlobalTransactional(name = "createOrder", timeoutMills = 300000)
public Long createOrder(OrderFormDTO orderForm) {
    // 1. item-service: 扣减库存
    itemClient.deductStock(orderForm.getItems());
    
    // 2. user-service: 扣减余额
    userClient.deductBalance(orderForm.getUserId(), orderForm.getTotal());
    
    // 3. trade-service: 创建订单（本地）
    orderMapper.insert(order);
    
    // 任一操作失败 → Seata 自动回滚 undo_log
}
```

**Seata 关键配置**（`bootstrap.yml`，所有微服务共享）：

```yaml
seata:
  registry:
    type: nacos
    nacos:
      server-addr: localhost:8848
      namespace: ""
      group: SEATA_GROUP
      application: seata-server
  tx-service-group: hmall-tx-group
  service:
    vgroup-mapping:
      hmall-tx-group: default
  data-source-proxy-mode: AT
```

**AT 模式原理**：

```
Phase 1: 一阶段提交
  ┌─────────────────────────────────────────────┐
  │ 各服务执行 SQL → 自动生成 before_image +    │
  │ after_image → 存入各自数据库的 undo_log 表   │
  │ → 提交本地事务，释放数据库锁                   │
  └─────────────────────────────────────────────┘
                     │
            Seata TC（事务协调器）
                     │
         ┌───────────┴───────────┐
         ▼                       ▼
    全部成功                     任意失败
         │                       │
Phase 2: 删除 undo_log     Phase 2: 回滚
   各服务执行                用 before_image
   DELETE FROM               反向补偿数据
   undo_log                 (undo_log)
```

### 设计亮点

**1. 无侵入集成**：只需 `@GlobalTransactional` 注解 + 引入 `seata-spring-boot-starter` 依赖 + 建 `undo_log` 表，业务代码几乎无需修改。

**2. Nacos 注册发现**：Seata Server 注册到 Nacos，微服务通过 Nacos 自动发现 Seata TC（事务协调器），无需硬编码地址。

**3. AT 模式自动补偿**：Seata 自动拦截 SQL 生成 `before_image` / `after_image`，开发者不需要写 TCC 的 try-confirm-cancel 三个方法。

### 对比分析

| 方案 | 一致性 | 性能 | 侵入性 | 适用场景 |
|------|:---:|:---:|:---:|------|
| 本地消息表 | 最终一致 | 高 | 低 | DB + MQ 场景 |
| **Seata AT** | **强一致（同步）** | **中（一阶段快速释放锁）** | **低（@GlobalTransactional 注解）** | **跨服务 DB 操作** |
| Seata TCC | 强一致 | 高 | 高（需写三方法） | 自定义资源操作 |
| Saga | 最终一致 | 高 | 高 | 长流程事务 |

### 面试展示要点

> "跨微服务的事务一致性用了 Seata AT 模式。一个 @GlobalTransactional 注解包裹下单流程——扣库存、扣余额、创建订单——三个操作分布在三个不同的数据库上。Seata 的 AT 模式核心是 undo_log：一阶段各服务正常提交 SQL，Seata 自动生成前后的数据快照存到 undo_log 表；如果全部成功就删掉 undo_log，如果有失败就根据 before_image 反向回滚。开发者不需要写补偿代码，Seata 自动拦截 SQL 做快照。通过 Nacos 注册 TC（事务协调器），微服务自动发现，只加了注解和一张表。"

---

## 11. Long → String 序列化：防 JS 精度丢失

### 设计动机

hmall 使用**雪花算法（Snowflake）**作为分布式 ID 生成策略，生成的是 19 位的 `Long` 类型（如 `1234567890123456789`）。但 JavaScript 的 `Number` 类型最大安全整数是 `2^53 - 1 ≈ 9 × 10^15`（16 位），无法完整表示 19 位雪花 ID。前端收到后端返回的 `Long` 类型 ID 后，末几位会被截断为 0，导致后续操作（如查询订单详情）因 ID 不匹配而失败。

### 实现方案

在 `hm-common` 模块中配置 Jackson 序列化器，将全局所有 `Long` 类型字段自动转为 `String` 输出：

**`JacksonConfig`（`hm-common`）**：

```java
@Configuration
public class JacksonConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            // 全局：所有 Long 类型序列化为 String
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(long.class, ToStringSerializer.instance);
        };
    }
}
```

**效果**：

```json
// 配置前（JS 无法正确接收）
{
  "id": 1234567890123456789,
  "userId": 9876543210987654321
}
// → JS 解析后：id = 1234567890123456700（精度丢失！）

// 配置后（JS 安全接收）
{
  "id": "1234567890123456789",
  "userId": "9876543210987654321"
}
// → JS 解析后：id = "1234567890123456789"（字符串，完全保留）
```

### 设计亮点

**1. 全局配置，零侵入**：只需在 `hm-common` 中定义一次 `JacksonConfig`，所有微服务通过依赖 `hm-common` 自动继承该配置，业务代码无需任何修改。

**2. 双向兼容**：`Long → String` 仅影响序列化（后端 → 前端）。`@RequestBody` 反序列化时，Jackson 自动将 String 转回 Long，Java 内部运算不受影响。

**3. MyBatis Plus 配合**：实体类中 `@TableId(type = IdType.ASSIGN_ID)` 启用雪花 ID，Jackson 输出时自动转换，前端拿到安全字符串。

### 对比分析

| 方案 | 前端兼容性 | 改动范围 | 数据库类型 |
|------|:---:|------|:---:|
| 改为 Integer ID | ✅ | 全链路改造 | 需迁移 |
| 前端用 BigInt | ⚠️（兼容性差） | 无 | — |
| 每个 DTO 手动转 | ⚠️ 易遗漏 | 大量 DTO 改动 | — |
| **全局 Long→String（hmall）** | **✅** | **一处配置全局生效** | **不变** |

### 面试展示要点

> "雪花 ID 是 19 位的 Long，JavaScript 的 Number 只能精确表示 16 位——ID 传到前端末几位就全变 0 了，后续用这个 ID 查订单全是 404。解决很简单但关键是全局生效：在 hm-common 里定义了一个 JacksonConfig，把 Long.class 和 long.class 的序列化器统一设为 ToStringSerializer，所有微服务通过依赖 hm-common 自动继承。后端 Java 内部运算还是用 Long，数据库也是 bigint，只是发到前端时才变成字符串。"

---

## 12. Feign 自动传递用户上下文

### 设计动机

在一个请求链（Gateway → trade-service → item-service → cart-service）中，下游微服务需要知道当前请求的用户 ID，否则无法做数据隔离查询（如"我的订单""我的购物车"）。如果每个服务都从 HTTP Header 中手动提取再手动塞入下一次 Feign 调用，代码重复且易遗漏。

### 实现方案

在 `hm-api` 模块的 `DefaultFeignConfig` 中实现 `RequestInterceptor`，**自动**从当前请求的 Header 中提取用户信息并透传到 Feign 请求：

```java
@Configuration
public class DefaultFeignConfig {
    @Bean
    public RequestInterceptor userInfoRequestInterceptor() {
        return requestTemplate -> {
            // 从当前线程的 RequestContext 获取原始请求
            ServletRequestAttributes attributes = 
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                // 自动透传用户相关 Header
                String userId = request.getHeader("X-User-Id");
                if (StrUtil.isNotBlank(userId)) {
                    requestTemplate.header("X-User-Id", userId);
                }
                // 可扩展：透传更多上下文（角色、TraceId 等）
            }
        };
    }
}
```

**传递链路**：

```
Gateway (:8080)
  │ AuthGlobalFilter 解析 JWT → 写入 Header:
  │   X-User-Id: 123456
  │   X-User-Role: user
  ▼
trade-service (:8085)
  │ ServerHttpRequest.getHeader("X-User-Id") → "123456"
  │
  │ 调用 itemClient.deductStock():
  │   DefaultFeignConfig.userInfoRequestInterceptor
  │   → 从当前请求提取 X-User-Id
  │   → 自动注入到 Feign 请求 Header
  ▼
item-service (:8081)
  │ ServerHttpRequest.getHeader("X-User-Id") → "123456"
  │ ✅ 无缝获取用户上下文
```

### 设计亮点

**1. 零业务代码侵入**：开发者写 `itemClient.deductStock()` 时完全不需要手动传用户 ID——Interceptor 自动完成透传，对业务代码透明。

**2. 集中配置全局生效**：在 `hm-api` 模块定义一次，所有使用 Feign 的微服务通过 `@FeignClient(configuration = DefaultFeignConfig.class)` 或全局 `@EnableFeignClients(defaultConfiguration = ...)` 自动启用。

**3. 可扩展**：`RequestInterceptor` 可以透传任意 Header（如 TraceId、TenantId、用户角色等），适合构建完整的分布式链路追踪上下文。

### 对比分析

| 方案 | 代码侵入 | 一致性 | 遗漏风险 |
|------|:---:|:---:|:---:|
| 每个 Feign 调用手动传参 | 高 | 低 | 高（易遗漏） |
| ThreadLocal 手动设置 | 中 | 中 | 中（需 try-finally 清理） |
| **Feign RequestInterceptor（hmall）** | **零** | **✅ 全局统一** | **无** |

### 面试展示要点

> "微服务调用链里每个下游都需要用户 ID 做数据隔离，如果每个 Feign 调用手动传参很容易忘。我在 hm-api 的 DefaultFeignConfig 里配了一个 RequestInterceptor，它自动从当前请求上下文提取 X-User-Id 等 Header，注入到下一次 Feign 请求中。对业务代码完全透明——开发者调用 itemClient.deductStock() 时根本不用管用户 ID 怎么传。而且是 hm-api 全局生效，所有微服务自动继承。"

---

## 13. Sentinel 降级熔断

### 设计动机

微服务之间通过 Feign 远程调用，但任何依赖服务都可能超时、报错或宕机。如果不做熔断保护，一个 item-service 的故障会级联拖垮 trade-service、cart-service，最终整个系统不可用（雪崩效应）。

### 实现方案

hmall 对关键的 Feign 接口配置了 **Sentinel FallbackFactory**：

```java
// ItemClientFallbackFactory — hm-api 模块
@Component
public class ItemClientFallbackFactory implements FallbackFactory<ItemClient> {
    @Override
    public ItemClient create(Throwable cause) {
        return new ItemClient() {
            @Override
            public ItemDTO queryItemById(Long id) {
                log.error("查询商品失败，触发降级: id={}", id, cause);
                // 返回兜底数据
                ItemDTO fallback = new ItemDTO();
                fallback.setId(id);
                fallback.setName("商品信息暂不可用");
                fallback.setPrice(0);
                return fallback;
            }
            
            @Override
            public void deductStock(List<OrderDetailDTO> items) {
                // 库存扣减失败 → 抛出业务异常，让上游 Seata 全局事务回滚
                throw new BizException("库存服务暂不可用，请稍后重试");
            }
        };
    }
}

// Feign 接口声明
@FeignClient(
    name = "item-service",
    fallbackFactory = ItemClientFallbackFactory.class
)
public interface ItemClient {
    @GetMapping("/items/{id}")
    ItemDTO queryItemById(@PathVariable("id") Long id);
    
    @PutMapping("/items/stock/deduct")
    void deductStock(@RequestBody List<OrderDetailDTO> items);
}
```

**Sentinel 控制面板规则**：

```json
{
  "resource": "GET:/items/{id}",
  "grade": 0,          // 0=慢调用比例
  "count": 200,        // 最大 RT 200ms
  "slowRatioThreshold": 0.5,  // 50% 请求超过阈值 → 熔断
  "timeWindow": 10     // 10 秒后进入半开状态
}
```

**熔断状态机**：

```
        触发熔断条件
CLOSED ──────────────► OPEN
  ↑                     │
  │    半开探测通过      │ 熔断时间窗口结束
  │    (getAllowed=true) │
  └── HALF_OPEN ◄───────┘
```

### 设计亮点

**1. FallbackFactory 提供异常原因**：相比简单的 `fallback`，`FallbackFactory` 的 `create(Throwable cause)` 可以获取失败原因，日志中区分"超时""限流""服务不可用"等不同场景。

**2. 降级策略按接口粒度**：商品查询降级返回 placeholder 数据（不阻塞用户浏览）；库存扣减降级抛异常（让 Seata 回滚，不能假装成功）。

**3. Nacos 持久化规则**：Sentinel 规则存储在 Nacos 配置中心，服务重启规则不丢失，且支持 Dashboard 实时调整。

### 对比分析

| 方案 | 自动熔断 | 降级策略 | 规则持久化 |
|------|:---:|:---:|:---:|
| 无熔断（裸 Feign） | ❌ | ❌ | — |
| Hystrix（停更） | ✅ | ✅ | 本地配置 |
| Resilience4j | ✅ | ✅ | 本地配置 |
| **Sentinel + Nacos（hmall）** | **✅** | **✅ 按接口粒度** | **✅ Nacos 持久化 + Dashboard 热调整** |

### 面试展示要点

> "微服务调用链的雪崩风险用 Sentinel 解决的。关键点有两个：一是用了 FallbackFactory 而不是简单的 fallback——这样能拿到 throwable cause，日志里可以区分超时还是服务挂了；二是降级策略按接口粒度——商品查询降级返回个 placeholder 不阻塞用户浏览，但库存扣减降级必须抛异常，让 Seata 回滚全局事务，不能假装扣成功了。Sentinel 规则存在 Nacos 里，重启不丢失，Dashboard 上实时调阈值。"

---

## 14. Nacos 动态路由热更新

### 设计动机

Gateway 的路由规则传统上写在 `application.yml` 配置文件中，新增一个微服务或修改一条路由规则需要修改配置文件 + 重启 Gateway。在生产环境中，每次调整路由都重启 Gateway 意味着全部请求中断。

### 实现方案

通过自定义 `DynamicRouteLoader`，从 Nacos 读取路由配置并动态写入 Gateway 的路由表：

```java
@Component
public class DynamicRouteLoader implements ApplicationEventPublisherAware {
    @Resource
    private RouteDefinitionWriter routeDefinitionWriter;
    
    private final List<RouteDefinition> routeDefinitions = new ArrayList<>();
    
    /**
     * 从 Nacos 动态加载路由并注册到 Gateway
     */
    @PostConstruct
    public void loadRoutes() {
        // 1. 从 Nacos 读取路由配置
        String dataId = "gateway-routes.json";
        String group = "DEFAULT_GROUP";
        String config = configService.getConfig(dataId, group, 5000);
        
        // 2. 解析 JSON 路由定义
        List<RouteDefinition> definitions = JSON.parseArray(
            config, RouteDefinition.class
        );
        
        // 3. 动态写入 Gateway 路由表
        for (RouteDefinition definition : definitions) {
            routeDefinitionWriter.save(Mono.just(definition)).subscribe();
            routeDefinitions.add(definition);
        }
    }
    
    /**
     * 监听 Nacos 配置变更 → 热更新路由
     */
    @PostConstruct
    public void addRouteListener() {
        configService.addListener("gateway-routes.json", "DEFAULT_GROUP",
            new Listener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    // 1. 清除旧路由
                    for (RouteDefinition def : routeDefinitions) {
                        routeDefinitionWriter.delete(Mono.just(def.getId())).subscribe();
                    }
                    // 2. 加载新路由
                    loadRoutes();
                    log.info("路由配置已热更新");
                }
            }
        );
    }
}
```

**Nacos 中的路由配置 JSON**：

```json
[
  {
    "id": "item-service",
    "uri": "lb://item-service",
    "predicates": [{"name": "Path", "args": {"pattern": "/api/items/**"}}]
  },
  {
    "id": "trade-service", 
    "uri": "lb://trade-service",
    "predicates": [{"name": "Path", "args": {"pattern": "/api/orders/**"}}]
  }
]
```

### 设计亮点

**1. 零停机热更新**：`configService.addListener` 监听 Nacos 配置变更，回调中自动清除旧路由 + 注册新路由，Gateway 进程不中断。

**2. RouteDefinitionWriter 原子操作**：Gateway 内置的 `RouteDefinitionWriter.save()` 和 `delete()` 是线程安全的，热更新期间不影响正在处理的请求。

**3. 双重启动**：`@PostConstruct` 既做首次加载也做变更重载（通过 `loadRoutes()` 复用），逻辑统一。

### 对比分析

| 方案 | 更新方式 | 停机时间 | 规则管理 |
|------|:---:|:---:|------|
| 配置文件 + 重启 | 手动改 yml + 重启 | 分钟级 | 分散在代码仓库 |
| Spring Cloud Config 热刷新 | @RefreshScope | 秒级 | 独立 Git 仓库 |
| **Nacos 动态路由（hmall）** | **配置中心变更 → Listener 回调** | **零停机** | **Nacos Dashboard 集中管理** |

### 面试展示要点

> "Gateway 的路由规则没有写死在 yml 里，而是存在 Nacos 配置中心。启动时从 Nacos 拉取 JSON 路由定义，用 RouteDefinitionWriter 动态写入 Gateway 路由表。关键是加了 Nacos Listener——配置变更时自动清除所有旧路由、重新加载新路由，整个 Gateway 进程不停机。新增一个微服务只需要在 Nacos Dashboard 里加一条 JSON 记录，30 秒内生效。"

---

## 15. RabbitMQ 延迟消息：订单超时取消

### 设计动机

电商平台的典型需求：用户下单后 30 分钟内未支付，系统自动取消订单并释放库存。这个"30 分钟后执行"的延迟任务不能简单地用 `Thread.sleep()` 或 `ScheduledExecutorService`——前者阻塞线程，后者重启后丢失所有待执行任务。

### 实现方案

利用 **RabbitMQ 死信队列（Dead Letter Queue）** 实现延迟消息：

```
订单创建
  │
  ▼
发送消息到 order.delay.queue
  │ TTL = 30 分钟（队列级别）


  │ 30 分钟后自动过期
  ▼
消息路由到死信交换机 order.dlx.exchange
  │
  ▼
order.cancel.queue（消费者监听）
  │
  ├─ 查询订单状态
  │    ├─ status = 待付款 → 取消订单 + 释放库存
  │    └─ status = 已付款 → 忽略（用户已支付）
  └─ 完成
```

**RabbitMQ 配置**（`trade-service` 的 `MQConfig`）：

```java
@Configuration
public class DelayOrderConfig {
    
    // 延迟队列：TTL 30 分钟，绑定死信交换机
    @Bean
    public Queue orderDelayQueue() {
        return QueueBuilder.durable("order.delay.queue")
            .deadLetterExchange("order.dlx.exchange")  // 过期后投递到死信交换机
            .deadLetterRoutingKey("order.cancel")       // 死信路由键
            .ttl(30 * 60 * 1000)  // 30 分钟 TTL
            .build();
    }
    
    // 死信交换机
    @Bean
    public DirectExchange orderDlxExchange() {
        return new DirectExchange("order.dlx.exchange");
    }
    
    // 取消订单队列（消费者监听此队列）
    @Bean
    public Queue orderCancelQueue() {
        return QueueBuilder.durable("order.cancel.queue").build();
    }
    
    // 绑定死信交换机 → 取消订单队列
    @Bean
    public Binding orderCancelBinding() {
        return BindingBuilder
            .bind(orderCancelQueue())
            .to(orderDlxExchange())
            .with("order.cancel");
    }
}
```

**下单时发送延迟消息**：

```java
// OrderServiceImpl.createOrder()
rabbitTemplate.convertAndSend(
    "order.delay.exchange",  // 普通交换机
    "order.delay",           // 路由到延迟队列
    order.getId()            // 消息体：订单 ID
);
// → 消息进入 order.delay.queue
// → 30 分钟后未消费 → TTL 过期 → 死信交换机 → order.cancel.queue
```

**取消订单消费者**：

```java
@RabbitListener(queues = "order.cancel.queue")
public void handleOrderCancel(Long orderId) {
    Order order = orderService.getById(orderId);
    if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
        orderService.cancelOrder(orderId);        // 取消订单
        itemClient.restoreStock(order.getItems()); // 释放库存
        log.info("订单 {} 超时未支付，已自动取消", orderId);
    }
    // status != 待付款 → 用户已支付，忽略
}
```

### 设计亮点

**1. 零代码延迟**：延迟逻辑完全由 RabbitMQ 的 TTL + DLX 机制实现，生产者只需要发一条普通消息，消费者在 30 分钟后自动收到——中间没有任何定时轮询。

**2. 幂等消费**：消费者收到消息后先检查订单状态，只有 `PENDING_PAYMENT` 状态才取消——如果用户在最后几秒完成了支付，不会错误取消。

**3. 消息可靠**：RabbitMQ 持久化队列 + 消息持久化（`durable=true`），Broker 重启后延迟消息不丢失。

### 对比分析

| 方案 | 精确度 | 可靠性 | 资源消耗 |
|------|:---:|:---:|:---:|
| 定时轮询 DB | 低（受轮询间隔影响） | 低 | 高 |
| Redis 过期回调 | 中 | 低（key 过期事件不可靠） | 中 |
| **RabbitMQ TTL+DLX（hmall）** | **高（队列级 TTL 精确）** | **✅ 持久化 + 确认机制** | **低** |
| RocketMQ 延迟消息 | 高（18 个延迟级别） | ✅ | 低 |

### 面试展示要点

> "订单超时取消没有用定时任务轮询数据库——那样不精确还浪费资源。用了 RabbitMQ 的死信队列：下单时发一条消息到延迟队列，队列设了 30 分钟 TTL，消费者不监听。30 分钟后消息自动过期，被死信交换机路由到取消队列，消费者才收到。关键是幂等设计——消费者收到消息后先查订单状态，如果用户已经在第 29 分钟支付了，就不取消。"

---

## 16. RBAC 动态权限：三层权限控制体系

### 设计动机

管理后台需要细粒度权限控制——不同运营角色（超级管理员、商品管理员、订单管理员）拥有不同的菜单和操作权限。传统的"角色硬编码在前端路由表"方案在角色变更时需要修改代码、重新部署，无法动态调整。同时，C 端消费者和管理端运营者共用同一套 Gateway，需要从认证层就做好隔离。

### 实现方案

hmall 的管理后台实现了从**认证隔离**到**动态 URL 匹配**到**按钮级指令**的三层权限控制：

```
用户登录 admin-service
  │
  ├─ 第 1 层：认证隔离（admin.jks 独立密钥库）
  │   │ C 端 JWT：hmall.jks 密钥库签名
  │   │ 管理端 JWT：admin.jks 密钥库签名（独立密钥对）
  │   │ Gateway AuthGlobalFilter 按路径前缀区分：
  │   │   /api/users/** → C 端密钥校验
  │   │   /api/admin/** → 管理端密钥校验
  │   │ → 两套 JWT 互不通用，从源头隔离
  │
  ├─ 第 2 层：动态 URL 权限匹配（admin-service 后端）
  │   │ DynamicSecurityService.loadDataSource()
  │   │   → 从数据库加载所有 资源（URL Pattern）+ 角色 的绑定关系
  │   │   → 注入 Spring Security 的 ConfigAttribute
  │   │
  │   │ DynamicAccessDecisionManager.decide()
  │   │   → 当前用户角色 ∩ 资源所需角色
  │   │   → 有交集？放行 : 403 Forbidden
  │
  └─ 第 3 层：Vue 按钮级指令（管理后台前端）
      │ v-permission 自定义指令
      │   → 从 Vuex store 读取当前用户的角色列表
      │   → 与元素绑定的所需角色比对
      │   → 无权限 → element.remove() 移除 DOM
```

**独立 JWT 密钥库隔离**（`application.yml`）：

```yaml
# admin-service — 管理员 JWT
mall:
  jwt:
    secret: admin.jks     # 独立密钥库
    key-pair: admin-key
    key-store-pass: admin123
    expiration: 86400

# C 端微服务 — 消费者 JWT  
mall:
  jwt:
    secret: hmall.jks     # 独立密钥库
    key-pair: hmall-key
    key-store-pass: hmall123
    expiration: 7200
```

**DynamicSecurityService 动态加载数据库权限**（`admin-service`）：

```java
@Bean
public DynamicSecurityService dynamicSecurityService() {
    return () -> {
        // 从数据库加载 资源→角色 映射
        List<UmsResource> resources = resourceMapper.selectList(null);
        
        Map<String, ConfigAttribute> map = new ConcurrentHashMap<>();
        for (UmsResource resource : resources) {
            // resource.getUrl() = "/api/admin/product/**"
            // resource.getRoleName() = "商品管理员"
            map.put(resource.getUrl(), 
                new SecurityConfig(resource.getRoleName()));
        }
        return map;
    };
}
```

**v-permission 按钮级指令**（Vue 3 前端）：

```typescript
// directives/permission.ts
app.directive('permission', {
  mounted(el, binding) {
    const requiredRole = binding.value;         // 'admin:product'
    const userRoles = store.state.user.roles;   // ['admin:product', 'admin:order']
    
    if (!userRoles.includes(requiredRole)) {
      // 无权限 → 从 DOM 中彻底移除，而非隐藏
      el.parentNode?.removeChild(el);
    }
  }
});
```

```vue
<!-- 使用：只有拥有 admin:product 角色的用户才能看到此按钮 -->
<el-button v-permission="'admin:product'" @click="addProduct">
  新增商品
</el-button>
```

### 设计亮点

**1. 独立密钥库隔离 C/B 端**：两套 JWT 使用不同的 `.jks` 密钥库签名，互不通用。即使某人获取到 C 端 Token，也无法访问管理后台接口——Gateway 按路径前缀选不同密钥校验。

**2. 数据库驱动权限动态刷新**：资源-角色绑定存储在 MySQL 的 `ums_resource` 表中，修改权限不需要改代码重新部署。`DynamicSecurityService` 的 `loadDataSource()` 在每次请求时从数据库加载最新配置。

**3. DOM 级移除杜绝绕过**：`v-permission` 使用 `removeChild()` 移除元素而非 `display:none` 隐藏——即使攻击者手动修改 CSS 也无法显示被移除的按钮。

**4. 前端双 axios 实例 + sessionStorage 独立 key**：portal 和 admin 使用不同的 axios 实例和 sessionStorage key，token 不会互相覆盖：

```typescript
// portal: sessionStorage.getItem('user_token')
// admin:  sessionStorage.getItem('admin_token')
```

### 对比分析

| 方案 | 认证隔离 | 权限动态刷新 | 按钮级控制 | 安全强度 |
|------|:---:|:---:|:---:|:---:|
| 前端路由表硬编码 | ❌ | ❌ 需改代码 | ❌ | 低 |
| 单一 JWT + 前端判断 | ❌ | ❌ | ⚠️ display:none | 中 |
| **三层 RBAC（hmall）** | **✅ 独立密钥库** | **✅ DB 实时加载** | **✅ DOM 移除** | **高** |

### 面试展示要点

> "管理后台的权限做了三层：第一层认证隔离——C 端和管理端用不同的 .jks 密钥库签发 JWT，Gateway 按路径前缀选不同密钥校验，两套 Token 互不通用。第二层动态 URL 权限——资源-角色绑定存在数据库，每次请求 Spring Security 从 DB 读取最新配置匹配，不需要改代码重启。第三层按钮级指令——Vue 自定义 v-permission 指令，用 removeChild 移除 DOM 而不是 display:none，改 CSS 也绕不过去。"

---

## 17. 级联管理：DB 事务 + Redis 缓存同步清除

### 设计动机

管理后台运营人员删除一个秒杀活动时，需要同时删除：数据库中的活动记录、关联的多个场次、场次下的商品关联，以及所有这些数据在 Redis 中的缓存。如果只删数据库不删缓存，前端会展示"已删除活动"的脏数据；如果部分删除失败（如 Redis 网络抖动），会出现数据库与缓存不一致。

### 实现方案

hmall 采用 **DB 事务级联删除 + Redis 批量缓存清除 + 失败重试** 的级联管理模式：

```
运营人员点击"删除秒杀活动"
  │
  ▼
admin-service → Feign 调用 trade-service
  │
  ├─ ① 数据库级联删除（同一事务内）
  │   │ DELETE FROM seckill_merchandise 
  │   │   WHERE session_id IN (SELECT id FROM seckill_session WHERE activity_id = ?)
  │   │ DELETE FROM seckill_session WHERE activity_id = ?
  │   │ DELETE FROM seckill_activity WHERE id = ?
  │   │ → 全部成功才提交，任一失败则回滚
  │
  └─ ② Redis 缓存批量清除（事务提交后）
      │ keys = seckill:activity:{id}, seckill:session:{sessionId}*, 
      │        seckill:stock:{id}*, seckill:order:{id}*
      │
      │ redisTemplate.delete(keys)  → 批量 UNLINK
      │   ├─ 成功 → 完成
      │   └─ 失败 → messageProducer.sendRetryMessage(keys)
      │         → 延迟重试队列 → 3 次仍失败 → 告警
```

**级联删除关键代码**（`SeckillServiceImpl`）：

```java
@Transactional
public void deleteActivity(Long activityId) {
    // 1. 按关联关系逐级删除（从子到父，保证外键约束）
    List<SeckillSession> sessions = 
        sessionMapper.selectList(new LambdaQueryWrapper<SeckillSession>()
            .eq(SeckillSession::getActivityId, activityId));
    
    for (SeckillSession session : sessions) {
        // 2. 删除场次下的商品关联
        merchandiseMapper.delete(new LambdaQueryWrapper<SeckillMerchandise>()
            .eq(SeckillMerchandise::getSessionId, session.getId()));
    }
    
    // 3. 删除场次
    sessionMapper.delete(new LambdaQueryWrapper<SeckillSession>()
        .eq(SeckillSession::getActivityId, activityId));
    
    // 4. 删除活动本身
    activityMapper.deleteById(activityId);
    
    // 5. 事务提交后 → 清除 Redis 缓存
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 事务成功提交后才清除缓存，避免脏读
                clearActivityCache(activityId, sessions);
            }
        }
    );
}
```

**Redis 缓存清除 + 重试**：

```java
private void clearActivityCache(Long activityId, List<SeckillSession> sessions) {
    List<String> keys = new ArrayList<>();
    keys.add("seckill:activity:" + activityId);
    
    for (SeckillSession session : sessions) {
        keys.add("seckill:session:" + session.getId());
        keys.add("seckill:stock:" + session.getId());
        keys.add("seckill:order:" + session.getId());
    }
    
    try {
        redisTemplate.delete(keys);  // 批量 UNLINK，非阻塞
    } catch (Exception e) {
        log.error("Redis 缓存清除失败，加入重试队列: activityId={}", activityId, e);
        // 发送到延迟重试队列，3 次后仍未成功则 DB 标记 pending_clean + 告警
        messageProducer.sendCacheCleanRetry(activityId, keys, 0);
    }
}
```

### 设计亮点

**1. afterCommit 钩子**：Redis 清除在 `TransactionSynchronization.afterCommit()` 中执行——确保只有数据库事务成功提交后才清除缓存，避免事务回滚后缓存已被删除导致缓存穿透。

**2. 逐级删除顺序**：从子表到父表（商品关联 → 场次 → 活动），保证 MySQL 外键约束不报错。

**3. Redis UNLINK 异步清除**：使用 `UNLINK`（Java 中为 `redisTemplate.delete()`，Lettuce 客户端默认使用 UNLINK）而非 `DEL`——异步回收内存不阻塞线程，大量 key 清除时不卡请求。

**4. 缓存清除失败兜底**：Redis 操作失败时发送重试消息到 MQ，3 次后仍失败则标记 `pending_clean` 状态到数据库 + 发出告警，保证最终一致性。

### 对比分析

| 方案 | 缓存一致性 | 部分失败处理 | 外键约束安全 |
|------|:---:|:---:|:---:|
| 先删缓存再删 DB | ❌ DB 回滚缓存已丢失 | ❌ | ❌ |
| 先删 DB 再删缓存 | ⚠️ 缓存删除失败不一致 | ❌ | ⚠️ |
| **事务级联 + afterCommit + 重试（hmall）** | **✅ afterCommit 钩子** | **✅ MQ 重试 + DB 标记** | **✅ 子→父逐级删** |

### 面试展示要点

> "管理后台删秒杀活动不是简单的 DELETE——要级联删活动、场次、商品关联三层数据，还要同步清除 Redis 里的库存缓存。关键设计有两个：一是 Redis 清除放在 afterCommit 钩子里，如果数据库事务回滚了不会误删缓存；二是 Redis 操作失败不是直接忽略——会发到 MQ 重试 3 次，3 次还失败就 DB 里标记 pending_clean 然后告警，走人工处理。这是一种'先保证 DB 强一致，再最大努力保证缓存一致'的设计思路。"

---

## 18. 服务启动顺序与端口分配

### 启动顺序

| 步骤 | 服务 | 端口 | 命令 | 说明 |
|:---:|------|:---:|------|------|
| 1 | MySQL | 3306 | — | 9 个数据库：hmall, hm_item, hm_cart, ... |
| 2 | Redis | 6379 | — | 缓存 + 秒杀库存 + 限流 + Agent Checkpoint |
| 3 | Elasticsearch | 9200 | — | 商品搜索索引 |
| 4 | RabbitMQ | 5672 | — | 秒杀异步下单 |
| 5 | Nacos | 8848 | — | 服务注册发现 + 配置中心 |
| 6 | Gateway | 8080 | `java -jar` | Spring Cloud Gateway |
| 7 | item-service | 8081 | `java -jar` | 商品微服务 |
| 8 | cart-service | 8082 | `java -jar` | 购物车微服务 |
| 9 | pay-service | 8083 | `java -jar` | 支付微服务 |
| 10 | user-service | 8084 | `java -jar` | 用户微服务 |
| 11 | trade-service | 8085 | `java -jar` | 交易微服务（含秒杀） |
| 12 | search-service | 8089 | `java -jar` | ES 搜索微服务 |
| 13 | admin-service | 8091 | `java -jar` | 管理后台微服务 |
| 14 | LightRAG Server | 9621 | `lightrag-server` | RAG 知识库引擎 |
| 15 | RAG MCP Server | 8008 | `uv run python start_rag_server.py` | MCP 桥接服务 |
| 16 | Agent Server | 8090 | `uv run python start_server.py` | 双 Agent 运行时 |
| 17 | hmall-frontend | 5173 | `npm run dev` | Vue 3 SPA |

### 端口总览

```
前端 (5173)
  └─ Vite 代理 /api → Gateway (8080)
  └─ 直连 Agent Server (8090) via LangGraph SDK

Gateway (8080)
  └─ 负载均衡 → item(8081) / cart(8082) / pay(8083) / user(8084) / trade(8085) / search(8089) / admin(8091)

Agent Server (8090)
  └─ Gateway → Java 微服务 (通过 httpx)
  └─ RAG MCP Server (8008) → LightRAG Server (9621)
```

---

## 19. 项目文件规模与统计

### Java 后端

| 模块 | 文件数 | 说明 |
|------|:---:|------|
| `hm-gateway` | 17 | Gateway + Nacos 配置 |
| `hm-service` | 8 | 聚合 BFF |
| `hm-api` | 14 | Feign 接口定义 |
| `hm-common` | 15 | 公共工具模块 |
| `item-service` | 18 | 商品微服务 + Feign |
| `cart-service` | 14 | 购物车微服务 |
| `user-service` | 18 | 用户 + 地址微服务 |
| `trade-service` | 22 | 交易 + 秒杀核心 |
| `pay-service` | 13 | 支付微服务 |
| `search-service` | 12 | ES 搜索 + 推荐 |
| `admin-service` | 16 | RBAC 管理后台 |
| **合计** | **~170** | Maven 多模块，Spring Boot 2.7 |

### 前端（Vue 3）

| 类别 | 文件数 | 说明 |
|------|:---:|------|
| 页面组件 (views) | 26 | portal 14 个 + admin 12 个 |
| 聊天组件 (components/chat) | 5 | ChatPanel / MessageBubble / InterruptActions / ChatWidget / AdminChat |
| Composables | 2 | useLangGraph / useLlmHealth |
| API 封装 | 18 | 双端 Axios 实例 |
| Stores (Pinia) | 2 | customerStore / adminStore |
| 路由 | 1 | 22 条路由 |
| TypeScript 类型 | 6 | — |
| 静态资源 | 84 | 商品图片等 |
| **合计** | **~170** | Vite + Vue 3 + SPA |

### Agent 服务（Python）

| 类别 | 文件数 | 说明 |
|------|:---:|------|
| Agent 定义 | 8 | customer + admin (agent/prompts/tools/regex) |
| 中间件 | 5 | auth / permission / regex_shortcut / rag_context / skills(框架) |
| MCP 服务 | 1 | rag_server.py (FastMCP) |
| 工具加载 | 2 | rag_loader / formatters |
| API 路由 | 3 | batch_report / health / custom_routes |
| Gateway | 2 | auth / http_client |
| 核心 | 3 | config / llms / redis_checkpoint |
| 启动脚本 | 2 | start_server / start_rag_server |
| Skills | 9 | 7 个 customer + 2 个 admin SKILL.md |
| **合计** | **~36** | uv + Python 3.11+ |

### 文档

| 目录 | 文件数 | 说明 |
|------|:---:|------|
| Agent 功能相关 | 5 | 设计/实现/推荐/RAG 集成/RAG 文档 |
| 秒杀功能实现 | 4 | 设计/实现/管理端设计/管理端实现 |
| 管理后台相关 | 2 | 设计/实现报告 |
| RAG 相关 | 1 | LightRAG 使用说明 |
| 前端实现相关 | 1 | 前端优化方案 |
| redis 相关 | 2 | 功能说明 |
| 根目录文档 | 2 | git-commit / optimization-report |
| **合计** | **~21** | Markdown 格式 |

---

> **项目总规模**：Java 微服务 ~170 文件 + Vue 前端 ~170 文件 + Agent 服务 ~36 文件 + 文档 ~21 文件 + LightRAG submodule。一次完整的开发环境启动需约 17 个进程。

---

## 20. 面试专题：项目最难部分与解决方案

### 面试官可能的提问方式

> "你在 hmall 项目中遇到的最大技术挑战是什么？你是怎么解决的？"
>
> "这个项目里最让你引以为傲的部分是什么？"
>
> "如果给新人讲这个项目最复杂的部分，你会怎么讲？"

本章提供一个**完整的、结构化的面试回答框架**，可以从三个维度中根据面试时间灵活选择。

---

### 维度一：秒杀系统 — 高并发下的"库存不准"与"系统不崩"

#### 问题本质

秒杀场景下，短时间数千用户同时抢购一件商品。面临两个核心矛盾：

| 矛盾 | 具体表现 |
|------|---------|
| **库存准确性与并发冲突** | 数据库行级锁串行化扣库存 → QPS 上限 ~500，完全扛不住秒杀流量 |
| **数据库压力** | 每次下单都要写订单表 + 扣库存表 → 连接池瞬间耗尽，雪崩 |

更隐蔽的问题是：如果先扣 Redis 再异步发 MQ，Redis 扣成功了但 MQ 发失败了——用户看到"秒杀成功"，实际订单没生成，库存还被扣掉了。**这是"部分失败"问题。**

#### 解决思路（三层递进）

**第一步：Lua 原子脚本保证库存一致性**

这是最核心的一步。把"检查库存 + 检查重复购买 + 扣减库存 + 标记已购买"四个操作写进一个 Redis Lua 脚本，利用 Redis 单线程模型天然原子执行。杜绝了"读后写"的竞态条件——不存在"库存还剩 1 件、两个人同时读到 1"的情况。

```lua
-- 四个操作在一次原子调用中完成
local stock = redis.call('GET', stockKey)
if stock <= 0 then return -1 end           -- 库存不足
if redis.call('SISMEMBER', orderKey, userId) == 1 then
    return -2                               -- 重复购买
end
redis.call('DECR', stockKey)                -- 扣库存
redis.call('SADD', orderKey, userId)        -- 防重复
return 1
```

**第二步：RabbitMQ 异步削峰**

用户请求在 Redis 层面就返回结果，后续数据库的订单创建由 MQ 消费者异步消化。1 万 QPS 的秒杀请求，数据库实际承受的写入压力被 RabbitMQ 平滑分散到秒级——这就是"削峰填谷"。

**第三步：本地消息表解决"部分失败"**

Redis 扣库存成功后，不是直接发 MQ，而是把 MQ 消息和业务数据写入同一数据库事务中。事务提交后，`@Scheduled` 定时任务扫描未发送的消息逐条重发。DB 写成功但 MQ 发失败？下次定时扫描会重发。DB 写失败？事务回滚，消息也不会落库。

```
同一事务：INSERT order + INSERT local_message → 同时成功或同时失败
@scheduled(fixedDelay=10s): 扫描 status=0 的记录 → 逐条发送 MQ → 标记 status=1
重试 5 次仍失败 → status=2（永久失败，告警 → 人工补偿）
```

#### 为什么这很难

秒杀不是单一技术点，而是一个**系统性工程问题**：Lua 原子性 → 异步削峰 → 分布式消息可靠性，三层缺一不可。任何一层有漏洞，要么超卖，要么数据不一致，要么系统崩溃。需要同时理解 **Redis 执行模型**、**消息队列可靠性**、**数据库事务** 和 **分布式最终一致性** 四个领域。

#### 核心思考

> "秒杀的本质矛盾不是'快'，而是'多个操作的一致性'。如果用分布式锁，拿到锁后查库存再扣——锁的粒度、超时、续约全是坑。Lua 脚本从模型层面规避了这些问题：Redis 单线程执行 Lua 天然串行，不需要锁。异步削峰解决的是吞吐问题，本地消息表解决的是可靠性问题——三层各司其职。"

---

### 维度二：Agent 系统 — 在"智能"与"可控"之间找平衡

#### 问题本质

用 LLM 做 Agent 有一个天然矛盾：

| 需求 | LLM 的天然倾向 | 矛盾 |
|------|---------------|------|
| **快速响应** | 每轮都做推理选工具（1-3s） | 运营人员每天看日报等不了 3 秒 |
| **安全操作** | 可能执行破坏性操作（取消订单、清空购物车） | LLM 无法保证"不想当然地调用危险工具" |
| **权限隔离** | AdminAgent 不应能操作 C 端购物车/秒杀 | 但所有工具都注册在同一 Agent 中 |
| **知识库** | 退换货政策在文档而非 API 中 | LLM 自身不知道业务规则 |

更深层的问题是：**每一层控制都不应该依赖 LLM 的"自觉"**——你不能在系统提示词里写"请勿取消订单"然后指望 LLM 遵守。

#### 解决思路（三级路由 + 中间件链）

**L1：正则快捷路由 — 高频模式零 LLM 消耗**

运营日报、猜你喜欢推荐，这些固定短语 100% 命中。`RegexShortcutMiddleware` 排在中间件链第一个位置，命中即直接调用工具 + 格式化返回，不经过 LLM。延迟从 2-3 秒降到 <1 秒，零 token 费用。

```python
# AdminAgent 的正则规则
(r"(运营日报|生成日报|今日数据|报告)", generate_daily_report)
# CustomerAgent 的正则规则
(r"(猜你喜欢|有什么推荐|帮我选|随便看看)", get_recommendations_api)
```

**L2：中断确认 — 破坏性操作的硬闸门**

取消订单、清空购物车、秒杀下单等破坏性操作，在工具函数内部调用 LangGraph 的 `interrupt()` 机制。Agent 执行到此处暂停，前端弹出确认弹窗，用户点了"确认"后 Agent 才从 checkpoint 恢复继续执行。**LLM 无法绕过**——这不是提示词约束，而是框架级的中断机制。

```python
# 工具函数内
def cancel_order_api(order_id):
    confirm = interrupt("确认取消订单吗？")  # Agent 在此暂停
    if confirm:
        # 用户点了确认后才执行
        return cancel_order(order_id)
```

**L3：LLM 推理 — 复杂多工具编排**

只有真正复杂的、需要多步推理的问题才走到 LLM ReAct 循环。`PermissionMiddleware` 在此层从工具列表里直接裁掉危险工具——AdminAgent 的工具列表里根本没有 `do_seckill_api`，LLM 想调用也无从调用。

#### 为什么这很难

Agent 开发的难点不在于"让 LLM 能做什么"，而在于"让 LLM 不能做什么"。纯提示词约束不可靠，纯规则路由又不够智能。三级路由 + 中间件链的设计本质上是**在智能与可控之间画了一条精确的界限**——高频固定的归正则、危险的归中断、复杂的归 LLM。中间件的顺序决定了优先级：正则在前、中断在中间、LLM 在最后，每一层都是硬约束。

#### 核心思考

> "做 Agent 最怕的不是 LLM 不够聪明，而是 LLM 太'聪明'了——它可能自作主张取消用户订单、在 AdminAgent 里调用秒杀接口。纯靠提示词约束是脆弱的，因为你不能枚举所有不该做的事。三级路由的设计哲学是：能用规则就不用 LLM，能用中断就强停，LLM 只在真正需要推理的时候介入。中间件链的顺序保证了这个优先级，不是写在文档里的建议，而是代码里的强行约束。"

---

### 维度三：分布式数据一致性 — 一套体系而非一个方案

#### 问题本质

hmall 有 9 个微服务、9 个独立数据库。一个下单流程涉及 3 个服务 3 个数据库：trade-service 建订单、item-service 扣库存、user-service 扣余额。三个写操作分布在三台独立机器上，任何一个失败都需要全部回滚。

同时还有另一类场景：下单后需要发 MQ 清空购物车——这是"数据库写 + 消息发送"的跨异构系统操作。

**两类操作的一致性需求不同：跨 DB 操作需要强一致（同步），跨 DB+MQ 操作可以做最终一致（异步）。**

#### 解决思路（分层处理，择其善者而从之）

**强一致场景 — Seata AT 模式**

下单流程这种跨微服务 DB 操作，用 Seata 的 AT（Automatic Transaction）模式。一个 `@GlobalTransactional` 注解包裹三个操作，Seata 自动拦截 SQL 生成 `before_image` 和 `after_image` 存入 `undo_log` 表。一阶段各服务正常提交释放锁，二阶段如果全部成功就删 undo_log，如果有失败就根据 before_image 反向回滚。

```java
@GlobalTransactional
public Long createOrder(OrderFormDTO form) {
    itemClient.deductStock(form.getItems());    // 扣库存
    userClient.deductBalance(userId, total);    // 扣余额
    orderMapper.insert(order);                  // 建订单
    // 任一失败 → Seata 自动按 undo_log 回滚
}
```

**最终一致场景 — 本地消息表（事务发件箱）**

下单成功 → 清空购物车这种跨 DB+MQ 场景，不适合用强一致（MQ 回滚不了）。用本地消息表：建订单的同时，把"清空购物车"这条 MQ 消息作为数据库的一行记录，和订单 INSERT 放同一事务。事务提交后，定时任务扫描 `t_local_message` 表逐条发 MQ。重试 5 次仍失败标记 `status=2`，走人工补偿。

**缓存一致性场景 — afterCommit + 重试**

管理后台删秒杀活动时，DB 里要级联删活动、场次、商品关联，Redis 里要清对应的库存缓存。Redis 清除操作放在 `TransactionSynchronization.afterCommit()` 钩子里——如果 DB 事务回滚了，不会误删缓存。Redis 操作失败也不直接忽略，走 MQ 重试 3 次 + DB 标记 `pending_clean` + 告警。

```java
@Transactional
public void deleteActivity(Long id) {
    // 逐级删除：商品关联 → 场次 → 活动
    // ...
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                clearActivityCache(id, sessions);  // 事务成功后清缓存
            }
        }
    );
}
```

#### 为什么这很难

分布式一致性是微服务架构中最容易"说得头头是道，实际全面踩坑"的领域。难在两点：

1. **不能一个方案打天下**：强一致（Seata）有性能代价，最终一致（本地消息表）有窗口期不一致风险。需要对业务场景做判断——哪些操作"必须马上对"，哪些可以"最终对"。
2. **一致性是系统性保障，不是局部的**：从 Seata → 本地消息表 → afterCommit 缓存清除 → Sentinel 熔断降级，它们形成了完整的一致性防护网。缺少任何一环，就会有某个角落出现数据不一致。

#### 核心思考

> "一致性设计最难的地方不是用哪个工具，而是知道什么时候该用强一致、什么时候该用最终一致。下单中间步骤必须强一致——库存扣了但订单没建，用户钱没了货也没了，这是事故。但下单后的清空购物车可以做最终一致——晚几秒清不影响用户体验。afterCommit 清缓存的思路是'先保证 DB 正确，再最大努力保证缓存正确'——缓存错了可以修，DB 错了要赔钱。"

---

### 面试回答策略建议

| 面试时长 | 建议选择 | 展示要点 |
|---------|---------|---------|
| **3 分钟** | 只讲维度一（秒杀） | Lua 原子 → MQ 削峰 → 本地消息表兜底，三层递进逻辑最清晰 |
| **5 分钟** | 维度一 + 维度二 | 先讲秒杀展示分布式功底，再讲 Agent 展示 AI 工程化能力 |
| **10 分钟** | 三维度全讲 | 秒杀（性能）→ Agent（智能化）→ 一致性（系统性），形成完整叙事 |
| **追问"一致性"** | 专门展开维度三 | 强调"分层处理、择其善者而从之"的设计哲学，不是一刀切 |

**回答时的关键技巧**：

1. **先讲"为什么难"**：不要上来就说方案，先说清楚问题本质和矛盾的不可调和性（如秒杀的"库存准确 vs 高并发"天然矛盾）
2. **用"如果不这样做会怎样"来反衬方案价值**：如"如果不加 Lua 脚本，两个请求同时读到库存=1，一起扣库存就超卖了"
3. **展现"系统性思维"**：不是孤立解决一个问题，而是一套组合拳（Lua + MQ + 本地消息表形成完整链路）
4. **适当提及"踩坑"**：如"最开始考虑用分布式锁，后来发现 Lua 脚本从模型层面规避了锁的复杂性"——显得你真的做过

---

## 21. Agent 用户画像构建与记忆机制

### 设计动机

电商 Agent 最大的挑战不是"能不能查到商品"，而是"知不知道用户是谁"——用户说"帮我推荐一款耳机"，如果 Agent 不知道用户之前买了什么、预算多少、偏好什么品牌，就只能给一个通用的热销列表。这和一个真正懂你的导购差距太大了。

更深层的问题：用户今天说"想要一个运动耳机但再考虑考虑"，三天后再来，Agent 如果不记得这句话，就得从头问起——用户体验非常割裂。

**总结核心矛盾**：
| 矛盾 | 表现 |
|------|------|
| **冷启动** | 新用户没有任何行为数据，推荐缺乏依据 |
| **跨服务数据孤岛** | 用户数据散落在 5 个微服务的独立数据库中（浏览、加购、购买、搜索、收藏），Agent 无法直接查询 |
| **跨对话记忆** | LLM 本身无状态，上次对话中用户表达的意图下次对话就忘了 |
| **性能 vs 精准** | 实时聚合全量用户行为数据需要多次 Feign 调用（2-3 秒），而推荐需要毫秒级响应 |

### 实现方案

hmall 构建了一套**三层记忆/画像体系**，由 Agent（Python）和后端微服务（Java）协同运作，共享同一份 Redis 数据：

```
                      ┌────────────────────────────────────────┐
                      │           Agent 服务 (Python)            │
                      │                                         │
                      │  analyze_user_preferences()              │
                      │    ├─ [优先] profile_store.get_profile() │
                      │    │   → Redis Hash 画像 (<5ms)          │
                      │    └─ [降级] 实时聚合 Gateway 调用        │
                      │        → backfill_profile() 回写 Redis   │
                      │                                         │
                      │  get_memories() / save_memory()          │
                      │    → LangGraph Store 语义记忆            │
                      └──────────────┬─────────────────────────┘
                                     │ 共享 Redis (db=0)
                      ┌──────────────┴─────────────────────────┐
                      │         后端微服务 (Java)                │
                      │                                         │
                      │  CartServiceImpl.writeCartProfile()      │
                      │    → HINCRBY profile:{uid}:categories 3  │
                      │                                         │
                      │  paySuccessListener.writePurchaseProfile │
                      │    → HINCRBY profile:{uid}:categories 5  │
                      └─────────────────────────────────────────┘
```

**Redis 画像 Key 设计（Java 和 Python 共享，db=0）**：

| Redis Key | 类型 | 内容 | TTL |
|-----------|------|------|-----|
| `profile:{userId}:categories` | Hash | `{类目名: 累计得分}` | 30 天 |
| `profile:{userId}:brands` | Hash | `{品牌名: 累计得分}` | 30 天 |
| `profile:{userId}:prices` | List | 最近购买价格列表（最多 20 条） | 30 天 |
| `profile:{userId}:stats` | Hash | `{purchase_count, cart_count, last_update}` | 30 天 |
| `profile:{userId}:events` | List | 行为事件 JSON 流（最近 50 条） | 7 天 |

#### Layer 1：行为事件流 — 可回溯的原始日志

每次用户行为（加购、购买）以 JSON 事件形式 `LPUSH` 到 `profile:{userId}:events`，保留最近 50 条。

```json
{"type": "cart", "item_id": 123, "category": "手机", "brand": "Apple",
 "price": 699900, "weight": 3, "ts": "2026-07-30T10:00:00"}
{"type": "purchase", "item_id": 456, "category": "耳机", "brand": "Sony",
 "price": 129900, "weight": 5, "ts": "2026-07-29T15:30:00"}
```

**价值**：当需要更深度的偏好分析时（如"用户最近一周的购买节奏"），Layer 2 的聚合分数不够，可以回溯源事件重新按时间衰减加权。

#### Layer 2：聚合画像 — 毫秒级偏好查询

后端 Java 在关键行为点**实时增量更新**画像得分，权重体系：

| 行为 | 触发点 | 权重 | 写入方式 |
|------|--------|:----:|---------|
| **purchase（购买）** | trade-service `paySuccessListener` | **5** | `HINCRBY profile:{uid}:categories {cat} 5×num` |
| **cart（加购）** | cart-service `CartServiceImpl.writeCartProfile()` | **3** | `HINCRBY profile:{uid}:categories {cat} 3` |
| **view（浏览）** | Agent 侧 `profile_store.record_event()` | **1** | 预留（当前未大规模启用） |

**加购画像写入**（`CartServiceImpl.writeCartProfile()`，行 315-367）：

```java
// 1. Feign 查询商品信息
List<ItemDTO> items = itemClient.queryItemsByIds(form.getItemIds());

// 2. Redis Pipeline 批量原子写入
stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (ItemDTO item : items) {
        // HINCRBY 原子增量 — 无需分布式锁
        connection.hIncrBy(categoryKey, item.getCategory(), 3);
        connection.hIncrBy(brandKey, item.getBrand(), 3);
    }
    connection.lPush(priceKey, item.getPrice().toString());
    connection.lTrim(priceKey, 0, 19);  // 保留最近 20 条
    connection.expire(categoryKey, Duration.ofDays(30));
    // ...
    return null;
});

// 3. 失败静默 — 不阻塞加购主流程
```

**购买画像写入**（`paySuccessListener.writePurchaseProfile()`, 行 88-167）：

```java
// 监听 RabbitMQ pay.success → 查订单详情 → Feign 查商品 → HINCRBY ×5
// 多商品场景：每个商品分别 HINCRBY，累加购买数量
```

**画像读取**（Agent 侧 `analyze_user_preferences`，`tools.py` 行 652-756）：

```python
# Phase 2 优先：读 Redis 画像 (<5ms, 0 次网络调用)
profile = await profile_store.get_profile(user_id)
if profile and profile.get("categories"):
    top_categories = sorted(profile["categories"].items(),
                           key=lambda x: x[1], reverse=True)[:3]
    return top_categories

# Phase 1 降级：实时计算 + 回写
orders = await http_client.get(f"{gateway}/orders/page")    # Feign
cart = await http_client.get(f"{gateway}/carts")            # Feign
items = await http_client.get(f"{gateway}/items?ids=...")   # Feign
prefs = _accumulate_preference(orders, cart, items)         # purchase×5, cart×3
await profile_store.backfill_profile(user_id, prefs)        # 回写 Redis
```

#### Layer 3：语义记忆 — 跨对话上下文

与 Layer 1/2 的数值型画像不同，Layer 3 存储**自然语言记忆**——"用户说过什么"。

两个工具通过 `profile/memory.py` 暴露给 LLM，使用 **LangGraph Store API** 操作：

```python
# save_memory — 当用户表达购物意图但未完成
# 例：用户说"想买手机但再考虑考虑"
await store.aput(
    ("user_memory", user_id),           # namespace
    f"shopping_intent_{timestamp}",     # key
    {"content": "正在挑选手机，预算约5000元，偏好品牌Apple", "ts": timestamp}
)

# get_memories — 每次对话开始时 LLM 自动调用
memories = []
async for item in store.asearch(("user_memory", user_id)):
    memories.append(item.value)
# → [{"content": "正在挑选手机，预算约5000元，偏好品牌Apple", "ts": "..."}]
```

**持久化方式**：
- 当前使用 `InMemoryStore`（开发环境轻量方案）
- 可无缝升级为 PostgreSQL 或 Redis 持久化后端——LangGraph Store 接口不变

**系统提示词中的使用规范**（`prompts.py`）：

```
每次对话开始时，自动调用 get_memories 读取历史记忆，
在首次回复中自然融入（如"欢迎回来！上次您在看手机类商品，
今天新到了一些热门款"）。

当用户明确表达购物意图但未完成时，调用 save_memory 保存意图。
不要生硬复述记忆内容，要自然地融入对话。
```

#### 完整数据流：从用户行为到 Agent 知识

```
用户行为              写入者                     Redis 层               Agent 使用
────────              ────                      ──────                 ────────
浏览商品 (前端)        [预留: Agent 侧]           events [行为流]        [暂未大规模使用]
加购 (前端/对话)       CartServiceImpl            categories [Hash]     analyze_user_preferences()
                      .writeCartProfile()        brands [Hash]         RecommendServiceImpl
                      (Pipeline HINCRBY ×3)     prices [List]         .recommend()
                                                stats [Hash]          (优先读画像 → 0次Feign)
                                                
下单支付 (前端)        paySuccessListener         categories [Hash]     analyze_user_preferences()
                      .writePurchaseProfile()    brands [Hash]         RecommendServiceImpl
                      (Pipeline HINCRBY ×5)     prices [List]         .recommend()
                                                stats [Hash]          
                                                
对话中的意图           save_memory 工具           LangGraph Store        get_memories 工具
("想买手机再看看")     (namespace=user_memory)    (InMemoryStore)       (下次对话自动读取)
```

### 设计亮点

**1. 画像与业务完全解耦 — 写入失败不阻塞主流程**

画像写入在 `try-catch` 中静默执行。加购时 Redis 不可用？加购照样成功，仅 `log.warn`。支付回调时 Redis 挂了？订单照样创建，画像稍后补。**用户永远不会因为"画像系统故障"而加不了购物车或付不了款**。

```java
// CartServiceImpl.writeCartProfile() — 静默失败
try {
    stringRedisTemplate.executePipelined(...);
} catch (Exception e) {
    log.warn("Failed to write cart profile for user {},不影响加购流程", userId, e);
    // 不抛异常，不阻塞加购
}
```

**2. Java 和 Python 共享 Redis 画像 — 跨语言零翻译开销**

后端 Java 用 `StringRedisTemplate` 写（纯字符串，无 Jackson 序列化歧义），Agent Python 用 `redis.asyncio` 读——同一个 `profile:123:categories`，`HGETALL` 返回 `{"手机":"25","耳机":"9"}`，两边解析零差异。没有 gRPC 定义、没有中间 API 层、没有额外的序列化开销。

**3. 画像优先、实时降级、自动回写 — 冷启动平滑过渡**

新用户第一次对话时 Redis 画像为空 → Agent 自动降级到实时聚合（3 次 Gateway 调用，2-3 秒）→ 聚合完成后 `backfill_profile()` 回写 Redis → 下次对话直接命中（<5ms）。用户无感知，Agent 自己完成了从冷到热的过渡。

**4. 三层记忆互补 — 各司其职**

| 层级 | 存储 | 查询延迟 | 适用场景 |
|------|------|:---:|------|
| Layer 1 行为流 | Redis List | <1ms | 最近行为回溯、时序分析 |
| Layer 2 聚合画像 | Redis Hash | <1ms | 类目/品牌/价格偏好查询 |
| Layer 3 语义记忆 | LangGraph Store | <5ms | 跨对话意图延续 |

Layer 2 只能告诉你"用户喜欢手机类目"，但不知道用户"想要 iPhone 但嫌贵"。Layer 3 记得这句话——多层互补，形成完整的"用户认知"。

**5. `HINCRBY` 原子增量 — 无锁并发写入**

Java 侧多个行为事件可能并发写入（用户同时加购商品 A 和商品 B），Python Agent 降级回写也可能与 Java 同时发生。`HINCRBY` 是 Redis 原子命令，天然保证 `读-改-写` 的并发安全，不需要分布式锁。

```bash
# 两个并发请求同时 HINCRBY
HINCRBY profile:123:categories "手机" 3    # → 手机: 3
HINCRBY profile:123:categories "手机" 5    # → 手机: 8  (原子累加，无覆盖)
```

### 对比分析

| 方案 | 跨服务画像 | 跨语言共享 | 写失败影响 | 跨对话记忆 | 冷启动 |
|------|:---:|:---:|:---:|:---:|:---:|
| 各服务独立本地缓存 | ❌ 数据孤岛 | — | 无 | ❌ | ❌ |
| 统一画像微服务（gRPC） | ✅ | ✅（需定义 proto） | ⚠️ 服务不可用影响 | ❌ | ⚠️ |
| 纯 LLM 记忆（系统提示词注入） | ❌ 无法聚合行为数据 | — | — | ⚠️ 上下文窗口有限 | ❌ |
| **Redis 共享 + 三层记忆（hmall）** | **✅ Java/Python 直连同一 Redis** | **✅ String 序列化零歧义** | **✅ 静默失败不阻塞** | **✅ LangGraph Store** | **✅ 降级回写** |

### 面试展示要点

> "Agent 最怕的是不知道自己服务的用户是谁。我们构建了一个三层记忆体系：Layer 1 行为事件流在 Redis List 里——每次加购、购买都 LPUSH 一条 JSON，保留最近 50 条，可以做时序分析。Layer 2 聚合画像在 Redis Hash 里——后端 Java 在加购和支付成功时做 HINCRBY 增量更新，类目得分×3、购买得分×5，Agent 读取时 <5ms 拿到用户偏好 Top3。Layer 3 语义记忆在 LangGraph Store 里——用户说'想买手机再看看'，Agent 调 save_memory 记下来，三天后再来自动读取。

> 关键设计有几个：一是'画像与业务解耦'——画像写入失败 try-catch 静默处理，不影响加购和支付主流程。二是'跨语言零翻译'——Java 用 StringRedisTemplate 写字符串，Python 用 redis.asyncio 读，同一个 Hash 两边解析零歧义。三是'画像优先、实时降级、自动回写'——首次对话 Redis 画像为空时自动降级到实时计算（3 次 Gateway 调用），算完后把结果 HSET 回 Redis，下次直接命中。整个冷启动过程对用户完全透明。"

---

*本文档基于 hmall 代码库实际审查整理。架构设计亮点包括：Agent 三级路由降低 LLM 调用成本、双 JWT 认证权限隔离、Redis + RabbitMQ 秒杀削峰、中间件链关注点分离、LightRAG + MCP 三层桥接、Gateway 统一认证限流、ES + LLM 协同推荐、Agent 三层画像记忆体系（行为流 + 聚合画像 + 语义记忆）、本地消息表分布式最终一致性、Seata 跨服务强一致性、Feign 用户上下文自动透传、Sentinel 熔断保护、Nacos 动态路由零停机热更新、RabbitMQ 延迟消息订单超时取消、RBAC 三层动态权限控制、级联管理 DB 事务 + Redis 缓存同步清除。*
