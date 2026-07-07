import request from './index'
import type { UserLoginVO, LoginFormDTO } from '@/types'

export function login(data: LoginFormDTO): Promise<UserLoginVO> {
  return request.post('/users/login', data)
}

export function deductMoney(pw: string, amount: number): Promise<void> {
  return request.post('/users/money/deduct', { pw, amount })
}
