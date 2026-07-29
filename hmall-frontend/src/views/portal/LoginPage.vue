<template>
  <div class="min-h-screen bg-[#f6f7f9] flex flex-col">
    <!-- 简化页头 -->
    <div class="bg-white border-b border-gray-100">
      <div class="container-main py-3 flex items-center justify-between">
        <router-link to="/portal/home">
          <img src="/img/logo.png" alt="枫叶商城" class="h-11" />
        </router-link>
        <router-link to="/portal/home" class="text-xs text-gray-400 hover:text-[#E4393C] transition-colors">
          返回首页
        </router-link>
      </div>
    </div>

    <!-- 登录卡片 -->
    <div class="flex-1 flex items-center justify-center py-14">
      <div class="flex w-[860px] bg-white rounded-2xl shadow-lift overflow-hidden">
        <!-- 品牌面板 -->
        <div class="relative w-[380px] flex-shrink-0 bg-gradient-to-br from-[#e4393c] via-[#f0502e] to-[#ff7a45] p-10 flex flex-col text-white overflow-hidden">
          <div class="absolute -right-14 -top-14 w-52 h-52 rounded-full bg-white/10"></div>
          <div class="absolute right-16 bottom-[-70px] w-60 h-60 rounded-full bg-white/10"></div>
          <div class="absolute left-10 bottom-24 w-16 h-16 rounded-full bg-white/5"></div>

          <div class="relative">
            <img src="/img/logo.png" alt="枫叶商城" class="h-12 brightness-0 invert" />
          </div>
          <div class="relative mt-14">
            <h2 class="text-[26px] font-bold leading-snug">品质好物<br />一站购齐</h2>
            <p class="mt-3 text-sm text-white/80 leading-relaxed">登录枫叶商城，畅享超值优惠与 AI 智能购物体验。</p>
          </div>
          <ul class="relative mt-auto space-y-3 text-[13px] text-white/85">
            <li class="flex items-center gap-2.5">
              <ShieldCheck class="w-4 h-4" />自营正品 · 假一赔十
            </li>
            <li class="flex items-center gap-2.5">
              <Truck class="w-4 h-4" />极速物流 · 211 限时达
            </li>
            <li class="flex items-center gap-2.5">
              <Sparkles class="w-4 h-4" />AI 客服 · 7x24 智能导购
            </li>
          </ul>
        </div>

        <!-- 表单面板 -->
        <div class="flex-1 p-10">
          <h2 class="text-xl font-bold text-gray-900 mb-6">欢迎登录</h2>

          <!-- Tab 切换 -->
          <div class="flex border-b border-gray-100 mb-6">
            <button
              @click="loginMode = 'password'"
              :class="[
                'flex-1 pb-3 text-sm transition-colors border-b-2 -mb-px',
                loginMode === 'password'
                  ? 'border-[#E4393C] text-[#E4393C] font-semibold'
                  : 'border-transparent text-gray-400 hover:text-gray-600'
              ]"
            >
              密码登录
            </button>
            <button
              @click="loginMode = 'code'"
              :class="[
                'flex-1 pb-3 text-sm transition-colors border-b-2 -mb-px',
                loginMode === 'code'
                  ? 'border-[#E4393C] text-[#E4393C] font-semibold'
                  : 'border-transparent text-gray-400 hover:text-gray-600'
              ]"
            >
              验证码登录
            </button>
          </div>

          <!-- 密码登录表单 -->
          <form v-if="loginMode === 'password'" @submit.prevent="handleLogin" class="space-y-5">
            <div>
              <label class="block text-[13px] font-medium text-gray-600 mb-1.5">用户名</label>
              <div class="relative">
                <User class="absolute left-3.5 top-1/2 -translate-y-1/2 w-4.5 h-4.5 text-gray-300" />
                <input
                  v-model="form.username"
                  type="text"
                  placeholder="邮箱 / 用户名 / 手机号"
                  class="login-input pl-10 pr-4"
                />
              </div>
            </div>

            <div>
              <label class="block text-[13px] font-medium text-gray-600 mb-1.5">密码</label>
              <div class="relative">
                <Lock class="absolute left-3.5 top-1/2 -translate-y-1/2 w-4.5 h-4.5 text-gray-300" />
                <input
                  v-model="form.password"
                  :type="showPwd ? 'text' : 'password'"
                  placeholder="请输入密码"
                  class="login-input pl-10 pr-11"
                  @keyup.enter="handleLogin"
                />
                <Eye
                  v-if="!showPwd"
                  class="absolute right-3.5 top-1/2 -translate-y-1/2 w-4.5 h-4.5 text-gray-300 cursor-pointer hover:text-gray-500 transition-colors"
                  @click="showPwd = true"
                />
                <EyeOff
                  v-else
                  class="absolute right-3.5 top-1/2 -translate-y-1/2 w-4.5 h-4.5 text-gray-300 cursor-pointer hover:text-gray-500 transition-colors"
                  @click="showPwd = false"
                />
              </div>
            </div>

            <div v-if="errorMsg" class="text-[#E4393C] text-[13px] text-center bg-red-50 py-2 rounded-lg">
              {{ errorMsg }}
            </div>

            <button
              type="submit"
              :disabled="loading"
              class="w-full py-3 rounded-xl text-white font-semibold bg-gradient-to-r from-[#f04548] to-[#d2202a] shadow-glow hover:shadow-lift hover:-translate-y-0.5 active:translate-y-0 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {{ loading ? '登录中...' : '登 录' }}
            </button>
          </form>

          <!-- 验证码登录表单 -->
          <form v-else @submit.prevent="handleCodeLogin" class="space-y-5">
            <div>
              <label class="block text-[13px] font-medium text-gray-600 mb-1.5">手机号</label>
              <div class="relative">
                <Smartphone class="absolute left-3.5 top-1/2 -translate-y-1/2 w-4.5 h-4.5 text-gray-300" />
                <input
                  v-model="codeForm.phone"
                  type="tel"
                  maxlength="11"
                  placeholder="请输入手机号"
                  class="login-input pl-10 pr-4"
                />
              </div>
            </div>

            <div>
              <label class="block text-[13px] font-medium text-gray-600 mb-1.5">验证码</label>
              <div class="flex gap-3">
                <div class="relative flex-1">
                  <KeyRound class="absolute left-3.5 top-1/2 -translate-y-1/2 w-4.5 h-4.5 text-gray-300" />
                  <input
                    v-model="codeForm.code"
                    type="text"
                    maxlength="6"
                    placeholder="请输入验证码"
                    class="login-input pl-10 pr-4"
                    @keyup.enter="handleCodeLogin"
                  />
                </div>
                <button
                  type="button"
                  @click="handleSendCode"
                  :disabled="codeCountdown > 0 || codeSending"
                  class="shrink-0 px-4 rounded-xl text-[13px] font-medium transition-all whitespace-nowrap"
                  :class="
                    codeCountdown > 0
                      ? 'bg-gray-100 text-gray-400 cursor-not-allowed'
                      : 'bg-red-50 text-[#E4393C] hover:bg-[#E4393C] hover:text-white'
                  "
                >
                  {{ codeCountdown > 0 ? `${codeCountdown}s 后重发` : codeSending ? '发送中...' : '发送验证码' }}
                </button>
              </div>
            </div>

            <div v-if="errorMsg" class="text-[#E4393C] text-[13px] text-center bg-red-50 py-2 rounded-lg">
              {{ errorMsg }}
            </div>

            <div v-if="successMsg" class="text-green-600 text-[13px] text-center bg-green-50 py-2 rounded-lg">
              {{ successMsg }}
            </div>

            <button
              type="submit"
              :disabled="loading"
              class="w-full py-3 rounded-xl text-white font-semibold bg-gradient-to-r from-[#f04548] to-[#d2202a] shadow-glow hover:shadow-lift hover:-translate-y-0.5 active:translate-y-0 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {{ loading ? '登录中...' : '登 录' }}
            </button>
          </form>

          <div class="mt-6 text-center text-xs text-gray-400">
            <span class="cursor-pointer hover:text-[#E4393C] transition-colors">忘记密码？</span>
            <span class="mx-2 text-gray-200">|</span>
            <span class="cursor-pointer hover:text-[#E4393C] transition-colors">注册新账号</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock, Eye, EyeOff, Smartphone, KeyRound, ShieldCheck, Truck, Sparkles } from 'lucide-vue-next'
import { useUserStore } from '@/stores/user'
import { sendCode as sendCodeApi } from '@/api/user'

const router = useRouter()
const userStore = useUserStore()

// 登录模式切换
const loginMode = ref<'password' | 'code'>('password')

// 密码登录表单
const form = reactive({ username: '', password: '' })
const showPwd = ref(false)

// 验证码登录表单
const codeForm = reactive({ phone: '', code: '' })
const codeCountdown = ref(0)
const codeSending = ref(false)

const loading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')

/** 密码登录 */
async function handleLogin() {
  if (!form.username || !form.password) {
    errorMsg.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    await userStore.login(form)
    goHome()
  } catch {
    errorMsg.value = '用户名或密码错误'
  } finally {
    loading.value = false
  }
}

/** 验证码登录 */
async function handleCodeLogin() {
  if (!codeForm.phone) {
    errorMsg.value = '请输入手机号'
    return
  }
  if (!/^1[3-9]\d{9}$/.test(codeForm.phone)) {
    errorMsg.value = '手机号格式不正确'
    return
  }
  if (!codeForm.code) {
    errorMsg.value = '请输入验证码'
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    await userStore.loginByCode({ phone: codeForm.phone, code: codeForm.code })
    goHome()
  } catch {
    errorMsg.value = '验证码错误或已过期'
  } finally {
    loading.value = false
  }
}

/** 发送验证码 */
async function handleSendCode() {
  const phone = codeForm.phone
  if (!phone) {
    errorMsg.value = '请输入手机号'
    return
  }
  if (!/^1[3-9]\d{9}$/.test(phone)) {
    errorMsg.value = '手机号格式不正确'
    return
  }
  errorMsg.value = ''
  successMsg.value = ''
  codeSending.value = true
  try {
    await sendCodeApi(phone)
    successMsg.value = '验证码已发送，5分钟内有效'
    // 60 秒倒计时
    codeCountdown.value = 60
    const timer = setInterval(() => {
      codeCountdown.value--
      if (codeCountdown.value <= 0) {
        clearInterval(timer)
      }
    }, 1000)
  } catch {
    errorMsg.value = '发送验证码失败，请稍后重试'
  } finally {
    codeSending.value = false
  }
}

/** 登录成功跳转 */
function goHome() {
  const returnUrl = sessionStorage.getItem('return-url') || '/portal/home'
  sessionStorage.removeItem('return-url')
  router.push(returnUrl)
}
</script>

<style scoped>
.login-input {
  @apply w-full py-3 bg-gray-50 border border-gray-200 rounded-xl outline-none text-sm transition-all;
}

.login-input:focus {
  @apply border-[#E4393C] bg-white;
  box-shadow: 0 0 0 3px rgba(228, 57, 60, .08);
}

.login-input::placeholder {
  @apply text-gray-300;
}

.w-4\.5 {
  width: 18px;
}

.h-4\.5 {
  height: 18px;
}
</style>
