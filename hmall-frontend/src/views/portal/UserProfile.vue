<template>
  <PortalLayout :show-search="false">
    <div class="container-main py-5">
      <!-- 用户信息卡 -->
      <div class="relative overflow-hidden rounded-2xl bg-gradient-to-r from-[#2b2f36] to-[#3d434e] p-8 mb-5 text-white">
        <div class="absolute -right-10 -top-10 w-48 h-48 rounded-full bg-white/5"></div>
        <div class="absolute right-32 -bottom-16 w-56 h-56 rounded-full bg-white/5"></div>

        <div class="relative flex items-center gap-6">
          <!-- 头像 -->
          <div class="w-20 h-20 rounded-full bg-gradient-to-br from-[#FF6B35] to-[#E4393C] flex items-center justify-center flex-shrink-0 shadow-glow ring-4 ring-white/10">
            <span class="text-2xl font-bold">{{ avatarLetter }}</span>
          </div>
          <!-- 信息 -->
          <div class="flex-1">
            <h2 class="text-xl font-bold mb-1.5">{{ userStore.username }}</h2>
            <div class="inline-flex items-center gap-2 bg-white/10 rounded-full px-4 py-1.5">
              <Wallet class="w-4 h-4 text-[#ffb199]" />
              <span class="text-xs text-white/70">账户余额</span>
              <span class="text-[#ffb199] font-bold">¥{{ formatPrice(userStore.balance) }}</span>
            </div>
          </div>
          <!-- 退出 -->
          <button
            @click="handleLogout"
            class="flex items-center gap-1.5 px-5 py-2 rounded-full border border-white/25 text-white/80 text-[13px] hover:bg-white/10 hover:text-white transition-all"
          >
            <LogOut class="w-4 h-4" />
            退出登录
          </button>
        </div>
      </div>

      <!-- 快捷服务 -->
      <div class="page-card p-6">
        <h3 class="section-title mb-5">我的服务</h3>
        <div class="grid grid-cols-4 gap-4">
          <router-link
            v-for="entry in entries"
            :key="entry.title"
            :to="entry.to"
            class="flex flex-col items-center gap-3 p-6 rounded-xl border border-gray-100 hover:border-transparent hover:shadow-lift hover:-translate-y-1 transition-all group"
          >
            <div
              class="w-14 h-14 rounded-2xl flex items-center justify-center transition-all group-hover:scale-110"
              :class="entry.bgClass"
            >
              <component :is="entry.icon" class="w-7 h-7" :class="entry.iconClass" />
            </div>
            <span class="text-[13px] text-gray-700 font-medium group-hover:text-[#E4393C] transition-colors">{{ entry.title }}</span>
            <span class="text-[11px] text-gray-400 -mt-1.5">{{ entry.desc }}</span>
          </router-link>
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { computed, markRaw } from 'vue'
import { useRouter } from 'vue-router'
import { LogOut, ShoppingBag, MapPin, ShoppingCart, Sparkles, Wallet } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'
import PortalLayout from './PortalLayout.vue'
import { useUserStore } from '@/stores/user'
import { formatPrice } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()

const avatarLetter = computed(() => (userStore.username || 'U').slice(0, 1).toUpperCase())

const entries = [
  {
    title: '我的订单', desc: '查看订单状态', to: '/portal/orders',
    icon: markRaw(ShoppingBag), bgClass: 'bg-red-50', iconClass: 'text-[#E4393C]',
  },
  {
    title: '收货地址', desc: '管理收货信息', to: '/portal/address',
    icon: markRaw(MapPin), bgClass: 'bg-orange-50', iconClass: 'text-[#FF6B35]',
  },
  {
    title: '我的购物车', desc: '去结算心仪商品', to: '/portal/cart',
    icon: markRaw(ShoppingCart), bgClass: 'bg-blue-50', iconClass: 'text-[#409EFF]',
  },
  {
    title: 'AI 客服', desc: '智能导购助手', to: '/portal/chat',
    icon: markRaw(Sparkles), bgClass: 'bg-purple-50', iconClass: 'text-[#7c3aed]',
  },
]

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确认退出登录吗？', '提示', {
      confirmButtonText: '确认退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/portal/home')
  } catch (e) {
    // 用户取消，静默处理
  }
}
</script>
