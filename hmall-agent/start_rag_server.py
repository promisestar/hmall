#!/usr/bin/env python3
"""RAG MCP Server 独立启动入口。

启动 FastMCP HTTP Server（默认端口 8008），桥接 LightRAG REST API，
供 hmall-agent 的 RAGMiddleware 通过 langchain-mcp-adapters 连接使用。

启动前确保：
1. LightRAG Server 已启动（默认 http://localhost:9621）
2. .env 中 RAG_USERNAME / RAG_PASSWORD 配置正确
3. LightRAG 知识库已导入文档（通过 LightRAG WebUI 上传）

启动方式：
    uv run python start_rag_server.py
"""

import logging
import sys
from pathlib import Path


def setup_environment():
    """配置运行环境。"""
    # 添加 src 到 Python 路径
    src_path = Path(__file__).parent / "src"
    sys.path.insert(0, str(src_path))

    # 加载 .env
    env_file = Path(__file__).parent / ".env"
    if env_file.exists():
        from dotenv import load_dotenv
        load_dotenv(env_file)


def main():
    setup_environment()

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )

    from src.core.config import get_settings
    from src.mcp_servers.rag_server import mcp

    settings = get_settings()
    port = settings.RAG_MCP_PORT

    print(f"🚀 Starting RAG MCP Server on port {port}")
    print(f"📍 LightRAG API: {settings.RAG_BASE_URL}")
    print(f"🔐 Auth: {'API Key' if settings.RAG_API_KEY else 'JWT (username/password)'}")
    print(f"🛠️  Tools: rag_query, rag_query_data, rag_graph_search")
    print(f"🔗 MCP Endpoint: http://localhost:{port}/mcp")

    mcp.run(transport="http", port=port)


if __name__ == "__main__":
    main()
