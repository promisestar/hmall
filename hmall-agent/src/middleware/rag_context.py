"""RAGMiddleware — RAG 动态控制中间件（预留）。

根据 context.enable_rag 动态注入 RAG 工具和提示词。
当前为预留桩，后续集成 LightRAG + MCP 桥接后启用。
"""

import logging

from langchain.agents.middleware import AgentMiddleware, ModelRequest

logger = logging.getLogger(__name__)


class RAGMiddleware(AgentMiddleware):
    """RAG 动态控制中间件（预留）。

    根据 context.enable_rag 动态注入 RAG 工具：
    - enable_rag=True → 追加 rag_query / rag_query_data / rag_graph_search 工具
    - enable_rag=False → 不注入 RAG 工具

    当前为预留桩，不做任何操作。
    """

    def wrap_model_call(self, request: ModelRequest, handler):
        """同步版本。"""
        self._maybe_inject_rag(request)
        return handler(request)

    async def awrap_model_call(self, request: ModelRequest, handler):
        """异步版本 — 中间件入口。"""
        self._maybe_inject_rag(request)
        return await handler(request)

    def _maybe_inject_rag(self, request: ModelRequest) -> None:
        """根据 enable_rag 动态注入 RAG 工具（预留）。"""
        context = getattr(request.runtime, "context", None) if request.runtime else None
        if not context:
            return

        enable_rag = getattr(context, "enable_rag", False)
        if not enable_rag:
            return

        # TODO: 后续实现 RAG 工具注入
        # from src.tools.rag_tools import rag_query, rag_query_data, rag_graph_search
        # tools = getattr(request, "tools", []) or []
        # tools.extend([rag_query, rag_query_data, rag_graph_search])
        # request = request.override(tools=tools)
        logger.debug("RAG 已启用，但工具注入尚未实现（预留）")
