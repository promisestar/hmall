#!/usr/bin/env python3
"""hmall Agent LangGraph Server 启动入口。

配置环境变量 + 启动 uvicorn + langgraph_api.server。
"""

import json
import os
import sys
from pathlib import Path


def setup_environment():
    """配置 LangGraph 运行环境。"""
    # 确保必要目录存在
    Path(".langgraph_api/ui/public").mkdir(parents=True, exist_ok=True)

    # 添加 src 到 Python 路径
    src_path = Path(__file__).parent / "src"
    sys.path.insert(0, str(src_path))

    # 读取 graph.json
    config_path = Path(__file__).parent / "graph.json"
    graphs = {}
    if config_path.exists():
        with open(config_path, "r", encoding="utf-8") as f:
            config = json.load(f)
            graphs = config.get("graphs", {})

    # 设置环境变量
    os.environ.update({
        "DATABASE_URI": ":memory:",
        "REDIS_URI": "fake",
        "MIGRATIONS_PATH": "__inmem",
        "ALLOW_PRIVATE_NETWORK": "true",
        "LANGGRAPH_UI_BUNDLER": "true",
        "LANGGRAPH_RUNTIME_EDITION": "inmem",
        "LANGSMITH_LANGGRAPH_API_VARIANT": "local_dev",
        "LANGGRAPH_ALLOW_BLOCKING": "true",
        "LANGGRAPH_API_URL": f"http://localhost:{os.getenv('AGENT_PORT', '8091')}",
        # Agent 图注册
        "LANGSERVE_GRAPHS": json.dumps(graphs) if graphs else "{}",
        # 自定义路由
        "LANGGRAPH_HTTP": json.dumps({"app": "api.batch_report:app"}),
        "N_JOBS_PER_WORKER": "3",
    })

    # 加载 .env
    env_file = Path(__file__).parent / ".env"
    if env_file.exists():
        from dotenv import load_dotenv
        load_dotenv(env_file)


def main():
    setup_environment()

    port = int(os.getenv("AGENT_PORT", "8091"))

    # 导入 LangGraph Server 的 FastAPI app 并添加 CORS 中间件
    # 必须先 setup_environment()（设置 langgraph 所需环境变量），再导入
    from fastapi.middleware.cors import CORSMiddleware
    from langgraph_api.server import app as langgraph_app

    langgraph_app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],          # 开发环境允许所有来源
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    print(f"🚀 Starting hmall Agent Server on port {port}")
    print(f"📍 API:      http://localhost:{port}")
    print(f"📚 Docs:     http://localhost:{port}/docs")
    print(f"🎨 Studio:   http://localhost:{port}/ui")
    print(f"💚 Health:   http://localhost:{port}/ok")

    import uvicorn
    uvicorn.run(
        langgraph_app,
        host="0.0.0.0",
        port=port,
        reload=False,
    )


if __name__ == "__main__":
    main()
