# 微服务架构电商平台（黑马商城）

基于微服务架构实现的电商商城项目，整合服务注册发现、远程调用、网关路由、服务保护、分布式事务、异步通信、搜索引擎等核心技术，构建高可用、易扩展的分布式电商系统。

## 项目架构

项目采用**微服务架构**，后端拆分为 7 个独立微服务模块 + 1 个聚合 BFF 服务，前端由 Nginx 托管 3 个独立子应用，实现业务解耦与模块化开发：

```
┌──────────────────────────────────────────────────────┐
│                     Nginx (前端)                       │
│  :18080 → hmall-portal (用户端商城)                    │
│  :18081 → hmall-admin (管理后台)                      │
│  :18082 → hm-refresh-admin (综合管理后台)              │
└─────────────┬────────────────────────────────────────┘
              │ /api → proxy_pass
              ▼
┌──────────────────────────────────────────────────────┐
│              后端服务 (Spring Cloud)                    │
│  ┌───────────┐  ┌───────────┐  ┌───────────┐         │
│  │ hm-gateway│  │ hm-service│  │cart-service│         │
│  │   8080    │  │   8080*   │  │   8082    │         │
│  └───────────┘  └───────────┘  └───────────┘         │
│  ┌───────────┐  ┌───────────┐  ┌───────────┐         │
│  │item-service│  │user-service│  │trade-servic│        │
│  │   8081    │  │   8083    │  │   8084    │         │
│  └───────────┘  └───────────┘  └───────────┘         │
│  ┌───────────┐  ┌───────────┐                         │
│  │ pay-service│  │search-servi│                        │
│  │   8085    │  │   8089    │                         │
│  └───────────┘  └───────────┘                         │
│         ↕ Nacos / Sentinel / Seata                    │
│         ↕ MySQL / Redis / ES / RabbitMQ               │
└──────────────────────────────────────────────────────┘
```

### 模块职责

| 模块 | 端口 | 说明 |
|------|------|------|
| **hm-gateway** | 8080 | API 网关，统一入口、JWT 认证、请求路由 |
| **hm-service** | 8080 | 单体聚合服务（BFF），管理后台直接入口 |
| **item-service** | 8081 | 商品微服务，商品 CRUD、库存管理 |
| **cart-service** | 8082 | 购物车微服务 |
| **user-service** | 8083 | 用户微服务，注册登录、余额管理 |
| **trade-service** | 8084 | 交易微服务，订单创建与管理 |
| **pay-service** | 8085 | 支付微服务，支付订单处理 |
| **search-service** | 8089 | 搜索微服务，基于 ES 的商品搜索 |

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
| 缓存 | Redis | — |
| 搜索引擎 | Elasticsearch | 7.12.1 |
| 消息队列 | RabbitMQ | — |
| API 文档 | Knife4j (Swagger) | 4.1.0 |
| 认证 | JWT + RSA | Spring Security Crypto |
| 工具库 | Hutool | 5.8.11 |
| 构建工具 | Maven | 3.8+ |

### 前端技术

| 类别 | 技术 | 说明 |
|------|------|------|
| 框架 | Vue.js 2.x | CDN 方式引入，多页面应用 |
| UI 组件库 | Element UI | CDN 方式引入 |
| HTTP 客户端 | Axios | 本地引入 |
| 图表库 | ECharts | 综合管理后台使用 |
| Web 服务器 | Nginx | 静态文件托管 + API 反向代理 |

## 核心技术与实现

### 1. 微服务远程调用

基于 **OpenFeign** 实现微服务间同步远程调用：

- 购物车查询时，远程调用商品微服务获取商品详情
- 自动集成负载均衡，基于 Nacos 注册信息动态选择实例
- Sentinel + Feign 集成，实现调用降级与熔断

### 2. 服务注册与配置中心

采用 **Nacos** 作为一站式服务治理平台：

- **服务注册与发现**：所有微服务启动后注册至 Nacos，自动发现与健康检测
- **配置管理中心**：集中管理微服务配置，支持配置热更新
- **共享配置**：抽取 JDBC、日志、Swagger、Seata、RabbitMQ 等公共配置

### 3. 统一网关路由

通过**网关微服务**实现前端请求统一入口与管控：

- 请求路由：转发前端请求至对应微服务
- 登录认证：基于 **JWT 令牌**实现用户登录校验
- 用户透传：解析 JWT 令牌，将用户信息附加至请求头传递给下游微服务
- 动态路由：路由配置托管于 Nacos，支持无重启更新

### 4. 服务保护与容错

集成 **Sentinel** 实现微服务高可用保障：

- 请求限流：控制接口访问频率
- 线程隔离：避免单个服务故障耗尽系统资源
- 服务熔断：应对服务依赖故障，快速失败保证稳定性

### 5. 分布式事务解决方案

引入 **Seata** 解决微服务分布式事务：

- 保证跨多个微服务的数据库操作原子性
- 支付、订单、库存等核心业务数据一致性保障

### 6. 同步 / 异步调用优化

核心业务同步、非核心业务异步：

- **同步调用**：基于 OpenFeign，适用于商品查询、订单创建等
- **异步调用**：基于 **RabbitMQ** 消息队列，支付成功后异步通知订单服务更新状态

### 7. 高性能搜索服务

使用 **Elasticsearch** 替代数据库模糊搜索：

- 支持品牌、分类、价格多维度过滤查询
- 基于聚合功能实时统计筛选条件
- 自定义算分函数，广告商品权重加权
- 商品数据变更通过 RabbitMQ 异步同步至 ES

## 项目亮点

1. 完整微服务技术栈落地，覆盖服务治理全流程
2. 同步 + 异步结合的服务调用方案，平衡业务一致性与系统性能
3. 动态配置、动态路由，适配生产环境灵活变更需求
4. 多层服务保护机制，保障系统高可用
5. 分布式事务解决方案，解决微服务数据一致性难题
6. 搜索引擎优化，提升电商核心搜索功能体验

---

## 环境准备

### 必需基础设施

| 组件 | 版本要求 | 用途 |
|------|---------|------|
| **JDK** | 11+ | Java 运行环境 |
| **Maven** | 3.8+ | 项目构建 |
| **MySQL** | 8.0 | 数据持久化 |
| **Nacos** | 2.x | 服务注册 & 配置中心 |
| **Redis** | 6.x+ | 缓存 |
| **Elasticsearch** | 7.12.x | 全文搜索引擎 |
| **RabbitMQ** | 3.x | 异步消息队列 |
| **Nginx** | 1.x | 前端静态资源服务 & API 反向代理 |

> **注意**：项目中的默认配置指向 `192.168.100.128`（Linux 虚拟机 IP），请根据实际环境修改。

---

## 后端使用指南

### 1. 后端目录结构

```
hmall/
├── pom.xml                  # 父 POM，定义版本与模块
├── hm-common/               # 公共模块（异常、工具类、拦截器、MyBatis 配置）
├── hm-api/                  # Feign 接口定义模块
│   ├── CartClient.java      #   购物车 Feign 客户端
│   ├── ItemClient.java      #   商品 Feign 客户端（含 Sentinel fallback）
│   ├── PayClient.java       #   支付 Feign 客户端（含 Sentinel fallback）
│   ├── TradeClient.java     #   交易 Feign 客户端
│   └── UserClient.java      #   用户 Feign 客户端
├── hm-gateway/              # Spring Cloud Gateway 网关服务（:8080）
├── hm-service/              # 单体聚合 BFF 服务（:8080）
├── item-service/            # 商品微服务（:8081）
├── cart-service/            # 购物车微服务（:8082）
├── user-service/            # 用户微服务（:8083）
├── trade-service/           # 交易微服务（:8084）
├── pay-service/             # 支付微服务（:8085）
└── search-service/          # 搜索微服务（:8089）
```

### 2. 数据库初始化

项目中没有提供 `.sql` 初始化脚本，需要在 MySQL 中手动创建以下数据库：

```sql
CREATE DATABASE IF NOT EXISTS hmall   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hm_item DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hm_cart DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hm_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hm_trade DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS hm_pay  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

数据库表可通过以下两种方式创建：

- **方式一（推荐）**：启动各微服务前，在配置中开启 MyBatis Plus 自动建表：
  ```yaml
  mybatis-plus:
    global-config:
      db-config:
        table-underline: true
  ```
  然后将各服务对应实体类上的 `@TableName` 注解对应的表手动创建（MyBatis Plus 不会自动 DDL）。

- **方式二**：根据各服务 `domain/po` 包下的实体类，手动编写 DDL 语句建表。核心表包括：
  - `item` — 商品表
  - `user` — 用户表
  - `cart` — 购物车表
  - `order` / `order_detail` / `order_logistics` — 订单相关表
  - `pay_order` — 支付订单表
  - `address` — 地址表

### 3. Nacos 配置

启动 Nacos 后，需要在 Nacos 控制台创建以下共享配置（Data ID）:

| Data ID | 用途 | 说明 |
|---------|------|------|
| `shared-jdbc.yaml` | 数据库连接 | MySQL 连接信息（地址、用户名、密码） |
| `shared-log.yaml` | 日志配置 | Logback 日志级别与格式 |
| `shared-swagger.yaml` | API 文档 | Knife4j / Swagger 开关 |
| `shared-seata.yaml` | 分布式事务 | Seata Server 连接信息 |
| `shared-rabbitmq.yaml` | 消息队列 | RabbitMQ 连接信息 |

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

1. **Nacos 地址**：编辑每个服务的 `bootstrap.yml`，将 `spring.cloud.nacos.server-addr` 改为实际 Nacos 地址。
2. **MySQL / Redis / ES / RabbitMQ**：修改 Nacos 共享配置或各服务的 `application-*.yaml` 中的连接地址。
3. **JWT 证书**：`hmall.jks` 密钥文件已放在 `hm-service` 和 `hm-gateway` 的资源目录中，密码为 `hmall123`。

### 5. 构建项目

在 `hmall/` 根目录下执行：

```bash
# 安装依赖并编译所有模块（跳过测试）
mvn clean install -DskipTests
```

### 6. 启动服务

建议按以下顺序启动微服务：

```bash
# 1. 基础服务（商品、用户）
mvn spring-boot:run -pl item-service
mvn spring-boot:run -pl user-service

# 2. 依赖基础服务的模块（购物车依赖商品服务）
mvn spring-boot:run -pl cart-service

# 3. 交易与支付（依赖商品、购物车、用户）
mvn spring-boot:run -pl trade-service
mvn spring-boot:run -pl pay-service

# 4. 搜索服务
mvn spring-boot:run -pl search-service

# 5. 网关（最后启动，负责路由转发）
mvn spring-boot:run -pl hm-gateway

# 6. 聚合 BFF 服务（注意：与网关共用 8080 端口，需错开启动）
mvn spring-boot:run -pl hm-service
```

也可以进入各模块目录单独启动：

```bash
cd item-service
mvn spring-boot:run
```

### 7. 服务间调用关系

```
cart-service  ──(Feign)──▶  item-service   (查询商品、扣减库存)
trade-service ──(Feign)──▶  cart-service   (删除购物车)
trade-service ──(Feign)──▶  item-service   (扣减/恢复库存)
trade-service ──(Feign)──▶  user-service   (扣减余额)
pay-service   ──(Feign)──▶  trade-service  (标记支付成功)
pay-service   ──(Feign)──▶  user-service   (扣减余额)
```

异步通信（RabbitMQ）：支付成功、商品变更等事件通过消息队列在 `item-service`、`cart-service`、`trade-service`、`pay-service`、`search-service` 之间传递。

### 8. 访问 API 文档

各微服务启动后，可通过 Knife4j 访问 API 文档：

- 商品服务：`http://localhost:8081/doc.html`
- 购物车服务：`http://localhost:8082/doc.html`
- 用户服务：`http://localhost:8083/doc.html`
- 更多请查看各服务 `application.yaml` 中的 `knife4j` 配置。

---

## 前端使用指南

### 1. 前端目录结构

```
hmall-nginx/
├── nginx.exe                      # Nginx Windows 可执行文件
├── conf/
│   └── nginx.conf                 # Nginx 主配置文件
├── html/
│   ├── hmall-portal/              # 【子应用 1】用户端商城（:18080）
│   │   ├── index.html             #   首页
│   │   ├── search.html            #   商品搜索页
│   │   ├── login.html             #   登录页
│   │   ├── cart.html              #   购物车页
│   │   ├── order-confirm.html     #   订单确认页
│   │   ├── pay.html               #   支付页
│   │   └── js/                    #   Vue + Axios + 业务脚本
│   │
│   ├── hmall-admin/               # 【子应用 2】商品/用户管理后台（:18081）
│   │   ├── items.html             #   商品管理页
│   │   ├── users.html             #   用户管理页
│   │   └── js/                    #   Vue + Element UI + Axios
│   │
│   └── hm-refresh-admin/          # 【子应用 3】综合管理后台（:18082）
│       ├── login.html             #   登录页
│       ├── index.html             #   主后台页（含路由）
│       └── js/                    #   Vue + Element UI + ECharts + 自实现路由
└── logs/                          # Nginx 日志
```

### 2. 前端技术说明

三个前端子应用均为**传统多页面应用（MPA）**，采用以下方式组织：

- **Vue.js 2.x**：通过 `<script>` 标签直接引入 `vue.js`（生产版），无需 `npm install`
- **Element UI**：通过 `<script>` 标签直接引入 `element.js` + `element.css`
- **Axios**：本地引入 `axios.min.js`，通过拦截器统一处理 token 携带与 401 跳转
- **无构建步骤**：所有代码为原始 HTML + CSS + JS，无需 webpack/vite 编译

**「无需 npm 安装」** — 所有前端依赖库已内嵌在 `html/*/js/` 目录中，开箱即用。

### 3. 运行前端

#### 方式一：使用项目自带的 Nginx（Windows）

```powershell
# 进入 nginx 目录
cd d:/Code/hmall/hmall-nginx

# 启动 Nginx
.\nginx.exe

# 如需重新加载配置
.\nginx.exe -s reload

# 如需停止
.\nginx.exe -s stop
```

#### 方式二：使用系统安装的 Nginx

```bash
# 将 nginx.conf 复制到你的 Nginx conf 目录，或指定配置启动
nginx -c /path/to/hmall-nginx/conf/nginx.conf
```

### 4. 访问前端页面

启动 Nginx 后，在浏览器中访问以下地址：

| 子应用 | 地址 | 说明 |
|--------|------|------|
| **用户端商城** | `http://localhost:18080` | 商品浏览、搜索、购物车、下单、支付 |
| **商品管理后台** | `http://localhost:18081/items.html` | 商品 CRUD 管理 |
| **用户管理后台** | `http://localhost:18081/users.html` | 用户 CRUD 管理 |
| **综合管理后台** | `http://localhost:18082` | 全套管理功能（自动跳转登录页） |

### 5. Nginx 配置说明

三个前端入口的 API 代理配置（`conf/nginx.conf`）：

```nginx
# 用户端商城 → http://localhost:8080 (hm-service)
server {
    listen 18080;
    location / { root html/hmall-portal; }
    location /api {
        rewrite /api/(.*) /$1 break;
        proxy_pass http://localhost:8080;
    }
}

# 管理后台 → http://localhost:8080 (hm-service)
server {
    listen 18081;
    location / { root html/hmall-admin; }
    location /api {
        rewrite /api/(.*) /$1 break;
        proxy_pass http://localhost:8080;
    }
}

# 综合管理后台 → http://localhost:8080 (hm-service)
server {
    listen 18082;
    location / { root html/hm-refresh-admin; }
    location /api {
        rewrite /api/(.*) /$1 break;
        proxy_pass http://localhost:8080;
    }
}
```

**关键点**：
- 所有 `/api/*` 请求被 `rewrite` 去除 `/api` 前缀后代理到后端 `http://localhost:8080`
- 静态文件由 Nginx 直接返回，不经过后端
- 如需切换后端地址，修改 `proxy_pass` 中的 `http://localhost:8080` 即可

### 6. 跨域处理

Nginx 反向代理已统一处理了跨域问题 — 前端通过 Nginx 同域访问 `/api`，由 Nginx 代理到后端，浏览器不感知后端实际地址。**无需后端单独配置 CORS**。

---

## 完整启动顺序

1. **启动基础设施**：MySQL、Nacos、Redis、Elasticsearch、RabbitMQ
2. **初始化数据库**：创建 6 个数据库，建表
3. **配置 Nacos**：导入共享配置（JDBC、日志、Swagger、Seata、RabbitMQ）
4. **构建后端**：`mvn clean install -DskipTests`
5. **启动微服务**：按 `item → user → cart → trade → pay → search → gateway` 顺序启动
6. **启动 Nginx**：`.\nginx.exe`
7. **访问前端**：浏览器打开 `http://localhost:18080`

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
<summary><b>Q: 前端页面加载后接口报 404？</b></summary>

1. 确认后端 `hm-service` 是否已启动在 `localhost:8080`
2. 确认 Nginx 的 `proxy_pass` 地址是否正确
3. 检查浏览器控制台 Network 面板，查看实际请求路径
</details>

<details>
<summary><b>Q: Elasticsearch 连接失败？</b></summary>

检查 ES 是否已启动，默认连接地址为 `192.168.100.128:9200`。可在各服务的 `application-local.yaml` 中修改 `spring.elasticsearch.uris`。
</details>

<details>
<summary><b>Q: 数据库连接失败？</b></summary>

1. 确认 MySQL 是否已启动
2. 确认已创建所需的 6 个数据库
3. 检查 Nacos 中 `shared-jdbc.yaml` 的连接信息（或 `application-local.yaml` 中的配置）
</details>

<details>
<summary><b>Q: 如何切换环境（local / dev）？</b></summary>

在 `bootstrap.yml` 中修改 `spring.profiles.active` 的值（`local` 或 `dev`）。`local` 使用 `application-local.yaml`，`dev` 使用 `application-dev.yaml`（同时从 Nacos 拉取共享配置）。
</details>

---

本项目完整实现了微服务架构电商商城，从服务拆分、远程调用、服务治理、容错保护到分布式事务、异步通信、高性能搜索，全面覆盖微服务开发核心技术点，是企业级微服务电商项目的典型实践。
