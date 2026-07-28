<template>
  <PortalLayout :show-search="true">
    <div class="container-main py-5">
      <div class="flex items-center justify-between mb-4">
        <h2 class="section-title">我的购物车</h2>
        <span v-if="cartStore.cartList.length > 0" class="text-xs text-gray-400">
          共 {{ cartStore.cartList.length }} 种商品
        </span>
      </div>

      <!-- 未登录 -->
      <div v-if="!userStore.isLogin" class="page-card p-24 text-center">
        <div class="w-20 h-20 mx-auto mb-5 rounded-full bg-gray-50 flex items-center justify-center">
          <ShoppingCart class="w-10 h-10 text-gray-300" />
        </div>
        <p class="text-gray-500 mb-1">请先登录后查看购物车</p>
        <p class="text-xs text-gray-400 mb-6">登录后可同步购物车中的商品</p>
        <router-link to="/portal/login" class="btn-primary">去登录</router-link>
      </div>

      <!-- 加载中 -->
      <div v-else-if="cartStore.loading" class="flex justify-center py-24">
        <el-icon class="is-loading text-3xl text-[#E4393C]"><Loading /></el-icon>
      </div>

      <!-- 空购物车 -->
      <div v-else-if="cartStore.cartList.length === 0" class="page-card p-24 text-center">
        <div class="w-20 h-20 mx-auto mb-5 rounded-full bg-gray-50 flex items-center justify-center">
          <ShoppingCart class="w-10 h-10 text-gray-300" />
        </div>
        <p class="text-gray-500 mb-1">购物车还是空的</p>
        <p class="text-xs text-gray-400 mb-6">去挑选几件心仪的商品吧</p>
        <router-link to="/portal/home" class="btn-primary">去逛逛</router-link>
      </div>

      <div v-else class="page-card overflow-hidden">
        <!-- 表头 -->
        <div class="px-5 pt-4">
          <div class="grid grid-cols-12 gap-4 text-xs text-gray-400 bg-gray-50 rounded-lg px-4 py-2.5 font-medium">
            <div class="col-span-1">
              <input
                type="checkbox"
                :checked="allChecked"
                @change="cartStore.toggleCheckAll(!allChecked)"
                class="w-4 h-4 accent-[#E4393C] cursor-pointer"
              />
            </div>
            <div class="col-span-4">商品信息</div>
            <div class="col-span-2 text-center">单价</div>
            <div class="col-span-2 text-center">数量</div>
            <div class="col-span-2 text-center">小计</div>
            <div class="col-span-1 text-center">操作</div>
          </div>

          <!-- 商品行 -->
          <div
            v-for="item in cartStore.cartList"
            :key="item.id"
            class="grid grid-cols-12 gap-4 items-center px-4 py-4 border-b border-gray-50 last:border-0 hover:bg-gray-50/60 rounded-lg transition-colors"
          >
            <div class="col-span-1">
              <input
                type="checkbox"
                :checked="item.checked"
                @change="cartStore.toggleCheck(item.itemId)"
                class="w-4 h-4 accent-[#E4393C] cursor-pointer"
              />
            </div>
            <div class="col-span-4 flex items-center gap-3 min-w-0">
              <img
                :src="item.image || '/img/like_01.png'"
                class="w-[72px] h-[72px] object-cover rounded-lg border border-gray-100 flex-shrink-0 cursor-pointer"
                @click="goDetail(item.itemId)"
              />
              <span
                class="text-[13px] text-gray-700 line-clamp-2 leading-relaxed cursor-pointer hover:text-[#E4393C] transition-colors"
                @click="goDetail(item.itemId)"
              >{{ item.name }}</span>
            </div>
            <div class="col-span-2 text-center text-[13px] text-gray-600">¥{{ formatPrice(item.price) }}</div>
            <div class="col-span-2 flex justify-center">
              <div class="flex items-center border border-gray-200 rounded-lg overflow-hidden">
                <button
                  @click="decreaseNum(item)"
                  class="w-8 h-8 flex items-center justify-center text-gray-500 hover:bg-gray-50 hover:text-[#E4393C] transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                  :disabled="item.num <= 1"
                >
                  <Minus class="w-3.5 h-3.5" />
                </button>
                <input
                  :value="item.num"
                  class="w-11 h-8 text-center text-[13px] border-x border-gray-200 outline-none"
                  readonly
                />
                <button
                  @click="increaseNum(item)"
                  class="w-8 h-8 flex items-center justify-center text-gray-500 hover:bg-gray-50 hover:text-[#E4393C] transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                  :disabled="item.num >= item.stock"
                >
                  <Plus class="w-3.5 h-3.5" />
                </button>
              </div>
            </div>
            <div class="col-span-2 text-center price-tag text-[15px]">
              ¥{{ formatPrice(item.price * item.num) }}
            </div>
            <div class="col-span-1 text-center">
              <button
                @click="cartStore.removeItem(item.itemId)"
                class="p-1.5 rounded-lg text-gray-300 hover:text-[#E4393C] hover:bg-red-50 transition-colors"
                title="删除"
              >
                <Trash2 class="w-4 h-4" />
              </button>
            </div>
          </div>
        </div>

        <!-- 底部结算栏 -->
        <div class="border-t border-gray-100 px-5 py-4 flex items-center justify-between bg-white sticky bottom-0">
          <div class="flex items-center gap-5">
            <label class="flex items-center gap-2 text-[13px] text-gray-600 cursor-pointer">
              <input
                type="checkbox"
                :checked="allChecked"
                @change="cartStore.toggleCheckAll(!allChecked)"
                class="w-4 h-4 accent-[#E4393C] cursor-pointer"
              />
              全选
            </label>
            <button
              @click="cartStore.removeChecked()"
              class="text-[13px] text-gray-400 hover:text-[#E4393C] transition-colors"
            >
              删除选中
            </button>
          </div>
          <div class="flex items-center gap-6">
            <div class="text-right">
              <p class="text-xs text-gray-400">
                已选 <span class="text-[#E4393C] font-bold text-sm">{{ cartStore.totalNum }}</span> 件商品
              </p>
              <p class="mt-0.5 text-sm text-gray-600">
                合计（不含运费）：
                <span class="price-tag text-[10px]">¥</span>
                <span class="price-tag text-[22px]">{{ formatPrice(cartStore.totalPrice) }}</span>
              </p>
            </div>
            <router-link
              to="/portal/order"
              class="flex items-center justify-center px-10 py-3.5 rounded-xl text-white text-base font-semibold bg-gradient-to-r from-[#f04548] to-[#d2202a] shadow-glow hover:shadow-lift hover:-translate-y-0.5 active:translate-y-0 transition-all"
              :class="cartStore.totalNum === 0 ? 'opacity-40 pointer-events-none' : ''"
            >
              去结算
            </router-link>
          </div>
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ShoppingCart, Minus, Plus, Trash2 } from 'lucide-vue-next'
import PortalLayout from './PortalLayout.vue'
import { useCartStore } from '@/stores/cart'
import { useUserStore } from '@/stores/user'
import { formatPrice } from '@/utils/format'
import type { CartItem } from '@/types'

const router = useRouter()
const cartStore = useCartStore()
const userStore = useUserStore()

const allChecked = computed(() =>
  cartStore.cartList.length > 0 && cartStore.cartList.every((item) => item.checked)
)

function goDetail(itemId: number) {
  router.push(`/portal/product/${itemId}`)
}

async function increaseNum(item: CartItem) {
  if (item.num < item.stock) {
    await cartStore.updateNum(item.itemId, item.num + 1)
  }
}

async function decreaseNum(item: CartItem) {
  if (item.num > 1) {
    await cartStore.updateNum(item.itemId, item.num - 1)
  }
}
</script>
