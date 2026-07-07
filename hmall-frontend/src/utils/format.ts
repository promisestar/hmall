/**
 * 价格格式化：分 → 元（字符串显示）
 */
export function formatPrice(val: number | string | null | undefined): string {
  if (val == null) return '0.00'
  if (typeof val === 'number') {
    return (val / 100).toFixed(2)
  }
  if (typeof val === 'string') {
    if (isNaN(Number(val))) return '0.00'
    const num = parseInt(val, 10)
    return (num / 100).toFixed(2)
  }
  return '0.00'
}

/**
 * 价格字符串转分（整数）
 */
export function priceToInt(val: string): number {
  const cleaned = val.replace(/[^\d.]/g, '')
  const num = parseFloat(cleaned)
  return isNaN(num) ? 0 : Math.round(num * 100)
}

/**
 * 获取 URL 查询参数
 */
export function getUrlParam(name: string): string {
  const search = window.location.search.substring(1)
  const params = new URLSearchParams(search)
  return params.get(name) || ''
}

/**
 * 日期格式化
 */
export function formatDate(dateStr: string, fmt = 'yyyy-MM-dd HH:mm:ss'): string {
  const date = new Date(dateStr)
  const o: Record<string, number> = {
    'M+': date.getMonth() + 1,
    'd+': date.getDate(),
    'H+': date.getHours(),
    'm+': date.getMinutes(),
    's+': date.getSeconds(),
  }
  let result = fmt
  if (/(y+)/.test(result)) {
    result = result.replace(RegExp.$1, (date.getFullYear() + '').substring(4 - RegExp.$1.length))
  }
  for (const k in o) {
    if (new RegExp('(' + k + ')').test(result)) {
      result = result.replace(RegExp.$1, (RegExp.$1.length === 1) ? (o[k] + '') : (('00' + o[k]).substring(('' + o[k]).length)))
    }
  }
  return result
}
