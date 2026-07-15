# hmall 管理后端微服务（admin-service）设计文档

> 版本：v1.0  
> 日期：2026-07-14  
> 参考实现：`nova-mall/nova-mall-admin`（单体管理后台）

---

## 1. 概述

### 1.1 背景与目标

hmall 当前是一套面向 C 端消费者的 Spring Cloud 微服务商城，包含网关（hm-gateway）、商品（item-service）、购物车（cart-service）、用户（user-service）、交易（trade-service）、支付（pay-service）、搜索（search-service）等微服务，以及一个开发期单体服务 hm-service。

目前缺少 **B 端管理后台**，运营人员无法通过 Web 界面管理商品、订单、用户等业务数据。本设计参考 `nova-mall-admin` 的管理后台实现，为 hmall 新增一个 **admin-service 管理后端微服务**，提供管理员认证、RBAC 权限、商品管理、订单管理、用户管理等核心运营能力。

### 1.2 设计原则

| 原则 | 说明 |
|------|------|
| **核心可用** | 不照搬 nova-mall-admin 全部模块（CMS 内容管理、SMS 营销秒杀等暂不实现），聚焦 RBAC + 商品 + 订单 + 用户四大核心 |
| **微服务适配** | nova-mall-admin 是单体应用直连数据库；admin-service 作为微服务注册到 Nacos，业务管理操作通过 Feign 调用下游微服务或直连管理库 |
| **复用现有基建** | 复用 hm-common 的 `R`、`PageDTO`、`PageQuery`、异常体系、UserContext；复用 Nacos 配置中心、Redis、JWT 体系 |
| **权限隔离** | 管理员认证体系与 C 端用户认证体系隔离，使用独立的 admin JWT 密钥和网关路由前缀 |
| **渐进扩展** | 预留营销、内容等模块扩展点，后续按需迭代 |

### 1.3 与 nova-mall-admin 的对比

| 维度 | nova-mall-admin | hmall admin-service |
|------|----------------|---------------------|
| 架构形态 | 单体应用（Spring Boot 3 + jakarta） | 微服务（Spring Boot 2.7 + Spring Cloud 2021） |
| 服务注册 | 无 | Nacos |
| 配置管理 | 本地 application.yml | Nacos 配置中心 + 共享配置 |
| 数据访问 | 直连 mall 单库 | 管理库 hm-admin 直连 + 业务数据通过 Feign 调用下游服务 |
| 认证方式 | JWT（对称密钥）+ Spring Security | JWT（RSA 非对称密钥，独立 keystore）+ 网关过滤器 |
| 权限模型 | RBAC + 动态 URL 权限（UmsResource） | 同（借鉴） |
| API 文档 | SpringDoc OpenAPI 3 | Knife4j（与现有服务统一） |
| 功能范围 | UMS + PMS + OMS + SMS + CMS + 文件上传 | UMS + PMS + OMS + 用户管理（核心子集） |

---

## 2. 整体架构

### 2.1 架构定位

```
                          ┌──────────────────────────────────┐
                          │          前端（Vue 管理后台）       │
                          └──────────────┬───────────────────┘
                                         │ /admin/**
                          ┌──────────────▼───────────────────┐
                          │          hm-gateway (8080)        │
                          │  ┌─────────────────────────────┐  │
                          │  │  AuthGlobalFilter            │  │
                          │  │  - /admin/login 白名单放行     │  │
                          │  │  - /admin/** 校验 admin JWT   │  │
                          │  │  - 传递 admin-id 请求头        │  │
                          │  └─────────────────────────────┘  │
                          │  DynamicRouteLoader (Nacos 路由)  │
                          └──────┬────────┬──────────┬────────┘
                                 │        │          │
                    ┌────────────▼──┐  ┌──▼────────┐ ┌▼──────────────┐
                    │ admin-service │  │item-service│ │ user-service  │
                    │   (8090)      │  │  (8081)    │ │   (8084)      │
                    │               │  └───────────┘ └───────────────┘
                    │ ┌───────────┐ │        ▲              ▲
                    │ │ RBAC 认证  │ │        │ Feign        │ Feign
                    │ │ 商品管理   │─┼────────┘              │
                    │ │ 订单管理   │─┼───────────────────────┘
                    │ │ 用户管理   │─┼──────┐
                    │ └───────────┘ │      │ Feign
                    │   hm-admin DB │      │
                    └───────────────┘      │
                                 │    ┌────▼─────┐  ┌──────────────┐
                                 │    │trade-svc │  │  pay-service │
                                 │    │ (8085)   │  │   (8083)     │
                                 │    └──────────┘  └──────────────┘
                          ┌──────▼──────┐
                          │   Redis     │  (admin token / 权限缓存)
                          └─────────────┘
                          ┌─────────────┐
                          │   Nacos     │  (注册发现 + 配置中心)
                          └─────────────┘
```

### 2.2 技术栈

| 类别 | 技术选型 | 说明 |
|------|---------|------|
| 基础框架 | Spring Boot 2.7.12 | 与 hmall 父 POM 保持一致 |
| 微服务 | Spring Cloud 2021.0.3 + Spring Cloud Alibaba 2021.0.4.0 | Nacos 注册发现 + 配置 |
| ORM | MyBatis Plus 3.4.3 | 与现有服务统一 |
| 认证 | JWT (RSA, 独立 admin.jks) | 与 C 端 JWT 隔离 |
| 缓存 | Redis (Lettuce) | 管理员 token、权限资源缓存 |
| API 文档 | Knife4j | 与现有服务统一 |
| 服务间调用 | OpenFeign | 调用 item/user/trade/pay 等下游服务 |
| 数据库 | MySQL | 独立管理库 `hm-admin` |

### 2.3 模块划分

admin-service 作为 hmall 的一个新 Maven 子模块，内部包结构如下：

```
admin-service/
├── pom.xml
└── src/main/
    ├── java/com/hmall/admin/
    │   ├── AdminServiceApplication.java      # 启动类
    │   ├── config/
    │   │   ├── AdminJwtProperties.java        # admin JWT 配置
    │   │   ├── AdminAuthProperties.java       # 权限白名单配置
    │   │   ├── SecurityConfig.java            # Spring Security 配置
    │   │   ├── MvcConfig.java                 # MVC + 拦截器注册
    │   │   └── MyBatisPlusConfig.java         # 分页插件等
    │   ├── controller/
    │   │   ├── AdminAuthController.java       # 管理员登录/登出/信息
    │   │   ├── AdminUserController.java       # 管理员 CRUD / 角色分配
    │   │   ├── RoleController.java            # 角色 CRUD / 菜单分配
    │   │   ├── MenuController.java            # 菜单树管理
    │   │   ├── ResourceController.java        # 资源(权限)管理
    │   │   ├── ProductAdminController.java    # 商品管理(委托 item-service)
    │   │   ├── OrderAdminController.java      # 订单管理(委托 trade-service)
    │   │   └── MemberAdminController.java     # C端用户管理(委托 user-service)
    │   ├── service/
    │   │   ├── IAdminAuthService.java
    │   │   ├── IAdminUserService.java
    │   │   ├── IRoleService.java
    │   │   ├── IMenuService.java
    │   │   ├── IResourceService.java
    │   │   └── impl/
    │   ├── mapper/                            # MyBatis Plus Mapper
    │   │   ├── AdminUserMapper.java
    │   │   ├── RoleMapper.java
    │   │   ├── MenuMapper.java
    │   │   ├── ResourceMapper.java
    │   │   └── RelationMapper.java            # 关联表 Mapper
    │   ├── domain/
    │   │   ├── po/                            # 持久化对象
    │   │   │   ├── AdminUser.java
    │   │   │   ├── Role.java
    │   │   │   ├── Menu.java
    │   │   │   ├── Resource.java
    │   │   │   ├── ResourceCategory.java
    │   │   │   └── *Relation.java             # 关联表 PO
    │   │   ├── dto/                           # 数据传输对象
    │   │   └── vo/                            # 视图对象
    │   ├── interceptor/
    │   │   └── AdminAuthInterceptor.java      # 管理员认证拦截器
    │   ├── security/
    │   │   ├── AdminUserDetails.java          # UserDetails 实现
    │   │   └── DynamicSecurityService.java    # 动态权限数据源
    │   └── feign/                             # Feign 客户端(调用下游微服务)
    │       ├── ItemFeignClient.java
    │       ├── UserFeignClient.java
    │       ├── TradeFeignClient.java
    │       └── PayFeignClient.java
    └── resources/
        ├── bootstrap.yml                       # Nacos 配置
        ├── application.yaml                    # 本地配置
        └── admin.jks                           # admin JWT 密钥库
```

---

## 3. 数据库设计

### 3.1 数据库规划

管理后台使用独立数据库 `hm-admin`，仅存储 RBAC 相关表（管理员、角色、菜单、资源及其关联）。业务数据（商品、订单、用户等）不冗余存储，通过 Feign 调用下游微服务管理。

### 3.2 RBAC 表结构（ER 关系）

```
┌──────────────┐     ┌──────────────────────┐     ┌──────────────┐
│  admin_user  │     │ admin_user_role_rel  │     │     role     │
├──────────────┤     ├──────────────────────┤     ├──────────────┤
│ id        PK │◄──┐ │ id                PK │ ┌──►│ id        PK │
│ username     │   │ │ admin_user_id     FK │─┘   │ name         │
│ password     │   │ │ role_id           FK │──┐  │ description  │
│ icon         │   └─┤                      │  │  │ admin_count  │
│ email        │     └──────────────────────┘  │  │ status       │
│ nick_name    │                               │  │ sort         │
│ note         │     ┌──────────────────────┐  │  │ create_time  │
│ status       │     │ role_menu_rel        │  │  └──────────────┘
│ create_time  │     ├──────────────────────┤  │
│ login_time   │     │ id                PK │  │
└──────────────┘     │ role_id           FK │──┘
                     │ menu_id           FK │──┐
                     └──────────────────────┘  │
                                               │
┌──────────────┐     ┌──────────────────────┐  │
│    menu      │     │ role_resource_rel    │  │
├──────────────┤     ├──────────────────────┤  │
│ id        PK │◄──┐ │ id                PK │  │
│ parent_id    │   │ │ role_id           FK │──┘
│ title        │   └─┤ resource_id       FK │──┐
│ level        │     └──────────────────────┘  │
│ sort         │                               │
│ name         │                               │
│ icon         │     ┌──────────────────────┐  │
│ hidden       │     │     resource         │  │
│ create_time  │     ├──────────────────────┤  │
└──────────────┘     │ id                PK │◄─┘
                     │ name                 │
                     │ url                  │
                     │ description          │
                     │ category_id       FK │──┐
                     │ create_time          │  │
                     └──────────────────────┘  │
                                               │
                     ┌──────────────────────┐  │
                     │ resource_category    │  │
                     ├──────────────────────┤  │
                     │ id                PK │◄─┘
                     │ name                 │
                     │ create_time          │
                     └──────────────────────┘
```

### 3.3 建表 SQL

```sql
CREATE DATABASE IF NOT EXISTS `hm-admin` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `hm-admin`;

-- 管理员表
CREATE TABLE `admin_user` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `username`    VARCHAR(64)  NOT NULL COMMENT '用户名',
  `password`    VARCHAR(128) NOT NULL COMMENT '密码(BCrypt)',
  `icon`        VARCHAR(512) DEFAULT NULL COMMENT '头像',
  `email`       VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
  `nick_name`   VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
  `note`        VARCHAR(256) DEFAULT NULL COMMENT '备注',
  `status`      INT          DEFAULT 1    COMMENT '状态: 0禁用 1启用',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `login_time`  DATETIME     DEFAULT NULL COMMENT '最后登录时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台管理员表';

-- 角色表
CREATE TABLE `role` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(64)  NOT NULL COMMENT '角色名称',
  `description` VARCHAR(256) DEFAULT NULL,
  `admin_count` INT          DEFAULT 0  COMMENT '关联管理员数',
  `status`      INT          DEFAULT 1  COMMENT '状态: 0禁用 1启用',
  `sort`        INT          DEFAULT 0,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台角色表';

-- 菜单表
CREATE TABLE `menu` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `parent_id`   BIGINT       DEFAULT 0  COMMENT '父级ID, 0为根',
  `title`       VARCHAR(64)  NOT NULL COMMENT '菜单名称',
  `level`       INT          DEFAULT 1  COMMENT '菜单级数',
  `sort`        INT          DEFAULT 0,
  `name`        VARCHAR(64)  DEFAULT NULL COMMENT '前端路由名称',
  `icon`        VARCHAR(128) DEFAULT NULL COMMENT '前端图标',
  `hidden`      INT          DEFAULT 0  COMMENT '是否隐藏: 0显示 1隐藏',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台菜单表';

-- 资源表(权限点)
CREATE TABLE `resource` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(64)  NOT NULL COMMENT '资源名称',
  `url`         VARCHAR(256) NOT NULL COMMENT '资源URL(Ant路径)',
  `description` VARCHAR(256) DEFAULT NULL,
  `category_id` BIGINT       DEFAULT NULL COMMENT '资源分类ID',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台资源(权限)表';

-- 资源分类表
CREATE TABLE `resource_category` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(64)  NOT NULL,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源分类表';

-- 管理员-角色关联表
CREATE TABLE `admin_user_role_rel` (
  `id`            BIGINT NOT NULL AUTO_INCREMENT,
  `admin_user_id` BIGINT NOT NULL,
  `role_id`       BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role` (`admin_user_id`, `role_id`),
  KEY `idx_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员-角色关联表';

-- 角色-菜单关联表
CREATE TABLE `role_menu_rel` (
  `id`       BIGINT NOT NULL AUTO_INCREMENT,
  `role_id`  BIGINT NOT NULL,
  `menu_id`  BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-菜单关联表';

-- 角色-资源关联表
CREATE TABLE `role_resource_rel` (
  `id`          BIGINT NOT NULL AUTO_INCREMENT,
  `role_id`     BIGINT NOT NULL,
  `resource_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_resource` (`role_id`, `resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-资源关联表';

-- 初始化超级管理员 (密码: admin123, BCrypt加密)
INSERT INTO `admin_user` (`username`, `password`, `nick_name`, `note`, `status`)
VALUES ('admin', '$2a$10$ NzPP9Jxj8bKQ8Q9tQ8Q9tQ8Q9tQ8Q9tQ8Q9tQ8Q9tQ8Q9tQ8Q9tQ', '超级管理员', '系统初始管理员', 1);

INSERT INTO `role` (`name`, `description`, `admin_count`, `status`, `sort`)
VALUES ('超级管理员', '拥有全部权限', 1, 1, 0);

INSERT INTO `admin_user_role_rel` (`admin_user_id`, `role_id`) VALUES (1, 1);
```

> **注意**：上方 BCrypt 密码哈希为占位示例，实际初始化时应使用 `BCryptPasswordEncoder` 生成真实哈希。

---

## 4. 核心模块设计

### 4.1 认证授权模块（RBAC）

#### 4.1.1 认证流程

借鉴 nova-mall-admin 的 JWT 认证 + 动态权限模型，适配 hmall 微服务架构：

```
┌─────────┐  1.POST /admin/login      ┌──────────────┐
│  前端    │ ──────────────────────────►│ admin-service │
│         │  username + password       │              │
│         │ ◄──────────────────────────│ 2.校验密码     │
│         │  3.返回 {token, tokenHead}  │   BCrypt比对  │
└─────────┘                            │ 4.生成admin JWT│
                                       │  5.缓存权限列表 │
┌─────────┐  6.GET /admin/product/list └──────┬───────┘
│  前端    │  Authorization: Bearer xxx       │
│         │ ─────────┐                        │
│         │          ▼                        ▼
│         │ ┌──────────────┐         ┌────────────────┐
│         │ │  hm-gateway   │         │ admin-service  │
│         │ │              │         │                │
│         │ │ 7.校验admin JWT│────────►│ 8.拦截器校验    │
│         │ │  传递admin-id │         │   动态权限       │
│         │ └──────────────┘         │   URL→资源映射  │
│         │                          │ 9.执行业务       │
│         │ ◄────────────────────────│   返回结果       │
└─────────┘                          └────────────────┘
```

#### 4.1.2 JWT 设计

admin-service 使用独立的 JWT 密钥库（`admin.jks`），与 C 端用户 JWT 完全隔离：

```yaml
# admin-service application.yaml
hm:
  admin:
    jwt:
      location: classpath:admin.jks        # 独立密钥库
      alias: admin
      password: ${ADMIN_JWT_KEYSTORE_PASSWORD:admin123}
      tokenTTL: 2h                          # 管理员token有效期较短
    auth:
      excludePaths:                         # 网关白名单(admin路由)
        - /admin/login
        - /admin/register
```

JWT Payload：

```json
{
  "sub": "1",              // admin_user.id
  "username": "admin",     // 管理员用户名
  "type": "ADMIN",         // 标识为管理员token(区别于C端用户)
  "iat": 1730000000,
  "exp": 1730007200
}
```

#### 4.1.3 动态权限模型

借鉴 nova-mall-admin 的 `DynamicSecurityService`，admin-service 实现动态 URL 权限控制：

- **资源表 `resource`** 存储 URL→权限编码的映射（如 `/admin/product/**` → `product:manage`）
- **角色-资源关联表 `role_resource_rel`** 定义角色拥有的权限
- 启动时从数据库全量加载资源列表，缓存到 Redis（key: `admin:resourceList`）
- 每次请求时，`AdminAuthInterceptor` 解析当前管理员角色 → 查询角色资源 → 匹配请求 URL

```java
// DynamicSecurityService - 核心逻辑（参考 nova-mall-admin MallSecurityConfig）
@Bean
public DynamicSecurityService dynamicSecurityService() {
    return () -> {
        Map<String, String> map = new ConcurrentHashMap<>();
        List<Resource> resourceList = resourceService.listAll();
        for (Resource resource : resourceList) {
            map.put(resource.getUrl(), resource.getId() + ":" + resource.getName());
        }
        return map;
    };
}
```

#### 4.1.4 网关集成

在 Nacos 的 `gateway-routes.json` 中新增 admin-service 路由：

```json
[
  {
    "id": "admin-service-route",
    "predicates": [{ "name": "Path", "args": { "pattern": "/admin/**" } }],
    "filters": [{ "name": "RewritePath", "args": { "regexp": "/admin/(?<segment>.*)", "replacement": "/admin/${segment}" } }],
    "uri": "lb://admin-service"
  }
]
```

网关 `AuthGlobalFilter` 修改：对 `/admin/**` 路径使用 admin JWT 密钥校验，白名单放行 `/admin/login`。传递 `admin-id` 请求头给下游。

> **替代方案**：若不想修改网关，admin-service 也可自带认证拦截器（类似 hm-service 的 `LoginInterceptor`），直接在服务内完成 JWT 校验和权限判断。此方案更解耦，推荐初期采用。

### 4.2 商品管理模块

借鉴 nova-mall-admin `PmsProductController`，但商品数据存储在 item-service 的 `hm-item` 库，admin-service 通过 Feign 调用。

#### 4.2.1 核心接口

| 接口 | 方法 | 路径 | 说明 | 对应 nova-mall-admin |
|------|------|------|------|---------------------|
| 分页查询商品 | GET | `/admin/product/list` | 支持按名称/状态/类目筛选 | PmsProductController.getList |
| 商品详情 | GET | `/admin/product/{id}` | 查询商品完整信息 | PmsProductController.getUpdateInfo |
| 新增商品 | POST | `/admin/product` | 创建商品 | PmsProductController.create |
| 更新商品 | PUT | `/admin/product/{id}` | 修改商品信息 | PmsProductController.update |
| 批量上下架 | POST | `/admin/product/publishStatus` | 批量修改上架状态 | PmsProductController.updatePublishStatus |
| 批量删除 | DELETE | `/admin/product` | 逻辑删除商品 | PmsProductController.updateDeleteStatus |
| 库存调整 | PUT | `/admin/product/stock/{id}` | 修改 SKU 库存 | PmsSkuStockController |

#### 4.2.2 实现方式

admin-service 的 `ProductAdminController` 通过 Feign 调用 item-service 已有的 `/items` 接口，并补充管理操作：

```java
@RestController
@RequestMapping("/admin/product")
@RequiredArgsConstructor
public class ProductAdminController {
    private final ItemFeignClient itemFeignClient;

    @GetMapping("/list")
    public R<PageDTO<ItemDTO>> list(ItemPageQuery query) {
        // 委托 item-service 分页查询
        return itemFeignClient.queryItemByPage(query);
    }

    @PostMapping("/publishStatus")
    public R<Void> updatePublishStatus(@RequestParam List<Long> ids,
                                        @RequestParam Integer publishStatus) {
        // 批量调用 item-service 更新状态
        itemFeignClient.batchUpdateStatus(ids, publishStatus);
        return R.ok();
    }
}
```

**item-service 需补充的管理接口**（在现有 `ItemController` 基础上扩展）：

```java
// item-service ItemController 新增
@PutMapping("/batch/status")
public void batchUpdateStatus(@RequestParam List<Long> ids, @RequestParam Integer status);

@PutMapping("/batch/stock")
public void batchUpdateStock(@RequestBody Map<Long, Integer> stockMap);
```

### 4.3 订单管理模块

借鉴 nova-mall-admin `OmsOrderController`，订单数据在 trade-service 的库中，通过 Feign 调用。

#### 4.3.1 核心接口

| 接口 | 方法 | 路径 | 说明 | 对应 nova-mall-admin |
|------|------|------|------|---------------------|
| 订单分页查询 | GET | `/admin/order/list` | 支持按状态/时间/订单号筛选 | OmsOrderController.list |
| 订单详情 | GET | `/admin/order/{id}` | 订单+明细+物流信息 | OmsOrderController.detail |
| 批量发货 | POST | `/admin/order/delivery` | 批量发货并记录物流 | OmsOrderController.delivery |
| 批量关闭 | POST | `/admin/order/close` | 关闭订单 | OmsOrderController.close |
| 修改备注 | POST | `/admin/order/note` | 修改订单备注和状态 | OmsOrderController.updateNote |

#### 4.3.2 订单状态映射

hmall 现有订单状态（`Order.status`）与 nova-mall-admin 的映射关系：

| hmall 状态值 | 含义 | 管理操作 |
|-------------|------|---------|
| 1 | 未付款 | 关闭订单 |
| 2 | 已付款,未发货 | **发货**（管理后台核心操作） |
| 3 | 已发货,未确认 | — |
| 4 | 确认收货,交易成功 | — |
| 5 | 交易取消,订单关闭 | — |
| 6 | 交易结束,已评价 | — |

**trade-service 需补充的管理接口**：

```java
// trade-service OrderController 新增
@GetMapping("/page")                          // 已有，可复用
@PostMapping("/batch/delivery")               // 批量发货
@PostMapping("/batch/close")                  // 批量关闭
@PostMapping("/{id}/note")                    // 修改备注
```

### 4.4 C 端用户管理模块

管理 hmall 现有的 `user` 表（C 端消费者），通过 Feign 调用 user-service。

#### 4.4.1 核心接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 用户分页查询 | GET | `/admin/member/list` | 按用户名/手机号/状态筛选 |
| 用户详情 | GET | `/admin/member/{id}` | 查询用户信息 |
| 修改用户状态 | POST | `/admin/member/status/{id}` | 冻结/解冻用户 |
| 修改用户余额 | POST | `/admin/member/balance/{id}` | 调整用户余额 |

**user-service 需补充的管理接口**：

```java
// user-service UserController 新增
@GetMapping("/page")                           // 分页查询
@GetMapping("/{id}")                           // 查询详情
@PostMapping("/status/{id}")                   // 修改状态
@PostMapping("/balance/{id}")                  // 调整余额
```

---

## 5. 接口设计

### 5.1 管理员认证接口

```
POST   /admin/login              管理员登录
       Request:  { "username": "admin", "password": "admin123" }
       Response: { "code": 200, "data": { "token": "xxx", "tokenHead": "Bearer " } }

POST   /admin/logout             管理员登出
       Header:   Authorization: Bearer xxx
       Response: { "code": 200, "msg": "OK" }

GET    /admin/info               获取当前管理员信息(含菜单和角色)
       Header:   Authorization: Bearer xxx
       Response: { "code": 200, "data": { "username": "admin", "menus": [...], "roles": [...] } }

POST   /admin/refreshToken       刷新token
       Header:   Authorization: Bearer xxx
       Response: { "code": 200, "data": { "token": "xxx", "tokenHead": "Bearer " } }
```

### 5.2 管理员管理接口

```
GET    /admin/admin/list          分页查询管理员列表
       Params:   keyword, pageNum, pageSize
GET    /admin/admin/{id}          查询管理员详情
POST   /admin/admin               新增管理员
POST   /admin/admin/update/{id}   更新管理员信息
POST   /admin/admin/delete/{id}   删除管理员
POST   /admin/admin/updatePassword  修改密码
POST   /admin/admin/updateStatus/{id}  修改启用状态
POST   /admin/admin/role/update   给管理员分配角色
       Params: adminId, roleIds
GET    /admin/admin/role/{adminId}  获取管理员的角色列表
```

### 5.3 角色管理接口

```
GET    /admin/role/list           分页查询角色
GET    /admin/role/listAll        查询全部角色
POST   /admin/role/create         新增角色
POST   /admin/role/update/{id}    更新角色
POST   /admin/role/delete         批量删除角色
POST   /admin/role/updateStatus/{id}  修改角色状态
GET    /admin/role/listMenu/{roleId}      获取角色的菜单
GET    /admin/role/listResource/{roleId}  获取角色的资源
POST   /admin/role/allocMenu      给角色分配菜单
POST   /admin/role/allocResource  给角色分配资源
```

### 5.4 菜单管理接口

```
GET    /admin/menu/tree           获取菜单树
POST   /admin/menu/create         新增菜单
POST   /admin/menu/update/{id}    更新菜单
POST   /admin/menu/delete/{id}    删除菜单
```

### 5.5 资源管理接口

```
GET    /admin/resource/list       分页查询资源
GET    /admin/resource/listAll    查询全部资源
POST   /admin/resource/create     新增资源
POST   /admin/resource/update/{id}  更新资源
POST   /admin/resource/delete/{id}  删除资源
GET    /admin/resourceCategory/listAll  查询资源分类
```

### 5.6 商品管理接口

```
GET    /admin/product/list        分页查询商品
GET    /admin/product/{id}        商品详情
POST   /admin/product             新增商品
PUT    /admin/product/{id}        更新商品
POST   /admin/product/publishStatus   批量上下架
DELETE /admin/product              批量删除
```

### 5.7 订单管理接口

```
GET    /admin/order/list          分页查询订单
GET    /admin/order/{id}          订单详情
POST   /admin/order/delivery      批量发货
POST   /admin/order/close         批量关闭
POST   /admin/order/note          修改备注
```

### 5.8 用户管理接口

```
GET    /admin/member/list         分页查询C端用户
GET    /admin/member/{id}         用户详情
POST   /admin/member/status/{id}  修改用户状态
POST   /admin/member/balance/{id} 调整用户余额
```

### 5.9 统一返回格式

复用 hm-common 的 `R<T>` 和 `PageDTO<T>`：

```json
// 普通响应
{ "code": 200, "msg": "OK", "data": { ... } }

// 分页响应
{
  "code": 200,
  "msg": "OK",
  "data": {
    "total": 100,
    "pages": 10,
    "list": [ ... ]
  }
}

// 错误响应
{ "code": 401, "msg": "未登录或token已过期", "data": null }
{ "code": 403, "msg": "无权限访问", "data": null }
```

---

## 6. 安全设计

### 6.1 认证与授权

| 层级 | 机制 | 说明 |
|------|------|------|
| 网关层 | admin JWT 校验 | hm-gateway 对 `/admin/**` 路径校验 admin JWT，白名单放行登录接口 |
| 服务层 | 动态权限拦截器 | admin-service 内置 `AdminAuthInterceptor`，基于 resource 表做 URL 级权限控制 |
| 方法层 | `@PreAuthorize`（可选） | 对关键操作增加注解级权限校验 |

### 6.2 密码安全

- 管理员密码使用 **BCrypt** 加盐哈希存储（与 C 端用户密码加密方式一致，复用 `spring-security-crypto`）
- 密码修改需校验旧密码
- 密码强度校验：最少 8 位，包含字母和数字

### 6.3 Token 安全

- admin JWT 有效期 **2 小时**（短于 C 端 30 分钟 → 实际可按需调整），支持续期
- 登出时将 JWT 的 `jti` 加入 Redis 黑名单（key: `admin:blacklist:{jti}`，TTL = token 剩余有效期）
- admin JWT 标记 `type: ADMIN`，网关拒绝 C 端 token 访问 `/admin/**`，反之亦然

### 6.4 权限缓存策略

```
Redis Key 设计:
  admin:resourceList          → 全量资源列表(Hash, 启动时加载, 资源变更时刷新)
  admin:adminRoles:{adminId}  → 管理员角色ID列表(Set, 登录时加载)
  admin:adminResources:{adminId} → 管理员可访问资源URL列表(Set, 登录时加载)
  admin:blacklist:{jti}       → 登出token黑名单(String, TTL=token剩余有效期)
```

权限变更（角色分配资源/管理员分配角色）时，主动清除对应缓存，下次请求重新加载。

---

## 7. 与现有微服务的集成

### 7.1 Feign 客户端设计

admin-service 通过 Feign 调用下游微服务，Feign 客户端定义在 admin-service 内部（或扩展 hm-api 模块）：

```java
// 商品服务 Feign 客户端
@FeignClient(name = "item-service", contextId = "admin-item",
             configuration = DefaultFeignConfig.class,
             fallbackFactory = ItemFeignFallbackFactory.class)
public interface ItemFeignClient {
    @GetMapping("/items/page")
    R<PageDTO<ItemDTO>> queryItemByPage(@SpringQueryMap ItemPageQuery query);

    @GetMapping("/items/{id}")
    R<ItemDTO> queryItemById(@PathVariable Long id);

    @PostMapping("/items")
    R<Void> saveItem(@RequestBody ItemDTO item);

    @PutMapping("/items/batch/status")
    R<Void> batchUpdateStatus(@RequestParam List<Long> ids, @RequestParam Integer status);
}

// 交易服务 Feign 客户端
@FeignClient(name = "trade-service", contextId = "admin-trade")
public interface TradeFeignClient {
    @GetMapping("/orders/page")
    R<PageDTO<OrderVO>> queryOrderByPage(@SpringQueryMap OrderPageQuery query);

    @GetMapping("/orders/{id}")
    R<OrderVO> queryOrderById(@PathVariable Long id);

    @PostMapping("/orders/batch/delivery")
    R<Void> batchDelivery(@RequestBody List<DeliveryParam> params);
}

// 用户服务 Feign 客户端
@FeignClient(name = "user-service", contextId = "admin-user")
public interface UserFeignClient {
    @GetMapping("/users/page")
    R<PageDTO<UserDTO>> queryUserByPage(@SpringQueryMap UserPageQuery query);

    @PostMapping("/users/status/{id}")
    R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status);
}
```

### 7.2 下游服务改造清单

| 服务 | 需新增接口 | 说明 |
|------|-----------|------|
| item-service | `PUT /items/batch/status` | 批量修改商品状态(上下架) |
| item-service | `PUT /items/batch/stock` | 批量修改库存 |
| item-service | `GET /items/page`（增强） | 增加按状态/类目/品牌筛选 |
| trade-service | `GET /orders/page`（增强） | 增加多条件分页查询 |
| trade-service | `POST /orders/batch/delivery` | 批量发货 |
| trade-service | `POST /orders/batch/close` | 批量关闭订单 |
| trade-service | `POST /orders/{id}/note` | 修改订单备注 |
| user-service | `GET /users/page` | C端用户分页查询 |
| user-service | `GET /users/{id}` | 用户详情 |
| user-service | `POST /users/status/{id}` | 修改用户状态 |
| user-service | `POST /users/balance/{id}` | 调整用户余额 |

### 7.3 Nacos 配置

admin-service 的 `bootstrap.yml`：

```yaml
spring:
  application:
    name: admin-service
  profiles:
    active: dev
  cloud:
    nacos:
      server-addr: ${NACOS_ADDR:192.168.100.128:8848}
      config:
        file-extension: yaml
        shared-configs:
          - dataId: shared-jdbc.yaml       # 共享数据源配置
          - dataId: shared-log.yaml        # 共享日志配置
          - dataId: shared-swagger.yaml    # 共享Swagger配置
          - dataId: shared-redis.yaml      # 共享Redis配置(新增)
```

admin-service 的 `application.yaml`：

```yaml
server:
  port: 8090
spring:
  datasource:
    url: jdbc:mysql://${hm.db.host}/hm-admin?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
    username: root
    password: ${hm.db.pw}
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: auto
hm:
  admin:
    jwt:
      location: classpath:admin.jks
      alias: admin
      password: ${ADMIN_JWT_KEYSTORE_PASSWORD:admin123}
      tokenTTL: 2h
    auth:
      excludePaths:
        - /admin/login
        - /admin/register
        - /doc.html
        - /webjars/**
        - /swagger-resources/**
knife4j:
  enable: true
  openapi:
    title: hmall 管理后台接口文档
    group:
      default:
        api-rule: package
        api-rule-resources:
          - com.hmall.admin.controller
```

### 7.4 网关路由配置

在 Nacos 的 `gateway-routes.json` 中新增：

```json
[
  {
    "id": "admin-service",
    "predicates": [{
      "name": "Path",
      "args": { "pattern": "/admin/**" }
    }],
    "filters": [],
    "uri": "lb://admin-service",
    "order": 0
  }
]
```

网关 `AuthGlobalFilter` 需区分 admin 路由和 C 端路由，使用不同 JWT 密钥校验。

---

## 8. 前端界面设计

### 8.1 现有前端管理后台分析

hmall 前端工程位于 `hmall-frontend/`，采用 Vue 3 单页应用，**C 端商城与 B 端管理后台共用同一工程**，通过路由前缀 `/portal/**`（C 端）与 `/admin/**`（B 端）区分。

#### 8.1.1 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.5 | 前端框架（Composition API + `<script setup>`） |
| TypeScript | 5.6 | 类型安全 |
| Vite | 5.4 | 构建工具 |
| Element Plus | 2.14 | UI 组件库（管理后台主力） |
| Pinia | 3.0 | 状态管理 |
| Vue Router | 4.6 | 路由（Hash 模式） |
| Tailwind CSS | 3.4 | 原子化 CSS |
| ECharts | 6.1 + vue-echarts | 数据可视化图表 |
| Axios | 1.18 | HTTP 请求 |
| @element-plus/icons-vue | 2.3 | 菜单/按钮图标 |
| lucide-vue-next | 1.0 | 辅助图标 |

#### 8.1.2 已有页面清单

| 页面文件 | 路由 | 功能 | 状态 |
|---------|------|------|------|
| `AdminLogin.vue` | `/admin/login` | 管理员登录（用户名/密码） | ✅ 已实现，但复用 C 端 `/users/login` 接口 |
| `AdminLayout.vue` | `/admin` | 后台主框架（侧边栏+顶栏+内容区） | ✅ 已实现，菜单硬编码 |
| `Dashboard.vue` | `/admin/dashboard` | 数据概览（统计卡片+折线图+饼图） | ⚠️ Mock 数据 |
| `ItemManage.vue` | `/admin/items` | 商品管理（CRUD+上下架+分页） | ✅ 已实现，对接 `/items/**` |
| `UserManage.vue` | `/admin/users` | 用户管理（列表+充值） | ⚠️ Mock 数据，无真实 API |
| — | `/admin/orders` | 订单管理 | ❌ 菜单中存在但路由/页面未实现 |

#### 8.1.3 现有实现的问题

| 问题 | 现状 | 改造方向 |
|------|------|---------|
| **认证未隔离** | `AdminLogin.vue` 调用 C 端 `loginApi`（`POST /users/login`），admin token 与 C 端 token 混用 | 对接 admin-service 专属登录接口 `POST /admin/login` |
| **请求拦截器不区分 token** | `api/index.ts` 只读 `sessionStorage.token`（C 端 token），admin 请求未携带 `admin-token` | 新增 admin 专属 axios 实例或请求拦截器区分 |
| **菜单硬编码** | `AdminLayout.vue` 侧边栏菜单写死在模板中，无法按角色动态显示 | 对接后端 `GET /admin/info` 返回的 `menus` 动态渲染 |
| **无 RBAC 管理页面** | 缺少管理员/角色/菜单/资源管理页面 | 新增 4 个 RBAC 管理页面 |
| **无按钮级权限** | 所有操作按钮对任何登录管理员可见 | 新增 `v-permission` 自定义指令 |
| **UserManage/Dashboard Mock** | 用户列表和统计数据为前端写死 | 对接真实管理 API |
| **订单管理缺失** | 菜单有入口但无页面 | 新增 `OrderManage.vue` |
| **Vite 代理单一** | `vite.config.ts` 仅代理 `/api` → `localhost:8080`（hm-service） | admin 接口走 `/api/admin/**` 经网关路由 |

### 8.2 前端目录结构设计

在现有 `hmall-frontend/src/` 基础上扩展，admin 相关文件归集到 `admin/` 子目录：

```
src/
├── api/
│   ├── index.ts                  # C端 axios 实例（已有，保持不变）
│   ├── admin.ts                  # ★ 新增：admin axios 实例（独立 baseURL + token）
│   ├── admin/
│   │   ├── auth.ts               # ★ 新增：管理员认证 API（登录/登出/信息/刷新）
│   │   ├── adminUser.ts          # ★ 新增：管理员管理 API
│   │   ├── role.ts               # ★ 新增：角色管理 API
│   │   ├── menu.ts               # ★ 新增：菜单管理 API
│   │   ├── resource.ts           # ★ 新增：资源管理 API
│   │   ├── product.ts            # ★ 新增：商品管理 API（对接 admin-service）
│   │   ├── order.ts              # ★ 新增：订单管理 API
│   │   └── member.ts             # ★ 新增：C端用户管理 API
│   ├── item.ts                   # 已有 C端商品 API（保持不变）
│   ├── user.ts                   # 已有 C端用户 API（保持不变）
│   └── ...
├── stores/
│   ├── admin.ts                  # ★ 改造：对接 admin-service 登录/权限
│   ├── user.ts                   # 已有（保持不变）
│   └── cart.ts                   # 已有（保持不变）
├── router/
│   └── index.ts                  # ★ 改造：新增 admin 子路由 + 动态路由
├── views/
│   ├── admin/                    # ★ 扩展
│   │   ├── AdminLayout.vue       # 改造：动态菜单
│   │   ├── AdminLogin.vue        # 改造：对接 admin-service 登录
│   │   ├── Dashboard.vue         # 改造：对接真实统计 API
│   │   ├── ItemManage.vue        # 改造：对接 admin-service 管理接口
│   │   ├── OrderManage.vue       # ★ 新增：订单管理页
│   │   ├── UserManage.vue        # 改造：对接真实用户管理 API
│   │   └── system/               # ★ 新增：RBAC 系统管理
│   │       ├── AdminUserManage.vue    # 管理员管理
│   │       ├── RoleManage.vue         # 角色管理
│   │       ├── MenuManage.vue         # 菜单管理
│   │       └── ResourceManage.vue     # 资源管理
│   └── portal/                   # C端页面（保持不变）
├── components/
│   └── admin/                    # ★ 新增：admin 公共组件
│       ├── AdminTableHeader.vue  # 通用搜索+操作栏
│       └── AdminPermButton.vue   # 权限按钮封装
├── directives/
│   └── permission.ts             # ★ 新增：v-permission 按钮级权限指令
├── types/
│   ├── index.ts                  # 已有 C端类型（保持不变）
│   └── admin.ts                  # ★ 新增：admin 相关类型定义
└── utils/
    └── format.ts                 # 已有（保持不变，admin 复用）
```

### 8.3 路由与导航设计

#### 8.3.1 路由结构

```typescript
// router/index.ts（改造后）
const routes = [
  // ... C端 portal 路由保持不变 ...

  // 管理后台登录页（不进入 AdminLayout）
  {
    path: '/admin/login',
    name: 'AdminLogin',
    component: () => import('@/views/admin/AdminLogin.vue'),
  },

  // 管理后台主框架
  {
    path: '/admin',
    component: () => import('@/views/admin/AdminLayout.vue'),
    redirect: '/admin/dashboard',
    meta: { requiresAdmin: true },
    children: [
      { path: 'dashboard', name: 'Dashboard',
        component: () => import('@/views/admin/Dashboard.vue'),
        meta: { title: '数据概览', icon: 'DataAnalysis' } },

      // 商品管理
      { path: 'items', name: 'ItemManage',
        component: () => import('@/views/admin/ItemManage.vue'),
        meta: { title: '商品管理', icon: 'Goods' } },

      // 订单管理（新增）
      { path: 'orders', name: 'OrderManage',
        component: () => import('@/views/admin/OrderManage.vue'),
        meta: { title: '订单管理', icon: 'Tickets' } },
      { path: 'orders/:id', name: 'OrderDetail',
        component: () => import('@/views/admin/OrderDetail.vue'),
        meta: { title: '订单详情', hidden: true } },

      // C端用户管理
      { path: 'users', name: 'UserManage',
        component: () => import('@/views/admin/UserManage.vue'),
        meta: { title: '用户管理', icon: 'UserFilled' } },

      // 系统管理（RBAC，新增）
      { path: 'system/admin', name: 'AdminUserManage',
        component: () => import('@/views/admin/system/AdminUserManage.vue'),
        meta: { title: '管理员管理', icon: 'User', parent: '系统管理' } },
      { path: 'system/role', name: 'RoleManage',
        component: () => import('@/views/admin/system/RoleManage.vue'),
        meta: { title: '角色管理', icon: 'UserFilled', parent: '系统管理' } },
      { path: 'system/menu', name: 'MenuManage',
        component: () => import('@/views/admin/system/MenuManage.vue'),
        meta: { title: '菜单管理', icon: 'Menu', parent: '系统管理' } },
      { path: 'system/resource', name: 'ResourceManage',
        component: () => import('@/views/admin/system/ResourceManage.vue'),
        meta: { title: '资源管理', icon: 'Setting', parent: '系统管理' } },
    ],
  },
]
```

#### 8.3.2 路由守卫改造

```typescript
router.beforeEach(async (to, _from, next) => {
  const userStore = useUserStore()
  const adminStore = useAdminStore()

  // C端鉴权（已有，保持不变）
  if (to.meta.requiresAuth && !userStore.isLogin) {
    return next('/portal/login')
  }

  // 管理后台鉴权
  if (to.meta.requiresAdmin && !adminStore.isAdminLogin) {
    return next('/admin/login')
  }

  // ★ 新增：首次进入后台时加载管理员信息和权限
  if (to.meta.requiresAdmin && adminStore.isAdminLogin && !adminStore.menus.length) {
    await adminStore.fetchAdminInfo()
  }

  // ★ 新增：菜单权限校验（动态菜单模式下）
  if (to.meta.requiresAdmin && to.name !== 'Dashboard') {
    if (!adminStore.hasRoutePermission(to.path)) {
      return next('/admin/dashboard')
    }
  }

  next()
})
```

#### 8.3.3 动态菜单渲染

`AdminLayout.vue` 改造：侧边栏菜单从 `adminStore.menus`（后端返回）动态渲染，替代硬编码菜单：

```vue
<!-- AdminLayout.vue 侧边栏改造 -->
<el-menu :default-active="activeMenu" :collapse="collapsed" router>
  <template v-for="menu in adminStore.menus" :key="menu.id">
    <!-- 有子菜单 -->
    <el-sub-menu v-if="menu.children?.length" :index="menu.name">
      <template #title>
        <el-icon><component :is="menu.icon || 'Menu'" /></el-icon>
        <span>{{ menu.title }}</span>
      </template>
      <el-menu-item
        v-for="child in menu.children"
        :key="child.id"
        :index="child.path"
      >
        {{ child.title }}
      </el-menu-item>
    </el-sub-menu>
    <!-- 无子菜单 -->
    <el-menu-item v-else :index="menu.path">
      <el-icon><component :is="menu.icon || 'Menu'" /></el-icon>
      <span>{{ menu.title }}</span>
    </el-menu-item>
  </template>
</el-menu>
```

### 8.4 状态管理设计（admin store 改造）

```typescript
// stores/admin.ts（改造后）
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { adminLogin, adminLogout, getAdminInfo } from '@/api/admin/auth'
import type { AdminMenu, AdminInfo } from '@/types/admin'

export const useAdminStore = defineStore('admin', () => {
  const adminToken = ref(sessionStorage.getItem('admin-token') || '')
  const adminInfo = ref<AdminInfo | null>(
    JSON.parse(sessionStorage.getItem('admin-info') || 'null')
  )
  const menus = ref<AdminMenu[]>([])
  const permissions = ref<string[]>([])  // 资源权限编码列表

  const isAdminLogin = computed(() => !!adminToken.value)
  const username = computed(() => adminInfo.value?.username || '管理员')

  /** 登录：对接 admin-service 专属接口 */
  async function login(loginForm: { username: string; password: string }) {
    const res = await adminLogin(loginForm)
    adminToken.value = res.token
    sessionStorage.setItem('admin-token', res.token)
    // 登录后立即获取管理员信息（含菜单和权限）
    await fetchAdminInfo()
  }

  /** 获取管理员信息：菜单树 + 角色列表 + 权限编码 */
  async function fetchAdminInfo() {
    const info = await getAdminInfo()
    adminInfo.value = info
    menus.value = info.menus || []
    permissions.value = info.permissions || []
    sessionStorage.setItem('admin-info', JSON.stringify(info))
  }

  /** 登出 */
  async function logout() {
    try {
      await adminLogout()
    } catch { /* 忽略 */ }
    adminToken.value = ''
    adminInfo.value = null
    menus.value = []
    permissions.value = []
    sessionStorage.removeItem('admin-token')
    sessionStorage.removeItem('admin-info')
  }

  /** 检查路由权限 */
  function hasRoutePermission(path: string): boolean {
    // 超级管理员拥有全部权限
    if (permissions.value.includes('*')) return true
    return menus.value.some(m =>
      m.path === path || m.children?.some(c => c.path === path)
    )
  }

  /** 检查按钮级权限 */
  function hasPermission(code: string): boolean {
    if (permissions.value.includes('*')) return true
    return permissions.value.includes(code)
  }

  return {
    adminToken, adminInfo, menus, permissions,
    isAdminLogin, username,
    login, fetchAdminInfo, logout,
    hasRoutePermission, hasPermission,
  }
})
```

### 8.5 API 层设计

#### 8.5.1 admin 专属 axios 实例

```typescript
// api/admin.ts（新增）
import axios from 'axios'
import router from '@/router'
import { ElMessage } from 'element-plus'

const adminInstance = axios.create({
  baseURL: '/api',          // 经 Vite 代理 → 网关 → admin-service
  timeout: 15000,
})

// 请求拦截：携带 admin-token
adminInstance.interceptors.request.use((config) => {
  const token = sessionStorage.getItem('admin-token')
  if (token) {
    config.headers.authorization = token
  }
  return config
})

// 响应拦截：解包 + token续期 + 401处理
adminInstance.interceptors.response.use(
  (response) => {
    // Token 续期
    const newToken = response.headers['authorization']
    if (newToken) {
      sessionStorage.setItem('admin-token', newToken)
    }
    const { code, msg, data } = response.data
    if (code === 200) return data
    ElMessage.error(msg || '请求失败')
    return Promise.reject(new Error(msg))
  },
  (error) => {
    if (error.response?.status === 401) {
      sessionStorage.removeItem('admin-token')
      sessionStorage.removeItem('admin-info')
      ElMessage.warning('登录已过期，请重新登录')
      router.push('/admin/login')
    } else if (error.response?.status === 403) {
      ElMessage.error('无权限执行此操作')
    } else {
      ElMessage.error('网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

export default adminInstance
```

#### 8.5.2 admin API 模块

```typescript
// api/admin/auth.ts
import adminRequest from '../admin'
import type { AdminInfo } from '@/types/admin'

export function adminLogin(data: { username: string; password: string }):
  Promise<{ token: string; tokenHead: string }> {
  return adminRequest.post('/admin/login', data)
}

export function adminLogout(): Promise<void> {
  return adminRequest.post('/admin/logout')
}

export function getAdminInfo(): Promise<AdminInfo> {
  return adminRequest.get('/admin/info')
}

export function refreshToken(): Promise<{ token: string; tokenHead: string }> {
  return adminRequest.get('/admin/refreshToken')
}
```

```typescript
// api/admin/product.ts（商品管理，对接 admin-service 转发）
import adminRequest from '../admin'
import type { Item, PageResult, PageQuery } from '@/types'

export function getAdminProductPage(params: PageQuery & { key?: string; status?: number }):
  Promise<PageResult<Item>> {
  return adminRequest.get('/admin/product/list', { params })
}

export function createProduct(data: Partial<Item>): Promise<void> {
  return adminRequest.post('/admin/product', data)
}

export function updateProduct(id: number, data: Partial<Item>): Promise<void> {
  return adminRequest.put(`/admin/product/${id}`, data)
}

export function batchUpdatePublishStatus(ids: number[], publishStatus: number): Promise<void> {
  return adminRequest.post('/admin/product/publishStatus', null, { params: { ids, publishStatus } })
}

export function deleteProducts(ids: number[]): Promise<void> {
  return adminRequest.delete('/admin/product', { params: { ids } })
}
```

```typescript
// api/admin/order.ts（订单管理，新增）
import adminRequest from '../admin'
import type { PageResult, PageQuery, OrderVO } from '@/types'

export interface OrderPageQuery extends PageQuery {
  status?: number
  orderId?: string
  startTime?: string
  endTime?: string
}

export function getAdminOrderPage(params: OrderPageQuery):
  Promise<PageResult<OrderVO>> {
  return adminRequest.get('/admin/order/list', { params })
}

export function getOrderDetail(id: string): Promise<OrderVO> {
  return adminRequest.get(`/admin/order/${id}`)
}

export function batchDelivery(params: { orderId: string; logisticsNo: string }[]): Promise<void> {
  return adminRequest.post('/admin/order/delivery', params)
}

export function batchCloseOrders(ids: string[], note: string): Promise<void> {
  return adminRequest.post('/admin/order/close', null, { params: { ids, note } })
}

export function updateOrderNote(id: string, note: string, status: number): Promise<void> {
  return adminRequest.post('/admin/order/note', null, { params: { id, note, status } })
}
```

### 8.6 页面设计

#### 8.6.1 登录页（AdminLogin.vue 改造）

```
┌──────────────────────────────────────────────┐
│                                              │
│            ┌────────────────────┐            │
│            │   枫叶商城管理系统    │            │
│            │                    │            │
│            │  [👤 用户名       ]  │            │
│            │  [🔒 密码         ]  │
│            │                    │            │
│            │  [    登 录     ]  │            │
│            │                    │            │
│            │   ← 返回商城首页     │            │
│            └────────────────────┘            │
│                                              │
└──────────────────────────────────────────────┘
```

改造点：
- 调用 `adminStore.login()`（对接 `POST /admin/login`），替代 `loginApi`
- 登录成功后自动加载管理员信息和菜单权限

#### 8.6.2 布局与动态菜单（AdminLayout.vue 改造）

```
┌─────────┬──────────────────────────────────────────┐
│ 黑马后台  │  ☰ 首页 / 商品管理          [在线] admin [退出] │
│         ├──────────────────────────────────────────┤
│ 📊 数据概览│                                          │
│ 📦 商品管理│          <router-view />                  │
│ 🎫 订单管理│          （各管理页面渲染区）                │
│ 👥 用户管理│                                          │
│ ⚙ 系统管理 │                                          │
│   ├ 管理员 │                                          │
│   ├ 角色   │                                          │
│   ├ 菜单   │                                          │
│   └ 资源   │                                          │
└─────────┴──────────────────────────────────────────┘
```

改造点：
- 侧边栏菜单从 `adminStore.menus` 动态渲染
- 顶栏显示当前管理员用户名（从 `adminStore.username`）
- 面包屑根据路由 `meta.title` 动态生成

#### 8.6.3 数据概览（Dashboard.vue 改造）

```
┌────────────┬────────────┬────────────┬────────────┐
│ 📦 今日订单  │ 👤 新增用户  │ 💰 今日销售额│ 📦 商品总数  │
│    156     │    38      │  ¥12,580   │   1,286    │
└────────────┴────────────┴────────────┴────────────┘
┌──────────────────────────┬──────────────────────┐
│       销售趋势（折线图）      │    分类占比（饼图）     │
│                          │                      │
│    📈 销售额/订单量趋势      │      🥧 各品类占比      │
│                          │                      │
└──────────────────────────┴──────────────────────┘
```

改造点：
- 统计卡片对接 admin-service 统计接口（Phase 4 实现，初期保留 Mock）
- 图表数据对接真实统计 API

#### 8.6.4 商品管理（ItemManage.vue 改造）

```
┌──────────────────────────────────────────────────────┐
│ [🔍 搜索商品名称    ] [搜索]              [+ 新增商品]  │
├────┬──────┬──────────┬────┬─────┬────┬────┬──────────┤
│ ID │ 图片  │ 商品名称   │ 分类│ 价格 │库存│状态│   操作    │
├────┼──────┼──────────┼────┼─────┼────┼────┼──────────┤
│ 1  │[img] │ iPhone 15 │手机│6999│100 │上架│编辑 上下架 删│
│ 2  │[img] │ MacBook   │电脑│9999│ 50 │上架│编辑 上下架 删│
├────┴──────┴──────────┴────┴─────┴────┴────┴──────────┤
│                              < 1 2 3 4 >  共 100 条   │
└──────────────────────────────────────────────────────┘

┌──── 新增/编辑商品 ────────────────────┐
│ 商品名称: [____________________]      │
│ 价格(元): [___________]               │
│ 库存:    [___________]               │
│ 分类:    [____________________]      │
│ 品牌:    [____________________]      │
│ 规格:    [____________________]      │
│ 图片URL: [____________________]      │
│                    [取消] [保存]      │
└──────────────────────────────────────┘
```

改造点：
- API 从 `@/api/item`（直连 hm-service）切换到 `@/api/admin/product`（经 admin-service）
- 新增批量上下架功能（多选 + 批量操作按钮）
- 复用现有 Element Plus 表格 + 弹窗模式

#### 8.6.5 订单管理（OrderManage.vue 新增）

```
┌──────────────────────────────────────────────────────┐
│ 订单号:[______] 状态:[▼全部] 时间:[▼] [查询] [重置]     │
├──────┬────────┬──────┬──────┬──────┬──────┬──────────┤
│ 订单号 │ 创建时间  │ 用户  │ 总金额│支付方式│ 状态 │   操作    │
├──────┼────────┼──────┼──────┼──────┼──────┼──────────┤
│1001  │07-14...│user1│¥2999│支付宝 │待发货│详情 发货 关闭│
│1002  │07-14...│user2│¥199 │微信   │已发货│详情         │
├──────┴────────┴──────┴──────┴──────┴──────┴──────────┤
│ ✅多选  [批量发货] [批量关闭]    < 1 2 3 > 共 50 条    │
└──────────────────────────────────────────────────────┘

┌──── 订单详情 ─────────────────────────────────────────┐
│ 订单号: 1001    状态: 待发货    创建时间: 2026-07-14   │
│ ┌─────────────────────────────────────────────────┐  │
│ │ 商品            数量   单价    小计              │  │
│ │ iPhone 15       1     ¥6999  ¥6999             │  │
│ └─────────────────────────────────────────────────┘  │
│ 收货人: 张三  电话: 138****8888                       │
│ 地址: 北京市朝阳区xxx                                │
│ 物流单号: [___________]  [确认发货]                   │
│ 备注: [____________________]              [关闭]      │
└──────────────────────────────────────────────────────┘
```

核心功能：
- 多条件筛选（订单号、状态、时间范围）
- 批量发货（弹窗输入物流单号）
- 批量关闭订单（输入关闭原因）
- 订单详情弹窗（商品明细 + 收货信息 + 物流 + 备注）

#### 8.6.6 用户管理（UserManage.vue 改造）

```
┌──────────────────────────────────────────────────────┐
│ [🔍 用户名/手机号] [▼全部状态] [搜索]                   │
├────┬──────────┬────────┬──────────┬──────┬──────────┤
│ ID │ 用户名    │ 手机号  │ 余额      │ 状态 │   操作    │
├────┼──────────┼────────┼──────────┼──────┼──────────┤
│ 1  │ admin    │138****8│¥10,000.00│ 正常 │冻结 充值   │
│ 2  │ test     │139****9│¥500.00   │ 冻结 │解冻 充值   │
├────┴──────────┴────────┴──────────┴──────┴──────────┤
│                              < 1 2 3 >  共 100 条   │
└──────────────────────────────────────────────────────┘

┌──── 余额调整 ──────────────────┐
│ 当前余额: ¥500.00              │
│ 调整金额: [+_____] (正数充值/负数扣减)│
│           [取消] [确认]        │
└───────────────────────────────┘
```

改造点：
- 删除 Mock 数据，对接 `@/api/admin/member`
- 新增用户状态切换（正常/冻结）
- 充值弹窗改为余额调整（支持正数充值、负数扣减）

#### 8.6.7 RBAC 系统管理（新增 4 个页面）

**管理员管理（AdminUserManage.vue）**

```
┌──────────────────────────────────────────────────────┐
│ [🔍 用户名] [搜索]                       [+ 新增管理员] │
├────┬──────┬──────┬──────┬──────┬──────┬──────────────┤
│ ID │ 用户名 │ 昵称  │ 邮箱  │状态  │角色  │    操作      │
├────┼──────┼──────┼──────┼──────┼──────┼──────────────┤
│ 1  │admin │超管  │a@x.c│启用  │超管  │编辑 删除 分配角色│
├────┴──────┴──────┴──────┴──────┴──────┴──────────────┤
│                              < 1 2 3 >              │
└──────────────────────────────────────────────────────┘

┌──── 分配角色 ─────────────────┐
│  管理员: admin                │
│  ☑ 超级管理员                  │
│  ☐ 运营人员                    │
│  ☐ 财务人员                    │
│              [取消] [确认]     │
└──────────────────────────────┘
```

**角色管理（RoleManage.vue）**

```
┌──────────────────────────────────────────────────────┐
│ [🔍 角色名] [搜索]                           [+ 新增角色]│
├────┬──────────┬──────────┬──────┬──────────────────────┤
│ ID │ 角色名称   │ 描述      │状态  │        操作          │
├────┼──────────┼──────────┼──────┼──────────────────────┤
│ 1  │超级管理员  │全部权限    │启用  │编辑 删除 分配菜单 分配资源│
│ 2  │运营人员    │商品订单管理│启用  │编辑 删除 分配菜单 分配资源│
└────┴──────────┴──────────┴──────┴──────────────────────┘

┌──── 分配菜单 ──────────────────┐
│  角色: 运营人员                 │
│  ☑ 数据概览                    │
│  ☑ 商品管理                    │
│  ☑ 订单管理                    │
│  ☐ 用户管理                    │
│  ☐ 系统管理                    │
│              [取消] [确认]     │
└──────────────────────────────┘
```

**菜单管理（MenuManage.vue）**

```
┌──────────────────────────────────────────────────────┐
│                                          [+ 新增菜单]  │
├──────────────────────────────────────────────────────┤
│ 菜单名称           │ 前端路由     │ 图标  │排序│ 操作   │
├──────────────────────────────────────────────────────┤
│ ▼ 系统管理         │ /system     │ ⚙   │ 0 │编辑 删除│
│   ├ 管理员管理     │ /system/admin│ 👤  │ 1 │编辑 删除│
│   ├ 角色管理       │ /system/role│ 👥  │ 2 │编辑 删除│
│   ├ 菜单管理       │ /system/menu│ 📋  │ 3 │编辑 删除│
│   └ 资源管理       │ /system/res │ 🔧  │ 4 │编辑 删除│
└──────────────────────────────────────────────────────┘
```

**资源管理（ResourceManage.vue）**

```
┌──────────────────────────────────────────────────────┐
│ [🔍 资源名] 分类:[▼全部] [搜索]                        [+ 新增资源]│
├────┬────────────┬─────────────────┬──────────┬────────┤
│ ID │ 资源名称     │ URL             │ 分类      │ 操作   │
├────┼────────────┼─────────────────┼──────────┼────────┤
│ 1  │商品列表     │/admin/product/** │ 商品管理  │编辑 删除│
│ 2  │订单发货     │/admin/order/del*│ 订单管理  │编辑 删除│
└────┴────────────┴─────────────────┴──────────┴────────┘
```

### 8.7 按钮级权限指令

新增 `v-permission` 自定义指令，实现按钮级权限控制：

```typescript
// directives/permission.ts（新增）
import type { Directive } from 'vue'
import { useAdminStore } from '@/stores/admin'

export const permission: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const adminStore = useAdminStore()
    const value = binding.value

    const codes = Array.isArray(value) ? value : [value]
    const hasPerm = codes.some(code => adminStore.hasPermission(code))

    if (!hasPerm) {
      el.parentNode?.removeChild(el)
    }
  },
}

// main.ts 注册
// app.directive('permission', permission)
```

使用示例：

```vue
<el-button v-permission="'product:create'" @click="openAddDialog">
  新增商品
</el-button>
<el-button v-permission="'product:delete'" type="danger" @click="handleDelete">
  删除
</el-button>
```

### 8.8 前端类型定义

```typescript
// types/admin.ts（新增）
export interface AdminInfo {
  id: number
  username: string
  icon?: string
  roles: string[]
  menus: AdminMenu[]
  permissions: string[]
}

export interface AdminMenu {
  id: number
  parentId: number
  title: string
  name?: string       // 前端路由名称
  path?: string       // 前端路由路径
  icon?: string
  level: number
  sort: number
  hidden: number
  children?: AdminMenu[]
}

export interface AdminUser {
  id: number
  username: string
  icon?: string
  email?: string
  nickName?: string
  note?: string
  status: number      // 0禁用 1启用
  createTime: string
  loginTime?: string
}

export interface Role {
  id: number
  name: string
  description?: string
  adminCount: number
  status: number
  sort: number
  createTime: string
}

export interface Resource {
  id: number
  name: string
  url: string
  description?: string
  categoryId?: number
  createTime: string
}

export interface ResourceCategory {
  id: number
  name: string
  createTime: string
}
```

### 8.9 Vite 代理配置

```typescript
// vite.config.ts（改造）
export default defineConfig({
  // ...
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',  // 网关地址
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
```

> 前端所有请求统一走 `/api` 前缀，由网关根据路径分发：`/admin/**` → admin-service，`/items/**` → item-service，`/users/**` → user-service 等。开发环境无需额外配置。

### 8.10 前端改造清单

| 优先级 | 改造项 | 涉及文件 | 依赖后端 |
|--------|--------|---------|---------|
| P0 | 新增 admin axios 实例 + 请求/响应拦截 | `api/admin.ts` | — |
| P0 | 改造 AdminLogin 对接 `/admin/login` | `AdminLogin.vue`, `stores/admin.ts` | admin-service 登录接口 |
| P0 | 改造 admin store（登录/信息/权限） | `stores/admin.ts` | admin-service `/admin/info` |
| P0 | 改造 AdminLayout 动态菜单 | `AdminLayout.vue` | admin-service 菜单接口 |
| P1 | 改造 ItemManage 对接 admin-service | `ItemManage.vue`, `api/admin/product.ts` | admin-service 商品管理 |
| P1 | 新增 OrderManage 页面 | `OrderManage.vue`, `api/admin/order.ts` | admin-service 订单管理 |
| P1 | 改造 UserManage 对接真实 API | `UserManage.vue`, `api/admin/member.ts` | admin-service 用户管理 |
| P2 | 新增 RBAC 管理页面（4 个） | `views/admin/system/*` | admin-service RBAC 接口 |
| P2 | 新增 v-permission 指令 | `directives/permission.ts` | admin-store 权限列表 |
| P2 | 新增路由守卫权限校验 | `router/index.ts` | admin-store 菜单 |
| P3 | 改造 Dashboard 对接真实统计 | `Dashboard.vue` | admin-service 统计接口 |
| P3 | admin API 类型定义 | `types/admin.ts` | — |

---

## 9. POM 依赖设计

```xml
<!-- admin-service/pom.xml -->
<project>
    <parent>
        <groupId>com.heima</groupId>
        <artifactId>hmall</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>admin-service</artifactId>

    <dependencies>
        <!-- 公共模块 -->
        <dependency>
            <groupId>com.heima</groupId>
            <artifactId>hm-common</artifactId>
            <version>1.0.0</version>
        </dependency>
        <!-- Feign 客户端 -->
        <dependency>
            <groupId>com.heima</groupId>
            <artifactId>hm-api</artifactId>
            <version>1.0.0</version>
        </dependency>
        <!-- web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <!-- 密码加密 -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-crypto</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-rsa</artifactId>
        </dependency>
        <!-- 数据库 -->
        <dependency>
            <groupId>mysql</groupId>
            <artifactId>mysql-connector-java</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-boot-starter</artifactId>
        </dependency>
        <!-- Redis -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <!-- Nacos 服务注册发现 -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <!-- Nacos 配置中心 -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-bootstrap</artifactId>
        </dependency>
        <!-- OpenFeign -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.openfeign</groupId>
            <artifactId>feign-okhttp</artifactId>
        </dependency>
    </dependencies>
</project>
```

---

## 10. 实现路线图

### Phase 1：基础框架与认证（MVP）

- [ ] 创建 admin-service Maven 模块，配置 Nacos、数据源、Redis
- [ ] 实现 RBAC 建表 SQL，初始化超级管理员
- [ ] 实现 `AdminAuthController`：登录、登出、获取信息、刷新token
- [ ] 实现 `AdminAuthInterceptor`：JWT 校验 + 动态权限拦截
- [ ] 实现管理员 CRUD 和角色分配
- [ ] 生成 admin.jks 密钥库
- [ ] 配置网关 admin 路由
- [ ] **前端**：新增 admin axios 实例 + 改造 AdminLogin 对接 `/admin/login`
- [ ] **前端**：改造 admin store（登录/获取信息/权限）+ AdminLayout 动态菜单

### Phase 2：商品与订单管理

- [ ] 扩展 item-service 管理接口（批量上下架、库存调整）
- [ ] 扩展 trade-service 管理接口（批量发货、关闭、备注）
- [ ] 实现 `ProductAdminController` 和 `OrderAdminController`
- [ ] Feign 客户端 + Fallback 降级
- [ ] **前端**：改造 ItemManage 对接 admin-service 商品管理 API
- [ ] **前端**：新增 OrderManage 订单管理页（列表/详情/批量发货/关闭）

### Phase 3：用户管理与权限完善

- [ ] 扩展 user-service 管理接口（分页查询、状态修改、余额调整）
- [ ] 实现 `MemberAdminController`
- [ ] 实现菜单树管理和资源管理
- [ ] 权限缓存优化（Redis + 变更主动刷新）
- [ ] **前端**：改造 UserManage 对接真实用户管理 API
- [ ] **前端**：新增 RBAC 管理页面（管理员/角色/菜单/资源 4 个页面）
- [ ] **前端**：新增 `v-permission` 按钮级权限指令 + 路由守卫权限校验

### Phase 4：扩展（按需）

- [ ] 营销管理（优惠券、首页广告）— 参考 nova-mall-admin SMS 模块
- [ ] 内容管理（帮助文档、专题）— 参考 nova-mall-admin CMS 模块
- [ ] 文件上传（MinIO 集成）— 参考 nova-mall-admin MinioController
- [ ] 操作日志审计
- [ ] 数据统计看板

---

## 11. 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 管理操作通过 Feign 调用下游，存在网络故障风险 | 管理操作失败 | 配置 Feign Fallback 降级 + 重试 + 超时控制 |
| 动态权限全量缓存，资源变更后不及时生效 | 权限判断不准 | 资源/角色变更时主动清除 Redis 缓存 |
| admin JWT 与 C 端 JWT 隔离不当导致越权 | 安全漏洞 | 使用独立密钥库 + token type 标记 + 网关路由隔离 |
| 下游服务需新增管理接口，影响现有 C 端逻辑 | C 端功能回归 | 管理接口使用独立路径（`/batch/`、`/admin/` 前缀），不修改现有 C 端接口 |
| 批量操作（发货/关闭）无事务保证 | 数据不一致 | 下游服务批量操作加 `@Transactional`；跨服务考虑 Seata（hmall 已集成） |
| 前端 admin/C 端 token 混用导致越权 | 安全漏洞 | admin 使用独立 axios 实例 + 独立 `admin-token` sessionStorage key + 独立请求头 |
| 前端动态菜单后端返回格式与路由不匹配 | 页面无法访问 | 统一 `AdminMenu` 类型定义，菜单 `path` 与前端路由 `path` 严格对应 |
| 前端改造影响现有 C 端功能 | C 端回归 | admin 代码归集到 `admin/` 子目录，C 端 API/store/页面不修改 |

---

## 附录 A：nova-mall-admin 功能模块对照表

| nova-mall-admin 模块 | 功能 | hmall admin-service 采纳情况 |
|---------------------|------|---------------------------|
| UMS - UmsAdminController | 管理员认证/CRUD/角色分配 | ✅ 采纳 |
| UMS - UmsRoleController | 角色CRUD/菜单资源分配 | ✅ 采纳 |
| UMS - UmsMenuController | 菜单树管理 | ✅ 采纳 |
| UMS - UmsResourceController | 资源(权限)管理 | ✅ 采纳 |
| UMS - UmsMemberLevelController | 会员等级 | ⏳ Phase 4 |
| PMS - PmsProductController | 商品CRUD/上下架/审核 | ✅ 采纳(委托item-service) |
| PMS - PmsBrandController | 品牌管理 | ⏳ Phase 4 |
| PMS - PmsProductCategoryController | 商品分类管理 | ⏳ Phase 4 |
| PMS - PmsProductAttributeController | 商品属性管理 | ⏳ Phase 4 |
| PMS - PmsSkuStockController | SKU库存管理 | ✅ 采纳(简化版) |
| OMS - OmsOrderController | 订单查询/发货/关闭/备注 | ✅ 采纳(委托trade-service) |
| OMS - OmsOrderReturnApplyController | 退货申请管理 | ⏳ Phase 4 |
| OMS - OmsOrderReturnReasonController | 退货原因管理 | ⏳ Phase 4 |
| OMS - OmsOrderSettingController | 订单设置 | ⏳ Phase 4 |
| SMS - SmsCouponController | 优惠券管理 | ⏳ Phase 4 |
| SMS - SmsFlashPromotionController | 秒杀活动管理 | ⏳ Phase 4 |
| SMS - SmsHomeAdvertiseController | 首页广告管理 | ⏳ Phase 4 |
| CMS - CmsSubjectController | 专题管理 | ❌ 不采纳 |
| CMS - CmsPreferenceAreaController | 专题偏好管理 | ❌ 不采纳 |
| MinioController / OssController | 文件上传 | ⏳ Phase 4 |
| AgentQueryController | 数据查询代理 | ❌ 不采纳(nova特有) |

---

## 附录 B：前端页面规划对照表

| 页面 | 现有状态 | 改造/新增动作 | 对接后端接口 | 优先级 |
|------|---------|-------------|------------|--------|
| AdminLogin | ✅ 已有（复用 C 端登录） | 改造：对接 admin-service 登录 | `POST /admin/login` | P0 |
| AdminLayout | ✅ 已有（菜单硬编码） | 改造：动态菜单 + 用户信息 | `GET /admin/info` | P0 |
| Dashboard | ⚠️ Mock 数据 | 改造：对接统计 API（初期保留 Mock） | `GET /admin/stats/**`（Phase 4） | P3 |
| ItemManage | ✅ 已有（对接 hm-service） | 改造：对接 admin-service + 批量操作 | `GET/POST/PUT /admin/product/**` | P1 |
| OrderManage | ❌ 不存在 | 新增：订单列表/详情/批量发货/关闭 | `GET/POST /admin/order/**` | P1 |
| UserManage | ⚠️ Mock 数据 | 改造：对接真实用户管理 API | `GET/POST /admin/member/**` | P1 |
| AdminUserManage | ❌ 不存在 | 新增：管理员 CRUD + 角色分配 | `GET/POST /admin/admin/**` | P2 |
| RoleManage | ❌ 不存在 | 新增：角色 CRUD + 菜单/资源分配 | `GET/POST /admin/role/**` | P2 |
| MenuManage | ❌ 不存在 | 新增：菜单树 CRUD | `GET/POST /admin/menu/**` | P2 |
| ResourceManage | ❌ 不存在 | 新增：资源/权限 CRUD | `GET/POST /admin/resource/**` | P2 |
