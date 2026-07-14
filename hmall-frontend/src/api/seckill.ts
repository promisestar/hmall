import request from './index'

// ==================== 秒杀相关类型 ====================

export interface SeckillProductVO {
  relationId: number
  productId: number
  name: string
  image: string
  spec: string
  originalPrice: number
  seckillPrice: number
  totalStock: number
  remainingStock: number
  soldCount: number
  limitNum: number
  status: number
  startTime?: string
  endTime?: string
}

export interface SeckillSessionVO {
  id: number
  name: string
  startTime: string
  endTime: string
  status: number
  products: SeckillProductVO[]
}

export interface SeckillActivityVO {
  id: number
  title: string
  status: number
  sessions: SeckillSessionVO[]
}

export interface SeckillResultVO {
  status: 'success' | 'pending' | 'failed'
  message: string
  orderId?: number
}

// ==================== API 方法 ====================

export function getSeckillActivities(): Promise<SeckillActivityVO[]> {
  return request.get('/seckill/activities')
}

export function getSeckillProduct(relationId: number): Promise<SeckillProductVO> {
  return request.get(`/seckill/products/${relationId}`)
}

export function doSeckill(relationId: number, quantity: number = 1): Promise<SeckillResultVO> {
  return request.post(`/seckill/order/${relationId}`, null, {
    params: { quantity },
  })
}

export function getSeckillResult(relationId: number): Promise<SeckillResultVO> {
  return request.get(`/seckill/result/${relationId}`)
}

// ==================== 轮询工具 ====================

/**
 * 轮询秒杀结果，最多重试 30 次（间隔 1.5s，总计 45s）
 * 遇到 429 限流时延长间隔
 */
export async function pollSeckillResult(
  relationId: number,
  onProgress?: (attempt: number) => void
): Promise<SeckillResultVO> {
  const maxAttempts = 30
  const interval = 1500

  for (let i = 0; i < maxAttempts; i++) {
    try {
      const result = await getSeckillResult(relationId)
      if (result.status === 'success' || result.status === 'failed') {
        return result
      }
      if (onProgress) onProgress(i + 1)
    } catch (error: any) {
      if (error.response?.status === 429) {
        // 被限流，延长等待
        await sleep(interval * 2)
        continue
      }
      console.error('轮询秒杀结果失败', error)
    }
    await sleep(interval)
  }

  return { status: 'failed', message: '排队超时，请稍后在订单列表查看' }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
