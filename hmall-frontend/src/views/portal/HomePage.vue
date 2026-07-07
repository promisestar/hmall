<template>
  <PortalLayout :show-search="true">
    <!-- Banner -->
    <div class="bg-white">
      <div class="container-main pt-4">
        <div class="flex gap-2">
          <!-- Categories -->
          <div class="w-[230px] flex-shrink-0 bg-[#F5F5F5] rounded overflow-hidden">
            <div
              v-for="cat in categories"
              :key="cat.name"
              class="px-4 py-2.5 text-sm hover:bg-white hover:text-[#E4393C] cursor-pointer transition-colors border-b border-gray-200 last:border-0"
            >
              {{ cat.name }}
            </div>
          </div>

          <!-- Banner Carousel -->
          <div class="flex-1 overflow-hidden rounded">
            <el-carousel :interval="4000" height="420px" indicator-position="inside">
              <el-carousel-item v-for="(banner, idx) in banners" :key="idx">
                <div class="w-full h-full flex items-center justify-center bg-gradient-to-r from-red-500 to-orange-500">
                  <div class="text-center text-white">
                    <h2 class="text-4xl font-bold mb-4">{{ banner.title }}</h2>
                    <p class="text-xl opacity-90">{{ banner.subtitle }}</p>
                  </div>
                </div>
              </el-carousel-item>
            </el-carousel>
          </div>

          <!-- Side Panel -->
          <div class="w-[200px] flex-shrink-0">
            <div class="bg-white rounded shadow-sm p-4 mb-2">
              <h4 class="font-bold text-sm mb-3 border-b pb-2">黑马快报</h4>
              <ul class="space-y-2 text-xs text-gray-600">
                <li v-for="n in 5" :key="n" class="flex items-center gap-1">
                  <span class="text-[#E4393C] font-bold">[特惠]</span>
                  备战开学季 全民半价购数码
                </li>
              </ul>
            </div>
            <div class="bg-white rounded shadow-sm p-3">
              <div class="grid grid-cols-3 gap-2 text-center text-xs">
                <div v-for="s in services" :key="s" class="py-1 hover:text-[#E4393C] cursor-pointer transition-colors">
                  {{ s }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Recommended -->
    <div class="container-main mt-6">
      <div class="bg-white rounded-lg shadow-sm p-4">
        <h3 class="text-lg font-bold mb-4 flex items-center gap-2">
          <img src="/img/clock.png" class="w-6 h-6" />
          今日推荐
        </h3>
        <div class="grid grid-cols-4 gap-4">
          <div v-for="n in 4" :key="n" class="card-shadow p-2 hover:-translate-y-1 transition-transform cursor-pointer">
            <img :src="`/img/today0${n}.png`" class="w-full h-40 object-cover rounded" />
          </div>
        </div>
      </div>
    </div>

    <!-- Guess You Like -->
    <div class="container-main mt-6">
      <div class="bg-white rounded-lg shadow-sm p-4">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-bold">猜你喜欢</h3>
          <span class="text-sm text-gray-500 cursor-pointer hover:text-[#E4393C]">换一换</span>
        </div>
        <div class="grid grid-cols-6 gap-3">
          <div
            v-for="n in 6"
            :key="n"
            class="card-shadow p-2 hover:-translate-y-1 transition-transform cursor-pointer"
          >
            <img :src="`/img/like_0${n > 3 ? n - 3 : n}.png`" class="w-full h-36 object-cover rounded mb-2" />
            <p class="text-xs text-gray-600 line-clamp-2">精品商品推荐 {{ n }}</p>
            <p class="text-[#E4393C] font-bold text-sm mt-1">¥116.00</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Floor: Appliances -->
    <div class="container-main mt-6">
      <div class="bg-white rounded-lg shadow-sm p-4">
        <div class="flex items-center justify-between mb-4 border-b pb-3">
          <h3 class="text-lg font-bold">家用电器</h3>
          <div class="flex gap-4 text-sm">
            <span class="text-[#E4393C] font-medium cursor-pointer">热门</span>
            <span class="text-gray-500 hover:text-[#E4393C] cursor-pointer">大家电</span>
            <span class="text-gray-500 hover:text-[#E4393C] cursor-pointer">生活电器</span>
            <span class="text-gray-500 hover:text-[#E4393C] cursor-pointer">厨房电器</span>
          </div>
        </div>
        <div class="grid grid-cols-5 gap-3">
          <div v-for="n in 10" :key="n" class="text-center hover:shadow-md transition-shadow p-2 rounded cursor-pointer">
            <img src="/img/like_01.png" class="w-full h-32 object-cover rounded mb-2" />
            <p class="text-xs text-gray-600 truncate">精品家电商品</p>
            <p class="text-[#E4393C] font-medium text-sm">¥299.00</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Floor: Phones -->
    <div class="container-main mt-6">
      <div class="bg-white rounded-lg shadow-sm p-4">
        <div class="flex items-center justify-between mb-4 border-b pb-3">
          <h3 class="text-lg font-bold">手机通讯</h3>
          <div class="flex gap-4 text-sm">
            <span class="text-[#E4393C] font-medium cursor-pointer">热门</span>
            <span class="text-gray-500 hover:text-[#E4393C] cursor-pointer">新机尝鲜</span>
            <span class="text-gray-500 hover:text-[#E4393C] cursor-pointer">高性价比</span>
          </div>
        </div>
        <div class="grid grid-cols-5 gap-3">
          <div v-for="n in 10" :key="n" class="text-center hover:shadow-md transition-shadow p-2 rounded cursor-pointer">
            <img src="/img/like_02.png" class="w-full h-32 object-cover rounded mb-2" />
            <p class="text-xs text-gray-600 truncate">精品手机商品</p>
            <p class="text-[#E4393C] font-medium text-sm">¥3999.00</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Brand -->
    <div class="container-main mt-6">
      <div class="bg-white rounded-lg shadow-sm p-4">
        <div class="grid grid-cols-10 gap-4">
          <img v-for="n in 10" :key="n" :src="`/img/brand${n < 10 ? '0' + n : n}.png`" class="h-10 mx-auto object-contain grayscale hover:grayscale-0 transition-all" />
        </div>
      </div>
    </div>
  </PortalLayout>
</template>

<script setup lang="ts">
import PortalLayout from './PortalLayout.vue'

const categories = [
  { name: '图书、音像、数字商品' },
  { name: '家用电器' },
  { name: '手机、数码' },
  { name: '电脑、办公' },
  { name: '家居、家具、家装' },
  { name: '服饰内衣' },
  { name: '个护化妆' },
  { name: '运动健康' },
  { name: '汽车用品' },
  { name: '母婴、玩具' },
  { name: '食品生鲜' },
  { name: '箱包手袋' },
]

const banners = [
  { title: '亿万元优惠', subtitle: '开学季大促 满99减30' },
  { title: '手机数码节', subtitle: '新品首发 低至5折' },
  { title: '家电焕新季', subtitle: '以旧换新 最高补贴1000元' },
]

const services = ['话费', '机票', '电影票', '游戏', '彩票', '加油站', '酒店', '火车票', '众筹', '理财', '礼品卡', '白条']
</script>
