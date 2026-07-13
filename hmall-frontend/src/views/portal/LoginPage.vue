<template>
  <div class="min-h-screen bg-[#f5f5f5] flex flex-col">
    <!-- Simplified Header -->
    <div class="bg-white border-b">
      <div class="container-main py-3">
        <router-link to="/portal/home">
          <img src="/img/logo.png" alt="黑马商城" class="h-12" />
        </router-link>
      </div>
    </div>

    <!-- Login Form -->
    <div class="flex-1 flex items-center justify-center py-20">
      <div class="bg-white rounded-2xl shadow-lg p-10 w-[420px]">
        <h2 class="text-2xl font-bold text-center mb-8 text-gray-800">欢迎登录</h2>

        <!-- Tab Switch -->
        <div class="flex border-b border-gray-200 mb-6">
          <button
            @click="loginMode = 'password'"
            :class="[
              'flex-1 pb-3 text-sm font-medium transition-colors border-b-2',
              loginMode === 'password'
                ? 'border-[#E4393C] text-[#E4393C]'
                : 'border-transparent text-gray-400 hover:text-gray-600'
            ]"
          >
            密码登录
          </button>
          <button
            @click="loginMode = 'code'"
            :class="[
              'flex-1 pb-3 text-sm font-medium transition-colors border-b-2',
              loginMode === 'code'
                ? 'border-[#E4393C] text-[#E4393C]'
                : 'border-transparent text-gray-400 hover:text-gray-600'
            ]"
          >
            验证码登录
          </button>
        </div>

        <!-- 密码登录表单 -->
        <form v-if="loginMode === 'password'" @submit.prevent="handleLogin" class="space-y-5">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">用户名</label>
            <div class="relative">
              <User class="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
              <input
                v-model="form.username"
                type="text"
                placeholder="邮箱/用户名/手机号"
                class="w-full pl-10 pr-4 py-3 border border-gray-300 rounded-lg outline-none focus:border-[#E4393C] focus:ring-1 focus:ring-[#E4393C] transition-colors text-sm"
              />
            </div>
          </div>

          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">密码</label>
            <div class="relative">
              <Lock class="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
              <input
                v-model="form.password"
                :type="showPwd ? 'text' : 'password'"
                placeholder="请输入密码"
                class="w-full pl-10 pr-12 py-3 border border-gray-300 rounded-lg outline-none focus:border-[#E4393C] focus:ring-1 focus:ring-[#E4393C] transition-colors text-sm"
                @keyup.enter="handleLogin"
              />
              <Eye
                v-if="!showPwd"
                class="absolute right-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400 cursor-pointer hover:text-gray-600"
                @click="showPwd = true"
              />
              <EyeOff
                v-else
                class="absolute right-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400 cursor-pointer hover:text-gray-600"
                @click="showPwd = false"
              />
            </div>
          </div>

          <div v-if="errorMsg" class="text-[#E4393C] text-sm text-center bg-red-50 py-2 rounded">
            {{ errorMsg }}
          </div>

          <button
            type="submit"
            :disabled="loading"
            class="w-full bg-[#E4393C] text-white py-3 rounded-lg font-medium hover:bg-[#C81623] transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {{ loading ? '登录中...' : '登录' }}
          </button>
        </form>

        <!-- 验证码登录表单 -->
        <form v-else @submit.prevent="handleCodeLogin" class="space-y-5">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">手机号</label>
            <div class="relative">
              <Smartphone class="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
              <input
                v-model="codeForm.phone"
                type="tel"
                maxlength="11"
                placeholder="请输入手机号"
                class="w-full pl-10 pr-4 py-3 border border-gray-300 rounded-lg outline-none focus:border-[#E4393C] focus:ring-1 focus:ring-[#E4393C] transition-colors text-sm"
              />
            </div>
          </div>

          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">验证码</label>
            <div class="flex gap-3">
              <div class="relative flex-1">
                <KeyRound class="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
                <input
                  v-model="codeForm.code"
                  type="text"
                  maxlength="6"
                  placeholder="请输入验证码"
                  class="w-full pl-10 pr-4 py-3 border border-gray-300 rounded-lg outline-none focus:border-[#E4393C] focus:ring-1 focus:ring-[#E4393C] transition-colors text-sm"
                  @keyup.enter="handleCodeLogin"
                />
              </div>
              <button
                type="button"
                @click="handleSendCode"
                :disabled="codeCountdown > 0 || codeSending"
                class="shrink-0 px-4 py-3 rounded-lg text-sm font-medium transition-colors whitespace-nowrap"
                :class="
                  codeCountdown > 0
                    ? 'bg-gray-100 text-gray-400 cursor-not-allowed'
                    : 'bg-[#E4393C] text-white hover:bg-[#C81623]'
                "
              >
                {{ codeCountdown > 0 ? `${codeCountdown}s 后重发` : codeSending ? '发送中...' : '发送验证码' }}
              </button>
            </div>
          </div>

          <div v-if="errorMsg" class="text-[#E4393C] text-sm text-center bg-red-50 py-2 rounded">
            {{ errorMsg }}
          </div>

          <div v-if="successMsg" class="text-green-600 text-sm text-center bg-green-50 py-2 rounded">
            {{ successMsg }}
          </div>

          <button
            type="submit"
            :disabled="loading"
            class="w-full bg-[#E4393C] text-white py-3 rounded-lg font-medium hover:bg-[#C81623] transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {{ loading ? '登录中...' : '登录' }}
          </button>
        </form>

        <div class="mt-6 text-center text-sm text-gray-500">
          <span class="cursor-pointer hover:text-[#E4393C]">忘记密码？</span>
          <span class="mx-2">|</span>
          <router-link to="/portal/home" class="hover:text-[#E4393C]">返回首页</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock, Eye, EyeOff, Smartphone, KeyRound } from 'lucide-vue-next'
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
