"""格式化函数 — 将 API 返回数据格式化为用户友好的文本输出。

所有价格字段在 hmall 中以「分」为单位存储，格式化时自动转换为「元」。
"""

from datetime import datetime
from typing import Any


def _yuan(fen: int | float | str | None) -> str:
    """分 → 元（保留 2 位小数）。"""
    if fen is None:
        return "0.00"
    try:
        return f"{float(fen) / 100:.2f}"
    except (TypeError, ValueError):
        return "0.00"


def _status_text(status: int | None, mapping: dict[int, str]) -> str:
    """状态码 → 文本。"""
    if status is None:
        return "未知"
    return mapping.get(status, f"状态{status}")


# ==================== 秒杀 ====================

_SECKILL_ACTIVITY_STATUS = {1: "未开始", 2: "进行中", 3: "已结束"}
_SECKILL_PRODUCT_STATUS = {0: "未开始", 1: "抢购中", 2: "已售罄", 3: "已结束"}


def format_seckill_activities(activities: list[dict]) -> str:
    """格式化秒杀活动列表。"""
    if not activities:
        return "当前没有进行中的秒杀活动"

    lines = ["⚡ 当前秒杀活动", "─" * 30]
    for activity in activities:
        title = activity.get("title", "未知活动")
        status = _status_text(activity.get("status"), _SECKILL_ACTIVITY_STATUS)
        lines.append(f"\n📢 {title} [{status}]")

        sessions = activity.get("sessions", [])
        for session in sessions:
            s_name = session.get("name", "")
            s_start = session.get("startTime", "")[11:16] if session.get("startTime") else ""
            s_end = session.get("endTime", "")[11:16] if session.get("endTime") else ""
            s_status = _status_text(session.get("status"), _SECKILL_ACTIVITY_STATUS)
            lines.append(f"🕐 {s_name} ({s_start}-{s_end}) [{s_status}]")

            products = session.get("products", [])
            for p in products:
                name = p.get("name", "未知商品")
                seckill_price = _yuan(p.get("seckillPrice"))
                original_price = _yuan(p.get("originalPrice"))
                remaining = p.get("remainingStock", 0)
                relation_id = p.get("relationId", "")
                lines.append(
                    f"   ├── {name} | ¥{seckill_price}(原价¥{original_price}) "
                    f"| 剩余 {remaining} 件 [ID:{relation_id}]"
                )
    return "\n".join(lines)


def format_seckill_product(product: dict) -> str:
    """格式化秒杀商品详情。"""
    if not product:
        return "未找到该秒杀商品"

    name = product.get("name", "未知商品")
    seckill_price = _yuan(product.get("seckillPrice"))
    original_price = _yuan(product.get("originalPrice"))
    total = product.get("totalStock", 0)
    remaining = product.get("remainingStock", 0)
    sold = product.get("soldCount", 0)
    limit = product.get("limitNum", 1)
    status = _status_text(product.get("status"), _SECKILL_PRODUCT_STATUS)
    spec = product.get("spec", "")
    relation_id = product.get("relationId", "")

    lines = [
        f"⚡ 秒杀商品详情 [ID:{relation_id}]",
        "─" * 30,
        f"商品名: {name}",
    ]
    if spec:
        lines.append(f"规格: {spec}")
    lines.extend([
        f"秒杀价: ¥{seckill_price} (原价 ¥{original_price})",
        f"库存: {remaining}/{total} 件 (已抢 {sold} 件)",
        f"限购: {limit} 件",
        f"状态: {status}",
    ])
    return "\n".join(lines)


def format_seckill_result(result: dict) -> str:
    """格式化秒杀下单结果。"""
    if not result:
        return "秒杀请求未返回结果"

    status = result.get("status", "unknown")
    message = result.get("message", "")
    order_id = result.get("orderId")

    if status == "success":
        msg = f"✅ 秒杀成功！订单号: {order_id}，请尽快支付。"
    elif status == "pending":
        msg = f"⏳ {message or '秒杀请求已提交，正在排队处理...'}"
    else:
        msg = f"❌ {message or '秒杀失败，请稍后重试'}"
    return msg


# ==================== 商品 ====================

_ITEM_STATUS = {1: "在售", 2: "已下架", 3: "已删除"}


def format_search_results(page_dto: dict, keyword: str = "") -> str:
    """格式化商品搜索结果（PageDTO<ItemDTO>）。"""
    if not page_dto:
        return "搜索失败，请稍后重试"

    items = page_dto.get("list", []) or page_dto.get("records", [])
    total = page_dto.get("total", len(items))

    if not items:
        kw = f"「{keyword}」" if keyword else ""
        return f"未找到{kw}相关商品"

    lines = [f"📦 搜索结果（共 {total} 件）", "─" * 30]
    for i, item in enumerate(items, 1):
        name = item.get("name", "未知商品")
        price = _yuan(item.get("price"))
        stock = item.get("stock", 0)
        item_id = item.get("id", "")
        lines.append(f"{i}. {name} | ¥{price} | 库存 {stock} 件 [ID:{item_id}]")
    return "\n".join(lines)


def format_item_detail(item: dict) -> str:
    """格式化商品详情。"""
    if not item:
        return "未找到该商品"

    name = item.get("name", "未知商品")
    price = _yuan(item.get("price"))
    stock = item.get("stock", 0)
    status = _status_text(item.get("status"), _ITEM_STATUS)
    brand = item.get("brand", "")
    category = item.get("category", "")
    spec = item.get("spec", "")
    item_id = item.get("id", "")

    lines = [
        f"📦 商品详情 [ID:{item_id}]",
        "─" * 30,
        f"名称: {name}",
    ]
    if brand:
        lines.append(f"品牌: {brand}")
    if category:
        lines.append(f"分类: {category}")
    if spec:
        lines.append(f"规格: {spec}")
    lines.extend([
        f"价格: ¥{price}",
        f"库存: {stock} 件",
        f"状态: {status}",
    ])
    return "\n".join(lines)


def format_item_page(page_dto: dict) -> str:
    """格式化商品分页列表。"""
    if not page_dto:
        return "暂无商品数据"

    items = page_dto.get("list", []) or page_dto.get("records", [])
    total = page_dto.get("total", len(items))
    pages = page_dto.get("pages", 1)
    current = page_dto.get("current", 1)

    if not items:
        return "暂无商品数据"

    lines = [f"📦 商品列表（第 {current}/{pages} 页，共 {total} 件）", "─" * 30]
    for i, item in enumerate(items, 1):
        name = item.get("name", "未知商品")
        price = _yuan(item.get("price"))
        stock = item.get("stock", 0)
        item_id = item.get("id", "")
        lines.append(f"{i}. {name} | ¥{price} | 库存 {stock} 件 [ID:{item_id}]")
    return "\n".join(lines)


# ==================== 购物车 ====================


def format_cart_list(carts: list[dict]) -> str:
    """格式化购物车列表。"""
    if not carts:
        return "🛒 购物车是空的"

    lines = ["🛒 我的购物车", "─" * 30]
    total_price = 0
    for i, item in enumerate(carts, 1):
        name = item.get("name", "未知商品")
        price = _yuan(item.get("price"))
        num = item.get("num", 1)
        item_id = item.get("itemId", item.get("id", ""))
        subtotal = float(item.get("price", 0)) * num / 100
        total_price += subtotal
        lines.append(f"{i}. {name} | ¥{price} × {num} | ¥{subtotal:.2f} [ID:{item_id}]")

    lines.append("─" * 30)
    lines.append(f"总计: ¥{total_price:.2f}")
    return "\n".join(lines)


# ==================== 订单 ====================

_ORDER_STATUS = {
    1: "待付款",
    2: "已付款",
    3: "已发货",
    4: "确认收货",
    5: "交易取消",
}


def format_order_list(page_dto: dict) -> str:
    """格式化订单列表。"""
    if not page_dto:
        return "暂无订单数据"

    orders = page_dto.get("list", []) or page_dto.get("records", [])
    total = page_dto.get("total", len(orders))

    if not orders:
        return "您还没有订单"

    lines = [f"📋 订单列表（共 {total} 笔）", "─" * 30]
    for i, order in enumerate(orders, 1):
        order_id = order.get("id", "")
        total_fee = _yuan(order.get("totalFee"))
        status = _status_text(order.get("status"), _ORDER_STATUS)
        create_time = str(order.get("createTime", ""))[:10]
        lines.append(f"{i}. 订单 {order_id} | ¥{total_fee} | {status} | {create_time}")
    return "\n".join(lines)


def format_order_detail(order: dict) -> str:
    """格式化订单详情。"""
    if not order:
        return "未找到该订单"

    order_id = order.get("id", "")
    total_fee = _yuan(order.get("totalFee"))
    status = _status_text(order.get("status"), _ORDER_STATUS)
    create_time = str(order.get("createTime", ""))[:19]
    pay_time = str(order.get("payTime", ""))[:19] if order.get("payTime") else "未支付"

    lines = [
        f"📋 订单详情 [ID:{order_id}]",
        "─" * 30,
        f"订单号: {order_id}",
        f"总金额: ¥{total_fee}",
        f"状态: {status}",
        f"下单时间: {create_time}",
        f"支付时间: {pay_time}",
    ]

    details = order.get("orderDetails", []) or order.get("details", [])
    if details:
        lines.append("商品明细:")
        for d in details:
            name = d.get("name", "未知商品")
            price = _yuan(d.get("price"))
            num = d.get("num", 1)
            lines.append(f"  - {name} | ¥{price} × {num}")

    return "\n".join(lines)


# ==================== 地址 ====================


def format_address_list(addresses: list[dict]) -> str:
    """格式化地址列表。"""
    if not addresses:
        return "您还没有收货地址"

    lines = ["📍 收货地址列表", "─" * 30]
    for i, addr in enumerate(addresses, 1):
        name = addr.get("name", "")
        phone = addr.get("phone", "")
        province = addr.get("province", "")
        city = addr.get("city", "")
        region = addr.get("region", "")
        detail = addr.get("detailAddress", "")
        is_default = addr.get("isDefault", 0)
        addr_id = addr.get("id", "")
        default_tag = " [默认]" if is_default == 1 else ""
        full_addr = f"{province}{city}{region}{detail}"
        lines.append(
            f"{i}. {name} {phone}{default_tag}\n   {full_addr} [ID:{addr_id}]"
        )
    return "\n".join(lines)


# ==================== 管理端 ====================


def format_admin_product_page(page_dto: dict) -> str:
    """格式化管理端商品分页列表。"""
    if not page_dto:
        return "暂无商品数据"

    items = page_dto.get("list", []) or page_dto.get("records", [])
    total = page_dto.get("total", len(items))

    if not items:
        return "暂无商品数据"

    lines = [f"📦 商品管理（共 {total} 件）", "─" * 30]
    for i, item in enumerate(items, 1):
        name = item.get("name", "未知商品")
        price = _yuan(item.get("price"))
        stock = item.get("stock", 0)
        status = _status_text(item.get("status"), _ITEM_STATUS)
        item_id = item.get("id", "")
        lines.append(f"{i}. [{item_id}] {name} | ¥{price} | 库存{stock} | {status}")
    return "\n".join(lines)


def format_admin_order_page(page_dto: dict) -> str:
    """格式化管理端订单分页列表。"""
    if not page_dto:
        return "暂无订单数据"

    orders = page_dto.get("list", []) or page_dto.get("records", [])
    total = page_dto.get("total", len(orders))

    if not orders:
        return "暂无订单数据"

    lines = [f"📋 订单管理（共 {total} 笔）", "─" * 30]
    for i, order in enumerate(orders, 1):
        order_id = order.get("id", "")
        total_fee = _yuan(order.get("totalFee"))
        status = _status_text(order.get("status"), _ORDER_STATUS)
        user_id = order.get("userId", "")
        create_time = str(order.get("createTime", ""))[:10]
        lines.append(
            f"{i}. [{order_id}] 用户{user_id} | ¥{total_fee} | {status} | {create_time}"
        )
    return "\n".join(lines)


def format_admin_seckill_page(page_dto: dict, title: str = "秒杀活动") -> str:
    """格式化管理端秒杀分页列表。"""
    if not page_dto:
        return f"暂无{title}数据"

    items = page_dto.get("list", []) or page_dto.get("records", [])
    total = page_dto.get("total", len(items))

    if not items:
        return f"暂无{title}数据"

    lines = [f"⚡ {title}管理（共 {total} 条）", "─" * 30]
    for i, item in enumerate(items, 1):
        item_title = item.get("title", item.get("name", ""))
        status = _status_text(item.get("status"), _SECKILL_ACTIVITY_STATUS)
        item_id = item.get("id", "")
        lines.append(f"{i}. [{item_id}] {item_title} | {status}")
    return "\n".join(lines)


def format_admin_user_page(page_dto: dict) -> str:
    """格式化管理端用户分页列表。"""
    if not page_dto:
        return "暂无用户数据"

    users = page_dto.get("list", []) or page_dto.get("records", [])
    total = page_dto.get("total", len(users))

    if not users:
        return "暂无用户数据"

    lines = [f"👥 用户管理（共 {total} 人）", "─" * 30]
    for i, user in enumerate(users, 1):
        username = user.get("username", "")
        phone = user.get("phone", "")
        balance = _yuan(user.get("balance"))
        status = "正常" if user.get("status", 1) == 1 else "冻结"
        user_id = user.get("id", "")
        lines.append(f"{i}. [{user_id}] {username} | {phone} | 余额¥{balance} | {status}")
    return "\n".join(lines)


def format_daily_report(
    orders: dict | None = None,
    seckill_promotions: dict | None = None,
    seckill_relations: dict | None = None,
    products: dict | None = None,
    users: dict | None = None,
) -> str:
    """格式化运营日报。

    各参数为对应查询工具返回的 PageDTO 数据。
    """
    today = datetime.now().strftime("%Y-%m-%d")

    lines = [
        "━" * 30,
        f"📅 {today} 枫叶商城运营日报",
        "━" * 30,
    ]

    # 订单概览
    lines.append("\n【订单概览】")
    if orders:
        order_total = orders.get("total", 0)
        lines.append(f"- 订单总数: {order_total} 笔")
    else:
        lines.append("- 订单数据获取失败")

    # 秒杀活动
    lines.append("\n【秒杀活动】")
    if seckill_promotions:
        promo_total = seckill_promotions.get("total", 0)
        lines.append(f"- 秒杀活动: {promo_total} 场")
    else:
        lines.append("- 秒杀活动数据获取失败")

    if seckill_relations:
        rel_total = seckill_relations.get("total", 0)
        lines.append(f"- 秒杀商品关联: {rel_total} 条")
    else:
        lines.append("- 秒杀商品数据获取失败")

    # 商品概况
    lines.append("\n【商品概况】")
    if products:
        product_total = products.get("total", 0)
        lines.append(f"- 商品总数: {product_total} 件")
    else:
        lines.append("- 商品数据获取失败")

    # 用户概况
    lines.append("\n【用户概况】")
    if users:
        user_total = users.get("total", 0)
        lines.append(f"- 用户总数: {user_total} 人")
    else:
        lines.append("- 用户数据获取失败")

    lines.append("━" * 30)
    return "\n".join(lines)
