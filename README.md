# dq-tool 数据质量检测工具

轻量 Web 应用,对关系型数据库做数据质量检测:表级行数/磁盘占用一览,字段级 NULL/空串/自定义空值规则统计与有值率,支持大表并发分段扫描、真实进度、断点续扫和 Excel 导出。

## 支持的数据库

| 数据库 | 说明 |
|---|---|
| MySQL | `jdbc:mysql://host:3306/` |
| PostgreSQL | `jdbc:postgresql://host:5432/db` |
| SQL Server | `jdbc:sqlserver://host:1433;databaseName=db` |
| Oracle | `jdbc:oracle:thin:@//host:1521/service` |
| 达梦 DM8 | `jdbc:dm://host:5236` |
| 人大金仓 KingbaseES | `jdbc:kingbase8://host:54321/db` |
| OceanBase(MySQL 模式) | `jdbc:oceanbase://host:2881/` |

七个驱动均来自 Maven 中央仓库,无需手动安装。

说明:

- Oracle 把空字符串存为 NULL,空串统计恒为 0(NULL 数已覆盖),这是数据库本身的行为
- SQL Server / Oracle / 达梦 / 人大金仓 / OceanBase 无公开便捷 Docker 镜像或环境,自动化测试只覆盖 MySQL / PostgreSQL / SQL Server,其余库接入真实环境后请先用小库验证
- SQL Server 的"库"指 schema(dbo 等),数据库(DATABASE)在浏览库页面下拉选择,jdbcUrl 中的 databaseName 仅作默认库;表/字段注释读扩展属性 `MS_Description`
- Oracle / 达梦 / 人大金仓 / OceanBase 无自动化测试环境,接入真实环境后请先用小库验证(MySQL / PostgreSQL / SQL Server 有 Testcontainers 端到端覆盖)

## 快速开始

要求:JDK 21+、Node 18+(仅开发模式需要)、Maven 3.8+。

开发模式:

```bash
# 后端(8080)
mvn spring-boot:run

# 前端(5173,代理 /api 到 8080)
cd web && npm install && npm run dev
```

打包交付(单 jar 内嵌前端):

```bash
cd web && npm install && npm run build   # 产物在 web/dist
cd .. && mvn package                     # prepare-package 阶段自动把 web/dist 拷进 jar
java -jar target/dq-tool-0.1.0.jar
```

> 访问 http://localhost:8080 即可使用(开发模式前端在 5173)。

原生安装包(jpackage,内嵌 JRE,目标机器无需安装 Java):

```bash
# macOS → target/jpackage/dist/dq-tool-1.0.dmg
scripts/package-mac.sh

# Windows(在 Windows 机器上执行)→ target/jpackage/dist/dq-tool-1.0.exe
scripts\package-win.bat

# Linux(Debian/Ubuntu,需 fakeroot)→ target/jpackage/dist/dq-tool_1.0_amd64.deb
scripts/package-linux.sh
```

推荐走 CI 全平台构建:推 `v*` tag(如 `git tag v1.0 && git push origin v1.0`)触发 `.github/workflows/release.yml`,云端并行构建 Windows exe、macOS dmg(Apple Silicon + Intel 两个架构)、Linux deb,全部自动挂到 GitHub Release。jpackage 不支持交叉编译,各平台包都在对应系统的 runner 上原生构建。

- 脚本自动完成前端构建 + `mvn package` + jpackage;只重打包可加 `--skip-build`
- dmg/exe 安装包要求主版本号 ≥ 1,脚本把项目版本 `0.1.0` 映射为安装包版本 `1.0`
- 安装版的数据目录固定为 `~/.dq-tool/data`(Windows 为 `%USERPROFILE%\.dq-tool\data`),与 jar 方式的 `./data` 不同
- Windows 生成 exe 安装包需 WiX Toolset 3.x;无 WiX 时把脚本里 `--type exe` 改为 `--type app-image`(免安装绿色目录)
- Windows 包带控制台窗口(显示启动日志);启动后浏览器访问 http://localhost:8080
- 安装包双击启动后会自动用默认浏览器打开首页(headless 服务器部署不受影响);应用本身是 Web 服务,没有桌面窗口,Dock/任务栏图标常驻即表示运行中

## 功能

- **数据源管理**:页面增删改查、测试连接;连接信息存本地 H2(`./data/dqconfig.mv.db`),密码 AES-GCM 加密存储(密钥见配置 `dq.security.secret`,生产环境请修改)
- **库/表浏览**:表列表展示估算行数(约)与数据+索引总占用,支持排序/搜索
- **扫描统计**:勾选表或全库扫描;后台线程池(默认 8 worker)按"分段"并发执行
- **真实进度**:每张表按主键/唯一键切分(默认 100 段),分段完成数即进度;任务总进度按行数加权
- **空值定义**:默认 NULL + 空字符串/纯空白(仅字符列);可添加自定义规则(如 `status IN (0,-1)`、`* IN (N/A)`),随任务持久化,结果与导出中注明
- **大表策略**:估算行数 > 100 万或体积 > 10GB 的表默认采样估算(PG/金仓用 TABLESAMPLE,MySQL/达梦/OB 用 LIMIT,结果有偏,UI 标注"估算值");可"强制全量"走分段精确统计
- **断点续扫**:取消/中断/失败的任务可继续,只重跑未完成的分段;表结构变化时会拒绝续扫并提示重新扫描
- **Excel 导出**:概览 / 字段明细 / 异常表 三个 Sheet,流式写出

## 配置(application.yml)

| 配置项 | 默认 | 说明 |
|---|---|---|
| `dq.scan.workers` | 8 | 全局扫描线程数(同时是各数据源连接池大小) |
| `dq.scan.chunks-per-table` | 100 | 每张表的分段数 |
| `dq.scan.row-threshold` | 1000000 | 采样行数阈值 |
| `dq.scan.size-threshold-bytes` | 10GB | 采样体积阈值 |
| `dq.scan.sample-rows` | 100000 | 采样行数 |
| `dq.scan.statement-timeout-seconds` | 1800 | 单条统计 SQL 超时 |
| `dq.security.secret` | change-me... | 密码加密密钥,生产必改 |

阈值也可在每个数据源上单独覆盖。

## 已知限制

- 无登录/权限控制,仅适合内网单机部署
- OceanBase 仅支持 MySQL 模式
- 无主键/唯一索引的表无法分段,整表单条 SQL 统计,无表内进度(显示耗时)
- MySQL/OceanBase/达梦的采样为 LIMIT 顺序采样,结果有偏
- 中断任务不做自动恢复,需手动点"继续扫描"
- 表列表的行数为各库元数据估算值(尤其 InnoDB),以扫描后的精确值为准

## 测试

```bash
mvn test
```

- 单元测试:方言 SQL 生成、规则谓词转义、分段键选择
- H2 分段正确性测试:分段累加 == 全表一条 SQL
- Testcontainers 集成测试(需要 Docker):真实 MySQL 8 / PG 15 / SQL Server 2019 上的并发分段全链路、空值规则、表与字段注释、导出。
  OrbStack 用户若报 "Could not find a valid Docker environment",带上 socket 再跑:
  `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock mvn test`
- 达梦 / 人大金仓 / OceanBase 无公开 Docker 镜像,未做自动化验证,接入真实环境后请先用小库验证
