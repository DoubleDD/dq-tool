# AGENTS.md — dq-tool 数据质量检测工具

> 本文件面向 AI 编码代理,描述该项目的一切背景。项目文档与代码注释统一使用中文。

## 项目概述

dq-tool 是一个轻量级单体 Web 应用,用于对关系型数据库做数据质量检测:

- 表级:估算行数、数据+索引占用一览
- 字段级:NULL / 空串 / 自定义空值规则统计与有值率
- 大表并发分段扫描(按主键/唯一键切分)、真实进度、断点续扫、Excel 导出
- 数据源连接信息存本地 H2 文件库,密码 AES-GCM 加密

支持 7 种数据库:MySQL、PostgreSQL、SQL Server、Oracle、达梦 DM8、人大金仓 KingbaseES、OceanBase(仅 MySQL 模式)。驱动全部来自 Maven 中央仓库。

无登录/权限控制,**仅适合内网单机部署**。

## 技术栈

- 后端:Java 21、Spring Boot 3.4.5(web / jdbc / validation)、H2(本地存储)、Apache POI(Excel 导出)、Maven
- 前端:Vue 3 + Vue Router + Element Plus + axios,Vite 5 构建(无 TypeScript、无状态库,`stores/tabs.js` 为自写简易 store)
- 测试:JUnit 5 + Spring Boot Test + Testcontainers(MySQL 8 / PG 15 / SQL Server 2019)
- 交付:Spring Boot fat jar(内嵌前端);jpackage 生成 dmg / exe / deb 原生安装包(内嵌 JRE)

## 项目结构

```
pom.xml                      后端构建(7 个 JDBC 驱动、前端产物拷贝配置)
src/main/java/com/example/dq/
  DqApplication.java         入口;启动时自动避让被占用的端口(8080 起向后探测 100 个)
  config/                    DqProperties(配置绑定)、SpaWebConfig(SPA 路由回退)、BrowserOpener(安装版自动开浏览器)
  controller/                REST API:/api/datasources、/api/datasources/{dsId}/{databases,schemas,schema-stats,schemas/{schema}/tables}、/api/scans
  dialect/                   核心抽象 DbDialect + 7 个方言实现 + DialectFactory + AbstractDialect
  model/                     DTO/枚举(DbType、ScanStatus、NullRule、Range 等)
  repository/                手写 JdbcTemplate 仓储(无 JPA/MyBatis)
  scan/                      ScanExecutor(全局线程池,调度单元=分段)、ChunkRunner(分段执行)、InterruptRecovery
  service/                   DataSourceService(连接池/密码加解密)、MetadataService、ScanService(任务生命周期)、ExportService(POI 流式导出)
  util/CryptoUtil.java       AES-GCM 加解密
src/main/resources/
  application.yml            全部可调配置(dq.scan.* / dq.security.secret)
  schema.sql                 H2 建表(含 scan_job_event 任务状态变更事件表)+ 老库升级的 ALTER ... IF NOT EXISTS,启动自动执行
src/test/java/com/example/dq/
  dialect/                   方言 SQL 生成、分段规划单元测试
  scan/ScanFlowTest.java     Testcontainers 端到端(MySQL/PG/SQLServer 三容器)
web/                         前端 Vue 工程(src/views 六个页面含 Dashboard 任务看板 + router + api)
docker-test-env/             手动验证用的 SQL Server / Oracle docker-compose(非 CI 使用)
scripts/                     package-mac.sh / package-win.bat / package-linux.sh(jpackage 打包)
.github/workflows/release.yml  推 v* tag 触发全平台安装包构建并挂到 GitHub Release
data/                        运行期生成的 H2 数据文件(勿提交改动)
```

## 构建与运行

要求:JDK 21+、Maven 3.8+、Node 18+(仅开发模式)。

```bash
# 开发模式:后端 8080 + 前端 5173(代理 /api 到 8080)
mvn spring-boot:run
cd web && npm install && npm run dev

# 交付:单 jar 内嵌前端 —— 必须先构建前端,再 mvn package
cd web && npm install && npm run build     # 产物 web/dist
cd .. && mvn package                        # prepare-package 阶段自动把 web/dist 拷进 jar 的 static/
java -jar target/dq-tool-0.1.0.jar          # 访问 http://localhost:8080
```

注意:`mvn package` 不会自动构建前端;`web/dist` 缺失或过期时 jar 内静态资源即为旧版/缺失。

## 测试

```bash
mvn test
```

- 单元测试:方言 SQL 生成、规则谓词转义、分段键选择(不需要 Docker)
- H2 分段正确性:分段累加 == 全表单条 SQL
- Testcontainers 集成测试(需要 Docker):MySQL 8 / PG 15 / SQL Server 2019 真实容器上的并发分段全链路、空值规则、注释、导出
  - OrbStack 用户若报 "Could not find a valid Docker environment":
    `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock mvn test`
  - Testcontainers 版本固定 1.21.4(pom.xml 有注释:旧版默认 API 1.32 被 Docker 29+ 拒绝)
- **达梦 / 人大金仓 / OceanBase / Oracle 无自动化覆盖**,相关方言改动只能接真实环境手动验证(`docker-test-env/` 有 SQL Server 和 Oracle 的 compose)

## 代码约定

- 注释、提交信息、文档全部使用中文;代码标识符用英文
- 分层:controller(薄)→ service(业务)→ repository(手写 JdbcTemplate)→ dialect(库差异抽象)。**所有数据库差异都必须收敛在 `dialect/` 包内**,service/scan 层不允许出现库特定的 SQL 或分支
- 新增数据库支持 = 实现 `DbDialect` 接口 + 在 `DialectFactory` 注册 + `DbType` 枚举 + pom 加驱动 + README 更新
- 扫描的调度单元是"分段(chunk)",不是表:分段状态持久化在 `scan_chunk` 表,断点续扫只重跑未完成分段
- 修改库表结构时:`schema.sql` 用 `CREATE TABLE IF NOT EXISTS` + 文件末尾追加 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 兼容老库,不允许破坏性变更
- 前端无 lint/格式化配置,跟随现有代码风格(2 空格缩进、单文件组件)
- 大表默认采样估算(行数 > 100 万或体积 > 10GB,阈值可在数据源级别覆盖);MySQL/达梦/OB 的采样是 LIMIT 顺序采样,结果有偏,UI 需标注"估算值"
- Oracle 把空字符串存为 NULL,空串统计恒为 0,这是数据库本身行为,不是 bug

## 安全考虑

- 数据源密码用 AES-GCM 加密存 H2(`password_enc` 列),密钥在 `application.yml` 的 `dq.security.secret`,默认值 `change-me-...`,改动密钥逻辑时注意向后兼容已存数据
- 应用无认证,任何能访问端口的人都能操作所有数据源,不要暴露到公网
- 自定义空值规则(如 `status IN (0,-1)`)会原样拼进统计 SQL,属设计如此的"信任内网用户"行为;不要在方言层之外引入新的 SQL 拼接,标识符必须经 `DbDialect.quote()` 处理
- H2 数据文件含连接信息与扫描结果,`data/` 目录不应提交或外发

## 发布流程

- 推 `v*` tag(如 `git tag v1.0 && git push origin v1.0`)触发 `.github/workflows/release.yml`,并行构建:Windows exe(x64 + ARM64)、macOS dmg(Apple Silicon + Intel)、Linux deb,全部挂到 GitHub Release
- jpackage 不支持交叉编译,各平台包在对应系统的 runner 上原生构建;Windows exe 需要 WiX Toolset 3.x(CI 中用 choco 安装)
- 本地打包用 `scripts/package-{mac,linux}.sh` / `scripts\package-win.bat`,可加 `--skip-build` 只重打包
- 安装包要求主版本号 ≥ 1,脚本把项目版本 `0.1.0` 映射为安装包版本 `1.0`
- 安装版数据目录固定为 `~/.dq-tool/data`(jar 方式为 `./data`),由 jpackage 的 `--java-options` 注入,修改打包脚本时保持这一区分
