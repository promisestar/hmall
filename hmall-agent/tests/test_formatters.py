"""格式化函数测试。

测试 formatters 模块中的各种格式化函数。
"""

from src.tools.formatters import (
    format_address_list,
    format_cart_list,
    format_daily_report,
    format_item_page,
    format_order_list,
    format_seckill_activities,
    format_seckill_product,
    format_search_results,
)


class TestFormatSeckillActivities:
    def test_empty_list(self):
        result = format_seckill_activities([])
        assert "没有" in result

    def test_with_activities(self):
        activities = [
            {
                "title": "618专场",
                "status": 2,
                "sessions": [
                    {
                        "name": "10:00场",
                        "startTime": "2026-07-15 10:00:00",
                        "endTime": "2026-07-15 12:00:00",
                        "status": 2,
                        "products": [
                            {
                                "name": "iPhone 15",
                                "seckillPrice": 599900,
                                "originalPrice": 699900,
                                "remainingStock": 45,
                                "relationId": 1,
                            }
                        ],
                    }
                ],
            }
        ]
        result = format_seckill_activities(activities)
        assert "618专场" in result
        assert "5999.00" in result
        assert "45" in result


class TestFormatSearchResults:
    def test_empty(self):
        result = format_search_results({}, keyword="手机")
        assert "未找到" in result

    def test_with_items(self):
        page_dto = {
            "total": 2,
            "list": [
                {"id": 1, "name": "iPhone 15", "price": 599900, "stock": 45},
                {"id": 2, "name": "MacBook", "price": 999900, "stock": 12},
            ],
        }
        result = format_search_results(page_dto, keyword="手机")
        assert "2 件" in result
        assert "iPhone 15" in result
        assert "5999.00" in result


class TestFormatCartList:
    def test_empty(self):
        result = format_cart_list([])
        assert "空的" in result

    def test_with_items(self):
        carts = [
            {"itemId": 1, "name": "iPhone 15", "price": 599900, "num": 2},
        ]
        result = format_cart_list(carts)
        assert "iPhone 15" in result
        assert "5999.00" in result
        assert "11998.00" in result


class TestFormatOrderList:
    def test_empty(self):
        result = format_order_list({})
        assert "没有" in result

    def test_with_orders(self):
        page_dto = {
            "total": 1,
            "list": [
                {"id": 1001, "totalFee": 599900, "status": 1, "createTime": "2026-07-15 10:00:00"},
            ],
        }
        result = format_order_list(page_dto)
        assert "1001" in result
        assert "5999.00" in result
        assert "待付款" in result


class TestFormatAddressList:
    def test_empty(self):
        result = format_address_list([])
        assert "没有" in result

    def test_with_addresses(self):
        addresses = [
            {
                "id": 1,
                "name": "张三",
                "phone": "13800138000",
                "province": "广东省",
                "city": "深圳市",
                "region": "南山区",
                "detailAddress": "科技园路1号",
                "isDefault": 1,
            }
        ]
        result = format_address_list(addresses)
        assert "张三" in result
        assert "13800138000" in result
        assert "默认" in result
        assert "广东省" in result


class TestFormatDailyReport:
    def test_with_data(self):
        result = format_daily_report(
            orders={"total": 156},
            seckill_promotions={"total": 3},
            seckill_relations={"total": 12},
            products={"total": 248},
            users={"total": 1230},
        )
        assert "运营日报" in result
        assert "156" in result
        assert "248" in result
        assert "1230" in result

    def test_with_none(self):
        result = format_daily_report()
        assert "运营日报" in result
        assert "失败" in result
