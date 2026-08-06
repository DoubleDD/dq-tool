package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.DbType
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.util.CryptoUtil
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap

/** 数据源配置管理 + 动态连接池 */
class DataSourceService(
    private val repo: DataSourceRepository,
    private val crypto: CryptoUtil,
    private val dialectFactory: DialectFactory,
    private val config: AppConfig,
    private val schemaStatRepo: SchemaStatRepository,
) {

    private val pools = ConcurrentHashMap<Long, HikariDataSource>()

    /** 各数据源的默认库(建池时首个连接的 catalog);多库方言在 database 为空时回落到这里 */
    private val defaultCatalogs = ConcurrentHashMap<Long, String>()

    fun list(): List<DataSourceConfig> {
        val all = repo.findAll()
        all.forEach { it.password = null } // 不出库密码
        return all
    }

    fun get(id: Long): DataSourceConfig {
        val c = repo.findById(id)
            ?: throw IllegalArgumentException("数据源不存在: $id")
        c.password = crypto.decrypt(c.password)
        return c
    }

    fun create(req: DataSourceRequest): Long {
        val c = DataSourceConfig()
        apply(c, req)
        c.dbMode = detectDbMode(req)
        c.password = crypto.encrypt(req.password)
        return repo.insert(c)
    }

    fun update(id: Long, req: DataSourceRequest) {
        val c = repo.findById(id)
            ?: throw IllegalArgumentException("数据源不存在: $id")
        // 密码留空表示沿用旧密码;探测需用真实密码连接
        val plainPassword = if (!req.password.isNullOrEmpty()) req.password else crypto.decrypt(c.password)
        apply(c, req)
        c.dbMode = detectDbMode(req, plainPassword)
        c.id = id
        val updatePassword = !req.password.isNullOrEmpty()
        if (updatePassword) {
            c.password = crypto.encrypt(req.password)
        }
        repo.update(c, updatePassword)
        evictPool(id)
    }

    fun delete(id: Long) {
        repo.delete(id)
        schemaStatRepo.deleteByDatasource(id)
        evictPool(id)
    }

    /** 测试连接(不落库,直接用请求参数);返回探测到的数据库兼容模式,无为 null */
    @Throws(SQLException::class)
    fun testConnection(jdbcUrl: String, username: String, password: String): String? {
        val type = DbType.fromJdbcUrl(jdbcUrl)
        val dialect = dialectFactory.get(type)
        try {
            Class.forName(dialect.driverClassName())
        } catch (e: ClassNotFoundException) {
            throw SQLException("JDBC 驱动未加载: " + dialect.driverClassName(), e)
        }
        DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
            return dialect.detectDbMode(conn)
        }
    }

    /** 探测数据库兼容模式(如 Kingbase 的 database_mode);失败返回 null,不影响保存 */
    private fun detectDbMode(req: DataSourceRequest): String? = detectDbMode(req, req.password)

    private fun detectDbMode(req: DataSourceRequest, password: String?): String? {
        return try {
            val dialect = dialectFactory.get(DbType.fromJdbcUrl(req.jdbcUrl))
            Class.forName(dialect.driverClassName())
            DriverManager.getConnection(req.jdbcUrl, req.username, password).use { conn ->
                dialect.detectDbMode(conn)
            }
        } catch (e: Exception) {
            null
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
        hc.jdbcUrl = c.jdbcUrl
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
    }

    private fun apply(c: DataSourceConfig, req: DataSourceRequest) {
        c.name = req.name
        c.jdbcUrl = req.jdbcUrl
        c.dbType = DbType.fromJdbcUrl(req.jdbcUrl)
        c.username = req.username
        c.rowThreshold = req.rowThreshold
        c.sizeThresholdBytes = req.sizeThresholdBytes
    }
}
