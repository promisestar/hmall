<template>
  <div class="portal-theme min-h-screen bg-[#f6f7f9] flex flex-col">
    <!-- 顶部导航条 -->
    <div class="bg-[#1a1d24] text-gray-400 text-xs">
      <div class="container-main flex justify-between items-center h-9">
        <div class="flex items-center gap-1">
          <span class="mr-2 text-gray-500">枫叶商城欢迎您</span>
          <router-link v-if="!userStore.isLogin" to="/portal/login" class="top-link">
            请登录
          </router-link>
          <template v-else>
            <router-link to="/portal/profile" class="top-link text-white font-medium">
              {{ userStore.username }}
            </router-link>
            <span @click="handleLogout" class="top-link cursor-pointer">退出</span>
          </template>
          <router-link to="/portal/home" class="top-link">首页</router-link>
          <router-link to="/portal/seckill" class="top-link text-[#ff7a45] font-medium">
            <Zap class="w-3 h-3" />限时秒杀
          </router-link>
        </div>
        <div class="flex items-center gap-1">
          <router-link to="/portal/orders" class="top-link">我的订单</router-link>
          <router-link to="/portal/chat" class="top-link text-[#ff7a45] font-medium">
            <Sparkles class="w-3 h-3" />AI 客服
          </router-link>
          <a href="#" class="top-link">客户服务</a>
          <router-link to="/admin/dashboard" class="top-link text-gray-200 font-medium">
            管理后台
          </router-link>
        </div>
      </div>
    </div>

    <!-- 页头：Logo + 搜索 + 购物车 -->
    <div class="bg-white/95 backdrop-blur border-b border-gray-100 sticky top-0 z-50 shadow-[0_2px_12px_rgba(27,31,38,.05)]">
      <div class="container-main py-3">
        <div class="flex items-center justify-between gap-6">
          <!-- Logo -->
          <router-link to="/portal/home" class="flex-shrink-0 flex items-center gap-2 group">
            <img src="/img/logo.png" alt="枫叶商城" class="h-11 transition-transform duration-300 group-hover:scale-105" />
          </router-link>

          <!-- 搜索框 -->
          <div class="flex-1 max-w-[520px]" v-if="showSearch">
            <div class="flex items-center rounded-full border-2 border-[#E4393C] overflow-hidden bg-white transition-shadow focus-within:shadow-glow">
              <Search class="w-4 h-4 text-gray-400 ml-4 flex-shrink-0" />
              <input
                v-model="searchKey"
                type="text"
                placeholder="搜索商品 / 品牌 / 分类"
                class="flex-1 px-3 py-2 outline-none text-sm bg-transparent"
                @keyup.enter="doSearch"
              />
              <button
                @click="doSearch"
                class="bg-gradient-to-r from-[#f04548] to-[#d2202a] text-white px-7 py-2 font-medium text-sm hover:opacity-90 active:scale-[.98] transition-all self-stretch"
              >
                搜索
              </button>
            </div>
          </div>

          <!-- 购物车入口 -->
          <router-link
            to="/portal/cart"
            class="relative flex items-center gap-2 border border-gray-200 rounded-full px-5 py-2 text-sm text-gray-700 hover:border-[#E4393C] hover:text-[#E4393C] hover:shadow-card transition-all"
          >
            <ShoppingCart class="w-[18px] h-[18px] text-[#E4393C]" />
            <span>购物车</span>
            <span
              v-if="cartNum > 0"
              class="absolute -top-1.5 -right-1.5 min-w-[20px] h-5 px-1 bg-gradient-to-r from-[#f04548] to-[#d2202a] text-white text-[11px] font-bold rounded-full flex items-center justify-center shadow-glow"
            >
              {{ cartNum > 99 ? '99+' : cartNum }}
            </span>
          </router-link>
        </div>
      </div>
    </div>

    <!-- 主内容 -->
    <main class="flex-1">
      <slot />
    </main>

    <!-- 服务保障条 -->
    <div class="bg-white border-t border-gray-100 mt-12">
      <div class="container-main py-6">
        <div class="grid grid-cols-4 gap-6">
          <div v-for="s in guarantees" :key="s.title" class="flex items-center gap-3">
            <div class="w-11 h-11 rounded-full bg-red-50 flex items-center justify-center flex-shrink-0">
              <component :is="s.icon" class="w-5 h-5 text-[#E4393C]" />
            </div>
            <div>
              <p class="text-sm font-semibold text-gray-800">{{ s.title }}</p>
              <p class="text-xs text-gray-400 mt-0.5">{{ s.desc }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 页脚 -->
    <footer class="bg-[#1a1d24] text-gray-500 text-sm">
      <div class="container-main py-10">
        <div class="grid grid-cols-5 gap-8">
          <div class="col-span-1">
            <img src="/img/logo.png" alt="枫叶商城" class="h-10 mb-4 brightness-200" />
            <p class="text-xs leading-relaxed text-gray-500">品质好物一站购，<br />让每一次购物都安心省心。</p>
          </div>
          <div v-for="col in footerLinks" :key="col.title">
            <h4 class="text-gray-200 font-medium mb-3 text-sm">{{ col.title }}</h4>
            <ul class="space-y-2 text-xs">
              <li v-for="link in col.items" :key="link">
                <a href="#" class="hover:text-white transition-colors">{{ link }}</a>
              </li>
            </ul>
          </div>
        </div>
        <div class="border-t border-white/10 mt-8 pt-6 text-center text-xs text-gray-600">
          <p>&copy; 2025 枫叶商城 hmall.com 版权所有</p>
        </div>
      </div>
    </footer>

    <!-- AI 客服浮动组件 -->
    <ChatWidget />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed, markRaw } from 'vue'
import { useRouter } from 'vue-router'
import { ShoppingCart, Search, Zap, Sparkles, ShieldCheck, Truck, RotateCcw, Headset } from 'lucide-vue-next'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'
import ChatWidget from '@/components/chat/ChatWidget.vue'

defineProps<{ showSearch?: boolean }>()

const router = useRouter()
const userStore = useUserStore()
const cartStore = useCartStore()
const searchKey = ref('')
const cartNum = computed(() => cartStore.cartNum)

// 服务保障
const guarantees = [
  { title: '正品保障', desc: '自营正品 假一赔十', icon: markRaw(ShieldCheck) },
  { title: '极速物流', desc: '211 限时达', icon: markRaw(Truck) },
  { title: '无忧售后', desc: '7 天无理由退换', icon: markRaw(RotateCcw) },
  { title: '贴心服务', desc: 'AI 客服 7x24 在线', icon: markRaw(Headset) },
]

// 页脚链接
const footerLinks = [
  { title: '购物指南', items: ['购物流程', '会员介绍', '常见问题', '联系客服'] },
  { title: '配送方式', items: ['上门自提', '211限时达', '配送服务查询', '配送费收取标准'] },
  { title: '支付方式', items: ['货到付款', '在线支付', '分期付款', '邮局汇款'] },
  { title: '售后服务', items: ['售后政策', '价格保护', '退款说明', '返修/退换货'] },
]

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

<style scoped>
.top-link {
  @apply flex items-center gap-1 px-2 py-1 rounded transition-colors hover:text-white;
}
</style>
