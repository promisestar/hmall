# RAG 知识查询技能（预留）

## 适用场景
运营人员想要查询运营策略、库存管理指南、订单分析方法等专业知识时激活此技能。

## 当前状态
**预留** — 后续集成 LightRAG + MCP 桥接后启用。

## 规划工具
- `rag_query(query)` — 语义检索知识库
- `rag_query_data(query)` — 数据查询
- `rag_graph_search(query)` — 图谱搜索

## 知识库文档（规划中）
- `seckill_strategy.md` — 秒杀运营策略
- `inventory_management.md` — 库存管理指南
- `order_analysis.md` — 订单分析指南
- `user_segmentation.md` — 用户分群方法
- `data_interpretation.md` — 数据指标解读

## 架构规划
```
AdminAgent → RAGMiddleware（enable_rag=true 时注入 RAG 工具）
  → rag_query() → MCP 桥接 → LightRAG Server (:9621)
  → 返回知识库检索结果
```

## 注意事项
- RAG 功能通过 context.enable_rag=true 开启
- RAGMiddleware 根据 enable_rag 动态注入 RAG 工具
- 当前为预留桩，不提供实际功能
