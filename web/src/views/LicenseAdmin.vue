<template>
  <div class="page-card">
    <div class="toolbar">
      <el-button @click="goBack">返回</el-button>
      <h3 style="margin: 0">授权码管理</h3>
      <el-button type="primary" @click="openGenerate">生成授权码</el-button>
    </div>

    <el-table v-loading="loading" :data="list">
      <el-table-column prop="appVersion" label="版本" width="80" />
      <el-table-column prop="customer" label="客户" min-width="140" show-overflow-tooltip />
      <el-table-column label="有效期" width="150">
        <template #default="{ row }">
          <span v-if="row.expiresAt">{{ row.expiresAt }}</span>
          <el-tag v-else size="small" type="success">永久</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="username" label="用户名" width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.username || '—' }}</template>
      </el-table-column>
      <el-table-column prop="sid" label="SID" width="100" show-overflow-tooltip>
        <template #default="{ row }">{{ row.sid || '—' }}</template>
      </el-table-column>
      <el-table-column prop="serverUrl" label="server_url" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.serverUrl || '—' }}</template>
      </el-table-column>
      <el-table-column label="功能" min-width="200">
        <template #default="{ row }">
          <template v-if="row.features">
            <el-tag v-for="k in row.features.split(',')" :key="k" size="small" style="margin-right: 4px">
              {{ featureLabel(k) }}
            </el-tag>
          </template>
          <span v-else style="color: var(--el-text-color-secondary)">仅基础功能</span>
        </template>
      </el-table-column>
      <el-table-column label="签发时间" width="160">
        <template #default="{ row }">{{ formatTime(row.issuedAt) }}</template>
      </el-table-column>
      <el-table-column label="授权码" width="90">
        <template #default="{ row }">
          <el-button link type="primary" @click="viewCode(row)">查看</el-button>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无签发记录" />
      </template>
    </el-table>

    <!-- 生成授权码:客户名必填;有效期永久/日期二选一;扩展字段选填 -->
    <el-dialog v-model="generateVisible" title="生成授权码" width="520px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="客户名称" required>
          <el-input v-model="form.customer" placeholder="如:某某公司" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-radio-group v-model="form.permanent">
            <el-radio :value="false">指定日期</el-radio>
            <el-radio :value="true">永久授权</el-radio>
          </el-radio-group>
          <el-date-picker
            v-if="!form.permanent"
            v-model="form.expiresDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择到期日(当天仍有效)"
            style="margin-left: 12px"
          />
        </el-form-item>
        <el-form-item label="server_url">
          <el-input v-model="form.serverUrl" placeholder="选填,仅留档,不下发用户实例" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="选填" />
        </el-form-item>
        <el-form-item label="功能">
          <el-checkbox-group v-model="form.features" class="feature-group">
            <el-checkbox v-for="f in FEATURES" :key="f.key" :value="f.key">{{ f.label }}</el-checkbox>
          </el-checkbox-group>
          <div class="feature-tip">基础业务功能(扫描/数据源/Excel/报告/AI/标记)恒可用;运行日志、授权码管理需在此勾选</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateVisible = false">取消</el-button>
        <el-button type="primary" :loading="generating" :disabled="!canGenerate" @click="onGenerate">生成</el-button>
      </template>
    </el-dialog>

    <!-- 查看/新生成的授权码:完整展示 + 复制 -->
    <el-dialog v-model="codeVisible" :title="codeTitle" width="560px" destroy-on-close>
      <el-input :model-value="currentCode" type="textarea" :rows="4" readonly />
      <template #footer>
        <el-button type="primary" @click="copyCode">复制</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'
import { goBack as historyBack } from '../utils/back'

const router = useRouter()

const list = ref([])
const loading = ref(false)
const generateVisible = ref(false)
const generating = ref(false)
const codeVisible = ref(false)
const codeTitle = ref('授权码')
const currentCode = ref('')

// 功能清单(与后端 LicenseFeature 对应):基础业务功能恒可用,运行日志/授权码管理需勾选才会写入授权码
const FEATURES = [
  { key: 'scan', label: '扫描检测' },
  { key: 'datasource', label: '数据源管理' },
  { key: 'excel', label: 'Excel导出' },
  { key: 'report', label: 'Word报告' },
  { key: 'ai_doc', label: 'AI表说明' },
  { key: 'ai_tag', label: 'AI自动打标' },
  { key: 'tag', label: '表标记' },
  { key: 'logs', label: '运行日志' },
  { key: 'license_admin', label: '授权码管理' }
]
const featureLabel = (key) => FEATURES.find((f) => f.key === key)?.label || key

const form = ref({ customer: '', permanent: false, expiresDate: '', serverUrl: '', username: '', features: FEATURES.map((f) => f.key) })

const canGenerate = computed(() =>
  form.value.customer.trim() && (form.value.permanent || form.value.expiresDate))

onMounted(load)

// 原路返回;无历史记录(直接打开)时兜底回首页
function goBack() {
  historyBack(router, '/')
}

async function load() {
  loading.value = true
  try {
    list.value = await request.get('/license/admin/codes')
  } finally {
    loading.value = false
  }
}

function openGenerate() {
  // 默认全勾(业务功能恒有,勾了也写入码内以显式声明;不勾的受控功能 logs/license_admin 客户将不可用)
  form.value = { customer: '', permanent: false, expiresDate: '', serverUrl: '', username: '', features: FEATURES.map((f) => f.key) }
  generateVisible.value = true
}

async function onGenerate() {
  generating.value = true
  try {
    const f = form.value
    const record = await request.post('/license/admin/codes', {
      customer: f.customer.trim(),
      expires: f.permanent ? 'permanent' : f.expiresDate,
      serverUrl: f.serverUrl.trim() || null,
      username: f.username.trim() || null,
      features: f.features.length ? f.features : null
    })
    generateVisible.value = false
    ElMessage.success('授权码已生成')
    viewCode(record, '新生成的授权码')
    load()
  } finally {
    generating.value = false
  }
}

function viewCode(row, title) {
  currentCode.value = row.code
  codeTitle.value = title || `授权码(${row.customer})`
  codeVisible.value = true
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(currentCode.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败,请手动选择复制')
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(
    `删除「${row.customer}」的签发记录?仅删除留档,已分发的授权码不受影响(离线验签无法吊销)。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  )
  await request.delete(`/license/admin/codes/${row.id}`)
  ElMessage.success('已删除')
  load()
}

/** epoch 毫秒转本地时间串 */
function formatTime(ts) {
  return ts ? new Date(Number(ts)).toLocaleString('zh-CN', { hour12: false }) : '—'
}
</script>

<style scoped>
.feature-group {
  display: flex;
  flex-wrap: wrap;
  gap: 0 12px;
  line-height: 28px;
}
.feature-tip {
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
