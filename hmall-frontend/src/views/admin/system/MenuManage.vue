<template>
  <div>
    <el-card>
      <div class="mb-4">
        <el-button type="primary" @click="openAddDialog(0)">
          <el-icon><Plus /></el-icon> 新增菜单
        </el-button>
      </div>
      <el-table
        :data="menuTree"
        v-loading="loading"
        row-key="id"
        :tree-props="{ children: 'children' }"
        border
        default-expand-all
        style="width: 100%"
      >
        <el-table-column prop="title" label="菜单名称" min-width="200" />
        <el-table-column prop="path" label="前端路由" width="200" />
        <el-table-column prop="icon" label="图标" width="120" align="center" />
        <el-table-column prop="sort" label="排序" width="80" align="center" />
        <el-table-column label="操作" width="200" align="center">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openAddDialog(row.id)">新增子菜单</el-button>
            <el-button size="small" type="warning" link @click="openEditDialog(row)">编辑</el-button>
            <el-popconfirm title="确定删除该菜单吗？" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button size="small" type="danger" link>删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑菜单' : '新增菜单'" width="500px" :close-on-click-modal="false">
      <el-form :model="form" label-width="80px" :rules="formRules" ref="formRef">
        <el-form-item label="菜单名称" prop="title">
          <el-input v-model="form.title" />
        </el-form-item>
        <el-form-item label="父级菜单">
          <el-tree-select
            v-model="form.parentId"
            :data="menuTreeForSelect"
            :props="{ label: 'title', children: 'children', value: 'id' }"
            check-strictly
            placeholder="顶级菜单"
            clearable
            class="w-full"
          />
        </el-form-item>
        <el-form-item label="前端路由">
          <el-input v-model="form.path" placeholder="/admin/xxx" />
        </el-form-item>
        <el-form-item label="路由名称">
          <el-input v-model="form.name" placeholder="RouteName" />
        </el-form-item>
        <el-form-item label="图标">
          <el-input v-model="form.icon" placeholder="Element Plus 图标名" />
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
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { getMenuTree, createMenu, updateMenu, deleteMenu } from '@/api/admin/menu'
import { ElMessage } from 'element-plus'
import type { AdminMenu } from '@/types/admin'

const menuTree = ref<AdminMenu[]>([])
const loading = ref(false)

const dialogVisible = ref(false)
const editing = ref(false)
const saving = ref(false)
const formRef = ref()

const form = reactive({
  id: 0,
  parentId: 0,
  title: '',
  name: '',
  path: '',
  icon: '',
  sort: 0,
  level: 1,
  hidden: 0,
})

const formRules = {
  title: [{ required: true, message: '请输入菜单名称', trigger: 'blur' }],
}

const menuTreeForSelect = computed(() => {
  const root = { id: 0, title: '顶级菜单', children: menuTree.value }
  return [root]
})

function openAddDialog(parentId: number) {
  editing.value = false
  Object.assign(form, { id: 0, parentId, title: '', name: '', path: '', icon: '', sort: 0, level: 1, hidden: 0 })
  dialogVisible.value = true
}

function openEditDialog(row: AdminMenu) {
  editing.value = true
  Object.assign(form, {
    id: row.id,
    parentId: row.parentId,
    title: row.title,
    name: row.name || '',
    path: row.path || '',
    icon: row.icon || '',
    sort: row.sort,
    level: row.level,
    hidden: row.hidden,
  })
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const data = {
      parentId: form.parentId || 0,
      title: form.title,
      name: form.name,
      path: form.path,
      icon: form.icon,
      sort: form.sort,
      level: form.level,
      hidden: form.hidden,
    }
    if (editing.value) {
      await updateMenu(form.id, data)
      ElMessage.success('更新成功')
    } else {
      await createMenu(data)
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
    await deleteMenu(id)
    ElMessage.success('删除成功')
    fetchData()
  } catch {
    ElMessage.error('删除失败')
  }
}

async function fetchData() {
  loading.value = true
  try {
    menuTree.value = await getMenuTree()
  } finally {
    loading.value = false
  }
}

onMounted(() => fetchData())
</script>
