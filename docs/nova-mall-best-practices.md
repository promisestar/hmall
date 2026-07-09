# Nova Mall → HMall 可吸收优秀实践分析

> 分析日期：2026-07-08  
> 来源项目：nova-mall（Spring Boot 3.5.14 + JDK 17 + MyBatis-Plus 3.5.15）  
> 目标项目：hmall（Spring Boot 2.7.12 + Spring Cloud 2021 + JDK 11）

---

## 一、项目概览对比

| 维度 | nova-mall | hmall |
|------|-----------|-------|
| 架构风格 | 模块化单体（5 子模块） | 微服务（Gateway + 8 服务） |
| JDK | 17（支持 record 等新特性） | 11 |
| ORM | MyBatis-Plus 3.5.15 | MyBatis-Plus 3.4.3 |
| 安全 | Spring Security + JWT + 动态 RBAC | Gateway JWT 拦截 + ThreadLocal |
| 设计模式 | 工厂+策略×3 / 责任链×3 | 传统分层，无显式模式 |
| 测试 | 12 个测试类，含责任链单测 | 仅 4 个测试类 |
| 文档 | API.md(4000+行) + 设计模式文档 + 部署文档 | 2 个 docs 文件 |
| 前端 | 无（纯后端） | Vue 3 SPA + 旧版 MPA |

---

## 二、hmall 已有优势（需保持）

在吸收 nova-mall 优秀实践前，先确认 hmall 已经做得好且不应丢失的能力：

1. **本地消息表 + 定时重发**：保证分布式最终一致性
2. **Seata 全局事务**：分布式场景下的强一致性保障
3. **Long → String 序列化**：防止 JS 雪花 ID 精度丢失
4. **Feign 自动传递用户上下文**：`DefaultFeignConfig.RequestInterceptor`
5. **Sentinel 降级**：`ItemClientFallbackFactory` 熔断保护
6. **Nacos 动态路由**：`DynamicRouteLoader` 热更新
7. **订单延迟取消**：RabbitMQ 延迟消息 30 分钟未支付取消
8. **前端 TypeScript 类型完善**：DTO 类型定义完整
9. **现有 optimization-report.md**：已有问题记录和修复历史

> **关键原则**：吸收 nova-mall 的优势时，不应破坏 hmall 已经做得好的部分（特别是分布式事务和消息最终一致性体系）。

---

## 三、强烈建议吸收（P0 — 高价值低风险）

### 3.1 统一 API 响应格式

**现状对比**：

| 项目 | 响应格式 | 问题 |
|------|---------|------|
| nova-mall | `CommonResult<T>`：`{code, message, data}` + 8 种状态码枚举 | 格式完全统一，每个 Controller 都返回 `CommonResult` |
| hmall | `R<T>`：部分接口返回裸类型 `Long`/`void`/`String` | `OrderController.createOrder()` 返回裸 `Long`，`PayController.applyPayOrder()` 返回裸 `String` |

**建议吸收**：

```java
// 当前 hmall 的不一致问题
@PostMapping
public Long createOrder(@RequestBody OrderFormDTO orderFormDTO) { ... }  // 裸 Long

@PostMapping("/apply")
public String applyPayOrder(@RequestBody PayApplyDTO applyDTO) { ... }   // 裸 String
```

改为统一返回 `R<T>`：

```java
@PostMapping
public R<Long> createOrder(@RequestBody OrderFormDTO orderFormDTO) { ... }

@PostMapping("/apply")
public R<String> applyPayOrder(@RequestBody PayApplyDTO applyDTO) { ... }
```

**收益**：前端无需对不同接口做 `responseType: 'text'` 特殊处理，错误处理逻辑统一。

---

### 3.2 业务错误码枚举体系

**nova-mall 做法**：`ResultCode` 枚举（实现 `IErrorCode` 接口）

```java
public enum ResultCode implements IErrorCode {
    SUCCESS(200, "操作成功"),
    FAILED(500, "操作失败"),
    VALIDATE_FAILED(404, "参数检验失败"),
    UNAUTHORIZED(401, "暂未登录或token已经过期"),
    FORBIDDEN(403, "没有相关权限"),
    SYSTEM_ERROR(501, "系统异常"),
    NULL_POINTER(502, "空指针异常");
}
```

**hmall 现状**：只有 HTTP 状态码，无业务错误码。如 `RuntimeException("库存不足！")` 前端无法区分。

**建议吸收**：在 hm-common 建立 `BizCode` 枚举，替换现有的 `RuntimeException` 裸抛：

```java
// 当前
throw new RuntimeException("库存不足！");

// 建议
throw new BizException(BizCode.STOCK_INSUFFICIENT);
```

**收益**：前端可根据错误码做差异化处理（如库存不足提示 vs 网络错误重试）。

---

### 3.3 请求日志 AOP

**nova-mall 做法**：`WebLogAspect` — `@Aspect @Order(1)` 环绕通知，自动记录：

- 请求 URL / HTTP 方法 / 客户端 IP
- 请求参数
- 响应结果
- 耗时（毫秒）
- 从 Swagger `@Operation` 注解自动提取接口描述

**hmall 现状**：关键业务节点（创建订单、扣款）几乎没有日志，排查问题极其困难。

**建议吸收**：在 hm-common 增加 `WebLogAspect`，拦截所有 Controller 方法：

```java
@Aspect
@Order(1)
@Component
public class WebLogAspect {
    @Around("execution(* com.hmall..controller..*.*(..))")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = joinPoint.proceed();
        long cost = System.currentTimeMillis() - start;
        log.info("{} {} [{}ms] -> {}", method, url, cost, result);
        return result;
    }
}
```

**收益**：零侵入记录所有 API 调用，排查问题效率提升 10 倍。

---

### 3.4 Token 续期机制

**nova-mall 做法**：`JwtTokenUtil.refreshHeadToken()`

- 每次请求通过 JWT 过滤器时检查 token
- 如果在 **30 分钟内续期过** → 返回原 token（防频繁刷新）
- 超过 30 分钟 → 更新 `created` 时间生成新 token → 通过响应头 `Authorization` 返回

**hmall 现状**：只有 30 分钟有效期，过期必须重新登录。无 refresh token，无自动续期。

**建议吸收**：在 gateway 的 `AuthGlobalFilter` 中增加续期逻辑：

```java
// 解析 JWT → 检查是否需要续期 → 新 token 通过响应头下发
response.getHeaders().set("Authorization", newToken);
```

**收益**：用户活跃使用期间不会被迫重新登录。

---

## 四、建议吸收（P1 — 中等改造）

### 4.1 全局异常处理精细化

**nova-mall 做法**：`@ControllerAdvice` 按优先级捕获 8 种异常：

```java
@ExceptionHandler(ApiException.class)         // 业务异常
@ExceptionHandler(SQLSyntaxErrorException.class) // SQL 语法错误
@ExceptionHandler(SQLException.class)         // 数据库约束冲突
@ExceptionHandler(NullPointerException.class) // 空指针
@ExceptionHandler(RuntimeException.class)     // 运行时异常
@ExceptionHandler(MethodArgumentNotValidException.class) // 参数校验
@ExceptionHandler(BindException.class)        // 参数绑定
@ExceptionHandler(Exception.class)            // 兜底
```

**hmall 现状**：`CommonExceptionAdvice` 只有 6 种且缺少 `SQLException` 和 `NullPointerException`。

**建议吸收**：补充 `SQLException`（SQL 约束冲突 → 业务异常提示）和 `NullPointerException`（空指针 → 明确错误信息）处理。

---

### 4.2 工厂+策略模式（可插拔扩展）

**nova-mall 做法**：通过 YAML 配置驱动策略工厂

```yaml
# 新增促销策略只需改配置，不需要改代码
promotion:
  types:
    DISCOUNT: discountPromotionStrategy      # 折扣
    FULL_REDUCTION: fullReductionStrategy    # 满减
    BUY_ONE_GET_ONE: buyOneGetOneStrategy    # 买一送一
    GIFT: giftPromotionStrategy              # 赠品
    COUPON: couponPromotionStrategy          # 优惠券
```

```java
@Component
public class PromotionStrategyFactory implements ApplicationContextAware {
    private final Map<String, PromotionStrategy> strategyMap = new ConcurrentHashMap<>();
    
    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        // 从 YAML 读取 key→beanName 映射，从 Spring 容器获取策略 Bean
    }
    
    public PromotionStrategy getStrategy(String type) {
        return strategyMap.get(type);
    }
}
```

**hmall 可应用场景**：

| 模块 | 现有逻辑 | 改造后 |
|------|---------|--------|
| 支付服务 | `if (type==1) alipay else if (type==2) wechat` | `PaymentStrategyFactory.get(type).pay()` |
| 营销模块 | 无 | 预留促销策略扩展点 |

**收益**：新增支付渠道/促销类型只需实现接口 + 配置一行，不改核心代码。

---

### 4.3 Redis 异常隔离切面

**nova-mall 做法**：`RedisCacheAspect` — `@Aspect @Order(2)` 拦截所有 `*CacheService.*` 方法

```java
@Around("execution(* com.su.mall..*CacheService.*(..))")
public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
    try {
        return joinPoint.proceed();
    } catch (Throwable e) {
        // 检查方法是否有 @CacheException 注解
        if (hasAnnotation(joinPoint, CacheException.class)) {
            throw e;  // 关键业务（验证码等）：必须抛异常
        }
        return null;  // 普通缓存：降级走 DB
    }
}
```

**hmall 可应用场景**：购物车缓存、商品详情缓存等 Redis 依赖场景。

**收益**：Redis 宕机时关键业务继续抛异常（用户感知），非关键业务自动降级到 DB（用户无感知）。

---

### 4.4 Controller 返回 Base Entity 改为 DTO

**nova-mall 做法**：Controller 层只暴露 DTO/VO，不暴露数据库实体。

**hmall 问题**：`CartController.updateCart()` 直接接收 `Cart` 实体，暴露了数据库字段。

**建议**：统一为 DTO 参数 + VO 返回值。

---

### 4.5 Redis Lua 原子操作

**nova-mall 做法**：秒杀库存用 Lua 脚本原子预减

```java
String lua = "if (redis.call('exists', KEYS[1]) == 1) then " +
    "local stock = tonumber(redis.call('get', KEYS[1])); " +
    "if (stock <= 0) then return -1 end; " +
    "redis.call('decr', KEYS[1]); return stock - 1; end; return -2;";
```

**hmall 可应用场景**：`item-service` 的库存扣减目前无乐观锁：

```java
// 当前：直接 update，无并发保护
@Update("update item set stock = stock - #{num} where id = #{itemId}")
void updateStock(@Param("itemId") Long itemId, @Param("num") Integer num);
```

**建议**：热点商品库存走 Redis Lua 预减 + 异步 MySQL 同步。

---

## 五、可参考吸收（P2 — 长期规划）

### 5.1 测试体系建设

**nova-mall**：12 个测试类，覆盖 Mapper / Service / 责任链 / 秒杀

**hmall**：仅 4 个测试类，零覆盖率

**建议**：
1. 从 `OrderServiceImpl.createOrder()` 开始写第一个单元测试
2. 为 `format.ts` 纯函数补测试（前端已有 mem 记录）
3. 使用 `@SpringBootTest` + `@Transactional` + `@Rollback` 模式

---

### 5.2 动态 RBAC 权限

**nova-mall 做法**：`DynamicAuthorizationManager` 运行时从数据库加载 URL-角色映射，`AntPathMatcher` 匹配。

**hmall 现状**：无角色概念，只有 userId。管理后台 `/admin` 理论上无后端权限校验。

**建议**：长期可建立 `ums_resource` / `ums_role` / `ums_role_resource_relation` 三表 + `DynamicSecurityMetadataSource`，实现 URL 级权限控制。

---

### 5.3 文档体系建设

**nova-mall**：API.md（4000+行完整接口文档）、DEPLOYMENT.md（含 2核4G 精细内存分配方案）、DESIGN_PATTERN_REFACTOR.md

**hmall**：仅 2 个 docs 文件

**建议**：
1. 编写 hmall API 文档（基于 Knife4j 导出 + 人工补充）
2. 编写部署手册（含 Docker Compose + 各服务 JVM 参数推荐）
3. 维护架构决策记录（ADR）

---

### 5.4 搜索模块解耦安全

**nova-mall 做法**：search 模块**不依赖 security**，只依赖 common。

**hmall 现状**：search-service 作为普通微服务通过 gateway 路由，认证在网关层已处理，本身也是独立服务。

**评价**：hmall 的搜索模块在这方面已经做得更好——通过网关统一认证，search-service 自身无需认证逻辑。

---

### 5.5 JDK 17 升级

**nova-mall**：JDK 17 + `record` 类型（如 `CustomConfigAttribute`）

**hmall**：JDK 11

**建议**：长期升级到 JDK 17 以使用 `record`、`sealed class`、`Pattern Matching` 等语言特性简化 DTO/VO 定义。

---

## 六、吸收优先级路线图

```
第一优先级（本周可完成）：
├── 3.1 统一 API 响应格式 ────── 改 3 个 Controller 返回值
├── 3.2 业务错误码枚举 ─────────── 新建 BizCode + 替换 RuntimeException
├── 3.3 请求日志 AOP ───────────── 新建 WebLogAspect（零侵入）
└── 3.4 Token 续期 ─────────────── 改 AuthGlobalFilter

第二优先级（本月可完成）：
├── 4.1 异常处理精细化 ─────────── 补 SQLException / NPE 处理
├── 4.4 Controller DTO 化 ──────── 改 CartController
└── 4.5 Redis Lua 库存预减 ─────── 热点商品库存保护

第三优先级（下季度）：
├── 4.2 工厂+策略模式 ──────────── 支付/促销可插拔
├── 5.1 测试体系 ───────────────── 从核心逻辑开始补测
├── 5.3 文档体系 ───────────────── API.md / 部署手册
└── 5.2 动态 RBAC ──────────────── 管理后台权限

长期规划：
├── 5.5 JDK 17 升级
├── 4.3 Redis 异常隔离切面
└── 责任链模式（订单生命周期管理）
```

---

## 七、不推荐吸收的部分

以下 nova-mall 的优秀实践**不适用于 hmall 的架构**：

| nova-mall 做法 | 不推荐原因 |
|----------------|-----------|
| 模块化单体架构 | hmall 已是微服务，回退为单体是倒退 |
| Handler 非 Spring Bean（手动 new） | hmall 的 Service 已是 Spring Bean，无需此模式 |
| RestHighLevelClient | 已废弃，hmall 若有 ES 升级应直接用 `ElasticsearchClient` |
| 无 Feign / 无 Seata / 无 Sentinel | hmall 已使用 Spring Cloud 全家桶，天然优于 nova-mall |

---

## 八、总结

nova-mall 最值得 hmall 吸收的核心优势是 **工程化基础设施**：

1. **统一响应 + 错误码** → 前后端协作更规范
2. **AOP 日志** → 线上排查效率飞跃
3. **Token 续期** → 用户体验提升
4. **策略模式** → 扩展性更好
5. **测试 + 文档** → 可维护性提升

这些改进均属于**非侵入式增强**，不会破坏 hmall 现有的微服务架构、分布式事务和消息体系。
