import adminRequest from '../admin'
import type { PageResult, PageQuery } from '@/types'
import type {
  SeckillPromotionAdminVO,
  SeckillSessionAdminVO,
  SeckillProductRelationAdminVO,
  SeckillOrderAdminVO,
  SeckillStockAdminVO,
  SeckillPromotionDTO,
  SeckillSessionDTO,
  SeckillProductRelationDTO,
} from '@/types/admin'

// ==================== 活动管理 ====================

/** 分页查询秒杀活动 */
export function getPromotionPage(params: PageQuery & { title?: string; status?: number }) {
  return adminRequest.get('/admin/seckill/promotion/list', { params }) as Promise<PageResult<SeckillPromotionAdminVO>>
}

/** 秒杀活动详情 */
export function getPromotionDetail(id: number) {
  return adminRequest.get(`/admin/seckill/promotion/${id}`) as Promise<SeckillPromotionAdminVO>
}

/** 创建秒杀活动 */
export function createPromotion(data: SeckillPromotionDTO) {
  return adminRequest.post('/admin/seckill/promotion', data) as Promise<number>
}

/** 修改秒杀活动 */
export function updatePromotion(data: SeckillPromotionDTO) {
  return adminRequest.put('/admin/seckill/promotion', data) as Promise<void>
}

/** 删除秒杀活动 */
export function deletePromotion(id: number) {
  return adminRequest.delete(`/admin/seckill/promotion/${id}`) as Promise<void>
}

// ==================== 场次管理 ====================

/** 分页查询秒杀场次 */
export function getSessionPage(params: PageQuery & { promotionId?: number }) {
  return adminRequest.get('/admin/seckill/session/list', { params }) as Promise<PageResult<SeckillSessionAdminVO>>
}

/** 秒杀场次详情 */
export function getSessionDetail(id: number) {
  return adminRequest.get(`/admin/seckill/session/${id}`) as Promise<SeckillSessionAdminVO>
}

/** 创建秒杀场次 */
export function createSession(data: SeckillSessionDTO) {
  return adminRequest.post('/admin/seckill/session', data) as Promise<number>
}

/** 修改秒杀场次 */
export function updateSession(data: SeckillSessionDTO) {
  return adminRequest.put('/admin/seckill/session', data) as Promise<void>
}

/** 删除秒杀场次 */
export function deleteSession(id: number) {
  return adminRequest.delete(`/admin/seckill/session/${id}`) as Promise<void>
}

// ==================== 商品关联管理 ====================

/** 分页查询秒杀商品关联 */
export function getRelationPage(params: PageQuery & { sessionId?: number; promotionId?: number }) {
  return adminRequest.get('/admin/seckill/relation/list', { params }) as Promise<PageResult<SeckillProductRelationAdminVO>>
}

/** 秒杀商品关联详情 */
export function getRelationDetail(id: number) {
  return adminRequest.get(`/admin/seckill/relation/${id}`) as Promise<SeckillProductRelationAdminVO>
}

/** 创建秒杀商品关联 */
export function createRelation(data: SeckillProductRelationDTO) {
  return adminRequest.post('/admin/seckill/relation', data) as Promise<number>
}

/** 修改秒杀商品关联 */
export function updateRelation(data: SeckillProductRelationDTO) {
  return adminRequest.put('/admin/seckill/relation', data) as Promise<void>
}

/** 删除秒杀商品关联 */
export function deleteRelation(id: number) {
  return adminRequest.delete(`/admin/seckill/relation/${id}`) as Promise<void>
}

/** 手动预热秒杀库存 */
export function manualPreheat(id: number) {
  return adminRequest.post(`/admin/seckill/relation/preheat/${id}`) as Promise<void>
}

// ==================== 秒杀订单管理 ====================

/** 分页查询秒杀订单 */
export function getSeckillOrderPage(params: PageQuery & { status?: number; relationId?: number; userId?: number }) {
  return adminRequest.get('/admin/seckill/order/list', { params }) as Promise<PageResult<SeckillOrderAdminVO>>
}

// ==================== 库存查询 ====================

/** 查询秒杀商品库存状态 */
export function getStockStatus(relationId: number) {
  return adminRequest.get(`/admin/seckill/stock/${relationId}`) as Promise<SeckillStockAdminVO[]>
}
