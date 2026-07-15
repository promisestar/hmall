/**
 * admin 相关类型定义
 */

/** GET /admin/info 返回的管理员信息 */
export interface AdminInfo {
  id: number
  username: string
  icon?: string
  roles: string[]
  menus: AdminMenu[]
  permissions: string[]
}

/** 菜单树节点 */
export interface AdminMenu {
  id: number
  parentId: number
  title: string
  name?: string
  path?: string
  icon?: string
  level: number
  sort: number
  hidden: number
  children?: AdminMenu[]
}

/** 管理员信息 */
export interface AdminUser {
  id: number
  username: string
  icon?: string
  email?: string
  nickName?: string
  note?: string
  status: number
  createTime: string
  loginTime?: string
  roleIds?: number[]
}

/** 管理员登录表单 */
export interface AdminLoginDTO {
  username: string
  password: string
}

/** Token 响应 */
export interface TokenVO {
  token: string
  tokenHead: string
}

/** 角色 */
export interface Role {
  id: number
  name: string
  description?: string
  adminCount: number
  status: number
  sort: number
  createTime: string
}

/** 菜单 */
export interface Menu {
  id: number
  parentId: number
  title: string
  level: number
  sort: number
  name?: string
  path?: string
  icon?: string
  hidden: number
  createTime: string
}

/** 资源(权限) */
export interface Resource {
  id: number
  name: string
  url: string
  method?: string
  description?: string
  categoryId?: number
  createTime: string
}

/** 资源分类 */
export interface ResourceCategory {
  id: number
  name: string
  createTime: string
}

/** 订单管理查询参数 */
export interface OrderPageQuery {
  pageNo?: number
  pageSize?: number
  status?: number
  orderId?: number
  startTime?: string
  endTime?: string
}

// ==================== 秒杀管理 ====================

/** 秒杀活动管理 VO */
export interface SeckillPromotionAdminVO {
  id: number
  title: string
  startDate: string
  endDate: string
  /** 状态: 0未开始 1进行中 2已结束 */
  status: number
  sessionCount: number
  productCount: number
  createTime: string
  updateTime: string
}

/** 秒杀场次管理 VO */
export interface SeckillSessionAdminVO {
  id: number
  promotionId: number
  promotionTitle?: string
  name: string
  startTime: string
  endTime: string
  /** 状态: 0未开始 1进行中 2已结束 */
  status: number
  productCount: number
  createTime: string
  updateTime: string
}

/** 秒杀商品关联管理 VO */
export interface SeckillProductRelationAdminVO {
  id: number
  promotionId: number
  sessionId: number
  productId: number
  productName?: string
  productImage?: string
  /** 秒杀价（分） */
  seckillPrice: number
  stock: number
  limitNum: number
  remainingStock: number
  soldCount: number
  preheated: boolean
  createTime: string
  updateTime: string
}

/** 秒杀订单管理 VO */
export interface SeckillOrderAdminVO {
  id: number
  orderId: number
  relationId: number
  productId?: number
  productName?: string
  userId: number
  quantity: number
  /** 秒杀价（分） */
  seckillPrice?: number
  /** 状态: 1待支付 2已支付 3已关闭 */
  status: number
  createTime: string
  updateTime: string
}

/** 秒杀每日库存快照 VO */
export interface SeckillStockAdminVO {
  id: number
  relationId: number
  batchDate: string
  stock: number
  sold: number
  remaining: number
}

/** 秒杀活动创建/修改 DTO */
export interface SeckillPromotionDTO {
  id?: number
  title: string
  startDate: string
  endDate: string
}

/** 秒杀场次创建/修改 DTO */
export interface SeckillSessionDTO {
  id?: number
  promotionId: number
  name: string
  startTime: string
  endTime: string
}

/** 秒杀商品关联创建/修改 DTO */
export interface SeckillProductRelationDTO {
  id?: number
  promotionId: number
  sessionId: number
  productId: number
  /** 秒杀价（分） */
  seckillPrice: number
  stock: number
  limitNum: number
}
