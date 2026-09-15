<template>
  <div class="page-card om-page">
    <div class="toolbar">
      <h3 style="margin: 0">对象管理</h3>
      <div class="toolbar-actions">
        <DatasourceSelect v-model="dsId" :datasources="datasources" :loading="dsLoading" style="width: 220px" @change="onDsChange" />
      </div>
    </div>

    <!-- 空状态内嵌数据源选择:进入页面后在视线中心直接点选,不用挪到右上角 -->
    <el-empty v-if="!dsId" description="请选择数据源,查看和维护它的数据目录" :image-size="90">
      <DatasourceSelect v-model="dsId" :datasources="datasources" :loading="dsLoading" style="width: 280px" @change="onDsChange" />
    </el-empty>

    <template v-else>
      <!-- 左侧目录树常驻 + 右侧三页签(列表/关系图/图谱),页签内容随选中目录实时联动 -->
      <div class="om-body" v-loading="loading">
        <div class="om-tree-pane" :style="{ width: treeWidth + 'px' }">
          <!-- 树操作栏:刷新 + 新建根目录 + 展开/收起所有 -->
          <div class="om-tree-head">
            <el-button size="small" :icon="Refresh" :loading="loading" @click="loadCatalog()">刷新</el-button>
            <el-button size="small" type="primary" :icon="Plus" @click="createDir(null)">新建根目录</el-button>
            <el-button size="small" class="om-tree-head-icon" :icon="Expand" title="展开所有" @click="expandAllNodes" />
            <el-button size="small" class="om-tree-head-icon" :icon="Fold" title="收起所有" @click="collapseAllNodes" />
          </div>
          <el-input
            v-model="treeKeyword"
            size="small"
            clearable
            :prefix-icon="Search"
            placeholder="搜索目录/挂载表/关系表"
            class="om-tree-search"
          />
          <div class="om-tree-tip">拖动目录/挂载表可移动位置(拖到目录上=移入);关系表可拖到左侧表节点上</div>
          <el-tree
            ref="treeRef"
            :data="treeData"
            node-key="nodeKey"
            :props="{ children: 'children', label: 'label' }"
            highlight-current
            :expand-on-click-node="false"
            draggable
            :allow-drag="allowNodeDrag"
            :allow-drop="allowNodeDrop"
            :filter-node-method="filterTreeNode"
            @node-click="onNodeClick"
            @node-expand="(data) => expandedKeys.add(data.nodeKey)"
            @node-collapse="(data) => expandedKeys.delete(data.nodeKey)"
            @node-drop="onNodeDrop"
          >
            <template #default="{ node, data }">
              <!-- 表节点同时是关系表拖放目标(原生 DnD,与 el-tree 自带拖动互不干扰);关系表是叶子:不可点/不可拖/无操作 -->
              <div
                class="om-tree-node"
                :class="{ 'om-tree-node-table': data.nodeType === 'table', 'om-tree-node-rel': data.nodeType === 'rel', 'om-rel-drop': data.nodeKey === relDropKey }"
                @dragover="data.nodeType === 'table' ? onRelDragOver($event, data) : null"
                @dragleave="data.nodeType === 'table' ? onRelDragLeave($event) : null"
                @drop="data.nodeType === 'table' ? onTreeRelDrop($event, data) : null"
              >
                <el-icon class="om-tree-icon" :title="data.nodeType === 'dir' ? '展开/收起' : data.nodeType === 'table' ? '挂载表' : '关系表'"
                         @click.stop="data.nodeType === 'dir' ? toggleNode(node) : null">
                  <Folder v-if="data.nodeType === 'dir'" />
                  <Grid v-else />
                </el-icon>
                <span class="om-tree-label" :title="data.raw?.comment || data.label">{{ data.label }}</span>
                <span v-if="data.nodeType === 'dir'" class="om-tree-count" title="本目录及子目录挂载表总数(含关系表)">{{ countTables(data.raw) }}</span>
                <span v-else-if="data.nodeType === 'table'" class="om-tree-count" title="关系表数量">{{ (data.raw.relations || []).length }}</span>
                <span v-if="data.nodeType !== 'rel'" class="om-tree-ops" @click.stop>
                  <template v-if="data.nodeType === 'dir'">
                    <el-icon title="新增子目录" @click="createDir(data.raw)"><Plus /></el-icon>
                    <el-icon title="移动" @click="openMoveDir(data.raw)"><Rank /></el-icon>
                    <el-icon title="重命名" @click="renameDir(data.raw)"><EditPen /></el-icon>
                    <el-icon title="删除" @click="removeDir(data.raw)"><Delete /></el-icon>
                  </template>
                  <template v-else>
                    <el-icon title="添加关系表" @click="openRelDialog(data.raw)"><Plus /></el-icon>
                    <el-icon title="移动" @click="openMoveTable(data.raw)"><Rank /></el-icon>
                    <el-icon title="取消挂载" @click="unmount(data.raw)"><Delete /></el-icon>
                  </template>
                </span>
              </div>
            </template>
          </el-tree>
          <el-empty v-if="!loading && !catalog.length" description="暂无目录,点击上方「新建根目录」开始" :image-size="70" />
        </div>
        <!-- 宽度拖拽手柄:骑跨两栏缝隙,左右拖动调整目录树宽度(口径同全局侧边栏) -->
        <div class="om-tree-resizer" :class="{ resizing: treeResizing }" title="拖动调整宽度" @mousedown="startTreeResize" />
        <div class="om-main-pane">
          <!-- 选中挂载表:右侧直接为其关系表清单(点左侧目录则回到三页签) -->
          <div v-if="selectedTable" class="om-tab-body om-table-pane">
            <div class="om-pane-head">
              <span class="om-pane-title" :title="selectedTable.tableName">{{ selectedTable.tableName }}</span>
              <span class="om-pane-sub">
                {{ selectedTable.comment || '—' }}
                <template v-if="selectedTable.remark"> · {{ selectedTable.remark }}</template>
                · {{ (selectedTable.relations || []).length }} 张关系表
              </span>
              <el-button size="small" :icon="Rank" @click="openMoveTable(selectedTable)">移动</el-button>
              <el-button size="small" :icon="Plus" @click="openRelDialog(selectedTable)">添加关系表</el-button>
              <el-button size="small" type="danger" plain :icon="Delete" @click="unmount(selectedTable)">取消挂载</el-button>
            </div>
            <el-table :data="selectedTable.relations || []" border row-key="id" class="om-table">
              <el-table-column label="关系表" min-width="190" show-overflow-tooltip>
                <template #default="{ row: rel }">
                  <!-- 拖动把手:拖到左侧树中的挂载表节点上即移动 -->
                  <span class="om-rel-drag" draggable="true" title="拖到左侧树中的挂载表上即移动"
                        @dragstart="onRelDragStart($event, rel)">
                    <el-icon><Rank /></el-icon>
                  </span>
                  <el-link type="primary" @click="goTable(rel)">{{ rel.tableName }}</el-link>
                  <el-tag size="small" type="warning" effect="plain" style="margin-left: 6px">关系</el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="isMultiDb" label="库" min-width="110" show-overflow-tooltip>
                <template #default="{ row: rel }"><span v-if="rel.dbName">{{ rel.dbName }}</span><span v-else class="om-dash">-</span></template>
              </el-table-column>
              <el-table-column prop="schemaName" label="schema" min-width="110" show-overflow-tooltip />
              <el-table-column label="注释" min-width="150" show-overflow-tooltip>
                <template #default="{ row: rel }"><span v-if="rel.comment">{{ rel.comment }}</span><span v-else class="om-dash">-</span></template>
              </el-table-column>
              <el-table-column label="备注" min-width="150" show-overflow-tooltip>
                <template #default="{ row: rel }"><span v-if="rel.remark">{{ rel.remark }}</span><span v-else class="om-dash">-</span></template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row: rel }">
                  <el-button link type="primary" @click="openMoveRel(rel)">移动</el-button>
                  <el-button link type="danger" @click="removeRelation(rel)">删除</el-button>
                </template>
              </el-table-column>
              <template #empty>暂无关系表,点右上角「添加关系表」</template>
            </el-table>
          </div>
          <el-tabs v-else v-model="activeTab" class="om-tabs">
            <!-- 列表:当前目录直接挂载的表(行内展开关系表子表) -->
            <el-tab-pane label="列表" name="list">
              <div class="om-tab-body om-table-pane">
                <template v-if="selectedDir">
                  <div class="om-pane-head">
                    <span class="om-pane-title" :title="selectedDir.name">{{ selectedDir.name }}</span>
                    <span class="om-pane-sub">已挂载 {{ filteredDirTables.length }}/{{ (selectedDir.tables || []).length }} 张表</span>
                    <el-input
                      v-model="listKeyword"
                      size="small"
                      clearable
                      :prefix-icon="Search"
                      placeholder="按表名/注释/备注过滤(含关系表)"
                      style="width: 240px"
                    />
                    <el-button size="small" type="primary" :icon="Plus" @click="mountVisible = true">挂载表</el-button>
                  </div>
                  <el-table :data="filteredDirTables" border row-key="id" class="om-table">
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
                    <template #empty>{{ listKeyword.trim() ? '没有匹配的表' : '该目录还没有挂载表,点右上角「挂载表」添加' }}</template>
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

    <!-- 移动对话框(目录/挂载表/关系表统一的「变更所属」按钮入口,与树内拖动等价) -->
    <ObjectMoveDialog
      v-model="moveVisible"
      :mode="moveCtx?.mode"
      :catalog="catalog"
      :current-id="moveCtx?.currentId"
      :current-name="moveCtx?.currentName || ''"
      @confirm="onMoveConfirm"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import { Delete, EditPen, Expand, Fold, Folder, Grid, Plus, Rank, Refresh, Search } from '@element-plus/icons-vue'
import request, {
  getObjectCatalog,
  createObjectDir,
  renameObjectDir,
  sortObjectDirs,
  deleteObjectDir,
  moveObjectDir,
  unmountObjectTable,
  moveObjectTable,
  deleteObjectTableRelation,
  moveObjectRelation
} from '../api'
import DatasourceSelect from '../components/DatasourceSelect.vue'
import ObjectTableMountDialog from '../components/ObjectTableMountDialog.vue'
import ObjectTableRelDialog from '../components/ObjectTableRelDialog.vue'
import ObjectMoveDialog from '../components/ObjectMoveDialog.vue'
import ObjectGraphCanvas from '../components/ObjectGraphCanvas.vue'
import ObjectDirGraphPane from '../components/ObjectDirGraphPane.vue'

// 对象管理(数据目录)页:按数据源维护「目录树 → 挂载表 → 关系表」三层数据目录。
// 整树一次拉取(GET object-catalog),所有增删改成功后整树重拉并保持当前选中;
// 左侧树展示到挂载表这一级:点目录右侧是三页签(列表/关系图/图谱),点挂载表右侧直接是其关系表清单;
// 「变更所属」两个等价入口:树内拖动(目录/表拖到目录上=移入,目录同级拖动=重排 POST object-dirs/sort;
// 关系表从右侧清单拖到树上的挂载表节点=换所属挂载表)与节点/行上「移动」按钮弹窗(POST .../move);
// 目录同级顺序默认按创建时间(后端 sort_order 初值),移动落在新同级末尾,可再拖动重排
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

/** 切换数据源:清空选中目录/挂载表并重拉整树,树加载后自动选中第一个根目录(右栏随之加载) */
function onDsChange() {
  selectedDirId.value = ''
  selectedTableId.value = ''
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
// 选中的挂载表(树表节点):有值时右侧直接显示其关系表清单,优先级高于目录三页签
const selectedTableId = ref('')

// 目录树宽度(可拖拽调整,持久化;口径同 App.vue 全局侧边栏)
const TREE_MIN_WIDTH = 200
const TREE_MAX_WIDTH = 600
const treeWidth = ref(Number(localStorage.getItem('dq-om-tree-width')) || 240)
const treeResizing = ref(false)

/** 目录树宽度拖拽:命中手柄后全局跟踪 mousemove,松手持久化;拖拽期间禁止文本选中、统一光标 */
function startTreeResize(e) {
  e.preventDefault()
  const startX = e.clientX
  const startWidth = treeWidth.value
  treeResizing.value = true
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'col-resize'
  const onMove = (ev) => {
    treeWidth.value = Math.min(TREE_MAX_WIDTH, Math.max(TREE_MIN_WIDTH, startWidth + ev.clientX - startX))
  }
  const onUp = () => {
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
    document.body.style.userSelect = ''
    document.body.style.cursor = ''
    treeResizing.value = false
    localStorage.setItem('dq-om-tree-width', String(treeWidth.value))
  }
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
}

/** 递归按 id 找目录节点 */
function findDir(nodes, id) {
  for (const n of nodes || []) {
    if (String(n.id) === String(id)) return n
    const hit = findDir(n.children, id)
    if (hit) return hit
  }
  return null
}

/** 递归按 id 找挂载表(跨目录) */
function findTableIn(nodes, id) {
  for (const n of nodes || []) {
    const hit = (n.tables || []).find((t) => String(t.id) === String(id))
    if (hit) return hit
    const sub = findTableIn(n.children, id)
    if (sub) return sub
  }
  return null
}

const selectedDir = computed(() => (selectedDirId.value ? findDir(catalog.value, selectedDirId.value) : null))
const selectedTable = computed(() => (selectedTableId.value ? findTableIn(catalog.value, selectedTableId.value) : null))

// ---------- 列表页签关键字过滤 ----------
const listKeyword = ref('')
// 切换选中目录/挂载表时清空过滤词
watch(selectedDirId, () => (listKeyword.value = ''))
watch(selectedTableId, () => (listKeyword.value = ''))

/**
 * 列表过滤:表名/注释/备注命中即保留;关系表名/注释/备注命中也保留所在挂载行(便于定位归属),
 * 展开内容仍是该表全部关系表。无关键字=原列表
 */
const filteredDirTables = computed(() => {
  const kw = listKeyword.value.trim().toLowerCase()
  const tables = selectedDir.value?.tables || []
  if (!kw) return tables
  const hit = (s) => (s || '').toLowerCase().includes(kw)
  return tables.filter((t) =>
    hit(t.tableName) || hit(t.comment) || hit(t.remark) ||
    (t.relations || []).some((r) => hit(r.tableName) || hit(r.comment) || hit(r.remark)))
})

// ---------- 树展开状态:默认展开到第一级,重拉整树(拖动移动/增删改)后保持展开/收起不变 ----------
// el-tree 的 default-expand-all/default-expanded-keys 只在新数据初始化时生效且不可控,
// 展开状态自维护:node-expand/collapse 同步进集合,重拉后按集合恢复
const expandedKeys = ref(new Set())

/** 新数据源首次加载的默认展开范围:展开到第 maxLevel 级目录(更深的层级收起) */
function defaultExpandedKeysOf(dirs, maxLevel) {
  if (maxLevel < 1) return []
  const keys = []
  for (const d of dirs || []) {
    keys.push(`d-${d.id}`)
    keys.push(...defaultExpandedKeysOf(d.children, maxLevel - 1))
  }
  return keys
}

/** 重拉整树后恢复展开状态(el-tree 新数据节点默认全收起,只把集合内的展开) */
async function applyExpandState() {
  await nextTick()
  const nodesMap = treeRef.value?.store?.nodesMap
  if (!nodesMap) return
  for (const key of expandedKeys.value) nodesMap[key]?.expand()
}

// ---------- 树节点组装(目录 → 子目录 + 挂载表 → 关系表叶子三级) ----------
/**
 * 原始目录(接口口径)转 el-tree 节点:目录 children = 子目录节点在前 + 挂载表节点在后,
 * 挂载表 children = 其登记的关系表叶子(不可点/不可拖);nodeKey 带 d-/t-/r- 前缀避免 id 撞键
 */
function toTreeNode(dir) {
  const dirNodes = (dir.children || []).map(toTreeNode)
  const tableNodes = (dir.tables || []).map((t) => ({
    nodeType: 'table',
    id: t.id,
    nodeKey: `t-${t.id}`,
    label: t.tableName,
    raw: t,
    children: (t.relations || []).map((r) => ({
      nodeType: 'rel',
      id: r.id,
      nodeKey: `r-${r.id}`,
      label: r.tableName,
      raw: r
    }))
  }))
  return {
    nodeType: 'dir',
    id: dir.id,
    nodeKey: `d-${dir.id}`,
    label: dir.name,
    raw: dir,
    children: [...dirNodes, ...tableNodes]
  }
}

const treeData = computed(() => (catalog.value || []).map(toTreeNode))

// ---------- 树搜索:关键字过滤(命中节点与其祖先路径可见),清空后还原展开状态 ----------
const treeKeyword = ref('')

/** el-tree 过滤:节点名或注释命中即保留;目录无命中但子孙有命中时 el-tree 会自动保留该目录 */
function filterTreeNode(value, data) {
  const kw = (value || '').trim().toLowerCase()
  if (!kw) return true
  return data.label.toLowerCase().includes(kw) || (data.raw?.comment || '').toLowerCase().includes(kw)
}

watch(treeKeyword, (kw) => {
  treeRef.value?.filter(kw)
  if (kw.trim()) {
    // 过滤时展开全部,命中的深层节点才看得见
    const nodesMap = treeRef.value?.store?.nodesMap || {}
    Object.values(nodesMap).forEach((n) => n.expand())
  } else {
    // 清空:还原原本的展开/收起状态
    applyExpandState()
  }
})

/** 展开所有:全部目录节点展开,并把目录 key 同步进展开状态集合(后续重拉保持) */
function expandAllNodes() {
  const nodesMap = treeRef.value?.store?.nodesMap || {}
  for (const [key, n] of Object.entries(nodesMap)) {
    n.expand()
    if (n.data?.nodeType === 'dir') expandedKeys.value.add(key)
  }
}

/** 收起所有:清空展开状态集合并收起全部目录节点 */
function collapseAllNodes() {
  expandedKeys.value = new Set()
  const nodesMap = treeRef.value?.store?.nodesMap || {}
  Object.values(nodesMap).forEach((n) => n.collapse())
}

/** 目录徽标计数:本目录及全部子目录挂载表总数(含各挂载表下登记的关系表) */
function countTables(dir) {
  let n = 0
  for (const t of dir.tables || []) n += 1 + (t.relations || []).length
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
    // 切换数据源/首次进入:展开状态重置为默认一级;刷新与增删改后的重拉则保持原展开/收起不变
    if (autoSelectRoot) expandedKeys.value = new Set(defaultExpandedKeysOf(catalog.value, 1))
    // 切换数据源场景:尚无选中且树非空,自动选中第一个根目录
    if (!selectedDirId.value && !selectedTableId.value && autoSelectRoot && catalog.value.length) {
      selectedDirId.value = catalog.value[0].id
    }
    // 重拉后选中目录/挂载表可能已被删除:不存在则清空,存在则恢复树高亮
    if (selectedDirId.value && !findDir(catalog.value, selectedDirId.value)) {
      selectedDirId.value = ''
    }
    if (selectedTableId.value && !findTableIn(catalog.value, selectedTableId.value)) {
      selectedTableId.value = ''
    }
    await applyExpandState()
    const curKey = selectedTableId.value ? `t-${selectedTableId.value}` : selectedDirId.value ? `d-${selectedDirId.value}` : null
    if (curKey) {
      treeRef.value?.setCurrentKey(curKey)
    }
  } finally {
    loading.value = false
  }
}

/** 点目录=右侧三页签;点挂载表=右侧直接显示其关系表清单;关系表不可点(恢复之前的高亮) */
function onNodeClick(data) {
  if (data.nodeType === 'rel') {
    treeRef.value?.setCurrentKey(selectedTableId.value ? `t-${selectedTableId.value}` : selectedDirId.value ? `d-${selectedDirId.value}` : null)
    return
  }
  if (data.nodeType === 'table') {
    selectedTableId.value = data.id
  } else {
    selectedTableId.value = ''
    selectedDirId.value = data.id
  }
}

/** 点击文件夹图标展开/收起该目录(等价于点击三角箭头,不触发选中) */
function toggleNode(node) {
  if (node.expanded) node.collapse()
  else node.expand()
}

// ---------- 树内拖动:同级排序 + 跨层级移动 ----------
/** 关系表叶子不可拖动(其移动走右侧清单拖到表节点) */
function allowNodeDrag(node) {
  return node.data?.nodeType !== 'rel'
}

/**
 * 拖放约束:
 * - 拖挂载表:只允许落到目录上(inner)=移动到该目录
 * - 拖目录:inner 落到目录上=移动到该目录(排除自身与子孙);before/after 仅同级目录之间=排序
 */
function allowNodeDrop(draggingNode, dropNode, type) {
  const drag = draggingNode.data
  const drop = dropNode.data
  if (drag.nodeType === 'table') {
    return type === 'inner' && drop.nodeType === 'dir'
  }
  if (type === 'inner') {
    if (drop.nodeType !== 'dir' || drop.id === drag.id) return false
    // 目标在自身子孙下会形成环,禁止(后端同样 400)
    let p = dropNode.parent
    while (p && p.level > 0) {
      if (p.data?.nodeType === 'dir' && p.data.id === drag.id) return false
      p = p.parent
    }
    return true
  }
  return drop.nodeType === 'dir' && draggingNode.parent === dropNode.parent
}

/**
 * 树内拖放完成:目录/挂载表落目录上=调移动接口;同级目录前后插入=按服务端口径整表重排
 * (el-tree 已就地改好本地顺序,提交失败与成功都重拉整树,以服务端口径确认/回滚)。
 */
async function onNodeDrop(draggingNode, dropNode, dropType) {
  const drag = draggingNode.data
  try {
    if (drag.nodeType === 'table') {
      await moveObjectTable(drag.id, dropNode.data.id)
      ElMessage.success('已移动挂载表')
    } else if (dropType === 'inner') {
      await moveObjectDir(drag.id, dropNode.data.id)
      ElMessage.success('已移动目录')
    } else {
      const parent = dropNode.parent
      const parentId = parent?.level === 0 ? 0 : parent?.data?.id
      // 该父级 children 里混有挂载表节点,排序只收集目录节点
      const orderedIds = (parent?.childNodes || []).filter((n) => n.data.nodeType === 'dir').map((n) => n.data.id)
      if (orderedIds.length) {
        await sortObjectDirs({ datasourceId: currentDs.value?.id ?? dsId.value, parentId, orderedIds })
      }
    }
  } catch {
    // 提交失败(request 层已提示错误):交给下方重拉整树,按服务端口径回滚本地状态
  } finally {
    await loadCatalog()
  }
}

// ---------- 关系表拖动(右侧清单 → 树上挂载表节点) ----------
// 自定义 MIME,避免与 el-tree 自带拖动(text/plain)混淆;dragover 阶段只能读 types
const REL_DRAG_TYPE = 'application/x-dq-object-rel'
// 关系表拖动时悬停的表节点 key,用于落点高亮
const relDropKey = ref('')

function onRelDragStart(e, rel) {
  e.dataTransfer.setData(REL_DRAG_TYPE, String(rel.id))
  e.dataTransfer.effectAllowed = 'move'
}

function onRelDragOver(e, data) {
  if (e.dataTransfer?.types?.includes(REL_DRAG_TYPE)) {
    e.preventDefault()
    e.dataTransfer.dropEffect = 'move'
    relDropKey.value = data.nodeKey
  }
}

/** 离开节点(含其子元素不算离开)时取消高亮 */
function onRelDragLeave(e) {
  if (!e.currentTarget.contains(e.relatedTarget)) relDropKey.value = ''
}

/** 关系表落到树中挂载表节点:变更所属挂载表 */
async function onTreeRelDrop(e, data) {
  const relId = e.dataTransfer?.getData(REL_DRAG_TYPE)
  relDropKey.value = ''
  if (!relId) return
  e.preventDefault()
  e.stopPropagation()
  try {
    await moveObjectRelation(Number(relId), data.id)
    ElMessage.success('已移动关系表')
  } catch {
    // 失败已由 request 层提示
  } finally {
    await loadCatalog()
  }
}

// ---------- 移动对话框(目录/挂载表/关系表统一的「变更所属」按钮入口) ----------
const moveVisible = ref(false)
// { mode: 'dir'|'table'|'rel', currentId, currentName, relId(rel 模式专用,要移动的关系记录 id) }
const moveCtx = ref(null)

function openMoveDir(dir) {
  moveCtx.value = { mode: 'dir', currentId: dir.id, currentName: dir.name }
  moveVisible.value = true
}

/** table 模式 tableId=要移动的挂载记录 id,currentId=所在目录 id(弹窗内禁选) */
function openMoveTable(table) {
  moveCtx.value = { mode: 'table', tableId: table.id, currentId: table.dirId, currentName: table.tableName }
  moveVisible.value = true
}

/** rel 模式 currentId=当前所属挂载表 id(弹窗内禁选),relId=关系记录自身 id */
function openMoveRel(rel) {
  moveCtx.value = { mode: 'rel', currentId: selectedTableId.value, currentName: rel.tableName, relId: rel.id }
  moveVisible.value = true
}

async function onMoveConfirm(target) {
  const ctx = moveCtx.value
  moveVisible.value = false
  if (!ctx) return
  try {
    if (ctx.mode === 'dir') {
      await moveObjectDir(ctx.currentId, target || 0)
      ElMessage.success('已移动目录')
    } else if (ctx.mode === 'table') {
      await moveObjectTable(ctx.tableId, target)
      ElMessage.success('已移动挂载表')
    } else {
      await moveObjectRelation(ctx.relId, target)
      ElMessage.success('已移动关系表')
    }
  } catch {
    // 失败已由 request 层提示
  } finally {
    await loadCatalog()
  }
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
  const dirName = findDir(catalog.value, row.dirId)?.name || ''
  const ok = await ElMessageBox.confirm(
    `确认把表「${row.tableName}」从目录「${dirName}」取消挂载?其关系表会一并移除。`,
    '取消挂载',
    { type: 'warning', confirmButtonText: '取消挂载', cancelButtonText: '返回' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  await unmountObjectTable(row.id)
  // 取消挂载的是当前右侧展示的表时,清空其选中避免悬空
  if (String(selectedTableId.value) === String(row.id)) selectedTableId.value = ''
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
/* 展开/收起所有为纯图标按钮,不拉伸 */
.om-tree-head .om-tree-head-icon {
  flex: 0 0 auto;
}
/* 拖动排序提示:说明目录顺序口径,提升可发现性 */
.om-tree-tip {
  margin: 0 0 6px;
  font-size: 11px;
  line-height: 16px;
  color: var(--el-text-color-placeholder);
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
/* 目录视图左右分栏:缝隙即拖拽手柄命中区,不再用 gap */
.om-body {
  flex: 1;
  min-height: 0;
  display: flex;
}
.om-tree-pane {
  flex-shrink: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px;
  overflow: auto;
}
/* 目录树宽度拖拽手柄:占满两栏缝隙,悬停/拖拽时中间竖条高亮(口径同 .sidebar-resizer) */
.om-tree-resizer {
  flex-shrink: 0;
  width: 16px;
  cursor: col-resize;
  position: relative;
}
.om-tree-resizer::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 7px;
  width: 2px;
  border-radius: 1px;
  background: transparent;
}
.om-tree-resizer:hover::after,
.om-tree-resizer.resizing::after {
  background: var(--el-color-primary-light-7);
}
html.dark .om-tree-resizer:hover::after,
html.dark .om-tree-resizer.resizing::after {
  background: var(--el-color-primary-dark-2);
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
/* 树节点:目录名 + 挂载表计数徽标,悬浮出操作按钮;行高固定(26px),节点吃满整行以便落点高亮覆盖整行 */
.om-tree-node {
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
}
.om-tree-icon {
  color: var(--el-color-warning);
  flex-shrink: 0;
  cursor: pointer;
}
/* 挂载表节点图标用主色,与目录文件夹区分 */
.om-tree-node-table .om-tree-icon {
  color: var(--el-color-primary);
}
/* 关系表叶子:弱色图标 + 默认光标,视觉上表达「不可点」 */
.om-tree-node-rel {
  cursor: default;
}
.om-tree-node-rel .om-tree-icon {
  color: var(--el-text-color-secondary);
}
/* 树搜索框与下方树留间距 */
.om-tree-search {
  margin-bottom: 6px;
}
/* 树内拖动落点高亮(el-tree 拖到目录上=inner 时给目标节点加 is-drop-inner):
   内置样式只给 .el-tree-node__label 上色,自定义节点模板没有该元素会落空,这里补整行高亮 */
.om-tree-pane :deep(.el-tree-node.is-drop-inner > .el-tree-node__content) {
  background-color: var(--el-color-primary-light-8);
  box-shadow: inset 0 0 0 1px var(--el-color-primary);
  border-radius: 4px;
}
/* 同级排序落点(before/after)内置有 1px 插入线,加粗加圆角更醒目 */
.om-tree-pane :deep(.el-tree__drop-indicator) {
  height: 2px;
  background-color: var(--el-color-primary);
}
/* 关系表拖动悬停的挂载表节点:同口径整行高亮 */
.om-tree-node.om-rel-drop {
  background-color: var(--el-color-primary-light-8);
  box-shadow: inset 0 0 0 1px var(--el-color-primary);
  border-radius: 4px;
}
/* 关系表拖动把手:拖到左侧树中的挂载表节点上即移动 */
.om-rel-drag {
  display: inline-flex;
  vertical-align: middle;
  margin-right: 6px;
  cursor: grab;
  color: var(--el-text-color-secondary);
}
.om-rel-drag:active {
  cursor: grabbing;
}
.om-rel-drag:hover {
  color: var(--el-color-primary);
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
