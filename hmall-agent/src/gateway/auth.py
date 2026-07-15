"""JWT 验证工具（预留）。

JWT_VERIFY_LOCAL=false 时依赖 Gateway 验证 JWT，本模块不做本地验证。
如需本地验证，可在此处实现双 keystore（hmall.jks / admin.jks）的 RSA JWT 解析。
"""

from src.core.config import get_settings

_settings = get_settings()


def verify_jwt(token: str, agent_type: str = "customer") -> dict | None:
    """验证 JWT 并返回用户信息。

    Args:
        token: JWT Token 字符串
        agent_type: "customer" 或 "admin"，决定使用哪个 keystore

    Returns:
        包含 user_id 的字典，验证失败返回 None

    Note:
        JWT_VERIFY_LOCAL=false 时直接返回 None，依赖 Gateway 验证。
    """
    if not _settings.JWT_VERIFY_LOCAL:
        # 依赖 Gateway 验证，Agent 层不做本地验证
        return None

    # TODO: 实现本地 JWT 验证（使用 hmall.jks / admin.jks）
    # from cryptography.hazmat.primitives.serialization import pkcs12
    # ...
    return None


def get_jti(token: str) -> str | None:
    """从 JWT 中提取 jti（JWT ID），用于黑名单检查。"""
    try:
        import base64
        import json

        parts = token.split(".")
        if len(parts) != 3:
            return None
        payload = parts[1]
        # 补齐 base64 padding
        payload += "=" * (4 - len(payload) % 4)
        decoded = json.loads(base64.urlsafe_b64decode(payload))
        return decoded.get("jti")
    except Exception:
        return None
