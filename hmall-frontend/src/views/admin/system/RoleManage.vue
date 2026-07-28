<template>
  <div>
    <PageHeader title="角色管理" description="角色权限与菜单资源配置" />

    <!-- Search -->
    <el-card class="mb-4" shadow="never">
      <div class="flex items-center justify-between">
        <el-input v-model="searchText" placeholder="搜索角色名称" clearable class="w-[240px]" @keyup.enter="fetchData">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="fetchData">搜索</el-button>
      </div>
    </el-card>

    <!-- Table -->
    <el-card>
      <div class="mb-4">
        <el-button type="primary" @click="openAddDialog">
          <el-icon><Plus /></el-icon> 新增角色
        </el-button>
      </div>
      <el-table :data="roles" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" align="center" />
        <el-table-column prop="name" label="角色名称" width="180" />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column prop="adminCount" label="管理员数" width="100" align="center" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openEditDialog(row)">编辑</el-button>
            <el-button size="small" type="info" link @click="openMenuDialog(row)">分配菜单</el-button>
            <el-button size="small" type="warning" link @click="openResourceDialog(row)">分配资源</el-button>
            <el-popconfirm v-if="row.id !== 1" title="确定删除该角色吗？" @confirm="handleDelete(row.id)">
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
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑角色' : '新增角色'" width="500px" :close-on-click-modal="false">
      <el-form :model="form" label-width="80px" :rules="formRules" ref="formRef">
        <el-form-item label="角色名称" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- Menu Dialog -->
    <el-dialog v-model="menuDialogVisible" title="分配菜单" width="400px">
      <div class="mb-2 text-gray-600">角色：{{ menuForm.roleName }}</div>
      <el-tree
        ref="menuTreeRef"
        :data="menuTreeData"
        :props="{ label: 'title', children: 'children' }"
        node-key="id"
        show-checkbox
        :default-checked-keys="menuForm.menuIds"
      />
      <template #footer>
        <el-button @click="menuDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingMenus" @click="handleSaveMenus">确认</el-button>
      </template>
    </el-dialog>

    <!-- Resource Dialog -->
    <el-dialog v-model="resourceDialogVisible" title="分配资源" width="500px">
      <div class="mb-2 text-gray-600">角色：{{ resourceForm.roleName }}</div>
      <el-checkbox-group v-model="resourceForm.resourceIds">
        <div v-for="res in allResources" :key="res.id" class="py-1">
          <el-checkbox :label="res.id">{{ res.name }} - {{ res.url }}</el-checkbox>
        </div>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="resourceDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingResources" @click="handleSaveResources">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Search, Plus } from '@element-plus/icons-vue'
import {
  getRolePage,
  createRole,
  updateRole,
  deleteRoles,
  getRoleMenus,
  getRoleResources,
  allocRoleMenus,
  allocRoleResources,
} from '@/api/admin/role'
import { getMenuTree } from '@/api/admin/menu'
import { getAllResources } from '@/api/admin/resource'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/admin/PageHeader.vue'
import type { Role, Resource, AdminMenu } from '@/types/admin'
import type { PageResult } from '@/types'

const roles = ref<Role[]>([])
const loading = ref(false)
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const searchText = ref('')

const dialogVisible = ref(false)
const editing = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: 0, name: '', description: '', sort: 0 })

const formRules = {
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
}

const menuDialogVisible = ref(false)
const savingMenus = ref(false)
const menuTreeRef = ref()
const menuTreeData = ref<AdminMenu[]>([])
const menuForm = reactive({ roleId: 0, roleName: '', menuIds: [] as number[] })

const resourceDialogVisible = ref(false)
const savingResources = ref(false)
const allResources = ref<Resource[]>([])
const resourceForm = reactive({ roleId: 0, roleName: '', resourceIds: [] as number[] })

function openAddDialog() {
  editing.value = false
  Object.assign(form, { id: 0, name: '', description: '', sort: 0 })
  dialogVisible.value = true
}

function openEditDialog(row: Role) {
  editing.value = true
  Object.assign(form, { id: row.id, name: row.name, description: row.description || '', sort: row.sort })
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editing.value) {
      await updateRole(form.id, { name: form.name, description: form.description, sort: form.sort })
      ElMessage.success('更新成功')
    } else {
      await createRole({ name: form.name, description: form.description, sort: form.sort, status: 1, adminCount: 0 })
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
    await deleteRoles([id])
    ElMessage.success('删除成功')
    fetchData()
  } catch {
    ElMessage.error('删除失败')
  }
}

async function openMenuDialog(row: Role) {
  menuForm.roleId = row.id
  menuForm.roleName = row.name
  const menuIds = await getRoleMenus(row.id)
  menuForm.menuIds = menuIds || []
  menuDialogVisible.value = true
}

async function handleSaveMenus() {
  savingMenus.value = true
  try {
    const checkedKeys = menuTreeRef.value?.getCheckedKeys() || []
    const halfCheckedKeys = menuTreeRef.value?.getHalfCheckedKeys() || []
    const allKeys = [...checkedKeys, ...halfCheckedKeys]
    await allocRoleMenus(menuForm.roleId, allKeys)
    ElMessage.success('菜单分配成功')
    menuDialogVisible.value = false
  } finally {
    savingMenus.value = false
  }
}

async function openResourceDialog(row: Role) {
  resourceForm.roleId = row.id
  resourceForm.roleName = row.name
  const resourceIds = await getRoleResources(row.id)
  resourceForm.resourceIds = resourceIds || []
  resourceDialogVisible.value = true
}

async function handleSaveResources() {
  savingResources.value = true
  try {
    await allocRoleResources(resourceForm.roleId, resourceForm.resourceIds)
    ElMessage.success('资源分配成功')
    resourceDialogVisible.value = false
  } finally {
    savingResources.value = false
  }
}

async function fetchData() {
  loading.value = true
  try {
    const res: PageResult<Role> = await getRolePage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      keyword: searchText.value || undefined,
    })
    roles.value = res.list || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await fetchData()
  menuTreeData.value = await getMenuTree()
  allResources.value = await getAllResources()
})
</script>
