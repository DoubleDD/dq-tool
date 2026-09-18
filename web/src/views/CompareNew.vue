<template>
  <div class="page-card card-fill">
    <div class="toolbar">
      <div class="title-wrap">
        <h3 style="margin: 0">{{ isEdit ? `编辑比对任务(T-${editJobId})` : '新建比对任务' }}</h3>
        <!-- 编辑模式标题旁显示任务状态标签(口径同列表页;待处理带原因悬浮提示) -->
        <el-tooltip v-if="isEdit && editJobStatus === 'PENDING'" :content="pendingReasonTip" placement="top" :show-after="200">
          <el-tag type="info" size="small">待处理</el-tag>
        </el-tooltip>
        <el-tag v-else-if="isEdit && editJobStatus" :type="statusTagType(editJobStatus)" size="small">
          {{ statusText(editJobStatus) }}
        </el-tag>
      </div>
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
           这里只定两类身份字段:默认身份字段(编码,对齐用,可多选=组合身份,恒参与比对)/对象名称(差异明细「对象」列显示名) -->
      <div v-if="form.table" class="field-table-wrap">
        <el-table :data="columns" v-loading="columnsLoading" border row-key="name" height="100%">
          <!-- 默认身份字段(编码)多选:勾多个 = 任务级默认组合身份;各对比表可在第 3 步按连线单独收缩 -->
          <el-table-column label="默认身份字段(编码)" width="130" align="center">
            <template #default="{ row }">
              <el-checkbox :model-value="keyFields.includes(row.name)" @change="(v) => toggleKeyField(row.name, v)">{{ '' }}</el-checkbox>
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
        基准表为权威数据,其他系统的数据将按身份字段逐行对齐到该表;默认身份字段(编码)用于逐行对齐、恒参与比对
        (勾多个 = 组合身份,全部相等才算同一行;各对比表可在第 3 步按实际连线单独收缩身份字段),
        对象名称决定差异明细「对象」列的名称(默认第一个文本型非主键字段);
        其余字段是否参与比对由第 3 步连线决定——有连线的基准字段即比对字段
      </div>
    </div>

    <!-- 步骤 2:选择比对系统并确认(多表选择器:左侧级联逐表 +/−,右侧已添加清单) -->
    <div v-show="step === 1" class="step-body step-fill">
      <TableMultiPicker
        v-model="targets"
        :datasources="datasources"
        :disabled-tables="disabledTargetTables"
        :validate-add="validateTarget"
        label="比对系统"
        panel-title="已添加比对系统"
        empty-text="还没有比对系统,请在左侧选好库/模式后,点表名右侧的 + 加入(至少 1 个)"
        :panel-max-height="168"
        @lane-change="onLaneChange"
      />
    </div>

    <!-- 步骤 3:字段映射(左侧基准表固定、右侧各对比表纵向排开单独滚动,人工连线;
         有连线的基准字段即比对字段,提交时按连线并集 + 身份字段汇总比对字段);
         画布上方身份条:逐对比表展示有效身份(推导 = 已连线的默认身份字段;可收缩为子集人工覆盖) -->
    <div v-show="step === 2" class="step-body step-fill">
      <div v-if="targets.length" class="identity-bar">
        <span class="identity-bar-label">有效身份</span>
        <div v-for="(t, i) in targets" :key="i" class="identity-item">
          <span class="identity-name" :title="targetLabel(t)">{{ t.table }}</span>
          <template v-if="identityState(i).effective.length">
            <el-tag v-for="k in identityState(i).effective" :key="k" size="small"
                    :type="identityState(i).override ? 'warning' : 'info'" effect="plain">{{ k }}</el-tag>
            <!-- 连上多个身份字段才允许收缩(单个无从收缩);覆盖标记 = 橙 tag + 「已调整」 -->
            <el-button v-if="identityState(i).derived.length > 1" size="small" link type="primary"
                       @click="openIdentityDialog(i)">{{ identityState(i).override ? '已调整' : '调整' }}</el-button>
          </template>
          <el-tag v-else size="small" type="danger">未连身份字段</el-tag>
        </div>
      </div>
      <CompareFieldMapping
        :base="{ datasourceId: form.datasourceId, db: form.db, schema: form.schema, table: form.table }"
        :base-label="baseTableLabel"
        :targets="mappingTargets"
        :key-fields="keyFields"
        v-model="mappings"
      >
        <!-- 列级对比:大模型预生成字段映射,放工具条最左(基准表全字段产出建议);人工在画布审核后可再手动增删 -->
        <template #toolbar-prepend>
          <template v-if="compareMode === 'COLUMN'">
            <el-button size="small" type="primary" plain :loading="aiSuggesting" :disabled="!targets.length" @click="aiSuggestMapping">
              AI 预生成字段映射
            </el-button>
            <!-- <span class="ai-suggest-tip">{{ aiSuggestNote || '大模型按字段名/注释逐目标产出映射建议,请在画布核对连线后再提交' }}</span> -->
          </template>
        </template>
      </CompareFieldMapping>
      <!-- 目标级身份收缩弹窗:候选 = 该对比表已连线的默认身份字段,勾选的子集作为 targets[].identity.keys 提交;
           全勾 = 与推导一致,不存覆盖 -->
      <el-dialog v-model="identityDialog.visible" title="调整该对比表的身份字段" width="420px" append-to-body>
        <div class="identity-dialog-tip">
          {{ identityDialog.index >= 0 ? targetLabel(targets[identityDialog.index]) : '' }}:取消勾选的字段不参与判同(至少保留 1 个)
        </div>
        <el-checkbox-group v-model="identityDialog.draft">
          <el-checkbox v-for="k in identityDialogCandidates" :key="k" :value="k" style="display: flex">{{ k }}</el-checkbox>
        </el-checkbox-group>
        <template #footer>
          <el-button @click="identityDialog.visible = false">取消</el-button>
          <el-button type="primary" :disabled="!identityDialog.draft.length" @click="confirmIdentityDialog">确定</el-button>
        </template>
      </el-dialog>
    </div>

    <!-- 向导操作按钮 -->
    <div class="wizard-actions">
      <el-button :disabled="step === 0" @click="step--">上一步</el-button>
      <el-button v-if="step < 2" type="primary" @click="next">下一步</el-button>
      <template v-else>
        <!-- 按钮文案分态:新建=开始比对 / 待处理编辑=保存修改(仍待处理) / 终态编辑=保存并重新比对 -->
        <el-button type="primary" :loading="submitting" :disabled="starting" @click="submit">{{ submitText }}</el-button>
        <!-- 待处理编辑多一个「保存并比对」:保存后直接 start 直启,省掉回列表再点「字段审核」 -->
        <el-button v-if="isEdit && editJobStatus === 'PENDING'" type="primary" :loading="starting" :disabled="submitting"
                   @click="submitAndStart">保存并比对</el-button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { QuestionFilled } from '@element-plus/icons-vue'
import { ElMessage } from '../utils/notify'
import request, { createCompareJob, getCompareJob, startCompareJob, suggestCompareMapping, updateCompareJob } from '../api'
import { statusTagType, statusText } from '../utils/format'
import { watchTask } from '../stores/backgroundTasks'
import TableCascadePicker from '../components/TableCascadePicker.vue'
import TableMultiPicker from '../components/TableMultiPicker.vue'
import CompareFieldMapping from '../components/CompareFieldMapping.vue'

const router = useRouter()

// ---------- 编辑模式(/compare/new?edit=<jobId>:「待处理」导入任务逐步确认/修改,终态任务再次编辑并重跑) ----------
// 组件按 route.fullPath 作 keep-alive key,?edit 变化即整页重挂载,query 只读一次即可
const route = useRoute()
const rawEditId = Number(route.query.edit)
// edit 非法(缺省/非数字)时按新建模式处理
const editJobId = Number.isInteger(rawEditId) && rawEditId > 0 ? rawEditId : null
const isEdit = !!editJobId
// 被编辑任务的状态(prefillEdit 反填时记录):PENDING=导入待处理(保存仍待处理)/终态=保存并直接重跑
const editJobStatus = ref(null)
// 被编辑任务的待处理原因(仅 PENDING;标题状态标签的悬浮提示用)
const editJobPendingReason = ref(null)
// 待处理原因 → 提示文案(与任务列表 PENDING_REASON_TIPS 同一取值口径)
const PENDING_REASON_TIPS = {
  DS_ERROR: '数据源异常:涉及的数据源连不上或未实测通过;修复数据源后仍可经「字段审核」确认开跑',
  MAPPING_RUNNING: '映射推导中:数据源连接实测与字段映射正在后台推导,完成后转「映射待审核」,届时可逐个审核',
  MAPPING_REVIEW: '映射待审核:字段映射已预生成,需人工审核(「字段审核」或「编辑」)确认后开始比对',
  IMPORT_ERROR: '导入异常:基准表校验未通过(表不存在或缺身份字段),请在本页修正后保存'
}
const pendingReasonTip = computed(() => PENDING_REASON_TIPS[editJobPendingReason.value] || '待处理:需人工处理后才开始比对')
// 提交按钮文案:新建=开始比对;编辑待处理=保存修改(仍待处理);编辑终态=保存并重新比对
const submitText = computed(() => {
  if (!isEdit) return '开始比对'
  return editJobStatus.value === 'PENDING' ? '保存修改' : '保存并重新比对'
})

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
// 默认身份字段(编码,基准表列名数组):默认勾 pkSeq 最小的主键列,无主键勾第一列;勾多个 = 组合身份,恒参与比对。
// 语义是「任务级默认身份/身份字段并集」:各对比表的有效身份 = 第 3 步已连线的 keyFields 子集(可人工收缩,见 identityOverrides)
const keyFields = ref([])

/** 勾选/取消默认身份字段:保持基准表字段顺序(keyFields 顺序即组合身份字段顺序) */
function toggleKeyField(name, checked) {
  const set = new Set(keyFields.value)
  if (checked) set.add(name)
  else set.delete(name)
  keyFields.value = columns.value.map((c) => c.name).filter((n) => set.has(n))
}
// 对象名称(显示名)字段:差异明细「对象」列的名称来源,默认第一个文本型非主键字段
const displayField = ref('')

// 文本型 jdbcType(与后端 CompareService.isTextType 同一口径:字符型 + CLOB/NCLOB)
const TEXT_JDBC_TYPES = new Set([1, 12, -1, -15, -9, -16, 2005, 2011])

// 匹配逻辑(与后端 CompareService.MatchMode 同一取值;EXACT=最严格:只按对象编码配对,
// 名称写法不同也算命中、名称差异走字段级差异;大模型归一化需要指定对象名称字段)
const MATCH_MODES = [
  { value: 'EXACT', label: '编码+名称', tip: '只按对象编码配对:编码一致即同一个对象(名称写法不同也算同一个对象,名称差异按字段差异体现);编码没配上的对象直接算缺失/多余,不做名称或大模型补配(最严格,差异看得最细)' },
  { value: 'CODE_NAME_LLM', label: '先编码后名称+大模型归一化', tip: '先用对象编码配;编码没配上的再按对象名称配;都没配上的交大模型按业务含义再认一轮(需要先配好大模型,残余过多时会自动跳过并提示)' }
]
const matchMode = ref('EXACT')
const matchModeTip = computed(() => MATCH_MODES.find((m) => m.value === matchMode.value)?.tip || '')
const matchModeLabel = computed(() => MATCH_MODES.find((m) => m.value === matchMode.value)?.label || matchMode.value)
// 「先编码后名称+大模型归一化」必须给出对象名称字段(否则无法按名称配对,后端提交时会 400)
const matchModeRequiresName = computed(() => matchMode.value !== 'EXACT')

/** 「自动」候选 = 第一个文本型非身份字段(按基准表字段顺序);无则空串(提交 null,object_name 落空串) */
function autoDisplayField() {
  const hit = columns.value.find((c) => !keyFields.value.includes(c.name) && TEXT_JDBC_TYPES.has(c.jdbcType))
  return hit?.name || ''
}

async function loadColumns() {
  columnsLoading.value = true
  // 初始化本步数据期间抑制数据链监听,避免被误判成「用户修改第 1 步数据」而清空后续步骤
  suppressInvalidate = true
  columns.value = []
  keyFields.value = []
  displayField.value = ''
  try {
    const q = form.db ? `?db=${encodeURIComponent(form.db)}` : ''
    const list = await request.get(
      `/datasources/${form.datasourceId}/schemas/${encodeURIComponent(form.schema)}/tables/${encodeURIComponent(form.table)}/columns${q}`
    )
    columns.value = list || []
    // 默认身份字段:主键列中 pkSeq 最小的;无主键则勾第一列
    const pk = [...columns.value].filter((c) => c.primaryKey).sort((a, b) => (a.pkSeq || 0) - (b.pkSeq || 0))
    keyFields.value = [(pk[0] || columns.value[0])?.name].filter(Boolean)
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
// 级联面板当前所在的 数据源/库/模式(由多表选择器 lane-change 同步,用来算「基准表本身」置灰)
const pick = reactive({ datasourceId: '', db: '', schema: '' })

/** 多表选择器级联所在栏变化时同步过来 */
function onLaneChange(ctx) {
  Object.assign(pick, ctx)
}
// 第 3 步字段映射:数组与 targets 同序,元素为 { 基准字段名: 目标列名 }
const mappings = ref([])
const submitting = ref(false)
// 待处理编辑「保存并比对」进行中(PUT 保存 + start 直启两步)
const starting = ref(false)
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

// ---------- 步骤 3:目标级有效身份(推导 + 人工收缩覆盖) ----------

// 目标级身份人工覆盖:数组与 targets 同序,元素为 null(按推导)或 string[](收缩后的 keyFields 子集);
// 只存「与推导不一致」的人工收缩,随第 3 步数据链一起作废(见 invalidateFrom)
const identityOverrides = ref([])

/** 该对比表映射中已连线的默认身份字段(推导身份;忽略大小写命中,返回 keyFields 原始大小写与顺序) */
function connectedKeys(map) {
  const lower = new Set(Object.keys(map || {}).map((bf) => bf.toLowerCase()))
  return keyFields.value.filter((k) => lower.has(k.toLowerCase()))
}

/**
 * 目标 i 的身份三态:derived = 推导(已连线的默认身份字段);override = 人工收缩(仍连着线的部分,
 * 与推导一致时归一为 null,不重复落 identity);effective = 实际生效(覆盖优先,否则推导)。
 * 覆盖里的字段被断线后自动从覆盖中剔除,全断完则退回推导——身份永远跟连线走,不会产生悬空覆盖
 */
function identityState(i) {
  const derived = connectedKeys(mappings.value[i])
  const ov = (identityOverrides.value[i] || []).filter((k) => derived.some((d) => d.toLowerCase() === k.toLowerCase()))
  const override = ov.length && ov.length < derived.length ? ov : null
  return { derived, override, effective: override || derived }
}

// 「调整身份」弹窗:候选 = 该对比表已连线的默认身份字段,draft = 勾选中的子集
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

/** 加入比对系统前校验:基准表本身不可作为目标(组件里已置灰,这里兜底拦快捷键路径) */
function validateTarget(t) {
  return isSameAsBase(t) ? '基准表本身不能作为比对系统' : true
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
      keyField: keyFields.value[0] || '', // 映射预生成接口仍收单主键:取第一个默认身份字段
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
      keyFields.value = []
      displayField.value = ''
      matchMode.value = 'EXACT'
      loadedTableKey.value = ''
      targets.value = []
    }
    if (n < 2) {
      // 第 3 步(字段映射)的数据:连线与目标级身份覆盖一并作废
      mappings.value = []
      identityOverrides.value = []
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
    if (!keyFields.value.length) return ElMessage.warning('请至少勾选一个默认身份字段(对象编码)')
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

/** 提交前校验(新建/编辑/保存并比对共用):不通过时弹提示并返回 false */
function validateSubmit() {
  if (!targets.value.length) {
    ElMessage.warning('请至少添加 1 个对比表(数据源 + 库/模式 + 表)')
    return false
  }
  if (matchModeRequiresName.value && !displayField.value) {
    ElMessage.warning(`匹配逻辑「${matchModeLabel.value}」需要指定对象名称字段,请回到第 1 步选择`)
    return false
  }
  const unmapped = targets.value.filter((t, i) => !identityState(i).effective.length)
  if (unmapped.length) {
    // 点名是哪几个对比表没有任何有效身份(数据源 · 库.模式.表),别让用户在多目标里自己猜
    const names = unmapped.map((t) => targetLabel(t) || t.table).join('、')
    ElMessage.warning(`有 ${unmapped.length} 个对比表还没有有效身份字段:${names}。请回到第 3 步,每张对比表至少连一个默认身份字段(或保留有效的身份覆盖)`)
    return false
  }
  return true
}

/**
 * 组装提交载荷(新建/编辑共用):比对字段 = 第 3 步连线的基准字段并集(有连线即参与比对)+ 身份字段
 * (默认身份字段/对象名称恒参与),保持基准表字段顺序;后端两条约束「映射的基准字段必须在比对字段内」
 * 「对象名称字段必须在比对字段内」由此一并满足
 */
function buildPayload() {
  const picked = new Set(keyFields.value)
  if (displayField.value) picked.add(displayField.value)
  for (const m of mappings.value) {
    for (const bf of Object.keys(m || {})) picked.add(bf)
  }
  const pickedLower = new Set([...picked].map((n) => n.toLowerCase()))
  const fields = columns.value.map((c) => c.name).filter((n) => pickedLower.has(n.toLowerCase()))
  return {
    name: form.name.trim(),
    baseDatasourceId: Number(form.datasourceId),
    baseDb: form.db || null,
    baseSchema: form.schema,
    baseTable: form.table,
    // 任务级默认身份字段(基准字段名数组,至少 1 个);keyField 继续带第一项做旧列兼容(后端按 keys[0] 写 key_field)
    keyFields: [...keyFields.value],
    keyField: keyFields.value[0] || '',
    fields,
    displayField: displayField.value || null,
    // 对象对齐匹配逻辑(第 1 步选择):EXACT / CODE_NAME_LLM;编辑模式同样允许改(不做限制)
    matchMode: matchMode.value,
    // 对比模式(第 1 步复选框):ROW 仅行级 / COLUMN 行级+列级;编辑模式同样允许改
    compareMode: compareMode.value,
    targets: targets.value.map((t, i) => ({
      datasourceId: Number(t.datasourceId),
      db: t.db || null,
      schema: t.schema,
      table: t.table,
      // 第 3 步人工连线的字段映射;空对象 = 不指定,后端按字段名自动匹配
      mapping: Object.keys(mappings.value[i] || {}).length ? mappings.value[i] : null,
      // 目标级身份人工覆盖:仅收缩过(与推导不一致)时带 { keys };null = 按推导(已连线的默认身份字段)
      identity: identityState(i).override ? { keys: identityState(i).override } : null
    }))
  }
}

async function submit() {
  if (!validateSubmit()) return
  submitting.value = true
  try {
    const payload = buildPayload()
    if (isEdit) {
      // 编辑模式:PUT 更新;待处理任务保持 PENDING(原因归一 MAPPING_REVIEW,回列表「字段审核」开跑,
      // 或直接点旁边的「保存并比对」),终态任务保存后按新配置直接重跑(旧差异明细覆盖,后端口径)
      await updateCompareJob(editJobId, payload)
      if (editJobStatus.value === 'PENDING') {
        ElMessage.success(`任务 T-${editJobId} 已保存,仍为「待处理」:请回列表经「字段审核」确认后开始比对`)
      } else {
        ElMessage.success(`任务 T-${editJobId} 已保存,开始重新比对`)
        watchTask() // 终态编辑=重跑:同样登记全局后台任务跟踪器
      }
    } else {
      const res = await createCompareJob(payload)
      ElMessage.success(`比对任务 T-${res.jobId} 已创建,开始执行`)
      watchTask() // 登记到全局后台任务跟踪器:离开列表页也能在头栏看进度、完成收通知
    }
    router.push('/compare')
  } finally {
    submitting.value = false
  }
}

/**
 * 待处理任务「保存并比对」:先按编辑口径 PUT 保存(校验/落库同 submit),再调 start 直启,
 * 省掉「回列表 → 字段审核 → 确认」一圈;start 被拦(DS_ERROR/映射未就绪等)时任务已保存、
 * 仍停「待处理」,错误由拦截器提示
 */
async function submitAndStart() {
  if (!validateSubmit()) return
  starting.value = true
  try {
    await updateCompareJob(editJobId, buildPayload())
    await startCompareJob(editJobId)
    ElMessage.success(`任务 T-${editJobId} 已保存,开始比对`)
    watchTask() // 与终态编辑重跑同口径:登记全局后台任务跟踪器
    router.push('/compare')
  } catch { /* 拦截器已提示 */ } finally {
    starting.value = false
  }
}

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

/**
 * 编辑预填(?edit=<jobId>):拉任务详情反填三步全部数据——任务名、基准四元组、身份字段、
 * 对比表清单与既有连线。可编辑状态:PENDING(待处理,保存后仍待处理)与终态 DONE/FAILED/CANCELED
 * (已完成再次编辑,保存后直接重跑);所有内容均可改(含对比模式/匹配逻辑,与终态编辑同口径)。
 * RUNNING 不可编辑。
 * 反填期间抑制数据链监听(suppressInvalidate),否则逐字段赋值会被误判成「用户改了第 1 步」
 * 而清空后两步;字段清单手动 loadColumns 一次(它会记录 loadedTableKey,之后回看重选同一张表
 * 不再清空重载),身份字段在其后改回任务值(loadColumns 默认按主键列/首文本列重置,不代表任务实际选择)
 */
async function prefillEdit(jobId) {
  let d
  try {
    d = await getCompareJob(jobId)
  } catch {
    router.push('/compare') // 拦截器已提示
    return
  }
  const job = d?.job
  if (!job) return
  if (!['PENDING', 'DONE', 'FAILED', 'CANCELED'].includes(job.status)) {
    ElMessage.warning('仅「待处理」或已结束的任务可以编辑')
    router.push('/compare')
    return
  }
  editJobStatus.value = job.status
  editJobPendingReason.value = job.pendingReason
  suppressInvalidate = true
  try {
    form.name = job.name
    form.datasourceId = String(job.baseDatasourceId)
    form.db = job.baseDb || ''
    form.schema = job.baseSchema || ''
    form.table = job.baseTable
    columnCompare.value = job.compareMode === 'COLUMN'
    matchMode.value = job.matchMode || 'EXACT'
    targets.value = (d.targets || []).map((t) => ({
      datasourceId: t.datasourceId, db: t.db || '', schema: t.schema || '', table: t.table
    }))
    mappings.value = (d.targets || []).map((t) => ({ ...(t.mapping || {}) }))
    // 目标级身份覆盖:identityJson 解析反填(null = 按推导);人工收缩的勾选在身份条上还原
    identityOverrides.value = (d.targets || []).map((t) => parseIdentityKeys(t.identityJson))
  } finally {
    // 等本轮 watch 冲刷完再解除抑制:反填触发的数据链监听(基准四元组/对比表清单变化)全部被跳过
    await nextTick()
    suppressInvalidate = false
  }
  await loadColumns()
  // 默认身份字段改回任务值:keyFieldsJson(JSON 数组)优先,老任务(null)退化为 [keyField] 单列;
  // 基准表读不出(IMPORT_ERROR)时按任务原值兜底,便于修正后重选
  const kf = parseJsonArray(job.keyFieldsJson)
  if (kf?.length) keyFields.value = kf
  else if (job.keyField) keyFields.value = [job.keyField]
  if (job.displayField) displayField.value = job.displayField
}

onMounted(async () => {
  try {
    datasources.value = await request.get('/datasources') || []
  } catch { /* 拦截器已提示 */ }
  // 编辑模式:数据源清单到位后再反填(级联选择器的多库判定依赖 dbType)
  if (isEdit) await prefillEdit(editJobId)
})
</script>

<style scoped>
.title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}
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

/* 第 3 步身份条:逐对比表展示有效身份(推导灰 tag / 人工覆盖橙 tag),横向滚动不换行 */
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
