# 数据比对测试环境(MySQL 双库)

给 **dq-tool 数据比对功能** 准备的现成测试环境:一个 Docker MySQL 8.0 实例里放两个库,
一个按国标规范建(基准库),一个模拟三方厂商的"脏库",并预置了**完全确定**的差异数据。

预期结果一句话:**基准库 100 条;厂商库 101 条;缺失 3 条 / 多余 4 条 / 差异 5 条(不一致字段共 7 处)**。

| 库 | 表 | 角色 | 行数 |
|---|---|---|---|
| `reservoir_base` | `reservoir_base_info` | 基准库(国标规范) | 100 |
| `reservoir_vendor` | `t_reservoir_info` | 三方厂商库(不规范) | 101 |

---

## 1. 快速开始

```bash
# 启动(首次会自动建库、建表、灌数,约 10~30 秒)
docker compose -f docker-test-env/compare-mysql/docker-compose.yml up -d

# 等健康检查通过后自检,确认 缺失3 / 多余4 / 差异5 成立
bash docker-test-env/compare-mysql/verify.sh
```

自检通过会输出 `✅ 自检通过:该环境符合「缺失3 / 多余4 / 差异5」的预期。`

其他常用命令:

```bash
# 停止(保留数据)
docker compose -f docker-test-env/compare-mysql/docker-compose.yml down

# 重置:删卷清空,下次启动重新初始化
docker compose -f docker-test-env/compare-mysql/docker-compose.yml down -v
docker compose -f docker-test-env/compare-mysql/docker-compose.yml up -d
```

> **端口用 3307,不是 3306**:本机 3306 已被另一个项目(`mariadb` 容器)占用,故错开。
> 如需改端口,编辑 `docker-compose.yml` 的 `ports` 即可。

---

## 2. 连接信息

| 项 | 值 |
|---|---|
| 主机 / 端口 | `127.0.0.1` / `3307` |
| 应用账号 | `dq` / `dq123456`(仅对两个测试库有权限,推荐) |
| root 账号 | `root` / `Test@12345` |
| JDBC(基准库) | `jdbc:mysql://127.0.0.1:3307/reservoir_base` |
| JDBC(厂商库) | `jdbc:mysql://127.0.0.1:3307/reservoir_vendor` |

`dq` 账号用 `mysql_native_password` 插件(Connector/J 9.x 在明文连接下用
`caching_sha2_password` 首次认证会报 *Public Key Retrieval is not allowed*);
root 仍是 `caching_sha2_password`,若不改连接串参数建议用 `dq` 账号。

已验证:本机 dq-tool(`:10000`)用上面的 JDBC + `dq` 账号**连接成功**
(MySQL 8.0.46 / mysql-connector-j 9.3.0)。

---

## 3. 两张表的差异(刻意设计)

### 基准库 `reservoir_base.reservoir_base_info`(按规范)

- 库名 / 表名 / 字段名统一小写 `snake_case`,语义完整
- 用**自然主键** `reservoir_code`(水库编码),12 位
- 每个字段都有规范的**中文注释**(含单位),表本身也有注释
- 数值字段用精确类型:`DECIMAL` / `SMALLINT`;时间字段带默认值
- 共 16 个业务字段

### 三方厂商库 `reservoir_vendor.t_reservoir_info`(不规范)

| 不规范点 | 具体表现 |
|---|---|
| 表名 | 带 `t_` 前缀,且**没有表注释** |
| 主键 | 用无业务含义的自增代理键 `vendor_id`;`reservoir_code` 只是普通索引 |
| 字段名 | 大小写混乱(`RESERVOIR_CODE` / `Reservoir_Type` / `basin_name` 混用) |
| 字段类型 | 总库容、坝高、经纬度一律用 `VARCHAR` 存;建成年份用 `INT` |
| 冗余字段 | 多出 `data_source` / `sync_time` / `is_deleted` / `ext_json` 四个私有列 |
| 注释 | 注释简短、无单位,不符合数据字典规范 |

> **重要**:两张表都保留了 `reservoir_code`(水库编码)和 `reservoir_name`(水库名称),
> 且厂商库**保留了基准表全部 16 个字段名**(仅大小写/类型/注释不同)。
> 数据比对是按「字段名(忽略大小写)」映射的,这样全选字段时才能得到干净的 3/4/5 结果。
> 如果厂商库把字段改成 `skbm`、`zrk` 这种拼音名,比对时会被判为「列缺失」,所有命中行都会变 DIFF——
> 那是另一个测试场景,需要时可自行改表验证。

---

## 4. 造数规则与预期差异

以基准库 100 条为源构造厂商库(`mysql-init/04-vendor-data.sql`):

```
基准库 100 条
   │  剔除 3 条          → 缺失 MISSING ×3
   ▼
 97 条同码复制
   │  挑 5 条随机改值     → 差异 DIFF ×5
   ▼
 再追加 4 条新编码       → 多余 EXTRA ×4
   ▼
厂商库 97 + 4 = 101 条
```

### 缺失(MISSING:基准有 / 厂商无)3 条

| 水库编码 | 水库名称 |
|---|---|
| `330100000012` | 银山水库 |
| `330100000055` | 老洲水库 |
| `330100000077` | 老溪水库 |

### 多余(EXTRA:厂商有 / 基准无)4 条

| 水库编码 | 水库名称 |
|---|---|
| `330199000001` | 厂商新增水库甲 |
| `330199000002` | 厂商新增水库乙 |
| `330199000003` | 厂商新增水库丙 |
| `330199000004` | 厂商新增水库丁 |

### 差异(DIFF:两边都有、字段值不同)5 条 / 共 7 个字段

| 水库编码 | 不一致字段 | 基准值 → 厂商值 |
|---|---|---|
| `330100000007` | `reservoir_name` | 黑洲水库 → 黑洲水库(厂商补录) |
| `330100000023` | `total_capacity` | 10633.5000 → 10933.5000 |
| `330100000041` | `reservoir_type` | 小型 → 中型 |
| `330100000068` | `build_year`、`dam_height` | 1978/70.50 → 1981/72.00 |
| `330100000090` | `manage_unit`、`status` | 浙江省市水利局/正常运行 → 浙江省市水利局(已划转)/除险加固 |

### 自检脚本覆盖的断言

`verify.sh` 会核对:行数、matched=97、missing=3、extra=4、diff 行=5、
不一致字段总数=7、SAME=92,以及缺失/多余的具体编码集合。

---

## 5. 在 dq-tool 里跑一次比对

> 授权说明:数据比对是受控功能,授权码必须含 `compare`。
> 本机 `:10000` 实例当前授权已包含 `compare`,可直接使用。

### 5.1 注册两个数据源

新建任务第三步的目标数据源**不再排除基准数据源**(只禁止"数据源+库+模式+表"与基准完全相同的组合),所以同一数据源下不同库/表也能互比;不过基准库与厂商库是两个 database,把同一个 MySQL 注册成两条数据源(分别指向两个库)选库更直观:

| 名称 | JDBC URL | 用户名 / 密码 |
|---|---|---|
| 基准库(国标) | `jdbc:mysql://127.0.0.1:3307/reservoir_base` | `dq` / `dq123456` |
| 三方厂商库 | `jdbc:mysql://127.0.0.1:3307/reservoir_vendor` | `dq` / `dq123456` |

可选:给两个数据源分别配「库过滤」白名单(基准库只勾 `reservoir_base`,厂商库只勾 `reservoir_vendor`),
后续选库列表更干净。

### 5.2 新建比对任务

`数据比对 → 新建`:

1. **步骤 1 基准表**:数据源选「基准库(国标)」,数据库栏(MySQL 无独立模式层,第三栏「模式」隐藏)选 `reservoir_base`,表栏选 `reservoir_base_info`
2. **步骤 2 比对字段**:主键选 `reservoir_code`,字段**全选 16 个**(默认即全选)
3. **步骤 3 比对系统**:数据源选「三方厂商库」,schema 选 `reservoir_vendor`,表选 `t_reservoir_info`

提交后到「差异明细」页查看。

### 5.3 预期质量指标(全选 16 个字段时)

| 指标 | 计算 | 预期 |
|---|---|---|
| 基准对象数 | — | 100 |
| 完全一致 SAME | — | 92 |
| 存在差异 DIFF | — | 5 |
| 对象缺失 MISSING | — | 3 |
| 对象多余 EXTRA | — | 4 |
| 对象覆盖率 | 97 / 100 | **97.00%** |
| 字段一致率 | 1 − 7 / (97×16) | **99.55%** |
| 数据完整率 | 1517 / (97×16) | **97.74%** |
| 综合评分 | 覆盖率×0.4 + 一致率×0.4 + 完整率×0.2 | **98.17%** |

> 只勾选部分字段时,分母变成 `matched × 勾选字段数`,一致率/完整率会随之变化;
> 覆盖率与 3/4/5 的计数不受影响。

---

## 6. 目录结构

```
docker-test-env/compare-mysql/
├── docker-compose.yml            # MySQL 8.0,端口 3307,挂载初始化脚本
├── gen-base-data.py              # 基准库 100 条数据的生成器(固定随机种子,可复现)
├── verify.sh                     # 自检:校验 缺失3/多余4/差异5 及各项计数
├── README.md                     # 本文档
├── conf.d/
│   └── charset.cnf               # 强制 mysql 客户端用 utf8mb4(否则中文会被双重编码)
└── mysql-init/                   # 仅在数据卷为空时按文件名顺序执行一次
    ├── 01-base-schema.sql        # 基准库 + 规范表结构(全字段注释)
    ├── 02-base-data.sql          # 基准库 100 条(由 gen-base-data.py 生成)
    ├── 03-vendor-schema.sql      # 厂商库 + 不规范表结构
    ├── 04-vendor-data.sql        # 厂商库数据:复制97 + 改5 + 追加4
    └── 05-init-user.sql          # 应用账号 dq 及授权
```

需要重建基准数据(例如改随机种子)时:

```bash
python3 docker-test-env/compare-mysql/gen-base-data.py \
  > docker-test-env/compare-mysql/mysql-init/02-base-data.sql
# 然后 down -v 重置重灌(注意:04 里引用的 8 个关键编码随种子可能变,需同步调整)
```

---

## 7. 字符集说明(重要)

本环境**端到端强制 utf8mb4**,包括三层保障:

1. 服务端:`command` 指定 `--character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci`
2. **客户端**:`conf.d/charset.cnf` 强制 `[client] default-character-set=utf8mb4`
   + compose 里设了 `LANG=C.UTF-8`
3. 初始化脚本:每个 `.sql` 首行都带 `SET NAMES utf8mb4;`

**为什么必须这么做**:官方 MySQL 镜像里 `mysql` 客户端的 `default-character-set=auto`,
容器 locale 不是 UTF-8 时会解析成 **latin1**。此时执行 UTF-8 的初始化脚本,中文字节会先被
当成 latin1 解释、再转成 utf8mb4 存进表,形成**双重编码**——用 DataGrip / JDBC 这类
真正按 UTF-8 读取的客户端打开就全是乱码;而如果再用同一台 latin1 客户端去查,反而"看起来正常",
极具迷惑性(早期版本的 `verify.sh` 就这样掩盖过这个问题)。

自检脚本已内置编码断言(中文名称 4 字符 / 12 字节、字段注释内容),可及时发现回归。

---

## 8. 验证对象匹配逻辑(匹配逻辑 1/2/3)

本环境预置的差异是「编码对得上、名称/字段有出入」,按默认口径即可复现;要专门验证**名称补配**与
**大模型归一化补配**,在库里临时造两条「编码不同、名称相同/相近」的脏数据即可。

新建任务时统一选:对象编码 = `RESERVOIR_CODE`、对象名称 = `RESERVOIR_NAME`,只改「匹配逻辑」:

| 匹配逻辑 | 预期现象 |
|---|---|
| 编码+名称都相等(默认) | 与自检口径一致:缺失 3 / 多余 4 / 差异 5(不一致字段 7 处);两侧编码不同的行不可能配上 |
| 先编码后名称 | 在上一行结果之上,**编码没配上而名称相同**的对象会被配上(缺失/多余数减少,差异明细对象列出现「名称配对」标签) |
| 编码/名称+大模型归一化 | 上面两路仍配不上的残余再交大模型;明细里 `compare_target.name_matched_count` 之外的命中记在 `ai_matched_count`,对象打「AI 配对」标签 |

造脏数据(临时验证用,验证完 `ROLLBACK` 或 `down -v` 重置):

```sql
BEGIN;
-- 厂商库:换个编码,名称与基准一致 → 匹配逻辑 2 应能配上
UPDATE reservoir_vendor.t_reservoir_info SET RESERVOIR_CODE = 'V-9001'
 WHERE RESERVOIR_NAME = (SELECT reservoir_name FROM reservoir_base.reservoir_base_info LIMIT 1);
-- 厂商库:编码与名称都与基准不同(如加「(改名)」后缀)→ 只有匹配逻辑 3 可能配上
UPDATE reservoir_vendor.t_reservoir_info SET RESERVOIR_NAME = CONCAT(RESERVOIR_NAME, '(改名)')
 WHERE RESERVOIR_CODE = 'V-9002';
-- 查询确认
SELECT RESERVOIR_CODE, RESERVOIR_NAME FROM reservoir_vendor.t_reservoir_info WHERE RESERVOIR_CODE LIKE 'V-90%';
-- ROLLBACK;  -- 临时验证不想留痕就回滚
```

匹配逻辑 3 需要先在「AI 配置」里配好可用的大模型;未配置、双侧残余超过 2000 条或模型调用失败时,
任务**不会失败**,残余仍按缺失/多余处理,原因写在差异明细页的目标说明里。

---

## 8. 常见问题

- **DataGrip 打开全是乱码**:本环境已修复(见第 7 节)。若你是在旧数据卷上看到的乱码,
  必须 `down -v` 重置后重新初始化才能修好——乱码是**已经写进表里的数据坏了**,改客户端设置没用。
  自检的 `中文编码` 一组断言通过就代表数据是好的。DataGrip 侧保持连接属性
  `characterEncoding=UTF-8` / `auto-detect` 即可。
- **`verify.sh` 报连不上 / 找不到容器**:容器没起或名字被改;先 `docker compose ... up -d`,
  再 `docker ps` 确认 `test-mysql-compare` 健康。
- **端口 3307 被占用**:改 `docker-compose.yml` 的 `ports`(如 `3317:3306`),并同步 `verify.sh`
  与数据源 JDBC 里的端口。
- **改了初始化 SQL 但没生效**:初始化脚本只在数据卷为空时执行,需 `down -v` 后重启。
- **中文乱码**:容器已强制 `utf8mb4`,若客户端乱码请设置连接字符集 `utf8mb4`。
- **root 账号连接报 `Public Key Retrieval is not allowed`**:用 `dq` 账号,或在 JDBC URL
  追加 `?allowPublicKeyRetrieval=true`。
- **验证完匹配逻辑后数据对不上自检结果**:第 8 节的 `UPDATE` 是直接改厂商库的,记得 `ROLLBACK`
  或 `down -v` 重置,否则 `verify.sh` 会报缺失/多余条数不符。
