# 购物引导技能

## 适用场景
用户想要浏览、搜索、查看商品详情时激活此技能。

## 工作流程
1. 确认用户的搜索意图（关键词/分类/价格区间）
2. 调用 search_items_api 或 get_item_page_api 获取商品列表
3. 如用户追问某商品，调用 get_item_detail_api 获取详情
4. 格式化输出：商品名、价格、库存、商品ID

## 可用工具
- `search_items_api(keyword, page_no, page_size)` — 关键词搜索商品
- `get_item_detail_api(item_id)` — 查看商品详情
- `get_item_page_api(page_no, page_size)` — 分页浏览商品

## 输出格式
```
📦 搜索结果（共 N 件）
─────────────────────
1. iPhone 15 | ¥5999.00 | 库存 45 件 [ID:1001]
2. MacBook Air | ¥8999.00 | 库存 12 件 [ID:1002]
```

## 注意事项
- 商品搜索和浏览不需要登录
- 价格以「元」为单位（后端返回的是「分」）
- 商品ID用 [ID:xxx] 标注，方便用户引用
