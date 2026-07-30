# Agent 个性化推荐能力实现说明文档

> 版本：v1.0
> 日期：2026-07-17
> 设计文档：`docs/Agent功能相关文档/agent-personalized-recommendation-design.md`
>
> 本文对应设计文档 Phase 1（步骤 1-6）：Agent 侧 2 个新工具 + 2 个 Formatter + 1 个 Skill + Prompt/正则/注册增强；后端 `GET /recommend` 接口严格遵循设计文档"SQL 聚合用户已购类目/品牌 → ES 按类目品牌召回 → 按销量排序"三步管线。

---

## 一、实现概况

本次实现为 CustomerAgent 扩展了对话式个性化推荐能力，覆盖 4 个后端模块（hm-api、search-service、trade-service、item-service）和 Agent 全链路（工具、Formatter、Skill、Prompt、正则、注册）。推荐管线遵循设计文档的三步流程：聚合用户已购类目/品牌偏好 → ES 按类目召回 → 销量排序。其中偏好聚合通过 Feign 调用 trade-service 获取已购商品（微服务数据库隔离），ES 召回通过 Feign 调用 search-service（search 已从 item 拆分为独立微服务）。

### 1.1 文件变更统计

| 类别 | 新增 | 修改 | 说明 |
|------|------|------|------|
| hm-api（Feign 基础设施） | 2 | 2 | SearchClient + FallbackFactory + DefaultFeignConfig 注册 + TradeClient 新增方法 |
| search-service（ES 召回） | 0 | 3 | Controller 端点 + Service 接口 + ServiceImpl ES 查询 |
| trade-service（已购商品） | 0 | 3 | OrderController 端点 + IOrderService 接口 + OrderServiceImpl 实现 |
| item-service（推荐接口） | 5 | 2 | Controller/Service/DTO/VO + pom.xml + Application |
| Agent 工具层 | 0 | 1 | tools.py 新增 2 工具 + 辅助函数 + 注册 |
| Agent Formatter | 0 | 1 | formatters.py 新增 2 个格式化函数 |
| Agent Skill | 1 | 0 | personalized-recommendation/SKILL.md |
| Agent Prompt/正则/注册 | 0 | 3 | prompts.py + regex_rules.py + agent.py |
| Gateway 路由 | 0 | 0 | Nacos 动态配置（手动） |
| **合计** | **8** | **15** | — |

### 1.2 Phase 1 范围对照

| 设计文档步骤 | 内容 | 本次实现状态 |
|-------------|------|-------------|
| 步骤 1 | Agent 新增 `get_recommendations_api` 工具 | ✅ 已实现 |
| 步骤 2 | Agent 新增 `analyze_user_preferences` 工具 | ✅ 已实现 |
| 步骤 3 | Agent 新增 `format_recommendations` + `format_preferences` | ✅ 已实现 |
| 步骤 4 | Agent 新增 `personalized-recommendation` Skill | ✅ 已实现 |
| 步骤 5 | Agent 修改 Prompt + 正则路由 + 工具注册 | ✅ 已实现 |
| 步骤 6 | 后端 `GET /recommend` 接口（SQL + ES） | ✅ 已实现 |
| 步骤 7-8 | 后端 `POST /behaviors` + 前端埋点 | ⏸ Phase 2 |
| 步骤 9-10 | Redis 画像 + Item-CF | ⏸ Phase 2 |

---

## 二、架构总览

### 2.1 系统架构

```
用户（C端对话）
  │  "有什么推荐" / "帮我选个手机" / 查看商品后
  ▼
┌──────────────────────────────────────────────────────────────────┐
│              Agent Service (LangGraph Server :8090)              │
│                                                                  │
│  中间件层                                                         │
│  ├── RegexShortcutMiddleware（L1: "推荐/猜你喜欢" 快捷路由 <5ms）  │
│  └── SkillsMiddleware（加载 personalized-recommendation）         │
│                          │                                       │
│  CustomerAgent（扩展后 20 个工具）                                │
│  ├── get_recommendations_api(scene, size, item_id)              │
│  │     → 调用后端 GET /recommend，返回商品列表                    │
│  └── analyze_user_preferences()                                  │
│        → 并发获取订单+购物车，Agent 侧聚合偏好画像                  │
│                          │                                       │
│  LLM 推理层（qwen-turbo）                                        │
│  ├── 理解推荐意图、选择策略、生成推荐理由                          │
│  └── "看了又看"场景从对话上下文提取 item_id                       │
└──────────────────────────┬───────────────────────────────────────┘
                           │ httpx (异步 HTTP, 携带 JWT)
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│              hm-gateway (:8080)                                  │
│  ├── AuthGlobalFilter（JWT → userId，写入 user-info header）      │
│  └── 路由：/recommend/** → item-service（需认证）                 │
└──┬───────────────────────────────────────────────────────────────┘
   │
   ▼
┌──────────────────────────────────────────────────────────────────┐
│  item-service (:8081) — RecommendController GET /recommend       │
│                                                                  │
│  RecommendServiceImpl 三步管线：                                  │
│  ① TradeClient（Feign）→ trade-service 获取已购商品              │
│     → 查 item 表补充 category/brand                              │
│     → 按购买数量加权聚合偏好类目 Top3 + 品牌 Top3 + 已购 ID       │
│                                                                  │
│  ② SearchClient（Feign）→ search-service ES 召回                  │
│     BoolQuery: termsQuery("category") + mustNot("_id")           │
│     sort: sold DESC                                              │
│                                                                  │
│  ③ MySQL 补充 stock/status + 过滤已下架 + 生成推荐标签            │
│                                                                  │
│  降级：ES 无结果 → MySQL 热销兜底（ORDER BY sold DESC）           │
└──────┬───────────────────────────┬───────────────────────────────┘
       │ Feign                     │ Feign（loadbalancer，不走 Gateway）
       ▼                           ▼
┌──────────────────────────┐ ┌──────────────────────────────────────┐
│ trade-service (:8085)    │ │ search-service (:8089)               │
│ OrderController          │ │ SearchController                     │
│ GET /orders/purchased-   │ │ GET /search/recommend                │
│   items                  │ │ SearchServiceImpl.recommendSearch()  │
│ → 已购 itemId + num 列表 │ │ → RestHighLevelClient 查 ES "items"  │
│   (按 itemId 聚合数量)    │ │ → 返回 List<ItemDTO>                 │
└──────────────────────────┘ └──────────────────────────────────────┘
```

### 2.2 推荐数据流

```
Agent 调用 GET /recommend?scene=home&size=10
  │
  ▼
Gateway: JWT → userId → user-info header → item-service
  │
  ▼
RecommendController: UserContext.getUser() 提取 userId
  │
  ▼
RecommendServiceImpl.recommend(userId, scene, size, itemId)
  │
  ├─ scene=detail && itemId≠null
  │   → itemService.getById(itemId) 获取种子商品类目
  │   → excludeIds = [itemId]
  │
  ├─ scene=home/cart && userId≠null
  │   → tradeClient.queryPurchasedItems()  ← Feign → trade-service
  │     → trade-service 查 order + order_detail 聚合已购 itemId+num
  │   → itemService.listByIds(itemIds) ← 查 item 表补充 category/brand
  │   → 按购买数量加权聚合类目偏好 Top3 + 品牌偏好 Top3
  │   → excludeIds = 已购 itemId 列表
  │
  ▼
searchClient.recommend(categories, excludeIds, size)  ← Feign → search-service
  │
  ├─ search-service: ES BoolQuery termsQuery("category") + mustNot("_id")
  │                  + sort sold DESC → List<ItemDTO>
  │
  ├─ Feign 降级（search-service 不可用）→ 返回空列表
  │
  ▼
ES 结果为空？ → MySQL 热销兜底（item 表 ORDER BY sold DESC）
  │
  ▼
itemService.listByIds(resultIds) → 批量补充 stock/status
  │
  ▼
过滤 status≠1（已下架）→ 生成 recommendTags → 组装 RecommendVO
  │
  ▼
返回 JSON → Agent format_recommendations → LLM 生成推荐理由 → 用户
```

### 2.3 与现有三级路由的关系

推荐能力接入现有三级路由体系，不改变路由架构：

```
用户消息
  │
  ├─ L1: RegexShortcutMiddleware（<5ms）
  │   ├── "推荐" / "猜你喜欢" / "帮我选" / "随便看看"
  │   │   → get_recommendations_api(scene=home)  ← 新增正则
  │   ├── "购物车推荐" / "凑单推荐"
  │   │   → get_recommendations_api(scene=cart)  ← 新增正则
  │   └── 其他正则规则不变
  │
  ├─ L2: interrupt
  │   └── 推荐不涉及 interrupt（只读操作）
  │
  └─ L3: LLM 兜底（~2s）
      ├── "帮我选个手机" → LLM 先调 analyze_user_preferences
      │                    → 再调 search_items_api / get_recommendations_api
      │                    → 结合偏好生成推荐
      └── "看了又看" → LLM 从对话上下文提取 item_id
                       → 调 get_recommendations_api(scene=detail, item_id=xxx)
```

---

## 三、后端实现详情

### 3.1 hm-api 模块（Feign 基础设施）

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/main/java/com/hmall/api/client/SearchClient.java` | 新增 | `@FeignClient("search-service")`，调用 `GET /search/recommend` |
| `src/main/java/com/hmall/api/client/fallback/SearchClientFallbackFactory.java` | 新增 | 降级工厂，search-service 不可用时返回空列表 |
| `src/main/java/com/hmall/api/config/DefaultFeignConfig.java` | 修改 | 注册 `SearchClientFallbackFactory` Bean |

**SearchClient 接口**：

```java
@FeignClient(value = "search-service", fallbackFactory = SearchClientFallbackFactory.class)
public interface SearchClient {

    @GetMapping("/search/recommend")
    List<ItemDTO> recommend(
            @RequestParam(value = "categories", required = false) List<String> categories,
            @RequestParam(value = "excludeIds", required = false) List<Long> excludeIds,
            @RequestParam("size") Integer size
    );
}
```

**降级工厂**：

```java
public class SearchClientFallbackFactory implements FallbackFactory<SearchClient> {
    @Override
    public SearchClient create(Throwable cause) {
        return new SearchClient() {
            @Override
            public List<ItemDTO> recommend(List<String> categories, List<Long> excludeIds, Integer size) {
                log.error("ES 推荐召回失败，降级返回空列表", cause);
                return CollUtils.emptyList();
            }
        };
    }
}
```

**设计要点**：

| 决策 | 理由 |
|------|------|
| Feign 而非直接查 ES | search-service 已从 item 拆分为独立微服务，item-service 中的 ES 依赖（pom.xml + ItemDoc.java）是拆分前残留 |
| `DefaultFeignConfig` 透传 `user-info` | Feign 调用走服务间直连（loadbalancer），不经过 Gateway，`RequestInterceptor` 自动透传 userId |
| 降级返回空列表而非抛异常 | 让 `RecommendServiceImpl` 能感知失败并降级为 MySQL 热销兜底，而非直接报错 |
| 复用 hm-api 的 `ItemDTO` | 与 ItemClient/TradeClient 共享同一 DTO，避免类型转换 |

### 3.2 search-service（ES 召回端点）

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/main/java/com/hmall/search/controller/SearchController.java` | 修改 | 新增 `GET /search/recommend` 端点 |
| `src/main/java/com/hmall/search/service/ISearchService.java` | 修改 | 新增 `recommendSearch` 方法签名 |
| `src/main/java/com/hmall/search/service/impl/SearchServiceImpl.java` | 修改 | 实现 ES BoolQuery 推荐召回 |

**Controller 端点**：

```java
@ApiOperation("推荐商品召回")
@GetMapping("/search/recommend")  // 注：实际为 @GetMapping("/recommend") 挂在 /search 前缀下
public List<ItemDTO> recommend(
        @RequestParam(value = "categories", required = false) List<String> categories,
        @RequestParam(value = "excludeIds", required = false) List<Long> excludeIds,
        @RequestParam("size") Integer size
) throws IOException {
    return searchService.recommendSearch(categories, excludeIds, size);
}
```

**ES 查询实现**：

```java
@Override
public List<ItemDTO> recommendSearch(List<String> categories, List<Long> excludeIds, Integer size) throws IOException {
    SearchRequest searchRequest = new SearchRequest("items");
    SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
    BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();

    // 按偏好类目过滤（多类目 terms 查询）
    if (categories != null && !categories.isEmpty()) {
        boolQueryBuilder.filter(QueryBuilders.termsQuery("category", categories));
    }

    // 排除已购商品
    if (excludeIds != null && !excludeIds.isEmpty()) {
        boolQueryBuilder.mustNot(QueryBuilders.termsQuery("_id", excludeIds));
    }

    searchSourceBuilder.query(boolQueryBuilder);
    searchSourceBuilder.size(size);
    searchSourceBuilder.sort(new FieldSortBuilder("sold").order(SortOrder.DESC));
    searchSourceBuilder.timeout(new TimeValue(10, TimeUnit.SECONDS));
    searchRequest.source(searchSourceBuilder);

    // 执行查询 + ItemDoc → ItemDTO 转换
    ...
}
```

**ES 查询策略**：

| 场景 | BoolQuery 构造 | 说明 |
|------|---------------|------|
| 有偏好类目 | `filter termsQuery("category", [类目列表])` | 多类目 OR 匹配 |
| 排除已购 | `mustNot termsQuery("_id", [已购ID列表])` | 避免重复推荐 |
| 无偏好（冷启动） | 空 BoolQuery（matchAll） | 返回全局热销 |
| 排序 | `FieldSortBuilder("sold").order(DESC)` | 按销量倒序 |
| 超时 | 10 秒 | 防止 ES 慢查询阻塞推荐 |

### 3.3 trade-service（已购商品聚合端点）

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/main/java/com/hmall/trade/controller/OrderController.java` | 修改 | 新增 `GET /orders/purchased-items` 端点 |
| `src/main/java/com/hmall/trade/service/IOrderService.java` | 修改 | 新增 `queryPurchasedItems()` 方法签名 |
| `src/main/java/com/hmall/trade/service/impl/OrderServiceImpl.java` | 修改 | 实现已购商品按 itemId 聚合 num |

**hm-api TradeClient 新增方法**：

```java
@FeignClient(value = "trade-service")
public interface TradeClient {
    @PutMapping("/orders/{orderId}")
    void markOrderPaySuccess(@PathVariable("orderId") Long orderId);

    /**
     * 获取当前用户已购商品 ID 及数量（有效订单：已付款/已发货/已收货/已评价）
     * userId 通过 DefaultFeignConfig 透传的 user-info header 获取。
     */
    @GetMapping("/orders/purchased-items")
    List<OrderDetailDTO> queryPurchasedItems();
}
```

**OrderServiceImpl 实现**：

```java
@Override
public List<OrderDetailDTO> queryPurchasedItems() {
    Long userId = UserContext.getUser();
    if (userId == null) return CollUtils.emptyList();

    // 1. 查询当前用户有效订单（status IN 2,3,4,6）
    List<Order> orders = lambdaQuery()
            .eq(Order::getUserId, userId)
            .in(Order::getStatus, 2, 3, 4, 6)
            .list();
    if (CollUtils.isEmpty(orders)) return CollUtils.emptyList();

    // 2. 查询这些订单的商品详情
    List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
    List<OrderDetail> details = detailService.lambdaQuery()
            .in(OrderDetail::getOrderId, orderIds)
            .list();
    if (CollUtils.isEmpty(details)) return CollUtils.emptyList();

    // 3. 按 itemId 聚合购买数量
    Map<Long, Integer> itemNumMap = new HashMap<>();
    for (OrderDetail detail : details) {
        itemNumMap.merge(detail.getItemId(), detail.getNum(), Integer::sum);
    }

    // 4. 转换为 DTO 列表（复用 hm-api 的 OrderDetailDTO：itemId + num）
    return itemNumMap.entrySet().stream()
            .map(e -> new OrderDetailDTO().setItemId(e.getKey()).setNum(e.getValue()))
            .collect(Collectors.toList());
}
```

**设计要点**：

| 决策 | 理由 |
|------|------|
| Feign 而非跨表 SQL | 微服务数据库隔离，item-service 无法访问 trade-service 的 `order` / `order_detail` 表 |
| 返回 itemId+num 而非完整订单 | 轻量级接口，仅返回推荐所需的最小数据（商品 ID + 购买数量），避免传输完整 OrderVO |
| 按 itemId 聚合 num | 同一商品可能在不同订单中购买多次，聚合后反映总购买量 |
| 复用 hm-api 的 `OrderDetailDTO` | 已有 DTO（itemId + num），无需新建 |
| `OrderDetail` 无 category/brand | trade-service 只返回 itemId+num，category/brand 由 item-service 查 item 表补充 |

### 3.4 item-service（推荐接口 + Feign 启用）

| 文件 | 操作 | 说明 |
|------|------|------|
| `pom.xml` | 修改 | 新增 hm-api + openfeign + loadbalancer + feign-okhttp 依赖 |
| `src/main/java/com/hmall/item/ItemApplication.java` | 修改 | 新增 `@EnableFeignClients` |
| `src/main/java/com/hmall/item/controller/RecommendController.java` | 新增 | `GET /recommend` 接口 |
| `src/main/java/com/hmall/item/service/IRecommendService.java` | 新增 | 推荐服务接口 |
| `src/main/java/com/hmall/item/service/impl/RecommendServiceImpl.java` | 新增 | 三步管线核心实现（Feign 聚合偏好 + ES 召回 + MySQL 补充） |
| `src/main/java/com/hmall/item/domain/dto/RecommendItemDTO.java` | 新增 | 推荐商品 DTO（含 recommendTags） |
| `src/main/java/com/hmall/item/domain/dto/RecommendVO.java` | 新增 | 响应 VO（含 basedOn 内部类） |

#### 3.3.1 pom.xml 依赖新增

```xml
<!--openFeign-->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
<!--负载均衡器-->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
<!--OK http 的依赖 -->
<dependency>
    <groupId>io.github.openfeign</groupId>
    <artifactId>feign-okhttp</artifactId>
</dependency>
<!--hm-api 共享 Feign 客户端-->
<dependency>
    <groupId>com.heima</groupId>
    <artifactId>hm-api</artifactId>
    <version>1.0.0</version>
</dependency>
```

#### 3.3.2 Application 启用 Feign

```java
@MapperScan("com.hmall.item.mapper")
@SpringBootApplication
@EnableScheduling
@EnableFeignClients(basePackages = "com.hmall.api.client", defaultConfiguration = DefaultFeignConfig.class)
public class ItemApplication {
```

#### 3.4.3 RecommendController

```java
@Api(tags = "个性化推荐接口")
@RestController
@RequestMapping("/recommend")
@RequiredArgsConstructor
public class RecommendController {

    private final IRecommendService recommendService;

    @ApiOperation("获取个性化推荐商品")
    @GetMapping
    public RecommendVO recommend(
            @RequestParam(defaultValue = "home") String scene,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long itemId
    ) {
        Long userId = UserContext.getUser();
        return recommendService.recommend(userId, scene, size, itemId);
    }
}
```

#### 3.4.4 RecommendServiceImpl 三步管线

核心实现遵循"Feign 聚合偏好 → ES 召回 → 销量排序"三步流程。偏好聚合通过 Feign 调用 trade-service 获取已购商品，再查 item 表补充 category/brand 后按购买数量加权聚合：

```java
@Override
public RecommendVO recommend(Long userId, String scene, Integer size, Long itemId) {
    // 1. 确定召回参数
    if ("detail".equals(scene) && itemId != null) {
        // scene=detail: 获取种子商品的类目
        Item seedItem = itemService.getById(itemId);
        if (seedItem != null) categories.add(seedItem.getCategory());
        excludeIds.add(itemId);
    } else if (userId != null) {
        // scene=home/cart: Feign 调用 trade-service 获取已购商品
        List<OrderDetailDTO> purchasedItems = safeQueryPurchasedItems();
        if (purchasedItems != null && !purchasedItems.isEmpty()) {
            excludeIds = purchasedItems.stream().map(OrderDetailDTO::getItemId).collect(...);
            // 查 item 表补充 category/brand
            List<Item> purchasedItemList = itemService.listByIds(itemIds);
            // 按购买数量加权聚合类目/品牌偏好 Top3
            Map<String, Integer> catScores = new HashMap<>();
            for (Item item : purchasedItemList) {
                Integer num = numMap.getOrDefault(item.getId(), 1);
                catScores.merge(item.getCategory(), num, Integer::sum);
            }
            categories = topN(catScores, 3);
            topBrands = topN(brandScores, 3);
        }
    }

    // 2. Feign 调用 search-service ES 召回
    List<ItemDTO> searchResults = searchClient.recommend(categories, excludeIds, size);

    // 3. ES 无结果时 MySQL 热销兜底
    if (searchResults.isEmpty()) {
        searchResults = mysqlHotFallback(excludeIds, size);
        isFallback = true;
    }

    // 4. MySQL 批量补充 stock/status
    Map<Long, Item> dbItemMap = itemService.listByIds(resultIds)...;

    // 5. 组装推荐商品，过滤已下架 + 生成推荐标签
    for (ItemDTO searchItem : searchResults) {
        if (status != 1) continue;  // 过滤非在售
        ri.setRecommendTags(generateTags(...));
        recommendItems.add(ri);
    }

    // 6. 组装响应（冷启动兜底时不设 basedOn）
    return vo;
}
```

**Feign 调用容错**：

```java
private List<OrderDetailDTO> safeQueryPurchasedItems() {
    try {
        return tradeClient.queryPurchasedItems();
    } catch (Exception e) {
        log.error("Feign 调用 trade-service 获取已购商品失败，降级为空列表", e);
        return new ArrayList<>();
    }
}
```

**偏好聚合辅助方法**：

```java
private List<String> topN(Map<String, Integer> scores, int n) {
    return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(n)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
}
```

**MySQL 热销兜底**：

```java
private List<ItemDTO> mysqlHotFallback(List<Long> excludeIds, int size) {
    log.warn("ES 召回无结果，降级为 MySQL 热销兜底");
    List<Item> items = itemService.lambdaQuery()
            .eq(Item::getStatus, 1)
            .notIn(!excludeIds.isEmpty(), Item::getId, excludeIds)
            .orderByDesc(Item::getSold)
            .last("LIMIT " + size)
            .list();
    return BeanUtils.copyList(items, ItemDTO.class);
}
```

**推荐标签生成规则**：

| 场景 | 标签 |
|------|------|
| 热销兜底（isFallback=true） | `["热销推荐"]` |
| scene=detail | `["相似推荐"]` + 品牌匹配时追加 `"您常买的品牌"` |
| scene=home/cart | 类目匹配时 `"同类目热销"` + 品牌匹配时 `"您常买的品牌"` |
| 无匹配标签 | `["热销推荐"]`（兜底） |

#### 3.4.5 DTO/VO 结构

**RecommendItemDTO**（推荐商品）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 商品 ID |
| `name` | String | 商品名称 |
| `price` | Integer | 价格（分） |
| `stock` | Integer | 库存（MySQL 补充） |
| `brand` | String | 品牌 |
| `category` | String | 类目 |
| `sold` | Integer | 销量 |
| `recommendTags` | List\<String\> | 推荐标签（后端生成，LLM 参考） |

**RecommendVO**（响应）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `list` | List\<RecommendItemDTO\> | 推荐商品列表 |
| `total` | Integer | 总数 |
| `basedOn` | BasedOn | 推荐依据（冷启动兜底时为 null） |

`BasedOn` 内部类：`topCategories` + `topBrands`（List\<String\>）

---

## 四、Agent 侧实现详情

### 4.1 工具层（2 个新工具）

文件：`hmall-agent/src/agents/customer/tools.py`

工具总数 18 → 20。新增工具均需登录（通过 `extract_token_from_config` 检查 token）。

#### 4.1.1 get_recommendations_api

```python
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
        return f"推荐服务暂时不可用，您可以尝试搜索商品。错误: {e}"
```

#### 4.1.2 analyze_user_preferences

```python
@tool
async def analyze_user_preferences(config: RunnableConfig) -> str:
    """分析当前用户的购物偏好（基于购买历史和购物车）。

    返回偏好的类目、品牌、价格区间，供推荐和搜索参考。
    需要登录。
    """
    token = extract_token_from_config(config)
    if not token:
        return "❌ 偏好分析需要先登录"

    # 并发获取购买历史和购物车
    orders_page, cart_items = await asyncio.gather(
        gateway_client.get("/orders/page", token=token, params={"pageNo": 1, "pageSize": 50}),
        gateway_client.get("/carts", token=token),
    )
    ...
```

**偏好聚合辅助函数**：

```python
def _accumulate_preference(cat_scores, brand_scores, prices, item, weight):
    """累加单个商品的偏好分数到聚合字典。

    Args:
        weight: 权重（购买=5，购物车=3）
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
```

**设计要点**：

| 决策 | 理由 |
|------|------|
| `scene` 参数化 | 让 LLM 根据对话上下文判断场景，而非后端猜测 |
| `asyncio.gather` 并发 | 订单 + 购物车并发获取，减少等待 |
| 购买权重 5，购物车权重 3 | 购买是更强信号，购物车是意向信号 |
| 批量查询商品详情 | `OrderDetail` 无 category/brand 字段，需额外调 `/items?ids=` 补充 |
| 不依赖后端画像服务 | 直接用现有 `/orders/page` + `/carts` 接口，零后端改动即可启用 |
| 降级返回提示 | 推荐非核心链路，失败时引导用户搜索，不阻断对话 |

#### 4.1.3 工具注册更新

```python
def get_all_tools():
    return [
        # 商品浏览（3）
        ...
        # 秒杀（3）
        ...
        # 购物车（5）
        ...
        # 订单（4）
        ...
        # 地址（3）
        ...
        # 个性化推荐（新增 2）
        get_recommendations_api,
        analyze_user_preferences,
    ]
```

### 4.2 Formatter 层

文件：`hmall-agent/src/tools/formatters.py`

新增 2 个格式化函数，复用现有 `_yuan()`（分转元）、`_table_row()`、`_table_sep()` 辅助函数。

#### 4.2.1 format_recommendations

将后端 `/recommend` 响应格式化为 Markdown 表格 + 推荐依据引用块：

```python
def format_recommendations(page_dto: dict, scene: str = "home") -> str:
    ...
    lines = [f"## 🎯 {title}（共 {total} 件）", ""]
    lines.append(_table_row("#", "商品", "价格", "库存", "标签", "ID"))
    lines.append(_table_sep(6))
    for i, item in enumerate(items, 1):
        ...
        lines.append(_table_row(i, name, f"¥{price}", f"{stock} 件", tag_str, f"`{item_id}`"))

    # 推荐依据摘要
    if based_on:
        ...
        lines.append(f"> **推荐依据**: {' | '.join(parts)}")

    return "\n".join(lines)
```

**输出示例**：

```markdown
## 🎯 猜你喜欢（共 10 件）

| # | 商品 | 价格 | 库存 | 标签 | ID |
|---|------|------|------|------|-----|
| 1 | iPhone 15 Pro | ¥7999.00 | 45 件 | 同类目热销, 您常买的品牌 | `2001` |
| 2 | AirPods Pro | ¥1899.00 | 120 件 | 搭配推荐 | `2002` |

> **推荐依据**: 偏好类目: 手机, 耳机 | 偏好品牌: Apple, Sony
```

#### 4.2.2 format_preferences

将 Agent 侧聚合的偏好数据格式化为 Markdown 列表：

```python
def format_preferences(cat_scores, brand_scores, prices, orders, cart) -> str:
    ...
    lines = [
        "## 📊 您的购物偏好分析",
        "",
        f"基于 {order_count} 笔订单 + {cart_count} 件购物车商品",
    ]
    # 类目 Top 3
    ...
    # 品牌 Top 3
    ...
    # 价格区间
    ...
```

### 4.3 Skill 规范

文件：`hmall-agent/src/workspace/customer/skills/personalized-recommendation/SKILL.md`

覆盖 4 种推荐场景的工作流规范：

| 场景 | 触发条件 | 工具调用链 |
|------|---------|-----------|
| 首页推荐 / 猜你喜欢 | "有什么推荐" / "猜我喜欢什么" | `get_recommendations_api(scene="home")` |
| 看了又看 | 查看商品后 / "还有类似的吗" | `get_recommendations_api(scene="detail", item_id=xxx)` |
| 购物车凑单 | "购物车还能加点什么" / "凑单推荐" | `get_recommendations_api(scene="cart")` |
| 偏好驱动推荐 | "我想换个手机" / "推荐点苹果生态的产品" | `analyze_user_preferences()` → `search_items_api()` |

**Skill 注册**（`agent.py`）：

```python
sources=[
    "/skills/shopping-guide/",
    "/skills/seckill-order/",
    "/skills/cart-management/",
    "/skills/order-management/",
    "/skills/address-management/",
    "/skills/personalized-recommendation/",  # ← 新增
],
```

### 4.4 Prompt 增强

文件：`hmall-agent/src/agents/customer/prompts.py`

SYSTEM_PROMPT 新增内容：

| 增强项 | 内容 |
|--------|------|
| 能力声明第 6 项 | `6. **个性化推荐**：基于用户购买/浏览历史推荐商品，支持猜你喜欢、看了又看、购物车凑单等场景，推荐时附带推荐理由` |
| 行为准则（4 条） | 模糊购物意图主动调用推荐工具；购买/浏览后顺势推荐；推荐务必说明理由；推荐后主动询问反馈形成闭环 |
| 输出格式 | `推荐：表格（含推荐标签列），推荐依据用 > 引用块说明` |

### 4.5 L1 正则路由

文件：`hmall-agent/src/agents/customer/regex_rules.py`

新增 2 条推荐正则规则 + `_extract_recommend_scene` 参数提取器：

```python
def _extract_recommend_scene(m: re.Match) -> dict:
    """从正则匹配中提取推荐场景。"""
    keyword = m.group(1) if m.groups() else ""
    if "凑单" in keyword or "购物车" in keyword:
        return {"scene": "cart"}
    return {"scene": "home"}
```

| 用户输入示例 | 匹配正则 | 路由工具 | 参数 |
|-------------|---------|---------|------|
| `有什么推荐` / `猜你喜欢` / `帮我选` / `随便看看` | `(?:推荐\|猜你喜欢\|有什么好\|帮我选\|随便看看\|给我推荐)` | `get_recommendations_api` | `scene=home` |
| `购物车推荐` / `凑单推荐` | `(?:购物车\|凑单).{0,5}(?:推荐\|加\|添\|凑)` | `get_recommendations_api` | `scene=cart` |
| `看了又看` / `相似商品` | （不拦截，走 L3 LLM） | `get_recommendations_api` | LLM 从上下文提取 `item_id` |

**"看了又看"不走 L1 的原因**：需要从对话上下文提取当前商品 ID，正则无法做到。由 L3 LLM 处理，LLM 能从最近对话历史中推断 `item_id` 并调用 `get_recommendations_api(scene="detail", item_id=xxx)`。

---

## 五、Gateway 路由配置

Gateway 路由配置存储在 Nacos 配置中心（`192.168.100.128:8848`），dataId 为 `gateway-routes.json`，不在本地代码库中。需在 Nacos 控制台手动新增路由条目：

```json
{
  "id": "item-recommend",
  "predicates": [{ "name": "Path", "args": { "pattern": "/recommend/**" } }],
  "uri": "lb://item-service"
}
```

**认证要求**：`/recommend/**` **不加入** `hm.auth.excludePaths`（免认证路径列表），确保走认证流程：

```
Gateway AuthGlobalFilter
  → 解析 JWT → 提取 userId
  → 写入 user-info header
  → 传递给 item-service
  → RecommendController 用 UserContext.getUser() 读取 userId
```

**免认证路径列表**（`application.yml`，`/recommend/**` 不在其中）：

```yaml
hm:
  auth:
    excludePaths:
      - /search/**
      - /users/login
      - /users/login/code
      - /users/code
      - /items/**
      - /admin/**
```

---

## 六、关键技术决策

### 6.1 决策：ES 召回通过 Feign 调用 search-service

**决策**：item-service 不直接查 ES，而是通过 Feign 调用 search-service 的 `/search/recommend` 端点。

**理由**：
- search-service 已从 item 拆分为独立微服务，item-service 中的 ES 依赖（pom.xml 中的 `elasticsearch-rest-high-level-client` 和 `ItemDoc.java`）是拆分前残留
- hm-api 模块已有完整的 Feign 基础设施（ItemClient、TradeClient 等 + DefaultFeignConfig + Fallback 工厂）
- Feign 调用走服务间直连（loadbalancer 服务发现），不经过 Gateway，`DefaultFeignConfig` 的 `RequestInterceptor` 自动透传 `user-info` header
- 符合微服务架构的职责分离：ES 查询能力归属 search-service

### 6.2 决策：新建 RecommendController 而非加到 ItemController

**决策**：新建独立的 `RecommendController`（`@RequestMapping("/recommend")`），而非加到 `ItemController`。

**理由**：
- `ItemController` 的路径是 `/items`，已被 Gateway 免认证排除（`/items/**`）
- `/recommend` 需要认证（用户维度推荐），不能放入免认证路径
- 独立 Controller 职责更清晰，推荐逻辑与商品 CRUD 解耦

### 6.3 决策：Formatter 用 Markdown 而非设计文档的纯文本

**决策**：`format_recommendations` 和 `format_preferences` 使用 Markdown 格式（表格、粗体、引用块），而非设计文档中的纯文本格式（`─` 分隔符）。

**理由**：
- 现有 15 个 Formatter 全部使用 Markdown 格式（`_table_row()` / `_table_sep()` / `_yuan()` 辅助函数）
- 前端 `MessageBubble.vue` 已集成 `marked` 库渲染 Markdown（标题/列表/表格/代码块/引用）
- 保持风格一致，避免推荐输出与其他工具的格式割裂

### 6.4 决策：推荐理由由 LLM 生成而非后端模板拼接

**决策**：后端只返回 `recommendTags`（商品级标签）和 `basedOn`（推荐依据摘要），推荐理由由 LLM 结合用户偏好生成。

**理由**：
- 后端模板拼接的理由生硬（"推荐理由：同类目热销"），缺乏个性化
- LLM 能结合偏好生成自然语言（"您常买 Apple 品牌产品，这款是同品类热销款"）
- LLM 能结合对话上下文调整话术（用户刚看了 iPhone → "与您刚看的 iPhone 相似"）
- 后端标签作为结构化数据给 LLM 参考，LLM 负责组织成自然语言

### 6.5 决策：analyze_user_preferences 不依赖后端画像服务

**决策**：偏好分析工具直接调用现有 `/orders/page` + `/carts` 接口，在 Agent 侧聚合。

**理由**：
- 零后端改动即可启用偏好分析能力
- 现有 `get_order_list_api` 和 `get_cart_list_api` 已验证可用
- Phase 1 无需后端画像服务，Phase 2 画像丰富后可切换为调用画像接口
- Agent 侧聚合逻辑简单（类目/品牌分数累加），无需复杂算法

### 6.6 决策："猜你喜欢"走 L1 正则，"看了又看"走 L3 LLM

**决策**：首页"猜你喜欢"通过 L1 正则快捷路由（<5ms），"看了又看"由 L3 LLM 处理。

**理由**：
- "猜你喜欢"是高频场景，参数固定（scene=home），适合正则拦截
- "看了又看"需要从对话上下文提取 `item_id`，正则无法做到
- LLM 能从最近对话历史中推断当前商品 ID，虽然慢 ~2s 但更准确

### 6.7 决策：偏好聚合通过 Feign + item 表而非跨表 SQL

**决策**：不在 item-service 中直接 JOIN `order_detail` + `order` 表聚合偏好，而是通过 Feign 调用 trade-service 获取已购商品 itemId+num，再查 item 表补充 category/brand 后在 item-service 侧聚合。

**理由**：
- 微服务数据库隔离：不同微服务的数据库是独立的，item-service 无法访问 trade-service 的 `order` / `order_detail` 表
- 设计文档原假设 item-service 和 trade-service 共享同一 MySQL（`shared-jdbc.yaml`），实际并非如此
- 微服务架构最佳实践：一个微服务不应直接访问另一个微服务的数据库表，即使物理上是同一数据库实例
- trade-service 只返回 itemId+num（轻量数据），category/brand 由 item-service 查自己的 item 表补充，职责分离清晰
- Feign 调用失败时 `safeQueryPurchasedItems` 降级返回空列表，推荐走热销兜底

---

## 七、配置说明与部署指引

### 7.1 新增依赖

**item-service pom.xml**：

| 依赖 | 用途 |
|------|------|
| `spring-cloud-starter-openfeign` | Feign 声明式 HTTP 客户端 |
| `spring-cloud-starter-loadbalancer` | 服务发现 + 负载均衡 |
| `feign-okhttp` | OkHttp 作为 Feign 底层 HTTP 客户端 |
| `hm-api`（com.heima:1.0.0） | 共享 Feign client + DTO + DefaultFeignConfig |

### 7.2 Nacos 配置

| 配置项 | dataId | 操作 |
|--------|--------|------|
| Gateway 动态路由 | `gateway-routes.json` | 新增 `/recommend/**` → `lb://item-service` 路由条目 |

### 7.3 启动检查清单

- [ ] Nacos 中 `gateway-routes.json` 已新增 `/recommend/**` 路由
- [ ] `/recommend/**` **未加入** `hm.auth.excludePaths`（确保需认证）
- [ ] item-service pom.xml 已引入 hm-api + openfeign 依赖
- [ ] item-service `ItemApplication` 已加 `@EnableFeignClients`
- [ ] trade-service 已启动（已购商品聚合依赖）
- [ ] search-service 已启动（ES 召回依赖）
- [ ] ES 索引 `items` 中有商品数据
- [ ] Agent 服务已重启加载新工具和 Skill
- [ ] 登录后对话输入"有什么推荐" → 返回推荐列表
- [ ] 未登录对话输入"有什么推荐" → 提示"需要先登录"
- [ ] 新用户（无订单）推荐 → 后端返回热销榜
- [ ] 推荐接口失败 → Agent 降级提示"推荐服务暂时不可用，您可以尝试搜索商品"

### 7.4 测试验证场景

| 测试场景 | 验证点 |
|---------|--------|
| L1 正则命中"推荐" | <5ms 返回推荐列表，不走 LLM |
| L1 正则命中"购物车推荐" | scene=cart 参数正确传递 |
| 未登录访问推荐 | 返回"需要先登录"提示 |
| 新用户推荐（无订单） | 后端返回热销榜，basedOn=null |
| scene=detail | 传 itemId，返回同类目商品 |
| search-service 不可用 | Feign 降级 → MySQL 热销兜底 |
| trade-service 不可用 | Feign 降级返回空列表 → 无偏好 → ES 全局热销兜底 |
| ES 无结果 | MySQL 热销兜底（`isFallback=true`，标签为"热销推荐"） |
| 推荐后加购 | 形成推荐→加购→凑单推荐闭环 |
| Formatter 空数据 | 返回"暂无推荐商品，您可以尝试搜索看看" |
| analyze_user_preferences 并发 | 订单+购物车并发获取无阻塞 |

---

## 八、降级与回滚

### 8.1 降级链路

```
推荐请求
  │
  ├─ 正常路径：Feign 获取已购商品 → item 表聚合偏好 → Feign ES 召回 → MySQL 补充 → 返回
  │
  ├─ Feign 降级（trade-service 不可用）
  │   → safeQueryPurchasedItems catch 返回空列表
  │   → 无偏好数据 → ES 全局热销召回
  │
  ├─ Feign 降级（search-service 不可用）
  │   → SearchClientFallbackFactory 返回空列表
  │   → RecommendServiceImpl 检测空结果 → MySQL 热销兜底
  │
  ├─ ES 无结果（偏好类目下无商品）
  │   → MySQL 热销兜底（item 表 ORDER BY sold DESC）
  │   → isFallback=true → basedOn=null → 标签为"热销推荐"
  │
  └─ Agent 降级（推荐接口 HTTP 错误）
      → GatewayError 捕获 → 返回"推荐服务暂时不可用，您可以尝试搜索商品"
      → 401 时返回"登录已过期，请重新登录后获取推荐"
```

### 8.2 各层降级行为

| 层级 | 故障场景 | 降级行为 | 影响 |
|------|---------|---------|------|
| trade-service | 服务不可用 | `safeQueryPurchasedItems` catch 返回空列表 | 无偏好数据，走 ES 全局热销兜底 |
| search-service | 服务不可用 / ES 宕机 | Feign Fallback 返回空列表 | 推荐 MySQL 热销兜底 |
| item-service | ES 无结果 | MySQL `ORDER BY sold DESC` | 返回全局热销，无个性化 |
| item-service | MySQL 也无数据 | 返回空列表 | Agent 提示"暂无推荐商品" |
| Agent | `/recommend` 接口返回错误 | `GatewayError` 捕获 | 提示用户手动搜索 |
| Agent | 用户未登录 | token 检查失败 | 提示"需要先登录" |
| Agent | `analyze_user_preferences` 中 `/items` 查询失败 | catch 忽略 | 仅用已有数据聚合（无 category/brand） |

### 8.3 回滚方案

推荐功能为增量新增，不修改任何现有功能，回滚安全：

1. **Agent 侧回滚**：从 `get_all_tools()` 移除 2 个推荐工具 + 从 `sources` 移除 Skill + 注释正则规则。无需删除文件。
2. **后端回滚**：删除 `RecommendController` + 从 Nacos 移除 `/recommend/**` 路由。item-service 的 Feign 依赖可保留（不影响其他功能）。
3. **数据无变更**：Phase 1 不新增数据库表，不修改现有表结构，无需数据回滚。

---

## 九、与设计文档的偏差说明

实现过程中因代码现状与设计文档假设不符，产生 4 处偏差，均已修正：

| # | 设计文档假设 | 实际代码现状 | 修正方案 |
|---|-------------|-------------|---------|
| 1 | item-service 直接查 ES（"ES 已有"） | item-service 的 ES 依赖是 search 拆分前的残留 | 改为 Feign 调用 search-service |
| 2 | Formatter 用纯文本格式（`─` 分隔符） | 现有 15 个 Formatter 全用 Markdown | 适配为 Markdown 风格 |
| 3 | `/recommend` 加到 ItemController | ItemController 是 `/items` 路径（已免认证） | 新建独立 RecommendController |
| 4 | RecommendMapper 跨表 JOIN `order_detail` + `order` + `item` | 微服务数据库隔离，item-service 无法访问 order 表 | 改为 Feign 调用 trade-service 获取已购商品 + item 表聚合偏好 |

**偏差 4 的详细背景**：设计文档第 6.1 节的 Phase 1 核心逻辑假设 item-service 和 trade-service 共享同一 MySQL 数据库（`shared-jdbc.yaml`），可直接跨表 JOIN `order_detail` + `order` + `item`。实际探索发现不同微服务的数据库是独立的，item-service 无法访问 trade-service 的 `order` / `order_detail` 表。正确做法是通过 Feign 调用 trade-service 新增的 `GET /orders/purchased-items` 端点获取已购商品 itemId+num，再在 item-service 侧查 item 表补充 category/brand 后聚合偏好。

---

## 十、后续优化方向

### 10.1 Phase 2 已完成项（用户画像持久化）

以下 Phase 2 项已通过 [hmall-agent-profile-and-notification-design.md](./hmall-agent-profile-and-notification-design.md) 第一部分落地：

| 方向 | 说明 | 阶段 | 状态 |
|------|------|------|------|
| 行为采集写入画像 | Agent 写操作工具成功后直接写 Redis 画像（`ProfileStore.record_event`），非 POST /behaviors + MQ | Phase 2 | ✅ 已完成 |
| Redis 画像计算 | `ProfileStore` HINCRBY 增量更新 `profile:{uid}:categories/brands/prices/stats`（非 Consumer + ZSet） | Phase 2 | ✅ 已完成 |
| 购买行为旁路采集 | `paySuccessListener` 支付成功后用 `StringRedisTemplate` HINCRBY 写入画像（非发 MQ 消息） | Phase 2 | ✅ 已完成 |
| 加购行为旁路采集 | `CartServiceImpl.addItem2Cart` 加购成功后用 `StringRedisTemplate` HINCRBY 写入画像（覆盖 Agent + 前端 UI 全路径） | Phase 2 | ✅ 已完成 |
| 推荐服务共享画像 | `RecommendServiceImpl.recommend()` 优先读 Redis 画像，miss 降级原 Feign 聚合 | Phase 2 | ✅ 已完成 |

### 10.2 待实施项

| 方向 | 说明 | 阶段 |
|------|------|------|
| 前端浏览埋点 | `ProductDetail.vue` 的 `onMounted` 上报 view 行为 | Phase 2 |
| Item-CF 共现矩阵 | `cf:{itemId}` Hash，定时任务计算 | Phase 2 |
| 向量召回 | ES `dense_vector` 语义相似，提升"看了又看"质量 | Phase 3 |
| 实时推荐反馈 | 用户点击/加购行为反馈到画像 | Phase 3 |
| 正则规则动态加载 | 推荐正则从 Nacos 动态加载 | Phase 3 |

### 10.3 Phase 2 实现说明

Phase 2 用户画像持久化已落地，详见 [hmall-agent-profile-and-notification-design.md](./hmall-agent-profile-and-notification-design.md) 第一部分。核心变更：

**Agent 侧（`hmall-agent/`）**：
- 新增 `src/profile/store.py` — `ProfileStore` 类：Redis 画像 CRUD + HINCRBY 增量更新，权重 purchase=5/cart=3/view=1（与 Phase 1 `_accumulate_preference` 一致）
- 新增 `src/profile/memory.py` — `save_memory` / `get_memories` 两个 @tool 工具，基于 LangGraph Store 跨会话语义记忆
- 修改 `tools.py` — `analyze_user_preferences` 画像优先（命中 0 次 Gateway）+ miss 降级 Phase 1 + 同步回写；加购/确认收货画像写入移至后端（避免双重记录），`update_cart_quantity_api` 不再记录 cart 事件
- 修改 `http_client.py` — 新增 `_extract_user_id` 函数（复用 `extract_token_from_config` 三级优先级 + JWT 兜底解码）
- 修改 `formatters.py` — `format_preferences` 适配画像直读模式（orders/cart 可选）
- 修改 `config.py` — 新增 `LANGGRAPH_STORE_URI`；`pyproject.toml` — `redis` 改为 `redis[hiredis]`

**后端侧（`hmall/`）**：
- 修改 `paySuccessListener.java` — 注入 `StringRedisTemplate` + `IOrderDetailService` + `ItemClient`，支付成功后查订单详情+商品信息，HINCRBY 写入 Redis 画像
- 修改 `CartServiceImpl.java` — 注入 `StringRedisTemplate`，加购成功后查商品信息，HINCRBY 写入 Redis 画像（覆盖 Agent + 前端 UI 全路径）
- 修改 `RecommendServiceImpl.java` — 注入 `StringRedisTemplate`，偏好聚合优先读 Redis 画像，miss 降级原 Feign 聚合

**关键实现偏差**：
1. **行为采集方式**：原设计为 `POST /behaviors` + MQ Consumer 异步落库，实际改为后端直接写 Redis（`paySuccessListener` 写 purchase 画像 + `CartServiceImpl` 写 cart 画像），覆盖 Agent + 前端 UI 全部行为路径，无需新增 API 和 MQ Consumer。Agent 侧 `analyze_user_preferences` miss 后同步回写画像
2. **Redis 画像结构**：原设计为 `up:` 前缀 + ZSet，实际改为 `profile:` 前缀 + Hash/List（HINCRBY 增量更新）
3. **序列化兼容性**：后端 `RedisService` 的 Hash 操作用 `redisTemplate`（Jackson 序列化），与 Agent 侧 `redis.asyncio`（plain string）不兼容。解决方案：后端画像读写均使用 `StringRedisTemplate`
4. **画像写入归属调整**：加购画像从 Agent 侧 `add_to_cart_api` 移至后端 `CartServiceImpl`（覆盖前端 UI 直接加购路径）；确认收货画像从 `confirm_receive_api` 移至 `paySuccessListener`（覆盖所有支付路径）；`update_cart_quantity_api` 不再记录 cart 事件（修改数量不改变偏好方向）。调整目的：避免双重记录 + 覆盖非 Agent 入口的行为

---

## 十一、与现有文档的关联

| 文档 | 关系 |
|------|------|
| `docs/Agent功能相关文档/agent-personalized-recommendation-design.md` | **设计文档**：本文档的实现源头，描述整体设计和 Phase 划分 |
| `docs/Agent功能相关文档/hmall-agent-implementation-report.md` | **关联**：Agent 整体实现说明，本文档在其基础上扩展推荐能力（工具 18→20） |
| `docs/redis功能相关文档/redis-integration-report.md` | **参考**：实现文档风格参照（分模块文件变更表 + 改造详情 + 降级回滚） |
| `hmall-agent/src/agents/customer/tools.py` | **修改**：新增 2 工具 + 辅助函数 + 注册 |
| `hmall-agent/src/agents/customer/prompts.py` | **修改**：SYSTEM_PROMPT 新增推荐能力声明 |
| `hmall-agent/src/agents/customer/regex_rules.py` | **修改**：新增 2 条推荐正则 + `_extract_recommend_scene` |
| `hmall-agent/src/agents/customer/agent.py` | **修改**：sources 新增 `/skills/personalized-recommendation/` |
| `hmall-agent/src/tools/formatters.py` | **修改**：新增 `format_recommendations` + `format_preferences` |
| `hmall-agent/src/workspace/customer/skills/personalized-recommendation/SKILL.md` | **新增**：推荐工作流规范 |
| `hmall/hm-api/src/main/java/com/hmall/api/client/SearchClient.java` | **新增**：search-service Feign 客户端 |
| `hmall/search-service/.../SearchController.java` | **修改**：新增 `/search/recommend` ES 召回端点 |
| `hmall/item-service/.../RecommendController.java` | **新增**：`GET /recommend` 接口 |
| `hmall/item-service/.../RecommendServiceImpl.java` | **新增**：三步管线核心实现 |
| `hmall/hm-api/.../client/TradeClient.java` | **修改**：新增 `queryPurchasedItems` Feign 方法 |
| `hmall/trade-service/.../OrderController.java` | **修改**：新增 `GET /orders/purchased-items` 端点 |
| `hmall/trade-service/.../OrderServiceImpl.java` | **修改**：实现已购商品按 itemId 聚合 num |
| `hmall/item-service/.../RecommendServiceImpl.java` | **新增**：三步管线核心实现（Feign 聚合偏好 + ES 召回 + MySQL 补充） |
| `hmall/hm-gateway/src/main/resources/application.yml` | **参考**：免认证路径配置（`/recommend/**` 不在排除列表） |

---

> **实现完成度**：Phase 1（步骤 1-6）全部实现，Agent 侧 2 个新工具 + 2 个 Formatter + 1 个 Skill + Prompt/正则/注册增强就绪，后端 `GET /recommend` 接口遵循"Feign 聚合偏好 → ES 召回 → 销量排序"三步管线（Feign 调用 trade-service 获取已购商品 + Feign 调用 search-service ES 召回），Gateway 路由需手动配置 Nacos。推荐具备完整降级链路（trade-service Feign 降级 → ES Fallback → MySQL 热销兜底 → Agent 搜索提示）。Phase 2（行为采集 + Redis 画像 + Item-CF）为后续优化项。
