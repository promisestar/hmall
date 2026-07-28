<template>
  <PortalLayout :show-search="true">
    <div class="container-main py-4">
      <!-- 面包屑 -->
      <div class="text-xs text-gray-400 mb-3 flex items-center gap-1.5">
        <router-link to="/portal/home" class="hover:text-[#E4393C] transition-colors">首页</router-link>
        <ChevronRight class="w-3 h-3 text-gray-300" />
        <span v-if="item?.category" class="hover:text-[#E4393C] cursor-pointer transition-colors" @click="goCategory">
          {{ item.category }}
        </span>
        <ChevronRight v-if="item?.category" class="w-3 h-3 text-gray-300" />
        <span class="text-gray-600 truncate max-w-[300px]">{{ item?.name || '商品详情' }}</span>
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="page-card p-24 text-center text-gray-400 text-sm">
        <el-icon class="is-loading text-2xl text-[#E4393C] mb-3"><Loading /></el-icon>
        <p>商品加载中...</p>
      </div>

      <!-- 商品不存在 -->
      <div v-else-if="!item" class="page-card p-24 text-center">
        <PackageX class="w-16 h-16 mx-auto mb-4 text-gray-200" />
        <p class="text-gray-500">商品不存在或已下架</p>
        <router-link to="/portal/home" class="btn-secondary mt-5 text-sm">返回首页</router-link>
      </div>

      <template v-else>
        <!-- 商品主区 -->
        <div class="page-card p-7 mb-4">
          <div class="flex gap-9">
            <!-- 商品图 -->
            <div class="w-[420px] flex-shrink-0">
              <div class="w-full h-[420px] bg-gray-50 rounded-xl overflow-hidden border border-gray-100 group">
                <img
                  :src="item.image || '/img/like_01.png'"
                  :alt="item.name"
                  class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-700"
                />
              </div>
            </div>

            <!-- 信息区 -->
            <div class="flex-1 min-w-0">
              <h1 class="text-xl font-bold text-gray-900 leading-relaxed mb-2">{{ item.name }}</h1>
              <p v-if="item.spec" class="text-[13px] text-gray-400 mb-4">{{ item.spec }}</p>

              <!-- 价格面板 -->
              <div class="relative overflow-hidden rounded-xl bg-gradient-to-r from-[#fff3f0] to-[#fff8ec] p-5 mb-5">
                <div class="absolute -right-6 -top-6 w-24 h-24 rounded-full bg-[#E4393C]/5"></div>
                <div class="flex items-baseline gap-2">
                  <span class="text-xs bg-[#E4393C] text-white px-1.5 py-0.5 rounded font-medium">商城价</span>
                  <span class="price-tag text-sm">¥</span>
                  <span class="price-tag text-[34px] leading-none">{{ formatPrice(item.price) }}</span>
                </div>
                <div class="flex items-center gap-5 mt-3 text-xs text-gray-500">
                  <span>库存 <em class="text-gray-800 not-italic font-semibold">{{ item.stock }}</em> 件</span>
                  <span>已售 <em class="text-gray-800 not-italic font-semibold">{{ item.sold }}</em> 件</span>
                  <span>评价 <em class="text-gray-800 not-italic font-semibold">{{ item.commentCount }}</em> 条</span>
                </div>
              </div>

              <!-- 参数信息 -->
              <div class="grid grid-cols-2 gap-x-8 gap-y-3 text-[13px] mb-6">
                <div class="flex items-center">
                  <span class="text-gray-400 w-14 flex-shrink-0">分类</span>
                  <span class="text-gray-700">{{ item.category || '—' }}</span>
                </div>
                <div class="flex items-center">
                  <span class="text-gray-400 w-14 flex-shrink-0">品牌</span>
                  <span class="text-gray-700">{{ item.brand || '—' }}</span>
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

              <!-- 数量选择 -->
              <div class="flex items-center gap-4 mb-7">
                <span class="text-[13px] text-gray-400">数量</span>
                <div class="flex items-center border border-gray-200 rounded-lg overflow-hidden">
                  <button
                    @click="decreaseQty"
                    :disabled="quantity <= 1"
                    class="w-9 h-9 flex items-center justify-center text-gray-500 hover:bg-gray-50 hover:text-[#E4393C] disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                  >
                    <Minus class="w-4 h-4" />
                  </button>
                  <input
                    v-model.number="quantity"
                    type="number"
                    min="1"
                    :max="item.stock"
                    class="w-14 h-9 text-center border-x border-gray-200 outline-none text-sm focus:border-[#E4393C] [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
                    @change="sanitizeQty"
                  />
                  <button
                    @click="increaseQty"
                    :disabled="quantity >= item.stock"
                    class="w-9 h-9 flex items-center justify-center text-gray-500 hover:bg-gray-50 hover:text-[#E4393C] disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                  >
                    <Plus class="w-4 h-4" />
                  </button>
                </div>
                <span class="text-xs text-gray-400">件</span>
                <span v-if="item.stock > 0 && item.stock < 20" class="text-xs text-[#FF6B35]">
                  仅剩 {{ item.stock }} 件
                </span>
              </div>

              <!-- 操作按钮 -->
              <div class="flex gap-3">
                <button
                  @click="handleAddToCart"
                  :disabled="adding || item.stock === 0"
                  class="flex items-center gap-2 px-10 py-3.5 rounded-xl text-white text-base font-semibold bg-gradient-to-r from-[#f04548] to-[#d2202a] shadow-glow hover:shadow-lift hover:-translate-y-0.5 active:translate-y-0 transition-all disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:translate-y-0"
                >
                  <ShoppingCart class="w-5 h-5" />
                  {{ adding ? '加购中...' : item.stock === 0 ? '暂时缺货' : '加入购物车' }}
                </button>
                <router-link
                  to="/portal/cart"
                  class="flex items-center gap-2 px-8 py-3.5 rounded-xl border border-gray-200 text-gray-600 text-base hover:border-[#E4393C] hover:text-[#E4393C] hover:bg-red-50/50 transition-all"
                >
                  去购物车
                </router-link>
              </div>
            </div>
          </div>
        </div>

        <!-- 商品详情 -->
        <div class="page-card p-7">
          <h3 class="section-title mb-5">商品详情</h3>
          <div class="grid grid-cols-2 gap-x-8 gap-y-3.5 text-[13px] pb-5 border-b border-gray-100">
            <div class="flex">
              <span class="text-gray-400 w-20 flex-shrink-0">商品名称</span>
              <span class="text-gray-700">{{ item.name }}</span>
            </div>
            <div class="flex">
              <span class="text-gray-400 w-20 flex-shrink-0">商品分类</span>
              <span class="text-gray-700">{{ item.category || '—' }}</span>
            </div>
            <div class="flex">
              <span class="text-gray-400 w-20 flex-shrink-0">商品品牌</span>
              <span class="text-gray-700">{{ item.brand || '—' }}</span>
            </div>
            <div class="flex">
              <span class="text-gray-400 w-20 flex-shrink-0">商品规格</span>
              <span class="text-gray-700">{{ item.spec || '—' }}</span>
            </div>
            <div class="flex">
              <span class="text-gray-400 w-20 flex-shrink-0">库存数量</span>
              <span class="text-gray-700">{{ item.stock }} 件</span>
            </div>
            <div class="flex">
              <span class="text-gray-400 w-20 flex-shrink-0">累计销量</span>
              <span class="text-gray-700">{{ item.sold }} 件</span>
            </div>
          </div>
          <div class="pt-5 text-sm text-gray-600 leading-loose">
            <p>{{ item.name }} —— 优质好物，品质保障。{{ item.spec ? '规格：' + item.spec + '。' : '' }}{{ item.brand ? '品牌：' + item.brand + '。' : '' }}</p>
            <p class="mt-2 text-gray-400">本商品由枫叶商城自营发货，享受七天无理由退换货服务。</p>
          </div>
        </div>
      </template>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Minus, Plus, ChevronRight, ShoppingCart, ShieldCheck, Truck, RotateCcw, PackageX } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import PortalLayout from './PortalLayout.vue'
import { getItemById } from '@/api/item'
import { useCartStore } from '@/stores/cart'
import { formatPrice } from '@/utils/format'
import type { Item } from '@/types'

const route = useRoute()
const router = useRouter()
const cartStore = useCartStore()

const item = ref<Item | null>(null)
const loading = ref(true)
const quantity = ref(1)
const adding = ref(false)

onMounted(async () => {
  const itemId = Number(route.params.itemId)
  if (!itemId) {
    loading.value = false
    return
  }
  try {
    item.value = await getItemById(itemId)
  } catch {
    item.value = null
  } finally {
    loading.value = false
  }
})

function goCategory() {
  if (item.value?.category) {
    router.push({ path: '/portal/search', query: { key: item.value.category } })
  }
}

function increaseQty() {
  if (item.value && quantity.value < item.value.stock) {
    quantity.value++
  }
}

function decreaseQty() {
  if (quantity.value > 1) {
    quantity.value--
  }
}

function sanitizeQty() {
  const max = item.value?.stock ?? 1
  if (!quantity.value || quantity.value < 1) {
    quantity.value = 1
  } else if (quantity.value > max) {
    quantity.value = max
  }
}

async function handleAddToCart() {
  if (!item.value) return
  if (item.value.stock === 0) {
    ElMessage.warning('商品库存不足')
    return
  }
  adding.value = true
  try {
    await cartStore.addToCart({
      itemId: item.value.id,
      name: item.value.name,
      spec: item.value.spec,
      price: item.value.price,
      image: item.value.image,
    })
    ElMessage.success('已加入购物车')
    router.push('/portal/cart')
  } catch {
    ElMessage.error('加购失败，请重试')
  } finally {
    adding.value = false
  }
}
</script>
