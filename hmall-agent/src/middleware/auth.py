"""AuthMiddleware — 双 JWT / Gateway introspect 认证中间件。

从 context_schema 读取 user_token，经 Gateway 权威接口解析 user_id 并注入 context。

Token 来源：
- C 端：用户登录 POST /users/login → Gateway 验签 → GET /users/me
- 管理端：管理后台登录 POST /admin/login → GET /admin/info

JWT_VERIFY_LOCAL=true 时仍可走本地 jks 验签（优先）；
默认 false：以 Gateway introspect 为准（方案 3）。
"""

import logging

from langchain.agents.middleware import AgentMiddleware, ModelRequest

from src.core.config import get_settings
from src.gateway.auth import verify_jwt
from src.gateway.introspect import IntrospectError, introspect
from src.security.jwt_payload import extract_identity_from_token

logger = logging.getLogger(__name__)
_settings = get_settings()


class AuthMiddleware(AgentMiddleware):
    """双端认证中间件：优先 Gateway introspect，写入 context.user_id。"""

    def wrap_model_call(self, request: ModelRequest, handler):
        """同步路径：尽量用本地解码兜底（DeepAgents 主路径为异步）。"""
        self._authenticate_sync_fallback(request)
        return handler(request)

    async def awrap_model_call(self, request: ModelRequest, handler):
        """异步路径 — 经 Gateway introspect 注入 user_id。"""
        await self._authenticate_async(request)
        return await handler(request)

    async def _authenticate_async(self, request: ModelRequest) -> None:
        context = getattr(request.runtime, "context", None) if request.runtime else None
        if not context:
            return

        token = getattr(context, "user_token", "")
        if not token:
            logger.debug("无 user_token，仅允许只读操作")
            return

        agent_type = getattr(context, "agent_type", "customer") or "customer"

        # 1) 本地 jks 验签（显式开启时）
        user_info = verify_jwt(token, agent_type)
        if user_info and user_info.get("user_id"):
            context.user_id = str(user_info["user_id"])
            logger.debug(
                "JWT 本地验证成功, agent_type=%s, user_id=%s",
                agent_type,
                context.user_id,
            )
            return

        # 2) Gateway introspect（权威）
        try:
            identity = await introspect(token, agent_type)
            context.user_id = identity["user_id"]
            # 以 Gateway 返回的 agent_type 校准（防前端传错）
            if hasattr(context, "agent_type"):
                context.agent_type = identity["agent_type"]
            logger.debug(
                "introspect 注入 user_id=%s agent_type=%s",
                context.user_id,
                identity["agent_type"],
            )
            return
        except IntrospectError as e:
            logger.warning("introspect 失败: %s", e)
            if not _settings.INTROSPECT_FALLBACK_JWT:
                # 不注入错误的本地 ID；工具层会因缺少可信 user_id / Gateway 401 失败
                return

        # 3) 可选回退：本地 payload 解码
        if not getattr(context, "user_id", ""):
            identity = extract_identity_from_token(token)
            if identity:
                context.user_id = identity["user_id"]
                logger.warning(
                    "introspect 不可用，回退本地 JWT 解码 user_id=%s",
                    context.user_id,
                )

    def _authenticate_sync_fallback(self, request: ModelRequest) -> None:
        """同步调用仅做轻量注入，避免在同步路径里阻塞打 Gateway。"""
        context = getattr(request.runtime, "context", None) if request.runtime else None
        if not context:
            return
        if getattr(context, "user_id", ""):
            return
        token = getattr(context, "user_token", "")
        if not token:
            return
        # 前端若已通过 context 传入 user_id（经 introspect 对齐后的值）则保留
        identity = extract_identity_from_token(token)
        if identity and _settings.INTROSPECT_FALLBACK_JWT:
            context.user_id = identity["user_id"]
