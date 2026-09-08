"""安全与多租户鉴权相关模块。

注意：不要在此文件中 import auth，以免与 gateway.introspect 形成循环依赖。
使用方式：
- from src.security.auth import auth
- from src.security.jwt_payload import extract_identity_from_token
"""

__all__ = ["auth", "jwt_payload"]
