<template>
  <div class="page-card">
    <div class="toolbar">
      <Breadcrumb :items="breadcrumbItems" />
      <div class="toolbar-actions">
        <el-button :icon="Refresh" :loading="refreshing" @click="refreshStats">刷新</el-button>
        <el-dropdown trigger="click" @command="onExportCommand">
          <el-button :disabled="!filteredSchemas.length">导出<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="exportCurrent">导出当前列表</el-dropdown-item>
              <el-dropdown-item command="export" :loading="exporting">导出扫描报告</el-dropdown-item>
              <el-dropdown-item command="exportDbStruct">导出表结构文档</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button type="primary" :disabled="!selected.length" :loading="submitting" @click="scanSelected">
          批量扫描{{ selected.length ? `(${selected.length})` : '' }}
        </el-button>
        <el-dropdown trigger="click" @command="onMoreCommand">
          <el-button>更多<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="filter">库过滤</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>
    <!-- 数据源不可达降级提示:本次库/架构清单来自本地 H2 缓存 -->
    <el-alert v-if="cacheFallback" type="warning" :closable="false" show-icon style="margin-bottom: 12px">
      <span>数据源当前不可达,正在展示<b>本地缓存</b>的库/架构清单;恢复网络后点「刷新」可重新同步。</span>
    </el-alert>
    <!-- SQL Server 跨库列表:搜索框同时匹配数据库名与架构名 -->
    <el-input v-model="keyword" :placeholder="isMultiDb ? '按数据库/架构名搜索' : '按库名搜索'" clearable style="width: 280px; margin-bottom: 12px" />
    <el-table :data="filteredSchemas" v-loading="loading" border :row-key="(row) => rowKeyOf(row.database, row.name)" @selection-change="onSelectionChange">
      <el-table-column type="selection" width="45" reserve-selection />
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column v-if="isMultiDb" prop="database" label="数据库" min-width="140" sortable />
      <el-table-column prop="name" :label="isMultiDb ? '架构(Schema)' : '库名(Schema)'" min-width="200" sortable>
        <template #default="{ row }">
          <el-link type="primary" @click="goTables(row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="描述" min-width="160">
        <template #default="{ row }">
          <span v-if="row.description" style="margin-right: 4px">{{ row.description }}</span>
          <span v-else style="color: var(--el-text-color-secondary); margin-right: 4px">—</span>
          <el-button link type="primary" @click="openDesc(row)">编辑</el-button>
        </template>
      </el-table-column>
      <el-table-column label="表数量" width="100" align="right" sortable :sort-method="(a, b) => (a.tableCount ?? -1) - (b.tableCount ?? -1)">
        <template #default="{ row }">
          <span :style="!row.tableCount ? 'color: var(--el-text-color-secondary)' : ''">{{ formatNumber(row.tableCount ?? 0) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="占用空间" width="110" align="right" sortable :sort-method="(a, b) => (a.sizeBytes ?? -1) - (b.sizeBytes ?? -1)">
        <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
      </el-table-column>
      <el-table-column label="标记" min-width="220">
        <template #default="{ row }">
          <template v-if="row.tags && row.tags.length">
            <el-tag
              v-for="tag in row.tags.slice(0, TAG_VISIBLE_COUNT)"
              :key="tag.tagId"
              :type="tag.kind === 'EMPTY' ? 'info' : 'primary'"
              size="small"
              effect="plain"
              style="cursor: pointer; margin: 0 4px 4px 0"
              @click="goTagTables(row, tag)"
            >
              <span :style="{ display: 'inline-block', width: '8px', height: '8px', borderRadius: '50%', background: tag.color, marginRight: '4px' }" />{{ tag.tagName }} {{ tag.count }}
            </el-tag>
            <el-popover v-if="row.tags.length > TAG_VISIBLE_COUNT" trigger="click" placement="bottom-start" width="280">
              <template #reference>
                <el-tag size="small" effect="plain" style="cursor: pointer; margin: 0 4px 4px 0">+{{ row.tags.length - TAG_VISIBLE_COUNT }} 更多</el-tag>
              </template>
              <div>
                <el-tag
                  v-for="tag in row.tags"
                  :key="tag.tagId"
                  :type="tag.kind === 'EMPTY' ? 'info' : 'primary'"
                  size="small"
                  effect="plain"
                  style="cursor: pointer; margin: 0 6px 6px 0"
                  @click="goTagTables(row, tag)"
                >
                  <span :style="{ display: 'inline-block', width: '8px', height: '8px', borderRadius: '50%', background: tag.color, marginRight: '4px' }" />{{ tag.tagName }} {{ tag.count }}
                </el-tag>
              </div>
            </el-popover>
          </template>
          <span v-else style="color: var(--el-text-color-secondary)">—</span>
        </template>
      </el-table-column>
      <el-table-column label="最近扫描" min-width="260" sortable :sort-method="(a, b) => scanAtMs(a) - scanAtMs(b)">
        <template #default="{ row }">
          <template v-if="row.lastScanStatus">
            <el-tag :type="statusTagType(row.lastScanStatus)" size="small" style="margin-right: 8px">
              {{ statusText(row.lastScanStatus) }}
            </el-tag>
            <el-progress
              v-if="['PENDING', 'RUNNING'].includes(row.lastScanStatus)"
              :percentage="scanPercent(row)"
              :stroke-width="10"
              style="width: 130px; display: inline-flex; vertical-align: middle"
            />
            <span v-else style="color: var(--el-text-color-secondary)">{{ formatDateTime(row.lastScanAt) }}</span>
          </template>
          <span v-else style="color: var(--el-text-color-secondary)">未扫描</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="isScanning(row)" @click="scanOne(row)">扫描</el-button>
          <el-button link type="primary" :disabled="statsLoaded && !row.lastScanStatus" @click="goScans(row)">扫描记录</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 开始扫描:与表列表页扫描弹窗保持一致(扫描范围为整库) -->
    <el-dialog v-model="scanDialogVisible" title="开始扫描" width="640px" destroy-on-close :close-on-press-escape="false">
      <el-form label-width="110px">
        <el-form-item label="扫描范围">
          <span v-if="scanTargets.length === 1">将对库「{{ displayName(scanTargets[0]) }}」发起全库扫描</span>
          <span v-else>将对 {{ scanTargets.length }} 个库发起全库扫描</span>
        </el-form-item>
        <el-form-item label="强制全量">
          <el-switch v-model="scanForm.forceFull" />
          <div class="form-tip">超过阈值的表不做采样,逐行精确统计</div>
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
          </div>
        </el-form-item>
        <el-form-item label="AI 自动打标">
          <el-checkbox v-model="scanForm.autoTag">扫描完成后由大模型自动打标</el-checkbox>
          <el-tooltip placement="top" :show-after="200">
            <template #content>
              <div>每张表扫描完成后,由大模型根据表注释/字段注释/表描述,从标记列表中选择最合适的标记自动打上(只增不删,已有标记的表不覆盖)</div>
              <div>表无任何注释时会抽样 100 行业务数据一并发送给大模型;未配置大模型时自动跳过</div>
            </template>
            <el-icon style="vertical-align: -2px; margin-left: 4px"><QuestionFilled /></el-icon>
          </el-tooltip>
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
        <el-button @click="scanDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitScans">提交扫描</el-button>
      </template>
    </el-dialog>

    <!-- 库描述编辑:用于 Word 报告「实例描述」列,空白保存即清除 -->
    <el-dialog v-model="descVisible" title="编辑库描述" width="480px" destroy-on-close :close-on-press-escape="false">
      <div style="margin-bottom: 8px; color: var(--el-text-color-secondary); font-size: 12px">
        库「{{ descRow ? displayName(descRow) : '' }}」的描述,将用于 Word 报告的「实例描述」列;留空保存即清除。
      </div>
      <el-input v-model="descText" type="textarea" :rows="3" maxlength="512" show-word-limit
                placeholder="如:地下水监测库" />
      <template #footer>
        <el-button @click="descVisible = false">取消</el-button>
        <el-button type="primary" :loading="descSaving" @click="saveDesc">保存</el-button>
      </template>
    </el-dialog>

    <!-- 库过滤:勾选需要显示的库,保存为数据源级白名单(与编辑数据源对话框的「库过滤」页签同一份配置) -->
    <el-dialog v-model="filterVisible" title="库过滤" width="560px" destroy-on-close :close-on-press-escape="false">
      <div v-loading="filterLoading">
        <div class="filter-tip">勾选需要显示的库;全部勾选(或全不勾)表示不过滤。系统库已默认不勾选,可按需勾回。</div>
        <template v-if="filterList.length">
          <div class="filter-all">
            <el-checkbox :model-value="filterCheckAll" :indeterminate="filterIndeterminate" @change="onFilterCheckAll">全部</el-checkbox>
            <span class="filter-count">已选 {{ filterChecked.length }} / {{ filterList.length }}</span>
          </div>
          <el-checkbox-group v-model="filterChecked" class="filter-list filter-grid">
            <el-checkbox v-for="db in filterList" :key="db" :value="db">
              {{ db }}<span v-if="isSystemSchema(db)" class="filter-sys-tag">系统</span>
            </el-checkbox>
          </el-checkbox-group>
        </template>
        <el-empty v-else-if="!filterLoading" description="没有可选择的库" :image-size="60" />
      </div>
      <template #footer>
        <el-button @click="filterVisible = false">取消</el-button>
        <el-button type="primary" :loading="filterSaving" :disabled="!filterList.length" @click="saveFilter">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import { ArrowDown, QuestionFilled, Refresh } from '@element-plus/icons-vue'
import request, { submitReportExport } from '../api'
import { setDsName, syncTab } from '../stores/tabs'
import { formatBytes, formatDateTime, formatNumber, statusTagType, statusText } from '../utils/format'
import { downloadFile } from '../utils/download'
import { cellText, exportListToExcel } from '../utils/listExport'
import { confirmAiUsable } from '../utils/aiCheck'
import Breadcrumb from '../components/Breadcrumb.vue'
const route = useRoute()
const router = useRouter()
const dsId = route.params.id
const schemas = ref([])
const dsName = ref('')
const dsRow = ref(null)
const databases = ref([])
const loading = ref(false)
const statsLoaded = ref(false)
const refreshing = ref(false)
// 本次库/schema 清单是否来自「数据源不可达降级读本地缓存」(响应头 X-Dq-Cache-Fallback)
const cacheFallback = ref(false)
const keyword = ref('')
const selected = ref([])
const submitting = ref(false)
const exporting = ref(false)
let timer = null

// ---------- 面包屑 ----------
const breadcrumbItems = computed(() => [
  { label: dsName.value || `数据源 ${dsId}` }
])

// 多库方言(SQL Server/Kingbase):数据库只是筛选条件,库列表按「数据库 + 架构」两列展示
const isMultiDb = computed(() => ['SQLSERVER', 'KINGBASE'].includes(dsRow.value?.dbType))

/** 行唯一键:跨库模式下同名 schema 可能分属不同数据库 */
function rowKeyOf(db, name) {
  return `${db || ''}${name}`
}

/** 行展示名:多库方言带数据库前缀(db.schema),单库方言只显示 schema 名 */
function displayName(row) {
  return isMultiDb.value && row.database ? `${row.database}.${row.name}` : row.name
}

// ---------- "导出"下拉 ----------
function onExportCommand(cmd) {
  if (cmd === 'exportCurrent') exportExcel()
  else if (cmd === 'export') exportReport()
  else if (cmd === 'exportDbStruct') exportDbStruct()
}

// ---------- "更多"下拉 ----------
function onMoreCommand(cmd) {
  if (cmd === 'filter') openFilter()
}

// ---------- 整库表结构 Word 导出(所有白名单过滤后的库,实时元数据,同步下载;无需勾选) ----------
function exportDbStruct() {
  downloadFile(`/api/datasources/${dsId}/export-dbstruct-word`)
}

// ---------- 库描述编辑(Word 报告「实例描述」列) ----------
const descVisible = ref(false)
const descSaving = ref(false)
const descRow = ref(null)
const descText = ref('')

function openDesc(row) {
  descRow.value = row
  descText.value = row.description || ''
  descVisible.value = true
}

async function saveDesc() {
  descSaving.value = true
  try {
    const db = descRow.value.database || ''
    const q = db ? `?db=${encodeURIComponent(db)}` : ''
    await request.put(`/datasources/${dsId}/schemas/${encodeURIComponent(descRow.value.name)}/description${q}`,
      { description: descText.value })
    descRow.value.description = descText.value.trim() || null
    ElMessage.success('描述已保存')
    descVisible.value = false
  } finally {
    descSaving.value = false
  }
}

// ---------- Word 报告导出(复用列表复选框;数据取每库最近一次完成扫描的历史快照) ----------
/** 是否已完成全表扫描:最近任务 DONE、无失败表、且覆盖当前全部表(与后端校验同口径) */
function isFullyScanned(row) {
  if (row.lastScanStatus !== 'DONE') return false
  if (row.lastScanTotalTables == null || row.lastScanDoneTables !== row.lastScanTotalTables) return false
  if (row.tableCount != null && row.lastScanTotalTables < row.tableCount) return false
  return true
}

/** 导出 Word 报告:勾选了库则导出勾选的;未勾选默认导出全部已完成全表扫描的库(弹窗提示确认)。
 *  异步任务:提交后到「报告列表」页查看进度与下载 */
async function exportReport() {
  let rows = selected.value
  if (!rows.length) {
    rows = schemas.value.filter(isFullyScanned)
    if (!rows.length) {
      ElMessage.warning('没有已完成全表扫描的库,请先扫描')
      return
    }
    try {
      await ElMessageBox.confirm(
        `未勾选库,将默认导出全部 ${rows.length} 个已完成全表扫描的库(${rows.map(displayName).join('、')}),是否继续?`,
        '导出扫描报告',
        { confirmButtonText: '导出', cancelButtonText: '取消', closeOnPressEscape: false }
      )
    } catch {
      return // 取消
    }
  }
  exporting.value = true
  try {
    // 跨库模式下勾选的库可能分属不同数据库:按数据库分组,逐库各提交一个导出任务
    const groups = new Map()
    for (const row of rows) {
      const db = row.database || ''
      if (!groups.has(db)) groups.set(db, [])
      groups.get(db).push(row.name)
    }
    for (const [db, names] of groups) {
      await submitReportExport(dsId, db, names)
    }
    ElMessageBox.confirm('导出任务已提交,生成可能需要几分钟。是否前往「报告列表」查看进度?', '导出扫描报告', {
      confirmButtonText: '前往查看',
      cancelButtonText: '留在此页',
      closeOnPressEscape: false
    }).then(() => router.push('/report-exports')).catch(() => {})
  } catch {
    // 提交失败由响应拦截器弹窗
  } finally {
    exporting.value = false
  }
}

// ---------- 库过滤弹窗(保存为数据源级白名单,与编辑数据源对话框同一份配置) ----------
const filterVisible = ref(false)
const filterLoading = ref(false)
const filterSaving = ref(false)
const filterList = ref([])
const filterChecked = ref([])
// 系统库/schema 名(后端按 dbType 返回,小写),库过滤默认不勾选并打「系统」标注
const systemSchemas = ref([])

/** 大小写不敏感判断库名是否为系统库 */
function isSystemSchema(name) {
  return systemSchemas.value.includes(String(name).toLowerCase())
}

const filterCheckAll = computed(() => filterList.value.length > 0 && filterChecked.value.length === filterList.value.length)
const filterIndeterminate = computed(() => filterChecked.value.length > 0 && filterChecked.value.length < filterList.value.length)

function onFilterCheckAll(val) {
  filterChecked.value = val ? [...filterList.value] : []
}

/** 打开弹窗并拉全量库列表(all=true 旁路白名单),按已存白名单回填勾选;未配置白名单时系统库默认不勾 */
async function openFilter() {
  filterVisible.value = true
  filterLoading.value = true
  filterList.value = []
  try {
    // 系统库清单与库列表并行拉取;系统清单失败不阻塞(退化为全勾)
    const sysPromise = request.get(`/db-types/${dsRow.value?.dbType}/system-schemas`).catch(() => [])
    let all
    if (isMultiDb.value) {
      // 多库方言:白名单作用于数据库层级
      all = await request.get(`/datasources/${dsId}/databases?all=true`)
    } else {
      all = await request.get(`/datasources/${dsId}/schemas?all=true`)
    }
    filterList.value = all || []
    systemSchemas.value = await sysPromise
    const cur = dsRow.value?.schemaFilter
    filterChecked.value = cur?.length
      ? filterList.value.filter((d) => cur.includes(d))
      : filterList.value.filter((d) => !isSystemSchema(d))
  } finally {
    filterLoading.value = false
  }
}

/** 保存白名单并刷新库列表;全勾/全不勾视为不过滤 */
async function saveFilter() {
  filterSaving.value = true
  try {
    const schemas = (filterChecked.value.length === 0 || filterChecked.value.length === filterList.value.length)
      ? null
      : [...filterChecked.value]
    await request.put(`/datasources/${dsId}/schema-filter`, { schemas })
    if (dsRow.value) dsRow.value.schemaFilter = schemas
    ElMessage.success('库过滤已更新')
    filterVisible.value = false
    // 多库方言跨库枚举用的数据库清单同样受白名单约束,保存后刷新
    if (isMultiDb.value) {
      databases.value = await request.get(`/datasources/${dsId}/databases`).catch(() => [])
    }
    await loadSchemas()
  } finally {
    filterSaving.value = false
  }
}

// 库行平铺展示的标记块上限,超出折叠为「+N 更多」
const TAG_VISIBLE_COUNT = 3

const filteredSchemas = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return schemas.value
  // 跨库模式下数据库名同样参与搜索
  return schemas.value.filter((s) =>
    s.name.toLowerCase().includes(kw) || (s.database || '').toLowerCase().includes(kw))
})

/** 先渲染库名列表,再异步补表数量/最近扫描/标记统计(失败不影响列表) */
async function loadSchemaStats(refresh = false) {
  // 跨库模式(多库方言)逐库拉取统计后按 数据库+架构 键合并;其余情况只拉一次
  const dbs = isMultiDb.value && databases.value.length ? databases.value : ['']
  const buildQ = (db) => {
    const params = new URLSearchParams()
    if (db) params.set('db', db)
    if (refresh) params.set('refresh', 'true')
    const s = params.toString()
    return s ? `?${s}` : ''
  }
  try {
    // 标记统计与库统计并行拉取;标记接口失败时返回 null,保留行内已有标记数据(轮询不刷丢)
    const [statsLists, tagLists] = await Promise.all([
      Promise.all(dbs.map((db) => request.get(`/datasources/${dsId}/schema-stats${buildQ(db)}`))),
      Promise.all(dbs.map((db) => request.get(`/datasources/${dsId}/schema-tag-stats${buildQ(db)}`).catch(() => null)))
    ])
    const byKey = new Map()
    statsLists.forEach((list, i) => (list || []).forEach((s) => byKey.set(rowKeyOf(dbs[i], s.name), s)))
    // 所有库的标记接口都失败才保留行内已有标记;只要有一个成功就按成功结果重建(失败的库标记置空)
    const tagsByKey = tagLists.some((t) => t) ? new Map() : null
    if (tagsByKey) {
      tagLists.forEach((list, i) => (list || []).forEach((s) => tagsByKey.set(rowKeyOf(dbs[i], s.schemaName), s.tags)))
    }
    schemas.value = schemas.value.map((s) => ({
      ...s,
      ...byKey.get(rowKeyOf(s.database, s.name)),
      // 接口只返回有标记表的库;无标记的库置 null,列内显示「—」
      ...(tagsByKey ? { tags: tagsByKey.get(rowKeyOf(s.database, s.name)) || null } : {})
    }))
  } catch (e) {
    // 统计查询失败(如连接超时)时保留纯名称列表,扫描记录入口不置灰
  } finally {
    statsLoaded.value = true
    // 有进行中的任务时启动轮询,实时刷新进度
    if (hasRunning() && !timer) startPolling()
  }
}

/** 手动刷新:从业务库拉最新库清单/库结构统计并覆盖本地缓存;数据源不可达时后端降级返回本地缓存 */
async function refreshStats() {
  refreshing.value = true
  try {
    // 库/schema 清单同为本地缓存,刷新时一并回源覆盖,保证业务库新增的库能出现在列表里
    if (isMultiDb.value) {
      databases.value = await request.get(`/datasources/${dsId}/databases?refresh=true`).catch(() => databases.value)
    }
    await loadSchemas(true)
    if (cacheFallback.value) {
      ElMessage.warning('数据源当前不可达,已保留本地缓存的库/架构清单')
    } else {
      ElMessage.success('已从数据源刷新库结构缓存')
    }
  } finally {
    refreshing.value = false
  }
}

async function loadSchemas(refresh = false) {
  statsLoaded.value = false
  cacheFallback.value = false
  if (isMultiDb.value && databases.value.length) {
    // 跨库模式:逐库拉取 schema 清单合并,行内携带所属数据库;取原始响应读降级响应头
    const perDb = await Promise.all(databases.value.map(async (db) => {
      const resp = await request.get(`/datasources/${dsId}/schemas?db=${encodeURIComponent(db)}${refresh ? '&refresh=true' : ''}`, { _raw: true }).catch(() => null)
      if (resp?.headers?.['x-dq-cache-fallback'] === 'true') cacheFallback.value = true
      return ((resp?.data) || []).map((name) => ({ name, database: db }))
    }))
    schemas.value = perDb.flat()
  } else {
    const resp = await request.get(`/datasources/${dsId}/schemas${refresh ? '?refresh=true' : ''}`, { _raw: true })
    cacheFallback.value = resp.headers?.['x-dq-cache-fallback'] === 'true'
    schemas.value = (resp.data || []).map((name) => ({ name, database: '' }))
  }
  loadSchemaStats(refresh)
}

async function load() {
  loading.value = true
  try {
    const dsList = await request.get('/datasources').catch(() => [])
    const ds = (dsList || []).find((d) => String(d.id) === String(dsId))
    dsRow.value = ds || null
    dsName.value = ds ? ds.name : ''
    // 回写数据源名并刷新页签标题(进入时 URL 可能没带 ?name=)
    if (dsName.value) {
      setDsName(dsId, dsName.value)
      syncTab(route)
    }
    if (ds && ['SQLSERVER', 'KINGBASE'].includes(ds.dbType)) {
      // 跨库枚举:拉取数据库清单(受白名单约束),用于逐库拉 schema/统计
      databases.value = await request.get(`/datasources/${dsId}/databases`).catch(() => [])
    }
    await loadSchemas()
  } finally {
    loading.value = false
  }
}

function goTables(row) {
  const q = row.database ? `?db=${encodeURIComponent(row.database)}` : ''
  router.push(`/datasources/${dsId}/schemas/${encodeURIComponent(row.name)}/tables${q}`)
}

function goScans(row) {
  const q = row.database ? `?db=${encodeURIComponent(row.database)}` : ''
  router.push(`/datasources/${dsId}/schemas/${encodeURIComponent(row.name)}/scans${q}`)
}

/** 点击标记块:跳表列表的只读筛选模式(该库 + 该标记) */
function goTagTables(row, tag) {
  router.push({
    path: `/datasources/${dsId}/schemas/${encodeURIComponent(row.name)}/tables`,
    query: {
      ...(row.database ? { db: row.database } : {}),
      tagId: tag.tagId,
      tagName: tag.tagName
    }
  })
}

function onSelectionChange(rows) {
  selected.value = rows
}

// ---------- 列表导出 Excel(导出当前搜索过滤后的库列表,列与页面一致) ----------
function exportExcel() {
  const headers = [
    ...(isMultiDb.value ? ['数据库'] : []),
    isMultiDb.value ? '架构(Schema)' : '库名(Schema)',
    '描述', '表数量', '占用空间', '标记', '最近扫描状态', '最近扫描时间'
  ]
  const rows = filteredSchemas.value.map((s) => [
    ...(isMultiDb.value ? [cellText(s.database)] : []),
    cellText(s.name),
    cellText(s.description),
    formatNumber(s.tableCount ?? 0),
    formatBytes(s.sizeBytes),
    (s.tags || []).map((t) => `${t.tagName} ${t.count}`).join(', '),
    s.lastScanStatus ? statusText(s.lastScanStatus) : '未扫描',
    s.lastScanAt ? formatDateTime(s.lastScanAt) : ''
  ])
  exportListToExcel(`库列表-${dsName.value || dsId}`, headers, rows, '库列表')
}

function isScanning(row) {
  return ['PENDING', 'RUNNING'].includes(row.lastScanStatus)
}

function hasRunning() {
  return schemas.value.some(isScanning)
}

/** 最近任务的表级进度(完成表数/总表数) */
function scanPercent(row) {
  if (!row.lastScanTotalTables) return 0
  return Math.min(100, Math.round((row.lastScanDoneTables / row.lastScanTotalTables) * 100))
}

/** 最近扫描时间毫秒数,供排序;未扫描的排最前(升序时) */
function scanAtMs(row) {
  return row.lastScanAt ? new Date(row.lastScanAt).getTime() : -1
}

function startPolling() {
  stopPolling()
  timer = setInterval(async () => {
    await loadSchemaStats()
    if (!hasRunning()) stopPolling()
  }, 2000)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

// 配置默认的并发 worker 线程数,扫描弹窗中展示并作为「并发线程数」默认值;首次打开扫描对话框时拉取并缓存
const defaultWorkers = ref(null)
let scanDefaultsFetched = false
async function ensureScanDefaults() {
  if (scanDefaultsFetched) return
  scanDefaultsFetched = true
  const cfg = await request.get('/scans/defaults').catch(() => null)
  defaultWorkers.value = cfg?.defaultWorkers ?? null
  // 首次拉取到默认值后回填弹窗(用户尚未填写时);拉取失败则留空,提交时按后端默认处理
  if (scanForm.workers == null) scanForm.workers = defaultWorkers.value
}

// ---------- 扫描弹窗(与表列表页保持一致;扫描范围为整库) ----------
const scanDialogVisible = ref(false)
// 本次扫描的目标库:单个(行内「扫描」)或批量(勾选)
const scanTargets = ref([])
// maxSizeValue 为空(null)表示不限制表大小
const scanForm = reactive({ forceFull: true, nullRules: [], maxSizeValue: null, maxSizeUnit: 'GB', autoTag: true, genDoc: true, workers: null })

// 表大小上限换算成字节;未设置返回 null
function maxSizeBytes() {
  if (!scanForm.maxSizeValue) return null
  return scanForm.maxSizeValue * (scanForm.maxSizeUnit === 'GB' ? 1073741824 : 1048576)
}

function openScanDialog(rows) {
  scanTargets.value = rows
  scanForm.forceFull = true
  scanForm.nullRules = []
  scanForm.maxSizeValue = null
  scanForm.maxSizeUnit = 'GB'
  // AI 相关默认勾选:未配置大模型时后端自动跳过
  scanForm.autoTag = true
  scanForm.genDoc = true
  scanForm.workers = defaultWorkers.value
  ensureScanDefaults()
  scanDialogVisible.value = true
}

/** 对目标库逐个提交全库扫描任务(tables=null 即整库),失败的单独计数 */
async function submitScans() {
  // 勾选了 AI 功能先校验可用性,不可用由用户决定是否继续(继续则后端静默跳过 AI)
  if (!(await confirmAiUsable(scanForm))) return
  const nullRules = scanForm.nullRules
    .filter((r) => r.column.trim() && r.valuesText.trim())
    .map((r) => ({
      column: r.column.trim(),
      values: r.valuesText.split(',').map((v) => v.trim()).filter(Boolean)
    }))
    .filter((r) => r.values.length > 0)

  submitting.value = true
  try {
    const results = await Promise.allSettled(scanTargets.value.map((row) =>
      request.post('/scans', {
        datasourceId: /^\d+$/.test(String(dsId)) ? Number(dsId) : dsId,
        schema: row.name,
        database: row.database || null,
        tables: null,
        forceFull: scanForm.forceFull,
        nullRules,
        maxTableSizeBytes: maxSizeBytes(),
        autoTag: scanForm.autoTag,
        genDoc: scanForm.genDoc,
        workers: scanForm.workers || null
      })
    ))
    const ok = results.filter((r) => r.status === 'fulfilled').length
    if (ok === results.length) {
      ElMessage.success(`已提交 ${ok} 个扫描任务`)
    } else {
      ElMessage.warning(`已提交 ${ok}/${results.length} 个扫描任务,其余提交失败`)
    }
    scanDialogVisible.value = false
    await loadSchemaStats()
  } finally {
    submitting.value = false
  }
}

function scanSelected() {
  // 跳过正在扫描的库,避免重复提交
  const targets = selected.value.filter((row) => !isScanning(row))
  if (!targets.length) {
    ElMessage.info('选中的库都正在扫描中')
    return
  }
  openScanDialog(targets)
}

function scanOne(row) {
  openScanDialog([row])
}

onMounted(load)

// 页签切换走 keep-alive:失活时停轮询,回来时刷新统计并按需恢复
onActivated(() => {
  loadSchemaStats()
})
onDeactivated(stopPolling)
onUnmounted(stopPolling)
</script>

<style scoped>
/* 工具栏按钮组:flex + gap 统一间距,并清掉 el-button 相邻默认 margin(el-dropdown 不吃该规则导致间距不一) */
.toolbar-actions {
  display: flex;
  gap: 12px;
}
.toolbar-actions :deep(.el-button) {
  margin-left: 0;
}
/* 库过滤/导出弹窗:提示行 + 全选行 + 勾选列表(宽松行距) */
.filter-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
  margin-bottom: 16px;
}
.filter-all {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 12px;
}
.filter-count {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
/* 库过滤系统库标注:小号灰底标签 */
.filter-sys-tag {
  margin-left: 6px;
  padding: 0 4px;
  font-size: 11px;
  line-height: 16px;
  border-radius: 3px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color);
}
.filter-list {
  display: flex;
  flex-direction: column;
  max-height: 400px;
  overflow-y: auto;
}
/* 库过滤弹窗专用:双列网格(导出弹窗带标签,保持单列) */
.filter-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 16px;
}
.filter-list :deep(.el-checkbox) {
  margin-right: 0;
  height: auto;
  padding: 7px 8px;
  border-radius: 4px;
  transition: background-color 0.15s;
}
.filter-list :deep(.el-checkbox:hover) {
  background-color: var(--el-fill-color-light);
}
.filter-list :deep(.el-checkbox__label) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 扫描弹窗:空值规则行与字段提示(与表列表页一致) */
.rule-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
</style>
