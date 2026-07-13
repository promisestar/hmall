import request from './index'
import type { UserLoginVO, LoginFormDTO, SendCodeDTO, LoginByCodeDTO } from '@/types'

// 密码登录
export function login(data: LoginFormDTO): Promise<UserLoginVO> {
  return request.post('/users/login', data)
}

// 发送短信验证码
export function sendCode(phone: string): Promise<void> {
  return request.post('/users/code', { phone })
}

// 验证码登录
export function loginByCode(data: LoginByCodeDTO): Promise<UserLoginVO> {
  return request.post('/users/login/code', data)
}

// 登出（使 token 失效）
export function logoutApi(): Promise<void> {
  return request.post('/users/logout')
}

// 扣减余额
export function deductMoney(pw: string, amount: number): Promise<void> {
  return request.post('/users/money/deduct', { pw, amount })
}
