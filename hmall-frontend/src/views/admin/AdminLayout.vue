<template>
  <el-container class="min-h-screen">
    <!-- Sidebar -->
    <el-aside :width="collapsed ? '64px' : '220px'" class="bg-[#304156] transition-all duration-300">
      <div class="h-16 flex items-center justify-center border-b border-gray-700">
        <h1 v-if="!collapsed" class="text-white text-lg font-bold">黑马商城后台</h1>
        <span v-else class="text-white text-xl font-bold">HM</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
        :collapse-transition="false"
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409EFF"
        router
        class="border-none"
      >
        <template v-for="menu in adminStore.menus" :key="menu.id">
          <!-- 有子菜单 -->
          <el-sub-menu v-if="menu.children?.length && !menu.path" :index="menu.name || String(menu.id)">
            <template #title>
              <el-icon><component :is="getIcon(menu.icon)" /></el-icon>
              <span>{{ menu.title }}</span>
            </template>
            <template v-for="child in menu.children" :key="child.id">
              <el-menu-item v-if="!child.hidden" :index="child.path">
                <el-icon v-if="child.icon"><component :is="getIcon(child.icon)" /></el-icon>
                <span>{{ child.title }}</span>
              </el-menu-item>
            </template>
          </el-sub-menu>
          <!-- 无子菜单 -->
          <el-menu-item v-else-if="!menu.hidden && menu.path" :index="menu.path">
            <el-icon><component :is="getIcon(menu.icon)" /></el-icon>
            <span>{{ menu.title }}</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <!-- Main -->
    <el-container>
      <el-header class="bg-white border-b flex items-center justify-between px-4 h-14">
        <div class="flex items-center gap-3">
          <el-button @click="collapsed = !collapsed" text size="large">
            <el-icon :size="20"><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
          </el-button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/admin/dashboard' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="flex items-center gap-3">
          <el-tag size="small" type="success">在线</el-tag>
          <span class="text-sm text-gray-600">{{ adminStore.username }}</span>
          <el-button text type="danger" size="small" @click="handleLogout">退出登录</el-button>
        </div>
      </el-header>

      <el-main class="bg-[#f0f2f5] p-6">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref, computed, markRaw } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Fold, Expand, DataAnalysis, Goods, UserFilled, Tickets, AlarmClock, User, Menu as MenuIcon, Setting } from '@element-plus/icons-vue'
import { useAdminStore } from '@/stores/admin'

const route = useRoute()
const router = useRouter()
const adminStore = useAdminStore()
const collapsed = ref(false)

const activeMenu = computed(() => route.path)
const currentTitle = computed(() => {
  const map: Record<string, string> = {
    '/admin/dashboard': '数据概览',
    '/admin/items': '商品管理',
    '/admin/orders': '订单管理',
    '/admin/users': '用户管理',
    '/admin/seckill': '秒杀管理',
    '/admin/system/admin': '管理员管理',
    '/admin/system/role': '角色管理',
    '/admin/system/menu': '菜单管理',
    '/admin/system/resource': '资源管理',
  }
  return map[route.path] || ''
})

/** 图标名称映射 */
const iconMap: Record<string, any> = {
  DataAnalysis: markRaw(DataAnalysis),
  Goods: markRaw(Goods),
  UserFilled: markRaw(UserFilled),
  Tickets: markRaw(Tickets),
  AlarmClock: markRaw(AlarmClock),
  User: markRaw(User),
  Menu: markRaw(MenuIcon),
  Setting: markRaw(Setting),
}

function getIcon(iconName?: string) {
  if (!iconName) return markRaw(MenuIcon)
  return iconMap[iconName] || markRaw(MenuIcon)
}

async function handleLogout() {
  await adminStore.logout()
  router.push('/admin/login')
}
</script>
