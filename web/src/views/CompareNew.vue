<template>
  <div class="page-card card-fill">
    <div class="toolbar">
      <h3 style="margin: 0">新建比对任务</h3>
      <div class="toolbar-actions">
        <el-button @click="router.push('/compare')">返回列表</el-button>
      </div>
    </div>
    <el-alert type="info" :closable="false" style="margin-bottom: 16px">
      <template #title>
        选定基准表与关注字段,再指定对比表并连线字段映射;任务将按比对主键逐字段对齐比对
      </template>
    </el-alert>
    <el-steps :active="step" align-center style="margin-bottom: 24px">
      <el-step title="选择基准表" />
      <el-step title="选择基准字段" />
      <el-step title="选择对比表" />
      <el-step title="字段映射" />
    </el-steps>

    <!-- 步骤 1:选择基准表(四栏级联:数据源 → 数据库 → 模式 → 表,模式栏按数据库类型动态显示) -->
    <div v-show="step === 0" class="step-body step-fill">
      <el-form label-width="90px" style="max-width: 640px">
        <el-form-item label="任务名称">
          <el-input v-model="form.name" placeholder="如:客户主数据三系统比对" maxlength="100" />
        </el-form-item>
      </el-form>
      <TableCascadePicker
        v-model:datasource-id="form.datasourceId"
        v-model:db="form.db"
        v-model:schema="form.schema"
        v-model:table="form.table"
        :datasources="datasources"
        @table-change="onBaseTableChange"
      />
      <div class="step-tip">基准表为权威数据,其他系统的数据将按比对主键逐字段对齐到该表</div>
    </div>

    <!-- 步骤 2:选择比对字段(字段表撑满剩余高度,表头固定、表体内部滚动) -->
    <div v-show="step === 1" class="step-body step-fill">
      <!-- 匹配逻辑:决定「两条数据算不算同一个对象」。对比表的编码/名称常与基准表对不上,
           默认「编码+名称都相等」最严格;选后两种时「对象名称」列为必选(要先知道拿哪个字段配名称) -->
      <el-form label-width="90px" class="match-mode-form">
        <el-form-item label="匹配逻辑">
          <el-radio-group v-model="matchMode">
            <el-radio v-for="m in MATCH_MODES" :key="m.value" :value="m.value">{{ m.label }}</el-radio>
          </el-radio-group>
          <div class="step-tip">{{ matchModeTip }}</div>
        </el-form-item>
      </el-form>
      <div class="field-table-wrap">
        <el-table ref="fieldTableRef" :data="columns" v-loading="columnsLoading" border row-key="name"
                  height="100%" @selection-change="onFieldSelectionChange">
          <!-- 第 1 列勾选=参与比对;第 2/3 列单选=对象编码(对齐主键)/对象名称(显示名),
               两组单选与勾选态互相独立(选单选不会自动勾选/取消勾选) -->
          <el-table-column type="selection" width="45" />
          <el-table-column label="对象编码" width="90" align="center">
            <template #default="{ row }">
              <el-radio v-model="keyField" :value="row.name">{{ '' }}</el-radio>
            </template>
          </el-table-column>
          <el-table-column label="对象名称" width="90" align="center">
            <template #default="{ row }">
              <!-- 只有参与比对的字段才能当对象名称(后端要求 displayField 属于 fields);禁用不改勾选态 -->
              <el-radio v-model="displayField" :value="row.name" :disabled="!selectedNames.includes(row.name)">{{ '' }}</el-radio>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="字段名" min-width="170" show-overflow-tooltip />
          <el-table-column label="类型" width="150">
            <template #default="{ row }">
              <el-tag size="small" type="info" plain>{{ row.displayType || row.typeName || '-' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="comment" label="说明" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.comment || '—' }}</template>
          </el-table-column>
        </el-table>
      </div>
      <div class="step-tip">
        已选 {{ selectedNames.length }} 个字段(共 {{ columns.length }} 个),未勾选的字段不参与比对;
        对象编码用于逐行对齐、恒参与比对,对象名称决定差异明细「对象」列的名称(默认第一个文本型非主键字段)
      </div>
    </div>

    <!-- 步骤 3:选择比对系统并确认(与第一步同款四栏级联,选完点「添加为比对系统」入列) -->
    <div v-show="step === 2" class="step-body step-fill">
      <div class="step3-layout">
        <div class="step3-targets">
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
        <!-- 右列:已添加比对系统清单 + 任务摘要(摘要占剩余高度,内容超出时卡内滚动) -->
        <div class="step3-side">
          <div class="target-panel">
            <div class="target-panel-head">
              <span>已添加比对系统</span>
              <span class="target-panel-count">{{ targets.length }}</span>
            </div>
            <div class="target-list">
              <div v-for="(t, i) in targets" :key="i" class="target-item">
                <span class="target-index">{{ i + 1 }}</span>
                <span class="target-label" :title="targetLabel(t)">{{ targetLabel(t) }}</span>
                <el-button link type="danger" @click="targets.splice(i, 1)">删除</el-button>
              </div>
              <div v-if="!targets.length" class="target-empty">还没有比对系统,请在左侧选好库/模式后,点表名右侧的 + 加入(至少 1 个)</div>
            </div>
          </div>
          <!-- 任务摘要:提交前最终确认。label-width + nowrap 固定标签列,避免比对字段/比对系统
               内容过长时(table-layout:auto)把标签列压成一字一行 -->
          <el-descriptions class="step3-summary" :column="1" border size="small" label-width="96px" title="任务摘要">
            <el-descriptions-item label="任务名称">{{ form.name }}</el-descriptions-item>
            <el-descriptions-item label="基准数据源">{{ baseDs?.name || '-' }}</el-descriptions-item>
            <el-descriptions-item label="基准表">{{ baseTableLabel }}</el-descriptions-item>
            <el-descriptions-item label="比对主键">{{ keyField }}</el-descriptions-item>
            <el-descriptions-item label="匹配逻辑">{{ matchModeLabel }}</el-descriptions-item>
            <el-descriptions-item label="比对字段">{{ selectedNames.length }} 个:{{ selectedNames.join('、') }}</el-descriptions-item>
            <el-descriptions-item label="对象名称">{{ displayField || '—(无可用文本字段)' }}</el-descriptions-item>
            <el-descriptions-item label="比对系统">
              <div v-for="(t, i) in targets" :key="i">{{ targetLabel(t) }}</div>
              <span v-if="!targets.length">—</span>
            </el-descriptions-item>
            <el-descriptions-item label="任务类型">长时任务</el-descriptions-item>
          </el-descriptions>
        </div>
      </div>
    </div>

    <!-- 步骤 4:字段映射(左侧基准表固定、右侧各对比表纵向排开单独滚动,人工连线) -->
    <div v-show="step === 3" class="step-body step-fill">
      <CompareFieldMapping
        :base="{ datasourceId: form.datasourceId, db: form.db, schema: form.schema, table: form.table }"
        :base-label="baseTableLabel"
        :targets="mappingTargets"
        :key-field="keyField"
        :base-columns="selectedNames"
        v-model="mappings"
      />
    </div>

    <!-- 向导操作按钮 -->
    <div class="wizard-actions">
      <el-button :disabled="step === 0" @click="step--">上一步</el-button>
      <el-button v-if="step < 3" type="primary" @click="next">下一步</el-button>
      <el-button v-else type="primary" :loading="submitting" @click="submit">开始比对</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '../utils/notify'
import request, { createCompareJob } from '../api'
import TableCascadePicker from '../components/TableCascadePicker.vue'
import CompareFieldMapping from '../components/CompareFieldMapping.vue'

const router = useRouter()

const step = ref(0)
const datasources = ref([])

// ---------- 步骤 1:基准表 ----------

const form = reactive({ name: '', datasourceId: '', db: '', schema: '', table: '' })

const baseDs = computed(() => datasources.value.find((d) => String(d.id) === String(form.datasourceId)))
// 多库方言(SQL Server/Kingbase)需要额外选数据库,与对象管理选表组件同一判定
const baseMultiDb = computed(() => ['SQLSERVER', 'KINGBASE'].includes(baseDs.value?.dbType))

const baseTableLabel = computed(() => {
  if (!form.table) return '-'
  const schemaPart = form.db ? `${form.db}.${form.schema}` : form.schema
  return schemaPart ? `${schemaPart}.${form.table}` : form.table
})

/** 选定基准表后:名称为空时给个默认名,减少手工输入 */
function onBaseTableChange(t) {
  if (!form.name && t?.name) form.name = `${t.name} 数据比对`
}

// ---------- 步骤 2:比对字段 ----------

const columns = ref([])
const columnsLoading = ref(false)
const keyField = ref('')
const selectedNames = ref([])
const fieldTableRef = ref()
// 对象名称(显示名)字段:与「对象编码」并列的单选列,默认预选「自动」候选
const displayField = ref('')

// 文本型 jdbcType(与后端 CompareService.isTextType 同一口径:字符型 + CLOB/NCLOB)
const TEXT_JDBC_TYPES = new Set([1, 12, -1, -15, -9, -16, 2005, 2011])

// 匹配逻辑(与后端 CompareService.MatchMode 同一取值;1=最严格,2/3 需要指定对象名称字段)
const MATCH_MODES = [
  { value: 'EXACT', label: '编码+名称都相等', tip: '对比表的对象编码与对象名称都与基准表完全一致,才算同一个对象(最严格,差异看得最细)' },
  { value: 'CODE_THEN_NAME', label: '先编码后名称', tip: '有编码先用编码配;编码没配上的对象,再用对象名称配一轮' },
  { value: 'CODE_NAME_LLM', label: '编码/名称+大模型归一化', tip: '先按编码、名称配;两侧都没配上的对象交大模型按业务含义再认一轮(需要先配好大模型,残余过多时会自动跳过并提示)' }
]
const matchMode = ref('EXACT')
const matchModeTip = computed(() => MATCH_MODES.find((m) => m.value === matchMode.value)?.tip || '')
const matchModeLabel = computed(() => MATCH_MODES.find((m) => m.value === matchMode.value)?.label || matchMode.value)
// 匹配逻辑 2/3 必须给出对象名称字段(否则无法按名称配对,后端提交时会 400)
const matchModeRequiresName = computed(() => matchMode.value !== 'EXACT')

/** 「自动」候选 = 已勾选字段里第一个文本型非主键字段(按基准表字段顺序);无则空串(提交 null,object_name 落空串) */
function autoDisplayField() {
  const hit = columns.value.find((c) =>
    c.name !== keyField.value && selectedNames.value.includes(c.name) && TEXT_JDBC_TYPES.has(c.jdbcType))
  return hit?.name || ''
}

/** 默认比对字段 = 对象编码(比对主键)+ 对象名称候选(第一个文本型非主键字段);找不到文本列时只勾主键 */
function defaultComparedFields() {
  const key = keyField.value
  const nameCol = columns.value.find((c) => c.name !== key && TEXT_JDBC_TYPES.has(c.jdbcType))
  return [key, nameCol?.name].filter(Boolean)
}

async function loadColumns() {
  columnsLoading.value = true
  columns.value = []
  keyField.value = ''
  selectedNames.value = []
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
    // 默认只勾「对象编码(比对主键)+ 对象名称(第一个文本型非主键字段)」两个字段:
    // 其余字段按需勾选,避免默认全量比对拖慢任务;与后端 displayField/object_name 的自动口径一致
    selectedNames.value = defaultComparedFields()
    displayField.value = autoDisplayField()
    await nextTick()
    syncFieldSelection()
  } catch {
    ElMessage.error('字段列表加载失败,请返回上一步重试')
  } finally {
    columnsLoading.value = false
  }
}

/**
 * 对象名称列只接受「仍处于勾选态」的字段:所选字段被取消勾选后回退到「自动」候选,
 * 避免提交时被后端判「对象名称字段必须在比对字段内」。勾选态本身不受单选列影响。
 */
function refreshDisplayField() {
  if (displayField.value && selectedNames.value.includes(displayField.value)) return
  displayField.value = autoDisplayField()
}

/** 把 selectedNames 同步到表格勾选态 */
function syncFieldSelection() {
  const table = fieldTableRef.value
  if (!table) return
  table.clearSelection()
  const want = new Set(selectedNames.value)
  for (const c of columns.value) {
    if (want.has(c.name)) table.toggleRowSelection(c, true)
  }
}

function onFieldSelectionChange(rows) {
  selectedNames.value = rows.map((r) => r.name)
  refreshDisplayField()
}

// ---------- 步骤 3:比对系统 ----------

// 已入列的比对系统:{ datasourceId, db, schema, table }
const targets = ref([])
// 级联面板当前所在的 数据源/库/模式(第三步只选到库/模式,具体表在表行上用 +/− 逐张切换)
const pick = reactive({ datasourceId: '', db: '', schema: '' })
// 第四步字段映射:数组与 targets 同序,元素为 { 基准字段名: 目标列名 };对比表清单一变就重置重连
const mappings = ref([])
const submitting = ref(false)

watch(() => targets.value.map((t) => `${t.datasourceId}|${t.db}|${t.schema}|${t.table}`).join(','),
  () => { mappings.value = [] })

/** 交给映射画布的对比表清单(带展示名) */
const mappingTargets = computed(() => targets.value.map((t) => ({
  datasourceId: t.datasourceId, db: t.db || '', schema: t.schema, table: t.table, label: targetLabel(t)
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

function targetMultiDb(t) {
  return ['SQLSERVER', 'KINGBASE'].includes(targetDs(t)?.dbType)
}

function targetLabel(t) {
  const ds = targetDs(t)
  if (!ds || !t.table) return ''
  const schemaPart = t.db ? `${t.db}.${t.schema}` : t.schema
  return `${ds.name} · ${schemaPart ? schemaPart + '.' : ''}${t.table}`
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

// ---------- 向导流转与提交 ----------

function next() {
  if (step.value === 0) {
    if (!form.name.trim()) return ElMessage.warning('请填写任务名称')
    if (!form.datasourceId) return ElMessage.warning('请选择基准数据源')
    if (baseMultiDb.value && !form.db) return ElMessage.warning('请选择数据库')
    if (!form.schema) return ElMessage.warning('请选择库/schema')
    if (!form.table) return ElMessage.warning('请选择基准表')
    step.value = 1
    loadColumns()
    return
  }
  if (step.value === 1) {
    if (!columns.value.length) return ElMessage.warning('基准表字段未加载,无法继续')
    if (!keyField.value) return ElMessage.warning('请选择比对主键')
    if (!selectedNames.value.length) return ElMessage.warning('请至少勾选 1 个比对字段')
    // 匹配逻辑 2/3 靠对象名称配对,没有名称字段就无法执行
    if (matchModeRequiresName.value && !displayField.value) {
      return ElMessage.warning(`匹配逻辑「${matchModeLabel.value}」需要指定对象名称字段,请在「对象名称」列选择`)
    }
    step.value = 2
    return
  }
  if (step.value === 2) {
    if (!targets.value.length) return ElMessage.warning('请至少添加 1 个对比表(数据源 + 库/模式 + 表)')
    step.value = 3
  }
}

async function submit() {
  if (!targets.value.length) return ElMessage.warning('请至少添加 1 个对比表(数据源 + 库/模式 + 表)')
  if (matchModeRequiresName.value && !displayField.value) {
    return ElMessage.warning(`匹配逻辑「${matchModeLabel.value}」需要指定对象名称字段,请回到第 2 步选择`)
  }
  const unmapped = targets.value.filter((t, i) => !isKeyMapped(mappings.value[i]))
  if (unmapped.length) {
    return ElMessage.warning(`有 ${unmapped.length} 个对比表还没连上比对主键「${keyField.value}」,请回到第 4 步连线`)
  }
  submitting.value = true
  try {
    // 比对字段保持基准表字段顺序,主键兜底包含
    const fields = columns.value.map((c) => c.name).filter((n) => selectedNames.value.includes(n) || n === keyField.value)
    // 对象名称字段必须落在本次提交的 fields 内(两列独立,这里兜底,不让后端 400)
    const displayFieldName = fields.includes(displayField.value) ? displayField.value : null
    const res = await createCompareJob({
      name: form.name.trim(),
      baseDatasourceId: Number(form.datasourceId),
      baseDb: form.db || null,
      baseSchema: form.schema,
      baseTable: form.table,
      keyField: keyField.value,
      fields,
      displayField: displayFieldName,
      // 对象对齐匹配逻辑(第 2 步选择):EXACT / CODE_THEN_NAME / CODE_NAME_LLM
      matchMode: matchMode.value,
      targets: targets.value.map((t, i) => ({
        datasourceId: Number(t.datasourceId),
        db: t.db || null,
        schema: t.schema,
        table: t.table,
        // 第四步人工连线的字段映射;空对象 = 不指定,后端按字段名自动匹配
        mapping: Object.keys(mappings.value[i] || {}).length ? mappings.value[i] : null
      }))
    })
    ElMessage.success(`比对任务 T-${res.jobId} 已创建,开始执行`)
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

/* 第一步/第二步/第三步:纵向撑满卡片剩余高度,内容区拉满、底部按钮始终贴底 */
.step-fill {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
}

/* 第二步匹配逻辑表单:卡片高度被字段表吃掉剩余空间,这里只需紧凑排版 */
.match-mode-form {
  flex: none;
  margin-bottom: 8px;
}
.match-mode-form :deep(.el-form-item) {
  margin-bottom: 0;
}
.match-mode-form :deep(.el-form-item__content) {
  display: block;
}

/* 第二步字段表容器:吃掉 step-fill 的剩余高度,el-table 用 height=100% 内部滚动 */
.field-table-wrap {
  flex: 1 1 auto;
  min-height: 0;
}

.step-tip {
  margin-top: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 步骤 3:左侧级联面板 + 添加按钮,右侧「已添加比对系统 + 任务摘要」两卡竖排 */
.step3-layout {
  display: flex;
  gap: 24px;
  align-items: stretch;
  flex: 1 1 auto;
  min-height: 0;
}
.step3-targets {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.step3-side {
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
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
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
/* 摘要卡:占右列剩余高度,内容(字段全列)超出时卡片内滚动,不撑破固定高度的卡片 */
.step3-summary {
  flex: 1 1 auto;
  width: 100%;
  min-height: 0;
  overflow: auto;
}
/* 标签列固定宽 + 不换行:否则比对字段等内容过长时会被 table-layout:auto 压成一字一行 */
.step3-summary :deep(.el-descriptions__label) {
  white-space: nowrap;
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
