package com.example.dq.controller;

import com.example.dq.model.CreateCompareJobRequest;
import com.example.dq.model.MappingSuggestRequest;
import com.example.dq.repository.CompareRepository;
import com.example.dq.service.CompareService;
import com.example.dq.web.Validators;
import io.javalin.http.Context;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据比对任务(Javalin handler,路由在 WebServer 注册) */
public class CompareController {

    private final CompareService service;

    public CompareController(CompareService service) {
        this.service = service;
    }

    /** 提交比对任务:同步校验后落库,后台执行,前端轮询任务列表看进度 */
    public void submit(Context ctx) {
        CreateCompareJobRequest req = Validators.validate(ctx.bodyAsClass(CreateCompareJobRequest.class));
        ctx.json(Map.of("jobId", service.submit(req)));
    }

    /** 列级对比·字段映射预生成:大模型逐目标产出「基准字段 → 目标列」建议,人工审核后随任务提交 */
    public void suggestMapping(Context ctx) {
        MappingSuggestRequest req = Validators.validate(ctx.bodyAsClass(MappingSuggestRequest.class));
        ctx.json(service.suggestMappings(req));
    }

    /** 任务列表(新的在前);query archived=true 时含已归档;
     * 筛选参数(可组合):kw 关键字 / status、tagIds 逗号分隔多值 / datasourceId / matchMode(LEGACY=仅编码老任务) / compareMode */
    public void list(Context ctx) {
        boolean includeArchived = "true".equalsIgnoreCase(ctx.queryParam("archived"));
        ctx.json(service.list(includeArchived, parseFilter(ctx)));
    }

    /** 解析列表筛选 query 参数;空值/非法值一律忽略(不筛),保证老前端不带参数时行为不变 */
    private static CompareRepository.JobFilter parseFilter(Context ctx) {
        String kw = trimToNull(ctx.queryParam("kw"));
        List<String> status = splitCsv(ctx.queryParam("status"));
        List<Long> tagIds = new java.util.ArrayList<>();
        for (String s : splitCsv(ctx.queryParam("tagIds"))) {
            try {
                tagIds.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                // 非法 id 忽略,不影响其他条件
            }
        }
        Long datasourceId = null;
        String ds = trimToNull(ctx.queryParam("datasourceId"));
        if (ds != null) {
            try {
                datasourceId = Long.parseLong(ds);
            } catch (NumberFormatException ignored) {
                // 非法 id 忽略
            }
        }
        return new CompareRepository.JobFilter(kw, status, tagIds, datasourceId,
            trimToNull(ctx.queryParam("matchMode")), trimToNull(ctx.queryParam("compareMode")));
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static List<String> splitCsv(String s) {
        String t = trimToNull(s);
        if (t == null) return List.of();
        List<String> out = new java.util.ArrayList<>();
        for (String part : t.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** RUNNING 任务瘦出行(后台任务中心 1s 轮询口径;compare 授权校验同前缀) */
    public void listActive(Context ctx) {
        ctx.json(service.listActive());
    }

    /** 任务详情:任务字段 + 目标指标列表 */
    public void detail(Context ctx) {
        ctx.json(service.detail(id(ctx)));
    }

    /** 「字段审核」确认映射并开始比对(仅 PENDING,否则 409;校验失败 400):
     * body {"mappings": {"<targetId>": {"基准字段": "目标列"}}},确认后 PENDING→RUNNING 进执行器 */
    public void confirmMapping(Context ctx) {
        ConfirmMappingRequest req = ctx.bodyAsClass(ConfirmMappingRequest.class);
        Map<Long, Map<String, String>> mappings = new LinkedHashMap<>();
        if (req.mappings != null) {
            for (Map.Entry<String, Map<String, String>> e : req.mappings.entrySet()) {
                try {
                    mappings.put(Long.parseLong(e.getKey()), e.getValue());
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException("映射目标 id 非法: " + e.getKey());
                }
            }
        }
        service.confirmMapping(id(ctx), mappings);
        ctx.json(Map.of("ok", true));
    }

    /** 「待处理」任务直接开始比对(编辑向导「保存并比对」在 update 后调用;仅 PENDING 且非 DS_ERROR,
     * 否则 400/409;映射已在编辑提交时按 submit 口径校验落库) */
    public void start(Context ctx) {
        service.start(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** 向导编辑提交(RUNNING 409):body 同 submit;PENDING 保存后仍为「待处理」(是否立即运行由前端再调
     * confirm-mapping),终态(DONE/FAILED/CANCELED)保存后直接按新配置重新比对(旧差异明细覆盖) */
    public void update(Context ctx) {
        CreateCompareJobRequest req = Validators.validate(ctx.bodyAsClass(CreateCompareJobRequest.class));
        service.update(id(ctx), req);
        ctx.json(Map.of("ok", true));
    }

    /** confirm-mapping 请求体:mappings 键为目标 id(JSON 对象键只能是字符串,转 Long) */
    public static class ConfirmMappingRequest {
        public Map<String, Map<String, String>> mappings;
    }

    /** 差异明细分页:query targetId/diffType/kw/page(size 缺省 20) 组合过滤 */
    public void diffs(Context ctx) {
        // targetId 可缺省:必须用 getOrNull()。Javalin 的 getOrDefault(T) 是 Kotlin 方法、形参非空,
        // Java 侧传 null 会先被 Intrinsics 非空检查拦下(即使请求带了 targetId 也一律 500)
        Long targetId = ctx.queryParamAsClass("targetId", Long.class).getOrNull();
        Integer page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
        Integer size = ctx.queryParamAsClass("size", Integer.class).getOrDefault(20);
        ctx.json(service.diffs(id(ctx), targetId, ctx.queryParam("diffType"), ctx.queryParam("kw"), page, size));
    }

    /** 质量报告:目标指标 + 问题字段排行 + 汇总 */
    public void report(Context ctx) {
        ctx.json(service.report(id(ctx)));
    }

    /** 重跑(仅 DONE/FAILED/CANCELED):清空既有结果重新执行 */
    public void rerun(Context ctx) {
        service.rerun(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** 归档/取消归档:query archived=true|false */
    public void archive(Context ctx) {
        boolean archived = !"false".equalsIgnoreCase(ctx.queryParam("archived"));
        service.archive(id(ctx), archived);
        ctx.json(Map.of("ok", true));
    }

    /** 删除任务(tx 级联删三表);RUNNING 中 409 */
    public void delete(Context ctx) {
        service.delete(id(ctx));
        ctx.json(Map.of("ok", true));
    }

    /** 比对报告导出 xlsx:首 sheet「总览」一行一系统,其后每个差异行一个 sheet 展开字段级明细;
     * 文件名跟随任务名(导入任务可直接对上来源文件,空名回退「比对总览-{id}.xlsx」) */
    public void export(Context ctx) throws Exception {
        long id = id(ctx);
        String filename = URLEncoder.encode(service.exportFileName(id), StandardCharsets.UTF_8);
        HttpServletResponse response = ctx.res();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        service.exportDiff(id, response.getOutputStream());
    }

    private static long id(Context ctx) {
        return ctx.pathParamAsClass("id", Long.class).get();
    }
}
