<template>
  <!-- 新增/编辑数据源:DataGrip 风格 —— 顶部名称+驱动,常规/SSH 隧道/库过滤/高级分页,测试连接固定在左下 -->
  <el-dialog v-model="visible" :title="form.id ? '编辑数据源' : '新增数据源'" width="640px" destroy-on-close :close-on-press-escape="false">
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
          <el-form-item label="分组">
            <el-select v-model="form.groupName" filterable allow-create default-first-option clearable
              placeholder="可输入新分组或选择已有分组" style="width: 100%">
              <el-option v-for="g in groups" :key="g" :label="g" :value="g" />
            </el-select>
          </el-form-item>
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
          <span class="ssh-tip">勾选需要显示的库;全部勾选(或不加载)表示不过滤。系统库已默认不勾选,可按需勾回。</span>
        </div>
        <template v-if="schemaFetched">
          <div class="sf-all">
            <el-checkbox :model-value="schemaCheckAll" :indeterminate="schemaIndeterminate" @change="onSchemaCheckAll">全部</el-checkbox>
            <span class="sf-count">已选 {{ schemaChecked.length }} / {{ schemaList.length }}</span>
          </div>
          <el-checkbox-group v-model="schemaChecked" class="sf-list sf-grid">
            <el-checkbox v-for="db in schemaList" :key="db" :value="db">
              {{ db }}<span v-if="isSystemSchema(db)" class="sf-sys-tag">系统</span>
            </el-checkbox>
          </el-checkbox-group>
        </template>
        <div v-else-if="form.schemaFilter?.length" class="ssh-tip sf-current">
          当前仅显示 {{ form.schemaFilter.length }} 个库:{{ form.schemaFilter.join('、') }}。点击「加载库列表」可修改。
        </div>
        <el-empty v-else description="当前不过滤,显示全部库" :image-size="60" />
      </el-tab-pane>
      <el-tab-pane label="高级" name="advanced">
        <el-form :model="form" label-width="120px" autocomplete="off">
          <template v-if="form.dbType === 'SQLSERVER'">
            <el-form-item label="加密(encrypt)">
              <el-select v-model="form.mssqlEncrypt" style="width: 100%">
                <el-option label="true(加密连接)" value="true" />
                <el-option label="false(不加密)" value="false" />
              </el-select>
            </el-form-item>
            <el-form-item label="信任服务器证书">
              <el-select v-model="form.mssqlTrustServerCertificate" style="width: 100%">
                <el-option label="true(自签名证书也接受)" value="true" />
                <el-option label="false(严格校验证书)" value="false" />
              </el-select>
            </el-form-item>
            <el-form-item label="TLS 协议版本">
              <el-select v-model="form.mssqlSslProtocol" style="width: 100%">
                <el-option label="TLSv1.1(兼容老版本 SQL Server)" value="TLSv1.1" />
                <el-option label="TLSv1" value="TLSv1" />
                <el-option label="TLSv1.2" value="TLSv1.2" />
                <el-option label="TLSv1.3" value="TLSv1.3" />
                <el-option label="TLS(驱动默认,自动协商)" value="TLS" />
              </el-select>
            </el-form-item>
            <div class="ssh-tip">SQL Server 连接参数,保存/测试连接时自动拼入 JDBC URL。老版本 SQL Server(2014 及更早,未打 TLS 1.2 补丁)选 TLSv1.1;连不上时再尝试其他版本。</div>
          </template>
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
        <div class="dg-footer-left">
          <el-button :loading="testing" @click="onTest">测试连接</el-button>
          <!-- DataGrip 风格:按钮右侧状态图标,悬停弹出详细信息;测试成功后主动弹出一次,点击其他位置才消失 -->
          <el-popover v-if="testResult" v-model:visible="testDetailVisible" placement="top" trigger="hover" :width="380" popper-class="dg-test-popper">
            <template #reference>
              <el-icon class="dg-test-icon" :class="testResult.ok ? 'ok' : 'fail'">
                <CircleCheck v-if="testResult.ok" />
                <CircleClose v-else />
              </el-icon>
            </template>
            <div class="dg-test-detail">
              <div class="dg-test-title" :class="testResult.ok ? 'ok' : 'fail'">
                {{ testResult.ok ? '连接成功' : '连接失败' }}
              </div>
              <template v-if="testResult.ok">
                <div class="dg-test-line">DBMS: {{ testResult.detail.dbmsName }} (ver. {{ testResult.detail.dbmsVersion }})</div>
                <div class="dg-test-line">驱动: {{ testResult.detail.driverName }} (ver. {{ testResult.detail.driverVersion }})</div>
                <div class="dg-test-line" v-if="testResult.detail.dbMode">兼容模式: {{ testResult.detail.dbMode }}</div>
                <div class="dg-test-line">Ping: {{ testResult.detail.pingMs }} ms</div>
                <div class="dg-test-line">SSL: {{ testResult.detail.ssl ? 'yes' : 'no' }}</div>
              </template>
              <div v-else class="dg-test-error">{{ testResult.message }}</div>
            </div>
          </el-popover>
        </div>
        <div>
          <el-button @click="visible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from '../utils/notify'
import { CircleCheck, CircleClose } from '@element-plus/icons-vue'
import request from '../api'
import DbTypeIcon from './DbTypeIcon.vue'
import { notifyDsListChanged } from '../utils/dsListChanged'

/**
 * 数据源新增/编辑弹窗,完全独立可在任意页面使用(数据源页卡片、抽样导出任务明细等)。
 * - v-model 控制显隐;打开时按 ds 初始化表单(新增传 null/不传)
 * - groups 传已有分组名列表,供「分组」下拉选择(默认空,可手输新分组)
 * - 保存成功 emit('saved') 并内部广播侧边栏数据源列表刷新,父组件只需刷新自己的列表
 */
const props = defineProps({
  modelValue: Boolean,
  ds: { type: Object, default: null },
  groups: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue', 'saved'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const saving = ref(false)
const testing = ref(false)
// 最近一次连接测试结果:{ ok, message?, detail? };null 表示尚未测试。行内图标+悬停详情展示,不弹全局通知
const testResult = ref(null)
// 详情浮层可见性(v-model 非受控,hover 仍生效;成功后置 true 主动弹出,点击其他位置才关闭)
const testDetailVisible = ref(false)

// 主动弹出后监听全局点击:点在浮层/图标以外才关闭
function onDocClickHideTestDetail(e) {
  if (e.target.closest('.dg-test-popper') || e.target.closest('.dg-test-icon')) return
  testDetailVisible.value = false
  document.removeEventListener('click', onDocClickHideTestDetail, true)
}
function autoShowTestDetail() {
  testDetailVisible.value = true
  document.removeEventListener('click', onDocClickHideTestDetail, true)
  document.addEventListener('click', onDocClickHideTestDetail, true)
}
onBeforeUnmount(() => document.removeEventListener('click', onDocClickHideTestDetail, true))
// 当前页签:general / ssh / schemas / advanced
const activeTab = ref('general')

const DB_TYPES = ['MYSQL', 'POSTGRESQL', 'DM', 'KINGBASE', 'OCEANBASE', 'SQLSERVER', 'ORACLE', 'HIGHGO']
const URL_PLACEHOLDERS = {
  MYSQL: 'jdbc:mysql://host:3306/',
  POSTGRESQL: 'jdbc:postgresql://host:5432/db',
  DM: 'jdbc:dm://host:5236',
  KINGBASE: 'jdbc:kingbase8://host:54321/db',
  OCEANBASE: 'jdbc:oceanbase://host:2881/',
  SQLSERVER: 'jdbc:sqlserver://host:1433;databaseName=db',
  ORACLE: 'jdbc:oracle:thin:@//host:1521/service',
  HIGHGO: 'jdbc:highgo://host:5866/db',
}

const DEFAULT_PORTS = {
  MYSQL: 3306,
  POSTGRESQL: 5432,
  DM: 5236,
  KINGBASE: 54321,
  OCEANBASE: 2881,
  SQLSERVER: 1433,
  ORACLE: 1521,
  HIGHGO: 5866,
}

const emptyForm = () => ({
  id: null,
  name: '',
  groupName: '',
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
  sizeThresholdBytes: null,
  // SQL Server 连接参数(「高级」页签,仅 SQLSERVER 生效;保存/测试时拼入 JDBC URL,
  // 默认组合兼容未打 TLS 1.2 补丁的老版本 SQL Server)
  mssqlEncrypt: 'true',
  mssqlTrustServerCertificate: 'true',
  mssqlSslProtocol: 'TLSv1.1',
})
const form = reactive(emptyForm())

// ---------- 库过滤页签(DataGrip Schemas 页风格) ----------
// 页签内的勾选状态独立于 form.schemaFilter:只有点过「加载库列表」才以勾选为准,否则保留已存值
const schemaList = ref([])
const schemaChecked = ref([])
const schemaFetched = ref(false)
const schemaLoading = ref(false)
// 系统库/schema 名(后端按 dbType 返回,小写),库过滤默认不勾选并打「系统」标注
const systemSchemas = ref([])

/** 大小写不敏感判断库名是否为系统库 */
function isSystemSchema(name) {
  return systemSchemas.value.includes(String(name).toLowerCase())
}
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
  const strKeys = ['host', 'database', 'jdbcUrl', 'username', 'sshHost', 'sshUsername', 'sshAuthMethod',
    'mssqlEncrypt', 'mssqlTrustServerCertificate', 'mssqlSslProtocol']
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
    form.sshPassword, form.sshPrivateKey, form.sshPassphrase, form.inputMode,
    form.mssqlEncrypt, form.mssqlTrustServerCertificate, form.mssqlSslProtocol],
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
    // 系统库清单与库列表并行拉取;系统清单失败不阻塞(退化为全勾)
    const sysPromise = request.get(`/db-types/${form.dbType}/system-schemas`).catch(() => [])
    let dbs
    if (form.id && !connDirty()) {
      dbs = await request.get(`/datasources/${form.id}/databases?all=true`)
      if (!dbs?.length) {
        dbs = await request.get(`/datasources/${form.id}/schemas?all=true`)
      }
    } else {
      if (!syncJdbcUrl()) return
      const jdbcUrl = composeJdbcUrl()
      if (!jdbcUrl || !form.username) {
        activeTab.value = 'general'
        ElMessage.warning('请先填写 JDBC 地址和用户名')
        return
      }
      if (!validateSsh()) return
      const res = await request.post('/datasources/preview-databases', {
        jdbcUrl,
        username: form.username,
        password: form.password,
        ...buildSshBody(),
        id: form.id ?? undefined,
      })
      if (!res.success) {
        ElMessage.error(res.message || '拉取库列表失败')
        return
      }
      dbs = res.databases || []
    }
    schemaList.value = dbs || []
    systemSchemas.value = await sysPromise
    // 已配置白名单时回填勾选(只勾仍存在于目标库中的);未配置时默认勾选业务库、系统库不勾
    schemaChecked.value = form.schemaFilter?.length
      ? schemaList.value.filter((db) => form.schemaFilter.includes(db))
      : schemaList.value.filter((db) => !isSystemSchema(db))
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
    case 'HIGHGO': return `jdbc:highgo://${h}:${p}/${db}`
    default: return ''
  }
}

// SQL Server 连接参数键(「高级」页签管理,从 URL 中剥离后不再出现在 URL 输入框里)
const MSSQL_URL_PARAM_KEYS = ['encrypt', 'trustServerCertificate', 'sslProtocol']

/** 从 SQL Server JDBC URL 中剥离高级页签管理的连接参数;返回 { url, params } */
function extractMssqlParams(url) {
  const params = {}
  const parts = (url || '').split(';')
  const kept = [parts[0]]
  for (const seg of parts.slice(1)) {
    const key = seg.split('=')[0].trim().toLowerCase()
    const hit = MSSQL_URL_PARAM_KEYS.find((k) => k.toLowerCase() === key)
    if (hit) {
      params[hit] = seg.slice(seg.indexOf('=') + 1).trim()
    } else {
      kept.push(seg)
    }
  }
  return { url: kept.join(';'), params }
}

/**
 * 组装最终 JDBC URL:SQLSERVER 时把「高级」页签的连接参数拼回 URL。
 * 先剥后拼,用户在 URL 里手写过这些参数也不会重复。
 */
function composeJdbcUrl() {
  let url = (form.jdbcUrl || '').trim()
  if (form.dbType !== 'SQLSERVER' || !url.startsWith('jdbc:sqlserver:')) return url
  url = extractMssqlParams(url).url
  return `${url};encrypt=${form.mssqlEncrypt};trustServerCertificate=${form.mssqlTrustServerCertificate};sslProtocol=${form.mssqlSslProtocol}`
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
    sshPassphrase: form.sshPassphrase,
  }
}

/** 弹窗打开时按传入的数据源行初始化表单(新增态 ds 为 null);每次打开都重置,避免残留上次状态 */
function initForm(row) {
  Object.assign(form, emptyForm())
  form.port = DEFAULT_PORTS[form.dbType] || null
  activeTab.value = 'general'
  schemaList.value = []
  schemaChecked.value = []
  schemaFetched.value = false
  if (row) {
    form.id = row.id
    form.name = row.name
    form.groupName = row.groupName || ''
    form.dbType = row.dbType || 'MYSQL'
    form.jdbcUrl = row.jdbcUrl
    // SQL Server:连接参数从 URL 剥离到「高级」页签字段,缺的回落默认值
    if (form.dbType === 'SQLSERVER') {
      const extracted = extractMssqlParams(row.jdbcUrl)
      form.jdbcUrl = extracted.url
      if (extracted.params.encrypt) form.mssqlEncrypt = extracted.params.encrypt
      if (extracted.params.trustServerCertificate) form.mssqlTrustServerCertificate = extracted.params.trustServerCertificate
      if (extracted.params.sslProtocol) form.mssqlSslProtocol = extracted.params.sslProtocol
    }
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
        sshPort: form.sshPort, sshUsername: form.sshUsername, sshAuthMethod: form.sshAuthMethod,
        mssqlEncrypt: form.mssqlEncrypt, mssqlTrustServerCertificate: form.mssqlTrustServerCertificate,
        mssqlSslProtocol: form.mssqlSslProtocol,
      }
    : null
}

watch(() => props.modelValue, (visible) => {
  if (visible) {
    initForm(props.ds)
  } else {
    // 弹窗关闭时清掉测试结果与可能挂着的全局点击监听
    testResult.value = null
    testDetailVisible.value = false
    document.removeEventListener('click', onDocClickHideTestDetail, true)
  }
})

async function onTest() {
  testResult.value = null
  testDetailVisible.value = false
  if (!syncJdbcUrl()) return
  const jdbcUrl = composeJdbcUrl()
  if (!jdbcUrl || !form.username) {
    activeTab.value = 'general'
    ElMessage.warning('请先填写 JDBC 地址和用户名')
    return
  }
  if (!validateSsh()) return
  testing.value = true
  try {
    const res = await request.post('/datasources/test', {
      name: form.name,
      jdbcUrl,
      username: form.username,
      password: form.password,
      ...buildSshBody(),
      // 编辑态:密码/SSH 秘密留空时由服务端回落已存值
      id: form.id ?? undefined,
    }, { _silent: true })
    if (res.success) {
      testResult.value = { ok: true, detail: res.detail || {} }
      // 成功后主动弹出详情一次,点击其他位置才消失
      autoShowTestDetail()
    } else {
      testResult.value = { ok: false, message: res.message || '连接失败' }
    }
  } catch (err) {
    // 409 等错误映射(如 SSH 隧道连接失败)也走行内反馈
    testResult.value = { ok: false, message: err.response?.data?.message || err.message || '连接失败' }
  } finally {
    testing.value = false
  }
}

async function onSave() {
  if (!syncJdbcUrl()) return
  const jdbcUrl = composeJdbcUrl()
  if (!form.name || !jdbcUrl || !form.username) {
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
      groupName: form.groupName?.trim() || null,
      jdbcUrl,
      username: form.username,
      password: form.password,
      ...buildSshBody(),
      schemaFilter: currentSchemaFilter(),
      rowThreshold: form.rowThreshold ?? undefined,
      sizeThresholdBytes: form.sizeThresholdBytes ?? undefined,
    }
    if (form.id) {
      await request.put(`/datasources/${form.id}`, body)
    } else {
      await request.post('/datasources', body)
    }
    ElMessage.success('保存成功')
    visible.value = false
    // 广播列表变更:侧边栏数据源菜单即时刷新(分组/名称可能已变);父组件列表由 saved 事件负责
    notifyDsListChanged()
    emit('saved')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
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
.dg-footer-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 连接测试结果:状态图标 + 悬停详情(DataGrip 风格) */
.dg-test-icon {
  font-size: 16px;
  cursor: default;
}
.dg-test-icon.ok {
  color: var(--el-color-success);
}
.dg-test-icon.fail {
  color: var(--el-color-danger);
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
  padding-bottom: 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 12px;
}
.sf-count {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
/* 库过滤系统库标注:小号灰底标签 */
.sf-sys-tag {
  margin-left: 6px;
  padding: 0 4px;
  font-size: 11px;
  line-height: 16px;
  border-radius: 3px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color);
}
.sf-list {
  display: flex;
  flex-direction: column;
  max-height: 400px;
  overflow-y: auto;
}
/* 库过滤列表:双列网格(与库列表页库过滤弹窗同风格) */
.sf-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 16px;
}
.sf-list :deep(.el-checkbox) {
  margin-right: 0;
  height: auto;
  padding: 7px 8px;
  border-radius: 4px;
  transition: background-color 0.15s;
}
.sf-list :deep(.el-checkbox:hover) {
  background-color: var(--el-fill-color-light);
}
.sf-list :deep(.el-checkbox__label) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sf-current {
  line-height: 1.8;
}
</style>

<!-- 连接测试详情弹层挂在 body 下,scoped 样式不生效,单独全局块(以 popper-class 限定范围) -->
<style>
.dg-test-popper .dg-test-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.dg-test-popper .dg-test-title.ok {
  color: var(--el-color-success);
}
.dg-test-popper .dg-test-title.fail {
  color: var(--el-color-danger);
}
.dg-test-popper .dg-test-line {
  font-size: 12px;
  line-height: 1.7;
  word-break: break-all;
}
.dg-test-popper .dg-test-error {
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
  white-space: pre-wrap;
}
</style>
