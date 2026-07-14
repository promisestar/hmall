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
