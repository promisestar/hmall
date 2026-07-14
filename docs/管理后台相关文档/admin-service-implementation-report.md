# hmall 管理后台（admin-service）实现说明文档

> 版本：v1.0
> 日期：2026-07-14
> 设计文档：`docs/admin-service-design.md`

---

## 一、实现概况

本次实现在 hmall 项目中新增了管理后台后端微服务 `admin-service` 及对应的前端管理后台页面改造，涵盖 **RBAC 认证授权、商品管理、订单管理、C 端用户管理** 四大核心模块。

### 1.1 文件变更统计

| 类别 | 数量 | 说明 |
|------|------|------|
| admin-service 新增 Java 文件 | 56 | 含 Controller 8 个、Service 10 个、Mapper 8 个等 |
| admin-service 配置文件 | 4 | pom.xml + bootstrap.yml + application.yaml + application-local.yaml |
| admin-service SQL | 1 | hm-admin-schema.sql（8 张表 + 初始化数据） |
| admin-service 密钥库 | 1 | admin.jks（RSA 2048，JKS 格式，JDK 11 生成） |
| **后端新增合计** | **62** | — |
| item-service 修改 | 1 | ItemController.java（+ batch/status、batch/stock、batch 接口，206 行） |
| trade-service 修改 | 3 | OrderController.java（105 行）+ IOrderService.java（38 行）+ OrderServiceImpl.java（265 行） |
| user-service 修改 | 1 | UserController.java（+ page、{id}、status/{id}、balance/{id} 接口，133 行） |
| hm-common 修改 | 2 | PageDTO.java（泛型参数 R→S 重命名）+ WebUtils.java（废弃 API 替换） |
| 根 pom.xml 修改 | 1 | Lombook 1.18.20→1.18.34 + 新增 admin-service module |
| **前端新增** | 14 | API 模块 9 个 + 类型定义 1 个 + 页面 4 个 + 指令 1 个 |
| **前端修改** | 8 | stores/admin.ts + router/index.ts + main.ts + 5 个 admin 页面 |
| **总计改动** | **93** | — |

---

## 二、架构总览

### 2.1 微服务架构

```
前端 (Vue 3 + Vite 5)
  │  /api/admin/**  →  Vite proxy  →  Gateway (8080)  →  admin-service (8090)
  │                                       │
  │                              AuthGlobalFilter
  │                              (admin JWT 校验 / 白名单放行)
  │                                       │
  └───────────────────────────────────────┘

admin-service (8090)
  ├── AdminAuthInterceptor → 动态权限拦截（自认证，不依赖网关）
  ├── AdminJwtTool (独立 admin.jks 密钥库，RSA 非对称)
  ├── RBAC 直连 MySQL (hm-admin 库，8 张表)
  │     admin_user / role / menu / resource / resource_category
  │     admin_user_role_rel / role_menu_rel / role_resource_rel
  └── 业务管理通过 Feign 调用下游
        ├── ItemFeignClient  →  item-service (8081) → hm-item 库
        ├── TradeFeignClient → trade-service (8085) → hm-trade 库
        └── UserFeignClient  → user-service (8084) → hm-user 库
```

### 2.2 认证链路

```
登录:   POST /admin/login (白名单)
          → AdminAuthController.login()
            → 查 hm-admin.admin_user 表
            → BCryptPasswordEncoder.matches() 验证密码
            → AdminJwtTool.createToken(id, username) 签发 JWT
              (payload: sub=id, username, type=ADMIN, iat, exp, jti)
            → 返回 {token, tokenHead: "Bearer "}

后续请求: Authorization: Bearer xxx
          → AdminAuthInterceptor.preHandle()
            → AdminJwtTool.parseToken() 解析 JWT
            → Redis 黑名单检查 (admin:blacklist:{jti})
            → DynamicSecurityService 动态 URL 权限匹配
            → UserContext.setUser(adminId)
```

### 2.3 与 C 端认证的隔离

| 维度 | C 端（user-service） | B 端（admin-service） |
|------|---------------------|----------------------|
| 密钥库 | classpath:hmall.jks | classpath:admin.jks（独立生成） |
| JWT 类型标记 | 无 | `type: ADMIN` |
| 存储位置 | sessionStorage `token` | sessionStorage `admin-token` |
| axios 实例 | `api/index.ts` | `api/admin.ts`（独立） |
| 登录接口 | `POST /users/login` | `POST /admin/login` |
| 用户表 | `hm-user.user` | `hm-admin.admin_user` |
| 密码加密 | BCrypt | BCrypt（同算法，不同表） |

---

## 三、后端实现详情

### 3.1 admin-service 模块结构

```
admin-service/
├── pom.xml                                      # POM 依赖（继承 hmall 父 POM）
├── src/main/
│   ├── java/com/hmall/admin/
│   │   ├── AdminServiceApplication.java         # 启动类
│   │   ├── config/
│   │   │   ├── AdminJwtProperties.java          # admin JWT 配置属性
│   │   │   ├── AdminAuthProperties.java         # 权限白名单配置
│   │   │   ├── SecurityConfig.java              # BCrypt + admin KeyPair Bean
│   │   │   ├── MyBatisPlusConfig.java           # 分页插件
│   │   │   ├── MvcConfig.java                   # MVC 配置 + 拦截器注册
│   │   │   └── AdminInterceptorConfig.java      # DynamicSecurityService + AuthInterceptor Bean
│   │   ├── controller/
│   │   │   ├── AdminAuthController.java         # 登录/登出/信息/刷新
│   │   │   ├── AdminUserController.java         # 管理员 CRUD
│   │   │   ├── RoleController.java              # 角色 CRUD + 菜单/资源分配
│   │   │   ├── MenuController.java              # 菜单树管理
│   │   │   ├── ResourceController.java          # 资源(权限)管理
│   │   │   ├── ProductAdminController.java      # 商品管理(委托 item-service)
│   │   │   ├── OrderAdminController.java        # 订单管理(委托 trade-service)
│   │   │   └── MemberAdminController.java       # C端用户管理(委托 user-service)
│   │   ├── service/                             # 接口 5 个
│   │   ├── service/impl/                        # 实现 5 个
│   │   ├── mapper/                              # MyBatis Plus Mapper 8 个
│   │   ├── domain/
│   │   │   ├── po/                              # 持久化对象 8 个
│   │   │   ├── dto/                             # 数据传输对象 2 个
│   │   │   └── vo/                              # 视图对象 3 个
│   │   ├── interceptor/
│   │   │   └── AdminAuthInterceptor.java        # 管理员认证拦截器
│   │   ├── security/
│   │   │   ├── AdminJwtTool.java                # 独立 admin JWT 工具
│   │   │   └── DynamicSecurityService.java      # 动态权限数据源接口
│   │   └── feign/
│   │       ├── ItemFeignClient.java             # 商品服务 Feign 客户端
│   │       ├── TradeFeignClient.java            # 交易服务 Feign 客户端
│   │       ├── UserFeignClient.java             # 用户服务 Feign 客户端
│   │       └── fallback/                        # 降级工厂 3 个
│   └── resources/
│       ├── bootstrap.yml                        # Nacos 配置
│       ├── application-local.yaml               # 本地开发配置
│       ├── application.yaml                     # 应用配置（数据源、Redis、JWT）
│       ├── admin.jks                            # admin RSA 密钥库
│       ├── hm-admin-schema.sql                  # RBAC 建表 SQL + 初始化数据
│       └── nacos-config-guide.md                # 网关路由配置说明
└── src/test/
    └── java/com/hmall/admin/
        └── PasswordGen.java                     # BCrypt 哈希生成工具
```

### 3.2 RBAC 认证授权

#### 3.2.1 认证流程（AdminAuthServiceImpl）

| 方法 | 逻辑 |
|------|------|
| `login(dto)` | 查 admin_user → 校验状态 → BCrypt 比对 → 签发 JWT（含 jti）→ 更新 login_time |
| `logout(token)` | 提取 jti → 计算剩余 TTL → `admin:blacklist:{jti}` 写入 Redis（TTL = 剩余有效期） |
| `getAdminInfo(id)` | 查 admin_user → 查 admin_user_role_rel → 查 role → 组装菜单树 + 权限编码列表 |
| `refreshToken(oldToken)` | 解析旧 token 判断是否可续期（refreshWindow 内）→ 签发新 token |

#### 3.2.2 AdminJwtTool（独立 JWT 工具）

```java
// 核心方法
String createToken(Long adminId, String username);  // 签发（payload: sub/id, username, type=ADMIN, iat, exp, jti）
String getJti(String token);                         // 提取 JWT ID
long getRemainingTTL(String token);                  // 剩余有效时间（秒）
Long parseToken(String token);                       // 解析并返回 admin userId
String refreshToken(String token);                   // 判断是否在续期窗口内，是则签发新 token
```

**关键设计**：
- 使用 Hutool JWT 库（与现有服务统一）
- 独立 `admin.jks` 密钥库，RSA 2048 非对称加密
- JWT payload 标记 `type: ADMIN` 区分 C 端用户
- 每个 JWT 注入唯一 `jti`（UUID），用于登出黑名单

#### 3.2.3 AdminAuthInterceptor（认证拦截器）

```java
public boolean preHandle(request, response, handler) {
    1. 检查白名单 (AdminAuthProperties.excludePaths) → 放行
    2. 提取 Authorization 请求头 → 截取 Bearer 后的 token
    3. AdminJwtTool.parseToken() 解析 JWT → 提取 adminId
    4. Redis 黑名单检查 (admin:blacklist:{jti}) → 命中返回 401
    5. DynamicSecurityService.getDataSource() → 获取资源 URL 映射
    6. AntPathMatcher 匹配当前请求 URL → 查当前管理员角色拥有的资源
    7. 匹配成功 → UserContext.setUser(adminId) → 放行
    8. 不匹配 → 返回 403
}
```

#### 3.2.4 DynamicSecurityService（动态权限数据源）

```java
@Bean
public DynamicSecurityService dynamicSecurityService() {
    return () -> {
        Map<String, ConfigAttribute> map = new ConcurrentHashMap<>();
        List<Resource> resourceList = resourceService.listAll();
        for (Resource resource : resourceList) {
            map.put(resource.getUrl(),
                    new SecurityConfig(resource.getId() + ":" + resource.getName()));
        }
        return map;
    };
}
```

资源变更时主动刷新 Redis 缓存 `admin:resourceList`。

#### 3.2.5 Redis Key 设计

| Key | 类型 | TTL | 说明 |
|-----|------|-----|------|
| `admin:blacklist:{jti}` | String | 动态（token 剩余 TTL） | 登出 token 黑名单 |
| `admin:resourceList` | Hash | 无过期 | 全量资源 URL → 权限编码映射 |

### 3.3 业务管理（Feign 设计）

#### 3.3.1 商品管理（ProductAdminController → item-service）

| 接口 | 方法 | 实现方式 |
|------|------|---------|
| `GET /admin/product/list` | 分页查询 | `ItemFeignClient.queryItemByPage(pageQuery)` → item-service `/items/page` |
| `GET /admin/product/{id}` | 商品详情 | `ItemFeignClient.queryItemById(id)` → item-service `/items/{id}` |
| `POST /admin/product` | 新增商品 | `ItemFeignClient.saveItem(item)` → item-service `POST /items` |
| `PUT /admin/product/{id}` | 更新商品 | `ItemFeignClient.updateItem(item)` → item-service `PUT /items` |
| `DELETE /admin/product/{id}` | 删除商品 | `ItemFeignClient.deleteItemById(id)` → item-service `DELETE /items/{id}` |
| `POST /admin/product/publishStatus` | 批量上下架 | `ItemFeignClient.batchUpdateStatus(ids, status)` → item-service `PUT /items/batch/status` |
| `POST /admin/product/stock` | 批量库存调整 | `ItemFeignClient.batchUpdateStock(stockMap)` → item-service `PUT /items/batch/stock` |
| `DELETE /admin/product/batch` | 批量删除 | `ItemFeignClient.batchDeleteItems(ids)` → item-service `DELETE /items/batch` |

**item-service 扩展接口**（`ItemController.java` 新增）：
- `PUT /items/batch/status` — 批量修改商品状态（上下架），同步 ES 索引 + 清除 Redis 缓存
- `PUT /items/batch/stock` — 批量修改库存
- `DELETE /items/batch` — 批量逻辑删除

#### 3.3.2 订单管理（OrderAdminController → trade-service）

| 接口 | 方法 | 实现方式 |
|------|------|---------|
| `GET /admin/order/list` | 订单分页查询 | `TradeFeignClient.queryOrderByPage(...)` → trade-service `/orders/admin/page` |
| `GET /admin/order/{id}` | 订单详情 | `TradeFeignClient.queryOrderById(id)` → trade-service `/orders/{id}` |
| `POST /admin/order/delivery` | 批量发货 | `TradeFeignClient.batchDelivery(orderIds)` → trade-service `POST /orders/batch/delivery` |
| `POST /admin/order/close` | 批量关闭 | `TradeFeignClient.batchCloseOrders(orderIds)` → trade-service `POST /orders/batch/close` |
| `POST /admin/order/{id}/note` | 修改备注+状态 | `TradeFeignClient.updateNote(id, note, status)` → trade-service `POST /orders/{id}/note` |

**trade-service 扩展接口**：
- `GET /orders/admin/page` — 管理后台分页查询（支持 status、orderId、startTime、endTime 筛选）
- `POST /orders/batch/delivery` — 批量发货（状态改为 3 + 记录发货时间）
- `POST /orders/batch/close` — 批量关闭订单（状态改为 5）
- `POST /orders/{id}/note` — 修改订单备注和状态

**订单状态映射**：

| 状态值 | 含义 | 管理操作 |
|--------|------|---------|
| 1 | 未付款 | 关闭订单 |
| 2 | 已付款，未发货 | **发货**（管理后台核心操作） |
| 3 | 已发货，未确认 | — |
| 4 | 确认收货，交易成功 | — |
| 5 | 交易取消，订单关闭 | — |
| 6 | 交易结束，已评价 | — |

#### 3.3.3 C 端用户管理（MemberAdminController → user-service）

| 接口 | 方法 | 实现方式 |
|------|------|---------|
| `GET /admin/member/list` | 用户分页查询 | `UserFeignClient.queryUserByPage(...)` → user-service `/users/page` |
| `GET /admin/member/{id}` | 用户详情 | `UserFeignClient.queryUserById(id)` → user-service `/users/{id}` |
| `POST /admin/member/status/{id}` | 修改用户状态 | `UserFeignClient.updateStatus(id, status)` → user-service `POST /users/status/{id}` |
| `POST /admin/member/balance/{id}` | 调整用户余额 | `UserFeignClient.updateBalance(id, delta)` → user-service `POST /users/balance/{id}` |

**user-service 扩展接口**（`UserController.java` 新增）：
- `GET /users/page` — C 端用户分页查询（支持 keyword、status 筛选，自动清除密码字段）
- `GET /users/{id}` — 用户详情
- `POST /users/status/{id}` — 修改用户状态（正常/冻结）
- `POST /users/balance/{id}` — 调整用户余额（正数充值/负数扣减，防负余额）

#### 3.3.4 Feign 降级策略

三个 Feign 客户端均配置 `FallbackFactory`：

| 降级工厂 | 降级行为 |
|----------|---------|
| `ItemFeignFallbackFactory` | 分页返回空 PageDTO，CRUD 操作返回 `R.error("商品服务暂时不可用")` |
| `TradeFeignFallbackFactory` | 分页返回空 PageDTO，操作返回 `R.error("订单服务暂时不可用")` |
| `UserFeignFallbackFactory` | 分页返回空 PageDTO，操作返回 `R.error("用户服务暂时不可用")` |

### 3.4 数据库设计

独立管理库 `hm-admin`，仅存储 RBAC 相关 8 张表：

| 表名 | 说明 | 关键字段 |
|------|------|---------|
| `admin_user` | 管理员表 | username(UNIQUE), password(BCrypt), status, login_time |
| `role` | 角色表 | name, admin_count, status, sort |
| `menu` | 菜单表 | parent_id, title, level, sort, name, icon, hidden |
| `resource` | 资源(权限)表 | name, url(Ant路径), category_id |
| `resource_category` | 资源分类表 | name |
| `admin_user_role_rel` | 管理员-角色关联 | UK(admin_user_id, role_id) |
| `role_menu_rel` | 角色-菜单关联 | UK(role_id, menu_id) |
| `role_resource_rel` | 角色-资源关联 | UK(role_id, resource_id) |

**初始化数据**：
- 超级管理员：`admin` / `admin123`（BCrypt 加密，需通过 `PasswordGen.java` 生成真实哈希）
- 超级管理员角色：`name=超级管理员`，拥有全部权限
- 关联：admin_user_role_rel (1, 1)

### 3.5 admin.jks 密钥库

| 参数 | 值 |
|------|-----|
| 格式 | JKS（显式指定 `-storetype JKS`，兼容 JDK 11 `KeyStore.getInstance("JKS")`） |
| 别名 | `admin` |
| 密码 | `admin123` |
| 算法 | RSA 2048 |
| 有效期 | 10 年（3650 天） |
| 生成工具 | JDK 11 `keytool` |
| 使用者 | `CN=admin-service, OU=hmall, O=heima, L=Beijing, ST=Beijing, C=CN` |

---

## 四、前端实现详情

### 4.1 文件变更清单

#### 4.1.1 新增文件（14 个）

| 文件 | 类别 | 说明 |
|------|------|------|
| `src/api/admin.ts` | API 实例 | admin 专属 axios（独立 token、baseURL、401 处理） |
| `src/api/admin/auth.ts` | API 模块 | 管理员认证 API（登录/登出/信息/刷新） |
| `src/api/admin/adminUser.ts` | API 模块 | 管理员管理 API |
| `src/api/admin/role.ts` | API 模块 | 角色管理 API |
| `src/api/admin/menu.ts` | API 模块 | 菜单管理 API |
| `src/api/admin/resource.ts` | API 模块 | 资源管理 API |
| `src/api/admin/product.ts` | API 模块 | 商品管理 API |
| `src/api/admin/order.ts` | API 模块 | 订单管理 API |
| `src/api/admin/member.ts` | API 模块 | C 端用户管理 API |
| `src/types/admin.ts` | 类型定义 | admin 相关 TypeScript 类型（11 个接口） |
| `src/directives/permission.ts` | 指令 | v-permission 按钮级权限指令 |
| `src/views/admin/OrderManage.vue` | 页面 | 订单管理页 |
| `src/views/admin/OrderDetail.vue` | 页面 | 订单详情页 |
| `src/views/admin/system/` | 页面 | RBAC 4 个管理页面 |

#### 4.1.2 修改文件（8 个）

| 文件 | 操作 | 改动要点 |
|------|------|---------|
| `src/stores/admin.ts` | **重写** | 对接 admin-service 登录/权限/菜单，async logout |
| `src/router/index.ts` | 修改 | 新增 RBAC 路由 + `requiresAdmin` 权限守卫 + adminInfo 加载 |
| `src/main.ts` | 修改 | 注册 `v-permission` 指令 |
| `src/views/admin/AdminLogin.vue` | 修改 | 对接 `POST /admin/login` |
| `src/views/admin/AdminLayout.vue` | 修改 | 动态菜单渲染 + 面包屑（修复 v-for+v-if 冲突） |
| `src/views/admin/ItemManage.vue` | **重写** | 对接 admin-service 商品管理 + 批量上下架/删除 |
| `src/views/admin/UserManage.vue` | **重写** | 删除 Mock，对接真实 API + 状态切换 + 余额调整 |
| `src/views/admin/Dashboard.vue` | 修改 | echarts 导入方式修复（echarts 6 兼容性） |

### 4.2 认证隔离设计

#### 4.2.1 admin 专属 axios 实例（api/admin.ts）

```typescript
// 与 C 端 api/index.ts 的关键区别
const adminInstance = axios.create({
  baseURL: '/api',           // 统一代理前缀
  timeout: 15000,
})

// 请求拦截：读取 sessionStorage 'admin-token'（非 'token'）
adminInstance.interceptors.request.use((config) => {
  const token = sessionStorage.getItem('admin-token')  // 独立 key
  if (token) config.headers.authorization = token
})

// 响应拦截：401 时清空 admin 状态并跳转登录页（location.hash 避免循环依赖）
adminInstance.interceptors.response.use(
  (response) => { /* 解包 R<T> + token 续期 */ },
  (error) => {
    if (error.response?.status === 401) {
      sessionStorage.removeItem('admin-token')
      location.hash = '#/admin/login'    // 不走 Vue Router，打破循环依赖
    }
  }
)
```

**关键设计**：使用 `location.hash` 替代 `router.push()`，避免 `api/admin.ts → router/index.ts → stores/admin.ts → api/admin/auth.ts → api/admin.ts` 的循环模块依赖导致模块初始化失败白屏。

#### 4.2.2 admin store（stores/admin.ts）

| 字段/方法 | 说明 |
|-----------|------|
| `adminToken` | 初始化自 `sessionStorage.getItem('admin-token')` |
| `adminInfo` | 初始化自 `sessionStorage.getItem('admin-info')`（JSON 反序列化） |
| `menus` | 动态菜单数组（每次 reload 重新从后端获取） |
| `permissions` | 权限编码数组（如 `["product:create", "*"]`） |
| `login(dto)` | 调 `adminLogin()` → 存 token → 调 `fetchAdminInfo()` |
| `fetchAdminInfo()` | 调 `getAdminInfo()` → 存 menus + permissions + adminInfo |
| `logout()` | 调 `adminLogout()` → 清除 sessionStorage |
| `hasRoutePermission(path)` | 检查菜单路由可见性 |
| `hasPermission(code)` | 检查按钮级操作权限（含 `*` 超管通配） |

### 4.3 路由守卫

```typescript
router.beforeEach(async (to, _from, next) => {
  // C 端鉴权（已有，保持不变）
  if (to.meta.requiresAuth && !userStore.isLogin)
    return next('/portal/login')

  // 管理后台鉴权
  if (to.meta.requiresAdmin && !adminStore.isAdminLogin)
    return next('/admin/login')

  // 首次进入后台：加载管理员信息和权限
  if (to.meta.requiresAdmin && adminStore.isAdminLogin && !adminStore.menus.length) {
    await adminStore.fetchAdminInfo()
  }

  next()
})
```

### 4.4 页面功能概要

| 页面 | 路由 | 核心功能 |
|------|------|---------|
| AdminLogin | `/admin/login` | 用户名/密码登录，调用 `POST /admin/login`，成功后加载菜单权限 |
| AdminLayout | `/admin` | 侧边栏动态菜单（menu 表返回）+ 面包屑 + 顶栏用户信息 + 退出 |
| Dashboard | `/admin/dashboard` | 统计卡片（今日订单/新增用户/销售额/商品总数）+ echarts 折线图+饼图 |
| ItemManage | `/admin/items` | 商品分页/搜索/新增/编辑/删除 + 批量上下架/删除 |
| OrderManage | `/admin/orders` | 订单分页/筛选（状态/订单号/时间）+ 批量发货/关闭 + 详情弹窗 |
| OrderDetail | `/admin/orders/:id` | 订单详情（商品明细 + 收货信息 + 状态） |
| UserManage | `/admin/users` | C 端用户分页/搜索 + 状态切换（正常/冻结）+ 余额调整 |
| AdminUserManage | `/admin/system/admin` | 管理员 CRUD + 角色分配（弹窗多选） |
| RoleManage | `/admin/system/role` | 角色 CRUD + 菜单分配 + 资源分配 |
| MenuManage | `/admin/system/menu` | 菜单树管理（树形表格 CRUD） |
| ResourceManage | `/admin/system/resource` | 资源/权限管理（CRUD + 分类筛选） |

### 4.5 v-permission 按钮级权限指令

```typescript
// directives/permission.ts
export const permission: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const adminStore = useAdminStore()
    const codes = Array.isArray(binding.value) ? binding.value : [binding.value]
    const hasPerm = codes.some(code => adminStore.hasPermission(code))
    if (!hasPerm) el.parentNode?.removeChild(el)  // 无权限直接移除 DOM
  }
}

// main.ts 注册
app.directive('permission', permission)
```

使用示例：
```vue
<el-button v-permission="'product:create'" @click="openAddDialog">新增商品</el-button>
<el-button v-permission="'product:delete'" type="danger" @click="handleDelete">删除</el-button>
```

---

## 五、配置说明

### 5.1 admin-service 配置（application.yaml）

```yaml
server:
  port: 8090

spring:
  datasource:
    url: jdbc:mysql://${hm.db.host:192.168.100.128}:3306/hm-admin
    username: ${hm.db.username:root}
    password: ${hm.db.password:123}
  redis:
    host: ${REDIS_HOST:192.168.100.128}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
    database: 0

hm:
  admin:
    jwt:
      location: classpath:admin.jks
      alias: admin
      password: ${ADMIN_JWT_KEYSTORE_PASSWORD:admin123}
      tokenTTL: 2h
      refreshWindow: 30m
    auth:
      excludePaths:
        - /admin/login
        - /admin/register
        - /doc.html
        - /doc.html/**
        - /webjars/**
        - /swagger-resources/**
        - /v2/api-docs
        - /v3/api-docs
        - /favicon.ico

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: auto

feign:
  okhttp:
    enabled: true
```

### 5.2 Nacos 配置（bootstrap.yml）

```yaml
spring:
  application:
    name: admin-service
  profiles:
    active: dev
  cloud:
    nacos:
      server-addr: 192.168.100.128:8848
      config:
        file-extension: yaml
        shared-configs:
          - dataId: shared-jdbc.yaml
          - dataId: shared-log.yaml
          - dataId: shared-swagger.yaml
```

### 5.3 父 POM 关键版本

| 依赖 | 版本 | 备注 |
|------|------|------|
| Spring Boot | 2.7.12 | 父 POM 继承 |
| Spring Cloud | 2021.0.3 | |
| Spring Cloud Alibaba | 2021.0.4.0 | |
| MyBatis Plus | 3.4.3 | |
| Lombok | **1.18.34** | 升级以兼容 JDK 21+ |
| Hutool | 5.8.11 | JWT + 工具类 |
| Knife4j | 4.1.0 | 统一由 hm-common 传递（openapi2-spring-boot-starter） |

### 5.4 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `hm.db.host` | `192.168.100.128` | MySQL 地址 |
| `hm.db.username` | `root` | MySQL 用户名 |
| `hm.db.password` | `123` | MySQL 密码 |
| `REDIS_HOST` | `192.168.100.128` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | 空 | Redis 密码（无密码不填） |
| `ADMIN_JWT_KEYSTORE_PASSWORD` | `admin123` | admin.jks 密钥库密码 |

---

## 六、关键技术决策与修复记录

### 6.1 决策：admin-service 自认证 vs 网关认证

**决策**：admin-service 自带 `AdminAuthInterceptor` 完成 JWT 校验和动态权限拦截，**不修改网关** `AuthGlobalFilter`。

**理由**：更解耦，admin-service 拥有独立的认证逻辑（独立密钥库、动态 URL 权限），不需要网关配置 admin 专用 JWT 属性。网关只需在 Nacos 添加 admin-service 路由并放行 `/admin/login` 白名单。

### 6.2 决策：hm-common 包扫描

**决策**：`AdminServiceApplication` 添加 `@ComponentScan({"com.hmall.admin", "com.hmall.common"})` 显式扫描公共模块。

**理由**：`hm-common` 的 `RedisService`、`MvcConfig` 等 Bean 通过 `spring.factories` 自动装配和 `@ComponentScan` 两条路径加载。当 `spring.factories` 未生效（如 hm-common 未 install 到本地仓库）时，`@ComponentScan` 作为兜底保障。

### 6.3 修复：Lombok 1.18.20 → 1.18.34

**问题**：构建时报 `NoSuchFieldError: JCImport.qualid`，因 IDE 的 Maven Runner 使用 JDK 23，而 Lombok 1.18.20 仅支持到 JDK 14。

**修复**：根 `pom.xml` 中 `org.projectlombok.version` 升级至 1.18.34（兼容 JDK 11-23）。

### 6.4 修复：admin.jks 格式兼容性

**问题**：JDK 9+ 的 `keytool` 默认生成 PKCS12 格式，文件名为 `.jks` 但实际格式不符，`KeyStore.getInstance("JKS")` 加载失败。

**修复**：用 JDK 11 的 `keytool` 重新生成，显式指定 `-storetype JKS`。

### 6.5 修复：前端循环模块依赖导致白屏

**问题链**：
```
router/index.ts
  → stores/admin.ts
    → api/admin/auth.ts
      → api/admin.ts
        → import router from '@/router'  ← 循环！
```

Vite/ESM 中 `router` 在模块初始化时为未完全初始化的代理对象，运行时抛出异常导致 Vue 应用崩溃白屏。

**修复**：`api/admin.ts` 中移除 `import router from '@/router'`，改用 `location.hash = '#/admin/login'` 做 401 跳转，打破循环依赖。

### 6.6 修复：AdminLayout.vue v-for + v-if 导致白屏

**问题**：Vue 3 中 `v-if` 优先级高于 `v-for`，以下代码执行 `v-if="!child.hidden"` 时 `child` 尚未被 `v-for` 定义：

```vue
<el-menu-item v-for="child in menu.children" v-if="!child.hidden" ... />
```

**修复**：用 `<template v-for>` 包裹，`v-if` 下移到内部 `<el-menu-item>` 上。

### 6.7 修复：BCrypt 哈希截断

**问题**：`hm-admin-schema.sql` 中初始管理员密码哈希值仅为 55 字符，标准 BCrypt 哈希应为 60 字符，`BCryptPasswordEncoder.matches()` 校验必然失败。

**修复**：提供 `PasswordGen.java` 工具类，运行 main 方法生成真实 60 字符 BCrypt 哈希后替换 SQL 中的占位值。

### 6.8 修复：PageDTO 泛型参数遮蔽警告

**问题**：`PageDTO.java` 中方法泛型参数 `R` 遮蔽同包下的 `R<T>` 响应泛型类，产生编译警告。

**修复**：将方法泛型参数 `R` 重命名为 `S`（纯占位符改名，调用方无影响）。

### 6.9 修复：Java 11 API 兼容性

**问题**：`Stream.toList()`（Java 16+）在 JDK 11 项目中不可用。

**修复**：替换为 `.collect(Collectors.toList())`。

---

## 七、部署指引

### 7.1 启动流程

```
1. 启动基础设施
   ├── MySQL 8.0+ (192.168.100.128:3306)
   ├── Redis 6.x+ (192.168.100.128:6379)
   └── Nacos 2.x (192.168.100.128:8848)

2. 创建数据库
   执行 hm-admin-schema.sql → 创建 hm-admin 库 + 8 张表 + 初始化超级管理员

3. 生成 BCrypt 密码哈希
   运行 PasswordGen.main() → 复制输出的 60 字符哈希 → 替换 SQL 中 admin 的 password

4. 构建所有模块
   mvn clean install -DskipTests    # 在 hmall 根目录执行

5. Nacos 配置
   gateway-routes.json 中追加 admin-service 路由（见 nacos-config-guide.md）

6. 启动服务
   按顺序: hm-gateway → admin-service → item-service → trade-service → user-service

7. 启动前端
   cd hmall-frontend && npm run dev
```

### 7.2 默认管理员账号

| 字段 | 值 |
|------|-----|
| 用户名 | `admin` |
| 密码 | `admin123`（BCrypt 加密存储） |
| 角色 | 超级管理员（拥有全部菜单和权限） |

### 7.3 启动检查清单

- [ ] MySQL `hm-admin` 库已创建，8 张表已建
- [ ] admin 用户 BCrypt 密码哈希为有效 60 字符（通过 PasswordGen 生成）
- [ ] `admin.jks` 存在且格式为 JKS（`keytool -list -keystore admin.jks -storetype JKS` 验证）
- [ ] `hm-common` 已 `mvn install` 到本地仓库（`~/.m2/repository/com/heima/hm-common/`）
- [ ] Nacos 中 `shared-jdbc.yaml` 配置可访问
- [ ] Redis 服务运行中（`redis-cli PING` → `PONG`）
- [ ] admin-service 启动日志无 `RedisConnectionFailureException`
- [ ] 前端控制台无 `Failed to fetch dynamically imported module` 错误
- [ ] 管理后台登录：`POST /admin/login` → 返回 `{code: 200, data: {token, tokenHead}}`
- [ ] 获取管理员信息：`GET /admin/info` → 返回菜单树 + 权限列表
- [ ] 商品管理：`GET /admin/product/list` → 正常分页
- [ ] 订单管理：`GET /admin/order/list` → 正常分页

---

## 八、已知问题与后续优化

### 8.1 动态菜单首次加载延迟

**现象**：首次进入后台时路由守卫同步等待 `fetchAdminInfo()`，若 admin-service 响应慢，导航会停顿数秒。

**缓解**：`adminToken` 存在但不调用 `fetchAdminInfo()` 时仍允许渲染 AdminLayout（空菜单），`fetchAdminInfo()` 改为异步非阻塞，完成后响应式更新菜单。

**当前状态**：未实现，作为后续优化项。

### 8.2 共享 Redis 配置缺失

**现象**：admin-service 的 Redis 配置写在 `application.yaml` 中，未使用 Nacos 共享配置 `shared-redis.yaml`。其他服务通过 `shared-redis.yaml` 配置。

**影响**：环境切换时需要分别维护 Redis 配置。

**当前状态**：已配置为本地 `application.yaml` 直连，功能正常。后续可统一到 Nacos `shared-redis.yaml`。

### 8.3 权限缓存实时性

**现象**：资源/角色变更时需手动清除 Redis 缓存 `admin:resourceList`。

**缓解**：在 `RoleController.allocResources()` 和 `RoleController.allocMenus()` 方法中增加主动清除缓存逻辑。

**当前状态**：已清除缓存逻辑就位，重启服务后自动重新加载。

### 8.4 Dashboard 数据为 Mock

**现象**：Dashboard 统计卡片和图表数据均为前端 Mock，未对接后端统计 API。

**影响**：数据概览不反映真实业务数据。

**当前状态**：Phase 4 扩展计划。

---

## 九、与本仓库其他文档的关联

| 文档 | 关系 |
|------|------|
| `docs/admin-service-design.md` | **设计文档**：本文档的源头，描述整体架构设计和接口规划 |
| `docs/redis-integration-report.md` | **参考文档**：本文档的编写风格和结构参考 |
| `admin-service/src/main/resources/nacos-config-guide.md` | **配置指南**：网关路由追加和 Nacos 配置说明 |
| `admin-service/src/main/resources/hm-admin-schema.sql` | **SQL 脚本**：RBAC 建表 DDL + 初始化数据 |
| `admin-service/src/test/java/.../PasswordGen.java` | **工具类**：BCrypt 哈希生成工具 |

---

> **实现完成度**：Phase 1（基础框架与认证）、Phase 2（商品与订单管理）、Phase 3（用户管理与 RBAC）核心功能全部实现。Phase 4（营销管理、内容管理、文件上传、数据统计看板）为后续扩展。
