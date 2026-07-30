# hmall-agent

> 枫叶商城 AI 智能助手（DeepAgent 架构 v2.0）
> 
> 基于 DeepAgents + LangGraph 构建，包含客服助手（CustomerAgent）和管理助手（AdminAgent）两个 Agent。

## 架构概览

```
用户（C端 / 管理后台）
  │
  │  LangGraph SDK (HTTP + SSE)
  ▼
Agent Service (LangGraph Server :8090)
  ├── 中间件层：AuthMiddleware → PermissionMiddleware → RegexShortcutMiddleware → SkillsMiddleware
  ├── CustomerAgent：18 个工具，5 个 Skills
  ├── AdminAgent：10 个工具，3 个 Skills
  └── 用户画像（Redis db=0，与后端共享）
  │
  │ HTTP (httpx)
  ▼
hm-gateway (:8080) → 各微服务 (:8081-:8090)
```

## 核心特性

- **三级路由**：L1 正则中间件（<5ms）→ L2 interrupt 状态机 → L3 LLM 兜底
- **双 JWT 认证**：C 端用户 JWT 和管理后台 JWT 独立验证
- **二次确认**：危险操作通过 LangGraph interrupt() 实现 Human-in-the-loop
- **Agent 零数据库**：所有数据操作通过 Gateway → 微服务 API 完成
- **用户画像持久化**：Redis Hash/List 增量聚合（db=0），Agent 侧与后端共享

## 快速开始

### 1. 环境要求

- Python >= 3.12
- uv（Python 包管理工具）
- hmall Java 后端服务运行中（Gateway :8080）
- Redis 运行中

### 2. 安装依赖

```bash
cd hmall-agent
uv sync
```

### 3. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY 等配置
```

### 4. 启动 Agent 服务

```bash
uv run python start_server.py
```

服务启动后：
- API: http://localhost:8090
- Docs: http://localhost:8090/docs
- Studio: http://localhost:8090/ui
- Health: http://localhost:8090/ok

### 5. 前端集成

```bash
cd hmall-frontend
npm install     # 安装 @langchain/langgraph-sdk
npm run dev
```

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DASHSCOPE_API_KEY` | 通义千问 API Key | — |
| `LLM_MODEL_NAME` | LLM 模型名 | qwen-turbo |
| `REDIS_HOST` | Redis 主机 | localhost |
| `PROFILE_REDIS_DB` | 画像 Redis DB（须与后端 spring.redis.database 一致） | 0 |
| `JAVA_GATEWAY_URL` | hmall Gateway 地址 | http://localhost:8080 |
| `AGENT_PORT` | Agent 服务端口 | 8090 |
| `JWT_VERIFY_LOCAL` | 是否本地验证 JWT | false（依赖 Gateway） |
| `RAG_BASE_URL` | LightRAG Server 地址 | http://localhost:9621 |
| `RAG_USERNAME` / `RAG_PASSWORD` | LightRAG 登录凭证 | admin / admin123 |
| `RAG_API_KEY` | LightRAG API Key（可选，优先于账号密码） | — |
| `RAG_MCP_PORT` | RAG MCP Server 端口 | 8008 |

## API 端点

### LangGraph 标准端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/assistants/search` | POST | 获取助手列表 |
| `/threads` | POST | 创建线程 |
| `/threads/{id}/runs/stream` | POST | 流式执行（SSE） |
| `/threads/{id}` | DELETE | 删除线程 |

### 自定义端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/batch-report` | POST | 批量运营报告 |
| `/api/v1/health` | GET | 健康检查 |

## Agent 注册

`graph.json` 定义了两个 Agent：

| Agent ID | 路径 | 说明 |
|----------|------|------|
| `customer_agent` | `./src/agents/customer/agent.py:agent` | C 端客服助手 |
| `admin_agent` | `./src/agents/admin/agent.py:agent` | 管理助手 |

## 项目结构

```
hmall-agent/
├── start_server.py          # 服务启动入口
├── graph.json               # Agent 注册配置
├── pyproject.toml           # 项目依赖
├── .env.example             # 环境变量模板
├── src/
│   ├── core/                # 核心配置（config/llms）
│   ├── gateway/             # HTTP 客户端 + JWT 验证
│   ├── middleware/          # 中间件（auth/permission/regex/rag）
│   ├── agents/
│   │   ├── customer/        # 客服 Agent（18 工具）
│   │   └── admin/           # 管理 Agent（10 工具）
│   ├── tools/               # 格式化工具
│   ├── api/                 # 自定义 API 路由
│   └── workspace/           # Skills 规范文件
└── tests/                   # 测试
```

## 启动顺序

1. 启动基础设施：MySQL / Redis / Nacos / RabbitMQ
2. 启动 Java 微服务：item → user → cart → trade → pay → search → admin → gateway
3. 启动 Agent 服务：`uv run python start_server.py`
4. 启动前端：`npm run dev`

### RAG 知识库（可选）

如需启用 RAG 知识库检索能力，需额外启动 LightRAG Server 和 RAG MCP Server：

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

启动后在前端对话面板头部点击「知识库」开关按钮启用 RAG 检索。

详细部署和使用说明见 `docs/Agent功能相关文档/hmall-agent-rag-integration.md`。
