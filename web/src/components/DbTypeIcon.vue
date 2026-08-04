<template>
  <span v-if="conf.svg" class="db-icon" :style="{ color: conf.color, width: size + 'px', height: size + 'px' }" v-html="conf.svg"></span>
  <span v-else class="db-badge" :style="{ background: conf.color, width: size + 'px', height: size + 'px', fontSize: Math.round(size * 0.32) + 'px' }">{{ conf.letters }}</span>
</template>

<script setup>
import { computed } from 'vue'
import mysql from '../assets/dbicons/mysql.svg?raw'
import postgresql from '../assets/dbicons/postgresql.svg?raw'
import oracle from '../assets/dbicons/oracle.svg?raw'
import sqlserver from '../assets/dbicons/microsoftsqlserver.svg?raw'

// 有官方 logo 的用 SVG,国产库(DM/Kingbase/OceanBase)用字母徽标兜底
const DB_MAP = {
  MYSQL: { svg: mysql, color: '#4479A1' },
  POSTGRESQL: { svg: postgresql, color: '#4169E1' },
  ORACLE: { svg: oracle, color: '#F80000' },
  SQLSERVER: { svg: sqlserver, color: '#CC2927' },
  DM: { letters: 'DM', color: '#D9001B' },
  KINGBASE: { letters: 'KB', color: '#2B5AED' },
  OCEANBASE: { letters: 'OB', color: '#1E6FFF' }
}
const FALLBACK = { letters: 'DB', color: '#909399' }

const props = defineProps({
  type: { type: String, default: '' },
  size: { type: Number, default: 18 }
})
const conf = computed(() => DB_MAP[props.type] || FALLBACK)
</script>

<style scoped>
.db-icon {
  display: inline-flex;
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}
.db-icon :deep(svg) {
  width: 100%;
  height: 100%;
  fill: currentColor;
}
.db-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 4px;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  flex-shrink: 0;
}
</style>
