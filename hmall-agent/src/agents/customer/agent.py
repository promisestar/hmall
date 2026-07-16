"""CustomerAgent 定义 — C 端客服助手 Agent。

使用 DeepAgents create_agent 声明式定义，配置：
- model: 通义千问 qwen-turbo
- tools: 18 个 C 端工具
- middleware: AuthMiddleware → PermissionMiddleware → RegexShortcutMiddleware → SkillsMiddleware
- context_schema: Context（agent_type, user_id, user_token, enable_rag）
- skills: 5 个 SKILL.md 规范文件
"""

from dataclasses import dataclass
from pathlib import Path

from deepagents import create_deep_agent as create_agent
from deepagents.backends import FilesystemBackend
from deepagents.middleware import SkillsMiddleware

from src.core.llms import qwen_model
from src.core.redis_checkpoint import checkpointer
from src.middleware.auth import AuthMiddleware
from src.middleware.permission import PermissionMiddleware
from src.middleware.regex_shortcut import RegexShortcutMiddleware

from src.agents.customer.prompts import SYSTEM_PROMPT
from src.agents.customer.regex_rules import REGEX_RULES
from src.agents.customer.tools import get_all_tools


@dataclass
class Context:
    """CustomerAgent 运行时上下文（通过 LangGraph SDK context 传入）。

    单次 run 不持久化，用于传递认证信息和运行时配置。
    """
    agent_type: str = "customer"     # "customer" or "admin"
    user_id: str = ""                # 当前 C 端用户 ID
    user_token: str = ""             # C 端用户 JWT Token
    enable_rag: bool = False         # RAG 开关（预留）


# ============================================================================
# Skills 配置 — 虚拟文件系统加载 SKILL.md 规范文件
# ============================================================================

skills_root = str(
    (Path(__file__).parent.parent.parent / "workspace" / "customer").resolve()
)
skills_backend = FilesystemBackend(root_dir=skills_root, virtual_mode=True)

skills_middleware = SkillsMiddleware(
    backend=skills_backend,
    sources=[
        "/skills/shopping-guide/",
        "/skills/seckill-order/",
        "/skills/cart-management/",
        "/skills/order-management/",
        "/skills/address-management/",
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
    model=qwen_model,                      # 通义千问 qwen-turbo
    tools=get_all_tools(),                 # 18 个 C 端工具
    backend=skills_backend,               # 虚拟文件系统
    middleware=[
        AuthMiddleware(),                  # 双 JWT 认证
        PermissionMiddleware(),            # 工具权限拦截
        regex_middleware,                  # L1 正则快捷路由
        skills_middleware,                 # Skills 规范加载
    ],
    system_prompt=SYSTEM_PROMPT,
    context_schema=Context,
    checkpointer=checkpointer,            # Redis Checkpoint 持久化 + interrupt 恢复
)
