<script setup>
/**
 * 「字段审核」弹窗(批量导入的「待处理」比对任务):内嵌向导第三步的可编辑字段映射画布
 * (CompareFieldMapping,**不是**差异页那个只读 CompareMappingView),用任务详情
 * (GET /api/compare-jobs/{id} 的基准表四元组 + 各目标表既有 mapping)预填;
 * 人工核对/改线后点「确认并开始比对」走 confirm-mapping(校验口径同向导提交:
 * 映射的基准字段必须在比对字段内、目标列必须存在、映射必须含比对主键),
 * 确认后任务 PENDING→RUNNING 进执行器,弹窗关闭并通知列表刷新。
 *
 * 数据源异常(pendingReason=DS_ERROR)的任务:画布上方红条提示先修数据源再回来确认
 * (confirm-mapping 是 DS_ERROR 任务的复活出口,确认时后端会重读两侧字段重验);
 * 导入异常(IMPORT_ERROR,基准表读不出/缺身份字段)的任务画布加载不出字段,提示走「编辑」向导修正。
 * 工具条左侧「AI 预生成字段映射」与向导第三步同款(列级对比任务才显示):大模型按基准表全字段
 * 逐目标产出建议并整组回填(替换现有连线,含导入时的推导结果),人工核对后再确认。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from '../utils/notify'
import request, { getCompareJob, confirmCompareMapping, suggestCompareMapping } from '../api'
import CompareFieldMapping from './CompareFieldMapping.vue'

const props = defineProps({
  // 弹窗显隐(v-model)
  modelValue: { type: Boolean, default: false },
  // 待审核的比对任务 id(PENDING)
  jobId: { type: [Number, String], default: null }
})
const emit = defineEmits(['update:modelValue', 'confirmed'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const detail = ref(null)
const loading = ref(false)
const confirming = ref(false)
// 「AI 预生成字段映射」进行中(与向导第三步同接口:逐目标逐次调大模型,耗时可到分钟级)
const aiSuggesting = ref(false)
// 画布 v-model:与 targets 同序,元素为 { 基准字段名: 目标列名 }
const mappings = ref([])

const job = computed(() => detail.value?.job || null)
const targets = computed(() => detail.value?.targets || [])

/** 基准表定位串:多库方言带库名 db.schema.table,否则 schema.table(与列表页 baseTableLabel 同口径) */
function locOf(spec) {
  const schemaPart = spec.db ? `${spec.db}.${spec.schema || ''}` : (spec.schema || '')
  return schemaPart ? `${schemaPart}.${spec.table}` : spec.table
}

// 交给映射画布的基准表四元组(任务详情字段直填;db 内核归一为空串,画布按 falsy 处理)
const baseSpec = computed(() => {
  const j = job.value
  if (!j) return null
  return { datasourceId: j.baseDatasourceId, db: j.baseDb || '', schema: j.baseSchema || '', table: j.baseTable }
})

const baseLabel = computed(() => (job.value ? locOf(baseSpec.value) : ''))

/** 交给映射画布的对比表清单(带展示名;dsName 为建任务时的数据源名称快照) */
const mappingTargets = computed(() => targets.value.map((t) => ({
  datasourceId: t.datasourceId,
  db: t.db || '',
  schema: t.schema || '',
  table: t.table,
  label: `${t.dsName || `数据源 ${t.datasourceId}`} · ${locOf(t)}`,
  dsName: t.dsName || ''
})))

/** 该对比表是否已把比对主键连上(与向导提交校验同一口径,忽略大小写) */
function isKeyMapped(map) {
  const k = job.value?.keyField
  if (!k) return true
  return Object.keys(map || {}).some((bf) => bf.toLowerCase() === k.toLowerCase())
}

// 弹窗打开即拉详情预填(destroy-on-close,每次打开都是全新画布)
watch(() => props.modelValue, (v) => {
  if (v && props.jobId) load()
})

async function load() {
  loading.value = true
  detail.value = null
  mappings.value = []
  try {
    const d = await getCompareJob(props.jobId)
    if (d?.job?.status !== 'PENDING') {
      ElMessage.warning('任务已不在「待处理」状态,请刷新列表')
      visible.value = false
      return
    }
    detail.value = d
    mappings.value = (d?.targets || []).map((t) => ({ ...(t.mapping || {}) }))
  } catch {
    // 拦截器已提示;详情拉不到就没有可审的内容,直接收起
    visible.value = false
  } finally {
    loading.value = false
  }
}

/** 「AI 预生成字段映射」(与向导第三步同一接口同一口径):基准表全字段交给大模型逐目标建议,
 *  按 targets 同序整体回填(现有连线被替换,人工在画布核对后再确认);预检大模型配置,不完整给引导 */
async function aiSuggest() {
  if (!job.value || aiSuggesting.value) return
  const cfg = await request.get('/ai-config', { _silent: true }).catch(() => null)
  if (!cfg?.available) {
    return ElMessage.warning('请先在「系统设置」完成大模型配置,再使用字段映射预生成')
  }
  aiSuggesting.value = true
  try {
    const res = await suggestCompareMapping({
      baseDatasourceId: job.value.baseDatasourceId,
      baseDb: job.value.baseDb || null,
      baseSchema: job.value.baseSchema,
      baseTable: job.value.baseTable,
      keyField: job.value.keyField,
      targets: targets.value.map((t) => ({
        datasourceId: t.datasourceId, db: t.db || null, schema: t.schema, table: t.table
      }))
    }, 10000 + targets.value.length * 130000)
    const list = res?.targets || []
    // 与 targets 同序整体回填,画布自动重渲染;确认校验(主键必连等)口径不变
    mappings.value = targets.value.map((_, i) => list[i]?.mapping || {})
    ElMessage.success('字段映射已预生成,请人工审核连线后再确认')
  } catch { /* 拦截器已提示 */ } finally {
    aiSuggesting.value = false
  }
}

/** 「确认并开始比对」:组装 targetId → mapping 全量映射(与向导提交时 targets[].mapping 同口径),后端校验落库后开始执行 */
async function confirm() {
  if (!job.value || confirming.value) return
  // 与向导提交同一拦截口径:每个对比表都必须连上比对主键,点名是哪几张没连
  const missingIdx = targets.value.map((t, i) => i).filter((i) => !isKeyMapped(mappings.value[i]))
  if (missingIdx.length) {
    const names = missingIdx.map((i) => mappingTargets.value[i]?.label || targets.value[i].table).join('、')
    return ElMessage.warning(`有 ${missingIdx.length} 个对比表还没连上比对主键「${job.value.keyField}」:${names}`)
  }
  confirming.value = true
  try {
    const payload = {}
    targets.value.forEach((t, i) => { payload[String(t.id)] = mappings.value[i] || {} })
    await confirmCompareMapping(props.jobId, payload)
    ElMessage.success(`任务 T-${props.jobId} 已开始比对`)
    visible.value = false
    emit('confirmed')
  } catch { /* 拦截器已提示 */ } finally {
    confirming.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="`字段审核${job ? ` - ${job.name}` : ''}`" width="92%" top="3vh" destroy-on-close>
    <div v-loading="loading" class="mr-body">
      <template v-if="job">
        <!-- 数据源异常红条:先修数据源再回来确认(映射可先核对,确认时后端会重验两侧字段) -->
        <el-alert v-if="job.pendingReason === 'DS_ERROR'" type="error" :closable="false" class="mr-alert">
          <template #title>
            该任务涉及异常数据源(连不上或未建档):请先在「数据源」页修复连接,再回到本窗口点「确认并开始比对」(确认时会重新校验,连不上会被拦下)
          </template>
        </el-alert>
        <el-alert v-else-if="job.pendingReason === 'IMPORT_ERROR'" type="error" :closable="false" class="mr-alert">
          <template #title>
            导入时基准表校验未通过(表不存在或缺身份字段),画布可能加载不出字段:建议先回列表点「编辑」进向导修正,再回来审核
          </template>
        </el-alert>
        <CompareFieldMapping
          v-if="baseSpec"
          :base="baseSpec"
          :base-label="baseLabel"
          :targets="mappingTargets"
          :key-field="job.keyField"
          v-model="mappings"
        >
          <!-- 列级对比任务:大模型预生成字段映射,放工具条最左(与向导第三步同款按钮) -->
          <template v-if="job.compareMode === 'COLUMN'" #toolbar-prepend>
            <el-button size="small" type="primary" plain :loading="aiSuggesting" :disabled="!targets.length" @click="aiSuggest">
              AI 预生成字段映射
            </el-button>
          </template>
        </CompareFieldMapping>
      </template>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="confirming" :disabled="!job" @click="confirm">确认并开始比对</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 弹窗内容区:显式高度,画布(flex:1)才能撑满剩余空间 */
.mr-body {
  height: 65vh;
  display: flex;
  flex-direction: column;
}
.mr-alert {
  flex: none;
  margin-bottom: 8px;
}
</style>
