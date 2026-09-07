<template>
  <div class="page-card settings-page">
    <div class="toolbar">
      <h3 style="margin: 0">系统设置</h3>
      <span class="settings-sub">全局配置项统一维护,修改即时生效</span>
    </div>

    <!-- 扫描设置:全局扫描参数 -->
    <el-card class="settings-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>扫描设置</span>
          <el-tag v-if="scanCustomized" size="small" type="warning" effect="plain">已自定义</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">使用配置文件默认</el-tag>
        </div>
      </template>
      <div class="settings-desc">
        作用于之后新建/续扫的扫描任务;未自定义的项使用配置文件(config.properties / application.yml)默认值,「恢复默认」即清除自定义回到配置值。
      </div>
      <el-form label-width="200px" v-loading="scanLoading">
        <el-form-item label="并发工作线程数">
          <el-input-number v-model="scanForm.workers" :min="1" :max="128" controls-position="right" style="width: 200px" />
          <span class="field-hint">同时执行的分段数(1~128)</span>
        </el-form-item>
        <el-form-item label="每张表分段数">
          <el-input-number v-model="scanForm.chunksPerTable" :min="1" :max="10000" controls-position="right" style="width: 200px" />
          <span class="field-hint">分段越多并行度越高,同时 SQL 开销越大</span>
        </el-form-item>
        <el-form-item label="采样行数阈值">
          <el-input-number v-model="scanForm.rowThreshold" :min="0" :step="100000" controls-position="right" style="width: 200px" />
          <span class="field-hint">估算行数超过该值默认采样(数据源可单独覆盖)</span>
        </el-form-item>
        <el-form-item label="采样体积阈值">
          <el-input-number v-model="scanForm.sizeThresholdGb" :min="0" :step="1" :precision="1" controls-position="right" style="width: 200px" />
          <span class="field-hint">体积(GB)超过该值默认采样(数据源可单独覆盖)</span>
        </el-form-item>
        <el-form-item label="采样行数">
          <el-input-number v-model="scanForm.sampleRows" :min="1" :step="10000" controls-position="right" style="width: 200px" />
          <span class="field-hint">采样表实际统计的行数</span>
        </el-form-item>
        <el-form-item label="单条统计 SQL 超时(秒)">
          <el-input-number v-model="scanForm.statementTimeoutSeconds" :min="1" :max="86400" controls-position="right" style="width: 200px" />
          <span class="field-hint">超时后该分段标记失败</span>
        </el-form-item>
      </el-form>
      <div class="card-actions">
        <el-button type="primary" :loading="scanSaving" @click="saveScanSettings">保存</el-button>
        <el-button v-if="scanCustomized" :loading="scanResetting" @click="resetScanSettings">恢复默认</el-button>
      </div>
    </el-card>

    <!-- 浏览器:桌面安装版应用模式首选浏览器 -->
    <el-card class="settings-card" shadow="never">
      <template #header>
        <span>浏览器</span>
      </template>
      <div class="settings-desc">
        桌面安装版启动或从托盘「打开窗口」时,用所选浏览器的应用模式(独立窗口,无地址栏/标签页)打开界面,选择对下次打开窗口生效。
        「自动」按系统优先级选择(Windows 优先 Edge,macOS/Linux 优先 Chrome);服务器部署(headless)不打开浏览器,本项无作用。
      </div>
      <el-form label-width="200px" v-loading="browserLoading">
        <el-form-item label="应用模式浏览器">
          <el-select v-model="browserSelected" style="width: 280px" @change="saveBrowser">
            <el-option value="auto" label="自动(按系统优先级)" />
            <el-option v-for="b in browserOptions" :key="b.id" :value="b.id" :label="b.name" />
          </el-select>
          <span v-if="!browserOptions.length" class="field-hint">未检测到 Chromium 系浏览器,打开时将使用系统默认浏览器</span>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- AI 配置:大模型接口 -->
    <el-card class="settings-card" shadow="never">
      <template #header>
        <span>AI 配置</span>
      </template><div class="settings-desc">
        用于生成「表说明」「AI 自动打标」的大模型接口,任意 OpenAI 兼容服务均可(DeepSeek / 通义 / 本地 vLLM 等)。
        生成表说明时只发送表结构元数据(表名、字段、注释),不发送业务数据;未填写的字段使用配置文件默认值。
      </div>
      <AiConfigForm ref="aiFormRef" />
      <div class="card-actions">
        <el-button type="primary" @click="saveAiConfig">保存</el-button>
      </div>
    </el-card>

    <!-- 标记与描述数据:跨机器迁移标记定义/表标记/表描述 -->
    <el-card class="settings-card" shadow="never">
      <template #header>
        <span>标记与描述数据</span>
      </template>
      <div class="settings-desc">
        把标记定义(含描述)、表-标记关联、表描述导出为 JSON 文件,在另一台机器导入,避免换机后重新打标与重新生成描述。
        导入时标记按名称合并(已存在则覆盖颜色与描述);表级数据按数据源对应——不同机器上同一数据源的命名可能不同,
        导入前会把文件里的数据源逐个映射到本机数据源(同名自动预填,也可选择不导入)。
      </div>
      <div class="card-actions" style="padding-left: 0">
        <el-button @click="exportAnnotations">导出</el-button>
        <el-upload :auto-upload="false" accept=".json" :limit="1" :show-file-list="false"
                   :on-change="onAnnotationFile" style="display: inline-block; margin-left: 12px">
          <el-button type="primary" :loading="annotationImporting">导入</el-button>
        </el-upload>
      </div>
    </el-card>

    <!-- 导入数据源映射弹窗:文件里的数据源 → 本机数据源 -->
    <el-dialog v-model="importDialogVisible" title="导入标记与描述数据" width="680px" :close-on-click-modal="false" :close-on-press-escape="false">
      <div class="settings-desc" style="margin-bottom: 12px">
        文件包含 {{ importPreview.tags }} 个标记(按名称合并导入)。表级数据来自以下数据源,请逐个选择对应的本机数据源:
      </div>
      <el-table :data="importMappingRows" size="small" border>
        <el-table-column label="文件中的数据源" prop="datasourceName" min-width="130" show-overflow-tooltip />
        <el-table-column label="表标记" width="70" align="right" prop="tableTags" />
        <el-table-column label="表描述" width="70" align="right" prop="tableDocs" />
        <el-table-column label="所属系统" width="80" align="right" prop="tableSystems" />
        <el-table-column label="导入到本机数据源" min-width="200">
          <template #default="{ row }">
            <el-select v-model="row.targetDsId" size="small" style="width: 100%">
              <el-option :value="0" label="— 不导入该数据源 —" />
              <el-option v-for="ds in localDatasources" :key="ds.id" :value="ds.id" :label="ds.name" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="annotationImporting" @click="confirmImport">确认导入</el-button>
      </template>
    </el-dialog>

    <!-- 外观:主题 -->
    <el-card class="settings-card" shadow="never">
      <template #header>
        <span>外观</span>
      </template>
      <div class="settings-desc">界面明暗主题,选择保存在本机浏览器。</div>
      <el-radio-group v-model="themeMode">
        <el-radio value="auto">跟随系统</el-radio>
        <el-radio value="light">浅色</el-radio>
        <el-radio value="dark">深色</el-radio>
      </el-radio-group>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onActivated } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import request from '../api'
import { downloadFile } from '../utils/download'
import { confirmImportFile } from '../utils/importFileIdentify'
import AiConfigForm from '../components/AiConfigForm.vue'
import { themeState, setThemeMode } from '../stores/theme'

// ---------- 扫描设置 ----------
const GIB = 1024 * 1024 * 1024
const scanLoading = ref(false)
const scanSaving = ref(false)
const scanResetting = ref(false)
const scanCustomized = ref(false)
const scanForm = reactive({
  workers: 8,
  chunksPerTable: 100,
  rowThreshold: 1000000,
  sizeThresholdGb: 10,
  sampleRows: 100000,
  statementTimeoutSeconds: 1800
})

async function loadScanSettings() {
  scanLoading.value = true
  try {
    const s = await request.get('/system-settings/scan')
    scanForm.workers = s.workers
    scanForm.chunksPerTable = s.chunksPerTable
    scanForm.rowThreshold = s.rowThreshold
    scanForm.sizeThresholdGb = Math.round((s.sizeThresholdBytes / GIB) * 10) / 10
    scanForm.sampleRows = s.sampleRows
    scanForm.statementTimeoutSeconds = s.statementTimeoutSeconds
    scanCustomized.value = !!s.customized
  } finally {
    scanLoading.value = false
  }
}

async function saveScanSettings() {
  if (!scanForm.workers || !scanForm.chunksPerTable || !scanForm.sampleRows || !scanForm.statementTimeoutSeconds
    || scanForm.rowThreshold < 0 || scanForm.sizeThresholdGb < 0) {
    ElMessage.warning('请检查填写内容:数值不能为空且不能小于 0')
    return
  }
  scanSaving.value = true
  try {
    const s = await request.put('/system-settings/scan', {
      workers: scanForm.workers,
      chunksPerTable: scanForm.chunksPerTable,
      rowThreshold: scanForm.rowThreshold,
      sizeThresholdBytes: Math.round(scanForm.sizeThresholdGb * GIB),
      sampleRows: scanForm.sampleRows,
      statementTimeoutSeconds: scanForm.statementTimeoutSeconds
    })
    scanCustomized.value = !!s.customized
    ElMessage.success('扫描设置已保存,对之后新建的扫描生效')
  } finally {
    scanSaving.value = false
  }
}

async function resetScanSettings() {
  scanResetting.value = true
  try {
    const s = await request.delete('/system-settings/scan')
    scanForm.workers = s.workers
    scanForm.chunksPerTable = s.chunksPerTable
    scanForm.rowThreshold = s.rowThreshold
    scanForm.sizeThresholdGb = Math.round((s.sizeThresholdBytes / GIB) * 10) / 10
    scanForm.sampleRows = s.sampleRows
    scanForm.statementTimeoutSeconds = s.statementTimeoutSeconds
    scanCustomized.value = !!s.customized
    ElMessage.success('已恢复为配置文件默认值')
  } finally {
    scanResetting.value = false
  }
}

// ---------- 浏览器设置 ----------
// 应用模式首选浏览器:选择即保存,对下次打开窗口生效(冷启动经 data/browser-app.txt 镜像读取)
const browserLoading = ref(false)
const browserSelected = ref('auto')
const browserOptions = ref([])

async function loadBrowserSettings() {
  browserLoading.value = true
  try {
    const v = await request.get('/system-settings/browser')
    browserOptions.value = v.browsers || []
    browserSelected.value = v.browser || 'auto'
  } finally {
    browserLoading.value = false
  }
}

async function saveBrowser(id) {
  try {
    await request.put('/system-settings/browser', { browser: id === 'auto' ? null : id })
    ElMessage.success('浏览器选择已保存,下次打开窗口时生效')
  } catch {
    // 保存失败(如所选浏览器已被卸载):错误提示由拦截器统一弹出,重新加载回滚显示
    await loadBrowserSettings()
  }
}

// ---------- AI 配置 ----------
const aiFormRef = ref(null)

async function saveAiConfig() {
  await aiFormRef.value?.save()
}

// ---------- 标记与描述数据 ----------
// 导出:桌面端弹原生保存对话框自选目录,浏览器走默认下载(见 utils/download.js)
function exportAnnotations() {
  downloadFile('/api/annotations/export')
}

// 导入:选文件先做内容识别(三种导出都是 .json,靠 app 字段区分)并弹窗确认归属,防止拿错文件;
// 确认后预检(解析文件里的数据源分布),弹窗让用户把文件数据源映射到本机数据源后再执行;
// 文件只有标记没有表级数据时跳过映射直接导入;失败消息由拦截器统一弹出
const annotationImporting = ref(false)
const importDialogVisible = ref(false)
const importPreview = ref({ tags: 0, datasources: [] })
const importMappingRows = ref([])   // [{datasourceName, tableTags, tableDocs, tableSystems, targetDsId}],targetDsId=0 表示不导入
const localDatasources = ref([])
let importFileRaw = null            // 暂存待导入的文件,确认时随映射一起提交

async function onAnnotationFile(file) {
  if (annotationImporting.value || !file?.raw) return
  // 先识别文件种类并弹窗确认;无法识别/不属于本功能/用户取消时直接结束
  if (!await confirmImportFile(file.raw, 'annotations')) return
  annotationImporting.value = true
  try {
    importFileRaw = file.raw
    const formData = new FormData()
    formData.append('file', file.raw)
    const preview = await request.post('/annotations/import/preview', formData)
    importPreview.value = preview
    if (!preview.datasources?.length) {
      // 无表级数据:无需映射,直接导入
      await confirmImport()
      return
    }
    // 本机数据源清单 + 默认映射:同名自动预填,对不上默认不导入(由用户手动选择)
    localDatasources.value = await request.get('/datasources')
    const byName = new Map(localDatasources.value.map((d) => [d.name, d.id]))
    importMappingRows.value = preview.datasources.map((d) => ({
      ...d,
      targetDsId: byName.get(d.datasourceName) ?? 0,
    }))
    importDialogVisible.value = true
  } catch {
    // 错误提示由响应拦截器统一弹出
  } finally {
    annotationImporting.value = false
  }
}

async function confirmImport() {
  if (!importFileRaw) return
  annotationImporting.value = true
  try {
    const formData = new FormData()
    formData.append('file', importFileRaw)
    const mapping = Object.fromEntries(importMappingRows.value.map((r) => [r.datasourceName, r.targetDsId]))
    formData.append('mapping', JSON.stringify(mapping))
    const r = await request.post('/annotations/import', formData)
    importDialogVisible.value = false
    ElMessageBox.alert(
      `新建标记 ${r.tagsCreated} 个,更新标记 ${r.tagsUpdated} 个;` +
      `新增表标记 ${r.tableTagsAdded} 条,跳过 ${r.tableTagsSkipped} 条;` +
      `导入表描述 ${r.docsUpserted} 条,跳过 ${r.docsSkipped} 条;` +
      `导入所属系统 ${r.systemsUpserted ?? 0} 条,跳过 ${r.systemsSkipped ?? 0} 条。`,
      '导入完成',
      { confirmButtonText: '知道了', closeOnPressEscape: false }
    )
  } catch {
    // 错误提示由响应拦截器统一弹出
  } finally {
    annotationImporting.value = false
  }
}

// ---------- 外观 ----------
// computed 双向绑定:与头部主题切换共享同一状态,任何入口改主题这里都即时同步
const themeMode = computed({
  get: () => themeState.mode,
  set: (mode) => setThemeMode(mode)
})

// 页面在 keep-alive 内:每次激活(含首次挂载)刷新,避免别的入口改了配置这里还显示旧值
onActivated(() => {
  loadScanSettings()
  loadBrowserSettings()
  aiFormRef.value?.load()
})
</script>

<style scoped>
.settings-page {
  max-width: 100%;
}

.settings-sub {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.settings-card {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
}

.settings-desc {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
  margin-bottom: 16px;
}

.field-hint {
  margin-left: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.card-actions {
  margin-top: 4px;
  padding-left: 200px;
}
</style>
