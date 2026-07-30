"""异步 HTTP 客户端 — 封装 httpx 调用 hmall Gateway。

所有 Agent 工具通过此客户端调用 Java 后端 API：
- C 端 API（经 Gateway :8080）：直接返回业务数据，Gateway 不包装
- 管理端 API（admin-service）：统一用 R<T> 包装（{code, msg, data}），需解包 .data
"""

import logging
from typing import Any

import httpx

from src.core.config import get_settings

logger = logging.getLogger(__name__)
_settings = get_settings()

# 请求超时配置
_TIMEOUT = httpx.Timeout(connect=5.0, read=30.0, write=10.0, pool=5.0)


class GatewayClient:
    """异步 HTTP 客户端，封装 httpx 调用 hmall Gateway。

    所有方法自动携带 Authorization 头（JWT Token）。
    管理端 API（路径以 /admin 开头）自动解包 R<T> 包装。
    """

    def __init__(self, base_url: str | None = None):
        self._base_url = (base_url or _settings.JAVA_GATEWAY_URL).rstrip("/")

    def _build_headers(self, token: str = "") -> dict[str, str]:
        """构建请求头，携带 Authorization。"""
        headers = {"Content-Type": "application/json"}
        if token:
            headers["authorization"] = token
        return headers

    def _is_admin_api(self, path: str) -> bool:
        """判断是否为管理端 API（路径以 /admin 开头）。"""
        return path.startswith("/admin")

    def _parse_response(self, path: str, resp: httpx.Response) -> Any:
        """解析响应：管理端 API 解包 R<T>，C 端直接返回 data。

        Raises:
            GatewayError: 请求失败（非 2xx 状态码或 R<T>.code != 200）
        """
        if resp.status_code == 429:
            raise GatewayError("请求过于频繁，请稍后再试（秒杀限流）", status_code=429)

        if resp.status_code == 401:
            raise GatewayError("登录已过期，请重新登录", status_code=401)

        if resp.status_code >= 400:
            raise GatewayError(
                f"Gateway 返回错误 {resp.status_code}: {resp.text[:200]}",
                status_code=resp.status_code,
            )

        data = resp.json()

        # 管理端 API 用 R<T> 包装：{code, msg, data}
        if self._is_admin_api(path):
            if isinstance(data, dict) and "code" in data:
                if data["code"] != 200:
                    raise GatewayError(
                        data.get("msg", "管理端 API 返回错误"),
                        status_code=resp.status_code,
                    )
                return data.get("data")
            return data

        # C 端 API 直接返回数据
        return data

    async def get(
        self, path: str, token: str = "", params: dict | None = None
    ) -> Any:
        """GET 请求。

        Args:
            path: API 路径（如 /carts, /admin/order/list）
            token: JWT Token，放入 Authorization 头
            params: 查询参数

        Returns:
            C 端直接返回业务数据，管理端解包 R<T>.data
        """
        url = f"{self._base_url}{path}"
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(
                url, params=params, headers=self._build_headers(token)
            )
        return self._parse_response(path, resp)

    async def post(
        self,
        path: str,
        token: str = "",
        json: dict | list | None = None,
        params: dict | None = None,
    ) -> Any:
        """POST 请求。

        Args:
            path: API 路径
            token: JWT Token
            json: 请求体（JSON）
            params: 查询参数
        """
        url = f"{self._base_url}{path}"
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.post(
                url, json=json, params=params, headers=self._build_headers(token)
            )
        return self._parse_response(path, resp)

    async def put(
        self, path: str, token: str = "", json: dict | None = None
    ) -> Any:
        """PUT 请求。"""
        url = f"{self._base_url}{path}"
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.put(
                url, json=json, headers=self._build_headers(token)
            )
        return self._parse_response(path, resp)

    async def delete(
        self, path: str, token: str = "", params: dict | None = None
    ) -> Any:
        """DELETE 请求。"""
        url = f"{self._base_url}{path}"
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.delete(
                url, params=params, headers=self._build_headers(token)
            )
        return self._parse_response(path, resp)


class GatewayError(Exception):
    """Gateway 调用异常。"""

    def __init__(self, message: str, status_code: int = 0):
        super().__init__(message)
        self.status_code = status_code


def extract_token_from_config(config) -> str:
    """从 LangGraph RunnableConfig 中提取 user_token。

    LangGraph 在调用工具时自动注入 config 参数（不展示给 LLM）。
    token 来源优先级：
    1. config.configurable.user_token（前端通过 config.configurable 传入，LangGraph 原样透传）
    2. config.configurable.context.user_token（中间件注入等场景）
    3. config.runtime.context.user_token（DeepAgents 运行时上下文）
    """
    if not config:
        return ""

    if not isinstance(config, dict):
        return ""

    # 1. config.configurable 中的 user_token（前端传入，最可靠）
    configurable = config.get("configurable", {})
    if isinstance(configurable, dict):
        token = configurable.get("user_token", "")
        if token:
            return token

    # 2. configurable.context.user_token（中间件/状态注入）
    context = configurable.get("context") if isinstance(configurable, dict) else None
    if context and hasattr(context, "user_token"):
        token = context.user_token
        if token:
            return token

    # 3. runtime.context.user_token（DeepAgents 运行时上下文）
    runtime = config.get("runtime", {}) if isinstance(config, dict) else {}
    context = runtime.get("context")
    if context and hasattr(context, "user_token"):
        token = context.user_token
        if token:
            return token

    return ""


def _extract_user_id(config) -> str:
    """从 LangGraph RunnableConfig 中提取 user_id。

    优先级与 extract_token_from_config 一致：
    1. config.configurable.user_id（前端传入，最可靠）
    2. config.configurable.context.user_id（AuthMiddleware 注入，JWT_VERIFY_LOCAL=true 时）
    3. config.runtime.context.user_id（DeepAgents 运行时上下文）

    以上均无时，尝试从 user_token JWT payload 解码 user_id（兜底，
    JWT_VERIFY_LOCAL=false 时 AuthMiddleware 不写入 context.user_id）。
    """
    if not config or not isinstance(config, dict):
        return ""

    configurable = config.get("configurable", {})
    if not isinstance(configurable, dict):
        configurable = {}

    # 1. config.configurable.user_id（前端传入）
    user_id = configurable.get("user_id", "")
    if user_id:
        return str(user_id)

    # 2. configurable.context.user_id（AuthMiddleware 注入）
    context = configurable.get("context")
    if context and hasattr(context, "user_id"):
        uid = getattr(context, "user_id", "")
        if uid:
            return str(uid)

    # 3. runtime.context.user_id（DeepAgents 运行时上下文）
    runtime = config.get("runtime", {})
    if isinstance(runtime, dict):
        context = runtime.get("context")
        if context and hasattr(context, "user_id"):
            uid = getattr(context, "user_id", "")
            if uid:
                return str(uid)

    # 4. 兜底：从 user_token JWT payload 解码 user_id
    token = configurable.get("user_token", "")
    if not token:
        context = configurable.get("context")
        if context and hasattr(context, "user_token"):
            token = getattr(context, "user_token", "")
    if token:
        uid = _decode_user_id_from_jwt(token)
        if uid:
            return str(uid)

    return ""


def _decode_user_id_from_jwt(token: str) -> str:
    """从 JWT payload 中解码 user_id（不验证签名）。

    复用 auth.get_jti 的 base64 解码模式，仅用于 JWT_VERIFY_LOCAL=false 时的兜底。
    """
    try:
        import base64
        import json

        parts = token.split(".")
        if len(parts) != 3:
            return ""
        payload = parts[1]
        payload += "=" * (-len(payload) % 4)
        decoded = json.loads(base64.urlsafe_b64decode(payload))
        user_id = decoded.get("user_id", "")
        return str(user_id) if user_id else ""
    except Exception:
        return ""


# 全局客户端实例
gateway_client = GatewayClient()
