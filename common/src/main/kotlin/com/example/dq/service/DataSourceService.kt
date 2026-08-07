package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.DbType
import com.example.dq.model.TestConnectionRequest
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.util.CryptoUtil
import com.example.dq.util.JdbcUrlRewriter
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap

/** 数据源配置管理 + 动态连接池;启用 SSH 隧道的数据源经 SshTunnelService 本地端口转发连接 */
class DataSourceService(
    private val repo: DataSourceRepository,
    private val crypto: CryptoUtil,
    private val dialectFactory: DialectFactory,
    private val config: AppConfig,
    private val schemaStatRepo: SchemaStatRepository,
    private val sshTunnelService: SshTunnelService = SshTunnelService(),
) {

    private val pools = ConcurrentHashMap<Long, HikariDataSource>()

    /** 各数据源的默认库(建池时首个连接的 catalog);多库方言在 database 为空时回落到这里 */
    private val defaultCatalogs = ConcurrentHashMap<Long, String>()

    fun list(): List<DataSourceConfig> {
        val all = repo.findAll()
        all.forEach {
            // 置空前先按密文判空:无密码只可能来自导入(新建密码必填、编辑留空沿用旧值)
            it.hasPassword = !it.password.isNullOrEmpty()
            it.password = null // 不出库秘密字段
            it.sshPassword = null
            it.sshPrivateKey = null
            it.sshPassphrase = null
        }
        return all
    }

    fun get(id: Long): DataSourceConfig {
        val c = repo.findById(id)
            ?: throw IllegalArgumentException("数据源不存在: $id")
        c.password = crypto.decrypt(c.password)
        c.sshPassword = crypto.decrypt(c.sshPassword)
        c.sshPrivateKey = crypto.decrypt(c.sshPrivateKey)
        c.sshPassphrase = crypto.decrypt(c.sshPassphrase)
        return c
    }

    fun create(req: DataSourceRequest): Long {
        val c = DataSourceConfig()
        apply(c, req)
        c.dbMode = detectDbMode(req, req.password, req.sshPassword, req.sshPrivateKey, req.sshPassphrase)
        c.password = crypto.encrypt(req.password)
        c.sshPassword = crypto.encrypt(req.sshPassword)
        c.sshPrivateKey = crypto.encrypt(req.sshPrivateKey)
        c.sshPassphrase = crypto.encrypt(req.sshPassphrase)
        return repo.insert(c)
    }

    fun update(id: Long, req: DataSourceRequest) {
        val c = repo.findById(id)
            ?: throw IllegalArgumentException("数据源不存在: $id")
        // 密码留空表示沿用旧密码;探测需用真实密码连接
        val plainPassword = if (!req.password.isNullOrEmpty()) req.password else crypto.decrypt(c.password)
        // SSH 三个秘密字段同样「留空沿用旧值」,各自独立的条件位
        val updateSshPassword = !req.sshPassword.isNullOrEmpty()
        val updateSshPrivateKey = !req.sshPrivateKey.isNullOrEmpty()
        val updateSshPassphrase = !req.sshPassphrase.isNullOrEmpty()
        val plainSshPassword = if (updateSshPassword) req.sshPassword else crypto.decrypt(c.sshPassword)
        val plainSshPrivateKey = if (updateSshPrivateKey) req.sshPrivateKey else crypto.decrypt(c.sshPrivateKey)
        val plainSshPassphrase = if (updateSshPassphrase) req.sshPassphrase else crypto.decrypt(c.sshPassphrase)
        apply(c, req)
        c.dbMode = detectDbMode(req, plainPassword, plainSshPassword, plainSshPrivateKey, plainSshPassphrase)
        c.id = id
        val updatePassword = !req.password.isNullOrEmpty()
        if (updatePassword) {
            c.password = crypto.encrypt(req.password)
        }
        if (updateSshPassword) {
            c.sshPassword = crypto.encrypt(req.sshPassword)
        }
        if (updateSshPrivateKey) {
            c.sshPrivateKey = crypto.encrypt(req.sshPrivateKey)
        }
        if (updateSshPassphrase) {
            c.sshPassphrase = crypto.encrypt(req.sshPassphrase)
        }
        repo.update(c, updatePassword, updateSshPassword, updateSshPrivateKey, updateSshPassphrase)
        evictPool(id)
    }

    fun delete(id: Long) {
        repo.delete(id)
        schemaStatRepo.deleteByDatasource(id)
        evictPool(id)
    }

    /** 单独更新库过滤白名单(库列表页弹窗);null/空列表表示不过滤。只动这一列,不影响其他配置 */
    fun updateSchemaFilter(id: Long, filter: List<String>?) {
        val c = repo.findById(id)
            ?: throw IllegalArgumentException("数据源不存在: $id")
        c.schemaFilter = filter
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
        repo.update(c, updatePassword = false)
    }

    /** 测试连接(不落库,直接用请求参数);返回探测到的数据库兼容模式,无为 null */
    @Throws(SQLException::class)
    fun testConnection(req: TestConnectionRequest): String? {
        val type = DbType.fromJdbcUrl(req.jdbcUrl)
        val dialect = dialectFactory.get(type)
        try {
            Class.forName(dialect.driverClassName())
        } catch (e: ClassNotFoundException) {
            throw SQLException("JDBC 驱动未加载: " + dialect.driverClassName(), e)
        }
        return withOptionalTunnel(req) { url ->
            DriverManager.getConnection(url, req.username, req.password).use { conn ->
                dialect.detectDbMode(conn)
            }
        }
    }

    /** 编辑对话框「库过滤」页签:用请求中的连接参数(可经 SSH 一次性隧道)拉取目标库的库名列表,不落库 */
    @Throws(SQLException::class)
    fun previewDatabases(req: TestConnectionRequest): List<String> {
        val type = DbType.fromJdbcUrl(req.jdbcUrl)
        val dialect = dialectFactory.get(type)
        try {
            Class.forName(dialect.driverClassName())
        } catch (e: ClassNotFoundException) {
            throw SQLException("JDBC 驱动未加载: " + dialect.driverClassName(), e)
        }
        return withOptionalTunnel(req) { url ->
            DriverManager.getConnection(url, req.username, req.password).use { conn ->
                // 只有多库方言(SQL Server)实现 listDatabases;其余方言的「库」就是 schema 列表(MySQL 的 schema 即库)
                val databases = dialect.listDatabases(conn)
                if (databases.isNotEmpty()) databases else dialect.listSchemas(conn)
            }
        }
    }

    /** 探测数据库兼容模式(如 Kingbase 的 database_mode);失败返回 null,不影响保存 */
    private fun detectDbMode(
        req: DataSourceRequest,
        password: String?,
        sshPassword: String?,
        sshPrivateKey: String?,
        sshPassphrase: String?,
    ): String? {
        return try {
            val dialect = dialectFactory.get(DbType.fromJdbcUrl(req.jdbcUrl))
            Class.forName(dialect.driverClassName())
            val testReq = TestConnectionRequest(
                jdbcUrl = req.jdbcUrl, username = req.username, password = password,
                sshEnabled = req.sshEnabled, sshHost = req.sshHost, sshPort = req.sshPort,
                sshUsername = req.sshUsername, sshAuthMethod = req.sshAuthMethod,
                sshPassword = sshPassword, sshPrivateKey = sshPrivateKey, sshPassphrase = sshPassphrase)
            withOptionalTunnel(testReq) { url ->
                DriverManager.getConnection(url, req.username, password).use { conn ->
                    dialect.detectDbMode(conn)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 启用 SSH 隧道时建一次性隧道并把 JDBC URL 改写为本地转发端口;未启用直接透传原 URL */
    private fun <T> withOptionalTunnel(req: TestConnectionRequest, block: (String) -> T): T {
        val jdbcUrl = req.jdbcUrl!!
        if (req.sshEnabled != true) {
            return block(jdbcUrl)
        }
        return sshTunnelService.openOneShot(
            sshHost = req.sshHost, sshPort = req.sshPort, sshUsername = req.sshUsername,
            sshAuthMethod = req.sshAuthMethod, sshPassword = req.sshPassword,
            sshPrivateKey = req.sshPrivateKey, sshPassphrase = req.sshPassphrase,
            jdbcUrl = jdbcUrl,
        ).use { tunnel ->
            block(JdbcUrlRewriter.rewrite(jdbcUrl, tunnel.localPort))
        }
    }

    @Throws(SQLException::class)
    fun getConnection(datasourceId: Long): Connection =
        pools.computeIfAbsent(datasourceId) { createPool(it) }.connection

    private fun createPool(datasourceId: Long): HikariDataSource {
        val c = get(datasourceId)
        val dialect = dialectFactory.get(c.dbType!!)
        val hc = HikariConfig()
        hc.poolName = "ds-$datasourceId"
        // 启用 SSH 隧道时经长驻隧道连接:URL 改写为本地转发端口,隧道随 evictPool 关闭
        hc.jdbcUrl = if (c.sshEnabled == true) {
            val localPort = sshTunnelService.ensureTunnel(datasourceId, c)
            JdbcUrlRewriter.rewrite(c.jdbcUrl!!, localPort)
        } else {
            c.jdbcUrl
        }
        hc.username = c.username
        hc.password = c.password
        hc.driverClassName = dialect.driverClassName()
        hc.maximumPoolSize = config.scan.workers + 2 // worker 占满时给元数据查询留余量
        hc.minimumIdle = 1
        hc.connectionTimeout = 30_000
        hc.idleTimeout = 300_000
        hc.maxLifetime = 1_800_000
        val ds = HikariDataSource(hc)
        // 连接池归还连接不重置 catalog,记录默认库供 useDatabase 回落,避免串库
        try {
            ds.connection.use { conn ->
                // 部分驱动(如 Oracle)没有 catalog 概念,getCatalog() 返回 null,而 CHM 不允许 null 值
                val catalog = conn.catalog
                if (catalog != null) {
                    defaultCatalogs[datasourceId] = catalog
                }
            }
        } catch (e: SQLException) {
            ds.close()
            throw IllegalStateException("数据源连接失败: $datasourceId", e)
        }
        return ds
    }

    /** 解析目标库:显式指定优先,否则回落到数据源默认库 */
    fun resolveDatabase(datasourceId: Long, database: String?): String? =
        if (!database.isNullOrBlank()) database else defaultCatalogs[datasourceId]

    private fun evictPool(id: Long) {
        val ds = pools.remove(id)
        defaultCatalogs.remove(id)
        ds?.close()
        sshTunnelService.close(id)
    }

    /** 复制非秘密字段(秘密字段由 create/update 按「留空不改」规则单独处理) */
    private fun apply(c: DataSourceConfig, req: DataSourceRequest) {
        c.name = req.name
        c.jdbcUrl = req.jdbcUrl
        c.dbType = DbType.fromJdbcUrl(req.jdbcUrl)
        c.username = req.username
        c.rowThreshold = req.rowThreshold
        c.sizeThresholdBytes = req.sizeThresholdBytes
        // 库过滤白名单:去空白去重,空列表归一为 null(不过滤)
        c.schemaFilter = req.schemaFilter
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
        c.sshEnabled = req.sshEnabled
        c.sshHost = req.sshHost
        c.sshPort = req.sshPort
        c.sshUsername = req.sshUsername
        c.sshAuthMethod = req.sshAuthMethod
    }
}
