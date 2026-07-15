"""AdminAgent 工具集 — 10 个管理端只读 @tool 工具 + generate_daily_report 编排。

所有工具纯只读，管理端 API 路径以 /admin 开头，GatewayClient 自动解包 R<T>。
需要管理端 JWT Token 认证（通过 RunnableConfig 自动注入）。
"""

import asyncio

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from src.gateway.http_client import GatewayError, extract_token_from_config, gateway_client
from src.tools.formatters import (
    format_admin_order_page,
    format_admin_product_page,
    format_admin_seckill_page,
    format_admin_user_page,
    format_daily_report,
    format_order_detail,
    format_item_detail,
)


# ============================================================================
# 商品管理（2 个）
# ============================================================================


@tool
async def admin_get_product_page_api(
    config: RunnableConfig,
    page_no: int = 1,
    page_size: int = 10,
    keyword: str = "",
) -> str:
    """分页查询商品列表。

    Args:
        page_no: 页码，默认第 1 页
        page_size: 每页数量，默认 10
        keyword: 搜索关键词（可选）
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 请先登录管理后台"
    params = {"pageNo": page_no, "pageSize": page_size}
    if keyword:
        params["keyword"] = keyword
    try:
        result = await gateway_client.get("/admin/product/list", token=token, params=params)
        return format_admin_product_page(result)
    except GatewayError as e:
        return f"❌ 获取商品列表失败: {e}"


@tool
async def admin_get_product_detail_api(product_id: int, config: RunnableConfig) -> str:
    """查看商品详情。

    Args:
        product_id: 商品 ID
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 请先登录管理后台"
    try:
        result = await gateway_client.get(f"/admin/product/{product_id}", token=token)
        return format_item_detail(result)
    except GatewayError as e:
        return f"❌ 获取商品详情失败: {e}"


# ============================================================================
# 订单管理（2 个）
# ============================================================================


@tool
async def admin_get_order_page_api(
    config: RunnableConfig,
    page_no: int = 1,
    page_size: int = 10,
    status: int | None = None,
    start_time: str = "",
    end_time: str = "",
) -> str:
    """分页查询订单列表，支持按状态和时间筛选。

    Args:
        page_no: 页码，默认第 1 页
        page_size: 每页数量，默认 10
        status: 订单状态筛选（1=待付款, 2=已付款, 3=已发货, 4=确认收货, 5=交易取消）
        start_time: 开始时间（格式：yyyy-MM-dd，可选）
        end_time: 结束时间（格式：yyyy-MM-dd，可选）
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 请先登录管理后台"
    params = {"pageNo": page_no, "pageSize": page_size}
    if status is not None:
        params["status"] = status
    if start_time:
        params["startTime"] = start_time
    if end_time:
        params["endTime"] = end_time
    try:
        result = await gateway_client.get("/admin/order/list", token=token, params=params)
        return format_admin_order_page(result)
    except GatewayError as e:
        return f"❌ 获取订单列表失败: {e}"


@tool
async def admin_get_order_detail_api(order_id: int, config: RunnableConfig) -> str:
    """查看订单详情。

    Args:
        order_id: 订单 ID
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 请先登录管理后台"
    try:
        result = await gateway_client.get(f"/admin/order/{order_id}", token=token)
        return format_order_detail(result)
    except GatewayError as e:
        return f"❌ 获取订单详情失败: {e}"


# ============================================================================
# 秒杀管理（4 个）
# ============================================================================


@tool
async def admin_get_seckill_promotion_page_api(
    config: RunnableConfig,
    page_no: int = 1,
    page_size: int = 10,
    title: str = "",
    status: int | None = None,
) -> str:
    """分页查询秒杀活动列表。

    Args:
        page_no: 页码，默认第 1 页
        page_size: 每页数量，默认 10
        title: 活动标题筛选（可选）
        status: 活动状态筛选（可选）
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 请先登录管理后台"
    params = {"pageNo": page_no, "pageSize": page_size}
    if title:
        params["title"] = title
    if status is not None:
        params["status"] = status
    try:
        result = await gateway_client.get(
            "/admin/seckill/promotion/list", token=token, params=params
        )
        return format_admin_seckill_page(result, "秒杀活动")
    except GatewayError as e:
        return f"❌ 获取秒杀活动列表失败: {e}"


@tool
async def admin_get_seckill_relation_page_api(
    config: RunnableConfig,
    page_no: int = 1,
    page_size: int = 10,
    session_id: int | None = None,
    promotion_id: int | None = None,
) -> str:
    """分页查询秒杀商品关联列表（含实时库存）。

    Args:
        page_no: 页码，默认第 1 页
        page_size: 每页数量，默认 10
        session_id: 场次 ID 筛选（可选）
        promotion_id: 活动 ID 筛选（可选）
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 请先登录管理后台"
    params = {"pageNo": page_no, "pageSize": page_size}
    if session_id is not None:
        params["sessionId"] = session_id
    if promotion_id is not None:
        params["promotionId"] = promotion_id
    try:
        result = await gateway_client.get(
            "/admin/seckill/relation/list", token=token, params=params
        )
        return format_admin_seckill_page(result, "秒杀商品关联")
    except GatewayError as e:
        return f"❌ 获取秒杀商品关联列表失败: {e}"


@tool
async def admin_get_seckill_order_page_api(
    config: RunnableConfig,
    page_no: int = 1,
    page_size: int = 10,
    status: int | None = None,
    relation_id: int | None = None,
    user_id: int | None = None,
) -> str:
    """分页查询秒杀订单列表。

    Args:
        page_no: 页码，默认第 1 页
        page_size: 每页数量，默认 10
        status: 订单状态筛选（可选）
        relation_id: 秒杀商品关联 ID 筛选（可选）
        user_id: 用户 ID 筛选（可选）
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 请先登录管理后台"
    params = {"pageNo": page_no, "pageSize": page_size}
    if status is not None:
        params["status"] = status
    if relation_id is not None:
        params["relationId"] = relation_id
    if user_id is not None:
        params["userId"] = user_id
    try:
        result = await gateway_client.get(
            "/admin/seckill/order/list", token=token, params=params
        )
        return format_admin_order_page(result)
    except GatewayError as e:
        return f"❌ 获取秒杀订单列表失败: {e}"


@tool
async def admin_get_seckill_stock_api(relation_id: int, config: RunnableConfig) -> str:
    """查询秒杀商品每日库存快照。

    Args:
        relation_id: 秒杀商品关联 ID
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 请先登录管理后台"
    try:
        result = await gateway_client.get(
            f"/admin/seckill/stock/{relation_id}", token=token
        )
        if not result:
            return f"暂无秒杀商品 {relation_id} 的库存快照数据"

        lines = [f"📦 秒杀商品 {relation_id} 库存快照", "─" * 30]
        for i, stock in enumerate(result, 1):
            date = stock.get("date", stock.get("createTime", ""))
            total = stock.get("totalStock", 0)
            remaining = stock.get("remainingStock", 0)
            sold = total - remaining
            lines.append(f"{i}. {date} | 总库存 {total} | 剩余 {remaining} | 已售 {sold}")
        return "\n".join(lines)
    except GatewayError as e:
        return f"❌ 获取库存快照失败: {e}"


# ============================================================================
# 用户管理（2 个）
# ============================================================================


@tool
async def admin_get_user_page_api(
    config: RunnableConfig,
    page_no: int = 1,
    page_size: int = 10,
    keyword: str = "",
    status: int | None = None,
) -> str:
    """分页查询 C 端用户列表。

    Args:
        page_no: 页码，默认第 1 页
        page_size: 每页数量，默认 10
        keyword: 搜索关键词（用户名/手机号，可选）
        status: 用户状态筛选（可选）
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 请先登录管理后台"
    params = {"pageNo": page_no, "pageSize": page_size}
    if keyword:
        params["keyword"] = keyword
    if status is not None:
        params["status"] = status
    try:
        result = await gateway_client.get("/admin/member/list", token=token, params=params)
        return format_admin_user_page(result)
    except GatewayError as e:
        return f"❌ 获取用户列表失败: {e}"


@tool
async def admin_get_user_detail_api(user_id: int, config: RunnableConfig) -> str:
    """查看 C 端用户详情。

    Args:
        user_id: 用户 ID
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 请先登录管理后台"
    try:
        result = await gateway_client.get(f"/admin/member/{user_id}", token=token)
        if not result:
            return f"未找到用户 {user_id}"

        username = result.get("username", "")
        phone = result.get("phone", "")
        balance = f"{float(result.get('balance', 0)) / 100:.2f}"
        status = "正常" if result.get("status", 1) == 1 else "冻结"
        created = str(result.get("createTime", ""))[:10]

        lines = [
            f"👥 用户详情 [ID:{user_id}]",
            "─" * 30,
            f"用户名: {username}",
            f"手机号: {phone}",
            f"余额: ¥{balance}",
            f"状态: {status}",
            f"注册时间: {created}",
        ]
        return "\n".join(lines)
    except GatewayError as e:
        return f"❌ 获取用户详情失败: {e}"


# ============================================================================
# 运营日报（多工具编排）
# ============================================================================


@tool
async def generate_daily_report(config: RunnableConfig) -> str:
    """生成运营日报。自动并发调用 5 个查询工具，格式化输出运营摘要。"""
    token = extract_token_from_config(config)
    if not token:
        return "❌ 请先登录管理后台"

    # 并发调用 5 个查询 API，各取第 1 页 1 条以获取总数
    params = {"pageNo": 1, "pageSize": 1}

    async def _safe_get(path: str, params: dict | None = None) -> dict | None:
        try:
            return await gateway_client.get(path, token=token, params=params or params)
        except GatewayError as e:
            return None

    try:
        orders, seckill_promotions, seckill_relations, products, users = (
            await asyncio.gather(
                _safe_get("/admin/order/list"),
                _safe_get("/admin/seckill/promotion/list"),
                _safe_get("/admin/seckill/relation/list"),
                _safe_get("/admin/product/list"),
                _safe_get("/admin/member/list"),
            )
        )
    except Exception as e:
        return f"❌ 生成运营日报失败: {e}"

    return format_daily_report(
        orders=orders,
        seckill_promotions=seckill_promotions,
        seckill_relations=seckill_relations,
        products=products,
        users=users,
    )


# ============================================================================
# 工具注册
# ============================================================================


def get_all_tools():
    """返回 AdminAgent 所需的全部工具列表。"""
    return [
        # 商品管理
        admin_get_product_page_api,
        admin_get_product_detail_api,
        # 订单管理
        admin_get_order_page_api,
        admin_get_order_detail_api,
        # 秒杀管理
        admin_get_seckill_promotion_page_api,
        admin_get_seckill_relation_page_api,
        admin_get_seckill_order_page_api,
        admin_get_seckill_stock_api,
        # 用户管理
        admin_get_user_page_api,
        admin_get_user_detail_api,
        # 运营日报
        generate_daily_report,
    ]
