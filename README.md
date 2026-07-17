# 微服务架构电商平台（枫叶商城）

基于微服务架构实现的电商商城项目，整合服务注册发现、远程调用、网关路由、服务保护、分布式事务、异步通信、搜索引擎、**高并发秒杀**、**RBAC 管理后台**、**AI Agent 智能助手（DeepAgent + LangGraph）**等核心技术，构建高可用、易扩展的分布式电商系统。

## 项目架构

项目采用**微服务架构**，后端拆分为 9 个独立微服务模块 + 1 个公共模块 + 1 个 API 定义模块 + 1 个 AI Agent 服务，前端基于 Vue 3 + Vite + TypeScript + Element Plus + TailwindCSS SPA 开发，实现业务解耦与模块化开发：

```
┌──────────────────────────────────────────────────────────────────┐
│                        前端 (Vue 3 + Vite)                        │
│  hmall-frontend (:5173)  C端商城 + 管理后台 + AI助手聊天           │
│  TailwindCSS + Element Plus + LangGraph SDK                       │
└────────────┬─────────────────────────────┬───────────────────────┘
             │ /api → proxy_pass           │ @langchain/langgraph-sdk
             ▼                             ▼ (SSE 流式)
┌────────────────────────┐    ┌────────────────────────────────────┐
│   hm-gateway (:8080)   │    │  hmall-agent (:8090)               │
│   JWT 认证 + 限流      │    │  LangGraph Server (Python)          │
│   动态路由              │    │  CustomerAgent + AdminAgent         │
└──┬──┬──┬──┬──┬──┬──┬──┘    │  三级路由: 正则→interrupt→LLM      │
   │  │  │  │  │  │  │       │  通义千问 qwen-turbo                │
   │  │  │  │  │  │  │       └──────────┬─────────────────────────┘
   ▼  ▼  ▼  ▼  ▼  ▼  ▼                  │ httpx → Gateway
┌────┐┌────┐┌────┐┌────┐┌────┐┌────┐   │
│item││cart││user││trade││pay ││search│  │
│svc ││svc ││svc ││-svc ││-svc││-svc │  │
│:8081│:8082│:8084│:8085││:8083│:8089│  │
└────┘└────┘└────┘└────┘└────┘└────┘   │
   │    │     │     │     │     │       │
   └────┴─────┴─────┴─────┴─────┘       │
          ↕ Nacos / Sentinel / Seata    │
          ↕ MySQL / Redis / ES / RabbitMQ
                                         │
┌────────┐                               │
│ admin  │◄──────────────────────────────┘
│ -svc   │
│ :8090  │
└────────┘
```

### 模块职责

| 模块 | 端口 | 说明 |
|------|------|------|
| **hm-gateway** | 8080 | API 网关，统一入口、JWT 认证、滑动窗口限流、动态路由 |
| **admin-service** | 8090 | 管理后台微服务，RBAC 权限控制、管理员认证、秒杀活动管理 |
| **hmall-agent** | 8090 | AI Agent 智能助手（Python LangGraph Server），CustomerAgent + AdminAgent，三级路由架构 |
| **hm-service** | 8080 | 单体聚合服务（BFF），直接面向 C 端 |
| **item-service** | 8081 | 商品微服务，商品 CRUD、库存管理、个性化推荐接口 |
| **cart-service** | 8082 | 购物车微服务 |
| **user-service** | 8084 | 用户微服务，注册登录、余额管理 |
| **trade-service** | 8085 | 交易微服务，订单创建与管理、**秒杀核心引擎**、已购商品聚合 |
| **pay-service** | 8083 | 支付微服务，支付订单处理 |
| **search-service** | 8089 | 搜索微服务，基于 ES 的商品搜索、推荐召回 |
| **hm-common** | — | 公共模块（异常、工具类、拦截器、MyBatis 配置、Redis 封装、Lua 脚本） |
| **hm-api** | — | Feign 接口定义模块（跨服务 DTO + Client 接口） |

## 核心技术栈

### 后端技术

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 11 |
| 基础框架 | Spring Boot | 2.7.12 |
| 微服务框架 | Spring Cloud + Alibaba | 2021.0.3 / 2021.0.4.0 |
| 服务注册与配置 | Nacos | Client 内置 |
| 远程调用 | OpenFeign + LoadBalancer | 内置 |
| 服务网关 | Spring Cloud Gateway | 内置 |
| 熔断限流 | Sentinel | Client 内置 |
| 分布式事务 | Seata | Client 内置 |
| ORM | MyBatis Plus | 3.4.3 |
| 数据库 | MySQL | 8.0 |
| 缓存 | Redis（Lettuce + StringRedisTemplate） | — |
| 搜索引擎 | Elasticsearch | 7.12.1 |
| 消息队列 | RabbitMQ | — |
| API 文档 | Knife4j (Swagger) | 4.1.0 |
| 认证 | JWT + RSA | Spring Security Crypto |
| 工具库 | Hutool | 5.8.11 |
| 构建工具 | Maven | 3.8+ |

### 前端技术（Vue 3 SPA）

| 类别 | 技术 | 说明 |
|------|------|------|
| 框架 | Vue 3 + Composition API | TypeScript |
| 构建工具 | Vite 5 | 开发服务器 + HMR 热更新 |
| UI 组件库 | Element Plus | Vue 3 版本，管理后台核心 UI |
| CSS 框架 | TailwindCSS 3 | 原子化 CSS，C 端商城样式 |
| HTTP 客户端 | Axios | 请求/响应拦截器，双端实例隔离（C 端/管理端） |
| 路由 | Vue Router 4 | Hash 模式，双端权限守卫 |
| 状态管理 | Pinia | Composition API 风格 |
| 图表 | ECharts 6 | 管理后台 Dashboard 数据看板 |
| 图标 | @element-plus/icons-vue + lucide-vue-next | — |
| AI 通信 | @langchain/langgraph-sdk ^1.x | Agent 流式对话（SSE） |
| Markdown | marked | Agent 消息 Markdown 渲染 |
| 二维码 | qrcode | 二维码生成 |

### AI Agent 技术

| 类别 | 技术 | 版本/说明 |
|------|------|-----------|
| 语言 | Python | ≥ 3.12 |
| 包管理 | uv | pip 替代，快速依赖解析 |
| Agent 框架 | DeepAgents (`create_agent`) | ≥ 0.5.9，声明式 Agent 定义 |
| 图执行引擎 | LangGraph (`langgraph-cli[inmem]`) | ≥ 0.4.26，图执行 + API Server + Checkpoint |
| LLM | 通义千问 qwen-turbo | DashScope OpenAI 兼容接口 |
| Checkpoint 后端 | Redis (db=1 隔离) | LangGraph 对话持久化 + interrupt 恢复 |
| HTTP 客户端 | httpx | 异步调用 Java 后端 API |
| 前端通信 | LangGraph SDK (SSE 流式) | context-only 模式 |



## 核心功能与技术实现

### 1. 高并发秒杀系统

完整的三层防超卖架构：

```
用户请求 → Gateway 滑动窗口限流（Redis ZSET Lua）
         → SeckillServiceImpl Lua 原子预减（限购+库存合一）
         → SeckillOrderListener MQ 异步下单（MySQL FOR UPDATE 行锁兜底）
```

- **第一层**：Gateway `RateLimitFilter`，每用户 5 秒内仅允许 1 次请求
- **第二层**：`seckill_deduct.lua` 将限购检查（HINCRBY）和库存预减（DECRBY）合并为单次原子操作，99% 无效请求在 Redis 层返回
- **第三层**：`SELECT ... FOR UPDATE` 行锁 + `UPDATE WHERE stock >= quantity` 双重保证，MySQL 行锁最终兜底
- **异步削峰**：Lua 预减成功后通过 RabbitMQ 异步创建订单
- **超时回补**：30 分钟延迟消息 + 定时任务兜底，自动回补 Redis + MySQL 库存和限购额度
- **活动预热**：定时任务每分钟扫描即将开始的场次，提前将库存写入 Redis（SETNX 防覆盖）
- **管理后台**：活动/场次/商品关联完整 CRUD、手动预热、秒杀订单查询、每日库存快照

### 2. RBAC 管理后台

独立的 B 端管理微服务（`admin-service`），与 C 端认证体系隔离：

- **RBAC 权限模型**：管理员 → 角色 → 菜单 + 资源（URL 权限点）
- **菜单动态加载**：前端侧边栏从数据库 `menu` 表加载，支持多级菜单
- **JWT 认证隔离**：管理后台使用独立的 admin JWT 密钥，与 C 端用户 token 互不干扰
- **Feign 代理模式**：admin-service 通过 Feign 调用下游微服务管理接口，获取业务数据
- **管理功能**：商品管理、订单管理、用户管理、秒杀管理（活动/场次/商品 CRUD + 订单查询 + 库存状态）
- **前端**：Vue 3 SPA（`/admin/**` 路由），复用 Element Plus 组件库

### 3. 微服务远程调用

基于 **OpenFeign** 实现微服务间同步远程调用：

- 购物车查询时，远程调用商品微服务获取商品详情
- 秒杀下单时，远程调用商品微服务获取商品信息
- 自动集成负载均衡，基于 Nacos 注册信息动态选择实例
- Sentinel + Feign 集成，实现调用降级与熔断

### 4. 服务注册与配置中心

采用 **Nacos** 作为一站式服务治理平台：

- **服务注册与发现**：所有微服务启动后注册至 Nacos，自动发现与健康检测
- **配置管理中心**：集中管理微服务配置，支持配置热更新
- **共享配置**：抽取 JDBC、日志、Swagger、Seata、RabbitMQ 等公共配置
- **动态路由**：Gateway 路由配置托管于 Nacos `gateway-routes.json`，支持无重启热更新

### 5. 统一网关路由与限流

通过 **hm-gateway** 实现前端请求统一入口与管控：

- 请求路由：转发前端请求至对应微服务
- JWT 认证：基于 RSA 非对称密钥的用户/管理员认证
- 用户透传：解析 JWT 令牌，将用户信息附加至请求头传递给下游微服务
- **滑动窗口限流**：基于 Redis ZSET + Lua 实现精确滑动窗口，对秒杀接口按用户维度限流
- fail-open 降级：Redis 不可用时限流器自动放行，由后端兜底

### 6. 服务保护与容错

集成 **Sentinel** 实现微服务高可用保障：

- 请求限流：控制接口访问频率
- 线程隔离：避免单个服务故障耗尽系统资源
- 服务熔断：应对服务依赖故障，快速失败保证稳定性

### 7. 分布式事务解决方案

引入 **Seata** 解决微服务分布式事务：

- 保证跨多个微服务的数据库操作原子性
- 支付、订单、库存等核心业务数据一致性保障

### 8. 同步 / 异步调用优化

核心业务同步、非核心业务异步：

- **同步调用**：基于 OpenFeign，适用于商品查询、订单创建等
- **异步调用**：基于 **RabbitMQ** 消息队列，支付成功/秒杀下单异步通知订单服务
- **延迟消息**：30 分钟超时自动取消未支付订单（秒杀/普通订单均支持）
- **本地消息表**：保证 MQ 消息可靠投递

### 9. 高性能搜索服务

使用 **Elasticsearch** 替代数据库模糊搜索：

- 支持品牌、分类、价格多维度过滤查询
- 基于聚合功能实时统计筛选条件
- 自定义算分函数，广告商品权重加权
- 商品数据变更通过 RabbitMQ 异步同步至 ES

### 10. AI Agent 智能助手

基于 **DeepAgent + LangGraph** 构建的双端 AI 助手，支持自然语言交互完成购物全流程和运营管理：

```
用户消息（C端 / 管理后台）
  │
  ├─ L1: RegexShortcutMiddleware (<5ms)
  │   ├── 拦截 "查看订单" / "秒杀活动" / "运营日报" 等高频指令
  │   ├── 直接调用对应工具 + 格式化输出
  │   └── 零 LLM 成本，拦截 80%+ 请求
  │
  ├─ L2: LangGraph interrupt() (多轮交互 / 二次确认)
  │   ├── 秒杀下单 → interrupt 商品确认 → 用户"确认" → 下单
  │   ├── 地址修改 → interrupt 选字段 → 输新值 → 执行
  │   └── 危险操作：取消订单/清空购物车/确认收货 需二次确认
  │
  └─ L3: LLM 兜底 (~2s，qwen-turbo)
      ├── 闲聊 / 复杂问题 / L1/L2 未命中
      └── LLM 自主选择工具调用
```

**CustomerAgent（C 端客服助手，20 个工具）**：

| 能力域 | 核心功能 |
|--------|---------|
| 商品浏览 | ES 搜索、商品详情、分页浏览 |
| 秒杀 | 活动列表、商品详情、秒杀下单（二次确认） |
| 购物车 | 查看、加购、改量、删除（确认）、清空（确认） |
| 订单 | 列表、详情、取消（确认）、确认收货（确认） |
| 地址 | 列表、新增（多轮收集）、修改（多轮交互） |
| **个性化推荐** | 猜你喜欢、看了又看、购物车凑单、偏好分析 |

**AdminAgent（管理助手，10 个只读工具 + 运营日报）**：

| 能力域 | 核心功能 |
|--------|---------|
| 商品管理 | 分页查询、商品详情 |
| 订单管理 | 分页查询（状态/时间筛选）、订单详情 |
| 秒杀管理 | 活动/场次/商品关联/订单查询、库存快照 |
| 用户管理 | C 端用户列表、用户详情 |
| 运营日报 | 多工具并发编排，格式化日报输出 |

**个性化推荐能力（Phase 1 已实现）**：

- **三种触发模式**：用户主动请求（L1 正则 <5ms）/ Agent 主动 Upsell / 偏好驱动推荐
- **后端管线**：Feign 聚合已购商品偏好 → search-service ES 召回 → 按销量排序
- **降级保障**：trade-service 不可用 → ES 全局热销 / search-service 不可用 → MySQL 热销兜底 / Agent 降级搜索提示
- **推荐闭环**：推荐 → 查看详情 → 加购 → 凑单推荐 → 用户反馈 → 再推荐

## 项目亮点

1. **三层防超卖秒杀系统**：Gateway 限流 + Redis Lua 原子预减 + MySQL 行锁兜底，完整高并发解决方案
2. **完整微服务技术栈落地**，覆盖服务治理全流程（注册发现、配置管理、网关路由、限流熔断、分布式事务）
3. **RBAC 管理后台**：独立微服务 + 动态菜单 + URL 权限控制，管理员与 C 端用户认证隔离
4. **AI Agent 智能助手**：基于 DeepAgent + LangGraph 的三级路由架构（正则→interrupt→LLM），双端对话式交互，支持个性化推荐
5. **对话式推荐**：Agent 理解用户偏好、生成推荐理由、形成推荐闭环，降级链路完整
6. **同步 + 异步结合**的服务调用方案，平衡业务一致性与系统性能
7. **动态配置、动态路由**，适配生产环境灵活变更需求
8. **多层服务保护机制**（Sentinel 限流熔断 + Redis 库存原子操作 + MySQL 行锁），保障系统高可用

---

## 环境准备

### 必需基础设施

| 组件 | 版本要求 | 用途 |
|------|---------|------|
| **JDK** | 11+ | Java 运行环境 |
| **Maven** | 3.8+ | 项目构建 |
| **Node.js** | 18+ | 前端 Vue 3 SPA 开发 |
| **MySQL** | 8.0 | 数据持久化 |
| **Nacos** | 2.x | 服务注册 & 配置中心 |
| **Redis** | 6.x+ | 缓存 + 秒杀库存 + 限流 |
| **Elasticsearch** | 7.12.x | 全文搜索引擎 |
| **RabbitMQ** | 3.x | 异步消息队列 + 延迟消息 |
> **注意**：项目中的默认配置指向 `192.168.100.128`（Linux 虚拟机 IP），请根据实际环境修改。

---

## 后端使用指南

### 1. 后端目录结构

```
hmall/
├── pom.xml                  # 父 POM，定义版本与模块
├── hm-common/               # 公共模块
│   ├── domain/              #   R、PageDTO、PageQuery 等通用模型
│   ├── exception/           #   异常体系
│   ├── service/             #   RedisService、RedisLockUtil
│   ├── utils/               #   RateLimitUtil、LuaScriptLoader
│   └── resources/lua/       #   seckill_deduct.lua、sliding_window_rate_limit.lua
├── hm-api/                  # Feign 接口定义模块
│   ├── client/              #   ItemClient、TradeClient、PayClient、UserClient、CartClient
│   ├── dto/                 #   跨服务 DTO（ItemDTO、OrderDetailDTO 等）
│   └── config/              #   DefaultFeignConfig
├── hm-gateway/              # Spring Cloud Gateway 网关服务（:8080）
│   ├── filters/             #   AuthGlobalFilter、RateLimitFilter
│   ├── routers/             #   DynamicRouteLoader（Nacos 动态路由）
│   └── config/              #   RateLimitProperties
├── admin-service/           # 管理后台微服务（:8090）
│   ├── controller/          #   ProductAdmin、OrderAdmin、SeckillAdmin 等
│   ├── feign/               #   ItemFeignClient、TradeFeignClient（代理下游服务）
│   ├── domain/              #   管理员、角色、菜单、资源 PO
│   └── resources/           #   hm-admin-schema.sql、seckill-admin-menu.sql
├── hm-service/              # 单体聚合 BFF 服务（:8080）
├── item-service/            # 商品微服务（:8081）
├── cart-service/            # 购物车微服务（:8082）
├── user-service/            # 用户微服务（:8084）
├── trade-service/           # 交易微服务 + 秒杀核心引擎（:8085）
│   ├── controller/          #   OrderController、SeckillController
│   ├── service/impl/        #   SeckillServiceImpl（秒杀核心逻辑）
│   ├── Listener/            #   SeckillOrderListener、paySuccessListener
│   ├── task/                #   SeckillPreheatTask、SeckillTimeoutTask
│   ├── domain/po/           #   SeckillPromotion、SeckillSession 等 5 张秒杀表
│   └── resources/db/        #   V2__seckill_tables.sql
├── pay-service/             # 支付微服务（:8083）
└── search-service/          # 搜索微服务（:8089）
```

### 2. 数据库初始化

项目需要在 MySQL 中创建以下数据库：

```sql
-- C 端业务库
CREATE DATABASE IF NOT EXISTS hmall   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hm_item DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hm_cart DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hm_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hm_trade DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hm_pay  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 管理后台库
CREATE DATABASE IF NOT EXISTS `hm-admin` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

建表方式：

- **方式一（推荐）**：启动各微服务时自动建表（MyBatis Plus DDL 或 Flyway 迁移）
- **方式二**：根据各服务 `domain/po` 包下的实体类手动编写 DDL
- **管理后台**：执行 `admin-service/src/main/resources/hm-admin-schema.sql` 初始化 RBAC 表和管理员账号
- **秒杀模块**：`trade-service/src/main/resources/db/migration/V2__seckill_tables.sql` 自动创建 5 张秒杀表
- **秒杀管理菜单**：执行 `admin-service/src/main/resources/seckill-admin-menu.sql` 注册秒杀管理菜单和权限点

核心业务表包括：

| 服务 | 数据库 | 核心表 |
|------|--------|--------|
| item-service | hm_item | `item` |
| user-service | hm_user | `user`、`address` |
| cart-service | hm_cart | `cart` |
| trade-service | hm_trade | `order`、`order_detail`、`order_logistics`、`seckill_promotion`、`seckill_session`、`seckill_product_relation`、`seckill_daily_stock`、`seckill_order` |
| pay-service | hm_pay | `pay_order` |
| admin-service | hm-admin | `admin_user`、`role`、`menu`、`resource`、`role_menu_rel`、`role_resource_rel` |

### 3. Nacos 配置

启动 Nacos 后，需要在 Nacos 控制台创建以下配置：

**共享配置（Data ID）**：

| Data ID | 用途 | 说明 |
|---------|------|------|
| `shared-jdbc.yaml` | 数据库连接 | MySQL 连接信息 |
| `shared-log.yaml` | 日志配置 | Logback 日志级别与格式 |
| `shared-swagger.yaml` | API 文档 | Knife4j / Swagger 开关 |
| `shared-seata.yaml` | 分布式事务 | Seata Server 连接信息 |
| `shared-rabbitmq.yaml` | 消息队列 | RabbitMQ 连接信息 |

**动态路由配置**：

| Data ID | 用途 |
|---------|------|
| `gateway-routes.json` | Gateway 动态路由规则（含 `/seckill/**` → trade-service、`/recommend/**` → item-service 路由） |

示例 `shared-jdbc.yaml`：
```yaml
spring:
  datasource:
    url: jdbc:mysql://你的IP:3306/hm-item?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
    username: root
    password: 你的密码
    driver-class-name: com.mysql.cj.jdbc.Driver
```

### 4. 修改配置文件

各服务的配置文件位于 `{service}/src/main/resources/` 目录下：

| 文件 | 用途 |
|------|------|
| `application.yaml` | 本地默认配置（端口、应用名等） |
| `application-local.yaml` | 本地开发 Profile 配置 |
| `application-dev.yaml` | Docker 开发 Profile 配置 |
| `bootstrap.yml` | Nacos 连接地址与共享配置引用 |

**需要修改的要点**：

1. **Nacos 地址**：编辑每个服务的 `bootstrap.yml`，将 `spring.cloud.nacos.server-addr` 改为实际 Nacos 地址
2. **MySQL / Redis / ES / RabbitMQ**：修改 Nacos 共享配置或各服务的 `application-*.yaml` 中的连接地址
3. **JWT 证书**：`hmall.jks` 密钥文件已放在 `hm-gateway` 的资源目录中，密码为 `hmall123`
4. **Gateway 路由**：确保 Nacos `gateway-routes.json` 包含所有必要路由（特别是 `/seckill/**` → `trade-service`、`/recommend/**` → `item-service` 和 `/admin/**` → `admin-service`）

### 5. 构建项目

```bash
# 在 hmall/ 根目录下执行
mvn clean install -DskipTests
```

### 6. 启动服务

建议按以下顺序启动微服务：

```bash
# 1. 基础服务（商品、用户）
mvn spring-boot:run -pl item-service
mvn spring-boot:run -pl user-service

# 2. 依赖基础服务的模块
mvn spring-boot:run -pl cart-service     # 依赖商品
mvn spring-boot:run -pl trade-service    # 依赖商品、购物车、用户
mvn spring-boot:run -pl pay-service      # 依赖交易、用户

# 3. 搜索服务
mvn spring-boot:run -pl search-service

# 4. 管理后台
mvn spring-boot:run -pl admin-service

# 5. 网关（最后启动，负责路由转发）
mvn spring-boot:run -pl hm-gateway

# 6. 聚合 BFF 服务（注意：与网关共用 8080 端口，需错开启动）
mvn spring-boot:run -pl hm-service
```

也可以进入各模块目录单独启动：
```bash
cd item-service && mvn spring-boot:run
```

### 7. 服务间调用关系

**同步调用（Feign）**：
```
cart-service  ──▶  item-service   (查询商品、扣减库存)
trade-service ──▶  item-service   (查询商品信息)
trade-service ──▶  cart-service   (删除购物车)
trade-service ──▶  user-service   (扣减余额)
pay-service   ──▶  trade-service  (标记支付成功、同步秒杀订单状态)
pay-service   ──▶  user-service   (扣减余额)
admin-service ──▶  item-service   (商品管理)
admin-service ──▶  trade-service  (订单管理、秒杀管理)
admin-service ──▶  user-service   (用户管理)
item-service  ──▶  trade-service  (推荐：获取已购商品)
item-service  ──▶  search-service (推荐：ES 召回)
```

**异步通信（RabbitMQ）**：
```
pay-service    ──▶ pay.direct ──▶ trade-service  (支付成功 → 标记订单已支付)
trade-service  ──▶ seckill.topic ──▶ trade-service (秒杀下单 → 异步扣库存创建订单)
trade-service  ──▶ trade.delay.direct ──▶ trade-service (30min 延迟 → 超时取消)
item-service   ──▶ search.exchange ──▶ search-service (商品变更 → 同步 ES)
```

### 8. 访问 API 文档

各微服务启动后，可通过 Knife4j 访问 API 文档：

- 商品服务：`http://localhost:8081/doc.html`
- 交易服务：`http://localhost:8085/doc.html`
- 管理后台：`http://localhost:8090/doc.html`
- 网关聚合：`http://localhost:8080/doc.html`

---

## 前端使用指南

### 1. 前端目录结构

```
hmall-frontend/                     # Vue 3 + Vite SPA（推荐开发模式）
├── index.html                      # HTML 入口
├── vite.config.ts                  # Vite 配置 + /api 代理到 :8080
├── tailwind.config.js              # TailwindCSS 配置
├── src/
│   ├── api/                        # Axios 请求模块（双端实例隔离）
│   │   ├── index.ts                #   C 端 axios 实例（token 管理 + 续期 + 401 跳转）
│   │   ├── admin.ts                #   管理后台 axios 实例（admin-token + 解包 R<T>）
│   │   ├── seckill.ts              #   秒杀 API + 轮询工具
│   │   ├── item.ts / cart.ts / order.ts / pay.ts / address.ts / user.ts
│   │   └── admin/                  #   管理后台 API 模块（product/order/member/seckill/auth/...）
│   ├── views/
│   │   ├── portal/                 # C 端页面（15 个）
│   │   │   ├── HomePage、SearchPage、ProductDetail、LoginPage
│   │   │   ├── CartPage、OrderConfirm、OrderList、PayPage、PaySuccess
│   │   │   ├── AddressList、UserProfile、ChatPage（AI助手）
│   │   │   └── SeckillList、SeckillDetail
│   │   └── admin/                  # 管理后台页面（12 个）
│   │       ├── Dashboard、ItemManage、OrderManage、OrderDetail
│   │       ├── UserManage、SeckillManage、ChatPage（AI助手）
│   │       └── system/（AdminUserManage/RoleManage/MenuManage/ResourceManage）
│   ├── components/chat/            # Agent 聊天组件
│   │   ├── ChatPanel.vue           #   可复用全页对话组件
│   │   ├── ChatWidget.vue          #   C 端浮动入口（路由跳转）
│   │   ├── AdminChat.vue           #   管理端 header 入口
│   │   ├── MessageBubble.vue       #   消息气泡（Markdown 渲染）
│   │   └── InterruptActions.vue    #   interrupt 确认卡片
│   ├── composables/
│   │   └── useLangGraph.ts         # LangGraph SDK 封装（对话/中断/会话管理）
│   ├── router/index.ts             # 路由配置（22 条，双端权限守卫）
│   ├── stores/                     # Pinia 状态管理（user/cart/admin）
│   ├── types/                      # TypeScript 类型定义（39+ 接口）
│   ├── directives/
│   │   └── permission.ts           # v-permission 按钮级权限指令
│   └── utils/
│       └── format.ts               # 工具函数（价格/日期格式化）
```

### hmall-agent 目录结构

```
hmall-agent/                        # AI Agent 服务（Python）
├── start_server.py                 # 启动入口（LangGraph Server :8090）
├── graph.json                      # Agent 图注册（customer_agent / admin_agent）
├── pyproject.toml                  # uv 依赖声明
├── .env.example                    # 环境变量模板
├── src/
│   ├── agents/                     # Agent 定义
│   │   ├── customer/               #   CustomerAgent（20 工具 + 6 Skills）
│   │   └── admin/                  #   AdminAgent（10 只读工具 + 3 Skills）
│   ├── middleware/                  # 中间件层（Auth/Permission/Regex/RAG）
│   ├── tools/formatters.py         # 格式化函数（17 个）
│   ├── gateway/                    # HTTP 客户端（httpx → Gateway）
│   ├── core/                       # 配置 + LLM 实例 + Redis Checkpoint
│   └── workspace/                  # Skills 规范文件（9 个 SKILL.md）
└── knowledge_base/                 # RAG 知识库（预留）
```


### 2. 环境变量配置

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `VITE_AGENT_URL` | LangGraph Agent 服务地址 | `http://localhost:8090` |

### 3. Vue 3 SPA 开发（推荐）

```bash
cd hmall-frontend

# 安装依赖
npm install

# 启动开发服务器（默认 :5173）
npm run dev

# 构建生产版本
npm run build
```

Vite 自动代理 `/api` 请求到 `http://localhost:8080`（hm-gateway）。

### 4. 访问前端页面

| 入口 | 地址 | 说明 |
|------|------|------|
| **C 端商城** | `http://localhost:5173/#/portal/home` | 商品浏览、购物车、下单、秒杀 |
| **秒杀专场** | `http://localhost:5173/#/portal/seckill` | 秒杀活动列表和秒杀详情 |
| **C 端 AI 助手** | `http://localhost:5173/#/portal/chat` | 客服对话，支持自然语言完成购物全流程 |
| **管理后台** | `http://localhost:5173/#/admin/dashboard` | 数据概览、商品/订单/用户/秒杀管理 |
| **管理端 AI 助手** | `http://localhost:5173/#/admin/chat` | 运营助手，订单查询、秒杀管理、运营日报 |

---

## 完整启动顺序

1. **启动基础设施**：MySQL、Nacos、Redis、Elasticsearch、RabbitMQ
2. **初始化数据库**：创建 7 个数据库，执行 schema SQL，插入秒杀管理菜单
3. **配置 Nacos**：导入共享配置（JDBC、日志、Swagger、Seata、RabbitMQ）+ `gateway-routes.json`（含 `/recommend/**` 路由）
4. **构建后端**：`mvn clean install -DskipTests`
5. **启动微服务**：`item → user → cart → trade → pay → search → admin → gateway`
6. **启动 Agent 服务**：
   ```bash
   cd hmall-agent
   cp .env.example .env
   # 编辑 .env，填入 DASHSCOPE_API_KEY 和 Redis 连接信息
   uv sync
   uv run python start_server.py    # LangGraph Server (:8090)
   ```
7. **启动前端**：`cd hmall-frontend && npm run dev`
8. **初始化数据**：插入秒杀活动/场次/商品关联数据（或通过管理后台创建）
9. **访问页面**：浏览器打开 `http://localhost:5173`

### 服务端口总览

| 服务 | 端口 | 说明 |
|------|------|------|
| hm-gateway | 8080 | API 网关（路由 + 认证 + 限流） |
| hm-service | 8080 | 聚合 BFF（与网关共用端口，需错开启动） |
| item-service | 8081 | 商品微服务 |
| cart-service | 8082 | 购物车微服务 |
| pay-service | 8083 | 支付微服务 |
| user-service | 8084 | 用户微服务 |
| trade-service | 8085 | 交易微服务 |
| search-service | 8089 | 搜索微服务 |
| admin-service | 8090 | 管理后台微服务 |
| hmall-agent | 8090 | AI Agent 服务（与 admin-service 共用端口，需错开启动） |

---

## 常见问题

<details>
<summary><b>Q: 启动微服务时报 Nacos 连接失败？</b></summary>

检查 `bootstrap.yml` 中 `spring.cloud.nacos.server-addr` 是否为正确的 Nacos 地址。默认值为 `192.168.100.128:8848`。
</details>

<details>
<summary><b>Q: hm-service 和 hm-gateway 端口冲突？</b></summary>

两个服务默认都使用 8080 端口。建议错开启动，或将其中一个的端口改为其他值（在 `application.yaml` 中修改 `server.port`）。
</details>

<details>
<summary><b>Q: 前端页面加载后秒杀接口报 404？</b></summary>

1. 确认 Nacos 中 `gateway-routes.json` 已配置 `/seckill/**` → `trade-service` 路由
2. 确认 `trade-service` 已启动，SeckillController 正常运行
3. 检查 Vite 代理配置（`vite.config.ts`）是否将 `/api` 代理到 `localhost:8080`
</details>

<details>
<summary><b>Q: 管理后台登录后侧边栏没有"秒杀管理"菜单？</b></summary>

执行 `admin-service/src/main/resources/seckill-admin-menu.sql` 插入菜单和权限数据。如果已执行但看不到，退出重新登录以刷新菜单缓存。
</details>

<details>
<summary><b>Q: 秒杀下单后商品详情 name 为空？</b></summary>

确认 `hm-api` 模块已 `mvn install` 到本地仓库。v1.1 已修复 `ItemClient.queryItemById` 的 Feign 路径匹配问题（`@RequestParam` → `@PathVariable`）。
</details>

<details>
<summary><b>Q: Elasticsearch 连接失败？</b></summary>

检查 ES 是否已启动，默认连接地址为 `192.168.100.128:9200`。可在各服务的 `application-local.yaml` 中修改 `spring.elasticsearch.uris`。
</details>

<details>
<summary><b>Q: 如何切换环境（local / dev）？</b></summary>

在 `bootstrap.yml` 中修改 `spring.profiles.active` 的值（`local` 或 `dev`）。`local` 使用 `application-local.yaml`，`dev` 使用 `application-dev.yaml`（同时从 Nacos 拉取共享配置）。
</details>

<details>
<summary><b>Q: AI 助手聊天页面无响应 / 消息发不出去？</b></summary>

1. 确认 Agent 服务已启动：`http://localhost:8090/ok` → `{"ok": true}`
2. 确认前端环境变量 `VITE_AGENT_URL` 指向正确的 Agent 地址（默认 `http://localhost:8090`）
3. 确认 `.env` 中的 `DASHSCOPE_API_KEY` 已填入有效密钥
4. Agent 服务与 admin-service 共用 8090 端口，需错开启动
</details>

<details>
<summary><b>Q: Agent 个性化推荐不可用？</b></summary>

1. 确认 Nacos `gateway-routes.json` 已配置 `/recommend/**` → `lb://item-service` 路由
2. 确认 `/recommend/**` **未加入** `hm.auth.excludePaths`（推荐需要用户认证）
3. 确认 trade-service 和 search-service 均已启动（偏好聚合和 ES 召回依赖）
4. 确认 ES 索引 `items` 中有商品数据
</details>

<details>
<summary><b>Q: admin-service 和 hmall-agent 端口冲突？</b></summary>

两个服务默认都使用 8090 端口。建议错开启动，或将其中一个的端口改为其他值：
- Agent：在 `.env` 中修改 `AGENT_PORT`
- admin-service：在 `application.yaml` 中修改 `server.port`
</details>

---

本项目完整实现了微服务架构电商商城，涵盖高并发秒杀系统、RBAC 管理后台、AI Agent 智能助手、对话式个性化推荐、服务发现与治理、分布式事务、异步通信、高性能搜索等核心技术，是企业级微服务电商项目的典型实践。
