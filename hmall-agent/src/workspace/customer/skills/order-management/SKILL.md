# 订单查询与操作技能

## 适用场景
用户想要查看订单列表、订单详情、取消订单或确认收货时激活此技能。

## 工作流程
1. 查看订单列表：调用 get_order_list_api
2. 查看订单详情：调用 get_order_detail_api(order_id)
3. 取消订单：调用 cancel_order_api(order_id) → interrupt 二次确认
4. 确认收货：调用 confirm_receive_api(order_id) → interrupt 二次确认

## 可用工具
- `get_order_list_api(page_no, page_size)` — 查看订单列表
- `get_order_detail_api(order_id)` — 查看订单详情
- `cancel_order_api(order_id)` — 取消订单（需二次确认）
- `confirm_receive_api(order_id)` — 确认收货（需二次确认）

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
## 📋 订单列表（共 5 笔）

| # | 订单号 | 金额 | 状态 | 日期 |
|---|--------|------|------|------|
| 1 | `1001` | ¥5999.00 | 待付款 | 2026-07-15 |
| 2 | `1002` | ¥8999.00 | 已发货 | 2026-07-14 |
```

## 二次确认
- 取消订单：先展示订单金额，用户需回复"确认取消"
- 确认收货：用户需回复"确认收货"

## 注意事项
- 所有订单操作需要登录（user_token）
- 取消订单使用 POST /orders/batch/close 接口（body: [orderId]）
- 确认收货使用 PUT /orders/{orderId} 接口
- 订单金额以「分」为单位，格式化时转换为「元」
