//! 业务层版本目录(仅 release 的安装/绿色形态;开发模式 debug_assertions 完全不经此模块)。
//!
//! 分层模型:exe(系统层)/ jre(runtime 层)不变,jar + 前端 static 作为业务层按版本落盘:
//!   <resources>/versions/<x.x.x>/dq-tool.jar
//!   <resources>/versions/<x.x.x>/static/(前端产物,含 index.html)
//!   <resources>/versions/<x.x.x>/manifest.json(业务更新包自带的元信息)
//!   <resources>/versions/current(文本指针,内容为一行版本号)
//! 业务层在线更新(bizupdate.rs)下载新版目录后改写 current 指针完成切换;
//! 磁盘只保留最近 2 个版本(gc_versions),就绪失败可回滚到次新版(second_newest)。

use std::path::{Path, PathBuf};

/// 一个解析成功的业务版本:版本号 + 目录
pub struct Resolved {
    pub version: String,
    pub dir: PathBuf,
}

/// 版本号按点分数字元组比较(2.0.14 > 2.0.9),不引 semver crate;
/// 允许带 v 前缀;任一段非纯数字则视为无法解析(该目录不参与版本选取)
pub fn version_key(v: &str) -> Option<Vec<u64>> {
    v.trim()
        .trim_start_matches('v')
        .split('.')
        .map(|seg| seg.parse::<u64>().ok())
        .collect()
}

/// 业务版本目录完整性判据:jar 与前端入口都在(缺任一即视为损坏,不参与选取)
fn is_valid_version_dir(dir: &Path) -> bool {
    dir.join("dq-tool.jar").is_file() && dir.join("static/index.html").is_file()
}

/// versions/ 下全部有效业务版本,按版本号从新到旧排序
fn valid_versions_desc(versions_dir: &Path) -> Vec<(String, PathBuf)> {
    let mut all: Vec<(Vec<u64>, String, PathBuf)> = std::fs::read_dir(versions_dir)
        .map(|rd| {
            rd.filter_map(|e| e.ok().map(|e| e.path()))
                .filter(|p| p.is_dir())
                .filter_map(|p| {
                    let name = p.file_name()?.to_str()?.to_string();
                    // .staging-* 是更新中的暂存目录,不算有效版本
                    let key = version_key(&name)?;
                    is_valid_version_dir(&p).then_some((key, name, p))
                })
                .collect()
        })
        .unwrap_or_default();
    all.sort_by(|a, b| b.0.cmp(&a.0));
    all.into_iter().map(|(_, v, p)| (v, p)).collect()
}

/// 解析当前业务版本:先读 current 指针并校验目录完整性;指针缺失/损坏/目录不完整时
/// 回落到磁盘上版本号最高的有效目录(此时指针不动,由调用方决定是否修复);
/// 一个有效版本都没有返回 Err(启动路径交给 fatal)
pub fn resolve_current(versions_dir: &Path) -> Result<Resolved, String> {
    if let Some(v) = read_current(versions_dir) {
        let dir = versions_dir.join(&v);
        if is_valid_version_dir(&dir) {
            return Ok(Resolved { version: v, dir });
        }
        eprintln!("[dq-tool-tauri] current 指针指向的业务版本 {v} 不完整,回落到磁盘最高有效版本");
    }
    valid_versions_desc(versions_dir)
        .into_iter()
        .next()
        .map(|(version, dir)| Resolved { version, dir })
        .ok_or_else(|| format!("{} 下没有可用的业务版本目录", versions_dir.display()))
}

/// 读 current 指针(一行版本号);文件不存在/读失败/为空返回 None
pub fn read_current(versions_dir: &Path) -> Option<String> {
    std::fs::read_to_string(versions_dir.join("current"))
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}

/// 写 current 指针:先写临时文件再 rename,避免更新中途断电留下半截指针
pub fn write_current(versions_dir: &Path, version: &str) -> Result<(), String> {
    let tmp = versions_dir.join(".current.tmp");
    std::fs::write(&tmp, format!("{version}\n")).map_err(|e| format!("写 current 临时文件失败:{e}"))?;
    std::fs::rename(&tmp, versions_dir.join("current")).map_err(|e| format!("切换 current 指针失败:{e}"))
}

/// 次新的有效业务版本(回滚目标):排序第二;不足两个版本返回 None
pub fn second_newest(versions_dir: &Path) -> Option<(String, PathBuf)> {
    valid_versions_desc(versions_dir).into_iter().nth(1)
}

/// 版本清理:保留 current 指向的版本 + 其余中版本号最高者,删除更老的;
/// 顺带清掉更新中断残留的 .staging-* 暂存(目录与 zip)。current 必须已校验有效
/// (调用方都在 resolve_current / 更新切换成功之后),本函数失败只记日志不抛错
pub fn gc_versions(versions_dir: &Path, current: &str) {
    let Ok(rd) = std::fs::read_dir(versions_dir) else { return };
    for entry in rd.filter_map(|e| e.ok().map(|e| e.path())) {
        let Some(name) = entry.file_name().and_then(|n| n.to_str()).map(|s| s.to_string()) else {
            continue;
        };
        if name.starts_with(".staging-") {
            let _ = std::fs::remove_dir_all(&entry);
            let _ = std::fs::remove_file(&entry);
        }
    }
    let mut kept_other = false;
    for (v, dir) in valid_versions_desc(versions_dir) {
        if v == current {
            continue;
        }
        if !kept_other {
            kept_other = true; // 其余中版本号最高者,留作回滚
            continue;
        }
        eprintln!("[dq-tool-tauri] 清理旧业务版本:{v}");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
