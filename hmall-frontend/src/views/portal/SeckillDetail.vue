<template>
  <PortalLayout>
    <div class="container-main py-6" v-if="product">
      <!-- 面包屑 -->
      <div class="flex items-center gap-2 text-sm text-gray-500 mb-4">
        <router-link to="/portal/seckill" class="hover:text-[#FF2D2D]">秒杀首页</router-link>
        <span>></span>
        <span class="text-gray-700">{{ product.name }}</span>
      </div>

      <!-- 商品信息区 -->
      <div class="bg-white rounded-xl overflow-hidden shadow-sm">
        <div class="bg-gradient-to-r from-[#FF2D2D] to-[#FF6B35] px-6 py-3 text-white flex items-center gap-2">
          <Zap class="w-5 h-5" />
          <span class="font-bold">限时秒杀</span>
          <!-- 倒计时 -->
          <div class="ml-auto flex items-center gap-2 text-sm">
            <span v-if="countdownLabel">{{ countdownLabel }}</span>
            <div class="flex items-center gap-1">
              <span class="bg-black/30 rounded px-1.5 py-0.5 font-mono">{{ countdown.h }}</span>
              <span>:</span>
              <span class="bg-black/30 rounded px-1.5 py-0.5 font-mono">{{ countdown.m }}</span>
              <span>:</span>
              <span class="bg-black/30 rounded px-1.5 py-0.5 font-mono">{{ countdown.s }}</span>
            </div>
          </div>
        </div>

        <div class="flex gap-6 p-6">
          <!-- 商品大图 -->
          <div class="w-[400px] flex-shrink-0">
            <img
              :src="product.image || '/img/like_01.png'"
              class="w-full h-[400px] object-cover rounded-lg"
              alt="商品图片"
            />
          </div>

          <!-- 商品信息 -->
          <div class="flex-1 flex flex-col">
            <h1 class="text-xl font-bold text-gray-800">{{ product.name }}</h1>
            <p v-if="product.spec" class="text-sm text-gray-500 mt-2">{{ product.spec }}</p>

            <!-- 价格区 -->
            <div class="bg-gradient-to-r from-[#FFF5F5] to-[#FFF0E0] rounded-lg p-4 mt-4">
              <div class="flex items-baseline gap-3">
                <span class="text-[#FF2D2D] text-sm">秒杀价</span>
                <span class="text-[#FF2D2D] text-sm font-medium">¥</span>
                <span class="text-[#FF2D2D] text-4xl font-bold">{{ formatPrice(product.seckillPrice) }}</span>
                <span class="text-gray-400 text-sm line-through">原价 ¥{{ formatPrice(product.originalPrice) }}</span>
              </div>
              <div class="flex items-center gap-4 mt-2 text-sm text-gray-500">
                <span>限购 {{ product.limitNum }} 件</span>
                <span>剩余 {{ product.remainingStock }} 件</span>
              </div>
            </div>

            <!-- 库存进度条 -->
            <div class="mt-4">
              <div class="flex items-center justify-between text-xs mb-1.5">
                <span class="text-[#FF2D2D] font-medium">已抢 {{ soldPercent }}%</span>
                <span class="text-gray-400">总量 {{ product.totalStock }} 件</span>
              </div>
              <div class="h-3 bg-gray-100 rounded-full overflow-hidden">
                <div
                  class="h-full bg-gradient-to-r from-[#FF2D2D] to-[#FF6B35] rounded-full transition-all duration-700"
                  :style="{ width: soldPercent + '%' }"
                ></div>
              </div>
            </div>

            <!-- 数量选择 -->
            <div class="flex items-center gap-4 mt-6">
              <span class="text-sm text-gray-600">购买数量</span>
              <div class="flex items-center border border-gray-300 rounded-lg overflow-hidden">
                <button
                  @click="quantity > 1 && quantity--"
                  :disabled="quantity <= 1"
                  class="px-3 py-2 text-gray-500 hover:bg-gray-50 disabled:opacity-30"
                >-</button>
                <input
                  v-model.number="quantity"
                  type="number"
                  min="1"
                  :max="product.limitNum"
                  class="w-14 text-center py-2 outline-none border-x border-gray-300"
                />
                <button
                  @click="quantity < product.limitNum && quantity++"
                  :disabled="quantity >= product.limitNum"
                  class="px-3 py-2 text-gray-500 hover:bg-gray-50 disabled:opacity-30"
                >+</button>
              </div>
              <span class="text-xs text-gray-400">每人限购 {{ product.limitNum }} 件</span>
            </div>

            <!-- 抢购按钮 -->
            <div class="mt-8">
              <button
                v-if="product.status === 1 && product.remainingStock > 0 && !seckillLoading && !result"
                @click="handleSeckill"
                class="w-full py-4 bg-gradient-to-r from-[#FF2D2D] to-[#FF6B35] text-white text-lg font-bold rounded-xl hover:shadow-2xl active:scale-[0.98] transition-all animate-pulse"
              >
                立即抢购
              </button>
              <button
                v-else-if="product.status === 0"
                disabled
                class="w-full py-4 bg-gray-200 text-gray-400 text-lg font-bold rounded-xl cursor-not-allowed"
              >
                活动即将开始
              </button>
              <button
                v-else-if="product.status === 2 || product.remainingStock === 0"
                disabled
                class="w-full py-4 bg-gray-200 text-gray-400 text-lg font-bold rounded-xl cursor-not-allowed"
              >
                已抢光
              </button>

              <!-- 抢购结果提示 -->
              <div v-if="result" class="mt-4 p-4 rounded-lg text-center" :class="resultBgClass">
                <p class="font-medium">{{ result.message }}</p>
                <button
                  v-if="result.status === 'success'"
                  @click="goPay"
                  class="mt-3 px-6 py-2 bg-[#FF2D2D] text-white rounded-lg text-sm hover:bg-[#E4393C] transition-colors"
                >
                  去支付
                </button>
                <button
                  v-if="result.status === 'failed'"
                  @click="resetResult"
                  class="mt-3 px-6 py-2 border border-gray-300 rounded-lg text-sm text-gray-600 hover:bg-gray-50 transition-colors"
                >
                  返回
                </button>
              </div>

              <!-- 排队进度 -->
              <div v-if="seckillLoading" class="mt-4 p-4 bg-[#FFF5F5] rounded-lg text-center">
                <div class="flex items-center justify-center gap-2 text-[#FF2D2D]">
                  <div class="w-4 h-4 border-2 border-[#FF2D2D] border-t-transparent rounded-full animate-spin"></div>
                  <span class="text-sm">排队中，请稍候... ({{ pollAttempt }}/30)</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 加载中 -->
    <div v-else class="container-main py-20 text-center text-gray-400">
      <div class="w-8 h-8 border-2 border-gray-300 border-t-[#FF2D2D] rounded-full animate-spin mx-auto mb-4"></div>
      <p>加载中...</p>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Zap } from 'lucide-vue-next'
import PortalLayout from './PortalLayout.vue'
import { getSeckillProduct, doSeckill, pollSeckillResult } from '@/api/seckill'
import { formatPrice } from '@/utils/format'
import type { SeckillProductVO, SeckillResultVO } from '@/api/seckill'

const route = useRoute()
const router = useRouter()

const product = ref<SeckillProductVO | null>(null)
const quantity = ref(1)
const seckillLoading = ref(false)
const result = ref<SeckillResultVO | null>(null)
const pollAttempt = ref(0)
const now = ref(new Date())
let timer: ReturnType<typeof setInterval>

const relationId = computed(() => Number(route.params.relationId))

const soldPercent = computed(() => {
  if (!product.value || !product.value.totalStock) return 0
  const sold = product.value.totalStock - product.value.remainingStock
  return Math.min(100, Math.round((sold / product.value.totalStock) * 100))
})

const countdownLabel = computed(() => {
  if (!product.value) return ''
  return product.value.status === 0 ? '距开始' : product.value.status === 1 ? '距结束' : ''
})

const countdown = computed(() => {
  if (!product.value) return { h: '00', m: '00', s: '00' }
  return calculateCountdown()
})

const resultBgClass = computed(() => {
  if (!result.value) return ''
  if (result.value.status === 'success') return 'bg-green-50 text-green-700'
  if (result.value.status === 'failed') return 'bg-gray-50 text-gray-600'
  return 'bg-[#FFF5F5] text-[#FF2D2D]'
})

function calculateCountdown() {
  if (!product.value) return { h: '00', m: '00', s: '00' }
  let target: number
  if (product.value.status === 0) {
    target = new Date(product.value.startTime || 0).getTime()
  } else if (product.value.status === 1) {
    target = new Date(product.value.endTime || 0).getTime()
  } else {
    return { h: '00', m: '00', s: '00' }
  }
  const diff = Math.max(0, target - now.value.getTime())
  const h = String(Math.floor(diff / 3600000)).padStart(2, '0')
  const m = String(Math.floor((diff % 3600000) / 60000)).padStart(2, '0')
  const s = String(Math.floor((diff % 60000) / 1000)).padStart(2, '0')
  return { h, m, s }
}

async function fetchProduct() {
  try {
    product.value = await getSeckillProduct(relationId.value)
  } catch (error) {
    console.error('获取秒杀商品详情失败', error)
  }
}

async function handleSeckill() {
  if (!product.value || seckillLoading.value) return
  seckillLoading.value = true
  result.value = null
  pollAttempt.value = 0

  try {
    const seckillResult = await doSeckill(relationId.value, quantity.value)
    if (seckillResult.status === 'pending') {
      // 开始轮询
      const finalResult = await pollSeckillResult(relationId.value, (attempt) => {
        pollAttempt.value = attempt
      })
      result.value = finalResult
    } else {
      result.value = seckillResult
    }
  } catch (error: any) {
    if (error.response?.status === 429) {
      result.value = { status: 'failed', message: '请求过于频繁，请稍后再试' }
    } else {
      result.value = { status: 'failed', message: '系统繁忙，请稍后重试' }
    }
  } finally {
    seckillLoading.value = false
  }
}

function goPay() {
  if (result.value?.orderId) {
    router.push(`/portal/pay/${result.value.orderId}`)
  }
}

function resetResult() {
  result.value = null
  fetchProduct()
}

watch(() => route.params.relationId, () => {
  if (relationId.value) {
    fetchProduct()
  }
})

onMounted(() => {
  fetchProduct()
  timer = setInterval(() => {
    now.value = new Date()
  }, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>
