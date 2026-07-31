# hmall 前端实现说明文档

> 版本：v1.1
> 日期：2026-07-31
> 设计文档：`docs/hmall_Agent相关文档/`、`docs/管理后台相关文档/`、`docs/秒杀功能实现/`

---

## 一、实现概况

hmall（枫叶商城）前端基于 **Vue 3 + Vite 5 + TypeScript + Element Plus + TailwindCSS** 构建，采用 **Hash 路由 + Pinia 状态管理**，覆盖 **C 端商城**（13 个页面）和 **管理后台**（12 个页面）两大场景，并集成了基于 LangGraph SDK 的 **AI Agent 助手**。

### 1.1 文件统计

| 类别 | 数量 | 说明 |
|------|------|------|
| Vue 页面组件 | 35 | 含 C 端 15 个 + 管理后台 12 个 + 公共/聊天组件 8 个 |
| TypeScript 模块 | 29 | 含 API 19 个 + Store 3 个 + 类型/路由/工具/指令/Composable 7 个 |
| 样式文件 | 1 | TailwindCSS + 全局样式 + 组件类 |
| 配置文件 | 8 | package.json + vite/tailwind/postcss/tsconfig x4 + index.html |
| 静态资源 | 93 | 商品图片、SVG 图标等 |
| **总计** | **166** | — |

---

## 二、技术栈

### 2.1 核心依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Vue | ^3.5.12 | 前端框架（Composition API） |
| Vite | ^5.4.10 | 构建工具 |
| TypeScript | ~5.6.2 | 类型系统 |
| Pinia | ^3.0.4 | 状态管理 |
| Vue Router | ^4.6.4 | 路由（Hash 模式） |
| Element Plus | ^2.14.2 | UI 组件库 |
| TailwindCSS | ^3.4.17 | 原子化 CSS 框架 |
| Axios | ^1.18.1 | HTTP 客户端 |
| ECharts | ^6.1.0 | 图表库（管理后台 Dashboard） |
| @langchain/langgraph-sdk | ^1.0.3 | AI Agent SDK（流式对话） |
| marked | ^18.0.6 | Markdown 渲染（Agent 消息） |
| lucide-vue-next | ^1.0.0 | 图标库（Agent 聊天 UI） |
| qrcode | ^1.5.4 | 二维码生成 |

### 2.2 工程化配置

| 配置项 | 说明 |
|--------|------|
| 包管理器 | npm（package-lock.json） |
| 路由模式 | Hash 路由（`createWebHashHistory`），兼容静态部署 |
| 代理 | Vite proxy `/api` → `http://localhost:8080`（网关） |
| 路径别名 | `@` → `src/` |
| 模块系统 | ES Module（`"type": "module"`） |
| CSS 方案 | TailwindCSS 3 + 全局 `style.css`（CSS 自定义属性） |

---

## 三、项目结构

```
hmall-frontend/
├── index.html                        # HTML 入口（枫叶商城标题）
├── package.json                      # 项目依赖与脚本
├── vite.config.ts                    # Vite 配置（代理、别名、端口）
├── tailwind.config.js                # TailwindCSS 配置
├── tsconfig.json / tsconfig.app.json / tsconfig.node.json
│
├── public/                           # 静态资源（商品图片 92 个）
│
└── src/
    ├── main.ts                       # 应用入口（注册 Pinia/Router/ElementPlus/图标/指令）
    ├── App.vue                       # 根组件（<router-view>）
    ├── style.css                     # Tailwind 指令 + 全局样式 + 组件类
    │
    ├── api/                          # API 层
    │   ├── index.ts                  # C 端 axios 实例（token → 解包 → 续期 → 401）
    │   ├── admin.ts                  # 管理端 axios 实例（admin-token → 解包 R<T> → 401/403）
    │   ├── user.ts                   # 用户 API（登录/登出/验证码/扣款）
    │   ├── item.ts                   # 商品 API（CRUD/搜索/聚合/库存）
    │   ├── cart.ts                   # 购物车 API（CRUD/批量删除）
    │   ├── order.ts                  # 订单 API（创建/分页/详情）
    │   ├── pay.ts                    # 支付 API（创建支付单/支付/查询）
    │   ├── address.ts                # 地址 API（CRUD + 默认设置）
    │   ├── seckill.ts                # 秒杀 API（活动/商品/下单/轮询）
    │   └── admin/                    # 管理后台 API 模块
    │       ├── auth.ts               #   管理员认证（登录/登出/信息/刷新）
    │       ├── adminUser.ts          #   管理员 CRUD
    │       ├── role.ts               #   角色管理
    │       ├── menu.ts               #   菜单管理
    │       ├── resource.ts           #   资源/权限管理
    │       ├── product.ts            #   商品管理
    │       ├── order.ts              #   订单管理
    │       ├── member.ts             #   C 端用户管理
    │       └── seckill.ts            #   秒杀管理
    │
    ├── stores/                       # Pinia 状态管理
    │   ├── user.ts                   # C 端用户状态（token/userInfo/登录/登出）
    │   ├── cart.ts                   # 购物车状态（列表/勾选/价格/数量）
    │   └── admin.ts                  # 管理后台状态（token/adminInfo/菜单/权限）
    │
    ├── types/                        # TypeScript 类型定义
    │   ├── index.ts                  # C 端类型（Item/Cart/Order/Pay/Address/Search 等 19 个接口）
    │   └── admin.ts                  # 管理端类型（Admin/Role/Menu/Resource/Seckill 等 20 个接口）
    │
    ├── router/
    │   └── index.ts                  # Hash 路由表（22 条）+ 双端权限守卫
    │
    ├── directives/
    │   └── permission.ts             # v-permission 按钮级权限指令
    │
    ├── utils/
    │   └── format.ts                 # 工具函数（价格/日期格式化、URL 参数解析）
    │
    ├── composables/
    │   └── useLangGraph.ts           # LangGraph Agent Composable（对话/中断/会话管理）
    │
    ├── components/
    │   └── chat/                     # Agent 聊天组件
    │       ├── ChatPanel.vue         #   聊天面板（消息列表 + 输入框）
    │       ├── ChatWidget.vue        #   聊天悬浮小部件
    │       ├── MessageBubble.vue     #   消息气泡（Markdown 渲染）
    │       └── InterruptActions.vue  #   中断操作按钮（确认/取消）
    │
    └── views/                        # 页面组件
        ├── portal/                   # C 端商城页面（15 个）
        │   ├── PortalLayout.vue      #   商城布局（顶栏 + 内容区）
        │   ├── HomePage.vue          #   首页（商品推荐）
        │   ├── SearchPage.vue        #   搜索页（筛选 + 排序 + 分页）
        │   ├── ProductDetail.vue     #   商品详情页
        │   ├── LoginPage.vue         #   登录页（密码/验证码双 Tab）
        │   ├── CartPage.vue          #   购物车页
        │   ├── OrderConfirm.vue      #   结算页
        │   ├── OrderList.vue         #   订单列表页
        │   ├── PayPage.vue           #   支付页
        │   ├── PaySuccess.vue        #   支付成功页
        │   ├── AddressList.vue       #   地址管理页
        │   ├── UserProfile.vue       #   个人中心页
        │   ├── SeckillList.vue       #   秒杀活动列表页
        │   ├── SeckillDetail.vue     #   秒杀详情页
        │   └── ChatPage.vue          #   C 端 AI 助手页
        │
        └── admin/                    # 管理后台页面（12 个）
            ├── AdminLayout.vue       #   后台布局（侧边栏 + 顶栏）
            ├── AdminLogin.vue        #   后台登录页
            ├── Dashboard.vue         #   数据看板（ECharts）
            ├── ItemManage.vue        #   商品管理
            ├── OrderManage.vue       #   订单管理
            ├── OrderDetail.vue       #   订单详情
            ├── UserManage.vue        #   C 端用户管理
            ├── SeckillManage.vue     #   秒杀管理（4 Tab）
            ├── ChatPage.vue          #   管理端 AI 助手页
            └── system/               #   RBAC 系统管理
                ├── AdminUserManage.vue   #   管理员管理
                ├── RoleManage.vue        #   角色管理
                ├── MenuManage.vue        #   菜单管理
                └── ResourceManage.vue    #   资源管理
```

---

## 四、C 端商城实现

### 4.1 路由设计

C 端使用 `/portal/*` 命名空间，共 **13 个路由**（含 1 个聊天页），支持 Hash 模式：

| 路由 | 页面 | 鉴权 | 说明 |
|------|------|------|------|
| `/portal/home` | HomePage | — | 首页 |
| `/portal/product/:itemId` | ProductDetail | — | 商品详情 |
| `/portal/search` | SearchPage | — | 搜索筛选 |
| `/portal/seckill` | SeckillList | — | 秒杀活动列表 |
| `/portal/seckill/:relationId` | SeckillDetail | ✅ requiresAuth | 秒杀详情（需登录） |
| `/portal/login` | LoginPage | — | 双 Tab 登录 |
| `/portal/cart` | CartPage | ✅ requiresAuth | 购物车 |
| `/portal/order` | OrderConfirm | ✅ requiresAuth | 结算确认 |
| `/portal/orders` | OrderList | ✅ requiresAuth | 订单列表 |
| `/portal/address` | AddressList | ✅ requiresAuth | 地址管理 |
| `/portal/profile` | UserProfile | ✅ requiresAuth | 个人中心 |
| `/portal/pay/:orderId` | PayPage | ✅ requiresAuth | 收银台 |
| `/portal/pay-success/:orderId` | PaySuccess | ✅ requiresAuth | 支付成功 |
| `/portal/chat` | ChatPage | — | AI 助手 |

### 4.2 认证体系

#### 4.2.1 C 端 axios 实例（api/index.ts）

```
请求拦截：从 sessionStorage 'token' 读取 → 注入 Authorization 头
响应拦截：
  ├── 200: 解包 response.data → 检测 X-New-Token 响应头（Gateway 续期）→ 自动更新 sessionStorage
  └── 401: 清除 token → 跳转 /portal/login
```

#### 4.2.2 User Store（stores/user.ts）

```
状态:
  token         → sessionStorage 'token'（Bearer token）
  userInfo      → sessionStorage 'user-info'（JSON: id/username/balance）

方法:
  login(dto)          → POST /users/login → saveLoginResult
  loginByCode(dto)    → POST /users/login/code → saveLoginResult
  logout()            → POST /users/logout（token 黑名单）→ clearState
  setUserInfo(info)   → 更新余额等字段（支付后同步）
```

**关键设计**：token 存 sessionStorage（非 Cookie），关闭标签页即失效。登出时先调后端 `/users/logout` 将 `jti` 写入 Redis 黑名单（TTL = 令牌剩余有效期），再清除本地状态。

### 4.3 核心业务链路

#### 4.3.1 完整购物链路

```
首页/搜索 → 商品详情 → 加购（POST /carts）
  → CartPage（勾选商品、修改数量）
  → OrderConfirm（选择地址、确认金额）→ POST /orders → 返回 orderId
  → PayPage（创建支付单 POST /pay-orders → 余额支付 POST /pay-orders/{id}）
  → PaySuccess（显示订单号，引导回首页）
```

#### 4.3.2 商品搜索链路

```
SearchPage:
  1. 关键词输入 / 分类筛选 / 品牌筛选 / 价格区间
  2. POST /search/filters → 获取可选分类/品牌聚合值
  3. GET /search/list → ES 搜索（分页 + 排序 + 聚合过滤）
  4. GET /search/suggestion → 搜索建议（自动补全）
```

#### 4.3.3 秒杀链路

```
SeckillList → 选择活动 → 切换场次 → 库存进度条
  → SeckillDetail（倒计时）→ 立即秒杀 POST /seckill/order/{relationId}
  → 轮询 GET /seckill/result/{relationId}（最多 45s）
  → success → 跳转支付 / failed → 提示失败
```

#### 4.3.4 验证码登录

```
LoginPage:
  Tab "验证码登录" → 输入手机号 → 获取验证码（POST /users/code）→ 60s 倒计时
  → 输入验证码 → POST /users/login/code → 签发 JWT → 跳转首页
```

### 4.4 购物车状态管理

| 计算属性 | 说明 |
|----------|------|
| `checkedItems` | 已勾选商品列表 |
| `totalPrice` | 已勾选商品总价（分） |
| `totalNum` | 已勾选商品总数量 |
| `cartNum` | 全部商品总数量（顶栏 Badge） |

**关键操作**：
- `fetchCartList()`：从后端加载购物车（仅 `cartList.length === 0` 时触发，避免覆盖本地 checked 状态）
- `addToCart(data)`：加购后全量刷新列表
- `toggleCheckAll(checked)`：本地全选/取消（不调后端）
- `clearCart()`：下单成功后前端同步清空（MQ 异步清空后端）

### 4.5 雪花 ID 精度保护

| 字段 | 类型 | 原因 |
|------|------|------|
| `OrderVO.id` | `string` | 订单 ID（后端 Long → JSON String → 前端 string） |
| `PayOrderVO.id` / `.bizOrderNo` | `string` | 支付单 ID / 业务订单号 |
| `PayApplyDTO.bizOrderNo` | `string` | 支付申请的业务订单号 |
| `PayOrderFormDTO.id` | `string` | 支付表单的支付单 ID |

**`applyPayOrder` 特殊处理**：后端返回裸字符串 → Axios `responseType: 'text'`，防止 `JSON.parse()` 将 19 位数字解析为 JS Number 导致精度丢失。

### 4.6 工具函数（utils/format.ts）

| 函数 | 说明 |
|------|------|
| `formatPrice(val)` | 价格分→元，返回 `"x.xx"` 字符串 |
| `priceToInt(val)` | 元字符串→分，返回 `number` |
| `getUrlParam(name)` | 获取 URL 查询参数 |
| `formatDate(dateStr, fmt)` | 日期格式化（默认 `yyyy-MM-dd HH:mm:ss`） |

---

## 五、管理后台实现

### 5.1 路由设计

管理后台使用 `/admin` 父路由 + 嵌套子路由，共 **12 个页面**：

| 路由 | 页面 | 鉴权 | 说明 |
|------|------|------|------|
| `/admin/login` | AdminLogin | — | 管理后台独立登录页 |
| `/admin/dashboard` | Dashboard | ✅ requiresAdmin | 数据看板 |
| `/admin/items` | ItemManage | ✅ requiresAdmin | 商品管理 |
| `/admin/orders` | OrderManage | ✅ requiresAdmin | 订单管理 |
| `/admin/orders/:id` | OrderDetail | ✅ requiresAdmin | 订单详情（hidden） |
| `/admin/users` | UserManage | ✅ requiresAdmin | C 端用户管理 |
| `/admin/seckill` | SeckillManage | ✅ requiresAdmin | 秒杀管理 |
| `/admin/chat` | ChatPage | ✅ requiresAdmin | AI 助手 |
| `/admin/system/admin` | AdminUserManage | ✅ requiresAdmin | 管理员管理 |
| `/admin/system/role` | RoleManage | ✅ requiresAdmin | 角色管理 |
| `/admin/system/menu` | MenuManage | ✅ requiresAdmin | 菜单管理 |
| `/admin/system/resource` | ResourceManage | ✅ requiresAdmin | 资源管理 |

### 5.2 认证隔离设计

#### 5.2.1 管理端 axios 实例（api/admin.ts）

与 C 端的核心区别：

| 维度 | C 端（api/index.ts） | 管理端（api/admin.ts） |
|------|---------------------|----------------------|
| sessionStorage key | `token` | `admin-token` |
| 响应解包 | `return response.data` | 解包 `R<T>`：`code === 200 → data`，非 200 `ElMessage.error` |
| 401 跳转 | `router.push('/portal/login')` | `location.hash = '#/admin/login'`（打破循环依赖） |
| 403 处理 | 无 | `ElMessage.error('无权限执行此操作')` |
| 超时 | 10s | 15s |

**关键设计**：管理端 401 跳转使用 `location.hash` 而非 `router.push()`，避免 `api/admin.ts → router/index.ts → stores/admin.ts → api/admin/auth.ts → api/admin.ts` 的循环模块依赖导致白屏。

#### 5.2.2 Admin Store（stores/admin.ts）

```
状态:
  adminToken    → sessionStorage 'admin-token'（Bearer token）
  adminInfo     → sessionStorage 'admin-info'（JSON）
  menus         → 动态菜单数组（从 /admin/info 返回）
  permissions   → 权限编码数组（如 ['product:create', '*']）

方法:
  login(dto)            → POST /admin/login → 存 token → fetchAdminInfo
  fetchAdminInfo()      → GET /admin/info → 存 menus + permissions + adminInfo
  logout()              → POST /admin/logout → 清除全部状态
  hasRoutePermission(p) → 检查路由是否在菜单中（含 '*' 超管通配）
  hasPermission(code)   → 检查按钮级操作权限
```

### 5.3 路由守卫

```typescript
router.beforeEach(async (to, _from, next) => {
  // 1. C 端鉴权
  if (to.meta.requiresAuth && !userStore.isLogin) return next('/portal/login')

  // 2. 管理后台鉴权
  if (to.meta.requiresAdmin && !adminStore.isAdminLogin) return next('/admin/login')

  // 3. 首次进入后台：异步加载管理员信息和权限
  if (to.meta.requiresAdmin && adminStore.isAdminLogin && !adminStore.menus.length) {
    try {
      await adminStore.fetchAdminInfo()
    } catch {
      await adminStore.logout()
      return next('/admin/login')
    }
  }

  next()
})
```

### 5.4 v-permission 按钮级权限指令

```typescript
// directives/permission.ts
// 用法: <el-button v-permission="'product:create'">新增商品</el-button>
//       <el-button v-permission="['product:create', 'product:update']">操作</el-button>
// 无权限时直接移除 DOM 元素
```

### 5.5 页面功能概要

| 页面 | 核心功能 |
|------|---------|
| AdminLogin | 用户名/密码登录，对接 `POST /admin/login` |
| AdminLayout | 侧边栏动态菜单（menu 表返回）+ 面包屑 + 顶栏用户信息/退出 |
| Dashboard | 统计卡片 + ECharts 折线图 + 饼图（当前为 Mock 数据） |
| ItemManage | 商品分页/搜索/新增/编辑/删除 + 批量上下架/删除 |
| OrderManage | 订单分页/筛选（状态/订单号/时间）+ 批量发货/关闭 + 详情弹窗 |
| OrderDetail | 订单详情（商品明细 + 收货信息 + 状态） |
| UserManage | C 端用户分页/搜索 + 状态切换（正常/冻结）+ 余额调整 |
| SeckillManage | 4-Tab 管理：活动/场次/商品关联/订单（CRUD + 预热状态） |
| AdminUserManage | 管理员 CRUD + 角色分配（弹窗多选） |
| RoleManage | 角色 CRUD + 菜单分配 + 资源分配 |
| MenuManage | 菜单树管理（树形表格 CRUD） |
| ResourceManage | 资源/权限管理（CRUD + 分类筛选） |

---

## 六、AI Agent 助手实现

### 6.1 技术概述

基于 LangGraph SDK 1.x 实现的双端 AI 助手，支持流式对话（SSE）、中断交互（二次确认）和多会话管理。

| 特性 | 说明 |
|------|------|
| 框架 | `@langchain/langgraph-sdk` v1.x |
| Agent 类型 | `customer_agent`（C 端） + `admin_agent`（管理端） |
| 上下文传递 | `AgentContext { agent_type, user_token, enable_rag }` |
| 流式方案 | `client.runs.stream()` SSE → `messages/partial` + `messages/complete` |
| 中断支持 | `__interrupt__` 检测 → `command: { resume }` / `goto: __end__` |
| 会话管理 | LangGraph Thread 持久化（创建/切换/删除/列表） |

### 6.2 文件清单

| 文件 | 行数 | 说明 |
|------|------|------|
| `composables/useLangGraph.ts` | 442 | Agent 核心 Composable（消息/中断/会话/流式） |
| `components/chat/ChatPanel.vue` | 508 | 聊天面板（消息列表 + 输入框 + 猜你想做快捷入口） |
| `components/chat/MessageBubble.vue` | 184 | 消息气泡（Markdown 渲染 + human/ai 样式区分） |
| `components/chat/InterruptActions.vue` | 81 | 中断确认按钮（确认/取消） |
| `components/chat/ChatWidget.vue` | 18 | 聊天悬浮小部件 |
| `views/portal/ChatPage.vue` | 35 | C 端 AI 助手页面 |
| `views/admin/ChatPage.vue` | 23 | 管理端 AI 助手页面 |
| `components/chat/AdminChat.vue` | 13 | 管理端聊天容器 |

### 6.3 组件架构

```
ChatPage（页面容器）
  └── ChatPanel（聊天面板）
        ├── MessageBubble[]（消息气泡列表，Markdown 渲染）
        │      └── InterruptActions（中断确认按钮，条件渲染）
        ├── "猜你想做" 快捷入口（预定义问题/操作）
        └── 输入框 + 发送按钮
```

### 6.4 useLangGraph Composable

核心方法：

| 方法 | 参数 | 说明 |
|------|------|------|
| `sendMessage(text, context)` | 消息文本 + Agent 上下文 | 创建/复用 Thread → 流式调用 Agent → 处理 SSE 事件 |
| `resume(value)` | 确认/拒绝值 | 恢复中断的 Agent 执行 |
| `rejectInterrupt()` | — | `goto: __end__` 取消中断 |
| `clearHistory()` | — | 删除 Thread + 清空消息 |
| `fetchThreads()` | — | 获取会话列表（含预览） |
| `switchThread(id)` | thread_id | 切换到已有会话 |
| `newConversation()` | — | 新建会话（不删除旧会话） |
| `deleteThread(id)` | thread_id | 删除指定会话 |

**流式消息处理**：
- `messages/partial`：增量 token 更新（同 id 复用 ChatMessage，通过数组索引触发 Vue 响应式）
- `messages/complete`：完整消息更新
- `values`：检测 `__interrupt__` → 设置 `interruptData` 触发确认按钮
- `error`：UI 中展示错误消息

**修复记录**：
- 修复 LangGraph SDK 禁止同时传 `configurable` 和 `context` 的 Bug（统一用 `context`）
- 修复前端界面不显示 Agent 消息的 Bug（`messages/partial` 响应式更新通过数组索引触发）
- 修复 token 无法传递到 Agent 工具的 Bug（SDK 1.x 正确转发 `context` 字段）
- 提高 `langgraph-sdk` 等级以修复兼容性问题
- 修复前端硬编码端口导致无法访问 Agent 的 Bug（改用环境变量 `VITE_AGENT_URL`）
- 修复跨域请求 Bug
- 更新 Agent 助手前端界面（Markdown 消息渲染、猜你想做快捷入口）
- 扩展 Agent 推荐能力

**Phase 2 新增能力**：
- **用户记忆工具**（`save_memory` / `get_memories`）：Agent 在对话开始时自动读取用户历史意图记忆，在首次回复中自然融入（如"欢迎回来！上次您在看手机类商品"）。用户明确表达未完成的购物意图时自动保存
- **个性化推荐增强**：Agent 通过 Redis 用户画像优先分析偏好（画像命中时 0 次后端调用），推荐结果附带个性化理由生成。后端 `CartServiceImpl` 和 `paySuccessListener` 在加购/支付时自动写入画像，前端无需额外适配
- **CustomerAgent 工具扩充**：从 20 个增至 **22 个**（新增 `save_memory` / `get_memories`）
- **AdminAgent 工具扩充**：从 10 个增至 **11 个**（新增秒杀管理相关工具）

---

## 七、配置说明

### 7.1 Vite 开发服务器

```typescript
// vite.config.ts
export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': resolve(__dirname, 'src') } },
  server: {
    host: '0.0.0.0',       // 允许局域网访问
    port: 5173,
    allowedHosts: true,     // 允许所有 Host 头
    proxy: {
      '/api': {
        target: 'http://localhost:8080',  // 网关地址
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),  // 去掉 /api 前缀
      },
    },
  },
})
```

### 7.2 开发命令

| 命令 | 说明 |
|------|------|
| `npm run dev` | 启动开发服务器（Vite HMR，端口 5173） |
| `npm run build` | 类型检查 + 生产构建（输出到 `dist/`） |
| `npm run preview` | 预览生产构建 |

### 7.3 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `VITE_AGENT_URL` | LangGraph Agent 服务地址 | `http://localhost:8090` |

---

## 八、关键设计决策

### 8.1 Hash 路由 vs History 路由

**决策**：使用 `createWebHashHistory`。

**理由**：前端 SPA 最终部署在 Nginx 静态目录，Hash 路由无需服务端配置 fallback 页面，兼容性更好。

### 8.2 管理端 401 跳转使用 location.hash

**决策**：`api/admin.ts` 401 处理使用 `location.hash = '#/admin/login'` 而非 `router.push()`。

**理由**：打破 `api/admin.ts → router/index.ts → stores/admin.ts → api/admin/auth.ts → api/admin.ts` 的循环模块依赖。Vite/ESM 中循环引用会导致模块初始化时 `router` 为未完全初始化的代理对象，运行时抛出异常导致 Vue 应用崩溃白屏。

### 8.3 sessionStorage 双 Key 隔离

**决策**：C 端用户 token 存 `sessionStorage['token']`，管理端 token 存 `sessionStorage['admin-token']`。

**理由**：同一浏览器 Tab 中 C 端用户和管理员可以独立登录，互不干扰。关闭标签页即自动清除。

### 8.4 购物车数据源策略

**决策**：`PortalLayout.onMounted` 仅在 `cartList.length === 0` 时调用 `fetchCartList()`。

**理由**：路由切换时 PortalLayout 重新挂载，无条件拉取会覆盖 CartPage 中用户通过 `toggleCheck` 设置的本地 `checked` 状态，导致结算页商品清单为空。

### 8.5 applyPayOrder 使用 responseType: 'text'

**决策**：`POST /pay-orders` 指定 `{ responseType: 'text' }`。

**理由**：后端返回裸露的雪花 ID 字符串（`text/plain`），Axios 默认 `responseType: 'json'` 会 `JSON.parse()` 将其转为 JS Number → 超 `Number.MAX_SAFE_INTEGER` 精度丢失。`responseType: 'text'` 直接获取原始字符串，避免精度损失。

### 8.6 Agent 环境变量化

**决策**：Agent 服务 URL 通过 `VITE_AGENT_URL` 环境变量配置，而非硬编码 `localhost:8090`。

**理由**：不同开发环境 Agent 服务地址可能不同，环境变量配置更灵活，避免因硬编码端口导致连接失败。

---

## 九、已知问题与后续优化

### 9.1 Dashboard 数据为 Mock

**现象**：管理后台 Dashboard 统计卡片和 ECharts 图表数据均为前端 Mock，未对接后端统计 API。

**影响**：数据看板不反映真实业务数据。

**状态**：后续扩展。

### 9.2 动态菜单首次加载延迟

**现象**：首次进入管理后台时路由守卫同步等待 `fetchAdminInfo()`，若 admin-service 响应慢，导航会停顿。

**缓解**：`adminToken` 存在时允许渲染 AdminLayout（空菜单），`fetchAdminInfo()` 改为异步非阻塞，完成后响应式更新菜单。

**状态**：未实现，后续优化项。

### 9.3 Agent 依赖 LangGraph 运行时

**现象**：Agent 助手功能完全依赖 LangGraph Server（Python）运行，前端仅作为客户端。

**影响**：必须确保 LangGraph Server 正常运行方可使用 AI 助手功能。

**状态**：架构设计如此，前后端分离的分层架构。

---

## 十、与本仓库其他文档的关联

| 文档 | 关系 |
|------|------|
| `docs/hmall_Agent相关文档/hmall_Agent设计方案文档.md` | Agent 设计方案：AI 助手设计来源（系统架构 / 推荐 / 画像 / RAG） |
| `docs/hmall_Agent相关文档/hmall_Agent实现说明文档.md` | Agent 实现报告：对应后端 Agent 实现 |
| `docs/hmall_Agent相关文档/hmall_Agent项目说明文档.md` | Agent 项目说明：快速理解 Agent 功能 |
| `docs/管理后台相关文档/hmall_Admin设计方案文档.md` | 管理后台设计方案：管理端页面设计来源 |
| `docs/管理后台相关文档/hmall_Admin实现说明文档.md` | 管理后台实现报告：对应后端 admin-service 实现 |
| `docs/管理后台相关文档/hmall_管理后台项目说明文档.md` | 管理后台项目说明：快速理解管理后台功能 |
| `docs/秒杀功能实现/hmall_seckill设计方案文档.md` | 秒杀设计方案：C 端 + 管理端秒杀页面设计来源 |
| `docs/秒杀功能实现/hmall_seckill实现说明文档.md` | 秒杀实现报告：对应后端秒杀功能实现 |
| `docs/秒杀功能实现/hmall_seckill项目说明文档.md` | 秒杀项目说明：快速理解秒杀功能 |
| `hmall-agent/` | Agent 后端服务：LangGraph + DeepAgents Python 服务端 |

---

> **实现完成度**：C 端商城核心链路（浏览→加购→下单→支付）全部实现；管理后台 RBAC + 商品/订单/用户管理全部实现；秒杀系统 C 端 + 管理端全部实现；AI Agent 助手双端集成完成，Phase 2 用户记忆 + 画像推荐增强已落地。Dashboard 数据对接为后续扩展。
