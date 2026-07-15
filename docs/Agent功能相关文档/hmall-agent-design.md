# hmall Agent 智能助手设计文档

> 版本：v1.0  
> 日期：2026-07-15  
> 参考项目：`nova-mall-agent`（FastAPI + LangChain + 通义千问）

---

## 1. 概述

### 1.1 背景与目标

hmall（枫叶商城）已完成微服务架构搭建，包含商品、购物车、订单、支付、用户、搜索、秒杀、管理后台等完整链路。当前缺少 AI 智能助手，用户需要手动浏览页面完成购物流程，运营人员需要逐个页面查看数据。

本设计参考 `nova-mall-agent` 的三级路由架构（正则 → 状态机 → LLM），为 hmall 构建两个 AI Agent：

- **客服助手（CustomerAgent）**：面向 C 端用户，支持商品浏览、秒杀、购物车、订单、地址等全链路自然语言交互
- **管理助手（AdminAgent）**：面向运营人员，支持秒杀管理、订单查询、商品管理、库存状态查看等只读操作 + 运营日报

### 1.2 设计原则

| 原则 | 说明 |
|------|------|
| **三级路由** | L1 正则匹配（<5ms，拦截 80%+ 高频指令）→ L2 状态机（多轮交互）→ L3 LLM 兜底（~2s） |
| **Agent 零数据库** | 所有数据操作通过 Gateway → 微服务 API 完成，Agent 不直连数据库 |
| **双 Token 隔离** | C 端用户 JWT 和管理后台 JWT 独立验证，互不干扰 |
| **二次确认** | 危险操作（取消订单、删除地址、清空购物车）展示信息后用户确认才执行 |
| **空数据兜底** | 所有查询在代码层检测空数据，直接返回固定提示，不走 LLM |
| **降级策略** | LLM API 超时/异常时自动切换固定兜底文案 |
| **复用现有基建** | 复用 hmall 的 Redis（对话记忆）、Gateway（路由+认证）、Nacos（配置） |

### 1.3 与 nova-mall-agent 的对比

| 维度 | nova-mall-agent | hmall Agent |
|------|----------------|-------------|
| 后端架构 | 单体（Portal:8085 + Admin:8080） | 微服务（Gateway:8080 → 9 个微服务） |
| API 调用方式 | 直连 Java 后端 | 经 Gateway 路由到微服务 |
| 认证体系 | 单一 JWT 密钥 | 双 JWT（C 端 RSA + 管理端独立 keystore） |
| 秒杀支持 | 有（flash promotion） | 有（三层防超卖秒杀系统） |
| 优惠券 | 有 | 无（hmall 暂未实现） |
| 售后退款 | 有 | 无（hmall 暂未实现） |
| 管理后台 | 独立 Admin Web | admin-service 微服务 + RBAC |
| RAG 知识库 | 有（5 文件 1800 行） | 规划中（运营知识库） |
| LLM | 通义千问 qwen-turbo | 通义千问 qwen-turbo（可替换） |
| 部署端口 | 8090 | 8090 |

---

## 2. 整体架构

### 2.1 系统架构

```
用户（C端 / 管理后台）
  │
  │  WebSocket / SSE / HTTP
  ▼
┌──────────────────────────────────────────────────────────┐
│              Agent Service (FastAPI :8090)                 │
│                                                            │
│  ┌────────────────────────────────────────────────────┐   │
│  │  Gateway 层                                          │   │
│  │  ├── WebSocket /ws/chat（实时流式）                   │   │
│  │  ├── POST /api/v1/chat（非流式）                      │   │
│  │  ├── POST /api/v1/chat/stream（SSE 流式）             │   │
│  │  ├── DELETE /api/v1/chat/history/{id}（清除记忆）     │   │
│  │  ├── JWT 认证（双 Token：C端 / 管理端）                │   │
│  │  └── 工具权限拦截（AdminAgent 纯只读）                 │   │
│  └────────────────────────────────────────────────────┘   │
│                          │                                 │
│  ┌───────────────────────▼────────────────────────────┐   │
│  │  Agent 调度层                                        │   │
│  │                                                       │   │
│  │  CustomerAgent                AdminAgent              │   │
│  │  ├── L1 正则路由（15+ 规则）    ├── L1 正则路由（8+ 规则）│   │
│  │  ├── L2 状态机（地址/秒杀）     ├── 运营日报（多工具编排）│   │
│  │  ├── L3 LLM 兜底               ├── RAG 知识增强        │   │
│  │  └── 二次确认机制               └── L3 LLM 兜底         │   │
│  └───────────────────────┬────────────────────────────┘   │
│                          │                                 │
│  ┌───────────────────────▼────────────────────────────┐   │
│  │  工具层（Python httpx → Java API Proxy）             │   │
│  │  ├── customer_api.py（18 个 C 端工具）                │   │
│  │  └── admin_api.py（10 个管理端工具）                  │   │
│  └───────────────────────┬────────────────────────────┘   │
│                          │                                 │
│  ┌───────────────────────▼────────────────────────────┐   │
│  │  基础设施                                            │   │
│  │  ├── Redis（对话记忆：20条/30min TTL）                │   │
│  │  ├── LangChain（LLM 框架）                           │   │
│  │  └── 通义千问 DashScope（LLM 推理）                   │   │
│  └────────────────────────────────────────────────────┘   │
└──────────────────────────┬───────────────────────────────┘
                           │ HTTP (httpx)
                           ▼
┌──────────────────────────────────────────────────────────┐
│              hm-gateway (:8080)                           │
│  ├── AuthGlobalFilter（JWT 认证 + user-id 透传）           │
│  ├── RateLimitFilter（秒杀限流）                           │
│  └── DynamicRouteLoader（Nacos 动态路由）                  │
└──┬──────┬────────┬────────┬────────┬────────┬───────────┘
   │      │        │        │        │        │
   ▼      ▼        ▼        ▼        ▼        ▼
 item   cart    user    trade    pay    admin    search
:8081   :8082   :8084   :8085   :8083   :8090   :8089
```

### 2.2 三级路由架构

```
用户消息
  │
  ├─ L1: 正则路由 (<5ms)
  │   ├── 匹配 "查看订单" / "查看购物车" / "秒杀活动" 等高频指令
  │   ├── 直接调用对应 API 工具 + 代码格式化输出
  │   └── 拦截 80%+ 请求，零 LLM 成本
  │
  ├─ L2: 状态机 (多轮交互)
  │   ├── 地址修改：问序号 → 问字段 → 问新值
  │   ├── 秒杀下单：查活动 → 选商品 → 确认下单
  │   └── 二次确认：取消订单/删除地址/清空购物车
  │
  └─ L3: LLM 兜底 (~2s)
      ├── 闲聊 / 复杂问题
      ├── LLM 自主选择工具调用（最多 3 轮）
      └── 结果格式化输出
```

### 2.3 技术栈

| 类别 | 技术 | 说明 |
|------|------|------|
| Web 框架 | FastAPI + WebSocket | 异步非阻塞，支持流式输出 |
| LLM | 阿里云通义千问（DashScope qwen-turbo） | OpenAI 兼容接口 |
| Agent 框架 | LangChain Core | 消息管理 + 工具调用 |
| 对话记忆 | Redis（复用 hmall Redis） | 20 条/30 分钟过期，db=1 隔离 |
| HTTP 客户端 | httpx | 异步调用 Java 后端 API |
| 日志 | Loguru | 结构化日志 |
| 监控 | Prometheus（可选） | 工具调用指标 |

---

## 3. CustomerAgent 设计（C 端客服助手）

### 3.1 工具清单（18 个）

基于 hmall C 端 API 设计，所有工具通过 Gateway `:8080` 调用：

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
| `do_seckill_api` | `POST /seckill/order/{relationId}` | 秒杀下单（需登录） |

#### 购物车（5 个）

| 工具名 | API | 说明 |
|--------|-----|------|
| `get_cart_list_api` | `GET /carts` | 购物车列表（需登录） |
| `add_to_cart_api` | `POST /carts` | 加入购物车（需登录） |
| `update_cart_quantity_api` | `PUT /carts/{itemId}` | 修改数量（需登录） |
| `delete_cart_item_api` | `DELETE /carts/{itemId}` | 删除商品（需登录，二次确认） |
| `clear_cart_api` | `DELETE /carts` | 清空购物车（需登录，二次确认） |

#### 订单（4 个）

| 工具名 | API | 说明 |
|--------|-----|------|
| `get_order_list_api` | `GET /orders/page` | 订单列表（需登录，支持状态筛选） |
| `get_order_detail_api` | `GET /orders/{id}` | 订单详情（需登录） |
| `cancel_order_api` | `POST /orders/batch/close` | 取消订单（需登录，二次确认） |
| `confirm_receive_api` | `PUT /orders/{orderId}` | 确认收货（需登录，二次确认） |

#### 收货地址（3 个）

| 工具名 | API | 说明 |
|--------|-----|------|
| `get_address_list_api` | `GET /addresses` | 地址列表（需登录） |
| `add_address_api` | `POST /addresses` | 新增地址（需登录，状态机多轮） |
| `update_address_api` | `PUT /addresses/{addressId}` | 修改地址（需登录，状态机多轮） |

> **注**：hmall 暂无优惠券和售后功能，相比 nova-mall-agent 减少 5 个工具。

### 3.2 正则路由规则

| 用户输入示例 | 匹配规则 | 路由工具 |
|-------------|---------|---------|
| `查看秒杀` / `秒杀活动` | `(?:查看\|查询\|当前).{0,3}秒杀` | `get_seckill_activities_api` |
| `搜索手机` / `查找商品` | `(?:搜索\|查找\|找).{0,3}(.+)` | `search_items_api` |
| `查看购物车` / `我的购物车` | `(?:查看\|查询).{0,5}购物车` | `get_cart_list_api` |
| `100加入购物车` | `(\d+)\s*加入购物车` | `add_to_cart_api` |
| `修改购物车100数量为3` | `修改\s*(\d+)\s*数量\s*(?:为\|改成)\s*(\d+)` | `update_cart_quantity_api` |
| `清空购物车` | `清空\s*购物车` | `clear_cart_api`（二次确认） |
| `查看订单` / `待付款订单` | `(?:查询\|查看).{0,5}(?:待付款\|待发货\|...)?订单` | `get_order_list_api` |
| `查看订单100` | `(?:查看\|看)\s*(?:订单\s*)?(\d+)` | `get_order_detail_api` |
| `取消订单100` | `取消\s*(?:订单\s*)?(\d+)` | `cancel_order_api`（二次确认） |
| `确认收货100` | `确认\s*(?:收货\s*)?(\d+)` | `confirm_receive_api`（二次确认） |
| `查看地址` / `我的地址` | `(?:查询\|查看).{0,5}地址` | `get_address_list_api` |
| `新增地址` | `新增地址\|添加地址` | `add_address_api`（状态机） |
| `修改地址1的姓名为张三` | `修改\s*(\d+)\s*(?:的)?\s*(字段)\s*(?:为)\s*(值)` | `update_address_api` |

### 3.3 状态机设计

#### 地址修改状态机

```
用户: "修改地址1"
  │
  ├─ Agent: "请问要修改哪个字段？(姓名/手机号/省份/城市/区/详细地址)"
  │
  ├─ 用户: "姓名"
  │
  ├─ Agent: "请输入新的姓名"
  │
  ├─ 用户: "张三"
  │
  └─ Agent: ✅ 地址1的姓名已修改为张三
```

#### 秒杀下单流程

```
用户: "查看秒杀活动"
  │
  ├─ Agent: 返回活动列表 + 场次 + 商品（含实时库存）
  │
  ├─ 用户: "秒杀商品100"  (100 = relationId)
  │
  ├─ Agent: "确认秒杀以下商品？\n商品名: iPhone 15\n秒杀价: ¥5999\n限购: 1件"
  │
  ├─ 用户: "确认"
  │
  └─ Agent: ✅ 秒杀请求已提交，正在排队... → 轮询结果
```

### 3.4 二次确认机制

| 操作 | 确认消息 | 执行条件 |
|------|---------|---------|
| 取消订单 | `确定要取消订单「{orderId}」？总金额 ¥{totalFee}。回复"确认取消"执行` | 用户回复包含"确认取消" |
| 确认收货 | `确定已收到订单「{orderId}」的商品？回复"确认收货"执行` | 用户回复包含"确认收货" |
| 删除购物车 | `确定要删除购物车中的「{itemName}」？回复"确认删除"执行` | 用户回复包含"确认删除" |
| 清空购物车 | `确定要清空购物车中的所有商品？回复"确认删除"执行` | 用户回复包含"确认删除" |

---

## 4. AdminAgent 设计（管理助手）

### 4.1 工具清单（10 个，纯只读）

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

> **注**：AdminAgent 纯只读，所有写操作（创建/修改/删除/发货/预热）均不可用，由权限拦截器阻止。

### 4.2 运营日报

自然语言触发（`运营日报` / `帮我做一份日报` / `生成周报`），自动编排 5 个工具调用：

```
📊 正在生成 2026-07-15 运营日报...

📋 步骤 1/5: 查询订单统计... ✅ 156单/¥89,200
📋 步骤 2/5: 查询秒杀活动... ✅ 3场进行中
📋 步骤 3/5: 查询秒杀库存... ✅ 12件预警
📋 步骤 4/5: 查询商品列表... ✅ 248件在售
📋 步骤 5/5: 查询用户统计... ✅ 总1,230人

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

### 4.3 RAG 知识增强（规划中）

AdminAgent 可集成 RAG 知识库，覆盖运营分析、商品管理、秒杀策略等领域：

```
knowledge_base/
└── admin_knowledge/
    ├── seckill_strategy.md        # 秒杀运营策略
    ├── inventory_management.md    # 库存管理指南
    ├── order_analysis.md          # 订单分析指南
    ├── user_segmentation.md       # 用户分群方法
    └── data_interpretation.md     # 数据指标解读
```

用户提问 `秒杀库存怎么设置合理？` → RAG 检索知识库 → 注入 LLM 上下文 → 专业回答。

---

## 5. 对话记忆设计

### 5.1 Redis Key 设计

复用 hmall 已有的 Redis 实例，使用 db=1 隔离（hmall 业务用 db=0）：

| Key 模式 | 类型 | TTL | 说明 |
|---------|------|-----|------|
| `agent:chat:{userId}:{conversationId}` | List | 30min | 对话历史（最多 20 条） |
| `agent:state:address:{conversationId}` | String | 10min | 地址修改状态机 |
| `agent:state:seckill:{conversationId}` | String | 10min | 秒杀下单状态机 |
| `agent:confirm:{conversationId}` | String | 5min | 二次确认待执行操作 |

### 5.2 记忆管理

```python
class ChatMemory:
    def __init__(self, conversation_id, user_id=None, max_history=20):
        self.key = f"agent:chat:{user_id}:{conversation_id}" if user_id \
                   else f"agent:chat:{conversation_id}"

    def add_message(self, role, content):
        redis.rpush(self.key, json.dumps({"role": role, "content": content}))
        redis.ltrim(self.key, -20, -1)  # 保留最近 20 条
        redis.expire(self.key, 1800)    # 30 分钟过期
```

---

## 6. 安全设计

### 6.1 双 JWT 认证

| Agent | Token 来源 | 密钥 | 验证方式 |
|-------|-----------|------|---------|
| CustomerAgent | C 端用户登录 `POST /users/login` | `hmall.jks`（RSA） | Gateway AuthGlobalFilter |
| AdminAgent | 管理后台登录 `POST /admin/login` | `admin.jks`（RSA，独立） | Gateway AuthGlobalFilter |

Agent 服务收到请求后：
1. 从 WebSocket 消息 / HTTP Header 提取 `user_token`
2. 调用 Gateway 验证 Token 有效性（或本地验证）
3. 提取 `user_id` / `admin_id` 用于数据隔离

### 6.2 工具权限隔离

| Agent | 读操作 | 写操作 |
|-------|--------|--------|
| CustomerAgent | ✅ 全部 C 端读工具 | ✅ 购物车/订单/地址写操作（需 Token） |
| AdminAgent | ✅ 全部管理端读工具 | ❌ 所有写操作被权限拦截器阻止 |

```python
# permissions.py
WRITE_TOOLS = {"add_to_cart_api", "update_cart_quantity_api", "delete_cart_item_api",
               "clear_cart_api", "cancel_order_api", "confirm_receive_api",
               "add_address_api", "update_address_api", "do_seckill_api"}

def check_tool_allowed(agent_type, tool_name):
    if agent_type == "admin" and tool_name in WRITE_TOOLS:
        return False, "管理助手不支持写操作"
    return True, ""
```

### 6.3 参数校验

代码层正则 + 类型检查：
- 数量 `quantity >= 1`
- 手机号 11 位数字
- 地址序号为正整数
- 商品 ID 为正整数

---

## 7. API 设计

### 7.1 WebSocket（推荐，实时流式）

```
WS /ws/chat

// 客户端发送
{"message": "查看秒杀活动", "agent_type": "customer", "user_token": "xxx", "conversation_id": "abc123"}

// 服务端返回
{"type": "start", "conversation_id": "abc123"}
{"type": "chunk", "content": "当前秒杀活动..."}
{"type": "chunk", "content": "iPhone 15 秒杀价 ¥5999"}
{"type": "end"}
```

### 7.2 HTTP SSE（流式）

```
POST /api/v1/chat/stream
Content-Type: application/json

{"message": "运营日报", "agent_type": "admin", "user_token": "xxx"}

// SSE 响应
data: {"content": "📊 正在生成运营日报..."}
data: {"content": "📋 步骤 1/5: 查询订单统计..."}
data: [DONE]
```

### 7.3 HTTP 非流式

```
POST /api/v1/chat
Content-Type: application/json

{"message": "查看购物车", "agent_type": "customer", "user_token": "xxx"}

// 响应
{"reply": "您的购物车有 3 件商品：\n1. iPhone 15 - ¥5999\n2. ..."}
```

### 7.4 清除对话历史

```
DELETE /api/v1/chat/history/{conversation_id}
```

---

## 8. 项目结构

```
hmall-agent/
├── app/
│   ├── main.py                        # FastAPI 入口
│   ├── config.py                      # 配置管理（Pydantic Settings）
│   ├── agents/
│   │   ├── base_agent.py              # Agent 基类：多轮工具调用、消息管理、流式输出
│   │   ├── customer_agent.py          # 客服 Agent 调度器（三级路由 + 状态机 + 二次确认）
│   │   ├── admin_agent.py             # 管理 Agent（纯只读 + 运营日报 + RAG）
│   │   ├── intent_router.py           # 正则意图路由器
│   │   ├── address_handlers.py        # 地址处理：状态机 + CRUD + 二次确认
│   │   ├── cart_handlers.py           # 购物车处理：加购/修改/删除 + 二次确认
│   │   ├── order_handlers.py          # 订单处理：取消/确认收货/详情
│   │   ├── seckill_handlers.py        # 秒杀处理：查活动/下单/轮询
│   │   └── admin_handlers.py          # 后台处理：统计查询 + 日报格式化
│   ├── tools/
│   │   ├── customer_api.py            # C 端 18 个 API 代理工具
│   │   └── admin_api.py               # 管理端 10 个 API 代理工具
│   ├── prompts/
│   │   ├── __init__.py                # load_prompt()
│   │   ├── customer_system_prompt.md  # 客服系统提示词
│   │   └── admin_system_prompt.md     # 管理端系统提示词
│   ├── gateway/
│   │   ├── ws.py                      # WebSocket 网关
│   │   ├── router.py                  # HTTP API 路由
│   │   ├── auth.py                    # 双 JWT 认证
│   │   ├── permissions.py             # 工具权限拦截
│   │   └── audit.py                   # 操作审计日志
│   ├── memory/
│   │   └── chat_memory.py             # Redis 对话记忆
│   ├── knowledge/
│   │   └── vector_store.py            # RAG 知识库（AdminAgent）
│   └── utils/
│       ├── http_client.py             # 公共 HTTP 客户端（httpx → Gateway）
│       ├── llm.py                     # LLM 封装（DashScope）
│       ├── tool_call.py               # tool_call 解析 + 流式过滤
│       ├── formatters.py              # 格式化函数
│       ├── logger.py                  # 日志工具
│       └── metrics.py                 # Prometheus 监控
├── knowledge_base/                    # 知识库文档
│   └── admin_knowledge/              # 管理员专业知识
├── tests/                             # 自动化测试
├── requirements.txt
├── .env.example
└── README.md
```

---

## 9. 配置设计

### 9.1 环境变量

```ini
# ==================== LLM ====================
DASHSCOPE_API_KEY=your_api_key
LLM_MODEL_NAME=qwen-turbo
LLM_TEMPERATURE=0.7
LLM_MAX_TOKENS=2048

# ==================== Redis ====================
REDIS_HOST=192.168.100.128
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DB=1

# ==================== Java 后端 ====================
JAVA_GATEWAY_URL=http://localhost:8080

# ==================== Agent 服务 ====================
AGENT_HOST=0.0.0.0
AGENT_PORT=8090
LOG_LEVEL=INFO

# ==================== JWT（可选，本地验证） ====================
# 如不配置，Agent 不做本地 JWT 验证，依赖 Gateway 验证
JWT_VERIFY_LOCAL=false
```

### 9.2 关键配置说明

| 配置 | 说明 |
|------|------|
| `JAVA_GATEWAY_URL` | hmall Gateway 地址，所有 API 调用经此路由 |
| `REDIS_DB=1` | 使用 db=1 与 hmall 业务数据（db=0）隔离 |
| `JWT_VERIFY_LOCAL` | 是否在 Agent 本地验证 JWT（true 时需配置密钥），false 时依赖 Gateway 验证 |

---

## 10. 工具调用示例

### 10.1 查看秒杀活动（L1 正则路由）

```
用户: "查看秒杀活动"
  │
  ├─ IntentRouter 匹配 → get_seckill_activities_api
  ├─ httpx GET http://localhost:8080/seckill/activities
  ├─ 代码格式化输出:
  │   ⚡ 当前秒杀活动
  │   ─────────────────────
  │   📢 618 专场 [进行中]
  │   🕐 10:00场 (10:00-12:00) [抢购中]
  │   ├── iPhone 15 | ¥5999 (原价¥6999) | 剩余 45 件
  │   └── MacBook Air | ¥8999 (原价¥9999) | 剩余 12 件
  │   🕐 14:00场 (14:00-16:00) [即将开始]
  │   └── AirPods Pro | ¥1499 (原价¥1999) | 总量 200 件
  └─ 不走 LLM，零成本
```

### 10.2 秒杀下单（L2 状态机 + 二次确认）

```
用户: "秒杀iPhone 15"
  │
  ├─ 查询秒杀活动 → 找到 relationId=1
  ├─ Agent: "确认秒杀以下商品？
  │   商品: iPhone 15 (128G)
  │   秒杀价: ¥5999 (原价 ¥6999)
  │   限购: 1 件
  │   剩余: 45 件
  │   回复"确认"下单"
  │
  ├─ 用户: "确认"
  │
  ├─ httpx POST http://localhost:8080/seckill/order/1?quantity=1
  ├─ 返回 pending → 轮询 GET /seckill/result/1
  │
  └─ Agent: "✅ 秒杀成功！订单号: 123456，请尽快支付。"
```

### 10.3 运营日报（L1 多工具编排）

```
用户: "运营日报"
  │
  ├─ AdminIntentRouter 匹配 → daily_report
  ├─ 流式输出进度:
  │   📊 正在生成 2026-07-15 运营日报...
  │   📋 步骤 1/5: 查询订单统计... ✅
  │   📋 步骤 2/5: 查询秒杀活动... ✅
  │   ...
  ├─ 格式化日报输出
  └─ 不走 LLM，零成本
```

---

## 11. 部署设计

### 11.1 独立部署（推荐）

Agent 作为独立 Python 服务部署，与 Java 微服务解耦：

```
hmall-agent (Python :8090)
  ↓ HTTP
hm-gateway (Java :8080)
  ↓ Feign / 路由
各微服务 (Java :8081-8090)
```

### 11.2 前端集成

在 `hmall-frontend` 中新增 Agent 对话组件：

| 位置 | 组件 | 说明 |
|------|------|------|
| C 端右下角 | `ChatWidget.vue` | 浮动按钮 → 展开对话窗口（WebSocket） |
| 管理后台 | `AdminChat.vue` | 独立页面或侧边栏（SSE 流式） |

### 11.3 启动顺序

```
1. 启动基础设施: MySQL / Redis / Nacos / RabbitMQ
2. 启动 Java 微服务: item → user → cart → trade → pay → search → admin → gateway
3. 启动 Agent: cd hmall-agent && python -m app.main
4. 启动前端: cd hmall-frontend && npm run dev
```

---

## 12. 与 nova-mall-agent 的差异适配

| 差异点 | nova-mall-agent | hmall Agent 适配方案 |
|--------|----------------|---------------------|
| API 调用 | 直连 Portal(8085) + Admin(8080) | 统一经 Gateway(8080) 路由 |
| 认证 | 单 JWT 共享密钥 | 双 JWT（C 端 RSA + 管理端独立 keystore），依赖 Gateway 验证 |
| 优惠券工具 | 5 个（领取/查询/历史） | 移除（hmall 无优惠券系统） |
| 售后工具 | 4 个（查询/申请/状态/退款） | 移除（hmall 无售后系统） |
| 秒杀工具 | 2 个（查活动/加购） | 3 个（查活动/查详情/秒杀下单），适配三层防超卖架构 |
| 管理后台 | 独立 Java Admin | admin-service 微服务 + RBAC，Agent 调 `/admin/**` 代理接口 |
| 商品搜索 | 数据库 LIKE | ES 全文检索（`/search`），支持品牌/分类/价格多维筛选 |
| 地址状态机 | 6 字段 | 6 字段（name/phone/province/city/region/detailAddress），与 hmall Address PO 对齐 |
| 订单取消 | `POST /order/cancel` | `POST /orders/batch/close`（批量关闭接口） |
| ID 精度 | JS Number | hmall 已有 Long→String 序列化保护，Agent 无需特殊处理 |

---

## 13. 后续优化方向

| 方向 | 说明 | 优先级 |
|------|------|--------|
| RAG 知识库 | 运营/商品/秒杀策略知识库，AdminAgent 专业知识问答 | P1 |
| 商品推荐 | 基于用户浏览/购买历史的个性化推荐 | P2 |
| 优惠券系统 | hmall 实现优惠券后，新增 5 个工具 | P2 |
| 售后系统 | hmall 实现售后后，新增 4 个工具 | P2 |
| 多模态 | 支持图片输入（商品图片识别、截图报错） | P3 |
| Agent 工具动态注册 | 从 Nacos 配置中心加载工具定义，无需重启 | P3 |
| 对话分析 | 对话日志分析，挖掘用户高频问题和痛点 | P3 |
