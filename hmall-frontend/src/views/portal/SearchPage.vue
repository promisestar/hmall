<template>
  <PortalLayout :show-search="true">
    <div class="container-main py-6">
      <!-- Search Header -->
      <div class="bg-white rounded-lg shadow-sm p-4 mb-4">
        <h2 class="text-lg font-bold text-[#E4393C] border-l-4 border-[#E4393C] pl-3">
          搜索 "{{ searchKey }}" 的结果
          <span v-if="total > 0" class="text-sm text-gray-500 font-normal ml-2">共 {{ total }} 件商品</span>
        </h2>
      </div>

      <div class="flex gap-4">
        <!-- Filters Sidebar -->
        <div class="w-[220px] flex-shrink-0">
          <div class="bg-white rounded-lg shadow-sm p-4 sticky top-24">
            <h4 class="font-bold text-sm mb-3">筛选条件</h4>

            <div class="mb-4">
              <h5 class="text-xs text-gray-500 mb-2">分类</h5>
              <div class="space-y-1">
                <div
                  v-for="c in ['手机', '电脑', '家电', '服饰', '食品']"
                  :key="c"
                  class="text-sm py-1 px-2 rounded cursor-pointer hover:bg-gray-100 transition-colors"
                  :class="filters.category === c ? 'bg-red-50 text-[#E4393C]' : ''"
                  @click="selectFilter('category', c)"
                >
                  {{ c }}
                </div>
              </div>
            </div>

            <div class="mb-4">
              <h5 class="text-xs text-gray-500 mb-2">价格区间</h5>
              <div class="flex items-center gap-1 text-sm">
                <input
                  v-model="priceMin"
                  type="number"
                  placeholder="¥最低"
                  class="w-[80px] border rounded px-2 py-1 text-xs outline-none focus:border-[#E4393C]"
                />
                <span class="text-gray-400">-</span>
                <input
                  v-model="priceMax"
                  type="number"
                  placeholder="¥最高"
                  class="w-[80px] border rounded px-2 py-1 text-xs outline-none focus:border-[#E4393C]"
                />
                <button
                  @click="applyPrice"
                  class="bg-[#E4393C] text-white text-xs px-2 py-1 rounded hover:bg-[#C81623]"
                >
                  确定
                </button>
              </div>
            </div>

            <button
              @click="resetFilters"
              class="w-full border border-gray-300 text-sm py-1.5 rounded hover:bg-gray-50 transition-colors"
            >
              重置筛选
            </button>
          </div>
        </div>

        <!-- Results -->
        <div class="flex-1">
          <!-- Sort Bar -->
          <div class="bg-white rounded-lg shadow-sm px-4 py-3 mb-3 flex items-center gap-6 text-sm">
            <span
              class="cursor-pointer hover:text-[#E4393C] transition-colors"
              :class="sortBy === '' ? 'text-[#E4393C] font-bold' : ''"
              @click="changeSort('')"
            >
              综合排序
            </span>
            <span
              class="cursor-pointer hover:text-[#E4393C] transition-colors"
              :class="sortBy === 'sold' ? 'text-[#E4393C] font-bold' : ''"
              @click="changeSort('sold')"
            >
              销量
            </span>
            <span
              class="cursor-pointer hover:text-[#E4393C] transition-colors flex items-center gap-1"
              :class="sortBy === 'price' ? 'text-[#E4393C] font-bold' : ''"
              @click="changeSort('price')"
            >
              价格
              <span v-if="sortBy === 'price'">{{ isAsc ? '↑' : '↓' }}</span>
            </span>
          </div>

          <!-- Product Grid -->
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

          <!-- Pagination -->
          <div v-if="total > pageSize" class="flex justify-center mt-6">
            <el-pagination
              v-model:current-page="pageNo"
              :page-size="pageSize"
              :total="total"
              layout="prev, pager, next"
              background
              @current-change="fetchData"
            />
          </div>
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { SearchX } from 'lucide-vue-next'
import PortalLayout from './PortalLayout.vue'
import { useCartStore } from '@/stores/cart'
import { useUserStore } from '@/stores/user'
import { searchList } from '@/api/item'
import { formatPrice } from '@/utils/format'
import type { Item } from '@/types'

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

function selectFilter(key: string, val: string) {
  filters.value[key] = filters.value[key] === val ? '' : val
  pageNo.value = 1
  fetchData()
}

function applyPrice() {
  pageNo.value = 1
  fetchData()
}

function resetFilters() {
  filters.value = {}
  priceMin.value = ''
  priceMax.value = ''
  sortBy.value = ''
  isAsc.value = false
  pageNo.value = 1
  fetchData()
}

function changeSort(field: string) {
  if (sortBy.value === field) {
    if (field === 'price') {
      isAsc.value = !isAsc.value
    }
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
    sortBy: sortBy.value || undefined,
    isAsc: isAsc.value || undefined,
    ...filters.value,
  }
  if (priceMin.value) params.minPrice = Number(priceMin.value) * 100
  if (priceMax.value) params.maxPrice = Number(priceMax.value) * 100

  try {
    const res = await searchList(params)
    items.value = res.list || []
    total.value = res.total || 0
  } catch {
    items.value = []
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
    await cartStore.addToCart({ itemId: item.id })
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
})

onMounted(() => fetchData())
</script>
