<template>
  <div>
    <el-page-header @back="$router.back()" title="返回" class="mb-4">
      <template #content>订单详情</template>
    </el-page-header>
    <el-card v-loading="loading">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="订单号">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.status }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.createTime }}</el-descriptions-item>
          <el-descriptions-item label="用户ID">{{ detail.userId }}</el-descriptions-item>
          <el-descriptions-item label="总金额">¥{{ formatPrice(detail.totalFee) }}</el-descriptions-item>
          <el-descriptions-item label="支付方式">{{ detail.paymentType }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getOrderDetail } from '@/api/admin/order'
import { formatPrice } from '@/utils/format'
import type { OrderVO } from '@/types'

const route = useRoute()
const detail = ref<OrderVO | null>(null)
const loading = ref(false)

onMounted(async () => {
  const id = route.params.id as string
  if (id) {
    loading.value = true
    try {
      detail.value = await getOrderDetail(id)
    } finally {
      loading.value = false
    }
  }
})
</script>
