<script setup>
/**
 * 「AI 判定明细」弹窗:按 jobId 拉取 GET /api/compare-jobs/{id}/ai-traces,
 * 按对比表(targetLabel)分组、组内按时间升序列出每次大模型调用(场景 tag + 批次 + 模型 + 时间),
 * 点击展开看「判定结果」(按 stage 渲染:补配/消歧 → 配对表格,字段映射 → 映射表格含「表格锁定」标注,
 * 时间列/佐证字段 → 选中列名,失败批次 → 错误摘要)与可折叠的「完整输入」「原始回答」(<pre> 限高滚动)。
 * 两个入口共用:比对报告页「AI 判定」(任务级)、字段审核页「AI 推导依据」(PENDING 任务,主要是 MAPPING 记录)。
 */
import { computed, ref, watch } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'
import { listCompareAiTraces } from '../api'
import { formatDateTime } from '../utils/format'

const props = defineProps({
  // 弹窗显隐(v-model)
  modelValue: { type: Boolean, default: false },
  // 比对任务 id
  jobId: { type: [Number, String], default: null }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const loading = ref(false)
const traces = ref([])
// 展开状态:记录 id 集合(点击行切换)
const expanded = ref(new Set())

// 阶段 → 场景中文 tag(配色与现有 tag 风格一致,plain 效果)
const STAGE_TAG = {
  RESIDUE: { text: '比对匹配 · 补配', type: 'warning' },
  SAME_NAME: { text: '比对匹配 · 同名消歧', type: 'danger' },
  MAPPING: { text: '字段映射', type: 'success' },
  TIME: { text: '时间列识别', type: 'primary' },
  EVIDENCE: { text: '佐证字段识别', type: 'info' }
}

function stageTag(stage) {
  return STAGE_TAG[stage] || { text: stage || '未知场景', type: 'info' }
}

/** 分组键:targetId 非空按 targetId 归组,空(任务级调用)按 targetLabel 归组 */
function groupKey(t) {
  return t.targetId != null && t.targetId !== '' ? `t:${t.targetId}` : `label:${t.targetLabel || ''}`
}

/** 按对比表分组的调用记录:组内按时间升序(后端已排序,这里再兜底排一次) */
const groups = computed(() => {
  const map = new Map()
  for (const t of traces.value) {
    const k = groupKey(t)
    if (!map.has(k)) map.set(k, { key: k, label: t.targetLabel || '任务级', items: [] })
    map.get(k).items.push(t)
  }
  for (const g of map.values()) {
    g.items.sort((a, b) => String(a.createdAt || '').localeCompare(String(b.createdAt || '')))
  }
  return [...map.values()]
})

watch(() => props.modelValue, (v) => {
  if (v && props.jobId) load()
})

async function load() {
  loading.value = true
  traces.value = []
  expanded.value = new Set()
  try {
    const list = await listCompareAiTraces(props.jobId)
    traces.value = Array.isArray(list) ? list : []
  } catch {
    // 拦截器已提示;保持空态
  } finally {
    loading.value = false
  }
}

function toggle(id) {
  const s = new Set(expanded.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  expanded.value = s
}

/** 调用耗时文案(毫秒):<1s 显毫秒,<60s 显秒(留一位小数),再长显 分秒 */
function durationText(ms) {
  if (ms == null) return ''
  if (ms < 1000) return `${ms}毫秒`
  const s = ms / 1000
  if (s < 60) return `${s.toFixed(1)}秒`
  return `${Math.floor(s / 60)}分${Math.round(s % 60)}秒`
}

/** 解析 resultJson(兼容「JSON 字符串」与「已解析对象」两种形态);解析失败/为 null 返回 null */
function parseResult(t) {
  let r = t.resultJson
  if (r == null) return null
  if (typeof r === 'string') {
    if (!r.trim()) return null
    try { r = JSON.parse(r) } catch { return null }
  }
  return r && typeof r === 'object' ? r : null
}

/** 字段映射行:基准字段 → 目标列;locked(表格锁定)里的行加标注(与 mapping 取并集,目标列优先取 mapping) */
function mappingRows(result) {
  const mapping = result?.mapping || {}
  const locked = result?.locked || {}
  const keys = [...new Set([...Object.keys(mapping), ...Object.keys(locked)])]
  return keys.map((k) => ({ base: k, target: mapping[k] ?? locked[k], locked: k in locked }))
}

/** 是否失败批次:resultJson 为 null(补配/消歧/时间/佐证)或 resultJson.failed 为真(映射推导失败仍带锁定项) */
function isFailed(t) {
  if (t.resultJson == null) return true
  return parseResult(t)?.failed === true
}

/** 时间列/佐证字段识别选中列文案:TIME={"field":"列名"};EVIDENCE={"fields":{"行政区划":"列名",...}} */
function pickedText(result) {
  if (!result) return '—'
  if (result.field) return result.field
  if (result.fields && typeof result.fields === 'object') {
    const parts = Object.entries(result.fields).map(([kind, col]) => `${kind}:${col}`)
    return parts.length ? parts.join('、') : '—'
  }
  return '—'
}
</script>

<template>
  <el-dialog v-model="visible" title="AI 判定明细" width="80%" top="4vh" append-to-body destroy-on-close>
    <div v-loading="loading" class="trace-body">
      <el-empty v-if="!loading && !groups.length" description="本次比对未调用大模型" :image-size="80" />
      <div v-for="g in groups" :key="g.key" class="trace-group">
        <div class="trace-group-title">{{ g.label }}</div>
        <div v-for="t in g.items" :key="t.id" class="trace-item">
          <!-- 记录行:场景 tag + 批次 + 模型 + 时间,点击展开/收起详情 -->
          <div class="trace-row" @click="toggle(t.id)">
            <el-icon class="trace-arrow" :class="{ open: expanded.has(t.id) }"><ArrowRight /></el-icon>
            <el-tag size="small" :type="stageTag(t.stage).type" effect="plain">{{ stageTag(t.stage).text }}</el-tag>
            <span v-if="t.batchNo != null" class="trace-batch">第 {{ t.batchNo }} 批</span>
            <span v-if="t.model" class="trace-model">{{ t.model }}</span>
            <span v-if="t.durationMs != null" class="trace-duration">耗时 {{ durationText(t.durationMs) }}</span>
            <el-tag v-if="isFailed(t)" size="small" type="danger" effect="plain">调用失败</el-tag>
            <span class="trace-time">{{ formatDateTime(t.createdAt) }}</span>
          </div>
          <!-- 展开详情:上半「判定结果」,下半可折叠「完整输入」「原始回答」 -->
          <div v-if="expanded.has(t.id)" class="trace-detail">
            <div class="trace-section-title">判定结果</div>
            <template v-if="parseResult(t)">
              <!-- 补配:配对表 + 未配上清单 -->
              <template v-if="t.stage === 'RESIDUE'">
                <el-table :data="parseResult(t).pairs || []" size="small" border max-height="300">
                  <el-table-column prop="baseCode" label="基准编码" min-width="120" />
                  <el-table-column prop="baseName" label="基准名称" min-width="140" />
                  <el-table-column prop="targetCode" label="目标编码" min-width="120" />
                  <el-table-column prop="targetName" label="目标名称" min-width="140" />
                </el-table>
                <div v-if="(parseResult(t).unmatchedBase || []).length" class="trace-unmatched">
                  未配上:{{ parseResult(t).unmatchedBase.map((u) => `${u.code}(${u.name})`).join('、') }}
                </div>
              </template>
              <!-- 同名消歧:配对表(多一列组号) -->
              <el-table v-else-if="t.stage === 'SAME_NAME'" :data="parseResult(t).pairs || []" size="small" border max-height="300">
                <el-table-column prop="group" label="组号" width="70" align="center" />
                <el-table-column prop="baseName" label="基准名称" min-width="140" />
                <el-table-column prop="targetCode" label="目标编码" min-width="120" />
                <el-table-column prop="targetName" label="目标名称" min-width="140" />
              </el-table>
              <!-- 字段映射:基准字段 → 目标列,locked 行加「表格锁定」标注;推导失败(failed)先提示再看表 -->
              <template v-else-if="t.stage === 'MAPPING'">
                <div v-if="parseResult(t).failed" class="trace-failed-msg">
                  推导失败,仅保留表格锁定项,请人工审核补线
                </div>
                <el-table :data="mappingRows(parseResult(t))" size="small" border max-height="300">
                  <el-table-column prop="base" label="基准字段" min-width="140" />
                  <el-table-column prop="target" label="目标列" min-width="140" />
                  <el-table-column label="标注" width="100" align="center">
                    <template #default="{ row }">
                      <el-tag v-if="row.locked" size="small" type="info" effect="plain">表格锁定</el-tag>
                    </template>
                  </el-table-column>
                </el-table>
              </template>
              <!-- 时间列 / 佐证字段识别:直接显示选中列名(佐证一次最多识别 3 类,按 类别:列名 列出) -->
              <div v-else class="trace-picked">选中列:{{ pickedText(parseResult(t)) }}</div>
            </template>
            <!-- 失败批次:resultJson 为 null,responseContent 是错误摘要 -->
            <div v-else class="trace-failed">
              该批调用失败
              <div v-if="t.responseContent" class="trace-failed-msg">{{ t.responseContent }}</div>
            </div>
            <el-collapse class="trace-raw">
              <el-collapse-item title="完整输入" name="request">
                <pre class="trace-pre">{{ t.requestContent || '(空)' }}</pre>
              </el-collapse-item>
              <el-collapse-item title="原始回答" name="response">
                <pre class="trace-pre">{{ t.responseContent || '(空)' }}</pre>
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.trace-body {
  min-height: 200px;
  max-height: 72vh;
  overflow-y: auto;
}
/* 分组标题:对比表名(任务级调用归「任务级」组) */
.trace-group-title {
  margin: 12px 0 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}
.trace-group:first-child .trace-group-title {
  margin-top: 0;
}
.trace-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  margin-bottom: 6px;
  overflow: hidden;
}
.trace-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  cursor: pointer;
  font-size: 12px;
}
.trace-row:hover {
  background: var(--el-fill-color-light);
}
.trace-arrow {
  flex: none;
  color: var(--el-text-color-secondary);
  transition: transform 0.2s;
}
.trace-arrow.open {
  transform: rotate(90deg);
}
.trace-batch {
  flex: none;
  color: var(--el-text-color-regular);
}
.trace-model {
  flex: none;
  color: var(--el-text-color-secondary);
}
.trace-duration {
  flex: none;
  color: var(--el-text-color-secondary);
}
.trace-time {
  margin-left: auto;
  flex: none;
  color: var(--el-text-color-secondary);
}
.trace-detail {
  padding: 10px 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-lighter);
}
.trace-section-title {
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}
.trace-unmatched {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.trace-picked {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.trace-failed {
  font-size: 12px;
  color: var(--el-color-danger);
}
.trace-failed-msg {
  margin-top: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--el-text-color-secondary);
}
.trace-raw {
  margin-top: 10px;
  --el-collapse-header-bg-color: transparent;
  --el-collapse-content-bg-color: transparent;
}
/* 完整输入/原始回答:原文展示(requestContent 含 [system]/[user] 两段),限高滚动 */
.trace-pre {
  margin: 0;
  max-height: 260px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}
</style>
