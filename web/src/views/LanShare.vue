<template>
  <div class="page-card lan-share-page">
    <div class="toolbar">
      <h2>局域网共享</h2>
    </div>

    <el-card class="settings-card" shadow="never">
      <template #header>本机共享</template>
      <div class="settings-desc">
        <ol>
          <li>开启后,本机通过 UDP 广播(端口 {{ status?.discoveryPort ?? '-' }})自动发现同一局域网内运行本软件的其他电脑,并可互相拉取标记、表描述与扫描记录。</li>
          <li>共享出口无鉴权,仅适合可信内网使用。</li>
        </ol>
      </div>
      <el-form label-width="140px">
        <el-form-item label="启用局域网共享">
          <el-switch v-model="form.enabled" :loading="saving" @change="onEnabledChange" />
          <span v-if="status" class="lan-running-hint" :class="{ off: !status.running }">
            {{ status.running ? '发现服务运行中' : '发现服务未运行' }}
          </span>
        </el-form-item>
        <el-form-item label="实例名称">
          <el-input
            v-model="form.instanceName"
            maxlength="128"
            show-word-limit
            :placeholder="status ? `默认:${status.instanceName}` : '默认使用计算机名'"
            style="max-width: 360px"
          />
        </el-form-item>
        <el-form-item label="本机地址">
          <div class="lan-self-info">
            <template v-if="status && status.addresses && status.addresses.length">
              <el-tag
                v-for="a in status.addresses" :key="a.address"
                class="lan-addr-tag" title="点击复制"
                @click="copyAddr(`${a.address}:${status.httpPort}`)"
              ><span v-if="a.iface" class="lan-addr-iface">{{ a.iface }}</span>{{ a.address }}:{{ status.httpPort }}</el-tag>
            </template>
            <span v-else>-</span>
            <span class="lan-addr-hint">点击复制;对方广播发现不到本机时,把地址发给同事在「在线实例」右上角手动添加</span>
          </div>
        </el-form-item>
      </el-form>
      <div class="card-actions">
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </el-card>

    <!-- 共享未启用时整卡隐藏,不与上面的「本机共享」设置混淆 -->
    <el-card v-if="status?.enabled" class="settings-card" shadow="never">
      <template #header>
        <div class="lan-peers-header">
          <span>在线实例</span>
          <div class="lan-manual-add">
            <el-input
              v-model="manualAddr"
              placeholder="手动添加:IP 或 IP:端口(默认 10000)"
              size="small"
              style="width: 280px"
              clearable
              @keyup.enter="addManual"
            />
            <el-button size="small" type="primary" :loading="addingManual" @click="addManual">添加</el-button>
            <el-button :icon="Refresh" text circle title="刷新" @click="loadPeers" />
          </div>
        </div>
      </template>
      <div class="settings-desc">
        <ol>
          <li>每 5 秒自动刷新;超过 15 秒未收到心跳的实例判离线。</li>
          <li>UDP 广播跨不了子网/VLAN、部分网络存在 AP 隔离,广播发现不到时可按对方 IP 手动添加(走 HTTP 直连探测)。</li>
          <li>「同步标记与描述」拉取对方的标记定义、表级打标、表描述与表所属系统。</li>
          <li>「导入扫描记录」在标注数据之外额外拉取对方扫描记录(数据源按名称自动匹配本机,匹配不到的任务跳过)。</li>
          <li>两者都会先预览对方数据,由你勾选后再同步。</li>
        </ol>
      </div>
      <el-table :data="peers" v-loading="loadingPeers" border>
        <el-table-column prop="instanceName" label="实例名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="地址" min-width="190">
          <template #default="{ row }">
            {{ row.host }}:{{ row.httpPort }}
            <el-tag v-if="row.manual" size="small" type="warning" class="lan-manual-tag">手动</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="appVersion" label="版本" width="100">
          <template #default="{ row }">{{ row.appVersion || '-' }}</template>
        </el-table-column>
        <el-table-column label="最后心跳" width="200">
          <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openPreview(row, 'annotations')">同步标记与描述</el-button>
            <el-button size="small" type="primary" @click="openPreview(row, 'all')">导入扫描记录</el-button>
            <el-button v-if="row.manual" size="small" type="danger" text @click="removeManual(row)">移除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <span v-if="status && !status.running">发现服务未运行,请检查端口 {{ status.discoveryPort }} 是否被占用</span>
          <span v-else>暂未发现其他实例(确认对方已开启共享;广播不可用的网络可在右上角手动添加)</span>
        </template>
      </el-table>
    </el-card>

    <!-- 同步前预览与勾选弹窗:两个操作共用;annotations 模式只显示标注段,all 模式含扫描任务段 -->
    <el-dialog
      v-model="previewVisible"
      :title="previewMode === 'all' ? `导入扫描记录 — ${previewPeerName}` : `同步标记与描述 — ${previewPeerName}`"
      width="min(1100px, 94vw)"
      top="3vh"
      :close-on-click-modal="false"
    >
      <div v-loading="previewLoading" class="lan-preview-body">
        <template v-if="preview">
          <div class="lan-preview-section-title">标注数据</div>
          <div class="lan-preview-group">
            <div class="lan-preview-group-head">
              <el-checkbox v-model="tagCheckAll" :indeterminate="tagIndeterminate" @change="onTagCheckAll">
                标记定义(已选 {{ selectedTagNames.length }}/{{ preview.annotations.tags.length }})
              </el-checkbox>
            </div>
            <el-checkbox-group v-model="selectedTagNames">
              <div v-for="t in preview.annotations.tags" :key="t.name" class="lan-tag-item">
                <el-checkbox :value="t.name">
                  <span class="lan-tag-dot" :style="{ background: t.color || '#909399' }"></span>
                  {{ t.name }}
                  <span class="lan-tag-type">{{ tagTypeText(t.tagType) }}</span>
                  <span v-if="t.description" class="lan-tag-desc">{{ t.description }}</span>
                </el-checkbox>
              </div>
              <div v-if="!preview.annotations.tags.length" class="lan-empty-hint">对方没有标记定义</div>
            </el-checkbox-group>
          </div>
          <div class="lan-preview-group lan-preview-cats">
            <div class="lan-cat-row">
              <el-checkbox v-model="sel.includeTableTags">
                表级打标({{ preview.annotations.tableTags.length }} 条,仅同步被勾选标记的关系)
              </el-checkbox>
              <el-button text type="primary" size="small" @click="openDetail('tableTags')">明细</el-button>
            </div>
            <div class="lan-cat-row">
              <el-checkbox v-model="sel.includeDocs">
                表描述({{ preview.annotations.tableDocs.length }} 条)
              </el-checkbox>
              <el-button text type="primary" size="small" @click="openDetail('tableDocs')">明细</el-button>
            </div>
          </div>
          <div v-if="preview.annotations.datasources.length" class="lan-ds-hint">
            涉及数据源:<span v-for="d in preview.annotations.datasources" :key="d.datasourceName" class="lan-ds-chip">
              {{ d.datasourceName }}(打标 {{ d.tableTags }} / 描述 {{ d.tableDocs }} / 系统 {{ d.tableSystems }})
            </span>
          </div>

          <!-- 数据源映射:对方数据源与本机数据源的对应(勾选才同步,默认全选;同名预填;无对应可新建无密码副本);
               all 模式随扫描记录勾选动态变化(只列被勾选任务涉及的数据源),annotations 模式按标注涉及数据源 -->
          <template v-if="involvedDatasources.length">
            <div class="lan-preview-section-title">数据源映射</div>
            <el-table
              ref="dsMapTableRef"
              :data="involvedDatasources"
              size="small"
              border
              max-height="260"
              class="lan-ds-map-table"
              @selection-change="(rows) => (selectedDsNames = rows.map((r) => r.peerName))"
            >
              <el-table-column type="selection" width="42" />
              <el-table-column label="对方数据源" min-width="200">
                <template #default="{ row }">
                  <div>{{ row.peerName }}</div>
                  <div class="lan-ds-url">{{ row.jdbcUrl }}</div>
                </template>
              </el-table-column>
              <el-table-column label="映射到本机" min-width="220">
                <template #default="{ row }">
                  <el-select
                    v-model="dsChoices[row.peerName]"
                    size="small"
                    style="width: 100%"
                    :disabled="!selectedDsNames.includes(row.peerName)"
                  >
                    <el-option
                      v-for="l in preview.localDatasources" :key="l.id" :value="l.id"
                      :label="`现有:${l.name}`"
                    />
                    <el-option value="__new__" label="新建到本机(同步连接信息,与导入导出口径一致)" />
                  </el-select>
                </template>
              </el-table-column>
            </el-table>
            <div class="lan-ds-hint">勾选的数据源才会同步;「新建到本机」同步名称/连接地址/用户名/密码(密码以密文传输,与数据源导出文件同一口径);取消勾选则该数据源下的打标/描述/扫描记录都不导入。</div>
          </template>

          <template v-if="previewMode === 'all'">
            <div class="lan-preview-section-title">扫描记录({{ preview.scanJobs.length }} 条)</div>
            <el-table
              ref="jobTableRef"
              :data="preview.scanJobs"
              border
              size="small"
              max-height="280"
              @selection-change="(rows) => (selectedJobIds = rows.map((r) => r.jobId))"
            >
              <el-table-column type="selection" width="42" />
              <el-table-column prop="datasourceName" label="数据源" min-width="110" show-overflow-tooltip />
              <el-table-column prop="schemaName" label="库/Schema" min-width="100" show-overflow-tooltip />
              <el-table-column label="状态" width="80">
                <template #default="{ row }">{{ statusText(row.status) }}</template>
              </el-table-column>
              <el-table-column label="表" width="76">
                <template #default="{ row }">{{ row.doneTables }}/{{ row.totalTables }}</template>
              </el-table-column>
              <el-table-column prop="createdAt" label="创建时间" width="150">
                <template #default="{ row }">{{ (row.createdAt || '-').replace('T', ' ').slice(0, 19) }}</template>
              </el-table-column>
              <template #empty>对方没有扫描记录</template>
            </el-table>
          </template>
        </template>
        <el-empty v-else-if="!previewLoading" description="加载预览失败" />
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!canSubmit" :loading="pulling" @click="submitPull">
          开始拉取
        </el-button>
      </template>
    </el-dialog>

    <!-- 标注明细弹窗(表级打标/表描述,表头固定) -->
    <el-dialog
      v-model="detailVisible"
      :title="detailType === 'tableTags' ? '表级打标明细' : '表描述明细'"
      width="min(900px, 90vw)"
      append-to-body
    >
      <el-table
        v-if="detailType === 'tableTags'"
        :data="preview?.annotations.tableTags || []"
        size="small"
        border
        max-height="60vh"
      >
        <el-table-column label="表" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.schemaName }}.{{ row.tableName }}</template>
        </el-table-column>
        <el-table-column label="标记" width="150">
          <template #default="{ row }">
            <span :class="{ 'lan-row-dim': !selectedTagNames.includes(row.tagName) }">
              {{ row.tagName }}
              <span v-if="!selectedTagNames.includes(row.tagName)" class="lan-row-skip">(不同步)</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="datasourceName" label="数据源" min-width="110" show-overflow-tooltip />
        <template #empty>无表级打标</template>
      </el-table>
      <el-table
        v-else
        :data="preview?.annotations.tableDocs || []"
        size="small"
        border
        max-height="60vh"
      >
        <el-table-column label="表" width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.schemaName }}.{{ row.tableName }}</template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip />
        <template #empty>无表描述</template>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import { Refresh } from '@element-plus/icons-vue'
import request from '../api'
import { notifyDsListChanged } from '../utils/dsListChanged'

const status = ref(null)
const form = reactive({ enabled: true, instanceName: '' })
const saving = ref(false)
const peers = ref([])
const loadingPeers = ref(false)
// 手动添加实例(广播发现不可用的网络按地址直连)
const manualAddr = ref('')
const addingManual = ref(false)
let timer = null
let mounted = false

// ---- 预览与勾选弹窗 ----
const previewVisible = ref(false)
const previewLoading = ref(false)
const preview = ref(null)
const previewMode = ref('annotations') // annotations | all
const previewPeerRow = ref(null)
const selectedTagNames = ref([])
const sel = reactive({ includeTableTags: true, includeDocs: true, includeSystems: true })
// 标注明细弹窗(表级打标/表描述)
const detailVisible = ref(false)
const detailType = ref('tableTags')
const selectedJobIds = ref([])
const jobTableRef = ref(null)
const pulling = ref(false)
// 数据源映射选择:对方数据源名 → '__new__'(新建无密码副本) / 本机数据源 id;未勾选的行整体跳过
const dsChoices = reactive({})
const dsMapTableRef = ref(null)
// 数据源映射表勾选状态(默认全选,未勾选的不同步)
const selectedDsNames = ref([])

const previewPeerName = computed(() => previewPeerRow.value?.instanceName || '')

/**
 * 映射表涉及的对方数据源,按模式取口径:
 * annotations 模式 = 标注数据涉及的数据源;all 模式 = 被勾选扫描记录涉及的数据源(随勾选动态变化)。
 * 映射表逐行由用户确认。
 */
const involvedDatasources = computed(() => {
  if (!preview.value) return []
  const names = new Set()
  if (previewMode.value === 'all') {
    const picked = new Set(selectedJobIds.value)
    ;(preview.value.scanJobs || []).forEach((j) => { if (picked.has(j.jobId)) names.add(j.datasourceName) })
  } else {
    ;(preview.value.annotations.datasources || []).forEach((d) => names.add(d.datasourceName))
  }
  const byName = new Map((preview.value.datasources || []).map((d) => [d.peerName, d]))
  const localByName = new Map((preview.value.localDatasources || []).map((l) => [l.name, l.id]))
  return [...names].map((name) => {
    const item = byName.get(name)
    return {
      peerName: name,
      jdbcUrl: item?.jdbcUrl || '',
      username: item?.username || '',
      matchedLocalId: item?.matchedLocalId ?? localByName.get(name) ?? null
    }
  })
})

// 映射行动态增删:新出现的行补默认映射(同名 > 新建)并默认勾选;消失的行选择状态随表格数据自动失效
watch(involvedDatasources, async (list) => {
  list.forEach((d) => {
    if (!(d.peerName in dsChoices)) dsChoices[d.peerName] = d.matchedLocalId ?? '__new__'
  })
  await nextTick()
  const table = dsMapTableRef.value
  if (!table) return
  list.forEach((d) => {
    if (!selectedDsNames.value.includes(d.peerName)) table.toggleRowSelection(d, true)
  })
})

/** 打开标注明细弹窗(表级打标/表描述) */
function openDetail(type) {
  detailType.value = type
  detailVisible.value = true
}
const tagCheckAll = computed({
  get: () => preview.value && selectedTagNames.value.length === preview.value.annotations.tags.length && preview.value.annotations.tags.length > 0,
  set: () => {}
})
const tagIndeterminate = computed(() =>
  preview.value && selectedTagNames.value.length > 0 && selectedTagNames.value.length < preview.value.annotations.tags.length)

const canSubmit = computed(() => {
  if (!preview.value) return false
  const annPicked = selectedTagNames.value.length > 0 || sel.includeTableTags || sel.includeDocs || sel.includeSystems
  if (previewMode.value === 'all') return annPicked || selectedJobIds.value.length > 0
  return annPicked
})

function onTagCheckAll(checked) {
  selectedTagNames.value = checked ? (preview.value?.annotations.tags || []).map((t) => t.name) : []
}

function tagTypeText(t) {
  return { AI: '可用于AI打标', MANUAL: '仅用于人工打标', SYSTEM: '系统' }[t] || '可用于AI打标'
}

function statusText(s) {
  return { DONE: '完成', FAILED: '失败', RUNNING: '运行中', CANCELED: '已取消', INTERRUPTED: '已中断', PENDING: '待运行' }[s] || s
}

async function load() {
  try {
    status.value = await request.get('/lan/status')
    form.enabled = status.value.enabled
    // 实例名留空表示用默认(主机名),不回显生效值避免用户误存
  } catch { /* 错误提示由拦截器统一弹出 */ }
}

async function loadPeers() {
  // 共享未启用时在线实例卡整体隐藏,不再请求;并清空旧列表避免重开瞬间闪出过期数据
  if (status.value && !status.value.enabled) {
    peers.value = []
    return
  }
  loadingPeers.value = true
  try {
    peers.value = await request.get('/lan/peers')
  } catch { /* 轮询失败维持旧列表 */ } finally {
    loadingPeers.value = false
  }
}

async function save() {
  saving.value = true
  try {
    status.value = await request.put('/lan/settings', {
      enabled: form.enabled,
      instanceName: form.instanceName?.trim() || null
    })
    form.enabled = status.value.enabled
    ElMessage.success('已保存')
  } catch { /* 拦截器已提示 */ } finally {
    saving.value = false
  }
}

/** 开关即时生效:切换即保存(不动实例名),失败回滚开关 */
let switchReverting = false
async function onEnabledChange(val) {
  if (switchReverting) return // 回滚触发的 change 不再发请求
  saving.value = true
  try {
    status.value = await request.put('/lan/settings', { enabled: val, instanceName: null })
    form.enabled = status.value.enabled
    ElMessage.success(val ? '已开启局域网共享' : '已关闭局域网共享')
    if (val) loadPeers() // 开启后立即拉一次,不等下一个 5s 轮询周期
  } catch {
    switchReverting = true
    form.enabled = !val
    nextTick(() => { switchReverting = false })
  } finally {
    saving.value = false
  }
}

/** 手动添加实例:解析「IP[:端口]」,服务端直连探测成功才入库 */
async function addManual() {
  const raw = manualAddr.value.trim()
  if (!raw) {
    ElMessage.warning('请输入对方地址(IP 或 IP:端口)')
    return
  }
  let host = raw
  let port = null
  const idx = raw.lastIndexOf(':')
  if (idx > 0) {
    host = raw.slice(0, idx)
    const p = Number(raw.slice(idx + 1))
    if (!Number.isInteger(p) || p < 1 || p > 65535) {
      ElMessage.warning('端口不正确')
      return
    }
    port = p
  }
  addingManual.value = true
  try {
    await request.post('/lan/peers/manual', { host, port })
    manualAddr.value = ''
    ElMessage.success('已添加')
    loadPeers()
  } catch { /* 拦截器已提示 */ } finally {
    addingManual.value = false
  }
}

/** 移除手动添加的实例 */
async function removeManual(row) {
  try {
    await request.delete(`/lan/peers/manual?host=${encodeURIComponent(row.host)}&port=${row.httpPort}`)
    ElMessage.success('已移除')
    loadPeers()
  } catch { /* 拦截器已提示 */ }
}

/** 打开同步前预览弹窗:拉取对方标注/扫描任务预览,默认全选 */
async function openPreview(row, mode) {
  previewPeerRow.value = row
  previewMode.value = mode
  previewVisible.value = true
  previewLoading.value = true
  preview.value = null
  selectedTagNames.value = []
  selectedJobIds.value = []
  sel.includeTableTags = true
  sel.includeDocs = true
  sel.includeSystems = true
  try {
    preview.value = await request.get(`/lan/preview/${row.instanceId}`, { timeout: 60000 })
    selectedTagNames.value = preview.value.annotations.tags.map((t) => t.name)
    selectedDsNames.value = []
    // 数据源映射默认值与默认勾选由 involvedDatasources 的 watcher 维护(映射行随扫描记录勾选动态变化)
    Object.keys(dsChoices).forEach((k) => delete dsChoices[k])
    // 扫描任务默认全选(触发 selection-change 后映射表随之刷新)
    await nextTick()
    if (mode === 'all') {
      jobTableRef.value?.toggleAllSelection()
    }
  } catch {
    previewVisible.value = false // 拦截器已提示
  } finally {
    previewLoading.value = false
  }
}

async function submitPull() {
  const row = previewPeerRow.value
  const hasAnnSelection = selectedTagNames.value.length > 0 || sel.includeTableTags || sel.includeDocs || sel.includeSystems
  // 数据源映射:未勾选 → 0(跳过);'__new__' → 进 createDatasources;其余为本机数据源 id
  const dsMapping = {}
  const createDatasources = []
  for (const d of involvedDatasources.value) {
    if (!selectedDsNames.value.includes(d.peerName)) {
      dsMapping[d.peerName] = 0
      continue
    }
    const choice = dsChoices[d.peerName]
    if (choice === '__new__') createDatasources.push(d.peerName)
    else if (choice != null) dsMapping[d.peerName] = choice
  }
  const body = {
    annotations: hasAnnSelection
      ? { tagNames: selectedTagNames.value, includeTableTags: sel.includeTableTags, includeDocs: sel.includeDocs, includeSystems: sel.includeSystems }
      : null,
    scanJobIds: previewMode.value === 'all' ? selectedJobIds.value : null,
    dsMapping,
    createDatasources
  }
  pulling.value = true
  try {
    // 扫描记录可能很大,放宽本次请求超时(全局默认 30s)
    const res = await request.post(`/lan/pull/${row.instanceId}`, body, { timeout: 180000 })
    previewVisible.value = false
    const lines = []
    if ((res.datasourcesCreated || []).length > 0) {
      lines.push(`数据源已同步到本机:${res.datasourcesCreated.join('、')}`)
      // 新建的数据源副本即时进侧边栏菜单(与数据源列表页增删改同一广播口径)
      notifyDsListChanged()
    }
    if (res.annotations) {
      const a = res.annotations
      lines.push(`标记与描述:标记 新建${a.tagsCreated}/更新${a.tagsUpdated},表标记 +${a.tableTagsAdded},描述 ${a.docsUpserted} 条,所属系统 ${a.systemsUpserted} 条`)
    }
    if (res.scans) {
      const s = res.scans
      lines.push(`扫描记录:导入 ${s.imported} 个任务,跳过 ${s.skipped},失败 ${s.failed}`)
      ;(s.warnings || []).slice(0, 5).forEach((w) => lines.push(`跳过说明:${w}`))
    }
    ;(res.errors || []).forEach((e) => lines.push(`失败:${e}`))
    const hasError = (res.errors || []).length > 0
    ElMessageBox.alert(lines.join('\n') || '没有可同步的数据', hasError ? '同步完成(部分失败)' : '同步完成', {
      confirmButtonText: '知道了',
      type: hasError ? 'warning' : 'success'
    })
  } catch { /* 拦截器已提示 */ } finally {
    pulling.value = false
  }
}

/** 复制本机地址到剪贴板(clipboard API 不可用时走 textarea 兜底) */
async function copyAddr(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`已复制:${text}`)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    try {
      document.execCommand('copy')
      ElMessage.success(`已复制:${text}`)
    } catch {
      ElMessage.warning('复制失败,请手动复制')
    }
    document.body.removeChild(ta)
  }
}

function formatTime(ms) {
  if (!ms) return '-'
  const d = new Date(ms)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function startPolling() {
  stopPolling()
  timer = setInterval(() => {
    loadPeers()
  }, 5000)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

onMounted(() => {
  mounted = true
  load()
  loadPeers()
  startPolling()
})

// 页面在 keep-alive 内:切回页签时重拉并恢复轮询,切走必须停轮询
onActivated(() => {
  if (!mounted.value) return
  load()
  loadPeers()
  startPolling()
})
onDeactivated(stopPolling)
onUnmounted(stopPolling)
</script>

<style scoped>
.lan-share-page .settings-card {
  margin-bottom: 16px;
}

/* 说明信息块:小字号 + 浅底色块包裹,多条说明用有序列表 */
.settings-desc {
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px 12px;
  margin-bottom: 16px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.settings-desc ol {
  margin: 0;
  padding-left: 22px;
}

.lan-running-hint {
  margin-left: 12px;
  color: var(--el-color-success);
  font-size: 13px;
}

.lan-running-hint.off {
  color: var(--el-color-info);
}

.lan-self-info {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.lan-addr-tag {
  cursor: pointer;
  font-family: var(--el-font-family-monospace, monospace);
}

/* 网卡名:地址前缀的小字标注 */
.lan-addr-iface {
  margin-right: 6px;
  font-size: 11px;
  opacity: 0.7;
}

.lan-addr-hint {
  flex-basis: 100%;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.lan-peers-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.lan-manual-add {
  display: flex;
  align-items: center;
  gap: 8px;
}

.lan-manual-tag {
  margin-left: 6px;
}

.lan-preview-section-title {
  font-weight: 600;
  margin: 4px 0 8px;
}

/* 弹窗内容内部滚动:扣掉头部(约 55px)/底部按钮(约 70px),避免整窗滚动 */
.lan-preview-body {
  max-height: calc(94vh - 130px);
  overflow-y: auto;
  padding-right: 6px;
}

.lan-preview-group {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px 12px;
  margin-bottom: 10px;
  max-height: 200px;
  overflow: auto;
}

.lan-preview-group-head {
  border-bottom: 1px dashed var(--el-border-color-lighter);
  margin-bottom: 4px;
  padding-bottom: 4px;
}

.lan-tag-item {
  line-height: 28px;
}

.lan-tag-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 2px;
}

.lan-tag-type {
  margin-left: 6px;
  font-size: 12px;
  color: var(--el-color-info);
}

.lan-tag-desc {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.lan-preview-cats {
  padding: 4px 12px;
}

/* 标注类别行:勾选框 + 右侧「明细」入口(明细走弹窗,不再就地展开) */
.lan-cat-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 36px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}

.lan-cat-row:last-child {
  border-bottom: none;
}

.lan-row-dim {
  color: var(--el-color-info);
}

.lan-row-skip {
  font-size: 12px;
}

.lan-ds-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
}

.lan-ds-chip {
  margin-right: 10px;
}

.lan-empty-hint {
  color: var(--el-color-info);
  font-size: 13px;
  padding: 4px 0;
}

.lan-ds-map-table {
  margin-bottom: 6px;
}

.lan-ds-url {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
</style>
