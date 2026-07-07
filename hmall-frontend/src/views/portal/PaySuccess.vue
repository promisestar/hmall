<template>
  <PortalLayout :show-search="false">
    <div class="container-main py-6">
      <div class="bg-white rounded-lg shadow-sm p-20 text-center max-w-[600px] mx-auto">
        <div class="w-20 h-20 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-6">
          <CheckCircle2 class="w-12 h-12 text-green-500" />
        </div>
        <h2 class="text-2xl font-bold text-gray-800 mb-2">支付成功！</h2>
        <p class="text-gray-500 mb-6">订单号：{{ orderId }}</p>
        <p class="text-sm text-gray-400 mb-8">感谢您的购买，我们将尽快为您发货</p>
        <div class="flex justify-center gap-4">
          <router-link to="/portal/home" class="btn-primary px-8 py-3">返回首页</router-link>
          <router-link to="/portal/search" class="btn-secondary px-8 py-3">继续购物</router-link>
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import { CheckCircle2 } from 'lucide-vue-next'
import PortalLayout from './PortalLayout.vue'

const route = useRoute()
const orderId = route.params.orderId as string
</script>
