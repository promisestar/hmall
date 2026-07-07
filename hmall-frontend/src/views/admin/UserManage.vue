<template>
  <div>
    <el-card>
      <el-table :data="users" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" align="center" />
        <el-table-column prop="username" label="用户名" min-width="180" />
        <el-table-column label="余额" width="150" align="center">
          <template #default="{ row }">
            <span class="text-[#E4393C] font-medium">¥{{ formatPrice(row.balance) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="注册时间" width="180" align="center">
          <template #default="{ row }">
            {{ row.createTime ? formatDate(row.createTime) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default>
            <el-tag type="success" size="small">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openEditDialog(row)">编辑</el-button>
            <el-button size="small" type="warning" link @click="openBalanceDialog(row)">充值</el-button>
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

    <!-- Edit Dialog -->
    <el-dialog v-model="dialogVisible" title="编辑用户" width="460px">
      <el-form :model="editForm" label-width="80px" ref="editFormRef">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="editForm.username" disabled />
        </el-form-item>
        <el-form-item label="余额">
          <el-input-number v-model="editForm.balance" :min="0" class="w-full" disabled />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- Balance Dialog -->
    <el-dialog v-model="balanceDialogVisible" title="余额充值" width="400px">
      <el-form :model="balanceForm" label-width="80px">
        <el-form-item label="当前余额">
          <span class="text-[#E4393C] font-bold">¥{{ formatPrice(balanceForm.currentBalance) }}</span>
        </el-form-item>
        <el-form-item label="充值金额" required>
          <el-input-number v-model="balanceForm.amount" :min="1" :max="1000000" class="w-full" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="balanceDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="recharging" @click="handleRecharge">确认充值</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { formatPrice, formatDate } from '@/utils/format'
import { ElMessage } from 'element-plus'

interface UserRow {
  id: number
  username: string
  balance: number
  createTime?: string
}

const users = ref<UserRow[]>([])
const loading = ref(false)
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)

const dialogVisible = ref(false)
const editForm = reactive({ username: '', balance: 0 })

const balanceDialogVisible = ref(false)
const recharging = ref(false)
const balanceForm = reactive({ userId: 0, currentBalance: 0, amount: 1000 })

function openEditDialog(row: UserRow) {
  editForm.username = row.username
  editForm.balance = row.balance
  dialogVisible.value = true
}

function openBalanceDialog(row: UserRow) {
  balanceForm.userId = row.id
  balanceForm.currentBalance = row.balance
  balanceForm.amount = 1000
  balanceDialogVisible.value = true
}

async function handleRecharge() {
  recharging.value = true
  try {
    ElMessage.success(`已成功充值 ¥${balanceForm.amount} 元`)
    balanceDialogVisible.value = false
    fetchData()
  } catch {
    ElMessage.error('充值失败')
  } finally {
    recharging.value = false
  }
}

async function fetchData() {
  loading.value = true
  try {
    users.value = [
      { id: 1, username: 'admin', balance: 1000000, createTime: '2025-01-01 10:00:00' },
      { id: 2, username: 'test', balance: 50000, createTime: '2025-03-15 14:30:00' },
      { id: 3, username: 'user001', balance: 12000, createTime: '2025-05-20 09:00:00' },
    ]
    total.value = 3
  } finally {
    loading.value = false
  }
}

onMounted(() => fetchData())
</script>
