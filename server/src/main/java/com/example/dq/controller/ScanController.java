package com.example.dq.controller;

import com.example.dq.model.ScanColumnView;
import com.example.dq.model.ScanJobView;
import com.example.dq.model.ScanRequest;
import com.example.dq.service.ExportService;
import com.example.dq.service.ScanService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scans")
public class ScanController {

    private final ScanService scanService;
    private final ExportService exportService;

    public ScanController(ScanService scanService, ExportService exportService) {
        this.scanService = scanService;
        this.exportService = exportService;
    }

    @PostMapping
    public Map<String, Long> create(@Valid @RequestBody ScanRequest req) throws Exception {
        return Map.of("jobId", scanService.createScan(req));
    }

    @GetMapping
    public List<ScanJobView> list(@RequestParam(required = false) Long datasourceId,
                                  @RequestParam(required = false) String dbName,
                                  @RequestParam(required = false) String schemaName) {
        return scanService.listJobs(datasourceId, dbName, schemaName);
    }

    @GetMapping("/{jobId}")
    public ScanJobView get(@PathVariable long jobId) {
        return scanService.getJob(jobId);
    }

    @PostMapping("/{jobId}/cancel")
    public void cancel(@PathVariable long jobId) {
        scanService.cancel(jobId);
    }

    @PostMapping("/{jobId}/resume")
    public void resume(@PathVariable long jobId) {
        scanService.resume(jobId);
    }

    @DeleteMapping("/{jobId}")
    public void delete(@PathVariable long jobId) {
        scanService.delete(jobId);
    }

    @GetMapping("/{jobId}/tables/{tableName}/columns")
    public List<ScanColumnView> columns(@PathVariable long jobId, @PathVariable String tableName) {
        return scanService.getColumns(jobId, tableName);
    }

    @GetMapping("/{jobId}/export")
    public void export(@PathVariable long jobId,
                       @RequestParam(required = false) String tableCols,
                       @RequestParam(required = false) String cols,
                       HttpServletResponse response) throws IOException {
        String filename = URLEncoder.encode("dq-scan-" + jobId + ".xlsx", StandardCharsets.UTF_8);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        // tableCols/cols:逗号分隔的列 key(表列表/字段明细 sheet),缺省导出全部列;空值表示只留固定首列
        exportService.export(jobId, splitKeys(tableCols), splitKeys(cols), response.getOutputStream());
    }

    private static List<String> splitKeys(String param) {
        return param == null ? null : Arrays.asList(param.split(","));
    }
}
