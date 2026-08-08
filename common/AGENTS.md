# AGENTS.md — common(共享业务内核)

> 本文件面向 AI 编码代理。这是 dq-tool 的共享内核模块(`:common`),承载 server(Javalin Web 版)的全部业务逻辑。项目文档与代码注释统一使用中文。

## 定位与边界

- **纯 Kotlin JVM 库,无任何 Web/桌面框架依赖**:不得引入 Javalin、Servlet 等壳层依赖
- **纯阻塞 API**:禁止 `suspend fun`,异步/轮询由壳层负责
- **内部 JSON 只用 Jackson 2**(`com.fasterxml`,如 AiService/ScanService/ChunkRunner 自持 mapper);server web 层的 Jackson 3(`tools.jackson`)不得 import 进内核
- 所有数据库差异收敛在 `dialect/` 包,service/scan 层不允许出现库特定 SQL 或分支;标识符必须经 `DbDialect.quote()`
- 扫描调度单元是分段(chunk),状态持久化在 `scan_chunk`,断点续扫只重跑未完成分段

## 结构

```
build.gradle.kts           kotlin("jvm")(版本在根声明)+ HikariCP(api 暴露)/H2/POI/Jackson2/Flyway/slf4j/7 个 JDBC 驱动 runtimeOnly
src/main/kotlin/com/example/dq/
  config/AppConfig.kt      内核配置(data class):dataDir/scan/securitySecret/ai 默认值/licensePublicKey;
                           server 由 KernelConfigAdapter 从 yml 映射
  dialect/                 DbDialect + AbstractDialect + 7 方言 + DialectFactory(object 单例)
  model/                   data class/枚举;3 个请求类带 @field:NotNull/@field:NotBlank(server 用 hibernate-validator 触发)
  repository/              Jdbc.kt 薄封装 + 8 仓储 + SchemaInit(Flyway 迁移封装)
  scan/                    ScanExecutor(线程池)/ ChunkRunner(表 DONE 后联动 TagService 自动打/摘「空表」标记 + 提交 AutoTagService 异步入队)/ InterruptRecovery
  service/                 12 个服务(含 LicenseService、DataSourceTransferService 数据源导入导出、SshTunnelService SSH 隧道本地端口转发、AutoTagService 扫描后 AI 自动打标——2 worker 守护线程池,表注释/字段注释/表描述全空时抽样 100 行业务数据发给 LLM 选 USER 标记,幂等只增不删,LLM 调用点可注入 fake 便于单测,构造器注入)
  license/LicenseCodec.kt  授权码编解码与 Ed25519 验签(object 纯函数)
  env/ServiceEnv.kt        服务组装器:H2 连接池 + Flyway 迁移 + 全部 service,server 启动时构建一次;
                           对 Java 友好(属性即 getter,dataSource 暴露给 server 的 AppShutdown)
  util/CryptoUtil.kt       AES-GCM(数据库密文);TransferCrypto(导出文件固定口令)/NavicatCrypto(.ncx 密码解密);
                           JdbcUrlRewriter(JDBC URL host:port 解析与改写,SSH 隧道用)
src/main/resources/db/migration/
  V1__baseline.sql         共有表全量建表
  V2__license_info.sql     授权信息表 + 老库列补齐(ALTER IF NOT EXISTS)
  V3__tag.sql              表标记:tag_def(含系统「空表」标记幂等插入)+ table_tag(外键级联删除)
  V4__data_source_ssh.sql  数据源 SSH 隧道配置列(ALTER IF NOT EXISTS;三个秘密字段加密存储)
  V5__data_source_schema_filter.sql  数据源库过滤白名单列(ALTER IF NOT EXISTS;逗号分隔,空=不过滤)
  V6__license_record.sql   授权码签发留档表(授权码管理,仅配置签发私钥的管理员实例;含绑定的软件版本号)
  V7__scan_job_auto_tag.sql  scan_job.auto_tag 列(AI 自动打标开关,ALTER IF NOT EXISTS;持久化保证断点续扫/重启后续扫仍读到开关)
src/test/kotlin/           方言/分段/级联删除/标记(TagRepository/TagService)/AI prompt/授权码/Flyway 迁移单测 + Testcontainers 端到端(MySQL/PG/SQLServer;SSH 隧道 SshTunnelIntegrationTest:linuxserver/openssh-server 跳板机 + MySQL 网络别名,注意该镜像 sshd 监听 2222 且默认 AllowTcpForwarding no 需 custom-cont-init.d 打开)
```

## 库表结构变更(取代旧 schema.sql 追加 ALTER 规则)

- 一律新增 `db/migration/V{n}__描述.sql`;**已发布的迁移文件禁止修改**(Flyway checksum 校验)
- 不允许破坏性变更(DROP/改列类型);新库从 V1 全量执行,老库 baseline 后增量执行,两条路径必须收敛
- `SchemaInit.run` 已配置 `baselineOnMigrate(true)` + `baselineVersion("1")`,勿改
- **快速路径(2026-08 启动优化)**:迁移脚本随软件版本固定,`SchemaInit.run` 先查 `flyway_schema_history`
  最新成功版本并与 classpath 扫描出的最大脚本版本比较,已是最新(≥)直接跳过 Flyway(实测省 ~0.8s 启动);
  无 history 表(新库/老库)、脚本扫描失败等一切异常都回落执行 Flyway,宁慢勿错。返回 Boolean 表示是否实际执行

## 构建与测试

```bash
./gradlew :common:test    # 在仓库根目录执行;Testcontainers 需 Docker
```

- OrbStack 用户:`DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock ./gradlew :common:test`
- 达梦/金仓/OceanBase/Oracle 方言无自动化覆盖,改动只能接真实环境手动验证
- LLM 实际调用无自动化覆盖(prompt 组装有 AiServiceTest)
