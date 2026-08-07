<template>
  <div class="page-card">
    <div class="toolbar">
      <h3 style="margin: 0">数据源管理</h3>
      <div>
        <el-button @click="openExportDialog">导出</el-button>
        <el-button @click="openImportDialog">导入</el-button>
        <el-button v-if="list.length" type="primary" @click="openDialog()">新增数据源</el-button>
      </div>
    </div>

    <div class="ds-grid" v-loading="loading">
      <el-card v-for="row in list" :key="row.id" shadow="hover" class="ds-card"
        :class="{ 'ds-no-password': row.hasPassword === false }"
        :title="row.hasPassword === false ? '未设置密码,请先编辑补充密码' : ''"
        @click="goSchemas(row)">
        <DbTypeIcon :type="row.dbType" :size="110" class="ds-bg-icon" />
        <div class="ds-card-header">
          <span class="ds-name" :title="row.name">
            <el-tooltip v-if="row.hasPassword === false" content="未设置密码,请先编辑补充密码" placement="top">
              <el-icon class="ds-error-icon"><WarningFilled /></el-icon>
            </el-tooltip>
            {{ row.name }}
          </span>
          <span>
            <el-tag size="small">{{ row.dbType }}</el-tag>
          </span>
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
          <el-button link type="primary" :disabled="row.hasPassword === false" @click.stop="goSchemas(row)">浏览库</el-button>
          <el-button link type="primary" @click.stop="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click.stop="onDelete(row)">删除</el-button>
        </div>
      </el-card>
      <el-empty v-if="!loading && !list.length" description="暂无数据源" style="grid-column: 1 / -1">
        <el-button type="primary" @click="openDialog()">新增数据源</el-button>
      </el-empty>
    </div>

    <!-- 新增/编辑数据源:DataGrip 风格 —— 顶部名称+驱动,常规/SSH 隧道/高级分页,测试连接固定在左下 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑数据源' : '新增数据源'" width="640px" destroy-on-close>
      <div class="dg-head">
        <el-input v-model="form.name" placeholder="名称" class="dg-name" />
        <el-select v-model="form.dbType" placeholder="数据库类型" class="dg-driver" @change="onDbTypeChange">
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
      </div>
      <el-tabs v-model="activeTab" class="dg-tabs">
        <el-tab-pane label="常规" name="general">
          <el-form :model="form" label-width="90px" autocomplete="off">
            <el-form-item label="连接方式">
              <el-radio-group v-model="form.inputMode" @change="onInputModeChange">
                <el-radio-button value="fields">默认</el-radio-button>
                <el-radio-button value="url">仅 URL</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <template v-if="form.inputMode === 'fields'">
              <el-form-item label="主机" required>
                <div class="dg-inline">
                  <el-input v-model="form.host" placeholder="IP 或主机名" style="flex: 1" />
                  <span class="dg-inline-label">端口</span>
                  <el-input-number v-model="form.port" :min="1" :max="65535" :controls="false" placeholder="端口" style="width: 110px" />
                </div>
              </el-form-item>
              <el-form-item label="用户名" required>
                <el-input v-model="form.username" autocomplete="off" />
              </el-form-item>
              <el-form-item label="密码" :required="!form.id">
                <el-input v-model="form.password" type="password" show-password autocomplete="new-password"
                  :placeholder="form.id ? '留空表示不修改' : '请输入密码'" />
              </el-form-item>
              <el-form-item v-if="form.dbType !== 'DM'" :label="form.dbType === 'ORACLE' ? '服务名' : '数据库'">
                <el-input v-model="form.database" :placeholder="form.dbType === 'ORACLE' ? 'Oracle 服务名,可留空' : '数据库名,可留空'" />
              </el-form-item>
              <el-form-item label="URL">
                <el-input :model-value="urlPreview" readonly placeholder="由上方设置自动生成" />
              </el-form-item>
            </template>
            <template v-else>
              <el-form-item label="用户名" required>
                <el-input v-model="form.username" autocomplete="off" />
              </el-form-item>
              <el-form-item label="密码" :required="!form.id">
                <el-input v-model="form.password" type="password" show-password autocomplete="new-password"
                  :placeholder="form.id ? '留空表示不修改' : '请输入密码'" />
              </el-form-item>
              <el-form-item label="URL" required>
                <el-input v-model="form.jdbcUrl" :placeholder="urlPlaceholder" />
              </el-form-item>
            </template>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="SSH 隧道" name="ssh">
          <el-form :model="form" label-width="90px" autocomplete="off">
            <el-form-item label="启用隧道">
              <div style="width: 100%">
                <el-switch v-model="form.sshEnabled" />
                <div class="ssh-tip">通过 SSH 跳板机转发连接目标数据库</div>
              </div>
            </el-form-item>
            <template v-if="form.sshEnabled">
              <el-form-item label="SSH 主机" required>
                <div class="dg-inline">
                  <el-input v-model="form.sshHost" placeholder="SSH 跳板机地址" style="flex: 1" />
                  <span class="dg-inline-label">端口</span>
                  <el-input-number v-model="form.sshPort" :min="1" :max="65535" :controls="false" placeholder="端口" style="width: 110px" />
                </div>
              </el-form-item>
              <el-form-item label="SSH 用户名" required>
                <el-input v-model="form.sshUsername" autocomplete="off" />
              </el-form-item>
              <el-form-item label="认证方式">
                <el-radio-group v-model="form.sshAuthMethod">
                  <el-radio-button value="password">密码</el-radio-button>
                  <el-radio-button value="publickey">私钥</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="form.sshAuthMethod === 'password'" label="SSH 密码" :required="!form.id">
                <el-input v-model="form.sshPassword" type="password" show-password autocomplete="new-password"
                  :placeholder="form.id ? '留空表示不修改' : '请输入 SSH 密码'" />
              </el-form-item>
              <template v-else>
                <el-form-item label="私钥内容" :required="!form.id">
                  <el-input v-model="form.sshPrivateKey" type="textarea" :rows="4" autocomplete="off"
                    :placeholder="form.id ? '粘贴 PEM 私钥内容,留空表示不修改' : '粘贴 PEM 私钥内容'" />
                </el-form-item>
                <el-form-item label="私钥口令">
                  <el-input v-model="form.sshPassphrase" type="password" show-password autocomplete="new-password"
                    :placeholder="form.id ? '可空,留空表示不修改' : '私钥口令,可空'" />
                </el-form-item>
              </template>
            </template>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="库过滤" name="schemas">
          <div class="sf-head">
            <el-button :loading="schemaLoading" @click="loadSchemaList">加载库列表</el-button>
            <span class="ssh-tip">勾选需要显示的库;全部勾选(或不加载)表示不过滤。数据库自身的系统库可不勾。</span>
          </div>
          <template v-if="schemaFetched">
            <div class="sf-all">
              <el-checkbox :model-value="schemaCheckAll" :indeterminate="schemaIndeterminate" @change="onSchemaCheckAll">全部</el-checkbox>
              <span class="sf-count">已选 {{ schemaChecked.length }} / {{ schemaList.length }}</span>
            </div>
            <el-checkbox-group v-model="schemaChecked" class="sf-list">
              <el-checkbox v-for="db in schemaList" :key="db" :value="db">{{ db }}</el-checkbox>
            </el-checkbox-group>
          </template>
          <div v-else-if="form.schemaFilter?.length" class="ssh-tip sf-current">
            当前仅显示 {{ form.schemaFilter.length }} 个库:{{ form.schemaFilter.join('、') }}。点击「加载库列表」可修改。
          </div>
          <el-empty v-else description="当前不过滤,显示全部库" :image-size="60" />
        </el-tab-pane>
        <el-tab-pane label="高级" name="advanced">
          <el-form :model="form" label-width="120px" autocomplete="off">
            <el-form-item label="行数阈值">
              <el-input-number v-model="form.rowThreshold" :min="0" :step="100000" style="width: 100%" />
            </el-form-item>
            <el-form-item label="大小阈值(字节)">
              <el-input-number v-model="form.sizeThresholdBytes" :min="0" :step="104857600" style="width: 100%" />
            </el-form-item>
            <div class="ssh-tip">行数/体积超过阈值的表按采样估算统计,留空使用全局默认值。</div>
          </el-form>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <div class="dg-footer">
          <el-button :loading="testing" @click="onTest">测试连接</el-button>
          <div>
            <el-button @click="dialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <!-- 导出数据源:勾选后通过 window.open 直接下载 JSON 文件 -->
    <el-dialog v-model="exportVisible" title="导出数据源" width="560px" destroy-on-close>
      <template v-if="list.length">
        <div class="export-head">
          <el-checkbox :model-value="exportCheckAll" :indeterminate="exportIndeterminate" @change="onExportCheckAll">全选</el-checkbox>
          <span class="export-count">已选 {{ exportChecked.length }} / {{ list.length }}</span>
        </div>
        <el-checkbox-group v-model="exportChecked" class="export-list">
          <el-checkbox v-for="row in list" :key="row.id" :value="row.id" class="export-item">
            <span class="export-item-main">
              <DbTypeIcon :type="row.dbType" />
              <span class="export-item-name">{{ row.name }}</span>
              <span class="export-item-url" :title="row.jdbcUrl">{{ row.jdbcUrl }}</span>
            </span>
          </el-checkbox>
        </el-checkbox-group>
        <div class="export-tip">导出文件包含加密后的连接密码,请妥善保管,勿对外发送。</div>
      </template>
      <el-empty v-else description="暂无数据源可导出" :image-size="80" />
      <template #footer>
        <el-button @click="exportVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!exportChecked.length" @click="doExport">导出</el-button>
      </template>
    </el-dialog>

    <!-- 导入数据源:文件上传或粘贴文本,成功后对话框内展示结果明细 -->
    <el-dialog v-model="importVisible" title="导入数据源" width="560px" destroy-on-close @closed="onImportClosed">
      <template v-if="!importResult">
        <el-radio-group v-model="importMode" class="import-mode">
          <el-radio-button value="file">文件导入</el-radio-button>
          <el-radio-button value="text">粘贴导入</el-radio-button>
        </el-radio-group>
        <template v-if="importMode === 'file'">
          <el-upload ref="uploadRef" drag :auto-upload="false" accept=".json,.ncx" :limit="1"
            :on-change="onImportFileChange" :on-exceed="onImportFileExceed" :on-remove="onImportFileRemove">
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽文件到此处,或 <em>点击选择文件</em></div>
          </el-upload>
          <div class="import-tip">
            支持本工具导出的 JSON 与 Navicat 连接导出的 .ncx 文件;重名数据源会自动追加序号后缀导入,不会覆盖已有配置。
          </div>
        </template>
        <template v-else>
          <el-input v-model="importText" type="textarea" :rows="10" resize="none"
            placeholder="粘贴 DataGrip「复制数据源到剪贴板」的内容(#DataSourceSettings# 开头),也支持本工具导出的 JSON 文本" />
          <div class="import-tip">
            DataGrip 剪贴板内容不含连接密码,导入后需逐个编辑数据源补充密码;重名数据源会自动追加序号后缀导入。
          </div>
        </template>
      </template>
      <div v-else class="import-result">
        <div class="import-summary">共解析 {{ importResult.total || 0 }} 条,成功导入 {{ importResult.imported?.length || 0 }} 条。</div>
        <template v-if="importResult.imported?.length">
          <div class="section-title">导入成功({{ importResult.imported.length }})</div>
          <div class="import-names">{{ importResult.imported.join('、') }}</div>
        </template>
        <template v-if="renamedList.length">
          <div class="section-title">自动改名({{ renamedList.length }})</div>
          <div v-for="r in renamedList" :key="r.from" class="import-rename-item">{{ r.from }} → {{ r.to }}</div>
        </template>
        <template v-if="importResult.failed?.length">
          <div class="section-title">导入失败({{ importResult.failed.length }})</div>
          <el-alert v-for="f in importResult.failed" :key="f.name" type="error" :closable="false" show-icon
            class="import-alert" :title="`${f.name}:${f.reason}`" />
        </template>
        <template v-if="importResult.warnings?.length">
          <div class="section-title">警告({{ importResult.warnings.length }})</div>
          <el-alert v-for="(w, i) in importResult.warnings" :key="i" type="warning" :closable="false" show-icon
            class="import-alert" :title="w" />
        </template>
      </div>
      <template #footer>
        <template v-if="!importResult">
          <el-button @click="importVisible = false">取消</el-button>
          <el-button type="primary" :disabled="!canImport" :loading="importing" @click="doImport">导入</el-button>
        </template>
        <el-button v-else type="primary" @click="importVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <LicenseFooter />
  </div>
</template>

<script setup>
import { computed, onActivated, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled, WarningFilled } from '@element-plus/icons-vue'
import request from '../api'
import { formatBytes, formatNumber } from '../utils/format'
import DbTypeIcon from '../components/DbTypeIcon.vue'
import LicenseFooter from '../components/LicenseFooter.vue'

const router = useRouter()
const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const testing = ref(false)
// 编辑对话框当前页签:general / ssh / advanced
const activeTab = ref('general')

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

const DEFAULT_PORTS = {
  MYSQL: 3306,
  POSTGRESQL: 5432,
  DM: 5236,
  KINGBASE: 54321,
  OCEANBASE: 2881,
  SQLSERVER: 1433,
  ORACLE: 1521
}

const emptyForm = () => ({
  id: null,
  name: '',
  dbType: 'MYSQL',
  inputMode: 'fields',
  jdbcUrl: '',
  host: '',
  port: null,
  database: '',
  username: '',
  password: '',
  sshEnabled: false,
  sshHost: '',
  sshPort: 22,
  sshUsername: '',
  sshAuthMethod: 'password',
  sshPassword: '',
  sshPrivateKey: '',
  sshPassphrase: '',
  schemaFilter: null,
  rowThreshold: null,
  sizeThresholdBytes: null
})
const form = reactive(emptyForm())

// ---------- 库过滤页签(DataGrip Schemas 页风格) ----------
// 页签内的勾选状态独立于 form.schemaFilter:只有点过「加载库列表」才以勾选为准,否则保留已存值
const schemaList = ref([])
const schemaChecked = ref([])
const schemaFetched = ref(false)
const schemaLoading = ref(false)
// 打开编辑对话框时的连接信息快照,用于判断用户是否改过连接参数
let connSnapshot = null

const schemaCheckAll = computed(() => schemaList.value.length > 0 && schemaChecked.value.length === schemaList.value.length)
const schemaIndeterminate = computed(() => schemaChecked.value.length > 0 && schemaChecked.value.length < schemaList.value.length)

function onSchemaCheckAll(val) {
  schemaChecked.value = val ? [...schemaList.value] : []
}

/** 连接信息是否相对已存数据源被修改过;改过则库列表要按表单新值实时连接拉取 */
function connDirty() {
  if (!form.id || !connSnapshot) return true
  // 秘密字段非空即视为要换新值
  if (form.password || form.sshPassword || form.sshPrivateKey || form.sshPassphrase) return true
  const strKeys = ['host', 'database', 'jdbcUrl', 'username', 'sshHost', 'sshUsername', 'sshAuthMethod']
  if (strKeys.some((k) => (form[k] ?? '') !== (connSnapshot[k] ?? ''))) return true
  if ((form.sshEnabled ?? false) !== (connSnapshot.sshEnabled ?? false)) return true
  if ((form.port ?? null) !== (connSnapshot.port ?? null)) return true
  if ((form.sshPort ?? null) !== (connSnapshot.sshPort ?? null)) return true
  return false
}

// 连接字段一旦被改动,之前拉到的库列表即失效(改名称/阈值不影响)
watch(
  () => [form.host, form.port, form.database, form.jdbcUrl, form.username, form.password,
    form.sshEnabled, form.sshHost, form.sshPort, form.sshUsername, form.sshAuthMethod,
    form.sshPassword, form.sshPrivateKey, form.sshPassphrase, form.inputMode],
  () => { schemaFetched.value = false }
)

/**
 * 加载库列表。编辑态且连接信息未改:直接走已存数据源的库列表接口(与浏览库页同源,all=true 旁路白名单拿全量);
 * 新增态或连接信息被改过:用表单连接参数实时连接拉取(preview-databases,密码留空由服务端回落已存值)。
 */
async function loadSchemaList() {
  if (schemaLoading.value) return
  schemaLoading.value = true
  try {
    let dbs
    if (form.id && !connDirty()) {
      dbs = await request.get(`/datasources/${form.id}/databases?all=true`)
      if (!dbs?.length) {
        dbs = await request.get(`/datasources/${form.id}/schemas?all=true`)
      }
    } else {
      if (!syncJdbcUrl()) return
      if (!form.jdbcUrl || !form.username) {
        activeTab.value = 'general'
        ElMessage.warning('请先填写 JDBC 地址和用户名')
        return
      }
      if (!validateSsh()) return
      const res = await request.post('/datasources/preview-databases', {
        jdbcUrl: form.jdbcUrl,
        username: form.username,
        password: form.password,
        ...buildSshBody(),
        id: form.id ?? undefined
      })
      if (!res.success) {
        ElMessage.error(res.message || '拉取库列表失败')
        return
      }
      dbs = res.databases || []
    }
    schemaList.value = dbs || []
    // 已配置白名单时回填勾选(只勾仍存在于目标库中的),未配置则全勾(=不过滤)
    schemaChecked.value = form.schemaFilter?.length
      ? schemaList.value.filter((db) => form.schemaFilter.includes(db))
      : [...schemaList.value]
    schemaFetched.value = true
    if (!schemaList.value.length) ElMessage.info('目标库没有可选择的库')
  } finally {
    schemaLoading.value = false
  }
}

// 编辑态且连接信息未改时,切到「库过滤」页签自动加载(走已存数据源接口);
// 新增态或连接信息被改过时需手动点按钮(按表单新值实时连接)
watch(activeTab, (tab) => {
  if (tab === 'schemas' && form.id && !connDirty() && !schemaFetched.value) loadSchemaList()
})

/** 保存时的库过滤值:加载过列表按勾选(全勾/全不勾=不过滤),没加载过保留已存配置 */
function currentSchemaFilter() {
  if (!schemaFetched.value) return form.schemaFilter ?? undefined
  if (schemaChecked.value.length === 0 || schemaChecked.value.length === schemaList.value.length) return null
  return [...schemaChecked.value]
}

const urlPlaceholder = computed(() => URL_PLACEHOLDERS[form.dbType] || 'jdbc:...')

/** 拆分填写模式下实时预览拼出的 JDBC URL(主机未填时不显示) */
const urlPreview = computed(() => {
  if (!form.host || !form.host.trim() || !form.port) return ''
  return buildJdbcUrl()
})

function onDbTypeChange(type) {
  form.port = DEFAULT_PORTS[type] || null
}

/** 解析现有 JDBC URL 到主机/端口/数据库(支持 host:// 和 Oracle @// 两种形式) */
function parseJdbcUrl() {
  const url = (form.jdbcUrl || '').trim()
  if (!url) return
  const m = url.match(/(?:@\/\/|:\/\/)([^/:;?]+)(?::(\d+))?/)
  if (m) {
    form.host = m[1]
    form.port = m[2] ? Number(m[2]) : (DEFAULT_PORTS[form.dbType] || null)
  }
  if (form.dbType === 'DM') {
    form.database = ''
    return
  }
  if (form.dbType === 'SQLSERVER') {
    const d = url.match(/databaseName=([^;]+)/i)
    form.database = d ? d[1] : ''
    return
  }
  const d = url.match(/(?:@\/\/|:\/\/)[^/:;?]+(?::\d+)?\/([^?;]+)/)
  form.database = d ? d[1] : ''
}

/** 填写方式切换时双向转换:拆分 → 拼 URL;URL → 解析拆分 */
function onInputModeChange(mode) {
  if (mode === 'fields') {
    parseJdbcUrl()
  } else if (form.host && form.host.trim()) {
    form.jdbcUrl = buildJdbcUrl()
  }
}

/** 拆分填写模式下,按数据库类型模板拼出 JDBC URL */
function buildJdbcUrl() {
  const h = (form.host || '').trim()
  const db = (form.database || '').trim()
  const p = form.port
  switch (form.dbType) {
    case 'MYSQL': return `jdbc:mysql://${h}:${p}/${db}`
    case 'POSTGRESQL': return `jdbc:postgresql://${h}:${p}/${db}`
    case 'DM': return `jdbc:dm://${h}:${p}`
    case 'KINGBASE': return `jdbc:kingbase8://${h}:${p}/${db}`
    case 'OCEANBASE': return `jdbc:oceanbase://${h}:${p}/${db}`
    case 'SQLSERVER': return `jdbc:sqlserver://${h}:${p}${db ? `;databaseName=${db}` : ''}`
    case 'ORACLE': return `jdbc:oracle:thin:@//${h}:${p}/${db}`
    default: return ''
  }
}

/** 拆分填写模式下校验字段并把拼好的 URL 写回 form.jdbcUrl */
function syncJdbcUrl() {
  if (form.inputMode !== 'fields') return true
  activeTab.value = 'general'
  if (!form.host || !form.host.trim()) {
    ElMessage.warning('请填写主机')
    return false
  }
  if (!form.port) {
    ElMessage.warning('请填写端口')
    return false
  }
  form.jdbcUrl = buildJdbcUrl()
  return true
}

/** SSH 隧道字段校验:开启时主机/用户名必填;私钥认证且新增态时私钥必填(编辑留空=不修改) */
function validateSsh() {
  if (!form.sshEnabled) return true
  if (!form.sshHost || !form.sshHost.trim()) {
    activeTab.value = 'ssh'
    ElMessage.warning('请填写 SSH 主机')
    return false
  }
  if (!form.sshUsername || !form.sshUsername.trim()) {
    activeTab.value = 'ssh'
    ElMessage.warning('请填写 SSH 用户名')
    return false
  }
  if (form.sshAuthMethod === 'publickey' && !form.id && !form.sshPrivateKey.trim()) {
    activeTab.value = 'ssh'
    ElMessage.warning('请粘贴私钥内容')
    return false
  }
  return true
}

/** 组装 SSH 隧道字段(create/update/test 三个接口同一套,sshEnabled=false 也透传以便后端关闭隧道) */
function buildSshBody() {
  return {
    sshEnabled: form.sshEnabled,
    sshHost: form.sshHost,
    sshPort: form.sshPort,
    sshUsername: form.sshUsername,
    sshAuthMethod: form.sshAuthMethod,
    sshPassword: form.sshPassword,
    sshPrivateKey: form.sshPrivateKey,
    sshPassphrase: form.sshPassphrase
  }
}

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
  form.port = DEFAULT_PORTS[form.dbType] || null
  activeTab.value = 'general'
  schemaList.value = []
  schemaChecked.value = []
  schemaFetched.value = false
  if (row) {
    form.id = row.id
    form.name = row.name
    form.dbType = row.dbType || 'MYSQL'
    form.jdbcUrl = row.jdbcUrl
    form.username = row.username
    form.password = ''
    form.schemaFilter = row.schemaFilter ?? null
    form.rowThreshold = row.rowThreshold ?? null
    form.sizeThresholdBytes = row.sizeThresholdBytes ?? null
    // SSH 隧道:只回填非秘密字段,密码/私钥/口令置空(留空=不修改)
    form.sshEnabled = row.sshEnabled ?? false
    form.sshHost = row.sshHost || ''
    form.sshPort = row.sshPort ?? 22
    form.sshUsername = row.sshUsername || ''
    form.sshAuthMethod = row.sshAuthMethod || 'password'
    // 编辑时把已存 JDBC URL 反解析回主机/端口/数据库,回填拆分填写模式的表单
    parseJdbcUrl()
  }
  // 连接信息快照(回填完成后);新增态为 null 即视为「全新连接」
  connSnapshot = row
    ? {
        host: form.host, port: form.port, database: form.database, jdbcUrl: form.jdbcUrl,
        username: form.username, sshEnabled: form.sshEnabled, sshHost: form.sshHost,
        sshPort: form.sshPort, sshUsername: form.sshUsername, sshAuthMethod: form.sshAuthMethod
      }
    : null
  dialogVisible.value = true
}

async function onTest() {
  if (!syncJdbcUrl()) return
  if (!form.jdbcUrl || !form.username) {
    activeTab.value = 'general'
    ElMessage.warning('请先填写 JDBC 地址和用户名')
    return
  }
  if (!validateSsh()) return
  testing.value = true
  try {
    const res = await request.post('/datasources/test', {
      name: form.name,
      jdbcUrl: form.jdbcUrl,
      username: form.username,
      password: form.password,
      ...buildSshBody()
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
  if (!syncJdbcUrl()) return
  if (!form.name || !form.jdbcUrl || !form.username) {
    activeTab.value = 'general'
    ElMessage.warning('请填写名称、JDBC 地址和用户名')
    return
  }
  if (!form.id && !form.password) {
    activeTab.value = 'general'
    ElMessage.warning('请填写密码')
    return
  }
  if (!validateSsh()) return
  saving.value = true
  try {
    const body = {
      name: form.name,
      jdbcUrl: form.jdbcUrl,
      username: form.username,
      password: form.password,
      ...buildSshBody(),
      schemaFilter: currentSchemaFilter(),
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
  // 未设密码的数据源不可浏览(卡片点击与浏览库按钮同一路径)
  if (row.hasPassword === false) return
  router.push(`/datasources/${row.id}/schemas?name=${encodeURIComponent(row.name)}`)
}

// ---------- 导出 ----------
const exportVisible = ref(false)
const exportChecked = ref([])

const exportCheckAll = computed(() => list.value.length > 0 && exportChecked.value.length === list.value.length)
const exportIndeterminate = computed(() => exportChecked.value.length > 0 && exportChecked.value.length < list.value.length)

function onExportCheckAll(val) {
  exportChecked.value = val ? list.value.map((r) => r.id) : []
}

function openExportDialog() {
  // 默认全选,与扫描导出对话框的默认行为一致
  exportChecked.value = list.value.map((r) => r.id)
  exportVisible.value = true
}

function doExport() {
  // 后端直接返回文件下载,用 window.open 绕开 axios 的 JSON 拦截器
  window.open('/api/datasources/export?ids=' + exportChecked.value.join(','), '_blank')
  exportVisible.value = false
}

// ---------- 导入 ----------
const importVisible = ref(false)
const importing = ref(false)
const importMode = ref('file')
const importFile = ref(null)
const importText = ref('')
const importResult = ref(null)
const uploadRef = ref(null)

// renamed 为 {原名称: 新名称} 映射,转成列表便于渲染
const renamedList = computed(() =>
  Object.entries(importResult.value?.renamed || {}).map(([from, to]) => ({ from, to }))
)
const canImport = computed(() =>
  importMode.value === 'file' ? !!importFile.value : !!importText.value.trim()
)

function openImportDialog() {
  importMode.value = 'file'
  importFile.value = null
  importText.value = ''
  importResult.value = null
  importVisible.value = true
}

function onImportFileChange(file) {
  // accept 属性只管文件选择器,拖拽进来的文件需要手动校验扩展名
  const name = (file.name || '').toLowerCase()
  if (!name.endsWith('.json') && !name.endsWith('.ncx')) {
    ElMessage.warning('仅支持 .json 或 .ncx 文件')
    uploadRef.value?.clearFiles()
    importFile.value = null
    return
  }
  importFile.value = file.raw || null
}

/** 超出 limit 时替换为最新选择的文件(handleStart 会再次触发 on-change) */
function onImportFileExceed(files) {
  uploadRef.value.clearFiles()
  uploadRef.value.handleStart(files[0])
}

function onImportFileRemove() {
  importFile.value = null
}

async function doImport() {
  if (!canImport.value || importing.value) return
  importing.value = true
  try {
    const formData = new FormData()
    if (importMode.value === 'file') {
      formData.append('file', importFile.value)
    } else {
      formData.append('text', importText.value)
    }
    importResult.value = await request.post('/datasources/import', formData)
  } finally {
    importing.value = false
  }
}

/** 对话框完全关闭后:有成功导入则刷新列表,并重置状态供下次打开 */
function onImportClosed() {
  if ((importResult.value?.imported?.length || 0) > 0) loadList()
  importResult.value = null
  importFile.value = null
  importText.value = ''
}

// 首页是常驻页签,用 onActivated 保证每次切回都刷新(首次挂载也会触发)
onActivated(loadList)
</script>

<style scoped>
/* 授权栏钉在视口底部:卡片撑满主区域,flex 布局配合 LicenseFooter 的 margin-top:auto */
.page-card {
  display: flex;
  flex-direction: column;
  min-height: calc(100% - 40px);
  box-sizing: border-box;
}
.ds-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}
.ds-card :deep(.el-card__body) {
  position: relative;
}
/* 卡片整体可点击(等同浏览库);未设密码时禁用并显示禁用光标 */
.ds-card {
  cursor: pointer;
  transition: transform 0.2s ease, border-color 0.2s ease, background-color 0.2s ease;
}
/* hover 高亮 + 上浮悬浮效果 */
.ds-card:hover {
  transform: translateY(-3px);
  border-color: var(--el-color-primary-light-5);
  background-color: var(--el-fill-color-extra-light);
}
/* 未设密码:整卡浅红警示(底色+边框),搭配标题前红色叹号图标 */
.ds-no-password {
  cursor: not-allowed;
  border-color: var(--el-color-danger-light-5);
  background-color: var(--el-color-danger-light-9);
}
/* hover 时警示色加深一档,保持可感知但不误导为可点击 */
.ds-no-password:hover {
  border-color: var(--el-color-danger-light-3);
  background-color: var(--el-color-danger-light-8);
}
/* 未设密码数据源标题前的红色叹号图标 */
.ds-error-icon {
  color: var(--el-color-danger);
  font-size: 16px;
}
.ds-bg-icon {
  position: absolute;
  right: 6px;
  bottom: 6px;
  opacity: 0.12;
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
  color: var(--el-text-color-secondary);
  width: 48px;
  flex-shrink: 0;
}
.ds-value {
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ds-actions {
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-extra-light);
}
.db-option {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.ssh-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
/* 编辑对话框(DataGrip 风格):顶部名称+驱动、行内小标签、底部左对齐测试连接 */
.dg-head {
  display: flex;
  gap: 12px;
  margin-bottom: 4px;
}
.dg-name {
  flex: 1;
}
.dg-driver {
  width: 220px;
  flex-shrink: 0;
}
.dg-tabs {
  margin-top: 8px;
}
.dg-inline {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.dg-inline-label {
  color: var(--el-text-color-regular);
  font-size: 14px;
  flex-shrink: 0;
}
.dg-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
/* 库过滤页签:加载按钮行 + 全选行 + 勾选列表 */
.sf-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.sf-all {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 8px;
}
.sf-count {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.sf-list {
  display: flex;
  flex-direction: column;
  max-height: 260px;
  overflow-y: auto;
}
.sf-current {
  line-height: 1.8;
}
/* 导出对话框:全选行 + 勾选项列表 */
.export-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}
.export-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.export-list {
  display: flex;
  flex-direction: column;
  max-height: 320px;
  overflow-y: auto;
}
.export-item {
  width: 100%;
  height: auto;
  margin-right: 0;
  padding: 6px 0;
}
.export-item :deep(.el-checkbox__label) {
  flex: 1;
  overflow: hidden;
}
.export-item-main {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
}
.export-item-name {
  color: var(--el-text-color-primary);
  font-weight: 600;
  white-space: nowrap;
}
.export-item-url {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.export-tip {
  margin-top: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
/* 导入对话框:说明与结果明细 */
.import-mode {
  margin-bottom: 12px;
}
.import-tip {
  margin-top: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.import-summary {
  margin-bottom: 12px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
}
.import-names {
  margin-bottom: 12px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.8;
}
.import-rename-item {
  margin-bottom: 4px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}
.import-alert {
  margin-bottom: 8px;
}
.section-title {
  margin: 12px 0 8px;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 600;
}
</style>
