<template>
  <PortalLayout :show-search="true">
    <!-- Hero 区：分类 + 轮播 + 侧栏 -->
    <div class="bg-white">
      <div class="container-main pt-4 pb-5">
        <div class="flex gap-3">
          <!-- 分类导航 -->
          <div class="w-[220px] flex-shrink-0 bg-[#f6f7f9] rounded-xl overflow-hidden py-1">
            <div
              v-for="cat in categories"
              :key="cat.name"
              class="group flex items-center justify-between px-4 py-[9px] text-[13px] text-gray-700 hover:bg-white hover:text-[#E4393C] hover:shadow-card cursor-pointer transition-all"
              @click="goSearch(cat.name)"
            >
              <span>{{ cat.name }}</span>
              <ChevronRight class="w-3.5 h-3.5 text-gray-300 group-hover:text-[#E4393C] transition-colors" />
            </div>
          </div>

          <!-- Banner 轮播 -->
          <div class="flex-1 overflow-hidden rounded-xl">
            <el-carousel :interval="4500" height="420px" indicator-position="inside">
              <el-carousel-item v-for="(banner, idx) in banners" :key="idx">
                <div
                  class="relative w-full h-full flex items-center overflow-hidden"
                  :class="banner.bgClass"
                >
                  <!-- 装饰圆 -->
                  <div class="absolute -right-16 -top-16 w-72 h-72 rounded-full bg-white/10"></div>
                  <div class="absolute right-24 bottom-[-60px] w-48 h-48 rounded-full bg-white/10"></div>
                  <div class="absolute left-1/2 top-8 w-24 h-24 rounded-full bg-white/5"></div>

                  <div class="relative px-14 text-white">
                    <span class="inline-block px-3 py-1 rounded-full bg-white/20 text-xs font-medium tracking-wide mb-4">
                      {{ banner.tag }}
                    </span>
                    <h2 class="text-4xl font-bold mb-3 tracking-wide">{{ banner.title }}</h2>
                    <p class="text-lg opacity-90 mb-6">{{ banner.subtitle }}</p>
                    <button
                      class="px-7 py-2.5 bg-white text-gray-900 text-sm font-semibold rounded-full hover:shadow-lift hover:-translate-y-0.5 active:translate-y-0 transition-all"
                      @click="goSearch(banner.keyword)"
                    >
                      立即选购
                    </button>
                  </div>
                </div>
              </el-carousel-item>
            </el-carousel>
          </div>

          <!-- 右侧栏 -->
          <div class="w-[210px] flex-shrink-0 flex flex-col gap-3">
            <!-- 用户卡片 -->
            <div class="page-card p-4 text-center">
              <div class="w-14 h-14 mx-auto rounded-full bg-gradient-to-br from-[#FF6B35] to-[#E4393C] flex items-center justify-center shadow-glow">
                <UserRound class="w-7 h-7 text-white" />
              </div>
              <p class="mt-2.5 text-sm font-medium text-gray-800">
                {{ userStore.isLogin ? `Hi，${userStore.username}` : 'Hi，欢迎光临' }}
              </p>
              <div v-if="!userStore.isLogin" class="flex gap-2 mt-3">
                <router-link to="/portal/login" class="flex-1 py-1.5 text-xs font-medium text-white bg-gradient-to-r from-[#f04548] to-[#d2202a] rounded-full hover:opacity-90 transition-opacity">
                  登录
                </router-link>
                <router-link to="/portal/login" class="flex-1 py-1.5 text-xs font-medium text-[#E4393C] border border-[#E4393C]/50 rounded-full hover:bg-red-50 transition-colors">
                  注册
                </router-link>
              </div>
              <div v-else class="flex gap-2 mt-3">
                <router-link to="/portal/orders" class="flex-1 py-1.5 text-xs font-medium text-gray-600 bg-gray-100 rounded-full hover:bg-gray-200 transition-colors">
                  我的订单
                </router-link>
                <router-link to="/portal/profile" class="flex-1 py-1.5 text-xs font-medium text-gray-600 bg-gray-100 rounded-full hover:bg-gray-200 transition-colors">
                  个人中心
                </router-link>
              </div>
            </div>

            <!-- 快报 -->
            <div class="page-card p-4 flex-1">
              <div class="flex items-center justify-between mb-2.5">
                <h4 class="font-bold text-[13px] text-gray-800">枫叶快报</h4>
                <span class="text-[11px] text-gray-400 cursor-pointer hover:text-[#E4393C] transition-colors">更多 &gt;</span>
              </div>
              <ul class="space-y-2 text-xs text-gray-500">
                <li v-for="n in 4" :key="n" class="flex items-start gap-1.5 cursor-pointer hover:text-[#E4393C] transition-colors">
                  <span class="shrink-0 px-1 rounded bg-red-50 text-[#E4393C] text-[10px] font-bold leading-4 mt-px">特惠</span>
                  <span class="flex-1 truncate">备战开学季 全民半价购数码</span>
                </li>
              </ul>
            </div>

            <!-- 生活服务 -->
            <div class="page-card p-3">
              <div class="grid grid-cols-4 gap-y-3 text-center">
                <div
                  v-for="s in services"
                  :key="s.name"
                  class="flex flex-col items-center gap-1 cursor-pointer group"
                >
                  <component :is="s.icon" class="w-5 h-5 text-gray-400 group-hover:text-[#E4393C] group-hover:-translate-y-0.5 transition-all" />
                  <span class="text-[11px] text-gray-500 group-hover:text-[#E4393C] transition-colors">{{ s.name }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 秒杀入口条 -->
    <div class="container-main mt-5">
      <router-link
        to="/portal/seckill"
        class="relative flex items-center justify-between rounded-xl overflow-hidden px-8 py-5 bg-gradient-to-r from-[#FF2D2D] via-[#f0502e] to-[#FF6B35] text-white shadow-glow group"
      >
        <div class="absolute -left-8 top-1/2 -translate-y-1/2 w-40 h-40 rounded-full bg-white/10"></div>
        <div class="absolute right-40 -bottom-16 w-52 h-52 rounded-full bg-white/10"></div>
        <div class="relative flex items-center gap-4">
          <div class="w-12 h-12 rounded-xl bg-white/20 flex items-center justify-center">
            <Zap class="w-6 h-6" />
          </div>
          <div>
            <h3 class="text-xl font-bold tracking-wide">限时秒杀 · 整点开抢</h3>
            <p class="text-xs opacity-85 mt-1">超低折扣 限量抢购 手慢无</p>
          </div>
        </div>
        <div class="relative flex items-center gap-2 text-sm font-semibold bg-white/20 rounded-full px-5 py-2 group-hover:bg-white group-hover:text-[#E4393C] transition-all">
          前往秒杀会场
          <ArrowRight class="w-4 h-4 group-hover:translate-x-1 transition-transform" />
        </div>
      </router-link>
    </div>

    <!-- 今日推荐 -->
    <div class="container-main mt-6">
      <div class="page-card p-5">
        <div class="flex items-center justify-between mb-4">
          <h3 class="section-title flex items-center gap-2">
            今日推荐
            <span class="text-xs font-normal text-gray-400 ml-1">编辑精选 每日更新</span>
          </h3>
          <router-link to="/portal/search" class="text-xs text-gray-400 hover:text-[#E4393C] transition-colors flex items-center gap-0.5">
            查看更多 <ChevronRight class="w-3.5 h-3.5" />
          </router-link>
        </div>
        <div class="grid grid-cols-4 gap-4">
          <div
            v-for="item in recommendItems"
            :key="item.id"
            class="group card-shadow p-2.5 cursor-pointer hover:-translate-y-1"
            @click="goDetail(item.id)"
          >
            <div class="relative overflow-hidden rounded-lg">
              <img :src="item.image || '/img/like_01.png'" class="w-full h-44 object-cover group-hover:scale-105 transition-transform duration-500" />
              <span class="absolute top-2 left-2 px-2 py-0.5 rounded-md bg-[#E4393C]/90 text-white text-[10px] font-bold backdrop-blur-sm">推荐</span>
            </div>
            <p class="text-sm text-gray-800 mt-2.5 truncate group-hover:text-[#E4393C] transition-colors">{{ item.name }}</p>
            <div class="flex items-baseline gap-1 mt-1.5">
              <span class="price-tag text-xs">¥</span>
              <span class="price-tag text-lg">{{ formatPrice(item.price) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 猜你喜欢 -->
    <div class="container-main mt-5">
      <div class="page-card p-5">
        <div class="flex items-center justify-between mb-4">
          <h3 class="section-title">猜你喜欢</h3>
          <button
            class="flex items-center gap-1 text-xs text-gray-400 hover:text-[#E4393C] transition-colors"
            @click="refreshGuessLike"
          >
            <RefreshCw class="w-3.5 h-3.5" />换一换
          </button>
        </div>
        <div class="grid grid-cols-6 gap-3">
          <div
            v-for="item in guessLikeItems"
            :key="item.id"
            class="group card-shadow p-2 cursor-pointer hover:-translate-y-1"
            @click="goDetail(item.id)"
          >
            <div class="overflow-hidden rounded-lg">
              <img :src="item.image || '/img/like_01.png'" class="w-full h-32 object-cover group-hover:scale-105 transition-transform duration-500" />
            </div>
            <p class="text-xs text-gray-600 mt-2 line-clamp-2 leading-relaxed group-hover:text-[#E4393C] transition-colors">{{ item.name }}</p>
            <div class="flex items-baseline gap-0.5 mt-1">
              <span class="price-tag text-[10px]">¥</span>
              <span class="price-tag text-base">{{ formatPrice(item.price) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 楼层：家用电器 -->
    <div class="container-main mt-5">
      <div class="page-card p-5">
        <div class="flex items-center justify-between mb-4 border-b border-gray-100 pb-3">
          <h3 class="section-title">家用电器</h3>
          <div class="flex gap-2 text-xs">
            <span
              v-for="tab in applianceTabs"
              :key="tab.label"
              class="px-3 py-1 rounded-full cursor-pointer transition-colors"
              :class="tab.active ? 'bg-[#E4393C] text-white font-medium' : 'text-gray-500 hover:text-[#E4393C] bg-gray-50'"
              @click="selectFloorTab('appliances', tab)"
            >{{ tab.label }}</span>
          </div>
        </div>
        <div class="grid grid-cols-5 gap-3">
          <div
            v-for="item in floorItems.appliances"
            :key="item.id"
            class="group text-center p-2 rounded-xl cursor-pointer hover:shadow-lift transition-all"
            @click="goDetail(item.id)"
          >
            <img :src="item.image || '/img/like_01.png'" class="w-full h-32 object-cover rounded-lg mb-2" />
            <p class="text-xs text-gray-600 truncate group-hover:text-[#E4393C] transition-colors">{{ item.name }}</p>
            <p class="price-tag text-sm mt-0.5">¥{{ formatPrice(item.price) }}</p>
          </div>
        </div>
      </div>
    </div>

    <!-- 楼层：手机通讯 -->
    <div class="container-main mt-5">
      <div class="page-card p-5">
        <div class="flex items-center justify-between mb-4 border-b border-gray-100 pb-3">
          <h3 class="section-title">手机通讯</h3>
          <div class="flex gap-2 text-xs">
            <span
              v-for="tab in phoneTabs"
              :key="tab.label"
              class="px-3 py-1 rounded-full cursor-pointer transition-colors"
              :class="tab.active ? 'bg-[#E4393C] text-white font-medium' : 'text-gray-500 hover:text-[#E4393C] bg-gray-50'"
              @click="selectFloorTab('phones', tab)"
            >{{ tab.label }}</span>
          </div>
        </div>
        <div class="grid grid-cols-5 gap-3">
          <div
            v-for="item in floorItems.phones"
            :key="item.id"
            class="group text-center p-2 rounded-xl cursor-pointer hover:shadow-lift transition-all"
            @click="goDetail(item.id)"
          >
            <img :src="item.image || '/img/like_02.png'" class="w-full h-32 object-cover rounded-lg mb-2" />
            <p class="text-xs text-gray-600 truncate group-hover:text-[#E4393C] transition-colors">{{ item.name }}</p>
            <p class="price-tag text-sm mt-0.5">¥{{ formatPrice(item.price) }}</p>
          </div>
        </div>
      </div>
    </div>

    <!-- 品牌墙（静态占位：品牌墙无后端 API，保持硬编码） -->
    <div class="container-main mt-5 mb-2">
      <div class="page-card p-5">
        <div class="grid grid-cols-10 gap-4 items-center">
          <img
            v-for="n in 10"
            :key="n"
            :src="`/img/brand${n < 10 ? '0' + n : n}.png`"
            class="h-10 mx-auto object-contain grayscale opacity-60 hover:grayscale-0 hover:opacity-100 hover:scale-110 transition-all duration-300 cursor-pointer"
          />
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, reactive, markRaw, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  ChevronRight, ArrowRight, Zap, RefreshCw, UserRound,
  Phone, Plane, Clapperboard, Gamepad2, Fuel, Hotel, TrainFront, Gift,
} from 'lucide-vue-next'
import PortalLayout from './PortalLayout.vue'
import { searchList } from '@/api/item'
import { formatPrice } from '@/utils/format'
import { useUserStore } from '@/stores/user'
import type { Item } from '@/types'

const router = useRouter()
const userStore = useUserStore()

// ---- 静态站点数据（无对应后端 API，保持硬编码） ----
const categories = [
  { name: '图书、音像、数字商品' },
  { name: '家用电器' },
  { name: '手机、数码' },
  { name: '电脑、办公' },
  { name: '家居、家具、家装' },
  { name: '服饰内衣' },
  { name: '个护化妆' },
  { name: '运动健康' },
  { name: '汽车用品' },
  { name: '母婴、玩具' },
  { name: '食品生鲜' },
  { name: '箱包手袋' },
]

const banners = [
  {
    tag: '开学季大促', title: '亿万元优惠', subtitle: '开学季大促 满99减30',
    keyword: '开学', bgClass: 'bg-gradient-to-br from-[#e4393c] via-[#f0502e] to-[#ff7a45]',
  },
  {
    tag: '新品首发', title: '手机数码节', subtitle: '新品首发 低至5折',
    keyword: '手机', bgClass: 'bg-gradient-to-br from-[#2563eb] via-[#3b82f6] to-[#06b6d4]',
  },
  {
    tag: '焕新补贴', title: '家电焕新季', subtitle: '以旧换新 最高补贴1000元',
    keyword: '家电', bgClass: 'bg-gradient-to-br from-[#7c3aed] via-[#8b5cf6] to-[#d946ef]',
  },
]

const services = [
  { name: '话费', icon: markRaw(Phone) },
  { name: '机票', icon: markRaw(Plane) },
  { name: '电影票', icon: markRaw(Clapperboard) },
  { name: '游戏', icon: markRaw(Gamepad2) },
  { name: '加油站', icon: markRaw(Fuel) },
  { name: '酒店', icon: markRaw(Hotel) },
  { name: '火车票', icon: markRaw(TrainFront) },
  { name: '礼品卡', icon: markRaw(Gift) },
]

// 楼层 Tab（记录激活态，便于样式高亮）
interface FloorTab { label: string; category: string; active: boolean }
const applianceTabs = reactive<FloorTab[]>([
  { label: '热门', category: '家用电器', active: true },
  { label: '大家电', category: '大家电', active: false },
  { label: '生活电器', category: '生活电器', active: false },
  { label: '厨房电器', category: '厨房电器', active: false },
])
const phoneTabs = reactive<FloorTab[]>([
  { label: '热门', category: '手机', active: true },
  { label: '新机尝鲜', category: '手机', active: false },
  { label: '高性价比', category: '手机', active: false },
])

// ---- 动态商品数据（对接后端 /search/list） ----
const recommendItems = ref<Item[]>([])
const guessLikeItems = ref<Item[]>([])
const floorItems = reactive<Record<string, Item[]>>({
  appliances: [],
  phones: [],
})

function goDetail(id: number) {
  router.push(`/portal/product/${id}`)
}

function goSearch(keyword: string) {
  router.push({ path: '/portal/search', query: { key: keyword } })
}

async function fetchRecommend() {
  try {
    const res = await searchList({ pageNo: 1, pageSize: 4 })
    recommendItems.value = res.list || []
  } catch { /* 静默降级 */ }
}

async function fetchGuessLike() {
  try {
    // 猜你喜欢：随机取一页最新商品
    const randomPage = Math.ceil(Math.random() * 10)
    const res = await searchList({ pageNo: randomPage, pageSize: 6 })
    guessLikeItems.value = res.list || []
  } catch { /* 静默降级 */ }
}

async function fetchFloor(floorKey: string, category: string) {
  try {
    const res = await searchList({ pageNo: 1, pageSize: 10, category })
    ;(floorItems as any)[floorKey] = res.list || []
  } catch { /* 静默降级 */ }
}

function selectFloorTab(floorKey: string, tab: FloorTab) {
  const tabs = floorKey === 'appliances' ? applianceTabs : phoneTabs
  tabs.forEach((t) => { t.active = t === tab })
  fetchFloor(floorKey, tab.category)
}

function refreshGuessLike() {
  fetchGuessLike()
}

onMounted(() => {
  fetchRecommend()
  fetchGuessLike()
  fetchFloor('appliances', '家用电器')
  fetchFloor('phones', '手机')
})
</script>
