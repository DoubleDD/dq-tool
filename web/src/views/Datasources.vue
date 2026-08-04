<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">数据源管理</h3>
      <el-button type="primary" @click="openDialog()">新增数据源</el-button>
    </div>

    <div class="ds-grid" v-loading="loading">
      <el-card v-for="row in list" :key="row.id" shadow="hover" class="ds-card">
        <DbTypeIcon :type="row.dbType" :size="110" class="ds-bg-icon" />
        <div class="ds-card-header">
          <span class="ds-name" :title="row.name">{{ row.name }}</span>
          <el-tag size="small">{{ row.dbType }}</el-tag>
        </div>
        <div class="ds-field" :title="row.jdbcUrl">
          <span class="ds-label">JDBC</span>
          <span class="ds-value">{{ row.jdbcUrl }}</span>
        </div>
        <div class="ds-field">
          <span class="ds-label">用户名</span>
          <span class="ds-value">{{ row.username }}</span>
        </div>
        <div class="ds-field">
          <span class="ds-label">阈值</span>
          <span class="ds-value">{{ formatNumber(row.rowThreshold) }} 行 / {{ formatBytes(row.sizeThresholdBytes) }}</span>
        </div>
        <div class="ds-actions">
          <el-button link type="primary" @click="goSchemas(row)">浏览库</el-button>
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </div>
      </el-card>
      <el-empty v-if="!loading && !list.length" description="暂无数据源,点击右上角新增" style="grid-column: 1 / -1" />
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑数据源' : '新增数据源'" width="560px" destroy-on-close>
      <el-form :model="form" label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="数据源名称" />
        </el-form-item>
        <el-form-item label="数据库类型">
          <el-select v-model="form.dbType" placeholder="选择类型以填充示例地址" style="width: 100%">
            <el-option v-for="t in DB_TYPES" :key="t" :label="t" :value="t">
              <span class="db-option">
                <DbTypeIcon :type="t" />
                {{ t }}
              </span>
            </el-option>
            <template #label="{ label }">
              <span class="db-option">
                <DbTypeIcon :type="label" />
                {{ label }}
              </span>
            </template>
          </el-select>
        </el-form-item>
        <el-form-item label="JDBC 地址" required>
          <el-input v-model="form.jdbcUrl" :placeholder="urlPlaceholder" />
        </el-form-item>
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" />
        </el-form-item>
        <el-form-item label="密码" :required="!form.id">
          <el-input v-model="form.password" type="password" show-password
            :placeholder="form.id ? '留空表示不修改' : '请输入密码'" />
        </el-form-item>
        <el-form-item label="行数阈值">
          <el-input-number v-model="form.rowThreshold" :min="0" :step="100000" style="width: 100%" />
        </el-form-item>
        <el-form-item label="大小阈值(字节)">
          <el-input-number v-model="form.sizeThresholdBytes" :min="0" :step="104857600" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :loading="testing" @click="onTest">测试连接</el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onActivated, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'
import { formatBytes, formatNumber } from '../utils/format'
import DbTypeIcon from '../components/DbTypeIcon.vue'

const router = useRouter()
const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const testing = ref(false)

const DB_TYPES = ['MYSQL', 'POSTGRESQL', 'DM', 'KINGBASE', 'OCEANBASE', 'SQLSERVER', 'ORACLE']
const URL_PLACEHOLDERS = {
  MYSQL: 'jdbc:mysql://host:3306/',
  POSTGRESQL: 'jdbc:postgresql://host:5432/db',
  DM: 'jdbc:dm://host:5236',
  KINGBASE: 'jdbc:kingbase8://host:54321/db',
  OCEANBASE: 'jdbc:oceanbase://host:2881/',
  SQLSERVER: 'jdbc:sqlserver://host:1433;databaseName=db',
  ORACLE: 'jdbc:oracle:thin:@//host:1521/service'
}

const emptyForm = () => ({
  id: null,
  name: '',
  dbType: 'MYSQL',
  jdbcUrl: '',
  username: '',
  password: '',
  rowThreshold: null,
  sizeThresholdBytes: null
})
const form = reactive(emptyForm())

const urlPlaceholder = computed(() => URL_PLACEHOLDERS[form.dbType] || 'jdbc:...')

async function loadList() {
  loading.value = true
  try {
    list.value = await request.get('/datasources')
  } finally {
    loading.value = false
  }
}

function openDialog(row) {
  Object.assign(form, emptyForm())
  if (row) {
    form.id = row.id
    form.name = row.name
    form.dbType = row.dbType || 'MYSQL'
    form.jdbcUrl = row.jdbcUrl
    form.username = row.username
    form.password = ''
    form.rowThreshold = row.rowThreshold ?? null
    form.sizeThresholdBytes = row.sizeThresholdBytes ?? null
  }
  dialogVisible.value = true
}

async function onTest() {
  if (!form.jdbcUrl || !form.username) {
    ElMessage.warning('请先填写 JDBC 地址和用户名')
    return
  }
  testing.value = true
  try {
    const res = await request.post('/datasources/test', {
      name: form.name,
      jdbcUrl: form.jdbcUrl,
      username: form.username,
      password: form.password
    })
    if (res.success) {
      ElMessage.success('连接成功')
    } else {
      ElMessage.error(res.message || '连接失败')
    }
  } finally {
    testing.value = false
  }
}

async function onSave() {
  if (!form.name || !form.jdbcUrl || !form.username) {
    ElMessage.warning('请填写名称、JDBC 地址和用户名')
    return
  }
  if (!form.id && !form.password) {
    ElMessage.warning('请填写密码')
    return
  }
  saving.value = true
  try {
    const body = {
      name: form.name,
      jdbcUrl: form.jdbcUrl,
      username: form.username,
      password: form.password,
      rowThreshold: form.rowThreshold ?? undefined,
      sizeThresholdBytes: form.sizeThresholdBytes ?? undefined
    }
    if (form.id) {
      await request.put(`/datasources/${form.id}`, body)
    } else {
      await request.post('/datasources', body)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    loadList()
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除数据源「${row.name}」吗?`, '删除确认', { type: 'warning' })
  await request.delete(`/datasources/${row.id}`)
  ElMessage.success('删除成功')
  loadList()
}

function goSchemas(row) {
  router.push(`/datasources/${row.id}/schemas?name=${encodeURIComponent(row.name)}`)
}

// 首页是常驻页签,用 onActivated 保证每次切回都刷新(首次挂载也会触发)
onActivated(loadList)
</script>

<style scoped>
.ds-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}
.ds-card :deep(.el-card__body) {
  position: relative;
}
.ds-bg-icon {
  position: absolute;
  right: 6px;
  bottom: 6px;
  opacity: 0.5;
  pointer-events: none;
}
.ds-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.ds-name {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ds-field {
  display: flex;
  font-size: 13px;
  line-height: 24px;
}
.ds-label {
  color: #909399;
  width: 48px;
  flex-shrink: 0;
}
.ds-value {
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ds-actions {
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px solid #ebeef5;
}
.db-option {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
</style>
