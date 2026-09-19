//! tauri 模块入口:Tauri 2(系统 WebView)套壳 server 模块的 Web UI。
//!
//! 三层分离(2026-09 业务层在线更新改造):exe(系统层)与 jre(runtime 层)只随全量
//! 安装包/全量 updater 变化;jar + 前端 static 作为业务层按版本落盘
//! resources/versions/<x.x.x>/,由 current 指针选版(versions.rs),支持业务层在线更新
//! 与就绪失败回滚(bizupdate.rs / backend.rs);开发模式(debug)完全不走 versions,
//! jar 直取 server/build/libs 最新、前端由仓库 web/dist 服务。
//!
//! 侧车(sidecar)模型:本进程拉起 `java -jar` server fat jar 作为子进程(backend.rs);
//! webview 窗口立即创建,经 dq 自定义协议从磁盘「当前业务版本 static」加载页面
//! (protocol.rs,改造前为 frontendDist 编译期内嵌),后台线程等后端就绪后只置 ready
//! 标志(页面不 navigate);窗口关闭/进程退出时杀掉 Java 子进程。
//!
//! 常驻 + 托盘(2026-08,极速启动方案):关闭窗口只隐藏不退出,Java 后端常驻,
//! 再次打开 = 纯 WebView 显示(毫秒级,不付 JVM 启动成本);托盘菜单「打开窗口/退出」,
//! 只有点退出(或 Cmd+Q/自动更新重启)才杀后端;单实例插件保证双击图标只唤起已有实例。
//!
//! 浏览器/托盘抑制:本模块直接 `java -jar` 走 DqApplication.main,其首行默认 headless=true,
//! headless 下 BrowserOpener 直接 return、TrayManager.installEarly 返回 false、心跳看门狗永不武装,
//! 两个桌面动作天然抑制,无需改动 server(Java 侧托盘与本 Rust 托盘是两回事,互不影响)。

// Windows:release 构建为 GUI 子系统,双击启动不弹控制台黑窗;debug 保留控制台便于看日志
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod backend;
mod bizupdate;
mod protocol;
mod versions;

use std::io::Write;
use std::path::PathBuf;
use std::process::Child;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::Manager;

/// 更新检查间隔(业务通道与全量通道共用):启动时立即检查一次,之后按此间隔轮询
const UPDATE_CHECK_INTERVAL: Duration = Duration::from_secs(30 * 60);

/// 供信号处理器杀子进程用(libc::kill 是 async-signal-safe 的);restart 时由 backend 更新
static CHILD_PID: AtomicI32 = AtomicI32::new(-1);

fn main() {
    let packaged = is_packaged();
    let portable = is_portable();
    // 业务层定位:release 走 versions/<current>/(含 GC),开发模式 jar 直取 + web/dist
    let (jar, versions_dir, static_root_path) = resolve_business_layer(packaged);
    // 浏览器访问管控令牌:每次启动随机 16 字节(32 位 hex),经 -Ddq.access-token 注入 java。
    // 前端从 IPC api_base() 取到后加 X-Dq-Token 头,webview 无感;浏览器直接访问同一后端则被拒。
    // token 绝不写进日志与 URL(仅存于进程内存与 java argv,argv 可被 ps 看到的取舍见文档)。
    let access_token = generate_access_token();
    eprintln!(
        "[dq-tool-tauri] 后端 jar: {}({}模式)",
        jar.display(),
        if portable {
            "绿色"
        } else if packaged {
            "安装"
        } else {
            "开发"
        }
    );

    // 拉起后端子进程(端口探测/stdout 避让解析/CHILD_PID 均在 BackendManager 内)
    let backend = Arc::new(
        backend::BackendManager::spawn(&jar, &access_token).unwrap_or_else(|e| fatal(&e)),
    );
    install_signal_handlers();

    // 当前前端 static 根的共享句柄:dq 协议每请求读取;业务更新/回滚成功后由 backend 侧更新
    let static_root = Arc::new(Mutex::new(static_root_path));
    let static_root_protocol = Arc::clone(&static_root);
    let child_on_exit = backend.child_handle();
    let child_on_tray = backend.child_handle();

    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        // 单实例:第二实例启动时唤起已有实例的主窗口后自己退出(安装版 macOS 由 LaunchServices 天然去重,
        // 此插件主要兜 Windows/Linux 与 dev 直跑;不支持的平台 init 为空操作)
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main_window(app);
        }))
        // dq 自定义协议:页面/静态资源从磁盘当前版本 static 根加载;wry 在 Windows 导航时
        // 会自动把 dq://localhost/... 映射为 http://dq.localhost/...,全平台写法一致
        .register_uri_scheme_protocol("dq", move |_ctx, request| {
            protocol::handle(&static_root_protocol, request)
        })
        // 自定义命令:导出任务「另存为」/通用下载「直存数据目录」(webview 经 __TAURI_INTERNALS__.invoke 调用)
        .manage(backend.port_state())
        .manage(access_token)
        .manage(backend.ready_flag())
        .invoke_handler(tauri::generate_handler![api_base, save_report_as, save_download])
        .setup(move |app| {
            let window = tauri::WebviewWindowBuilder::new(
                app,
                "main",
                // 窗口先出:立即创建 webview 经 dq 协议加载页面,后台线程等后端;
                // 就绪后只置 ready 标志,页面不 navigate —— 消除「双击后数秒无窗口」的等待
                tauri::WebviewUrl::External(
                    "dq://localhost/index.html".parse().expect("合法的 dq 协议 URL"),
                ),
            )
            .title("dq-tool 数据质量检测")
            .inner_size(1440.0, 900.0)
            // 关闭 Tauri 自带的拖放处理器:Windows 上 wry 会把 WebView2 的 OLE drop target
            // 换成自己的(枚举子窗口 RevokeDragDrop + RegisterDragDrop),而它只认 CF_HDROP,
            // 非文件拖拽在 DragOver 一律回 DROPEFFECT_NONE —— 前端 HTML5 拖放(el-upload 拖拽
            // 上传、对象管理树拖动、数据源卡片拖到分组)会全部收不到 drop;浏览器 / jpackage
            // 形态没有这层宿主覆盖,所以只测浏览器发现不了。Tauri 官方要求:
            // "Disabling it is required to use HTML5 drag and drop on the frontend on Windows"。
            // 本应用不监听 tauri://drag-drop 事件,关闭零副作用(G6 画布走 pointer 事件,无关)。
            .disable_drag_drop_handler()
            .build()?;
            // 常驻模型:关窗只隐藏不退出,后端继续跑;再次打开 = 显示窗口(毫秒级)
            let win_on_close = window.clone();
            window.on_window_event(move |event| {
                if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                    api.prevent_close();
                    let _ = win_on_close.hide();
                }
            });
            // 托盘:打开窗口 / 退出(退出才杀 Java 后端,与窗口关闭解耦)
            let menu = tauri::menu::MenuBuilder::new(app)
                .items(&[
                    &tauri::menu::MenuItemBuilder::with_id("open", "打开窗口").build(app)?,
                    &tauri::menu::MenuItemBuilder::with_id("quit", "退出").build(app)?,
                ])
                .build()?;
            tauri::tray::TrayIconBuilder::new()
                .tooltip("dq-tool 数据质量检测")
                .icon(app.default_window_icon().expect("tauri.conf.json 已配置图标").clone())
                .menu(&menu)
                .on_menu_event(move |app, event| match event.id().as_ref() {
                    "open" => show_main_window(app),
                    "quit" => {
                        kill_child(&child_on_tray);
                        app.exit(0);
                    }
                    _ => {}
                })
                .build(app)?;
            // 启动监督:就绪后只置 ready 标志,由前端轮询 IPC api_base() 拿到动态端口/token
            // 后走 X-Dq-Token 头访问 API;release 首次就绪失败回滚次新版一次,再失败 fatal
            let mgr = Arc::clone(&backend);
            let vd_supervise = versions_dir.clone();
            let sr_supervise = Arc::clone(&static_root);
            std::thread::spawn(move || backend::supervise_startup(mgr, vd_supervise, sr_supervise));
            // 更新后台线程(业务通道 + 全量通道串联):仅打包形态(安装/绿色);开发模式不检查。
            // 各通道启停细节(绿色版禁全量、macOS 禁业务通道)见 bizupdate.rs
            if packaged {
                let handle = app.handle().clone();
                bizupdate::spawn_update_thread(
                    handle,
                    Arc::clone(&backend),
                    versions_dir,
                    Arc::clone(&static_root),
                    portable,
                );
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .unwrap_or_else(|e| fatal(&format!("Tauri 初始化失败:{e}")));

    // 参数名带下划线前缀:handle 只在 macOS 分支使用,Windows 构建会报 unused_variables
    app.run(move |_handle, event| {
        use tauri::RunEvent;
        match event {
            RunEvent::ExitRequested { .. } | RunEvent::Exit => kill_child(&child_on_exit),
            // macOS:窗口隐藏后点 Dock 图标重新显示
            #[cfg(target_os = "macos")]
            RunEvent::Reopen { .. } => show_main_window(_handle),
            _ => {}
        }
    });
}

/// 业务层(jar + 前端 static)定位:返回 (jar 路径, versions 目录[仅打包形态], static 根)。
/// release(安装/绿色):resources/versions/ 解析 current(含完整性回落),随后 GC 只留两版;
/// 开发模式(debug):jar = DQ_SERVER_JAR > server/build/libs 最新,前端 = 仓库 web/dist,
/// 完全不经 versions。DQ_SERVER_JAR 覆盖在两种形态都生效(救急/测试用),但 release 的
/// 前端 static 仍取自 versions(覆盖 jar 不影响页面来源)
fn resolve_business_layer(packaged: bool) -> (PathBuf, Option<PathBuf>, PathBuf) {
    if !packaged {
        let jar = find_dev_jar().unwrap_or_else(|e| fatal(&format!("定位 server fat jar 失败:{e}")));
        return (jar, None, repo_root().join("web/dist"));
    }
    // is_packaged() 已为真,bundled_resources_dir 必然命中
    let resources = bundled_resources_dir().expect("安装版必有 resources 目录");
    let versions_dir = resources.join("versions");
    let resolved = versions::resolve_current(&versions_dir)
        .unwrap_or_else(|e| fatal(&format!("解析业务版本失败:{e}")));
    eprintln!(
        "[dq-tool-tauri] 业务版本: {}({})",
        resolved.version,
        resolved.dir.display()
    );
    versions::gc_versions(&versions_dir, &resolved.version);
    let jar = match std::env::var("DQ_SERVER_JAR") {
        Ok(p) => {
            let jar = PathBuf::from(&p);
            if !jar.is_file() {
                fatal(&format!("DQ_SERVER_JAR 指向的文件不存在:{p}"));
            }
            jar
        }
        Err(_) => resolved.dir.join("dq-tool.jar"),
    };
    (jar, Some(versions_dir), resolved.dir.join("static"))
}

/// 显示并聚焦主窗口(托盘「打开窗口」/单实例唤起/macOS Dock  reopened 共用)
fn show_main_window(app: &tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

/// 开发模式下的仓库根(tauri/src-tauri 的上两级)
fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .canonicalize()
        .expect("仓库根目录存在")
}

/// 用户主目录:Windows 原生环境通常只有 USERPROFILE,MSYS 环境才有 HOME,两者都试
fn home_dir() -> PathBuf {
    std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("."))
}

// ---- 全量自动更新(tauri-plugin-updater,更新源为 GitHub Releases 的 latest.json)----
//
// 由 bizupdate::spawn_update_thread 的后台线程按 UPDATE_CHECK_INTERVAL 轮询调用
// (业务通道之后;绿色版与开发模式不进该线程)。流程:check → 有新版则静默预下载 →
// 下载完成弹原生对话框询问:
//   「立即更新」→ install + 重启(重启前显式杀 java 子进程,防止孤儿占着 H2 文件锁);
//   「暂不更新」→ 版本号写入 ~/.dq-tool/update-skipped.txt(与业务通道共用同一跳过文件),
//   同一版本不再重复下载/打扰,出现更新版本时重新走流程。检查/下载失败只记日志,不影响主流程。

/// 用户选择「暂不更新」的版本记录(纯文本,一个版本号;业务通道与全量通道共用)
fn skipped_version_path() -> PathBuf {
    home_dir().join(".dq-tool").join("update-skipped.txt")
}

fn read_skipped_version() -> Option<String> {
    std::fs::read_to_string(skipped_version_path())
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}

fn write_skipped_version(version: &str) {
    let path = skipped_version_path();
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let _ = std::fs::write(path, version);
}

fn try_auto_update(app: &tauri::AppHandle, child: Arc<Mutex<Child>>) -> Result<(), String> {
    use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
    use tauri_plugin_updater::UpdaterExt;

    // 插件 API 是 async,用 tauri 自带的 async_runtime 阻塞等待,不引 tokio 依赖
    let update = tauri::async_runtime::block_on(async {
        app.updater()
            .map_err(|e| e.to_string())?
            .check()
            .await
            .map_err(|e| e.to_string())
    })?;
    let Some(update) = update else {
        return Ok(()); // 已是最新
    };
    let new_version = update.version.to_string();
    if read_skipped_version().as_deref() == Some(new_version.as_str()) {
        eprintln!("[dq-tool-tauri] 新版本 {new_version} 此前已被用户跳过,不再提示");
        return Ok(());
    }

    eprintln!("[dq-tool-tauri] 发现新版本 {new_version},后台预下载更新包...");
    let downloaded = std::cell::Cell::new(0usize);
    let next_mark = std::cell::Cell::new(32 * 1024 * 1024usize);
    let bytes = tauri::async_runtime::block_on(update.download(
        |chunk_len, _total| {
            let n = downloaded.get() + chunk_len;
            downloaded.set(n);
            if n >= next_mark.get() {
                eprintln!("[dq-tool-tauri] 更新包已预下载 {}MB", n / 1024 / 1024);
                next_mark.set(n + 32 * 1024 * 1024);
            }
        },
        || eprintln!("[dq-tool-tauri] 更新包预下载完成"),
    ))
    .map_err(|e| e.to_string())?;

    let yes = app
        .dialog()
        .message(format!(
            "新版本 {new_version} 已预下载完成。\n\n「立即更新」将关闭窗口并重启程序(进行中的扫描会中断,之后可断点续扫);「暂不更新」则该版本不再提示。"
        ))
        .title("dq-tool 更新")
        .kind(MessageDialogKind::Info)
        .buttons(MessageDialogButtons::OkCancelCustom(
            "立即更新".into(),
            "暂不更新".into(),
        ))
        .blocking_show();
    if yes {
        eprintln!("[dq-tool-tauri] 用户确认更新,安装并重启...");
        kill_child(&child); // 显式杀 java 后端,不等退出事件,防孤儿占 H2 锁
        update.install(bytes).map_err(|e| e.to_string())?;
        app.restart();
    } else {
        eprintln!("[dq-tool-tauri] 用户暂不更新,跳过版本 {new_version}");
        write_skipped_version(&new_version);
    }
    Ok(())
}

/// 安装版内嵌资源目录:macOS 为 <exe>/../Resources(.app 布局),
/// Windows/Linux 为 <exe>/resources/(Tauri 2 的 bundle.resources glob 保留 resources/ 前缀落盘,
/// NSIS 装到 $INSTDIR\resources\;旧布局资源与 exe 同目录,保留兼容)。
/// 判据为 versions/ 目录存在(三层分离后业务层按版本落盘;改造前判 backend/dq-tool.jar)
fn bundled_resources_dir() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let exe_dir = exe.parent()?;
    [
        exe_dir.join("../Resources"),
        exe_dir.to_path_buf(),
        exe_dir.join("resources"),
    ]
    .into_iter()
    .find(|d| d.join("versions").is_dir())
}

/// 是否安装版:仅 release 构建可能为安装版;debug 构建(tauri dev)恒为开发模式 ——
/// 打包残留的 src-tauri/resources 会复制到 target/debug/resources,若按资源存在判断,
/// dev 会被误判为安装版(数据目录错走 ~/.dq-tool/data、误启用自动更新)
fn is_packaged() -> bool {
    if cfg!(debug_assertions) {
        return false;
    }
    bundled_resources_dir().is_some()
}

/// 是否绿色免安装版:release 构建且 exe 同目录存在 PORTABLE.txt 标记文件
/// (由 scripts\package-tauri-win-portable.bat 写入)。绿色版数据目录在 exe 同目录
/// data/、不启用全量自动更新(业务层在线更新照常),其余与安装版一致(内嵌资源照常消费)
fn is_portable() -> bool {
    if cfg!(debug_assertions) {
        return false;
    }
    std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(|d| d.join("PORTABLE.txt").is_file()))
        .unwrap_or(false)
}

/// exe 所在目录(绿色版数据目录的锚点)
fn exe_dir() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."))
}

/// 数据目录(与后端 -Ddq.data-dir 口径一致):绿色版 <exe>/data;安装版 ~/.dq-tool/data;
/// 开发模式 $DQ_DATA_DIR 或仓库根 ./data(相对路径锚到仓库根,保证 main() 与 IPC 各处读到同一目录)
fn data_dir() -> PathBuf {
    if is_portable() {
        return exe_dir().join("data");
    }
    if is_packaged() {
        return home_dir().join(".dq-tool").join("data");
    }
    if let Ok(dir) = std::env::var("DQ_DATA_DIR") {
        let dir = PathBuf::from(dir);
        return if dir.is_absolute() { dir } else { repo_root().join(dir) };
    }
    repo_root().join("data")
}

/// 日志目录:与后端同口径,取数据目录的同级 logs/(开发 ./logs、安装版 ~/.dq-tool/logs、绿色版 <exe>/logs)
fn logs_dir() -> PathBuf {
    data_dir()
        .parent()
        .map(|p| p.join("logs"))
        .unwrap_or_else(|| PathBuf::from("logs"))
}

/// 前端初始化用 IPC:返回后端 API 基址(含动态端口)与访问令牌;后端未就绪返回 null(前端轮询)。
/// 端口读被 stdout 解析线程回填过的 Arc,端口避让后自然是最新值;token 不落日志、不回显。
#[tauri::command]
fn api_base(
    port: tauri::State<'_, Arc<Mutex<u16>>>,
    token: tauri::State<'_, String>,
    ready: tauri::State<'_, Arc<AtomicBool>>,
) -> Option<serde_json::Value> {
    if !ready.load(Ordering::SeqCst) {
        return None;
    }
    let port = *port.lock().ok()?;
    Some(serde_json::json!({
        "base": format!("http://127.0.0.1:{port}/api"),
        "token": token.inner().clone(),
    }))
}

/// 生成 16 字节随机 token(32 位 hex);系统随机源不可用时启动失败(绝不退化为可预测值)
fn generate_access_token() -> String {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).expect("系统随机源不可用");
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// 导出任务「另存为」:原生保存对话框 + 从数据目录复制产物文件。
/// 产物本就落在本机数据目录,直接复制——不引 HTTP client,也不需要 fs 插件权限。
/// 返回 Ok(false) 表示用户在对话框中取消。
#[tauri::command]
async fn save_report_as(app: tauri::AppHandle, name: String, source_name: String) -> Result<bool, String> {
    use tauri_plugin_dialog::DialogExt;

    // sourceName 由前端传后端返回的真实文件名(磁盘 basename,如「数据源名-数据调研报告-任务id.docx」),
    // 与后端 WordReportExportService 产物命名保持一致;文件名由后端过滤非法字符生成,可直接 join
    let src = data_dir().join("reports").join(&source_name);
    if !src.is_file() {
        return Err("报告文件不存在或已被移动,请重新导出".into());
    }
    let (tx, rx) = std::sync::mpsc::channel();
    app.dialog()
        .file()
        .set_file_name(&name)
        .save_file(move |target| {
            let _ = tx.send(target);
        });
    // 对话框回调在 UI 线程,recv 阻塞放线程池,不占 async runtime worker
    let target = tauri::async_runtime::spawn_blocking(move || rx.recv())
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())?;
    let Some(target) = target else {
        return Ok(false);
    };
    let target = target.into_path().map_err(|e| e.to_string())?;
    std::fs::copy(&src, &target).map_err(|e| format!("保存失败:{e}"))?;
    eprintln!("[dq-tool-tauri] 报告另存为:{} -> {}", src.display(), target.display());
    Ok(true)
}

/// 通用下载「直存数据目录」:GET 本地后端流式导出接口,写 <数据目录>/exports/(不经内存/IPC),
/// 也不再弹原生保存框——文件名以后端 Content-Disposition 为准,先写 `<name>.part` 再 rename,
/// 成功后返回绝对路径,前端经 POST /api/system/open 调系统默认关联程序打开。
/// 不弹框的原因:保存框要等响应头(后端生成完整份导出才写出首字节),大导出先弹「正在导出」
/// 提示再直存,体验远好于等半分钟对话框。
/// path 为前端传入的完整路径(含 query),如 /api/scans/123/export?tableCols=&cols=。
/// 返回 Ok(目标绝对路径)。
#[tauri::command]
async fn save_download(
    port: tauri::State<'_, Arc<Mutex<u16>>>,
    token: tauri::State<'_, String>,
    path: String,
) -> Result<String, String> {
    let port = *port.lock().map_err(|e| e.to_string())?;
    let url = format!("http://127.0.0.1:{port}{path}");
    // 门禁令牌:后端配置了 dq.access-token 时 Rust 自发请求也必须带头(未配置时该头无影响)
    let token = token.inner().clone();
    // 阻塞式 HTTP 放线程池,不占 async runtime worker;http_status_as_error(false)
    // 以便读出非 2xx 的错误体给前端 toast
    let mut resp = tauri::async_runtime::spawn_blocking(move || {
        let config = ureq::Agent::config_builder()
            .http_status_as_error(false)
            .build();
        ureq::Agent::new_with_config(config)
            .get(&url)
            .header("X-Dq-Token", &token)
            .call()
            .map_err(|e| format!("请求后端失败:{e}"))
    })
    .await
    .map_err(|e| e.to_string())??;
    if resp.status() != 200 {
        let body = resp.body_mut().read_to_string().unwrap_or_default();
        return Err(format!("下载失败:HTTP {} {}", resp.status(), body.trim()));
    }
    // 解码后的文件名理论上都是后端生成的 basename,仍防一手路径分隔符
    let name = resp
        .headers()
        .get("Content-Disposition")
        .and_then(|v| v.to_str().ok())
        .and_then(parse_cd_filename)
        .unwrap_or_else(|| "download".to_string());
    let name = name
        .rsplit(['/', '\\'])
        .next()
        .unwrap_or("download")
        .to_string();

    let dir = data_dir().join("exports");
    let target = dir.join(&name);
    let tmp = dir.join(format!("{name}.part"));
    let display = target.display().to_string();
    tauri::async_runtime::spawn_blocking(move || {
        std::fs::create_dir_all(&dir).map_err(|e| format!("创建导出目录失败:{e}"))?;
        let mut file = std::fs::File::create(&tmp).map_err(|e| format!("创建文件失败:{e}"))?;
        // 流式读写:大 Excel 不经内存,也不走 IPC;失败清掉半成品,成功才 rename 成正式名
        if let Err(e) = std::io::copy(&mut resp.body_mut().as_reader(), &mut file) {
            let _ = std::fs::remove_file(&tmp);
            return Err(format!("写入文件失败:{e}"));
        }
        std::fs::rename(&tmp, &target).map_err(|e| format!("保存失败:{e}"))?;
        Ok::<_, String>(())
    })
    .await
    .map_err(|e| e.to_string())??;
    eprintln!("[dq-tool-tauri] 下载直存:{path} -> {display}");
    Ok(display)
}

/// 从 Content-Disposition 解析文件名:后端统一 `attachment; filename*=UTF-8''<percent-encoded>`
fn parse_cd_filename(header: &str) -> Option<String> {
    let marker = "filename*=UTF-8''";
    let idx = header.find(marker)?;
    let encoded = header[idx + marker.len()..].trim().trim_matches('"');
    percent_decode(encoded)
}

/// 最小 percent 解码(UTF-8,'+' 按 form 编码还原为空格),避免为此单引一个 crate
fn percent_decode(s: &str) -> Option<String> {
    fn hex(b: u8) -> Option<u8> {
        match b {
            b'0'..=b'9' => Some(b - b'0'),
            b'a'..=b'f' => Some(b - b'a' + 10),
            b'A'..=b'F' => Some(b - b'A' + 10),
            _ => None,
        }
    }
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() {
            out.push(hex(b[i + 1])? * 16 + hex(b[i + 2])?);
            i += 3;
        } else {
            out.push(if b[i] == b'+' { b' ' } else { b[i] });
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

/// 开发模式 jar 定位:DQ_SERVER_JAR 环境变量 > server/build/libs/dq-tool-*.jar(取最新)。
/// (release 形态不再走这里:三层分离后 jar 属业务层,由 versions/ 解析;
///  改造前 debug 优先于内嵌资源的原因仍成立——打包残留的 src-tauri/resources 会被
///  tauri-build 复制到 target/debug/resources,若先命中内嵌资源,`tauri dev` 会一直跑旧打包 jar)
fn find_dev_jar() -> Result<PathBuf, String> {
    if let Ok(p) = std::env::var("DQ_SERVER_JAR") {
        let p = PathBuf::from(p);
        if p.is_file() {
            return Ok(p);
        }
        return Err(format!("DQ_SERVER_JAR 指向的文件不存在:{}", p.display()));
    }
    latest_dev_jar().ok_or_else(|| {
        format!(
            "{} 下没有 dq-tool-*.jar(需先 ./gradlew :server:shadowJar)",
            repo_root().join("server/build/libs").display()
        )
    })
}

/// 开发默认 jar:仓库 server/build/libs 下按修改时间取最新的 dq-tool-*.jar(排除 plain)
fn latest_dev_jar() -> Option<PathBuf> {
    let libs_dir = repo_root().join("server/build/libs");
    let mut candidates: Vec<PathBuf> = std::fs::read_dir(&libs_dir)
        .ok()?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| {
            p.file_name()
                .and_then(|n| n.to_str())
                .map(|n| n.starts_with("dq-tool-") && n.ends_with(".jar") && !n.contains("plain"))
                .unwrap_or(false)
        })
        .collect();
    // 按修改时间取最新
    candidates.sort_by_key(|p| {
        std::fs::metadata(p)
            .and_then(|m| m.modified())
            .unwrap_or(std::time::SystemTime::UNIX_EPOCH)
    });
    candidates.pop()
}

fn kill_child(child: &Arc<Mutex<Child>>) {
    let mut child = child.lock().unwrap();
    // 已退出则 kill 返回 Err,忽略即可
    let _ = child.kill();
    let _ = child.wait();
}

/// 启动失败:stderr + 落盘 + (Windows release)原生错误框,然后退出。
/// Windows 安装版是 GUI 子系统进程、没有控制台,只 eprintln 等于「双击没反应」——
/// 必须把原因写进日志文件并弹一个用户看得见的框(2026-09 补,见 docs/wiki/Tauri兼容性.md B2)
fn fatal(msg: &str) -> ! {
    eprintln!("[dq-tool-tauri] 启动失败:{msg}");
    log_fatal(msg);
    show_fatal_dialog(msg);
    std::process::exit(1);
}

/// 把启动失败原因追加到 <日志目录>/tauri-startup.log;日志本身写不进去也绝不再抛异常
fn log_fatal(msg: &str) {
    let path = logs_dir().join("tauri-startup.log");
    let Some(parent) = path.parent() else { return };
    if std::fs::create_dir_all(parent).is_err() {
        return;
    }
    let line = format!("{} [dq-tool-tauri] 启动失败:{msg}\n", utc_timestamp());
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&path) {
        let _ = f.write_all(line.as_bytes());
    }
}

/// 极简 UTC 时间戳(YYYY-MM-DD HH:MM:SSZ):不引时间库,按 days-from-civil 逆算(便于与后端日志比对)
fn utc_timestamp() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let (days, rem) = (secs / 86_400, secs % 86_400);
    let (hh, mm, ss) = (rem / 3_600, (rem % 3_600) / 60, rem % 60);
    let z = days as i64 + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if month <= 2 { yoe + era * 400 + 1 } else { yoe + era * 400 };
    format!("{year:04}-{month:02}-{day:02} {hh:02}:{mm:02}:{ss:02}Z")
}

/// Windows release(安装版/绿色版):GUI 子系统无控制台,补一个置顶的原生错误框。
/// 直接声明 user32 的 MessageBoxW,不为一个弹框引 windows-sys 依赖;dev 构建保留控制台可看,不弹框
#[cfg(all(windows, not(debug_assertions)))]
fn show_fatal_dialog(msg: &str) {
    let log = logs_dir().join("tauri-startup.log");
    let text = to_wide(&format!(
        "dq-tool 启动失败:\n\n{msg}\n\n详细信息见日志文件:\n{}",
        log.display()
    ));
    let caption = to_wide("dq-tool 数据质量检测");
    unsafe {
        MessageBoxW(
            std::ptr::null_mut(),
            text.as_ptr(),
            caption.as_ptr(),
            MB_OK | MB_ICONERROR | MB_SETFOREGROUND | MB_TOPMOST,
        );
    }
}

#[cfg(not(all(windows, not(debug_assertions))))]
fn show_fatal_dialog(_msg: &str) {}

#[cfg(all(windows, not(debug_assertions)))]
fn to_wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

// user32!MessageBoxW(仅 Windows release 用;声明在模块级,#[link] 才生效)
#[cfg(all(windows, not(debug_assertions)))]
#[link(name = "user32")]
unsafe extern "system" {
    fn MessageBoxW(
        hwnd: *mut core::ffi::c_void,
        text: *const u16,
        caption: *const u16,
        u_type: u32,
    ) -> i32;
}

#[cfg(all(windows, not(debug_assertions)))]
const MB_OK: u32 = 0x0000_0000;
#[cfg(all(windows, not(debug_assertions)))]
const MB_ICONERROR: u32 = 0x0000_0010;
#[cfg(all(windows, not(debug_assertions)))]
const MB_SETFOREGROUND: u32 = 0x0001_0000;
#[cfg(all(windows, not(debug_assertions)))]
const MB_TOPMOST: u32 = 0x0004_0000;

// ---- Ctrl+C / SIGTERM 兜底(仅 Unix):终端直接 kill 本进程时不至于留下孤儿 Java 子进程 ----
// (窗口关闭的正常退出路径由 RunEvent::ExitRequested/Exit 处理;
//  Windows 无 POSIX 信号,GUI 子系统进程也没有控制台 Ctrl+C 场景,靠 RunEvent 即可)

#[cfg(unix)]
unsafe extern "C" fn on_signal(_sig: i32) {
    let pid = CHILD_PID.load(Ordering::SeqCst);
    if pid > 0 {
        unsafe { libc::kill(pid, libc::SIGTERM) };
    }
    unsafe { libc::_exit(0) };
}

fn install_signal_handlers() {
    #[cfg(unix)]
    unsafe {
        libc::signal(libc::SIGINT, on_signal as *const () as libc::sighandler_t);
        libc::signal(libc::SIGTERM, on_signal as *const () as libc::sighandler_t);
    }
}
