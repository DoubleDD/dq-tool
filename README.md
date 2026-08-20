# dq-tool 数据质量检测工具

轻量 Web 应用,对关系型数据库做数据质量检测:表级行数/磁盘占用一览,字段级 NULL/空串/自定义空值规则统计与有值率,支持大表并发分段扫描、真实进度、断点续扫和 Excel 导出,并可调用大模型生成 AI 表说明。

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
- SQL Server 的"库"指 schema(dbo 等),数据库(DATABASE)在浏览库页面下拉选择,jdbcUrl 中的 databaseName 仅作默认库;表/字段注释读扩展属性 `MS_Description`
- Oracle / 达梦 / 人大金仓 / OceanBase 无自动化测试环境,接入真实环境后请先用小库验证(MySQL / PostgreSQL / SQL Server 有 Testcontainers 端到端覆盖)

## 快速开始

要求:JDK 25+、Node 24+(仅开发模式需要)。构建用 Gradle(仓库自带 wrapper,无需安装)。

开发模式:

```bash
# 后端(10000;窗口/托盘需显式 -Djava.awt.headless=false,见 Makefile 的 dev 目标)
./gradlew :server:run

# 前端(5173,代理 /api 到 10000)
cd web && npm install && npm run dev
```

打包交付(单 jar 内嵌前端):

```bash
cd web && npm install && npm run build   # 产物在 web/dist
cd .. && ./gradlew :server:shadowJar     # processResources 自动把 web/dist 拷进 jar
java -jar server/build/libs/dq-tool-0.1.6.jar
```

> 访问 http://localhost:10000 即可使用(开发模式前端在 5173)。10000 被占用时会自动向后探测可用端口;也可用 `--server.port=` 参数或 `SERVER_PORT` 环境变量指定。

原生安装包(jpackage,内嵌 JRE,目标机器无需安装 Java):

```bash
# macOS → server/build/jpackage/dist/dq-tool-1.2.dmg
scripts/package-mac.sh

# Windows(在 Windows 机器上执行)→ server/build/jpackage/dist/dq-tool-1.2.zip(免安装,解压后双击 dq-tool.exe)
scripts\package-win.bat

# Linux(Debian/Ubuntu,需 fakeroot)→ server/build/jpackage/dist/dq-tool_1.2_amd64.deb
scripts/package-linux.sh
```

推荐走 CI 全平台构建:推 `v*` tag(如 `git tag v1.2 && git push origin v1.2`)触发 `.github/workflows/release.yml`,云端并行构建 Windows 免安装 zip(x64 + ARM64)、macOS dmg(Apple Silicon + Intel)、Linux deb,全部自动挂到 GitHub Release;也支持 workflow_dispatch 手动触发(产物以 artifact 下载,保留 30 天)。jpackage 不支持交叉编译,各平台包都在对应系统的 runner 上原生构建。

- 脚本自动完成前端构建 + `./gradlew :server:shadowJar` + jpackage;只重打包可加 `--skip-build`
- dmg/deb 安装包要求主版本号 ≥ 1,脚本把项目版本 `0.1.6` 映射为安装包版本 `1.6`
- 安装版的数据目录固定为 `~/.dq-tool/data`(Windows 为 `%USERPROFILE%\.dq-tool\data`),与 jar 方式的 `./data` 不同
- 运行日志输出到数据目录的 `logs/` 子目录,按天滚动(`dq-tool.yyyy-MM-dd.log`,保留 30 天)
- Windows 包为免安装 zip:解压后双击 `dq-tool.exe`,无控制台窗口,日志写入 `%USERPROFILE%\.dq-tool\data\logs\`;无需管理员权限,删除目录即卸载
- 安装包启动后会自动用默认浏览器打开首页(headless 服务器部署不受影响);应用本身是 Web 服务,没有桌面窗口,Dock/任务栏图标常驻即表示运行中

## 功能

- **数据源管理**:页面增删改查、测试连接;连接信息存本地 H2(`./data/dqconfig.mv.db`),密码 AES-GCM 加密存储(密钥见配置 `dq.security.secret`,生产环境请修改)
- **库/表浏览**:库列表带统计缓存(schema-stats);表列表展示估算行数(约)与数据+索引总占用,支持排序/搜索,并可直达每表最近一次扫描结果
- **任务看板**:Dashboard 汇总扫描任务状态,任务详情含状态变更时间线
- **扫描统计**:勾选表或全库扫描;后台线程池(默认 8 worker)按"分段"并发执行
- **真实进度**:每张表按主键/唯一键切分(默认 100 段),分段完成数即进度;任务总进度按行数加权
- **空值定义**:默认 NULL + 空字符串/纯空白(仅字符列);可添加自定义规则(如 `status IN (0,-1)`、`* IN (N/A)`),随任务持久化,结果与导出中注明
- **大表策略**:估算行数 > 100 万或体积 > 10GB 的表默认采样估算(PG/金仓用 TABLESAMPLE,MySQL/达梦/OB 用 LIMIT,结果有偏,UI 标注"估算值");可"强制全量"走分段精确统计
- **断点续扫**:取消/中断/失败的任务可继续,只重跑未完成的分段;表结构变化时会拒绝续扫并提示重新扫描
- **Excel 导出**:概览 + 表列表 + 每表字段明细(每表一个 Sheet)+ 异常表,流式写出;导出列可选,表名/表注释固定前列
- **AI 表说明**:配置 OpenAI 兼容接口后,根据表结构(表名/字段/注释,不含业务数据)生成表用途描述,支持手动触发生成与手动编辑,结果存 H2
- **授权码**:程序需输入授权码激活后才能使用;授权码为离线 Ed25519 签名(含客户标识与有效期),到期后需换领新码

## 授权码

程序启动后未激活时所有功能接口返回 401,页面强制跳到激活页;输入授权码激活后正常使用,到期(到期日当天仍有效)后需换领新授权码续期。

分发方签发授权码(私钥自行保管,勿提交仓库/外发):

```bash
# 1) 生成密钥对(只需一次),把生成的 license-public.key 内容写入 server/src/main/resources/license-public.key
make license-keypair

# 2) 签发授权码(交互式,依次提示输入私钥/客户/有效期/版本/扩展字段/功能列表,回车用默认值)
make license
```
(等价于直接运行 `java scripts/LicenseKeygen.java`。默认值:客户=内部测试,私钥=当前目录 `license-private.key`,有效期=30 天后(可输入 `permanent` 永久),软件版本=根目录 VERSION 文件去 `0.` 前缀;SID 回车自动生成 UUID;功能列表回车=仅基础业务功能。)

授权码为离线验证,无需授权服务器;格式 `DQ1.<payload>.<签名>`,payload 含客户标识、有效期和扩展字段(server_url/username/sid/签发时间戳/功能列表);其中 username、sid、签发时间会展示在前端授权信息处,server_url 不回传前端。注意:纯离线方案无法防逆向破解,仅作为分发门槛。

功能列表(逗号分隔,可选):`scan,datasource,excel,report,ai_doc,ai_tag,tag,logs,license_admin`。扫描/数据源/Excel/报告/AI/标记为基础业务功能恒可用;`logs`(运行日志)、`license_admin`(授权码管理)为受控功能,需显式包含,否则对应页面入口隐藏、接口返回 403。详见 [docs/wiki/授权码.md](docs/wiki/授权码.md)「功能列表」。

### 授权码管理(仅分发方/管理员)

在 `application.yml` 配置签发私钥文件路径后,该实例即管理员实例,首页底部出现「授权码管理」入口:

```yaml
dq:
  license:
    private-key-file: "/path/to/license-private.key"
```

管理页(/license-admin)支持:生成新授权码(自动绑定当前软件版本号)、留档查看全部签发记录(含完整授权码与 server_url)、删除记录;不提供编辑。删除仅删除留档记录,离线验签方案无法吊销已分发的授权码。用户实例(未配置私钥)访问管理接口一律 403。

首页底部同时显示当前软件版本号。

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
| `dq.license.public-key-file` | classpath:license-public.key | 授权码验签公钥(Ed25519)文件路径:`classpath:` 前缀读 jar 内资源,否则按文件系统路径(支持 `${user.home}`);公钥文件由 `scripts/LicenseKeygen.java --gen-keypair` 生成 |
| `dq.license.private-key-file` | — | 签发私钥文件路径(写法同上);配置即管理员实例,开放授权码管理(生成/查看/删除)。仅分发方配置 |
| `ai.base-url` / `ai.api-key` / `ai.model` | — | AI 表说明的默认接口配置,页面配置优先,未设置的字段逐字段回落到此默认值(默认 key 为明文,仅适合内网) |

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
./gradlew :server:test
```

- 单元测试:方言 SQL 生成、规则谓词转义、分段键选择、AI 表说明 prompt 组装
- H2 测试(不需要 Docker):分段累加 == 全表一条 SQL;任务删除的级联清理(分段/字段/表/任务四级)
- Testcontainers 集成测试(需要 Docker):真实 MySQL 8 / PG 15 / SQL Server 2019 上的并发分段全链路、空值规则、表与字段注释、导出。
  OrbStack 用户若报 "Could not find a valid Docker environment",带上 socket 再跑:
  `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock ./gradlew :server:test`
- 达梦 / 人大金仓 / OceanBase / Oracle 未做自动化验证,接入真实环境后请先用小库验证
- LLM 实际调用无自动化覆盖,AI 表说明功能需配置真实接口后手动验证
