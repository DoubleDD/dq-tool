package com.example.dq.repository

import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import java.sql.ResultSet
import java.sql.Types

class DataSourceRepository(private val jdbc: Jdbc) {

    /** password_enc 已加密存储,读取后由调用方解密 */
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
        c
    }

    fun insert(c: DataSourceConfig): Long =
        jdbc.insert(
            "INSERT INTO data_source(name, db_type, jdbc_url, username, password_enc, row_threshold, size_threshold_bytes, db_mode) " +
                    "VALUES (?,?,?,?,?,?,?,?)",
            c.name, c.dbType!!.name, c.jdbcUrl, c.username, c.password, c.rowThreshold, c.sizeThresholdBytes, c.dbMode)

    fun update(c: DataSourceConfig, updatePassword: Boolean) {
        val sql = "UPDATE data_source SET name=?, db_type=?, jdbc_url=?, username=?, row_threshold=?, size_threshold_bytes=?, db_mode=?, updated_at=CURRENT_TIMESTAMP" +
                (if (updatePassword) ", password_enc=?" else "") + " WHERE id=?"
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
            var idx = 8
            if (updatePassword) ps.setString(idx++, c.password)
            ps.setLong(idx, c.id!!)
            ps.executeUpdate()
        }
    }

    fun delete(id: Long) {
        jdbc.update("DELETE FROM data_source WHERE id=?", id)
    }

    fun findById(id: Long): DataSourceConfig? =
        jdbc.queryOne("SELECT * FROM data_source WHERE id=?", id, mapper = mapper)

    fun findAll(): List<DataSourceConfig> =
        jdbc.query("SELECT * FROM data_source ORDER BY id", mapper = mapper)
}
