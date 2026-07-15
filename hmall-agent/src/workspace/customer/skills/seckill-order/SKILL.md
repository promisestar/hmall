# 秒杀下单流程技能

## 适用场景
用户想要查看秒杀活动、秒杀商品详情或进行秒杀下单时激活此技能。

## 工作流程
1. 调用 get_seckill_activities_api 获取当前秒杀活动列表
2. 用户指定某秒杀商品后，调用 get_seckill_product_api 获取详情
3. 用户确认秒杀意图后，调用 do_seckill_api（触发 interrupt 二次确认）
4. 用户回复"确认"后，执行秒杀下单
5. 返回秒杀结果（成功/排队中/失败）

## 可用工具
- `get_seckill_activities_api()` — 获取秒杀活动列表（含场次和商品）
- `get_seckill_product_api(relation_id)` — 查看秒杀商品详情
- `do_seckill_api(relation_id)` — 秒杀下单（需登录 + 二次确认）

## 输出格式
```
⚡ 当前秒杀活动
─────────────────────
📢 618专场 [进行中]
🕐 10:00场 (10:00-12:00) [抢购中]
   ├── iPhone 15 | ¥5999.00(原价¥6999.00) | 剩余 45 件 [ID:1]
   └── MacBook Air | ¥8999.00(原价¥9999.00) | 剩余 12 件 [ID:2]
```

## 二次确认流程
```
用户: "秒杀商品1"
  → Agent 调用 do_seckill_api(relation_id=1)
  → 工具内查询商品详情 → interrupt 确认
  → 前端展示确认卡片
  → 用户回复"确认"
  → 执行 POST /seckill/order/1?quantity=1
  → 返回秒杀结果
```

## 注意事项
- 秒杀下单需要登录（user_token）
- 秒杀接口有限流（每用户 5 秒 1 次），429 时提示稍后重试
- 秒杀结果可能是 success/pending/failed
- 秒杀价和原价以「分」为单位，格式化时转换为「元」
