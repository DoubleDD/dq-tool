<script setup>
/**
 * 「字段审核」弹窗(批量导入的「待处理」比对任务):内嵌向导第三步的可编辑字段映射画布
 * (CompareFieldMapping,**不是**差异页那个只读 CompareMappingView),用任务详情
 * (GET /api/compare-jobs/{id} 的基准表四元组 + 各目标表既有 mapping)预填;
 * 人工核对/改线后点「确认并开始比对」走 confirm-mapping(校验口径同向导提交:
 * 映射的基准字段必须在比对字段内、目标列必须存在、每个对比表都要有有效身份——
 * 至少连一个默认身份字段,或保留人工收缩的身份覆盖),
 * 确认后任务 PENDING→RUNNING 进执行器,弹窗关闭并通知列表刷新。
 *
 * 数据源异常(pendingReason=DS_ERROR)的任务:画布上方红条提示先修数据源再回来确认
 * (confirm-mapping 是 DS_ERROR 任务的复活出口,确认时后端会重读两侧字段重验);
 * 导入异常(IMPORT_ERROR,基准表读不出/缺身份字段)的任务画布加载不出字段,提示走「编辑」向导修正。
 * 工具条左侧「AI 预生成字段映射」与向导第三步同款(列级对比任务才显示):大模型按基准表全字段
 * 逐目标产出建议并整组回填(替换现有连线,含导入时的推导结果),人工核对后再确认。
 */
import { computed, reactive, ref, watch } from 'vue'
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

/** 交给映射画布的对比表清单(带展示名;回落链:自定义显示名(V72)> 库描述(schema_doc)> 数据源名称快照) */
const mappingTargets = computed(() => targets.value.map((t) => ({
  datasourceId: t.datasourceId,
  db: t.db || '',
  schema: t.schema || '',
  table: t.table,
  label: `${t.displayName || t.schemaDesc || t.dsName || `数据源 ${t.datasourceId}`} · ${locOf(t)}`,
  dsName: t.displayName || t.schemaDesc || t.dsName || ''
})))

/** 解析后端返回的 JSON 数组(兼容「JSON 字符串」与「已解析数组」两种形态);解析不出非空数组返回 null */
function parseJsonArray(v) {
  if (v == null) return null
  let a = v
  if (typeof v === 'string') {
    if (!v.trim()) return null
    try { a = JSON.parse(v) } catch { return null }
  }
  if (!Array.isArray(a)) return null
  const out = a.filter((x) => typeof x === 'string' && x)
  return out.length ? out : null
}

/** 解析目标级 identityJson({keys:[...]} 的 JSON 字符串或已解析对象)为 keys 数组;无覆盖返回 null */
function parseIdentityKeys(v) {
  if (v == null) return null
  let o = v
  if (typeof v === 'string') {
    if (!v.trim()) return null
    try { o = JSON.parse(v) } catch { return null }
  }
  return parseJsonArray(Array.isArray(o) ? o : o?.keys)
}

// 任务级默认身份字段:keyFieldsJson(JSON 数组)优先,老任务(null)退化为 [keyField] 单列
const jobKeyFields = computed(() => parseJsonArray(job.value?.keyFieldsJson) || (job.value?.keyField ? [job.value.keyField] : []))

// 目标级身份人工覆盖:与 targets 同序,null = 按推导,string[] = 收缩后的 keyFields 子集(预填自各目标 identityJson)
const identityOverrides = ref([])

/** 该对比表映射中已连线的默认身份字段(推导身份;忽略大小写命中,返回 keyFields 原始大小写与顺序) */
function connectedKeys(map) {
  const lower = new Set(Object.keys(map || {}).map((bf) => bf.toLowerCase()))
  return jobKeyFields.value.filter((k) => lower.has(k.toLowerCase()))
}

/** 目标 i 的身份三态(与向导第 3 步同口径):覆盖仍连着线的部分优先,与推导一致归一 null,effective = 覆盖 ?? 推导 */
function identityState(i) {
  const derived = connectedKeys(mappings.value[i])
  const ov = (identityOverrides.value[i] || []).filter((k) => derived.some((d) => d.toLowerCase() === k.toLowerCase()))
  const override = ov.length && ov.length < derived.length ? ov : null
  return { derived, override, effective: override || derived }
}

// 「调整身份」弹窗:候选 = 该对比表已连线的默认身份字段
const identityDialog = reactive({ visible: false, index: -1, draft: [] })
const identityDialogCandidates = computed(() => (identityDialog.index >= 0 ? identityState(identityDialog.index).derived : []))

function openIdentityDialog(i) {
  identityDialog.index = i
  identityDialog.draft = [...identityState(i).effective]
  identityDialog.visible = true
}

/** 确定收缩:按 keyFields 顺序归一;勾满(与推导一致)则不存覆盖,至少保留 1 个(按钮已兜底) */
function confirmIdentityDialog() {
  const i = identityDialog.index
  if (i < 0) return
  const derived = identityState(i).derived
  const picked = derived.filter((k) => identityDialog.draft.includes(k))
  identityOverrides.value[i] = picked.length && picked.length < derived.length ? picked : null
  identityDialog.visible = false
}

// 弹窗打开即拉详情预填(destroy-on-close,每次打开都是全新画布)
watch(() => props.modelValue, (v) => {
  if (v && props.jobId) load()
})

async function load() {
  loading.value = true
  detail.value = null
  mappings.value = []
  identityOverrides.value = []
  try {
    const d = await getCompareJob(props.jobId)
    if (d?.job?.status !== 'PENDING') {
      ElMessage.warning('任务已不在「待处理」状态,请刷新列表')
      visible.value = false
      return
    }
    detail.value = d
    mappings.value = (d?.targets || []).map((t) => ({ ...(t.mapping || {}) }))
    // 目标级身份覆盖反填(null = 按推导):审核时可直接看到/再调整人工收缩
    identityOverrides.value = (d?.targets || []).map((t) => parseIdentityKeys(t.identityJson))
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
      keyField: jobKeyFields.value[0] || '', // 映射预生成接口仍收单主键:取第一个默认身份字段
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

/** 「确认并开始比对」:组装 targetId → mapping 全量映射(与向导提交时 targets[].mapping 同口径),
 *  另带人工收缩过的目标级身份 identities(targetId → { keys }),后端校验落库后开始执行 */
async function confirm() {
  if (!job.value || confirming.value) return
  // 与向导提交同一拦截口径:每个对比表都要有有效身份(至少连一个默认身份字段,或保留有效覆盖),点名是哪几张
  const missingIdx = targets.value.map((t, i) => i).filter((i) => !identityState(i).effective.length)
  if (missingIdx.length) {
    const names = missingIdx.map((i) => mappingTargets.value[i]?.label || targets.value[i].table).join('、')
    return ElMessage.warning(`有 ${missingIdx.length} 个对比表还没有有效身份字段:${names}。每张对比表至少连一个默认身份字段`)
  }
  confirming.value = true
  try {
    const mappingsPayload = {}
    targets.value.forEach((t, i) => { mappingsPayload[String(t.id)] = mappings.value[i] || {} })
    // 仅人工收缩过(与推导不一致)的目标带身份覆盖;缺省 = 全部按推导
    const identities = {}
    targets.value.forEach((t, i) => {
      const ov = identityState(i).override
      if (ov) identities[String(t.id)] = { keys: ov }
    })
    const payload = { mappings: mappingsPayload }
    if (Object.keys(identities).length) payload.identities = identities
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
        <!-- 身份条(与向导第 3 步同口径):逐对比表展示有效身份(推导灰 tag / 人工覆盖橙 tag),连了多个可收缩 -->
        <div class="identity-bar">
          <span class="identity-bar-label">有效身份</span>
          <div v-for="(t, i) in targets" :key="i" class="identity-item">
            <span class="identity-name" :title="mappingTargets[i]?.label || t.table">{{ t.table }}</span>
            <template v-if="identityState(i).effective.length">
              <el-tag v-for="k in identityState(i).effective" :key="k" size="small"
                      :type="identityState(i).override ? 'warning' : 'info'" effect="plain">{{ k }}</el-tag>
              <el-button v-if="identityState(i).derived.length > 1" size="small" link type="primary"
                         @click="openIdentityDialog(i)">{{ identityState(i).override ? '已调整' : '调整' }}</el-button>
            </template>
            <el-tag v-else size="small" type="danger">未连身份字段</el-tag>
          </div>
        </div>
        <CompareFieldMapping
          v-if="baseSpec"
          :base="baseSpec"
          :base-label="baseLabel"
          :targets="mappingTargets"
          :key-fields="jobKeyFields"
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
    <!-- 目标级身份收缩弹窗(与向导第 3 步同款):勾选的子集作为 identities[targetId].keys 随确认提交 -->
    <el-dialog v-model="identityDialog.visible" title="调整该对比表的身份字段" width="420px" append-to-body>
      <div class="identity-dialog-tip">
        {{ identityDialog.index >= 0 ? (mappingTargets[identityDialog.index]?.label || '') : '' }}:取消勾选的字段不参与判同(至少保留 1 个)
      </div>
      <el-checkbox-group v-model="identityDialog.draft">
        <el-checkbox v-for="k in identityDialogCandidates" :key="k" :value="k" style="display: flex">{{ k }}</el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="identityDialog.visible = false">取消</el-button>
        <el-button type="primary" :disabled="!identityDialog.draft.length" @click="confirmIdentityDialog">确定</el-button>
      </template>
    </el-dialog>
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
/* 身份条:逐对比表展示有效身份(推导灰 tag / 人工覆盖橙 tag),横向滚动不换行 */
.identity-bar {
  flex: none;
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 8px;
  padding: 6px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-light);
  overflow-x: auto;
  white-space: nowrap;
}
.identity-bar-label {
  flex: none;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}
.identity-item {
  flex: none;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.identity-name {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--el-text-color-secondary);
}
.identity-dialog-tip {
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
