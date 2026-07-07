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

        <div class="space-y-5">
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
            @click="handleLogin"
            :disabled="loading"
            class="w-full bg-[#E4393C] text-white py-3 rounded-lg font-medium hover:bg-[#C81623] transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {{ loading ? '登录中...' : '登录' }}
          </button>
        </div>

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
import { useRouter, useRoute } from 'vue-router'
import { User, Lock, Eye, EyeOff } from 'lucide-vue-next'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const form = reactive({ username: '', password: '' })
const showPwd = ref(false)
const loading = ref(false)
const errorMsg = ref('')

async function handleLogin() {
  if (!form.username || !form.password) {
    errorMsg.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    await userStore.login(form)
    const returnUrl = sessionStorage.getItem('return-url') || '/portal/home'
    sessionStorage.removeItem('return-url')
    router.push(returnUrl)
  } catch {
    errorMsg.value = '用户名或密码错误'
  } finally {
    loading.value = false
  }
}
</script>
