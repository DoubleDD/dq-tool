<template>
  <div class="page-card">
    <div class="toolbar">
      <Breadcrumb :items="breadcrumbItems" />
      <div class="toolbar-actions">
        <el-button :icon="Refresh" :loading="refreshing" @click="refreshTables">刷新</el-button>
        <el-dropdown trigger="click" @command="onExportCommand">
          <el-button>导出<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="current" :disabled="!filteredTables.length">导出当前列表</el-dropdown-item>
              <el-dropdown-item command="latest" :disabled="!hasLatestScans">导出扫描结果</el-dropdown-item>
              <el-dropdown-item command="report" :disabled="exporting">导出扫描报告</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <!-- 最新扫描结果导出弹窗宿主:无触发按钮,由下拉菜单项经 ref 唤起 -->
        <ExportButton ref="latestExportRef" hide-trigger :latest-url="latestExportUrl" />
        <el-button @click="$router.push(`/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/scans${dbQuery()}`)">扫描记录</el-button>
        <template v-if="!filterTagId">
          <el-button :disabled="!selectedTables.length" :loading="collectBatchLoading" @click="collectBatch">
            人工采集{{ selectedTables.length ? `(${selectedTables.length})` : '' }}
          </el-button>
          <el-button :disabled="!selectedTables.length" :loading="batchDocLoading" @click="generateDocsBatch">
            生成描述{{ selectedTables.length ? `(${selectedTables.length})` : '' }}
          </el-button>
          <el-button :disabled="!selectedTables.length" @click="batchTagDialogVisible = true">
            批量打标{{ selectedTables.length ? `(${selectedTables.length})` : '' }}
          </el-button>
          <el-button type="primary" @click="openScanDialog">开始扫描</el-button>
        </template>
      </div>
    </div>

    <!-- 按标记筛选的只读模式提示 -->
    <el-alert v-if="filterTagId" type="warning" :closable="false" style="margin-bottom: 12px">
      <span style="margin-right: 16px">按标记「{{ filterTagName }}」筛选中 · 只读</span>
      <el-button link type="primary" @click="clearTagFilter">清除筛选</el-button>
    </el-alert>

    <div style="display: flex; gap: 16px; align-items: center; margin-bottom: 12px">
      <el-input v-model="keyword" placeholder="按表名或注释搜索" clearable style="width: 280px" />
      <el-select
        v-if="!filterTagId"
        v-model="selectedTagIds"
        multiple
        collapse-tags
        collapse-tags-tooltip
        clearable
        placeholder="按标记筛选"
        style="width: 240px"
      >
        <el-option v-for="tag in availableTags" :key="tag.id" :label="tag.name" :value="String(tag.id)">
          <span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; vertical-align: middle" :style="{ background: tag.color }" />
          {{ tag.name }}
        </el-option>
      </el-select>
      <el-select v-model="dataFilter" clearable placeholder="全部表" style="width: 130px">
        <el-option label="有数据表" value="hasData" />
        <el-option label="无数据表" value="noData" />
      </el-select>
      <!-- 列设置:勾选自定义显示列,隐藏列存 localStorage,新增列默认显示 -->
      <el-popover placement="bottom-end" :width="150" trigger="click">
        <template #reference>
          <el-button :icon="Setting" style="margin-left: auto">列设置</el-button>
        </template>
        <!-- 全选:部分勾选时显示半选状态 -->
        <el-checkbox
          class="col-setting-all"
          :model-value="visibleCols.length === COLUMN_DEFS.length"
          :indeterminate="visibleCols.length > 0 && visibleCols.length < COLUMN_DEFS.length"
          @change="toggleAllCols"
        >全选</el-checkbox>
        <el-checkbox-group v-model="visibleCols" class="col-setting">
          <el-checkbox v-for="c in COLUMN_DEFS" :key="c.key" :value="c.key">{{ c.label }}</el-checkbox>
        </el-checkbox-group>
      </el-popover>
    </div>

    <el-alert v-if="tables.length" type="success" :closable="false" style="margin-bottom: 12px">
      <span style="margin-right: 24px">总行数: 约 {{ formatNumber(totalEstRows) }}</span>
      <span style="margin-right: 24px">总大小: {{ formatBytes(totalSizeBytes) }}</span>
      <span style="margin-right: 24px">表数量: {{ tables.length }}</span>
      <span style="margin-right: 24px">字段数量: {{ columnCount === null ? '-' : formatNumber(columnCount) }}</span>
      <span>
        空表数量:
        <el-link type="primary" :disabled="!emptyTables.length" @click="dataFilter = 'noData'">{{ emptyTables.length }}</el-link>
      </span>
    </el-alert>

    <!-- max-height 由视口计算:表头固定 + 纵向滚动;列宽超出容器自动出横向滚动 -->
    <div ref="tableWrapRef">
      <el-table :data="filteredTables" v-loading="loading" border row-key="name" :max-height="tableMaxHeight" @selection-change="onSelectionChange">
      <el-table-column v-if="!filterTagId" type="selection" width="45" reserve-selection fixed="left" />
      <el-table-column type="index" label="序号" width="60" fixed="left" />
      <el-table-column prop="name" label="表名" min-width="180" sortable show-overflow-tooltip fixed="left">
        <template #default="{ row }">
          <el-link type="primary" @click="goTableDetail(row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column v-if="colVisible('comment')" key="comment" prop="comment" label="注释" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.comment">{{ row.comment }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column v-if="colVisible('tags')" key="tags" label="标记" min-width="160">
        <template #default="{ row }">
          <template v-if="(tableTags[row.name] || []).length">
            <el-tag
              v-for="tag in tableTags[row.name]"
              :key="tag.id"
              size="small"
              class="table-tag"
              :type="tag.kind === 'EMPTY' ? 'info' : undefined"
              :effect="tag.kind === 'EMPTY' ? 'plain' : 'dark'"
              :color="tag.kind === 'EMPTY' ? undefined : tag.color"
              :style="tag.kind === 'EMPTY' ? {} : { borderColor: tag.color }"
            >{{ tag.name }}</el-tag>
          </template>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column v-if="colVisible('system')" key="system" label="所属系统" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="tableSystems[row.name]">{{ tableSystems[row.name] }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column v-if="colVisible('doc')" key="doc" label="描述" min-width="220">
        <template #header>
          <el-tooltip placement="top" :show-after="200">
            <template #content>
              <div>由大模型根据表结构(表名/字段/注释)生成的描述,比表注释更能体现表的用途</div>
              <div>生成只发送表结构元数据,不发送业务数据</div>
            </template>
            <span>描述 <el-icon style="vertical-align: -2px"><QuestionFilled /></el-icon></span>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <div class="doc-cell">
            <!-- 已有描述:刷新按钮重新生成;无描述:显示生成入口;标记筛选只读模式下隐藏生成/编辑入口 -->
            <template v-if="!filterTagId">
              <el-tooltip v-if="docs[row.name]" content="重新生成描述(基于最新表结构,覆盖现有描述)" placement="top" :show-after="200">
                <el-button link type="primary" :loading="docLoading[row.name]" @click="generateDoc(row)">
                  <el-icon v-if="!docLoading[row.name]"><Refresh /></el-icon>
                </el-button>
              </el-tooltip>
              <el-button v-else link type="primary" :loading="docLoading[row.name]" @click="generateDoc(row)">生成描述</el-button>
            </template>
            <el-tooltip v-if="docs[row.name]" :content="docs[row.name]" placement="top" :show-after="200">
              <span class="doc-text" :style="filterTagId ? 'cursor: default' : ''" @click="openDocEdit(row)">{{ docs[row.name] }}</span>
            </el-tooltip>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="colVisible('storage')" key="storage" prop="storageInfo" label="引擎/表空间" width="130">
        <template #default="{ row }">
          <span v-if="row.storageInfo">{{ row.storageInfo }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column v-if="colVisible('rows')" key="rows" label="行数" width="140" sortable :sort-method="(a, b) => (effectiveRows(a).value ?? -1) - (effectiveRows(b).value ?? -1)">
        <template #header>
          <el-tooltip placement="top" :show-after="200">
            <template #content>
              <div>已全量扫描的表显示 COUNT(*) 精确值;未扫描或采样扫描的表显示元数据估算值(带"约"前缀)</div>
              <div>超过采样阈值(默认 100 万行或 10GB,可在数据源配置)的表,扫描时默认只采样统计</div>
            </template>
            <span>行数 <el-icon style="vertical-align: -2px"><QuestionFilled /></el-icon></span>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <template v-if="effectiveRows(row).value !== null && effectiveRows(row).value !== undefined">
            <span v-if="effectiveRows(row).exact">{{ formatNumber(effectiveRows(row).value) }}</span>
            <span v-else>约 {{ formatNumber(effectiveRows(row).value) }}</span>
          </template>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column v-if="colVisible('size')" key="size" label="总大小" width="140" sortable :sort-method="(a, b) => (effectiveSize(a) ?? -1) - (effectiveSize(b) ?? -1)">
        <template #header>
          <el-tooltip placement="top" :show-after="200">
            <template #content>
              <div>数据 + 索引占用;已扫描的表取最近一次扫描时记录的值,未扫描的表取当前元数据值</div>
            </template>
            <span>总大小 <el-icon style="vertical-align: -2px"><QuestionFilled /></el-icon></span>
          </el-tooltip>
        </template>
        <template #default="{ row }">{{ formatBytes(effectiveSize(row)) }}</template>
      </el-table-column>
      <el-table-column v-if="colVisible('scanTime')" key="scanTime" label="最近扫描时间" width="170" sortable :sort-method="(a, b) => latestScanTime(a) - latestScanTime(b)">
        <template #default="{ row }">
          <span v-if="latestScans[row.name]">{{ formatDateTime(latestScans[row.name].finishedAt) }}</span>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <!-- 正在扫描:显示分段进度,点击跳到任务详情;排队中的表显示 0% -->
          <el-progress
            v-if="runningScans[row.name]"
            :percentage="scanPercent(runningScans[row.name])"
            :stroke-width="10"
            style="cursor: pointer"
            @click="goRunningJob(row)"
          />
          <template v-else-if="!filterTagId">
            <el-button link type="primary" @click="openTagDialog(row)">打标</el-button>
            <el-button link type="primary" :loading="collectLoading[row.name]" @click="toggleCollect(row)">
              {{ collectMap[row.name] ? '取消采集' : '采集' }}
            </el-button>
            <el-button link type="primary" @click="scanSingle(row)">扫描</el-button>
          </template>
          <span v-else style="color: var(--el-text-color-placeholder)">-</span>
        </template>
      </el-table-column>
    </el-table>
    </div>

    <el-dialog v-model="scanDialogVisible" title="开始扫描" width="640px" destroy-on-close :close-on-press-escape="false">
      <el-form label-width="110px">
        <el-form-item label="扫描范围">
          <span v-if="singleTable">仅扫描表:{{ singleTable }}</span>
          <span v-else-if="selectedTables.length">已选 {{ selectedTables.length }} 张表</span>
          <span v-else>未选择表,将扫描全库</span>
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
            <div v-if="skippedBySize" class="form-tip" style="color: var(--el-color-warning)">
              当前范围内有 {{ skippedBySize }} 张表超过上限,将被跳过
            </div>
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
        <el-button type="primary" :loading="submitting" @click="submitScan">提交扫描</el-button>
      </template>
    </el-dialog>

    <!-- 手动编辑表描述 -->
    <el-dialog v-model="docEditVisible" :title="`编辑描述 - ${docEditTable}`" width="560px" append-to-body :close-on-press-escape="false">
      <el-input v-model="docEditText" type="textarea" :rows="5" maxlength="2000" show-word-limit
                placeholder="输入该表的用途描述" />
      <template #footer>
        <el-button @click="docEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="docEditSaving" @click="saveDocEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 打标弹窗(勾选 + 新建标记;编辑/删除统一在「标记统计」页维护) -->
    <TableTagDialog
      v-model="tagDialogVisible"
      :ds-id="dsId"
      :schema="schema"
      :db="db"
      :table-name="tagDialogTable"
      :current-tags="tableTags[tagDialogTable] || []"
      @saved="onTagsSaved"
    />

    <!-- 批量打标弹窗(勾选多张表 → 批量打上选中标记,只增不删;可就地新建标记) -->
    <BatchTagDialog
      v-model="batchTagDialogVisible"
      :ds-id="dsId"
      :schema="schema"
      :db="db"
      :table-names="selectedTables.map((t) => t.name)"
      @saved="onBatchTagged"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, QuestionFilled, Refresh, Setting } from '@element-plus/icons-vue'
import request, { submitReportExport } from '../api'
import TableTagDialog from '../components/TableTagDialog.vue'
import BatchTagDialog from '../components/BatchTagDialog.vue'
import Breadcrumb from '../components/Breadcrumb.vue'
import ExportButton from '../components/ExportButton.vue'
import { ensureDsName, getDsName, syncTab } from '../stores/tabs'
import { formatBytes, formatDateTime, formatNumber } from '../utils/format'
import { cellText, exportListToExcel } from '../utils/listExport'

const route = useRoute()
const router = useRouter()
const dsId = route.params.id
const schema = route.params.schema
const db = route.query.db || ''

const tables = ref([])
const loading = ref(false)
// 手动刷新表结构缓存中状态
const refreshing = ref(false)
// Word 报告导出中状态(只导出当前库,未扫描时后端拦截提示)
const exporting = ref(false)

/** 导出当前库的 Word 数据调研报告(异步任务,单库范围;提交后到「报告列表」页查看) */
async function exportReport() {
  exporting.value = true
  try {
    await submitReportExport(dsId, db, [schema])
    ElMessage.success('导出任务已提交,可在「报告列表」页签查看进度')
  } catch {
    // 提交失败由响应拦截器弹窗
  } finally {
    exporting.value = false
  }
}

/** 导出当前过滤后的表列表 Excel(列与页面一致,行数/大小取值口径同表格展示) */
function exportExcel() {
  const headers = ['表名', '注释', '标记', '所属系统', '描述', '引擎/表空间', '行数', '总大小', '最近扫描时间']
  const rows = filteredTables.value.map((t) => {
    const er = effectiveRows(t)
    const latest = latestScans.value[t.name]
    return [
      cellText(t.name),
      cellText(t.comment),
      (tableTags.value[t.name] || []).map((tag) => tag.name).join(', '),
      cellText(tableSystems.value[t.name]),
      cellText(docs.value[t.name]),
      cellText(t.storageInfo),
      er.value === null || er.value === undefined ? '' : (er.exact ? '' : '约 ') + formatNumber(er.value),
      formatBytes(effectiveSize(t)),
      latest ? formatDateTime(latest.finishedAt) : ''
    ]
  })
  exportListToExcel(`表列表-${schemaLabel.value}`, headers, rows, '表列表')
}

/** 导出下拉:当前列表(页面所见行)/扫描结果(每表最新 DONE 快照,弹窗选列)/扫描报告(Word 调研报告) */
function onExportCommand(cmd) {
  if (cmd === 'current') {
    exportExcel()
  } else if (cmd === 'latest') {
    latestExportRef.value?.open()
  } else if (cmd === 'report') {
    exportReport()
  }
}
// schema 下所有基表的字段总数(业务库元数据查询,失败时显示 -)
const columnCount = ref(null)
const keyword = ref('')

// 可自定义显示列(表名/序号/操作列固定显示);存隐藏列而非可见列,新增列对老用户默认显示
const COLUMN_DEFS = [
  { key: 'comment', label: '注释' },
  { key: 'tags', label: '标记' },
  { key: 'system', label: '所属系统' },
  { key: 'doc', label: '描述' },
  { key: 'storage', label: '引擎/表空间' },
  { key: 'rows', label: '行数' },
  { key: 'size', label: '总大小' },
  { key: 'scanTime', label: '最近扫描时间' }
]
const HIDDEN_COLS_KEY = 'tables.hiddenColumns'
// 读取本地保存的隐藏列;无保存记录时默认隐藏「所属系统」列(该功能暂缓,数据仍在采集,可在列设置开启)
function loadHiddenCols() {
  const raw = localStorage.getItem(HIDDEN_COLS_KEY)
  if (raw === null) return ['system']
  try {
    const saved = JSON.parse(raw)
    if (Array.isArray(saved)) {
      const all = COLUMN_DEFS.map((c) => c.key)
      return saved.filter((k) => all.includes(k))
    }
  } catch { /* 本地缓存损坏按默认处理 */ }
  return ['system']
}
const hiddenCols = ref(loadHiddenCols())
watch(hiddenCols, (v) => localStorage.setItem(HIDDEN_COLS_KEY, JSON.stringify(v)), { deep: true })
// 列设置弹层的勾选值 = 全部列减去隐藏列
const visibleCols = computed({
  get: () => COLUMN_DEFS.map((c) => c.key).filter((k) => !hiddenCols.value.includes(k)),
  set: (v) => { hiddenCols.value = COLUMN_DEFS.map((c) => c.key).filter((k) => !v.includes(k)) }
})
const colVisible = (key) => !hiddenCols.value.includes(key)
// 全选/全不选
const toggleAllCols = (checked) => {
  visibleCols.value = checked ? COLUMN_DEFS.map((c) => c.key) : []
}

// 表格最大高度 = 视口高 - 表格顶部位置 - 底部留白,配合 max-height 实现表头固定 + 纵向滚动
const tableWrapRef = ref(null)
const tableMaxHeight = ref(600)
function updateTableMaxHeight() {
  const top = tableWrapRef.value?.getBoundingClientRect().top
  if (top) tableMaxHeight.value = Math.max(300, Math.floor(window.innerHeight - top - 16))
}
// 本地标记多选筛选:选中的标记 id(字符串数组),OR 逻辑--任一命中即展示
const selectedTagIds = ref([])
const selectedTables = ref([])
// 每张表最近一次 DONE 扫描的信息(表名 -> { jobId, finishedAt, totalRows, sizeBytes, sampled }),
// 有值的表名渲染为链接;非采样表的 totalRows 为精确行数,优先于元数据估算展示
const latestScans = ref({})
// 是否有任一表的最新 DONE 扫描数据:无数据时禁用「导出扫描结果」按钮(后端同样以 409 拦截)
const hasLatestScans = computed(() => Object.keys(latestScans.value).length > 0)
// 最新扫描结果导出 URL:每表最近一次表级 DONE 快照,跨任务,不依赖指定任务记录
const latestExportUrl = computed(
  () => `/api/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/export-latest${dbQuery()}`)
// 「导出扫描结果」弹窗组件引用(hideTrigger 模式,由导出下拉菜单项唤起)
const latestExportRef = ref(null)
// 运行中任务里每张未完成表的分段进度(表名 -> { jobId, status, doneChunks, totalChunks })
const runningScans = ref({})
// AI 生成的表说明(表名 -> 说明文字),本地 H2 查询
const docs = ref({})
// 行内「说明」按钮的生成中状态(表名 -> bool)
const docLoading = ref({})
const batchDocLoading = ref(false)
// 手动编辑描述弹窗
const docEditVisible = ref(false)
const docEditTable = ref('')
const docEditText = ref('')
const docEditSaving = ref(false)

// 整个库的表→标记 map(表名 -> [{id,name,color,kind}]),含系统驱动的空表标记
const tableTags = ref({})
// 当前库下已使用的标记列表(从 tableTags 提取去重,按 id 排序),供标记筛选下拉
const availableTags = computed(() => {
  const seen = new Map()
  for (const tags of Object.values(tableTags.value)) {
    for (const tag of tags) {
      if (!seen.has(tag.id)) seen.set(tag.id, tag)
    }
  }
  return [...seen.values()].sort((a, b) => a.id - b.id)
})
// 打标弹窗
const tagDialogVisible = ref(false)
const tagDialogTable = ref('')

// 整个库的表→所属系统 map(表名 -> 系统名),独立于标记体系;列默认隐藏,可在「列设置」开启
const tableSystems = ref({})
// 批量打标弹窗
const batchTagDialogVisible = ref(false)

// 本库已采集表 map:表名 -> 采集记录 id(行内「采集/取消采集」按钮状态)
const collectMap = ref({})
// 行内采集按钮的提交中状态(表名 -> bool);批量采集提交中状态
const collectLoading = ref({})
const collectBatchLoading = ref(false)

// 采集请求项:四元组定位一张表(数据源+库+schema+表名),注释做快照
function collectItem(row) {
  return {
    datasourceId: /^\d+$/.test(String(dsId)) ? Number(dsId) : dsId,
    dbName: db || null,
    schemaName: schema,
    tableName: row.name,
    tableComment: row.comment || null
  }
}

// 行内「采集/取消采集」切换:取消按记录 id 删除,采集走批量接口(单表即一项)
async function toggleCollect(row) {
  const id = collectMap.value[row.name]
  collectLoading.value[row.name] = true
  try {
    if (id) {
      await request.delete(`/manual-collects/${id}`)
      const next = { ...collectMap.value }
      delete next[row.name]
      collectMap.value = next
      ElMessage.success(`已取消采集「${row.name}」`)
    } else {
      await request.post('/manual-collects', { items: [collectItem(row)] })
      ElMessage.success(`已采集「${row.name}」,可在「人工采集」页签查看`)
      await reloadCollectMap()
    }
  } finally {
    collectLoading.value[row.name] = false
  }
}

// 批量采集勾选的表:后端幂等,已存在的跳过计数
async function collectBatch() {
  collectBatchLoading.value = true
  try {
    const res = await request.post('/manual-collects', { items: selectedTables.value.map(collectItem) })
    ElMessage.success(`人工采集完成:新增 ${res.added} 张,已存在跳过 ${res.skipped} 张`)
    await reloadCollectMap()
  } finally {
    collectBatchLoading.value = false
  }
}

// 重拉本库采集 map(表名 -> 记录 id),失败静默维持旧值
async function reloadCollectMap() {
  const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
  collectMap.value = await request.get(`${base}/manual-collects${dbQuery()}`).catch(() => ({}))
}

// 按标记筛选的只读模式:路由 query 带 tagId 时只显示打了该标记的表,并禁用所有操作类交互
const filterTagId = computed(() => (route.query.tagId ? String(route.query.tagId) : ''))
const filterTagName = computed(() => route.query.tagName || '')

// 清除标记筛选:回到无 tagId 的路由
function clearTagFilter() {
  router.push({ path: route.path, query: db ? { db } : {} })
}

function openTagDialog(row) {
  tagDialogTable.value = row.name
  tagDialogVisible.value = true
}

// 打标保存成功:回填该表最新标记数组
function onTagsSaved(tags) {
  tableTags.value[tagDialogTable.value] = tags
}

// 批量打标成功:重拉本库打标 map(批量接口只返回计数)
async function onBatchTagged() {
  const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
  tableTags.value = await request.get(`${base}/table-tags${dbQuery()}`).catch(() => ({}))
}

const scanDialogVisible = ref(false)
const submitting = ref(false)
// maxSizeValue 为空(null)表示不限制表大小
const scanForm = reactive({ forceFull: true, nullRules: [], maxSizeValue: null, maxSizeUnit: 'GB', autoTag: true, genDoc: true, workers: null })
// 行内"扫描"按钮带出的单表目标;为空则按勾选/全库走
const singleTable = ref('')

// 配置默认的并发 worker 线程数,扫描弹窗中展示并作为「并发线程数」默认值;首次打开扫描对话框时拉取并缓存
const defaultWorkers = ref(null)
let scanDefaultsFetched = false
async function fetchScanDefaults() {
  if (scanDefaultsFetched) return
  scanDefaultsFetched = true
  try {
    const cfg = await request.get('/scans/defaults')
    defaultWorkers.value = cfg.defaultWorkers ?? null
    // 首次拉取到默认值后回填弹窗(用户尚未填写时);拉取失败则留空,提交时按后端默认处理
    if (scanForm.workers == null) scanForm.workers = defaultWorkers.value
  } catch {
    // 拉取失败不影响扫描,弹窗中默认值显示 "-"
  }
}

// 行数取值:非采样的最新完成扫描给的是 COUNT(*) 精确值,优先于元数据估算;
// 采样表的 totalRows 只是采样行数,不能当作全表行数,仍用估算值
function effectiveRows(row) {
  const s = latestScans.value[row.name]
  if (s && !s.sampled && s.totalRows !== null && s.totalRows !== undefined) {
    return { value: s.totalRows, exact: true }
  }
  return { value: row.estRows, exact: false }
}

// 大小取值:已扫描的表用最近一次扫描时记录的快照(比当前元数据更接近扫描口径),否则用元数据
function effectiveSize(row) {
  const s = latestScans.value[row.name]
  return s && s.sizeBytes !== null && s.sizeBytes !== undefined ? s.sizeBytes : row.sizeBytes
}

// 数据筛选:'' 全部 / hasData 有数据(有效行数>0) / noData 无数据(有效行数为 0 或未知)
const dataFilter = ref('')
// 空表:行数为 0(含未知)的表,已全量扫描的按精确值算
const emptyTables = computed(() => tables.value.filter((t) => !effectiveRows(t).value))
// 库级汇总:行数/大小按各表有效值(扫描准确值优先)求和
const totalEstRows = computed(() => tables.value.reduce((sum, t) => sum + (effectiveRows(t).value || 0), 0))
const totalSizeBytes = computed(() => tables.value.reduce((sum, t) => sum + (effectiveSize(t) || 0), 0))

// 当前扫描范围内的表:单表 > 勾选 > 全库
const scopeTables = computed(() => {
  if (singleTable.value) return tables.value.filter((t) => t.name === singleTable.value)
  return selectedTables.value.length ? selectedTables.value : tables.value
})

// 表大小上限换算成字节;未设置返回 null
function maxSizeBytes() {
  if (!scanForm.maxSizeValue) return null
  return scanForm.maxSizeValue * (scanForm.maxSizeUnit === 'GB' ? 1073741824 : 1048576)
}

// 范围内将被大小上限跳过的表数量(大小未知的表不参与统计)
const skippedBySize = computed(() => {
  const limit = maxSizeBytes()
  if (!limit) return 0
  return scopeTables.value.filter((t) => t.sizeBytes != null && t.sizeBytes > limit).length
})

const filteredTables = computed(() => {
  let list = tables.value
  if (dataFilter.value === 'noData') list = emptyTables.value
  else if (dataFilter.value === 'hasData') list = list.filter((t) => !!effectiveRows(t).value)
  // 标记筛选只读模式:该库 table-tags map 中含该 tagId 的表
  if (filterTagId.value) {
    list = list.filter((t) =>
      (tableTags.value[t.name] || []).some((tag) => String(tag.id) === filterTagId.value))
  }
  // 本地标记多选筛选:选中的标记中任一命中即展示(OR)
  if (selectedTagIds.value.length) {
    list = list.filter((t) =>
      (tableTags.value[t.name] || []).some((tag) => selectedTagIds.value.includes(String(tag.id))))
  }
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter((t) =>
      t.name.toLowerCase().includes(kw) || (t.comment || '').toLowerCase().includes(kw))
  }
  return list
})

async function load(refresh = false) {
  loading.value = true
  try {
    const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
    // refresh=true 时表清单强制从业务库拉最新结构并覆盖本地缓存;其余数据为本地 H2/实时查询,不受 refresh 影响
    const q = dbQuery()
    const tablesUrl = `${base}/tables${q}${refresh ? (q ? '&' : '?') + 'refresh=true' : ''}`
    // 最新扫描映射/表说明查的是本地 H2,失败时仅影响表名是否可点与说明展示,不阻塞表列表
    // 字段总数走业务库元数据,失败时也不阻塞表列表(显示 -)
    const [tableList, latest, tableDocs, colCount, tagMap, collects, systemMap] = await Promise.all([
      request.get(tablesUrl),
      request.get(`${base}/latest-scan-jobs${dbQuery()}`).catch(() => ({})),
      request.get(`${base}/table-docs${dbQuery()}`).catch(() => ({})),
      request.get(`${base}/column-count${dbQuery()}`).catch(() => null),
      request.get(`${base}/table-tags${dbQuery()}`).catch(() => ({})),
      request.get(`${base}/manual-collects${dbQuery()}`).catch(() => ({})),
      request.get(`${base}/table-systems${dbQuery()}`).catch(() => ({}))
    ])
    tables.value = tableList
    latestScans.value = latest || {}
    docs.value = tableDocs || {}
    columnCount.value = colCount
    tableTags.value = tagMap || {}
    collectMap.value = collects || {}
    tableSystems.value = systemMap || {}
  } finally {
    loading.value = false
    // 数据到位后布局可能变化(汇总条出现),重算表格最大高度
    nextTick(updateTableMaxHeight)
  }
}

/** 手动刷新:从业务库拉最新表结构并覆盖本地缓存 */
async function refreshTables() {
  refreshing.value = true
  try {
    await load(true)
    ElMessage.success('已从数据源刷新表结构缓存')
  } finally {
    refreshing.value = false
  }
}

// 轮询运行中扫描进度(本地 H2 查询,开销极小);没有运行中任务时也低频探一次,能发现别处发起的扫描
let pollTimer = null
async function fetchRunning() {
  const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
  runningScans.value = await request.get(`${base}/running-scans${dbQuery()}`).catch(() => ({}))
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(fetchRunning, 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// 表级分段进度百分比
function scanPercent(s) {
  if (!s.totalChunks) return 0
  return Math.min(100, Math.round((s.doneChunks / s.totalChunks) * 100))
}

// 点击进度条跳到正在扫描的任务详情
function goRunningJob(row) {
  router.push(`/scans/${runningScans.value[row.name].jobId}?schema=${encodeURIComponent(db ? `${db}.${schema}` : schema)}`)
}

// 行内"扫描"按钮:只扫这一张表
function scanSingle(row) {
  openScanDialog()
  singleTable.value = row.name
}

// 生成单表 AI 说明;大模型响应较慢,单请求超时放宽到 130s(后端读超时 120s)
async function generateDoc(row) {
  docLoading.value[row.name] = true
  try {
    const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
    const res = await request.post(`${base}/tables/${encodeURIComponent(row.name)}/doc${dbQuery()}`, null, { timeout: 130000 })
    docs.value[row.name] = res.description
    ElMessage.success(`已生成「${row.name}」的表说明`)
  } finally {
    docLoading.value[row.name] = false
  }
}

// 打开手动编辑描述弹窗(标记筛选只读模式下不可编辑)
function openDocEdit(row) {
  if (filterTagId.value) return
  docEditTable.value = row.name
  docEditText.value = docs.value[row.name] || ''
  docEditVisible.value = true
}

// 保存手动编辑的描述
async function saveDocEdit() {
  const text = docEditText.value.trim()
  if (!text) {
    ElMessage.warning('描述不能为空')
    return
  }
  docEditSaving.value = true
  try {
    const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
    await request.put(`${base}/tables/${encodeURIComponent(docEditTable.value)}/doc${dbQuery()}`, { description: text })
    docs.value[docEditTable.value] = text
    docEditVisible.value = false
    ElMessage.success('已保存')
  } finally {
    docEditSaving.value = false
  }
}

// 批量生成勾选的表说明:并发 2 逐表调单表接口,单表失败不影响其他
async function generateDocsBatch() {
  const queue = [...selectedTables.value]
  batchDocLoading.value = true
  let ok = 0
  let fail = 0
  async function worker() {
    while (queue.length) {
      const row = queue.shift()
      try {
        const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}`
        const res = await request.post(`${base}/tables/${encodeURIComponent(row.name)}/doc${dbQuery()}`, null, { timeout: 130000 })
        docs.value[row.name] = res.description
        ok++
      } catch {
        fail++ // 单表错误消息已由拦截器弹出
      }
    }
  }
  try {
    await Promise.all([worker(), worker()])
    ElMessage.success(`表说明生成完成:成功 ${ok} 张,失败 ${fail} 张`)
  } finally {
    batchDocLoading.value = false
  }
}

// 排序用:该表最近扫描完成时间的毫秒值,未扫描过排最前
function latestScanTime(row) {
  const t = latestScans.value[row.name]?.finishedAt
  return t ? new Date(t).getTime() : 0
}

// ---------- 面包屑 ----------
const dsName = computed(() => getDsName(dsId) || `数据源 ${dsId}`)
const schemaLabel = computed(() => (db ? `${db}.${schema}` : schema))
const breadcrumbItems = computed(() => [
  { label: dsName.value, to: `/datasources/${dsId}/schemas` },
  { label: schemaLabel.value }
])

function dbQuery() {
  return db ? `?db=${encodeURIComponent(db)}` : ''
}

function onSelectionChange(rows) {
  selectedTables.value = rows
}

// 点击表名统一进入字段明细页:已扫描带 jobId(展示扫描统计),未扫描仅结构
function goTableDetail(row) {
  const s = latestScans.value[row.name]
  const base = `/datasources/${dsId}/schemas/${encodeURIComponent(schema)}/tables/${encodeURIComponent(row.name)}${dbQuery()}`
  router.push(s ? `${base}${dbQuery() ? '&' : '?'}jobId=${s.jobId ?? s}` : base)
}

function openScanDialog() {
  singleTable.value = ''
  scanForm.forceFull = true
  scanForm.nullRules = []
  scanForm.maxSizeValue = null
  scanForm.maxSizeUnit = 'GB'
  // AI 相关默认勾选:未配置大模型时后端自动跳过
  scanForm.autoTag = true
  scanForm.genDoc = true
  scanForm.workers = defaultWorkers.value
  fetchScanDefaults()
  scanDialogVisible.value = true
}

async function submitScan() {
  const nullRules = scanForm.nullRules
    .filter((r) => r.column.trim() && r.valuesText.trim())
    .map((r) => ({
      column: r.column.trim(),
      values: r.valuesText.split(',').map((v) => v.trim()).filter(Boolean)
    }))
    .filter((r) => r.values.length > 0)

  submitting.value = true
  try {
    const res = await request.post('/scans', {
      datasourceId: /^\d+$/.test(String(dsId)) ? Number(dsId) : dsId,
      schema,
      database: db || null,
      tables: singleTable.value ? [singleTable.value] : (selectedTables.value.length ? selectedTables.value.map((t) => t.name) : null),
      forceFull: scanForm.forceFull,
      nullRules,
      maxTableSizeBytes: maxSizeBytes(),
      autoTag: scanForm.autoTag,
      genDoc: scanForm.genDoc,
      workers: scanForm.workers || null,
    })
    ElMessage.success('扫描任务已提交')
    scanDialogVisible.value = false
    // 带上库名标签,供页签标题展示
    const schemaLabel = db ? `${db}.${schema}` : schema
    router.push(`/scans/${res.jobId}?schema=${encodeURIComponent(schemaLabel)}`)
  } finally {
    submitting.value = false
  }
}

// 首次挂载标记:onActivated 在首次挂载后也会触发,避免与 onMounted 重复加载
const mounted = ref(false)

onMounted(async () => {
  await load()
  mounted.value = true
  fetchRunning()
  startPolling()
  window.addEventListener('resize', updateTableMaxHeight)
  // 数据源名兜底解析:刷新/直达 URL 无 ?name= 时也能恢复真名,并刷新页签标题
  ensureDsName(dsId).then(() => syncTab(route))
})

// 页签下钻时是失活而非卸载:停掉轮询,回来时重载表列表(行数/大小/最近扫描/表说明)并恢复轮询
onActivated(() => {
  if (!mounted.value) return // 首次挂载由 onMounted 处理
  load()
  fetchRunning()
  startPolling()
})

onDeactivated(stopPolling)

onUnmounted(() => {
  stopPolling()
  window.removeEventListener('resize', updateTableMaxHeight)
})
</script>

<style scoped>
/* 工具栏按钮组:flex + gap 统一间距,并清掉 el-button 相邻默认 margin(el-dropdown 不吃该规则导致间距不一) */
.toolbar-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
.toolbar-actions :deep(.el-button) {
  margin-left: 0;
}
.table-tag {
  margin: 0 4px 2px 0;
}
/* 列设置弹层:复选框纵向排列 */
.col-setting {
  display: flex;
  flex-direction: column;
}
/* 全选行与列表之间加分隔线 */
.col-setting-all {
  width: 100%;
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding-bottom: 4px;
  margin-bottom: 4px;
}
.doc-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}
.doc-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
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
