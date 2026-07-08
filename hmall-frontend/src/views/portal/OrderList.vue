<template>
  <PortalLayout>
    <div class="container-main py-6">
      <!-- 页面标题 -->
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-xl font-bold text-gray-800">我的订单</h2>
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="bg-white rounded-lg shadow-sm p-12 flex justify-center">
        <el-icon class="is-loading text-2xl text-gray-400"><Loading /></el-icon>
        <span class="ml-2 text-gray-400">加载中...</span>
      </div>

      <!-- 空状态 -->
      <div v-else-if="orderList.length === 0" class="bg-white rounded-lg shadow-sm p-12 text-center">
        <div class="text-gray-400 text-5xl mb-4">📋</div>
        <p class="text-gray-500 text-base">暂无订单</p>
        <router-link to="/portal/home" class="text-[#E4393C] text-sm mt-2 inline-block hover:underline">
          去逛逛
        </router-link>
      </div>

      <!-- 订单表格 -->
      <div v-else class="bg-white rounded-lg shadow-sm overflow-hidden">
        <el-table
          :data="orderList"
          style="width: 100%"
          stripe
          highlight-current-row
          @row-click="showDetail"
        >
          <!-- 所购商品 -->
          <el-table-column label="商品信息" min-width="280" header-align="center">
            <template #default="{ row }">
              <div v-if="row.detailVOs && row.detailVOs.length > 0" class="flex items-center gap-1.5">
                <div
                  v-for="item in row.detailVOs.slice(0, 3)"
                  :key="item.id"
                  class="flex items-center gap-1.5 bg-gray-50 rounded px-1.5 py-1 min-w-0"
                >
                  <img
                    :src="item.image"
                    :alt="item.name"
                    class="w-7 h-7 object-cover rounded border border-gray-200 flex-shrink-0"
                  />
                  <span class="text-sm text-gray-700 truncate">
                    {{ item.name }}
                    <span class="text-gray-400">x{{ item.num }}</span>
                  </span>
                </div>
                <span v-if="row.detailVOs.length > 3" class="text-xs text-gray-400 flex-shrink-0">
                  等{{ row.detailVOs.length }}件
                </span>
              </div>
              <span v-else class="text-gray-400 text-xs">-</span>
            </template>
          </el-table-column>

          <!-- 订单编号 -->
          <el-table-column prop="id" label="订单编号" min-width="140" header-align="center" align="center">
            <template #default="{ row }">
              <span class="text-xs text-gray-500 font-mono">{{ truncateId(row.id) }}</span>
            </template>
          </el-table-column>

          <!-- 订单金额 -->
          <el-table-column prop="totalFee" label="金额" min-width="100" header-align="center" align="right">
            <template #default="{ row }">
              <span class="text-[#E4393C] font-bold">¥{{ formatPrice(row.totalFee) }}</span>
            </template>
          </el-table-column>

          <!-- 支付方式 -->
          <el-table-column prop="paymentType" label="支付方式" min-width="90" header-align="center" align="center">
            <template #default="{ row }">
              {{ paymentTypeText[row.paymentType] || '未知' }}
            </template>
          </el-table-column>

          <!-- 订单状态 -->
          <el-table-column prop="status" label="状态" min-width="100" header-align="center" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">
                {{ statusText[row.status] || '未知' }}
              </el-tag>
            </template>
          </el-table-column>

          <!-- 下单时间 -->
          <el-table-column prop="createTime" label="下单时间" min-width="155" header-align="center" align="center">
            <template #default="{ row }">
              {{ formatDate(row.createTime, 'yyyy-MM-dd HH:mm') }}
            </template>
          </el-table-column>

          <!-- 支付时间 -->
          <el-table-column prop="payTime" label="支付时间" min-width="155" header-align="center" align="center">
            <template #default="{ row }">
              <span v-if="row.payTime">{{ formatDate(row.payTime, 'yyyy-MM-dd HH:mm') }}</span>
              <span v-else class="text-gray-400">-</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 分页 -->
        <div class="flex justify-center py-4 border-t border-gray-100">
          <el-pagination
            v-model:current-page="pageNo"
            :page-size="pageSize"
            :total="total"
            layout="total, prev, pager, next, jumper"
            background
            @current-change="fetchOrders"
          />
        </div>
      </div>

      <!-- 订单详情弹窗 -->
      <el-dialog
        v-model="dialogVisible"
        :title="`订单详情 #${currentOrder ? truncateId(currentOrder.id) : ''}`"
        width="620px"
        destroy-on-close
        center
      >
        <template v-if="currentOrder">
          <!-- 订单概要信息 -->
          <div class="grid grid-cols-2 gap-x-8 gap-y-3 mb-5">
            <div class="text-sm">
              <span class="text-gray-400">订单状态：</span>
              <el-tag :type="statusTagType(currentOrder.status)" size="small">
                {{ statusText[currentOrder.status] || '未知' }}
              </el-tag>
            </div>
            <div class="text-sm">
              <span class="text-gray-400">支付方式：</span>
              <span>{{ paymentTypeText[currentOrder.paymentType] || '未知' }}</span>
            </div>
            <div class="text-sm">
              <span class="text-gray-400">下单时间：</span>
              <span>{{ formatDate(currentOrder.createTime, 'yyyy-MM-dd HH:mm:ss') }}</span>
            </div>
            <div class="text-sm">
              <span class="text-gray-400">支付时间：</span>
              <span v-if="currentOrder.payTime">{{ formatDate(currentOrder.payTime, 'yyyy-MM-dd HH:mm:ss') }}</span>
              <span v-else class="text-gray-400">-</span>
            </div>
          </div>

          <!-- 订单总额 -->
          <div class="flex justify-end border-t border-gray-100 pt-3 mb-5">
            <span class="text-gray-500 text-sm mr-2">订单总额：</span>
            <span class="text-[#E4393C] text-lg font-bold">¥{{ formatPrice(currentOrder.totalFee) }}</span>
          </div>

          <!-- 商品清单 -->
          <div class="border-t border-gray-100 pt-4">
            <h4 class="text-sm font-medium text-gray-700 mb-3">
              商品清单（共{{ currentOrder.detailVOs?.length || 0 }}件）
            </h4>
            <div class="space-y-3">
              <div
                v-for="item in currentOrder.detailVOs"
                :key="item.id"
                class="flex items-center gap-3 bg-gray-50 rounded-lg p-3"
              >
                <img
                  :src="item.image"
                  :alt="item.name"
                  class="w-14 h-14 object-cover rounded border border-gray-200 flex-shrink-0"
                />
                <div class="flex-1 min-w-0">
                  <p class="text-sm text-gray-800 truncate">{{ item.name }}</p>
                  <p class="text-xs text-gray-400 mt-0.5">单价 ¥{{ formatPrice(item.price) }}</p>
                </div>
                <div class="text-right flex-shrink-0">
                  <p class="text-sm text-gray-600">x{{ item.num }}</p>
                  <p class="text-sm text-[#E4393C] font-medium mt-0.5">
                    ¥{{ formatPrice(item.price * item.num) }}
                  </p>
                </div>
              </div>
            </div>
          </div>
        </template>

        <template #footer>
          <el-button @click="dialogVisible = false">关闭</el-button>
        </template>
      </el-dialog>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import PortalLayout from './PortalLayout.vue'
import { getOrderPage } from '@/api/order'
import { formatPrice, formatDate } from '@/utils/format'
import type { OrderVO } from '@/types'

// 订单数据
const orderList = ref<OrderVO[]>([])
const pageNo = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(true)

// 弹窗
const dialogVisible = ref(false)
const currentOrder = ref<OrderVO | null>(null)

// 订单状态映射
const statusText: Record<number, string> = {
  1: '待付款',
  2: '已付款',
  3: '已发货',
  4: '已完成',
  5: '已取消',
  6: '已评价',
}

// 状态标签颜色
function statusTagType(status: number): '' | 'success' | 'warning' | 'danger' | 'info' {
  const map: Record<number, '' | 'success' | 'warning' | 'danger' | 'info'> = {
    1: 'warning',
    2: '',
    3: '',
    4: 'success',
    5: 'info',
    6: 'success',
  }
  return map[status] || 'info'
}

// 支付方式映射
const paymentTypeText: Record<number, string> = {
  1: '支付宝',
  2: '微信',
  3: '余额',
}

// 雪花ID截断显示
function truncateId(id: string): string {
  if (!id) return ''
  if (id.length <= 8) return id
  return `...${id.slice(-8)}`
}

// 点击行打开详情弹窗
function showDetail(row: OrderVO) {
  currentOrder.value = row
  dialogVisible.value = true
}

// 获取订单列表
async function fetchOrders() {
  loading.value = true
  try {
    const res = await getOrderPage({ pageNo: pageNo.value, pageSize: pageSize.value })
    orderList.value = res.list || []
    total.value = res.total || 0
  } catch {
    orderList.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchOrders()
})
</script>

<style scoped>
/* 表格行 hover 时显示手型光标 */
:deep(.el-table__row) {
  cursor: pointer;
}
</style>
