import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useAdminStore } from '@/stores/admin'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    // 用户端商城路由
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
      path: '/portal/product/:itemId',
      name: 'ProductDetail',
      component: () => import('@/views/portal/ProductDetail.vue'),
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
      path: '/portal/seckill',
      name: 'SeckillList',
      component: () => import('@/views/portal/SeckillList.vue'),
    },
    {
      path: '/portal/seckill/:relationId',
      name: 'SeckillDetail',
      component: () => import('@/views/portal/SeckillDetail.vue'),
      meta: { requiresAuth: true },
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
      path: '/portal/address',
      name: 'AddressList',
      component: () => import('@/views/portal/AddressList.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/portal/profile',
      name: 'UserProfile',
      component: () => import('@/views/portal/UserProfile.vue'),
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
          path: 'orders',
          name: 'OrderManage',
          component: () => import('@/views/admin/OrderManage.vue'),
        },
        {
          path: 'orders/:id',
          name: 'OrderDetail',
          component: () => import('@/views/admin/OrderDetail.vue'),
          meta: { hidden: true },
        },
        {
          path: 'users',
          name: 'UserManage',
          component: () => import('@/views/admin/UserManage.vue'),
        },
        {
          path: 'seckill',
          name: 'SeckillManage',
          component: () => import('@/views/admin/SeckillManage.vue'),
        },
        {
          path: 'system/admin',
          name: 'AdminUserManage',
          component: () => import('@/views/admin/system/AdminUserManage.vue'),
        },
        {
          path: 'system/role',
          name: 'RoleManage',
          component: () => import('@/views/admin/system/RoleManage.vue'),
        },
        {
          path: 'system/menu',
          name: 'MenuManage',
          component: () => import('@/views/admin/system/MenuManage.vue'),
        },
        {
          path: 'system/resource',
          name: 'ResourceManage',
          component: () => import('@/views/admin/system/ResourceManage.vue'),
        },
      ],
    },
    {
      path: '/',
      redirect: '/portal/home',
    },
  ],
})

router.beforeEach(async (to, _from, next) => {
  const userStore = useUserStore()
  const adminStore = useAdminStore()

  if (to.meta.requiresAuth && !userStore.isLogin) {
    return next('/portal/login')
  }

  if (to.meta.requiresAdmin && !adminStore.isAdminLogin) {
    return next('/admin/login')
  }

  // 首次进入后台时加载管理员信息和权限
  if (to.meta.requiresAdmin && adminStore.isAdminLogin && !adminStore.menus.length) {
    try {
      await adminStore.fetchAdminInfo()
    } catch {
      await adminStore.logout()
      return next('/admin/login')
    }
  }

  if (to.name === 'Login' && userStore.isLogin) {
    return next('/portal/home')
  }

  next()
})

export default router
