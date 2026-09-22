//! 业务层(jar + 前端 static)自动更新通道。
//!
//! 与全量 updater(tauri-plugin-updater)在同一后台线程串联,沿用 30 分钟轮询节奏,
//! 每轮先业务后全量:
//!   1. 拉 GitHub Releases 的 business-latest.json 清单(DQ_BUSINESS_MANIFEST 可覆盖,测试用)
//!   2. version ≤ 当前业务版本 或在 update-skipped.txt(与全量通道共用同一跳过文件)→ 跳过;
//!      minShell > 壳版本 → 业务包装不了,交回全量 updater 流程
//!   3. 预下载 zip 到 versions/.staging-<v>.zip → minisign 验签(与全量 updater 同一把公钥)
//!      → 解压到 versions/.staging-<v>/ → 校验完整性 → rename 为 versions/<v>/
//!   4. 原生对话框询问:立即 = 切 current → GC 留新旧两版 → 重启后端 → 就绪后 location.reload()
//!      让页面从新 static 重载(就绪失败自动回滚);暂不 = 写 skipped(新版本目录留着,不占运行)
//! 全程失败只记日志,不影响使用;macOS 不启用本通道(.app 是签名整体,自修改 resources
//! 会破坏 Gatekeeper 校验,mac 业务层仍由全量 updater 覆盖);开发模式不启用(main 不建线程)。
//!
//! 手动离线升级(manual_business_update):现场网络不通自动通道时,系统设置页「选择升级包」
//! 触发——用户选本地 business zip(要求同目录有 Release 页同名 .zip.sig),验签/解压校验/
//! 确认对话框/落位/切换重启全部复用自动通道同一套函数;离线拿不到 business-latest.json,
//! 不做 minShell 检查,壳过旧时靠切换后「就绪失败自动回滚」兜底。

use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::Deserialize;
use tauri::Manager;

use crate::backend::BackendManager;
use crate::versions;

/// 业务更新清单默认地址(GitHub Releases 固定资产)
const DEFAULT_MANIFEST_URL: &str =
    "https://github.com/DoubleDD/dq-tool/releases/latest/download/business-latest.json";

/// minisign 验签公钥(base64 原文):与 tauri.conf.json plugins.updater.pubkey 同一把,
/// 业务层与壳共用同一签名密钥对(私钥即 scripts/updater-private.key,见 tauri/AGENTS.md)
const PUBKEY_B64: &str = "dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk6IEFDQTlDODQwMjBGMTEyNwpSV1FuRVE4Q2hKektDc1hub1EwUE0zbDJZNk9tNkxDcXllUi9jNitjS2FlOVNWWFlLWGdRRm90Swo=";

/// 业务更新清单(business-latest.json)
#[derive(Deserialize)]
struct Manifest {
    version: String,
    url: String,
    /// zip 的 minisign 签名(base64 编码的 .minisig 文件内容,与 updater 的 signature 字段同格式)
    signature: String,
    /// 运行该业务包所需的最低壳版本(tauri.conf.json 的 version)
    #[serde(rename = "minShell")]
    min_shell: Option<String>,
    notes: Option<String>,
}

/// 本轮业务更新的收尾指示
enum Outcome {
    /// 无事发生或业务通道已处理完毕
    Done,
    /// minShell 高于壳版本:业务包装不了,需交回全量 updater 通道
    NeedShellUpdate,
}

/// 版本切换互斥:自动轮询与手动离线升级共用,防止两路并发「换目录 + 重启」
static SWITCH_LOCK: Mutex<()> = Mutex::new(());

/// 手动业务升级的结果(序列化给前端)
#[derive(serde::Serialize)]
pub struct ManualOutcome {
    /// "cancelled"(用户取消任一对话框) | "installed"(已切换并重启)
    pub status: &'static str,
    pub version: Option<String>,
}

/// 更新后台线程:启动即检查一轮,之后每 UPDATE_CHECK_INTERVAL 一轮;
/// 每轮先业务通道后全量通道,任一失败只记日志,间隔照常
pub fn spawn_update_thread(
    app: tauri::AppHandle,
    mgr: Arc<BackendManager>,
    versions_dir: Option<PathBuf>,
    static_root: Arc<Mutex<PathBuf>>,
    portable: bool,
) {
    std::thread::spawn(move || loop {
        let mut shell_checked = false;
        // 业务通道:安装版与绿色版都启用;macOS 跳过(.app 自修改问题,见模块注释)
        if !cfg!(target_os = "macos") {
            if let Some(vd) = versions_dir.as_ref() {
                match try_business_update(&app, &mgr, vd, &static_root) {
                    Ok(Outcome::NeedShellUpdate) => {
                        shell_checked = true;
                        run_shell_update(&app, &mgr, portable);
                    }
                    Ok(Outcome::Done) => {}
                    Err(e) => eprintln!("[dq-tool-tauri] 业务更新失败(忽略,不影响使用):{e}"),
                }
            }
        }
        // 全量通道:仅安装版(绿色版禁用 —— 更新包是 NSIS 安装包,会装进 Programs 目录破坏绿色形态)
        if !shell_checked && !portable {
            run_shell_update(&app, &mgr, portable);
        }
        std::thread::sleep(crate::UPDATE_CHECK_INTERVAL);
    });
}

fn run_shell_update(app: &tauri::AppHandle, mgr: &BackendManager, portable: bool) {
    if portable {
        eprintln!("[dq-tool-tauri] 绿色版不走全量 updater,请手动下载新版整体替换");
        return;
    }
    if let Err(e) = crate::try_auto_update(app, mgr.child_handle()) {
        eprintln!("[dq-tool-tauri] 自动更新失败(忽略,不影响使用):{e}");
    }
}

/// 业务通道单轮:清单 → 跳过判断 → 下载 → 验签 → 解压 → 校验 → 询问 → 切换重启
fn try_business_update(
    app: &tauri::AppHandle,
    mgr: &Arc<BackendManager>,
    versions_dir: &Path,
    static_root: &Arc<Mutex<PathBuf>>,
) -> Result<Outcome, String> {
    let manifest_url = std::env::var("DQ_BUSINESS_MANIFEST")
        .unwrap_or_else(|_| DEFAULT_MANIFEST_URL.to_string());
    let manifest = fetch_manifest(&manifest_url)?;

    let current = versions::resolve_current(versions_dir)?.version;
    let new_key = versions::version_key(&manifest.version)
        .ok_or_else(|| format!("清单版本号无法解析:{}", manifest.version))?;
    if Some(&new_key) <= versions::version_key(&current).as_ref() {
        return Ok(Outcome::Done); // 已是最新
    }
    if crate::read_skipped_version().as_deref() == Some(manifest.version.as_str()) {
        eprintln!("[dq-tool-tauri] 新业务版本 {} 此前已被用户跳过,不再提示", manifest.version);
        return Ok(Outcome::Done);
    }
    // minShell 高于壳版本时业务包装不了,交回全量通道升级壳。
    // 注意壳版本取 tauri.conf.json 的 version(app.package_info()),不是 env!("CARGO_PKG_VERSION")
    // —— Cargo.toml 的 package.version 恒为 0.1.0,与产品版本无映射
    if let Some(min_shell) = manifest.min_shell.as_deref() {
        let shell = app.package_info().version.to_string();
        match (versions::version_key(min_shell), versions::version_key(&shell)) {
            (Some(req), Some(cur)) if req > cur => {
                eprintln!(
                    "[dq-tool-tauri] 业务版本 {} 要求壳版本 ≥ {min_shell}(当前 {shell}),转全量更新通道",
                    manifest.version
                );
                return Ok(Outcome::NeedShellUpdate);
            }
            (None, _) => eprintln!("[dq-tool-tauri] 清单 minShell 无法解析:{min_shell},按无约束处理"),
            _ => {}
        }
    }

    eprintln!("[dq-tool-tauri] 发现新业务版本 {},后台预下载...", manifest.version);
    let v = &manifest.version;
    let staging_zip = versions_dir.join(format!(".staging-{v}.zip"));
    let staging_dir = versions_dir.join(format!(".staging-{v}"));
    download_zip(&manifest.url, &staging_zip)?;
    // 验签失败:删暂存、记日志、结束本轮(不进入解压)
    if let Err(e) = verify_signature(&staging_zip, &manifest.signature) {
        let _ = std::fs::remove_file(&staging_zip);
        return Err(e);
    }
    eprintln!("[dq-tool-tauri] 业务更新包验签通过,解压...");
    extract_zip(&staging_zip, &staging_dir)?;
    let _ = std::fs::remove_file(&staging_zip);
    validate_staging(&staging_dir, v)?;

    // 与手动离线升级互斥:落位到切换完成全程持锁(对话框阻塞期间手动流程会在最后一步等待)
    let _guard = SWITCH_LOCK.lock().unwrap();
    let final_dir = versions_dir.join(v);
    if final_dir.exists() {
        // 旧残留(上次「暂不更新」留下的目录或中断的 rename):以本次新解压为准
        let _ = std::fs::remove_dir_all(&final_dir);
    }
    std::fs::rename(&staging_dir, &final_dir).map_err(|e| format!("落位 versions/{v} 失败:{e}"))?;

    // 原生对话框确认(阻塞期间本线程停住,下一轮检查顺延,与全量通道一致)
    use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
    let mut msg = format!(
        "新业务版本 {v} 已预下载完成。\n\n「立即更新」将重启后端服务并刷新页面(进行中的扫描会中断,之后可断点续扫);「暂不更新」则该版本不再提示。"
    );
    if let Some(notes) = manifest.notes.as_deref().filter(|n| !n.trim().is_empty()) {
        msg.push_str(&format!("\n\n更新说明:{notes}"));
    }
    let yes = app
        .dialog()
        .message(msg)
        .title("dq-tool 更新")
        .kind(MessageDialogKind::Info)
        .buttons(MessageDialogButtons::OkCancelCustom(
            "立即更新".into(),
            "暂不更新".into(),
        ))
        .blocking_show();
    if !yes {
        eprintln!("[dq-tool-tauri] 用户暂不更新,跳过业务版本 {v}(版本目录保留,不影响运行)");
        crate::write_skipped_version(v);
        return Ok(Outcome::Done);
    }

    eprintln!("[dq-tool-tauri] 用户确认更新");
    // 就绪失败已自动回滚;错误(含写 current 失败)记日志后按「忽略,不影响使用」收尾
    if let Err(e) = switch_to_version(app, mgr, versions_dir, static_root, &final_dir, v) {
        eprintln!("[dq-tool-tauri] 切换业务版本失败(忽略,不影响使用):{e}");
    }
    Ok(Outcome::Done)
}

/// 切换业务版本并重启后端:写 current 指针 → GC 留新旧两版 → 重启 java →
/// 就绪成功才切 static 根并刷新页面(旧页面全程由旧 static 服务,不混版本);
/// 就绪失败自动回滚次新版(与启动监督共用 rollback_once,回滚失败不 fatal)。
/// 自动通道与手动离线升级共用;调用方须已持有 SWITCH_LOCK。
fn switch_to_version(
    app: &tauri::AppHandle,
    mgr: &Arc<BackendManager>,
    versions_dir: &Path,
    static_root: &Arc<Mutex<PathBuf>>,
    final_dir: &Path,
    v: &str,
) -> Result<(), String> {
    eprintln!("[dq-tool-tauri] 切换业务版本 {v} 并重启后端...");
    versions::write_current(versions_dir, v)?;
    // GC 留新旧两版:新版成为 current,旧版作为「其余中最高者」留作回滚
    versions::gc_versions(versions_dir, v);
    let switched = mgr
        .restart(&final_dir.join("dq-tool.jar"))
        .and_then(|_| mgr.wait_ready().map(|_| ()));
    match switched {
        Ok(()) => {
            // 就绪后才切 static 根并刷新页面:旧页面全程由旧 static 服务,不混版本
            *static_root.lock().unwrap() = final_dir.join("static");
            mgr.ready_flag().store(true, Ordering::SeqCst);
            eprintln!("[dq-tool-tauri] 业务版本 {v} 已就绪,刷新页面");
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.eval("location.reload()");
            }
            Ok(())
        }
        Err(e) => {
            eprintln!("[dq-tool-tauri] 新版本 {v} 就绪失败:{e};回滚上一业务版本");
            match crate::backend::rollback_once(mgr, versions_dir, static_root) {
                Ok(old) => eprintln!("[dq-tool-tauri] 已回滚到业务版本 {old}"),
                // 与启动路径不同,这里不 fatal:current 已被回滚逻辑指回旧版,
                // 用户重启应用即可恢复;壳保持可用比直接退出好
                Err(e2) => eprintln!("[dq-tool-tauri] 回滚也失败:{e2};请重启应用"),
            }
            Err(format!("新版本 {v} 就绪失败,已回滚上一业务版本:{e}"))
        }
    }
}

/// 手动离线升级(系统设置「选择升级包」):用户选本地 business zip,要求同目录有
/// Release 页同名的 .zip.sig(base64 单行 minisig,与清单 signature 字段同格式)。
/// 验签 → 解压校验 → 版本确认 → 落位 versions/<v>/ → 切换重启,全部复用自动通道函数。
/// 离线拿不到 business-latest.json,不做 minShell 检查:壳过旧时切换后就绪失败,
/// 由 switch_to_version 自动回滚兜底。macOS/开发模式由调用方(main.rs)拦截,不进这里。
pub fn manual_business_update(
    app: &tauri::AppHandle,
    mgr: &Arc<BackendManager>,
    versions_dir: &Path,
    static_root: &Arc<Mutex<PathBuf>>,
) -> Result<ManualOutcome, String> {
    use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
    let cancelled = || ManualOutcome { status: "cancelled", version: None };

    let picked = app
        .dialog()
        .file()
        .add_filter("dq-tool 业务升级包", &["zip"])
        .blocking_pick_file();
    let Some(picked) = picked else {
        return Ok(cancelled());
    };
    let zip_path = picked.into_path().map_err(|e| format!("升级包路径不可用:{e}"))?;

    // 签名文件约定:与 zip 同目录、全名追加 .sig(Release 页三个附件中的 .zip.sig)
    let mut sig_os = zip_path.as_os_str().to_os_string();
    sig_os.push(".sig");
    let sig_path = PathBuf::from(sig_os);
    let signature = std::fs::read_to_string(&sig_path).map_err(|_| {
        format!(
            "未找到签名文件,请将 Release 页面同名的 {} 与升级包放在同一目录后重试",
            sig_path
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_else(|| ".zip.sig".to_string())
        )
    })?;
    verify_signature(&zip_path, signature.trim())?;
    eprintln!("[dq-tool-tauri] 手动升级包验签通过,解压...");

    let staging_dir = versions_dir.join(".staging-manual");
    if let Err(e) = extract_zip(&zip_path, &staging_dir) {
        let _ = std::fs::remove_dir_all(&staging_dir);
        return Err(e);
    }
    // 版本号以包内 manifest.json 为准(离线没有清单可比对)
    let v = match read_staging_version(&staging_dir) {
        Ok(v) => v,
        Err(e) => {
            let _ = std::fs::remove_dir_all(&staging_dir);
            return Err(e);
        }
    };
    if let Err(e) = validate_staging(&staging_dir, &v) {
        let _ = std::fs::remove_dir_all(&staging_dir);
        return Err(e);
    }

    // 同版本放行(修复重装场景);更低版本需用户显式确认降级
    let current = versions::resolve_current(versions_dir)?.version;
    if let (Some(new_key), Some(cur_key)) =
        (versions::version_key(&v), versions::version_key(&current))
    {
        if new_key < cur_key {
            let yes = app
                .dialog()
                .message(format!(
                    "升级包版本 {v} 低于当前业务版本 {current}。\n\n确认降级安装?"
                ))
                .title("dq-tool 手动升级")
                .kind(MessageDialogKind::Warning)
                .buttons(MessageDialogButtons::OkCancelCustom(
                    "降级安装".into(),
                    "取消".into(),
                ))
                .blocking_show();
            if !yes {
                let _ = std::fs::remove_dir_all(&staging_dir);
                return Ok(cancelled());
            }
        }
    }

    let yes = app
        .dialog()
        .message(format!(
            "业务版本 {v} 已校验通过。\n\n「立即更新」将重启后端服务并刷新页面(进行中的扫描会中断,之后可断点续扫)。"
        ))
        .title("dq-tool 手动升级")
        .kind(MessageDialogKind::Info)
        .buttons(MessageDialogButtons::OkCancelCustom(
            "立即更新".into(),
            "取消".into(),
        ))
        .blocking_show();
    if !yes {
        let _ = std::fs::remove_dir_all(&staging_dir);
        return Ok(cancelled());
    }

    let _guard = SWITCH_LOCK.lock().unwrap();
    let final_dir = versions_dir.join(&v);
    if final_dir.exists() {
        let _ = std::fs::remove_dir_all(&final_dir);
    }
    std::fs::rename(&staging_dir, &final_dir).map_err(|e| format!("落位 versions/{v} 失败:{e}"))?;
    switch_to_version(app, mgr, versions_dir, static_root, &final_dir, &v)?;
    Ok(ManualOutcome { status: "installed", version: Some(v) })
}

/// 读暂存目录内 manifest.json 的版本号并校验可解析
fn read_staging_version(staging_dir: &Path) -> Result<String, String> {
    let text = std::fs::read_to_string(staging_dir.join("manifest.json"))
        .map_err(|e| format!("读取包内 manifest.json 失败:{e}"))?;
    let inner: serde_json::Value =
        serde_json::from_str(&text).map_err(|e| format!("解析包内 manifest.json 失败:{e}"))?;
    let v = inner["version"].as_str().unwrap_or("").to_string();
    if versions::version_key(&v).is_none() {
        return Err(format!("升级包内 manifest.json 版本号无效:{v}"));
    }
    Ok(v)
}

/// 拉取并解析业务更新清单(30 秒总超时,防止网络悬死拖住更新线程)
fn fetch_manifest(url: &str) -> Result<Manifest, String> {
    let config = ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(30)))
        .build();
    let mut resp = ureq::Agent::new_with_config(config)
        .get(url)
        .call()
        .map_err(|e| format!("拉取业务更新清单失败:{e}"))?;
    let body = resp
        .body_mut()
        .read_to_string()
        .map_err(|e| format!("读取业务更新清单失败:{e}"))?;
    serde_json::from_str(&body).map_err(|e| format!("解析业务更新清单失败:{e}"))
}

/// 流式预下载 zip(15 分钟总超时;每 32MB 打一条进度日志,与全量通道口径一致)
fn download_zip(url: &str, dest: &Path) -> Result<(), String> {
    use std::io::{Read, Write};
    let config = ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(15 * 60)))
        .build();
    let mut resp = ureq::Agent::new_with_config(config)
        .get(url)
        .call()
        .map_err(|e| format!("下载业务更新包失败:{e}"))?;
    let mut file = std::fs::File::create(dest).map_err(|e| format!("创建暂存文件失败:{e}"))?;
    let mut reader = resp.body_mut().as_reader();
    let mut buf = vec![0u8; 1024 * 1024];
    let mut total = 0usize;
    let mut next_mark = 32 * 1024 * 1024;
    loop {
        let n = reader.read(&mut buf).map_err(|e| format!("下载中断:{e}"))?;
        if n == 0 {
            break;
        }
        file.write_all(&buf[..n]).map_err(|e| format!("写暂存文件失败:{e}"))?;
        total += n;
        if total >= next_mark {
            eprintln!("[dq-tool-tauri] 业务更新包已预下载 {}MB", total / 1024 / 1024);
            next_mark += 32 * 1024 * 1024;
        }
    }
    eprintln!("[dq-tool-tauri] 业务更新包预下载完成({}MB)", total / 1024 / 1024);
    Ok(())
}

/// minisign 验签:与 tauri-plugin-updater 的 verify_signature 同一流程
/// (base64 解码 → PublicKey/Signature::decode → verify)
fn verify_signature(zip_path: &Path, signature_b64: &str) -> Result<(), String> {
    use base64::Engine;
    let b64 = base64::engine::general_purpose::STANDARD;
    let decode_str = |s: &str| -> Result<String, String> {
        let bytes = b64.decode(s).map_err(|e| format!("base64 解码失败:{e}"))?;
        String::from_utf8(bytes).map_err(|e| format!("base64 内容非 UTF-8:{e}"))
    };
    let data = std::fs::read(zip_path).map_err(|e| format!("读取暂存包失败:{e}"))?;
    let public_key = minisign_verify::PublicKey::decode(&decode_str(PUBKEY_B64)?)
        .map_err(|e| format!("验签公钥解析失败:{e}"))?;
    let signature = minisign_verify::Signature::decode(&decode_str(signature_b64)?)
        .map_err(|e| format!("签名解析失败:{e}"))?;
    public_key
        .verify(&data, &signature, true)
        .map_err(|e| format!("业务更新包验签失败:{e}"))
}

/// 解压 zip 到 staging 目录:逐条目写盘,enclosed_name 防 zip-slip 穿越
fn extract_zip(zip_path: &Path, staging_dir: &Path) -> Result<(), String> {
    if staging_dir.exists() {
        let _ = std::fs::remove_dir_all(staging_dir);
    }
    std::fs::create_dir_all(staging_dir).map_err(|e| format!("创建暂存目录失败:{e}"))?;
    let file = std::fs::File::open(zip_path).map_err(|e| format!("打开更新包失败:{e}"))?;
    let mut archive = zip::ZipArchive::new(std::io::BufReader::new(file))
        .map_err(|e| format!("更新包不是合法 zip:{e}"))?;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| format!("读取 zip 条目失败:{e}"))?;
        let Some(rel) = entry.enclosed_name() else {
            continue; // 路径含 .. 等不安全成分,跳过
        };
        let out = staging_dir.join(rel);
        if entry.is_dir() {
            std::fs::create_dir_all(&out).map_err(|e| format!("创建目录失败:{e}"))?;
        } else {
            if let Some(parent) = out.parent() {
                std::fs::create_dir_all(parent).map_err(|e| format!("创建目录失败:{e}"))?;
            }
            let mut out_file = std::fs::File::create(&out).map_err(|e| format!("创建文件失败:{e}"))?;
            std::io::copy(&mut entry, &mut out_file).map_err(|e| format!("解压失败:{e}"))?;
        }
    }
    Ok(())
}

/// staging 目录完整性校验:jar、前端入口、包内 manifest.json 的版本号与清单一致
fn validate_staging(staging_dir: &Path, expect_version: &str) -> Result<(), String> {
    for required in ["dq-tool.jar", "static/index.html", "manifest.json"] {
        if !staging_dir.join(required).is_file() {
            return Err(format!("更新包缺少必需文件:{required}"));
        }
    }
    let text = std::fs::read_to_string(staging_dir.join("manifest.json"))
        .map_err(|e| format!("读取包内 manifest.json 失败:{e}"))?;
    let inner: serde_json::Value =
        serde_json::from_str(&text).map_err(|e| format!("解析包内 manifest.json 失败:{e}"))?;
    let inner_version = inner["version"].as_str().unwrap_or("");
    if inner_version != expect_version {
        return Err(format!(
            "包内版本号 {inner_version} 与清单 {expect_version} 不一致"
        ));
    }
    Ok(())
}
