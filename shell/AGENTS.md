# AGENTS.md — shell 模块(JCEF 桌面壳)

> 本文件面向 AI 编码代理,描述 shell 子模块的定位与 JCEF 接入要点。项目全景见根 AGENTS.md。

## 模块定位

shell 用 JCEF(Java Chromium Embedded Framework)把 server 模块的 Web UI 套壳成桌面应用:
同一 JVM 进程内启动 Javalin 后端(复用 server 的 WebServer 装配),再用内嵌 Chromium 窗口加载
`http://localhost:<端口>`。与 desktop 模块(Compose 原生 UI)不同,shell 不重写任何界面,
页面就是 web/ 的前端;与 server 安装版(外部浏览器 --app 窗口)不同,窗口由本进程内嵌渲染。

**硬边界:shell 不得修改 common/server/desktop/web 的任何文件**,只消费 server 的公开 API
(`ConfigLoader.load()` / `new WebServer(config)` / `start` / `port()` / `stop()`)。

## 构建与运行

```bash
./gradlew :shell:run          # 开发运行(工作目录为仓库根,数据目录 ./data)
./gradlew :shell:shadowJar    # fat jar:shell/build/libs/dq-tool-shell-<version>.jar(约 200MB,内嵌 natives)
scripts/package-shell-mac.sh  # macOS dmg(参考 scripts/package-mac.sh;Linux 未实现,见脚本内 TODO)
                                # 快捷命令:make package-shell / make package-shell-skip(--skip-build)
scripts\package-shell-win.bat # Windows 免安装 zip(CI 的 windows-shell job 用)
```

- Kotlin + application + shadow 插件,mainClass `com.example.dq.shell.MainKt`
- `implementation(project(":server"))`:transitively 带上 common 内核与前端静态资源
  (server 的 processResources 会把 web/dist 拷进 jar,故打包前需先 `cd web && npm run build`)
- 启动早期日志复用 server 的 `StartupLog`(main 第一行初始化,写 数据目录同级 logs/startup.log,
  覆盖读配置/端口/内核装配/CEF 初始化/窗口显示每一步与未捕获异常);「Failed to launch JVM」
  弹窗发生在 JVM 创建之前,Java 日志记不到,用 `set WITH_CONSOLE=1` 再打一次包
  (jpackage `--win-console`),JVM 自身报错会直接显示在控制台

## JCEF 接入要点

- 坐标(Maven 中央仓库,me.friwi 维护,2026 年仍活跃):
  - `me.friwi:jcefmaven:146.0.10` —— 官方接入封装(CefAppBuilder),transitively 带入 jcef-api
  - `me.friwi:jcef-natives-<平台>:jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179`
    (runtimeOnly,平台在 build.gradle.kts 按 os.name/os.arch 解析:windows-amd64/arm64、
    macosx-amd64/arm64、linux-amd64/arm64)
  - 两个版本必须同属一个发布:jcefmaven 从 jcef-api 的 `build_meta.json` 读 release_tag,
    按 `/jcef-natives-{platform}-{tag}.tar.gz` 资源路径在 classpath 找内嵌 natives,对不上会退回联网下载
- **natives 策略:fat jar 只内嵌当前平台**(单个约 136MB tar.gz,合进 jar 根部,shadowJar 直接合并即可);
  运行时 jcefmaven 解压到 `~/.dq-tool/jcef-bundle`(install.lock 标记,跨启动复用;应用安装目录只读不能放)。
  不声明 natives 依赖也能跑——首启会从 Maven 中央/GitHub 下载,但 dmg 分发不能依赖用户网络
- 初始化:`CefAppBuilder.build()` 完成解压 → 补 `java.library.path` → 按平台 `System.load`
  → macOS 追加 `--framework-dir-path` / `--main-bundle-path` / `--browser-subprocess-path`
  → `CefApp.getInstance`。**AppHandler 必须经 `builder.setAppHandler(MavenCefAppHandlerAdapter)` 注入,
  不能用 `CefApp.addAppHandler`(macOS 上会破坏命令行处理)**
- `windowless_rendering_enabled = false`(窗口化渲染,非 OSR 离屏):macOS arm64 上 OSR 会因
  CEF/JOGL 争抢主线程崩溃(java-cef issue #514),窗口化是唯一可靠模式;OSR 才需要 jogl 的 add-exports
- `root_cache_path` 显式指向 `~/.dq-tool/jcef-bundle/cache`:默认值的进程单例锁会导致多实例
  或异常退出后无法启动(CEF 官方警告)
- **HTTP 磁盘缓存必须每次启动清理**(ShellWindow 里删 `Default/Cache`、`Default/Code Cache`、
  `GPUCache`):server 不发 Cache-Control,前端发版后资产哈希变化,磁盘缓存里的旧 index.html
  会引用已不存在的 js —— SPA 兜底返回 text/html 被 ES module 严格 MIME 检查拒绝 → **白屏**
  (实测踩坑,2026-08)。只删缓存目录,`Default/Local Storage`/`WebStorage` 等用户数据保留
- 排障入口:`remote_debugging_port = 9222`(http://localhost:9222 打开 DevTools)+
  `--enable-logging=stderr`(JS console 报错落 stderr);白屏类问题先看
  `curl localhost:9222/json` 确认页面加载状态,再用 CDP 查 DOM/console
- **线程模型:CefApp 初始化、createBrowser、JFrame 组装全部在 main 线程完成,不包
  `SwingUtilities.invokeLater`**(与 jcefmaven 官方示例 MainFrame 一致)——macOS 上 createBrowser
  的 native 调用要与 AppKit 主线程同步,从 EDT 发起会静默死锁:无异常、窗口不出现(实测踩坑)
- JDK 16+ 必须加三个 `--add-opens`:`java.desktop/sun.awt`、`java.desktop/sun.lwawt`、
  `java.desktop/sun.lwawt.macosx`(run 任务与打包脚本都已注入)

## macOS 注意事项

- **不需要 `-XstartOnFirstThread`**(那是 SWT 的要求);JCEF 用独立的 `jcef Helper.app`
  子进程承载 CEF 消息循环,路径由 jcefmaven 自动注入
- 解压 natives 后 jcefmaven 会对解压目录去 quarantine 属性(UnquarantineUtil);
  但未签名的 dmg 分发到其他 Mac 仍可能受 Gatekeeper 拦截,正式分发需签名/公证(未做)
- shell 需要图形环境:`Main.kt` 显式检查 `GraphicsEnvironment.isHeadless()`,headless 下明确报错退出;
  打包脚本**不要**注入 `-Djava.awt.headless=false`(shell 不读该属性,默认即 headful)
- 运行时 stderr 会出现若干噪音,均为已知无害项,无需处理:
  - `Exception in thread "AppKit Thread"`(无堆栈):JCEF macOS 上的已知输出怪癖,
    正常运行中也会出现(见 jcefmaven issue #103)
  - `Signature validation of current process failed (-67030)`:未签名进程上 CEF 的
    权限自检报错,功能不受影响,签名/公证后消失
  - `error messaging the mach port for IMKCFRunLoopWakeUpReliable`:macOS 输入法框架噪音
  - JDK 25 的 `System::loadLibrary restricted method` 警告:jcefmaven 上游行为

## 与 server 的边界:浏览器/托盘抑制

server 的桌面动作只在两个挂载点触发,均由 `DqApplication.main` / `WebServer.onReady()` 驱动:

- `DqApplication.earlyDesktopFeedback`(显式 headless=false 时):`TrayManager.installEarly` + 启动画面
- `WebServer.onReady`:`BrowserOpener.openBrowser`(打开外部浏览器 --app 窗口)+ `TrayManager.onReady`(托盘)

server **没有**提供抑制开关;shell 的做法是不走 `DqApplication.main`、不调 `onReady`,
只调 `WebServer` 的构造/start/port/stop,两个桌面动作从源头不触发,无需改动 server。
心跳看门狗(DesktopSession)只在 BrowserOpener 成功拉起 --app 窗口后武装,shell 路径永不武装。

附带代价:`DqApplication` 的端口解析(--server.port= > SERVER_PORT > yml)与避让逻辑
(向后探测 100 个)是 private,shell 在 `Main.kt` 复刻了一份,**修改 server 侧逻辑时请同步**;
单实例保护无此问题 —— 检测逻辑在 server 的 `InstanceLock`(公开 API),两个入口直接共用
(已有实例时 shell 弹提示框退出,server 桌面版打开已有实例窗口退出)。

## 窗口生命周期

关闭 JCEF 窗口 = 退出整个进程:`ShellWindow.shutdown()`(幂等,换线程)依次
`CefApp.dispose()` → `WebServer.stop()`(停 Javalin + 关 Hikari,不走 System.exit)
→ `exitProcess(0)`;Ctrl+C/kill 由 Main 注册的 shutdown hook 兜底停后端。
托盘为 shell 自写最小实现(`ShellTray`:打开窗口/退出)——server 的 TrayManager 菜单动作
耦合 BrowserOpener(「打开窗口」拉起外部浏览器),与 JCEF 窗口模型不符,无法复用。
