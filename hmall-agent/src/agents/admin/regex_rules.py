"""AdminAgent L1 正则快捷路由规则。

运营日报等高频指令直接路由到对应工具，跳过 LLM。
每条规则：(正则模式, 工具名, 参数提取器)
"""


REGEX_RULES = [
    # === 运营日报（多工具编排） ===
    (
        r"(?:运营|生成|帮我做).{0,3}日报",
        "generate_daily_report",
        None,
    ),
    # === 商品列表 ===
    (
        r"(?:查看|查询|商品).{0,3}列表",
        "admin_get_product_page_api",
        None,
    ),
    # === 订单列表 ===
    (
        r"(?:查看|查询).{0,5}订单",
        "admin_get_order_page_api",
        None,
    ),
    # === 秒杀活动列表 ===
    (
        r"(?:秒杀|查看).{0,3}活动",
        "admin_get_seckill_promotion_page_api",
        None,
    ),
]
