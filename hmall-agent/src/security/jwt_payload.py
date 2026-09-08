"""JWT payload 解析（不校验签名）。

与 Java 侧约定对齐：
- C 端：payload.user = userId
- 管理端：payload.sub = adminUserId，payload.type = \"ADMIN\"

JWT_VERIFY_LOCAL=false 时由 Gateway 做签名校验；Agent Server 多租户
隔离只需稳定提取身份，因此这里只做 payload 解码。
"""

from __future__ import annotations

import base64
import json
import logging
from typing import Any

logger = logging.getLogger(__name__)


def decode_jwt_payload(token: str) -> dict[str, Any] | None:
    """解码 JWT payload，失败返回 None。"""
    if not token or not isinstance(token, str):
        return None
    try:
        parts = token.strip().split(".")
        if len(parts) != 3:
            return None
        payload = parts[1]
        payload += "=" * (-len(payload) % 4)
        decoded = json.loads(base64.urlsafe_b64decode(payload))
        return decoded if isinstance(decoded, dict) else None
    except Exception as e:
        logger.debug("JWT payload 解码失败: %s", e)
        return None


def extract_identity_from_token(token: str) -> dict[str, str] | None:
    """从 JWT 提取多租户身份。

    Returns:
        {"agent_type": "customer"|"admin", "user_id": str, "owner": "customer:123"}
        无法识别时返回 None。
    """
    decoded = decode_jwt_payload(token)
    if not decoded:
        return None

    token_type = str(decoded.get("type", "")).upper()
    if token_type == "ADMIN" or (
        "sub" in decoded and "user" not in decoded and decoded.get("sub") is not None
    ):
        user_id = decoded.get("sub")
        agent_type = "admin"
    else:
        user_id = decoded.get("user", decoded.get("user_id"))
        agent_type = "customer"

    if user_id is None or user_id == "":
        return None

    user_id_str = str(user_id)
    return {
        "agent_type": agent_type,
        "user_id": user_id_str,
        "owner": f"{agent_type}:{user_id_str}",
    }


def extract_bearer_token(
    authorization: str | None = None,
    headers: dict[bytes, bytes] | None = None,
) -> str:
    """从 Authorization 头提取 token，兼容 Bearer 与裸 JWT。"""
    raw = authorization
    if not raw and headers:
        # ASGI headers 为 bytes key；同时兼容大小写
        for key in (b"authorization", b"Authorization"):
            if key in headers:
                val = headers[key]
                raw = val.decode("latin-1") if isinstance(val, (bytes, bytearray)) else str(val)
                break

    if not raw:
        return ""

    raw = raw.strip()
    parts = raw.split(None, 1)
    if len(parts) == 2 and parts[0].lower() == "bearer":
        return parts[1].strip()
    return raw
