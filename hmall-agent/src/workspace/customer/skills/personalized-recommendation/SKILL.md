# 个性化推荐技能

## 适用场景
用户想要商品推荐、想看猜你喜欢、浏览商品后想看相关推荐、
购物车凑单，或表达模糊购物意图（"帮我选""随便看看"）时激活。

## 工作流程

### 场景 1：首页推荐 / 猜你喜欢
用户说："有什么推荐" / "猜我喜欢什么" / "帮我选个商品"
1. 调用 get_recommendations_api(scene="home", size=10)
2. 结合返回的 basedOn 信息，生成推荐理由
3. 主动询问用户对哪些推荐感兴趣

### 场景 2：看了又看（商品详情后推荐）
用户查看某商品后，或说："还有类似的吗" / "看了又看"
1. 从对话上下文提取当前 item_id
2. 调用 get_recommendations_api(scene="detail", item_id=xxx, size=5)
3. 说明"与您刚看的 X 相似"并推荐

### 场景 3：购物车凑单
用户说："购物车还能加点什么" / "凑单推荐"
1. 调用 get_recommendations_api(scene="cart", size=5)
2. 说明推荐商品与购物车商品的搭配关系

### 场景 4：偏好驱动推荐（Agent 自主推理）
用户说："我想换个手机" / "推荐点苹果生态的产品"
1. 调用 analyze_user_preferences() 获取用户偏好
2. 根据偏好用 search_items_api 搜索匹配商品
3. 结合偏好解释推荐理由

## 可用工具
- `get_recommendations_api(scene, size, item_id)` — 后端推荐召回
- `analyze_user_preferences()` — 用户偏好分析
- `search_items_api(keyword)` — 偏好驱动搜索（推荐不足时补充）
- `get_item_detail_api(item_id)` — 用户对推荐商品感兴趣时查看详情
- `add_to_cart_api(item_id)` — 推荐后加购

## 推荐理由生成规则
- 结合 analyze_user_preferences 返回的偏好类目/品牌
- 结合 get_recommendations_api 返回的 recommendTags 和 basedOn
- 生成自然语言理由，如："您之前购买过 iPhone 14，可能对这款 iPhone 15 感兴趣"
- 如无偏好数据，说明是热销推荐："这款是近期热销商品，评价不错"

## 输出格式（Markdown 语法，前端 marked 渲染）
```
## 🎯 猜你喜欢（共 10 件）

| # | 商品 | 价格 | 库存 | 标签 | ID |
|---|------|------|------|------|-----|
| 1 | iPhone 15 Pro | ¥7999.00 | 45 件 | 同类目热销, 您常买的品牌 | `2001` |
| 2 | AirPods Pro | ¥1899.00 | 120 件 | 搭配推荐 | `2002` |

> **推荐依据**: 偏好类目: 手机, 耳机 | 偏好品牌: Apple, Sony
```

## 注意事项
- 推荐需要登录，未登录时提示用户先登录
- 推荐接口失败时降级为 search_items_api 搜索热销
- 新用户无购买历史时，后端返回热销榜，不强行解释推荐理由
- 推荐后主动询问用户反馈，形成推荐→反馈→再推荐的闭环
