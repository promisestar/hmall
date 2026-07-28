<template>
  <PortalLayout :show-search="false">
    <div class="container-main py-5">
      <h2 class="section-title mb-4">确认订单</h2>

      <div class="page-card p-6">
        <!-- 收货地址 -->
        <div class="mb-7">
          <div class="flex items-center justify-between mb-3">
            <h4 class="flex items-center gap-1.5 text-sm font-semibold text-gray-800">
              <MapPin class="w-4 h-4 text-[#E4393C]" />收货地址
            </h4>
            <router-link to="/portal/address" class="text-xs text-[#E4393C] hover:underline">
              管理收货地址
            </router-link>
          </div>
          <div v-if="loadingAddress" class="text-sm text-gray-400 py-2">加载中...</div>
          <div v-else-if="addresses.length === 0" class="text-sm text-gray-400 py-2">
            暂无收货地址，请先
            <router-link to="/portal/address" class="text-[#E4393C] hover:underline">新增地址</router-link>
          </div>
          <div v-else class="grid grid-cols-2 gap-3">
            <label
              v-for="addr in addresses"
              :key="addr.id"
              class="relative flex items-start gap-3 p-3.5 border rounded-xl cursor-pointer transition-all"
              :class="selectedAddressId === addr.id
                ? 'border-[#E4393C] bg-red-50/60 shadow-[0_0_0_3px_rgba(228,57,60,.06)]'
                : 'border-gray-200 hover:border-[#E4393C]/50'"
            >
              <input
                type="radio"
                :value="addr.id"
                v-model="selectedAddressId"
                class="mt-1 accent-[#E4393C]"
              />
              <div class="text-[13px] min-w-0">
                <p class="font-semibold text-gray-800">
                  {{ addr.contact }}
                  <span class="text-gray-400 font-normal ml-2">{{ addr.mobile }}</span>
                  <span v-if="addr.isDefault === 1" class="ml-1.5 px-1.5 py-px bg-[#E4393C] text-white text-[10px] rounded">默认</span>
                </p>
                <p class="text-gray-500 mt-1 leading-relaxed">{{ addr.province }}{{ addr.city }}{{ addr.town }} {{ addr.street }}</p>
              </div>
              <CheckCircle2
                v-if="selectedAddressId === addr.id"
                class="absolute top-3 right-3 w-4 h-4 text-[#E4393C]"
              />
            </label>
          </div>
        </div>

        <!-- 商品清单 -->
        <div class="mb-6">
          <h4 class="flex items-center gap-1.5 text-sm font-semibold text-gray-800 mb-3">
            <ShoppingBag class="w-4 h-4 text-[#E4393C]" />商品清单
          </h4>
          <div class="border border-gray-100 rounded-xl overflow-hidden">
            <div
              v-for="item in cartStore.checkedItems"
              :key="item.id"
              class="flex items-center gap-4 px-4 py-3.5 border-b border-gray-50 last:border-0 hover:bg-gray-50/50 transition-colors"
            >
              <img :src="item.image || '/img/like_01.png'" class="w-14 h-14 object-cover rounded-lg border border-gray-100" />
              <span class="flex-1 text-[13px] text-gray-700 line-clamp-2 min-w-0">{{ item.name }}</span>
              <span class="text-[13px] text-gray-400 whitespace-nowrap">¥{{ formatPrice(item.price) }} × {{ item.num }}</span>
              <span class="price-tag text-sm w-24 text-right">¥{{ formatPrice(item.price * item.num) }}</span>
            </div>
          </div>
        </div>

        <!-- 金额汇总 + 提交 -->
        <div class="rounded-xl bg-gray-50 px-6 py-4">
          <div class="flex justify-end items-baseline gap-2">
            <span class="text-[13px] text-gray-500">共 {{ cartStore.totalNum }} 件商品，应付总额：</span>
            <span class="price-tag text-xs">¥</span>
            <span class="price-tag text-[26px]">{{ formatPrice(cartStore.totalPrice) }}</span>
          </div>
          <div class="flex justify-end gap-3 mt-4">
            <router-link to="/portal/cart" class="btn-secondary">返回购物车</router-link>
            <button
              @click="submitOrder"
              :disabled="submitting"
              class="btn-primary text-base px-10 disabled:opacity-50"
            >
              {{ submitting ? '提交中...' : '提交订单' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { MapPin, ShoppingBag, CheckCircle2 } from 'lucide-vue-next'
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
