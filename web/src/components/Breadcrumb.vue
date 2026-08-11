<template>
  <el-breadcrumb separator="/" class="page-breadcrumb">
    <el-breadcrumb-item v-for="item in items" :key="item.label" :to="item.to">
      {{ item.label }}
    </el-breadcrumb-item>
  </el-breadcrumb>
</template>

<script setup>
defineProps({
  items: { type: Array, default: () => [] }
})
</script>
