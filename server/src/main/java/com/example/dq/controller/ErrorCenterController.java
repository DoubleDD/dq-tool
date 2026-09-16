package com.example.dq.controller;

import com.example.dq.model.ErrorEvent;
import com.example.dq.model.ErrorLevel;
import com.example.dq.model.ErrorPage;
import com.example.dq.model.ErrorQuery;
import com.example.dq.model.ErrorRecord;
import com.example.dq.model.ErrorSource;
import com.example.dq.model.ErrorStats;
import com.example.dq.service.ErrorCenterService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 错误中心端点:前端上报 + 统一查询/筛选/标记/清理/导出。
 *
 * <p>`/api/errors` 前缀在授权前置校验中放行(与 `/api/diagnostics` 同待遇):未激活/启动异常
 * 恰是最需要排查的场景;写操作本身只影响错误记录,不触达业务数据。
 *
 * <p>安全边界:前端上报内容一律按不可信输入处理 —— 来源固定为 FRONTEND(不采信客户端),
 * 单次最多 {@link #MAX_REPORT_EVENTS} 条、字段超长截断,体积超限直接丢弃;
 * 简单限流({@link #REPORT_MIN_INTERVAL_MS})挡同机脚本高频刷写。
 */
public class ErrorCenterController {

    /** 单次上报最多接收条数(前端本身也按 50 条批量) */
    private static final int MAX_REPORT_EVENTS = 50;
    private static final int MAX_MESSAGE_LEN = 2000;
    private static final int MAX_DETAIL_LEN = 20000;
    private static final int MAX_CONTEXT_LEN = 20000;
    private static final int MAX_ROUTE_LEN = 512;

    /** 导出条数上限:一次最多导出这些聚合错误(带完整堆栈,文件已足够反馈给开发) */
    private static final int EXPORT_MAX = 500;

    /** 上报限流窗口:同一窗口内只接收一次批量上报(前端已批量聚合,正常远低于此频率) */
    private static final long REPORT_MIN_INTERVAL_MS = 200;

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 百分号编码序列 %XX:命中才尝试解码,避免对普通文本做无谓处理 */
    private static final Pattern PERCENT_SEQ = Pattern.compile("%[0-9A-Fa-f]{2}");

    private final ErrorCenterService service;
    private volatile long lastReportAt = 0L;

    public ErrorCenterController(ErrorCenterService service) {
        this.service = service;
    }

    // ---------- 前端上报 ----------

    /** POST /api/errors/report:前端错误批量上报 */
    public void report(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastReportAt < REPORT_MIN_INTERVAL_MS) {
            ctx.json(Map.of("accepted", 0, "dropped", 0, "throttled", true));
            return;
        }
        lastReportAt = now;

        ReportRequest req;
        try {
            req = ctx.bodyAsClass(ReportRequest.class);
        } catch (Exception e) {
            // 上报体损坏不能反过来报错(会引发前端重试风暴)
            ctx.json(Map.of("accepted", 0, "dropped", 0));
            return;
        }
        List<FrontendErrorItem> items = req == null || req.events() == null ? List.of() : req.events();
        int accepted = 0;
        int dropped = 0;
        for (FrontendErrorItem item : items) {
            if (accepted >= MAX_REPORT_EVENTS) {
                dropped++;
                continue;
            }
            if (item == null || isBlank(item.message())) {
                dropped++;
                continue;
            }
            service.record(new ErrorEvent(
                    ErrorSource.FRONTEND,
                    truncate(defaultIfBlank(item.kind(), "JS_ERROR"), 128),
                    parseLevel(item.level()),
                    truncate(item.message(), MAX_MESSAGE_LEN),
                    truncate(item.stack(), MAX_DETAIL_LEN),
                    truncate(item.context(), MAX_CONTEXT_LEN),
                    truncate(item.logger(), 255),
                    null,
                    truncate(item.route(), MAX_ROUTE_LEN),
                    LocalDateTime.now()
            ));
            accepted++;
        }
        ctx.json(Map.of("accepted", accepted, "dropped", dropped));
    }

    // ---------- 查询 ----------

    /** GET /api/errors/stats:统计卡片 */
    public void stats(Context ctx) {
        ErrorStats stats = service.stats();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", stats.getTotal());
        body.put("open", stats.getOpen());
        body.put("todayNew", stats.getTodayNew());
        body.put("bySource", stats.getBySource());
        body.put("byLevel", stats.getByLevel());
        body.put("dropped", service.droppedCount());
        body.put("spoolPending", service.spoolPending());
        ctx.json(body);
    }

    /** GET /api/errors:分页列表(最近发生时间倒序) */
    public void list(Context ctx) {
        ErrorPage page = service.query(queryFrom(ctx));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", page.getTotal());
        body.put("page", page.getPage());
        body.put("size", page.getSize());
        body.put("items", page.getItems());
        ctx.json(body);
    }

    /** GET /api/errors/{id}:单条详情(含完整堆栈) */
    public void detail(Context ctx) {
        long id = pathId(ctx);
        ErrorRecord record = service.findById(id);
        if (record == null) {
            throw new NotFoundResponse("错误记录不存在: " + id);
        }
        ctx.json(record);
    }

    // ---------- 标记/删除/清理 ----------

    /** PUT /api/errors/{id}/status:标记已处理/已忽略 */
    public void updateStatus(Context ctx) {
        long id = pathId(ctx);
        StatusRequest req = ctx.bodyAsClass(StatusRequest.class);
        String status = parseStatus(req == null ? null : req.status());
        service.updateStatus(List.of(id), status, truncate(req == null ? null : req.note(), MAX_MESSAGE_LEN));
        ctx.json(Map.of("ok", true, "status", status));
    }

    /** POST /api/errors/batch-status:批量标记 */
    public void batchStatus(Context ctx) {
        BatchStatusRequest req = ctx.bodyAsClass(BatchStatusRequest.class);
        List<Long> ids = req == null || req.ids() == null ? List.of() : req.ids();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("ids 不能为空");
        }
        String status = parseStatus(req.status());
        int updated = service.updateStatus(ids, status, truncate(req.note(), MAX_MESSAGE_LEN));
        ctx.json(Map.of("ok", true, "updated", updated, "status", status));
    }

    /** DELETE /api/errors/{id}:删除单条 */
    public void delete(Context ctx) {
        int deleted = service.delete(List.of(pathId(ctx)));
        ctx.json(Map.of("ok", true, "deleted", deleted));
    }

    /** POST /api/errors/clear:按筛选条件清空(条件全空 = 清空全部) */
    public void clear(Context ctx) {
        ClearRequest req = ctx.bodyAsClass(ClearRequest.class);
        int deleted = service.clear(queryOf(null, null, req == null ? List.of() : req.statuses(),
                req == null ? null : req.keyword(), req == null ? null : req.from(), req == null ? null : req.to(),
                req == null ? List.of() : req.sources(), req == null ? List.of() : req.levels()));
        ctx.json(Map.of("ok", true, "deleted", deleted));
    }

    /** POST /api/errors/purge:手动按天数清理 */
    public void purge(Context ctx) {
        PurgeRequest req = ctx.bodyAsClass(PurgeRequest.class);
        int days = req == null || req.days() == null ? 30 : req.days();
        ctx.json(Map.of("ok", true, "deleted", service.purge(days), "days", days));
    }

    // ---------- 导出 ----------

    /**
     * GET /api/errors/export:导出 Markdown/JSON(便于发给开发)。
     * 两种口径:`ids=1,2,3` 导出**所选行**(表格勾选/单行导出,忽略筛选条件,最多 {@link #EXPORT_MAX} 条);
     * 不带 ids 时沿用列表筛选条件导出全部匹配项。
     */
    public void export(Context ctx) {
        List<Long> ids = parseIds(ctx.queryParam("ids"));
        List<ErrorRecord> items;
        if (!ids.isEmpty()) {
            items = service.findByIds(ids.size() > EXPORT_MAX ? ids.subList(0, EXPORT_MAX) : ids);
        } else {
            ErrorQuery q = queryOf(1, EXPORT_MAX, statusesFrom(ctx), ctx.queryParam("keyword"),
                    ctx.queryParam("from"), ctx.queryParam("to"),
                    multiParam(ctx, "source"), multiParam(ctx, "level"));
            items = service.query(q).getItems();
        }
        String format = ctx.queryParam("format");
        String stamp = FILE_TS.format(LocalDateTime.now());
        if ("json".equalsIgnoreCase(format)) {
            ctx.contentType("application/json;charset=utf-8");
            ctx.header("Content-Disposition", "attachment; filename=\"dq-errors-" + stamp + ".json\"");
            ctx.json(items);
            return;
        }
        ctx.contentType("text/markdown;charset=utf-8");
        ctx.header("Content-Disposition", "attachment; filename=\"dq-errors-" + stamp + ".md\"");
        ctx.result(toMarkdown(items));
    }

    /** 解析 `ids=1,2,3`:只保留正整数并去重保序;非法输入静默忽略(导出参数容错) */
    private List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(text);
                if (id > 0 && !ids.contains(id)) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // 非数字忽略
            }
        }
        return ids;
    }

    private String toMarkdown(List<ErrorRecord> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("# dq-tool 错误中心导出\n\n");
        sb.append("- 导出时间:").append(LocalDateTime.now()).append('\n');
        sb.append("- 记录数:").append(items.size()).append('\n');
        sb.append("- 应用版本:").append(items.isEmpty() ? "-" : nullToDash(items.get(0).getAppVersion())).append("\n\n");
        if (items.isEmpty()) {
            sb.append("_无匹配的错误记录_\n");
            return sb.toString();
        }
        sb.append("| # | 来源 | 级别 | 类型 | 次数 | 首次 | 最近 | 消息 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        int i = 1;
        for (ErrorRecord r : items) {
            sb.append("| ").append(i++).append(" | ").append(r.getSource()).append(" | ").append(r.getLevel())
                    .append(" | ").append(escapeCell(r.getKind())).append(" | ").append(r.getOccurrences())
                    .append(" | ").append(r.getFirstSeen()).append(" | ").append(r.getLastSeen())
                    .append(" | ").append(escapeCell(r.getMessage()))
                    .append(" |\n");
        }
        sb.append("\n## 明细\n");
        for (ErrorRecord r : items) {
            sb.append("\n### [").append(r.getSource()).append("/").append(r.getLevel()).append("] ")
                    .append(r.getKind()).append(" ×").append(r.getOccurrences()).append('\n');
            sb.append("- 消息:").append(nullToDash(r.getMessage())).append('\n');
            sb.append("- 位置:").append(nullToDash(decodePercent(r.getRoute()))).append('\n');
            sb.append("- 线程:").append(nullToDash(r.getThread())).append("  logger:")
                    .append(nullToDash(r.getLogger())).append('\n');
            sb.append("- 首次/最近:").append(r.getFirstSeen()).append(" / ").append(r.getLastSeen()).append('\n');
            if (r.getContext() != null && !r.getContext().isBlank()) {
                sb.append("- 上下文:\n\n```\n").append(decodePercent(r.getContext())).append("\n```\n");
            }
            if (r.getDetail() != null && !r.getDetail().isBlank()) {
                sb.append("- 堆栈:\n\n```\n").append(r.getDetail()).append("```\n");
            }
        }
        return sb.toString();
    }

    // ---------- 参数解析 ----------

    private ErrorQuery queryFrom(Context ctx) {
        return queryOf(parseInt(ctx.queryParam("page"), 1), parseInt(ctx.queryParam("size"), 50),
                statusesFrom(ctx), ctx.queryParam("keyword"), ctx.queryParam("from"), ctx.queryParam("to"),
                multiParam(ctx, "source"), multiParam(ctx, "level"));
    }

    private ErrorQuery queryOf(Integer page, Integer size, List<String> statuses, String keyword,
                               String from, String to, List<String> sources, List<String> levels) {
        return new ErrorQuery(
                normalizeEnum(sources, SOURCES),
                normalizeEnum(levels, LEVELS),
                normalizeEnum(statuses, STATUSES),
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                parseTime(from, false),
                parseTime(to, true),
                page == null ? 1 : page,
                size == null ? 50 : size
        );
    }

    private static final Set<String> SOURCES = Set.of("FRONTEND", "BACKEND", "DATABASE", "TASK", "STARTUP");
    private static final Set<String> LEVELS = Set.of("WARN", "ERROR", "FATAL");
    private static final Set<String> STATUSES = Set.of("OPEN", "RESOLVED", "IGNORED");

    /** 只保留合法枚举值(忽略大小写),非法值静默丢弃而不是 400(筛选参数容错) */
    private List<String> normalizeEnum(List<String> values, Set<String> allowed) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v == null || v.isBlank()) {
                continue;
            }
            String upper = v.trim().toUpperCase();
            if (allowed.contains(upper) && !out.contains(upper)) {
                out.add(upper);
            }
        }
        return out;
    }

    private List<String> multiParam(Context ctx, String name) {
        List<String> values = ctx.queryParams(name);
        List<String> out = new ArrayList<>();
        for (String v : values) {
            // 逗号分隔也支持(前端拼 query 更省事)
            for (String part : v.split(",")) {
                if (!part.isBlank()) {
                    out.add(part);
                }
            }
        }
        return out;
    }

    private List<String> statusesFrom(Context ctx) {
        return multiParam(ctx, "status");
    }

    /** ISO 本地时间解析:支持 `yyyy-MM-dd`(to 侧补到当天末尾)与 `yyyy-MM-ddTHH:mm:ss` */
    private LocalDateTime parseTime(String text, boolean endOfDay) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            String value = text.trim();
            if (value.length() == 10) {
                LocalDate date = LocalDate.parse(value);
                return endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay();
            }
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private ErrorLevel parseLevel(String level) {
        if (level == null) {
            return ErrorLevel.ERROR;
        }
        try {
            return ErrorLevel.valueOf(level.trim().toUpperCase());
        } catch (Exception e) {
            return ErrorLevel.ERROR;
        }
    }

    private String parseStatus(String status) {
        if (status != null) {
            String upper = status.trim().toUpperCase();
            if (STATUSES.contains(upper)) {
                return upper;
            }
        }
        throw new IllegalArgumentException("非法状态(仅支持 OPEN/RESOLVED/IGNORED): " + status);
    }

    private long pathId(Context ctx) {
        return Long.parseLong(ctx.pathParam("id"));
    }

    /**
     * 展示用百分号解码:接口路径/页面地址里的查询串是编码过的(如 `?name=%E4%BB%BB%E5%8A%A1`),
     * 原文导出读不了。只在确实含 %XX 时解码,先把 `+` 临时转义再交给 URLDecoder ——
     * 避免它按表单口径把路径里的 `+` 解成空格(与前端 decodeURIComponent 口径一致)。
     * 解码失败(裸 % 等非法序列)原样返回;数据库里始终保留原始值。
     */
    private static String decodePercent(String text) {
        if (text == null || !PERCENT_SEQ.matcher(text).find()) {
            return text;
        }
        try {
            return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return text;
        }
    }

    private int parseInt(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String defaultIfBlank(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static String nullToDash(String text) {
        return text == null || text.isBlank() ? "-" : text;
    }

    private static String escapeCell(String text) {
        if (text == null) {
            return "-";
        }
        return text.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    // ---------- 请求体 ----------

    /** 前端上报的单条错误(来源由服务端固定为 FRONTEND,不采信客户端) */
    public record FrontendErrorItem(
            String kind,
            String level,
            String message,
            String stack,
            String route,
            String context,
            String logger
    ) {
    }

    public record ReportRequest(List<FrontendErrorItem> events) {
    }

    public record StatusRequest(String status, String note) {
    }

    public record BatchStatusRequest(List<Long> ids, String status, String note) {
    }

    public record ClearRequest(List<String> sources, List<String> levels, List<String> statuses,
                               String keyword, String from, String to) {
    }

    public record PurgeRequest(Integer days) {
    }
}
