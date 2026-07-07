# HMall 项目代码优化分析报告

> 分析日期：2026-07-06  
> 分析范围：后端全部微服务模块及前端 Nginx 项目

---

## 目录

1. [关键问题（安全/稳定性）](#1-关键问题安全稳定性)
2. [架构与设计问题](#2-架构与设计问题)
3. [依赖管理问题](#3-依赖管理问题)
4. [代码质量问题](#4-代码质量问题)
5. [性能问题](#5-性能问题)
6. [配置问题](#6-配置问题)
7. [测试覆盖问题](#7-测试覆盖问题)
8. [前端优化建议](#8-前端优化建议)
9. [优化优先级路线图](#9-优化优先级路线图)

---

## 1. 关键问题（安全/稳定性）

### 1.1 【严重】SQL 注入隐患 — `${}` 代替 `#{}`

**文件：**
- `hm-service/src/main/java/com/hmall/mapper/UserMapper.java` 第18行
- `user-service/src/main/java/com/hmall/user/mapper/UserMapper.java` 第18行

```java
// 当前代码（不安全）
@Update("update user set balance = balance - ${totalFee} where id = #{userId}")
void updateMoney(@Param("userId") Long userId, @Param("totalFee") Integer totalFee);
```

**问题：** 使用 `${}` 进行字符串拼接而非 `#{}` 参数化。虽然当前参数是 `Integer` 类型风险较低，但这是不安全编码模式，若未来被重构为 String 类型将产生 SQL 注入漏洞。

**修复：**
```java
@Update("update user set balance = balance - #{totalFee} where id = #{userId}")
void updateMoney(@Param("userId") Long userId, @Param("totalFee") Integer totalFee);
```

---

### 1.2 【严重】硬编码密码在配置文件中

**文件：**
- `hm-service/src/main/resources/application.yaml` 第46行
- `hm-gateway/src/main/resources/application.yml` 第7行
- `user-service/src/main/resources/application.yaml` 第7行

```yaml
hm:
  jwt:
    password: hmall123  # JWT 密钥库密码明文
```

**问题：** JWT 密钥库密码 `hmall123` 明文硬编码，泄露后攻击者可伪造 JWT 令牌。

**修复：** 使用环境变量或加密配置：
```yaml
hm:
  jwt:
    password: ${JWT_KEYSTORE_PASSWORD:hmall123}  # 默认值仅用于本地开发
```

---

### 1.3 【严重】网关认证过滤器 `includePaths` 未生效

**文件：** `hm-gateway/src/main/java/com/hmall/gateway/filters/AuthGlobalFilter.java` 第42-45行

```java
// 当前代码：只使用 excludePaths，includePaths 被定义但从未使用
if (isExclude(request.getPath().toString())) {
    return chain.filter(exchange);
}
```

**问题：** `AuthProperties` 中定义了 `includePaths`（需要校验的路径列表），但在 `AuthGlobalFilter` 中从未使用。这意味着 Nacos 配置了哪些路径需要校验也不会生效，造成白名单/黑名单逻辑不完整。

**修复：**
```java
// 同时检查 includePaths（如果配置了，则只对 includePaths 中的路径做认证）
if (isExclude(request.getPath().toString())) {
    return chain.filter(exchange);
}
if (!authProperties.getIncludePaths().isEmpty() 
    && !isInclude(request.getPath().toString())) {
    return chain.filter(exchange);  // 不在 include 列表中的直接放行
}
```

---

### 1.4 【高危】支付密码通过 URL 查询参数传输

**文件：**
- `hm-service/src/main/java/com/hmall/controller/UserController.java` 第34行
- `user-service/src/main/java/com/hmall/user/controller/UserController.java` 第35行
- `hm-api/src/main/java/com/hmall/api/client/UserClient.java` 第22行

```java
// 当前代码：pw 通过 URL QueryString 传输
@PutMapping("/money/deduct")
public void deductMoney(@RequestParam("pw") String pw, @RequestParam("amount") Integer amount)
```

**问题：** 支付密码通过 GET 参数传递，会被记录在 Nginx 日志、浏览器历史、代理服务器日志中。

**修复：** 改为 `@RequestBody` POST 传输：
```java
@PostMapping("/money/deduct")
public void deductMoney(@RequestBody DeductRequest request) {
    // request.getPw(), request.getAmount()
}
```

---

### 1.5 【高危】并发扣款缺少分布式锁

**文件：**
- `hm-service/src/main/java/com/hmall/service/impl/UserServiceImpl.java` 第68-84行
- `user-service/src/main/java/com/hmall/user/service/impl/UserServiceImpl.java` 第69-85行

```java
public void deductMoney(String pw, Integer totalFee) {
    // 1. 校验密码
    // 2. 直接扣款 — 无锁保护
    baseMapper.updateMoney(UserContext.getUser(), totalFee);
}
```

**问题：** 扣款操作没有乐观锁（version 字段）或分布式锁保护。在高并发场景下可能出现重复扣款或余额异常。

**修复方案：**

方案A（乐观锁）：
```java
// SQL 改为带版本号的更新
@Update("update user set balance = balance - #{totalFee}, version = version + 1 " +
        "where id = #{userId} and balance >= #{totalFee} and version = #{version}")
int updateMoney(@Param("userId") Long userId, @Param("totalFee") Integer totalFee,
                @Param("version") Integer version);
// 返回值=0 表示并发冲突，需要重试
```

方案B（分布式锁）：
```java
public void deductMoney(String pw, Integer totalFee) {
    String lockKey = "lock:user:deduct:" + UserContext.getUser();
    RLock lock = redissonClient.getLock(lockKey);
    try {
        if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
            // 扣款逻辑
        }
    } finally {
        lock.unlock();
    }
}
```

---

### 1.6 【高危】ES 连接地址硬编码 + 连接泄漏

**文件：** `search-service/src/main/java/com/hmall/search/config/ElasticsearchConfig.java`

```java
// 硬编码地址
@Bean
public RestHighLevelClient restHighLevelClient() {
    return new RestHighLevelClient(
        RestClient.builder(HttpHost.create("192.168.100.128:9200"))  // 硬编码
    );
}

// 重复创建了第二个 Bean（连接泄漏！）
@Bean
public RestHighLevelClient restHighLevelClientWithClose() {
    return new RestHighLevelClient(RestClient.builder(HttpHost.create("192.168.100.128:9200")));
}
```

**问题：** 
1. ES 地址硬编码在 Java 代码中
2. 创建了两个 `RestHighLevelClient` Bean，每次应用启动都会创建两个 ES 连接，浪费资源

**修复：**
```java
@Configuration
@ConfigurationProperties(prefix = "elasticsearch")
public class ElasticsearchConfig {
    private String host;
    private int port;

    @Bean
    public RestHighLevelClient restHighLevelClient() {
        return new RestHighLevelClient(
            RestClient.builder(new HttpHost(host, port, "http"))
        );
    }
}
```

```yaml
# application.yaml
elasticsearch:
  host: ${ES_HOST:192.168.100.128}
  port: ${ES_PORT:9200}
```

---

### 1.7 【高危】hm-service 与微服务存在大量重复代码

**范围：** 整个 `hm-service/` 与 `cart-service/`、`item-service/`、`user-service/`、`trade-service/`、`pay-service/` 之间。

**问题：** `hm-service` 是项目初期的单体版本，后来拆分出各微服务后，`hm-service` 中的 Service/Controller/Mapper 代码并未移除，形成两个版本的并行维护：

| 重复内容 | hm-service 中 | 微服务中 |
|---------|-------------|---------|
| `CartServiceImpl` | ✅（硬编码 max=10） | `cart-service`（使用 `CartProperties` 配置化） |
| `ItemServiceImpl` | ✅（大量重复） | `item-service` |
| `UserServiceImpl` | ✅ | `user-service` |
| `PayOrderServiceImpl` | ✅ | `pay-service` |
| 全部 Mapper | ✅ | 各微服务 |

**后果：** 修改一处容易遗漏另一处，导致业务逻辑不一致。例如购物车数量限制，`hm-service` 是硬编码 `10`，而 `cart-service` 是配置化读取。

**修复：** 建议确定一个主版本：
- **方案A**：以微服务版本为准，`hm-service` 中的重复代码改为通过 Feign 调用微服务
- **方案B**：以 `hm-service` 单体为准，废弃微服务版本
- **推荐方案A**，保证架构一致性

---

## 2. 架构与设计问题

### 2.1 DTO/VO 类在多个模块中重复定义

以下类在 3-4 个模块中存在完全相同的定义：

| 类名 | 出现位置 |
|------|---------|
| `ItemDTO` | `hm-api`, `hm-service`, `item-service`, `search-service` |
| `OrderDetailDTO` | `hm-api`, `hm-service`, `item-service`, `search-service` |
| `ItemPageQuery` | `hm-service`, `item-service`, `search-service` |
| `ItemDoc` | `item-service`, `search-service` |
| `PayChannel/PayStatus/PayType` 枚举 | `hm-service`, `pay-service` |

**修复：** 这些 DTO 应在 `hm-api` 中统一定义，其他模块通过依赖 `hm-api` 引入。删除各服务中重复的类定义。

---

### 2.2 `hm-common` 模块职责不清晰

**文件：** `hm-common/pom.xml`

**问题：** `hm-common` 模块引入了大量不应该在公共模块中的依赖：

| 依赖 | 是否应该在此模块 |
|------|----------------|
| `mybatis-plus-boot-starter` | ❌ ORM 框架不是所有服务都需要 |
| `tomcat-embed-core` | ❌ Web 容器不应在公共模块 |
| `spring-boot-starter-amqp` | ❌ 消息队列不是所有服务都需要 |
| `spring-boot-starter-aop` | ⚠️ 建议可选 |
| `spring-boot-starter-data-redis` | ❌ 缓存不是所有服务都需要 |

**后果：** 所有引用 `hm-common` 的模块都会被强制引入这些依赖，即使是网关（不需要 DB、MQ、Cache）也不例外。

**修复方案：**
1. 将 `hm-common` 拆分为多个子模块：
   - `hm-common-core` — 异常、工具类、基础注解
   - `hm-common-mybatis` — MyBatis-Plus 配置（可选引入）
   - `hm-common-mq` — RabbitMQ 公共配置（可选引入）
   - `hm-common-cache` — Redis 公共配置（可选引入）
2. 或者将 `hm-common` 中所有非核心依赖的 scope 改为 `provided`/`optional`

---

### 2.3 `hm-api` 模块不应该依赖 MyBatis-Plus

**文件：** `hm-api/pom.xml` 第42-45行

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-boot-starter</artifactId>
</dependency>
```

**问题：** `hm-api` 的职责是 Feign 接口定义和 DTO 声明，不应该依赖 ORM 框架。这会引入大量不必要的数据库相关依赖。

**修复：** 如果确实需要 `@TableName` 等注解，改为只依赖 `mybatis-plus-annotation`：
```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-annotation</artifactId>
    <version>3.4.3</version>
</dependency>
```

---

### 2.4 缺少统一异常处理体系

**问题：** 各服务中的 Controller 大多没有统一的异常处理。部分方法抛出异常后直接返回 500，没有业务友好的错误信息。

**示例（trade-service/OrderController）：**
```java
@PostMapping
public Long createOrder(@RequestBody OrderFormDTO orderFormDTO) {
    return orderService.createOrder(orderFormDTO);  // 没有 try-catch，异常直接抛出
}
```

**修复：** 在 `hm-common` 中定义全局异常处理：
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e) {
        return R.error(e.getCode(), e.getMessage());
    }
    
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("未知错误", e);
        return R.error(500, "服务器内部错误");
    }
}
```

---

### 2.5 前端三个子应用代码严重重复

**范围：** `hmall-nginx/html/` 下的三个子应用

**问题：**
- `hmall-portal`、`hmall-admin`、`hm-refresh-admin` 三个子应用各自独立引入了 `vue.js`、`axios.min.js`、`element.js` 等基础库，文件完全相同
- 没有共享的组件库或公共样式
- `hm-refresh-admin` 自实现了简易 `ViewRouter`，缺少完整的 SPA 路由方案

**修复：**
- 将 `vue.js`、`axios.min.js`、`element.js/css` 等公共库提取到 `html/common/` 目录
- 使用 `index.html` 中的 `<script src="/common/vue.js">` 统一引用

---

## 3. 依赖管理问题

### 3.1 父 POM 中 `item-service` 模块重复声明

**文件：** `hmall/pom.xml` 第12-16行

```xml
<module>hm-common</module>
<module>hm-service</module>
<module>item-service</module>
<module>item-service</module>   <!-- 重复声明 -->
<module>cart-service</module>
```

---

### 3.2 `spring-security-rsa` 版本未统一管理

**文件：**
- `hm-gateway/pom.xml` — 硬编码 `1.0.9.RELEASE`
- `hm-service/pom.xml` — 硬编码 `1.0.9.RELEASE`
- `pay-service/pom.xml` — 未指定版本（依赖传递）
- `user-service/pom.xml` — 未指定版本（依赖传递）
- `trade-service/pom.xml` — 未指定版本（依赖传递）

**修复：** 在父 POM 的 `<dependencyManagement>` 中统一声明版本。

---

### 3.3 `hm-common` 中 AMQP 依赖重复声明

**文件：** `hm-common/pom.xml` 第77-98行

`spring-boot-starter-amqp` 已包含 `spring-amqp` 和 `spring-rabbit`，但仍显式声明了后者，造成冗余。

**修复：** 删除 `spring-amqp` 和 `spring-rabbit` 的显式声明。

---

### 3.4 `search-service` 缺少 `spring-boot-maven-plugin`

**文件：** `search-service/pom.xml`

`search-service` 没有配置 `spring-boot-maven-plugin`，无法被打包为可执行 JAR。

**修复：**
```xml
<build>
    <finalName>${project.artifactId}</finalName>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

---

### 3.5 Elasticsearch 版本属性定义但未使用

**文件：** `hmall/pom.xml` 第43行

```xml
<elasticsearch.version>7.12.1</elasticsearch.version>
```

该属性在 `<properties>` 中定义了，但在 `<dependencyManagement>` 中未声明 ES 依赖，子模块中的 ES 依赖版本实际由 Spring Boot BOM 控制。属性形同虚设。

**修复：** 在 `<dependencyManagement>` 中添加 ES 依赖声明。

---

## 4. 代码质量问题

### 4.1 `hm-common` 中 `UserContext` 线程安全问题

**文件：** `hm-common/src/main/java/com/hmall/common/utils/UserContext.java`

```java
public class UserContext {
    private static final ThreadLocal<Long> tl = new ThreadLocal<>();
    
    public static void setUser(Long userId) { tl.set(userId); }
    public static Long getUser() { return tl.get(); }
    public static void removeUser() { tl.remove(); }
}
```

**问题：** 使用 ThreadLocal 在线程池环境下可能导致内存泄漏：
1. 线程复用后未及时清理 ThreadLocal
2. 如果微服务间通过 Feign 异步调用，子线程无法获取父线程的 ThreadLocal
3. 缺少 `try-finally` 保证清理

**修复：**
```java
// 1. 拦截器中加 finally 确保清理
try {
    UserContext.setUser(userId);
    return true;
} finally {
    // 不要在这里 remove，等请求处理完再 remove
}

// 2. 使用 TransmittableThreadLocal 支持跨线程传递
private static final TransmittableThreadLocal<Long> tl = new TransmittableThreadLocal<>();

// 3. Feign 拦截器中传递用户上下文
requestTemplate.header("user-info", String.valueOf(UserContext.getUser()));
```

---

### 4.2 商品查询已使用批量查询（无明显 N+1 问题）

**核实结果：** 原分析有误。`CartServiceImpl.handleCartItems()` 已正确使用批量查询：

```java
// CartServiceImpl.java 第88-111行 — 当前实现
private void handleCartItems(List<CartVO> vos) {
    // 1. 收集所有 itemId
    Set<Long> itemIds = vos.stream().map(CartVO::getItemId).collect(Collectors.toSet());
    // 2. 一次性批量查询（非逐条查询）
    List<ItemDTO> items = itemClient.queryItemsByIds(itemIds);
    // ...
}
```

`ItemClient.queryItemById` 单条接口虽已定义，但在全项目中没有任何调用方。购物车查询为 1 次 DB + 1 次 RPC 批量调用，不存在 N+1 问题。

---

### 4.3 订单创建中仍存在的同步 RPC 调用

**文件：** `trade-service/src/main/java/com/hmall/trade/service/impl/OrderServiceImpl.java`

订单创建在 `@GlobalTransactional` 中的实际调用链路：

```java
@GlobalTransactional
public Long createOrder(OrderFormDTO orderFormDTO) {
    // 1. 查询商品详情 → itemClient.queryItemsByIds() (RPC 批量)
    // 2. 计算金额 → 纯内存计算
    // 3. 创建订单 → save(order) (本地 DB)
    // 4. 保存订单详情 → detailService.saveBatch() (本地 DB)
    // 5. 插入本地消息表 → 异步清除购物车 (本地 DB，已优化)
    // 6. 扣减库存 → itemClient.deductStock() (同步 RPC，仍在事务内)
    // 7. 发送延迟消息 → RabbitMQ (异步)
}
```

**已优化部分：** 清除购物车已从同步 RPC（`cartClient.deleteCartItemByIds`，第86行已注释）改为**本地消息表 + RabbitMQ**异步方式（第93-105行），避免了跨服务的 RPC 阻塞。

**仍存在问题：**

1. `itemClient.deductStock()`（第109行）仍是同步 RPC，扣减库存在 Seata 全局事务内，锁持有时间随 RPC 耗时增长
2. `@GlobalTransactional` 虽然只保留 2 个 RPC + N 个本地 DB 操作，但扣减库存的全局锁仍影响并发性能

**修复建议：**
- 考虑库存预扣方案：下单时 Redis 预扣库存 → 异步同步 DB，减少 RPC 同步等待
- 扣减库存失败通过补偿机制（如订单状态回滚）处理，而非依靠全局事务锁

---

### 4.4 日志不完善

**问题：** 大量方法没有关键业务日志，排查问题困难。

**修复建议：** 在关键业务节点添加日志：
```java
log.info("创建订单开始, userId={}, items={}", userId, itemIds);
// ... 业务逻辑 ...
log.info("创建订单成功, orderId={}, amount={}", order.getId(), order.getTotalFee());
```

---

### 4.5 缺少参数校验

**问题：** 多个 Controller 的入参没有 `@Valid` / `@Validated` 校验注解，参数非法时直接导致 500 错误而不是友好的 400 校验失败提示。

**示例：**
```java
@PostMapping
public Long createOrder(@RequestBody OrderFormDTO orderFormDTO) {
    // orderFormDTO 没有校验注解
}
```

**修复：**
```java
@Data
public class OrderFormDTO {
    @NotNull(message = "商品列表不能为空")
    private List<OrderItemDTO> items;
    
    @NotNull(message = "地址ID不能为空")
    private Long addressId;
}

@PostMapping
public Long createOrder(@Valid @RequestBody OrderFormDTO orderFormDTO) { ... }
```

---

## 5. 性能问题

### 5.1 Elasticsearch 使用过时的 `RestHighLevelClient`

**文件：**
- `search-service/src/main/java/com/hmall/search/config/ElasticsearchConfig.java`
- `item-service` 中的 ES 相关代码

**问题：** `RestHighLevelClient` 在 ES 7.15.0+ 中已被标记为废弃，推荐使用新的 `ElasticsearchClient`（Java API Client）。

**修复：** 迁移到新的 Elasticsearch Java Client：
```java
// 新版
ElasticsearchClient client = new ElasticsearchClient(
    new RestClientTransport(restClient, new JacksonJsonpMapper())
);
```

---

### 5.2 商品搜索缺少结果缓存

**文件：** `search-service` 搜索实现

**问题：** 每次搜索请求都直接查询 Elasticsearch，热门商品搜索结果没有缓存，重复查询浪费资源。

**修复：**
```java
@Cacheable(value = "item:search", key = "#query.keyword + '_' + #query.page", unless = "#result == null")
public PageDTO<ItemDTO> search(ItemPageQuery query) {
    // ES 查询逻辑
}
```

---

### 5.3 缺少分页查询

**问题：** 部分列表查询接口（如用户列表、订单列表）没有分页参数，一次性返回全部数据。

**修复：** 所有列表查询添加分页支持，使用 MyBatis Plus 的 `Page` 对象。

---

## 6. 配置问题

### 6.1 Nacos 地址硬编码

**文件：** 所有服务的 `bootstrap.yml`

```yaml
spring:
  cloud:
    nacos:
      server-addr: 192.168.100.128:8848  # 硬编码
```

**修复：** 使用环境变量：
```yaml
spring:
  cloud:
    nacos:
      server-addr: ${NACOS_ADDR:192.168.100.128:8848}
```

---

### 6.2 数据库密码硬编码

**文件：** 各服务 `application-local.yaml`

```yaml
spring:
  datasource:
    password: 123  # 硬编码
```

**修复：**
```yaml
spring:
  datasource:
    password: ${DB_PASSWORD:123}
```

---

### 6.3 Nacos 配置的 Group 不一致

**问题：** 部分共享配置使用 `DEFAULT_GROUP`，部分没有指定（默认也是 `DEFAULT_GROUP`），但配置命名不统一。

**修复：** 统一 Nacos 配置命名规范：
- `shared-jdbc.yaml` → `hmall-shared-jdbc.yaml`
- 所有服务统一使用 `DEFAULT_GROUP`

---

## 7. 测试覆盖问题

### 7.1 缺少单元测试

**现状：** 项目中仅 `item-service` 有 2 个 ES 连接测试类（`ElasticSeachTest.java`、`TestConnection.java`），且都已注释或包含硬编码配置。其余所有服务模块完全没有单元测试。

**影响：** 任何重构或修改都缺少自动化质量保障。

**建议：**
1. 优先给 `hm-common` 工具类编写单元测试
2. 给核心业务 Service（`OrderServiceImpl`、`CartServiceImpl`）编写集成测试
3. 使用 TestContainers 或 H2 内存数据库进行 DAO 层测试
4. 对 Feign 调用使用 Mock 测试

---

### 7.2 测试配置的 ES 地址硬编码

**文件：**
- `item-service/src/test/java/com/hmall/item/es/ElasticSeachTest.java` 第158-160行
- `item-service/src/test/java/com/hmall/item/es/TestConnection.java` 第142-144行

```java
HttpHost.create("192.168.100.128:9200")
```

**修复：** 使用 `application-test.yaml` 配置或 TestContainers 启动嵌入式 ES。

---

## 8. 前端优化建议

### 8.1 静态资源无缓存策略

**文件：** `hmall-nginx/conf/nginx.conf`

**问题：** Nginx 配置中没有对静态资源设置缓存头，每次访问都重新请求 JS/CSS/图片。

**修复：**
```nginx
location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg)$ {
    expires 30d;
    add_header Cache-Control "public, immutable";
}
```

---

### 8.2 Gzip 压缩未启用

**修复：**
```nginx
gzip on;
gzip_min_length 1k;
gzip_types text/plain application/javascript text/css application/json;
```

---

### 8.3 前端无工程化构建

**问题：** 三个子应用使用原始 HTML + JS + CSS 开发，没有模块化、打包、代码分割等现代前端工程能力。

**建议：** 迁移到 Vue 2 + webpack 或 Vue 3 + Vite 工程化方案，获得：
- 组件化开发（`.vue` 单文件组件）
- 代码分割和懒加载
- CSS 预处理（Sass/Less）
- 开发热更新
- 生产环境代码压缩和 Tree Shaking

---

### 8.4 前端无路由守卫和权限控制

**文件：** `hm-refresh-admin/js/view-router.js`

**问题：** `hm-refresh-admin` 的自实现 `ViewRouter` 缺少路由守卫，登录校验仅在前端进行（检查 sessionStorage），可被绕过。

**建议：** 后端所有管理接口必须做权限校验，前端路由守卫仅作为 UX 优化。

---

## 9. 优化优先级路线图

### P0 — 立即修复（安全风险）

| 序号 | 问题 | 影响 |
|------|------|------|
| 1 | `${}` 代替 `#{}` 的 SQL 注入隐患（§1.1） | SQL 注入 |
| 2 | JWT 密码明文硬编码（§1.2） | 令牌伪造 |
| 3 | ES 地址硬编码 + 连接泄漏（§1.6） | 资源泄漏 |
| 4 | 支付密码 URL 传输（§1.4） | 密码泄露 |

### P1 — 近期修复（稳定性与架构）

| 序号 | 问题 | 影响 |
|------|------|------|
| 5 | 并发扣款缺锁（§1.5） | 资金安全 |
| 6 | hm-service 重复代码清理（§1.7） | 维护风险 |
| 7 | 网关 includePaths 未生效（§1.3） | 认证绕过 |
| 8 | `search-service` 缺打包插件（§3.4） | 无法部署 |

### P2 — 中期优化（代码质量与性能）

| 序号 | 问题 | 影响 |
|------|------|------|
| 9 | DTO 重复定义清理（§2.1） | 维护成本 |
| 10 | 全局异常处理（§2.4） | 用户体验 |
| 11 | 参数校验添加（§4.5） | 健壮性 |
| 12 | ES Client 升级（§5.1） | 技术债务 |
| 13 | 订单长事务优化（§4.3） | 性能 |

### P3 — 长期规划（工程化与质量保障）

| 序号 | 问题 | 影响 |
|------|------|------|
| 15 | `hm-common` 模块拆分（§2.2） | 架构清晰度 |
| 16 | `hm-api` 依赖精简（§2.3） | 依赖卫生 |
| 17 | 单元测试覆盖（§7.1） | 质量保障 |
| 18 | 前端工程化迁移（§8.3） | 开发效率 |
| 19 | Nginx 静态资源缓存（§8.1） | 加载性能 |

---

## 附录：依赖版本统一建议

建议在父 POM 的 `<dependencyManagement>` 中统一管理以下版本，避免各模块版本不一致：

```xml
<dependencyManagement>
    <dependencies>
        <!-- ES 版本统一 -->
        <dependency>
            <groupId>org.elasticsearch.client</groupId>
            <artifactId>elasticsearch-rest-high-level-client</artifactId>
            <version>${elasticsearch.version}</version>
        </dependency>
        
        <!-- RSA 版本统一 -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-rsa</artifactId>
        </dependency>
        
        <!-- Hutool 版本统一 -->
        <dependency>
            <groupId>cn.hutool</groupId>
            <artifactId>hutool-all</artifactId>
            <version>${hutool.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

> 本报告基于静态代码分析和人工审查，部分运行时问题（如内存泄漏、死锁）需要在压测环境中进一步验证。
