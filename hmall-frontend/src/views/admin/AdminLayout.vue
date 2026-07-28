<template>
  <div class="h-screen flex overflow-hidden bg-[#f0f2f5]">
    <!-- 侧边栏 -->
    <aside class="w-[220px] bg-[#1f2a3d] flex flex-col flex-shrink-0 shadow-xl z-20">
      <!-- Logo -->
      <div class="h-16 flex items-center gap-2.5 px-5 border-b border-white/[.06]">
        <div class="w-9 h-9 rounded-xl bg-gradient-to-br from-[#409EFF] to-[#2563eb] flex items-center justify-center shadow-lg shadow-blue-500/25 flex-shrink-0">
          <LayoutDashboard class="w-[18px] h-[18px] text-white" />
        </div>
        <div class="leading-tight">
          <h1 class="text-white font-bold text-[15px] tracking-wide">枫叶商城</h1>
          <p class="text-[10px] text-white/35 tracking-wider">ADMIN CONSOLE</p>
        </div>
      </div>

      <!-- 菜单 -->
      <el-menu
        :default-active="route.path"
        router
        class="border-none flex-1 overflow-y-auto pt-2 sidebar-menu"
        background-color="transparent"
        text-color="rgba(255,255,255,.55)"
        active-text-color="#FFFFFF"
      >
        <el-menu-item index="/admin/dashboard">
          <el-icon><DataAnalysis /></el-icon>
          <span>数据看板</span>
        </el-menu-item>
        <el-menu-item index="/admin/items">
          <el-icon><Goods /></el-icon>
          <span>商品管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/orders">
          <el-icon><Document /></el-icon>
          <span>订单管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/seckill">
          <el-icon><AlarmClock /></el-icon>
          <span>秒杀管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/users">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-sub-menu index="/system">
          <template #title>
            <el-icon><Setting /></el-icon>
            <span>系统管理</span>
          </template>
          <el-menu-item index="/admin/system/admin">
            <el-icon><UserFilled /></el-icon>
            <span>管理员管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/system/role">
            <el-icon><Lock /></el-icon>
            <span>角色管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/system/menu">
            <el-icon><Menu /></el-icon>
            <span>菜单管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/system/resource">
            <el-icon><Folder /></el-icon>
            <span>资源管理</span>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>

      <!-- 侧边栏底部信息 -->
      <div class="px-5 py-4 border-t border-white/[.06]">
        <p class="text-[10px] text-white/25">Hmall Admin v1.0</p>
      </div>
    </aside>

    <!-- 主区域 -->
    <div class="flex-1 flex flex-col overflow-hidden">
      <!-- 头部 -->
      <header class="h-16 bg-white border-b border-gray-100 flex items-center justify-between px-6 shadow-sm z-10">
        <el-breadcrumb separator="/" class="text-sm">
          <el-breadcrumb-item :to="{ path: '/admin/dashboard' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item v-if="route.meta.title">{{ route.meta.title }}</el-breadcrumb-item>
        </el-breadcrumb>

        <div class="flex items-center gap-3">
          <!-- AI 客服 -->
          <AdminChat />

          <!-- LLM 提供商切换 -->
          <el-select v-model="llmProvider" size="small" style="width: 96px">
            <el-option label="OpenAI" value="openai" />
            <el-option label="通义千问" value="qwen" />
          </el-select>

          <span class="w-px h-5 bg-gray-200 mx-1"></span>

          <!-- 管理员菜单 -->
          <el-dropdown trigger="click" @command="handleCommand">
            <div class="flex items-center gap-2.5 cursor-pointer px-2 py-1.5 rounded-lg hover:bg-gray-50 transition-colors">
              <div class="w-9 h-9 rounded-full bg-gradient-to-br from-[#409EFF] to-[#2563eb] flex items-center justify-center shadow-md shadow-blue-500/20">
                <span class="text-white text-sm font-bold">{{ adminInitial }}</span>
              </div>
              <div class="leading-tight">
                <p class="text-[13px] font-medium text-gray-700">{{ adminStore.username }}</p>
                <p class="text-[10px] text-gray-400">管理员</p>
              </div>
              <ArrowDown class="w-3.5 h-3.5 text-gray-400" />
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="portal">
                  <span class="flex items-center gap-2"><Store class="w-4 h-4" />返回商城</span>
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <span class="flex items-center gap-2 text-[#F56C6C]"><LogOut class="w-4 h-4" />退出登录</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- 内容区 -->
      <main class="flex-1 overflow-y-auto bg-[#f0f2f5] p-5">
        <slot />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  DataAnalysis,
  Goods,
  Document,
  User,
  Setting,
  AlarmClock,
  UserFilled,
  Lock,
  Menu,
  Folder,
  ArrowDown,
} from '@element-plus/icons-vue'
import { LayoutDashboard, Store, LogOut } from 'lucide-vue-next'
import { useAdminStore } from '@/stores/admin'
import AdminChat from '@/components/chat/AdminChat.vue'

const route = useRoute()
const router = useRouter()
const adminStore = useAdminStore()

const adminInitial = computed(() => (adminStore.username || 'A').slice(0, 1).toUpperCase())

// LLM 提供商（AdminChat 组件使用）
const llmProvider = ref(localStorage.getItem('hmall_llm_provider') || 'openai')
watch(llmProvider, (val) => {
  localStorage.setItem('hmall_llm_provider', val)
})

function handleCommand(cmd: string) {
  if (cmd === 'logout') {
    handleLogout()
  } else if (cmd === 'portal') {
    router.push('/portal/home')
  }
}

function handleLogout() {
  adminStore.logout()
  router.push('/admin/login')
}
</script>

<style scoped>
.sidebar-menu {
  --el-menu-item-height: 46px;
  --el-menu-sub-item-height: 42px;
}

.sidebar-menu :deep(.el-menu-item),
.sidebar-menu :deep(.el-sub-menu__title) {
  margin: 2px 10px;
  border-radius: 10px;
  transition: all .2s ease;
}

.sidebar-menu :deep(.el-menu-item:hover),
.sidebar-menu :deep(.el-sub-menu__title:hover) {
  background: rgba(255, 255, 255, .06);
  color: rgba(255, 255, 255, .9);
}

.sidebar-menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(135deg, #409eff 0%, #2563eb 100%);
  color: #fff;
  font-weight: 600;
  box-shadow: 0 4px 12px rgba(37, 99, 235, .35);
}

.sidebar-menu :deep(.el-menu) {
  background: transparent;
}

.sidebar-menu :deep(.el-menu-item .el-icon),
.sidebar-menu :deep(.el-sub-menu__title .el-icon) {
  font-size: 17px;
}
</style>
