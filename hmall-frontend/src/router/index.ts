import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useAdminStore } from '@/stores/admin'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    // 用户端商城路由
    // 注意：PortalLayout 由各页面组件自行在模板中引入包裹（<PortalLayout>...</PortalLayout>），
    // 而不是作为路由的父组件渲染，因此这里不能用 children 嵌套（PortalLayout 内部没有 <router-view>）。
    {
      path: '/portal',
      redirect: '/portal/home',
    },
    {
      path: '/portal/home',
      name: 'Home',
      component: () => import('@/views/portal/HomePage.vue'),
    },
    {
      path: '/portal/login',
      name: 'Login',
      component: () => import('@/views/portal/LoginPage.vue'),
    },
    {
      path: '/portal/search',
      name: 'Search',
      component: () => import('@/views/portal/SearchPage.vue'),
    },
    {
      path: '/portal/cart',
      name: 'Cart',
      component: () => import('@/views/portal/CartPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/portal/order',
      name: 'OrderConfirm',
      component: () => import('@/views/portal/OrderConfirm.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/portal/orders',
      name: 'OrderList',
      component: () => import('@/views/portal/OrderList.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/portal/pay/:orderId',
      name: 'Pay',
      component: () => import('@/views/portal/PayPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/portal/pay-success/:orderId',
      name: 'PaySuccess',
      component: () => import('@/views/portal/PaySuccess.vue'),
      meta: { requiresAuth: true },
    },
    // 管理后台路由
    {
      path: '/admin',
      component: () => import('@/views/admin/AdminLayout.vue'),
      redirect: '/admin/dashboard',
      meta: { requiresAdmin: true },
      children: [
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('@/views/admin/Dashboard.vue'),
        },
        {
          path: 'items',
          name: 'ItemManage',
          component: () => import('@/views/admin/ItemManage.vue'),
        },
        {
          path: 'users',
          name: 'UserManage',
          component: () => import('@/views/admin/UserManage.vue'),
        },
      ],
    },
    {
      path: '/admin/login',
      name: 'AdminLogin',
      component: () => import('@/views/admin/AdminLogin.vue'),
    },
    // 默认重定向到用户端首页
    {
      path: '/',
      redirect: '/portal/home',
    },
  ],
})

// 路由守卫
router.beforeEach((to, _from, next) => {
  const userStore = useUserStore()
  const adminStore = useAdminStore()

  // 用户端鉴权
  if (to.meta.requiresAuth && !userStore.isLogin) {
    return next('/portal/login')
  }

  // 管理后台鉴权
  if (to.meta.requiresAdmin && !adminStore.isAdminLogin) {
    return next('/admin/login')
  }

  // 已登录用户访问登录页 → 重定向到首页
  if (to.name === 'Login' && userStore.isLogin) {
    return next('/portal/home')
  }

  next()
})

export default router
