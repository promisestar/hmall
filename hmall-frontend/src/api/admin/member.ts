import adminRequest from '../admin'
import type { PageResult, PageQuery } from '@/types'

/** C端用户信息 */
export interface MemberInfo {
  id: number
  username: string
  phone: string
  status: number
  balance: number
  createTime: string
  updateTime: string
}

/** 分页查询C端用户 */
export function getMemberPage(params: PageQuery & { keyword?: string; status?: number }) {
  return adminRequest.get('/admin/member/list', { params }) as Promise<PageResult<MemberInfo>>
}

/** 用户详情 */
export function getMemberDetail(id: number) {
  return adminRequest.get(`/admin/member/${id}`) as Promise<MemberInfo>
}

/** 修改用户状态(冻结/解冻) */
export function updateMemberStatus(id: number, status: number) {
  return adminRequest.post(`/admin/member/status/${id}`, null, { params: { status } }) as Promise<void>
}

/** 调整用户余额 */
export function updateMemberBalance(id: number, delta: number) {
  return adminRequest.post(`/admin/member/balance/${id}`, null, { params: { delta } }) as Promise<void>
}
