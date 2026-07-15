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
    context_schema 中的 user_token 可通过 config 获取。
    """
    if not config:
        return ""

    # 尝试从 configurable.context 获取
    configurable = config.get("configurable", {}) if isinstance(config, dict) else {}
    context = configurable.get("context")
    if context and hasattr(context, "user_token"):
        return context.user_token or ""

    # 尝试从 runtime 获取
    runtime = config.get("runtime", {}) if isinstance(config, dict) else {}
    context = runtime.get("context")
    if context and hasattr(context, "user_token"):
        return context.user_token or ""

    return ""


# 全局客户端实例
gateway_client = GatewayClient()
