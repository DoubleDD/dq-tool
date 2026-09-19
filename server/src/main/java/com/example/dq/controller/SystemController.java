package com.example.dq.controller;

import com.example.dq.config.AppConfig;
import com.example.dq.model.OpenFileRequest;
import com.example.dq.util.SystemOpen;
import io.javalin.http.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 系统级杂项端点(导出「直存数据目录 + 打开」的后半段):
 * POST /api/system/save-download —— 浏览器 / --app 形态与 Tauri 对齐的直存:服务端自调目标导出接口
 * (复用全部流式导出端点,零改动;Tauri 形态由 Rust save_download 扮演同一角色),
 * 响应流写 <数据目录>/exports/ 后返回 {path,name,size},前端成功后提供「打开文件/打开文件夹」;
 * POST /api/system/open —— 调系统默认关联程序打开产物文件;POST /api/system/reveal —— 打开所在目录并选中。
 * 路径一律限制在数据目录内,防任意文件打开。
 */
public class SystemController {

    /** 自调 HTTP client:无超时——大导出生成可达数分钟,与导出接口本身口径一致 */
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Pattern QUOTED_FILENAME = Pattern.compile("filename=\"([^\"]+)\"");

    private final AppConfig config;
    /** 实际绑定端口(server.port=0 时配置里没有,由 WebServer 注入) */
    private final IntSupplier port;
    /** 浏览器门禁令牌(配置 dq.access-token):自调必须自带,未配置时该头无影响 */
    private final List<String> accessTokens;

    public SystemController(AppConfig config, IntSupplier port, List<String> accessTokens) {
        this.config = config;
        this.port = port;
        this.accessTokens = accessTokens;
    }

    /** body {path};仅允许数据目录内的真实文件,由 SystemOpen 按系统默认关联打开 */
    public void open(Context ctx) throws Exception {
        SystemOpen.INSTANCE.openDefault(resolveInDataDir(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** body {path};打开文件所在目录并选中(macOS Finder / Windows 资源管理器) */
    public void reveal(Context ctx) throws Exception {
        SystemOpen.INSTANCE.reveal(resolveInDataDir(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** body {path: "/api/...(含 query)"};仅允许 /api/ 下的 GET 导出接口(自调直存,不走 system/ 与 SSE) */
    public void saveDownload(Context ctx) throws Exception {
        String target = ctx.bodyAsClass(OpenFileRequest.class).getPath();
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("缺少 path");
        }
        if (!target.startsWith("/api/") || target.startsWith("/api/system/") || target.startsWith("/api/logs/")) {
            throw new IllegalArgumentException("只能保存 /api/ 下的 GET 导出接口");
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port.getAsInt() + target)).GET();
        if (!accessTokens.isEmpty()) {
            req.header("X-Dq-Token", accessTokens.get(0));
        }
        // 流式:响应体直接写盘,不经内存;生成耗时期间本请求挂起(前端已弹「正在导出」)
        HttpResponse<InputStream> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        String name = sanitize(filenameFromDisposition(
                resp.headers().firstValue("Content-Disposition").orElse(null)));
        Path dir = config.getDataDir().resolve("exports");
        Files.createDirectories(dir);
        Path tmp = dir.resolve(name + "." + UUID.randomUUID().toString().substring(0, 8) + ".part");
        Path file = dir.resolve(name);
        long size;
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(tmp)) {
            if (resp.statusCode() / 100 != 2) {
                // 错误体一般很小,读出来给提示(与 Tauri 侧 Rust 读错误体一致)
                String msg = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("导出失败:HTTP " + resp.statusCode() + " " + msg.trim());
            }
            size = in.transferTo(out);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        ctx.json(Map.of("path", file.toAbsolutePath().toString(), "name", name, "size", size));
    }

    /** 数据目录内真实文件解析:resolve 对绝对路径直接取自身;normalize + toRealPath 消解 ../ 与符号链接 */
    private Path resolveInDataDir(Context ctx) throws IOException {
        String raw = ctx.bodyAsClass(OpenFileRequest.class).getPath();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("缺少 path");
        }
        Path base = config.getDataDir().toRealPath();
        Path target = base.resolve(raw.strip()).normalize();
        if (!Files.exists(target)) {
            throw new IllegalStateException("文件不存在或已被移动,请重新导出");
        }
        Path real = target.toRealPath();
        if (!real.startsWith(base)) {
            throw new IllegalArgumentException("只能打开数据目录内的文件");
        }
        return real;
    }

    /** 解码后的文件名理论上都是后端生成的 basename,仍防一手路径分隔符 */
    private static String sanitize(String name) {
        int unix = name.lastIndexOf('/');
        int win = name.lastIndexOf('\\');
        int idx = Math.max(unix, win);
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    /** 从 Content-Disposition 解析文件名:优先 filename*=UTF-8''(与前端/Rust 同口径),退回 filename="..." */
    static String filenameFromDisposition(String header) {
        if (header != null) {
            int idx = header.toLowerCase().indexOf("filename*=utf-8''");
            if (idx >= 0) {
                String encoded = header.substring(idx + "filename*=utf-8''".length()).trim();
                int semi = encoded.indexOf(';');
                if (semi >= 0) {
                    encoded = encoded.substring(0, semi).trim();
                }
                if (encoded.length() >= 2 && encoded.startsWith("\"") && encoded.endsWith("\"")) {
                    encoded = encoded.substring(1, encoded.length() - 1);
                }
                try {
                    // '+' 按 form 编码还原为空格(与 Rust percent_decode 口径一致)
                    return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {
                    // 百分号编码非法时退回 quoted 解析
                }
            }
            Matcher m = QUOTED_FILENAME.matcher(header);
            if (m.find()) {
                return m.group(1);
            }
        }
        return "download";
    }
}
