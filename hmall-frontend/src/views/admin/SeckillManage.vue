<template>
  <div>
    <el-tabs v-model="activeTab" @tab-change="handleTabChange">
      <!-- ==================== Tab 1: 活动管理 ==================== -->
      <el-tab-pane label="活动管理" name="promotion">
        <el-card class="mb-4">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-3">
              <el-input v-model="promoSearch.title" placeholder="活动标题" clearable class="w-[200px]" @keyup.enter="promoPageNo = 1; fetchPromotions()" />
              <el-select v-model="promoSearch.status" placeholder="状态" clearable class="w-[120px]">
                <el-option label="未开始" :value="0" />
                <el-option label="进行中" :value="1" />
                <el-option label="已结束" :value="2" />
              </el-select>
              <el-button type="primary" @click="promoPageNo = 1; fetchPromotions()">搜索</el-button>
            </div>
            <el-button type="primary" @click="openPromoDialog()">
              <el-icon><Plus /></el-icon> 新增活动
            </el-button>
          </div>
        </el-card>

        <el-card>
          <el-table :data="promotions" v-loading="promoLoading" stripe border style="width: 100%">
            <el-table-column prop="id" label="ID" width="70" align="center" />
            <el-table-column prop="title" label="活动标题" min-width="180" show-overflow-tooltip />
            <el-table-column prop="startDate" label="开始日期" width="120" align="center" />
            <el-table-column prop="endDate" label="结束日期" width="120" align="center" />
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sessionCount" label="场次数" width="90" align="center" />
            <el-table-column prop="productCount" label="商品数" width="90" align="center" />
            <el-table-column label="创建时间" width="170" align="center">
              <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="160" align="center" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" link @click="openPromoDialog(row)">编辑</el-button>
                <el-popconfirm title="删除活动将级联删除其下场次和商品关联，确定？" @confirm="handleDeletePromotion(row.id)">
                  <template #reference>
                    <el-button size="small" type="danger" link>删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
          <div class="flex justify-end mt-4">
            <el-pagination v-model:current-page="promoPageNo" :page-size="promoPageSize" :total="promoTotal" layout="total, prev, pager, next" background @current-change="fetchPromotions" />
          </div>
        </el-card>

        <el-dialog v-model="promoDialogVisible" :title="promoEditing ? '编辑活动' : '新增活动'" width="480px" :close-on-click-modal="false">
          <el-form :model="promoForm" label-width="90px" :rules="promoRules" ref="promoFormRef">
            <el-form-item label="活动标题" prop="title">
              <el-input v-model="promoForm.title" />
            </el-form-item>
            <el-form-item label="开始日期" prop="startDate">
              <el-date-picker v-model="promoForm.startDate" type="date" value-format="YYYY-MM-DD" class="w-full" />
            </el-form-item>
            <el-form-item label="结束日期" prop="endDate">
              <el-date-picker v-model="promoForm.endDate" type="date" value-format="YYYY-MM-DD" class="w-full" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="promoDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="promoSaving" @click="handleSavePromotion">保存</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ==================== Tab 2: 场次管理 ==================== -->
      <el-tab-pane label="场次管理" name="session">
        <el-card class="mb-4">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-3">
              <el-select v-model="sessionSearch.promotionId" placeholder="选择活动筛选" clearable class="w-[220px]" @change="sessionPageNo = 1; fetchSessions()">
                <el-option v-for="p in promotionOptions" :key="p.id" :label="p.title" :value="p.id" />
              </el-select>
              <el-button type="primary" @click="sessionPageNo = 1; fetchSessions()">搜索</el-button>
            </div>
            <el-button type="primary" @click="openSessionDialog()">
              <el-icon><Plus /></el-icon> 新增场次
            </el-button>
          </div>
        </el-card>

        <el-card>
          <el-table :data="sessions" v-loading="sessionLoading" stripe border style="width: 100%">
            <el-table-column prop="id" label="ID" width="70" align="center" />
            <el-table-column prop="promotionTitle" label="所属活动" min-width="150" show-overflow-tooltip />
            <el-table-column prop="name" label="场次名称" min-width="120" show-overflow-tooltip />
            <el-table-column label="开始时间" width="170" align="center">
              <template #default="{ row }">{{ formatDateTime(row.startTime) }}</template>
            </el-table-column>
            <el-table-column label="结束时间" width="170" align="center">
              <template #default="{ row }">{{ formatDateTime(row.endTime) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="productCount" label="商品数" width="90" align="center" />
            <el-table-column label="操作" width="160" align="center" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" link @click="openSessionDialog(row)">编辑</el-button>
                <el-popconfirm title="删除场次将级联删除其下商品关联，确定？" @confirm="handleDeleteSession(row.id)">
                  <template #reference>
                    <el-button size="small" type="danger" link>删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
          <div class="flex justify-end mt-4">
            <el-pagination v-model:current-page="sessionPageNo" :page-size="sessionPageSize" :total="sessionTotal" layout="total, prev, pager, next" background @current-change="fetchSessions" />
          </div>
        </el-card>

        <el-dialog v-model="sessionDialogVisible" :title="sessionEditing ? '编辑场次' : '新增场次'" width="520px" :close-on-click-modal="false">
          <el-form :model="sessionForm" label-width="90px" :rules="sessionRules" ref="sessionFormRef">
            <el-form-item label="所属活动" prop="promotionId">
              <el-select v-model="sessionForm.promotionId" placeholder="选择活动" class="w-full" @change="onSessionPromotionChange">
                <el-option v-for="p in promotionOptions" :key="p.id" :label="p.title" :value="p.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="场次名称" prop="name">
              <el-input v-model="sessionForm.name" placeholder="如：10:00场" />
            </el-form-item>
            <el-form-item label="开始时间" prop="startTime">
              <el-date-picker v-model="sessionForm.startTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" class="w-full" />
            </el-form-item>
            <el-form-item label="结束时间" prop="endTime">
              <el-date-picker v-model="sessionForm.endTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" class="w-full" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="sessionDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="sessionSaving" @click="handleSaveSession">保存</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ==================== Tab 3: 商品关联管理 ==================== -->
      <el-tab-pane label="商品管理" name="relation">
        <el-card class="mb-4">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-3">
              <el-select v-model="relationSearch.promotionId" placeholder="按活动筛选" clearable class="w-[200px]" @change="onRelationPromotionChange">
                <el-option v-for="p in promotionOptions" :key="p.id" :label="p.title" :value="p.id" />
              </el-select>
              <el-select v-model="relationSearch.sessionId" placeholder="按场次筛选" clearable class="w-[200px]" @change="relationPageNo = 1; fetchRelations()">
                <el-option v-for="s in sessionOptions" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
              <el-button type="primary" @click="relationPageNo = 1; fetchRelations()">搜索</el-button>
            </div>
            <el-button type="primary" @click="openRelationDialog()">
              <el-icon><Plus /></el-icon> 新增商品
            </el-button>
          </div>
        </el-card>

        <el-card>
          <el-table :data="relations" v-loading="relationLoading" stripe border style="width: 100%">
            <el-table-column prop="id" label="ID" width="70" align="center" />
            <el-table-column label="图片" width="80" align="center">
              <template #default="{ row }">
                <img :src="row.productImage || '/img/like_01.png'" class="w-12 h-12 object-cover rounded" />
              </template>
            </el-table-column>
            <el-table-column prop="productName" label="商品名称" min-width="160" show-overflow-tooltip />
            <el-table-column label="秒杀价" width="100" align="center">
              <template #default="{ row }">
                <span class="text-[#E4393C] font-medium">¥{{ formatPrice(row.seckillPrice) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="stock" label="总库存" width="90" align="center" />
            <el-table-column prop="remainingStock" label="剩余" width="90" align="center" />
            <el-table-column prop="soldCount" label="已售" width="90" align="center" />
            <el-table-column prop="limitNum" label="限购" width="80" align="center" />
            <el-table-column label="预热" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="row.preheated ? 'success' : 'info'" size="small">{{ row.preheated ? '已预热' : '未预热' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="240" align="center" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" link @click="openRelationDialog(row)">编辑</el-button>
                <el-button size="small" type="success" link @click="handlePreheat(row.id)">预热</el-button>
                <el-button size="small" type="info" link @click="handleViewStock(row.id)">库存</el-button>
                <el-popconfirm title="确定删除该商品关联？" @confirm="handleDeleteRelation(row.id)">
                  <template #reference>
                    <el-button size="small" type="danger" link>删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
          <div class="flex justify-end mt-4">
            <el-pagination v-model:current-page="relationPageNo" :page-size="relationPageSize" :total="relationTotal" layout="total, prev, pager, next" background @current-change="fetchRelations" />
          </div>
        </el-card>

        <el-dialog v-model="relationDialogVisible" :title="relationEditing ? '编辑商品' : '新增商品'" width="520px" :close-on-click-modal="false">
          <el-form :model="relationForm" label-width="100px" :rules="relationRules" ref="relationFormRef">
            <el-form-item label="所属活动" prop="promotionId">
              <el-select v-model="relationForm.promotionId" placeholder="选择活动" class="w-full" @change="onRelationFormPromotionChange">
                <el-option v-for="p in promotionOptions" :key="p.id" :label="p.title" :value="p.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="所属场次" prop="sessionId">
              <el-select v-model="relationForm.sessionId" placeholder="选择场次" class="w-full">
                <el-option v-for="s in relationFormSessions" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="商品ID" prop="productId">
              <el-input-number v-model="relationForm.productId" :min="1" class="w-full" />
            </el-form-item>
            <el-form-item label="秒杀价(元)" prop="seckillPriceYuan">
              <el-input-number v-model="relationForm.seckillPriceYuan" :min="0.01" :precision="2" class="w-full" />
            </el-form-item>
            <el-form-item label="秒杀库存" prop="stock">
              <el-input-number v-model="relationForm.stock" :min="1" class="w-full" />
            </el-form-item>
            <el-form-item label="限购数量" prop="limitNum">
              <el-input-number v-model="relationForm.limitNum" :min="1" class="w-full" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="relationDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="relationSaving" @click="handleSaveRelation">保存</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ==================== Tab 4: 秒杀订单 ==================== -->
      <el-tab-pane label="秒杀订单" name="order">
        <el-card class="mb-4">
          <div class="flex items-center gap-3">
            <el-select v-model="orderSearch.status" placeholder="订单状态" clearable class="w-[130px]">
              <el-option label="待支付" :value="1" />
              <el-option label="已支付" :value="2" />
              <el-option label="已关闭" :value="3" />
            </el-select>
            <el-input v-model.number="orderSearch.relationId" placeholder="商品关联ID" clearable class="w-[150px]" />
            <el-input v-model.number="orderSearch.userId" placeholder="用户ID" clearable class="w-[150px]" />
            <el-button type="primary" @click="orderPageNo = 1; fetchOrders()">搜索</el-button>
          </div>
        </el-card>

        <el-card>
          <el-table :data="orders" v-loading="orderLoading" stripe border style="width: 100%">
            <el-table-column prop="id" label="ID" width="70" align="center" />
            <el-table-column prop="orderId" label="订单ID" width="100" align="center" />
            <el-table-column prop="productName" label="商品名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="userId" label="用户ID" width="100" align="center" />
            <el-table-column prop="quantity" label="数量" width="80" align="center" />
            <el-table-column label="秒杀价" width="100" align="center">
              <template #default="{ row }">
                <span v-if="row.seckillPrice" class="text-[#E4393C]">¥{{ formatPrice(row.seckillPrice) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="orderStatusType(row.status)" size="small">{{ orderStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="下单时间" width="170" align="center">
              <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
            </el-table-column>
          </el-table>
          <div class="flex justify-end mt-4">
            <el-pagination v-model:current-page="orderPageNo" :page-size="orderPageSize" :total="orderTotal" layout="total, prev, pager, next" background @current-change="fetchOrders" />
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 库存详情对话框 -->
    <el-dialog v-model="stockDialogVisible" title="每日库存快照" width="560px">
      <el-table :data="stockList" v-loading="stockLoading" stripe border>
        <el-table-column prop="batchDate" label="批次日期" width="150" align="center" />
        <el-table-column prop="stock" label="当日库存" width="120" align="center" />
        <el-table-column prop="sold" label="已售" width="100" align="center" />
        <el-table-column label="剩余" width="100" align="center">
          <template #default="{ row }">
            <span :class="row.remaining === 0 ? 'text-red-500' : 'text-green-600'">{{ row.remaining }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance } from 'element-plus'
import type { SeckillPromotionAdminVO, SeckillSessionAdminVO, SeckillProductRelationAdminVO, SeckillOrderAdminVO, SeckillStockAdminVO } from '@/types/admin'
import {
  getPromotionPage, createPromotion, updatePromotion, deletePromotion,
  getSessionPage, createSession, updateSession, deleteSession,
  getRelationPage, createRelation, updateRelation, deleteRelation, manualPreheat,
  getSeckillOrderPage, getStockStatus,
} from '@/api/admin/seckill'

const activeTab = ref('promotion')
const loadedTabs = ref<Set<string>>(new Set())

// ==================== 公共工具 ====================

function formatPrice(cents: number): string {
  return (cents / 100).toFixed(2)
}

function formatDateTime(dt: string): string {
  if (!dt) return ''
  return dt.replace('T', ' ').substring(0, 19)
}

function statusText(s: number): string {
  return ['未开始', '进行中', '已结束'][s] || '未知'
}

function statusType(s: number): '' | 'success' | 'info' | 'warning' | 'danger' {
  return (['info', 'success', 'warning'] as const)[s] || ''
}

function orderStatusText(s: number): string {
  return ['', '待支付', '已支付', '已关闭'][s] || '未知'
}

function orderStatusType(s: number): '' | 'success' | 'info' | 'warning' | 'danger' {
  return (['', 'warning', 'success', 'info'] as const)[s] || ''
}

// ==================== Tab 1: 活动管理 ====================

const promotions = ref<SeckillPromotionAdminVO[]>([])
const promoLoading = ref(false)
const promoTotal = ref(0)
const promoPageNo = ref(1)
const promoPageSize = ref(10)
const promoSearch = reactive({ title: '', status: undefined as number | undefined })

const promoDialogVisible = ref(false)
const promoEditing = ref<SeckillPromotionAdminVO | null>(null)
const promoSaving = ref(false)
const promoFormRef = ref<FormInstance>()
const promoForm = reactive({ id: undefined as number | undefined, title: '', startDate: '', endDate: '' })
const promoRules = {
  title: [{ required: true, message: '请输入活动标题', trigger: 'blur' }],
  startDate: [{ required: true, message: '请选择开始日期', trigger: 'change' }],
  endDate: [{ required: true, message: '请选择结束日期', trigger: 'change' }],
}

/** 活动下拉选项（供场次/商品Tab使用） */
const promotionOptions = ref<SeckillPromotionAdminVO[]>([])

async function fetchPromotions() {
  promoLoading.value = true
  try {
    const res = await getPromotionPage({
      pageNo: promoPageNo.value,
      pageSize: promoPageSize.value,
      title: promoSearch.title || undefined,
      status: promoSearch.status,
    })
    promotions.value = res.list || []
    promoTotal.value = res.total || 0
  } finally {
    promoLoading.value = false
  }
}

async function fetchPromotionOptions() {
  const res = await getPromotionPage({ pageNo: 1, pageSize: 100 })
  promotionOptions.value = res.list || []
}

function openPromoDialog(row?: SeckillPromotionAdminVO) {
  promoEditing.value = row || null
  if (row) {
    Object.assign(promoForm, { id: row.id, title: row.title, startDate: row.startDate, endDate: row.endDate })
  } else {
    Object.assign(promoForm, { id: undefined, title: '', startDate: '', endDate: '' })
  }
  promoDialogVisible.value = true
}

async function handleSavePromotion() {
  const valid = await promoFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (promoForm.startDate > promoForm.endDate) {
    ElMessage.warning('开始日期不能晚于结束日期')
    return
  }
  promoSaving.value = true
  try {
    const data = { title: promoForm.title, startDate: promoForm.startDate, endDate: promoForm.endDate }
    if (promoEditing.value) {
      await updatePromotion({ ...data, id: promoForm.id } as any)
      ElMessage.success('更新成功')
    } else {
      await createPromotion(data as any)
      ElMessage.success('新增成功')
    }
    promoDialogVisible.value = false
    fetchPromotions()
    fetchPromotionOptions()
  } finally {
    promoSaving.value = false
  }
}

async function handleDeletePromotion(id: number) {
  try {
    await deletePromotion(id)
    ElMessage.success('删除成功')
    fetchPromotions()
    fetchPromotionOptions()
  } catch {
    // 错误提示由拦截器处理
  }
}

// ==================== Tab 2: 场次管理 ====================

const sessions = ref<SeckillSessionAdminVO[]>([])
const sessionLoading = ref(false)
const sessionTotal = ref(0)
const sessionPageNo = ref(1)
const sessionPageSize = ref(10)
const sessionSearch = reactive({ promotionId: undefined as number | undefined })

const sessionDialogVisible = ref(false)
const sessionEditing = ref<SeckillSessionAdminVO | null>(null)
const sessionSaving = ref(false)
const sessionFormRef = ref<FormInstance>()
const sessionForm = reactive({
  id: undefined as number | undefined,
  promotionId: undefined as number | undefined,
  name: '',
  startTime: '',
  endTime: '',
})
const sessionRules = {
  promotionId: [{ required: true, message: '请选择活动', trigger: 'change' }],
  name: [{ required: true, message: '请输入场次名称', trigger: 'blur' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }],
}

/** 场次下拉选项（供商品Tab使用） */
const sessionOptions = ref<SeckillSessionAdminVO[]>([])

async function fetchSessions() {
  sessionLoading.value = true
  try {
    const res = await getSessionPage({
      pageNo: sessionPageNo.value,
      pageSize: sessionPageSize.value,
      promotionId: sessionSearch.promotionId,
    })
    sessions.value = res.list || []
    sessionTotal.value = res.total || 0
  } finally {
    sessionLoading.value = false
  }
}

async function fetchSessionOptions(promotionId?: number) {
  if (!promotionId) {
    sessionOptions.value = []
    return
  }
  const res = await getSessionPage({ pageNo: 1, pageSize: 100, promotionId })
  sessionOptions.value = res.list || []
}

function onSessionPromotionChange() {
  // 场次搜索栏的活动变更时不做额外操作
}

function openSessionDialog(row?: SeckillSessionAdminVO) {
  sessionEditing.value = row || null
  if (row) {
    Object.assign(sessionForm, {
      id: row.id,
      promotionId: row.promotionId,
      name: row.name,
      startTime: row.startTime,
      endTime: row.endTime,
    })
  } else {
    Object.assign(sessionForm, { id: undefined, promotionId: undefined, name: '', startTime: '', endTime: '' })
  }
  sessionDialogVisible.value = true
}

async function handleSaveSession() {
  const valid = await sessionFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (sessionForm.startTime >= sessionForm.endTime) {
    ElMessage.warning('开始时间不能晚于结束时间')
    return
  }
  sessionSaving.value = true
  try {
    const data = {
      promotionId: sessionForm.promotionId!,
      name: sessionForm.name,
      startTime: sessionForm.startTime,
      endTime: sessionForm.endTime,
    }
    if (sessionEditing.value) {
      await updateSession({ ...data, id: sessionForm.id } as any)
      ElMessage.success('更新成功')
    } else {
      await createSession(data as any)
      ElMessage.success('新增成功')
    }
    sessionDialogVisible.value = false
    fetchSessions()
  } finally {
    sessionSaving.value = false
  }
}

async function handleDeleteSession(id: number) {
  try {
    await deleteSession(id)
    ElMessage.success('删除成功')
    fetchSessions()
  } catch {
    // 错误提示由拦截器处理
  }
}

// ==================== Tab 3: 商品关联管理 ====================

const relations = ref<SeckillProductRelationAdminVO[]>([])
const relationLoading = ref(false)
const relationTotal = ref(0)
const relationPageNo = ref(1)
const relationPageSize = ref(10)
const relationSearch = reactive({ promotionId: undefined as number | undefined, sessionId: undefined as number | undefined })

const relationDialogVisible = ref(false)
const relationEditing = ref<SeckillProductRelationAdminVO | null>(null)
const relationSaving = ref(false)
const relationFormRef = ref<FormInstance>()
const relationForm = reactive({
  id: undefined as number | undefined,
  promotionId: undefined as number | undefined,
  sessionId: undefined as number | undefined,
  productId: undefined as number | undefined,
  seckillPriceYuan: 0,
  stock: 1,
  limitNum: 1,
})
const relationRules = {
  promotionId: [{ required: true, message: '请选择活动', trigger: 'change' }],
  sessionId: [{ required: true, message: '请选择场次', trigger: 'change' }],
  productId: [{ required: true, message: '请输入商品ID', trigger: 'blur' }],
  seckillPriceYuan: [{ required: true, message: '请输入秒杀价', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }],
  limitNum: [{ required: true, message: '请输入限购数量', trigger: 'blur' }],
}

/** 商品关联对话框中的场次选项（根据选中的活动级联加载） */
const relationFormSessions = ref<SeckillSessionAdminVO[]>([])

async function fetchRelations() {
  relationLoading.value = true
  try {
    const res = await getRelationPage({
      pageNo: relationPageNo.value,
      pageSize: relationPageSize.value,
      promotionId: relationSearch.promotionId,
      sessionId: relationSearch.sessionId,
    })
    relations.value = res.list || []
    relationTotal.value = res.total || 0
  } finally {
    relationLoading.value = false
  }
}

async function onRelationPromotionChange() {
  relationSearch.sessionId = undefined
  if (relationSearch.promotionId) {
    await fetchSessionOptions(relationSearch.promotionId)
  } else {
    sessionOptions.value = []
  }
  relationPageNo.value = 1
  fetchRelations()
}

async function onRelationFormPromotionChange() {
  relationForm.sessionId = undefined
  if (relationForm.promotionId) {
    const res = await getSessionPage({ pageNo: 1, pageSize: 100, promotionId: relationForm.promotionId })
    relationFormSessions.value = res.list || []
  } else {
    relationFormSessions.value = []
  }
}

function openRelationDialog(row?: SeckillProductRelationAdminVO) {
  relationEditing.value = row || null
  if (row) {
    Object.assign(relationForm, {
      id: row.id,
      promotionId: row.promotionId,
      sessionId: row.sessionId,
      productId: row.productId,
      seckillPriceYuan: row.seckillPrice / 100,
      stock: row.stock,
      limitNum: row.limitNum,
    })
    // 加载该活动下的场次选项
    if (row.promotionId) {
      onRelationFormPromotionChange()
    }
  } else {
    Object.assign(relationForm, {
      id: undefined, promotionId: undefined, sessionId: undefined,
      productId: undefined, seckillPriceYuan: 0, stock: 1, limitNum: 1,
    })
    relationFormSessions.value = []
  }
  relationDialogVisible.value = true
}

async function handleSaveRelation() {
  const valid = await relationFormRef.value?.validate().catch(() => false)
  if (!valid) return
  relationSaving.value = true
  try {
    const data = {
      promotionId: relationForm.promotionId!,
      sessionId: relationForm.sessionId!,
      productId: relationForm.productId!,
      seckillPrice: Math.round(relationForm.seckillPriceYuan * 100),
      stock: relationForm.stock,
      limitNum: relationForm.limitNum,
    }
    if (relationEditing.value) {
      await updateRelation({ ...data, id: relationForm.id } as any)
      ElMessage.success('更新成功')
    } else {
      await createRelation(data as any)
      ElMessage.success('新增成功')
    }
    relationDialogVisible.value = false
    fetchRelations()
  } finally {
    relationSaving.value = false
  }
}

async function handleDeleteRelation(id: number) {
  try {
    await deleteRelation(id)
    ElMessage.success('删除成功')
    fetchRelations()
  } catch {
    // 错误提示由拦截器处理
  }
}

async function handlePreheat(id: number) {
  try {
    await manualPreheat(id)
    ElMessage.success('预热成功')
    fetchRelations()
  } catch {
    // 错误提示由拦截器处理
  }
}

// ==================== 库存详情 ====================

const stockDialogVisible = ref(false)
const stockLoading = ref(false)
const stockList = ref<SeckillStockAdminVO[]>([])

async function handleViewStock(relationId: number) {
  stockDialogVisible.value = true
  stockLoading.value = true
  try {
    stockList.value = await getStockStatus(relationId)
  } finally {
    stockLoading.value = false
  }
}

// ==================== Tab 4: 秒杀订单 ====================

const orders = ref<SeckillOrderAdminVO[]>([])
const orderLoading = ref(false)
const orderTotal = ref(0)
const orderPageNo = ref(1)
const orderPageSize = ref(10)
const orderSearch = reactive({
  status: undefined as number | undefined,
  relationId: undefined as number | undefined,
  userId: undefined as number | undefined,
})

async function fetchOrders() {
  orderLoading.value = true
  try {
    const res = await getSeckillOrderPage({
      pageNo: orderPageNo.value,
      pageSize: orderPageSize.value,
      status: orderSearch.status,
      relationId: orderSearch.relationId,
      userId: orderSearch.userId,
    })
    orders.value = res.list || []
    orderTotal.value = res.total || 0
  } finally {
    orderLoading.value = false
  }
}

// ==================== Tab 切换 ====================

function handleTabChange(tab: string) {
  if (loadedTabs.value.has(tab)) return
  loadedTabs.value.add(tab)
  switch (tab) {
    case 'promotion':
      fetchPromotions()
      break
    case 'session':
      fetchPromotionOptions()
      fetchSessions()
      break
    case 'relation':
      fetchPromotionOptions()
      fetchRelations()
      break
    case 'order':
      fetchOrders()
      break
  }
}

onMounted(() => {
  loadedTabs.value.add('promotion')
  fetchPromotions()
})
</script>
