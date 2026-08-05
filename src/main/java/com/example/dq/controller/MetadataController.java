package com.example.dq.controller;

import com.example.dq.model.SchemaStat;
import com.example.dq.model.TableDocUpdateRequest;
import com.example.dq.model.TableDocView;
import com.example.dq.model.TableStat;
import com.example.dq.repository.ScanRepository;
import com.example.dq.service.MetadataService;
import com.example.dq.service.TableDocService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasources/{dsId}")
public class MetadataController {

    private final MetadataService service;
    private final TableDocService tableDocService;

    public MetadataController(MetadataService service, TableDocService tableDocService) {
        this.service = service;
        this.tableDocService = tableDocService;
    }

    @GetMapping("/databases")
    public List<String> listDatabases(@PathVariable long dsId) throws SQLException {
        return service.listDatabases(dsId);
    }

    @GetMapping("/schemas")
    public List<String> listSchemas(@PathVariable long dsId,
                                    @RequestParam(required = false) String db) throws SQLException {
        return service.listSchemas(dsId, db);
    }

    /** 库列表页概览:schema + 表数量 + 最近一次扫描 */
    @GetMapping("/schema-stats")
    public List<SchemaStat> listSchemaStats(@PathVariable long dsId,
                                            @RequestParam(required = false) String db) throws SQLException {
        return service.listSchemaStats(dsId, db);
    }

    @GetMapping("/schemas/{schema}/tables")
    public List<TableStat> listTables(@PathVariable long dsId, @PathVariable String schema,
                                      @RequestParam(required = false) String db) throws SQLException {
        return service.listTables(dsId, db, schema);
    }

    /** 每张表最近一次 DONE 扫描的信息(表名 -> {jobId, finishedAt}),表列表页点击表名直达最新结果、展示最近扫描时间 */
    @GetMapping("/schemas/{schema}/latest-scan-jobs")
    public Map<String, ScanRepository.LatestScan> latestScanJobs(@PathVariable long dsId, @PathVariable String schema,
                                                                 @RequestParam(required = false) String db) {
        return service.latestScanJobsByTable(dsId, db, schema);
    }

    /** 运行中任务里每张未完成表的分段进度(表名 -> {jobId, status, doneChunks, totalChunks}),表列表页轮询展示"扫描中"进度 */
    @GetMapping("/schemas/{schema}/running-scans")
    public Map<String, ScanRepository.RunningScan> runningScans(@PathVariable long dsId, @PathVariable String schema,
                                                                @RequestParam(required = false) String db) {
        return service.runningScansByTable(dsId, db, schema);
    }

    /** AI 表说明(表名 -> 说明文字),本地查询不连业务库 */
    @GetMapping("/schemas/{schema}/table-docs")
    public Map<String, String> tableDocs(@PathVariable long dsId, @PathVariable String schema,
                                         @RequestParam(required = false) String db) {
        return tableDocService.list(dsId, db, schema);
    }

    /** 触发大模型生成单表说明并落库 */
    @PostMapping("/schemas/{schema}/tables/{table}/doc")
    public TableDocView generateTableDoc(@PathVariable long dsId, @PathVariable String schema, @PathVariable String table,
                                         @RequestParam(required = false) String db) throws SQLException {
        return tableDocService.generate(dsId, db, schema, table);
    }

    /** 手动编辑单表说明 */
    @PutMapping("/schemas/{schema}/tables/{table}/doc")
    public TableDocView updateTableDoc(@PathVariable long dsId, @PathVariable String schema, @PathVariable String table,
                                       @RequestParam(required = false) String db,
                                       @RequestBody TableDocUpdateRequest req) {
        return tableDocService.update(dsId, db, schema, table, req.description());
    }
}
