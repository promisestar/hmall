<template>
  <div class="min-h-screen bg-[#f5f5f5] flex flex-col">
    <!-- Top Bar -->
    <div class="bg-[#333] text-gray-300 text-xs">
      <div class="container-main flex justify-between items-center h-8">
        <div class="flex items-center gap-4">
          <span>黑马商城欢迎您！</span>
          <router-link v-if="!userStore.isLogin" to="/portal/login" class="hover:text-white transition-colors">
            请登录
          </router-link>
          <template v-else>
            <span class="text-white">{{ userStore.username }}</span>
            <span @click="handleLogout" class="cursor-pointer hover:text-white transition-colors">退出</span>
          </template>
          <router-link to="/portal/home" class="hover:text-white transition-colors">首页</router-link>
        </div>
        <div class="flex items-center gap-4">
          <router-link to="/portal/orders" class="hover:text-white transition-colors">我的订单</router-link>
          <a href="#" class="hover:text-white transition-colors">客户服务</a>
          <router-link to="/admin/dashboard" class="text-[#E4393C] hover:text-red-400 transition-colors font-medium">
            管理后台
          </router-link>
        </div>
      </div>
    </div>

    <!-- Header with Search -->
    <div class="bg-white border-b border-gray-200 sticky top-0 z-50">
      <div class="container-main py-3">
        <div class="flex items-center justify-between">
          <!-- Logo -->
          <router-link to="/portal/home" class="flex-shrink-0">
            <img src="/img/logo.png" alt="黑马商城" class="h-12" />
          </router-link>

          <!-- Search Bar -->
          <div class="flex-1 max-w-[500px] mx-8" v-if="showSearch">
            <div class="flex">
              <input
                v-model="searchKey"
                type="text"
                placeholder="搜索商品"
                class="flex-1 border-2 border-[#E4393C] rounded-l px-4 py-2 outline-none focus:border-[#C81623] text-sm"
                @keyup.enter="doSearch"
              />
              <button
                @click="doSearch"
                class="bg-[#E4393C] text-white px-6 py-2 rounded-r hover:bg-[#C81623] transition-colors font-medium text-sm"
              >
                搜索
              </button>
            </div>
          </div>

          <!-- Cart -->
          <router-link to="/portal/cart" class="relative flex items-center border border-gray-300 rounded px-4 py-2 hover:border-[#E4393C] transition-colors">
            <ShoppingCart class="w-5 h-5 text-[#E4393C]" />
            <span class="ml-2 text-sm">购物车</span>
            <span
              v-if="cartNum > 0"
              class="absolute -top-2 -right-2 bg-[#E4393C] text-white text-xs rounded-full w-5 h-5 flex items-center justify-center"
            >
              {{ cartNum > 99 ? '99+' : cartNum }}
            </span>
          </router-link>
        </div>
      </div>
    </div>

    <!-- Main Content -->
    <main class="flex-1">
      <slot />
    </main>

    <!-- Footer -->
    <footer class="bg-[#333] text-gray-400 text-sm mt-10">
      <div class="container-main py-8">
        <div class="grid grid-cols-4 gap-8">
          <div>
            <h4 class="text-white font-medium mb-3">购物指南</h4>
            <ul class="space-y-2">
              <li>购物流程</li>
              <li>会员介绍</li>
              <li>常见问题</li>
              <li>联系客服</li>
            </ul>
          </div>
          <div>
            <h4 class="text-white font-medium mb-3">配送方式</h4>
            <ul class="space-y-2">
              <li>上门自提</li>
              <li>211限时达</li>
              <li>配送服务查询</li>
              <li>配送费收取标准</li>
            </ul>
          </div>
          <div>
            <h4 class="text-white font-medium mb-3">支付方式</h4>
            <ul class="space-y-2">
              <li>货到付款</li>
              <li>在线支付</li>
              <li>分期付款</li>
              <li>邮局汇款</li>
            </ul>
          </div>
          <div>
            <h4 class="text-white font-medium mb-3">售后服务</h4>
            <ul class="space-y-2">
              <li>售后政策</li>
              <li>价格保护</li>
              <li>退款说明</li>
              <li>返修/退换货</li>
            </ul>
          </div>
        </div>
        <div class="border-t border-gray-600 mt-6 pt-6 text-center">
          <p>&copy; 2025 黑马商城 hmall.com 版权所有</p>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ShoppingCart } from 'lucide-vue-next'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'

defineProps<{ showSearch?: boolean }>()

const router = useRouter()
const userStore = useUserStore()
const cartStore = useCartStore()
const searchKey = ref('')
const cartNum = computed(() => cartStore.cartNum)

function doSearch() {
  const key = searchKey.value.trim()
  if (key) {
    router.push({ path: '/portal/search', query: { key } })
  } else {
    router.push('/portal/search')
  }
}

async function handleLogout() {
  await userStore.logout()
  router.push('/portal/home')
}

onMounted(async () => {
  // 只在购物车数据为空时才拉取，避免覆盖已选中的 checked 状态
  if (userStore.isLogin && cartStore.cartList.length === 0) {
    await cartStore.fetchCartList()
  }
})
</script>
