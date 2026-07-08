// 通用分页查询
export interface PageQuery {
  pageNo?: number
  pageSize?: number
  sortBy?: string
  isAsc?: boolean
}

// 通用分页结果
export interface PageResult<T> {
  total: number
  pages: number
  list: T[]
}

// 用户信息
export interface UserInfo {
  id: number
  username: string
  balance: number
}

// 用户登录响应
export interface UserLoginVO {
  token: string
  userId: number
  username: string
  balance: number
}

// 登录表单
export interface LoginFormDTO {
  username: string
  password: string
}

// 商品
export interface Item {
  id: number
  name: string
  price: number
  stock: number
  image: string
  category: string
  brand: string
  spec: string
  sold: number
  commentCount: number
  isAD: boolean
  status: number
}

// 购物车项
export interface CartItem {
  id: number
  itemId: number
  name: string
  image: string
  price: number
  stock: number
  num: number
  checked?: boolean
}

// 购物车表单
export interface CartFormDTO {
  itemId: number
  name?: string
  spec?: string
  price?: number
  image?: string
}

// 订单详情条目
export interface OrderDetailDTO {
  itemId: number
  num: number
}

// 订单表单
export interface OrderFormDTO {
  addressId: number
  paymentType: number
  details: OrderDetailDTO[]
}

// 订单
export interface OrderVO {
  id: number
  totalFee: number
  paymentType: number
  userId: number
  status: number
  createTime: string
  payTime?: string
  consignTime?: string
  endTime?: string
  closeTime?: string
  commentTime?: string
  detailVOs?: OrderDetailVO[]
}

export interface OrderDetailVO {
  id: number
  itemId: number
  num: number
  name: string
  price: number
  image: string
}

// 支付表单
export interface PayOrderFormDTO {
  id: number
  pw: string
}

// 支付申请DTO
export interface PayApplyDTO {
  bizOrderNo: number
  payChannelCode: string
}

// 支付订单
export interface PayOrderVO {
  id: number
  bizOrderNo: number
  payChannelCode: string
  amount: number
  status: number
  createTime: string
  payTime?: string
  closeTime?: string
}

// 搜索过滤项（ES 聚合结果：分类/品牌 -> 可选值列表）
export interface SearchFilters {
  category: string[]
  brand: string[]
}

// 搜索参数
export interface SearchParams extends PageQuery {
  key?: string
  category?: string
  brand?: string
  minPrice?: number
  maxPrice?: number
}

// 地址
export interface Address {
  id: number
  userId: number
  contact: string
  mobile: string
  province: string
  city: string
  town: string
  street: string
  isDefault: boolean
}
