package com.textdiff.export;

import com.textdiff.engine.CompareConfig;
import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Rules;
import com.textdiff.engine.Status;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 导出装配：从 result.jsonl 装载行 → CSV/ZIP 输出（端点与任务完成自动导出共用）。 */
public final class ExportAssembler {
    private ExportAssembler() {}

    public static List<String> columnNames(JobRecord job) {
        try {
            CompareConfig cfg = Rules.parseLegacy(job.configLine, " | ", "|||||");
            return cfg.columnNames;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public static List<RowDiff> loadRows(JobRecord job, Predicate<RowDiff> filter) {
        try (var stream = ResultFiles.stream(Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
            List<RowDiff> rows = new ArrayList<>();
            stream.filter(filter).forEach(rows::add);
            return rows;
        }
    }

    public static final Predicate<RowDiff> ALL = r -> true;

    public static final Predicate<RowDiff> DATA = r -> Status.SECTION_DATA.equals(r.section);

    /** CSV 分片阈值：每 2 万条数据切一个文件（防止 Excel 打开大文件卡死）。 */
    public static final int SPLIT_ROWS = 20000;

    /**
     * 全量 CSV 导出（分片）：≤2 万条单文件 {base}_全量.csv；超出按序切 {base}_全量_001.csv、_002…。
     * 各分片表头列宽按整批统一。
     */
    public static List<Path> writeFullCsv(Path dir, JobRecord job, Predicate<RowDiff> filter) {
        List<RowDiff> rows = loadRows(job, filter);
        List<String> names = columnNames(job);
        int width = CsvExporter.fullWidth(rows);
        return writeParts(dir, base(job), "_全量.csv", rows,
                (file, part) -> CsvExporter.writeFull(file, part, names, width));
    }

    /** 差异 CSV 导出（分片）：一条字段差异一行，≤2 万行单文件，超出切 {base}_差异_001.csv…。 */
    public static List<Path> writeDiffCsv(Path dir, JobRecord job, Predicate<RowDiff> filter) {
        List<RowDiff> rows = loadRows(job, filter);
        List<String> names = columnNames(job);
        return writeParts(dir, base(job), "_差异.csv", rows,
                (file, part) -> CsvExporter.writeDiffFields(file, part, names));
    }

    private interface PartWriter {
        void write(Path file, List<RowDiff> part) throws java.io.IOException;
    }

    /** 分片写入：≤阈值单文件原后缀名；超过按 3 位序号切（_全量_001.csv）。 */
    private static List<Path> writeParts(Path dir, String base, String suffix,
                                         List<RowDiff> rows, PartWriter writer) {
        try {
            List<Path> out = new ArrayList<>();
            if (rows.size() <= SPLIT_ROWS) {
                Path f = dir.resolve(base + suffix);
                writer.write(f, rows);
                out.add(f);
                return out;
            }
            String stem = suffix.substring(0, suffix.length() - ".csv".length());
            int parts = (rows.size() + SPLIT_ROWS - 1) / SPLIT_ROWS;
            for (int i = 0; i < parts; i++) {
                Path f = dir.resolve(base + stem + String.format("_%03d.csv", i + 1));
                writer.write(f, rows.subList(i * SPLIT_ROWS, Math.min(rows.size(), (i + 1) * SPLIT_ROWS)));
                out.add(f);
            }
            return out;
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("CSV 分片导出失败: " + base + suffix, e);
        }
    }

    private static String base(JobRecord job) {
        String name = job.label != null && !job.label.isEmpty() ? job.label : job.nickname;
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** 多个 CSV 打包 ZIP（条目名 UTF-8）。 */
    public static void zip(Path zipFile, List<Path> files) {
        try {
            Files.createDirectories(zipFile.getParent());
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zipFile),
                    java.nio.charset.Charset.forName("UTF-8"))) {
                for (Path f : files) {
                    z.putNextEntry(new ZipEntry(f.getFileName().toString()));
                    z.write(Files.readAllBytes(f));
                    z.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("ZIP 打包失败: " + zipFile, e);
        }
    }
}
