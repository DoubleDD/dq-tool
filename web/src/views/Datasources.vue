<template>
  <div class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <h3 style="margin: 0">数据源</h3>
        <span v-if="list.length" class="toolbar-sub">{{ list.length }} 个连接</span>
        <el-input v-if="list.length" v-model="keyword" placeholder="搜索名称/主机/类型" clearable
          :prefix-icon="Search" style="width: 220px" />
        <el-select v-if="list.length && groupOptions.length" v-model="groupFilter" placeholder="按分组筛选"
          clearable style="width: 150px">
          <el-option label="未分组" value="__ungrouped__" />
          <el-option v-for="g in groupOptions" :key="g" :label="g" :value="g" />
        </el-select>
      </div>
      <div class="toolbar-right">
        <el-button @click="openExportDialog()">导出配置(JSON)</el-button>
        <el-button @click="openImportDialog()">导入配置</el-button>
        <el-button type="primary" @click="openDialog()">新增数据源</el-button>
      </div>
    </div>

    <!-- 空状态:四步上手引导,替代单纯 el-empty -->
    <div v-if="!loading && !list.length" class="flow-guide">
      <div class="flow-guide-title">快速上手</div>
      <div class="flow-steps">
        <div class="flow-step active" @click="openDialog()">
          <div class="flow-step-no">1</div>
          <div class="flow-step-body">
            <div class="flow-step-name">连接数据源</div>
            <div class="flow-step-desc">新增数据库连接,支持 8 种数据库</div>
          </div>
        </div>
        <el-icon class="flow-arrow"><ArrowRight /></el-icon>
        <div class="flow-step disabled">
          <div class="flow-step-no">2</div>
          <div class="flow-step-body">
            <div class="flow-step-name">浏览库表</div>
            <div class="flow-step-desc">查看库下的表、字段与索引结构</div>
          </div>
        </div>
        <el-icon class="flow-arrow"><ArrowRight /></el-icon>
        <div class="flow-step disabled">
          <div class="flow-step-no">3</div>
          <div class="flow-step-body">
            <div class="flow-step-name">发起扫描</div>
            <div class="flow-step-desc">检测空值、空表等数据质量问题</div>
          </div>
        </div>
        <el-icon class="flow-arrow"><ArrowRight /></el-icon>
        <div class="flow-step disabled">
          <div class="flow-step-no">4</div>
          <div class="flow-step-body">
            <div class="flow-step-name">导出扫描报告</div>
            <div class="flow-step-desc">Excel 明细或 Word 调研报告</div>
          </div>
        </div>
      </div>
      <div class="flow-guide-foot">
        <el-button type="primary" @click="openDialog()">新增数据源,开始第一步</el-button>
      </div>
    </div>

    <!-- 有数据源:轻量「下一步」提示 -->
    <el-alert v-else-if="list.length" type="info" :closable="false" show-icon class="next-tip">
      <template #title>
        已连接 <b>{{ list.length }}</b> 个数据源。点击卡片查看库与表并发起扫描;进度可在顶部「扫描记录」跟进,报告在「报告列表」页下载。
      </template>
    </el-alert>

    <!-- 分组区块:一个分组一个可折叠区块,未分组排最后;全库无任何分组时退化为平铺网格(不显示区块头) -->
    <div v-if="list.length || loading" v-loading="loading">
      <el-empty v-if="!loading && !filteredList.length" description="没有匹配的数据源" :image-size="80" />
      <div v-for="g in groupedList" :key="g.key" class="ds-group">
        <!-- 分组头同时是拖拽放置目标(折叠时也可拖入);is-drop-target 为 dragover 高亮 -->
        <div v-if="hasAnyGroup" class="ds-group-header"
          :class="{ 'is-drop-target': dropTargetKey === g.key }"
          @click="toggleGroup(g.key)"
          @dragover.prevent="onGroupDragOver($event, g.key)"
          @dragleave="onGroupDragLeave($event, g.key)"
          @drop.prevent="onGroupDrop(g.key)">
          <el-icon class="ds-group-arrow" :class="{ 'is-collapsed': isGroupCollapsed(g.key) }"><ArrowDown /></el-icon>
          <span class="ds-group-name">{{ g.name }}</span>
          <span class="ds-group-count">{{ g.items.length }} 个连接</span>
        </div>
        <div v-show="!isGroupCollapsed(g.key)" class="ds-grid"
          :class="{ 'is-drop-target': dropTargetKey === g.key }"
          @dragover.prevent="onGroupDragOver($event, g.key)"
          @dragleave="onGroupDragLeave($event, g.key)"
          @drop.prevent="onGroupDrop(g.key)">
          <el-card v-for="row in g.items" :key="row.id" shadow="hover" class="ds-card"
            :class="{ 'ds-no-password': row.hasPassword === false, 'ds-conn-error': row.connStatus === 'ERROR', 'is-dragging': draggingId === row.id }"
            :title="row.hasPassword === false ? '未设置密码,请先编辑补充密码' : (row.connStatus === 'ERROR' ? `连接失败:${row.connError || '请检查连接信息'}` : row.jdbcUrl)"
            draggable="true"
            @dragstart="onDragStart($event, row)"
            @dragend="onDragEnd"
            @click="goSchemas(row)">
            <DbTypeIcon :type="row.dbType" :size="72" class="ds-bg-icon" />
            <div class="ds-card-header">
              <span class="ds-name" :title="row.name">
                <el-tooltip v-if="row.hasPassword === false" content="未设置密码,请先编辑补充密码" placement="top">
                  <el-icon class="ds-error-icon"><WarningFilled /></el-icon>
                </el-tooltip>
                <!-- 表格批量导入时连不上的数据源:与未设密码的红框同款警示,两个提示可并存 -->
                <el-tooltip v-if="row.connStatus === 'ERROR'" placement="top">
                  <template #content>
                    <div>{{ row.connError || '连接失败' }}</div>
                    <div>表格批量导入时无法连接,请检查连接信息</div>
                  </template>
                  <span class="ds-conn-error-tag">
                    <el-icon class="ds-error-icon"><WarningFilled /></el-icon>
                    <span class="ds-conn-error-text">连接失败</span>
                  </span>
                </el-tooltip>
                {{ row.name }}
              </span>
              <span class="ds-card-right">
                <el-icon class="ds-fav" :class="{ 'ds-fav-on': isFav(row.id) }"
                  :title="isFav(row.id) ? '取消收藏' : '收藏(置顶展示)'"
                  @click.stop="toggleFavorite(row)">
                  <StarFilled v-if="isFav(row.id)" /><Star v-else />
                </el-icon>
                <el-tag size="small">{{ row.dbType }}</el-tag>
              </span>
            </div>
            <div class="ds-meta">
              <span class="ds-meta-item"><el-icon><Connection /></el-icon>{{ dbHost(row.jdbcUrl) }}</span>
              <span class="ds-meta-item" v-if="row.username"><el-icon><User /></el-icon>{{ row.username }}</span>
            </div>
            <!-- 左下角编辑 / 右下角删除;浏览库靠点击卡片本体 -->
            <div class="ds-card-foot">
              <el-button link class="ds-icon-btn" @click.stop="openDialog(row)">
                <el-icon><EditPen /></el-icon>
              </el-button>
              <el-button link type="danger" class="ds-icon-btn" @click.stop="onDelete(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
          </el-card>
        </div>
      </div>
    </div>

    <!-- 新增/编辑数据源弹窗(独立组件,抽样导出页等处复用);编辑态由 openDialog(row) 传入数据源行 -->
    <DatasourceEditDialog v-model="editVisible" :ds="editRow" :groups="groupOptions" @saved="loadList" />

    <!-- 导出数据源:勾选后通过 window.open 直接下载 JSON 文件 -->
    <el-dialog v-model="exportVisible" title="导出数据源" width="560px" destroy-on-close :close-on-press-escape="false">
      <template v-if="list.length">
        <div class="export-head">
          <el-checkbox :model-value="exportCheckAll" :indeterminate="exportIndeterminate" @change="onExportCheckAll">全选</el-checkbox>
          <span class="export-count">已选 {{ exportChecked.length }} / {{ list.length }}</span>
        </div>
        <el-checkbox-group v-model="exportChecked" class="export-list">
          <el-checkbox v-for="row in list" :key="row.id" :value="row.id" class="export-item">
            <span class="export-item-main">
              <DbTypeIcon :type="row.dbType" />
              <span class="export-item-name">{{ row.name }}</span>
              <span class="export-item-url" :title="row.jdbcUrl">{{ row.jdbcUrl }}</span>
            </span>
          </el-checkbox>
        </el-checkbox-group>
        <div class="export-tip">导出文件包含加密后的连接密码,请妥善保管,勿对外发送。</div>
      </template>
      <el-empty v-else description="暂无数据源可导出" :image-size="80" />
      <template #footer>
        <el-button @click="exportVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!exportChecked.length" @click="doExport">导出</el-button>
      </template>
    </el-dialog>

    <!-- 导入数据源:文件上传或粘贴文本,成功后对话框内展示结果明细 -->
    <el-dialog v-model="importVisible" title="导入数据源" width="560px" destroy-on-close :close-on-press-escape="false" @closed="onImportClosed">
      <template v-if="!importResult">
        <el-radio-group v-model="importMode" class="import-mode">
          <el-radio-button value="file">文件导入</el-radio-button>
          <el-radio-button value="text">粘贴导入</el-radio-button>
        </el-radio-group>
        <template v-if="importMode === 'file'">
          <el-upload ref="uploadRef" drag :auto-upload="false" accept=".json,.ncx" :limit="1"
            :on-change="onImportFileChange" :on-exceed="onImportFileExceed" :on-remove="onImportFileRemove">
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽文件到此处,或 <em>点击选择文件</em></div>
          </el-upload>
          <div class="import-tip">
            支持本工具导出的 JSON 与 Navicat 连接导出的 .ncx 文件;重名数据源会自动追加序号后缀导入,不会覆盖已有配置。
          </div>
        </template>
        <template v-else>
          <el-input v-model="importText" type="textarea" :rows="10" resize="none"
            placeholder="粘贴 DataGrip「复制数据源到剪贴板」的内容(#DataSourceSettings# 开头),也支持本工具导出的 JSON 文本" />
          <div class="import-tip">
            DataGrip 剪贴板内容不含连接密码,导入后需逐个编辑数据源补充密码;重名数据源会自动追加序号后缀导入。
          </div>
        </template>
      </template>
      <div v-else class="import-result">
        <div class="import-summary">共解析 {{ importResult.total || 0 }} 条,成功导入 {{ importResult.imported?.length || 0 }} 条。</div>
        <template v-if="importResult.imported?.length">
          <div class="section-title">导入成功({{ importResult.imported.length }})</div>
          <div class="import-names">{{ importResult.imported.join('、') }}</div>
        </template>
        <template v-if="renamedList.length">
          <div class="section-title">自动改名({{ renamedList.length }})</div>
          <div v-for="r in renamedList" :key="r.from" class="import-rename-item">{{ r.from }} → {{ r.to }}</div>
        </template>
        <template v-if="importResult.failed?.length">
          <div class="section-title">导入失败({{ importResult.failed.length }})</div>
          <el-alert v-for="f in importResult.failed" :key="f.name" type="error" :closable="false" show-icon
            class="import-alert" :title="`${f.name}:${f.reason}`" />
        </template>
        <template v-if="importResult.warnings?.length">
          <div class="section-title">警告({{ importResult.warnings.length }})</div>
          <el-alert v-for="(w, i) in importResult.warnings" :key="i" type="warning" :closable="false" show-icon
            class="import-alert" :title="w" />
        </template>
      </div>
      <template #footer>
        <template v-if="!importResult">
          <el-button @click="importVisible = false">取消</el-button>
          <el-button type="primary" :disabled="!canImport" :loading="importing" @click="doImport">导入</el-button>
        </template>
        <el-button v-else type="primary" @click="importVisible = false">关闭</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup>
import { computed, onActivated, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import { ArrowDown, ArrowRight, Connection, Delete, EditPen, Search, Star, StarFilled, UploadFilled, User, WarningFilled } from '@element-plus/icons-vue'
import request from '../api'
import DbTypeIcon from '../components/DbTypeIcon.vue'
import DatasourceEditDialog from '../components/DatasourceEditDialog.vue'
import { tabState } from '../stores/tabs'
import { loadDsFavorites, saveDsFavorites, sortDsByFavorite } from '../utils/dsFavorites'
import { notifyDsListChanged } from '../utils/dsListChanged'
import { downloadFile } from '../utils/download'
import { confirmImportFile } from '../utils/importFileIdentify'

const router = useRouter()
const list = ref([])
const loading = ref(false)
// 搜索关键字(匹配名称/主机/用户名/类型)
const keyword = ref('')
// 分组筛选:'' 全部,'__ungrouped__' 未分组,其余按分组名精确匹配
const groupFilter = ref('')
// 已有分组列表:从数据源聚合去重,供筛选下拉与编辑表单选择
const groupOptions = computed(() => {
  const set = new Set()
  list.value.forEach((r) => { if (r.groupName) set.add(r.groupName) })
  return [...set].sort()
})
// 未分组区块的 key(与筛选下拉的「未分组」选项共用同一哨兵值)
const UNGROUPED = '__ungrouped__'
// 全库是否存在任何命名分组:没有时退化为平铺网格,不渲染区块头
const hasAnyGroup = computed(() => groupOptions.value.length > 0)
// 被折叠的分组 key 集合;默认全部展开
const collapsedGroups = ref([])

function isGroupCollapsed(key) {
  return collapsedGroups.value.includes(key)
}

function toggleGroup(key) {
  const i = collapsedGroups.value.indexOf(key)
  if (i >= 0) collapsedGroups.value.splice(i, 1)
  else collapsedGroups.value.push(key)
}

/** 过滤后的数据源按分组成块:命名分组按名称排序,未分组排最后;组内保持收藏置顶顺序 */
const groupedList = computed(() => {
  const map = new Map()
  filteredList.value.forEach((r) => {
    const key = r.groupName || UNGROUPED
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(r)
  })
  return [...map.entries()]
    .map(([key, items]) => ({ key, name: key === UNGROUPED ? '未分组' : key, items }))
    .sort((a, b) => {
      if (a.key === UNGROUPED) return 1
      if (b.key === UNGROUPED) return -1
      return a.key.localeCompare(b.key, 'zh')
    })
})

// ---------- 卡片拖拽改分组(原生 HTML5 drag & drop) ----------
// 正在拖拽的数据源 id;null 表示无拖拽,用于拖动虚影样式
const draggingId = ref(null)
// 当前 dragover 命中的放置目标分组 key(UNGROUPED 哨兵或分组名),驱动目标高亮
const dropTargetKey = ref(null)

function onDragStart(e, row) {
  draggingId.value = row.id
  e.dataTransfer.effectAllowed = 'move'
  e.dataTransfer.setData('text/plain', String(row.id))
}

function onDragEnd() {
  draggingId.value = null
  dropTargetKey.value = null
}

function onGroupDragOver(e, key) {
  e.dataTransfer.dropEffect = 'move'
  dropTargetKey.value = key
}

function onGroupDragLeave(e, key) {
  // 移入子元素也会触发 dragleave,仅真正离开该区块时清除高亮
  if (!e.currentTarget.contains(e.relatedTarget) && dropTargetKey.value === key) {
    dropTargetKey.value = null
  }
}

/** 放置到目标分组:同组直接忽略;跨组调轻量分组接口后整体重拉(与保存后惯例一致),失败提示由 axios 拦截器统一弹出 */
async function onGroupDrop(key) {
  const id = draggingId.value
  draggingId.value = null
  dropTargetKey.value = null
  if (id == null) return
  const row = list.value.find((r) => r.id === id)
  if (!row) return
  // 拖到「未分组」区块传 null;后端空白同样归一为未分组
  const target = key === UNGROUPED ? null : key
  if (target === (row.groupName || null)) return
  await request.put(`/datasources/${id}/group`, { groupName: target })
  ElMessage.success(target ? `已移动到分组「${target}」` : '已移出分组')
  loadList()
  notifyDsListChanged()
}
// 收藏:前端本地偏好,按数据源 id 存 localStorage;收藏的卡片排最前,同收藏按收藏时间倒序(与侧边栏共用 dsFavorites 工具)
const favorites = ref(loadDsFavorites())

function isFav(id) {
  return favorites.value.includes(id)
}

function toggleFavorite(row) {
  const i = favorites.value.indexOf(row.id)
  if (i >= 0) favorites.value.splice(i, 1)
  else favorites.value.push(row.id)
  saveDsFavorites(favorites.value)
}

/** 搜索过滤 + 分组筛选 + 收藏置顶(收藏时间倒序) */
const filteredList = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  let arr = list.value
  if (kw) {
    arr = arr.filter((r) =>
      [r.name, r.jdbcUrl, r.username, r.dbType, r.groupName].some((v) => (v || '').toLowerCase().includes(kw))
    )
  }
  if (groupFilter.value === '__ungrouped__') {
    arr = arr.filter((r) => !r.groupName)
  } else if (groupFilter.value) {
    arr = arr.filter((r) => r.groupName === groupFilter.value)
  }
  return sortDsByFavorite(arr, favorites.value)
})
// 新增/编辑数据源弹窗(独立组件 DatasourceEditDialog,抽样导出页等处复用):editRow 为要编辑的数据源行,新增传 null
const editVisible = ref(false)
const editRow = ref(null)

function openDialog(row) {
  editRow.value = row || null
  editVisible.value = true
}

async function loadList() {
  loading.value = true
  try {
    list.value = await request.get('/datasources')
  } finally {
    loading.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除数据源「${row.name}」吗?`, '删除确认', { type: 'warning', closeOnPressEscape: false })
  await request.delete(`/datasources/${row.id}`)
  ElMessage.success('删除成功')
  loadList()
  notifyDsListChanged()
}

function goSchemas(row) {
  // 未设密码的数据源不可浏览(卡片点击与浏览库按钮同一路径)
  if (row.hasPassword === false) return
  router.push(`/datasources/${row.id}/schemas?name=${encodeURIComponent(row.name)}`)
}
/** 从 JDBC URL 提取主机名用于卡片摘要(仅展示,完整地址悬停卡片可见) */
function dbHost(jdbcUrl) {
  const m = (jdbcUrl || '').match(/(?:@\/\/|:\/\/)([^/:;?]+)/)
  return m ? m[1] : ''
}

// ---------- 导出 ----------
const exportVisible = ref(false)
const exportChecked = ref([])

const exportCheckAll = computed(() => list.value.length > 0 && exportChecked.value.length === list.value.length)
const exportIndeterminate = computed(() => exportChecked.value.length > 0 && exportChecked.value.length < list.value.length)

function onExportCheckAll(val) {
  exportChecked.value = val ? list.value.map((r) => r.id) : []
}

function openExportDialog() {
  // 默认全选,与扫描导出对话框的默认行为一致
  exportChecked.value = list.value.map((r) => r.id)
  exportVisible.value = true
}

function doExport() {
  // 桌面端弹原生保存对话框自选目录,浏览器走默认下载(见 utils/download.js)
  downloadFile('/api/datasources/export?ids=' + exportChecked.value.join(','))
  exportVisible.value = false
}

// ---------- 导入 ----------
const importVisible = ref(false)
const importing = ref(false)
const importMode = ref('file')
const importFile = ref(null)
const importText = ref('')
const importResult = ref(null)
const uploadRef = ref(null)

// renamed 为 {原名称: 新名称} 映射,转成列表便于渲染
const renamedList = computed(() =>
  Object.entries(importResult.value?.renamed || {}).map(([from, to]) => ({ from, to }))
)
const canImport = computed(() =>
  importMode.value === 'file' ? !!importFile.value : !!importText.value.trim()
)

function openImportDialog() {
  importMode.value = 'file'
  importFile.value = null
  importText.value = ''
  importResult.value = null
  importVisible.value = true
}

async function onImportFileChange(file) {
  // accept 属性只管文件选择器,拖拽进来的文件需要手动校验扩展名
  const name = (file.name || '').toLowerCase()
  if (!name.endsWith('.json') && !name.endsWith('.ncx')) {
    ElMessage.warning('仅支持 .json 或 .ncx 文件')
    uploadRef.value?.clearFiles()
    importFile.value = null
    return
  }
  if (!file.raw) return
  // 识别文件种类并弹窗确认:确认后直接开始导入;无法识别/不属于本功能/用户取消时清空选择
  if (!await confirmImportFile(file.raw, 'datasource')) {
    uploadRef.value?.clearFiles()
    importFile.value = null
    return
  }
  importFile.value = file.raw
  doImport()
}

/** 超出 limit 时替换为最新选择的文件(handleStart 会再次触发 on-change) */
function onImportFileExceed(files) {
  uploadRef.value.clearFiles()
  uploadRef.value.handleStart(files[0])
}

function onImportFileRemove() {
  importFile.value = null
}

async function doImport() {
  if (!canImport.value || importing.value) return
  importing.value = true
  try {
    const formData = new FormData()
    if (importMode.value === 'file') {
      formData.append('file', importFile.value)
    } else {
      formData.append('text', importText.value)
    }
    importResult.value = await request.post('/datasources/import', formData)
  } finally {
    importing.value = false
  }
}

/** 对话框完全关闭后:有成功导入则刷新列表,并重置状态供下次打开 */
function onImportClosed() {
  if ((importResult.value?.imported?.length || 0) > 0) {
    loadList()
    notifyDsListChanged()
  }
  importResult.value = null
  importFile.value = null
  importText.value = ''
}

// 侧边栏「数据源」操作下拉(pendingDsDialog = new|import|export)时自动打开对应对话框。
// 导出依赖 list(全选),先等列表加载完成再开框;命令消费后立即清空,避免重复弹框。
watch(
  () => tabState.pendingDsDialog,
  async (v) => {
    if (!v) return
    tabState.pendingDsDialog = ''
    await loadList()
    if (v === 'new') openDialog()
    else if (v === 'import') openImportDialog()
    else if (v === 'export') openExportDialog()
  },
  { immediate: true }
)

// 侧边栏数据源项右侧编辑图标(pendingDsEditId = 数据源 id)时自动打开编辑对话框。
// 与 pendingDsDialog 同理:先等列表加载完成再按 id 找行;消费后立即清空,避免重复弹框。
watch(
  () => tabState.pendingDsEditId,
  async (v) => {
    if (!v) return
    tabState.pendingDsEditId = ''
    await loadList()
    const row = list.value.find((d) => String(d.id) === String(v))
    if (row) openDialog(row)
  },
  { immediate: true }
)

// 数据源页切回时刷新(首次挂载也会触发)
onActivated(loadList)
</script>

<style scoped>
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.toolbar-sub {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 空状态:四步上手引导卡 */
.flow-guide {
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  padding: 24px 28px;
  margin-bottom: 16px;
  background: var(--dq-surface);
}
.flow-guide-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 16px;
}
.flow-steps {
  display: flex;
  align-items: stretch;
  gap: 12px;
  flex-wrap: wrap;
}
.flow-step {
  flex: 1;
  min-width: 180px;
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  cursor: default;
}
.flow-step.active {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  cursor: pointer;
  transition: transform 0.2s ease, border-color 0.2s ease;
}
.flow-step.active:hover {
  transform: translateY(-2px);
  border-color: var(--el-color-primary);
}
.flow-step.disabled {
  opacity: 0.62;
}
.flow-step-no {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: var(--el-text-color-placeholder);
}
.flow-step.active .flow-step-no {
  background: var(--el-color-primary);
}
.flow-step-body {
  min-width: 0;
}
.flow-step-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.flow-step-desc {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
.flow-arrow {
  align-self: center;
  color: var(--el-text-color-placeholder);
  flex-shrink: 0;
}
.flow-guide-foot {
  margin-top: 18px;
  text-align: center;
}

/* 有数据源:下一步提示条 */
.next-tip {
  margin-bottom: 16px;
}
.ds-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}
/* 分组区块:头部可点击折叠/展开,底色+左侧强调条与卡片网格明显区分 */
.ds-group {
  margin-bottom: 20px;
}
.ds-group-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 12px;
  cursor: pointer;
  user-select: none;
  font-weight: 600;
  background-color: var(--el-fill-color);
  border-left: 3px solid var(--el-color-primary-light-5);
  border-radius: 6px;
  transition: background-color 0.2s ease;
}
.ds-group-header:hover {
  background-color: var(--el-fill-color-dark);
  color: var(--el-color-primary);
}
.ds-group-arrow {
  transition: transform 0.2s ease;
}
.ds-group-arrow.is-collapsed {
  transform: rotate(-90deg);
}
.ds-group-count {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}
/* 拖拽改分组:拖动中的卡片半透明虚影 */
.ds-card.is-dragging {
  opacity: 0.4;
}
/* 拖拽放置目标(分组头/卡片网格)高亮:主色虚线框 + 浅底 */
.ds-group-header.is-drop-target,
.ds-grid.is-drop-target {
  outline: 2px dashed var(--el-color-primary-light-5);
  outline-offset: 2px;
  background-color: var(--el-color-primary-light-9);
  border-radius: 6px;
}
.ds-card :deep(.el-card__body) {
  position: relative;
}
/* 卡片整体可点击(等同浏览库);未设密码时禁用并显示禁用光标 */
.ds-card {
  cursor: pointer;
  transition: transform 0.2s ease, border-color 0.2s ease, background-color 0.2s ease;
}
/* hover 高亮 + 上浮悬浮效果 */
.ds-card:hover {
  transform: translateY(-3px);
  border-color: var(--el-color-primary-light-5);
  background-color: var(--el-fill-color-extra-light);
}
/* 未设密码:整卡浅红警示(底色+边框),搭配标题前红色叹号图标 */
.ds-no-password {
  cursor: not-allowed;
  border-color: var(--el-color-danger-light-5);
  background-color: var(--el-color-danger-light-9);
}
/* hover 时警示色加深一档,保持可感知但不误导为可点击 */
.ds-no-password:hover {
  border-color: var(--el-color-danger-light-3);
  background-color: var(--el-color-danger-light-8);
}
/* 未设密码数据源标题前的红色叹号图标 */
.ds-error-icon {
  color: var(--el-color-danger);
  font-size: 16px;
}
/* 连接失败:与未设密码同款浅红警示(不禁用点击,仍可进库列表排查) */
.ds-conn-error {
  border-color: var(--el-color-danger-light-5);
  background-color: var(--el-color-danger-light-9);
}
.ds-conn-error:hover {
  border-color: var(--el-color-danger-light-3);
  background-color: var(--el-color-danger-light-8);
}
/* 连接失败标记:叹号图标 + 红字,与未设密码图标可并存 */
.ds-conn-error-tag {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  vertical-align: middle;
}
.ds-conn-error-text {
  color: var(--el-color-danger);
  font-size: 12px;
}
.ds-bg-icon {
  position: absolute;
  right: 6px;
  bottom: 6px;
  opacity: 0.12;
  pointer-events: none;
}
.ds-card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
  gap: 8px;
}
.ds-name {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  flex: 1;
  min-width: 0;
  gap: 4px 8px;
  font-size: 17px;
  font-weight: 700;
  white-space: normal;
  word-break: break-all;
}
.ds-card-right {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
/* 收藏星标:默认灰,收藏后高亮 */
.ds-fav {
  cursor: pointer;
  font-size: 15px;
  color: var(--el-text-color-placeholder);
  transition: color 0.2s ease, transform 0.2s ease;
}
.ds-fav:hover {
  color: var(--el-color-warning);
  transform: scale(1.15);
}
.ds-fav-on {
  color: var(--el-color-warning);
}
.ds-meta {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 20px;
}
.ds-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ds-meta-item .el-icon {
  color: var(--el-text-color-placeholder);
}
/* 卡片底部:左下编辑 / 右下删除,浏览库靠点击卡片本体 */
.ds-card-foot {
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-extra-light);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.ds-icon-btn {
  padding: 4px 6px;
  font-size: 16px;
  color: var(--el-text-color-secondary);
}
.ds-icon-btn:hover {
  color: var(--el-color-primary);
}
.ds-icon-btn.el-button--danger {
  color: var(--el-color-danger);
}
.ds-icon-btn.el-button--danger:hover {
  color: var(--el-color-danger-light-3);
}
/* 导出对话框:全选行 + 勾选项列表 */
.export-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}
.export-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.export-list {
  display: flex;
  flex-direction: column;
  max-height: 320px;
  overflow-y: auto;
}
.export-item {
  width: 100%;
  height: auto;
  margin-right: 0;
  padding: 6px 0;
}
.export-item :deep(.el-checkbox__label) {
  flex: 1;
  overflow: hidden;
}
.export-item-main {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
}
.export-item-name {
  color: var(--el-text-color-primary);
  font-weight: 600;
  white-space: nowrap;
}
.export-item-url {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.export-tip {
  margin-top: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
/* 导入对话框:说明与结果明细 */
.import-mode {
  margin-bottom: 12px;
}
.import-tip {
  margin-top: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.import-summary {
  margin-bottom: 12px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
}
.import-names {
  margin-bottom: 12px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.8;
}
.import-rename-item {
  margin-bottom: 4px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}
.import-alert {
  margin-bottom: 8px;
}
.section-title {
  margin: 12px 0 8px;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 600;
}
</style>
