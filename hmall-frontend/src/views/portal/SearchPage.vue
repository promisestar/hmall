<template>
  <PortalLayout :show-search="true">
    <div class="container-main py-6">
      <!-- Search Header -->
      <div class="bg-white rounded-lg shadow-sm p-4 mb-4">
        <h2 class="text-lg font-bold text-[#E4393C] border-l-4 border-[#E4393C] pl-3">
          搜索{{ searchKey ? ` "${searchKey}"` : '全部商品' }}的结果
          <!-- 仅在有结果时显示计数，避免 "0 结果 + 共 10000 件" 的不一致显示 -->
          <span v-if="items.length > 0" class="text-sm text-gray-500 font-normal ml-2">共 {{ total }} 件商品</span>
        </h2>
      </div>

      <!-- Filters：顶部横向排布（与 nginx 旧版 jQuery search.html 一致） -->
      <div class="bg-white rounded-lg shadow-sm mb-4 text-sm">
        <div v-if="filterOptions.category.length > 0" class="flex items-start px-4 py-3 border-b border-gray-100">
          <span class="text-gray-500 w-16 flex-shrink-0 pt-0.5">分类：</span>
          <div class="flex flex-wrap gap-x-4 gap-y-1 flex-1">
            <span
              v-for="c in filterOptions.category"
              :key="c"
              class="cursor-pointer hover:text-[#E4393C] transition-colors"
              :class="filters.category === c ? 'text-[#E4393C] font-bold' : 'text-gray-700'"
              @click="selectFilter('category', c)"
            >{{ c }}</span>
          </div>
        </div>

        <div v-if="filterOptions.brand.length > 0" class="flex items-start px-4 py-3 border-b border-gray-100">
          <span class="text-gray-500 w-16 flex-shrink-0 pt-0.5">品牌：</span>
          <div class="flex flex-wrap gap-x-4 gap-y-1 flex-1">
            <span
              v-for="b in filterOptions.brand"
              :key="b"
              class="cursor-pointer hover:text-[#E4393C] transition-colors"
              :class="filters.brand === b ? 'text-[#E4393C] font-bold' : 'text-gray-700'"
              @click="selectFilter('brand', b)"
            >{{ b }}</span>
          </div>
        </div>

        <div class="flex items-center px-4 py-3">
          <span class="text-gray-500 w-16 flex-shrink-0">价格：</span>
          <div class="flex items-center flex-wrap gap-2 flex-1">
            <span
              v-for="r in priceRanges"
              :key="r.label"
              class="px-2 py-0.5 rounded cursor-pointer border transition-colors"
              :class="activePriceLabel === r.label
                ? 'text-[#E4393C] border-[#E4393C]'
                : 'text-gray-700 border-gray-200 hover:text-[#E4393C]'"
              @click="selectPriceRange(r)"
            >{{ r.label }}</span>
            <span class="text-gray-300 mx-1">|</span>
            <input
              v-model="priceMin"
              type="number"
              placeholder="¥最低"
              class="w-20 border rounded px-2 py-0.5 text-sm outline-none focus:border-[#E4393C]"
            />
            <span class="text-gray-400">-</span>
            <input
              v-model="priceMax"
              type="number"
              placeholder="¥最高"
              class="w-20 border rounded px-2 py-0.5 text-sm outline-none focus:border-[#E4393C]"
            />
            <button
              @click="applyPrice"
              class="bg-[#E4393C] text-white px-3 py-0.5 rounded text-sm hover:bg-[#C81623]"
            >确定</button>
          </div>
        </div>

        <div v-if="hasActiveFilter" class="flex justify-end px-4 py-2 border-t border-gray-100">
          <button
            @click="resetFilters"
            class="text-gray-500 hover:text-[#E4393C] text-sm"
          >× 重置筛选</button>
        </div>
      </div>

      <!-- Sort Bar -->
      <div class="bg-white rounded-lg shadow-sm px-4 py-3 mb-3 flex items-center gap-6 text-sm">
        <span
          class="cursor-pointer hover:text-[#E4393C] transition-colors"
          :class="sortBy === '' ? 'text-[#E4393C] font-bold' : ''"
          @click="changeSort('')"
        >综合排序</span>
        <span
          class="cursor-pointer hover:text-[#E4393C] transition-colors"
          :class="sortBy === 'sold' ? 'text-[#E4393C] font-bold' : ''"
          @click="changeSort('sold')"
        >
          销量
          <span v-if="sortBy === 'sold'" class="ml-0.5">{{ isAsc ? '↑' : '↓' }}</span>
        </span>
        <span
          class="cursor-pointer hover:text-[#E4393C] transition-colors"
          :class="sortBy === 'price' ? 'text-[#E4393C] font-bold' : ''"
          @click="changeSort('price')"
        >
          价格
          <span v-if="sortBy === 'price'" class="ml-0.5">{{ isAsc ? '↑' : '↓' }}</span>
        </span>
      </div>

      <!-- Product Grid / Empty / Loading -->
      <div v-if="loading" class="flex justify-center py-20">
        <el-icon class="is-loading text-3xl text-[#E4393C]"><Loading /></el-icon>
      </div>

      <div v-else-if="items.length === 0" class="bg-white rounded-lg shadow-sm py-20 text-center text-gray-500">
        <SearchX class="w-16 h-16 mx-auto mb-4 text-gray-300" />
        <p class="text-lg">未找到相关商品</p>
        <p class="text-sm mt-2">请尝试其他关键词</p>
      </div>

      <div v-else class="grid grid-cols-4 gap-3">
        <div
          v-for="item in items"
          :key="item.id"
          class="bg-white rounded-lg shadow-sm overflow-hidden hover:shadow-md hover:-translate-y-1 transition-all cursor-pointer group"
        >
          <div class="relative overflow-hidden">
            <img :src="item.image || '/img/like_01.png'" class="w-full h-48 object-cover group-hover:scale-105 transition-transform duration-300" />
          </div>
          <div class="p-3">
            <p class="text-sm text-gray-800 line-clamp-2 mb-2 min-h-[40px]">{{ item.name }}</p>
            <p class="text-[#E4393C] font-bold text-lg mb-2">¥{{ formatPrice(item.price) }}</p>
            <div class="flex items-center justify-between text-xs text-gray-400">
              <span>{{ item.sold || 0 }}人付款</span>
              <button
                @click.stop="addToCart(item)"
                class="bg-[#E4393C] text-white px-3 py-1 rounded text-xs hover:bg-[#C81623] transition-colors"
                :disabled="addingId === item.id"
              >
                {{ addingId === item.id ? '...' : '加入购物车' }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Pagination：独立于商品列表渲染，只要后端返回 total > 0 就显示（含跨页跳转） -->
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
import { SearchX } from 'lucide-vue-next'
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
