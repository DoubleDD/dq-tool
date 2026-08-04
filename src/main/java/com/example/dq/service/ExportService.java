package com.example.dq.service;

import com.example.dq.model.NullRule;
import com.example.dq.model.ScanColumnView;
import com.example.dq.model.ScanJobView;
import com.example.dq.model.ScanStatus;
import com.example.dq.model.ScanTableView;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/** 扫描结果导出 xlsx(SXSSF 流式写,大结果集不占内存) */
@Service
public class ExportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScanService scanService;

    public ExportService(ScanService scanService) {
        this.scanService = scanService;
    }

    public void export(long jobId, OutputStream out) throws IOException {
        ScanJobView job = scanService.getJob(jobId);
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            writeOverview(wb, job);
            writeColumns(wb, job);
            writeFailed(wb, job);
            wb.write(out);
            wb.dispose();
        }
    }

    private void writeOverview(SXSSFWorkbook wb, ScanJobView job) {
        Sheet sheet = wb.createSheet("概览");
        int r = 0;
        r = kv(sheet, r, "数据源", nullSafe(job.datasourceName()));
        r = kv(sheet, r, "库/Schema", job.schemaName());
        r = kv(sheet, r, "状态", job.status().name());
        r = kv(sheet, r, "强制全量", job.forceFull() ? "是" : "否");
        r = kv(sheet, r, "空值规则", rulesText(job.nullRules()));
        r = kv(sheet, r, "开始时间", job.startedAt() != null ? FMT.format(job.startedAt()) : "");
        r = kv(sheet, r, "结束时间", job.finishedAt() != null ? FMT.format(job.finishedAt()) : "");
        r++;
        header(sheet.createRow(r++), "表名", "注释", "引擎/表空间", "总行数", "是否采样", "采样行数", "整体有值率%", "状态");
        for (ScanTableView t : job.tables()) {
            List<ScanColumnView> cols = scanService.getColumns(job.id(), t.tableName());
            double avgRate = cols.isEmpty() ? 0 : cols.stream().mapToDouble(ScanColumnView::fillRate).average().orElse(0);
            Row row = sheet.createRow(r++);
            int c = 0;
            row.createCell(c++).setCellValue(t.tableName());
            row.createCell(c++).setCellValue(nullSafe(t.comment()));
            row.createCell(c++).setCellValue(nullSafe(t.storageInfo()));
            if (t.totalRows() != null) row.createCell(c++).setCellValue(t.totalRows()); else { row.createCell(c++).setCellValue(""); }
            row.createCell(c++).setCellValue(t.sampled() ? "是(估算)" : "否");
            if (t.sampleRows() != null) row.createCell(c++).setCellValue(t.sampleRows()); else { row.createCell(c++).setCellValue(""); }
            row.createCell(c++).setCellValue(round2(avgRate));
            row.createCell(c++).setCellValue(t.status().name());
        }
    }

    private void writeColumns(SXSSFWorkbook wb, ScanJobView job) {
        Sheet sheet = wb.createSheet("字段明细");
        header(sheet.createRow(0), "表名", "字段", "注释", "类型", "键", "可空", "默认值",
                "总行数", "NULL数", "空串数", "规则命中数", "有值数", "有值率%");
        int r = 1;
        for (ScanTableView t : job.tables()) {
            if (t.status() != ScanStatus.DONE) {
                continue;
            }
            for (ScanColumnView col : scanService.getColumns(job.id(), t.tableName())) {
                Row row = sheet.createRow(r++);
                int c = 0;
                row.createCell(c++).setCellValue(t.tableName());
                row.createCell(c++).setCellValue(col.columnName());
                row.createCell(c++).setCellValue(nullSafe(col.columnComment()));
                row.createCell(c++).setCellValue(nullSafe(col.columnType()));
                row.createCell(c++).setCellValue(nullSafe(col.keyLabel()));
                row.createCell(c++).setCellValue(col.nullable() == null ? "" : (col.nullable() ? "是" : "否"));
                row.createCell(c++).setCellValue(nullSafe(col.defaultValue()));
                row.createCell(c++).setCellValue(col.totalRows());
                row.createCell(c++).setCellValue(col.nullCount());
                row.createCell(c++).setCellValue(col.emptyCount());
                row.createCell(c++).setCellValue(col.ruleHitCount());
                row.createCell(c++).setCellValue(col.valueCount());
                row.createCell(c++).setCellValue(round2(col.fillRate()));
            }
        }
    }

    private void writeFailed(SXSSFWorkbook wb, ScanJobView job) {
        List<ScanTableView> failed = job.tables().stream()
                .filter(t -> t.status() == ScanStatus.FAILED).toList();
        if (failed.isEmpty()) {
            return;
        }
        Sheet sheet = wb.createSheet("异常表");
        header(sheet.createRow(0), "表名", "错误信息");
        int r = 1;
        for (ScanTableView t : failed) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(t.tableName());
            row.createCell(1).setCellValue(nullSafe(t.error()));
        }
    }

    private int kv(Sheet sheet, int r, String k, String v) {
        Row row = sheet.createRow(r);
        row.createCell(0).setCellValue(k);
        row.createCell(1).setCellValue(v);
        return r + 1;
    }

    private void header(Row row, String... names) {
        for (int i = 0; i < names.length; i++) {
            row.createCell(i).setCellValue(names[i]);
        }
    }

    private String rulesText(List<NullRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return "默认(NULL + 空字符串)";
        }
        return "默认(NULL + 空字符串) + " + rules.stream()
                .map(r -> r.column() + " IN (" + String.join(",", r.values()) + ")")
                .collect(Collectors.joining("; "));
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
