"""通过 Gateway 权威接口解析当前用户身份（方案 3：introspect）。

C 端：GET /users/me → { userId, agentType }
管理端：GET /admin/info → AdminInfoVO.id（经 R<T> 解包）

userId 以 Gateway 验签后的 UserContext 为准，Agent 不再把本地 JWT 解码当作权威来源。
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import time
from typing import Any

from src.core.config import get_settings
from src.gateway.http_client import GatewayError, gateway_client
from src.security.jwt_payload import extract_identity_from_token

logger = logging.getLogger(__name__)
_settings = get_settings()

# token_hash → (expire_at, identity_dict)
_cache: dict[str, tuple[float, dict[str, str]]] = {}
_cache_lock = asyncio.Lock()


class IntrospectError(Exception):
    """身份探查失败。"""

    def __init__(self, message: str, status_code: int = 401):
        super().__init__(message)
        self.status_code = status_code


def _cache_key(token: str, agent_type: str) -> str:
    digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
    return f"{agent_type}:{digest}"


def _guess_agent_type(token: str, explicit: str | None = None) -> str:
    """优先用显式头，其次用 JWT payload 猜测（仅用于选 introspect 路径）。"""
    if explicit in ("customer", "admin"):
        return explicit
    identity = extract_identity_from_token(token)
    if identity:
        return identity["agent_type"]
    return "customer"


async def _cache_get(key: str) -> dict[str, str] | None:
    async with _cache_lock:
        item = _cache.get(key)
        if not item:
            return None
        expire_at, value = item
        if time.monotonic() >= expire_at:
            del _cache[key]
            return None
        return value


async def _cache_set(key: str, value: dict[str, str], ttl: float) -> None:
    async with _cache_lock:
        # 简单上限，防止泄漏
        if len(_cache) > 4096:
            now = time.monotonic()
            stale = [k for k, (exp, _) in _cache.items() if now >= exp]
            for k in stale:
                del _cache[k]
            if len(_cache) > 4096:
                _cache.clear()
        _cache[key] = (time.monotonic() + ttl, value)


def _normalize_identity(user_id: Any, agent_type: str) -> dict[str, str]:
    if user_id is None or user_id == "":
        raise IntrospectError("introspect 响应缺少 userId", status_code=401)
    user_id_str = str(user_id)
    return {
        "user_id": user_id_str,
        "agent_type": agent_type,
        "owner": f"{agent_type}:{user_id_str}",
    }


async def introspect(
    token: str,
    agent_type: str | None = None,
    *,
    use_cache: bool = True,
) -> dict[str, str]:
    """向 Gateway 探查当前用户，返回 {user_id, agent_type, owner}。

    Raises:
        IntrospectError: token 无效、Gateway 拒绝或上游不可用
    """
    if not token or not token.strip():
        raise IntrospectError("缺少登录凭证", status_code=401)

    resolved_type = _guess_agent_type(token, agent_type)
    ttl = float(getattr(_settings, "INTROSPECT_CACHE_TTL", 60) or 60)
    key = _cache_key(token, resolved_type)

    if use_cache and ttl > 0:
        cached = await _cache_get(key)
        if cached:
            return cached

    try:
        if resolved_type == "admin":
            data = await gateway_client.get("/admin/info", token=token)
            # AdminInfoVO: { id, username, ... }
            user_id = None
            if isinstance(data, dict):
                user_id = data.get("id", data.get("userId"))
            identity = _normalize_identity(user_id, "admin")
        else:
            data = await gateway_client.get("/users/me", token=token)
            user_id = None
            if isinstance(data, dict):
                user_id = data.get("userId", data.get("user_id"))
            identity = _normalize_identity(user_id, "customer")
    except GatewayError as e:
        status = e.status_code or 401
        if status in (401, 403):
            raise IntrospectError("登录已过期或无效，请重新登录", status_code=401) from e
        logger.warning("introspect Gateway 错误 status=%s: %s", status, e)
        raise IntrospectError(
            "身份服务暂时不可用，请稍后重试",
            status_code=503,
        ) from e
    except IntrospectError:
        raise
    except Exception as e:
        logger.warning("introspect 异常: %s", e)
        raise IntrospectError(
            "身份服务暂时不可用，请稍后重试",
            status_code=503,
        ) from e

    if use_cache and ttl > 0:
        await _cache_set(key, identity, ttl)

    logger.debug(
        "introspect 成功 owner=%s via Gateway",
        identity["owner"],
    )
    return identity


def clear_introspect_cache() -> None:
    """测试或登出场景清空缓存。"""
    _cache.clear()
