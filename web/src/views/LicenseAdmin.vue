<template>
  <div class="page-card">
    <div class="toolbar">
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
      <el-table-column label="菜单" min-width="240">
        <template #default="{ row }">
          <el-tag v-for="k in menuKeys(row)" :key="k" size="small" style="margin-right: 4px">
            {{ menuLabel(k) }}
          </el-tag>
          <el-tag v-if="row.bypassAuth" size="small" type="warning" style="margin-right: 4px">免鉴权</el-tag>
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

    <!-- 生成授权码:客户名必填;有效期永久/日期二选一;扩展字段选填;弹窗加宽(宽屏桌面工具)让菜单平铺更从容 -->
    <el-dialog v-model="generateVisible" title="生成授权码" width="1040px" destroy-on-close :close-on-press-escape="false">
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
        <el-form-item label="菜单">
          <div class="menu-head">
            <!-- 全选:半选态=只勾了部分菜单;点击全选/全不选 -->
            <el-checkbox :model-value="allMenusChecked" :indeterminate="menuIndeterminate" @change="toggleAllMenus">全选</el-checkbox>
            <span class="menu-tip">勾选即展示:客户实例侧边栏只显示勾选的菜单,不再区分基础/受控功能</span>
          </div>
          <el-checkbox-group v-model="form.menus" class="menu-group">
            <el-checkbox v-for="m in MENUS" :key="m.key" :value="m.key">{{ m.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="接口鉴权">
          <el-checkbox v-model="form.bypassAuth">免鉴权(演示用:实例无需访问令牌即可调用全部接口)</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateVisible = false">取消</el-button>
        <el-button type="primary" :loading="generating" :disabled="!canGenerate" @click="onGenerate">生成</el-button>
      </template>
    </el-dialog>

    <!-- 查看/新生成的授权码:完整展示 + 复制 -->
    <el-dialog v-model="codeVisible" :title="codeTitle" width="560px" destroy-on-close :close-on-press-escape="false">
      <el-input :model-value="currentCode" type="textarea" :rows="4" readonly />
      <template #footer>
        <el-button type="primary" @click="copyCode">复制</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import request from '../api'

const list = ref([])
const loading = ref(false)
const generateVisible = ref(false)
const generating = ref(false)
const codeVisible = ref(false)
const codeTitle = ref('授权码')
const currentCode = ref('')

// 菜单清单(与后端 LicenseMenu 对应,顺序一致):勾选即客户实例侧边栏可见
const MENUS = [
  { key: 'datasource', label: '数据源' },
  { key: 'dashboard', label: '扫描记录' },
  { key: 'tags', label: '标记统计' },
  { key: 'manual-collects', label: '人工采集' },
  { key: 'report-exports', label: '报告列表' },
  { key: 'sample-exports', label: '抽样导出' },
  { key: 'compare', label: '数据比对' },
  { key: 'relations', label: 'ER 关系' },
  { key: 'object-manage', label: '对象管理' },
  { key: 'sql-console', label: 'SQL 控制台' },
  { key: 'lan-share', label: '局域网共享' },
  { key: 'ai-usage', label: '模型用量统计' },
  { key: 'settings', label: '系统设置' },
  { key: 'diagnostics', label: '系统诊断' },
  { key: 'logs', label: '运行日志' },
  { key: 'license-admin', label: '授权管理' }
]
const menuLabel = (key) => MENUS.find((m) => m.key === key)?.label || key
const menuKeys = (row) => (row.menus || '').split(',').filter(Boolean)

// 全选:全部勾选为真、部分勾选为半选态
const allMenusChecked = computed(() => form.value.menus.length === MENUS.length)
const menuIndeterminate = computed(() => form.value.menus.length > 0 && form.value.menus.length < MENUS.length)
function toggleAllMenus(checked) {
  form.value.menus = checked ? MENUS.map((m) => m.key) : []
}

const form = ref({ customer: '', permanent: false, expiresDate: '', serverUrl: '', username: '', menus: MENUS.map((m) => m.key), bypassAuth: false })

const canGenerate = computed(() =>
  form.value.customer.trim() && (form.value.permanent || form.value.expiresDate))

onMounted(load)

async function load() {
  loading.value = true
  try {
    list.value = await request.get('/license/admin/codes')
  } finally {
    loading.value = false
  }
}

function openGenerate() {
  // 默认全勾(客户实例菜单全开放),按需取消勾选;免鉴权默认关,仅演示场景开启
  form.value = { customer: '', permanent: false, expiresDate: '', serverUrl: '', username: '', menus: MENUS.map((m) => m.key), bypassAuth: false }
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
      menus: f.menus,
      bypassAuth: f.bypassAuth
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
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', closeOnPressEscape: false }
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
.menu-head {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 2px;
}
.menu-group {
  display: flex;
  flex-wrap: wrap;
  /* 宽弹窗下固定项宽对齐成列;16 个菜单约 4~5 列平铺 */
  gap: 0 8px;
  line-height: 30px;
}
.menu-group .el-checkbox {
  width: 150px;
  margin-right: 0;
}
.menu-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
