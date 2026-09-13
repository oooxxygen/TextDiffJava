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

    /** 全量 CSV 文件名：{nickname}_全量.csv。 */
    public static Path writeFullCsv(Path dir, JobRecord job, Predicate<RowDiff> filter) {
        Path file = dir.resolve(base(job) + "_全量.csv");
        CsvExporter.writeFull(file, loadRows(job, filter), columnNames(job));
        return file;
    }

    /** 差异 CSV 文件名：{nickname}_差异.csv（一条字段差异一行）。 */
    public static Path writeDiffCsv(Path dir, JobRecord job, Predicate<RowDiff> filter) {
        Path file = dir.resolve(base(job) + "_差异.csv");
        CsvExporter.writeDiffFields(file, loadRows(job, filter), columnNames(job));
        return file;
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
