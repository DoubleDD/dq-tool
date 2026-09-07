<template>
  <el-dialog
    :model-value="modelValue"
    :title="`打标 - ${tableName}`"
    width="720px"
    append-to-body
    :close-on-press-escape="false"
    @update:model-value="$emit('update:modelValue', $event)"
    @open="onOpen"
  >
    <div v-loading="loading">
      <!-- 当前表标记勾选(空表标记由系统驱动,不列出);标记的编辑/删除统一在「标记统计」页维护 -->
      <div class="section-title">当前表标记</div>
      <el-checkbox-group v-model="checkedTagIds">
        <el-checkbox v-for="tag in userTags" :key="tag.id" :value="tag.id">
          <span class="tag-dot" :style="{ background: tag.color }"></span>{{ tag.name }}
        </el-checkbox>
      </el-checkbox-group>
      <div v-if="!userTags.length" class="empty-tip">暂无可用标记,可在下方新建</div>

      <el-divider />

      <!-- 新建标记(列表中没有想要的时候就地建;编辑/删除在「标记统计」页;新建区与批量打标弹窗共用 TagCreateForm) -->
      <div class="section-title">新建标记</div>
      <TagCreateForm ref="createFormRef" @created="onTagCreated" />
    </div>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from '../utils/notify'
import request from '../api'
import TagCreateForm from './TagCreateForm.vue'

const props = defineProps({
  modelValue: Boolean,
  dsId: [String, Number],
  schema: String,
  db: { type: String, default: '' },
  tableName: { type: String, default: '' },
  // 该表当前全部标记(含空表标记),勾选初始值只取其中的 USER 标记
  currentTags: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const loading = ref(false)
const saving = ref(false)
// 全部 USER 标记(空表标记是系统驱动,不参与勾选)
const userTags = ref([])
const checkedTagIds = ref([])
// 就地新建区(共享组件),弹窗打开时 reset 清掉上次输入
const createFormRef = ref(null)

function dbQuery() {
  return props.db ? `?db=${encodeURIComponent(props.db)}` : ''
}

function tableTagsUrl() {
  const base = `/datasources/${props.dsId}/schemas/${encodeURIComponent(props.schema)}`
  return `${base}/tables/${encodeURIComponent(props.tableName)}/tags${dbQuery()}`
}

async function fetchTags() {
  loading.value = true
  try {
    const all = await request.get('/tags')
    userTags.value = (all || []).filter((t) => t.kind === 'USER')
  } finally {
    loading.value = false
  }
}

// 新建标记成功:刷新列表并自动勾选新标记(打标时新建的基本就是要打的)
async function onTagCreated(tag) {
  await fetchTags()
  if (tag?.id && !checkedTagIds.value.includes(tag.id)) {
    checkedTagIds.value.push(tag.id)
  }
}

function onOpen() {
  checkedTagIds.value = props.currentTags.filter((t) => t.kind === 'USER').map((t) => t.id)
  createFormRef.value?.reset()
  fetchTags()
}

// 确定:整体替换该表的 USER 标记,返回该表最新标记数组(含空表标记)
async function save() {
  saving.value = true
  try {
    const tags = await request.put(tableTagsUrl(), { tagIds: checkedTagIds.value })
    emit('saved', tags || [])
    emit('update:modelValue', false)
    ElMessage.success('已保存')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.section-title {
  font-weight: 600;
  margin-bottom: 10px;
}
.tag-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: -1px;
}
.empty-tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
