package com.example.dq.service;

import com.example.dq.config.DqProperties;
import com.example.dq.dialect.DbDialect;
import com.example.dq.dialect.DialectFactory;
import com.example.dq.model.DataSourceConfig;
import com.example.dq.model.DataSourceRequest;
import com.example.dq.model.DbType;
import com.example.dq.repository.DataSourceRepository;
import com.example.dq.util.CryptoUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 数据源配置管理 + 动态连接池 */
@Service
public class DataSourceService {

    private final DataSourceRepository repo;
    private final CryptoUtil crypto;
    private final DialectFactory dialectFactory;
    private final DqProperties props;
    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();
    /** 各数据源的默认库(建池时首个连接的 catalog);多库方言在 database 为空时回落到这里 */
    private final Map<Long, String> defaultCatalogs = new ConcurrentHashMap<>();

    public DataSourceService(DataSourceRepository repo, CryptoUtil crypto,
                             DialectFactory dialectFactory, DqProperties props) {
        this.repo = repo;
        this.crypto = crypto;
        this.dialectFactory = dialectFactory;
        this.props = props;
    }

    public List<DataSourceConfig> list() {
        List<DataSourceConfig> all = repo.findAll();
        all.forEach(c -> c.setPassword(null)); // 不出库密码
        return all;
    }

    public DataSourceConfig get(long id) {
        DataSourceConfig c = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + id));
        c.setPassword(crypto.decrypt(c.getPassword()));
        return c;
    }

    public long create(DataSourceRequest req) {
        DataSourceConfig c = new DataSourceConfig();
        apply(c, req);
        c.setDbMode(detectDbMode(req));
        c.setPassword(crypto.encrypt(req.password()));
        return repo.insert(c);
    }

    public void update(long id, DataSourceRequest req) {
        DataSourceConfig c = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + id));
        // 密码留空表示沿用旧密码;探测需用真实密码连接
        String plainPassword = (req.password() != null && !req.password().isEmpty())
                ? req.password() : crypto.decrypt(c.getPassword());
        apply(c, req);
        c.setDbMode(detectDbMode(req, plainPassword));
        c.setId(id);
        boolean updatePassword = req.password() != null && !req.password().isEmpty();
        if (updatePassword) {
            c.setPassword(crypto.encrypt(req.password()));
        }
        repo.update(c, updatePassword);
        evictPool(id);
    }

    public void delete(long id) {
        repo.delete(id);
        evictPool(id);
    }

    /** 测试连接(不落库,直接用请求参数);返回探测到的数据库兼容模式,无为 null */
    public String testConnection(String jdbcUrl, String username, String password) throws SQLException {
        DbType type = DbType.fromJdbcUrl(jdbcUrl);
        DbDialect dialect = dialectFactory.get(type);
        try {
            Class.forName(dialect.driverClassName());
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC 驱动未加载: " + dialect.driverClassName(), e);
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            return dialect.detectDbMode(conn);
        }
    }

    /** 探测数据库兼容模式(如 Kingbase 的 database_mode);失败返回 null,不影响保存 */
    private String detectDbMode(DataSourceRequest req) {
        return detectDbMode(req, req.password());
    }

    private String detectDbMode(DataSourceRequest req, String password) {
        try {
            DbDialect dialect = dialectFactory.get(DbType.fromJdbcUrl(req.jdbcUrl()));
            Class.forName(dialect.driverClassName());
            try (Connection conn = DriverManager.getConnection(req.jdbcUrl(), req.username(), password)) {
                return dialect.detectDbMode(conn);
            }
        } catch (Exception e) {
            return null;
        }
    }

    public Connection getConnection(long datasourceId) throws SQLException {
        return pools.computeIfAbsent(datasourceId, this::createPool).getConnection();
    }

    private HikariDataSource createPool(long datasourceId) {
        DataSourceConfig c = get(datasourceId);
        DbDialect dialect = dialectFactory.get(c.getDbType());
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("ds-" + datasourceId);
        hc.setJdbcUrl(c.getJdbcUrl());
        hc.setUsername(c.getUsername());
        hc.setPassword(c.getPassword());
        hc.setDriverClassName(dialect.driverClassName());
        hc.setMaximumPoolSize(props.getScan().getWorkers() + 2); // worker 占满时给元数据查询留余量
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(30_000);
        hc.setIdleTimeout(300_000);
        hc.setMaxLifetime(1_800_000);
        HikariDataSource ds = new HikariDataSource(hc);
        // 连接池归还连接不重置 catalog,记录默认库供 useDatabase 回落,避免串库
        try (Connection conn = ds.getConnection()) {
            // 部分驱动(如 Oracle)没有 catalog 概念,getCatalog() 返回 null,而 CHM 不允许 null 值
            String catalog = conn.getCatalog();
            if (catalog != null) {
                defaultCatalogs.put(datasourceId, catalog);
            }
        } catch (SQLException e) {
            ds.close();
            throw new IllegalStateException("数据源连接失败: " + datasourceId, e);
        }
        return ds;
    }

    /** 解析目标库:显式指定优先,否则回落到数据源默认库 */
    public String resolveDatabase(long datasourceId, String database) {
        return (database != null && !database.isBlank()) ? database : defaultCatalogs.get(datasourceId);
    }

    private void evictPool(long id) {
        HikariDataSource ds = pools.remove(id);
        defaultCatalogs.remove(id);
        if (ds != null) {
            ds.close();
        }
    }

    private void apply(DataSourceConfig c, DataSourceRequest req) {
        c.setName(req.name());
        c.setJdbcUrl(req.jdbcUrl());
        c.setDbType(DbType.fromJdbcUrl(req.jdbcUrl()));
        c.setUsername(req.username());
        c.setRowThreshold(req.rowThreshold());
        c.setSizeThresholdBytes(req.sizeThresholdBytes());
    }
}
