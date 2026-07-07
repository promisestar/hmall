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
        <el-menu-item index="/admin/dashboard">
          <el-icon><DataAnalysis /></el-icon>
          <span>数据概览</span>
        </el-menu-item>
        <el-menu-item index="/admin/items">
          <el-icon><Goods /></el-icon>
          <span>商品管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/users">
          <el-icon><UserFilled /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-sub-menu index="order">
          <template #title>
            <el-icon><Tickets /></el-icon>
            <span>订单管理</span>
          </template>
          <el-menu-item index="/admin/orders">订单列表</el-menu-item>
        </el-sub-menu>
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
          <span class="text-sm text-gray-600">{{ adminStore.adminUser?.username || '管理员' }}</span>
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
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Fold, Expand, DataAnalysis, Goods, UserFilled, Tickets } from '@element-plus/icons-vue'
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
    '/admin/users': '用户管理',
  }
  return map[route.path] || ''
})

function handleLogout() {
  adminStore.logout()
  router.push('/admin/login')
}
</script>
