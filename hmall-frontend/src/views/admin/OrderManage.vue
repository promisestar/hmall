<template>
  <div>
    <!-- Search -->
    <el-card class="mb-4">
      <div class="flex items-center gap-3 flex-wrap">
        <el-input v-model="searchForm.orderId" placeholder="订单号" clearable class="w-[180px]" />
        <el-select v-model="searchForm.status" placeholder="全部状态" clearable class="w-[120px]">
          <el-option label="未付款" :value="1" />
          <el-option label="已付款,未发货" :value="2" />
          <el-option label="已发货,未确认" :value="3" />
          <el-option label="确认收货,交易成功" :value="4" />
          <el-option label="交易取消,订单关闭" :value="5" />
          <el-option label="交易结束,已评价" :value="6" />
        </el-select>
        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DDTHH:mm:ss"
        />
        <el-button type="primary" @click="fetchData">查询</el-button>
        <el-button @click="resetSearch">重置</el-button>
        <div class="flex-1" />
        <el-button
          v-if="selectedIds.length"
          type="primary"
          size="small"
          @click="openDeliveryDialog"
        >批量发货</el-button>
        <el-button
          v-if="selectedIds.length"
          type="danger"
          size="small"
          @click="handleBatchClose"
        >批量关闭</el-button>
      </div>
    </el-card>

    <!-- Table -->
    <el-card>
      <el-table
        :data="orders"
        v-loading="loading"
        stripe
        border
        style="width: 100%"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="50" align="center" />
        <el-table-column label="订单号" width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button type="primary" link @click="showDetail(row)">{{ row.id }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" align="center" />
        <el-table-column prop="userId" label="用户ID" width="100" align="center" />
        <el-table-column label="总金额" width="120" align="center">
          <template #default="{ row }">
            <span class="text-[#E4393C] font-medium">¥{{ formatPrice(row.totalFee) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="支付方式" width="100" align="center">
          <template #default="{ row }">
            {{ paymentTypeText(row.paymentType) }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="showDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 2"
              size="small"
              type="success"
              link
              @click="handleSingleDelivery(row)"
            >发货</el-button>
            <el-button
              v-if="row.status === 1 || row.status === 2"
              size="small"
              type="danger"
              link
              @click="handleSingleClose(row)"
            >关闭</el-button>
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

    <!-- Delivery Dialog -->
    <el-dialog v-model="deliveryVisible" title="批量发货" width="450px">
      <el-alert title="确认对以下订单进行发货操作？" type="info" :closable="false" class="mb-4" />
      <div class="mb-2">
        <span>选中订单数：<strong>{{ deliveryIds.length }}</strong> 个</span>
      </div>
      <template #footer>
        <el-button @click="deliveryVisible = false">取消</el-button>
        <el-button type="primary" :loading="delivering" @click="confirmDelivery">确认发货</el-button>
      </template>
    </el-dialog>

    <!-- Detail Dialog -->
    <el-dialog v-model="detailVisible" title="订单详情" width="700px">
      <template v-if="detailData">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="订单号">{{ detailData.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detailData.status)" size="small">
              {{ statusText(detailData.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detailData.createTime }}</el-descriptions-item>
          <el-descriptions-item label="支付时间">{{ detailData.payTime || '—' }}</el-descriptions-item>
          <el-descriptions-item label="发货时间">{{ detailData.consignTime || '—' }}</el-descriptions-item>
          <el-descriptions-item label="用户ID">{{ detailData.userId }}</el-descriptions-item>
          <el-descriptions-item label="总金额">
            <span class="text-[#E4393C] font-medium">¥{{ formatPrice(detailData.totalFee) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="支付方式">{{ paymentTypeText(detailData.paymentType) }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="mt-4 mb-2 font-medium">商品明细</h4>
        <el-table :data="detailData.detailVOs || []" border size="small">
          <el-table-column label="商品" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <img :src="row.image" class="w-10 h-10 rounded" />
                <span>{{ row.name }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="num" label="数量" width="80" align="center" />
          <el-table-column label="单价" width="100" align="center">
            <template #default="{ row }">¥{{ formatPrice(row.price) }}</template>
          </el-table-column>
          <el-table-column label="小计" width="100" align="center">
            <template #default="{ row }">¥{{ formatPrice(row.price * row.num) }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { getAdminOrderPage, getOrderDetail, batchDelivery, batchCloseOrders } from '@/api/admin/order'
import { formatPrice } from '@/utils/format'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { OrderVO, PageResult } from '@/types'

const orders = ref<OrderVO[]>([])
const loading = ref(false)
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const selectedIds = ref<(string | number)[]>([])

const searchForm = reactive({
  orderId: '',
  status: undefined as number | undefined,
})

const dateRange = ref<[string, string] | null>(null)

const deliveryVisible = ref(false)
const deliveryIds = ref<(string | number)[]>([])
const delivering = ref(false)

const detailVisible = ref(false)
const detailData = ref<OrderVO | null>(null)

function statusText(status: number) {
  const map: Record<number, string> = {
    1: '未付款', 2: '已付款,未发货', 3: '已发货,未确认',
    4: '交易成功', 5: '已关闭', 6: '已评价',
  }
  return map[status] || '未知'
}

function statusTagType(status: number) {
  const map: Record<number, string> = {
    1: 'warning', 2: 'primary', 3: 'info',
    4: 'success', 5: 'danger', 6: 'success',
  }
  return map[status] || 'info'
}

function paymentTypeText(type?: number) {
  if (!type) return '—'
  const map: Record<number, string> = { 1: '支付宝', 2: '微信', 3: '余额' }
  return map[type] || '其他'
}

function handleSelectionChange(rows: OrderVO[]) {
  selectedIds.value = rows.map(r => r.id)
}

function openDeliveryDialog() {
  deliveryIds.value = [...selectedIds.value]
  deliveryVisible.value = true
}

async function confirmDelivery() {
  delivering.value = true
  try {
    await batchDelivery(deliveryIds.value)
    ElMessage.success(`已发货 ${deliveryIds.value.length} 个订单`)
    deliveryVisible.value = false
    fetchData()
  } finally {
    delivering.value = false
  }
}

async function handleSingleDelivery(row: OrderVO) {
  try {
    await ElMessageBox.confirm(`确认对订单 ${row.id} 进行发货操作？`, '发货确认', { type: 'info' })
    await batchDelivery([row.id])
    ElMessage.success('发货成功')
    fetchData()
  } catch {
    // 用户取消
  }
}

async function handleBatchClose() {
  try {
    await ElMessageBox.confirm(`确定关闭选中的 ${selectedIds.value.length} 个订单吗？`, '批量关闭', { type: 'warning' })
    await batchCloseOrders(selectedIds.value)
    ElMessage.success('批量关闭成功')
    fetchData()
  } catch {
    // 用户取消
  }
}

async function handleSingleClose(row: OrderVO) {
  try {
    await ElMessageBox.confirm(`确定关闭订单 ${row.id} 吗？`, '关闭订单', { type: 'warning' })
    await batchCloseOrders([row.id])
    ElMessage.success('订单已关闭')
    fetchData()
  } catch {
    // 用户取消
  }
}

async function showDetail(row: OrderVO) {
  try {
    detailData.value = await getOrderDetail(row.id)
    detailVisible.value = true
  } catch {
    ElMessage.error('获取订单详情失败')
  }
}

function resetSearch() {
  searchForm.orderId = ''
  searchForm.status = undefined
  dateRange.value = null
  fetchData()
}

async function fetchData() {
  loading.value = true
  try {
    const orderIdNum = searchForm.orderId ? Number(searchForm.orderId) : undefined
    const res: PageResult<OrderVO> = await getAdminOrderPage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      status: searchForm.status,
      orderId: orderIdNum,
      startTime: dateRange.value?.[0],
      endTime: dateRange.value?.[1],
    })
    orders.value = res.list || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

onMounted(() => fetchData())
</script>
