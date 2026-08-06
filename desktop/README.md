# desktop — dq-tool 桌面版

dq-tool 的纯 JVM 桌面重写版:Kotlin + JetBrains Compose Multiplatform Desktop,去掉 Web 前端与 Spring Boot,UI 进程内直接调用服务层。根 Gradle 多模块构建的子模块,命令统一在仓库根目录执行。

## 技术栈

- Kotlin 2.4 + Compose Multiplatform 1.11(Desktop)
- H2(本地存储,库表结构与原 Web 版一致)+ HikariCP
- 手写 JDBC 薄封装(`repository/Jdbc.kt`,替代 JdbcTemplate)
- Jackson(JSON 列 / AI 接口)、Apache POI(Excel 导出)、SLF4J + Logback
- 7 种数据库 JDBC 驱动:MySQL、PostgreSQL、SQL Server、Oracle、达梦 DM8、人大金仓 KingbaseES、OceanBase(仅 MySQL 模式)
- 测试:JUnit 5 + Testcontainers(MySQL 8 / PG 15 / SQL Server 2019)

## 构建与运行

要求:JDK 21+(开发机 25 验证通过),无需安装 Gradle(wrapper 自带)。

```bash
./gradlew :desktop:run          # 启动桌面应用,数据目录默认 ./data
./gradlew :desktop:test         # 全部测试(单元 + Testcontainers,需 Docker)
./gradlew :desktop:packageDmg   # macOS 安装包(desktop/build/compose/binaries/main/dmg/)
./gradlew :desktop:packageMsi / :desktop:packageDeb   # Windows / Linux(需在对应平台构建)
```

数据目录优先级:`-Ddq.data.dir` > 环境变量 `DQ_DATA_DIR` > `./data`。

## 配置

数据目录下放 `config.properties` 可覆盖默认值(键名沿用原 application.yml 的点分层级):

```properties
dq.scan.workers=8                  # 全局扫描工作线程数
dq.scan.chunks-per-table=100       # 每张表的分段数
dq.scan.row-threshold=1000000      # 超过该估算行数默认采样
dq.scan.size-threshold-bytes=10737418240   # 超过该体积(10GB)默认采样
dq.scan.sample-rows=100000         # 采样行数
dq.scan.statement-timeout-seconds=1800     # 单条统计 SQL 超时
dq.security.secret=change-me-32bytes-secret-key-0000   # 数据源密码 AES-GCM 密钥
ai.api-key=                        # AI 接口默认 key(页面未配置时回落)
ai.base-url=                       # AI 接口默认地址
ai.model=                          # AI 默认模型
```

## 与原 Web 版的关系

- 业务逻辑(方言/扫描/仓储/服务)从 `../server/src/main/java` 逐行平移,类名/方法名保持一致,SQL 一字未改
- H2 库表结构不变,可直接读老库的 `data/dqconfig.mv.db`(密码密文兼容,默认密钥相同)
- 原工程的 controller/Web 层未平移(桌面版不需要);功能差异见各页面实现说明
