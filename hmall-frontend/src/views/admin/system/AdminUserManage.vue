<template>
  <div>
    <PageHeader title="管理员管理" description="后台账号与角色分配" />

    <!-- Search -->
    <el-card class="mb-4" shadow="never">
      <div class="flex items-center justify-between">
        <el-input v-model="searchText" placeholder="搜索用户名/昵称" clearable class="w-[240px]" @keyup.enter="fetchData">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="fetchData">搜索</el-button>
      </div>
    </el-card>

    <!-- Table -->
    <el-card>
      <div class="mb-4">
        <el-button type="primary" @click="openAddDialog">
          <el-icon><Plus /></el-icon> 新增管理员
        </el-button>
      </div>
      <el-table :data="users" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" align="center" />
        <el-table-column prop="username" label="用户名" width="150" />
        <el-table-column prop="nickName" label="昵称" width="150" />
        <el-table-column prop="email" label="邮箱" width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" align="center" />
        <el-table-column label="操作" width="250" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openEditDialog(row)">编辑</el-button>
            <el-button size="small" type="info" link @click="openRoleDialog(row)">分配角色</el-button>
            <el-button
              size="small"
              :type="row.status === 1 ? 'warning' : 'success'"
              link
              @click="handleToggleStatus(row)"
            >{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
            <el-popconfirm v-if="row.id !== 1" title="确定删除该管理员吗？" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button size="small" type="danger" link>删除</el-button>
              </template>
            </el-popconfirm>
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

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑管理员' : '新增管理员'" width="500px" :close-on-click-modal="false">
      <el-form :model="form" label-width="80px" :rules="formRules" ref="formRef">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="editing" />
        </el-form-item>
        <el-form-item v-if="!editing" label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickName" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- Role Dialog -->
    <el-dialog v-model="roleDialogVisible" title="分配角色" width="400px">
      <div class="mb-2 text-gray-600">管理员：{{ roleForm.username }}</div>
      <el-checkbox-group v-model="roleForm.roleIds">
        <div v-for="role in allRoles" :key="role.id" class="py-1">
          <el-checkbox :label="role.id">{{ role.name }} - {{ role.description }}</el-checkbox>
        </div>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingRoles" @click="handleSaveRoles">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Search, Plus } from '@element-plus/icons-vue'
import {
  getAdminUserPage,
  createAdminUser,
  updateAdminUser,
  deleteAdminUser,
  updateAdminUserStatus,
  allocAdminRoles,
  getAdminRoles,
} from '@/api/admin/adminUser'
import { getAllRoles } from '@/api/admin/role'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/admin/PageHeader.vue'
import type { AdminUser, Role } from '@/types/admin'
import type { PageResult } from '@/types'

const users = ref<AdminUser[]>([])
const loading = ref(false)
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const searchText = ref('')
const allRoles = ref<Role[]>([])

const dialogVisible = ref(false)
const editing = ref(false)
const saving = ref(false)
const formRef = ref()

const form = reactive({
  id: 0,
  username: '',
  password: '',
  nickName: '',
  email: '',
  note: '',
  status: 1,
})

const formRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const roleDialogVisible = ref(false)
const savingRoles = ref(false)
const roleForm = reactive({ adminId: 0, username: '', roleIds: [] as number[] })

function openAddDialog() {
  editing.value = false
  Object.assign(form, { id: 0, username: '', password: '', nickName: '', email: '', note: '', status: 1 })
  dialogVisible.value = true
}

function openEditDialog(row: AdminUser) {
  editing.value = true
  Object.assign(form, {
    id: row.id,
    username: row.username,
    password: '',
    nickName: row.nickName || '',
    email: row.email || '',
    note: row.note || '',
    status: row.status,
  })
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editing.value) {
      await updateAdminUser(form.id, { nickName: form.nickName, email: form.email, note: form.note, status: form.status })
      ElMessage.success('更新成功')
    } else {
      await createAdminUser({ username: form.username, password: form.password, nickName: form.nickName, email: form.email, note: form.note, status: form.status })
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    fetchData()
  } finally {
    saving.value = false
  }
}

async function handleDelete(id: number) {
  try {
    await deleteAdminUser(id)
    ElMessage.success('删除成功')
    fetchData()
  } catch {
    ElMessage.error('删除失败')
  }
}

async function handleToggleStatus(row: AdminUser) {
  try {
    await updateAdminUserStatus(row.id, row.status === 1 ? 0 : 1)
    ElMessage.success('状态已更新')
    fetchData()
  } catch {
    ElMessage.error('操作失败')
  }
}

async function openRoleDialog(row: AdminUser) {
  roleForm.adminId = row.id
  roleForm.username = row.username
  const roleIds = await getAdminRoles(row.id)
  roleForm.roleIds = roleIds || []
  roleDialogVisible.value = true
}

async function handleSaveRoles() {
  savingRoles.value = true
  try {
    await allocAdminRoles(roleForm.adminId, roleForm.roleIds)
    ElMessage.success('角色分配成功')
    roleDialogVisible.value = false
  } finally {
    savingRoles.value = false
  }
}

async function fetchData() {
  loading.value = true
  try {
    const res: PageResult<AdminUser> = await getAdminUserPage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      keyword: searchText.value || undefined,
    })
    users.value = res.list || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await fetchData()
  allRoles.value = await getAllRoles()
})
</script>
