<template>
  <div class="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100 flex items-center justify-center">
    <div class="bg-white rounded-2xl shadow-xl p-10 w-[420px]">
      <div class="text-center mb-8">
        <h1 class="text-2xl font-bold text-gray-800">管理后台登录</h1>
        <p class="text-sm text-gray-500 mt-2">黑马商城管理系统</p>
      </div>

      <el-form :model="form" label-position="top" size="large">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入管理员用户名" :prefix-icon="User" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>

        <div v-if="errorMsg" class="text-red-500 text-sm text-center mb-3 bg-red-50 py-2 rounded">
          {{ errorMsg }}
        </div>

        <el-button
          type="primary"
          size="large"
          class="w-full"
          :loading="loading"
          @click="handleLogin"
        >
          {{ loading ? '登录中...' : '登录' }}
        </el-button>
      </el-form>

      <div class="mt-4 text-center">
        <router-link to="/portal/home" class="text-sm text-gray-500 hover:text-blue-500 transition-colors">
          返回商城首页
        </router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { useAdminStore } from '@/stores/admin'

const router = useRouter()
const adminStore = useAdminStore()
const form = reactive({ username: '', password: '' })
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
    await adminStore.login({ ...form })
    router.push('/admin/dashboard')
  } catch {
    errorMsg.value = '用户名或密码错误'
  } finally {
    loading.value = false
  }
}
</script>
