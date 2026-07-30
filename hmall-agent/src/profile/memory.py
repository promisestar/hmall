"""Layer 3 对话记忆 — 基于 LangGraph Store 的跨会话语义记忆。

与 Layer 1/2 的 Redis 结构化画像互补：
- Layer 1/2 存储"用户喜欢什么类目/品牌"（数值型得分）
- Layer 3 存储"用户说过什么"（语义记忆）

记忆工具通过 @tool 装饰器暴露给 LLM，LLM 可在对话中自主调用：
- save_memory: 保存购物意图/偏好等对话记忆
- get_memories: 读取历史记忆，在对话中自然融入
"""

import json
import logging
import time

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from src.gateway.http_client import _extract_user_id

logger = logging.getLogger(__name__)

MEMORY_NAMESPACE = "user_memory"


def _get_store(config: RunnableConfig):
    """从 config 中获取 LangGraph Store 实例。"""
    if not config or not isinstance(config, dict):
        return None
    configurable = config.get("configurable", {})
    if not isinstance(configurable, dict):
        return None
    return configurable.get("store")


@tool
async def save_memory(key: str, value: str, config: RunnableConfig) -> str:
    """保存一条对话记忆到长期存储。

    当用户表达购物意图但未完成时调用。例如：
    - 用户说"想买手机但再看看" → key="shopping_intent", value="正在挑选手机，预算约 5000"
    - 用户频繁看某个品牌 → key="brand_preference", value="对 Apple 产品感兴趣"

    Args:
        key: 记忆标识（如 shopping_intent / price_sensitivity / last_viewed）
        value: 记忆内容
    """
    store = _get_store(config)
    if not store:
        return "记忆服务未启用"

    user_id = _extract_user_id(config)
    if not user_id:
        return "无法识别用户身份，记忆保存失败"

    try:
        await store.aput(
            namespace=(MEMORY_NAMESPACE, user_id),
            key=key,
            value={"content": value, "ts": int(time.time())},
        )
        return f"已记住: {key}"
    except Exception as e:
        logger.warning("保存记忆失败, user_id=%s, key=%s: %s", user_id, key, e)
        return "记忆保存失败，请稍后重试"


@tool
async def get_memories(config: RunnableConfig) -> str:
    """读取当前用户的历史对话记忆。

    在对话开始时或推荐商品前调用，了解用户之前的购物意图和偏好。
    返回 JSON 格式的记忆列表。

    Returns:
        JSON 格式的记忆列表，暂无记忆时返回提示文本
    """
    store = _get_store(config)
    if not store:
        return "记忆服务未启用"

    user_id = _extract_user_id(config)
    if not user_id:
        return "无法识别用户身份，记忆读取失败"

    try:
        items = await store.asearch(
            namespace=(MEMORY_NAMESPACE, user_id),
            limit=20,
        )
    except Exception as e:
        logger.warning("读取记忆失败, user_id=%s: %s", user_id, e)
        return "记忆读取失败，请稍后重试"

    if not items:
        return "暂无历史记忆"

    memories = []
    for item in items:
        content = ""
        if hasattr(item, "value"):
            content = item.value.get("content", "")
        elif isinstance(item, dict):
            value = item.get("value", {})
            content = value.get("content", "") if isinstance(value, dict) else str(value)
        key = item.key if hasattr(item, "key") else item.get("key", "")
        memories.append({"key": key, "content": content})

    return json.dumps(memories, ensure_ascii=False)
