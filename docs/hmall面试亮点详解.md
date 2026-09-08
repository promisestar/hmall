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
8. [个性化推荐：画像 + ES 召回 + 标签理由](#8-个性化推荐画像--es-召回--标签理由)
9. [本地消息表 + 定时重发：分布式最终一致性](#9-本地消息表--定时重发分布式最终一致性)
10. [Seata 全局事务：跨服务强一致性](#10-seata-全局事务跨服务强一致性)
11. [Long → String 序列化：防 JS 精度丢失](#11-long--string-序列化防-js-精度丢失)
12. [Feign 自动传递用户上下文](#12-feign-自动传递用户上下文)
13. [Sentinel 降级熔断](#13-sentinel-降级熔断)
14. [Nacos 动态路由热更新](#14-nacos-动态路由热更新)
15. [RabbitMQ 延迟消息：订单超时取消](#15-rabbitmq-延迟消息订单超时取消)
16. [RBAC 动态权限：三层权限控制体系](#16-rbac-动态权限三层权限控制体系)
17. [级联管理：DB 事务级联删除 + Redis 缓存清除](#17-级联管理db-事务级联删除--redis-缓存清除)
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
              │ ┌────▼─────┐ │ │ │  │   (22 tools) │ │
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
         │  │ 多库)  │  │ +限流+ │  │ RAG 知识库    │ │
         │  │        │  │ 画像)  │  │              │ │
         │  └────────┘  └────────┘  └──────────────┘ │
         └────────────────────────────────────────────┘
```

**Java 微服务矩阵**：

| 服务 | 端口 | 数据库 | 职责 |
|------|:---:|------|------|
| **hm-gateway** | 8080 | — | API 网关：JWT 认证、滑动窗口限流（Redis ZSET + Lua） |
| **hm-service** | — | `hmall` | 单体/聚合参考模块（与网关分离部署时使用） |
| **item-service** | 8081 | `hm_item` | 商品微服务：CRUD、库存、个性化推荐编排 |
| **cart-service** | 8082 | `hm_cart` | 购物车微服务 |
| **pay-service** | 8083 | `hm_pay` | 支付微服务 |
| **user-service** | 8084 | `hm_user` | 用户微服务：登录、余额、地址 |
| **trade-service** | 8085 | `hm_trade` | 交易微服务 + 秒杀核心引擎 |
| **search-service** | 8089 | —（ES） | 搜索微服务：Elasticsearch 全文检索与推荐召回 |
| **admin-service** | 8091 | `hm_admin` | 管理后台微服务：RBAC 权限体系 |

**Agent 服务矩阵**：

| 服务 | 端口 | 技术栈 | 职责 |
|------|:---:|------|------|
| **Agent Server** | 8090 | LangGraph Server + DeepAgents | 双 Agent 运行时（Customer + Admin） |
| **RAG MCP Server** | 8008 | FastMCP | LightRAG API → MCP 工具桥接 |
| **LightRAG Server** | 9621 | LightRAG (HKU) | 知识图谱 + 向量检索引擎 |
| **LLM** | 云端 / 本地 | DashScope 或 OpenAI 兼容（如 vLLM） | 默认通义千问；`.env` 可切本地模型 |

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

Agent 的 ReAct 循环中，LLM 每一轮调用通常伴随 1–3 秒延迟与可观的 token 成本。若用户只是发出「运营日报」「猜你喜欢」「查看购物车」这类意图清晰、映射稳定的指令，仍完整走「LLM 推理 → 工具选择 → 结果格式化」链路，既不经济也不够快。

另一方面，取消订单、秒杀下单、清空购物车、确认收货等操作具有破坏性；新增/修改地址则需要多轮字段收集。若由 LLM 在工具调用中直接执行，用户缺少显式确认或补全入口，风险不可接受。因此需要一套**不依赖 LLM「自觉」**的分层路由：高频只读走规则，危险写操作走框架级中断，复杂编排才交给模型。

### 实现方案

CustomerAgent 与 AdminAgent 均采用同一套三级路由语义，优先级由中间件链与工具内部逻辑共同保证：

```
用户消息
  │
  ├─ L1 正则快捷路由（RegexShortcutMiddleware）
  │   │ 在 wrap_model_call / awrap_model_call 中拦截
  │   │ 命中只读规则 → 直接 invoke 工具，返回 AIMessage（跳过 LLM，目标延迟 <5ms）
  │   │ 未命中 → 交给后续中间件 / LLM
  │   │
  │   ├─ AdminAgent：运营日报、商品列表、订单列表、秒杀活动列表
  │   └─ CustomerAgent：秒杀查询、购物车、订单、地址、推荐、搜索、商品列表等只读指令
  │
  ├─ L2 中断确认 / 多轮收集（LangGraph interrupt，落在工具函数内部）
  │   │ 写操作执行前 interrupt → 前端 InterruptActions 弹窗 → resume 后继续
  │   │
  │   ├─ 确认类：cancel_order_api / delete_cart_item_api / clear_cart_api
  │   │         confirm_receive_api / do_seckill_api
  │   └─ 多轮收集：add_address_api / update_address_api
  │
  └─ L3 LLM 推理（DeepAgent ReAct）
       │ 模糊意图、多工具编排、自由对话
       │ 进入 LLM 前仍经过：Auth → Permission → Regex → RAG → Skills
```

**L1 正则规则**定义在各 Agent 的 `regex_rules.py`，结构为 `(pattern, tool_name, extractor)`：

```python
# AdminAgent（节选）
REGEX_RULES = [
    (r"(?:运营|生成|帮我做).{0,3}日报", "generate_daily_report", None),
    (r"(?:查看|查询|商品).{0,3}列表", "admin_get_product_page_api", None),
    (r"(?:查看|查询).{0,5}订单", "admin_get_order_page_api", None),
    (r"(?:秒杀|查看).{0,3}活动", "admin_get_seckill_promotion_page_api", None),
]

# CustomerAgent（节选）——仅只读；写操作故意不进 L1，交给 L2 interrupt
REGEX_RULES = [
    (r"(?:查看|查询|当前).{0,3}秒杀", "get_seckill_activities_api", None),
    (r"(?:查看|查询|我的).{0,5}购物车", "get_cart_list_api", None),
    (r"(?:推荐|猜你喜欢|有什么好|帮我选|随便看看|给我推荐)",
     "get_recommendations_api", _extract_recommend_scene),
    (r"(?:搜索|查找|找)\s*(.+)", "search_items_api", _extract_keyword),
    # 另有订单列表/详情、地址列表、商品分页等
]
```

`RegexShortcutMiddleware` 取对话中最后一条 human 消息做 `re.search`，命中则从 `tool_registry` 取出对应工具并 `invoke`；写操作相关短语（取消订单、清空购物车、秒杀下单等）明确不在规则表中，避免绕过二次确认。

**L2 中断确认**：工具内部调用 `interrupt({type, message, expected_response, ...})`，执行流在 LangGraph checkpoint 处暂停。前端 `InterruptActions.vue` 渲染确认/取消或字段表单；用户提交后通过 `stream.submit(null, { command: { resume: value } })` 恢复。例如秒杀工具会先拉商品详情展示价格与库存，再 interrupt 等待用户回复「确认」后才 `POST /seckill/order/{relationId}`。

### 设计亮点

**1. 成本与时延分层**：最高频、意图稳定的只读指令在 L1 完成，延迟从秒级降到毫秒级，且零 token；复杂问题才进入 L3，避免「一刀切」全部走模型。

**2. 安全边界下沉到框架**：破坏性操作与地址多轮收集的闸门写在工具代码里，依赖 LangGraph `interrupt`，而不是系统提示词里的「请先询问用户」——提示词可被模型忽略，checkpoint 中断不能被模型绕过。

**3. 读写分离的规则设计**：L1 只注册只读工具；写操作即使语言模型想「快捷执行」，也没有正则捷径，必须走带 interrupt 的工具路径。

**4. 中间件顺序保证优先级**：完整链为 Auth → Permission → Regex → RAG → Skills。正则位于认证/权限之后、RAG/Skills 与 LLM 之前——既保证身份与工具集正确，又能在调用模型前短路。

### 对比分析

| 方案 | 简单问题延迟 | 破坏性操作保护 | Token 消耗 | 可扩展性 |
|------|:---:|:---:|:---:|:---:|
| 全部走 LLM ReAct | 2–3s | 依赖模型判断（不可靠） | 高 | 高 |
| 全部规则路由 | <1s | 可强制 | 零 | 低（难覆盖模糊意图） |
| **三级路由（hmall）** | **L1 命中毫秒级 / L3 秒级** | **✅ 框架级 interrupt** | **按需** | **规则 + 模型互补** |

### 面试展示要点

> 「我们没有让 LLM 处理所有请求。运营日报、查看购物车这类高频只读指令由 RegexShortcutMiddleware 在调用模型前直接命中工具，延迟和 token 都能省下来。取消订单、秒杀、清车等写操作在工具里调用 LangGraph interrupt，前端弹窗确认后才从 checkpoint resume——这不是提示词约束，模型绕不过去。规则表刻意只放只读工具，写操作没有 L1 捷径。中间件顺序是 Auth → Permission → Regex → RAG → Skills，正则短路发生在 LLM 之前，权限过滤保证 Admin 场景看不到写工具。」

---

## 3. 双 JWT 认证体系

### 设计动机

hmall 同时服务两类身份截然不同的使用者：

- **C 端消费者**：经 portal 登录（`POST /users/login`），JWT 由 `hmall.jks`（RSA）签发；
- **B 端运营者**：经 admin 登录（`POST /admin/login`），JWT 由独立的 `admin.jks` 签发。

二者权限模型、密钥生命周期与可调用 API 面均不同。若共用单一 JWT，一旦 Token 泄露或角色字段被篡改，横向越权风险极高。同时，Agent 服务会代替用户调用 Java Gateway：必须把用户 JWT 完整透传到下游，并在 Agent 侧再做一层「能看见哪些工具」的约束，否则 LLM 仍可能尝试调用危险写工具。

### 实现方案

```
前端 ChatPanel / AdminChat
  │ sessionStorage 分 key 存放 user_token / admin_token
  │ LangGraph SDK context 注入：user_token + agent_type（customer|admin）
  ▼
Agent Server
  │ AuthMiddleware（awrap_model_call）
  │   ├─ 无 token：放行（仅适合只读浏览；写工具内部仍会因缺 token 失败）
  │   ├─ 有 token：按 agent_type 选择密钥体系
  │   │     JWT_VERIFY_LOCAL=true  → verify_jwt 验签，写入 context.user_id
  │   │     JWT_VERIFY_LOCAL=false → 不本地验签，依赖 Gateway（默认）
  │   └─ 注意：中间件不解析 JWT 内 role 字段；身份边界由 agent_type + 双 jks 保证
  │
  │ PermissionMiddleware（awrap_model_call）
  │   └─ agent_type == "admin" 时，从 request.tools 剔除 WRITE_TOOLS
  │
  │ 工具执行：extract_token_from_config（三层 fallback）
  │   configurable.user_token → configurable.context.user_token → runtime.context.user_token
  │   → Authorization: Bearer 调 Gateway
  ▼
Gateway AuthGlobalFilter
  │ 按路径选择 C 端 / 管理端密钥校验 Authorization
  │ 成功后写入下游头：user-info = userId
  ▼
Java 微服务
  过滤器/拦截器解析 user-info → UserContext（ThreadLocal）
```

**PermissionMiddleware 写工具黑名单**（`src/middleware/permission.py`）：

```python
WRITE_TOOLS = {
    "add_to_cart_api", "update_cart_quantity_api",
    "delete_cart_item_api", "clear_cart_api",
    "cancel_order_api", "confirm_receive_api",
    "add_address_api", "update_address_api",
    "do_seckill_api",
}
# 仅当 agent_type == "admin" 时过滤；CustomerAgent 保留全量工具，
# 写操作另需有效 Token + 工具内 interrupt。
```

AdminAgent 在注册阶段本身就只挂载只读查询工具 + `generate_daily_report`，PermissionMiddleware 是防御纵深：防止 RAG 动态注入或其他路径意外带入写工具后被模型选中。

### 设计亮点

**1. 双密钥从签发源头隔离**：C/B 端 Token 使用不同 `.jks`，Gateway 按路径选密钥；拿到 C 端 Token 无法伪造管理端请求。

**2. Agent 侧「可见即可用」裁剪**：权限控制下沉到工具列表，而不是仅在提示词中写「管理员不要下单」。Admin 场景下 LLM 的 tool schema 里根本没有秒杀/取消订单等入口。

**3. Token 透传与可配置验签**：`JWT_VERIFY_LOCAL` 允许 Agent 在本地验签或完全信任 Gateway。生产环境可减少 Agent 进程对密钥文件的依赖；开发环境可打开本地验签便于排查。

**4. 三层 fallback 取 Token**：兼容 LangGraph SDK、中间件注入、DeepAgents runtime 等不同调用路径，避免「前端明明传了 token、工具却读不到」的脆弱点。

### 对比分析

| 方案 | C/B 端隔离 | 控制层级 | 工具层保护 | 运维复杂度 |
|------|:---:|:---:|:---:|:---:|
| 单一 JWT + 前端判断 | 弱 | 仅前端 | ❌ | 低 |
| 双 JWT + Gateway | ✅ | Gateway | ❌（模型仍可见写工具） | 中 |
| **双 JWT + Gateway + Agent 中间件（hmall）** | **✅** | **Gateway + Agent** | **✅ 列表级过滤** | **中（可配置验签）** |

### 面试展示要点

> 「认证不是只做在 Gateway。前端把 token 和 agent_type 放进 LangGraph context；AuthMiddleware 负责透传，并可按配置本地验签写入 user_id——我们并不依赖 JWT 里的 role 字段，C/B 隔离靠独立 jks 和 agent_type。PermissionMiddleware 在 admin 场景直接从 tools 里拿掉写操作；AdminAgent 注册时本身也没有这些工具，这是双保险。工具调 Gateway 时用 extract_token_from_config 做三层 fallback，再由 Gateway 验签并写入 user-info。整条链是：网关拦非法 Token、中间件裁工具集、工具内再校验登录与 interrupt。」

---

## 4. 秒杀核心：Redis + RabbitMQ 异步削峰

### 设计动机

秒杀是典型的「瞬时尖峰流量 vs 有限库存」问题。若同步路径直接对 MySQL 做扣库存 + 建单，连接池与行锁会迅速成为瓶颈，轻则超时雪崩，重则超卖。工程上需要同时满足：

1. **高吞吐**：用户请求尽快得到受理反馈；
2. **不超卖 / 不超限购**：预减与限购必须原子；
3. **最终正确**：异步落库失败可补偿，前端能获知真实终态。

hmall 采用「Redis Lua 前置预减 + RabbitMQ 异步建单 + MySQL 行锁兜底 + Redis 结果轮询」的分层方案。

### 实现方案（三层防超卖）

```
用户秒杀请求
  │
  ▼
trade-service SeckillServiceImpl.doSeckill
  │
  ├─① per-user 分布式锁（seckill:lock:user:{userId}）
  │     防同一用户短时间重复提交
  │
  ├─② Redis Lua 原子预减（seckill_deduct.lua）
  │     KEYS[1] = seckill:stock:{relationId}   # String 剩余库存
  │     KEYS[2] = seckill:limit:{relationId}   # Hash  userId → 已购数量
  │     ARGV   = userId, quantity, limitNum
  │     返回：1 成功 / 0 售罄 / -1 未预热 / -2 超限购
  │
  ├─③ 预减成功 → 发送 MQ（seckill.order）
  │     发送失败 → 立刻回补 stock + 回滚 limit，返回系统繁忙
  │
  └─④ HTTP 立即返回 SeckillResultVO.pending()（排队中）
        │ 注意：同步路径不创建订单
        ▼
SeckillOrderListener（第三层：MySQL 最终扣减）
  ├─ SELECT ... FOR UPDATE 锁定当日 seckill_daily_stock
  ├─ 库存不足 / 并发扣减失败
  │     → rollbackRedis（INCRBY stock + HINCRBY limit 回退）
  │     → setResult(userId, relationId, "0")
  ├─ 成功：写 order + order_detail + seckill_order
  ├─ 发送 30 分钟延迟取消消息（delayed exchange，复用订单超时机制）
  └─ setResult(userId, relationId, orderId)   # TTL 120s
        │
        ▼
前端 / Agent 轮询 GET /seckill/result/{relationId}
  key 不存在 → pending
  value == "0" → failed（已售罄等）
  value 为数字 → success(orderId)
```

**Lua 核心逻辑**（`hm-common/src/main/resources/lua/seckill_deduct.lua`）：

```lua
-- 库存未初始化（Key 不存在）→ -1
local stock = redis.call('GET', KEYS[1])
if stock == false then return -1 end
stock = tonumber(stock)

-- 限购：已购 + 本次 > limitNum → -2
local purchased = redis.call('HGET', KEYS[2], ARGV[1])
purchased = (purchased == false) and 0 or tonumber(purchased)
local quantity = tonumber(ARGV[2])
local limitNum = tonumber(ARGV[3])
if purchased + quantity > limitNum then return -2 end

-- 库存不足 → 0
if stock < quantity then return 0 end

-- 原子：扣库存 + 累加已购
redis.call('DECRBY', KEYS[1], quantity)
redis.call('HINCRBY', KEYS[2], ARGV[1], quantity)
return 1
```

**预热**：`preheat(relationId)` 将活动库存 `SET` 到 Redis（SETNX 避免定时任务覆盖已扣减库存），并初始化当日 `seckill_daily_stock` 快照，供消费者行锁扣减。

### 设计亮点

**1. 单脚本完成库存与限购**：限购不是「买过就不能买」的粗粒度 SET 去重，而是 Hash 计数，天然支持 `limitNum > 1`；与库存扣减同脚本执行，杜绝「库存够但限购竞态」或反向问题。

**2. 同步路径极短**：用户线程只做锁 + Lua + 发 MQ，立刻返回 `pending`，把建单压力转移到消费者，实现削峰。

**3. 结果 Key 补齐异步可见性**：异步架构最大的产品问题是「用户不知道最终成没成」。`seckill:result:{userId}:{relationId}` 把终态（订单号或 `"0"`）显式落 Redis，TTL 120 秒，供前端/Agent 轮询——这是异步下单的必备闭环，而非可选项。

**4. Redis 与 MySQL 双层一致**：消费者以 `FOR UPDATE` + 条件更新做最终裁决；失败时主动回补 Redis，避免预减成功但 DB 拒绝后的「幽灵占坑」。

**5. 失败路径可补偿**：MQ 发送失败当场回补；消费失败写 `result=0` 并回补；延迟消息复用统一超时取消通道，未支付订单可释放资源。

### 对比分析

| 方案 | 吞吐 | 超卖/超限购风险 | DB 压力 | 终态可感知性 |
|------|:---:|:---:|:---:|:---:|
| 数据库行级锁同步下单 | 低 | 低 | 极高 | 同步可知 |
| Redis 分布式锁 + 同步 DB | 中 | 中（锁粒度/续约复杂） | 高 | 同步可知 |
| 仅 Redis 预减 + MQ（无 DB 兜底/无结果 Key） | 高 | 中（不一致难感知） | 低 | 差 |
| **Lua + MQ + MySQL 行锁 + result 轮询（hmall）** | **高** | **极低** | **低** | **✅ 可轮询** |

### 面试展示要点

> 「秒杀不是简单 DECR。Lua 把库存检查、限购 Hash 累加、扣减合成一次原子操作，支持每人限购多件；接口在预减成功后立刻返回 pending，真正建单在 MQ 消费者里用 MySQL 行锁落地。成功把订单号、失败把 0 写到 seckill:result，前端轮询拿终态。MQ 发送失败或 DB 扣减失败都会回补 Redis，避免库存被永久占住。这是典型的『前置缓冲削峰 + 异步落库 + 可查询终态』三段式。」

---

## 5. Agent 中间件链设计

### 设计动机

一次 Agent 请求在进入 LLM 之前，需要横切地完成多件事：身份透传/可选验签、按 Agent 类型裁剪工具、高频指令短路、按需注入 RAG 工具、加载 Skills 行为规范。若把这些逻辑硬编码进 `create_agent` 或散落在工具函数中，会出现：

- 关注点耦合，难以单测某一层；
- 顺序隐式，容易出现「未鉴权就调工具」或「RAG 先于正则导致浪费」；
- 降级策略不统一（例如 RAG 挂掉拖垮整条对话）。

DeepAgents / LangChain 的 `AgentMiddleware` 提供了声明式链式扩展点，hmall 据此组装一条显式、可替换的中间件链。

### 实现方案

CustomerAgent 与 AdminAgent **共用同一中间件顺序**（见各自 `agent.py`）：

```
AuthMiddleware
  → PermissionMiddleware
  → RegexShortcutMiddleware
  → RAGMiddleware
  → SkillsMiddleware（deepagents.middleware，框架内置）
```

> **重要更正**：代码库中**不存在** `CacheMiddleware`。会话级或模块级缓存（如 `rag_loader` 对 MCP 工具列表的缓存）是实现细节，并非中间件链上的一环。面试描述请以此为准。

**各中间件职责**：

| 中间件 | 源码位置 | 主要钩子 | 职责 |
|------|---------|:---:|------|
| **AuthMiddleware** | `src/middleware/auth.py` | `wrap_model_call` / `awrap_model_call` | 读取 `context.user_token`、`agent_type`；可选本地 JWT 验签并写入 `user_id` |
| **PermissionMiddleware** | `src/middleware/permission.py` | 同上 | `agent_type=admin` 时从 `request.tools` 剔除 `WRITE_TOOLS` |
| **RegexShortcutMiddleware** | `src/middleware/regex_shortcut.py` | 同上 | 正则命中只读指令则直接 invoke 工具并返回，跳过 LLM |
| **RAGMiddleware** | `src/middleware/rag_context.py` | 同上 | `enable_rag=true` 时通过 MCP 加载并注入 RAG 工具；失败则降级放行 |
| **SkillsMiddleware** | DeepAgents 框架 | 框架约定 | 从虚拟文件系统加载 `/skills/*/SKILL.md`，注入行为规范 |

**规模**：CustomerAgent 注册 22 个工具（商品/秒杀/购物车/订单/地址/推荐/偏好 + `save_memory`/`get_memories`）；AdminAgent 11 个工具（10 个只读查询 + `generate_daily_report`）。Skills：Customer 7 个、Admin 3 个（含各自的 `rag-query`）。

**中间件接口形态**（概念上）：

```python
class AgentMiddleware:
    def wrap_model_call(self, request, handler):
        # 同步路径：可修改 request 或短路返回
        return handler(request)

    async def awrap_model_call(self, request, handler):
        # 异步路径：hmall 自定义中间件均实现此入口
        return await handler(request)
```

### 设计亮点

**1. 显式优先级**：认证与权限先于一切业务短路，避免「未登录/错误 Agent 类型」仍命中正则工具；正则先于 RAG，避免知识库检索抢走本可用规则秒回的请求。

**2. RAG 优雅降级**：`RAGMiddleware` 在 MCP Server 不可达或加载失败时记录 warning，并继续 `handler(request)`，Agent 仍可使用业务工具对话——知识库是增强能力，不是单点依赖。

**3. Skills 与代码解耦**：购物引导、秒杀确认话术、日报结构等写在 `SKILL.md`，运营/产品改规范不必改 Python 工具实现。

**4. Admin 只读双保险**：注册期工具集只读 + PermissionMiddleware 运行时过滤，防御动态工具注入带来的权限回潮。

### 对比分析

| 方案 | 横切管理 | 顺序可控 | 降级能力 | 可测试性 |
|------|:---:|:---:|:---:|:---:|
| 硬编码在 Agent 定义中 | ❌ | ❌ | 难统一 | 低 |
| 散落装饰器 | ⚠️ | 易乱 | ⚠️ | 中 |
| **DeepAgent Middleware 链（hmall）** | **✅** | **✅ 声明式有序** | **✅ 逐级（尤其 RAG）** | **高（可单测中间件）** |

### 面试展示要点

> 「Agent 的横切关注点没有堆在一个大函数里，而是组装成中间件链：Auth → Permission → Regex → RAG → Skills。代码里没有 CacheMiddleware，不要按过时文档讲。Auth 负责 token 与可选验签，Permission 在 admin 场景裁掉写工具，Regex 对高频只读指令毫秒级短路，RAG 按前端开关动态注入且 MCP 挂了只降级不堵死，Skills 用 Markdown 规范行为。顺序本身就是产品策略：先保证身份与权限，再省 token，再考虑知识库增强。」

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
  │   │ 解析用户信息写入 Header（user-info）
  │   │ 校验失败 → 401
  │
  ├─ RateLimitFilter（滑动窗口限流）
  │   │ Redis ZSET 实现滑动窗口计数器
  │   │ Lua 脚本原子操作：ZREMRANGEBYSCORE + ZCARD + ZADD + PEXPIRE
  │   │ 超出限制 → HTTP 429
  │
  └─ 路由转发 → 下游微服务
```

**滑动窗口限流 Lua 脚本**：

```lua
-- rate_limit.lua
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])      -- 窗口毫秒
local maxRequests = tonumber(ARGV[3])
local requestId = ARGV[4]            -- UUID member

redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
local count = redis.call('ZCARD', key)
if count < maxRequests then
    redis.call('ZADD', key, now, requestId)
    redis.call('PEXPIRE', key, window + 1000)
    return 1
end
return 0
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

**3. Nacos 动态路由**：路由定义存放在 Nacos `gateway-routes.json`，由 `DynamicRouteLoader` 经 `getConfigAndSignListener` 拉取并写入 `RouteDefinitionWriter`，配置变更即可热更新，无需重启 Gateway（详见第 14 节；**不是** `@RefreshScope`）。

### 对比分析

| 方案 | 实现方式 | 精确度 | 动态调整 |
|------|------|:---:|:---:|
| Gateway 固定窗口 | 内存计数器 | 低（边界问题） | 需重启 |
| Sentinel 限流 | 内置算法 | 中 | ✅ Dashboard 调整 |
| **Redis ZSET 滑动窗口（hmall）** | **Lua 原子** | **高（时间维度精确）** | **规则可配置；路由热更新见 §14** |

### 面试展示要点

> "限流没有用简单的固定窗口——那在两个窗口交界处会瞬间翻倍。我用 Redis ZSET 实现了一个精确的滑动窗口：ZREMRANGEBYSCORE 清理过期记录 → ZCARD 统计窗口内请求数 → ZADD 追加新请求——三步在一个 Lua 脚本里原子执行。路由规则另存在 Nacos 的 gateway-routes.json，由 DynamicRouteLoader 监听变更后全量刷新 RouteDefinition，和限流配置是两条线，都无需重启 Gateway。"

---

## 8. 个性化推荐：画像 + ES 召回 + 标签理由

### 设计动机

电商推荐要同时满足「推得准」和「说得清」。若完全交给 LLM 自由发挥，模型容易编造不存在的商品或过期价格；若只返回冷冰冰的 ID 列表，用户缺少点击动机。hmall 的拆分原则是：

- **召回与排序**交给 Java + Elasticsearch（可实时、可降级、可观测）；
- **可解释展示**用规则标签 + Agent 侧 Markdown 格式化；
- **偏好感知**优先读共享 Redis 画像，避免每次推荐都打穿多个微服务。

需要强调：当前实现中的「推荐理由」主要来自服务端 `generateTags()` 的规则标签与 `basedOn` 摘要，以及 Agent 的表格/引用块渲染——**并不是**为每条商品再单独发起一次 LLM 长文生成。

### 实现方案

```
用户：「有什么推荐？」「帮我凑单」等
  │
  ├─ L1 正则命中 → get_recommendations_api(scene=home|cart|detail)
  │
  ▼
Gateway GET /recommend
  ▼
item-service RecommendServiceImpl
  │
  ├─① 偏好来源
  │    优先：Redis profile:{userId}:categories / brands（与 Agent 共享）
  │    miss ：Feign 拉已购明细 → 聚合类目/品牌 TopN，并排除已购 ID
  │    detail 场景：以种子商品类目为召回条件
  │
  ├─② Feign → search-service /search/recommend（ES 按类目召回）
  │
  ├─③ ES 无结果 → MySQL 按销量热销兜底（isFallback=true）
  │
  └─④ 批量补 stock/status，过滤下架商品
       generateTags(...) 生成 recommendTags
       组装 RecommendVO（list + basedOn）
  ▼
Agent format_recommendations
  → Markdown 表格 + 「推荐依据」引用块（类目/品牌/场景文案）

可选工具 analyze_user_preferences
  ├─ 优先 profile_store.get_profile(user_id)   # <5ms
  └─ miss：并发拉 /orders/page + /carts → 加权聚合 → backfill_profile
```

**场景矩阵**：

| 场景 | 典型触发 | 召回策略 | 可解释信息 |
|------|---------|---------|-----------|
| `home` | L1「猜你喜欢 / 帮我选 / 随便看看」 | 画像 Top 类目 + ES，排除已购 | tags + basedOn 类目/品牌 |
| `detail` | 工具传入 `itemId` | 种子商品同类目 | 「看了又看」类标签 |
| `cart` | L1「凑单 / 购物车推荐」 | 偏好类目召回 | 凑单场景文案 |

### 设计亮点

**1. 职责分离，避免幻觉商品**：候选集永远来自 ES/MySQL 真实库存数据；LLM 不负责「想出」SKU，只在对话层组织话术与后续动作（加购、看详情）。

**2. 画像命中降低扇出**：item-service 与 Agent Python 侧共享同一套 Redis Hash；推荐与偏好分析在热路径上可做到「0 次或极少 Feign」。

**3. 多级降级链路完整**：画像 miss → 实时聚合；ES 空 → MySQL 热销；推荐 HTTP 失败 → Agent 提示用户改用搜索。任一层故障不应让整个对话不可用。

**4. 可解释但不贵**：规则标签与 `basedOn` 提供「为什么推」的结构化依据，成本远低于对每个商品再跑一遍生成式理由。

### 对比分析

| 方案 | 召回可靠性 | 可解释性 | 延迟与成本 | 幻觉风险 |
|------|:---:|:---:|:---:|:---:|
| 纯规则/热销榜 | 中 | 弱 | 低 | 低 |
| 纯 LLM 推荐商品 | 差 | 强（但可能胡编） | 高 | 高 |
| **画像 + ES + 规则标签（hmall）** | **高** | **中高（结构化）** | **低～中** | **低** |

### 面试展示要点

> 「推荐不是让 LLM 瞎编商品。item-service 先读 Redis 画像拿 Top 类目，再 Feign 调 search-service 做 ES 召回，ES 没有结果就降级 MySQL 热销，最后用规则打 recommendTags。Agent 的 get_recommendations_api 只负责调 /recommend 并把结果格式化成表格和推荐依据。analyze_user_preferences 是另一条工具链，同样画像优先、miss 再实时聚合并回写。这样既实时准确，又不会把生成式模型放在召回链路上。」

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

hmall 在分布式环境下使用**雪花算法（Snowflake）**生成主键，典型值为 19 位 `Long`（例如 `1234567890123456789`）。浏览器端 JavaScript 的 `Number` 基于 IEEE 754 双精度浮点，**最大安全整数**为 `Number.MAX_SAFE_INTEGER = 2^53 - 1 ≈ 9.007×10^15`（约 16 位）。当后端把 Long 以 JSON number 直接下发时，前端解析后低位会被舍入，表现为末几位变成 `0`。此后用错误 ID 查询订单、商品、秒杀关联，会出现「详情 404 / 操作失败」等隐蔽故障，且很难从业务日志一眼看出是精度问题。

### 实现方案

在公共模块 `hm-common` 中通过 Jackson 定制全局序列化策略（类名是 **`JsonConfig`**，而非历史上误写的 `JacksonConfig`）：

```java
@Configuration
@ConditionalOnClass(ObjectMapper.class)
public class JsonConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jackson2ObjectMapperBuilderCustomizer() {
        return builder -> {
            // 出站：Long / BigInteger → JSON 字符串，保护 JS 精度
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(BigInteger.class, ToStringSerializer.instance);
        };
    }
}
```

**效果对比**：

```json
// 配置前（JS 不安全）
{ "id": 1234567890123456789 }
// 浏览器中可能变成 1234567890123456700

// 配置后（安全）
{ "id": "1234567890123456789" }
```

入站方面：`@RequestBody` / 查询参数反序列化时，Jackson 仍可将数字字符串还原为 `Long`，Java 服务内部算术与 MyBatis 映射继续使用数值类型；数据库字段类型保持 `bigint`，无需迁移。

### 设计亮点

**1. 一处配置，全局生效**：所有依赖 `hm-common` 的微服务自动继承，避免在每个 DTO 上重复加 `@JsonSerialize`。

**2. 影响面可控**：只改**出站序列化形态**，不改领域模型与持久化类型，对现有 SQL、缓存 Key、Feign 契约侵入最小。

**3. 覆盖 BigInteger**：与大整数/部分序列化路径一并处理，减少遗漏角落。

**4. 与 MyBatis-Plus 雪花策略配合**：实体使用 `@TableId(type = IdType.ASSIGN_ID)` 时，生成与传输形成闭环。

### 对比分析

| 方案 | 前端兼容性 | 改动范围 | 持久化类型 | 遗漏风险 |
|------|:---:|------|:---:|:---:|
| 缩短为 Integer/自增 ID | ✅ | 全链路改造 | 需迁移 | 低 |
| 前端全面改用 BigInt | ⚠️ 生态与 JSON 支持不一致 | 前端大 | 不变 | 中 |
| 每个 DTO 手动 `@JsonSerialize` | ✅ | 大量样板代码 | 不变 | 高 |
| **全局 Long/BigInteger→String（hmall）** | **✅** | **公共模块一处** | **不变** | **低** |

### 面试展示要点

> 「雪花 ID 是 19 位 Long，JS Number 只能安全表示到约 16 位，直接当 JSON 数字传会丢精度，后面所有按 ID 查详情都会错。我们在 hm-common 的 JsonConfig 里给 Long 和 BigInteger 挂了 ToStringSerializer，微服务依赖公共包就自动生效。后端计算和数据库仍是数值类型，只在返回给前端时变成字符串——改动面小，但能把一类线上隐患彻底堵上。」

---

## 12. Feign 自动传递用户上下文

### 设计动机

在 Gateway → trade-service → item-service / cart-service 这类调用链中，几乎每个下游都需要「当前用户是谁」来做数据隔离（我的订单、我的购物车、已购商品排除等）。若要求业务开发者在每一个 Feign 方法上手动增加 `userId` 参数，既重复又极易遗漏；遗漏的后果往往是查到别人的数据或空指针，属于高危缺陷。

因此需要在基础设施层提供**自动、统一、与网关约定一致**的用户上下文透传。

### 实现方案

全链路约定请求头名为 **`user-info`**（注意：不是文档中曾误写的 `X-User-Id`）：

1. **入口**：`hm-gateway` 的 `AuthGlobalFilter` 完成 JWT 校验后，将解析出的用户 ID 写入下游请求头 `user-info`；
2. **服务内**：各服务通过过滤器/拦截器读取 `user-info`，放入 `UserContext`（基于 ThreadLocal 的用户上下文）；
3. **出口**：`hm-api` 模块 `DefaultFeignConfig` 注册 `RequestInterceptor`，在发起 Feign 调用前把 `UserContext.getUser()` 再次写入 `user-info`。

```java
public class DefaultFeignConfig {
    @Bean
    public RequestInterceptor userInfoRequestInterceptor() {
        return template -> {
            Long userInfo = UserContext.getUser();
            if (userInfo != null) {
                template.header("user-info", userInfo.toString());
            }
        };
    }
}
```

传递示意：

```
Gateway (:8080)
  AuthGlobalFilter → header user-info: 123456
      ▼
trade-service
  解析 user-info → UserContext.setUser(123456)
  业务代码：itemClient.deductStock(...)
      │ DefaultFeignConfig.RequestInterceptor
      │ 自动附加 user-info: 123456
      ▼
item-service
  再次解析 user-info → UserContext
  ✅ 无需业务方法签名携带 userId
```

### 设计亮点

**1. 对业务代码零侵入**：开发者书写 `itemClient.xxx()` 时不必关心用户 ID 如何传递，降低认知负担与遗漏率。

**2. 与网关头名严格对齐**：全链路统一 `user-info`，避免「网关写 A、Feign 传 B」的分裂协议。

**3. 集中治理**：配置落在 `hm-api`，配合 `@FeignClient` 的 configuration / 默认配置一次启用；后续若要透传 TraceId、租户 ID，可在同一 Interceptor 扩展。

**4. 与限流协同**：Gateway `RateLimitFilter` 同样从 `user-info` 读取 userId 作为滑动窗口维度，认证与限流共享同一上下文约定。

### 对比分析

| 方案 | 业务侵入 | 一致性 | 遗漏风险 | 与网关协同 |
|------|:---:|:---:|:---:|:---:|
| 每个 Feign 方法手传 userId | 高 | 差 | 高 | 需各自约定 |
| 仅 ThreadLocal、无 Interceptor | 中 | 中 | 中（跨线程/异步易丢） | 弱 |
| **UserContext + Feign RequestInterceptor（hmall）** | **零** | **高** | **低** | **✅ 同头名 user-info** |

### 面试展示要点

> 「微服务调用链里每个下游都要知道当前用户。我们没有让每个 Feign 接口手传 userId，而是 Gateway 验签后写 user-info，服务内进 UserContext，再在 hm-api 的 DefaultFeignConfig 里用 RequestInterceptor 自动带回 Feign 请求。业务侧调用 itemClient 完全无感。头名全链路统一叫 user-info，限流过滤器也读同一个头，协议不分裂。」

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

Spring Cloud Gateway 的路由若写死在本地 `application.yml` / `bootstrap.yml` 中，每次新增微服务、调整 Path 断言或切换 `lb://` 目标，都需要改配置并**重启 Gateway**。网关是全局入口，重启意味着短时全部流量中断，在生产环境代价过高。

因此需要把路由定义外置到配置中心，并在运行期把变更同步进 Gateway 内存路由表，实现**零停机热更新**。hmall 通过自定义组件 `DynamicRouteLoader` 完成这一能力，而不是依赖 `@RefreshScope` 刷新本地 yml 片段——后者刷新的是普通配置 Bean，并不能直接等价于动态增删 `RouteDefinition`。

### 实现方案

实现类：`hm-gateway/.../routers/DynamicRouteLoader.java`。

**配置约定**：

| 项 | 值 | 说明 |
|----|-----|------|
| Nacos `dataId` | `gateway-routes.json` | 路由定义文件 |
| Nacos `group` | `DEFAULT_GROUP` | 配置分组 |
| 内容格式 | JSON 数组 | 元素反序列化为 Spring Cloud Gateway 的 `RouteDefinition` |
| 写入组件 | `RouteDefinitionWriter` | Gateway 提供的路由表写 API |

**启动与监听（一次调用完成两件事）**：

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicRouteLoader {

    private final NacosConfigManager nacosConfigManager;
    private final RouteDefinitionWriter writer;

    private final String dataId = "gateway-routes.json";
    private final String group = "DEFAULT_GROUP";

    /** 记录当前已注册到 Gateway 的路由 id，供下次全量替换时删除 */
    private Set<String> routeIds = new HashSet<>();

    @PostConstruct
    public void initRouteConfigLoader() throws NacosException {
        // getConfigAndSignListener：拉取当前配置 + 注册变更监听，二者合一
        String configInfo = nacosConfigManager.getConfigService()
                .getConfigAndSignListener(dataId, group, 5000, new Listener() {
                    @Override
                    public Executor getExecutor() {
                        // null → 使用 Nacos 客户端默认回调线程
                        return null;
                    }

                    @Override
                    public void receiveConfigInfo(String configInfo) {
                        // Nacos 配置变更推送 → 热更新
                        updateConfigInfo(configInfo);
                    }
                });
        // 冷启动：用首次拉取到的配置立刻装载路由表
        updateConfigInfo(configInfo);
    }
}
```

要点：

1. **不是**「`getConfig` + 另起一个 `@PostConstruct` 调 `addListener`」两段式；代码使用的是 Nacos 的 **`getConfigAndSignListener`**，返回值即当前配置正文，同时完成 Listener 注册。
2. 冷启动路径调用一次 `updateConfigInfo(configInfo)`；之后仅当 Nacos 推送变更时，在 `receiveConfigInfo` 中再次调用，实现热更新。
3. 组件**未**实现 `ApplicationEventPublisherAware`，也**未**发布 Spring 应用事件；路由变更完全通过 `RouteDefinitionWriter` 完成。

**全量替换路由表**：

```java
public void updateConfigInfo(String configInfo) {
    log.info("更新路由表: {}", configInfo);
    // Hutool：JSON 数组 → List<RouteDefinition>
    List<RouteDefinition> routeDefinitions =
            JSONUtil.toList(configInfo, RouteDefinition.class);

    // 1) 删除上一轮登记过的全部路由 id
    for (String routeId : routeIds) {
        writer.delete(Mono.just(routeId)).subscribe();
    }
    routeIds.clear();

    // 2) 写入本轮全部路由，并记录 id
    for (RouteDefinition routeDefinition : routeDefinitions) {
        writer.save(Mono.just(routeDefinition)).subscribe();
        routeIds.add(routeDefinition.getId());
    }
}
```

更新策略是**全量覆盖**：先按本地缓存的 `routeIds` 逐个 `delete`，再对本次 JSON 解析结果逐个 `save`。`routeIds` 使用 `HashSet<String>` 只存 id，而不是缓存整份 `RouteDefinition` 列表——删除时只需要 id。

`save` / `delete` 返回 Reactor `Mono`，代码以 `.subscribe()` 触发订阅；这是 Gateway 动态路由 API 的常见写法（写路径为响应式）。

**Nacos 中配置内容形态（示意）**：

```json
[
  {
    "id": "item-service",
    "uri": "lb://item-service",
    "predicates": [
      { "name": "Path", "args": { "pattern": "/items/**" } }
    ]
  },
  {
    "id": "trade-service",
    "uri": "lb://trade-service",
    "predicates": [
      { "name": "Path", "args": { "pattern": "/orders/**" } }
    ]
  }
]
```

实际 Path 需与网关对外暴露的路径、StripPrefix 等过滤器约定保持一致；`uri` 使用 `lb://` 时依赖服务发现（Nacos 注册中心）做负载均衡。

**端到端时序**：

```
Gateway 进程启动
  → @PostConstruct initRouteConfigLoader
  → getConfigAndSignListener(gateway-routes.json)
       ├─ 返回当前 JSON ──→ updateConfigInfo（首次装载）
       └─ 注册 Listener
              │
运维在 Nacos Console 修改并发布 gateway-routes.json
              │
              ▼
       receiveConfigInfo(新 JSON)
              │
              ▼
       updateConfigInfo：delete(旧 routeIds) → save(新定义) → 刷新 routeIds
              │
              ▼
       后续请求按新路由表转发（进程不重启）
```

### 设计亮点

**1. 拉取与监听合并，生命周期清晰**：`getConfigAndSignListener` 保证「启动必有一份初始配置 + 后续变更可推送」，避免只加 Listener 却忘记首次加载、或首次加载与监听逻辑分叉两套代码。

**2. 以 `RouteDefinitionWriter` 为唯一写入面**：直接操作 Gateway 运行时路由表，语义明确；不把「动态路由」误说成 `@RefreshScope` 刷新普通 `@ConfigurationProperties`——后者解决不了 `RouteDefinition` 的增删。

**3. 全量替换 + `routeIds` 簿记**：每次更新先清旧再写新，实现简单，避免差分合并漏删；本地 `Set` 记录本组件写入过的 id，防止只知新配置、不知旧 id 而无法删除。

**4. 运维面集中**：路由变更收敛到 Nacos Dashboard 发布 `gateway-routes.json`，无需发版网关制品，适合频繁调整转发规则的微服务演进阶段。

### 实现边界与面试注意点

如实说明当前实现的边界，避免过度包装：

| 点 | 现状 |
|----|------|
| 更新粒度 | 全量替换，非按 id 增量 patch |
| 并发/失败 | `delete`/`save` 异步 subscribe，无显式重试与事务；极端情况下短暂路由空窗取决于执行时序 |
| 解析库 | Hutool `JSONUtil.toList`，不是 Fastjson `JSON.parseArray` |
| 与 `@RefreshScope` | **无关**；动态路由不依赖 RefreshScope |
| 本地 yml 静态路由 | 若同时存在，需注意与动态路由的叠加/覆盖关系（以实际 Gateway 组合为准） |

### 对比分析

| 方案 | 更新方式 | 停机 | 规则存放 | 与本项目关系 |
|------|---------|:---:|---------|-------------|
| 本地 yml + 重启 | 改文件重启 | 有 | 代码仓 | 传统方式，运维重 |
| `@RefreshScope` 刷新配置 Bean | 配置刷新事件 | 无（但管的是普通配置） | 配置中心 | **不用于本项目的路由表热更新** |
| Spring Cloud Config + 自研刷新 | 配置总线 | 无～短 | Git | 未采用 |
| **`DynamicRouteLoader` + Nacos Listener（hmall）** | **`getConfigAndSignListener` → `RouteDefinitionWriter`** | **无进程重启** | **Nacos `gateway-routes.json`** | **实际实现** |

### 面试展示要点

> 「Gateway 路由没有写死在 yml 里长期靠重启生效，而是放在 Nacos 的 gateway-routes.json。DynamicRouteLoader 在 @PostConstruct 里调用 getConfigAndSignListener：一次拿到当前配置并注册监听。返回值立刻 updateConfigInfo 做冷启动装载；之后配置变更走 receiveConfigInfo 再全量更新。更新时用本地 routeIds 把旧路由 delete 掉，再把 JSON 反序列化成 RouteDefinition 列表 save 进去。这里用的是 RouteDefinitionWriter 操作运行时路由表，不是 @RefreshScope。解析用的是 Hutool JSONUtil。新增微服务时在 Nacos 加一条路由发布即可，网关进程不用重启。」

---

## 15. RabbitMQ 延迟消息：订单超时取消

### 设计动机

下单后若用户长时间未支付，系统需要在约 30 分钟后自动取消订单并释放库存/相关预占，否则会造成「死库存」与营销库存不准。该需求本质是**可靠的延迟任务**：

- 不能在业务线程 `Thread.sleep`（阻塞、无法水平扩展）；
- 不能仅靠进程内 `ScheduledExecutorService`（重启即丢失全部待取消任务）；
- 需要与支付成功路径**幂等协同**（用户可能在第 29 分钟完成支付）。

### 实现方案

hmall 使用 **RabbitMQ 延迟消息插件**（`x-delayed-message` / `@Exchange(delayed = "true")`），在发送时为每条消息设置 `setDelay(1800000)`（30 分钟）。这与「队列级 TTL + 死信交换机（DLX）」是不同实现路径——本项目以插件方案为准。

```
创建订单成功（含秒杀建单成功）
  │
  rabbitTemplate.convertAndSend(
      MQConstants.DELAY_EXCHANGE_NAME,  // trade.delay.direct（delayed）
      MQConstants.DELAY_ORDER_KEY,      // delay.order
      orderId,
      msg -> {
          msg.getMessageProperties().setDelay(1_800_000);
          return msg;
      }
  )
  │
  ▼ 到期后投递到 DELAY_ORDER_QUEUE
orderDelayMessageListener.listenOrderDelayMessage(orderId)
  ├─ 查订单；若为空或 status != 待付款(1) → 直接返回
  ├─ Feign 查支付单：若已支付成功 → markOrderPaySuccess（补偿标记）
  └─ 否则 cancelOrder（取消并恢复库存等）
```

监听声明（`orderDelayMessageListener`）：

```java
@RabbitListener(bindings = @QueueBinding(
    value = @Queue(name = MQConstants.DELAY_ORDER_QUEUE_NAME),
    exchange = @Exchange(name = MQConstants.DELAY_EXCHANGE_NAME, delayed = "true"),
    key = MQConstants.DELAY_ORDER_KEY
))
public void listenOrderDelayMessage(Long orderId) { ... }
```

普通下单（`OrderServiceImpl`）与秒杀建单（`SeckillOrderListener`）**共用**同一套 Exchange / RoutingKey / 延迟时长，避免两套超时语义不一致。

### 设计亮点

**1. 每消息独立延迟**：插件允许在消息属性上设置 delay，无需为「固定 30 分钟」单独维护 TTL 队列拓扑，也更易扩展为差异化超时（若未来需要）。

**2. 幂等与支付对账**：到期后不是盲目取消，而是先看订单状态，再查支付流水；已支付则补标记成功，未支付才取消——覆盖「延迟消息到达时支付回调稍晚」的竞态。

**3. 通道复用**：秒杀异步建单成功后同样投递延迟取消，超时治理逻辑集中，降低维护成本。

**4. 与本地消息表分工清晰**：本地消息表解决的是「DB 与 MQ 发送」的最终一致；延迟插件解决的是「未来某一刻触发」的时间维度问题，二者叠加而非互相替代。

### 对比分析

| 方案 | 时间精度 | 重启可靠性 | 拓扑复杂度 | 与支付竞态处理 |
|------|:---:|:---:|:---:|:---:|
| 定时扫表 | 取决于扫描间隔 | 高（状态在 DB） | 低 | 需自行设计 |
| Redis Key 过期通知 | 中 | 低（通知不可靠） | 低 | 弱 |
| 队列 TTL + DLX | 高 | 高 | 较高（多队列/交换机） | 需自建 |
| **Delayed Message Plugin（hmall）** | **高** | **高（持久化队列）** | **中（插件依赖）** | **✅ 状态+支付单双重判断** |

### 面试展示要点

> 「订单超时取消我们用的是 RabbitMQ 延迟消息插件，不是 TTL+死信那套。下单成功后往 delayed exchange 发消息，setDelay 30 分钟；到期由 orderDelayMessageListener 消费。消费者先看订单是不是还待付款，再查支付单——已经支付就补成功标记，没支付才取消并恢复库存。秒杀建单成功后也走同一条延迟通道，超时语义统一。」

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

## 17. 级联管理：DB 事务级联删除 + Redis 缓存清除

### 设计动机

管理端删除一场秒杀活动，表面上是删一行 `seckill_promotion`，实际上关联着：

- 多个场次（`seckill_session`）；
- 场次下的商品关联（`seckill_product_relation`）；
- 每日库存快照（`seckill_daily_stock`）；
- Redis 中已预热的 `seckill:stock:{relationId}` 与限购 `seckill:limit:{relationId}`。

若只删活动主表，子表残留会导致后台脏数据与统计错误；若只删库不删 Redis，C 端仍可能按旧库存键下单或展示错误剩余。因此需要**事务内级联删除 + 对齐真实 Key 的缓存清理**，并在缓存失败时做出明确的工程取舍。

### 实现方案

入口为 `SeckillServiceImpl.deletePromotion(Long id)`（`@Transactional(rollbackFor = Exception.class)`）：

```
deletePromotion(id)
  │
  ├─ 活动不存在 → 业务异常
  ├─ status == 进行中(1) → 拒绝删除（保护线上场次）
  ├─ 查询该活动下全部 session
  └─ 对每个 session 调用 deleteSessionCascade(sessionId)
        │
        ├─ 查出全部 product relation
        ├─ 对每个 relationId：clearRelationCache
        │     redis.delete(seckill:stock:{relationId})
        │     redis.delete(seckill:limit:{relationId})
        │     异常仅 log.warn，不抛出 → 不阻断 DB 事务
        ├─ 批量删除 relation
        ├─ 按 relationId 列表删除 daily_stock
        └─ 删除 session
  └─ 最后删除 promotion 本身
```

对应代码骨架：

```java
@Transactional(rollbackFor = Exception.class)
public void deletePromotion(Long id) {
    SeckillPromotion promotion = promotionMapper.selectById(id);
    // ... 存在性与「进行中不可删」校验
    List<SeckillSession> sessions = sessionMapper.selectList(
        new LambdaQueryWrapper<SeckillSession>().eq(SeckillSession::getPromotionId, id));
    for (SeckillSession session : sessions) {
        deleteSessionCascade(session.getId());
    }
    promotionMapper.deleteById(id);
}

private void clearRelationCache(Long relationId) {
    try {
        redisService.delete(STOCK_KEY_PREFIX + relationId);
        redisService.delete(LIMIT_KEY_PREFIX + relationId);
    } catch (Exception e) {
        log.warn("清除秒杀Redis缓存失败, relationId={}", relationId, e);
    }
}
```

### 设计亮点

**1. 子→父顺序与领域模型对齐**：先清 relation/缓存与 daily_stock，再删 session，最后删 promotion，避免关联数据孤儿化。

**2. 缓存 Key 与运行时一致**：清理的是真正参与 Lua 预减的 `stock` / `limit`，而不是虚构的 `seckill:order` SET。文档与实现必须对齐，否则「以为清了缓存」实际无效。

**3. 进行中活动保护**：状态校验把「误删正在开场的活动」挡在业务层，属于写路径上的显式不变量。

**4. 缓存失败的取舍（如实说明）**：当前实现是在事务方法内直接 `delete`，**没有**使用 `TransactionSynchronization.afterCommit`，也**没有** MQ 重试队列或 `pending_clean` 标记。Redis 异常只打 warn，DB 删除仍提交。这是有意偏向「运营删除可达、关联数据必须干净；缓存做尽力而为，可靠 TTL/再次预热收敛」的策略。面试时应按真实实现表述，避免夸大成完整的 afterCommit + 重试体系。

### 对比分析

| 方案 | DB 关联完整性 | 缓存一致性 | 删除可达性 | 复杂度 |
|------|:---:|:---:|:---:|:---:|
| 只删主表 | ❌ | ❌ | 高 | 低 |
| 先删缓存再删 DB | 中 | 回滚后易穿透 | 中 | 中 |
| afterCommit + MQ 重试清缓存 | ✅ | 更强 | 高 | 高 |
| **事务级联 + 尽力清缓存（hmall 现状）** | **✅** | **较好（失败可残留）** | **高** | **中** |

### 面试展示要点

> 「删秒杀活动不是一行 DELETE。我们会校验进行中活动不能删，然后按场次级联删商品关联、每日库存和场次，最后删活动。每个 relation 还会清 Redis 里的 stock 和 limit——这正是 Lua 预减用的两个 Key。Redis 清失败我们只打日志，不让删除接口失败，因为更不能接受的是后台删不掉活动、库里留下半截关联。目前没有 afterCommit 钩子和清缓存重试队列，这是『DB 强一致、缓存尽力而为』的明确取舍，而不是漏做。」

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
| 中间件 | 5 | auth / permission / regex_shortcut / rag_context / SkillsMiddleware(框架) |
| MCP 服务 | 1 | rag_server.py (FastMCP) |
| 工具加载 | 2 | rag_loader / formatters |
| API 路由 | 3 | batch_report / health / custom_routes |
| Gateway | 2 | auth / http_client |
| 核心 | 2 | config / llms |
| 用户画像 | 2 | user_profile/store / memory |
| 启动脚本 | 2 | start_server / start_rag_server |
| Skills | 10 | 7 个 customer + 3 个 admin SKILL.md |
| **合计** | **~40+** | uv + Python ≥3.12 |

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

更隐蔽的问题是：如果先扣 Redis 再异步发 MQ，Redis 扣成功了但 MQ 发失败——库存被预减却没有建单。实现上发送失败会当场回补 Redis；消费失败则回补并写 `seckill:result=0`。**这是"部分失败"与补偿问题。**

#### 解决思路（三层递进）

**第一步：Lua 原子脚本保证库存一致性**

这是最核心的一步。把「检查库存 + 检查限购 + 扣减库存 + 递增已购数量」写进一个 Redis Lua 脚本，利用 Redis 单线程模型天然原子执行。杜绝了"读后写"的竞态条件——不存在"库存还剩 1 件、两个人同时读到 1"的情况。

```lua
-- 库存 + 限购在一次原子调用中完成（seckill_deduct.lua）
-- KEYS: stock String / limit Hash；ARGV: userId, quantity, limitNum
if purchased + quantity > limitNum then return -2 end  -- 超限购
if stock < quantity then return 0 end                  -- 售罄
redis.call('DECRBY', stockKey, quantity)
redis.call('HINCRBY', limitKey, userId, quantity)
return 1
```

**第二步：RabbitMQ 异步削峰 + 结果可轮询**

预减成功后，接口立刻返回 `pending`，把建单压力交给 `SeckillOrderListener`。消费者侧用 `SELECT ... FOR UPDATE` 锁定当日库存快照并落库；成功则 `setResult(orderId)`，失败则回补 Redis 并 `setResult("0")`。前端/Agent 通过 `GET /seckill/result/{relationId}` 感知终态。这样 瞬时尖峰流量在 Redis/MQ 被吸收，MySQL 只承受被平滑后的写入，同时异步链路不再「黑盒」。

**第三步：分层补偿，而不是一套机制包打天下**

秒杀链路上的「部分失败」主要靠**同步回补 + 消费端回补 + result 失败态**解决：MQ 发送失败当场回补库存/限购；消费失败同样回补并写 `"0"`。普通下单后清空购物车则另走**本地消息表（Transactional Outbox）**：与订单同事务写入 `t_local_message`，由 `@Scheduled` 扫描重发，保证 DB 与 MQ 最终一致。两套问题域不同，不能混为一谈。

```
秒杀：Lua 预减 → 发 MQ（失败即回补）→ 消费者 FOR UPDATE 扣 MySQL
      → setResult(orderId | "0") → 前端轮询
普通下单清车：同事务 INSERT order + INSERT local_message
      → @Scheduled 扫描 status=0 → 发 MQ → 成功/重试/永久失败
```

#### 为什么这很难

秒杀不是单一技术点，而是一个**系统性工程问题**：Lua 原子预减 → MQ 异步建单 → MySQL 行锁兜底 + 结果轮询，再叠加普通单的本地消息表，缺一不可。任何一层有漏洞，要么超卖，要么数据不一致，要么系统崩溃。需要同时理解 **Redis 执行模型**、**消息队列可靠性**、**数据库事务** 和 **分布式最终一致性** 四个领域。

#### 核心思考

> "秒杀的本质矛盾不是'快'，而是'多个操作的一致性'。如果用分布式锁，拿到锁后查库存再扣——锁的粒度、超时、续约全是坑。Lua 脚本从模型层面规避了这些问题：Redis 单线程执行 Lua 天然串行，不需要锁。异步削峰解决吞吐，MySQL 行锁与 Redis 回补解决最终库存一致，result key 解决前端可见性——各司其职。"

---

### 维度二：Agent 系统 — 在"智能"与"可控"之间找平衡

#### 问题本质

用 LLM 做 Agent 有一个天然矛盾：

| 需求 | LLM 的天然倾向 | 矛盾 |
|------|---------------|------|
| **快速响应** | 每轮都做推理选工具（1-3s） | 运营人员每天看日报等不了 3 秒 |
| **安全操作** | 可能执行破坏性操作（取消订单、清空购物车） | LLM 无法保证"不想当然地调用危险工具" |
| **权限隔离** | AdminAgent 不应能操作 C 端购物车/秒杀 | 若工具过滤不严，LLM 可能尝试调用写工具 |
| **知识库** | 退换货政策在文档而非 API 中 | LLM 自身不知道业务规则 |

更深层的问题是：**每一层控制都不应该依赖 LLM 的"自觉"**——你不能在系统提示词里写"请勿取消订单"然后指望 LLM 遵守。

#### 解决思路（三级路由 + 中间件链）

**L1：正则快捷路由 — 高频模式零 LLM 消耗**

运营日报、猜你喜欢推荐，这些固定短语 100% 命中。`RegexShortcutMiddleware` 排在 Auth/Permission 之后、RAG/Skills 之前，命中即直接调用工具并短路 LLM。延迟从 2-3 秒降到 <1 秒，零 token 费用。

```python
# AdminAgent（与代码一致的风格）
(r"(?:运营|生成|帮我做).{0,3}日报", "generate_daily_report", None)
# CustomerAgent
(r"(?:推荐|猜你喜欢|有什么好|帮我选|随便看看|给我推荐)",
 "get_recommendations_api", _extract_recommend_scene)
```

**L2：中断确认 — 破坏性操作的硬闸门**

取消订单、清空购物车、秒杀下单等破坏性操作，在工具函数内部调用 LangGraph 的 `interrupt()` 机制。Agent 执行到此处暂停，前端弹出确认弹窗，用户点了"确认"后 Agent 才从 checkpoint 恢复继续执行。**LLM 无法绕过**——这不是提示词约束，而是框架级的中断机制。

```python
# 工具函数内（示意）
approval = interrupt({"type": "confirmation", "message": "确认取消订单？", ...})
if approval.strip() == "确认":
    return await gateway_client.put(...)
```

**L3：LLM 推理 — 复杂多工具编排**

只有真正复杂的、需要多步推理的问题才走到 LLM ReAct 循环。`PermissionMiddleware` 在此层从工具列表里直接裁掉危险工具——AdminAgent 的工具列表里根本没有 `do_seckill_api`，LLM 想调用也无从调用。

#### 为什么这很难

Agent 开发的难点不在于「让 LLM 能做什么」，而在于「让 LLM 不能做什么」。纯提示词约束不可靠，纯规则路由又不够智能。三级路由 + 中间件链的本质，是在智能与可控之间划出硬边界：高频固定意图归正则，危险写操作归框架级 interrupt，复杂编排才归 LLM。中间件顺序 Auth → Permission → Regex → RAG → Skills 把「身份 → 工具可见性 → 短路 → 增强 → 规范」固化进代码路径，而不是写在 README 里的建议。

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

**缓存一致性场景 — 事务级联删除 + 尽力清 Redis**

管理后台删除秒杀活动时，业务不变量是「进行中不可删」与「子表不能残留」。`deletePromotion` 在同一事务内按场次调用 `deleteSessionCascade`：先对每个 `relationId` 清理 `seckill:stock` / `seckill:limit`，再删商品关联与每日库存，最后删场次与活动。Redis 删除包在 try/catch 中，失败只打 `warn`，**不回滚**数据库事务——优先保证运营删除可达与 DB 关联完整。需要如实说明：当前实现**没有** `afterCommit` 钩子，也**没有**清缓存 MQ 重试 / `pending_clean`；残留 Key 依赖后续预热覆盖或自然失效收敛。这是「DB 强一致、缓存尽力而为」的显式取舍。

```java
@Transactional(rollbackFor = Exception.class)
public void deletePromotion(Long id) {
    // 校验非进行中 → 遍历 session → deleteSessionCascade
    // cascade：clearRelationCache(stock/limit) → 删 relation/daily_stock/session
    promotionMapper.deleteById(id);
}
```

#### 为什么这很难

分布式一致性是微服务架构中最容易"说得头头是道，实际全面踩坑"的领域。难在两点：

1. **不能一个方案打天下**：强一致（Seata）有性能代价，最终一致（本地消息表）有窗口期不一致风险。需要对业务场景做判断——哪些操作"必须马上对"，哪些可以"最终对"。
2. **一致性是系统性保障，不是局部的**：从 Seata → 本地消息表 → 秒杀级联删除时尽力清缓存 → Sentinel 熔断降级，它们形成了完整的一致性防护网。缺少任何一环，就会有某个角落出现数据不一致。

#### 核心思考

> "一致性设计最难的地方不是用哪个工具，而是知道什么时候该用强一致、什么时候该用最终一致。下单中间步骤必须强一致——库存扣了但订单没建，用户钱没了货也没了，这是事故。但下单后的清空购物车可以做最终一致——晚几秒清不影响用户体验。级联删除清缓存的思路是「先保证 DB 关联正确，再最大努力清 Redis」——缓存错了可以修或靠预热覆盖，DB 错了要赔钱。"

---

### 面试回答策略建议

| 面试时长 | 建议选择 | 展示要点 |
|---------|---------|---------|
| **3 分钟** | 只讲维度一（秒杀） | Lua 原子预减 → MQ 削峰 + result 轮询 → MySQL 行锁/回补，并点到普通单的本地消息表 |
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

两个工具通过 `user_profile/memory.py` 暴露给 LLM，使用 **LangGraph Store API** 操作：

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

*本文档基于 hmall 代码库实际审查整理。架构设计亮点包括：Agent 三级路由降低 LLM 调用成本、双 JWT + agent_type 权限隔离、Redis Lua + MQ + MySQL 行锁秒杀削峰与结果轮询、中间件链 Auth→Permission→Regex→RAG→Skills（无 CacheMiddleware）、LightRAG + MCP 三层桥接、Gateway 统一认证与滑动窗口限流、画像 + ES 召回推荐、Agent 三层画像记忆体系、本地消息表最终一致性、Seata AT 强一致、Feign user-info 自动透传、Sentinel 熔断、Nacos 动态路由、RabbitMQ 延迟消息插件订单超时取消、RBAC 三层权限、秒杀级联删除 + Redis 尽力清缓存。*
