"""RAGMiddleware — RAG 动态控制中间件。

根据 context.enable_rag 动态注入 RAG 工具到 Agent：
- enable_rag=True → 通过 rag_loader 加载 MCP RAG 工具并追加到 request.tools
- enable_rag=False → 不注入 RAG 工具

工具加载失败时（MCP Server 不可达）只 log warning 不阻塞，Agent 仍可使用现有业务工具。
"""

import logging

from langchain.agents.middleware import AgentMiddleware, ModelRequest

from src.tools.rag_loader import get_rag_tools, is_available

logger = logging.getLogger(__name__)


class RAGMiddleware(AgentMiddleware):
    """RAG 动态控制中间件。

    根据 context.enable_rag 动态注入 RAG 工具：
    - enable_rag=True → 追加 rag_query / rag_query_data / rag_graph_search 工具
    - enable_rag=False → 不注入 RAG 工具

    MCP Server 不可达时降级为不注入（log warning），保证 Agent 核心功能不受影响。
    """

    def wrap_model_call(self, request: ModelRequest, handler):
        """同步版本（实际 RAG 工具加载是异步的，同步路径不注入）。"""
        self._maybe_inject_rag_sync(request)
        return handler(request)

    async def awrap_model_call(self, request: ModelRequest, handler):
        """异步版本 — 中间件入口。"""
        await self._maybe_inject_rag(request)
        return await handler(request)

    def _maybe_inject_rag_sync(self, request: ModelRequest) -> None:
        """同步注入（仅检查开关，不实际加载异步工具）。

        异步工具加载在 awrap_model_call 中完成。
        同步路径只做开关检查，避免阻塞。
        """
        context = getattr(request.runtime, "context", None) if request.runtime else None
        if not context:
            return

        enable_rag = getattr(context, "enable_rag", False)
        if not enable_rag:
            return

        # 同步路径无法 await get_rag_tools()，只 log 提示
        if not is_available():
            logger.debug("RAG 已启用但工具未加载（同步路径），将在异步路径加载")

    async def _maybe_inject_rag(self, request: ModelRequest) -> None:
        """异步注入 RAG 工具。

        1. 检查 context.enable_rag
        2. enable_rag=True 时调用 rag_loader.get_rag_tools() 获取 MCP 工具
        3. 追加到 request.tools
        4. 失败时 log warning 不阻塞
        """
        context = getattr(request.runtime, "context", None) if request.runtime else None
        if not context:
            return

        enable_rag = getattr(context, "enable_rag", False)
        if not enable_rag:
            return

        # 加载 RAG 工具（带缓存）
        rag_tools = await get_rag_tools()
        if not rag_tools:
            logger.warning(
                "RAG 已启用但无可用工具（MCP Server 可能未启动），"
                "Agent 将不使用 RAG 检索能力"
            )
            return

        # 追加 RAG 工具到现有工具列表
        tools = getattr(request, "tools", None) or []
        # 避免重复注入同名工具
        existing_names = {t.name for t in tools if hasattr(t, "name")}
        new_tools = [t for t in rag_tools if hasattr(t, "name") and t.name not in existing_names]

        if new_tools:
            tools = list(tools) + new_tools
            logger.info(
                "RAG 工具注入成功: %s",
                [t.name for t in new_tools],
            )
            try:
                request = request.override(tools=tools)
            except (AttributeError, TypeError):
                # 降级：直接修改（如果 override 不可用）
                if hasattr(request, "tools"):
                    request.tools = tools
