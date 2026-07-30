"""CustomerAgent 工具集 — 18 个 C 端 @tool 工具。

工具分类：
- 商品浏览（3 个）：search_items_api, get_item_detail_api, get_item_page_api
- 秒杀（3 个）：get_seckill_activities_api, get_seckill_product_api, do_seckill_api（interrupt）
- 购物车（5 个）：get_cart_list_api, add_to_cart_api, update_cart_quantity_api,
                  delete_cart_item_api（interrupt）, clear_cart_api（interrupt）
- 订单（4 个）：get_order_list_api, get_order_detail_api, cancel_order_api（interrupt）,
                confirm_receive_api（interrupt）
- 地址（3 个）：get_address_list_api, add_address_api（interrupt 多轮）,
                update_address_api（interrupt 多轮）

需要认证的工具通过 RunnableConfig 自动注入 token（不展示给 LLM）。
"""

import asyncio
import re

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool
from langgraph.types import interrupt

from src.gateway.http_client import (
    GatewayError,
    _extract_user_id,
    extract_token_from_config,
    gateway_client,
)
from src.profile.store import profile_store
from src.profile.memory import get_memories, save_memory
from src.tools.formatters import (
    format_address_list,
    format_cart_list,
    format_item_detail,
    format_item_page,
    format_order_detail,
    format_order_list,
    format_preferences,
    format_recommendations,
    format_search_results,
    format_seckill_activities,
    format_seckill_product,
    format_seckill_result,
)


# ============================================================================
# 商品浏览（3 个）— Gateway 对 /items/** 和 /search/** 排除认证
# ============================================================================


@tool
async def search_items_api(keyword: str, page_no: int = 1, page_size: int = 10) -> str:
    """搜索商品。支持按关键词全文检索。

    Args:
        keyword: 搜索关键词（商品名/品牌/分类）
        page_no: 页码，默认第 1 页
        page_size: 每页数量，默认 10
    """
    params = {"key": keyword, "pageNo": page_no, "pageSize": page_size}
    try:
        result = await gateway_client.get("/search/list", params=params)
        return format_search_results(result, keyword)
    except GatewayError as e:
        return f"❌ 搜索失败: {e}"


@tool
async def get_item_detail_api(item_id: int) -> str:
    """根据商品 ID 查看商品详情。

    Args:
        item_id: 商品 ID
    """
    try:
        result = await gateway_client.get(f"/items/{item_id}")
        return format_item_detail(result)
    except GatewayError as e:
        return f"❌ 获取商品详情失败: {e}"


@tool
async def get_item_page_api(page_no: int = 1, page_size: int = 10) -> str:
    """分页浏览商品列表。

    Args:
        page_no: 页码，默认第 1 页
        page_size: 每页数量，默认 10
    """
    params = {"pageNo": page_no, "pageSize": page_size}
    try:
        result = await gateway_client.get("/items/page", params=params)
        return format_item_page(result)
    except GatewayError as e:
        return f"❌ 获取商品列表失败: {e}"


# ============================================================================
# 秒杀（3 个）
# ============================================================================


@tool
async def get_seckill_activities_api() -> str:
    """获取当前所有秒杀活动列表，含场次、商品和实时库存信息。"""
    try:
        result = await gateway_client.get("/seckill/activities")
        if not result:
            return "当前没有进行中的秒杀活动"
        return format_seckill_activities(result)
    except GatewayError as e:
        return f"❌ 获取秒杀活动失败: {e}"


@tool
async def get_seckill_product_api(relation_id: int) -> str:
    """查看秒杀商品详情，含实时库存和限购信息。

    Args:
        relation_id: 秒杀商品关联 ID
    """
    try:
        result = await gateway_client.get(f"/seckill/products/{relation_id}")
        return format_seckill_product(result)
    except GatewayError as e:
        return f"❌ 获取秒杀商品详情失败: {e}"


@tool
async def do_seckill_api(relation_id: int, config: RunnableConfig) -> str:
    """秒杀下单。需要登录并二次确认。

    Args:
        relation_id: 秒杀商品关联 ID
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 秒杀需要先登录，请登录后再试"

    # 先查询商品详情
    try:
        product = await gateway_client.get(f"/seckill/products/{relation_id}")
    except GatewayError as e:
        return f"❌ 获取秒杀商品详情失败: {e}"

    if not product:
        return f"未找到秒杀商品 relationId={relation_id}"

    name = product.get("name", "未知商品")
    seckill_price = f"{float(product.get('seckillPrice', 0)) / 100:.2f}"
    limit = product.get("limitNum", 1)
    remaining = product.get("remainingStock", 0)

    if remaining <= 0:
        return f"❌ {name} 已售罄"

    # L2: interrupt 请求用户确认
    approval = interrupt({
        "type": "confirmation",
        "message": (
            f"确认秒杀以下商品？\n"
            f"商品名: {name}\n"
            f"秒杀价: ¥{seckill_price}\n"
            f"限购: {limit}件\n"
            f"剩余: {remaining}件\n"
            f'回复"确认"下单'
        ),
        "expected_response": "确认",
    })

    if approval.strip() == "确认":
        try:
            result = await gateway_client.post(
                f"/seckill/order/{relation_id}",
                token=token,
                params={"quantity": 1},
            )
            return format_seckill_result(result)
        except GatewayError as e:
            if e.status_code == 429:
                return "❌ 秒杀请求过于频繁，请 5 秒后重试"
            return f"❌ 秒杀失败: {e}"
    else:
        return "❌ 已取消秒杀"


# ============================================================================
# 购物车（5 个）— 需要登录
# ============================================================================


@tool
async def get_cart_list_api(config: RunnableConfig) -> str:
    """查看当前用户的购物车列表。需要登录。"""
    token = extract_token_from_config(config)
    if not token:
        return "❌ 查看购物车需要先登录"
    try:
        result = await gateway_client.get("/carts", token=token)
        return format_cart_list(result)
    except GatewayError as e:
        return f"❌ 获取购物车失败: {e}"


@tool
async def add_to_cart_api(item_id: int, config: RunnableConfig) -> str:
    """将商品加入购物车。需要登录。

    Args:
        item_id: 商品 ID
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 加入购物车需要先登录"
    try:
        await gateway_client.post(
            "/carts",
            token=token,
            json={"itemId": item_id, "num": 1},
        )

        # Phase 2: cart 画像由后端 CartServiceImpl.addItem2Cart 写入，
        # Agent 不再重复记录（避免双重记录，后端覆盖 Agent + 前端 UI 全部加购路径）

        return f"✅ 商品 {item_id} 已加入购物车"
    except GatewayError as e:
        return f"❌ 加入购物车失败: {e}"


@tool
async def update_cart_quantity_api(item_id: int, num: int, config: RunnableConfig) -> str:
    """修改购物车中商品的数量。需要登录。

    Args:
        item_id: 商品 ID
        num: 新的数量（必须 >= 1）
    """
    if num < 1:
        return "❌ 数量必须大于等于 1"

    token = extract_token_from_config(config)
    if not token:
        return "❌ 修改购物车需要先登录"
    try:
        await gateway_client.put(
            f"/carts/{item_id}",
            token=token,
            json={"num": num},
        )

        # Phase 2: 修改数量不记录 cart 事件（add_to_cart_api 已记录，修改数量不改变偏好方向）

        return f"✅ 购物车中商品 {item_id} 的数量已修改为 {num}"
    except GatewayError as e:
        return f"❌ 修改购物车数量失败: {e}"


@tool
async def delete_cart_item_api(item_id: int, config: RunnableConfig) -> str:
    """删除购物车中的指定商品。需要登录并二次确认。

    Args:
        item_id: 商品 ID
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 删除购物车商品需要先登录"

    # L2: interrupt 请求用户确认
    approval = interrupt({
        "type": "confirmation",
        "message": f"确定要删除购物车中的商品 {item_id}？回复\"确认删除\"执行",
        "expected_response": "确认删除",
    })

    if approval.strip() == "确认删除":
        try:
            await gateway_client.delete(f"/carts/{item_id}", token=token)
            return f"✅ 购物车中商品 {item_id} 已删除"
        except GatewayError as e:
            return f"❌ 删除购物车商品失败: {e}"
    else:
        return "❌ 已取消删除"


@tool
async def clear_cart_api(config: RunnableConfig) -> str:
    """清空购物车中的所有商品。需要登录并二次确认。"""
    token = extract_token_from_config(config)
    if not token:
        return "❌ 清空购物车需要先登录"

    # 先获取购物车列表，检查是否为空
    try:
        cart_list = await gateway_client.get("/carts", token=token)
    except GatewayError as e:
        return f"❌ 获取购物车失败: {e}"

    if not cart_list:
        return "购物车已经是空的"

    # L2: interrupt 请求用户确认
    approval = interrupt({
        "type": "confirmation",
        "message": f"确定要清空购物车中的所有 {len(cart_list)} 件商品？回复\"确认删除\"执行",
        "expected_response": "确认删除",
    })

    if approval.strip() == "确认删除":
        # 收集所有 item ID 并批量删除
        item_ids = [
            item.get("itemId", item.get("id"))
            for item in cart_list
            if item.get("itemId") or item.get("id")
        ]
        if item_ids:
            try:
                ids_str = ",".join(str(i) for i in item_ids)
                await gateway_client.delete("/carts", token=token, params={"ids": ids_str})
                return "✅ 购物车已清空"
            except GatewayError as e:
                return f"❌ 清空购物车失败: {e}"
        return "购物车已经是空的"
    else:
        return "❌ 已取消清空购物车"


# ============================================================================
# 订单（4 个）— 需要登录
# ============================================================================


@tool
async def get_order_list_api(
    config: RunnableConfig,
    page_no: int = 1,
    page_size: int = 10,
) -> str:
    """查看当前用户的订单列表。需要登录。

    Args:
        page_no: 页码，默认第 1 页
        page_size: 每页数量，默认 10
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 查看订单需要先登录"
    params = {"pageNo": page_no, "pageSize": page_size}
    try:
        result = await gateway_client.get("/orders/page", token=token, params=params)
        return format_order_list(result)
    except GatewayError as e:
        return f"❌ 获取订单列表失败: {e}"


@tool
async def get_order_detail_api(order_id: int, config: RunnableConfig) -> str:
    """查看订单详情。需要登录。

    Args:
        order_id: 订单 ID
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 查看订单详情需要先登录"
    try:
        result = await gateway_client.get(f"/orders/{order_id}", token=token)
        return format_order_detail(result)
    except GatewayError as e:
        return f"❌ 获取订单详情失败: {e}"


@tool
async def cancel_order_api(order_id: int, config: RunnableConfig) -> str:
    """取消订单。需要登录并二次确认。

    Args:
        order_id: 订单 ID
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 取消订单需要先登录"

    # 先查询订单详情获取金额信息
    try:
        order = await gateway_client.get(f"/orders/{order_id}", token=token)
    except GatewayError as e:
        return f"❌ 获取订单信息失败: {e}"

    if not order:
        return f"未找到订单 {order_id}"

    total_fee = f"{float(order.get('totalFee', 0)) / 100:.2f}"

    # L2: interrupt 请求用户确认
    approval = interrupt({
        "type": "confirmation",
        "message": (
            f"确定要取消订单「{order_id}」？\n"
            f"订单金额: ¥{total_fee}\n"
            f'回复"确认取消"执行'
        ),
        "expected_response": "确认取消",
    })

    if approval.strip() == "确认取消":
        try:
            await gateway_client.post(
                "/orders/batch/close",
                token=token,
                json=[order_id],
            )
            return f"✅ 订单 {order_id} 已取消"
        except GatewayError as e:
            return f"❌ 取消订单失败: {e}"
    else:
        return "❌ 已取消操作"


@tool
async def confirm_receive_api(order_id: int, config: RunnableConfig) -> str:
    """确认收货。需要登录并二次确认。

    Args:
        order_id: 订单 ID
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 确认收货需要先登录"

    # L2: interrupt 请求用户确认
    approval = interrupt({
        "type": "confirmation",
        "message": f"确定已收到订单「{order_id}」的商品？回复\"确认收货\"执行",
        "expected_response": "确认收货",
    })

    if approval.strip() == "确认收货":
        try:
            await gateway_client.put(f"/orders/{order_id}", token=token)

            # Phase 2: purchase 画像由后端 paySuccessListener 在支付成功时写入，
            # 确认收货不再重复记录 purchase 事件（避免画像得分翻倍）

            return f"✅ 订单 {order_id} 已确认收货"
        except GatewayError as e:
            return f"❌ 确认收货失败: {e}"
    else:
        return "❌ 已取消操作"


# ============================================================================
# 收货地址（3 个）— 需要登录
# ============================================================================


@tool
async def get_address_list_api(config: RunnableConfig) -> str:
    """查看当前用户的收货地址列表。需要登录。"""
    token = extract_token_from_config(config)
    if not token:
        return "❌ 查看地址需要先登录"
    try:
        result = await gateway_client.get("/addresses", token=token)
        return format_address_list(result)
    except GatewayError as e:
        return f"❌ 获取地址列表失败: {e}"


@tool
async def add_address_api(config: RunnableConfig) -> str:
    """新增收货地址。需要登录，通过多轮交互收集地址信息。"""
    token = extract_token_from_config(config)
    if not token:
        return "❌ 新增地址需要先登录"

    # L2: interrupt 收集地址信息
    address_input = interrupt({
        "type": "address_input",
        "message": (
            "请提供收货地址信息，按以下格式回复：\n"
            "姓名, 手机号, 省份, 城市, 区, 详细地址\n"
            "例如：张三, 13800138000, 广东省, 深圳市, 南山区, 科技园路1号"
        ),
    })

    # 解析用户输入
    parts = [p.strip() for p in address_input.split(",")]
    if len(parts) < 6:
        return "❌ 地址信息不完整，请提供：姓名, 手机号, 省份, 城市, 区, 详细地址"

    name, phone, province, city, region, detail_address = parts[:6]

    # 校验手机号
    if not re.match(r"^1\d{10}$", phone):
        return "❌ 手机号格式不正确，请输入 11 位手机号"

    address_data = {
        "name": name,
        "phone": phone,
        "province": province,
        "city": city,
        "region": region,
        "detailAddress": detail_address,
        "isDefault": 0,
    }

    try:
        await gateway_client.post("/addresses", token=token, json=address_data)
        return f"✅ 收货地址已添加：{name} {phone} {province}{city}{region}{detail_address}"
    except GatewayError as e:
        return f"❌ 新增地址失败: {e}"


@tool
async def update_address_api(address_id: int, config: RunnableConfig) -> str:
    """修改收货地址。需要登录，通过多轮交互收集修改字段和新值。

    Args:
        address_id: 地址 ID
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 修改地址需要先登录"

    # 先获取当前地址信息
    try:
        current = await gateway_client.get(f"/addresses/{address_id}", token=token)
    except GatewayError as e:
        return f"❌ 获取地址信息失败: {e}"

    if not current:
        return f"未找到地址 {address_id}"

    # L2: interrupt #1 — 请求修改字段
    field_response = interrupt({
        "type": "field_selection",
        "message": (
            f"当前地址：{current.get('name', '')} {current.get('phone', '')} "
            f"{current.get('province', '')}{current.get('city', '')}"
            f"{current.get('region', '')}{current.get('detailAddress', '')}\n"
            f"请问要修改哪个字段？(姓名/手机号/省份/城市/区/详细地址)"
        ),
    })

    field_map = {
        "姓名": "name",
        "手机号": "phone",
        "省份": "province",
        "城市": "city",
        "区": "region",
        "详细地址": "detailAddress",
    }

    field_cn = field_response.strip()
    field_en = field_map.get(field_cn)
    if not field_en:
        return f"❌ 不支持的字段「{field_cn}」，可选：姓名/手机号/省份/城市/区/详细地址"

    # L2: interrupt #2 — 请求新值
    new_value = interrupt({
        "type": "value_input",
        "message": f"请输入新的{field_cn}",
    })

    new_value = new_value.strip()
    if not new_value:
        return "❌ 新值不能为空"

    # 校验手机号
    if field_en == "phone" and not re.match(r"^1\d{10}$", new_value):
        return "❌ 手机号格式不正确，请输入 11 位手机号"

    # 构建更新数据（完整 AddressDTO）
    updated = dict(current)
    updated[field_en] = new_value
    # 移除不需要的字段
    updated.pop("id", None)
    updated.pop("userId", None)

    try:
        await gateway_client.put(f"/addresses/{address_id}", token=token, json=updated)
        return f"✅ 地址 {address_id} 的{field_cn}已修改为「{new_value}」"
    except GatewayError as e:
        return f"❌ 修改地址失败: {e}"


# ============================================================================
# 个性化推荐（2 个）— 需要登录
# ============================================================================


def _accumulate_preference(cat_scores, brand_scores, prices, item, weight):
    """累加单个商品的偏好分数到聚合字典。

    Args:
        cat_scores:  类目得分字典（category -> 累计分）
        brand_scores: 品牌得分字典（brand -> 累计分）
        prices:      价格列表（收集所有购买价格）
        item:        商品数据（需含 category/brand/price/num 字段）
        weight:      权重（购买=5，购物车=3）
    """
    category = item.get("category", "")
    brand = item.get("brand", "")
    price = item.get("price")
    num = item.get("num", 1)

    if category:
        cat_scores[category] = cat_scores.get(category, 0) + weight * num
    if brand:
        brand_scores[brand] = brand_scores.get(brand, 0) + weight * num
    if price:
        prices.append(price)


@tool
async def get_recommendations_api(
    config: RunnableConfig,
    scene: str = "home",
    size: int = 10,
    item_id: int = 0,
) -> str:
    """基于用户浏览/购买历史获取个性化商品推荐。

    Args:
        scene: 推荐场景
            - home: 猜你喜欢（首页推荐，基于用户整体偏好）
            - detail: 看了又看（基于指定商品找相似）
            - cart: 购物车凑单推荐
        size: 返回数量，默认 10
        item_id: 当前商品 ID（scene=detail 时必填）
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 个性化推荐需要先登录，登录后我可以根据您的偏好推荐商品"

    params = {"scene": scene, "size": size}
    if item_id:
        params["itemId"] = item_id

    try:
        result = await gateway_client.get("/recommend", token=token, params=params)
        return format_recommendations(result, scene)
    except GatewayError as e:
        if e.status_code == 401:
            return "❌ 登录已过期，请重新登录后获取推荐"
        # 降级：推荐接口失败时提示用户可手动搜索
        return f"推荐服务暂时不可用，您可以尝试搜索商品。错误: {e}"


@tool
async def analyze_user_preferences(config: RunnableConfig) -> str:
    """分析当前用户的购物偏好（基于购买历史和购物车）。

    返回偏好的类目、品牌、价格区间，供推荐和搜索参考。
    需要登录。
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 偏好分析需要先登录"

    # ====== Phase 2: 画像优先（命中时 0 次 Gateway 调用） ======
    user_id = _extract_user_id(config)
    if user_id:
        try:
            profile = await profile_store.get_profile(user_id)
            if profile and (profile.get("categories") or profile.get("brands")):
                return format_preferences(
                    profile["categories"], profile["brands"], profile["prices"],
                )
        except Exception:
            pass  # 画像读取失败降级到实时计算
    # ====== Phase 2 结束 ======

    try:
        # 并发获取购买历史和购物车
        orders_page, cart_items = await asyncio.gather(
            gateway_client.get(
                "/orders/page", token=token, params={"pageNo": 1, "pageSize": 50}
            ),
            gateway_client.get("/carts", token=token),
        )
    except GatewayError as e:
        return f"❌ 获取用户数据失败: {e}"

    orders = orders_page.get("list", []) or orders_page.get("records", []) if orders_page else []
    cart = cart_items if cart_items else []

    # 收集所有 itemId（OrderDetail 无 category/brand，需额外批量查询）
    item_ids = set()
    for order in orders:
        details = order.get("orderDetails", []) or order.get("details", [])
        for d in details:
            iid = d.get("itemId", d.get("id"))
            if iid:
                item_ids.add(iid)
    for item in cart:
        iid = item.get("itemId", item.get("id"))
        if iid:
            item_ids.add(iid)

    # 批量查询商品详情获取 category/brand
    item_map = {}
    if item_ids:
        try:
            ids_str = ",".join(str(i) for i in item_ids)
            items_detail = await gateway_client.get("/items", params={"ids": ids_str})
            if items_detail:
                for item in items_detail:
                    item_map[item.get("id")] = item
        except GatewayError:
            pass  # 查询失败时降级，仅用已有数据聚合

    # 聚合分析
    category_scores = {}
    brand_scores = {}
    price_points = []

    # 购买历史（权重 5）
    for order in orders:
        details = order.get("orderDetails", []) or order.get("details", [])
        for d in details:
            iid = d.get("itemId", d.get("id"))
            full_item = item_map.get(iid, {})
            merged = {
                "price": d.get("price"),
                "num": d.get("num", 1),
                "category": full_item.get("category", ""),
                "brand": full_item.get("brand", ""),
            }
            _accumulate_preference(category_scores, brand_scores, price_points, merged, weight=5)

    # 购物车（权重 3）
    for item in cart:
        iid = item.get("itemId", item.get("id"))
        full_item = item_map.get(iid, {})
        merged = {
            "price": item.get("price"),
            "num": item.get("num", 1),
            "category": full_item.get("category", ""),
            "brand": full_item.get("brand", ""),
        }
        _accumulate_preference(category_scores, brand_scores, price_points, merged, weight=3)

    # ====== Phase 2: 同步回写画像（backfill_profile 内部有 try/except，不会抛异常） ======
    if user_id and category_scores:
        try:
            await profile_store.backfill_profile(
                user_id, category_scores, brand_scores, price_points,
            )
        except Exception:
            pass  # 回写失败不影响响应，下次 miss 时会再次触发
    # ====== Phase 2 结束 ======

    return format_preferences(category_scores, brand_scores, price_points, orders, cart)


def get_all_tools():
    """返回 CustomerAgent 所需的全部工具列表。"""
    return [
        # 商品浏览
        search_items_api,
        get_item_detail_api,
        get_item_page_api,
        # 秒杀
        get_seckill_activities_api,
        get_seckill_product_api,
        do_seckill_api,
        # 购物车
        get_cart_list_api,
        add_to_cart_api,
        update_cart_quantity_api,
        delete_cart_item_api,
        clear_cart_api,
        # 订单
        get_order_list_api,
        get_order_detail_api,
        cancel_order_api,
        confirm_receive_api,
        # 地址
        get_address_list_api,
        add_address_api,
        update_address_api,
        # 个性化推荐
        get_recommendations_api,
        analyze_user_preferences,
        # 用户记忆（Phase 2 新增）
        save_memory,
        get_memories,
    ]
