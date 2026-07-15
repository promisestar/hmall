"""CustomerAgent L1 正则快捷路由规则。

仅拦截只读指令（无 interrupt），写操作由 L2 interrupt 处理。
每条规则：(正则模式, 工具名, 参数提取器)
"""

import re

# 参数提取器函数
def _extract_keyword(m: re.Match) -> dict:
    return {"keyword": m.group(1).strip()}


def _extract_order_id(m: re.Match) -> dict:
    return {"order_id": int(m.group(1))}


REGEX_RULES = [
    # === 秒杀（只读） ===
    (
        r"(?:查看|查询|当前).{0,3}秒杀",
        "get_seckill_activities_api",
        None,
    ),
    # === 购物车（只读） ===
    (
        r"(?:查看|查询|我的).{0,5}购物车",
        "get_cart_list_api",
        None,
    ),
    # === 订单（只读） ===
    (
        r"(?:查询|查看).{0,5}(?:待付款|待发货|待收货|已完成)?订单",
        "get_order_list_api",
        None,
    ),
    (
        r"(?:查看|看)\s*(?:订单\s*)?(\d+)",
        "get_order_detail_api",
        _extract_order_id,
    ),
    # === 地址（只读） ===
    (
        r"(?:查询|查看|我的).{0,5}地址",
        "get_address_list_api",
        None,
    ),
    # === 商品搜索（只读） ===
    (
        r"(?:搜索|查找|找)\s*(.+)",
        "search_items_api",
        _extract_keyword,
    ),
    # === 商品列表（只读） ===
    (
        r"(?:商品列表|浏览商品|看看商品|商品)",
        "get_item_page_api",
        None,
    ),
]

# 注意：以下操作不在此拦截，由 L2 interrupt 处理：
# - 取消订单：取消\s*(?:订单\s*)?(\d+) → cancel_order_api（需确认）
# - 确认收货：确认\s*(?:收货\s*)?(\d+) → confirm_receive_api（需确认）
# - 清空购物车：清空\s*购物车 → clear_cart_api（需确认）
# - 修改地址：修改\s*(\d+) → update_address_api（多轮收集）
# - 新增地址：新增地址|添加地址 → add_address_api（多轮收集）
# - 秒杀下单：秒杀\s*(?:商品\s*)?(\d+) → do_seckill_api（需确认）
