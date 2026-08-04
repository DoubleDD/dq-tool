package com.example.dq.repository;

import com.example.dq.model.DataSourceConfig;
import com.example.dq.model.DbType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class DataSourceRepository {

    private final JdbcTemplate jdbc;

    public DataSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** password_enc 已加密存储,读取后由调用方解密 */
    private final RowMapper<DataSourceConfig> mapper = (rs, i) -> {
        DataSourceConfig c = new DataSourceConfig();
        c.setId(rs.getLong("id"));
        c.setName(rs.getString("name"));
        c.setDbType(DbType.valueOf(rs.getString("db_type")));
        c.setJdbcUrl(rs.getString("jdbc_url"));
        c.setUsername(rs.getString("username"));
        c.setPassword(rs.getString("password_enc"));
        long rt = rs.getLong("row_threshold");
        c.setRowThreshold(rs.wasNull() ? null : rt);
        long st = rs.getLong("size_threshold_bytes");
        c.setSizeThresholdBytes(rs.wasNull() ? null : st);
        return c;
    };

    public long insert(DataSourceConfig c) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO data_source(name, db_type, jdbc_url, username, password_enc, row_threshold, size_threshold_bytes) "
                            + "VALUES (?,?,?,?,?,?,?)", new String[]{"ID"});
            ps.setString(1, c.getName());
            ps.setString(2, c.getDbType().name());
            ps.setString(3, c.getJdbcUrl());
            ps.setString(4, c.getUsername());
            ps.setString(5, c.getPassword());
            if (c.getRowThreshold() != null) ps.setLong(6, c.getRowThreshold()); else ps.setNull(6, java.sql.Types.BIGINT);
            if (c.getSizeThresholdBytes() != null) ps.setLong(7, c.getSizeThresholdBytes()); else ps.setNull(7, java.sql.Types.BIGINT);
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    public void update(DataSourceConfig c, boolean updatePassword) {
        String sql = "UPDATE data_source SET name=?, db_type=?, jdbc_url=?, username=?, row_threshold=?, size_threshold_bytes=?, updated_at=CURRENT_TIMESTAMP"
                + (updatePassword ? ", password_enc=?" : "") + " WHERE id=?";
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, c.getName());
            ps.setString(2, c.getDbType().name());
            ps.setString(3, c.getJdbcUrl());
            ps.setString(4, c.getUsername());
            if (c.getRowThreshold() != null) ps.setLong(5, c.getRowThreshold()); else ps.setNull(5, java.sql.Types.BIGINT);
            if (c.getSizeThresholdBytes() != null) ps.setLong(6, c.getSizeThresholdBytes()); else ps.setNull(6, java.sql.Types.BIGINT);
            int idx = 7;
            if (updatePassword) ps.setString(idx++, c.getPassword());
            ps.setLong(idx, c.getId());
            return ps;
        });
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM data_source WHERE id=?", id);
    }

    public Optional<DataSourceConfig> findById(long id) {
        List<DataSourceConfig> list = jdbc.query("SELECT * FROM data_source WHERE id=?", mapper, id);
        return list.stream().findFirst();
    }

    public List<DataSourceConfig> findAll() {
        return jdbc.query("SELECT * FROM data_source ORDER BY id", mapper);
    }
}
