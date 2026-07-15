"""RAG MCP Server 预留桩（FastMCP）。

后续集成 LightRAG + MCP 桥接后启用，提供 RAG 查询工具：
- rag_query: 语义检索知识库
- rag_query_data: 数据查询
- rag_graph_search: 图谱搜索
"""

# 预留桩，后续实现
# from fastmcp import FastMCP
#
# mcp = FastMCP("hmall-rag")
#
# @mcp.tool()
# async def rag_query(query: str) -> str:
#     """语义检索知识库。"""
#     ...
#
# @mcp.tool()
# async def rag_query_data(query: str) -> str:
#     """数据查询。"""
#     ...
#
# @mcp.tool()
# async def rag_graph_search(query: str) -> str:
#     """图谱搜索。"""
#     ...
#
# if __name__ == "__main__":
#     mcp.run(transport="http", port=8008)
