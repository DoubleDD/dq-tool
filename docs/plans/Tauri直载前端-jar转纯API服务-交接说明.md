# 交接说明 — Tauri 直载前端 · jar 转纯 API 服务

> **本文件用途**:agent 到 agent 的工作交接。接手者请先读完本文件,再读同目录的
> [实施计划](Tauri直载前端-jar转纯API服务-实施计划.md)。
> 本文件记录的是**截至 2026-09-11 的现场状态**;计划文档记的是**要做什么**。两者冲突时以本文件的现场事实为准,并更新计划文档。
> 交接完成、功能收尾后本文件可删除(它不描述产品行为,不属于 wiki)。

---

## 0. 一句话任务

把 dq-tool 交付的 fat jar 变成**不含前端的纯 API 服务**,让 Tauri 从自己的 `frontendDist` 直载 `web/dist`;
同时**不能让 jpackage 分发线(Win 免安装 zip)和 `make dev` 的浏览器模式退化**。

## 1. 工作区与分支(绝对路径,直接可用)

| 项 | 值 |
|---|---|
| worktree | `/Volumes/code/com.codeup.aliyun/tools-tauri-static` |
| 分支 | `feature/tauri-static`(无 upstream,未推送) |
| 基点 | `main` 的 `0affe48` |
| 已提交 | `5883a4b` `docs(plan): 新增 Tauri 直载前端与 jar 转纯 API 服务实施计划` |
| 主工作树(不要在这里改代码) | `/Volumes/code/com.codeup.aliyun/tools`(在 `main`,有与本任务无关的未提交改动) |

**所有实现都在 worktree 里做。** 主工作树有别人的未提交改动(`docs/wiki/对象管理.md`、`web/src/components/ObjectDirGraphPane.vue`、`web/src/views/TableDetail.vue`),不要碰。

## 2. 必读文档

1. 本目录 [Tauri直载前端-jar转纯API服务-实施计划.md](Tauri直载前端-jar转纯API服务-实施计划.md) — 目标架构、改动清单、任务表、风险回滚
2. 同目录 [浏览器访问管控-实施计划.md](浏览器访问管控-实施计划.md) — token 门禁的既有设计,**本任务激活它并修正它**(修正点见计划文档 §5.3)
3. 根 `AGENTS.md` + `tauri/AGENTS.md` — 项目红线与 tauri 侧车协议,开工前必读

## 3. 用户已拍板的决策(不要推翻,除非用户改口)

| 决策 | 选择 | 含义 |
|---|---|---|
| 跨域方案 | **B:宽松 CORS + 每次启动随机 token 门禁** | 不手写 CORS 过滤器,用 Javalin 自带 CorsPlugin 的 `anyHost()`;靠 `X-Dq-Token` 挡住同机浏览器里的恶意网页 |
| jpackage 去留 | **保留**,给 jar 加 `dq.web.static-dir` 从磁盘发静态 | 一个 jar 两种形态:`static-dir` 空 = 纯 API(Tauri),非空 = 从磁盘发页面(jpackage / `make dev`) |
| 实施范围 | 本轮先只出方案文档,**代码尚未动一行** | 接手者从 T1 开始 |

## 4. 已核实的技术事实(已逐行读源码确认,不要重新调研)

1. **Tauri 2 的 asset 协议对未知路径无条件回退 `index.html`**
   依据:`tauri-2.11.5/src/manager/mod.rs:404-428`,依次尝试 `{path}` → `{path}.html` → `{path}/index.html` → `index.html`。
   ⇒ `createWebHistory()` 与 `api/index.js:24` 的 `window.location.href='/activate'` **不需要改**。
   ⚠️ 副作用:回退不看扩展名,缺失的 `.js` 也会返回 HTML 200,重现服务端 `WebServer.java:479-492` 特意规避的「MIME 白屏」。
2. **Javalin 7.2.2 的 CorsPlugin 能用 `anyHost()` 覆盖 `Origin: null`**
   依据:`CorsPlugin.handleCors` 的 `when` 分支里 `"*" in origins -> "*"` **排在** `clientOrigin == "null" -> return` **之前**。
   约束:`allowCredentials` 必须为 false(`*` + credentials 会被 `require` 直接拒),我们不用 Cookie,满足。
   插件同时负责把无匹配路由的 OPTIONS 预检从 404/405 改写成 200,并回显 `Access-Control-Request-Headers`(即 `x-dq-token` 自动放行)。
   ⇒ **不要手写 CORS 过滤器**。
3. **Cookie 方案在 Tauri 路径下不可用**:webview 从 `tauri://localhost` 发往 `http://127.0.0.1:P` 是第三方 Cookie 上下文,`SameSite=Strict` 必失效,axios 也未开 `withCredentials`。
   ⇒ Tauri 路径只走 `X-Dq-Token` 头;Cookie 只保留给浏览器/jpackage 那条同源路径。
4. **`EventSource` 不能自定义请求头**(`web/src/views/Logs.vue:104`)⇒ 必须支持 `?token=` query 放行,**且仅限该端点**。
5. **就绪探针不需要带 token**:`/api/license/status` 必须免 token——它是 `InstanceLock.findRunningInstancePort` 探测「同数据目录已有实例」的握手端点,对端进程不可能知道本实例 token。
6. **端口避让回填通道已存在**:`main.rs` 的 stdout 读线程解析「避让到 N」更新 `Arc<Mutex<u16>>`。前端**不要缓存首次端口**,`api_base()` 每次读最新的。
7. `scripts/package-tauri-{mac.sh,win.bat}` 里的 `(cd web && npm run build)` **保留**——它现在直接喂 `frontendDist`,不再是为了塞进 jar。

## 5. 环境与工具链(已实测)

| 项 | 值 |
|---|---|
| pnpm | 11.7.0 ✔(与 `packageManager` 一致) |
| Node | v25.7.0 ✔ |
| `JAVA_HOME` | `/Library/Java/JavaVirtualMachines/temurin-21.jdk/...`(**是 21**) |
| 实际编译用 JDK 25 | `~/.jdks/jbrsdk-25.0.4-osx-aarch64-b508.27` — **Gradle toolchain 自动扫到,无需改 `JAVA_HOME`** |
| web/node_modules | 已安装(214 包) |
| tauri/node_modules | 已安装(2 包) |
| `tauri/src-tauri/target/` | **空的,cargo 未热身**;首次 `cargo check` 要全量编译依赖树(数分钟) |

## 6. 当前完成度与基线数字

- **代码改动:0。** T1–T15 全部未开工。
- 构建已验证:`make build` = `./gradlew :server:shadowJar` → **BUILD SUCCESSFUL in 31s**。
- 改动前基线(供 T3 对照):
  - jar:`server/build/libs/dq-tool-0.2.0.1.jar`,**73M**,43793 条目
  - 内嵌 `static/` 条目 **116 个**(其中 `static/assets/` 下 114 个)
  - T3 完成后这个数字必须是 **0**
- 验证命令(注意坑):
  ```bash
  # ⚠️ 不要用 unzip -l ... | grep -c 'static/'
  #    worktree 目录名 tools-tauri-static 本身含 "static/",Archive 行会被误计 1 个
  unzip -l "$JAR" | awk 'NF>=4 {print $4}' | grep -c '^static/'
  ```

## 7. 任务清单(与看板同源;看板在 DSH 主工作区,接手者不一定看得到,此处为准)

| # | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T1 | `dq.web.static-dir` 配置贯通(`DqProperties` / `ConfigLoader` / `application.yml`) | — | `ConfigLoaderTest` 通过;yml 与 `-D` 均生效 |
| T2 | `WebServer.preloadStatic` 双来源枚举(配了 dir 走 `Files.walk`,否则走现有 classpath 分支)+ 启动日志补 source | T1 | 配 dir 时从磁盘加载,不配时退回 classpath;`serveStatic`/`staticCache`/SPA 回退/`assets-manifest` 全部保留 |
| T3 | `shadowJar { exclude("static/**") }`;去掉 `dependsOn(buildWebForRelease)` 并删除该任务(`processResources` 保留) | — | `unzip -l` 的 static 条目为 **0**(硬校验,不以「构建成功」为准);`make build` 通过 |
| T4 | 接 Javalin CorsPlugin:`path="/api/*"`、`anyHost()`、`exposeHeader("Content-Disposition")`、`maxAge(3600)`,`allowCredentials=false` | — | 冒烟测试带 `Origin: null` 与 `tauri://localhost` 均回 `Access-Control-Allow-Origin: *` |
| T5 | `dq.access-token` + `AccessGuard`,校验插在 `WebServer.java:207` 的 `beforeMatched` **最前面**(早于就绪闸门与授权闸门),常量时间比较;豁免清单固定为 `/api/health`、`/api/license/status`、`/api/lan/share/**`;token 不打日志不回传;未配置时行为与现状完全一致 | T4 | 新增 token 单测全绿 |
| T6 | 新增 `web/src/api/base.js`,导出 `apiBase`/`apiUrl()`/`authHeaders()`/`initApiBase()`;合并归档计划的 `utils/access-token.js`,**避免两个 token 来源** | — | 非 Tauri 下行为与现状完全一致(回归) |
| T7 | 前端 9 处裸 `/api` 调用点改造 | T6 | grep 无残留裸相对路径 |
| T8 | Tauri 生成 token + java 命令加 `-Ddq.access-token`;删除 `window.navigate` 改置 ready 标志;`eprintln` 的 URL 不带 token | T5 | `cargo check`;后端日志含「浏览器访问管控已启用」且不含 token 值 |
| T9 | 新增 IPC `api_base()` 返回 `{base,token}`(未就绪返回 null);capabilities 删 `remote.urls`、加 `allow-api-base`;新增 `permissions/api-base.toml` 后 **`touch build.rs`** | T8 | `make tauri` 页面加载数据成功 |
| T10 | `tauri.conf.json` 的 `frontendDist` 由 `../ui` 改 `../../web/dist`;`tauri/ui/` 作废 | T9 | 页面来自本地资源(停掉后端仍能出壳) |
| T11 | `save_download_as` / `save_report_as` 自发的 HTTP 请求加 `X-Dq-Token` | T8 | 导出「另存为」可用 |
| T12 | `package-win.bat` / `package-mac.sh` / `package-linux.sh`:`%INPUT%` 下加 `static\`(xcopy `web\dist`),jpackage 加 `--java-options "-Ddq.web.static-dir=${APPDIR}/static"` | T2,T3 | Win 免安装 zip 解压即用 |
| T13 | 测试:`WebServerSmokeTest` 回归 + 新增 `WebServerStaticDirTest` + `WebServerAccessTokenTest` | T2,T5 | `make test` 全绿 |
| T14 | **三平台真机 Origin/CORS/SSE 实测**(本任务最大未知点) | T9,T10 | 结果回填计划文档 §8 表格 |
| T15 | 文档同步六处:根 `AGENTS.md`、`技术栈与项目结构.md`、`构建运行与测试.md`、`打包与发布.md`、`tauri/AGENTS.md`、`代码约定与安全.md`,外加 `CHANGELOG.md` | 全部 | 无文档遗留旧口径 |

**T7 的 9 处调用点清单**(改前请重新 grep 复核):
`web/src/api/index.js`(axios baseURL + header;`location.href='/activate'` 不改)、`web/src/main.js`、`web/src/App.vue:288` 与 `:443`(**443 是裸 axios,不经过实例拦截器,最易漏**)、`web/src/stores/tabs.js:46`、`web/src/router/index.js:48`、`web/src/views/Logs.vue:104`(EventSource 走 `?token=`)、`web/src/views/ReportExports.vue:167` 与 `web/src/views/SampleExports.vue:85/114/496`(改走 `downloadFile`)、`web/src/utils/download.js` 浏览器分支、`web/index.html` 启动诊断内联脚本。

## 8. 建议实施顺序

```
T1 → T2 → T3        服务端先瘦身,可独立验证(unzip -l 就是成果)
T6 → T7             前端抽 base,此阶段浏览器形态应零回归
T4 → T5             CORS + token 门禁
T8 → T9 → T10 → T11 Tauri 主链路
T12                 jpackage
T13 → T15           测试与文档
T14                 三平台实测(见风险)
```

⚠️ **顺序陷阱**:一旦 T3 落地,在 T12 完成前 **jpackage 产物是坏的**。若不想留中间态,把 T12 紧跟在 T3 之后做。

## 9. 沙箱与审批(接手者必读)

worktree 在 DSH 会话工作区 `/Volumes/code/com.codeup.aliyun/tools` **之外**。因此:

- 在 worktree 里**写任何文件**都需要 `sandbox_permissions: danger-full-access` + 一句 justification,会弹用户审批。
- 本会话此前已对 `<worktree>` 路径拒绝过写入,按规则可**直接提权不必先撞一次拒绝**。
- 读操作不受限。
- 建议把写操作**合并成尽量少的命令**(例如「改完多个文件 + 构建」一条),减少审批次数。

## 10. 项目红线(摘自根 AGENTS.md,违反即返工)

- 注释、提交信息、文档**全部中文**;代码标识符英文
- **业务代码只在 `common`(Kotlin)写一份**,server 只消费;数据库差异收敛在 `dialect/`
- 库表结构变更一律新增 Flyway 迁移,**已发布迁移禁止修改**
- **导入导出格式向后兼容是铁律**
- `data/` 不提交不外发;功能性 `.bat` 注释一律英文且必须保持 **CRLF**
- 提交信息格式见 `.agents/skills/git-commit/SKILL.md`:`<类型>(<scope>): <总结>` + 「变更内容/影响范围」正文;禁止 `git add -A`,只加自己改的文件
- 改了某领域行为/约定,**必须同步更新对应 `docs/wiki/` 子文件**

## 11. 已知风险与未验证项

| 项 | 状态 | 处置 |
|---|---|---|
| **三平台 webview 到底发什么 `Origin`** | **未实测,本任务最大未知点** | 若某平台**完全不发** `Origin`(不是 `null`),CorsPlugin 会直接 return,预检失败、API 全挂 ⇒ 回落到 Rust 侧 loopback 反代(单 origin、零 CORS、前端零改动;代价是 SSE 反代要自己写流式转发)。详见计划文档 §7 |
| `shadowJar` 的 `exclude("static/**")` 实际匹配行为 | 未验证 | T3 必须用 `unzip -l` 硬校验;若 exclude 不生效,改用 Shadow 的 `filesMatching`/`exclude` 变体或调整 `processResources` |
| Rust 侧未热身 | `tauri/src-tauri/target/` 为空 | 首次 `cargo check` 数分钟;T8 前建议先跑一次 |
| `Origin: *` 放大攻击面 | 已由 token 门禁覆盖 | 豁免清单保持最小;token 值不进日志 |
| token 经 `-D` 进 argv,`ps` 可见 | 内网单机定位下接受 | 介意则改为 Java 生成 + stdout 输出、Rust 解析(端口避让已有此通道) |

## 12. 完成定义(DoD)

1. `unzip -l` 确认交付 jar 的 `static/` 条目为 0,且 `java -jar` 启动后 `/api/health` 正常、`/` 不影响既有行为。
2. `make test` 全绿(含新增的 StaticDir 与 AccessToken 用例)。
3. Windows 免安装 zip 与 macOS dmg 的 jpackage 路径经 `static-dir` 后页面正常。
4. `make tauri` 在 **三个平台各验证一次**:页面秒开、数据加载、日志页 SSE 有流、导出「另存为」成功;并把实测 `Origin` 值回填计划文档 §8。
5. T15 六处文档无旧口径残留(尤其根 `AGENTS.md` 里「前端构建与后端运行解耦」整条需重写)。
6. `CHANGELOG.md` 已补本次改动段落(构建期 `verifyChangelog` 是硬校验,漏了会直接构建失败)。
