"""RAG MCP 工具加载器 — 封装 langchain-mcp-adapters 连接 RAG MCP Server。

提供模块级缓存的工具加载能力，避免 RAGMiddleware 每次 model_call 都重新连接 MCP Server。

核心接口：
- get_rag_tools(): 异步获取 RAG MCP 工具列表（带缓存）
- is_available(): 同步检查 MCP Server 是否可用（基于上次加载结果）
- refresh(): 强制刷新工具列表（配置变更时使用）
"""

import asyncio
import logging
from typing import Any

from langchain_core.tools import BaseTool

from src.core.config import get_settings

logger = logging.getLogger(__name__)
settings = get_settings()

# 模块级缓存
_rag_tools: list[BaseTool] | None = None
_rag_client: Any = None  # MultiServerMCPClient 实例
_load_lock = asyncio.Lock()
_last_load_success: bool = False


def _get_mcp_endpoint() -> str:
    """构建 MCP Server 端点 URL。"""
    port = settings.RAG_MCP_PORT
    # FastMCP 3.x HTTP transport 的默认端点是 /mcp
    return f"http://localhost:{port}/mcp"


async def get_rag_tools() -> list[BaseTool]:
    """获取 RAG MCP 工具列表（带模块级缓存）。

    首次调用时连接 MCP Server 加载工具，后续直接返回缓存。
    如果首次加载失败，返回空列表（不抛异常，保证不阻塞 Agent）。

    Returns:
        LangChain Tool 列表（rag_query / rag_query_data / rag_graph_search），
        加载失败时返回空列表
    """
    global _rag_tools, _rag_client, _last_load_success

    if _rag_tools is not None:
        return _rag_tools

    async with _load_lock:
        # double-check：其他协程可能已经完成加载
        if _rag_tools is not None:
            return _rag_tools

        try:
            # 延迟导入，避免 MCP Server 未启动时影响 Agent 启动
            from langchain_mcp_adapters.client import MultiServerMCPClient

            endpoint = _get_mcp_endpoint()
            logger.info("连接 RAG MCP Server: %s", endpoint)

            _rag_client = MultiServerMCPClient(
                {
                    "rag": {
                        "url": endpoint,
                        "transport": "streamable_http",
                    }
                }
            )

            _rag_tools = await _rag_client.get_tools()
            _last_load_success = True
            tool_names = [t.name for t in _rag_tools]
            logger.info("RAG MCP 工具加载成功: %s", tool_names)
            return _rag_tools
        except Exception as e:
            logger.warning(
                "RAG MCP 工具加载失败（Agent 将不注入 RAG 工具）: %s", e
            )
            _rag_tools = []
            _last_load_success = False
            return _rag_tools


def is_available() -> bool:
    """检查 RAG MCP 工具是否已成功加载。

    Returns:
        True 表示工具已加载且非空
    """
    return _last_load_success and _rag_tools is not None and len(_rag_tools) > 0


async def refresh() -> list[BaseTool]:
    """强制刷新工具列表（清除缓存后重新加载）。

    适用于 MCP Server 重启或工具变更后重新加载。

    Returns:
        最新的 LangChain Tool 列表
    """
    global _rag_tools, _rag_client, _last_load_success
    async with _load_lock:
        _rag_tools = None
        _rag_client = None
        _last_load_success = False

    return await get_rag_tools()
