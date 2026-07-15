"""AdminAgent 定义 — 管理助手 Agent。

使用 DeepAgents create_agent 声明式定义，配置：
- model: 通义千问 qwen-turbo
- tools: 10 个管理端只读工具 + generate_daily_report
- middleware: AuthMiddleware → PermissionMiddleware（纯只读）→ RegexShortcutMiddleware → SkillsMiddleware
- context_schema: Context（agent_type="admin", user_id, user_token, enable_rag）
- skills: 3 个 SKILL.md 规范文件
"""

from dataclasses import dataclass
from pathlib import Path

from deepagents import create_deep_agent as create_agent
from deepagents.backends import FilesystemBackend
from deepagents.middleware import SkillsMiddleware

from src.core.llms import qwen_model
from src.middleware.auth import AuthMiddleware
from src.middleware.permission import PermissionMiddleware
from src.middleware.regex_shortcut import RegexShortcutMiddleware

from src.agents.admin.prompts import SYSTEM_PROMPT
from src.agents.admin.regex_rules import REGEX_RULES
from src.agents.admin.tools import get_all_tools


@dataclass
class Context:
    """AdminAgent 运行时上下文。"""
    agent_type: str = "admin"
    user_id: str = ""                # 管理员 ID
    user_token: str = ""             # 管理后台 JWT Token
    enable_rag: bool = False         # RAG 开关


# ============================================================================
# Skills 配置
# ============================================================================

skills_root = str(
    (Path(__file__).parent.parent.parent / "workspace" / "admin").resolve()
)
skills_backend = FilesystemBackend(root_dir=skills_root, virtual_mode=True)

skills_middleware = SkillsMiddleware(
    backend=skills_backend,
    sources=[
        "/skills/daily-report/",
        "/skills/data-query/",
        "/skills/rag-query/",
    ],
)

# ============================================================================
# L1 正则快捷路由中间件
# ============================================================================

regex_middleware = RegexShortcutMiddleware(
    tool_registry=get_all_tools(),
    rules=REGEX_RULES,
)

# ============================================================================
# Agent 创建
# ============================================================================

agent = create_agent(
    model=qwen_model,
    tools=get_all_tools(),
    backend=skills_backend,
    middleware=[
        AuthMiddleware(),
        PermissionMiddleware(),        # AdminAgent 纯只读，拦截所有写操作
        regex_middleware,              # L1 正则快捷路由（运营日报等）
        skills_middleware,
    ],
    system_prompt=SYSTEM_PROMPT,
    context_schema=Context,
)
