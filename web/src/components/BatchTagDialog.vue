<template>
  <el-dialog
    :model-value="modelValue"
    title="批量打标"
    width="720px"
    append-to-body
    :close-on-press-escape="false"
    @update:model-value="$emit('update:modelValue', $event)"
    @open="onOpen"
  >
    <div v-loading="loading">
      <div class="tip">对勾选的 {{ tableNames.length }} 张表打上选中的标记(只增不删,已有该标记的表自动跳过)</div>

      <!-- 勾选要批量打上的标记(空表标记由系统驱动,不列出);编辑/删除统一在「标记统计」页维护 -->
      <div class="section-title">选择标记</div>
      <el-checkbox-group v-model="checkedTagIds">
        <el-checkbox v-for="tag in userTags" :key="tag.id" :value="tag.id">
          <span class="tag-dot" :style="{ background: tag.color }"></span>{{ tag.name }}
        </el-checkbox>
      </el-checkbox-group>
      <div v-if="!userTags.length" class="empty-tip">暂无可用标记,可在下方新建</div>

      <el-divider />

      <!-- 新建标记(列表中没有想要的时候就地建;编辑/删除在「标记统计」页;新建区与单表打标弹窗共用 TagCreateForm) -->
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
import { ElMessage } from 'element-plus'
import request from '../api'
import TagCreateForm from './TagCreateForm.vue'

const props = defineProps({
  modelValue: Boolean,
  dsId: [String, Number],
  schema: String,
  db: { type: String, default: '' },
  // 勾选的表名列表
  tableNames: { type: Array, default: () => [] }
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
  checkedTagIds.value = []
  createFormRef.value?.reset()
  fetchTags()
}

// 确定:对勾选的表批量确保打上选中标记(幂等,已存在的跳过),返回 新增/跳过 计数
async function save() {
  if (!checkedTagIds.value.length) {
    ElMessage.warning('请勾选要打的标记')
    return
  }
  saving.value = true
  try {
    const base = `/datasources/${props.dsId}/schemas/${encodeURIComponent(props.schema)}`
    const res = await request.put(`${base}/table-tags${dbQuery()}`, {
      tableNames: props.tableNames,
      tagIds: checkedTagIds.value
    })
    emit('saved')
    emit('update:modelValue', false)
    ElMessage.success(`批量打标完成:新增 ${res?.added ?? 0} 条,已存在跳过 ${res?.skipped ?? 0} 条`)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-bottom: 12px;
}
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
