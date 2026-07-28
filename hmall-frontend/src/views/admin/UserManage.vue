<template>
  <div>
    <PageHeader title="用户管理" description="商城用户查询、启停与密码重置" />

    <!-- Search -->
    <el-card class="mb-4" shadow="never">
      <div class="flex items-center gap-3">
        <el-input v-model="searchText" placeholder="用户名/手机号" clearable class="w-[200px]" @keyup.enter="fetchData" />
        <el-select v-model="searchStatus" placeholder="全部状态" clearable class="w-[120px]">
          <el-option label="正常" :value="1" />
          <el-option label="冻结" :value="2" />
        </el-select>
        <el-button type="primary" @click="fetchData">搜索</el-button>
      </div>
    </el-card>

    <!-- Table -->
    <el-card>
      <el-table :data="users" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" align="center" />
        <el-table-column prop="username" label="用户名" min-width="150" />
        <el-table-column prop="phone" label="手机号" width="150" align="center">
          <template #default="{ row }">
            {{ row.phone ? maskPhone(row.phone) : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="余额" width="150" align="center">
          <template #default="{ row }">
            <span class="text-[#E4393C] font-medium">¥{{ formatPrice(row.balance) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="注册时间" width="180" align="center">
          <template #default="{ row }">
            {{ row.createTime ? formatDate(row.createTime) : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '正常' : '冻结' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              :type="row.status === 1 ? 'warning' : 'success'"
              link
              @click="handleToggleStatus(row)"
            >
              {{ row.status === 1 ? '冻结' : '解冻' }}
            </el-button>
            <el-button size="small" type="primary" link @click="openBalanceDialog(row)">余额调整</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="flex justify-end mt-4">
        <el-pagination
          v-model:current-page="pageNo"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="fetchData"
        />
      </div>
    </el-card>

    <!-- Balance Dialog -->
    <el-dialog v-model="balanceDialogVisible" title="余额调整" width="400px">
      <el-form :model="balanceForm" label-width="80px">
        <el-form-item label="用户名">
          <span>{{ balanceForm.username }}</span>
        </el-form-item>
        <el-form-item label="当前余额">
          <span class="text-[#E4393C] font-bold">¥{{ formatPrice(balanceForm.currentBalance) }}</span>
        </el-form-item>
        <el-form-item label="调整金额" required>
          <el-input-number v-model="balanceForm.amount" :step="1000" class="w-full" />
          <div class="text-xs text-gray-400 mt-1">正数充值，负数扣减</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="balanceDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjusting" @click="handleAdjustBalance">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { getMemberPage, updateMemberStatus, updateMemberBalance, type MemberInfo } from '@/api/admin/member'
import { formatPrice, formatDate } from '@/utils/format'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/admin/PageHeader.vue'
import type { PageResult } from '@/types'

const users = ref<MemberInfo[]>([])
const loading = ref(false)
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const searchText = ref('')
const searchStatus = ref<number | undefined>(undefined)

const balanceDialogVisible = ref(false)
const adjusting = ref(false)
const balanceForm = reactive({
  userId: 0,
  username: '',
  currentBalance: 0,
  amount: 1000,
})

function maskPhone(phone: string) {
  if (phone.length >= 7) {
    return phone.slice(0, 3) + '****' + phone.slice(-4)
  }
  return phone
}

function openBalanceDialog(row: MemberInfo) {
  balanceForm.userId = row.id
  balanceForm.username = row.username
  balanceForm.currentBalance = row.balance
  balanceForm.amount = 1000
  balanceDialogVisible.value = true
}

async function handleAdjustBalance() {
  adjusting.value = true
  try {
    await updateMemberBalance(balanceForm.userId, balanceForm.amount)
    ElMessage.success('余额调整成功')
    balanceDialogVisible.value = false
    fetchData()
  } finally {
    adjusting.value = false
  }
}

async function handleToggleStatus(row: MemberInfo) {
  const action = row.status === 1 ? '冻结' : '解冻'
  try {
    await ElMessageBox.confirm(`确定${action}用户 ${row.username} 吗？`, `${action}用户`, { type: 'warning' })
    await updateMemberStatus(row.id, row.status === 1 ? 2 : 1)
    ElMessage.success(`${action}成功`)
    fetchData()
  } catch {
    // 用户取消
  }
}

async function fetchData() {
  loading.value = true
  try {
    const res: PageResult<MemberInfo> = await getMemberPage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      keyword: searchText.value || undefined,
      status: searchStatus.value,
    })
    users.value = res.list || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

onMounted(() => fetchData())
</script>
