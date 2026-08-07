<template>
  <el-dialog
    :model-value="modelValue"
    :title="`打标 - ${tableName}`"
    width="620px"
    append-to-body
    @update:model-value="$emit('update:modelValue', $event)"
    @open="onOpen"
  >
    <div v-loading="loading">
      <!-- 上半:当前表标记勾选(空表标记由系统驱动,不列出) -->
      <div class="section-title">当前表标记</div>
      <el-checkbox-group v-model="checkedTagIds">
        <el-checkbox v-for="tag in userTags" :key="tag.id" :value="tag.id">
          <span class="tag-dot" :style="{ background: tag.color }"></span>{{ tag.name }}
        </el-checkbox>
      </el-checkbox-group>
      <div v-if="!userTags.length" class="empty-tip">暂无可用标记,可在下方新建</div>

      <el-divider />

      <!-- 下半:标记集中管理(全局,跨数据源共享) -->
      <div class="section-title">标记管理(全局)</div>
      <div class="tag-edit-row">
        <el-input v-model="createForm.name" placeholder="新标记名称" maxlength="50" style="width: 220px" />
        <el-color-picker v-model="createForm.color" :predefine="presetColors" />
        <el-button type="primary" :loading="operating" @click="createTag">新建</el-button>
      </div>
      <div v-for="tag in userTags" :key="tag.id" class="tag-manage-row">
        <template v-if="editingId === tag.id">
          <el-input v-model="editForm.name" maxlength="50" style="width: 200px" />
          <el-color-picker v-model="editForm.color" :predefine="presetColors" />
          <el-button link type="primary" :loading="operating" @click="saveEdit">保存</el-button>
          <el-button link @click="editingId = null">取消</el-button>
        </template>
        <template v-else>
          <span class="tag-dot" :style="{ background: tag.color }"></span>
          <span class="tag-name">{{ tag.name }}</span>
          <span class="tag-count">{{ tag.tableCount }} 张表</span>
          <el-button link type="primary" @click="startEdit(tag)">编辑</el-button>
          <el-button link type="danger" @click="removeTag(tag)">删除</el-button>
        </template>
      </div>
      <div v-if="!userTags.length" class="empty-tip">暂无标记</div>
    </div>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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
const emit = defineEmits(['update:modelValue', 'saved', 'tagsChanged'])

const loading = ref(false)
const saving = ref(false)
const operating = ref(false)
// 全部 USER 标记(空表标记是系统驱动,不参与勾选与管理)
const userTags = ref([])
const checkedTagIds = ref([])
const editingId = ref(null)
const createForm = reactive({ name: '', color: '#409EFF' })
const editForm = reactive({ name: '', color: '#409EFF' })

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
  editingId.value = null
  createForm.name = ''
  fetchTags()
}

// 管理操作后刷新标记列表,并剔除勾选里已不存在的标记(如被删除的)
async function refreshAfterOp() {
  await fetchTags()
  const alive = new Set(userTags.value.map((t) => t.id))
  checkedTagIds.value = checkedTagIds.value.filter((id) => alive.has(id))
  emit('tagsChanged')
}

async function createTag() {
  const name = createForm.name.trim()
  if (!name) {
    ElMessage.warning('标记名称不能为空')
    return
  }
  operating.value = true
  try {
    await request.post('/tags', { name, color: createForm.color || '#409EFF' })
    createForm.name = ''
    ElMessage.success('已创建')
    await refreshAfterOp()
  } finally {
    operating.value = false
  }
}

function startEdit(tag) {
  editingId.value = tag.id
  editForm.name = tag.name
  editForm.color = tag.color
}

async function saveEdit() {
  const name = editForm.name.trim()
  if (!name) {
    ElMessage.warning('标记名称不能为空')
    return
  }
  operating.value = true
  try {
    await request.put(`/tags/${editingId.value}`, { name, color: editForm.color || '#409EFF' })
    editingId.value = null
    ElMessage.success('已保存')
    await refreshAfterOp()
  } finally {
    operating.value = false
  }
}

async function removeTag(tag) {
  await ElMessageBox.confirm(
    `确定删除标记「${tag.name}」吗?已打该标记的 ${tag.tableCount} 张表会自动解除。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除' }
  )
  operating.value = true
  try {
    await request.delete(`/tags/${tag.id}`)
    ElMessage.success('已删除')
    await refreshAfterOp()
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
.tag-manage-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
}
.tag-name {
  min-width: 120px;
}
.tag-count {
  flex: 1;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.empty-tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
