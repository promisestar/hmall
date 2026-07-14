<template>
  <div>
    <!-- Search -->
    <el-card class="mb-4">
      <div class="flex items-center gap-3">
        <el-input v-model="searchText" placeholder="搜索资源名称" clearable class="w-[200px]" @keyup.enter="fetchData" />
        <el-select v-model="searchCategoryId" placeholder="全部分类" clearable class="w-[150px]">
          <el-option v-for="cat in categories" :key="cat.id" :label="cat.name" :value="cat.id" />
        </el-select>
        <el-button type="primary" @click="fetchData">搜索</el-button>
      </div>
    </el-card>

    <!-- Table -->
    <el-card>
      <div class="mb-4">
        <el-button type="primary" @click="openAddDialog">
          <el-icon><Plus /></el-icon> 新增资源
        </el-button>
      </div>
      <el-table :data="resources" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" align="center" />
        <el-table-column prop="name" label="资源名称" width="180" />
        <el-table-column prop="url" label="URL" min-width="200" show-overflow-tooltip />
        <el-table-column prop="method" label="方法" width="80" align="center" />
        <el-table-column label="分类" width="120" align="center">
          <template #default="{ row }">
            {{ getCategoryName(row.categoryId) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openEditDialog(row)">编辑</el-button>
            <el-popconfirm title="确定删除该资源吗？" @confirm="handleDelete(row.id)">
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
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑资源' : '新增资源'" width="500px" :close-on-click-modal="false">
      <el-form :model="form" label-width="80px" :rules="formRules" ref="formRef">
        <el-form-item label="资源名称" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="URL" prop="url">
          <el-input v-model="form.url" placeholder="/admin/product/**" />
        </el-form-item>
        <el-form-item label="HTTP方法">
          <el-select v-model="form.method" placeholder="不限" clearable class="w-full">
            <el-option label="GET" value="GET" />
            <el-option label="POST" value="POST" />
            <el-option label="PUT" value="PUT" />
            <el-option label="DELETE" value="DELETE" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.categoryId" placeholder="请选择" clearable class="w-full">
            <el-option v-for="cat in categories" :key="cat.id" :label="cat.name" :value="cat.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
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
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { getResourcePage, createResource, updateResource, deleteResource, getResourceCategories } from '@/api/admin/resource'
import { ElMessage } from 'element-plus'
import type { Resource, ResourceCategory } from '@/types/admin'
import type { PageResult } from '@/types'

const resources = ref<Resource[]>([])
const loading = ref(false)
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const searchText = ref('')
const searchCategoryId = ref<number | undefined>(undefined)
const categories = ref<ResourceCategory[]>([])

const dialogVisible = ref(false)
const editing = ref(false)
const saving = ref(false)
const formRef = ref()

const form = reactive({
  id: 0,
  name: '',
  url: '',
  method: '',
  description: '',
  categoryId: undefined as number | undefined,
})

const formRules = {
  name: [{ required: true, message: '请输入资源名称', trigger: 'blur' }],
  url: [{ required: true, message: '请输入URL', trigger: 'blur' }],
}

function getCategoryName(categoryId?: number) {
  if (!categoryId) return '—'
  return categories.value.find(c => c.id === categoryId)?.name || '—'
}

function openAddDialog() {
  editing.value = false
  Object.assign(form, { id: 0, name: '', url: '', method: '', description: '', categoryId: undefined })
  dialogVisible.value = true
}

function openEditDialog(row: Resource) {
  editing.value = true
  Object.assign(form, {
    id: row.id,
    name: row.name,
    url: row.url,
    method: row.method || '',
    description: row.description || '',
    categoryId: row.categoryId,
  })
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const data = {
      name: form.name,
      url: form.url,
      method: form.method,
      description: form.description,
      categoryId: form.categoryId,
    }
    if (editing.value) {
      await updateResource(form.id, data)
      ElMessage.success('更新成功')
    } else {
      await createResource(data)
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
    await deleteResource(id)
    ElMessage.success('删除成功')
    fetchData()
  } catch {
    ElMessage.error('删除失败')
  }
}

async function fetchData() {
  loading.value = true
  try {
    const res: PageResult<Resource> = await getResourcePage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      keyword: searchText.value || undefined,
      categoryId: searchCategoryId.value,
    })
    resources.value = res.list || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await fetchData()
  categories.value = await getResourceCategories()
})
</script>
