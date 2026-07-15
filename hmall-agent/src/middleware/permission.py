"""PermissionMiddleware — 工具权限拦截中间件。

AdminAgent 纯只读：过滤掉所有写操作工具，LLM 无法选择它们。
CustomerAgent：允许所有工具（写操作需 Token + interrupt 二次确认）。
"""

import logging

from langchain.agents.middleware import AgentMiddleware, ModelRequest

logger = logging.getLogger(__name__)

# 写操作工具集（危险操作）— CustomerAgent 可用，AdminAgent 不可用
WRITE_TOOLS = {
    # 购物车写操作
    "add_to_cart_api",
    "update_cart_quantity_api",
    "delete_cart_item_api",
    "clear_cart_api",
    # 订单写操作
    "cancel_order_api",
    "confirm_receive_api",
    # 地址写操作
    "add_address_api",
    "update_address_api",
    # 秒杀写操作
    "do_seckill_api",
}


class PermissionMiddleware(AgentMiddleware):
    """工具权限拦截中间件。

    AdminAgent 纯只读：过滤掉所有写操作工具，LLM 无法选择它们。
    CustomerAgent：允许所有工具（写操作需 Token + interrupt 二次确认）。
    """

    def wrap_model_call(self, request: ModelRequest, handler):
        """同步版本。"""
        request = self._filter_tools(request)
        return handler(request)

    async def awrap_model_call(self, request: ModelRequest, handler):
        """异步版本 — 中间件入口。"""
        request = self._filter_tools(request)
        return await handler(request)

    def _filter_tools(self, request: ModelRequest) -> ModelRequest:
        """根据 agent_type 过滤工具。"""
        context = getattr(request.runtime, "context", None) if request.runtime else None
        agent_type = (
            getattr(context, "agent_type", "customer") if context else "customer"
        )

        if agent_type != "admin":
            return request

        # admin 过滤掉写操作工具
        tools = getattr(request, "tools", None) or []
        filtered_tools = [tool for tool in tools if tool.name not in WRITE_TOOLS]

        if len(filtered_tools) < len(tools):
            removed = len(tools) - len(filtered_tools)
            logger.info(
                "AdminAgent 过滤了 %d 个写操作工具，剩余 %d 个只读工具",
                removed,
                len(filtered_tools),
            )

        # 使用 override 方法替换 tools（如果 ModelRequest 支持）
        try:
            return request.override(tools=filtered_tools)
        except (AttributeError, TypeError):
            # 如果 override 不可用，直接修改（降级处理）
            if hasattr(request, "tools"):
                request.tools = filtered_tools
            return request
