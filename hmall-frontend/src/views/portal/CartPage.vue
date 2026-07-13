<template>
  <PortalLayout :show-search="true">
    <div class="container-main py-6">
      <h2 class="text-lg font-bold mb-4 border-l-4 border-[#E4393C] pl-3">我的购物车</h2>

      <div v-if="!userStore.isLogin" class="bg-white rounded-lg shadow-sm p-20 text-center">
        <ShoppingCart class="w-16 h-16 mx-auto mb-4 text-gray-300" />
        <p class="text-gray-500 mb-4">请先登录后查看购物车</p>
        <router-link to="/portal/login" class="btn-primary">去登录</router-link>
      </div>

      <div v-else-if="cartStore.loading" class="flex justify-center py-20">
        <el-icon class="is-loading text-3xl text-[#E4393C]"><Loading /></el-icon>
      </div>

      <div v-else-if="cartStore.cartList.length === 0" class="bg-white rounded-lg shadow-sm p-20 text-center">
        <ShoppingCart class="w-16 h-16 mx-auto mb-4 text-gray-300" />
        <p class="text-gray-500 mb-4">购物车还是空的</p>
        <router-link to="/portal/home" class="btn-primary">去逛逛</router-link>
      </div>

      <div v-else class="bg-white rounded-lg shadow-sm">
        <!-- Cart Table -->
        <div class="p-4">
          <div class="grid grid-cols-12 gap-4 text-sm text-gray-500 border-b pb-3 mb-3 font-medium">
            <div class="col-span-1">
              <input
                type="checkbox"
                :checked="allChecked"
                @change="cartStore.toggleCheckAll(!allChecked)"
                class="w-4 h-4 accent-[#E4393C]"
              />
            </div>
            <div class="col-span-4">商品信息</div>
            <div class="col-span-2 text-center">单价</div>
            <div class="col-span-2 text-center">数量</div>
            <div class="col-span-2 text-center">小计</div>
            <div class="col-span-1 text-center">操作</div>
          </div>

          <div
            v-for="item in cartStore.cartList"
            :key="item.id"
            class="grid grid-cols-12 gap-4 items-center py-4 border-b border-gray-100 last:border-0"
          >
            <div class="col-span-1">
              <input
                type="checkbox"
                :checked="item.checked"
                @change="cartStore.toggleCheck(item.itemId)"
                class="w-4 h-4 accent-[#E4393C]"
              />
            </div>
            <div class="col-span-4 flex items-center gap-3">
              <img :src="item.image || '/img/like_01.png'" class="w-20 h-20 object-cover rounded" />
              <span class="text-sm line-clamp-2">{{ item.name }}</span>
            </div>
            <div class="col-span-2 text-center text-sm">¥{{ formatPrice(item.price) }}</div>
            <div class="col-span-2 flex justify-center">
              <div class="flex items-center border rounded">
                <button
                  @click="decreaseNum(item)"
                  class="px-2 py-1 text-gray-500 hover:bg-gray-100 transition-colors"
                  :disabled="item.num <= 1"
                >
                  -
                </button>
                <input
                  :value="item.num"
                  class="w-12 text-center text-sm border-x py-1 outline-none"
                  readonly
                />
                <button
                  @click="increaseNum(item)"
                  class="px-2 py-1 text-gray-500 hover:bg-gray-100 transition-colors"
                  :disabled="item.num >= item.stock"
                >
                  +
                </button>
              </div>
            </div>
            <div class="col-span-2 text-center text-sm font-bold text-[#E4393C]">
              ¥{{ formatPrice(item.price * item.num) }}
            </div>
            <div class="col-span-1 text-center">
              <button
                @click="cartStore.removeItem(item.itemId)"
                class="text-gray-400 hover:text-[#E4393C] transition-colors text-sm"
              >
                删除
              </button>
            </div>
          </div>
        </div>

        <!-- Bottom Bar -->
        <div class="border-t p-4 flex items-center justify-between">
          <div class="flex items-center gap-4">
            <label class="flex items-center gap-2 text-sm cursor-pointer">
              <input
                type="checkbox"
                :checked="allChecked"
                @change="cartStore.toggleCheckAll(!allChecked)"
                class="w-4 h-4 accent-[#E4393C]"
              />
              全选
            </label>
            <button
              @click="cartStore.removeChecked()"
              class="text-sm text-gray-500 hover:text-[#E4393C] transition-colors"
            >
              删除选中
            </button>
          </div>
          <div class="flex items-center gap-6">
            <div class="text-right">
              <p class="text-sm text-gray-500">
                已选 <span class="text-[#E4393C] font-bold">{{ cartStore.totalNum }}</span> 件商品
              </p>
              <p class="text-lg">
                合计：<span class="text-[#E4393C] font-bold text-xl">¥{{ formatPrice(cartStore.totalPrice) }}</span>
              </p>
            </div>
            <router-link
              to="/portal/order"
              class="bg-[#E4393C] text-white px-8 py-3 rounded text-lg font-medium hover:bg-[#C81623] transition-colors"
              :class="cartStore.totalNum === 0 ? 'opacity-50 pointer-events-none' : ''"
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
import { ShoppingCart } from 'lucide-vue-next'
import PortalLayout from './PortalLayout.vue'
import { useCartStore } from '@/stores/cart'
import { useUserStore } from '@/stores/user'
import { formatPrice } from '@/utils/format'
import type { CartItem } from '@/types'

const cartStore = useCartStore()
const userStore = useUserStore()

const allChecked = computed(() =>
  cartStore.cartList.length > 0 && cartStore.cartList.every((item) => item.checked)
)

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
