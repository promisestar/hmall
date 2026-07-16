<template>
  <!-- 头部导航按钮 → 跳转独立聊天页 -->
  <router-link to="/admin/chat">
    <el-button type="primary" text size="small">
      <el-icon class="mr-1"><ChatDotRound /></el-icon>
      AI助手
    </el-button>
  </router-link>
</template>

<script setup lang="ts">
import { ChatDotRound } from '@element-plus/icons-vue'
</script>
