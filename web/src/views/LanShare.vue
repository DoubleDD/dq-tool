<template>
  <div class="page-card lan-share-page">
    <div class="toolbar">
      <h2>局域网共享</h2>
    </div>

    <el-card class="settings-card" shadow="never">
      <template #header>本机共享</template>
      <div class="settings-desc">
        开启后,本机通过 UDP 广播(端口 {{ status?.discoveryPort ?? '-' }})自动发现同一局域网内运行本软件的其他电脑,
        并可互相拉取标记、表描述与扫描记录。共享出口无鉴权,仅适合可信内网使用。
      </div>
      <el-form label-width="140px">
        <el-form-item label="启用局域网共享">
          <el-switch v-model="form.enabled" />
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
      </el-form>
      <div class="card-actions">
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </el-card>

    <el-card class="settings-card" shadow="never">
      <template #header>
        <div class="lan-peers-header">
          <span>在线实例</span>
          <el-button :icon="Refresh" text circle title="刷新" @click="loadPeers" />
        </div>
      </template>
      <div class="settings-desc">
        每 5 秒自动刷新;超过 15 秒未收到心跳的实例判离线。「同步标记与描述」拉取对方的标记定义、表级打标、
        表描述与表所属系统;「导入全部数据」额外拉取对方扫描记录(数据源按名称自动匹配本机,匹配不到的任务跳过)。
        两者都会先预览对方数据,由你勾选后再同步。
      </div>
      <el-table :data="peers" v-loading="loadingPeers" border>
        <el-table-column prop="instanceName" label="实例名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="地址" min-width="170">
          <template #default="{ row }">{{ row.host }}:{{ row.httpPort }}</template>
        </el-table-column>
        <el-table-column prop="appVersion" label="版本" width="100">
          <template #default="{ row }">{{ row.appVersion || '-' }}</template>
        </el-table-column>
        <el-table-column label="最后心跳" width="170">
          <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openPreview(row, 'annotations')">同步标记与描述</el-button>
            <el-button size="small" type="primary" @click="openPreview(row, 'all')">导入全部数据</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <span v-if="status && !status.running">共享未启用,开启后自动发现同网段实例</span>
          <span v-else>暂未发现其他实例(确认对方已开启共享且在同一局域网)</span>
        </template>
      </el-table>
    </el-card>

    <!-- 同步前预览与勾选弹窗:两个操作共用;annotations 模式只显示标注段,all 模式含扫描任务段 -->
    <el-dialog
      v-model="previewVisible"
      :title="previewMode === 'all' ? `导入全部数据 — ${previewPeerName}` : `同步标记与描述 — ${previewPeerName}`"
      width="720px"
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
            <el-collapse v-model="expandedCats">
              <el-collapse-item name="tableTags">
                <template #title>
                  <el-checkbox v-model="sel.includeTableTags" @click.stop>
                    表级打标({{ preview.annotations.tableTags.length }} 条,仅同步被勾选标记的关系)
                  </el-checkbox>
                  <span class="lan-expand-hint">点击展开明细</span>
                </template>
                <el-table :data="preview.annotations.tableTags" size="small" border max-height="200">
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
              </el-collapse-item>
              <el-collapse-item name="tableDocs">
                <template #title>
                  <el-checkbox v-model="sel.includeDocs" @click.stop>
                    表描述({{ preview.annotations.tableDocs.length }} 条)
                  </el-checkbox>
                  <span class="lan-expand-hint">点击展开明细</span>
                </template>
                <el-table :data="preview.annotations.tableDocs" size="small" border max-height="200">
                  <el-table-column label="表" width="200" show-overflow-tooltip>
                    <template #default="{ row }">{{ row.schemaName }}.{{ row.tableName }}</template>
                  </el-table-column>
                  <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip />
                  <template #empty>无表描述</template>
                </el-table>
              </el-collapse-item>
              <el-collapse-item name="tableSystems">
                <template #title>
                  <el-checkbox v-model="sel.includeSystems" @click.stop>
                    表所属系统({{ preview.annotations.tableSystems.length }} 条)
                  </el-checkbox>
                  <span class="lan-expand-hint">点击展开明细</span>
                </template>
                <el-table :data="preview.annotations.tableSystems" size="small" border max-height="200">
                  <el-table-column label="表" min-width="200" show-overflow-tooltip>
                    <template #default="{ row }">{{ row.schemaName }}.{{ row.tableName }}</template>
                  </el-table-column>
                  <el-table-column prop="systemName" label="所属系统" min-width="140" show-overflow-tooltip />
                  <template #empty>无所属系统数据</template>
                </el-table>
              </el-collapse-item>
            </el-collapse>
          </div>
          <div v-if="preview.annotations.datasources.length" class="lan-ds-hint">
            涉及数据源:<span v-for="d in preview.annotations.datasources" :key="d.datasourceName" class="lan-ds-chip">
              {{ d.datasourceName }}(打标 {{ d.tableTags }} / 描述 {{ d.tableDocs }} / 系统 {{ d.tableSystems }})
            </span>
          </div>

          <!-- 数据源映射:对方数据源与本机数据源的对应(同名预填;无对应可新建无密码副本或跳过) -->
          <template v-if="involvedDatasources.length">
            <div class="lan-preview-section-title">数据源映射</div>
            <el-table :data="involvedDatasources" size="small" border class="lan-ds-map-table">
              <el-table-column label="对方数据源" min-width="200">
                <template #default="{ row }">
                  <div>{{ row.peerName }}</div>
                  <div class="lan-ds-url">{{ row.jdbcUrl }}</div>
                </template>
              </el-table-column>
              <el-table-column label="映射到本机" min-width="220">
                <template #default="{ row }">
                  <el-select v-model="dsChoices[row.peerName]" size="small" style="width: 100%">
                    <el-option
                      v-for="l in preview.localDatasources" :key="l.id" :value="l.id"
                      :label="`现有:${l.name}`"
                    />
                    <el-option value="__new__" label="新建到本机(同步连接信息,与导入导出口径一致)" />
                    <el-option value="__skip__" label="跳过(不同步该数据源的数据)" />
                  </el-select>
                </template>
              </el-table-column>
            </el-table>
            <div class="lan-ds-hint">「新建到本机」同步名称/连接地址/用户名/密码(密码以密文传输,与数据源导出文件同一口径);「跳过」则该数据源下的打标/描述/扫描记录都不导入。</div>
          </template>

          <template v-if="previewMode === 'all'">
            <div class="lan-preview-section-title">扫描记录</div>
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
  </div>
</template>

<script setup>
import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import request from '../api'

const status = ref(null)
const form = reactive({ enabled: true, instanceName: '' })
const saving = ref(false)
const peers = ref([])
const loadingPeers = ref(false)
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
const expandedCats = ref([])
const selectedJobIds = ref([])
const jobTableRef = ref(null)
const pulling = ref(false)
// 数据源映射选择:对方数据源名 → '__new__'(新建无密码副本)/ '__skip__'(跳过) / 本机数据源 id
const dsChoices = reactive({})

const previewPeerName = computed(() => previewPeerRow.value?.instanceName || '')

/** 预览数据实际涉及的对方数据源(标注分布 ∪ 扫描任务),映射表逐行由用户确认 */
const involvedDatasources = computed(() => {
  if (!preview.value) return []
  const names = new Set()
  ;(preview.value.annotations.datasources || []).forEach((d) => names.add(d.datasourceName))
  ;(preview.value.scanJobs || []).forEach((j) => names.add(j.datasourceName))
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
    // 数据源映射默认:同名匹配 > 新建无密码副本
    Object.keys(dsChoices).forEach((k) => delete dsChoices[k])
    involvedDatasources.value.forEach((d) => {
      dsChoices[d.peerName] = d.matchedLocalId ?? '__new__'
    })
    // 扫描任务默认全选
    if (mode === 'all') {
      await nextTick()
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
  // 数据源映射:'__skip__' → 0(强制跳过);'__new__' → 进 createDatasources;其余为本机数据源 id
  const dsMapping = {}
  const createDatasources = []
  for (const d of involvedDatasources.value) {
    const choice = dsChoices[d.peerName]
    if (choice === '__new__') createDatasources.push(d.peerName)
    else if (choice === '__skip__') dsMapping[d.peerName] = 0
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

.lan-running-hint {
  margin-left: 12px;
  color: var(--el-color-success);
  font-size: 13px;
}

.lan-running-hint.off {
  color: var(--el-color-info);
}

.lan-peers-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.lan-preview-section-title {
  font-weight: 600;
  margin: 4px 0 8px;
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
  padding: 0 12px;
}

.lan-preview-cats :deep(.el-collapse-item__header) {
  height: 40px;
}

.lan-expand-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-color-info);
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
