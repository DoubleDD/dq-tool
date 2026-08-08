//! tauri 模块入口:Tauri 2(系统 WebView)套壳 server 模块的 Web UI。
//!
//! 侧车(sidecar)模型:本进程拉起 `java -jar` server fat jar 作为子进程,轮询就绪后
//! 创建 webview 窗口加载 `http://127.0.0.1:<port>`;窗口关闭/进程退出时杀掉 Java 子进程。
//!
//! 与 shell(JCEF)模块的关键差异 —— 浏览器/托盘抑制:
//! shell 在同一 JVM 内装配 WebServer,必须绕过 onReady 才能抑制外部浏览器/托盘;
//! 本模块直接 `java -jar` 走 DqApplication.main,其首行默认 headless=true,headless 下
//! BrowserOpener 直接 return、TrayManager.installEarly 返回 false、心跳看门狗永不武装,
//! 两个桌面动作天然抑制,无需改动 server。

// Windows:release 构建为 GUI 子系统,双击启动不弹控制台黑窗;debug 保留控制台便于看日志
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// 后端就绪等待总超时(H2 迁移 + Flyway 首次初始化可能较慢)
const READY_TIMEOUT: Duration = Duration::from_secs(60);
/// 就绪探针路径:授权状态接口不受授权拦截(WebServer.java 排除 /api/license/**)
const READY_PATH: &str = "/api/license/status";

/// 供信号处理器杀子进程用(libc::kill 是 async-signal-safe 的)
static CHILD_PID: AtomicI32 = AtomicI32::new(-1);

fn main() {
    let jar = find_server_jar().unwrap_or_else(|e| fatal(&format!("定位 server fat jar 失败:{e}")));
    let java = find_java().unwrap_or_else(|e| fatal(&format!("定位 java 运行时失败:{e}")));
    let packaged = is_packaged();
    eprintln!(
        "[dq-tool-tauri] 后端 jar: {}(java: {},{}模式)",
        jar.display(),
        java.display(),
        if packaged { "安装" } else { "开发" }
    );

    // 取一个空闲端口后释放;竞态窗口内被抢注时 DqApplication 会向后避让,
    // 由 stdout 读线程解析「避让到 N」回填实际端口
    let probed_port = pick_free_port().unwrap_or_else(|e| fatal(&format!("探测空闲端口失败:{e}")));
    let actual_port = Arc::new(Mutex::new(probed_port));

    let mut cmd = Command::new(&java);
    cmd.arg("-XX:+UseZGC");
    if packaged {
        // 安装版数据目录固定 ~/.dq-tool/data(与 jpackage 安装版口径一致);
        // 开发模式不传,走后端默认 ./data(cwd 已切到仓库根)
        let home = home_dir();
        cmd.arg(format!("-Ddq.data-dir={}/.dq-tool/data", home.display()));
    } else {
        // 开发模式:工作目录固定仓库根,数据目录 ./data 与其他模块口径一致;
        // DQ_DATA_DIR 环境变量可覆盖(如用临时数据目录冒烟,避免动本地开发库)
        cmd.current_dir(repo_root());
        if let Ok(dir) = std::env::var("DQ_DATA_DIR") {
            cmd.arg(format!("-Ddq.data-dir={dir}"));
        }
    }
    cmd.arg("-jar")
        .arg(&jar)
        .arg(format!("--server.port={probed_port}"))
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit());
    // Windows:java.exe 是控制台程序,GUI 父进程不加 CREATE_NO_WINDOW 拉起时会新弹一个控制台窗口
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let mut child = cmd
        .spawn()
        .unwrap_or_else(|e| fatal(&format!("拉起 Java 后端失败:{e}")));
    CHILD_PID.store(child.id() as i32, Ordering::SeqCst);
    install_signal_handlers();

    // stdout 读线程:转发日志 + 解析端口避让输出(格式见 DqApplication:「端口 %d 被占用,避让到 %d」)
    let stdout = child.stdout.take().expect("已声明 piped");
    let port_slot = Arc::clone(&actual_port);
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines() {
            let Ok(line) = line else { break };
            eprintln!("[backend] {line}");
            if let Some(idx) = line.find("避让到 ") {
                if let Ok(port) = line[idx + "避让到 ".len()..].trim().parse::<u16>() {
                    *port_slot.lock().unwrap() = port;
                }
            }
        }
    });

    let port = wait_ready(&mut child, &actual_port)
        .unwrap_or_else(|e| fatal(&format!("后端未在 {} 秒内就绪:{e}", READY_TIMEOUT.as_secs())));
    eprintln!("[dq-tool-tauri] 后端已就绪: http://127.0.0.1:{port}");

    let child = Arc::new(Mutex::new(child));
    let child_on_exit = Arc::clone(&child);
    let child_on_update = Arc::clone(&child);
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .setup(move |app| {
            let url = format!("http://127.0.0.1:{port}").parse().expect("合法 URL");
            tauri::WebviewWindowBuilder::new(app, "main", tauri::WebviewUrl::External(url))
                .title("dq-tool 数据质量检测")
                .inner_size(1440.0, 900.0)
                .build()?;
            // 自动更新:仅安装模式;后台线程预下载,完事后弹窗确认(开发模式不检查)
            if packaged {
                let handle = app.handle().clone();
                std::thread::spawn(move || auto_update(handle, child_on_update));
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .unwrap_or_else(|e| fatal(&format!("Tauri 初始化失败:{e}")));

    app.run(move |_handle, event| {
        use tauri::RunEvent;
        if matches!(event, RunEvent::ExitRequested { .. } | RunEvent::Exit) {
            kill_child(&child_on_exit);
        }
    });
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

// ---- 自动更新(tauri-plugin-updater,更新源为 GitHub Releases 的 latest.json)----
//
// 流程:后台 check → 有新版则静默预下载 → 下载完成弹原生对话框询问:
//   「立即更新」→ install + 重启(重启前显式杀 java 子进程,防止孤儿占着 H2 文件锁);
//   「暂不更新」→ 版本号写入 ~/.dq-tool/update-skipped.txt,同一版本不再重复下载/打扰,
//   出现更新版本时重新走流程。检查/下载失败只记日志,不影响主流程。

/// 用户选择「暂不更新」的版本记录(纯文本,一个版本号)
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

fn auto_update(app: tauri::AppHandle, child: Arc<Mutex<Child>>) {
    if let Err(e) = try_auto_update(&app, &child) {
        eprintln!("[dq-tool-tauri] 自动更新失败(忽略,不影响使用):{e}");
    }
}

fn try_auto_update(app: &tauri::AppHandle, child: &Arc<Mutex<Child>>) -> Result<(), String> {
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
        kill_child(child); // 显式杀 java 后端,不等退出事件,防孤儿占 H2 锁
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
/// NSIS 装到 $INSTDIR\resources\;旧布局资源与 exe 同目录,保留兼容)
fn bundled_resources_dir() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let exe_dir = exe.parent()?;
    [
        exe_dir.join("../Resources"),
        exe_dir.to_path_buf(),
        exe_dir.join("resources"),
    ]
    .into_iter()
    .find(|d| d.join("backend/dq-tool.jar").is_file())
}

fn is_packaged() -> bool {
    bundled_resources_dir()
        .map(|d| d.join("backend/dq-tool.jar").exists())
        .unwrap_or(false)
}

/// 定位 server fat jar:DQ_SERVER_JAR 环境变量 > 安装版内嵌资源 > 开发默认 ../server/build/libs/dq-tool-*.jar(取最新)
fn find_server_jar() -> Result<PathBuf, String> {
    if let Ok(p) = std::env::var("DQ_SERVER_JAR") {
        let p = PathBuf::from(p);
        if p.is_file() {
            return Ok(p);
        }
        return Err(format!("DQ_SERVER_JAR 指向的文件不存在:{}", p.display()));
    }
    if let Some(res) = bundled_resources_dir() {
        let p = res.join("backend/dq-tool.jar");
        if p.is_file() {
            return Ok(p);
        }
    }
    let libs_dir = repo_root().join("server/build/libs");
    let mut candidates: Vec<PathBuf> = std::fs::read_dir(&libs_dir)
        .map_err(|e| format!("读取 {} 失败:{e}(需先 ./gradlew :server:shadowJar)", libs_dir.display()))?
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
    candidates
        .pop()
        .ok_or_else(|| format!("{} 下没有 dq-tool-*.jar(需先 ./gradlew :server:shadowJar)", libs_dir.display()))
}

/// 定位 java:DQ_JAVA 环境变量 > 安装版内嵌 jlink 运行时 > PATH 上的 java
fn find_java() -> Result<PathBuf, String> {
    if let Ok(p) = std::env::var("DQ_JAVA") {
        return Ok(PathBuf::from(p));
    }
    if let Some(res) = bundled_resources_dir() {
        let java_bin = if cfg!(windows) { "jre/bin/java.exe" } else { "jre/bin/java" };
        let p = res.join(java_bin);
        if p.is_file() {
            return Ok(p);
        }
    }
    Ok(PathBuf::from("java"))
}

fn pick_free_port() -> Result<u16, String> {
    let listener = TcpListener::bind("127.0.0.1:0").map_err(|e| e.to_string())?;
    listener
        .local_addr()
        .map(|a| a.port())
        .map_err(|e| e.to_string())
}

/// 轮询就绪探针直到 200;子进程提前退出或超时则报错。返回实际端口(含避让回填)
fn wait_ready(child: &mut Child, port_slot: &Arc<Mutex<u16>>) -> Result<u16, String> {
    let deadline = Instant::now() + READY_TIMEOUT;
    loop {
        if let Some(status) = child.try_wait().map_err(|e| e.to_string())? {
            return Err(format!("Java 后端进程提前退出:{status}"));
        }
        let port = *port_slot.lock().unwrap();
        if probe(port) {
            return Ok(port);
        }
        if Instant::now() >= deadline {
            return Err(format!("探针 GET {READY_PATH} 一直未返回 200(端口 {port})"));
        }
        std::thread::sleep(Duration::from_millis(200));
    }
}

/// 就绪探针:裸 TcpStream 手写 HTTP/1.0 GET,只看状态行是否 200(不引 HTTP client 依赖)
fn probe(port: u16) -> bool {
    let Ok(mut stream) = TcpStream::connect_timeout(
        &format!("127.0.0.1:{port}").parse().expect("合法地址"),
        Duration::from_millis(500),
    ) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_millis(500)));
    let req = format!("GET {READY_PATH} HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
    if stream.write_all(req.as_bytes()).is_err() {
        return false;
    }
    let mut buf = [0u8; 128];
    let Ok(n) = stream.read(&mut buf) else {
        return false;
    };
    let head = String::from_utf8_lossy(&buf[..n]);
    head.starts_with("HTTP/") && head[..head.find('\n').unwrap_or(head.len())].contains(" 200")
}

fn kill_child(child: &Arc<Mutex<Child>>) {
    let mut child = child.lock().unwrap();
    // 已退出则 kill 返回 Err,忽略即可
    let _ = child.kill();
    let _ = child.wait();
}

fn fatal(msg: &str) -> ! {
    eprintln!("[dq-tool-tauri] 启动失败:{msg}");
    std::process::exit(1);
}

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
