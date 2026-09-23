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
      <el-tabs v-model="activeTab">
        <!-- Tab1 手动打标:勾选多张表 → 全部打上选中标记(只增不删) -->
        <el-tab-pane label="手动打标" name="manual">
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
        </el-tab-pane>

        <!-- Tab2 AI 打标:候选标记交后台任务逐表选其一 -->
        <el-tab-pane label="AI 打标" name="ai">
          <div class="tip">对勾选的 {{ tableNames.length }} 张表逐张交给大模型打标(备份表自动跳过,不抽样业务数据)</div>

          <!-- 候选 = 可用于 AI 打标的 USER 标记(kind=USER 且 tagType=AI) -->
          <div class="section-title">候选标记</div>
          <el-checkbox-group v-model="checkedAiTagIds" :disabled="aiSubmitting">
            <el-checkbox v-for="tag in aiTags" :key="tag.id" :value="tag.id">
              <span class="tag-dot" :style="{ background: tag.color }"></span>{{ tag.name }}
            </el-checkbox>
          </el-checkbox-group>
          <div v-if="!aiTags.length" class="empty-tip">暂无可用于 AI 打标的标记,可在「标记统计」页将标记类型改为「可用于AI打标」</div>

          <div class="ai-tip">选中的标记将作为候选发给大模型,由模型为每张表选择最合适的标签;模型认为没有合适的标记时该表不打标。任务后台异步执行,可在右上角任务中心查看进度。</div>
        </el-tab-pane>
      </el-tabs>
    </div>
    <template #footer>
      <template v-if="activeTab === 'manual'">
        <el-button @click="$emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">确定</el-button>
      </template>
      <template v-else>
        <el-button :disabled="aiSubmitting" @click="$emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" :loading="aiSubmitting" :disabled="!checkedAiTagIds.length" @click="startAiTag">
          开始打标
        </el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from '../utils/notify'
import request from '../api'
import { confirmAiUsable } from '../utils/aiCheck'
import { watchTask } from '../stores/backgroundTasks'
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
// 当前页签:手动打标 / AI 打标(打开时重置回手动)
const activeTab = ref('manual')
// 全部 USER 标记(空表/备份表标记是系统驱动,不参与勾选)
const userTags = ref([])
const checkedTagIds = ref([])
// 可用于 AI 打标的 USER 标记(kind=USER 且 tagType=AI),默认全选作为候选
const aiTags = ref([])
const checkedAiTagIds = ref([])
// AI 打标提交态(任务后台异步执行,提交即关窗,进度由任务中心跟踪)
const aiSubmitting = ref(false)
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
    aiTags.value = userTags.value.filter((t) => t.tagType === 'AI')
    checkedAiTagIds.value = aiTags.value.map((t) => t.id)
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
  activeTab.value = 'manual'
  checkedTagIds.value = []
  aiSubmitting.value = false
  createFormRef.value?.reset()
  fetchTags()
}

// 确定(手动页签):对勾选的表批量确保打上选中标记(幂等,已存在的跳过),返回 新增/跳过 计数
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

// 开始打标(AI 页签):先校验 AI 可用性,提交后台任务即返回(taskId),
// 进度/完成通知由全局后台任务跟踪器接管(stores/backgroundTasks.js 的 ai-tag kind)
async function startAiTag() {
  if (!checkedAiTagIds.value.length) {
    ElMessage.warning('请勾选候选标记')
    return
  }
  if (!(await confirmAiUsable({ autoTag: true, genDoc: false }))) return
  aiSubmitting.value = true
  try {
    const base = `/datasources/${props.dsId}/schemas/${encodeURIComponent(props.schema)}`
    const res = await request.post(`${base}/ai-tag-batch${dbQuery()}`, {
      tableNames: props.tableNames,
      tagIds: checkedAiTagIds.value
    })
    watchTask() // 登记到全局后台任务跟踪器:关弹窗后仍可从头部任务入口看进度、收完成通知
    emit('saved')
    emit('update:modelValue', false)
    ElMessage.success(`已提交 AI 打标任务 #${res?.taskId},可在右上角任务中心查看进度`)
  } finally {
    aiSubmitting.value = false
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
.ai-tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-top: 12px;
  line-height: 1.6;
}
</style>
