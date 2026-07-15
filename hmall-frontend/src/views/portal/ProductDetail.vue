<template>
  <PortalLayout :show-search="true">
    <div class="container-main py-4">
      <!-- Breadcrumb -->
      <div class="text-xs text-gray-500 mb-4 flex items-center gap-1">
        <router-link to="/portal/home" class="hover:text-[#E4393C] transition-colors">首页</router-link>
        <span class="text-gray-300">&gt;</span>
        <span class="text-gray-700 truncate max-w-[300px]">{{ item?.name || '商品详情' }}</span>
      </div>

      <!-- Loading -->
      <div v-if="loading" class="bg-white rounded-lg shadow-sm p-20 text-center text-gray-400">
        加载中...
      </div>

      <!-- Error -->
      <div v-else-if="!item" class="bg-white rounded-lg shadow-sm p-20 text-center text-gray-400">
        商品不存在或已下架
      </div>

      <template v-else>
        <!-- Product Main Section -->
        <div class="bg-white rounded-lg shadow-sm p-6 mb-4">
          <div class="flex gap-8">
            <!-- Image -->
            <div class="w-[400px] flex-shrink-0">
              <div class="w-full h-[400px] bg-gray-50 rounded-lg overflow-hidden border border-gray-100">
                <img
                  :src="item.image || '/img/like_01.png'"
                  :alt="item.name"
                  class="w-full h-full object-cover"
                />
              </div>
            </div>

            <!-- Info -->
            <div class="flex-1 min-w-0">
              <!-- Name -->
              <h1 class="text-xl font-bold text-gray-800 leading-relaxed mb-3">{{ item.name }}</h1>

              <!-- Subtitle / Spec -->
              <p v-if="item.spec" class="text-sm text-gray-500 mb-4">规格：{{ item.spec }}</p>

              <!-- Price Card -->
              <div class="bg-gradient-to-r from-red-50 to-orange-50 rounded-lg p-5 mb-5">
                <div class="flex items-baseline gap-2">
                  <span class="text-sm text-[#E4393C] font-medium">¥</span>
                  <span class="text-3xl font-bold text-[#E4393C]">{{ formatPrice(item.price) }}</span>
                </div>
                <div class="flex items-center gap-4 mt-2 text-xs text-gray-500">
                  <span>库存 <em class="text-gray-700 not-italic font-medium">{{ item.stock }}</em> 件</span>
                  <span>已售 <em class="text-gray-700 not-italic font-medium">{{ item.sold }}</em> 件</span>
                </div>
              </div>

              <!-- Meta Tags -->
              <div class="grid grid-cols-2 gap-x-8 gap-y-3 text-sm mb-6">
                <div class="flex items-center">
                  <span class="text-gray-400 w-16 flex-shrink-0">分类</span>
                  <span class="text-gray-700">{{ item.category || '—' }}</span>
                </div>
                <div class="flex items-center">
                  <span class="text-gray-400 w-16 flex-shrink-0">品牌</span>
                  <span class="text-gray-700">{{ item.brand || '—' }}</span>
                </div>
                <div class="flex items-center">
                  <span class="text-gray-400 w-16 flex-shrink-0">规格</span>
                  <span class="text-gray-700">{{ item.spec || '—' }}</span>
                </div>
                <div class="flex items-center">
                  <span class="text-gray-400 w-16 flex-shrink-0">评价</span>
                  <span class="text-gray-700">{{ item.commentCount }} 条</span>
                </div>
              </div>

              <!-- Quantity Selector -->
              <div class="flex items-center gap-4 mb-6">
                <span class="text-sm text-gray-400">数量</span>
                <div class="flex items-center border border-gray-300 rounded">
                  <button
                    @click="decreaseQty"
                    :disabled="quantity <= 1"
                    class="w-9 h-9 flex items-center justify-center text-gray-600 hover:bg-gray-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                  >
                    <Minus class="w-4 h-4" />
                  </button>
                  <input
                    v-model.number="quantity"
                    type="number"
                    min="1"
                    :max="item.stock"
                    class="w-14 h-9 text-center border-x border-gray-300 outline-none text-sm focus:border-[#E4393C] [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
                    @change="sanitizeQty"
                  />
                  <button
                    @click="increaseQty"
                    :disabled="quantity >= item.stock"
                    class="w-9 h-9 flex items-center justify-center text-gray-600 hover:bg-gray-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                  >
                    <Plus class="w-4 h-4" />
                  </button>
                </div>
                <span class="text-xs text-gray-400">件</span>
              </div>

              <!-- Action Buttons -->
              <div class="flex gap-4">
                <button
                  @click="handleAddToCart"
                  :disabled="adding || item.stock === 0"
                  class="px-10 py-3 bg-[#E4393C] text-white text-base font-medium rounded hover:bg-[#C81623] transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {{ adding ? '加购中...' : '加入购物车' }}
                </button>
                <router-link
                  to="/portal/cart"
                  class="px-8 py-3 border border-gray-300 text-gray-700 text-base rounded hover:border-[#E4393C] hover:text-[#E4393C] transition-colors flex items-center"
                >
                  去购物车
                </router-link>
              </div>
            </div>
          </div>
        </div>

        <!-- Product Description -->
        <div class="bg-white rounded-lg shadow-sm p-6">
          <h3 class="text-lg font-bold mb-4 border-l-4 border-[#E4393C] pl-3">商品详情</h3>
          <div class="grid grid-cols-2 gap-x-8 gap-y-3 text-sm pb-4 border-b border-gray-100">
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
              <span class="text-gray-400 w-20 flex-shrink-0">销量</span>
              <span class="text-gray-700">{{ item.sold }} 件</span>
            </div>
          </div>
          <div class="pt-4 text-sm text-gray-600 leading-relaxed">
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
import { Minus, Plus } from 'lucide-vue-next'
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
