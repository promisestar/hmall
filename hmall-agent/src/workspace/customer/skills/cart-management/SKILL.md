# 购物车管理技能

## 适用场景
用户想要查看购物车、加入商品、修改数量、删除商品或清空购物车时激活此技能。

## 工作流程
1. 查看购物车：直接调用 get_cart_list_api
2. 加入购物车：调用 add_to_cart_api(item_id)
3. 修改数量：调用 update_cart_quantity_api(item_id, num)
4. 删除商品：调用 delete_cart_item_api(item_id) → interrupt 二次确认
5. 清空购物车：调用 clear_cart_api → interrupt 二次确认

## 可用工具
- `get_cart_list_api()` — 查看购物车列表
- `add_to_cart_api(item_id)` — 加入购物车
- `update_cart_quantity_api(item_id, num)` — 修改数量（num >= 1）
- `delete_cart_item_api(item_id)` — 删除商品（需二次确认）
- `clear_cart_api()` — 清空购物车（需二次确认）

## 输出格式（Markdown 表格 + 引用块汇总）
```
## 🛒 我的购物车

| # | 商品 | 单价 | 数量 | 小计 | ID |
|---|------|------|------|------|-----|
| 1 | iPhone 15 | ¥5999.00 | 2 | ¥11998.00 | `1001` |
| 2 | MacBook Air | ¥8999.00 | 1 | ¥8999.00 | `1002` |

> **总计: ¥20997.00**
```

## 二次确认
- 删除商品：用户需回复"确认删除"
- 清空购物车：用户需回复"确认删除"

## 注意事项
- 所有购物车操作需要登录（user_token）
- 修改数量时 num 必须 >= 1
- 清空购物车时先获取购物车列表，再用批量删除接口
