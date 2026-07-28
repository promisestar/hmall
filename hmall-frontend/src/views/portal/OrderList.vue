<template>
  <PortalLayout>
    <div class="container-main py-5">
      <!-- 页面标题 -->
      <div class="flex items-center justify-between mb-4">
        <h2 class="section-title">我的订单</h2>
        <router-link to="/portal/home" class="text-xs text-gray-400 hover:text-[#E4393C] transition-colors">
          继续购物 &gt;
        </router-link>
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="page-card p-16 flex justify-center items-center">
        <el-icon class="is-loading text-2xl text-[#E4393C]"><Loading /></el-icon>
        <span class="ml-2 text-gray-400 text-sm">订单加载中...</span>
      </div>

      <!-- 空状态 -->
      <div v-else-if="orderList.length === 0" class="page-card p-20 text-center">
        <div class="w-20 h-20 mx-auto mb-5 rounded-full bg-gray-50 flex items-center justify-center">
          <ClipboardList class="w-10 h-10 text-gray-300" />
        </div>
        <p class="text-gray-500 mb-1">暂无订单</p>
        <p class="text-xs text-gray-400 mb-6">您还没有下过单，去逛逛吧</p>
        <router-link to="/portal/home" class="btn-primary">去逛逛</router-link>
      </div>

      <!-- 订单表格 -->
      <div v-else class="page-card overflow-hidden">
        <el-table
          :data="orderList"
          style="width: 100%"
          @row-click="showDetail"
        >
          <!-- 所购商品 -->
          <el-table-column label="商品信息" min-width="280" header-align="center">
            <template #default="{ row }">
              <div v-if="row.detailVOs && row.detailVOs.length > 0" class="flex items-center gap-1.5">
                <div
                  v-for="item in row.detailVOs.slice(0, 3)"
                  :key="item.id"
                  class="flex items-center gap-1.5 bg-gray-50 rounded-lg px-1.5 py-1 min-w-0"
                >
                  <img
                    :src="item.image"
                    :alt="item.name"
                    class="w-7 h-7 object-cover rounded border border-gray-100 flex-shrink-0"
                  />
                  <span class="text-xs text-gray-600 truncate">
                    {{ item.name }}
                    <span class="text-gray-400">x{{ item.num }}</span>
                  </span>
                </div>
                <span v-if="row.detailVOs.length > 3" class="text-[11px] text-gray-400 flex-shrink-0">
                  等{{ row.detailVOs.length }}件
                </span>
              </div>
              <span v-else class="text-gray-300 text-xs">-</span>
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
              <span class="price-tag text-sm">¥{{ formatPrice(row.totalFee) }}</span>
            </template>
          </el-table-column>

          <!-- 支付方式 -->
          <el-table-column prop="paymentType" label="支付方式" min-width="90" header-align="center" align="center">
            <template #default="{ row }">
              <span class="text-[13px] text-gray-600">{{ paymentTypeText[row.paymentType] || '未知' }}</span>
            </template>
          </el-table-column>

          <!-- 订单状态 -->
          <el-table-column prop="status" label="状态" min-width="100" header-align="center" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small" effect="light" round>
                {{ statusText[row.status] || '未知' }}
              </el-tag>
            </template>
          </el-table-column>

          <!-- 下单时间 -->
          <el-table-column prop="createTime" label="下单时间" min-width="155" header-align="center" align="center">
            <template #default="{ row }">
              <span class="text-xs text-gray-500">{{ formatDate(row.createTime, 'yyyy-MM-dd HH:mm') }}</span>
            </template>
          </el-table-column>

          <!-- 支付时间 -->
          <el-table-column prop="payTime" label="支付时间" min-width="155" header-align="center" align="center">
            <template #default="{ row }">
              <span v-if="row.payTime" class="text-xs text-gray-500">{{ formatDate(row.payTime, 'yyyy-MM-dd HH:mm') }}</span>
              <span v-else class="text-gray-300">-</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 分页 -->
        <div class="flex justify-center py-4 border-t border-gray-50">
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
        width="620px"
        destroy-on-close
        :show-close="true"
      >
        <template #header>
          <div class="flex items-center gap-3">
            <span class="text-[15px] font-semibold text-gray-800">订单详情</span>
            <span class="text-xs text-gray-400 font-mono">#{{ currentOrder ? truncateId(currentOrder.id) : '' }}</span>
            <el-tag v-if="currentOrder" :type="statusTagType(currentOrder.status)" size="small" effect="light" round>
              {{ statusText[currentOrder.status] || '未知' }}
            </el-tag>
          </div>
        </template>
        <template v-if="currentOrder">
          <!-- 订单概要信息 -->
          <div class="grid grid-cols-2 gap-x-8 gap-y-3 mb-5 bg-gray-50 rounded-xl p-4">
            <div class="text-[13px]">
              <span class="text-gray-400">支付方式：</span>
              <span class="text-gray-700">{{ paymentTypeText[currentOrder.paymentType] || '未知' }}</span>
            </div>
            <div class="text-[13px]">
              <span class="text-gray-400">下单时间：</span>
              <span class="text-gray-700">{{ formatDate(currentOrder.createTime, 'yyyy-MM-dd HH:mm:ss') }}</span>
            </div>
            <div class="text-[13px]">
              <span class="text-gray-400">支付时间：</span>
              <span v-if="currentOrder.payTime" class="text-gray-700">{{ formatDate(currentOrder.payTime, 'yyyy-MM-dd HH:mm:ss') }}</span>
              <span v-else class="text-gray-300">-</span>
            </div>
            <div class="text-[13px]">
              <span class="text-gray-400">订单总额：</span>
              <span class="price-tag">¥{{ formatPrice(currentOrder.totalFee) }}</span>
            </div>
          </div>

          <!-- 商品清单 -->
          <div>
            <h4 class="text-[13px] font-semibold text-gray-700 mb-3">
              商品清单（共 {{ currentOrder.detailVOs?.length || 0 }} 件）
            </h4>
            <div class="space-y-2.5">
              <div
                v-for="item in currentOrder.detailVOs"
                :key="item.id"
                class="flex items-center gap-3 bg-gray-50 rounded-xl p-3"
              >
                <img
                  :src="item.image"
                  :alt="item.name"
                  class="w-14 h-14 object-cover rounded-lg border border-gray-100 flex-shrink-0"
                />
                <div class="flex-1 min-w-0">
                  <p class="text-[13px] text-gray-800 truncate">{{ item.name }}</p>
                  <p class="text-xs text-gray-400 mt-0.5">单价 ¥{{ formatPrice(item.price) }}</p>
                </div>
                <div class="text-right flex-shrink-0">
                  <p class="text-xs text-gray-500">x{{ item.num }}</p>
                  <p class="text-[13px] price-tag mt-0.5">
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
import { ClipboardList } from 'lucide-vue-next'
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
::deep(.el-table__row) {
  cursor: pointer;
}
</style>
