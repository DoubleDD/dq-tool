package com.example.dq.model;

/** 数据源配置(存 H2) */
public class DataSourceConfig {

    private Long id;
    private String name;
    private DbType dbType;
    private String jdbcUrl;
    private String username;
    /** 解密后的密码,仅在内存和请求中使用,不出库 */
    private String password;
    private Long rowThreshold;
    private Long sizeThresholdBytes;
    /** 数据库兼容模式(如 Kingbase 的 pg/oracle/mysql),保存数据源时探测,可空 */
    private String dbMode;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public DbType getDbType() { return dbType; }
    public void setDbType(DbType dbType) { this.dbType = dbType; }
    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Long getRowThreshold() { return rowThreshold; }
    public void setRowThreshold(Long rowThreshold) { this.rowThreshold = rowThreshold; }
    public Long getSizeThresholdBytes() { return sizeThresholdBytes; }
    public void setSizeThresholdBytes(Long sizeThresholdBytes) { this.sizeThresholdBytes = sizeThresholdBytes; }
    public String getDbMode() { return dbMode; }
    public void setDbMode(String dbMode) { this.dbMode = dbMode; }
}
