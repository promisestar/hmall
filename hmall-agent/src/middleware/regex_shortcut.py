"""RegexShortcutMiddleware — L1 正则快捷路由中间件。

在 LLM 调用前检查用户消息，匹配高频指令时直接调用对应工具并返回结果，
跳过 LLM 推理，实现 <5ms 响应。

不匹配的消息正常传递给 LLM（L3 兜底）。
二次确认类操作（取消订单/删除/清空）不在 L1 拦截，由 L2 interrupt 处理。
"""

import logging
import re
from typing import Any, Callable

from langchain.agents.middleware import AgentMiddleware, ModelRequest
from langchain_core.messages import AIMessage

logger = logging.getLogger(__name__)

# 规则类型：(正则模式, 工具名, 参数提取器)
Rule = tuple[str, str, Callable[[re.Match], dict] | None]


class RegexShortcutMiddleware(AgentMiddleware):
    """L1 正则快捷路由中间件。

    拦截 wrap_model_call，在 LLM 调用前检查用户消息：
    - 匹配高频只读指令 → 直接 ainvoke 工具，返回 AIMessage（跳过 LLM）
    - 不匹配或写操作 → 传递给下一层中间件 / LLM

    仅拦截只读工具（无 interrupt），写操作由 L2 interrupt 处理。
    """

    def __init__(self, tool_registry: list, rules: list[Rule]):
        super().__init__()
        self._tools = {t.name: t for t in tool_registry}
        self._rules = rules

    def _try_shortcut(self, request: ModelRequest) -> AIMessage | None:
        """尝试正则匹配，命中则直接调用工具返回结果。

        Returns:
            AIMessage（命中）或 None（未命中，传递给 LLM）
        """
        messages = getattr(request, "messages", [])
        if not messages:
            return None

        last_msg = messages[-1]
        if getattr(last_msg, "type", None) != "human":
            return None

        # 提取文本内容
        content = getattr(last_msg, "content", "")
        if isinstance(content, list):
            # 处理多模态消息，提取文本部分
            text = " ".join(
                part.get("text", "") for part in content if isinstance(part, dict)
            )
        elif isinstance(content, str):
            text = content
        else:
            return None

        if not text.strip():
            return None

        # 遍历规则，匹配第一个命中的
        for pattern, tool_name, extractor in self._rules:
            match = re.search(pattern, text)
            if match and tool_name in self._tools:
                tool = self._tools[tool_name]
                params = extractor(match) if extractor else {}
                logger.info(
                    "L1 正则命中: pattern=%s → tool=%s, params=%s",
                    pattern[:30],
                    tool_name,
                    params,
                )
                return tool.invoke(params)

        return None

    async def _atry_shortcut(self, request: ModelRequest) -> AIMessage | None:
        """异步版本的正则匹配+工具调用。"""
        messages = getattr(request, "messages", [])
        if not messages:
            return None

        last_msg = messages[-1]
        if getattr(last_msg, "type", None) != "human":
            return None

        content = getattr(last_msg, "content", "")
        if isinstance(content, list):
            text = " ".join(
                part.get("text", "") for part in content if isinstance(part, dict)
            )
        elif isinstance(content, str):
            text = content
        else:
            return None

        if not text.strip():
            return None

        for pattern, tool_name, extractor in self._rules:
            match = re.search(pattern, text)
            if match and tool_name in self._tools:
                tool = self._tools[tool_name]
                params = extractor(match) if extractor else {}
                logger.info(
                    "L1 正则命中: pattern=%s → tool=%s, params=%s",
                    pattern[:30],
                    tool_name,
                    params,
                )
                # 异步工具使用 ainvoke
                try:
                    result = await tool.ainvoke(params)
                except Exception:
                    # ainvoke 失败，尝试同步 invoke
                    try:
                        result = tool.invoke(params)
                    except Exception as e:
                        logger.warning("L1 工具调用失败 %s: %s", tool_name, e)
                        return None

                # 确保 result 是字符串
                content_str = str(result) if not isinstance(result, str) else result
                return AIMessage(content=content_str)

        return None

    def wrap_model_call(self, request: ModelRequest, handler):
        """同步版本 — 尝试 shortcut，命中则返回，否则传递给下一层。"""
        shortcut = self._try_shortcut(request)
        if shortcut is not None:
            return shortcut
        return handler(request)

    async def awrap_model_call(self, request: ModelRequest, handler):
        """异步版本 — 中间件入口。"""
        shortcut = await self._atry_shortcut(request)
        if shortcut is not None:
            return shortcut
        return await handler(request)
