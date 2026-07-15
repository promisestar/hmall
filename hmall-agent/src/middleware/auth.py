"""AuthMiddleware — 双 JWT 认证中间件。

从 context_schema 读取 user_token，验证 JWT 有效性（可选），提取 user_id 用于数据隔离。

Token 来源：
- C 端：用户登录 POST /users/login → hmall.jks（RSA）
- 管理端：管理后台登录 POST /admin/login → admin.jks（RSA，独立）

JWT_VERIFY_LOCAL=false 时依赖 Gateway 验证，本中间件仅透传 token。
"""

import logging

from langchain.agents.middleware import AgentMiddleware, ModelRequest

from src.gateway.auth import verify_jwt

logger = logging.getLogger(__name__)


class AuthMiddleware(AgentMiddleware):
    """双 JWT 认证中间件。

    从 context_schema 读取 user_token 和 agent_type，
    验证 JWT 并注入 user_id 到 context。
    无 Token 时仅允许只读操作（如查看商品），写操作由工具层检查。
    """

    def wrap_model_call(self, request: ModelRequest, handler):
        """同步版本（实际使用异步版本）。"""
        self._authenticate(request)
        return handler(request)

    async def awrap_model_call(self, request: ModelRequest, handler):
        """异步版本 — 中间件入口。"""
        self._authenticate(request)
        return await handler(request)

    def _authenticate(self, request: ModelRequest) -> None:
        """从 context 读取 token 并认证。"""
        context = getattr(request.runtime, "context", None) if request.runtime else None
        if not context:
            return

        token = getattr(context, "user_token", "")
        if not token:
            logger.debug("无 user_token，仅允许只读操作")
            return

        agent_type = getattr(context, "agent_type", "customer")

        # 尝试验证 JWT（JWT_VERIFY_LOCAL=false 时返回 None，依赖 Gateway）
        user_info = verify_jwt(token, agent_type)
        if user_info:
            context.user_id = user_info.get("user_id", "")
            logger.debug(
                "JWT 验证成功, agent_type=%s, user_id=%s",
                agent_type,
                context.user_id,
            )
        else:
            logger.debug(
                "JWT 本地验证未启用或失败, 依赖 Gateway 验证, agent_type=%s",
                agent_type,
            )
