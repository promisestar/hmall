"""LangGraph 自定义 Auth：按用户隔离 threads（多租户会话历史）。

机制：
1. @auth.authenticate — 经 Gateway introspect 获取权威 userId（方案 3）
2. @auth.on.threads.* — 创建时写入 metadata.owner，读写/搜索时按 owner 过滤

owner 格式：`{agent_type}:{user_id}`，避免 C 端与管理端同数字 ID 冲突。
"""

from __future__ import annotations

import logging

from langgraph_sdk import Auth

from src.core.config import get_settings
from src.gateway.introspect import IntrospectError, introspect
from src.security.jwt_payload import (
    extract_bearer_token,
    extract_identity_from_token,
)

logger = logging.getLogger(__name__)
_settings = get_settings()

auth = Auth()

# 无需登录即可访问的探测类路径（不触及用户会话资源）
_PUBLIC_PATH_PREFIXES = (
    "/ok",
    "/docs",
    "/openapi.json",
    "/redoc",
    "/info",
    "/metrics",
    "/api/v1/health",
    "/api/v1/llm",
)

_AGENT_TYPE_HEADER_KEYS = (
    b"x-hmall-agent-type",
    b"X-Hmall-Agent-Type",
    b"x-hmall-agent_type",
)


def _is_public_path(path: str | None) -> bool:
    if not path:
        return False
    return any(path == p or path.startswith(p + "/") for p in _PUBLIC_PATH_PREFIXES)


def _header_value(headers: dict | None, keys: tuple[bytes, ...]) -> str:
    if not headers:
        return ""
    for key in keys:
        if key in headers:
            val = headers[key]
            if isinstance(val, (bytes, bytearray)):
                return val.decode("latin-1").strip()
            return str(val).strip()
    return ""


def _owner_filter(ctx: Auth.types.AuthContext) -> dict:
    """仅允许访问 metadata.owner 匹配当前用户的资源。"""
    identity = getattr(ctx.user, "identity", None) or ""
    if not identity or identity.startswith("system:"):
        raise Auth.exceptions.HTTPException(
            status_code=401,
            detail="未登录，无法访问会话资源",
        )
    return {"owner": identity}


async def _resolve_identity(token: str, agent_type_hint: str | None) -> dict[str, str]:
    """优先 Gateway introspect；可选回退本地 JWT 解码。"""
    try:
        return await introspect(token, agent_type_hint)
    except IntrospectError as e:
        if _settings.INTROSPECT_FALLBACK_JWT:
            local = extract_identity_from_token(token)
            if local:
                logger.warning(
                    "introspect 失败(%s)，已回退本地 JWT 解码 owner=%s",
                    e,
                    local["owner"],
                )
                return local
        raise Auth.exceptions.HTTPException(
            status_code=e.status_code or 401,
            detail=str(e) or "身份校验失败",
        ) from e


@auth.authenticate
async def authenticate(
    authorization: str | None = None,
    headers: dict | None = None,
    path: str | None = None,
) -> Auth.types.MinimalUserDict:
    """经 Gateway introspect 得到多租户 identity。"""
    if _is_public_path(path):
        return {
            "identity": "system:public",
            "is_authenticated": False,
            "permissions": [],
        }

    token = extract_bearer_token(authorization, headers)
    if not token:
        raise Auth.exceptions.HTTPException(
            status_code=401,
            detail="缺少 Authorization，请先登录后再访问 Agent",
        )

    agent_type_hint = _header_value(headers, _AGENT_TYPE_HEADER_KEYS).lower() or None
    if agent_type_hint not in (None, "customer", "admin"):
        agent_type_hint = None

    identity = await _resolve_identity(token, agent_type_hint)

    logger.debug(
        "Agent Auth 通过(introspect): owner=%s agent_type=%s",
        identity["owner"],
        identity["agent_type"],
    )
    return {
        "identity": identity["owner"],
        "is_authenticated": True,
        "permissions": ["threads:read", "threads:write"],
        "display_name": identity["owner"],
        "user_id": identity["user_id"],
        "agent_type": identity["agent_type"],
    }


@auth.on.threads.create
async def on_thread_create(
    ctx: Auth.types.AuthContext,
    value: Auth.types.on.threads.create.value,
):
    """创建会话时打上 owner，并限制仅本人可见。"""
    filters = _owner_filter(ctx)
    owner = filters["owner"]
    metadata = value.setdefault("metadata", {})
    metadata["owner"] = owner
    agent_type, _, user_id = owner.partition(":")
    metadata["agent_type"] = agent_type
    metadata["user_id"] = user_id
    return filters


@auth.on.threads.read
async def on_thread_read(
    ctx: Auth.types.AuthContext,
    value: Auth.types.on.threads.read.value,
):
    return _owner_filter(ctx)


@auth.on.threads.update
async def on_thread_update(
    ctx: Auth.types.AuthContext,
    value: Auth.types.on.threads.update.value,
):
    return _owner_filter(ctx)


@auth.on.threads.delete
async def on_thread_delete(
    ctx: Auth.types.AuthContext,
    value: Auth.types.on.threads.delete.value,
):
    return _owner_filter(ctx)


@auth.on.threads.search
async def on_thread_search(
    ctx: Auth.types.AuthContext,
    value: Auth.types.on.threads.search.value,
):
    return _owner_filter(ctx)


@auth.on.threads.create_run
async def on_thread_create_run(
    ctx: Auth.types.AuthContext,
    value: Auth.types.on.threads.create_run.value,
):
    """在他人 thread 上跑 run 会被 owner 过滤拒绝。"""
    return _owner_filter(ctx)


@auth.on.store()
async def on_store(ctx: Auth.types.AuthContext, value: dict):
    """Store 按 namespace 第二段 user_id 隔离（与 memory.py 约定一致）。"""
    identity = getattr(ctx.user, "identity", "") or ""
    if not identity or identity.startswith("system:"):
        raise Auth.exceptions.HTTPException(status_code=401, detail="未登录")

    user_id = identity.split(":", 1)[-1]
    namespace = value.get("namespace") or ()
    if not isinstance(namespace, (tuple, list)):
        raise Auth.exceptions.HTTPException(status_code=403, detail="非法 Store namespace")

    if len(namespace) >= 2 and str(namespace[1]) == str(user_id):
        return None

    raise Auth.exceptions.HTTPException(
        status_code=403,
        detail="无权访问其他用户的记忆数据",
    )
