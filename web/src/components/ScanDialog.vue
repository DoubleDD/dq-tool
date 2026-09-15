<template>
  <el-dialog :model-value="modelValue" title="开始扫描" width="640px" destroy-on-close :close-on-press-escape="false"
             @update:model-value="(v) => emit('update:modelValue', v)">
    <el-form label-width="110px">
      <el-form-item label="扫描范围">
        <div style="width: 100%">
          <!-- 单表选择模式(扫描详情页):从传入表清单中选一张重新扫描 -->
          <template v-if="selectableTables && selectableTables.length">
            <el-select v-model="scanForm.table" placeholder="选择要扫描的表" style="width: 280px">
              <el-option v-for="t in selectableTables" :key="t" :label="t" :value="t" />
            </el-select>
            <div class="form-tip">从当前任务的表清单中选择一张表重新扫描</div>
          </template>
          <span v-else-if="scopeLabel">{{ scopeLabel }}</span>
          <template v-else>
            <span v-if="fixedTable">仅扫描表:{{ fixedTable }}</span>
            <span v-else-if="selectedTables.length">已选 {{ selectedTables.length }} 张表</span>
            <span v-else>未选择表,将扫描全库</span>
          </template>
        </div>
      </el-form-item>
      <el-form-item label="强制全量">
        <el-switch v-model="scanForm.forceFull" />
        <div class="form-tip">超过阈值的表不做采样,逐行精确统计</div>
      </el-form-item>
      <el-form-item label="采样行数">
        <div style="width: 100%">
          <el-input-number v-model="scanForm.sampleRows" :min="1" placeholder="全局默认"
                           controls-position="right" style="width: 160px" />
          <div class="form-tip">超过阈值(默认 100 万行或 10GB)的表只统计样本的行数,留空使用全局默认值(系统设置可改)</div>
        </div>
      </el-form-item>
      <el-form-item label="表大小上限">
        <div style="width: 100%">
          <el-input-number v-model="scanForm.maxSizeValue" :min="1" placeholder="不限制"
                           controls-position="right" style="width: 160px" />
          <el-select v-model="scanForm.maxSizeUnit" style="width: 80px; margin-left: 8px">
            <el-option label="MB" value="MB" />
            <el-option label="GB" value="GB" />
          </el-select>
          <div class="form-tip">只扫描不超过该大小的表(按元数据估算的数据+索引大小),留空表示不限制</div>
          <div v-if="skippedBySize" class="form-tip" style="color: var(--el-color-warning)">
            当前范围内有 {{ skippedBySize }} 张表超过上限,将被跳过
          </div>
        </div>
      </el-form-item>
      <el-form-item label="AI 自动打标">
        <el-checkbox v-model="scanForm.autoTag">扫描完成后由大模型自动打标</el-checkbox>
        <el-tooltip placement="top" :show-after="200">
          <template #content>
            <div>每张表扫描完成后,由大模型根据表注释/字段注释/表描述,从标记列表中选择最合适的标记自动打上</div>
            <div>对已有标记的表默认跳过,也可在下方选择增量追加或全量覆盖(仅替换 AI 旧标记,人工标记保留)</div>
            <div>表无任何注释时会抽样 100 行业务数据一并发送给大模型;未配置大模型时自动跳过</div>
          </template>
          <el-icon style="vertical-align: -2px; margin-left: 4px"><QuestionFilled /></el-icon>
        </el-tooltip>
      </el-form-item>
      <el-form-item v-if="scanForm.autoTag" label="已有标签的表">
        <div style="width: 100%">
          <el-radio-group v-model="scanForm.autoTagMode">
            <el-radio value="SKIP">跳过</el-radio>
            <el-radio value="APPEND">增量追加</el-radio>
            <el-radio value="OVERWRITE">全量覆盖</el-radio>
          </el-radio-group>
          <div class="form-tip">跳过=不动已有标记;增量追加=保留旧标记再追加新标记;全量覆盖=只替换 AI 旧标记,人工标记保留</div>
        </div>
      </el-form-item>
      <el-form-item label="生成表描述">
        <el-checkbox v-model="scanForm.genDoc">扫描完成后由大模型生成表描述</el-checkbox>
        <el-tooltip placement="top" :show-after="200">
          <template #content>
            <div>每张表扫描完成后,由大模型根据表结构生成表描述;已有描述的表不覆盖</div>
            <div>未配置大模型时自动跳过</div>
          </template>
          <el-icon style="vertical-align: -2px; margin-left: 4px"><QuestionFilled /></el-icon>
        </el-tooltip>
      </el-form-item>
      <el-form-item label="并发线程数">
        <el-input-number v-model="scanForm.workers" :min="1" :max="128" placeholder="默认"
                         controls-position="right" style="width: 160px" />
        <div class="form-tip">扫描并发 worker 线程数,留空使用配置默认值({{ defaultWorkers ?? '—' }});增大可加速但会增加数据库负载</div>
      </el-form-item>
      <el-form-item label="空值规则">
        <div style="width: 100%">
          <div v-for="(rule, idx) in scanForm.nullRules" :key="idx" class="rule-row">
            <el-input v-model="rule.column" placeholder="列名(* 表示所有列)" style="width: 180px" />
            <el-input v-model="rule.valuesText" placeholder="视为空的取值,逗号分隔" style="flex: 1" />
            <el-button link type="danger" @click="scanForm.nullRules.splice(idx, 1)">删除</el-button>
          </div>
          <el-button link type="primary" @click="scanForm.nullRules.push({ column: '', valuesText: '' })">
            + 添加规则
          </el-button>
          <div class="form-tip">例如:列名 *,取值 0,-1 表示所有列中值为 0 或 -1 的也视为空</div>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">提交扫描</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '../utils/notify'
import { QuestionFilled } from '@element-plus/icons-vue'
import request from '../api'
import { confirmAiUsable } from '../utils/aiCheck'

/**
 * 扫描对话框(表列表页/库列表页/扫描详情页共用):
 * 字段与提交逻辑与原先表列表页/库列表页各自内联的弹窗完全一致,另含两个新增任务级字段:
 * 采样行数(sampleRows,留空=全局默认)与 AI 打标模式(autoTagMode,对已有标记的表 跳过/追加/覆盖)。
 * 模式:
 * - 默认:扫 props.schema/props.database,范围 单表(fixedTable)> 勾选(selectedTables)> 全库;
 * - selectableTables:范围变为表清单下拉,选一张重新扫描(扫描详情页);
 * - targets(整库多目标,库列表页批量扫描):逐库提交后不跳转,emit submitted(null)。
 * 提交成功:单目标跳 /scans/:jobId;AI 可用性校验走 confirmAiUsable。
 */
const props = defineProps({
  modelValue: Boolean,
  datasourceId: { type: [Number, String], required: true },
  schema: { type: String, default: '' },
  database: { type: String, default: '' },
  /** 单表模式:定死只扫这一张表,范围项显示「仅扫描表:xxx」 */
  fixedTable: { type: String, default: '' },
  /** 勾选模式的表行(含 name/sizeBytes) */
  selectedTables: { type: Array, default: () => [] },
  /** 大小上限跳过提示用的范围内表清单(含 sizeBytes);null 不提示 */
  scopeTables: { type: Array, default: null },
  /** 自定义范围文案(库列表整库批量扫描) */
  scopeLabel: { type: String, default: '' },
  /** 表名清单:范围项变为单表下拉选择(扫描详情页「单表扫描」) */
  selectableTables: { type: Array, default: null },
  /** 整库多目标(库列表页批量扫描):[{ schema, database }],逐库提交、不跳转 */
  targets: { type: Array, default: null },
})
const emit = defineEmits(['update:modelValue', 'submitted'])
const router = useRouter()

const submitting = ref(false)
// maxSizeValue/sampleRows 为空(null)表示不限制/用全局默认
const scanForm = reactive({
  forceFull: true, nullRules: [], maxSizeValue: null, maxSizeUnit: 'GB',
  autoTag: true, autoTagMode: 'SKIP', genDoc: true, workers: null, sampleRows: null, table: '',
})

// 打开弹窗时重置表单(默认值与原先两页内联弹窗一致:强制全量开、AI 默认勾选、大小上限留空)
watch(() => props.modelValue, (visible) => {
  if (!visible) return
  scanForm.forceFull = true
  scanForm.nullRules = []
  scanForm.maxSizeValue = null
  scanForm.maxSizeUnit = 'GB'
  // AI 相关默认勾选:未配置大模型时后端自动跳过
  scanForm.autoTag = true
  scanForm.autoTagMode = 'SKIP'
  scanForm.genDoc = true
  scanForm.workers = defaultWorkers.value
  scanForm.sampleRows = null
  scanForm.table = ''
  fetchScanDefaults()
})

// 配置默认的并发 worker 线程数,弹窗中展示并作为「并发线程数」默认值;首次打开时拉取并缓存
const defaultWorkers = ref(null)
let scanDefaultsFetched = false
async function fetchScanDefaults() {
  if (scanDefaultsFetched) return
  scanDefaultsFetched = true
  try {
    const cfg = await request.get('/scans/defaults')
    defaultWorkers.value = cfg.defaultWorkers ?? null
    // 首次拉取到默认值后回填(用户尚未填写时);拉取失败则留空,提交时按后端默认处理
    if (scanForm.workers == null) scanForm.workers = defaultWorkers.value
  } catch {
    // 拉取失败不影响扫描,弹窗中默认值显示 "-"
  }
}

// 表大小上限换算成字节;未设置返回 null
function maxSizeBytes() {
  if (!scanForm.maxSizeValue) return null
  return scanForm.maxSizeValue * (scanForm.maxSizeUnit === 'GB' ? 1073741824 : 1048576)
}

// 范围内将被大小上限跳过的表数量(大小未知的表不参与统计);未传 scopeTables 不提示
const skippedBySize = computed(() => {
  const list = props.scopeTables
  if (!list) return 0
  const limit = maxSizeBytes()
  if (!limit) return 0
  return list.filter((t) => t.sizeBytes != null && t.sizeBytes > limit).length
})

// 提交范围:单表选择 > 单表定死 > 勾选 > 整库(null)
const targetTables = computed(() => {
  if (scanForm.table) return [scanForm.table]
  if (props.fixedTable) return [props.fixedTable]
  return props.selectedTables.length ? props.selectedTables.map((t) => t.name) : null
})

function normalizedDsId() {
  return /^\d+$/.test(String(props.datasourceId)) ? Number(props.datasourceId) : props.datasourceId
}

function buildPayload() {
  const nullRules = scanForm.nullRules
    .filter((r) => r.column.trim() && r.valuesText.trim())
    .map((r) => ({
      column: r.column.trim(),
      values: r.valuesText.split(',').map((v) => v.trim()).filter(Boolean)
    }))
    .filter((r) => r.values.length > 0)
  return {
    forceFull: scanForm.forceFull,
    nullRules,
    maxTableSizeBytes: maxSizeBytes(),
    autoTag: scanForm.autoTag,
    // AI 打标未勾选时模式一并清空(后端只在使用自动打标时读取)
    autoTagMode: scanForm.autoTag ? scanForm.autoTagMode : null,
    genDoc: scanForm.genDoc,
    workers: scanForm.workers || null,
    sampleRows: scanForm.sampleRows || null,
  }
}

async function submit() {
  if (props.selectableTables && props.selectableTables.length && !scanForm.table) {
    ElMessage.warning('请先选择要扫描的表')
    return
  }
  // 勾选了 AI 功能先校验可用性,不可用由用户决定是否继续(继续则后端静默跳过 AI)
  if (!(await confirmAiUsable(scanForm))) return
  submitting.value = true
  try {
    if (props.targets && props.targets.length) {
      await submitMulti()
    } else {
      await submitSingle()
    }
  } finally {
    submitting.value = false
  }
}

/** 单目标提交:成功后跳扫描详情页(带库名标签,供页签标题展示) */
async function submitSingle() {
  const res = await request.post('/scans', {
    datasourceId: normalizedDsId(),
    schema: props.schema,
    database: props.database || null,
    tables: targetTables.value,
    ...buildPayload()
  })
  ElMessage.success('扫描任务已提交')
  emit('update:modelValue', false)
  emit('submitted', res.jobId)
  const schemaLabel = props.database ? `${props.database}.${props.schema}` : props.schema
  router.push(`/scans/${res.jobId}?schema=${encodeURIComponent(schemaLabel)}`)
}

/** 整库多目标提交(库列表页批量扫描):逐库提交、汇总结果、不跳转 */
async function submitMulti() {
  const results = await Promise.allSettled(props.targets.map((row) =>
    request.post('/scans', {
      datasourceId: normalizedDsId(),
      schema: row.schema,
      database: row.database || null,
      tables: null,
      ...buildPayload()
    })
  ))
  const ok = results.filter((r) => r.status === 'fulfilled').length
  if (ok === results.length) {
    ElMessage.success(`已提交 ${ok} 个扫描任务`)
  } else {
    ElMessage.warning(`已提交 ${ok}/${results.length} 个扫描任务,其余提交失败`)
  }
  emit('update:modelValue', false)
  emit('submitted', null)
}
</script>

<style scoped>
.rule-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
</style>
