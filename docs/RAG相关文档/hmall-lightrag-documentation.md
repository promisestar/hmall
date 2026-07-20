# hmall 项目 LightRAG 知识检索系统说明文档

> 版本：v1.0
> 日期：2026-07-20
> 涵盖 LightRAG 项目简介、在 hmall 中的使用方式、知识库管理及文档入库指南

---

## 目录

- [一、LightRAG 项目简介](#一lightrag-项目简介)
- [二、LightRAG 在 hmall 项目中的使用位置](#二lightrag-在-hmall-项目中的使用位置)
  - [2.1 整体架构](#21-整体架构)
  - [2.2 后端使用清单](#22-后端使用清单)
  - [2.3 前端使用清单](#23-前端使用清单)
  - [2.4 文档清单](#24-文档清单)
- [三、知识库文档管理](#三知识库文档管理)
  - [3.1 已规划的知识库内容](#31-已规划的知识库内容)
  - [3.2 通过 WebUI 入库文档](#32-通过-webui-入库文档)
  - [3.3 通过 API 入库文档](#33-通过-api-入库文档)
  - [3.4 文档入库处理流程](#34-文档入库处理流程)
- [四、LightRAG 技术深度介绍](#四lightrag-技术深度介绍)
  - [4.1 核心架构](#41-核心架构)
  - [4.2 六种检索模式](#42-六种检索模式)
  - [4.3 存储体系](#43-存储体系)
  - [4.4 文档处理管线](#44-文档处理管线)
- [五、配置与启动](#五配置与启动)
  - [5.1 LightRAG 端配置](#51-lightrag-端配置)
  - [5.2 Agent 端配置](#52-agent-端配置)
  - [5.3 启动顺序](#53-启动顺序)
- [六、故障排查](#六故障排查)

---

## 一、LightRAG 项目简介

**LightRAG**（全称 _Simple and Fast Retrieval-Augmented Generation_）是由香港大学数据科学实验室（HKUDS）开发的开源 RAG 引擎。其核心创新在于**将知识图谱（Graph RAG）与向量检索（Vector RAG）深度融合**，在保证检索效率的同时提供更丰富的语义关联能力。

### 核心特性

| 特性 | 说明 |
|------|------|
| **双重检索** | 知识图谱检索 + 向量语义检索，支持 6 种查询模式 |
| **知识图谱自动构建** | 入库时自动抽取实体和关系，构建知识图谱 |
| **多存储后端** | 支持 NetworkX（内存）、Neo4j、PostgreSQL、MongoDB、Milvus 等 |
| **多模态支持** | 集成 RAG-Anything，支持 PDF、图片、Office 文档、表格等格式 |
| **WebUI 管理界面** | 内置 React 前端，支持文档上传、知识检索、图谱可视化 |
| **企业级特性** | OAuth2 认证、Reranker 重排序、引用溯源、文档删除与 KG 重建、RAGAS 评估、Langfuse 追踪 |

### 在 hmall 项目中的角色

LightRAG 作为 **git submodule** 集成到 hmall 项目中，独立部署为 REST API 服务（默认端口 9621），通过 MCP（Model Context Protocol）协议与 Agent 服务桥接，为 CustomerAgent（C 端客服）和 AdminAgent（管理助手）提供知识库检索能力。

- **GitHub 仓库**：[https://github.com/HKUDS/LightRAG](https://github.com/HKUDS/LightRAG)
- **学术论文**：[arXiv 2410.05779](https://arxiv.org/abs/2410.05779)
- **项目路径**：`d:/Code/hmall/LightRAG`（git submodule）

---

## 二、LightRAG 在 hmall 项目中的使用位置

### 2.1 整体架构

```
用户前端 (ChatPanel.vue)
  │  头部「知识库」开关 → enable_rag → sessionStorage 持久化
  │  sendMessage(text, {agent_type, user_token, enable_rag})
  ▼
Agent Server (start_server.py, :8090)
  ├─ RAGMiddleware (src/middleware/rag_context.py)
  │    │ enable_rag=true  → 动态注入 RAG 工具
  │    │ enable_rag=false → 仅使用业务工具
  │    │ 失败时 log warning，不阻塞主流程
  ▼
RAG MCP Server (start_rag_server.py, :8008)
  ├─ LightRAGClient（httpx 异步客户端）
  │    ├─ JWT access_token 缓存 + 401 自动重新登录
  │    └─ 可选 X-API-Key 认证
  │    └─ 3 个 MCP 工具：rag_query / rag_query_data / rag_graph_search
  ▼
LightRAG Server (lightrag-server, :9621)
  ├─ POST /query       → 语义检索 + LLM 生成答案
  ├─ POST /query/data  → 结构化检索（实体/关系/文本块）
  ├─ POST /documents/upload → 文档入库
  └─ WebUI (:9621/webui) → 知识库文档管理
```

### 2.2 后端使用清单

#### 核心实现（3 个文件）

| 文件 | 职责 |
|------|------|
| `hmall-agent/src/mcp_servers/rag_server.py` | **RAG MCP Server**：FastMCP HTTP 服务，封装 LightRAGClient + 3 个 MCP 工具（rag_query / rag_query_data / rag_graph_search），内部通过 `httpx.AsyncClient` 调用 LightRAG REST API |
| `hmall-agent/src/middleware/rag_context.py` | **RAGMiddleware**：Agent 中间件，检查 `context.enable_rag`，为 true 时通过 `rag_loader.py` 获取 MCP RAG 工具并动态注入到 Agent 工具列表 |
| `hmall-agent/src/tools/rag_loader.py` | **MCP 工具加载器**：封装 `langchain-mcp-adapters` 的 `MultiServerMCPClient`，连接 `http://localhost:8008`（streamable_http 传输），模块级缓存工具列表 |

#### Agent 集成（4 个文件）

| 文件 | 集成方式 |
|------|---------|
| `hmall-agent/src/agents/customer/agent.py` | 中间件链含 `RAGMiddleware()`，Context 含 `enable_rag: bool = False`，Skills sources 含 `/skills/rag-query/` |
| `hmall-agent/src/agents/admin/agent.py` | 中间件链含 `RAGMiddleware()`，Context 含 `enable_rag: bool = False`，Skills sources 含 `/skills/rag-query/` |
| `hmall-agent/src/agents/customer/prompts.py` | System Prompt 第 7 条能力描述：RAG 知识库检索（退换货政策、支付方式、配送说明等） |
| `hmall-agent/src/agents/admin/prompts.py` | System Prompt 第 6 条能力描述：RAG 知识库检索（运营策略、库存管理指南、订单分析方法等） |

#### Skills 规范（2 个文件）

| 文件 | 适用场景 |
|------|---------|
| `hmall-agent/src/workspace/customer/skills/rag-query/SKILL.md` | C 端 RAG 技能规范：退换货政策、支付方式、配送说明、会员权益等商城常见知识 |
| `hmall-agent/src/workspace/admin/skills/rag-query/SKILL.md` | 管理端 RAG 技能规范：运营策略、库存管理指南、订单分析方法、秒杀活动策划等专业知识 |

#### 启动脚本（1 个文件）

| 文件 | 说明 |
|------|------|
| `hmall-agent/start_rag_server.py` | RAG MCP Server 独立启动入口（端口 8008），配置环境变量后启动 FastMCP 服务 |

#### 配置（2 个文件）

| 文件 | 说明 |
|------|------|
| `hmall-agent/src/core/config.py` | 配置类定义 7 个 RAG 环境变量 |
| `hmall-agent/.env.example` | RAG 配置模板 |

### 2.3 前端使用清单

| 文件 | 改动 |
|------|------|
| `hmall-frontend/src/components/chat/ChatPanel.vue` | 头部右侧「知识库」开关按钮（书本图标 + 状态指示灯），`ragEnabled` 状态持久化到 `sessionStorage`，`handleSend` / `handleQuickAction` 调用 `sendMessage` 时传入 `enable_rag` |
| `hmall-frontend/src/composables/useLangGraph.ts` | `AgentContext` 接口已预留 `enable_rag?: boolean` 字段 |

### 2.4 文档清单

| 文件 | 说明 |
|------|------|
| `docs/Agent功能相关文档/hmall-agent-rag-integration.md` | RAG 集成完整文档（架构/部署/使用/配置/故障排查） |
| `docs/Agent功能相关文档/hmall-agent-design.md` 第 16 章 | RAG 集成设计章节 |
| `docs/Agent功能相关文档/hmall-agent-implementation-report.md` 第 3.8.4/3.8.5/8.2 节 | RAG 实现报告 |
| `docs/RAG相关文档/hmall-lightrag-documentation.md` | **本文档** — LightRAG 完整使用说明 |
| `hmall-agent/README.md` | 启动顺序含 RAG 可选步骤 |

---

## 三、知识库文档管理

### 3.1 已规划的知识库内容

LightRAG 知识库按 Agent 角色划分为两类文档：

#### C 端客服知识库（CustomerAgent 使用）

| 文档类别 | 示例内容 | 检索场景 |
|---------|---------|---------|
| 退换货政策 | 7 天无理由退货条件、换货流程、退款时效 | 用户询问"怎么退货？""换货需要多久？" |
| 支付方式说明 | 支持的支付渠道、分期规则、支付限额 | 用户询问"支持微信支付吗？""可以分期吗？" |
| 配送说明 | 配送范围、运费标准、预计时效 | 用户询问"包邮吗？""几天能到？" |
| 会员权益 | 会员等级、积分规则、专属优惠 | 用户询问"会员有什么好处？" |
| 常见问题 FAQ | 账号问题、订单问题、优惠券使用 | 通用客服场景 |

#### 管理端运营知识库（AdminAgent 使用）

| 文档类别 | 示例内容 | 检索场景 |
|---------|---------|---------|
| 运营策略指南 | 促销活动策划方法、定价策略、用户分群 | 运营询问"怎么设置秒杀活动？" |
| 库存管理指南 | 安全库存设置、补货策略、库存预警 | 运营询问"库存设置多少合适？" |
| 订单分析方法 | 订单漏斗分析、转化率优化、异常订单排查 | 运营询问"怎么分析订单转化率？" |
| 商品运营规范 | 商品上架规范、类目管理、商品标签体系 | 运营询问"商品怎么分类？" |
| 数据报表解读 | 日报/周报指标说明、数据波动归因 | 运营询问"GMV 下降怎么看？" |

> **注**：当前知识库内容为框架预留，实际文档由运营人员通过 LightRAG WebUI 上传维护。LightRAG 作为 git submodule 已就绪，Agent 集成代码已完成，RAG 能力开关已实现。

### 3.2 通过 WebUI 入库文档

LightRAG 内置 WebUI 管理界面，支持可视化的文档上传和管理。

#### 步骤

1. **启动 LightRAG Server**

```bash
cd d:/Code/hmall/LightRAG
lightrag-server
```

2. **访问 WebUI**

打开浏览器访问 `http://localhost:9621/webui`

3. **登录**

使用 `.env` 中 `AUTH_ACCOUNTS` 配置的账号密码（默认 `admin:admin123`）

4. **上传文档**

进入 **Document Management** 页面：
- 点击上传按钮
- 选择文档文件（支持 PDF / DOCX / TXT / Markdown / Excel 等格式）
- 确认上传

5. **等待处理**

LightRAG 自动执行入库管线（分块 → 向量嵌入 → 实体抽取 → 图谱构建），处理完成后文档状态变为 `PROCESSED`。

6. **验证入库**

进入 **Retrieval** 页面，输入查询关键词验证检索效果。

#### 支持的文件格式

| 格式 | 说明 |
|------|------|
| PDF | 含文本的 PDF（扫描版需 OCR） |
| DOCX | Microsoft Word 文档 |
| TXT | 纯文本文件 |
| Markdown (.md) | Markdown 格式文档 |
| Excel (.xlsx) | 表格数据 |
| 图片 | 通过 RAG-Anything 多模态扩展支持 |

### 3.3 通过 API 入库文档

也可以通过 LightRAG REST API 编程式入库：

```bash
# 上传文档（需先获取 JWT token）
curl -X POST http://localhost:9621/documents/upload \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/document.pdf"
```

> 获取 token：`POST /login`，form-data 传入 `username` 和 `password`。

### 3.4 文档入库处理流程

LightRAG 收到文档后，自动执行以下处理管线：

```
Step 1: 文档分块 (Chunking)
  └─ 按 token 滑动窗口分块（默认 1200 tokens/块，重叠 100 tokens）

Step 2: 向量嵌入 (Embedding)
  └─ 对每个 chunk 生成向量 → 存入向量数据库

Step 3: 实体和关系抽取 (Entity & Relation Extraction)
  └─ 对每个 chunk 调用 LLM 抽取实体（如"秒杀活动""库存""商品"）
  └─ 抽取实体间关系（如"秒杀活动 ⏤影响⏤ 库存"）

Step 4: 知识图谱合并 (Graph Merging)
  └─ 新实体与已有实体去重合并
  └─ 新关系与已有关系合并
  └─ 更新知识图谱索引
```

---

## 四、LightRAG 技术深度介绍

### 4.1 核心架构

LightRAG 的核心设计是**将知识图谱检索（Graph RAG）和向量语义检索（Vector RAG）融合为统一的检索管线**：

```
                  ┌──────────────┐
用户查询 ────────►│ 关键词抽取    │◄── LLM 提取 hl_keywords / ll_keywords
                  └──────┬───────┘
                         │
         ┌───────────────┼───────────────┐
         ▼                               ▼
┌─────────────────┐             ┌─────────────────┐
│ 知识图谱检索      │             │ 向量语义检索      │
│ (Graph RAG)      │             │ (Vector RAG)     │
├─────────────────┤             ├─────────────────┤
│ • 实体相似度匹配  │             │ • chunk 向量匹配  │
│ • 关系网络遍历    │             │ • 语义相似度排序  │
│ • 社区发现       │             │ • 可选 Reranker  │
│ (local/global)   │             │ (naive)          │
└────────┬────────┘             └────────┬────────┘
         │                               │
         └───────────┬───────────────────┘
                     ▼
         ┌─────────────────────┐
         │ 上下文拼接 + LLM 生成 │
         │ → 最终答案 + 引用来源 │
         └─────────────────────┘
```

### 4.2 六种检索模式

LightRAG 提供 6 种查询模式，Agent 可通过 MCP 工具的 `mode` 参数选择：

| 模式 | 原理 | 适用场景 | 速度 |
|------|------|---------|------|
| **mix**（默认） | 知识图谱 + 向量检索融合，可选 Reranker 重排序 | 通用场景，推荐首选 | ⭐⭐⭐ |
| **hybrid** | local 图谱 + global 图谱结果去重拼接 | 需要全面覆盖的场景 | ⭐⭐ |
| **local** | 基于实体嵌入相似度检索相关实体和邻居关系 | 精确实体查询 | ⭐⭐⭐⭐ |
| **global** | 社区发现 + 摘要聚合，适合宏观问题 | 总结性问题（如"整个项目包含哪些模块"） | ⭐⭐ |
| **naive** | 纯向量语义检索，不涉及知识图谱 | 关键词匹配 | ⭐⭐⭐⭐⭐ |
| **bypass** | 直接转发 LLM，不检索知识库 | 普通对话 | ⭐⭐⭐⭐⭐ |

```python
# MCP 工具调用示例
rag_query(query="秒杀活动库存设置多少合适？", mode="mix")
rag_query_data(query="退换货政策", mode="hybrid")
rag_graph_search(query="用户分群与购买行为")
```

### 4.3 存储体系

LightRAG 采用三层存储架构，通过 `source_id` 链表关联：

```
┌───────────────────────────────────────────────┐
│ 原始文本层 (KV Storage)                        │
│   full_docs: "doc-{md5}" → 原始文档全文        │
│   text_chunks: "chunk-{md5}" → 分块文本内容    │
└──────────────┬────────────────────────────────┘
               │ source_id 链
┌──────────────▼────────────────────────────────┐
│ 语义检索层 (Vector Storage)                    │
│   chunks_vdb: chunk 向量 + entity 向量         │
│   entities_vdb: 实体向量 + source_id 回溯      │
└──────────────┬────────────────────────────────┘
               │ source_id 链
┌──────────────▼────────────────────────────────┐
│ 关系推理层 (Graph Storage)                     │
│   graph nodes: 实体 (名称/类型/描述/度中心性)   │
│   graph edges: 关系 (源/目标/关键词/权重)       │
│   entity_chunks: 实体↔chunk 映射表             │
└───────────────────────────────────────────────┘
```

**配置方式：纯环境变量，不需要专门的 API**

LightRAG 的所有存储后端通过以下四个环境变量配置，**无需调用任何 API**，只需在 `.env` 中设置即可：

| 环境变量 | 职责 | 默认值 |
|---------|------|--------|
| `LIGHTRAG_GRAPH_STORAGE` | 知识图谱（实体节点 + 关系边） | `NetworkXStorage` |
| `LIGHTRAG_VECTOR_STORAGE` | 向量存储（chunk 向量 + entity 向量） | `NanoVectorDBStorage` |
| `LIGHTRAG_KV_STORAGE` | 键值存储（原始文档 + 分块文本） | `JsonKVStorage` |
| `LIGHTRAG_DOC_STATUS_STORAGE` | 文档处理状态 | `JsonDocStatusStorage` |

**可选存储后端完整矩阵**：

| 存储层 | 开发环境（零依赖） | 单机生产 | 集群/高可用生产 |
|--------|-------------------|---------|----------------|
| **图存储** | `NetworkXStorage`（内存） | `Neo4JStorage` / `PGGraphStorage`（AGE） | `Neo4JStorage` / `MongoGraphStorage` / `OpenSearchGraphStorage` / `MemgraphStorage` |
| **向量存储** | `NanoVectorDBStorage`（内存） | `MilvusVectorDBStorage` / `PGVectorStorage` | `MilvusVectorDBStorage` / `QdrantVectorDBStorage` / `OpenSearchVectorDBStorage` / `MongoVectorDBStorage` |
| **KV 存储** | `JsonKVStorage`（文件） | `RedisKVStorage` / `PGKVStorage` | `MongoKVStorage` / `OpenSearchKVStorage` |
| **文档状态** | `JsonDocStatusStorage`（文件） | `PGDocStatusStorage` | `MongoDocStatusStorage` / `OpenSearchDocStatusStorage` |

**hmall 项目当前使用的存储配置**（开发环境，零外部依赖）：

```bash
# .env
LIGHTRAG_KV_STORAGE=JsonKVStorage
LIGHTRAG_DOC_STATUS_STORAGE=JsonDocStatusStorage
LIGHTRAG_GRAPH_STORAGE=NetworkXStorage
LIGHTRAG_VECTOR_STORAGE=NanoVectorDBStorage
```

> 开发环境四个后端均使用**零外部依赖**的实现，LightRAG Server 启动即用，无需额外安装数据库。

**生产环境推荐配置示例**：

```bash
# 方案一：PostgreSQL 一体化（推荐，图+向量+KV 三者合一）
LIGHTRAG_KV_STORAGE=PGKVStorage
LIGHTRAG_DOC_STATUS_STORAGE=PGDocStatusStorage
LIGHTRAG_GRAPH_STORAGE=PGGraphStorage         # 需 pg 安装 Apache AGE 插件
LIGHTRAG_VECTOR_STORAGE=PGVectorStorage        # 需 pg 安装 pgvector 插件
POSTGRES_URI=postgresql://user:pass@host:5432/lightrag

# 方案二：Neo4j（图）+ Milvus（向量）+ Redis（KV）
LIGHTRAG_GRAPH_STORAGE=Neo4JStorage
LIGHTRAG_VECTOR_STORAGE=MilvusVectorDBStorage
LIGHTRAG_KV_STORAGE=RedisKVStorage
NEO4J_URI=bolt://localhost:7687
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=your_password
MILVUS_URI=http://localhost:19530
REDIS_URI=redis://localhost:6379
```

**各方案对比**：

| 维度 | 开发环境（当前） | PostgreSQL 一体化 | Neo4j + Milvus + Redis |
|------|:---:|:---:|:---:|
| 外部依赖 | 无 | 仅 PostgreSQL | Neo4j + Milvus + Redis |
| 图查询性能 | 一般 | 一般（AGE） | **优秀** |
| 向量检索性能 | 一般 | 良好 | **优秀**（Milvus 专长） |
| 数据持久化 | JSON 文件 | ✅ 数据库 | ✅ 数据库 |
| 运维复杂度 | 零 | 低 | 中等 |
| 适用规模 | 开发/测试 | 中小规模生产 | 大规模生产 |

> **注**：切换存储后端只需修改 `.env` 中的四个 `LIGHTRAG_*_STORAGE` 变量 + 对应的连接 URI，**无需修改任何代码**。数据库实例需事先创建，LightRAG 仅负责在实例内建表/建索引。

### 4.4 文档处理管线

#### 阶段 1：文档分块

```
原始文档
  └─ 按 token 滑动窗口分块
       chunk_token_size = 1200 tokens（默认）
       chunk_overlap_token_size = 100 tokens
       → 步长 = 1200 - 100 = 1100 tokens
  └─ 输出：[{tokens, content, chunk_order_index}, ...]
```

#### 阶段 2：实体和关系抽取

对每个 chunk 调用 LLM，按以下格式抽取：

```
实体格式: entity<|#|>实体名<|#|>实体类型<|#|>实体描述
关系格式: relation<|#|>源实体<|#|>目标实体<|#|>关系关键词<|#|>关系描述
```

默认实体类型（11 类）：`Person, Creature, Organization, Location, Event, Concept, Method, Content, Data, Artifact, NaturalObject`

#### 阶段 3：图合并

- **同名实体** → 合并描述，更新向量
- **同名关系**（source+target 相同）→ 追加关键词，合并描述
- **新实体/关系** → 插入图谱

---

## 五、配置与启动

### 5.1 LightRAG 端配置

LightRAG 项目位于 `d:/Code/hmall/LightRAG`，需在项目根目录创建 `.env` 文件：

```bash
# LLM 配置（用于实体抽取和答案生成）
LLM_BINDING=openai
LLM_BINDING_HOST=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_BINDING_API_KEY=your_dashscope_api_key
LLM_MODEL=qwen-turbo

# Embedding 配置（用于向量检索）
EMBEDDING_BINDING=openai
EMBEDDING_BINDING_HOST=https://dashscope.aliyuncs.com/compatible-mode/v1
EMBEDDING_BINDING_API_KEY=your_dashscope_api_key
EMBEDDING_MODEL=text-embedding-v3

# 认证配置
AUTH_ACCOUNTS=admin:admin123

# 服务器配置
HOST=0.0.0.0
PORT=9621
```

### 5.2 Agent 端配置

`hmall-agent/.env` 中的 RAG 相关配置：

```bash
# RAG（LightRAG + MCP）
RAG_BASE_URL=http://localhost:9621       # LightRAG Server 地址
RAG_USERNAME=admin                       # LightRAG 登录用户名
RAG_PASSWORD=admin123                    # LightRAG 登录密码
RAG_SPACE_ID=hmall_space                 # 工作空间隔离标识
RAG_API_KEY=                             # API Key（可选，优先于账号密码）
RAG_AUTH_ENABLED=true                    # 是否启用认证
RAG_MCP_PORT=8008                        # RAG MCP Server 监听端口
```

### 5.3 启动顺序

```bash
# Step 1: 启动 LightRAG Server（端口 9621）
cd d:/Code/hmall/LightRAG
lightrag-server

# Step 2: 启动 RAG MCP Server（端口 8008）
cd d:/Code/hmall/hmall-agent
uv run python start_rag_server.py

# Step 3: 启动 Agent Server（端口 8090）
uv run python start_server.py

# Step 4: 启动前端
cd d:/Code/hmall/hmall-frontend
npm run dev
```

**验证服务可用**：

```bash
# 验证 LightRAG Server
curl http://localhost:9621/health

# 验证 RAG MCP Server（启动日志应显示 Mountain icon + 端口信息）

# 验证 LLM 连通性
curl http://localhost:8090/api/v1/llm/health
```

---

## 六、故障排查

| 问题 | 排查步骤 |
|------|---------|
| **前端开启「知识库」后 Agent 不使用 RAG 工具** | 1. 检查 LightRAG Server 是否启动：`curl http://localhost:9621/health`；2. 检查 RAG MCP Server 是否启动：查看 `start_rag_server.py` 启动日志；3. 检查 `RAG_BASE_URL` / `RAG_USERNAME` / `RAG_PASSWORD` 配置是否正确；4. 查看 Agent Server 日志中是否有 RAG 工具加载警告 |
| **知识库无检索结果** | 1. 访问 LightRAG WebUI 确认文档已上传并状态为 `PROCESSED`；2. 尝试在 WebUI 中直接输入查询关键词测试；3. 调整查询模式（如 mix → hybrid） |
| **LightRAG 登录失败** | 1. 检查 LightRAG `.env` 中 `AUTH_ACCOUNTS` 格式：`username:password`（明文）；2. 确认 `RAG_USERNAME` / `RAG_PASSWORD` 与 LightRAG 配置一致；3. 如果未配置认证，设置 `RAG_AUTH_ENABLED=false` |
| **文档入库后检索不到** | 1. 确认文档处理状态为 `PROCESSED`（非 `PENDING`）；2. 检查 chunk_token_size 是否过小导致信息丢失；3. 尝试用更具体的关键词查询 |
| **RAG MCP Server 启动失败** | 1. 确认 `fastmcp` / `httpx` 已安装：`uv sync`；2. 检查端口 8008 是否被占用；3. 确认 LightRAG Server 已先启动并可访问 |

---

> 更多细节请参考：
> - RAG 集成完整文档：`docs/Agent功能相关文档/hmall-agent-rag-integration.md`
> - RAG 设计文档：`docs/Agent功能相关文档/hmall-agent-design.md` 第 16 章
> - LightRAG 官方仓库：[https://github.com/HKUDS/LightRAG](https://github.com/HKUDS/LightRAG)
