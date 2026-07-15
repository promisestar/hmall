"""Redis Checkpoint 后端配置 — 复用 hmall Redis, db=1 隔离。"""

from langgraph.checkpoint.redis import RedisSaver

from src.core.config import get_settings

_settings = get_settings()

# 复用 hmall Redis，使用 db=1 与业务数据（db=0）隔离
# LangGraph 自动管理 Checkpoint 的读写，支持 interrupt 中断恢复
checkpointer = RedisSaver(redis_url=_settings.redis_url)
