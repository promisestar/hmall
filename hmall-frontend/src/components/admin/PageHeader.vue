<template>
  <div class="flex items-center justify-between mb-4">
    <div>
      <h2 class="text-lg font-bold text-gray-800">{{ title }}</h2>
      <p v-if="description" class="text-xs text-gray-400 mt-0.5">{{ description }}</p>
    </div>
    <slot />
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>
