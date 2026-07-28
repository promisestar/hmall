<template>
  <PortalLayout :show-search="true">
    <!-- 秒杀头部横幅 -->
    <div class="relative bg-gradient-to-r from-[#FF2D2D] via-[#f0502e] to-[#FF6B35] overflow-hidden">
      <div class="absolute -left-10 top-1/2 -translate-y-1/2 w-52 h-52 rounded-full bg-white/10"></div>
      <div class="absolute right-1/4 -top-16 w-64 h-64 rounded-full bg-white/10"></div>
      <div class="absolute right-10 bottom-[-40px] w-40 h-40 rounded-full bg-white/5"></div>

      <div class="container-main relative py-10 flex items-center justify-between">
        <div>
          <div class="flex items-center gap-3">
            <div class="w-12 h-12 rounded-xl bg-white/20 flex items-center justify-center">
              <Zap class="w-7 h-7 text-white" />
            </div>
            <h1 class="text-3xl font-bold text-white tracking-wide">限时秒杀</h1>
          </div>
          <p class="text-white/80 text-sm mt-2.5">超值好物 · 整点开抢 · 手慢无</p>
        </div>

        <!-- 当前场次倒计时 -->
        <div v-if="activeSession && timeLeft" class="flex items-center gap-2.5 bg-white/15 backdrop-blur-sm rounded-2xl px-5 py-3">
          <Timer class="w-4 h-4 text-white" />
          <span class="text-white/90 text-[13px]">{{ timeLabel }}</span>
          <div class="flex items-center gap-1">
            <span class="cd-block">{{ pad(timeLeft.hours) }}</span>
            <span class="text-white font-bold">:</span>
            <span class="cd-block">{{ pad(timeLeft.minutes) }}</span>
            <span class="text-white font-bold">:</span>
            <span class="cd-block">{{ pad(timeLeft.seconds) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 场次切换 Tabs -->
    <div class="bg-white/95 backdrop-blur border-b border-gray-100">
      <div class="container-main">
        <div class="flex">
          <div
            v-for="s in sessions"
            :key="s.id"
            @click="switchSession(s)"
            class="flex-1 text-center py-4 cursor-pointer relative transition-colors group"
            :class="s.id === activeSession?.id ? '' : 'hover:bg-gray-50'"
          >
            <div
              class="text-base font-semibold transition-colors"
              :class="s.id === activeSession?.id ? 'text-[#E4393C]' : 'text-gray-700 group-hover:text-[#E4393C]'"
            >
              {{ s.name || sessionTimeLabel(s) }}
            </div>
            <div
              class="text-xs mt-1 transition-colors"
              :class="s.id === activeSession?.id ? 'text-[#E4393C]/80' : 'text-gray-400'"
            >
              {{ sessionTimeLabel(s) }} · {{ isSessionActive(s) ? '抢购中' : '即将开始' }}
            </div>
            <div
              v-if="s.id === activeSession?.id"
              class="absolute bottom-0 left-1/2 -translate-x-1/2 w-10 h-[3px] rounded-full bg-gradient-to-r from-[#f04548] to-[#d2202a]"
            ></div>
          </div>
          <!-- 无场次数据时的占位 -->
          <div v-if="sessions.length === 0 && !loading" class="flex-1 py-4 text-center text-gray-300 text-sm">
            暂无秒杀场次
          </div>
        </div>
      </div>
    </div>

    <!-- 商品列表 -->
    <div class="container-main py-6">
      <!-- 加载中 -->
      <div v-if="loading" class="flex justify-center py-24">
        <el-icon class="is-loading text-3xl text-[#E4393C]"><Loading /></el-icon>
      </div>

      <!-- 空状态 -->
      <div v-else-if="products.length === 0" class="page-card py-24 text-center">
        <div class="w-20 h-20 mx-auto mb-5 rounded-full bg-red-50 flex items-center justify-center">
          <Zap class="w-10 h-10 text-[#E4393C]/40" />
        </div>
        <p class="text-gray-500">该场次暂无秒杀商品</p>
        <p class="text-xs text-gray-400 mt-2">敬请期待下一场</p>
      </div>

      <!-- 商品网格 -->
      <div v-else class="grid grid-cols-4 gap-4">
        <div
          v-for="p in products"
          :key="p.relationId"
          class="group page-card overflow-hidden cursor-pointer hover:shadow-lift hover:-translate-y-1 transition-all duration-300"
          @click="goDetail(p)"
        >
          <!-- 商品图 -->
          <div class="relative overflow-hidden">
            <img
              :src="p.image || '/img/like_01.png'"
              :alt="p.name"
              class="w-full h-48 object-cover group-hover:scale-105 transition-transform duration-500"
            />
            <!-- 折扣标签 -->
            <div class="absolute top-2.5 left-2.5 bg-gradient-to-r from-[#f04548] to-[#d2202a] text-white text-xs px-2.5 py-1 rounded-lg font-bold shadow-glow">
              {{ discountText(p) }}
            </div>
            <div class="absolute top-2.5 right-2.5 bg-black/50 backdrop-blur-sm text-white/90 text-[10px] px-2 py-1 rounded-lg">
              秒杀价
            </div>
          </div>

          <div class="p-3.5">
            <!-- 商品名 -->
            <p class="text-[13px] text-gray-800 line-clamp-2 min-h-[40px] leading-relaxed group-hover:text-[#E4393C] transition-colors">
              {{ p.name }}
            </p>

            <!-- 价格区 -->
            <div class="flex items-baseline gap-2 mt-2">
              <span class="price-tag text-xs">¥</span>
              <span class="price-tag text-[22px]">{{ formatPrice(p.seckillPrice) }}</span>
              <del class="text-xs text-gray-300">¥{{ formatPrice(p.originalPrice) }}</del>
            </div>

            <!-- 库存进度 -->
            <div class="mt-2.5">
              <div class="flex justify-between text-[11px] text-gray-400 mb-1">
                <span>已抢{{ soldPercent(p) }}%</span>
                <span>剩 {{ p.remainingStock }} 件</span>
              </div>
              <div class="w-full bg-gray-100 rounded-full h-1.5 overflow-hidden">
                <div
                  class="h-full rounded-full bg-gradient-to-r from-[#FF6B35] to-[#E4393C] transition-all duration-500"
                  :style="{ width: soldPercent(p) + '%' }"
                ></div>
              </div>
            </div>

            <!-- 购买按钮 -->
            <button
              class="w-full mt-3.5 py-2.5 rounded-xl font-semibold text-sm transition-all"
              :class="buttonClass(p)"
              :disabled="!canBuy(p)"
              @click.stop="goDetail(p)"
            >
              {{ buttonText(p) }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { Zap, Timer } from 'lucide-vue-next'
import PortalLayout from './PortalLayout.vue'
import {
  getSeckillActivities,
  type SeckillSessionVO,
  type SeckillProductVO,
} from '@/api/seckill'
import { formatPrice } from '@/utils/format'

const router = useRouter()

const sessions = ref<SeckillSessionVO[]>([])
const activeSession = ref<SeckillSessionVO | null>(null)
const loading = ref(false)
const timeLeft = ref<{ hours: number; minutes: number; seconds: number } | null>(null)
const timeLabel = ref('距结束')
let timer: ReturnType<typeof setInterval> | null = null

// 当前场次的商品（商品挂在场次下，随活动接口一起返回）
const products = computed<SeckillProductVO[]>(() => activeSession.value?.products || [])

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

function sessionTimeLabel(s: SeckillSessionVO): string {
  const st = parseTime(s.startTime)
  const et = parseTime(s.endTime)
  if (!st || !et) return ''
  return `${pad(st.getHours())}:${pad(st.getMinutes())} - ${pad(et.getHours())}:${pad(et.getMinutes())}`
}

function isSessionActive(s: SeckillSessionVO): boolean {
  const st = parseTime(s.startTime)
  const et = resolveEndTime(s)
  if (!st || !et) return false
  const now = Date.now()
  return now >= st.getTime() && now <= et.getTime()
}

/** 解析结束时间，处理跨天场次（如 22:00 - 次日 02:00）：结束早于开始则视为次日 */
function resolveEndTime(s: SeckillSessionVO): Date | null {
  const st = parseTime(s.startTime)
  const et = parseTime(s.endTime)
  if (!st || !et) return et
  if (et.getTime() < st.getTime()) {
    return new Date(et.getTime() + 24 * 60 * 60 * 1000)
  }
  return et
}

function discountText(p: SeckillProductVO): string {
  if (!p.originalPrice) return ''
  return ((p.seckillPrice / p.originalPrice) * 10).toFixed(1) + '折'
}

function soldPercent(p: SeckillProductVO): number {
  if (!p.totalStock) return 100
  return Math.min(100, Math.round((p.soldCount / p.totalStock) * 100))
}

function canBuy(p: SeckillProductVO): boolean {
  return !!activeSession.value && isSessionActive(activeSession.value) && p.remainingStock > 0
}

function buttonText(p: SeckillProductVO): string {
  if (p.remainingStock <= 0) return '已抢光'
  if (!activeSession.value || !isSessionActive(activeSession.value)) return '即将开始'
  return '立即抢购'
}

function buttonClass(p: SeckillProductVO): string {
  if (canBuy(p)) {
    return 'bg-gradient-to-r from-[#f04548] to-[#d2202a] text-white shadow-glow hover:shadow-lift hover:-translate-y-0.5 active:translate-y-0'
  }
  return 'bg-gray-100 text-gray-300 cursor-not-allowed'
}

function switchSession(s: SeckillSessionVO) {
  if (activeSession.value?.id === s.id) return
  activeSession.value = s
  updateCountdown()
}

function updateCountdown() {
  if (!activeSession.value) return
  const st = parseTime(activeSession.value.startTime)
  const et = resolveEndTime(activeSession.value)
  if (!st || !et) {
    timeLeft.value = null
    return
  }

  const now = Date.now()
  if (isSessionActive(activeSession.value)) {
    timeLabel.value = '距本场结束'
    setTimeLeft(Math.max(0, et.getTime() - now))
  } else {
    timeLabel.value = '距本场开始'
    setTimeLeft(Math.max(0, st.getTime() - now))
  }
}

function setTimeLeft(diff: number) {
  const hours = Math.floor(diff / 3600000)
  const minutes = Math.floor((diff % 3600000) / 60000)
  const seconds = Math.floor((diff % 60000) / 1000)
  timeLeft.value = { hours, minutes, seconds }
}

onMounted(async () => {
  loading.value = true
  try {
    const activities = await getSeckillActivities()
    // 汇总所有活动的场次（通常只有一个进行中活动）
    sessions.value = (activities || []).flatMap((a) => a.sessions || [])
    // 默认选中正在进行的场次，否则选第一个
    activeSession.value =
      sessions.value.find((s) => isSessionActive(s)) || sessions.value[0] || null
    updateCountdown()
  } catch {
    sessions.value = []
  } finally {
    loading.value = false
  }

  // 每秒更新倒计时
  timer = setInterval(updateCountdown, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

function goDetail(p: SeckillProductVO) {
  router.push(`/portal/seckill/${p.relationId}`)
}
</script>

<style scoped>
.cd-block {
  @apply w-8 h-8 rounded-lg bg-black/30 backdrop-blur-sm text-white text-sm font-bold flex items-center justify-center tabular-nums;
}
</style>
