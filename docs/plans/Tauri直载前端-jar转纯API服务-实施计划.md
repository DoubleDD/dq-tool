# Tauri 直载前端 · jar 转纯 API 服务 实施计划

> 状态:**已实施**(2026-09-11):T1–T13、T15 完成并通过验证(`unzip -l` 的 `static/` 条目为 0、`make test` 全绿、`cargo check` 通过、纯 API / static-dir / token 门禁 / CORS 端到端冒烟通过);T14 真机 webview 实发 `Origin` 待三平台回填。
> 目标形态:交付的 fat jar 不再内嵌前端,变成纯 API 服务;Tauri 从自己的 `frontendDist` 直载静态资源。
> 关系:[浏览器访问管控-实施计划](浏览器访问管控-实施计划.md) 中「Tauri 注入随机 token」一节被本计划**激活并修正**(原方案假设 webview 通过
> `navigate` 到后端 origin,同源;CORS 不存在,原方案的 token 与 Cookie 细节需按新架构调整)。本计划实施后,那份计划的其余部分(浏览器直连管控)仍可独立推进。

## 一、目标

- fat jar 内**不含任何前端资源**,只有 `/api/**` 与桌面/托盘等既有能力;`java -jar` 直接当内网小服务用。
- Tauri 3 平台(WebView2 / WKWebView / WebKitGTK)从 `web/dist` 本地加载页面,API 走 `http://127.0.0.1:<动态端口>`。
- jpackage 分发线(Win 免安装 zip)与 `make dev` 的 Chrome `--app` 模式**不退化**:新增 `dq.web.static-dir`,从磁盘目录发静态。
- 顺带消灭一类历史故障:前端资源不再需要反复打开 75MB fat jar(Windows 杀软实时扫描导致 assets 404,见 `WebServer.preloadStatic()` 注释)。

## 二、非目标(明确不做)

- 不做登录/权限体系。token 是「防误开门槛 + 防浏览器网页读取本地 API」的门闩,不是鉴权。
- 不动 `common` 内核(纯 Web 层 + 壳层改动),不动 `dialect/`、不新增 Flyway 迁移。
- 不砍 jpackage 分发线(用户已确认保留)。
- 不改局域网共享的既有口径(`/api/lan/share/**` 依然无鉴权,仅限可信内网)。

## 三、目标架构

```
                     ┌─────────────────────────────── Tauri 进程 ───────────────────────────────┐
                     │  1. getrandom 生成 token                                                  │
                     │  2. 探测空闲端口 → spawn java -jar --server.port=P -Ddq.access-token=T   │
                     │  3. 创建窗口,加载 frontendDist(web/dist)← 本地资源,秒开,无需 navigate │
                     │  4. 就绪线程轮询 /api/license/status(带 token)                          │
                     └───────────────┬───────────────────────────────────────────────────────────┘
                                     │  IPC: api_base() → { base:"http://127.0.0.1:P/api", token:T }
                                     ▼
   webview origin: tauri://localhost (mac/Linux) / http://tauri.localhost (Win)
       └─ axios baseURL = base,请求头 X-Dq-Token: T      ──── 跨域 ────►  java 127.0.0.1:P
                                                                            /api/**  (CORS: *)
                                                                            /*       (无静态,404)

   另一条线(jpackage / make dev),同一个 jar、加 -Ddq.web.static-dir:
       java -jar dq-tool.jar -Ddq.web.static-dir=${APPDIR}/static   → / 与 /assets/* 从磁盘发
       Chrome --app http://127.0.0.1:P/?token=T                     → 同源,token 走 query + Cookie
```

## 四、关键调研结论(实施前请勿推翻,均已在本机源码核对)

1. **Tauri 2 的 asset 协议对未知路径无条件回退 `index.html`**:`manager/mod.rs:404-428` 依次尝试 `{path}` → `{path}.html` → `{path}/index.html` → `index.html`。
   ⇒ `createWebHistory()` 与 `window.location.href='/activate'`(`web/src/api/index.js:24`)**不需要改**。
   ⚠️ 代价:回退不看扩展名,缺失的 `.js` 也会返回 HTML 200 ⇒ 重现服务端 `WebServer.java:479-492` 特意规避的「MIME 白屏」。
   webview 与本机 bundle 同源同版本,正常不会命中;但**前端启动诊断面板不能再假设「带扩展名的 404 是真缺失」**。
2. **CORS 可以用 Javalin 自带插件一把梭**(纠正初判)。`CorsPlugin.handleCors` 的 `when` 分支顺序是
   `"*" in origins -> "*"` 在 `clientOrigin == "null" -> return` **之前**,所以 `anyHost()` 对 `Origin: null`(自定义协议可能发这个)
   与 `Origin: tauri://localhost` / `http://tauri.localhost` 一律回 `Access-Control-Allow-Origin: *`。
   约束:`allowCredentials` 必须为 false(插件里对 `*` + credentials 直接 require 失败)——我们不用 Cookie,满足。
   插件还负责把无匹配路由的 OPTIONS 预检从 404/405 改写成 200,并回显 `Access-Control-Request-Headers`(即 `x-dq-token` 自动放行)。
   **不要手写 CORS 过滤器**。
3. `tauri.conf.json > build.frontendDist` 目前是 `../ui`(启动占位页)。改指 `../../web/dist` 后 `tauri/ui/` 作废。
4. **Cookie 方案在 Tauri 路径下不可用**:webview 从 `tauri://localhost` 发往 `http://127.0.0.1:P` 属第三方 Cookie 上下文,
   且 axios 未开 `withCredentials`。⇒ Tauri 路径只走 `X-Dq-Token` 头;归档计划里的 `Set-Cookie` 只服务于浏览器/jpackage 同源路径。
5. **`EventSource` 不能自定义请求头**(`web/src/views/Logs.vue:104` 的 `/api/logs/stream`)⇒ 必须支持 `?token=` query 放行,且仅限该端点。
6. 归档计划里「Rust 侧 `wait_ready`/`probe` 加 token」的做法在新架构中**不需要**:`/api/license/status` 保持免 token(它是
   `InstanceLock.findRunningInstancePort` 探测同数据目录已有实例的握手端点,对端进程不可能知道本实例 token)。
7. `scripts/package-tauri-{mac.sh,win.bat}` 里的 `(cd web && npm run build)` **保留**——它现在直接喂 `frontendDist`,不再是为了塞进 jar。

## 五、改动清单

### 1. server:静态资源源可插拔 + fat jar 瘦身

| 文件 | 改动 |
|---|---|
| `config/DqProperties.java` | `dq.web.static-dir`(String,默认 `""` = 用 classpath,即 dev/测试现状) |
| `config/ConfigLoader.java` | 收集 yml `dq.web.static-dir` / `-Ddq.web.static-dir` / `DQ_WEB_STATIC_DIR`;相对路径按工作目录解析(与 `data-dir` 同口径) |
| `web/WebServer.java` | `preloadStatic()` 的**枚举来源**拆成两个分支:配置了 `static-dir` 且目录存在 → `Files.walk` 磁盘;否则走现有 classpath/jar 分支。`serveStatic`/`staticCache`/SPA 回退/`/api/assets-manifest` **全部保留不动** |
| `web/WebServer.java` | 启动日志补来源:`静态资源 N/M 加载完成(内存缓存, source=<dir 或 classpath>)` |
| `server/build.gradle.kts` | `tasks.shadowJar { exclude("static/**") }` —— 一行让**交付 jar 变纯**;`processResources` 保留(dev/测试照旧从 `web/dist` 拷 `static`),因此 `:server:run`、`make dev`、现有冒烟测试**零改动** |
| `server/build.gradle.kts` | `tasks.shadowJar` 去掉 `dependsOn(buildWebForRelease)`;`buildWebForRelease` 删除(由 jpackage/tauri 脚本各自保证前端产物)。`tasks.test` 的 `dependsOn(buildWeb)` 暂留 |

> 设计意图:**一个 jar 两种形态,靠 `static-dir` 切换**。Tauri 不传 → 纯 API;jpackage 传 → 从磁盘发。classpath 分支留给 dev 与 `:server:run`。

### 2. server:CORS

`web/WebServer.java` 的 `Javalin.create(cfg -> ...)` 回调内新增(约 6 行):

```java
cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
    rule.path = "/api/*";
    rule.anyHost();              // 含 Origin: null,见「关键结论 2」;配合 token 门禁才安全
    rule.exposeHeader("Content-Disposition");   // 浏览器同源路径下仍可能走 blob 下载
    rule.maxAge(3600);
}));
```

### 3. server:token 门禁(激活归档计划)

按 [浏览器访问管控-实施计划](浏览器访问管控-实施计划.md) 的 §1 落 `dq.access-token` 配置 + `AccessGuard`,**但按下表修正**:

| 归档计划原设计 | 本计划修正 |
|---|---|
| 放行条件:header / query / Cookie 三选一 | 保留,但**豁免清单**明确为:`/api/health`、`/api/license/status`、`/api/lan/share/**`;`OPTIONS` 天然不进 `beforeMatched`(方法不匹配具体路由),但仍要在 `after` 后确认 |
| 未命中:`/api/**` 403;GET 无扩展名 → 403 HTML 提示页;带扩展名静态放行 | 新增 `static-dir` 形态后静态路径不变;**但 pure-API 形态下没有静态**,提示页改为直接 403 + JSON 文案即可,不必区分扩展名 |
| Tauri navigate 到 `?token=` | Tauri **不再 navigate**;token 经 IPC `api_base()` 交给前端,由前端加 `X-Dq-Token` 头 |
| 前端从 `location.search` 捕获 token 存 sessionStorage | 保留(服务浏览器 `?token=` 路径);Tauri 路径改从 IPC 取 |
| token 值不打日志、不回传接口 | 保留;额外要求 `main.rs` 的 `eprintln!` 日志 URL 不带 token |

实现要点:token 校验插在 `WebServer.java:207` 现有 `beforeMatched("/api/*")` 的**最前面**(早于就绪闸门与授权闸门),比较用常量时间比较。

### 4. web:API base 与 token 统一到一个模块

新增 `web/src/api/base.js`,**合并**归档计划的 `utils/access-token.js` 与新的 base 逻辑(避免两个 token 来源):

```js
export let apiBase = '/api'        // 非 Tauri:相对路径(同源)
export function apiUrl(path)       // → `${apiBase}${path}`
export function authHeaders()      // → token 存在时 { 'X-Dq-Token': token }
export async function initApiBase()// Tauri:轮询 invoke('api_base') 直到非空;否则读 ?token= 后 initAccessToken()
```

| 文件 | 改动 |
|---|---|
| `web/src/api/index.js` | `axios.create({ baseURL: apiBase })` → 由 `initApiBase()` 注入;请求拦截器加 `authHeaders()`;`window.location.href='/activate'` **不改**(见结论 1) |
| `web/src/main.js` | 先 `await initApiBase()`,再轮询 `${apiBase}/health`(原为 `/api/health`) |
| `web/src/App.vue:288`、`stores/tabs.js:46` | 裸 `fetch` → `apiUrl()` + `authHeaders()` |
| `web/src/App.vue:443` | `axios.get('/api/heartbeat')` 是**裸 axios**(不经过实例拦截器),必须补 `apiUrl()` + header —— 归档计划已识别此坑 |
| `web/src/router/index.js:48` | 裸 `fetch('/api/license/status')` → `apiUrl()` + header |
| `web/src/views/Logs.vue:104` | `EventSource` → `apiUrl('/logs/stream') + '?token=' + token`(见结论 5) |
| `web/src/views/ReportExports.vue:167`、`SampleExports.vue:496` | `a.href='/api/...'` → 改走 `downloadFile()`,由 Rust `save_download_as` 带 token 发请求 |
| `web/src/views/SampleExports.vue:85,114` | `el-link href="/api/sample-export-template"` → 同上改走 `downloadFile()` |
| `web/src/utils/download.js` | 浏览器分支 `window.open(apiPath)` → `window.open(apiUrl(apiPath))`;Tauri 分支不变(Rust 侧加 token) |
| `web/index.html` | 启动诊断内联脚本:去掉 `/api/assets-manifest` 比对(该能力在 pure-API 形态下失去意义);health 轮询在 Tauri 下改经 `invoke('api_base')`,否则保持相对路径 |

### 5. tauri:加载本地资源 + 下发 base/token

| 文件 | 改动 |
|---|---|
| `src-tauri/tauri.conf.json` | `build.frontendDist`: `../ui` → `../../web/dist`;新增 `build.devUrl`(开发走 5173)+ `beforeDevCommand`(可选,替代 `make dev-web`) |
| `src-tauri/Cargo.toml` | 加 `getrandom`(`Cargo.lock` 已有 0.4.3 传递依赖,复用不增条目) |
| `src-tauri/src/main.rs` | ① main 开头生成 16 字节随机 → 32 位 hex token;② java 命令加 `-Ddq.access-token=<token>`(开发/安装/绿色三态都加);③ **删除**就绪线程里的 `window.navigate`,改为置 ready 标志;④ 新增 IPC 命令 `api_base()` 返回 `{ base, token }`,未就绪返回 `null`;⑤ `save_download_as`/`save_report_as` 请求加 `X-Dq-Token`;⑥ 所有 `eprintln!` 的 URL 不带 token |
| `src-tauri/capabilities/default.json` | 删 `remote.urls`(本地来源不需要);`permissions` 加 `allow-api-base` |
| `src-tauri/permissions/api-base.toml`(新增) | 新增后**必须 `touch build.rs`**,否则 build.rs 不发 rerun 指令(见 `tauri/AGENTS.md`) |
| `tauri/ui/index.html` | 作废删除(窗口直接加载 `frontendDist` 的 index.html 占位页) |
| `src-tauri/resources/` | 不变(仍只有 jar + JRE),前端不进去 |

> 端口避让回填不变:`api_base()` 读的就是被 stdout 解析线程更新过的那个 `Arc<Mutex<u16>>`。
> 前端在就绪前反复轮询,端口被避让时下一次轮询自然拿到新值,**不要在前端缓存首次端口**。

### 6. jpackage:把前端当磁盘资源带上

| 文件 | 改动 |
|---|---|
| `scripts/package-win.bat` | `%INPUT%` 下新增 `static\`(xcopy `web\dist`);`jpackage` 加 `--java-options "-Ddq.web.static-dir=${APPDIR}/static"`;脚本内的 `npm run build` 保留 |
| `scripts/package-mac.sh`、`package-linux.sh` | 同上(mac jpackage 任务当前 CI 停用,但脚本保持一致,避免日后恢复时踩坑) |
| `scripts/package-tauri-{mac.sh,win.bat}` | **基本不变**(`web/dist` 构建保留,只是目的从「塞进 jar」变成「喂 frontendDist」);`--skip-build` 分支需同步确认不再依赖 jar 内静态 |

### 7. 测试与验收

- `server/src/test/.../WebServerSmokeTest.java`:现有静态断言在 classpath 形态下应继续通过(回归);**新增** `WebServerStaticDirTest`:临时目录放 `index.html`+`assets/x.js`,`-Ddq.web.static-dir` 指向它 → `GET /` 与 `GET /assets/x.js` 200、`/api/assets-manifest` 命中。
- 新增 `WebServerAccessTokenTest`(归档计划 §4):无 token 时 `/api/datasources` 403;带 `X-Dq-Token` 200;`?token=` 放行 SSE;`/api/health`、`/api/license/status` 免 token;OPTIONS 预检不被 403。
- **打包产物验证(必做)**:`./gradlew :server:shadowJar` 后 `unzip -l` 确认 `static/` 条目为 0。
- **真机联调(必做,三平台各自一次)**:`make tauri` → 页面秒开、数据正常、日志页 SSE 有流、导出「另存为」可用。
  ⚠️ **必须实测 `Origin` 到底是什么**(WKWebView / WebView2 / WebKitGTK 各一次),这是本计划最大的未知点;验证方式:后端临时打印 `Origin` 头,或在 `LogStreamAppender` 里观察。
- `cd tauri/src-tauri && cargo check`;`cd web && npm run build`。

### 8. 文档同步(铁律)

- 根 `AGENTS.md`:红线里「前端构建与后端运行解耦」整条重写(不再有 jar 内嵌静态);「项目定位」补纯 API 形态;文档索引加本计划。
- `docs/wiki/技术栈与项目结构.md`:模块职责里 server 不再是「薄壳 + 静态资源」。
- `docs/wiki/构建运行与测试.md`:`buildWebForRelease` 删除后的 release 打包链路。
- `docs/wiki/打包与发布.md`:jpackage 改 `static-dir`、tauri 改 `frontendDist`。
- `tauri/AGENTS.md`:侧车协议(不再 navigate、新增 `api_base` 命令、token 注入)、`ui/` 作废、模块定位图。
- `docs/wiki/代码约定与安全.md`:`dq.access-token` 与 CORS 的安全边界。
- `CHANGELOG.md`:发版段落到。

## 六、任务拆解

| # | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T1 | `dq.web.static-dir` 配置贯通(DqProperties/ConfigLoader/application.yml) | — | `ConfigLoaderTest` 通过;yml 与 `-D` 均生效 |
| T2 | `WebServer.preloadStatic` 双来源枚举 + 启动日志 | T1 | 配 dir 时从磁盘加载,不配时退回 classpath |
| T3 | `shadowJar { exclude("static/**") }` + 删 `buildWebForRelease` | — | `unzip -l` 无 `static/`;`make build` 通过 |
| T4 | CORS 插件接入(`anyHost`,path `/api/*`) | — | 冒烟测试带 `Origin: null` 与 `tauri://localhost` 均回 `*` |
| T5 | `dq.access-token` + `AccessGuard` + 豁免清单 | T4 | 新增 token 单测全绿 |
| T6 | `web/src/api/base.js`(base + token 统一) | — | 非 Tauri 下行为与现状完全一致(回归) |
| T7 | 前端 9 处裸调用点改造(§4 表) | T6 | grep 无残留裸 `/api/` 相对路径 |
| T8 | Tauri:token 生成 + `-Ddq.access-token` + 删 navigate | T5 | `cargo check`;后端启动日志含「浏览器访问管控已启用」且不含 token 值 |
| T9 | Tauri:IPC `api_base()` + 权限文件 + capabilities | T8 | `make tauri` 页面加载数据成功 |
| T10 | `frontendDist` 切 `web/dist`,`tauri/ui/` 作废 | T9 | `make tauri` 页面来自本地资源(断网/停后端仍能出壳) |
| T11 | `save_download_as` / `save_report_as` 带 token | T8 | 导出「另存为」成功 |
| T12 | jpackage 脚本带 `static/` + `static-dir` | T2,T3 | Win 免安装 zip 解压即用,页面正常 |
| T13 | 测试补齐(SmokeTest 回归 + StaticDirTest + AccessTokenTest) | T2,T5 | `make test` 全绿 |
| T14 | 三平台真机 Origin/CORS/SSE 实测 | T9,T10 | 每平台记录实测 `Origin` 值到本文件 |
| T15 | 文档同步(§8 六处) | 全部 | 无文档遗留旧口径 |

推荐顺序:T1–T3(服务端先瘦身,可独立验证)→ T6–T7(前端抽 base,浏览器形态零回归)→ T4–T5(CORS+门禁)→ T8–T11(Tauri 主链路)→ T12(jpackage)→ T13–T15。

## 七、风险与回滚

| 风险 | 影响 | 处置 |
|---|---|---|
| **某平台 webview 不发 `Origin` 头**(不是 `null`,而是完全没有) | CORS 插件直接 return,预检失败,API 全挂 | 实测确认(T14);真出现则回落到 Rust 侧反代(见下) |
| Rust 反代兜底方案 | — | 若 CORS 走不通:去掉 `frontendDist` 直载,由 Rust 起 loopback HTTP 服务同时提供前端与 `/api` 反代,**单 origin、零 CORS、前端零改动**;代价是 SSE 反代要自己写流式转发 |
| `shadowJar exclude` 在 Shadow 插件下的匹配行为 | jar 里仍残留 static | T3 用 `unzip -l` 硬校验,不用「构建成功」当判据 |
| token 经 `-D` 进 argv,`ps` 可见 | 同机其他用户可读 | 内网单机定位下接受;介意则改为 Java 生成并 stdout 输出、Rust 解析(现有端口避让已有此通道) |
| `Origin: *` 放大攻击面 | 同机浏览器里的恶意网页可读写无鉴权 API | 已被 token 门禁覆盖(方案 B 的全部意义);豁免清单保持最小 |
| asset 协议对缺失资源回退 HTML | 缺资源白屏、MIME 报错难定位 | 已记录;前端启动诊断面板去掉「扩展名 404」假设,改为比对 `web/dist` 清单(可选加固) |

**回滚**:所有改动均可独立回退——T1–T3 回退即恢复内嵌静态;T4–T5 门禁默认关闭(`dq.access-token` 为空时行为完全不变);T8–T11 回退即恢复 `navigate` 到后端 origin(回到今天的同源模型)。

## 八、实测记录(T14 填)

> **macOS 后端侧已验证(2026-09-11,模拟 `Origin`)**:用最终 jar 起服务后,`Origin: null`、`Origin: tauri://localhost`、`Origin: http://tauri.localhost` 均回 `Access-Control-Allow-Origin: *`;`OPTIONS` 预检带 `Access-Control-Request-Headers: x-dq-token` 不被门禁拦且回 200/`*`。
> **仍待真机**:三平台 webview 实际发出的 `Origin` 值(尤其是否存在**完全不发 Origin** 的平台,那会绕过 CorsPlugin 直接 return,需回落 Rust loopback 反代)、日志页 SSE 实流、导出「另存为」。
>
> **抓取方法(已内置,无需改代码)**:配置了 `dq.access-token` 时(Tauri 必配),后端对每种不同的 `/api` 请求 `Origin` 各记一条 INFO 日志「/api 请求来源 Origin=…」(每进程去重)。三平台各跑一次 `make tauri`,看数据目录 `logs/dq-tool-*.log`(或 Rust 转发到终端的 `[backend]` 行)即可回填下表;若某平台**完全看不到该行**,即命中 §7 的「webview 不发 Origin」回落条件。
>
> **源码推断(Tauri 2.11.5,2026-09-11)**:`src/manager/webview.rs` 由窗口 URL 派生 `window_origin`(仅 `data:` 或无 host 时才退化为 `null`)——macOS/Linux 窗口 URL 为 `tauri://localhost`,Windows 为 wry workaround `http://tauri.localhost`(见 `src/manager/mod.rs::tauri_protocol_url`),据此三平台预期 Origin 依次为 `tauri://localhost` / `tauri://localhost` / `http://tauri.localhost`;社区实践(如 screenpipe「allow http://tauri.localhost origin for Windows」)一致。后端已对前两者及 `null` 验证 CorsPlugin 回 `*`,故 §7「某平台完全不发 Origin」的风险已很小,**实发值仍以真机日志为准**。

| 平台 | webview | 实测 `Origin` 头 | 预检结果 | SSE | 备注 |
|---|---|---|---|---|---|
| Windows | WebView2 | 待填 | | | |
| macOS | WKWebView | 待填 | | | |
| Linux | WebKitGTK | 待填 | | | |
