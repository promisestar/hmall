<template>
  <div>
    <!-- 页面标题 -->
    <div class="flex items-center justify-between mb-5">
      <div>
        <h2 class="text-lg font-bold text-gray-800">数据看板</h2>
        <p class="text-xs text-gray-400 mt-0.5">实时掌握商城运营核心指标</p>
      </div>
      <span class="text-xs text-gray-400">{{ today }}</span>
    </div>

    <!-- 统计卡片 -->
    <div class="grid grid-cols-4 gap-4 mb-5">
      <div
        v-for="(stat, i) in stats"
        :key="i"
        class="page-card p-5 relative overflow-hidden group hover:shadow-lift hover:-translate-y-0.5 transition-all duration-300"
      >
        <div
          class="absolute -right-5 -top-5 w-20 h-20 rounded-full opacity-[.07] group-hover:scale-125 transition-transform duration-500"
          :style="{ background: stat.color }"
        ></div>
        <div class="flex items-start justify-between">
          <div>
            <p class="text-xs text-gray-400 mb-2">{{ stat.title }}</p>
            <p class="text-[26px] font-bold text-gray-800 leading-none tabular-nums">{{ stat.value }}</p>
            <p class="flex items-center gap-1 text-xs mt-2.5" :class="stat.trendUp ? 'text-green-500' : 'text-[#F56C6C]'">
              <TrendingUp v-if="stat.trendUp" class="w-3.5 h-3.5" />
              <TrendingDown v-else class="w-3.5 h-3.5" />
              {{ stat.trend }}
              <span class="text-gray-300 ml-0.5">{{ stat.trendLabel }}</span>
            </p>
          </div>
          <div
            class="w-11 h-11 rounded-xl flex items-center justify-center flex-shrink-0"
            :style="{ background: stat.color + '1a' }"
          >
            <component :is="stat.icon" class="w-5.5 h-5.5" :style="{ color: stat.color }" />
          </div>
        </div>
      </div>
    </div>

    <!-- 图表 -->
    <div class="grid grid-cols-2 gap-4">
      <div class="page-card p-5">
        <div class="flex items-center justify-between mb-3">
          <h3 class="text-sm font-semibold text-gray-800">销售趋势</h3>
          <span class="text-[11px] text-gray-300 bg-gray-50 rounded-full px-2.5 py-1">近 7 日</span>
        </div>
        <div ref="lineChartRef" class="h-72"></div>
      </div>
      <div class="page-card p-5">
        <div class="flex items-center justify-between mb-3">
          <h3 class="text-sm font-semibold text-gray-800">分类销售占比</h3>
          <span class="text-[11px] text-gray-300 bg-gray-50 rounded-full px-2.5 py-1">全渠道</span>
        </div>
        <div ref="pieChartRef" class="h-72"></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, markRaw, onMounted, onUnmounted } from 'vue'
import {
  ShoppingCart,
  ClipboardList,
  Users,
  Package,
  TrendingUp,
  TrendingDown,
} from 'lucide-vue-next'
import * as echarts from 'echarts'

const lineChartRef = ref<HTMLElement>()
const pieChartRef = ref<HTMLElement>()
let lineChart: echarts.ECharts | null = null
let pieChart: echarts.ECharts | null = null

const today = computed(() =>
  new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })
)

// 仪表盘演示数据（统计接口未实现，保持硬编码）
const stats = [
  {
    title: '今日销售额', value: '¥ 12,580', icon: markRaw(ShoppingCart),
    color: '#409EFF', trendUp: true, trend: '12.5%', trendLabel: '较昨日',
  },
  {
    title: '今日订单量', value: '486', icon: markRaw(ClipboardList),
    color: '#E4393C', trendUp: true, trend: '8.2%', trendLabel: '较昨日',
  },
  {
    title: '今日新增用户', value: '128', icon: markRaw(Users),
    color: '#7c3aed', trendUp: false, trend: '3.1%', trendLabel: '较昨日',
  },
  {
    title: '商品总数', value: '1,250', icon: markRaw(Package),
    color: '#10b981', trendUp: true, trend: '2.4%', trendLabel: '较上周',
  },
]

const resizeHandler = () => {
  lineChart?.resize()
  pieChart?.resize()
}

onMounted(() => {
  // 折线图：销售趋势（渐变面积图）
  if (lineChartRef.value) {
    lineChart = echarts.init(lineChartRef.value)
    lineChart.setOption({
      tooltip: { trigger: 'axis', borderWidth: 0, textStyle: { fontSize: 12 } },
      grid: { left: 10, right: 14, bottom: 6, top: 30, containLabel: true },
      xAxis: {
        type: 'category',
        data: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'],
        axisLine: { lineStyle: { color: '#e8eaee' } },
        axisTick: { show: false },
        axisLabel: { color: '#9aa1ad', fontSize: 11 },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: '#f2f4f7' } },
        axisLabel: { color: '#9aa1ad', fontSize: 11 },
      },
      series: [
        {
          name: '销售额',
          data: [820, 932, 901, 934, 1290, 1330, 1258],
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 7,
          lineStyle: { width: 3, color: '#409EFF' },
          itemStyle: { color: '#409EFF', borderColor: '#fff', borderWidth: 2 },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(64,158,255,.28)' },
              { offset: 1, color: 'rgba(64,158,255,.02)' },
            ]),
          },
        },
      ],
    })
  }

  // 饼图：分类销售占比（环形）
  if (pieChartRef.value) {
    pieChart = echarts.init(pieChartRef.value)
    pieChart.setOption({
      tooltip: { trigger: 'item', borderWidth: 0, textStyle: { fontSize: 12 } },
      legend: {
        bottom: 0,
        icon: 'circle',
        itemWidth: 8,
        itemHeight: 8,
        textStyle: { color: '#9aa1ad', fontSize: 11 },
      },
      color: ['#409EFF', '#E4393C', '#7c3aed', '#10b981', '#FF6B35'],
      series: [
        {
          type: 'pie',
          radius: ['48%', '70%'],
          center: ['50%', '44%'],
          avoidLabelOverlap: true,
          itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
          label: { show: false },
          emphasis: {
            label: { show: true, fontSize: 13, fontWeight: 600, color: '#2b2f36' },
            scaleSize: 6,
          },
          data: [
            { value: 320, name: '数码电子' },
            { value: 240, name: '家用电器' },
            { value: 180, name: '服装鞋帽' },
            { value: 150, name: '食品生鲜' },
            { value: 100, name: '图书音像' },
          ],
        },
      ],
    })
  }

  window.addEventListener('resize', resizeHandler)
})

onUnmounted(() => {
  window.removeEventListener('resize', resizeHandler)
  lineChart?.dispose()
  pieChart?.dispose()
})
</script>

<style scoped>
.w-5\.5 {
  width: 22px;
}

.h-5\.5 {
  height: 22px;
}
</style>
