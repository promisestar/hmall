<template>
  <PortalLayout>
    <!-- 秒杀头部 -->
    <div class="bg-gradient-to-r from-[#FF2D2D] to-[#FF6B35] py-8">
      <div class="container-main">
        <div class="flex items-center gap-3 text-white">
          <Zap class="w-10 h-10" />
          <div>
            <h1 class="text-3xl font-bold tracking-wide">限时秒杀</h1>
            <p class="text-sm opacity-80 mt-1">超低折扣 限量抢购 手慢无</p>
          </div>
        </div>
      </div>
    </div>

    <!-- 场次切换栏 -->
    <div class="bg-white border-b-2 border-[#FF2D2D] sticky top-[65px] z-40">
      <div class="container-main">
        <div class="flex items-center gap-1 overflow-x-auto py-2">
          <button
            v-for="session in allSessions"
            :key="session.id"
            @click="activeSessionId = session.id"
            :class="[
              'flex-shrink-0 px-6 py-3 rounded-t-lg transition-all text-center min-w-[120px]',
              activeSessionId === session.id
                ? 'bg-[#FF2D2D] text-white shadow-lg scale-105'
                : 'bg-gray-50 text-gray-600 hover:bg-gray-100',
              session.status === 2 && 'opacity-40 cursor-not-allowed',
            ]"
          >
            <div class="text-base font-bold">{{ formatSessionTime(session.startTime) }}</div>
            <div class="text-xs mt-0.5">
              <span v-if="session.status === 0">即将开始</span>
              <span v-else-if="session.status === 1" class="animate-pulse">抢购中</span>
              <span v-else>已结束</span>
            </div>
          </button>
        </div>
      </div>
    </div>

    <!-- 商品列表 -->
    <div class="container-main py-6">
      <div v-if="activeProducts.length === 0" class="text-center py-20 text-gray-400">
        <Package class="w-16 h-16 mx-auto mb-4 opacity-40" />
        <p class="text-lg">暂无秒杀商品</p>
      </div>

      <div v-else class="grid grid-cols-2 gap-4">
        <div
          v-for="product in activeProducts"
          :key="product.relationId"
          class="bg-white rounded-xl overflow-hidden shadow-sm hover:shadow-xl transition-all duration-300 hover:-translate-y-1 cursor-pointer flex"
          @click="goDetail(product.relationId)"
        >
          <!-- 商品图片 -->
          <div class="w-[160px] flex-shrink-0 relative">
            <img
              :src="product.image || '/img/like_01.png'"
              class="w-full h-full object-cover"
              alt="秒杀商品"
            />
            <div
              v-if="product.status === 2 || product.remainingStock === 0"
              class="absolute inset-0 bg-black/50 flex items-center justify-center"
            >
              <span class="text-white text-xl font-bold tracking-wider rotate-[-15deg] border-2 border-white px-3 py-1 rounded">
                已抢光
              </span>
            </div>
          </div>

          <!-- 商品信息 -->
          <div class="flex-1 p-4 flex flex-col justify-between">
            <div>
              <h3 class="text-sm font-medium text-gray-800 line-clamp-2 leading-snug">{{ product.name }}</h3>
              <p v-if="product.spec" class="text-xs text-gray-400 mt-1 truncate">{{ product.spec }}</p>

              <!-- 库存进度条 -->
              <div class="mt-2">
                <div class="flex items-center justify-between text-xs mb-1">
                  <span class="text-[#FF2D2D] font-medium">
                    已抢 {{ soldPercent(product) }}%
                  </span>
                  <span class="text-gray-400">剩余 {{ product.remainingStock }} 件</span>
                </div>
                <div class="h-2 bg-gray-100 rounded-full overflow-hidden">
                  <div
                    class="h-full bg-gradient-to-r from-[#FF2D2D] to-[#FF6B35] rounded-full transition-all duration-500"
                    :style="{ width: soldPercent(product) + '%' }"
                  ></div>
                </div>
              </div>
            </div>

            <!-- 价格和按钮 -->
            <div class="flex items-end justify-between mt-3">
              <div>
                <div class="flex items-baseline gap-1">
                  <span class="text-[#FF2D2D] text-xs font-medium">¥</span>
                  <span class="text-[#FF2D2D] text-2xl font-bold">{{ formatPrice(product.seckillPrice) }}</span>
                </div>
                <div class="text-xs text-gray-400 line-through">¥{{ formatPrice(product.originalPrice) }}</div>
              </div>
              <button
                :class="[
                  'px-5 py-2 rounded-lg text-sm font-medium transition-all',
                  product.status === 1 && product.remainingStock > 0
                    ? 'bg-gradient-to-r from-[#FF2D2D] to-[#FF6B35] text-white hover:shadow-lg active:scale-95 animate-pulse'
                    : 'bg-gray-200 text-gray-400 cursor-not-allowed',
                ]"
                @click.stop="goDetail(product.relationId)"
              >
                {{ getButtonText(product) }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { Zap, Package } from 'lucide-vue-next'
import PortalLayout from './PortalLayout.vue'
import { getSeckillActivities } from '@/api/seckill'
import { formatPrice } from '@/utils/format'
import type { SeckillActivityVO, SeckillSessionVO, SeckillProductVO } from '@/api/seckill'

const router = useRouter()
const activities = ref<SeckillActivityVO[]>([])
const activeSessionId = ref<number>(0)
const now = ref(new Date())
let timer: ReturnType<typeof setInterval>

const allSessions = computed<SeckillSessionVO[]>(() => {
  const sessions: SeckillSessionVO[] = []
  for (const act of activities.value) {
    for (const s of act.sessions) {
      sessions.push({ ...s, status: getSessionStatus(s) })
    }
  }
  return sessions
})

const activeProducts = computed<SeckillProductVO[]>(() => {
  const session = allSessions.value.find((s) => s.id === activeSessionId.value)
  return session?.products || []
})

function getSessionStatus(session: SeckillSessionVO): number {
  const start = new Date(session.startTime).getTime()
  const end = new Date(session.endTime).getTime()
  const t = now.value.getTime()
  if (t < start) return 0
  if (t > end) return 2
  return 1
}

function formatSessionTime(timeStr: string): string {
  const d = new Date(timeStr)
  const h = String(d.getHours()).padStart(2, '0')
  const m = String(d.getMinutes()).padStart(2, '0')
  return `${h}:${m}`
}

function soldPercent(product: SeckillProductVO): number {
  if (!product.totalStock || product.totalStock === 0) return 0
  const sold = product.totalStock - product.remainingStock
  return Math.min(100, Math.round((sold / product.totalStock) * 100))
}

function getButtonText(product: SeckillProductVO): string {
  if (product.remainingStock === 0) return '已抢光'
  if (product.status === 0) return '即将开始'
  if (product.status === 2) return '已结束'
  return '立即抢购'
}

function goDetail(relationId: number) {
  router.push(`/portal/seckill/${relationId}`)
}

async function fetchActivities() {
  try {
    const res = await getSeckillActivities()
    activities.value = res || []
    // 默认选中第一个进行中或即将开始的场次
    const sessions = allSessions.value
    const active = sessions.find((s) => s.status === 1) || sessions.find((s) => s.status === 0)
    if (active) activeSessionId.value = active.id
    else if (sessions.length > 0) activeSessionId.value = sessions[0].id
  } catch (error) {
    console.error('获取秒杀活动失败', error)
  }
}

onMounted(() => {
  fetchActivities()
  timer = setInterval(() => {
    now.value = new Date()
  }, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>
