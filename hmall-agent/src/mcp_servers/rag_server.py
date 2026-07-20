"""RAG MCP Server — 基于 FastMCP 的 LightRAG 桥接服务。

通过 MCP 协议对外暴露 3 个 RAG 检索工具，内部调用 LightRAG REST API：
- rag_query: 语义检索知识库（POST /query），返回答案 + 引用
- rag_query_data: 结构化数据查询（POST /query/data），返回 entities/relationships/chunks
- rag_graph_search: 知识图谱搜索（POST /query/data 聚合图谱元素）

启动方式：
    python start_rag_server.py
    # 或
    uv run python src/mcp_servers/rag_server.py

传输方式：HTTP（默认端口 8008），供 langchain-mcp-adapters 的 MultiServerMCPClient 连接。
"""

import asyncio
import logging
import time
from typing import Any

import httpx
from fastmcp import FastMCP

from src.core.config import get_settings

logger = logging.getLogger(__name__)
settings = get_settings()

# LightRAG token 缓存：避免每次请求都登录
# LightRAG 默认 TOKEN_EXPIRE_HOURS=4，这里提前 30 分钟刷新
_TOKEN_REFRESH_AHEAD = 30 * 60

# HTTP 请求超时（LightRAG 查询可能涉及 LLM 调用，需要较长超时）
_HTTP_TIMEOUT = httpx.Timeout(connect=5.0, read=120.0, write=10.0, pool=5.0)


class LightRAGClient:
    """封装 LightRAG REST API，带 JWT token 缓存和 401 自动重登录。

    所有方法均为 async，使用 httpx.AsyncClient 单例复用连接。
    """

    def __init__(self, base_url: str, username: str, password: str, api_key: str = ""):
        self._base_url = base_url.rstrip("/")
        self._username = username
        self._password = password
        self._api_key = api_key  # 可选，优先用 API Key 认证
        self._token: str = ""
        self._token_obtained_at: float = 0
        self._token_ttl: float = 3.5 * 3600  # 假设 4 小时过期，提前 30 分钟刷新
        self._client: httpx.AsyncClient | None = None
        self._lock = asyncio.Lock()  # 防止并发登录

    async def _get_client(self) -> httpx.AsyncClient:
        """获取 httpx.AsyncClient 单例。"""
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(timeout=_HTTP_TIMEOUT)
        return self._client

    def _build_headers(self) -> dict[str, str]:
        """构建请求头，携带认证信息。"""
        headers = {"Content-Type": "application/json"}
        if self._api_key:
            # API Key 认证方式
            headers["X-API-Key"] = self._api_key
        elif self._token:
            # JWT Bearer 认证方式
            headers["Authorization"] = f"Bearer {self._token}"
        return headers

    async def login(self) -> str:
        """登录获取 JWT token。

        LightRAG 的 /login 端点接受 OAuth2PasswordRequestForm（form-encoded）。
        未配置认证时返回 guest token。

        Returns:
            access_token 字符串
        """
        client = await self._get_client()
        try:
            resp = await client.post(
                f"{self._base_url}/login",
                data={
                    "username": self._username,
                    "password": self._password,
                },
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
            resp.raise_for_status()
            data = resp.json()
            token = data.get("access_token", "")
            if not token:
                raise ValueError("LightRAG /login 未返回 access_token")

            self._token = token
            self._token_obtained_at = time.time()
            auth_mode = data.get("auth_mode", "unknown")
            logger.info(
                "LightRAG 登录成功 (auth_mode=%s, username=%s)", auth_mode, self._username
            )
            return token
        except Exception as e:
            logger.error("LightRAG 登录失败: %s", e)
            raise

    async def _ensure_token(self) -> str:
        """确保 token 有效，过期或不存在时自动重新登录。"""
        # 使用 API Key 时不需要 token
        if self._api_key:
            return ""

        now = time.time()
        if self._token and (now - self._token_obtained_at) < (self._token_ttl - _TOKEN_REFRESH_AHEAD):
            return self._token

        # 并发登录保护
        async with self._lock:
            # double-check：其他协程可能已经完成登录
            if self._token and (now - self._token_obtained_at) < (self._token_ttl - _TOKEN_REFRESH_AHEAD):
                return self._token
            return await self.login()

    async def _request(
        self,
        method: str,
        path: str,
        *,
        json: dict | None = None,
        params: dict | None = None,
        retry_on_401: bool = True,
    ) -> dict[str, Any]:
        """发送已认证请求，401 时自动重登录重试一次。

        Args:
            method: HTTP 方法（GET/POST）
            path: API 路径（如 /query）
            json: JSON 请求体
            params: 查询参数
            retry_on_401: 401 时是否自动重登录重试

        Returns:
            响应 JSON
        """
        await self._ensure_token()
        client = await self._get_client()
        url = f"{self._base_url}{path}"

        resp = await client.request(
            method,
            url,
            json=json,
            params=params,
            headers=self._build_headers(),
        )

        # 401 时清除 token 并重试一次
        if resp.status_code == 401 and retry_on_401 and not self._api_key:
            logger.warning("LightRAG 返回 401，尝试重新登录")
            self._token = ""
            await self.login()
            resp = await client.request(
                method,
                url,
                json=json,
                params=params,
                headers=self._build_headers(),
            )

        if resp.status_code >= 400:
            raise LightRAGError(
                f"LightRAG API 返回错误 {resp.status_code}: {resp.text[:300]}",
                status_code=resp.status_code,
            )

        return resp.json()

    async def query(self, query: str, mode: str = "mix") -> dict[str, Any]:
        """语义检索知识库。

        Args:
            query: 查询文本（min_length=3）
            mode: 查询模式（local/global/hybrid/naive/mix/bypass，默认 mix）

        Returns:
            {response: str, references: [{reference_id, file_path, content?}], response_time?: float}
        """
        body = {
            "query": query,
            "mode": mode,
            "include_references": True,
            "include_chunk_content": False,
        }
        return await self._request("POST", "/query", json=body)

    async def query_data(self, query: str, mode: str = "mix") -> dict[str, Any]:
        """结构化数据查询，返回 entities/relationships/chunks。

        Args:
            query: 查询文本（min_length=3）
            mode: 查询模式（默认 mix）

        Returns:
            {status, message, data: {entities, relationships, chunks, references}, metadata}
        """
        body = {"query": query, "mode": mode}
        return await self._request("POST", "/query/data", json=body)

    async def close(self) -> None:
        """关闭 HTTP 客户端。"""
        if self._client and not self._client.is_closed:
            await self._client.aclose()


class LightRAGError(Exception):
    """LightRAG API 调用异常。"""

    def __init__(self, message: str, status_code: int = 0):
        super().__init__(message)
        self.status_code = status_code


# ============================================================================
# LightRAGClient 单例（从配置初始化）
# ============================================================================

_lightrag_client: LightRAGClient | None = None


def get_lightrag_client() -> LightRAGClient:
    """获取 LightRAGClient 单例（懒初始化）。"""
    global _lightrag_client
    if _lightrag_client is None:
        _lightrag_client = LightRAGClient(
            base_url=settings.RAG_BASE_URL,
            username=settings.RAG_USERNAME,
            password=settings.RAG_PASSWORD,
            api_key=settings.RAG_API_KEY,
        )
    return _lightrag_client


# ============================================================================
# 格式化工具：将 LightRAG 响应转为 LLM 友好的文本
# ============================================================================


def _format_query_result(result: dict[str, Any]) -> str:
    """格式化 /query 响应为文本。"""
    response = result.get("response", "")
    references = result.get("references") or []

    lines = [response]

    if references:
        lines.append("")
        lines.append("**参考来源：**")
        for i, ref in enumerate(references, 1):
            file_path = ref.get("file_path", "未知来源")
            lines.append(f"{i}. {file_path}")

    return "\n".join(lines)


def _format_query_data_result(result: dict[str, Any]) -> str:
    """格式化 /query/data 响应为文本。"""
    status = result.get("status", "unknown")
    if status != "success":
        return f"查询失败：{result.get('message', '未知错误')}"

    data = result.get("data", {}) or {}
    entities = data.get("entities") or []
    relationships = data.get("relationships") or []
    chunks = data.get("chunks") or []

    lines = [f"**检索结果**（{len(entities)} 实体 / {len(relationships)} 关系 / {len(chunks)} 文本块）"]

    if entities:
        lines.append("")
        lines.append("**相关实体：**")
        for i, ent in enumerate(entities, 1):
            name = ent.get("entity_name", "未知")
            ent_type = ent.get("entity_type", "")
            desc = ent.get("description", "")
            lines.append(f"{i}. [{ent_type}] {name}：{desc}")

    if relationships:
        lines.append("")
        lines.append("**实体关系：**")
        for i, rel in enumerate(relationships, 1):
            src = rel.get("src_id", "?")
            tgt = rel.get("tgt_id", "?")
            desc = rel.get("description", "")
            weight = rel.get("weight", 0)
            lines.append(f"{i}. {src} → {tgt}（权重 {weight}）：{desc}")

    if chunks:
        lines.append("")
        lines.append("**相关文本块：**")
        for i, chunk in enumerate(chunks, 1):
            content = chunk.get("content", "")
            file_path = chunk.get("file_path", "")
            # 截断过长的文本块
            if len(content) > 500:
                content = content[:500] + "..."
            lines.append(f"{i}. [{file_path}] {content}")

    return "\n".join(lines)


def _format_graph_result(result: dict[str, Any]) -> str:
    """格式化图谱搜索响应（基于 query_data 的 entities + relationships）。"""
    return _format_query_data_result(result)


# ============================================================================
# FastMCP Server 定义
# ============================================================================

mcp = FastMCP("hmall-rag")


@mcp.tool()
async def rag_query(query: str, mode: str = "mix") -> str:
    """语义检索知识库，返回基于知识库生成的答案和参考来源。

    适用于：运营策略咨询、商品知识问答、库存管理指南、订单分析方法等
    需要基于专业知识库回答的问题。

    Args:
        query: 查询文本（至少 3 个字符），描述你想了解的知识
        mode: 查询模式
            - mix（默认）：融合知识图谱 + 向量检索，效果最佳
            - hybrid：混合 local + global 检索
            - local：聚焦具体实体及其关系
            - global：提供更宽泛的上下文
            - naive：简单向量相似度搜索
            - bypass：绕过 RAG 直接用 LLM 回答

    Returns:
        知识库检索答案 + 参考来源列表
    """
    if len(query.strip()) < 3:
        return "查询文本至少需要 3 个字符"

    try:
        client = get_lightrag_client()
        result = await client.query(query, mode=mode)
        return _format_query_result(result)
    except LightRAGError as e:
        logger.error("rag_query 调用失败: %s", e)
        return f"❌ 知识库检索失败: {e}"
    except Exception as e:
        logger.error("rag_query 异常: %s", e, exc_info=True)
        return f"❌ 知识库检索异常: {e}"


@mcp.tool()
async def rag_query_data(query: str, mode: str = "mix") -> str:
    """结构化数据查询，返回知识图谱中的实体、关系和文本块。

    适用于：需要查看知识库中具体实体定义、实体间关系、原始文本片段的场景。
    与 rag_query 的区别：本工具返回结构化的检索中间数据，不生成最终答案。

    Args:
        query: 查询文本（至少 3 个字符）
        mode: 查询模式（默认 mix，可选 local/global/hybrid/naive/bypass）

    Returns:
        结构化检索结果：相关实体列表 + 实体关系列表 + 相关文本块
    """
    if len(query.strip()) < 3:
        return "查询文本至少需要 3 个字符"

    try:
        client = get_lightrag_client()
        result = await client.query_data(query, mode=mode)
        return _format_query_data_result(result)
    except LightRAGError as e:
        logger.error("rag_query_data 调用失败: %s", e)
        return f"❌ 知识库数据查询失败: {e}"
    except Exception as e:
        logger.error("rag_query_data 异常: %s", e, exc_info=True)
        return f"❌ 知识库数据查询异常: {e}"


@mcp.tool()
async def rag_graph_search(query: str) -> str:
    """知识图谱搜索，基于查询提取相关实体和关系。

    适用于：探索知识库中实体间的关联关系，如"秒杀活动与库存的关系"、
    "用户分群与购买行为的关系"等图谱探索场景。

    Args:
        query: 查询文本（至少 3 个字符），描述要探索的实体或关系

    Returns:
        知识图谱子图：相关实体 + 实体间关系
    """
    if len(query.strip()) < 3:
        return "查询文本至少需要 3 个字符"

    try:
        client = get_lightrag_client()
        # 图谱搜索复用 query_data，重点展示 entities 和 relationships
        result = await client.query_data(query, mode="mix")
        return _format_graph_result(result)
    except LightRAGError as e:
        logger.error("rag_graph_search 调用失败: %s", e)
        return f"❌ 图谱搜索失败: {e}"
    except Exception as e:
        logger.error("rag_graph_search 异常: %s", e, exc_info=True)
        return f"❌ 图谱搜索异常: {e}"


# ============================================================================
# 启动入口
# ============================================================================

if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
    port = settings.RAG_MCP_PORT
    logger.info("启动 RAG MCP Server (port=%d, LightRAG=%s)", port, settings.RAG_BASE_URL)
    mcp.run(transport="http", port=port)
