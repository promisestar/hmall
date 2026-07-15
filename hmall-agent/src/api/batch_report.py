"""自定义 FastAPI 路由 — 批量运营报告。

通过 LANGGRAPH_HTTP 环境变量挂载到 LangGraph Server。
"""

import httpx
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI()


class BatchReportRequest(BaseModel):
    """批量运营报告请求。"""
    assistant_id: str = "admin_agent"
    message: str = "运营日报"


@app.post("/api/v1/batch-report")
async def batch_report(request: BatchReportRequest):
    """批量运营报告（内部调用 AdminAgent）。

    通过 LangGraph 通用端点调用 admin_agent 生成运营日报。
    """
    payload = {
        "assistant_id": request.assistant_id,
        "input": {
            "messages": [
                {"type": "human", "content": request.message}
            ]
        },
    }
    async with httpx.AsyncClient(timeout=60.0) as client:
        resp = await client.post(
            "http://localhost:8090/runs/wait",
            json=payload,
        )
        return resp.json()


@app.get("/api/v1/health")
async def health():
    """健康检查端点。"""
    return {"status": "ok", "service": "hmall-agent"}
