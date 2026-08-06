package com.example.dq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dq")
public class DqProperties {

    private final Scan scan = new Scan();
    private final Security security = new Security();
    private final License license = new License();
    private final Desktop desktop = new Desktop();

    public Scan getScan() {
        return scan;
    }

    public Security getSecurity() {
        return security;
    }

    public License getLicense() {
        return license;
    }

    public Desktop getDesktop() {
        return desktop;
    }

    public static class Scan {
        /** 全局扫描工作线程数 */
        private int workers = 8;
        /** 每张表的分段数 */
        private int chunksPerTable = 100;
        /** 超过该估算行数默认采样 */
        private long rowThreshold = 1_000_000L;
        /** 超过该体积(字节)默认采样 */
        private long sizeThresholdBytes = 10L * 1024 * 1024 * 1024;
        /** 采样行数 */
        private long sampleRows = 100_000L;
        /** 单条统计 SQL 超时(秒) */
        private int statementTimeoutSeconds = 1800;

        public int getWorkers() { return workers; }
        public void setWorkers(int workers) { this.workers = workers; }
        public int getChunksPerTable() { return chunksPerTable; }
        public void setChunksPerTable(int chunksPerTable) { this.chunksPerTable = chunksPerTable; }
        public long getRowThreshold() { return rowThreshold; }
        public void setRowThreshold(long rowThreshold) { this.rowThreshold = rowThreshold; }
        public long getSizeThresholdBytes() { return sizeThresholdBytes; }
        public void setSizeThresholdBytes(long sizeThresholdBytes) { this.sizeThresholdBytes = sizeThresholdBytes; }
        public long getSampleRows() { return sampleRows; }
        public void setSampleRows(long sampleRows) { this.sampleRows = sampleRows; }
        public int getStatementTimeoutSeconds() { return statementTimeoutSeconds; }
        public void setStatementTimeoutSeconds(int statementTimeoutSeconds) { this.statementTimeoutSeconds = statementTimeoutSeconds; }
    }

    public static class Security {
        /** 数据源密码对称加密密钥 */
        private String secret = "change-me-32bytes-secret-key-0000";

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }

    public static class License {
        /** 授权码验签公钥(Ed25519,base64 的 X.509 编码);为空时激活接口报"未配置授权公钥" */
        private String publicKey = "";

        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    }

    public static class Desktop {
        /** 页面心跳超时(秒):--app 窗口关闭后超过该时长未收到心跳则退出进程;<=0 禁用看门狗 */
        private int shutdownTimeoutSeconds = 45;

        public int getShutdownTimeoutSeconds() { return shutdownTimeoutSeconds; }
        public void setShutdownTimeoutSeconds(int shutdownTimeoutSeconds) { this.shutdownTimeoutSeconds = shutdownTimeoutSeconds; }
    }
}
