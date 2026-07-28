<template>
  <PortalLayout :show-search="false">
    <div class="container-main py-6">
      <h2 class="section-title mb-4">订单支付</h2>

      <div class="page-card p-8 max-w-[620px] mx-auto">
        <!-- 订单信息 -->
        <div class="text-center pb-6 border-b border-dashed border-gray-200">
          <p class="text-xs text-gray-400">订单编号：{{ orderId }}</p>
          <div class="flex items-baseline justify-center gap-1 mt-3">
            <span class="price-tag text-base">¥</span>
            <span class="price-tag text-[36px] leading-none">{{ formatPrice(amount) }}</span>
          </div>
          <p v-if="countdown > 0" class="inline-flex items-center gap-1.5 text-xs text-[#FF6B35] bg-orange-50 rounded-full px-3 py-1 mt-3">
            <Timer class="w-3.5 h-3.5" />
            剩余支付时间 {{ formatCountdown(countdown) }}
          </p>
        </div>

        <!-- 支付方式 -->
        <div class="grid grid-cols-3 gap-3 my-6">
          <div
            v-for="method in payMethods"
            :key="method.value"
            class="relative flex flex-col items-center gap-2 py-4 rounded-xl border cursor-pointer transition-all"
            :class="activePayMethod === method.value
              ? 'border-[#E4393C] bg-red-50/60 shadow-[0_0_0_3px_rgba(228,57,60,.06)]'
              : 'border-gray-200 hover:border-[#E4393C]/50'"
            @click="activePayMethod = method.value"
          >
            <component :is="method.icon" class="w-6 h-6" :class="activePayMethod === method.value ? 'text-[#E4393C]' : 'text-gray-400'" />
            <span class="text-[13px]" :class="activePayMethod === method.value ? 'text-[#E4393C] font-semibold' : 'text-gray-600'">
              {{ method.label }}
            </span>
            <CheckCircle2
              v-if="activePayMethod === method.value"
              class="absolute top-2 right-2 w-4 h-4 text-[#E4393C]"
            />
          </div>
        </div>

        <!-- 余额支付 -->
        <div v-if="activePayMethod === 'balance'" class="text-center">
          <p class="text-[13px] text-gray-500 mb-5">
            账户余额：<span class="price-tag text-base">¥{{ formatPrice(userStore.balance) }}</span>
          </p>
          <div class="flex justify-center mb-5">
            <input
              v-model="password"
              type="password"
              placeholder="请输入支付密码"
              class="border border-gray-200 rounded-xl px-4 py-3 w-[280px] text-center text-sm outline-none focus:border-[#E4393C] focus:shadow-[0_0_0_3px_rgba(228,57,60,.08)] transition-all"
              @keyup.enter="payByBalance"
            />
          </div>
          <button
            @click="payByBalance"
            :disabled="paying || !password"
            class="btn-primary px-12 py-3 text-base disabled:opacity-50"
          >
            {{ paying ? '支付中...' : '确认支付' }}
          </button>
        </div>

        <!-- 扫码支付 -->
        <div v-else class="text-center">
          <div class="inline-block bg-white border border-gray-100 rounded-2xl p-5 shadow-card mb-3">
            <img :src="qrDataUrl || '/img/erweima.png'" class="w-44 h-44 mx-auto" />
          </div>
          <p class="text-[13px] text-gray-500">请使用{{ activePayMethod === 'wechat' ? '微信' : '支付宝' }}「扫一扫」完成支付</p>
          <div class="mt-4">
            <el-button @click="refreshQr" size="small" round :loading="refreshingQr">刷新二维码</el-button>
          </div>
        </div>

        <!-- 辅助操作 -->
        <div class="mt-8 text-center text-xs">
          <router-link to="/portal/home" class="text-gray-400 hover:text-[#E4393C] transition-colors">
            返回首页
          </router-link>
          <span class="mx-2.5 text-gray-200">|</span>
          <span @click="checkPayStatus" class="text-[#E4393C] cursor-pointer hover:underline">
            我已完成支付，查询支付状态
          </span>
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, markRaw } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import QRCode from 'qrcode'
import { Wallet, MessageCircle, QrCode as QrCodeIcon, Timer, CheckCircle2 } from 'lucide-vue-next'
import PortalLayout from './PortalLayout.vue'
import { useUserStore } from '@/stores/user'
import { getOrderById } from '@/api/order'
import { applyPayOrder, tryPayOrderByBalance } from '@/api/pay'
import { formatPrice } from '@/utils/format'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const orderId = ref(route.params.orderId as string)
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
  { label: '余额支付', value: 'balance' as const, icon: markRaw(Wallet) },
  { label: '微信支付', value: 'wechat' as const, icon: markRaw(MessageCircle) },
  { label: '支付宝', value: 'alipay' as const, icon: markRaw(QrCodeIcon) },
]

// 支付渠道映射：不同支付方式对应后端的 payChannelCode / payType
const channelMap = {
  balance: { code: 'balance', type: 5 },
  wechat: { code: 'wx_pub_qr', type: 4 },
  alipay: { code: 'alipay_qr', type: 3 },
}

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
    await tryPayOrderByBalance(payOrderId.value, { id: payOrderId.value, pw: password.value })
    // 支付成功后同步更新 sessionStorage 中的余额，避免页面刷新后展示陈旧数据
    if (userStore.userInfo) {
      userStore.setUserInfo({
        ...userStore.userInfo,
        balance: userStore.balance - amount.value,
      })
    }
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

/** 按当前选中的支付渠道创建/刷新支付单 */
async function ensurePayOrder() {
  const { code, type } = channelMap[activePayMethod.value]
  payOrderId.value = await applyPayOrder({
    bizOrderNo: orderId.value,
    amount: amount.value,
    payChannelCode: code,
    payType: type,
    orderInfo: `订单${orderId.value}`,
  })
  // 扫码渠道需要刷新二维码
  if (activePayMethod.value !== 'balance') {
    await refreshQr()
  }
}

// 切换支付方式时重新创建对应渠道的支付单
watch(activePayMethod, async () => {
  if (!amount.value) return // 订单信息尚未加载完成时忽略
  try {
    await ensurePayOrder()
  } catch {
    ElMessage.error('创建支付单失败')
  }
})

onMounted(async () => {
  try {
    const order = await getOrderById(orderId.value)
    amount.value = order.totalFee
    // 生成支付单（按当前选中的支付渠道创建）
    await ensurePayOrder()
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
