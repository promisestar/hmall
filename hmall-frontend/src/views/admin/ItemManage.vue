<template>
  <div>
    <!-- Search & Actions -->
    <el-card class="mb-4">
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-3">
          <el-input
            v-model="searchText"
            placeholder="搜索商品名称"
            clearable
            class="w-[240px]"
            @keyup.enter="fetchData"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button type="primary" @click="fetchData">搜索</el-button>
        </div>
        <div class="flex items-center gap-2">
          <el-button
            v-if="selectedIds.length"
            type="warning"
            size="small"
            @click="handleBatchPublish(1)"
          >批量上架</el-button>
          <el-button
            v-if="selectedIds.length"
            type="info"
            size="small"
            @click="handleBatchPublish(2)"
          >批量下架</el-button>
          <el-button
            v-if="selectedIds.length"
            type="danger"
            size="small"
            @click="handleBatchDelete"
          >批量删除</el-button>
          <el-button type="primary" @click="openAddDialog">
            <el-icon><Plus /></el-icon>
            新增商品
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- Table -->
    <el-card>
      <el-table
        :data="items"
        v-loading="loading"
        stripe
        border
        style="width: 100%"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="50" align="center" />
        <el-table-column prop="id" label="ID" width="80" align="center" />
        <el-table-column label="图片" width="100" align="center">
          <template #default="{ row }">
            <img :src="row.image || '/img/like_01.png'" class="w-16 h-16 object-cover rounded" />
          </template>
        </el-table-column>
        <el-table-column prop="name" label="商品名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="category" label="分类" width="120" align="center" />
        <el-table-column label="价格(元)" width="120" align="center">
          <template #default="{ row }">
            <span class="text-[#E4393C] font-medium">¥{{ formatPrice(row.price) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="stock" label="库存" width="100" align="center" />
        <el-table-column prop="sold" label="销量" width="100" align="center" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '上架' : '下架' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openEditDialog(row)">编辑</el-button>
            <el-button size="small" :type="row.status === 1 ? 'warning' : 'success'" link @click="toggleStatus(row)">
              {{ row.status === 1 ? '下架' : '上架' }}
            </el-button>
            <el-popconfirm title="确定删除该商品吗？" @confirm="handleDelete(row.id)">
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
    <el-dialog
      v-model="dialogVisible"
      :title="editingItem ? '编辑商品' : '新增商品'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form :model="form" label-width="80px" :rules="formRules" ref="formRef">
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="价格(元)" prop="price">
          <el-input-number v-model="form.price" :min="0.01" :precision="2" class="w-full" />
        </el-form-item>
        <el-form-item label="库存" prop="stock">
          <el-input-number v-model="form.stock" :min="0" class="w-full" />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-input v-model="form.category" />
        </el-form-item>
        <el-form-item label="品牌" prop="brand">
          <el-input v-model="form.brand" />
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="form.spec" />
        </el-form-item>
        <el-form-item label="图片URL">
          <el-input v-model="form.image" placeholder="/img/like_01.png" />
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
import { Search, Plus } from '@element-plus/icons-vue'
import {
  getAdminProductPage,
  createProduct,
  updateProduct,
  batchUpdatePublishStatus,
  deleteProducts,
} from '@/api/admin/product'
import { formatPrice } from '@/utils/format'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Item, PageResult } from '@/types'

const items = ref<Item[]>([])
const loading = ref(false)
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const searchText = ref('')
const selectedIds = ref<number[]>([])

const dialogVisible = ref(false)
const editingItem = ref<Item | null>(null)
const saving = ref(false)
const formRef = ref()

const form = reactive({
  name: '',
  price: 0,
  stock: 0,
  category: '',
  brand: '',
  spec: '',
  image: '',
})

const formRules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }],
  category: [{ required: true, message: '请输入分类', trigger: 'blur' }],
  brand: [{ required: true, message: '请输入品牌', trigger: 'blur' }],
}

function handleSelectionChange(rows: Item[]) {
  selectedIds.value = rows.map(r => r.id)
}

function openAddDialog() {
  editingItem.value = null
  Object.assign(form, { name: '', price: 0, stock: 0, category: '', brand: '', spec: '', image: '' })
  dialogVisible.value = true
}

function openEditDialog(item: Item) {
  editingItem.value = item
  Object.assign(form, {
    name: item.name,
    price: item.price / 100,
    stock: item.stock,
    category: item.category || '',
    brand: item.brand || '',
    spec: item.spec || '',
    image: item.image || '',
  })
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  const data: any = {
    name: form.name,
    price: Math.round(form.price * 100),
    stock: form.stock,
    category: form.category,
    brand: form.brand,
    spec: form.spec,
    image: form.image,
  }
  if (editingItem.value) data.id = editingItem.value.id

  try {
    if (editingItem.value) {
      await updateProduct(editingItem.value.id, data)
      ElMessage.success('更新成功')
    } else {
      await createProduct(data)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    fetchData()
  } finally {
    saving.value = false
  }
}

async function toggleStatus(item: Item) {
  try {
    await batchUpdatePublishStatus([item.id], item.status === 1 ? 2 : 1)
    ElMessage.success('状态已更新')
    fetchData()
  } catch {
    ElMessage.error('操作失败')
  }
}

async function handleDelete(id: number) {
  try {
    await deleteProducts([id])
    ElMessage.success('删除成功')
    fetchData()
  } catch {
    ElMessage.error('删除失败')
  }
}

async function handleBatchPublish(status: number) {
  try {
    await batchUpdatePublishStatus(selectedIds.value, status)
    ElMessage.success(`已${status === 1 ? '上架' : '下架'} ${selectedIds.value.length} 个商品`)
    fetchData()
  } catch {
    ElMessage.error('操作失败')
  }
}

async function handleBatchDelete() {
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selectedIds.value.length} 个商品吗？`, '批量删除', {
      type: 'warning',
    })
    await deleteProducts(selectedIds.value)
    ElMessage.success('批量删除成功')
    fetchData()
  } catch {
    // 用户取消
  }
}

async function fetchData() {
  loading.value = true
  try {
    const res: PageResult<Item> = await getAdminProductPage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      key: searchText.value || undefined,
    })
    items.value = res.list || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

onMounted(() => fetchData())
</script>
