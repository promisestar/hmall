<template>
  <PortalLayout :show-search="false">
    <div class="container-main py-6">
      <h2 class="text-lg font-bold mb-4 border-l-4 border-[#E4393C] pl-3">订单支付</h2>

      <div class="bg-white rounded-lg shadow-sm p-6 max-w-[600px] mx-auto">
        <!-- Order Info -->
        <div class="text-center mb-6">
          <p class="text-gray-500 text-sm">订单编号：{{ orderId }}</p>
          <p class="text-3xl font-bold text-[#E4393C] mt-2">¥{{ formatPrice(amount) }}</p>
          <p class="text-xs text-gray-400 mt-1" v-if="countdown > 0">
            剩余支付时间：{{ formatCountdown(countdown) }}
          </p>
        </div>

        <!-- Pay Method Tabs -->
        <div class="flex border rounded-lg overflow-hidden mb-6">
          <div
            v-for="method in payMethods"
            :key="method.value"
            class="flex-1 py-3 text-center cursor-pointer text-sm transition-colors"
            :class="activePayMethod === method.value
              ? 'bg-[#E4393C] text-white font-medium'
              : 'hover:bg-gray-50'"
            @click="activePayMethod = method.value"
          >
            {{ method.label }}
          </div>
        </div>

        <!-- Balance Payment -->
        <div v-if="activePayMethod === 'balance'" class="text-center">
          <div class="mb-4">
            <p class="text-sm text-gray-500 mb-2">
              账户余额：<span class="text-[#E4393C] font-bold">¥{{ formatPrice(userStore.balance) }}</span>
            </p>
          </div>
          <div class="flex justify-center mb-4">
            <input
              v-model="password"
              type="password"
              placeholder="请输入支付密码"
              class="border rounded-lg px-4 py-3 w-[280px] text-center outline-none focus:border-[#E4393C] focus:ring-1 focus:ring-[#E4393C]"
              @keyup.enter="payByBalance"
            />
          </div>
          <button
            @click="payByBalance"
            :disabled="paying || !password"
            class="btn-primary px-10 py-3 text-lg disabled:opacity-50"
          >
            {{ paying ? '支付中...' : '确认支付' }}
          </button>
        </div>

        <!-- QR Code Payment -->
        <div v-else class="text-center">
          <div class="bg-gray-50 rounded-lg p-8 inline-block mb-4">
            <img :src="qrDataUrl || '/img/erweima.png'" class="w-48 h-48 mx-auto" />
          </div>
          <p class="text-sm text-gray-500">请使用{{ activePayMethod === 'wechat' ? '微信' : '支付宝' }}扫码支付</p>
          <div class="mt-4">
            <el-button @click="refreshQr" size="small" :loading="refreshingQr">刷新二维码</el-button>
          </div>
        </div>

        <!-- Status Check -->
        <div class="mt-6 text-center">
          <router-link to="/portal/home" class="text-sm text-gray-500 hover:text-[#E4393C] transition-colors">
            返回首页
          </router-link>
          <span class="mx-2 text-gray-300">|</span>
          <span @click="checkPayStatus" class="text-sm text-[#E4393C] cursor-pointer hover:underline">
            查询支付状态
          </span>
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import QRCode from 'qrcode'
import PortalLayout from './PortalLayout.vue'
import { useUserStore } from '@/stores/user'
import { getOrderById } from '@/api/order'
import { applyPayOrder, tryPayOrderByBalance } from '@/api/pay'
import { formatPrice } from '@/utils/format'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const orderId = ref(Number(route.params.orderId))
const amount = ref(0)
const payOrderId = ref('')
const password = ref('')
const paying = ref(false)
const activePayMethod = ref<'balance' | 'wechat' | 'alipay'>('balance')
const qrDataUrl = ref('')
const refreshingQr = ref(false)
const countdown = ref(1800)
let timer: ReturnType<typeof setInterval> | null = null

const payMethods = [
  { label: '余额支付', value: 'balance' as const },
  { label: '微信支付', value: 'wechat' as const },
  { label: '支付宝', value: 'alipay' as const },
]

function formatCountdown(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
}

async function payByBalance() {
  if (!password.value) {
    ElMessage.warning('请输入支付密码')
    return
  }
  paying.value = true
  try {
    await tryPayOrderByBalance(Number(payOrderId.value), { id: Number(payOrderId.value), pw: password.value })
    router.push(`/portal/pay-success/${orderId.value}`)
  } catch {
    ElMessage.error('支付失败，请检查密码或余额')
  } finally {
    paying.value = false
  }
}

async function refreshQr() {
  refreshingQr.value = true
  try {
    const url = `hmall:pay:${orderId.value}:${Date.now()}`
    qrDataUrl.value = await QRCode.toDataURL(url, { width: 200, margin: 2 })
  } finally {
    refreshingQr.value = false
  }
}

async function checkPayStatus() {
  try {
    const order = await getOrderById(orderId.value)
    if (order.status === 2) {
      router.push(`/portal/pay-success/${orderId.value}`)
    } else {
      ElMessage.info('订单尚未支付')
    }
  } catch {
    ElMessage.error('查询失败')
  }
}

onMounted(async () => {
  try {
    const order = await getOrderById(orderId.value)
    amount.value = order.totalFee
    // 生成支付单（之前创建订单时漏了这一步）
    payOrderId.value = await applyPayOrder({
      bizOrderNo: orderId.value,
      amount: order.totalFee,
      payChannelCode: 'balance',
      payType: 5,          // 5 = 余额支付
      orderInfo: `订单${orderId.value}`,
    })
  } catch {
    ElMessage.error('获取订单信息失败')
  }
  await refreshQr()
  timer = setInterval(() => {
    if (countdown.value > 0) {
      countdown.value--
    } else {
      clearInterval(timer!)
      timer = null
    }
  }, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>
