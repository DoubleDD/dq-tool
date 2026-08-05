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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 扫描结果导出 xlsx(SXSSF 流式写,大结果集不占内存) */
@Service
public class ExportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 带 key 的列定义,供导出预览按 key 选择要导出的列 */
    private interface Keyed {
        String key();

        String header();
    }

    /** 字段明细 sheet 的可选列定义;"表名/表注释/字段"为固定前列,不参与选择 */
    private record Col(String key, String header, Function<ScanColumnView, Object> value) implements Keyed {}

    /** 表列表 sheet 的可选列定义;"表名"列恒为第一列,不参与选择;value 第二参为该表字段平均有值率 */
    private record TCol(String key, String header, BiFunction<ScanTableView, Double, Object> value) implements Keyed {}

    private static final List<Col> COLUMN_DEFS = List.of(
            new Col("comment", "注释", c -> nullSafe(c.columnComment())),
            new Col("type", "类型", c -> nullSafe(c.columnType())),
            new Col("key", "键", c -> nullSafe(c.keyLabel())),
            new Col("nullable", "可空", c -> c.nullable() == null ? "" : (c.nullable() ? "是" : "否")),
            new Col("default", "默认值", c -> nullSafe(c.defaultValue())),
            new Col("totalRows", "总行数", ScanColumnView::totalRows),
            new Col("nullCount", "NULL数", ScanColumnView::nullCount),
            new Col("emptyCount", "空串数", ScanColumnView::emptyCount),
            new Col("ruleHitCount", "规则命中数", ScanColumnView::ruleHitCount),
            new Col("valueCount", "有值数", ScanColumnView::valueCount),
            new Col("fillRate", "有值率%", c -> round2(c.fillRate())));

    private static final List<TCol> TABLE_DEFS = List.of(
            new TCol("comment", "注释", (t, avg) -> nullSafe(t.comment())),
            new TCol("storage", "引擎/表空间", (t, avg) -> nullSafe(t.storageInfo())),
            new TCol("totalRows", "总行数", (t, avg) -> t.totalRows() == null ? "" : t.totalRows()),
            new TCol("sampled", "是否采样", (t, avg) -> t.sampled() ? "是(估算)" : "否"),
            new TCol("sampleRows", "采样行数", (t, avg) -> t.sampleRows() == null ? "" : t.sampleRows()),
            new TCol("fillRate", "整体有值率%", (t, avg) -> round2(avg)),
            new TCol("status", "状态", (t, avg) -> t.status().name()));

    private final ScanService scanService;

    public ExportService(ScanService scanService) {
        this.scanService = scanService;
    }

    public void export(long jobId, OutputStream out) throws IOException {
        export(jobId, null, null, out);
    }

    /**
     * 导出扫描结果。
     *
     * @param tableCols 表列表 sheet 要导出的列 key(见 TABLE_DEFS);null = 全部列,空集 = 只要固定首列
     * @param cols      字段明细 sheet 要导出的列 key(见 COLUMN_DEFS);null = 全部列,空集 = 只要固定列(表名/表注释/字段)
     */
    public void export(long jobId, List<String> tableCols, List<String> cols, OutputStream out) throws IOException {
        ScanJobView job = scanService.getJob(jobId);
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            writeOverview(wb, job);
            writeTables(wb, job, tableCols);
            writeColumns(wb, job, cols);
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
        writeSummary(sheet, r, job);
    }

    /** 统计总结:表/字段规模、空表空字段、总行数、占用空间(空表/空字段口径与前端一致:0 行 / 有值数为 0) */
    private void writeSummary(Sheet sheet, int r, ScanJobView job) {
        long done = 0;
        long failed = 0;
        int emptyTables = 0;
        int fieldTotal = 0;
        int emptyFields = 0;
        long totalRows = 0;
        long sizeBytes = 0;
        boolean anySampled = false;
        for (ScanTableView t : job.tables()) {
            if (t.status() == ScanStatus.DONE) {
                done++;
            } else if (t.status() == ScanStatus.FAILED) {
                failed++;
            }
            List<ScanColumnView> cols = scanService.getColumns(job.id(), t.tableName());
            fieldTotal += cols.size();
            emptyFields += (int) cols.stream().filter(c -> c.valueCount() == 0).count();
            if (t.totalRows() != null) {
                totalRows += t.totalRows();
                if (t.totalRows() == 0) {
                    emptyTables++;
                }
            } else {
                totalRows += t.scannedRows();
            }
            if (t.sizeBytes() != null) {
                sizeBytes += t.sizeBytes();
            }
            anySampled |= t.sampled();
        }
        sheet.createRow(r++).createCell(0).setCellValue("统计总结");
        r = kv(sheet, r, "统计表数", String.format("%,d(完成 %,d,失败 %,d)", (long) job.tables().size(), done, failed));
        r = kv(sheet, r, "空表数(0 行)", String.format("%,d", emptyTables));
        r = kv(sheet, r, "空表率", percent(emptyTables, job.tables().size()));
        r = kv(sheet, r, "字段总数", String.format("%,d", fieldTotal));
        r = kv(sheet, r, "空字段数(有值数为 0)", String.format("%,d", emptyFields));
        r = kv(sheet, r, "空字段率", percent(emptyFields, fieldTotal));
        r = kv(sheet, r, "总数据行数", String.format("%,d", totalRows) + (anySampled ? "(含采样估算)" : ""));
        kv(sheet, r, "总占用空间", formatBytes(sizeBytes));
    }

    /** 百分比(保留两位小数),分母为 0 时返回 "-" */
    private static String percent(long part, long total) {
        return total > 0 ? String.format("%.2f%%", part * 100.0 / total) : "-";
    }

    private void writeTables(SXSSFWorkbook wb, ScanJobView job, List<String> tableCols) {
        List<TCol> selected = selectCols(TABLE_DEFS, tableCols);
        Sheet sheet = wb.createSheet("表列表");
        writeHeader(sheet.createRow(0), selected, "表名");
        int r = 1;
        for (ScanTableView t : job.tables()) {
            List<ScanColumnView> cols = scanService.getColumns(job.id(), t.tableName());
            double avgRate = cols.isEmpty() ? 0 : cols.stream().mapToDouble(ScanColumnView::fillRate).average().orElse(0);
            Row row = sheet.createRow(r++);
            int c = 0;
            row.createCell(c++).setCellValue(t.tableName());
            for (TCol def : selected) {
                cell(row.createCell(c++), def.value().apply(t, avgRate));
            }
        }
    }

    private void writeColumns(SXSSFWorkbook wb, ScanJobView job, List<String> cols) {
        List<Col> selected = selectCols(COLUMN_DEFS, cols);
        Set<String> usedNames = new HashSet<>();
        for (ScanTableView t : job.tables()) {
            if (t.status() != ScanStatus.DONE) {
                continue;
            }
            Sheet sheet = wb.createSheet(sheetName(t.tableName(), usedNames));
            writeHeader(sheet.createRow(0), selected, "表名", "表注释", "字段");
            int r = 1;
            for (ScanColumnView col : scanService.getColumns(job.id(), t.tableName())) {
                Row row = sheet.createRow(r++);
                int c = 0;
                row.createCell(c++).setCellValue(t.tableName());
                row.createCell(c++).setCellValue(nullSafe(t.comment()));
                row.createCell(c++).setCellValue(col.columnName());
                for (Col def : selected) {
                    cell(row.createCell(c++), def.value().apply(col));
                }
            }
        }
    }

    /** 表头:固定前列 + 选中的可选列 */
    private static void writeHeader(Row head, List<? extends Keyed> selected, String... fixedCols) {
        int c = 0;
        for (String fixed : fixedCols) {
            head.createCell(c++).setCellValue(fixed);
        }
        for (Keyed def : selected) {
            head.createCell(c++).setCellValue(def.header());
        }
    }

    /** 按请求的 key 过滤列定义,保持定义顺序;null = 全部列,空集/全部未知 = 只留固定首列 */
    private static <K extends Keyed> List<K> selectCols(List<K> defs, List<String> cols) {
        if (cols == null) {
            return defs;
        }
        Set<String> keys = new HashSet<>(cols);
        return defs.stream().filter(d -> keys.contains(d.key())).toList();
    }

    /** 数字写数值单元格,其余写字符串;null 写空串 */
    private static void cell(org.apache.poi.ss.usermodel.Cell cell, Object value) {
        if (value == null) {
            cell.setCellValue("");
        } else if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    /** sheet 名取自表名:替换非法字符、截断到 31 字符,重名时追加 _2/_3 后缀 */
    private static String sheetName(String tableName, Set<String> usedNames) {
        String base = tableName.replaceAll("[\\\\/?*\\[\\]:]", "_");
        String name = truncate(base, 31);
        int n = 2;
        while (!usedNames.add(name)) {
            name = truncate(base, 31 - ("_" + n).length()) + "_" + n;
            n++;
        }
        return name;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
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

    /** 字节数转可读单位,如 1.3 GB */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double v = bytes;
        int u = -1;
        do {
            v /= 1024;
            u++;
        } while (v >= 1024 && u < units.length - 1);
        return String.format("%.1f %s", v, units[u]);
    }
}
