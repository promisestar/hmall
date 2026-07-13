<template>
  <PortalLayout :show-search="false">
    <div class="container-main py-6">
      <!-- Header -->
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-bold border-l-4 border-[#E4393C] pl-3">收货地址管理</h2>
        <button
          @click="openAddDialog"
          class="px-4 py-2 border border-[#E4393C] text-[#E4393C] text-sm rounded hover:bg-[#E4393C] hover:text-white transition-colors"
        >
          + 新增收货地址
        </button>
      </div>

      <!-- Loading -->
      <div v-if="loading" class="bg-white rounded-lg shadow-sm p-20 text-center text-gray-400">
        加载中...
      </div>

      <!-- Empty -->
      <div v-else-if="addresses.length === 0" class="bg-white rounded-lg shadow-sm p-20 text-center">
        <p class="text-gray-400 mb-4">暂无收货地址</p>
        <button
          @click="openAddDialog"
          class="px-6 py-2 bg-[#E4393C] text-white text-sm rounded hover:bg-[#C81623] transition-colors"
        >
          添加地址
        </button>
      </div>

      <!-- Address List -->
      <div v-else class="space-y-3">
        <div
          v-for="addr in addresses"
          :key="addr.id"
          class="bg-white rounded-lg shadow-sm p-5 flex items-start gap-4 hover:shadow-md transition-shadow"
        >
          <!-- Content -->
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-3 mb-2">
              <span class="font-bold text-gray-800">{{ addr.contact }}</span>
              <span class="text-gray-500 text-sm">{{ addr.mobile }}</span>
              <span
                v-if="addr.isDefault === 1"
                class="px-2 py-0.5 bg-[#E4393C] text-white text-xs rounded"
              >
                默认
              </span>
            </div>
            <p class="text-sm text-gray-600">
              {{ addr.province }}{{ addr.city }}{{ addr.town }} {{ addr.street }}
            </p>
          </div>

          <!-- Actions -->
          <div class="flex items-center gap-4 text-sm flex-shrink-0">
            <button
              v-if="addr.isDefault !== 1"
              @click="handleSetDefault(addr.id)"
              class="text-gray-500 hover:text-[#E4393C] transition-colors"
            >
              设为默认
            </button>
            <button
              @click="openEditDialog(addr)"
              class="text-gray-500 hover:text-[#E4393C] transition-colors"
            >
              编辑
            </button>
            <button
              @click="handleDelete(addr)"
              class="text-gray-500 hover:text-[#E4393C] transition-colors"
            >
              删除
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Add/Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingAddress ? '编辑收货地址' : '新增收货地址'"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="90px">
        <el-form-item label="联系人" prop="contact">
          <el-input v-model="formData.contact" placeholder="请输入收货人姓名" maxlength="20" />
        </el-form-item>
        <el-form-item label="手机号" prop="mobile">
          <el-input v-model="formData.mobile" placeholder="请输入手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="省" prop="province">
          <el-input v-model="formData.province" placeholder="如：广东省" />
        </el-form-item>
        <el-form-item label="市" prop="city">
          <el-input v-model="formData.city" placeholder="如：深圳市" />
        </el-form-item>
        <el-form-item label="区/县" prop="town">
          <el-input v-model="formData.town" placeholder="如：南山区" />
        </el-form-item>
        <el-form-item label="详细地址" prop="street">
          <el-input
            v-model="formData.street"
            type="textarea"
            :rows="2"
            placeholder="请输入详细地址（街道、门牌号等）"
            maxlength="100"
          />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="formData.isDefault" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="flex justify-end gap-3">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" @click="handleSubmit" :loading="submitting">
            确认
          </el-button>
        </div>
      </template>
    </el-dialog>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import PortalLayout from './PortalLayout.vue'
import {
  getAddressList,
  addAddress,
  updateAddress,
  deleteAddress,
  setDefaultAddress,
} from '@/api/address'
import type { Address } from '@/types'

const addresses = ref<Address[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const editingAddress = ref<Address | null>(null)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const formData = reactive({
  contact: '',
  mobile: '',
  province: '',
  city: '',
  town: '',
  street: '',
  isDefault: 0 as number,
})

const formRules: FormRules = {
  contact: [{ required: true, message: '请输入收货人姓名', trigger: 'blur' }],
  mobile: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '请输入正确的手机号', trigger: 'blur' },
  ],
  province: [{ required: true, message: '请输入省份', trigger: 'blur' }],
  city: [{ required: true, message: '请输入城市', trigger: 'blur' }],
  town: [{ required: true, message: '请输入区/县', trigger: 'blur' }],
  street: [{ required: true, message: '请输入详细地址', trigger: 'blur' }],
}

onMounted(() => {
  fetchAddresses()
})

async function fetchAddresses() {
  loading.value = true
  try {
    addresses.value = await getAddressList()
  } catch {
    ElMessage.error('获取地址列表失败')
  } finally {
    loading.value = false
  }
}

function resetForm() {
  formData.contact = ''
  formData.mobile = ''
  formData.province = ''
  formData.city = ''
  formData.town = ''
  formData.street = ''
  formData.isDefault = 0
}

function openAddDialog() {
  editingAddress.value = null
  resetForm()
  dialogVisible.value = true
}

function openEditDialog(addr: Address) {
  editingAddress.value = addr
  formData.contact = addr.contact
  formData.mobile = addr.mobile
  formData.province = addr.province
  formData.city = addr.city
  formData.town = addr.town
  formData.street = addr.street
  formData.isDefault = addr.isDefault
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const data = {
        contact: formData.contact,
        mobile: formData.mobile,
        province: formData.province,
        city: formData.city,
        town: formData.town,
        street: formData.street,
        isDefault: formData.isDefault,
      }
      if (editingAddress.value) {
        await updateAddress(editingAddress.value.id, data)
        ElMessage.success('修改成功')
      } else {
        await addAddress(data)
        ElMessage.success('添加成功')
      }
      dialogVisible.value = false
      await fetchAddresses()
    } catch {
      ElMessage.error(editingAddress.value ? '修改失败' : '添加失败')
    } finally {
      submitting.value = false
    }
  })
}

async function handleDelete(addr: Address) {
  try {
    await ElMessageBox.confirm(
      `确认删除 ${addr.contact} 的收货地址吗？`,
      '提示',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' },
    )
    await deleteAddress(addr.id)
    ElMessage.success('删除成功')
    await fetchAddresses()
  } catch (e) {
    // 用户取消删除时也会 reject，静默处理
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

async function handleSetDefault(id: number) {
  try {
    await setDefaultAddress(id)
    ElMessage.success('已设为默认')
    await fetchAddresses()
  } catch {
    ElMessage.error('设置失败')
  }
}
</script>
