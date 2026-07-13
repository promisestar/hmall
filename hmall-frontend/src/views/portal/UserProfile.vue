<template>
  <PortalLayout :show-search="false">
    <div class="container-main py-6">
      <!-- User Info Card -->
      <div class="bg-white rounded-lg shadow-sm p-6 mb-4">
        <div class="flex items-center gap-6">
          <!-- Avatar -->
          <div class="w-20 h-20 rounded-full bg-gradient-to-br from-gray-100 to-gray-200 flex items-center justify-center flex-shrink-0">
            <User class="w-10 h-10 text-gray-400" />
          </div>
          <!-- Info -->
          <div class="flex-1">
            <h2 class="text-xl font-bold text-gray-800 mb-1">{{ userStore.username }}</h2>
            <div class="flex items-center gap-2 text-sm">
              <span class="text-gray-500">账户余额</span>
              <span class="text-[#E4393C] font-bold text-lg">¥{{ formatPrice(userStore.balance) }}</span>
            </div>
          </div>
          <!-- Logout -->
          <button
            @click="handleLogout"
            class="px-5 py-2 border border-[#E4393C] text-[#E4393C] text-sm rounded hover:bg-[#E4393C] hover:text-white transition-colors flex items-center gap-1.5"
          >
            <LogOut class="w-4 h-4" />
            退出登录
          </button>
        </div>
      </div>

      <!-- Quick Entries -->
      <div class="bg-white rounded-lg shadow-sm p-6">
        <h3 class="text-lg font-bold mb-4 border-l-4 border-[#E4393C] pl-3">我的服务</h3>
        <div class="grid grid-cols-4 gap-4">
          <router-link
            to="/portal/orders"
            class="flex flex-col items-center gap-3 p-6 rounded-lg border border-gray-100 hover:border-[#E4393C] hover:shadow-md transition-all group"
          >
            <div class="w-14 h-14 rounded-full bg-red-50 flex items-center justify-center group-hover:bg-red-100 transition-colors">
              <ShoppingBag class="w-7 h-7 text-[#E4393C]" />
            </div>
            <span class="text-sm text-gray-700 group-hover:text-[#E4393C] transition-colors">我的订单</span>
          </router-link>

          <router-link
            to="/portal/address"
            class="flex flex-col items-center gap-3 p-6 rounded-lg border border-gray-100 hover:border-[#E4393C] hover:shadow-md transition-all group"
          >
            <div class="w-14 h-14 rounded-full bg-red-50 flex items-center justify-center group-hover:bg-red-100 transition-colors">
              <MapPin class="w-7 h-7 text-[#E4393C]" />
            </div>
            <span class="text-sm text-gray-700 group-hover:text-[#E4393C] transition-colors">收货地址</span>
          </router-link>

          <router-link
            to="/portal/cart"
            class="flex flex-col items-center gap-3 p-6 rounded-lg border border-gray-100 hover:border-[#E4393C] hover:shadow-md transition-all group"
          >
            <div class="w-14 h-14 rounded-full bg-red-50 flex items-center justify-center group-hover:bg-red-100 transition-colors">
              <ShoppingCart class="w-7 h-7 text-[#E4393C]" />
            </div>
            <span class="text-sm text-gray-700 group-hover:text-[#E4393C] transition-colors">我的购物车</span>
          </router-link>

          <router-link
            to="/portal/home"
            class="flex flex-col items-center gap-3 p-6 rounded-lg border border-gray-100 hover:border-[#E4393C] hover:shadow-md transition-all group"
          >
            <div class="w-14 h-14 rounded-full bg-red-50 flex items-center justify-center group-hover:bg-red-100 transition-colors">
              <Home class="w-7 h-7 text-[#E4393C]" />
            </div>
            <span class="text-sm text-gray-700 group-hover:text-[#E4393C] transition-colors">回到首页</span>
          </router-link>
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { User, LogOut, ShoppingBag, MapPin, ShoppingCart, Home } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'
import PortalLayout from './PortalLayout.vue'
import { useUserStore } from '@/stores/user'
import { formatPrice } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()

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
