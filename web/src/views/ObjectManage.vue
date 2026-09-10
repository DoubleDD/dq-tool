<template>
  <div class="page-card om-page">
    <div class="toolbar">
      <h3 style="margin: 0">对象管理</h3>
      <div class="toolbar-actions">
        <el-select v-model="dsId" filterable placeholder="选择数据源" style="width: 220px" :loading="dsLoading" @change="onDsChange">
          <el-option v-for="ds in datasources" :key="ds.id" :value="String(ds.id)" :label="ds.name">
            <div class="ds-option">
              <DbTypeIcon :type="ds.dbType" :size="14" />
              <span>{{ ds.name }}</span>
            </div>
          </el-option>
        </el-select>
      </div>
    </div>

    <el-empty v-if="!dsId" description="请选择数据源,查看和维护它的数据目录" :image-size="90" />

    <template v-else>
      <!-- 左侧目录树常驻 + 右侧三页签(列表/关系图/图谱),页签内容随选中目录实时联动 -->
      <div class="om-body" v-loading="loading">
        <div class="om-tree-pane">
          <!-- 树操作栏:刷新 + 新建根目录 -->
          <div class="om-tree-head">
            <el-button size="small" :icon="Refresh" :loading="loading" @click="loadCatalog()">刷新</el-button>
            <el-button size="small" type="primary" :icon="Plus" @click="createDir(null)">新建根目录</el-button>
          </div>
          <el-tree
            ref="treeRef"
            :data="catalog"
            node-key="id"
            :props="{ children: 'children', label: 'name' }"
            highlight-current
            default-expand-all
            @node-click="onDirClick"
          >
            <template #default="{ data }">
              <div class="om-tree-node">
                <el-icon class="om-tree-icon"><Folder /></el-icon>
                <span class="om-tree-label" :title="data.name">{{ data.name }}</span>
                <span class="om-tree-count" title="本目录及子目录挂载表总数">{{ countTables(data) }}</span>
                <span class="om-tree-ops" @click.stop>
                  <el-icon title="新增子目录" @click="createDir(data)"><Plus /></el-icon>
                  <el-icon title="重命名" @click="renameDir(data)"><EditPen /></el-icon>
                  <el-icon title="删除" @click="removeDir(data)"><Delete /></el-icon>
                </span>
              </div>
            </template>
          </el-tree>
          <el-empty v-if="!loading && !catalog.length" description="暂无目录,点击上方「新建根目录」开始" :image-size="70" />
        </div>
        <div class="om-main-pane">
          <el-tabs v-model="activeTab" class="om-tabs">
            <!-- 列表:当前目录直接挂载的表(行内展开关系表子表) -->
            <el-tab-pane label="列表" name="list">
              <div class="om-tab-body om-table-pane">
                <template v-if="selectedDir">
                  <div class="om-pane-head">
                    <span class="om-pane-title" :title="selectedDir.name">{{ selectedDir.name }}</span>
                    <span class="om-pane-sub">已挂载 {{ (selectedDir.tables || []).length }} 张表</span>
                    <el-button size="small" type="primary" :icon="Plus" @click="mountVisible = true">挂载表</el-button>
                  </div>
                  <el-table :data="selectedDir.tables || []" border row-key="id" class="om-table">
                    <!-- 行内展开:该挂载表的关系表子表 -->
                    <el-table-column type="expand">
                      <template #default="{ row }">
                        <div class="om-rel-wrap">
                          <el-table :data="row.relations || []" size="small" border>
                            <el-table-column label="关系表" min-width="170" show-overflow-tooltip>
                              <template #default="{ row: rel }">
                                <el-link type="primary" @click="goTable(rel)">{{ rel.tableName }}</el-link>
                                <el-tag size="small" type="warning" effect="plain" style="margin-left: 6px">关系</el-tag>
                              </template>
                            </el-table-column>
                            <el-table-column v-if="isMultiDb" label="库" min-width="110" show-overflow-tooltip>
                              <template #default="{ row: rel }"><span v-if="rel.dbName">{{ rel.dbName }}</span><span v-else class="om-dash">-</span></template>
                            </el-table-column>
                            <el-table-column prop="schemaName" label="schema" min-width="110" show-overflow-tooltip />
                            <el-table-column label="注释" min-width="140" show-overflow-tooltip>
                              <template #default="{ row: rel }"><span v-if="rel.comment">{{ rel.comment }}</span><span v-else class="om-dash">-</span></template>
                            </el-table-column>
                            <el-table-column label="备注" min-width="140" show-overflow-tooltip>
                              <template #default="{ row: rel }"><span v-if="rel.remark">{{ rel.remark }}</span><span v-else class="om-dash">-</span></template>
                            </el-table-column>
                            <el-table-column label="操作" width="80">
                              <template #default="{ row: rel }">
                                <el-button link type="danger" @click="removeRelation(rel)">删除</el-button>
                              </template>
                            </el-table-column>
                            <template #empty>暂无关系表,可在本行点「添加关系表」</template>
                          </el-table>
                        </div>
                      </template>
                    </el-table-column>
                    <el-table-column label="表名" min-width="180" show-overflow-tooltip>
                      <template #default="{ row }">
                        <el-link type="primary" @click="goTable(row)">{{ row.tableName }}</el-link>
                      </template>
                    </el-table-column>
                    <el-table-column v-if="isMultiDb" label="库" min-width="120" show-overflow-tooltip>
                      <template #default="{ row }"><span v-if="row.dbName">{{ row.dbName }}</span><span v-else class="om-dash">-</span></template>
                    </el-table-column>
                    <el-table-column prop="schemaName" label="schema" min-width="120" show-overflow-tooltip />
                    <el-table-column label="注释" min-width="150" show-overflow-tooltip>
                      <template #default="{ row }"><span v-if="row.comment">{{ row.comment }}</span><span v-else class="om-dash">-</span></template>
                    </el-table-column>
                    <el-table-column label="备注" min-width="150" show-overflow-tooltip>
                      <template #default="{ row }"><span v-if="row.remark">{{ row.remark }}</span><span v-else class="om-dash">-</span></template>
                    </el-table-column>
                    <el-table-column label="操作" width="200" fixed="right" class-name="om-op-cell">
                      <template #default="{ row }">
                        <el-button link type="primary" @click="openRelDialog(row)">添加关系表</el-button>
                        <el-button link type="danger" @click="unmount(row)">取消挂载</el-button>
                      </template>
                    </el-table-column>
                    <template #empty>该目录还没有挂载表,点右上角「挂载表」添加</template>
                  </el-table>
                </template>
                <el-empty v-else description="请选择左侧目录" :image-size="70" class="om-tab-empty" />
              </div>
            </el-tab-pane>
            <!-- 关系图:以选中目录为根的目录子树 mindmap(原图视图);显示开关在画布顶部工具栏 -->
            <el-tab-pane label="关系图" name="graph" lazy>
              <div class="om-tab-body om-graph-pane">
                <ObjectGraphCanvas
                  v-if="selectedDir"
                  :root-dir="selectedDir"
                  :ds-id="dsId"
                  :direction="graphDirection"
                  :show-fields="showFields"
                  :max-fields="maxFields"
                  :show-field-type="showFieldType"
                  :field-name-mode="fieldNameMode"
                >
                  <template #toolbar>
                    <el-checkbox v-model="verticalLayout" size="small">垂直布局</el-checkbox>
                    <el-checkbox v-model="showFields" size="small">显示字段</el-checkbox>
                    <span class="om-setting-item">
                      <span class="om-setting-label">字段数</span>
                      <el-input-number v-model="maxFields" size="small" :min="1" :max="100" :disabled="!showFields" />
                    </span>
                    <el-checkbox v-model="showFieldType" size="small" :disabled="!showFields">显示类型</el-checkbox>
                    <!-- 名字口径三档(表名/字段同规则):仅中文(默认,无注释回退英文)/仅英文/中英文同时显示(中文在前) -->
                    <el-select v-model="fieldNameMode" size="small" :disabled="!showFields" style="width: 150px">
                      <el-option label="仅显示字段中文名" value="chinese" />
                      <el-option label="仅显示字段英文名" value="english" />
                      <el-option label="中英文同时显示" value="both" />
                    </el-select>
                  </template>
                </ObjectGraphCanvas>
                <el-empty v-else description="请选择左侧目录" :image-size="70" class="om-tab-empty" />
              </div>
            </el-tab-pane>
            <!-- 图谱:选中目录(含子目录)挂载表之间的 ER 推导关系(只读) -->
            <el-tab-pane label="图谱" name="atlas" lazy>
              <div class="om-tab-body">
                <ObjectDirGraphPane :ds-id="dsId" :dir="selectedDir" />
              </div>
            </el-tab-pane>
          </el-tabs>
        </div>
      </div>
    </template>

    <!-- 挂载表对话框(数据源固定为当前已选,目录为当前选中目录) -->
    <ObjectTableMountDialog
      v-if="dsId && selectedDir"
      v-model="mountVisible"
      :datasource="currentDs"
      :dir-id="selectedDir.id"
      @done="(r) => onMutated(batchMsg('挂载', '表', r))"
    />

    <!-- 添加关系表对话框(relTarget=行内点「添加关系表」的挂载表) -->
    <ObjectTableRelDialog
      v-if="dsId && relTarget"
      v-model="relVisible"
      :datasource="currentDs"
      :object-table-id="relTarget.id"
      :table-name="relTarget.tableName"
      :db-name="relTarget.dbName"
      :schema-name="relTarget.schemaName"
      @done="(r) => onMutated(batchMsg('添加', '关系表', r))"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import { Delete, EditPen, Folder, Plus, Refresh } from '@element-plus/icons-vue'
import request, {
  getObjectCatalog,
  createObjectDir,
  renameObjectDir,
  deleteObjectDir,
  unmountObjectTable,
  deleteObjectTableRelation
} from '../api'
import DbTypeIcon from '../components/DbTypeIcon.vue'
import ObjectTableMountDialog from '../components/ObjectTableMountDialog.vue'
import ObjectTableRelDialog from '../components/ObjectTableRelDialog.vue'
import ObjectGraphCanvas from '../components/ObjectGraphCanvas.vue'
import ObjectDirGraphPane from '../components/ObjectDirGraphPane.vue'

// 对象管理(数据目录)页:按数据源维护「目录树 → 挂载表 → 关系表」三层数据目录。
// 整树一次拉取(GET object-catalog),所有增删改成功后整树重拉并保持当前选中目录;
// 布局:左侧目录树常驻,右侧三页签随选中目录实时联动——
//   列表(当前目录挂载表)/ 关系图(以选中目录为根的目录子图)/ 图谱(目录子树表之间的 ER 推导关系,只读)
const router = useRouter()

// ---------- 数据源选择 ----------
const datasources = ref([])
const dsLoading = ref(false)
const dsId = ref('')
const currentDs = computed(() => datasources.value.find((d) => String(d.id) === dsId.value))
// 多库方言(SQL Server/Kingbase)才展示「库」列,与选表面板/库列表页同口径
const isMultiDb = computed(() => ['SQLSERVER', 'KINGBASE'].includes(currentDs.value?.dbType))

async function loadDatasources() {
  dsLoading.value = true
  try {
    datasources.value = await request.get('/datasources')
  } finally {
    dsLoading.value = false
  }
}

/** 切换数据源:清空选中目录并重拉整树,树加载后自动选中第一个根目录(右栏随之加载) */
function onDsChange() {
  selectedDirId.value = ''
  catalog.value = []
  loadCatalog(true)
}

// ---------- 目录树数据 ----------
const catalog = ref([])
const loading = ref(false)
// 右侧页签:list=列表 / graph=关系图 / atlas=图谱
const activeTab = ref('list')
// 关系图布局方向:H=水平(compact-box LR,单侧向右,默认),V=垂直(compact-box TB,单侧向下),
// 设置工具栏用「垂直布局」checkbox 操作
const graphDirection = ref('H')
const verticalLayout = computed({
  get: () => graphDirection.value === 'V',
  set: (v) => { graphDirection.value = v ? 'V' : 'H' }
})
// 关系图节点显示开关:是否展示字段区、每表默认显示字段数、是否显示字段类型;
// 名字口径三档(表名与字段同规则):chinese=仅中文(默认,无注释回退英文)/english=仅英文/both=中英文同时显示(中文在前)
const showFields = ref(true)
const maxFields = ref(10)
const showFieldType = ref(false)
const fieldNameMode = ref('chinese')
const treeRef = ref(null)
const selectedDirId = ref('')

/** 递归按 id 找目录节点 */
function findDir(nodes, id) {
  for (const n of nodes || []) {
    if (String(n.id) === String(id)) return n
    const hit = findDir(n.children, id)
    if (hit) return hit
  }
  return null
}

const selectedDir = computed(() => (selectedDirId.value ? findDir(catalog.value, selectedDirId.value) : null))

/** 目录徽标计数:本目录及全部子目录挂载表总数 */
function countTables(dir) {
  let n = (dir.tables || []).length
  for (const c of dir.children || []) n += countTables(c)
  return n
}

/**
 * 拉取整树并恢复选中高亮
 * @param {boolean} autoSelectRoot 无选中目录时自动选中第一个根目录(切换数据源场景,右侧主内容直接可用)
 */
async function loadCatalog(autoSelectRoot = false) {
  if (!dsId.value) return
  loading.value = true
  try {
    // 接口返回虚拟根节点 ObjectDirNode(id=0),目录树渲染取其 children(顶层目录数组)
    const root = await getObjectCatalog(dsId.value)
    catalog.value = root && Array.isArray(root.children) ? root.children : []
    // 切换数据源场景:尚无选中目录且树非空,自动选中第一个根目录
    if (!selectedDirId.value && autoSelectRoot && catalog.value.length) {
      selectedDirId.value = catalog.value[0].id
    }
    // 重拉后选中目录可能已被删除:不存在则清空,存在则恢复树高亮
    if (selectedDirId.value && !findDir(catalog.value, selectedDirId.value)) {
      selectedDirId.value = ''
    }
    if (selectedDirId.value) {
      await nextTick()
      treeRef.value?.setCurrentKey(selectedDirId.value)
    }
  } finally {
    loading.value = false
  }
}

function onDirClick(data) {
  selectedDirId.value = data.id
}

// ---------- 目录增删改 ----------
/** 新建目录:parent 为空=根目录,否则为其子目录 */
async function createDir(parent) {
  if (!dsId.value) return
  const { value } = await ElMessageBox.prompt(
    parent ? `在「${parent.name}」下新建子目录` : '新建根目录',
    '新建目录',
    {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputPlaceholder: '请输入目录名称',
      inputValidator: (v) => (v && v.trim() ? true : '名称不能为空')
    }
  ).catch(() => ({ value: null }))
  const name = (value || '').trim()
  if (!name) return
  await createObjectDir({ datasourceId: currentDs.value?.id ?? dsId.value, parentId: parent ? parent.id : null, name })
  ElMessage.success('目录已创建')
  await loadCatalog()
}

async function renameDir(dir) {
  const { value } = await ElMessageBox.prompt('重命名目录', `当前名称:${dir.name}`, {
    confirmButtonText: '保存',
    cancelButtonText: '取消',
    inputValue: dir.name,
    inputValidator: (v) => (v && v.trim() ? true : '名称不能为空')
  }).catch(() => ({ value: null }))
  const name = (value || '').trim()
  if (!name || name === dir.name) return
  await renameObjectDir(dir.id, name)
  ElMessage.success('目录已重命名')
  await loadCatalog()
}

/** 删除响应的级联统计文案:按已知键拼装,缺字段时退化为通用提示(后端契约未固定键名) */
function formatDeleteStats(res) {
  if (!res || typeof res !== 'object') return '目录已删除'
  const parts = []
  if (res.dirs != null) parts.push(`目录 ${res.dirs} 个`)
  if (res.tables != null) parts.push(`挂载表 ${res.tables} 张`)
  if (res.relations != null) parts.push(`关系表 ${res.relations} 张`)
  return parts.length ? `已删除:${parts.join('、')}` : '目录已删除'
}

async function removeDir(dir) {
  const ok = await ElMessageBox.confirm(
    `删除目录「${dir.name}」将级联删除其全部子目录、挂载表与关系表,且不可恢复。确认删除?`,
    '删除目录',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  const res = await deleteObjectDir(dir.id)
  ElMessage.success(formatDeleteStats(res))
  await loadCatalog()
}

// ---------- 挂载表 / 关系表 ----------
const mountVisible = ref(false)
const relVisible = ref(false)
const relTarget = ref(null)

function openRelDialog(row) {
  relTarget.value = row
  relVisible.value = true
}

/** 对话框保存成功:统一提示 + 重拉整树 */
async function onMutated(text) {
  ElMessage.success(text)
  await loadCatalog()
}

/** 批量挂载/关系完成后的提示文案:已x动词 N 张xx,已存在跳过 M 张 */
function batchMsg(verb, noun, r) {
  const skipped = r?.existing ? `,${r.existing} 张已存在跳过` : ''
  return `已${verb} ${r?.mounted ?? 0} 张${noun}${skipped}`
}

async function unmount(row) {
  const ok = await ElMessageBox.confirm(
    `确认把表「${row.tableName}」从目录「${selectedDir.value?.name || ''}」取消挂载?其关系表会一并移除。`,
    '取消挂载',
    { type: 'warning', confirmButtonText: '取消挂载', cancelButtonText: '返回' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  await unmountObjectTable(row.id)
  ElMessage.success('已取消挂载')
  await loadCatalog()
}

async function removeRelation(rel) {
  const ok = await ElMessageBox.confirm(
    `确认删除关系表「${rel.tableName}」?`,
    '删除关系表',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  await deleteObjectTableRelation(rel.id)
  ElMessage.success('已删除关系表')
  await loadCatalog()
}

/** 跳该表字段明细页(同表列表页跳 TableDetail 的路由参数形态;未扫描不带 jobId) */
function goTable(row) {
  const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(row.schemaName)}/tables/${encodeURIComponent(row.tableName)}`
  router.push(row.dbName ? `${base}?db=${encodeURIComponent(row.dbName)}` : base)
}

onMounted(loadDatasources)
</script>

<style scoped>
/* 铺满 .main 可视高度(同 ER 关系页口径):flex 列布局,内部两栏各自滚动 */
.om-page {
  display: flex;
  flex-direction: column;
  height: calc(100% - 40px);
  box-sizing: border-box;
  overflow: hidden;
}
.toolbar-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
.toolbar-actions :deep(.el-button) {
  margin-left: 0;
}
.ds-option {
  display: flex;
  align-items: center;
  gap: 6px;
}
/* 目录树操作栏:刷新/新建根目录 */
.om-tree-head {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.om-tree-head .el-button {
  flex: 1;
  margin-left: 0;
}
/* 关系图画布顶部工具栏内的开关:清掉 el-checkbox 默认右间距,交给工具栏 gap */
.om-graph-pane .el-checkbox {
  margin-right: 0;
}
.om-setting-item {
  display: flex;
  align-items: center;
  gap: 6px;
}
.om-setting-item .el-input-number {
  width: 88px;
}
.om-setting-label {
  font-size: 12px;
  color: var(--el-text-color-regular);
}
/* 目录视图左右分栏 */
.om-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 16px;
}
.om-tree-pane {
  width: 300px;
  flex-shrink: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px;
  overflow: auto;
}
/* 右侧主区:三页签(列表/关系图/图谱),页签区 flex 纵向撑满、内容吃剩余高度(参考表详情页 er-fullheight 口径) */
.om-main-pane {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 0 12px 12px;
  display: flex;
  flex-direction: column;
}
.om-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.om-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.om-tabs :deep(.el-tab-pane) {
  height: 100%;
}
/* 页签内容体:纵向 flex,列表自滚动、画布吃满 */
.om-tab-body {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.om-tab-empty {
  margin: auto;
}
.om-table-pane {
  overflow: auto;
}
.om-pane-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.om-pane-title {
  font-size: 15px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.om-pane-sub {
  flex: 1;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.om-table {
  flex: 1;
}
/* 树节点:目录名 + 挂载表计数徽标,悬浮出操作按钮 */
.om-tree-node {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}
.om-tree-icon {
  color: var(--el-color-warning);
  flex-shrink: 0;
}
.om-tree-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.om-tree-count {
  flex-shrink: 0;
  min-width: 18px;
  padding: 0 5px;
  height: 16px;
  line-height: 16px;
  border-radius: 8px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  font-size: 11px;
  text-align: center;
}
.om-tree-ops {
  margin-left: auto;
  flex-shrink: 0;
  display: inline-flex;
  gap: 6px;
  visibility: hidden;
}
.om-tree-node:hover .om-tree-ops {
  visibility: visible;
}
.om-tree-ops .el-icon {
  color: var(--el-text-color-secondary);
}
.om-tree-ops .el-icon:hover {
  color: var(--el-color-primary);
}
/* 关系表展开行:留缩进,背景与主表区分 */
.om-rel-wrap {
  padding: 8px 16px 8px 48px;
  background: var(--el-fill-color-light);
}
.om-dash {
  color: var(--el-text-color-placeholder);
}
/* 操作列两个链接按钮保持一行,不换行 */
.om-table :deep(.om-op-cell .cell) {
  white-space: nowrap;
}
/* 关系图页签画布区:吃满页签内容高度 */
.om-graph-pane {
  overflow: hidden;
}
</style>
