"""LLM 实例工厂 — 通义千问 qwen-turbo（DashScope OpenAI 兼容接口）。"""

from langchain_openai import ChatOpenAI

from src.core.config import get_settings

_settings = get_settings()

# 通义千问 qwen-turbo，通过 OpenAI 兼容接口接入
qwen_model = ChatOpenAI(
    model=_settings.LLM_MODEL_NAME,
    api_key=_settings.DASHSCOPE_API_KEY,
    base_url=_settings.LLM_API_BASE,
    temperature=_settings.LLM_TEMPERATURE,
    max_tokens=_settings.LLM_MAX_TOKENS,
)
