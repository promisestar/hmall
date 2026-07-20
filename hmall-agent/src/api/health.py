"""LLM 健康检查路由 — 真实 ping DashScope API 判断 LLM 服务连通性。

通过发送 max_tokens=1 的最小 chat completions 请求验证：
1. API Key 有效性
2. LLM 服务可达性
3. 模型可用性

模块级缓存 10 秒，避免高频轮询消耗 token。
"""

import time

import httpx
from fastapi import APIRouter

from src.core.config import get_settings

router = APIRouter(tags=["health"])

_settings = get_settings()

# 模块级缓存：避免高频轮询消耗 LLM token
_cache: dict = {"result": None, "ts": 0.0}
_CACHE_TTL = 10  # 缓存 10 秒


async def _ping_llm() -> dict:
    """真实 ping LLM API，返回健康状态字典。"""
    if not _settings.DASHSCOPE_API_KEY:
        return {
            "llm_reachable": False,
            "detail": "DASHSCOPE_API_KEY 未配置",
        }

    start = time.monotonic()
    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            resp = await client.post(
                f"{_settings.LLM_API_BASE}/chat/completions",
                headers={
                    "Authorization": f"Bearer {_settings.DASHSCOPE_API_KEY}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": _settings.LLM_MODEL_NAME,
                    "messages": [{"role": "user", "content": "ping"}],
                    "max_tokens": 1,
                },
            )
        latency_ms = int((time.monotonic() - start) * 1000)

        if resp.status_code == 200:
            return {
                "llm_reachable": True,
                "latency_ms": latency_ms,
                "model": _settings.LLM_MODEL_NAME,
            }

        # 常见错误：401 鉴权失败 / 429 限流 / 5xx 服务端错误
        return {
            "llm_reachable": False,
            "latency_ms": latency_ms,
            "detail": f"HTTP {resp.status_code}: {resp.text[:200]}",
        }
    except httpx.TimeoutException:
        return {
            "llm_reachable": False,
            "detail": "LLM API 请求超时（8s）",
        }
    except httpx.ConnectError as e:
        return {
            "llm_reachable": False,
            "detail": f"无法连接 LLM API: {e}",
        }
    except Exception as e:  # noqa: BLE001 — 健康检查需要兜底所有异常
        return {
            "llm_reachable": False,
            "detail": f"未知错误: {e}",
        }


@router.get("/api/v1/llm/health")
async def llm_health() -> dict:
    """LLM API 连通性健康检查。

    返回结构：
    {
        "status": "ok" | "error",          # 整体状态
        "llm_reachable": bool,              # LLM API 是否可达
        "latency_ms": int | null,           # 探测延迟（毫秒）
        "model": str,                       # 模型名称
        "detail": str | null,               # 错误详情
        "cached": bool,                     # 是否命中缓存
        "checked_at": float                 # 检查时间戳
    }
    """
    now = time.monotonic()

    # 命中缓存
    if _cache["result"] and (now - _cache["ts"]) < _CACHE_TTL:
        result = dict(_cache["result"])
        result["cached"] = True
        result["checked_at"] = _cache["ts"]
        return result

    # 真实 ping
    ping_result = await _ping_llm()
    reachable = ping_result.get("llm_reachable", False)

    result = {
        "status": "ok" if reachable else "error",
        "llm_reachable": reachable,
        "latency_ms": ping_result.get("latency_ms"),
        "model": _settings.LLM_MODEL_NAME,
        "detail": ping_result.get("detail"),
        "cached": False,
        "checked_at": time.time(),
    }

    # 写入缓存
    _cache["result"] = result
    _cache["ts"] = now

    return result
