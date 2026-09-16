<template>
  <div class="page-card card-fill">
    <div class="toolbar">
      <h3 style="margin: 0">新建比对任务</h3>
      <div class="toolbar-actions">
        <el-button @click="router.push('/compare')">返回列表</el-button>
      </div>
    </div>
    <!-- <el-alert type="info" :closable="false" style="margin-bottom: 16px">
      <template #title>
        选定基准表并确定对比模式、匹配逻辑、对象编码与对象名称,再指定对比表并连线字段映射;有连线的基准字段即比对字段,任务按比对主键逐字段对齐比对
      </template>
    </el-alert> -->
    <!-- 步骤条:已激活(≤maxStep)的步骤可点击回看,纯切换视图、不改任何数据;未激活的步骤灰显禁止点击 -->
    <el-steps :active="maxStep" align-center style="margin-bottom: 24px">
      <el-step
        v-for="(title, i) in STEP_TITLES"
        :key="i"
        :title="title"
        :class="i <= maxStep ? 'step-clickable' : 'step-disabled'"
        @click="goStep(i)"
      />
    </el-steps>

    <!-- 步骤 1:选择基准表(四栏级联:数据源 → 数据库 → 模式 → 表,模式栏按数据库类型动态显示),
         并在本步一次定齐 对比模式/匹配逻辑/对象编码/对象名称 -->
    <div v-show="step === 0" class="step-body step-fill">
      <!-- 元信息同一行:任务名称 + 对比模式 + 匹配逻辑(说明文案收进问号悬浮提示,省两行纵向空间)。
           对比模式:勾选「列对比」= 行级+列级(身份对齐后逐字段比对全部连线字段 + 大模型预生成映射);
           不勾选 = 仅行级(通常只连身份字段、映射人工连线)。匹配逻辑:决定「两条数据算不算同一个对象」,
           默认「编码+名称」最严格;选「先编码后名称+大模型归一化」时「对象名称」列为必选 -->
      <div class="base-meta-row">
        <span class="meta-label">任务名称</span>
        <el-input v-model="form.name" placeholder="如:客户主数据三系统比对" maxlength="100" class="meta-name" />
        <el-divider direction="vertical" />
        <!-- <span class="meta-label">对比模式</span> -->
        <el-checkbox v-model="columnCompare">列对比</el-checkbox>
        <el-tooltip placement="top" :content="modeTip" :show-after="200">
          <el-icon class="meta-help"><QuestionFilled /></el-icon>
        </el-tooltip>
        <el-divider direction="vertical" />
        <span class="meta-label">匹配方式</span>
        <el-radio-group v-model="matchMode">
          <el-radio v-for="m in MATCH_MODES" :key="m.value" :value="m.value">{{ m.label }}</el-radio>
        </el-radio-group>
        <el-tooltip placement="top" :content="matchModeTip" :show-after="200">
          <el-icon class="meta-help"><QuestionFilled /></el-icon>
        </el-tooltip>
      </div>
      <div class="base-cascade-wrap" :class="{ compact: form.table }">
        <TableCascadePicker
          v-model:datasource-id="form.datasourceId"
          v-model:db="form.db"
          v-model:schema="form.schema"
          v-model:table="form.table"
          :datasources="datasources"
          @table-change="onBaseTableChange"
        />
      </div>
      <!-- 字段表:选定基准表即加载。没有勾选列——哪些字段参与比对由第 3 步连线决定(有连线的基准字段即比对字段);
           这里只选两个身份字段:对象编码(对齐主键,恒参与比对)/对象名称(差异明细「对象」列显示名) -->
      <div v-if="form.table" class="field-table-wrap">
        <el-table :data="columns" v-loading="columnsLoading" border row-key="name" height="100%">
          <el-table-column label="对象编码" width="90" align="center">
            <template #default="{ row }">
              <el-radio v-model="keyField" :value="row.name">{{ '' }}</el-radio>
            </template>
          </el-table-column>
          <el-table-column label="对象名称" width="90" align="center">
            <template #default="{ row }">
              <el-radio v-model="displayField" :value="row.name">{{ '' }}</el-radio>
            </template>
          </el-table-column>
          <el-table-column label="字段名" min-width="240" show-overflow-tooltip>
            <!-- 字段说明跟在字段名后面(弱化灰色),不再单独占列 -->
            <template #default="{ row }">
              <span>{{ row.name }}</span>
              <span v-if="row.comment" class="field-comment">{{ row.comment }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="150">
            <template #default="{ row }">
              <el-tag size="small" type="info" plain>{{ row.displayType || row.typeName || '-' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="step-tip">
        基准表为权威数据,其他系统的数据将按比对主键逐字段对齐到该表;对象编码用于逐行对齐、恒参与比对,
        对象名称决定差异明细「对象」列的名称(默认第一个文本型非主键字段);
        其余字段是否参与比对由第 3 步连线决定——有连线的基准字段即比对字段
      </div>
    </div>

    <!-- 步骤 2:选择比对系统并确认(与第一步同款四栏级联,选完点「添加为比对系统」入列) -->
    <div v-show="step === 1" class="step-body step-fill">
      <div class="step2-layout">
        <div class="step2-targets">
          <TableCascadePicker
            v-model:datasource-id="pick.datasourceId"
            v-model:db="pick.db"
            v-model:schema="pick.schema"
            :datasources="datasources"
            :disabled-tables="disabledTargetTables"
            :added-tables="addedTargetTables"
            label="比对系统"
            :show-selected="false"
            toggleable
            @toggle="toggleTarget"
          />
        </div>
        <!-- 右列:已添加比对系统清单 -->
        <div class="step2-side">
          <div class="target-panel">
            <div class="target-panel-head">
              <span>已添加比对系统</span>
              <span class="target-panel-count">{{ targets.length }}</span>
            </div>
            <div class="target-list">
              <div v-for="(t, i) in targets" :key="i" class="target-item">
                <span class="target-index">{{ i + 1 }}</span>
                <span class="target-label" :title="targetLabel(t)">
                  <span class="target-ds">{{ targetDs(t)?.name || '' }}</span>
                  <span class="target-loc">{{ targetLoc(t) }}</span>
                </span>
                <el-button link type="danger" @click="targets.splice(i, 1)">删除</el-button>
              </div>
              <div v-if="!targets.length" class="target-empty">还没有比对系统,请在左侧选好库/模式后,点表名右侧的 + 加入(至少 1 个)</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 步骤 3:字段映射(左侧基准表固定、右侧各对比表纵向排开单独滚动,人工连线;
         有连线的基准字段即比对字段,提交时按连线并集 + 身份两字段汇总比对字段) -->
    <div v-show="step === 2" class="step-body step-fill">
      <CompareFieldMapping
        :base="{ datasourceId: form.datasourceId, db: form.db, schema: form.schema, table: form.table }"
        :base-label="baseTableLabel"
        :targets="mappingTargets"
        :key-field="keyField"
        v-model="mappings"
      >
        <!-- 列级对比:大模型预生成字段映射,放工具条最左(基准表全字段产出建议);人工在画布审核后可再手动增删 -->
        <template #toolbar-prepend>
          <template v-if="compareMode === 'COLUMN'">
            <el-button type="primary" plain :loading="aiSuggesting" :disabled="!targets.length" @click="aiSuggestMapping">
              AI 预生成字段映射
            </el-button>
            <!-- <span class="ai-suggest-tip">{{ aiSuggestNote || '大模型按字段名/注释逐目标产出映射建议,请在画布核对连线后再提交' }}</span> -->
          </template>
        </template>
      </CompareFieldMapping>
    </div>

    <!-- 向导操作按钮 -->
    <div class="wizard-actions">
      <el-button :disabled="step === 0" @click="step--">上一步</el-button>
      <el-button v-if="step < 2" type="primary" @click="next">下一步</el-button>
      <el-button v-else type="primary" :loading="submitting" @click="submit">开始比对</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { QuestionFilled } from '@element-plus/icons-vue'
import { ElMessage } from '../utils/notify'
import request, { createCompareJob, suggestCompareMapping } from '../api'
import { watchTask } from '../stores/backgroundTasks'
import TableCascadePicker from '../components/TableCascadePicker.vue'
import CompareFieldMapping from '../components/CompareFieldMapping.vue'

const router = useRouter()

const step = ref(0)
// 已激活的最远步骤(0 起,经「下一步」校验通过才推进):决定步骤条高亮位置与哪些步骤可点击回看
const maxStep = ref(0)
// 三个步骤标题,步骤条 v-for 用
const STEP_TITLES = ['选择基准表', '选择对比表', '字段映射']
// 对比模式复选框「列对比」(提交值落 compare_mode):勾选 = COLUMN 行级+列级,第 3 步出现「AI 预生成字段映射」;
// 不勾选 = ROW 仅行级,映射人工连线。模式只影响提交元数据与映射来源,比对字段一律由第 3 步连线决定
const columnCompare = ref(false)
const compareMode = computed(() => (columnCompare.value ? 'COLUMN' : 'ROW'))
const MODE_TIPS = {
  ROW: '仅行级对比:一般只连对象编码、对象名称等身份字段,字段少、分钟级;字段映射由人工连线完成',
  COLUMN: '行级+列级对比:身份对齐后逐字段比对连线的信息字段,全量扫描、小时级;字段映射由大模型预生成、人工审核'
}
const modeTip = computed(() => MODE_TIPS[compareMode.value])
const datasources = ref([])

// ---------- 步骤 1:基准表与身份字段 ----------

const form = reactive({ name: '', datasourceId: '', db: '', schema: '', table: '' })

const baseDs = computed(() => datasources.value.find((d) => String(d.id) === String(form.datasourceId)))
// 多库方言(SQL Server/Kingbase)需要额外选数据库,与对象管理选表组件同一判定
const baseMultiDb = computed(() => ['SQLSERVER', 'KINGBASE'].includes(baseDs.value?.dbType))

const baseTableLabel = computed(() => {
  if (!form.table) return '-'
  const schemaPart = form.db ? `${form.db}.${form.schema}` : form.schema
  return schemaPart ? `${schemaPart}.${form.table}` : form.table
})

// 基准表字段清单(选定表即加载):本步用来选对象编码/对象名称两个身份字段,提交时也是比对字段排序的依据
const columns = ref([])
const columnsLoading = ref(false)
// 对象编码(比对主键):默认 pkSeq 最小的主键列,无主键取第一列;恒参与比对
const keyField = ref('')
// 对象名称(显示名)字段:差异明细「对象」列的名称来源,默认第一个文本型非主键字段
const displayField = ref('')

// 文本型 jdbcType(与后端 CompareService.isTextType 同一口径:字符型 + CLOB/NCLOB)
const TEXT_JDBC_TYPES = new Set([1, 12, -1, -15, -9, -16, 2005, 2011])

// 匹配逻辑(与后端 CompareService.MatchMode 同一取值;EXACT=最严格,大模型归一化需要指定对象名称字段)
const MATCH_MODES = [
  { value: 'EXACT', label: '编码+名称', tip: '对比表的对象编码与对象名称都与基准表完全一致,才算同一个对象(最严格,差异看得最细)' },
  { value: 'CODE_NAME_LLM', label: '先编码后名称+大模型归一化', tip: '先用对象编码配;编码没配上的再按对象名称配;都没配上的交大模型按业务含义再认一轮(需要先配好大模型,残余过多时会自动跳过并提示)' }
]
const matchMode = ref('EXACT')
const matchModeTip = computed(() => MATCH_MODES.find((m) => m.value === matchMode.value)?.tip || '')
const matchModeLabel = computed(() => MATCH_MODES.find((m) => m.value === matchMode.value)?.label || matchMode.value)
// 「先编码后名称+大模型归一化」必须给出对象名称字段(否则无法按名称配对,后端提交时会 400)
const matchModeRequiresName = computed(() => matchMode.value !== 'EXACT')

/** 「自动」候选 = 第一个文本型非主键字段(按基准表字段顺序);无则空串(提交 null,object_name 落空串) */
function autoDisplayField() {
  const hit = columns.value.find((c) => c.name !== keyField.value && TEXT_JDBC_TYPES.has(c.jdbcType))
  return hit?.name || ''
}

async function loadColumns() {
  columnsLoading.value = true
  // 初始化本步数据期间抑制数据链监听,避免被误判成「用户修改第 1 步数据」而清空后续步骤
  suppressInvalidate = true
  columns.value = []
  keyField.value = ''
  displayField.value = ''
  try {
    const q = form.db ? `?db=${encodeURIComponent(form.db)}` : ''
    const list = await request.get(
      `/datasources/${form.datasourceId}/schemas/${encodeURIComponent(form.schema)}/tables/${encodeURIComponent(form.table)}/columns${q}`
    )
    columns.value = list || []
    // 默认比对主键:主键列中 pkSeq 最小的;无主键则取第一列
    const pk = [...columns.value].filter((c) => c.primaryKey).sort((a, b) => (a.pkSeq || 0) - (b.pkSeq || 0))
    keyField.value = (pk[0] || columns.value[0])?.name || ''
    displayField.value = autoDisplayField()
    // 记录字段已按当前基准表加载:重选同一张表(回看场景)时不再清空重载,保住后两步已填数据
    loadedTableKey.value = baseTableKey()
  } catch {
    ElMessage.error('字段列表加载失败,请重新选择基准表')
  } finally {
    suppressInvalidate = false
    columnsLoading.value = false
  }
}

/**
 * 选定基准表后:名称为空时给个默认名,并随即加载字段清单(对象编码/名称在本步选择,字段表要即刻可用)。
 * 换表 = 第 1 步数据变更:本步字段数据与后两步(对比表清单、映射连线)全部作废;
 * 级联清空(t=null)交给 baseTableKey 监听统一走数据链,这里不重复处理
 */
function onBaseTableChange(t) {
  if (!form.name && t?.name) form.name = `${t.name} 数据比对`
  if (!t?.name) return
  if (loadedTableKey.value === baseTableKey()) return
  invalidateFrom(0)
  loadColumns()
}

// ---------- 步骤 2:比对系统 ----------

// 已入列的比对系统:{ datasourceId, db, schema, table }
const targets = ref([])
// 级联面板当前所在的 数据源/库/模式(第 2 步只选到库/模式,具体表在表行上用 +/− 逐张切换)
const pick = reactive({ datasourceId: '', db: '', schema: '' })
// 第 3 步字段映射:数组与 targets 同序,元素为 { 基准字段名: 目标列名 }
const mappings = ref([])
const submitting = ref(false)
// 列级对比:AI 预生成字段映射的状态与逐目标结果提示(提示随对比表清单变化失效)
const aiSuggesting = ref(false)
const aiSuggestNote = ref('')

// 第 2 步数据链:已添加清单一变,第 3 步映射作废重连,激活进度收回第 2 步(数据链细节见下方监听区)
watch(() => targets.value.map((t) => `${t.datasourceId}|${t.db}|${t.schema}|${t.table}`).join(','),
  (nv, ov) => { if (suppressInvalidate || nv === ov) return; aiSuggestNote.value = ''; invalidateFrom(1) })

/** 交给映射画布的对比表清单(带展示名) */
const mappingTargets = computed(() => targets.value.map((t) => ({
  datasourceId: t.datasourceId, db: t.db || '', schema: t.schema, table: t.table, label: targetLabel(t),
  // 数据源名单独带一份:字段映射画布标题/映射管理弹窗要拼「数据源名 · 中文表名」,从 label 里拆太脆
  dsName: targetDs(t)?.name || ''
})))

/** 该对比表是否已把比对主键连上(未连则无法按主键对齐行,提交时拦下) */
function isKeyMapped(map) {
  if (!keyField.value) return true
  return Object.keys(map || {}).some((bf) => bf.toLowerCase() === keyField.value.toLowerCase())
}

// 目标数据源不再排除基准数据源:同一数据库下不同表互比是常见场景,改为禁用「基准表本身」这一组合
function targetDs(t) {
  return datasources.value.find((d) => String(d.id) === String(t.datasourceId))
}

/** 目标定位串:库/模式.表(多库方言带 db 前缀)。右列清单里与数据源名分两行展示,避免单行截断 */
function targetLoc(t) {
  const schemaPart = t.db ? `${t.db}.${t.schema}` : t.schema
  return `${schemaPart ? schemaPart + '.' : ''}${t.table}`
}

function targetLabel(t) {
  const ds = targetDs(t)
  if (!ds || !t.table) return ''
  return `${ds.name} · ${targetLoc(t)}`
}

/** 是否与基准表完全同源(数据源+库+模式+表都相同):该组合禁止作为比对系统 */
function isSameAsBase(t) {
  return String(t.datasourceId) === String(form.datasourceId) && (t.db || '') === (form.db || '') &&
    t.schema === form.schema && t.table === form.table
}

// 当前面板所在数据源+库/模式就是基准那一个时,把基准表本身交给选表组件置灰
const disabledTargetTables = computed(() => {
  const sameDs = String(pick.datasourceId) === String(form.datasourceId)
  const sameDb = (pick.db || '') === (form.db || '')
  return sameDs && sameDb && pick.schema === form.schema && form.table ? [form.table] : []
})

// 当前库/模式下已加入目标的表名:交给组件渲染成绿色「−」态
const addedTargetTables = computed(() => targets.value
  .filter((t) => String(t.datasourceId) === String(pick.datasourceId) && (t.db || '') === (pick.db || '') && t.schema === pick.schema)
  .map((t) => t.table))

/** 表行「+/−」:没加过就加入,加过就移出(移出后可再次加入);基准表本身由组件置灰,这里兜底拦截 */
function toggleTarget(t) {
  const idx = targets.value.findIndex((x) =>
    String(x.datasourceId) === String(pick.datasourceId) && (x.db || '') === (pick.db || '') &&
    x.schema === pick.schema && x.table === t.name)
  if (idx >= 0) {
    targets.value.splice(idx, 1)
    return
  }
  const next = { datasourceId: pick.datasourceId, db: pick.db, schema: pick.schema, table: t.name }
  if (isSameAsBase(next)) return ElMessage.warning('基准表本身不能作为比对系统')
  targets.value.push(next)
}

/** 列级对比:大模型逐目标预生成字段映射并整体回填,人工在画布审核后可再手动增删 */
async function aiSuggestMapping() {
  if (!targets.value.length || aiSuggesting.value) return
  // 预检大模型配置(不完整时给引导,不空等后端报错)
  const cfg = await request.get('/ai-config', { _silent: true }).catch(() => null)
  if (!cfg?.available) {
    return ElMessage.warning('请先在「系统设置」完成大模型配置,再使用字段映射预生成')
  }
  aiSuggesting.value = true
  aiSuggestNote.value = ''
  try {
    // 不再预圈比对字段:fields 留空 = 基准表全部字段交给大模型建议,连线结果即比对字段
    const res = await suggestCompareMapping({
      baseDatasourceId: Number(form.datasourceId),
      baseDb: form.db || null,
      baseSchema: form.schema,
      baseTable: form.table,
      keyField: keyField.value,
      targets: targets.value.map((t) => ({
        datasourceId: Number(t.datasourceId), db: t.db || null, schema: t.schema, table: t.table
      }))
    }, 10000 + targets.value.length * 130000)
    const list = res?.targets || []
    // 与 targets 同序整体回填,画布自动重渲染;提交校验(主键必连等)口径不变
    mappings.value = targets.value.map((_, i) => list[i]?.mapping || {})
    aiSuggestNote.value = list
      .map((t, i) => `${targetLabel(targets.value[i]) || t.table}:${t.note || '已生成映射'}`)
      .join(';')
    ElMessage.success('字段映射已预生成,请人工审核后再提交')
  } catch { /* 拦截器已提示 */ } finally {
    aiSuggesting.value = false
  }
}

// ---------- 向导数据链:前序数据一变,后续所有步骤清空 ----------

/**
 * 程序化改写数据(进入某步加载其数据、级联清空)期间置位,抑制数据链监听,
 * 避免「进入某步初始化自身数据」被误判成「用户修改了该步数据」而误清空后续步骤
 */
let suppressInvalidate = false

// 字段清单已按哪张基准表加载过(四元组键):重选同一张表时不清空重载,保住后两步已填数据
const loadedTableKey = ref('')

function baseTableKey() {
  return [form.datasourceId, form.db, form.schema, form.table].join('|')
}

/**
 * 第 n 步(0 起)的数据被用户改动:清空其后所有步骤的数据,并把激活进度收回到第 n 步
 * (后续步骤立刻变灰禁止点击,只能在第 n 步点「下一步」重新逐层激活)
 */
function invalidateFrom(n) {
  suppressInvalidate = true
  try {
    if (n < 1) {
      // 基准表变了:本步随表加载的字段数据与身份字段选择一并失效,第 2 步(选择对比表)清单也作废
      columns.value = []
      keyField.value = ''
      displayField.value = ''
      matchMode.value = 'EXACT'
      loadedTableKey.value = ''
      targets.value = []
    }
    if (n < 2) {
      // 第 3 步(字段映射)的数据
      mappings.value = []
    }
    maxStep.value = Math.min(maxStep.value, n)
  } finally {
    suppressInvalidate = false
  }
}

// 数据链监听:级联清空/换库换模式导致基准表四元组变化时,清空全部后续数据(换表走 onBaseTableChange,
// 那里先 invalidateFrom(0) 再 loadColumns,本监听被 suppressInvalidate 跳过,不会重复清空);
// 任务名只是展示元数据、不参与依赖链,改动不清空后续步骤
watch(baseTableKey, (nv, ov) => {
  if (suppressInvalidate || nv === ov) return
  invalidateFrom(0)
})

// ---------- 向导流转与提交 ----------

/** 点击步骤条回看:只允许已激活步骤(≤maxStep),纯切换视图,不动任何数据 */
function goStep(i) {
  if (i > maxStep.value) return
  step.value = i
}

function next() {
  if (step.value === 0) {
    if (!form.name.trim()) return ElMessage.warning('请填写任务名称')
    if (!form.datasourceId) return ElMessage.warning('请选择基准数据源')
    if (baseMultiDb.value && !form.db) return ElMessage.warning('请选择数据库')
    if (!form.schema) return ElMessage.warning('请选择库/schema')
    if (!form.table) return ElMessage.warning('请选择基准表')
    if (!columns.value.length) return ElMessage.warning('基准表字段未加载,无法继续')
    if (!keyField.value) return ElMessage.warning('请选择比对主键(对象编码)')
    // 「先编码后名称+大模型归一化」靠对象名称配对,没有名称字段就无法执行
    if (matchModeRequiresName.value && !displayField.value) {
      return ElMessage.warning(`匹配逻辑「${matchModeLabel.value}」需要指定对象名称字段,请在「对象名称」列选择`)
    }
    step.value = 1
    maxStep.value = Math.max(maxStep.value, 1)
    return
  }
  if (step.value === 1) {
    if (!targets.value.length) return ElMessage.warning('请至少添加 1 个对比表(数据源 + 库/模式 + 表)')
    step.value = 2
    maxStep.value = Math.max(maxStep.value, 2)
  }
}

async function submit() {
  if (!targets.value.length) return ElMessage.warning('请至少添加 1 个对比表(数据源 + 库/模式 + 表)')
  if (matchModeRequiresName.value && !displayField.value) {
    return ElMessage.warning(`匹配逻辑「${matchModeLabel.value}」需要指定对象名称字段,请回到第 1 步选择`)
  }
  const unmapped = targets.value.filter((t, i) => !isKeyMapped(mappings.value[i]))
  if (unmapped.length) {
    // 点名是哪几个对比表没连主键(数据源 · 库.模式.表),别让用户在多目标里自己猜
    const names = unmapped.map((t) => targetLabel(t) || t.table).join('、')
    return ElMessage.warning(`有 ${unmapped.length} 个对比表还没连上比对主键「${keyField.value}」:${names}。请回到第 3 步连线`)
  }
  submitting.value = true
  try {
    // 比对字段 = 第 3 步连线的基准字段并集(有连线即参与比对)+ 身份两字段(对象编码/对象名称恒参与),
    // 保持基准表字段顺序;后端两条约束「映射的基准字段必须在比对字段内」「对象名称字段必须在比对字段内」由此一并满足
    const picked = new Set([keyField.value])
    if (displayField.value) picked.add(displayField.value)
    for (const m of mappings.value) {
      for (const bf of Object.keys(m || {})) picked.add(bf)
    }
    const pickedLower = new Set([...picked].map((n) => n.toLowerCase()))
    const fields = columns.value.map((c) => c.name).filter((n) => pickedLower.has(n.toLowerCase()))
    const res = await createCompareJob({
      name: form.name.trim(),
      baseDatasourceId: Number(form.datasourceId),
      baseDb: form.db || null,
      baseSchema: form.schema,
      baseTable: form.table,
      keyField: keyField.value,
      fields,
      displayField: displayField.value || null,
      // 对象对齐匹配逻辑(第 1 步选择):EXACT / CODE_NAME_LLM
      matchMode: matchMode.value,
      // 对比模式(第 1 步复选框):ROW 仅行级 / COLUMN 行级+列级
      compareMode: compareMode.value,
      targets: targets.value.map((t, i) => ({
        datasourceId: Number(t.datasourceId),
        db: t.db || null,
        schema: t.schema,
        table: t.table,
        // 第 3 步人工连线的字段映射;空对象 = 不指定,后端按字段名自动匹配
        mapping: Object.keys(mappings.value[i] || {}).length ? mappings.value[i] : null
      }))
    })
    ElMessage.success(`比对任务 T-${res.jobId} 已创建,开始执行`)
    watchTask() // 登记到全局后台任务跟踪器:离开列表页也能在头栏看进度、完成收通知
    router.push('/compare')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    datasources.value = await request.get('/datasources') || []
  } catch { /* 拦截器已提示 */ }
})
</script>

<style scoped>
.toolbar-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
.toolbar-actions :deep(.el-button) {
  margin-left: 0;
}

/* 步骤条交互:已激活步骤可点击回看(纯切换视图),未激活步骤保持灰态且禁止点击 */
.step-clickable {
  cursor: pointer;
}
.step-clickable:hover :deep(.el-step__title) {
  color: var(--el-color-primary);
}
.step-disabled {
  cursor: not-allowed;
}

/* 列级对比·AI 预生成映射提示(按钮本体经 #toolbar-prepend 插槽放在「按名称自动匹配」左侧) */
.ai-suggest-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  max-width: 520px;
}

.step-body {
  min-height: 300px;
}

/* 三步通用:卡片高度锁成 el-main 可视高度(减自身上下 20px 外边距),
   否则内容会把卡片顶高、底部按钮悬在半空;固定高度下各步内容区 flex:1 撑满,
   「上一步/下一步」始终贴卡片底部。min-height 兜底:窗口过矮时退化为整页滚动,不把内容压扁 */
.page-card.card-fill {
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  height: calc(100% - 40px);
  min-height: 480px;
}

/* 各步:纵向撑满卡片剩余高度,内容区拉满、底部按钮始终贴底 */
.step-fill {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
}

/* 第 1 步级联面板:未选表时铺满剩余高度(与旧版一致);选定表后压缩成固定高度,把剩余空间让给字段表 */
.base-cascade-wrap {
  flex: 1 1 auto;
  min-height: 240px;
  display: flex;
  flex-direction: column;
}
.base-cascade-wrap.compact {
  flex: none;
  height: 240px;
}

/* 第 1 步元信息行:任务名称 + 对比模式 + 匹配逻辑同一行,名称输入框吃剩余宽度,说明收进问号悬浮提示 */
.base-meta-row {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.meta-label {
  flex: none;
  font-size: 14px;
  color: var(--el-text-color-regular);
}
/* 三组设置之间的竖分隔符:拉高一点、两侧留白与行内间距拉开层次 */
.base-meta-row :deep(.el-divider--vertical) {
  height: 1.2em;
  margin: 0 10px;
}
.meta-name {
  flex: 1 1 auto;
  max-width: 420px;
}
.meta-help {
  color: var(--el-text-color-secondary);
  cursor: help;
}
/* 与输入框/单选组同排时去掉单选组默认右侧间距差异,视觉对齐 */
.base-meta-row :deep(.el-radio-group) {
  flex: none;
}

/* 第 1 步字段表容器:吃掉 step-fill 的剩余高度,el-table 用 height=100% 内部滚动 */
.field-table-wrap {
  flex: 1 1 auto;
  min-height: 180px;
}
/* 字段表行高压窄(默认 12px 上下内边距偏大,纯展示列表 6px 更紧凑) */
.field-table-wrap :deep(.el-table__cell) {
  padding: 6px 0;
}
/* 字段说明跟在字段名后面:弱化灰色、与字段名拉开一点距离 */
.field-comment {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.step-tip {
  margin-top: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 步骤 2:左侧级联面板 + 添加按钮,右侧「已添加比对系统」卡 */
.step2-layout {
  display: flex;
  gap: 24px;
  align-items: stretch;
  flex: 1 1 auto;
  min-height: 0;
}
.step2-targets {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.step2-side {
  flex: none;
  width: 420px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
/* 已添加比对系统卡:高度贴合内容,条数多时列表内部滚动 */
.target-panel {
  flex: none;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  overflow: hidden;
  background: var(--el-fill-color-blank);
}
.target-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.target-panel-count {
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.target-list {
  flex: none;
  max-height: 168px;
  overflow: auto;
  padding: 4px;
}
.target-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 13px;
}
.target-item:hover {
  background: var(--el-fill-color-light);
}
.target-item .target-label {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}
/* 两行各自单行省略(整卡 title 有完整串),第二行库.表弱化显示 */
.target-item .target-label > span {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.target-item .target-loc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.target-empty {
  padding: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.target-index {
  flex: none;
  width: 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 50%;
  background: var(--el-color-primary-light-8);
  color: var(--el-color-primary);
  font-size: 12px;
}

.wizard-actions {
  margin-top: 24px;
  display: flex;
  justify-content: center;
  gap: 12px;
}
.wizard-actions :deep(.el-button) {
  margin-left: 0;
}
</style>
