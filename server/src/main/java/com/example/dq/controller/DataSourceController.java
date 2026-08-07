package com.example.dq.controller;

import com.example.dq.service.DataSourceService;
import com.example.dq.service.DataSourceTransferService;
import com.example.dq.model.DataSourceConfig;
import com.example.dq.model.DataSourceRequest;
import com.example.dq.model.SchemaFilterRequest;
import com.example.dq.model.TestConnectionRequest;
import com.example.dq.web.Validators;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 数据源管理(Javalin handler,路由在 WebServer 注册) */
public class DataSourceController {

    private static final Logger log = LoggerFactory.getLogger(DataSourceController.class);

    private final DataSourceService service;
    private final DataSourceTransferService transferService;

    public DataSourceController(DataSourceService service, DataSourceTransferService transferService) {
        this.service = service;
        this.transferService = transferService;
    }

    public void list(Context ctx) {
        ctx.json(service.list());
    }

    public void create(Context ctx) {
        DataSourceRequest req = Validators.validate(ctx.bodyAsClass(DataSourceRequest.class));
        ctx.json(Map.of("id", service.create(req)));
    }

    public void update(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        service.update(id, Validators.validate(ctx.bodyAsClass(DataSourceRequest.class)));
    }

    public void delete(Context ctx) {
        service.delete(ctx.pathParamAsClass("id", Long.class).get());
    }

    /** 库列表页「库过滤」弹窗:单独更新库过滤白名单(schemas 为 null/空表示不过滤) */
    public void updateSchemaFilter(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        SchemaFilterRequest req = ctx.bodyAsClass(SchemaFilterRequest.class);
        service.updateSchemaFilter(id, req == null ? null : req.getSchemas());
    }

    public void test(Context ctx) {
        TestConnectionRequest req = Validators.validate(ctx.bodyAsClass(TestConnectionRequest.class));
        try {
            String dbMode = service.testConnection(req);
            Map<String, Object> ok = new HashMap<>();
            ok.put("success", true);
            if (dbMode != null) {
                ok.put("dbMode", dbMode);
            }
            ctx.json(ok);
        } catch (SQLException e) {
            log.warn("测试连接失败 {}: {}", req.getJdbcUrl(), e.getMessage(), e);
            ctx.json(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 「库过滤」页签(新增态,或编辑态改了连接信息):用表单连接参数拉取目标库的库名列表;id 非空时留空的密码/SSH 秘密回落到已存配置 */
    public void previewDatabases(Context ctx) {
        TestConnectionRequest req = Validators.validate(ctx.bodyAsClass(TestConnectionRequest.class));
        TestConnectionRequest merged = mergeStoredCredentials(req);
        try {
            Map<String, Object> ok = new HashMap<>();
            ok.put("success", true);
            ok.put("databases", service.previewDatabases(merged));
            ctx.json(ok);
        } catch (SQLException e) {
            log.warn("拉取库列表失败 {}: {}", merged.getJdbcUrl(), e.getMessage(), e);
            ctx.json(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 编辑态拉库列表:表单里密码/SSH 秘密字段留空表示沿用已存值,与 update 的「留空不改」规则一致 */
    private TestConnectionRequest mergeStoredCredentials(TestConnectionRequest req) {
        if (req.getId() == null) {
            return req;
        }
        final DataSourceConfig stored;
        try {
            stored = service.get(req.getId()); // get 会解密秘密字段
        } catch (IllegalArgumentException e) {
            return req;
        }
        Boolean sshEnabled = req.getSshEnabled() != null ? req.getSshEnabled() : stored.getSshEnabled();
        boolean sshOn = Boolean.TRUE.equals(sshEnabled);
        return new TestConnectionRequest(
                req.getJdbcUrl(),
                isBlank(req.getUsername()) ? stored.getUsername() : req.getUsername(),
                isBlank(req.getPassword()) ? stored.getPassword() : req.getPassword(),
                sshEnabled,
                sshOn && isBlank(req.getSshHost()) ? stored.getSshHost() : req.getSshHost(),
                sshOn && req.getSshPort() == null ? stored.getSshPort() : req.getSshPort(),
                sshOn && isBlank(req.getSshUsername()) ? stored.getSshUsername() : req.getSshUsername(),
                sshOn && isBlank(req.getSshAuthMethod()) ? stored.getSshAuthMethod() : req.getSshAuthMethod(),
                sshOn && isBlank(req.getSshPassword()) ? stored.getSshPassword() : req.getSshPassword(),
                sshOn && isBlank(req.getSshPrivateKey()) ? stored.getSshPrivateKey() : req.getSshPrivateKey(),
                sshOn && isBlank(req.getSshPassphrase()) ? stored.getSshPassphrase() : req.getSshPassphrase(),
                req.getId());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 导出数据源配置为 JSON 文件(密码用导出文件固定密钥加密,跨实例可导入) */
    public void export(Context ctx) throws IOException {
        String idsParam = ctx.queryParam("ids");
        if (idsParam == null || idsParam.isBlank()) {
            throw new IllegalArgumentException("请指定要导出的数据源 ids");
        }
        final List<Long> ids;
        try {
            ids = Arrays.stream(idsParam.split(",")).map(String::trim).map(Long::parseLong).toList();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ids 参数格式错误,应为逗号分隔的数字列表");
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("请指定要导出的数据源 ids");
        }
        String filename = URLEncoder.encode("dq-datasources-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json",
                StandardCharsets.UTF_8);
        HttpServletResponse response = ctx.res();
        response.setContentType("application/json");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        transferService.export(ids, response.getOutputStream());
    }

    /** 导入数据源配置:文件(自有 .json / Navicat .ncx)或粘贴文本(自有 JSON / DataGrip 剪贴板 XML) */
    public void importDs(Context ctx) throws IOException {
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            // DataGrip 只支持复制数据源到剪贴板,允许前端直接粘贴文本导入,按内容自动识别格式
            String text = ctx.formParam("text");
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("请选择要导入的文件或粘贴数据源内容");
            }
            String trimmed = text.trim();
            if (trimmed.startsWith("{")) {
                ctx.json(transferService.importJson(
                        new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
            } else if (trimmed.contains("<data-source")) {
                ctx.json(transferService.importDataGrip(text));
            } else {
                throw new IllegalArgumentException("无法识别的粘贴内容:支持本工具导出的 JSON 或 DataGrip 复制的数据源");
            }
            return;
        }
        String filename = file.filename() == null ? "" : file.filename();
        String lower = filename.toLowerCase(Locale.ROOT);
        try (var in = file.content()) {
            if (lower.endsWith(".json")) {
                ctx.json(transferService.importJson(in));
            } else if (lower.endsWith(".ncx")) {
                ctx.json(transferService.importNcx(in));
            } else {
                throw new IllegalArgumentException("仅支持 .json 或 .ncx 文件");
            }
        }
    }
}
