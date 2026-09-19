//! 后端(java 子进程)生命周期管理。
//!
//! BackendManager 持有子进程句柄(托盘退出/RunEvent/全量 updater 杀进程共用同一个
//! Arc<Mutex<Child>>)、实际端口(含 stdout 避让回填)与就绪标志;restart 用于业务层
//! 在线更新:杀旧 → 以同一套命令行参数拉起新版本 jar → 就绪标志复位。
//! 监督逻辑(supervise_startup):首次等就绪失败时,release 形态回滚到次新业务版本
//! 重拉一次(回滚只试一次),再失败 fatal;rollback_once 同时供业务更新通道复用。

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// 后端就绪等待总超时(H2 迁移 + Flyway 首次初始化可能较慢)
const READY_TIMEOUT: Duration = Duration::from_secs(60);
/// 就绪探针路径:授权状态接口不受授权拦截(WebServer.java 排除 /api/license/**)
const READY_PATH: &str = "/api/license/status";

/// 后端进程管理:spawn/restart 走同一份命令行组装,token 留存供重启时原样注入
pub struct BackendManager {
    child: Arc<Mutex<Child>>,
    actual_port: Arc<Mutex<u16>>,
    ready: Arc<AtomicBool>,
    access_token: String,
}

impl BackendManager {
    /// 首次拉起后端;失败立即报错(启动路径交给 fatal)
    pub fn spawn(jar: &Path, access_token: &str) -> Result<Self, String> {
        let actual_port = Arc::new(Mutex::new(0u16));
        let child = spawn_child(jar, access_token, &actual_port)?;
        Ok(Self {
            child: Arc::new(Mutex::new(child)),
            actual_port,
            ready: Arc::new(AtomicBool::new(false)),
            access_token: access_token.to_string(),
        })
    }

    /// 重启到新版本 jar(业务层在线更新用):杀旧进程 → 就绪标志复位 → 重新拉起。
    /// 不就绪;由调用方 wait_ready,失败走 rollback_once
    pub fn restart(&self, jar: &Path) -> Result<(), String> {
        crate::kill_child(&self.child);
        self.ready.store(false, Ordering::SeqCst);
        let child = spawn_child(jar, &self.access_token, &self.actual_port)?;
        *self.child.lock().unwrap() = child;
        Ok(())
    }

    /// 轮询就绪探针直到 200;子进程提前退出或超时则报错。返回实际端口(含避让回填)
    pub fn wait_ready(&self) -> Result<u16, String> {
        let deadline = Instant::now() + READY_TIMEOUT;
        loop {
            if let Some(status) = self.child.lock().unwrap().try_wait().map_err(|e| e.to_string())? {
                return Err(format!("Java 后端进程提前退出:{status}"));
            }
            let port = *self.actual_port.lock().unwrap();
            if probe(port) {
                return Ok(port);
            }
            if Instant::now() >= deadline {
                return Err(format!("探针 GET {READY_PATH} 一直未返回 200(端口 {port})"));
            }
            std::thread::sleep(Duration::from_millis(200));
        }
    }

    /// 后端实际端口(含避让回填)的共享句柄:托管为状态供 api_base/save_download 拼本地 URL
    pub fn port_state(&self) -> Arc<Mutex<u16>> {
        Arc::clone(&self.actual_port)
    }

    /// 就绪标志的共享句柄:托管为状态供 api_base 判断是否返回 null
    pub fn ready_flag(&self) -> Arc<AtomicBool> {
        Arc::clone(&self.ready)
    }

    /// 子进程句柄:托盘「退出」/RunEvent/全量 updater 杀进程用
    pub fn child_handle(&self) -> Arc<Mutex<Child>> {
        Arc::clone(&self.child)
    }
}

/// 组装并拉起 java 子进程;返回子进程句柄。actual_port 先写入探测端口,
/// stdout 读线程解析到避让输出后再回填实际端口
fn spawn_child(jar: &Path, access_token: &str, actual_port: &Arc<Mutex<u16>>) -> Result<Child, String> {
    let java = find_java()?;
    eprintln!("[dq-tool-tauri] java 运行时: {}", java.display());
    // 取一个空闲端口后释放;竞态窗口内被抢注时 DqApplication 会向后避让,
    // 由 stdout 读线程解析「避让到 N」回填实际端口
    let probed_port = pick_free_port()?;
    *actual_port.lock().unwrap() = probed_port;

    let mut cmd = Command::new(&java);
    // 数据目录:与 data_dir() 同口径(绿色 <exe>/data、安装 ~/.dq-tool/data、开发 ./data 或 DQ_DATA_DIR),
    // 显式传给后端(原开发模式不传走后端默认 ./data,等价——cwd 已切到仓库根);
    // Rust 侧读「最大内存」设置也按同一目录找 config.properties
    let data_dir = crate::data_dir();
    if !crate::is_portable() && !crate::is_packaged() {
        // 开发模式:工作目录固定仓库根(相对路径的资源/日志口径与其他模块一致)
        cmd.current_dir(crate::repo_root());
    }
    cmd.arg(format!("-Ddq.data-dir={}", data_dir.display()));
    // 堆上限:系统设置页可改,落 <数据目录>/config.properties(dq.jvm.xmx-mb,单位 MB),JVM 启动后
    // 不可调,故由拉起方在启动前读取注入;读不到/非法回落默认 1024MB(2026-09 由 384MB 上调:
    // 大表扫描/导出/AI 并发会打满 384MB 进入 GC 空转,表现为接口全挂)
    cmd.arg("-XX:+UseG1GC");
    cmd.arg(format!("-Xmx{}m", configured_xmx_mb(&data_dir)));
    // JDK 25 AOT 类缓存:jar 同目录存在 dq-tool.aot 才启用,开发模式/未训练环境静默跳过。
    // 打包脚本不生成(2026-08 实测 macOS 收益≈0,启动大头是 H2+Flyway 真实初始化而非类加载,
    // 详见 tauri/AGENTS.md);需要时手动 record→create 训练后放到 jar 同目录即可生效
    if let Some(cache) = find_aot_cache(jar) {
        cmd.arg(format!("-XX:AOTCache={}", cache.display()));
    }
    // 纯 API 后端默认无浏览器管控;注入随机 token 后,浏览器直接打开 Tauri 拉起的后端被门禁拒绝
    cmd.arg(format!("-Ddq.access-token={access_token}"));
    cmd.arg("-jar")
        .arg(jar)
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
    let mut child = cmd.spawn().map_err(|e| format!("拉起 Java 后端失败:{e}"))?;
    crate::CHILD_PID.store(child.id() as i32, Ordering::SeqCst);

    // stdout 读线程:转发日志 + 解析端口避让输出(格式见 DqApplication:「端口 %d 被占用,避让到 %d」)
    let stdout = child.stdout.take().expect("已声明 piped");
    let port_slot = Arc::clone(actual_port);
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
    Ok(child)
}

/// 启动监督(独立后台线程):等首次就绪;release 形态失败则回滚次新业务版本重拉一次
/// (回滚只试一次),再失败 fatal。就绪只置标志,页面不 navigate —— 保持「窗口秒出」的现感
pub fn supervise_startup(
    mgr: Arc<BackendManager>,
    versions_dir: Option<PathBuf>,
    static_root: Arc<Mutex<PathBuf>>,
) {
    match mgr.wait_ready() {
        Ok(port) => {
            eprintln!("[dq-tool-tauri] 后端已就绪: http://127.0.0.1:{port}");
            mgr.ready.store(true, Ordering::SeqCst);
        }
        Err(first) => {
            let Some(vd) = versions_dir else {
                // 开发模式无版本目录可回滚,维持旧的直接 fatal 行为
                crate::fatal(&format!("后端未在 {} 秒内就绪:{first}", READY_TIMEOUT.as_secs()));
            };
            eprintln!("[dq-tool-tauri] 后端首次就绪失败:{first};尝试回滚到次新业务版本");
            match rollback_once(&mgr, &vd, &static_root) {
                Ok(v) => eprintln!("[dq-tool-tauri] 已回滚到业务版本 {v} 并就绪"),
                Err(second) => crate::fatal(&format!(
                    "后端就绪失败({first}),回滚次新版也失败:{second}"
                )),
            }
        }
    }
}

/// 回滚一次:current 指针改指次新业务版本 → 重启后端 → 等就绪 → static 根切到回滚版。
/// 启动监督与业务更新通道(新版就绪失败时)共用;成功返回回滚到的版本号
pub fn rollback_once(
    mgr: &BackendManager,
    versions_dir: &Path,
    static_root: &Arc<Mutex<PathBuf>>,
) -> Result<String, String> {
    let (version, dir) = crate::versions::second_newest(versions_dir)
        .ok_or_else(|| "磁盘上没有次新业务版本可回滚".to_string())?;
    crate::versions::write_current(versions_dir, &version)?;
    mgr.restart(&dir.join("dq-tool.jar"))?;
    let port = mgr.wait_ready()?;
    *static_root.lock().unwrap() = dir.join("static");
    mgr.ready.store(true, Ordering::SeqCst);
    eprintln!("[dq-tool-tauri] 后端已就绪(回滚版 {version}): http://127.0.0.1:{port}");
    Ok(version)
}

/// 定位 java:DQ_JAVA 环境变量 > 安装版内嵌 jre 运行时 > PATH 上的 java
fn find_java() -> Result<PathBuf, String> {
    if let Ok(p) = std::env::var("DQ_JAVA") {
        return Ok(PathBuf::from(p));
    }
    if let Some(res) = crate::bundled_resources_dir() {
        let java_bin = if cfg!(windows) { "jre/bin/java.exe" } else { "jre/bin/java" };
        let p = res.join(java_bin);
        if p.is_file() {
            return Ok(p);
        }
    }
    Ok(PathBuf::from("java"))
}

/// 定位 JDK 25 AOT 类缓存:DQ_AOT_CACHE 环境变量 > jar 同目录 dq-tool.aot;不存在返回 None(不用缓存)
fn find_aot_cache(jar: &Path) -> Option<PathBuf> {
    if let Ok(p) = std::env::var("DQ_AOT_CACHE") {
        let p = PathBuf::from(p);
        if p.is_file() {
            return Some(p);
        }
    }
    let p = jar.parent()?.join("dq-tool.aot");
    p.is_file().then_some(p)
}

/// 系统设置「最大内存」(MB):读 <数据目录>/config.properties 的 dq.jvm.xmx-mb,
/// 未设置/解析失败/超出 512~8192 一律回落默认 1024。与服务端 JvmMemoryConfig 口径保持一致,
/// 改键名/范围/默认值时两边同步。
fn configured_xmx_mb(data_dir: &Path) -> u32 {
    const DEFAULT_MB: u32 = 1024;
    let Ok(content) = std::fs::read_to_string(data_dir.join("config.properties")) else {
        return DEFAULT_MB;
    };
    for line in content.lines() {
        if let Some(value) = line.trim().strip_prefix("dq.jvm.xmx-mb=") {
            if let Ok(mb) = value.trim().parse::<u32>() {
                if (512..=8192).contains(&mb) {
                    return mb;
                }
            }
        }
    }
    DEFAULT_MB
}

fn pick_free_port() -> Result<u16, String> {
    let listener = TcpListener::bind("127.0.0.1:0").map_err(|e| e.to_string())?;
    listener
        .local_addr()
        .map(|a| a.port())
        .map_err(|e| e.to_string())
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
