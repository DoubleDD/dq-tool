package com.example.dq.controller;

import com.example.dq.model.LanManualPeerRequest;
import com.example.dq.model.LanPullRequest;
import com.example.dq.model.LanSettingsRequest;
import com.example.dq.service.LanShareService;
import io.javalin.http.Context;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 局域网共享(Javalin handler,路由在 WebServer 注册):
 * 状态/在线实例/设置为本机页面用;share/* 两个导出端点供同网段其他实例 HTTP 拉取(授权前置校验放行),
 * pull 为主动从指定实例拉取数据导入本机。
 */
public class LanController {

    private final LanShareService service;

    public LanController(LanShareService service) {
        this.service = service;
    }

    /** 本机共享状态:有效开关/发现是否在跑/实例 id 与名称/发现端口/在线实例数 */
    public void status(Context ctx) {
        ctx.json(service.status());
    }

    /** 当前在线的其他实例列表(UDP 心跳发现,纯内存) */
    public void peers(Context ctx) {
        ctx.json(service.peers());
    }

    /** 保存共享设置(开关/实例名,null 字段不修改);开关即时启停发现服务 */
    public void saveSettings(Context ctx) {
        ctx.json(service.saveSettings(ctx.bodyAsClass(LanSettingsRequest.class)));
    }

    /** 共享出口:本机标注数据预览(标记清单 + 表级数据按数据源分布),供 peer 拉取前勾选 */
    public void shareAnnotationsPreview(Context ctx) {
        ctx.json(service.localAnnotationsPreview());
    }

    /** 共享出口:导出本机全部标记与描述(含表所属系统),供 peer 拉取;浏览器直接访问则下载 JSON */
    public void shareAnnotations(Context ctx) throws IOException {
        streamJson(ctx, "dq-annotations");
        service.exportAnnotations(ctx.res().getOutputStream());
    }

    /** 共享出口:本机数据源清单(脱敏,不含密码),供 peer 做映射选择与无密码副本新建 */
    public void shareDatasources(Context ctx) {
        ctx.json(service.localDatasourcesPreview());
    }

    /** 共享出口:本机扫描任务预览(轻量摘要),供 peer 勾选要导入的任务 */
    public void shareScansPreview(Context ctx) {
        ctx.json(service.localScanJobsPreview());
    }

    /** 共享出口:导出本机扫描记录;queryParam ids 逗号分隔,缺省/空 = 全部任务 */
    public void shareScans(Context ctx) throws IOException {
        streamJson(ctx, "dq-scans");
        service.exportScans(parseIds(ctx.queryParam("ids")), ctx.res().getOutputStream());
    }

    /** 共享出口:本机实例身份(实例 id/名称/版本),供其他实例手动添加时直连探测确认 */
    public void shareInfo(Context ctx) {
        ctx.json(service.shareInfo());
    }

    /** 手动添加实例(UDP 广播发现不可用的网络):立即直连探测,确认对方是本软件实例才入库 */
    public void addManualPeer(Context ctx) {
        LanManualPeerRequest req = ctx.bodyAsClass(LanManualPeerRequest.class);
        ctx.json(service.addManualPeer(req.getHost() == null ? "" : req.getHost(), req.getPort()));
    }

    /** 移除手动添加的实例(query 参数 host/port) */
    public void removeManualPeer(Context ctx) {
        String host = ctx.queryParam("host");
        String port = ctx.queryParam("port");
        if (host == null || host.isBlank() || port == null) {
            throw new IllegalArgumentException("缺少 host/port 参数");
        }
        service.removeManualPeer(host.trim(), Integer.parseInt(port));
        ctx.json(Map.of("ok", true));
    }

    /** 同步前预览:拉取指定在线实例的标注/扫描任务预览,供页面勾选 */
    public void previewPeer(Context ctx) {
        ctx.json(service.previewPeer(ctx.pathParam("instanceId")));
    }

    /** 主动从指定在线实例拉取勾选的数据(标注选择 / 扫描任务 id 列表)并导入合并 */
    public void pull(Context ctx) {
        String instanceId = ctx.pathParam("instanceId");
        ctx.json(service.pull(instanceId, ctx.bodyAsClass(LanPullRequest.class)));
    }

    private static void streamJson(Context ctx, String prefix) {
        String filename = URLEncoder.encode(prefix + "-"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json",
                StandardCharsets.UTF_8);
        HttpServletResponse response = ctx.res();
        response.setContentType("application/json");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
    }

    /** 逗号分隔的任务 id 列表;空白项忽略,非法值走 400 */
    private static List<Long> parseIds(String ids) {
        if (ids == null || ids.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(ids.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .toList();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("任务 id 列表格式不正确", e);
        }
    }
}
