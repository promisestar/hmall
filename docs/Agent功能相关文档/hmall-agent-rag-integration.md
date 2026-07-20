# hmall Agent RAG 知识库集成文档

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
