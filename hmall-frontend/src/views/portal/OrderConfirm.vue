<template>
  <PortalLayout :show-search="false">
    <div class="container-main py-6">
      <h2 class="text-lg font-bold mb-4 border-l-4 border-[#E4393C] pl-3">确认订单</h2>

      <div class="bg-white rounded-lg shadow-sm p-6">
        <!-- Items -->
        <div class="mb-6">
          <h4 class="font-medium mb-3 text-sm text-gray-500 border-b pb-2">商品清单</h4>
          <div
            v-for="item in cartStore.checkedItems"
            :key="item.id"
            class="flex items-center gap-4 py-3 border-b border-gray-100 last:border-0"
          >
            <img :src="item.image || '/img/like_01.png'" class="w-16 h-16 object-cover rounded" />
            <span class="flex-1 text-sm">{{ item.name }}</span>
            <span class="text-sm text-gray-500">¥{{ formatPrice(item.price) }} x {{ item.num }}</span>
            <span class="text-sm font-bold text-[#E4393C]">¥{{ formatPrice(item.price * item.num) }}</span>
          </div>
        </div>

        <!-- Total -->
        <div class="text-right mb-6 pb-4 border-b">
          <span class="text-gray-500">共 {{ cartStore.totalNum }} 件商品，合计：</span>
          <span class="text-2xl font-bold text-[#E4393C]">¥{{ formatPrice(cartStore.totalPrice) }}</span>
        </div>

        <!-- Address -->
        <div class="mb-6">
          <div class="flex items-center justify-between mb-3 border-b pb-2">
            <h4 class="font-medium text-sm text-gray-500">收货地址</h4>
            <router-link to="/portal/address" class="text-xs text-[#E4393C] hover:underline">
              管理收货地址
            </router-link>
          </div>
          <div v-if="loadingAddress" class="text-sm text-gray-400">加载中...</div>
          <div v-else-if="addresses.length === 0" class="text-sm text-gray-400">暂无收货地址</div>
          <div v-else class="space-y-2">
            <label
              v-for="addr in addresses"
              :key="addr.id"
              class="flex items-start gap-3 p-3 border rounded cursor-pointer hover:border-[#E4393C] transition-colors"
              :class="selectedAddressId === addr.id ? 'border-[#E4393C] bg-red-50' : 'border-gray-200'"
            >
              <input
                type="radio"
                :value="addr.id"
                v-model="selectedAddressId"
                class="mt-1 accent-[#E4393C]"
              />
              <div class="text-sm">
                <p class="font-medium">{{ addr.contact }} <span class="text-gray-400 ml-2">{{ addr.mobile }}</span></p>
                <p class="text-gray-500 mt-0.5">{{ addr.province }}{{ addr.city }}{{ addr.town }} {{ addr.street }}</p>
              </div>
            </label>
          </div>
        </div>

        <!-- Submit -->
        <div class="flex justify-end gap-4">
          <router-link to="/portal/cart" class="btn-secondary">返回购物车</router-link>
          <button
            @click="submitOrder"
            :disabled="submitting"
            class="btn-primary text-lg px-10 disabled:opacity-50"
          >
            {{ submitting ? '提交中...' : '提交订单' }}
          </button>
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import PortalLayout from './PortalLayout.vue'
import { useCartStore } from '@/stores/cart'
import { createOrder } from '@/api/order'
import { getAddressList } from '@/api/address'
import { formatPrice } from '@/utils/format'
import { ElMessage } from 'element-plus'
import type { Address } from '@/types'

const router = useRouter()
const cartStore = useCartStore()
const submitting = ref(false)
const addresses = ref<Address[]>([])
const loadingAddress = ref(false)
const selectedAddressId = ref<number | null>(null)

onMounted(async () => {
  loadingAddress.value = true
  try {
    addresses.value = await getAddressList()
    // 默认选中第一个（或 isDefault 的地址）
    const defaultAddr = addresses.value.find((a) => a.isDefault)
    selectedAddressId.value = defaultAddr?.id ?? addresses.value[0]?.id ?? null
  } catch {
    // 静默降级：地址列表为空
  } finally {
    loadingAddress.value = false
  }
})

async function submitOrder() {
  if (cartStore.checkedItems.length === 0) {
    ElMessage.warning('请选择要购买的商品')
    return
  }
  if (!selectedAddressId.value) {
    ElMessage.warning('请选择收货地址')
    return
  }
  submitting.value = true
  try {
    const details = cartStore.checkedItems.map((item) => ({
      itemId: item.itemId,
      num: item.num,
    }))
    const orderId = await createOrder({ addressId: selectedAddressId.value, paymentType: 1, details })
    await cartStore.clearCart()
    router.push(`/portal/pay/${orderId}`)
  } catch {
    ElMessage.error('提交订单失败')
  } finally {
    submitting.value = false
  }
}
</script>
