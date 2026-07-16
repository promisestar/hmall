# 数据查询技能

## 适用场景
运营人员想要查询商品、订单、秒杀活动、秒杀订单、库存、用户等管理数据时激活此技能。

## 可用工具

### 商品管理
- `admin_get_product_page_api(page_no, page_size, keyword)` — 分页查询商品
- `admin_get_product_detail_api(product_id)` — 商品详情

### 订单管理
- `admin_get_order_page_api(page_no, page_size, status, start_time, end_time)` — 分页查询订单
- `admin_get_order_detail_api(order_id)` — 订单详情

### 秒杀管理
- `admin_get_seckill_promotion_page_api(page_no, page_size, title, status)` — 秒杀活动列表
- `admin_get_seckill_relation_page_api(page_no, page_size, session_id, promotion_id)` — 秒杀商品关联
- `admin_get_seckill_order_page_api(page_no, page_size, status, relation_id, user_id)` — 秒杀订单列表
- `admin_get_seckill_stock_api(relation_id)` — 库存快照

### 用户管理
- `admin_get_user_page_api(page_no, page_size, keyword, status)` — C端用户列表
- `admin_get_user_detail_api(user_id)` — 用户详情

## 订单状态
| 状态码 | 含义 |
|--------|------|
| 1 | 待付款 |
| 2 | 已付款 |
| 3 | 已发货 |
| 4 | 确认收货 |
| 5 | 交易取消 |

## 输出格式（Markdown 表格）
```
## 📦 商品管理（共 248 件）

| # | ID | 商品 | 价格 | 库存 | 状态 |
|---|-----|------|------|------|------|
| 1 | `1001` | iPhone 15 | ¥5999.00 | 45 | 在售 |
| 2 | `1002` | MacBook Air | ¥8999.00 | 12 | 在售 |
```

## 注意事项
- 纯只读操作，不能执行任何写操作
- 管理端 API 使用 R<T> 包装，工具层自动解包 .data
- 所有操作需要管理端登录（admin-token）
- 价格以「元」为单位（后端返回的是「分」）
- 分页参数：page_no 从 1 开始，page_size 默认 10
