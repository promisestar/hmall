"""hmall Agent 全局配置（Pydantic Settings 集中管理环境变量）。"""

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """集中管理所有环境变量配置。"""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # ==================== LLM ====================
    DASHSCOPE_API_KEY: str = ""
    LLM_MODEL_NAME: str = "qwen-turbo"
    LLM_API_BASE: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    LLM_TEMPERATURE: float = 0.7
    LLM_MAX_TOKENS: int = 2048

    # ==================== Redis（Checkpoint 后端） ====================
    REDIS_HOST: str = "localhost"
    REDIS_PORT: int = 6379
    REDIS_PASSWORD: str = ""
    REDIS_DB: int = 1  # db=1 与 hmall 业务数据（db=0）隔离

    # ==================== Java 后端 ====================
    JAVA_GATEWAY_URL: str = "http://localhost:8080"

    # ==================== Agent 服务 ====================
    AGENT_HOST: str = "0.0.0.0"
    AGENT_PORT: int = 8090
    LOG_LEVEL: str = "INFO"

    # ==================== JWT（双 Token 验证） ====================
    JWT_VERIFY_LOCAL: bool = False  # false 时依赖 Gateway 验证
    CUSTOMER_JKS_PATH: str = "keys/hmall.jks"
    ADMIN_JKS_PATH: str = "keys/admin.jks"

    # ==================== RAG（预留） ====================
    RAG_BASE_URL: str = "http://localhost:9621"
    RAG_USERNAME: str = "admin"
    RAG_PASSWORD: str = "admin123"
    RAG_SPACE_ID: str = "hmall_space"

    @property
    def redis_url(self) -> str:
        """构建 Redis 连接 URL。"""
        auth = f":{self.REDIS_PASSWORD}@" if self.REDIS_PASSWORD else ""
        return f"redis://{auth}{self.REDIS_HOST}:{self.REDIS_PORT}/{self.REDIS_DB}"

    @property
    def project_root(self) -> Path:
        """项目根目录。"""
        return Path(__file__).parent.parent.parent


@lru_cache
def get_settings() -> Settings:
    """获取全局配置单例。"""
    return Settings()
