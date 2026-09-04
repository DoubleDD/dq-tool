<template>
  <el-dialog
    :model-value="modelValue"
    :title="`打标 - ${tableName}`"
    width="620px"
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

      <!-- 新建标记(列表中没有想要的时候就地建;编辑/删除在「标记统计」页) -->
      <div class="section-title">新建标记</div>
      <div class="tag-edit-row">
        <el-input v-model="createForm.name" placeholder="新标记名称" maxlength="50" style="width: 220px" />
        <el-color-picker v-model="createForm.color" :predefine="presetColors" />
        <el-button type="primary" :loading="operating" @click="createTag">新建</el-button>
      </div>
      <div class="tag-edit-row">
        <el-input
          v-model="createForm.description"
          placeholder="描述(可选,供 AI 自动打标理解标记含义)"
          maxlength="500"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 3 }"
        />
      </div>
    </div>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../api'

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
const operating = ref(false)
// 全部 USER 标记(空表标记是系统驱动,不参与勾选)
const userTags = ref([])
const checkedTagIds = ref([])
const createForm = reactive({ name: '', color: '#409EFF', description: '' })

// 预设色板
const presetColors = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399', '#9B59B6', '#16A085', '#D35400']

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

function onOpen() {
  checkedTagIds.value = props.currentTags.filter((t) => t.kind === 'USER').map((t) => t.id)
  createForm.name = ''
  createForm.description = ''
  fetchTags()
}

// 新建后刷新标记列表,新标记即可勾选
async function createTag() {
  const name = createForm.name.trim()
  if (!name) {
    ElMessage.warning('标记名称不能为空')
    return
  }
  operating.value = true
  try {
    await request.post('/tags', { name, color: createForm.color || '#409EFF', description: createForm.description.trim() || null })
    createForm.name = ''
    createForm.description = ''
    ElMessage.success('已创建')
    await fetchTags()
  } finally {
    operating.value = false
  }
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
.tag-edit-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}
.empty-tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
