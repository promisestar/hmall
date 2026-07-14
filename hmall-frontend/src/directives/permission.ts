import type { Directive } from 'vue'
import { useAdminStore } from '@/stores/admin'

/**
 * v-permission 按钮级权限指令
 * 用法: v-permission="'product:create'" 或 v-permission="['product:create', 'product:update']"
 * 当管理员没有对应权限时，移除该 DOM 元素
 */
export const permission: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const adminStore = useAdminStore()
    const value = binding.value

    if (!value) return

    const codes = Array.isArray(value) ? value : [value]
    const hasPerm = codes.some(code => adminStore.hasPermission(code))

    if (!hasPerm) {
      el.parentNode?.removeChild(el)
    }
  },
}
