# hmall 管理后台项目说明文档

> 本文档面向项目理解与快速上手，描述各功能的**设计动机**、**实现思路**和**最终效果**，不涉及具体代码引用与实现细节。

---

## 目录

1. [项目定位](#1-项目定位)
2. [总体架构](#2-总体架构)
3. [RBAC 认证授权体系](#3-rbac-认证授权体系)
4. [商品管理](#4-商品管理)
5. [订单管理](#5-订单管理)
6. [C 端用户管理](#6-c-端用户管理)
7. [秒杀管理](#7-秒杀管理)
8. [数据概览](#8-数据概览)
9. [AI 聊天助手](#9-ai-聊天助手)
10. [前端设计](#10-前端设计)
11. [认证隔离设计](#11-认证隔离设计)
12. [配置体系](#12-配置体系)
13. [启动流程](#13-启动流程)

---

## 1. 项目定位

hmall 管理后台是为枫叶商城运营人员提供的 **B 端 Web 管理系统**，通过独立微服务 `admin-service` 与前端管理界面，实现对商品、订单、用户、秒杀等业务数据的管理，以及管理员的 RBAC 权限控制。

它能够：

- 让运营人员通过 Web 界面管理商品上下架、库存调整、批量操作
- 处理订单的发货、关闭、备注等管理操作
- 查看和管理 C 端注册用户的信息和状态
- 通过角色和权限精细化控制每个管理员能访问哪些页面和接口
- 通过内嵌 AI 聊天助手，用自然语言查询运营数据、生成日报

管理后台与 C 端商城**共用同一前端工程**（`hmall-frontend`），通过路由前缀 `/admin/**` 和 `/portal/**` 区分 B 端和 C 端页面，**共用同一后端微服务集群**，通过独立的 `admin-service` 和独立 JWT 密钥完成认证隔离。

---

## 2. 总体架构

### 部署拓扑

```
运营商人员（浏览器）
  │
  ▼
hmall-frontend (Vue 3 + Vite 5)
  │  /api/admin/** 请求
  ▼
hm-gateway (:8080)  —  AuthGlobalFilter（admin JWT 白名单）
  │
  ├── admin-service (:8090)  —  RBAC 认证 + 业务管理入口
  │   ├── 管理员/角色/菜单/资源 → 直连 hm-admin MySQL
  │   ├── 商品管理 → Feign → item-service (:8081) → hm-item MySQL
  │   ├── 订单管理 → Feign → trade-service (:8085) → hm-trade MySQL
  │   └── 用户管理 → Feign → user-service (:8084) → hm-user MySQL
  │
  └── Redis  —  Token 黑名单 / 权限缓存

基础设施：Nacos（注册发现 + 配置中心）/ RabbitMQ（C 端消息队列）
```

### 模块边界

管理后台严格遵循"**入口统一、数据不冗余**"原则：

| 数据域 | 所有权 | 管理后台如何操作 |
|--------|--------|-----------------|
| RBAC 数据（管理员/角色/菜单/资源） | `admin-service` 独立库 `hm-admin` | 直连 MySQL CRUD |
| 商品数据 | `item-service` 的 `hm-item` 库 | Feign 委托，不冗余存储 |
| 订单数据 | `trade-service` 的 `hm-trade` 库 | Feign 委托，不冗余存储 |
| 用户数据 | `user-service` 的 `hm-user` 库 | Feign 委托，不冗余存储 |
| 秒杀数据 | `seckill-service` | Feign 委托 |

### 关键设计决策

**为什么 admin-service 是独立微服务而不是在 Gateway 加权限？**
Gateway 适合做粗粒度拦截（登录校验、白名单放行），但管理后台需要动态权限校验——"用户 A 能访问 `/admin/orders` 但不能访问 `/admin/products`"。这种细粒度控制需要查数据库（角色→菜单→资源），放到 Gateway 会让网关变重。独立 `admin-service` 自带 `AdminAuthInterceptor`，拦截器内完成 JWT 校验 + 权限匹配，对 Gateway 透明。

---

## 3. RBAC 认证授权体系

### 设计动机

管理后台有多个运营角色——商品管理员、订单管理员、超级管理员——各自职责不同，不能所有人都有全部权限。需要一个灵活的权限系统，让管理员可以动态分配角色和权限，而不需要改代码。

### 实现思路

采用经典的 **RBAC（Role-Based Access Control）** 模型，五大实体三层关系：

```
管理员 (admin_user)
  │  N:N (admin_user_role_rel)
  ▼
角色 (role)  —  "商品管理员"、"订单管理员"、"超级管理员"
  │  N:N (role_menu_rel)          │  N:N (role_resource_rel)
  ▼                                ▼
菜单 (menu)                      资源 (resource + resource_category)
  "商品列表"、"订单管理"...        "GET:/admin/product/**"
```

**认证流程**：
1. 管理员通过 `POST /admin/login` 登录，`admin-service` 的 `AdminAuthService` 用 BCrypt 验证密码
2. 验证通过后生成 JWT（包含 `userId`、`username`、`type: ADMIN`），RSA 非对称加密（独立 `admin.jks` 密钥库）
3. 前端将 Token 存入 `sessionStorage['admin-token']`，后续所有请求通过 `api/admin.ts`（独立 axios 实例）自动附带

**授权流程**：
1. `AdminAuthInterceptor` 拦截所有 `/admin/**` 请求（白名单除外）
2. 从 JWT 解析出 `userId` → 查用户的角色 → 查角色拥有的资源
3. 将当前请求路径与资源列表做 Ant 路径匹配 → 匹配成功放行，失败返回 403

**Token 续期**：JWT 有效期 2 小时，最后 30 分钟为"续期窗口"。如果管理员在续期窗口内发起请求，系统自动颁发新 Token（无需重新登录），实现"活跃用户永不下线"。

**Token 黑名单（登出）**：管理员退出登录时，JWT 被加入 Redis 黑名单（TTL 等于 Token 剩余有效期）。后续请求的拦截器先检查黑名单，命中则拒绝——实现即时登出生效。

### 最终效果

- 超级管理员可以创建"商品管理员"角色，分配商品管理菜单和接口权限
- 新入职的运营人员注册后，只需分配"商品管理员"角色即可立即开始工作
- 修改角色权限后，该角色下的所有管理员下次请求立即生效（拦截器每次实时查库）
- 退出登录后 Token 立即失效（Redis 黑名单），不会被恶意复用
- 页面按钮也受权限控制：没有"删除商品"权限的管理员，页面上连"删除"按钮都看不见（`v-permission` 指令）

### 核心数据表

| 表 | 说明 |
|----|------|
| `admin_user` | 管理员（username / password(BCrypt) / status / login_time） |
| `role` | 角色（name / admin_count / status / sort） |
| `menu` | 菜单树（parent_id / title / level / name / icon / hidden） |
| `resource` | 接口权限（name / url(Ant 路径) / category_id） |
| `resource_category` | 权限分类 |

---

## 4. 商品管理

### 设计动机

C 端商品数据存储在 `item-service` 的 `hm-item` 库中。管理后台需要对其进行 CRUD 操作，但不能绕过 `item-service` 直连数据库——这违背微服务的"数据自治"原则。

### 实现思路

`admin-service` 的 `ProductAdminController` 不直接操作 `hm-item` 数据库，而是通过 OpenFeign 调用 `item-service` 的 `ItemController` 接口。

```
admin-service                        item-service
ProductAdminController               ItemController
  │  ProductAdminService               │  IItemService
  │  → Feign (ItemClient)              │  → ItemServiceImpl
  │                                    │  → ItemMapper
  ▼  HTTP 调用                         ▼  MyBatis Plus
  item-service:8081                    hm-item MySQL
```

关键 Feign 接口（`ItemClient`）：
- `GET /items/page` — 分页查询（支持 name/category/brand/status 多条件筛选）
- `GET /items/{id}` — 商品详情
- `POST /items` — 新增商品
- `PUT /items/{id}` — 修改商品
- `PUT /items/status/{id}/{status}` — 上下架状态切换
- `PUT /items/stock/{id}/{stock}` — 库存调整
- `DELETE /items` — 删除（支持批量）
- `PUT /items/publish/batch` — 批量上下架

### 最终效果

- 商品列表页支持多条件搜索（名称/类目/品牌/状态），分页展示
- 支持单个或批量上下架、删除
- 新增/编辑商品时填写名称、价格、类目、品牌、图片、库存等信息
- 库存不足时管理员可手动调整库存数量
- 所有操作通过 Feign 透明转发，管理后台不持有商品数据

---

## 5. 订单管理

### 设计动机

运营人员需要处理订单的发货、退款、异常关闭等操作。订单数据存储在 `trade-service` 的 `hm-trade` 库，同样通过 Feign 委托操作。

### 实现思路

`OrderAdminController` 通过 `TradeClient`（Feign）调用 `trade-service` 的订单管理接口：

```
admin-service                        trade-service
OrderAdminController                 OrderController
  │  OrderAdminService                 │  IOrderService
  │  → Feign (TradeClient)             │
  ▼                                    ▼
  trade-service:8085                   hm-trade MySQL
```

支持的操作：
- **分页查询**：按订单号/状态/时间范围/用户 ID 多条件筛选
- **订单详情**：查看完整订单信息（含商品明细、收货地址、支付状态）
- **批量发货**：选择多个"已支付"状态的订单，批量填入物流单号
- **批量关闭**：关闭异常订单
- **修改备注**：为订单添加/修改运营备注

### 最终效果

- 订单列表按时间倒序排列，支持状态筛选（待支付/已支付/已发货/已完成/已取消）
- 点击订单进入详情页，展示完整订单信息
- 已支付的订单可"发货"操作，弹窗填入物流公司和单号
- 异常订单可"关闭"并填写关闭原因

---

## 6. C 端用户管理

### 设计动机

运营人员需要查看和管理 C 端注册用户——查看用户详情、封禁/解封用户、调整用户余额。

### 实现思路

`MemberAdminController` 通过 `UserClient`（Feign）调用 `user-service`：

- **分页查询**：按用户名/手机号/状态筛选
- **用户详情**：查看完整用户信息
- **状态切换**：封禁（status=0）或解封（status=1）用户
- **余额调整**：修改用户账户余额

### 最终效果

- 用户列表页支持搜索和分页
- 点击用户进入详情页
- 问题用户可即时封禁，下线后无法登录
- 客诉场景下可手动调整用户余额

---

## 7. 秒杀管理

### 设计动机

秒杀活动是电商核心玩法之一。运营人员需要查看秒杀活动的参与情况、订单数据、库存剩余。

### 实现思路

`SeckillAdminController` 通过 Feign 调用 `seckill-service`，提供秒杀活动、秒杀商品关联、秒杀订单、秒杀库存的多维度查询。

### 最终效果

- 查看所有秒杀活动列表和状态
- 查看秒杀商品关联关系和库存剩余
- 查看秒杀订单的参与情况

---

## 8. 数据概览

### 设计动机

运营人员登录后需要一个"仪表盘"——一眼看到今日订单数、新增用户、待处理订单、库存预警等关键指标。好的数据概览能帮助运营快速定位问题。

### 实现思路

前端 `Dashboard.vue` 页面通过 ECharts（`vue-echarts`）渲染统计卡片和图表。部分数据通过后端接口实时查询，部分图表数据使用前端 Mock 数据（标注为演示用）。

### 最终效果

- 顶部 4 个统计卡片：今日订单数 / 新增用户 / 待处理订单 / 商品总数
- 中间折线图：近 7 天订单趋势
- 底部饼图：各状态订单占比

---

## 9. AI 聊天助手

### 设计动机

运营人员在管理后台常常需要查询数据——"昨天卖了多少订单"、"秒杀活动 RPM00171 的数据怎么样"。每次都要在不同页面之间搜索、筛选、导出，操作繁琐。

### 实现思路

管理后台通过 LangGraph SDK 连接 hmall Agent 的 `admin_agent`。前端 `ChatPage.vue` 内嵌聊天界面，支持流式 SSE 输出。AdminAgent 提供 11 个只读工具（查询商品、订单、用户、秒杀数据），外加运营日报生成。

```
管理后台 ChatPage
  │  LangGraph SDK (SSE)
  ▼
hmall Agent (:8090) → AdminAgent
  │  HTTP (httpx)
  ▼
hm-gateway → admin-service / item-service / trade-service
```

### 最终效果

- 管理后台右上角点击 AI 图标打开聊天面板
- 输入"生成今天的运营日报"→ Agent 并发查询多维度数据，返回结构化 Markdown 日报
- 输入"查看订单 1005 的详情"→ Agent 返回订单完整信息
- 支持流式输出，打字机效果逐字展示

---

## 10. 前端设计

### 设计动机

管理后台需要区别于 C 端商城的视觉风格——更专业、更高效、更注重数据展示。同时因为共用前端工程，需要保证 B/C 端的路由、状态、样式完全隔离。

### 实现思路

**技术栈**：Vue 3（Composition API + `<script setup>`）+ TypeScript + Element Plus + Tailwind CSS + ECharts

**路由隔离**：通过 Vue Router 的 Hash 模式区分：
- `#/admin/**` → 管理后台页面（含 `beforeEnter` 守卫检查 admin-token）
- `#/portal/**` → C 端商城页面

**状态隔离**：独立的 Pinia store（`stores/admin.ts`），独立的 axios 实例（`api/admin.ts`），独立的 `sessionStorage` key（`admin-token`）。

**布局**：`AdminLayout.vue` 实现经典的后台三栏布局——左侧动态菜单树（从后端获取，含图标和折叠）、顶部导航栏（用户信息 + 退出登录）、右侧内容区。

**按钮权限**：自定义 `v-permission` 指令，根据当前管理员的资源权限列表，动态显示/隐藏页面按钮。没有"删除"权限的管理员，连按钮都看不到。

**12 个管理页面**：

| 路由 | 页面 | 功能 |
|------|------|------|
| `/admin/login` | `AdminLogin.vue` | 管理员登录 |
| `/admin/dashboard` | `Dashboard.vue` | 数据概览 |
| `/admin/items` | `ItemManage.vue` | 商品管理 |
| `/admin/orders` | `OrderManage.vue` | 订单列表 |
| `/admin/orders/:id` | `OrderDetail.vue` | 订单详情 |
| `/admin/users` | `UserManage.vue` | 用户管理 |
| `/admin/seckill` | `SeckillManage.vue` | 秒杀管理 |
| `/admin/system/admin` | `AdminUserManage.vue` | 管理员管理 |
| `/admin/system/role` | `RoleManage.vue` | 角色管理 |
| `/admin/system/menu` | `MenuManage.vue` | 菜单管理 |
| `/admin/system/resource` | `ResourceManage.vue` | 资源/权限管理 |
| `/admin/chat` | `ChatPage.vue` | AI 聊天 |

### 最终效果

- 登录页采用居中卡片式设计，与 C 端商城登录页风格区分
- 后台主框架左侧菜单可折叠，子菜单支持展开/收缩
- 数据表格统一使用 Element Plus 的 `el-table`，支持排序、筛选、分页
- ECharts 图表自适应窗口大小，暗色/亮色主题跟随系统
- 操作按钮通过权限指令动态显示，无权限的管理员看到的是干净的界面

---

## 11. 认证隔离设计

### 设计动机

管理后台和 C 端商城的用户体系完全不同——管理员是内部运营人员，C 端用户是消费者。两套认证绝不能混淆。

### 实现思路

从**密钥、Token、数据库、中间件**四个维度彻底隔离：

| 维度 | C 端 | B 端（管理后台） |
|------|------|-----------------|
| 密钥库 | `hmall.jks` | `admin.jks`（独立生成） |
| JWT 类型标记 | 无 | `type: ADMIN` |
| 前端存储 | `sessionStorage.token` | `sessionStorage.admin-token` |
| HTTP 客户端 | `api/index.ts` | `api/admin.ts`（独立实例） |
| 登录接口 | `POST /users/login` | `POST /admin/login` |
| 用户表 | `hm-user.user` | `hm-admin.admin_user` |
| 密码加密 | BCrypt | BCrypt（同算法不同表） |
| 拦截器 | `hm-gateway AuthGlobalFilter` | `admin-service AdminAuthInterceptor` |

**为什么 admin 不走 Gateway 统一认证？**
C 端的 Gateway 认证只需校验"这个 Token 是否有效"——因为 C 端用户没有角色权限概念，登录后所有功能可用。但管理后台需要"这个管理员有权访问这个接口吗"——需要查数据库做 RBAC 匹配。这种逻辑放到 `admin-service` 内部更解耦：Gateway 只需放行 `/admin/**` 请求（不做认证），认证授权由 `admin-service` 自己完成。

### 最终效果

- 管理员登录需独立账号密码，C 端用户无法访问管理后台
- 即使 C 端 JWT 泄露，也无法通过管理后台认证（不同的密钥库）
- 前端路由守卫检查 `admin-token`，未登录自动跳转登录页

---

## 12. 配置体系

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| `admin-service` | **8090** | 管理后台微服务 |
| `hm-gateway` | 8080 | API 网关 |
| `hmall-agent` | 8090 | AI Agent 服务（不同进程，同端口） |

### 关键环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `REDIS_HOST` | `192.168.100.128` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | `123456` | Redis 密码 |
| `ADMIN_JWT_KEYSTORE_PASSWORD` | `admin123` | JWT 密钥库密码 |

### JWT 配置

| 配置项 | 值 | 说明 |
|--------|-----|------|
| 密钥库路径 | `classpath:admin.jks` | RSA 2048 位 |
| 密钥别名 | `admin` | 密钥别名 |
| Token 有效期 | 2 小时 | 支持 30 分钟续期窗口 |

### 白名单路径（无需认证）

`/admin/login`、`/admin/register`、Swagger 文档路径（`/doc.html`、`/webjars/**`、`/swagger-resources/**`、`/v2/api-docs`、`/v3/api-docs`）、`/favicon.ico`

### 数据库

| 数据库 | 使用者 | 表数量 |
|--------|--------|--------|
| `hm-admin` | admin-service | 8 张（RBAC 专有） |
| `hm-item` | item-service | 商品相关 |
| `hm-trade` | trade-service | 订单相关 |
| `hm-user` | user-service | C 端用户相关 |

### Nacos 共享配置

- `shared-jdbc.yaml` — 共享数据源配置
- `shared-log.yaml` — 共享日志配置
- `shared-swagger.yaml` — Swagger API 文档配置

---

## 13. 启动流程

### 环境要求

- Java 8+
- MySQL 8.0+
- Redis
- Nacos 2.x
- Node.js 18+（前端）

### 初始化数据库

```sql
-- 执行管理后台 SQL 建表脚本
source hmall/hmall-admin/src/main/resources/hm-admin-schema.sql
```

初始数据：
- 超级管理员：`admin` / `admin123`
- 角色"超级管理员"拥有全部菜单和资源权限

### 启动后端微服务

```bash
# 按依赖顺序启动
# 1. 基础设施：Nacos / Redis / MySQL / RabbitMQ
# 2. 基础服务：item-service → user-service → trade-service → seckill-service
# 3. 网关：hm-gateway
# 4. 管理后台：admin-service
```

### 启动前端

```bash
cd hmall-frontend
npm install
npm run dev
```

访问 `http://localhost:5173/#/admin/login`，使用 `admin` / `admin123` 登录。

### 启动 AI 助手（可选）

```bash
cd hmall-agent
uv sync
cp .env.example .env
uv run python start_server.py
```

启动后在管理后台左侧菜单或右上角点击 AI 图标，即可使用 AdminAgent 聊天助手。

### API 文档

管理后台 Swagger 文档：`http://localhost:8090/doc.html`

提供所有管理接口的在线调试，含参数说明和返回示例。

---

*本文档基于 hmall 管理后台 v1.0 编写，各功能的最新状态以实际代码为准。*
