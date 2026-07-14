<template>
  <div>
    <!-- Stats Cards -->
    <el-row :gutter="20" class="mb-6">
      <el-col :span="6" v-for="stat in stats" :key="stat.label">
        <el-card shadow="hover">
          <div class="flex items-center gap-4">
            <div :class="stat.color" class="w-12 h-12 rounded-lg flex items-center justify-center text-white text-xl">
              <el-icon :size="24"><component :is="stat.icon" /></el-icon>
            </div>
            <div>
              <p class="text-sm text-gray-500">{{ stat.label }}</p>
              <p class="text-2xl font-bold text-gray-800">{{ stat.value }}</p>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Charts -->
    <el-row :gutter="20">
      <el-col :span="16">
        <el-card>
          <template #header>
            <span class="font-medium">销售趋势</span>
          </template>
          <v-chart :option="lineChartOption" style="height: 350px" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <template #header>
            <span class="font-medium">分类占比</span>
          </template>
          <v-chart :option="pieChartOption" style="height: 350px" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import 'echarts'
import { ShoppingCart, User, Money, Box } from '@element-plus/icons-vue'

const stats = [
  { label: '今日订单', value: '156', color: 'bg-blue-500', icon: ShoppingCart },
  { label: '新增用户', value: '38', color: 'bg-green-500', icon: User },
  { label: '今日销售额', value: '¥12,580', color: 'bg-orange-500', icon: Money },
  { label: '商品总数', value: '1,286', color: 'bg-purple-500', icon: Box },
]

const lineChartOption = computed(() => ({
  tooltip: { trigger: 'axis' as const },
  legend: { data: ['销售额', '订单量'] },
  grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
  xAxis: { type: 'category' as const, data: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'] },
  yAxis: [{ type: 'value' as const, name: '销售额(元)' }, { type: 'value' as const, name: '订单量' }],
  series: [
    { name: '销售额', type: 'line', smooth: true, data: [8200, 9320, 9010, 9340, 12900, 13300, 13200], color: '#409EFF' },
    { name: '订单量', type: 'line', smooth: true, yAxisIndex: 1, data: [120, 132, 101, 134, 190, 230, 210], color: '#67C23A' },
  ],
}))

const pieChartOption = computed(() => ({
  tooltip: { trigger: 'item' as const },
  legend: { orient: 'vertical' as const, left: 'left' },
  series: [{
    type: 'pie',
    radius: ['40%', '70%'],
    data: [
      { value: 335, name: '手机数码' },
      { value: 310, name: '家用电器' },
      { value: 234, name: '服装鞋帽' },
      { value: 135, name: '食品生鲜' },
      { value: 154, name: '其他' },
    ],
  }],
}))
</script>
