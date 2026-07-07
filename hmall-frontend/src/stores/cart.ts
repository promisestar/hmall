import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  getCartList,
  addToCart as addToCartApi,
  updateCartNum,
  deleteCartItem,
  deleteCartItems,
} from '@/api/cart'
import type { CartItem, CartFormDTO } from '@/types'

export const useCartStore = defineStore('cart', () => {
  const cartList = ref<CartItem[]>([])
  const loading = ref(false)

  const checkedItems = computed(() => cartList.value.filter((item) => item.checked))
  const totalPrice = computed(() =>
    checkedItems.value.reduce((sum, item) => sum + item.price * item.num, 0)
  )
  const totalNum = computed(() =>
    checkedItems.value.reduce((sum, item) => sum + item.num, 0)
  )
  const cartNum = computed(() =>
    cartList.value.reduce((sum, item) => sum + item.num, 0)
  )

  async function fetchCartList() {
    loading.value = true
    try {
      cartList.value = await getCartList()
    } finally {
      loading.value = false
    }
  }

  async function addToCart(data: CartFormDTO) {
    await addToCartApi(data)
    await fetchCartList()
  }

  async function updateNum(id: number, num: number) {
    await updateCartNum(id, { num })
    const item = cartList.value.find((i) => i.id === id)
    if (item) item.num = num
  }

  async function removeItem(id: number) {
    await deleteCartItem(id)
    cartList.value = cartList.value.filter((i) => i.id !== id)
  }

  async function removeChecked() {
    const ids = checkedItems.value.map((i) => i.id)
    if (ids.length === 0) return
    await deleteCartItems(ids)
    cartList.value = cartList.value.filter((i) => !i.checked)
  }

  function toggleCheck(id: number) {
    const item = cartList.value.find((i) => i.id === id)
    if (item) item.checked = !item.checked
  }

  function toggleCheckAll(checked: boolean) {
    cartList.value.forEach((item) => (item.checked = checked))
  }

  function clearCart() {
    cartList.value = []
  }

  return {
    cartList,
    loading,
    checkedItems,
    totalPrice,
    totalNum,
    cartNum,
    fetchCartList,
    addToCart,
    updateNum,
    removeItem,
    removeChecked,
    toggleCheck,
    toggleCheckAll,
    clearCart,
  }
})
