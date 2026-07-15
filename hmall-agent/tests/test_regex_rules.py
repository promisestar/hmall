"""RegexShortcutMiddleware 正则路由匹配测试。

测试 L1 正则规则是否能正确匹配用户输入并路由到对应工具。
"""

import pytest

from src.agents.customer.regex_rules import REGEX_RULES as CUSTOMER_RULES
from src.agents.admin.regex_rules import REGEX_RULES as ADMIN_RULES


class TestCustomerRegexRules:
    """CustomerAgent 正则路由规则测试。"""

    def _match(self, text: str) -> tuple[str, dict] | None:
        """尝试匹配文本，返回 (工具名, 参数) 或 None。"""
        import re

        for pattern, tool_name, extractor in CUSTOMER_RULES:
            match = re.search(pattern, text)
            if match:
                params = extractor(match) if extractor else {}
                return tool_name, params
        return None

    def test_seckill_activities(self):
        """查看秒杀 → get_seckill_activities_api"""
        result = self._match("查看秒杀活动")
        assert result is not None
        assert result[0] == "get_seckill_activities_api"

    def test_seckill_query(self):
        """查询秒杀 → get_seckill_activities_api"""
        result = self._match("查询秒杀")
        assert result is not None
        assert result[0] == "get_seckill_activities_api"

    def test_cart_list(self):
        """查看购物车 → get_cart_list_api"""
        result = self._match("查看购物车")
        assert result is not None
        assert result[0] == "get_cart_list_api"

    def test_my_cart(self):
        """我的购物车 → get_cart_list_api"""
        result = self._match("我的购物车")
        assert result is not None
        assert result[0] == "get_cart_list_api"

    def test_order_list(self):
        """查看订单 → get_order_list_api"""
        result = self._match("查看订单")
        assert result is not None
        assert result[0] == "get_order_list_api"

    def test_order_detail(self):
        """查看订单100 → get_order_detail_api, order_id=100"""
        result = self._match("查看订单100")
        assert result is not None
        assert result[0] == "get_order_detail_api"
        assert result[1] == {"order_id": 100}

    def test_address_list(self):
        """查看地址 → get_address_list_api"""
        result = self._match("查看地址")
        assert result is not None
        assert result[0] == "get_address_list_api"

    def test_search_items(self):
        """搜索手机 → search_items_api, keyword=手机"""
        result = self._match("搜索手机")
        assert result is not None
        assert result[0] == "search_items_api"
        assert result[1] == {"keyword": "手机"}

    def test_no_match_chat(self):
        """闲聊不匹配 → None"""
        result = self._match("你好，今天天气怎么样？")
        assert result is None

    def test_no_match_write_op(self):
        """写操作不匹配（由 L2 interrupt 处理）"""
        result = self._match("取消订单100")
        assert result is None


class TestAdminRegexRules:
    """AdminAgent 正则路由规则测试。"""

    def _match(self, text: str) -> tuple[str, dict] | None:
        """尝试匹配文本。"""
        import re

        for pattern, tool_name, extractor in ADMIN_RULES:
            match = re.search(pattern, text)
            if match:
                params = extractor(match) if extractor else {}
                return tool_name, params
        return None

    def test_daily_report(self):
        """运营日报 → generate_daily_report"""
        result = self._match("运营日报")
        assert result is not None
        assert result[0] == "generate_daily_report"

    def test_generate_report(self):
        """生成日报 → generate_daily_report"""
        result = self._match("生成日报")
        assert result is not None
        assert result[0] == "generate_daily_report"

    def test_product_list(self):
        """商品列表 → admin_get_product_page_api"""
        result = self._match("查看商品列表")
        assert result is not None
        assert result[0] == "admin_get_product_page_api"

    def test_order_list(self):
        """查看订单 → admin_get_order_page_api"""
        result = self._match("查看订单")
        assert result is not None
        assert result[0] == "admin_get_order_page_api"

    def test_seckill_promotion(self):
        """秒杀活动 → admin_get_seckill_promotion_page_api"""
        result = self._match("秒杀活动")
        assert result is not None
        assert result[0] == "admin_get_seckill_promotion_page_api"
