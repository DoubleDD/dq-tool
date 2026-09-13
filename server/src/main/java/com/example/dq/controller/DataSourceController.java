package com.example.dq.controller;

import com.example.dq.service.DataSourceService;
import com.example.dq.service.DataSourceTransferService;
import com.example.dq.service.MetadataTransferService;
import com.example.dq.model.DataSourceConfig;
import com.example.dq.model.DataSourceRequest;
import com.example.dq.model.DbType;
import com.example.dq.model.GroupUpdateRequest;
import com.example.dq.model.SchemaFilterRequest;
import com.example.dq.model.TestConnectionRequest;
import com.example.dq.model.TestConnectionResult;
import com.example.dq.web.Validators;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

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
    private final MetadataTransferService metadataTransferService;

    public DataSourceController(DataSourceService service, DataSourceTransferService transferService,
                                MetadataTransferService metadataTransferService) {
        this.service = service;
        this.transferService = transferService;
        this.metadataTransferService = metadataTransferService;
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

    /** 数据源管理页卡片拖拽:单独更新分组(groupName 为 null/空白表示移到未分组) */
    public void updateGroup(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        GroupUpdateRequest req = ctx.bodyAsClass(GroupUpdateRequest.class);
        service.updateGroup(id, req == null ? null : req.getGroupName());
    }

    public void test(Context ctx) {
        TestConnectionRequest req = Validators.validate(ctx.bodyAsClass(TestConnectionRequest.class));
        // 编辑对话框点测试:密码/SSH 秘密字段留空表示沿用已存值(id 非空时回落),与「留空不改」规则一致
        TestConnectionRequest merged = mergeStoredCredentials(req);
        try {
            TestConnectionResult r = service.testConnection(merged);
            Map<String, Object> ok = new HashMap<>();
            ok.put("success", true);
            // 前端按 DataGrip 风格在按钮旁弹出详情(DBMS/驱动版本、Ping、SSL、兼容模式)
            ok.put("detail", r);
            ctx.json(ok);
        } catch (SQLException e) {
            log.warn("测试连接失败 {}: {}", merged.getJdbcUrl(), e.getMessage(), e);
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

    /** 库过滤页签/弹窗:按数据库类型返回系统库/schema 名(默认不勾选),纯静态方言信息 */
    public void systemSchemas(Context ctx) {
        DbType type = DbType.valueOf(ctx.pathParam("dbType").toUpperCase(Locale.ROOT));
        ctx.json(service.systemSchemas(type));
    }

    /** 编辑态测试连接/拉库列表:表单里密码/SSH 秘密字段留空表示沿用已存值,与 update 的「留空不改」规则一致 */
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

    /** 导出数据源元数据缓存为 JSON 文件(结构缓存 + 标注数据,供连不上库的人导入离线使用) */
    public void exportMetadata(Context ctx) throws IOException {
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
        String filename = URLEncoder.encode("dq-metadata-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json",
                StandardCharsets.UTF_8);
        HttpServletResponse response = ctx.res();
        response.setContentType("application/json");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        metadataTransferService.export(ids, response.getOutputStream());
    }

    /** 元数据导入预检(multipart 文件上传或粘贴文本):返回文件内各数据源规模与本机数据源清单 + 连接身份自动匹配,供前端做数据源映射 */
    public void previewMetadataImport(Context ctx) throws IOException {
        UploadedFile file = ctx.uploadedFile("file");
        if (file != null) {
            try (var in = file.content()) {
                ctx.json(metadataTransferService.preview(in));
            }
            return;
        }
        String text = ctx.formParam("text");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("请选择要导入的文件或粘贴元数据内容");
        }
        ctx.json(metadataTransferService.preview(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }

    /** 导入数据源配置:文件(自有 .json / Navicat .ncx)或粘贴文本(自有 JSON / DataGrip 剪贴板 XML);
     * 自有 JSON 再按顶层 app 区分:dq-tool=连接配置、dq-tool-metadata=元数据缓存(可选表单字段 mapping=数据源映射 JSON) */
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
                importJsonDispatch(ctx, text.getBytes(StandardCharsets.UTF_8));
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
                importJsonDispatch(ctx, in.readAllBytes());
            } else if (lower.endsWith(".ncx")) {
                ctx.json(transferService.importNcx(in));
            } else {
                throw new IllegalArgumentException("仅支持 .json 或 .ncx 文件");
            }
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 自有 JSON 按顶层 app 分发到连接配置导入或元数据导入;元数据导入支持可选表单字段 mapping(文件数据源名 → 本机数据源 id,缺失/0=自动匹配或新建) */
    private void importJsonDispatch(Context ctx, byte[] bytes) throws IOException {
        String app = null;
        try {
            tools.jackson.databind.JsonNode node = JSON.readTree(bytes);
            app = node == null || node.get("app") == null ? null : node.get("app").asText();
        } catch (Exception ignored) {
            // 不是合法 JSON,交给 importJson 按非法文件统一报错
        }
        if ("dq-tool-metadata".equals(app)) {
            ctx.json(metadataTransferService.importJson(new ByteArrayInputStream(bytes), parseMapping(ctx.formParam("mapping"))));
        } else {
            ctx.json(transferService.importJson(new ByteArrayInputStream(bytes)));
        }
    }

    /** 解析数据源映射表单字段;为空视为未指定(自动匹配),非法 JSON 走 400 */
    private static Map<String, Long> parseMapping(String mappingJson) {
        if (mappingJson == null || mappingJson.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(mappingJson, new tools.jackson.core.type.TypeReference<Map<String, Long>>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("数据源映射格式不正确", e);
        }
    }
}
