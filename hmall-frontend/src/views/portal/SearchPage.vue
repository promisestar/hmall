<template>
  <PortalLayout :show-search="true">
    <div class="container-main py-5">
      <!-- 结果标题 -->
      <div class="page-card px-5 py-4 mb-3 flex items-center justify-between">
        <h2 class="section-title text-base">
          搜索{{ searchKey ? ` "${searchKey}"` : '全部商品' }}的结果
        </h2>
        <!-- 仅在有结果时显示计数，避免 "0 结果 + 共 10000 件" 的不一致显示 -->
        <span v-if="items.length > 0" class="text-xs text-gray-400">
          共 <em class="not-italic text-[#E4393C] font-bold">{{ total }}</em> 件商品
        </span>
      </div>

      <!-- 筛选区 -->
      <div class="page-card mb-3 text-[13px]">
        <div v-if="filterOptions.category.length > 0" class="flex items-start px-5 py-3 border-b border-gray-50">
          <span class="text-gray-400 w-16 flex-shrink-0 pt-0.5">分类</span>
          <div class="flex flex-wrap gap-x-5 gap-y-1.5 flex-1">
            <span
              v-for="c in filterOptions.category"
              :key="c"
              class="cursor-pointer transition-colors hover:text-[#E4393C]"
              :class="filters.category === c ? 'text-[#E4393C] font-bold' : 'text-gray-600'"
              @click="selectFilter('category', c)"
            >{{ c }}</span>
          </div>
        </div>

        <div v-if="filterOptions.brand.length > 0" class="flex items-start px-5 py-3 border-b border-gray-50">
          <span class="text-gray-400 w-16 flex-shrink-0 pt-0.5">品牌</span>
          <div class="flex flex-wrap gap-x-5 gap-y-1.5 flex-1">
            <span
              v-for="b in filterOptions.brand"
              :key="b"
              class="cursor-pointer transition-colors hover:text-[#E4393C]"
              :class="filters.brand === b ? 'text-[#E4393C] font-bold' : 'text-gray-600'"
              @click="selectFilter('brand', b)"
            >{{ b }}</span>
          </div>
        </div>

        <div class="flex items-center px-5 py-3">
          <span class="text-gray-400 w-16 flex-shrink-0">价格</span>
          <div class="flex items-center flex-wrap gap-2 flex-1">
            <span
              v-for="r in priceRanges"
              :key="r.label"
              class="px-2.5 py-1 rounded-full cursor-pointer transition-all text-xs"
              :class="activePriceLabel === r.label
                ? 'bg-[#E4393C] text-white font-medium shadow-glow'
                : 'text-gray-600 bg-gray-50 hover:text-[#E4393C] hover:bg-red-50'"
              @click="selectPriceRange(r)"
            >{{ r.label }}</span>
            <span class="text-gray-200 mx-1">|</span>
            <input
              v-model="priceMin"
              type="number"
              placeholder="¥最低"
              class="w-20 bg-gray-50 rounded-full px-3 py-1 text-xs outline-none border border-transparent focus:border-[#E4393C] focus:bg-white transition-colors"
            />
            <span class="text-gray-300">-</span>
            <input
              v-model="priceMax"
              type="number"
              placeholder="¥最高"
              class="w-20 bg-gray-50 rounded-full px-3 py-1 text-xs outline-none border border-transparent focus:border-[#E4393C] focus:bg-white transition-colors"
            />
            <button
              @click="applyPrice"
              class="bg-[#E4393C] text-white px-3.5 py-1 rounded-full text-xs hover:bg-[#C81623] transition-colors"
            >确定</button>
          </div>
        </div>

        <div v-if="hasActiveFilter" class="flex justify-end px-5 py-2 border-t border-gray-50">
          <button
            @click="resetFilters"
            class="flex items-center gap-1 text-gray-400 hover:text-[#E4393C] text-xs transition-colors"
          >
            <X class="w-3 h-3" />重置筛选
          </button>
        </div>
      </div>

      <!-- 排序栏 -->
      <div class="page-card px-5 py-3 mb-3 flex items-center gap-7 text-[13px]">
        <span
          class="cursor-pointer transition-colors hover:text-[#E4393C]"
          :class="sortBy === '' ? 'text-[#E4393C] font-bold' : 'text-gray-600'"
          @click="changeSort('')"
        >综合排序</span>
        <span
          class="cursor-pointer transition-colors hover:text-[#E4393C] flex items-center gap-0.5"
          :class="sortBy === 'sold' ? 'text-[#E4393C] font-bold' : 'text-gray-600'"
          @click="changeSort('sold')"
        >
          销量
          <ArrowDown v-if="sortBy === 'sold' && !isAsc" class="w-3 h-3" />
          <ArrowUp v-else-if="sortBy === 'sold' && isAsc" class="w-3 h-3" />
        </span>
        <span
          class="cursor-pointer transition-colors hover:text-[#E4393C] flex items-center gap-0.5"
          :class="sortBy === 'price' ? 'text-[#E4393C] font-bold' : 'text-gray-600'"
          @click="changeSort('price')"
        >
          价格
          <ArrowDown v-if="sortBy === 'price' && !isAsc" class="w-3 h-3" />
          <ArrowUp v-else-if="sortBy === 'price' && isAsc" class="w-3 h-3" />
        </span>
      </div>

      <!-- 商品列表 / 空态 / 加载 -->
      <div v-if="loading" class="flex justify-center py-24">
        <el-icon class="is-loading text-3xl text-[#E4393C]"><Loading /></el-icon>
      </div>

      <div v-else-if="items.length === 0" class="page-card py-24 text-center">
        <SearchX class="w-16 h-16 mx-auto mb-4 text-gray-200" />
        <p class="text-base text-gray-500">未找到相关商品</p>
        <p class="text-xs text-gray-400 mt-2">换个关键词试试，或浏览全部商品</p>
        <button @click="resetFilters" class="btn-secondary mt-5 text-sm">清空筛选条件</button>
      </div>

      <div v-else class="grid grid-cols-4 gap-3">
        <div
          v-for="item in items"
          :key="item.id"
          class="group page-card overflow-hidden cursor-pointer hover:shadow-lift hover:-translate-y-1 transition-all duration-300"
          @click="goDetail(item.id)"
        >
          <div class="relative overflow-hidden">
            <img
              :src="item.image || '/img/like_01.png'"
              class="w-full h-48 object-cover group-hover:scale-105 transition-transform duration-500"
            />
            <div class="absolute inset-x-0 bottom-0 h-10 bg-gradient-to-t from-black/20 to-transparent opacity-0 group-hover:opacity-100 transition-opacity"></div>
          </div>
          <div class="p-3.5">
            <p class="text-[13px] text-gray-800 line-clamp-2 min-h-[40px] leading-relaxed group-hover:text-[#E4393C] transition-colors">
              {{ item.name }}
            </p>
            <div class="flex items-baseline gap-0.5 mt-2">
              <span class="price-tag text-xs">¥</span>
              <span class="price-tag text-xl">{{ formatPrice(item.price) }}</span>
            </div>
            <div class="flex items-center justify-between mt-2.5">
              <span class="text-[11px] text-gray-400">{{ item.sold || 0 }} 人付款</span>
              <button
                @click.stop="addToCart(item)"
                class="flex items-center gap-1 bg-red-50 text-[#E4393C] px-3 py-1.5 rounded-full text-xs font-medium hover:bg-[#E4393C] hover:text-white transition-colors disabled:opacity-50"
                :disabled="addingId === item.id"
              >
                <ShoppingCart class="w-3 h-3" />
                {{ addingId === item.id ? '加入中' : '加入购物车' }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- 分页：独立于商品列表渲染，只要后端返回 total > 0 就显示（含跨页跳转） -->
      <div v-if="total > 0" class="flex justify-center mt-6">
        <el-pagination
          v-model:current-page="pageNo"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next, jumper"
          background
          @current-change="fetchData"
        />
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { SearchX, ShoppingCart, ArrowUp, ArrowDown, X } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import PortalLayout from './PortalLayout.vue'
import { useCartStore } from '@/stores/cart'
import { useUserStore } from '@/stores/user'
import { searchList, searchFilters } from '@/api/item'
import { formatPrice } from '@/utils/format'
import type { Item, SearchFilters } from '@/types'

const route = useRoute()
const router = useRouter()
const cartStore = useCartStore()
const userStore = useUserStore()

const searchKey = ref((route.query.key as string) || '')
const items = ref<Item[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const addingId = ref<number | null>(null)
const sortBy = ref('')
const isAsc = ref(false)
const filters = ref<Record<string, string>>({})
const priceMin = ref('')
const priceMax = ref('')
// 分类/品牌可选项：来自后端聚合结果（随搜索关键字/已选条件动态变化）
const filterOptions = ref<SearchFilters>({ category: [], brand: [] })

// 预设价格区间（与 nginx 旧版 jQuery search.html 的 prices 数组保持一致）
// max=999999 表示无上限
const priceRanges = [
  { label: '100以下', min: 0, max: 100 },
  { label: '100~299元', min: 100, max: 299 },
  { label: '300~599元', min: 300, max: 599 },
  { label: '600~899元', min: 600, max: 899 },
  { label: '900~1599元', min: 900, max: 1599 },
  { label: '1600以上元', min: 1600, max: 999999 },
]
// 当前选中的预设价格标签（用于高亮；与手动输入 min/max 不强绑定）
const activePriceLabel = ref<string | null>(null)

const hasActiveFilter = computed(
  () =>
    !!filters.value.category ||
    !!filters.value.brand ||
    !!priceMin.value ||
    !!priceMax.value ||
    !!sortBy.value
)

function buildFilterParams() {
  const params: Record<string, any> = {
    key: searchKey.value || undefined,
    ...filters.value,
  }
  if (priceMin.value) params.minPrice = Number(priceMin.value) * 100
  if (priceMax.value) params.maxPrice = Number(priceMax.value) * 100
  return params
}

async function fetchFilters() {
  try {
    const res = await searchFilters(buildFilterParams())
    filterOptions.value = {
      category: res?.category || [],
      brand: res?.brand || [],
    }
  } catch {
    filterOptions.value = { category: [], brand: [] }
  }
}

function selectFilter(key: string, val: string) {
  filters.value[key] = filters.value[key] === val ? '' : val
  pageNo.value = 1
  fetchData()
  fetchFilters()
}

function applyPrice() {
  activePriceLabel.value = null
  pageNo.value = 1
  fetchData()
  fetchFilters()
}

function selectPriceRange(r: { label: string; min: number; max: number }) {
  if (activePriceLabel.value === r.label) {
    // 再次点击同一区间 → 取消
    activePriceLabel.value = null
    priceMin.value = ''
    priceMax.value = ''
  } else {
    activePriceLabel.value = r.label
    priceMin.value = String(r.min)
    priceMax.value = r.max === 999999 ? '' : String(r.max)
  }
  pageNo.value = 1
  fetchData()
  fetchFilters()
}

function resetFilters() {
  filters.value = {}
  priceMin.value = ''
  priceMax.value = ''
  activePriceLabel.value = null
  sortBy.value = ''
  isAsc.value = false
  pageNo.value = 1
  fetchData()
  fetchFilters()
}

function changeSort(field: string) {
  if (sortBy.value === field && field) {
    // 重复点击同一个非空排序项 → 切换升降序
    // 注意：field 为空（"综合排序"）时不要切，避免空字符串触发
    isAsc.value = !isAsc.value
  } else {
    sortBy.value = field
    isAsc.value = false
  }
  pageNo.value = 1
  fetchData()
}

async function fetchData() {
  loading.value = true
  const params: Record<string, any> = {
    key: searchKey.value || undefined,
    pageNo: pageNo.value,
    pageSize: pageSize.value,
    ...filters.value,
  }
  if (priceMin.value) params.minPrice = Number(priceMin.value) * 100
  if (priceMax.value) params.maxPrice = Number(priceMax.value) * 100
  // 仅当有排序字段时发送 sortBy/isAsc（避免默认值 false 被 || 误判丢成 undefined）
  if (sortBy.value) {
    params.sortBy = sortBy.value
    params.isAsc = isAsc.value
  }

  try {
    const res = await searchList(params)
    items.value = res.list || []
    total.value = res.total || 0
  } catch {
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function goDetail(id: number) {
  router.push(`/portal/product/${id}`)
}

async function addToCart(item: Item) {
  if (!userStore.isLogin) {
    router.push('/portal/login')
    return
  }
  addingId.value = item.id
  try {
    await cartStore.addToCart({
      itemId: item.id,
      name: item.name,
      price: item.price,
      image: item.image,
      spec: item.spec,
    })
    ElMessage.success('已加入购物车')
  } catch {
    ElMessage.error('加入购物车失败')
  } finally {
    addingId.value = null
  }
}

watch(() => route.query.key, (val) => {
  searchKey.value = (val as string) || ''
  pageNo.value = 1
  fetchData()
  fetchFilters()
})

onMounted(() => {
  fetchData()
  fetchFilters()
})
</script>
