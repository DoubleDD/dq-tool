//! dq 自定义协议处理器:webview 窗口 URL 为 dq://localhost/index.html,
//! 页面与静态资源从磁盘「当前业务版本 static 根」加载(release: <resources>/versions/<current>/static;
//! 开发模式:仓库 web/dist)。
//!
//! 改造前 exe 经 frontendDist 编译期内嵌前端;三层分离后前端归属业务层随版本目录分发,
//! exe 不再内嵌,业务层在线更新重启后端后页面 location.reload() 即从新 static 加载
//! (static 根由 BackendManager 在重启/回滚成功后更新,本处理器每请求读取)。
//!
//! 路由规则(前端是 createWebHistory):/ 与无扩展名路径回退 index.html;
//! 有扩展名按文件读,不存在返回 404。用户注册的自定义协议在 Tauri IPC/ACL 里按 local
//! 来源处理(tauri-2.11.5 webview/mod.rs is_local_url),capabilities 无需 remote.urls。

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use tauri::http::{Request, Response, StatusCode};

/// 协议请求处理:同步读盘即可(本地文件,前端首屏几十个资源,量级无压力)
pub fn handle(static_root: &Arc<Mutex<PathBuf>>, request: Request<Vec<u8>>) -> Response<Vec<u8>> {
    let rel = request.uri().path().trim_start_matches('/');
    // 拒绝路径穿越与编码迂回:前端产物(vite 哈希文件名)均为 ASCII,
    // 不支持 % 解码,含 %/\\ 或 .. 的一律 400
    if rel.split('/').any(|seg| seg == ".." || seg.contains(['%', '\\'])) {
        return text_response(StatusCode::BAD_REQUEST, "bad path");
    }
    let root = static_root.lock().expect("static 根锁").clone();
    let file = root.join(rel);
    if file.is_file() {
        return file_response(&file);
    }
    // 文件不存在:有扩展名的是真 404;无扩展名的是前端路由,回退 index.html
    if Path::new(rel).extension().is_some() {
        return text_response(StatusCode::NOT_FOUND, "not found");
    }
    let index = root.join("index.html");
    if index.is_file() {
        file_response(&index)
    } else {
        text_response(StatusCode::NOT_FOUND, "index.html missing")
    }
}

/// 读文件并按扩展名给 Content-Type;text/* 补 charset=utf-8(页面含中文)
fn file_response(path: &Path) -> Response<Vec<u8>> {
    match std::fs::read(path) {
        Ok(bytes) => {
            let mut content_type = mime_guess::from_path(path)
                .first_or_octet_stream()
                .to_string();
            if content_type.starts_with("text/") || content_type == "image/svg+xml" {
                content_type.push_str("; charset=utf-8");
            }
            Response::builder()
                .header("Content-Type", content_type)
                .body(bytes)
                .expect("响应构造失败")
        }
        Err(e) => text_response(StatusCode::INTERNAL_SERVER_ERROR, &e.to_string()),
    }
}

fn text_response(status: StatusCode, msg: &str) -> Response<Vec<u8>> {
    Response::builder()
        .status(status)
        .header("Content-Type", "text/plain; charset=utf-8")
        .body(msg.as_bytes().to_vec())
        .expect("响应构造失败")
}
