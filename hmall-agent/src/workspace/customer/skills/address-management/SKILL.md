# 地址管理技能（含状态机流程）

## 适用场景
用户想要查看收货地址、新增地址或修改地址时激活此技能。

## 工作流程
1. 查看地址列表：直接调用 get_address_list_api
2. 新增地址：调用 add_address_api → interrupt 收集地址信息
3. 修改地址：调用 update_address_api(address_id) → interrupt 收集字段 → interrupt 收集新值

## 可用工具
- `get_address_list_api()` — 查看地址列表
- `add_address_api()` — 新增地址（多轮收集）
- `update_address_api(address_id)` — 修改地址（多轮收集）

## 地址字段
| 字段 | 说明 |
|------|------|
| name | 姓名 |
| phone | 手机号（11位） |
| province | 省份 |
| city | 城市 |
| region | 区 |
| detailAddress | 详细地址 |
| isDefault | 是否默认（0/1） |

## 输出格式
```
📍 收货地址列表
─────────────────────
1. 张三 13800138000 [默认]
   广东省深圳市南山区科技园路1号 [ID:1]
2. 李四 13900139000
   北京市北京市朝阳区建国路88号 [ID:2]
```

## 新增地址状态机
```
用户: "新增地址"
  → Agent 调用 add_address_api()
  → interrupt: "请提供收货地址信息：姓名, 手机号, 省份, 城市, 区, 详细地址"
  → 用户回复: "张三, 13800138000, 广东省, 深圳市, 南山区, 科技园路1号"
  → 解析并 POST /addresses
  → ✅ 收货地址已添加
```

## 修改地址状态机
```
用户: "修改地址1"
  → Agent 调用 update_address_api(address_id=1)
  → 获取当前地址信息
  → interrupt #1: "请问要修改哪个字段？(姓名/手机号/省份/城市/区/详细地址)"
  → 用户回复: "姓名"
  → interrupt #2: "请输入新的姓名"
  → 用户回复: "王五"
  → 合并字段并 PUT /addresses/1
  → ✅ 地址1的姓名已修改为「王五」
```

## 注意事项
- 所有地址操作需要登录（user_token）
- 手机号校验：11位数字，以1开头
- 修改地址时需要先获取当前地址，合并修改字段后发送完整 AddressDTO
- 地址修改是两轮 interrupt（字段选择 + 新值输入）
- 新增地址是一轮 interrupt（一次性收集所有字段）
