package com.example.dq.repository

import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import java.sql.ResultSet
import java.sql.Types

class DataSourceRepository(private val jdbc: Jdbc) {

    /** password_enc 与 SSH 秘密列已加密存储,读取后由调用方解密 */
    private val mapper: (ResultSet) -> DataSourceConfig = { rs ->
        val c = DataSourceConfig()
        c.id = rs.getLong("id")
        c.name = rs.getString("name")
        c.dbType = DbType.valueOf(rs.getString("db_type"))
        c.jdbcUrl = rs.getString("jdbc_url")
        c.username = rs.getString("username")
        c.password = rs.getString("password_enc")
        val rt = rs.getLong("row_threshold")
        c.rowThreshold = if (rs.wasNull()) null else rt
        val st = rs.getLong("size_threshold_bytes")
        c.sizeThresholdBytes = if (rs.wasNull()) null else st
        c.dbMode = rs.getString("db_mode")
        c.schemaFilter = decodeSchemaFilter(rs.getString("schema_filter"))
        c.groupName = rs.getString("group_name")
        c.sshEnabled = rs.getBoolean("ssh_enabled")
        c.sshHost = rs.getString("ssh_host")
        val sp = rs.getInt("ssh_port")
        c.sshPort = if (rs.wasNull()) null else sp
        c.sshUsername = rs.getString("ssh_username")
        c.sshAuthMethod = rs.getString("ssh_auth_method")
        c.sshPassword = rs.getString("ssh_password_enc")
        c.sshPrivateKey = rs.getString("ssh_private_key_enc")
        c.sshPassphrase = rs.getString("ssh_passphrase_enc")
        c.connStatus = rs.getString("conn_status")
        c.connError = rs.getString("conn_error")
        c.connCheckedAt = rs.getTimestamp("conn_checked_at")?.toLocalDateTime()
        c
    }

    fun insert(c: DataSourceConfig): Long =
        jdbc.insert(
            "INSERT INTO data_source(name, db_type, jdbc_url, username, password_enc, row_threshold, size_threshold_bytes, db_mode, schema_filter, group_name, " +
                    "ssh_enabled, ssh_host, ssh_port, ssh_username, ssh_auth_method, ssh_password_enc, ssh_private_key_enc, ssh_passphrase_enc) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            c.name, c.dbType!!.name, c.jdbcUrl, c.username, c.password, c.rowThreshold, c.sizeThresholdBytes, c.dbMode,
            encodeSchemaFilter(c.schemaFilter), c.groupName,
            c.sshEnabled == true, c.sshHost, c.sshPort, c.sshUsername, c.sshAuthMethod,
            c.sshPassword, c.sshPrivateKey, c.sshPassphrase)

    /**
     * 更新数据源;秘密字段照「留空不改」模板:对应 update 标记为 true 才更新该列,否则保留旧值
     */
    fun update(
        c: DataSourceConfig,
        updatePassword: Boolean,
        updateSshPassword: Boolean = false,
        updateSshPrivateKey: Boolean = false,
        updateSshPassphrase: Boolean = false,
    ) {
        val sql = "UPDATE data_source SET name=?, db_type=?, jdbc_url=?, username=?, row_threshold=?, size_threshold_bytes=?, db_mode=?, schema_filter=?, group_name=?, " +
                "ssh_enabled=?, ssh_host=?, ssh_port=?, ssh_username=?, ssh_auth_method=?, updated_at=CURRENT_TIMESTAMP" +
                (if (updatePassword) ", password_enc=?" else "") +
                (if (updateSshPassword) ", ssh_password_enc=?" else "") +
                (if (updateSshPrivateKey) ", ssh_private_key_enc=?" else "") +
                (if (updateSshPassphrase) ", ssh_passphrase_enc=?" else "") +
                " WHERE id=?"
        jdbc.withStatement({ conn -> conn.prepareStatement(sql) }) { ps ->
            ps.setString(1, c.name)
            ps.setString(2, c.dbType!!.name)
            ps.setString(3, c.jdbcUrl)
            ps.setString(4, c.username)
            val rt = c.rowThreshold
            if (rt != null) ps.setLong(5, rt) else ps.setNull(5, Types.BIGINT)
            val st = c.sizeThresholdBytes
            if (st != null) ps.setLong(6, st) else ps.setNull(6, Types.BIGINT)
            ps.setString(7, c.dbMode)
            ps.setString(8, encodeSchemaFilter(c.schemaFilter))
            ps.setString(9, c.groupName)
            ps.setBoolean(10, c.sshEnabled == true)
            ps.setString(11, c.sshHost)
            val sp = c.sshPort
            if (sp != null) ps.setInt(12, sp) else ps.setNull(12, Types.INTEGER)
            ps.setString(13, c.sshUsername)
            ps.setString(14, c.sshAuthMethod)
            var idx = 15
            if (updatePassword) ps.setString(idx++, c.password)
            if (updateSshPassword) ps.setString(idx++, c.sshPassword)
            if (updateSshPrivateKey) ps.setString(idx++, c.sshPrivateKey)
            if (updateSshPassphrase) ps.setString(idx++, c.sshPassphrase)
            ps.setLong(idx, c.id!!)
            ps.executeUpdate()
        }
    }

    fun delete(id: Long) {
        jdbc.update("DELETE FROM data_source WHERE id=?", id)
    }

    /**
     * 单独更新连接状态标记(抽样导出检测/导出前复测后写);只动 conn_status/conn_error/conn_checked_at 三列。
     * 「修复后保留历史错误」靠调用方把旧 conn_error 原样传回实现
     */
    fun updateConnStatus(id: Long, status: String?, error: String?) {
        jdbc.update("UPDATE data_source SET conn_status=?, conn_error=?, conn_checked_at=CURRENT_TIMESTAMP WHERE id=?",
            status, error, id)
    }

    /**
     * 连接信息被编辑(DataSourceService.update)后清除连接状态标记,三列全部回到「未检测」。
     * 旧错误消息随旧连接信息一并过时,不再保留
     */
    fun clearConnStatus(id: Long) {
        jdbc.update("UPDATE data_source SET conn_status=NULL, conn_error=NULL, conn_checked_at=NULL WHERE id=?", id)
    }

    /** 单独更新分组名(数据源卡片拖拽改分组);只动 group_name 一列,null 表示未分组 */
    fun updateGroup(id: Long, groupName: String?) {
        jdbc.update("UPDATE data_source SET group_name=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
            groupName, id)
    }

    /** 单独更新数据源名(抽样导入「同名数据源以表格名称为主」);只动 name 一列,连接状态标记不动 */
    fun updateName(id: Long, name: String) {
        jdbc.update("UPDATE data_source SET name=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", name, id)
    }

    fun findById(id: Long): DataSourceConfig? =
        jdbc.queryOne("SELECT * FROM data_source WHERE id=?", id, mapper = mapper)

    fun findAll(): List<DataSourceConfig> =
        jdbc.query("SELECT * FROM data_source ORDER BY id", mapper = mapper)

    companion object {
        /** 库过滤白名单落库为逗号分隔串;空串/NULL 解码为 null(不过滤) */
        fun encodeSchemaFilter(filter: List<String>?): String? =
            filter?.takeIf { it.isNotEmpty() }?.joinToString(",")

        fun decodeSchemaFilter(raw: String?): List<String>? =
            raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
    }
}
