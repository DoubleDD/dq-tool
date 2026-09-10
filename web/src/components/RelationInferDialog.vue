<template>
  <!-- 关系推导对话框:选锚点字段(可配映射字段名)→ 提交推导任务 → 1s 轮询进度(关闭即停轮询) -->
  <el-dialog
    :model-value="modelValue"
    title="推导关联关系"
    width="560px"
    :close-on-click-modal="phase !== 'running'"
    @update:model-value="(v) => emit('update:modelValue', v)"
    @closed="onClosed"
  >
    <!-- 表单阶段:锚点表 + 锚点字段多选 + 语义匹配开关(AI 未配置时置灰) -->
    <el-form v-if="phase === 'form'" label-width="110px">
      <el-form-item label="锚点表">
        <span>{{ tableName }}</span>
      </el-form-item>
      <el-form-item label="锚点字段">
        <el-select
          v-model="selectedColumns"
          multiple
          filterable
          collapse-tags
          collapse-tags-tooltip
          :loading="columnsLoading"
          placeholder="选择参与推导的字段(可多选)"
          style="width: 100%"
        >
          <el-option v-for="c in columns" :key="c.name" :value="c.name" :label="c.name">
            <span>{{ c.name }}</span>
            <span style="float:right;color:var(--el-text-color-secondary);font-size:12px">
              {{ c.displayType || '' }}{{ c.comment ? ` · ${c.comment}` : '' }}
            </span>
          </el-option>
        </el-select>
        <div class="form-tip">推导将在整个库内查找与锚点字段同名或映射名的字段,并验证值交集与基数</div>
      </el-form-item>
      <!-- 映射字段名:每个已选锚点字段一行,自由输入回车成标签(可留空);名字匹配按本名+映射名一起查 -->
      <el-form-item v-if="selectedColumns.length" label="映射字段名">
        <div class="alias-list">
          <div v-for="name in selectedColumns" :key="name" class="alias-row">
            <span class="alias-field" :title="name">{{ name }}</span>
            <el-select
              v-model="aliasMap[name]"
              multiple
              filterable
              allow-create
              default-first-option
              :reserve-keyword="false"
              placeholder="其他表中的字段名,回车添加,如 work_order_id"
              no-data-text="输入后回车添加"
              style="flex: 1"
            />
          </div>
        </div>
        <div class="form-tip">锚点字段在其他表里的叫法(可留空),如 id 被关联表用 work_order_id / workOrderId 引用</div>
      </el-form-item>
      <el-form-item label="只用别名匹配">
        <el-switch v-model="aliasOnly" />
        <span class="form-tip" style="margin-left: 8px">
          只用映射字段名查找,不用锚点字段本名(避免 id 等通用名在全库误命中)
        </span>
      </el-form-item>
      <el-alert
        v-if="aliasOnly && aliasOnlyNoAliases"
        type="warning"
        :closable="false"
        title="已开启只用别名匹配,但所有锚点字段都未填映射字段名,将匹配不到任何字段"
        show-icon
        style="margin-bottom: 12px"
      />
      <el-form-item label="语义匹配">
        <el-switch v-model="useSemantic" :disabled="!aiAvailable" />
        <span class="form-tip" style="margin-left: 8px">
          {{ aiAvailable ? '借助大模型按表/字段注释语义推导,耗时较长' : '语义匹配需先在「系统设置」完成大模型接口配置' }}
        </span>
      </el-form-item>
      <el-alert v-if="columnsError" type="error" :closable="false" :title="columnsError" show-icon />
    </el-form>

    <!-- 运行阶段:阶段中文名 + 进度条(1s 轮询) -->
    <div v-else-if="phase === 'running'" v-loading="!job">
      <template v-if="job">
        <div class="stage-row">
          <span>当前阶段:{{ stageText(job.stage) }}</span>
          <span class="form-tip">{{ job.doneSteps }}/{{ job.totalSteps }}</span>
        </div>
        <el-progress :percentage="percent" :stroke-width="12" striped striped-flow />
      </template>
    </div>

    <!-- 完成阶段:提示发现的候选数 -->
    <el-result
      v-else-if="phase === 'done'"
      icon="success"
      title="推导完成"
      :sub-title="`发现 ${job?.foundCount ?? 0} 条候选关系,可在 ER 图中确认或否决`"
    />

    <!-- 失败阶段:展示后端错误 -->
    <el-result v-else icon="error" title="推导失败" :sub-title="job?.error || '未知错误'" />

    <template #footer>
      <template v-if="phase === 'form'">
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" :disabled="!selectedColumns.length || columnsLoading" :loading="submitting" @click="submit">
          开始推导
        </el-button>
      </template>
      <template v-else-if="phase === 'done'">
        <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      </template>
      <template v-else-if="phase === 'failed'">
        <el-button @click="emit('update:modelValue', false)">关闭</el-button>
        <el-button type="primary" @click="phase = 'form'">重新推导</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import request, { getRelationInferJob, submitRelationInfer } from '../api'

// 关系推导对话框(自成闭环):打开时拉锚点表字段+查 AI 可用性(决定语义开关是否可选) →
// 提交 POST /relation-infer → 1s 轮询任务详情(模式同 SampleExports.vue:终态停轮询) → DONE 提示候选数并 emit done;
// 语义匹配未配置大模型时开关置灰(仅名字匹配),配置完整时可选(默认关)
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  dsId: { type: [String, Number], required: true },
  db: { type: String, default: '' },
  schema: { type: String, required: true },
  tableName: { type: String, required: true }
})

// done:推导成功完成(父级刷新图/提示);payload 为任务详情
const emit = defineEmits(['update:modelValue', 'done'])

// 阶段:form 填表 / running 轮询 / done 完成 / failed 失败
const phase = ref('form')
const columns = ref([])
const columnsLoading = ref(false)
const columnsError = ref('')
const selectedColumns = ref([])
// 各已选锚点字段的映射字段名:{ 字段名: [映射名...] };随勾选变化增删键,提交时组装 fields
const aliasMap = ref({})
const aliasOnly = ref(false) // 只用别名匹配:搜索名集合只含映射字段名,不含锚点字段本名
const useSemantic = ref(false) // 语义匹配开关,默认关;AI 不可用时置灰
const aiAvailable = ref(false)
const submitting = ref(false)
const job = ref(null)

let pollTimer = null

// 勾选变化:为新增字段补空映射数组,丢掉取消勾选字段的映射(提交只按当前勾选项组装)
watch(selectedColumns, (names) => {
  const next = {}
  for (const n of names) next[n] = aliasMap.value[n] || []
  aliasMap.value = next
})

// 只用别名匹配但所有已选字段都没填映射名 → 必然零命中,表单内给出预警(不强制拦截)
const aliasOnlyNoAliases = computed(() =>
  selectedColumns.value.length > 0 &&
  selectedColumns.value.every((n) => !(aliasMap.value[n] || []).some((a) => a && a.trim()))
)

const percent = computed(() => {
  if (!job.value || !job.value.totalSteps) return 0
  return Math.min(100, Math.round((job.value.doneSteps / job.value.totalSteps) * 100))
})

/** 阶段枚举 → 中文名 */
function stageText(stage) {
  return { NAME_MATCH: '名字匹配', SEMANTIC_TABLE: '语义筛选', SEMANTIC_COLUMN: '字段精判', VERIFY: '验证' }[stage] || '准备中'
}

/** 打开对话框时拉锚点表字段列表(与字段明细页同一 API);主键字段默认勾选 */
async function loadColumns() {
  columnsLoading.value = true
  columnsError.value = ''
  columns.value = []
  selectedColumns.value = []
  aliasMap.value = {}
  try {
    const base = `/datasources/${props.dsId}/schemas/${encodeURIComponent(props.schema)}`
    const q = props.db ? `?db=${encodeURIComponent(props.db)}` : ''
    const list = await request.get(`${base}/tables/${encodeURIComponent(props.tableName)}/columns${q}`)
    columns.value = list || []
    selectedColumns.value = columns.value.filter((c) => c.primaryKey).map((c) => c.name)
  } catch {
    columnsError.value = '字段列表加载失败,请关闭后重试'
  } finally {
    columnsLoading.value = false
  }
}

/** 查 AI 可用性(GET /ai-config 的 available,秒回;失败按不可用置灰,与 aiCheck.js 同口径) */
async function loadAiAvailable() {
  aiAvailable.value = false
  useSemantic.value = false
  const cfg = await request.get('/ai-config', { _silent: true }).catch(() => null)
  aiAvailable.value = !!cfg?.available
}

async function submit() {
  if (!selectedColumns.value.length || submitting.value) return
  submitting.value = true
  try {
    // fields 契约:每字段 name + aliases(本名之外的映射字段名,去空串去重,可空数组)
    const fields = selectedColumns.value.map((name) => ({
      name,
      aliases: [...new Set((aliasMap.value[name] || []).map((a) => a.trim()).filter(Boolean))]
    }))
    const res = await submitRelationInfer({
      datasourceId: /^\d+$/.test(String(props.dsId)) ? Number(props.dsId) : props.dsId,
      dbName: props.db || null,
      schemaName: props.schema,
      table: props.tableName,
      fields,
      useSemantic: useSemantic.value,
      aliasOnly: aliasOnly.value
    })
    phase.value = 'running'
    job.value = null
    startPolling(res.jobId)
  } catch {
    // 提交失败由响应拦截器弹出提示,停留在表单阶段
  } finally {
    submitting.value = false
  }
}

/** 1s 轮询任务详情;DONE/FAILED 终态停轮询 */
function startPolling(jobId) {
  stopPolling()
  const tick = async () => {
    try {
      const detail = await getRelationInferJob(jobId)
      job.value = detail
      if (detail.status === 'DONE') {
        stopPolling()
        phase.value = 'done'
        emit('done', detail)
      } else if (detail.status === 'FAILED') {
        stopPolling()
        phase.value = 'failed'
      }
    } catch { /* 单次轮询失败维持旧状态,下个周期重试 */ }
  }
  tick()
  pollTimer = setInterval(tick, 1000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(() => props.modelValue, (v) => {
  if (v) {
    phase.value = 'form'
    job.value = null
    loadColumns()
    loadAiAvailable()
  } else {
    // 关闭对话框:停轮询(任务在后端继续跑,重开不追溯)
    stopPolling()
  }
})

function onClosed() {
  stopPolling()
}

onUnmounted(stopPolling)
</script>

<style scoped>
.stage-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
/* 映射字段名:每行 = 锚点字段名(定宽省略) + 自由输入标签框;多选字段时限高滚动 */
.alias-list {
  width: 100%;
  max-height: 180px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.alias-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.alias-field {
  width: 120px;
  flex-shrink: 0;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
