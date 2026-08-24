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

    <!-- AI 配置:大模型接口 -->
    <el-card class="settings-card" shadow="never">
      <template #header>
        <span>AI 配置</span>
      </template>
      <div class="settings-desc">
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
        导入时标记按名称合并(已存在则覆盖颜色与描述),表级数据按数据源名匹配,本机没有同名数据源的行会跳过并在结果中计数。
      </div>
      <div class="card-actions" style="padding-left: 0">
        <el-button @click="exportAnnotations">导出</el-button>
        <el-upload :auto-upload="false" accept=".json" :limit="1" :show-file-list="false"
                   :on-change="onAnnotationFile" style="display: inline-block; margin-left: 12px">
          <el-button type="primary" :loading="annotationImporting">导入</el-button>
        </el-upload>
      </div>
    </el-card>

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
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'
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

// ---------- AI 配置 ----------
const aiFormRef = ref(null)

async function saveAiConfig() {
  await aiFormRef.value?.save()
}

// ---------- 标记与描述数据 ----------
// 导出:后端直接返回文件下载,用 window.open 绕开 axios 的 JSON 拦截器(与数据源导出一致)
function exportAnnotations() {
  window.open('/api/annotations/export', '_blank')
}

// 导入:选文件即上传,成功后弹窗展示合并摘要;失败消息由拦截器统一弹出
const annotationImporting = ref(false)

async function onAnnotationFile(file) {
  if (annotationImporting.value || !file?.raw) return
  annotationImporting.value = true
  try {
    const formData = new FormData()
    formData.append('file', file.raw)
    const r = await request.post('/annotations/import', formData)
    ElMessageBox.alert(
      `新建标记 ${r.tagsCreated} 个,更新标记 ${r.tagsUpdated} 个;` +
      `新增表标记 ${r.tableTagsAdded} 条,跳过 ${r.tableTagsSkipped} 条;` +
      `导入表描述 ${r.docsUpserted} 条,跳过 ${r.docsSkipped} 条。`,
      '导入完成',
      { confirmButtonText: '知道了' }
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
  aiFormRef.value?.load()
})
</script>

<style scoped>
.settings-page {
  max-width: 860px;
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
