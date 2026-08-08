# AGENTS.md — tauri 模块(Tauri 2 桌面壳)

> 本文件面向 AI 编码代理,描述 tauri 子模块的定位与侧车(sidecar)接入要点。项目全景见根 AGENTS.md。

## 模块定位

tauri 用 Tauri 2(系统 WebView:macOS WKWebView / Windows WebView2 / Linux WebKitGTK)把
server 模块的 Web UI 套壳成桌面应用。与 shell 模块(JCEF,同 JVM 进程内嵌 Chromium,
fat jar 约 200MB)不同,tauri 进程本身不含 Java:**Rust 侧拉起 `java -jar` server fat jar
作为子进程**,轮询就绪后 webview 加载 `http://127.0.0.1:<port>`,窗口关闭时杀掉子进程。
体积比 shell 小一个数量级,代价是后端成为独立子进程(生命周期由 Rust 侧管理)。

**硬边界:tauri 不得修改 common/server/desktop/shell/web 的任何文件**,只消费 server fat jar
的既有行为(与 shell 的边界口径一致)。

## 浏览器/托盘抑制:靠 headless 默认值天然免疫

直接 `java -jar` 走 `DqApplication.main`,其首行默认 `headless=true`;headless 下
`BrowserOpener.openBrowser` 直接 return、`TrayManager.installEarly` 返回 false、
心跳看门狗(DesktopSession)只在 BrowserOpener 成功拉起 --app 窗口后才武装 ——
两个桌面动作从源头不触发,**无需像 shell 那样绕过 onReady,也无需传任何属性**。

## 构建与运行

```bash
# 前置:前端产物 + server fat jar
cd web && npm install && npm run build
./gradlew :server:shadowJar

# 开发运行(工作目录/数据目录 ./data 与其他模块口径一致)
cd tauri && npm install && npm run dev
# 或根目录快捷命令(自动先构建 web/dist + fat jar):make tauri

scripts/package-tauri-mac.sh          # macOS dmg(参考 scripts/package-shell-mac.sh;Linux 未实现,见脚本内 TODO)
scripts\package-tauri-win.bat         # Windows NSIS 安装包(CI 的 windows-tauri job 用)
                                        # 快捷命令:make package-tauri / make package-tauri-skip(--skip-build)
```

- 不是 Gradle 模块(npm + cargo 工程,同 web/ 的管理方式),`settings.gradle.kts` 不包含它
- 要求:Rust(cargo 1.77+)、Node、`@tauri-apps/cli` ^2(npm devDependency,无前端框架依赖)
- 开发模式数据目录 `./data`(cwd 切到仓库根,可用 `DQ_DATA_DIR` 环境变量覆盖);安装版 `~/.dq-tool/data`(由 Rust 侧传 `-Ddq.data-dir`)

## 侧车协议(src-tauri/src/main.rs)

- **jar 定位**:`DQ_SERVER_JAR` 环境变量 > 安装版内嵌资源 `backend/dq-tool.jar`(macOS 在 `<exe>/../Resources/`;Windows/Linux 在 `<exe>/resources/` —— Tauri 2 的 bundle.resources glob 会保留 `resources/` 前缀落盘,NSIS 装到 `$INSTDIR\resources\`,`bundled_resources_dir` 另兼容"与 exe 同目录"的旧布局)> 开发默认 `server/build/libs/dq-tool-*.jar`(按修改时间取最新)
- **java 定位**:`DQ_JAVA` 环境变量 > 安装版内嵌 `jre/bin/java`(Windows 为 `java.exe`,完整 JRE)> PATH 的 `java`
- **端口**:`TcpListener::bind(127.0.0.1:0)` 取空闲端口后释放传给 `--server.port=`;
  竞态被抢注时 DqApplication 向后避让,Rust 读线程解析 stdout 的「端口 N 被占用,避让到 M」回填实际端口
  (**改 DqApplication 该输出格式时请同步 main.rs 的解析**)
- **就绪探针**:轮询 `GET /api/license/status` 直到 200(该端点不受授权拦截),超时 60 秒;
  子进程提前退出立即报错。探针用裸 TcpStream 手写 HTTP/1.0,不引 HTTP client 依赖
- **窗口**:就绪后 `WebviewWindowBuilder` 以 `WebviewUrl::External` 创建(tauri.conf.json 不预定义窗口);
  `ui/index.html` 只是 frontendDist 的占位,正常不显示
- **退出联动**:关窗/退出 → `RunEvent::ExitRequested|Exit` 杀子进程;Ctrl+C/SIGTERM 由
  libc 信号处理器兜底(杀子进程后 `_exit`),不留孤儿 java 进程
- **Windows 不弹终端**:crate 根 `#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]`
  使 release exe 为 GUI 子系统(双击不出控制台;debug 保留控制台看日志),拉起 java.exe 子进程时
  再加 `CREATE_NO_WINDOW`(0x08000000)—— 控制台子系统的子进程被 GUI 父进程拉起时会新分配控制台窗口

## 自动更新(tauri-plugin-updater)

- 仅安装模式启用(开发模式不检查);`setup()` 窗口创建后 spawn 后台线程,全程阻塞式 API,不引 async runtime
- 流程:`check()`(读 GitHub Releases 固定地址 `/releases/latest/download/latest.json`)→ 有新版则**后台静默预下载**(约 170MB,进度只打日志)→ 下完弹原生对话框(tauri-plugin-dialog,webview 是远程 URL 不适合做更新 UI)→ 「立即更新」= **先显式杀 java 子进程**(防孤儿占 H2 文件锁导致新实例后端起不来)再 `install()` + `app.restart()`;「暂不更新」= 版本号写入 `~/.dq-tool/update-skipped.txt`,同版本不再下载/提示,更新的版本出现时重新走流程;任何失败只记日志
- 签名:minisign 密钥对,**私钥直接入库 `scripts/updater-private.key`**(单行 base64、无密码;分发方多机打包需要,2026-08 起从"私钥仅存本地"改为入库——仓库公开,验签退化为形式约束,实际防护靠 Release 写权限,介意者请知悉),公钥在 `tauri.conf.json` 的 `plugins.updater.pubkey`;CI 由 "Load updater signing key" 步骤把文件内容注入 `TAURI_SIGNING_PRIVATE_KEY`(tauri CLI 只认内容、不认 `_PATH` 变体),本地打包由 package-tauri-win.bat 未配置环境变量时自动读该文件;**丢私钥 = 更新链断裂,需换密钥对并发全量包**
- `createUpdaterArtifacts: true` 产出 `dq-tool_<v>_x64-setup.nsis.zip` + `.sig`(updater 实际下载 .nsis.zip 而非 setup.exe);CI 生成 `latest.json`(version/signature/url)挂 Release
- `nsis.installMode: "currentUser"`(装 `%LOCALAPPDATA%\Programs`,**更新免 UAC**,高频迭代必需;旧 perMachine 安装需手工卸载重装一次)
- **版本纪律:updater 按 semver 比较,每次发版必须递增 tauri.conf.json 的 version**,否则同版本不会被识别为更新

## 打包

- `scripts/package-tauri-mac.sh` / `scripts\package-tauri-win.bat`:web/dist + shadowJar → 组装 `tauri/src-tauri/resources/`
  (jar → `backend/dq-tool.jar`;完整 JRE → `jre/`:复制本机 JDK 后只删开发工具 bin 启动器与 jmods,
  运行库模块不裁剪 —— JDBC 驱动大量反射/按名加载,jdeps/jlink 静态裁剪覆盖不全,
  实测达梦驱动初始化要 jdk.charsets 的 EUC-KR,裁剪后运行时才炸)
  → `npm run tauri build`(mac 打 dmg,Windows 打 NSIS `dq-tool_<版本>_x64-setup.exe`)
- `tauri.conf.json` 的 `version` 与项目版本保持 `0.x.y → x.y.0` 映射(安装包主版本号 ≥ 1,且必须是三段 semver),升级需手动同步
- `resources/` 是打包产物,已 gitignore;`tauri build` 不带 resources 也能跑(开发模式)
- 图标源图 `src-tauri/icons-source.png`(占位图,**TODO: 换正式 logo**),改后用 `npm run icon -- src-tauri/icons-source.png -o src-tauri/icons` 重新生成
- 未签名公证的 dmg 分发到其他 Mac 可能被 Gatekeeper 拦截(与 shell 相同的已知限制)
