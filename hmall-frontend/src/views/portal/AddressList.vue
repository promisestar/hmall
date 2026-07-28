<template>
  <PortalLayout :show-search="false">
    <div class="container-main py-5">
      <div class="flex items-center justify-between mb-4">
        <h2 class="section-title">收货地址管理</h2>
        <button class="btn-primary text-sm" @click="openDialog()">
          <Plus class="w-4 h-4" />新增地址
        </button>
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="flex justify-center py-24">
        <el-icon class="is-loading text-3xl text-[#E4393C]"><Loading /></el-icon>
      </div>

      <!-- 空状态 -->
      <div v-else-if="addressList.length === 0" class="page-card p-24 text-center">
        <div class="w-20 h-20 mx-auto mb-5 rounded-full bg-gray-50 flex items-center justify-center">
          <MapPin class="w-10 h-10 text-gray-300" />
        </div>
        <p class="text-gray-500 mb-1">暂无收货地址</p>
        <p class="text-xs text-gray-400 mb-6">新增收货地址，下单更便捷</p>
        <button class="btn-primary" @click="openDialog()">新增地址</button>
      </div>

      <!-- 地址列表 -->
      <div v-else class="grid grid-cols-3 gap-4">
        <div
          v-for="addr in addressList"
          :key="addr.id"
          class="page-card p-5 relative group hover:shadow-lift transition-all"
        >
          <div class="flex items-center gap-2 mb-2.5">
            <span class="font-semibold text-gray-800">{{ addr.contact }}</span>
            <span class="text-xs text-gray-400">{{ addr.mobile }}</span>
            <span v-if="addr.isDefault === 1" class="ml-auto px-2 py-0.5 bg-[#E4393C] text-white text-[10px] rounded-full font-medium">
              默认
            </span>
          </div>
          <p class="text-[13px] text-gray-500 leading-relaxed min-h-[40px]">
            {{ addr.province }}{{ addr.city }}{{ addr.town }} {{ addr.street }}
          </p>
          <div class="flex justify-end gap-1 mt-3 pt-3 border-t border-gray-50 opacity-0 group-hover:opacity-100 transition-opacity">
            <button
              v-if="addr.isDefault !== 1"
              @click="handleSetDefault(addr)"
              class="flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs text-gray-400 hover:text-[#E4393C] hover:bg-red-50 transition-colors"
            >
              <Star class="w-3.5 h-3.5" />设为默认
            </button>
            <button
              @click="openDialog(addr)"
              class="flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs text-gray-400 hover:text-[#409EFF] hover:bg-blue-50 transition-colors"
            >
              <Pencil class="w-3.5 h-3.5" />编辑
            </button>
            <button
              @click="handleDelete(addr)"
              class="flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs text-gray-400 hover:text-[#E4393C] hover:bg-red-50 transition-colors"
            >
              <Trash2 class="w-3.5 h-3.5" />删除
            </button>
          </div>
        </div>
      </div>

      <!-- 新增/编辑地址弹窗 -->
      <el-dialog
        v-model="dialogVisible"
        :title="isEdit ? '编辑收货地址' : '新增收货地址'"
        width="520px"
      >
        <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" size="large">
          <el-form-item label="联系人" prop="contact">
            <el-input v-model="form.contact" placeholder="请输入联系人姓名" />
          </el-form-item>
          <el-form-item label="手机号" prop="mobile">
            <el-input v-model="form.mobile" placeholder="请输入手机号" maxlength="11" />
          </el-form-item>
          <el-form-item label="省份" prop="province">
            <el-input v-model="form.province" placeholder="如：广东省" />
          </el-form-item>
          <el-form-item label="城市" prop="city">
            <el-input v-model="form.city" placeholder="如：深圳市" />
          </el-form-item>
          <el-form-item label="区/县" prop="town">
            <el-input v-model="form.town" placeholder="如：南山区" />
          </el-form-item>
          <el-form-item label="详细地址" prop="street">
            <el-input
              v-model="form.street"
              type="textarea"
              :rows="2"
              placeholder="街道、楼牌号等详细信息"
            />
          </el-form-item>
          <el-form-item label="设为默认">
            <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dialogVisible = false">取 消</el-button>
          <el-button type="primary" @click="handleSave" :loading="saving">确 定</el-button>
        </template>
      </el-dialog>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Plus, MapPin, Star, Pencil, Trash2 } from 'lucide-vue-next'
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

// 表单类型：新增时无 id / userId
type AddressForm = Omit<Address, 'id' | 'userId'> & { id?: number }

const addressList = ref<Address[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const emptyForm: AddressForm = {
  contact: '',
  mobile: '',
  province: '',
  city: '',
  town: '',
  street: '',
  isDefault: 0,
}

const form = reactive<AddressForm>({ ...emptyForm })

const rules: FormRules = {
  contact: [{ required: true, message: '请输入联系人姓名', trigger: 'blur' }],
  mobile: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  province: [{ required: true, message: '请输入省份', trigger: 'blur' }],
  city: [{ required: true, message: '请输入城市', trigger: 'blur' }],
  town: [{ required: true, message: '请输入区/县', trigger: 'blur' }],
  street: [{ required: true, message: '请输入详细地址', trigger: 'blur' }],
}

async function fetchAddresses() {
  loading.value = true
  try {
    addressList.value = await getAddressList()
  } catch {
    addressList.value = []
  } finally {
    loading.value = false
  }
}

function openDialog(addr?: Address) {
  if (addr) {
    isEdit.value = true
    Object.assign(form, {
      id: addr.id,
      contact: addr.contact,
      mobile: addr.mobile,
      province: addr.province,
      city: addr.city,
      town: addr.town,
      street: addr.street,
      isDefault: addr.isDefault,
      notes: addr.notes,
    })
  } else {
    isEdit.value = false
    Object.assign(form, { ...emptyForm })
  }
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const { id, ...data } = form
    if (isEdit.value && id) {
      await updateAddress(id, data)
      ElMessage.success('地址更新成功')
    } else {
      await addAddress(data)
      ElMessage.success('地址添加成功')
    }
    dialogVisible.value = false
    await fetchAddresses()
  } catch {
    ElMessage.error('保存失败，请重试')
  } finally {
    saving.value = false
  }
}

async function handleDelete(addr: Address) {
  try {
    await ElMessageBox.confirm(
      `确定删除收货地址"${addr.contact} ${addr.mobile}"吗？`,
      '删除确认',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
    await deleteAddress(addr.id)
    ElMessage.success('删除成功')
    await fetchAddresses()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

async function handleSetDefault(addr: Address) {
  try {
    await setDefaultAddress(addr.id)
    ElMessage.success('已设为默认地址')
    await fetchAddresses()
  } catch {
    ElMessage.error('设置失败')
  }
}

onMounted(() => {
  fetchAddresses()
})
</script>
