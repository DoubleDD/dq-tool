<template>
  <!-- 扫描记录导出/导入入口:导出依赖外部表格勾选的 selectedIds;导入先预检再弹映射对话框 -->
  <!-- 必须单根容器:否则 Fragment 子节点会成为父级 flex(space-between)工具栏的直接子项被撑开 -->
  <div style="display: inline-flex; align-items: center; gap: 12px">
    <el-button :icon="Download" :disabled="!selectedIds.length" @click="exportSelected">导出记录</el-button>
    <el-upload ref="uploadRef" :auto-upload="false" accept=".json" :limit="1" :show-file-list="false"
               :on-change="onImportFile" style="display: inline-block">
      <el-button type="primary" :loading="importing">导入记录</el-button>
    </el-upload>
  </div>

  <!-- 导入扫描记录弹窗:文件里的数据源 → 本机数据源 -->
  <el-dialog v-model="importDialogVisible" title="导入扫描记录" width="640px" :close-on-click-modal="false" :close-on-press-escape="false">
    <div style="color: var(--el-text-color-secondary); font-size: 13px; margin-bottom: 12px">
      文件包含 {{ importPreview.totalJobs }} 条扫描记录,来自以下数据源,请逐个选择对应的本机数据源:
    </div>
    <el-table :data="importMappingRows" size="small" border>
      <el-table-column label="文件中的数据源" prop="datasourceName" min-width="140" show-overflow-tooltip />
      <el-table-column label="记录数" width="80" align="right" prop="jobs" />
      <el-table-column label="导入到本机数据源" min-width="200">
        <template #default="{ row }">
          <el-select v-model="row.targetDsId" size="small" style="width: 100%">
            <el-option :value="0" label="— 不导入该数据源 —" />
            <el-option v-for="ds in importPreview.localDatasources" :key="ds.id" :value="ds.id" :label="ds.name" />
          </el-select>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="importDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="importing" @click="confirmImport">确认导入</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { Download } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import api from '../api'
import { downloadFile } from '../utils/download'
import { confirmImportFile } from '../utils/importFileIdentify'

// selectedIds:外部表格当前勾选的扫描任务 id,导出用;imported:导入完成后通知父组件刷新列表
const props = defineProps({
  selectedIds: { type: Array, default: () => [] },
})
const emit = defineEmits(['imported'])

// 下载统一走 downloadFile(桌面端原生保存对话框,浏览器 window.open,见 utils/download.js)
function exportSelected() {
  downloadFile(`/api/scans/transfer/export?ids=${encodeURIComponent(props.selectedIds.join(','))}`)
}

// ---------- 扫描记录导入 ----------
// 选文件先做内容识别(三种导出都是 .json,靠 app 字段区分)并弹窗确认归属,
// 防止拿错文件;确认后预检(解析文件里的数据源分布),弹窗让用户把文件数据源映射到本机数据源后再执行;
// 同名自动预填,对不上默认不导入(由用户手动选择);失败消息由拦截器统一弹出
const importing = ref(false)
const importDialogVisible = ref(false)
const importPreview = ref({ totalJobs: 0, datasources: [], localDatasources: [] })
const importMappingRows = ref([])  // [{datasourceName, jobs, targetDsId}],targetDsId=0 表示不导入
const uploadRef = ref(null)        // el-upload 实例,选中文件后立即清内部列表,否则 limit=1 会挡住下次选择
let importFileRaw = null           // 暂存待导入的文件,确认时随映射一起提交

async function onImportFile(file) {
  // 拿到 raw 后立刻清空内部 fileList:不清的话 limit=1 会让第二次选择只触发 on-exceed,on-change 不再回调
  uploadRef.value?.clearFiles()
  if (importing.value || !file?.raw) return
  // 先识别文件种类并弹窗确认;无法识别/不属于本功能/用户取消时直接结束
  if (!await confirmImportFile(file.raw, 'scans')) return
  importing.value = true
  try {
    importFileRaw = file.raw
    const formData = new FormData()
    formData.append('file', file.raw)
    const preview = await api.post('/scans/transfer/preview', formData)
    importPreview.value = preview
    if (!preview.datasources?.length) {
      // 空文件:无需映射,直接导入(结果会提示没有记录)
      await confirmImport()
      return
    }
    // 默认映射:后端预检已给出同名匹配 id,对不上默认不导入
    importMappingRows.value = preview.datasources.map((d) => ({
      ...d,
      targetDsId: d.matchedDatasourceId ?? 0,
    }))
    importDialogVisible.value = true
  } catch {
    // 错误提示由响应拦截器统一弹出
  } finally {
    importing.value = false
  }
}

async function confirmImport() {
  if (!importFileRaw) return
  importing.value = true
  try {
    const formData = new FormData()
    formData.append('file', importFileRaw)
    const mapping = Object.fromEntries(importMappingRows.value.map((r) => [r.datasourceName, r.targetDsId]))
    formData.append('mapping', JSON.stringify(mapping))
    const r = await api.post('/scans/transfer/import', formData)
    importDialogVisible.value = false
    let text = `共 ${r.total} 条:导入 ${r.imported} 条,跳过 ${r.skipped} 条(重复或未映射),失败 ${r.failed} 条。`
    if (r.warnings?.length) {
      text += '\n' + r.warnings.join('\n')
    }
    ElMessageBox.alert(text, '导入完成', { confirmButtonText: '知道了', closeOnPressEscape: false })
    emit('imported')
  } catch {
    // 错误提示由响应拦截器统一弹出
  } finally {
    importing.value = false
  }
}
</script>
