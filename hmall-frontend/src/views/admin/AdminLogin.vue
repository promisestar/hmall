<template>
  <div class="relative min-h-screen bg-[#151a26] flex items-center justify-center overflow-hidden">
    <!-- 背景装饰 -->
    <div class="absolute -left-32 -top-32 w-[480px] h-[480px] rounded-full bg-[#409EFF]/10 blur-3xl"></div>
    <div class="absolute -right-24 bottom-[-120px] w-[420px] h-[420px] rounded-full bg-[#E4393C]/10 blur-3xl"></div>
    <div class="absolute left-1/3 top-1/4 w-64 h-64 rounded-full bg-[#7c3aed]/10 blur-3xl"></div>

    <!-- 登录卡片 -->
    <div class="relative w-[400px] rounded-2xl bg-white/[.07] backdrop-blur-xl border border-white/10 shadow-2xl p-9">
      <!-- 品牌标识 -->
      <div class="flex flex-col items-center mb-8">
        <div class="w-14 h-14 rounded-2xl bg-gradient-to-br from-[#409EFF] to-[#2563eb] flex items-center justify-center shadow-lg shadow-blue-500/30 mb-4">
          <LayoutDashboard class="w-7 h-7 text-white" />
        </div>
        <h1 class="text-xl font-bold text-white tracking-wide">枫叶商城 · 管理后台</h1>
        <p class="text-xs text-white/40 mt-1.5">Hmall Admin Console</p>
      </div>

      <el-form :model="form" label-position="top" size="large" class="admin-login-form">
        <el-form-item>
          <el-input v-model="form.username" placeholder="管理员用户名" :prefix-icon="User" />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="登录密码"
            :prefix-icon="Lock"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>

        <div v-if="errorMsg" class="text-[#ff7875] text-[13px] text-center mb-4 bg-red-500/10 border border-red-500/20 py-2 rounded-lg">
          {{ errorMsg }}
        </div>

        <el-button
          type="primary"
          size="large"
          class="w-full login-btn"
          :loading="loading"
          @click="handleLogin"
        >
          {{ loading ? '登录中...' : '登 录' }}
        </el-button>
      </el-form>

      <div class="mt-6 text-center">
        <router-link to="/portal/home" class="inline-flex items-center gap-1 text-xs text-white/40 hover:text-white/80 transition-colors">
          <ArrowLeft class="w-3 h-3" />返回商城首页
        </router-link>
      </div>
    </div>

    <!-- 底部版权 -->
    <p class="absolute bottom-5 text-[11px] text-white/25">&copy; 2025 枫叶商城 hmall.com 版权所有</p>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { LayoutDashboard, ArrowLeft } from 'lucide-vue-next'
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

<style scoped>
.admin-login-form :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, .06);
  border: 1px solid rgba(255, 255, 255, .12);
  box-shadow: none;
  border-radius: 10px;
  padding: 4px 14px;
}

.admin-login-form :deep(.el-input__wrapper.is-focus) {
  border-color: #409eff;
  box-shadow: 0 0 0 3px rgba(64, 158, 255, .15);
}

.admin-login-form :deep(.el-input__inner) {
  color: #fff;
}

.admin-login-form :deep(.el-input__inner::placeholder) {
  color: rgba(255, 255, 255, .3);
}

.admin-login-form :deep(.el-input__prefix),
.admin-login-form :deep(.el-input__suffix) {
  color: rgba(255, 255, 255, .35);
}

.login-btn {
  border-radius: 10px;
  font-weight: 600;
  letter-spacing: 2px;
  background: linear-gradient(135deg, #409eff 0%, #2563eb 100%);
  border: none;
  box-shadow: 0 4px 14px rgba(37, 99, 235, .35);
}

.login-btn:hover {
  opacity: .92;
  box-shadow: 0 6px 20px rgba(37, 99, 235, .45);
}
</style>
