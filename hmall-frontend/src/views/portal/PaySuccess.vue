<template>
  <PortalLayout :show-search="false">
    <div class="container-main py-8">
      <div class="page-card p-16 text-center max-w-[560px] mx-auto">
        <!-- 成功图标 -->
        <div class="relative w-20 h-20 mx-auto mb-6 animate-scale-in">
          <div class="absolute inset-0 rounded-full bg-green-100 animate-ping opacity-40"></div>
          <div class="relative w-20 h-20 bg-gradient-to-br from-green-400 to-green-500 rounded-full flex items-center justify-center shadow-lg shadow-green-200">
            <Check class="w-10 h-10 text-white" :stroke-width="3" />
          </div>
        </div>

        <h2 class="text-2xl font-bold text-gray-900 mb-2 animate-fade-up">支付成功</h2>
        <p class="text-[13px] text-gray-400 animate-fade-up">订单号：{{ orderId }}</p>
        <p class="text-sm text-gray-500 mt-3 animate-fade-up">感谢您的购买，我们将尽快为您发货</p>

        <div class="flex justify-center gap-3 mt-9">
          <router-link to="/portal/orders" class="btn-secondary">查看订单</router-link>
          <router-link to="/portal/home" class="btn-primary">继续购物</router-link>
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Check } from 'lucide-vue-next'
import PortalLayout from './PortalLayout.vue'
import { useCartStore } from '@/stores/cart'

const route = useRoute()
const cartStore = useCartStore()
const orderId = route.params.orderId as string

onMounted(() => {
  // 支付成功后清空前端购物车缓存，下次查询时自动从 Redis/MySQL 拉取最新数据
  cartStore.clearCart()
})
</script>
