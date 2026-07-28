<template>
  <PortalLayout :show-search="true">
    <div class="container-main py-4">
      <!-- 面包屑 -->
      <div class="text-xs text-gray-400 mb-3 flex items-center gap-1.5">
        <router-link to="/portal/home" class="hover:text-[#E4393C] transition-colors">首页</router-link>
        <ChevronRight class="w-3 h-3 text-gray-300" />
        <router-link to="/portal/seckill" class="hover:text-[#E4393C] transition-colors">限时秒杀</router-link>
        <ChevronRight class="w-3 h-3 text-gray-300" />
        <span class="text-gray-600">商品详情</span>
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="page-card p-24 text-center">
        <el-icon class="is-loading text-2xl text-[#E4393C] mb-3"><Loading /></el-icon>
        <p class="text-gray-400 text-sm">加载中...</p>
      </div>

      <!-- 商品不存在 -->
      <div v-else-if="!goods" class="page-card p-24 text-center">
        <PackageX class="w-16 h-16 mx-auto mb-4 text-gray-200" />
        <p class="text-gray-500">秒杀商品不存在或已下架</p>
        <router-link to="/portal/seckill" class="btn-secondary mt-5 text-sm">返回秒杀会场</router-link>
      </div>

      <template v-else>
        <!-- 商品主区 -->
        <div class="page-card p-7 mb-4">
          <div class="flex gap-9">
            <!-- 商品图 -->
            <div class="w-[420px] flex-shrink-0">
              <div class="relative w-full h-[420px] bg-gray-50 rounded-xl overflow-hidden border border-gray-100 group">
                <img
                  :src="goods.image || '/img/like_01.png'"
                  :alt="goods.name"
                  class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-700"
                />
                <div class="absolute top-3.5 left-3.5 bg-gradient-to-r from-[#f04548] to-[#d2202a] text-white text-xs px-3 py-1.5 rounded-lg font-bold shadow-glow">
                  {{ discountText }}
                </div>
                <div v-if="timeRangeText" class="absolute bottom-0 inset-x-0 bg-gradient-to-t from-black/50 to-transparent text-white text-xs px-4 py-2.5 flex items-center gap-1.5">
                  <Timer class="w-3.5 h-3.5" />
                  秒杀时段 {{ timeRangeText }}
                </div>
              </div>
            </div>

            <!-- 信息区 -->
            <div class="flex-1 min-w-0">
              <h1 class="text-xl font-bold text-gray-900 leading-relaxed mb-4">{{ goods.name }}</h1>
              <p v-if="goods.spec" class="text-[13px] text-gray-400 mb-4 -mt-2">{{ goods.spec }}</p>

              <!-- 秒杀价格面板 -->
              <div class="relative overflow-hidden rounded-xl bg-gradient-to-r from-[#FF2D2D] to-[#FF6B35] p-5 mb-4 text-white">
                <div class="absolute -right-6 -top-6 w-24 h-24 rounded-full bg-white/10"></div>
                <div class="flex items-center justify-between">
                  <div class="flex items-baseline gap-2">
                    <span class="text-xs bg-white/25 px-1.5 py-0.5 rounded font-medium">秒杀价</span>
                    <span class="text-sm font-bold">¥</span>
                    <span class="text-[34px] font-bold leading-none tracking-tight">{{ formatPrice(goods.seckillPrice) }}</span>
                    <del class="text-sm text-white/60">¥{{ formatPrice(goods.originalPrice) }}</del>
                  </div>
                  <!-- 倒计时 -->
                  <div v-if="timeLeft" class="text-center bg-white/15 backdrop-blur-sm rounded-xl px-4 py-2.5">
                    <p class="text-[11px] text-white/85 mb-1.5">{{ timeLabel }}</p>
                    <div class="flex items-center gap-1">
                      <span class="cd-block">{{ pad(timeLeft.hours) }}</span>
                      <span class="font-bold">:</span>
                      <span class="cd-block">{{ pad(timeLeft.minutes) }}</span>
                      <span class="font-bold">:</span>
                      <span class="cd-block">{{ pad(timeLeft.seconds) }}</span>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 库存进度 -->
              <div class="rounded-xl bg-gray-50 p-4 mb-4">
                <div class="flex justify-between text-xs text-gray-500 mb-2">
                  <span>秒杀进度</span>
                  <span>已抢 <em class="not-italic font-bold text-[#E4393C]">{{ soldPercent }}%</em>，剩余 {{ goods.remainingStock }} 件</span>
                </div>
                <div class="w-full bg-gray-200 rounded-full h-2 overflow-hidden">
                  <div
                    class="h-full rounded-full bg-gradient-to-r from-[#FF6B35] to-[#E4393C] transition-all duration-500"
                    :style="{ width: soldPercent + '%' }"
                  ></div>
                </div>
              </div>

              <!-- 信息 -->
              <div class="grid grid-cols-2 gap-x-8 gap-y-3 text-[13px] mb-6">
                <div class="flex items-center">
                  <span class="text-gray-400 w-14 flex-shrink-0">限量</span>
                  <span class="text-gray-700">{{ goods.totalStock }} 件</span>
                </div>
                <div class="flex items-center">
                  <span class="text-gray-400 w-14 flex-shrink-0">限购</span>
                  <span class="text-[#FF6B35] font-medium">每人限 {{ goods.limitNum }} 件</span>
                </div>
                <div class="flex items-center col-span-2">
                  <span class="text-gray-400 w-14 flex-shrink-0">服务</span>
                  <div class="flex items-center gap-4 text-gray-600">
                    <span class="flex items-center gap-1"><ShieldCheck class="w-3.5 h-3.5 text-[#E4393C]" />正品保障</span>
                    <span class="flex items-center gap-1"><Truck class="w-3.5 h-3.5 text-[#E4393C]" />极速发货</span>
                    <span class="flex items-center gap-1"><RotateCcw class="w-3.5 h-3.5 text-[#E4393C]" />7天无理由</span>
                  </div>
                </div>
              </div>

              <!-- 操作按钮 -->
              <div class="flex gap-3">
                <button
                  @click="handleBuy"
                  :disabled="!canBuy || buying"
                  class="flex items-center gap-2 px-12 py-3.5 rounded-xl text-white text-base font-semibold bg-gradient-to-r from-[#f04548] to-[#d2202a] shadow-glow hover:shadow-lift hover:-translate-y-0.5 active:translate-y-0 transition-all disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:translate-y-0"
                >
                  <Zap class="w-5 h-5" />
                  {{ buying ? buyingText : canBuy ? '立即抢购' : buttonText }}
                </button>
                <router-link
                  to="/portal/seckill"
                  class="flex items-center gap-2 px-8 py-3.5 rounded-xl border border-gray-200 text-gray-600 hover:border-[#E4393C] hover:text-[#E4393C] hover:bg-red-50/50 transition-all"
                >
                  返回秒杀列表
                </router-link>
              </div>
            </div>
          </div>
        </div>

        <!-- 秒杀说明 -->
        <div class="page-card p-7">
          <h3 class="section-title mb-5">秒杀说明</h3>
          <ul class="space-y-2.5 text-sm text-gray-500">
            <li class="flex items-start gap-2">
              <span class="w-1.5 h-1.5 rounded-full bg-[#E4393C] mt-2 flex-shrink-0"></span>
              秒杀商品数量有限，先到先得，抢完即止
            </li>
            <li class="flex items-start gap-2">
              <span class="w-1.5 h-1.5 rounded-full bg-[#E4393C] mt-2 flex-shrink-0"></span>
              每人限购 {{ goods.limitNum }} 件，超过限购数量将无法购买
            </li>
            <li class="flex items-start gap-2">
              <span class="w-1.5 h-1.5 rounded-full bg-[#E4393C] mt-2 flex-shrink-0"></span>
              秒杀订单请在 30 分钟内完成支付，超时订单将自动取消
            </li>
            <li class="flex items-start gap-2">
              <span class="w-1.5 h-1.5 rounded-full bg-[#E4393C] mt-2 flex-shrink-0"></span>
              秒杀商品不支持与其他优惠叠加使用
            </li>
          </ul>
        </div>
      </template>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Zap, Timer, ChevronRight, ShieldCheck, Truck, RotateCcw, PackageX } from 'lucide-vue-next'
import PortalLayout from './PortalLayout.vue'
import { useUserStore } from '@/stores/user'
import {
  getSeckillProduct,
  doSeckill,
  pollSeckillResult,
  type SeckillProductVO,
} from '@/api/seckill'
import { formatPrice } from '@/utils/format'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const goods = ref<SeckillProductVO | null>(null)
const loading = ref(true)
const buying = ref(false)
const buyingText = ref('抢购中...')
const timeLeft = ref<{ hours: number; minutes: number; seconds: number } | null>(null)
const timeLabel = ref('距秒杀结束')
let timer: ReturnType<typeof setInterval> | null = null

const relationId = Number(route.params.relationId)

function pad(n: number): string {
  return n.toString().padStart(2, '0')
}

/** 解析场次时间：兼容 "HH:mm" / "HH:mm:ss" / ISO 时间串 */
function parseTime(t?: string): Date | null {
  if (!t) return null
  const m = t.match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?$/)
  if (m) {
    const d = new Date()
    d.setHours(Number(m[1]), Number(m[2]), Number(m[3] || 0), 0)
    return d
  }
  const d = new Date(t)
  return Number.isNaN(d.getTime()) ? null : d
}

const startDate = computed(() => parseTime(goods.value?.startTime))
// 结束时间需处理跨天场次（如 22:00 - 次日 02:00）：结束早于开始则视为次日
const endDate = computed(() => {
  const st = parseTime(goods.value?.startTime)
  const et = parseTime(goods.value?.endTime)
  if (!st || !et) return et
  if (et.getTime() < st.getTime()) {
    return new Date(et.getTime() + 24 * 60 * 60 * 1000)
  }
  return et
})

const inTime = computed(() => {
  if (!startDate.value || !endDate.value) return true // 无时间信息时不阻塞购买
  const now = Date.now()
  return now >= startDate.value.getTime() && now <= endDate.value.getTime()
})

const timeRangeText = computed(() => {
  if (!startDate.value || !endDate.value) return ''
  return `${pad(startDate.value.getHours())}:${pad(startDate.value.getMinutes())} - ${pad(endDate.value.getHours())}:${pad(endDate.value.getMinutes())}`
})

const discountText = computed(() => {
  if (!goods.value || !goods.value.originalPrice) return ''
  return ((goods.value.seckillPrice / goods.value.originalPrice) * 10).toFixed(1) + '折'
})

const soldPercent = computed(() => {
  if (!goods.value || !goods.value.totalStock) return 100
  return Math.min(100, Math.round((goods.value.soldCount / goods.value.totalStock) * 100))
})

const canBuy = computed(() => {
  if (!goods.value) return false
  return inTime.value && goods.value.remainingStock > 0
})

const buttonText = computed(() => {
  if (!goods.value) return ''
  if (!inTime.value) return '活动未开始'
  if (goods.value.remainingStock <= 0) return '已抢光'
  return '立即抢购'
})

function updateCountdown() {
  if (!goods.value || !startDate.value || !endDate.value) {
    timeLeft.value = null
    return
  }
  const now = Date.now()
  if (inTime.value) {
    timeLabel.value = '距秒杀结束'
    setTimeLeft(Math.max(0, endDate.value.getTime() - now))
  } else if (now < startDate.value.getTime()) {
    timeLabel.value = '距秒杀开始'
    setTimeLeft(Math.max(0, startDate.value.getTime() - now))
  } else {
    timeLeft.value = null
  }
}

function setTimeLeft(diff: number) {
  const hours = Math.floor(diff / 3600000)
  const minutes = Math.floor((diff % 3600000) / 60000)
  const seconds = Math.floor((diff % 60000) / 1000)
  timeLeft.value = { hours, minutes, seconds }
}

async function handleBuy() {
  if (!userStore.isLogin) {
    ElMessage.warning('请先登录')
    router.push('/portal/login')
    return
  }

  buying.value = true
  buyingText.value = '抢购中...'
  try {
    const result = await doSeckill(relationId, 1)

    if (result.status === 'success' && result.orderId) {
      ElMessage.success('抢购成功！')
      router.push(`/portal/pay/${result.orderId}`)
      return
    }

    if (result.status === 'pending') {
      // 排队中：轮询秒杀结果（后端异步处理）
      buyingText.value = '排队中，请稍候...'
      const final = await pollSeckillResult(relationId)
      if (final.status === 'success' && final.orderId) {
        ElMessage.success('抢购成功！')
        router.push(`/portal/pay/${final.orderId}`)
      } else {
        ElMessage.error(final.message || '抢购失败')
      }
      return
    }

    ElMessage.error(result.message || '抢购失败')
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.msg || '抢购失败，请重试')
  } finally {
    buying.value = false
    buyingText.value = '抢购中...'
  }
}

onMounted(async () => {
  if (!relationId) {
    loading.value = false
    return
  }
  try {
    goods.value = await getSeckillProduct(relationId)
    updateCountdown()
    timer = setInterval(updateCountdown, 1000)
  } catch {
    goods.value = null
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.cd-block {
  @apply w-8 h-8 rounded-lg bg-black/30 backdrop-blur-sm text-white text-sm font-bold flex items-center justify-center tabular-nums;
}
</style>
