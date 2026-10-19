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
import java.nio.file.StandardCopyOption;
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
     * 全量 CSV 导出（分片，<b>流式</b>）：≤2 万条单文件 {base}_全量.csv；超出按序切 {base}_全量_001.csv、_002…。
     * 行直接从 result.jsonl 流入 CSV，不在堆上驻留列表——大作业（数百万行）的全量导出曾是
     * 自动生成任务的主要 OOM 源。各分片表头列宽按整批统一（先流式扫一遍取最大栏位数）。
     */
    public static List<Path> writeFullCsv(Path dir, JobRecord job, Predicate<RowDiff> filter) {
        List<String> names = columnNames(job);
        int width = scanFullWidth(job, filter);
        return streamCsv(dir, base(job), "_全量.csv", job, filter,
                new PartedRowWriter() {
                    @Override public void header(java.io.BufferedWriter w) throws java.io.IOException {
                        CsvExporter.writeFullHeader(w, names, width);
                    }
                    @Override public boolean row(java.io.BufferedWriter w, RowDiff r) throws java.io.IOException {
                        CsvExporter.writeFullRow(w, r, names, width);
                        return true;
                    }
                });
    }

    /** 差异 CSV 导出（分片，流式）：一条字段差异一行，≤2 万行单文件，超出切 {base}_差异_001.csv…。 */
    public static List<Path> writeDiffCsv(Path dir, JobRecord job, Predicate<RowDiff> filter) {
        List<String> names = columnNames(job);
        return streamCsv(dir, base(job), "_差异.csv", job, filter,
                new PartedRowWriter() {
                    @Override public void header(java.io.BufferedWriter w) throws java.io.IOException {
                        CsvExporter.writeDiffHeader(w);
                    }
                    @Override public boolean row(java.io.BufferedWriter w, RowDiff r) throws java.io.IOException {
                        return CsvExporter.writeDiffRow(w, r, names);
                    }
                });
    }

    /** 全量 CSV 列宽 = 各行 A/B 栏位数的最大值（流式扫描，仅取长度不驻留行）。 */
    private static int scanFullWidth(JobRecord job, Predicate<RowDiff> filter) {
        try (var stream = ResultFiles.stream(Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
            int[] width = {0};
            stream.filter(filter).forEach(r -> {
                if (r.aCols != null) width[0] = Math.max(width[0], r.aCols.length);
                if (r.bCols != null) width[0] = Math.max(width[0], r.bCols.length);
            });
            return width[0];
        }
    }

    private interface PartedRowWriter {
        void header(java.io.BufferedWriter w) throws java.io.IOException;

        /** 写一条记录；返回是否产出内容（决定空结果是否仍落仅表头文件）。 */
        boolean row(java.io.BufferedWriter w, RowDiff r) throws java.io.IOException;
    }

    /**
     * 流式分片写：逐条从 result.jsonl 读取直接写当前分片，满 {@link #SPLIT_ROWS} 条关闭并开下一片。
     * 首片先按无序号命名（{base}{suffix}），出现第二片时改名 {base}{stem}_001 保持既有命名契约。
     * 一条记录都不产出时仍落一个仅表头的文件（与旧实现一致）。
     */
    private static List<Path> streamCsv(Path dir, String base, String suffix, JobRecord job,
                                        Predicate<RowDiff> filter, PartedRowWriter writer) {
        try {
            List<Path> out = new ArrayList<>();
            String stem = suffix.substring(0, suffix.length() - ".csv".length());
            java.io.BufferedWriter w = null;
            Path cur = null;
            int inPart = 0, partIdx = 0;
            boolean anyContent = false;
            try (var stream = ResultFiles.stream(Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
                for (RowDiff r : (Iterable<RowDiff>) stream.filter(filter)::iterator) {
                    if (w == null) {
                        if (partIdx == 0) {
                            cur = dir.resolve(base + suffix);
                        } else {
                            if (partIdx == 1) { // 首片升级为序号命名
                                Path renamed = dir.resolve(base + stem + String.format("_%03d.csv", 1));
                                Files.move(cur, renamed, StandardCopyOption.REPLACE_EXISTING);
                                out.set(0, renamed);
                                cur = renamed;
                            }
                            cur = dir.resolve(base + stem + String.format("_%03d.csv", partIdx + 1));
                        }
                        w = CsvExporter.newBomWriter(cur);
                        writer.header(w);
                        out.add(cur);
                    }
                    anyContent |= writer.row(w, r);
                    if (++inPart >= SPLIT_ROWS) {
                        w.close();
                        w = null;
                        inPart = 0;
                        partIdx++;
                    }
                }
            }
            if (w != null) w.close();
            if (out.isEmpty()) { // 空结果：仍落仅表头的单文件
                Path f = dir.resolve(base + suffix);
                try (java.io.BufferedWriter bw = CsvExporter.newBomWriter(f)) {
                    writer.header(bw);
                }
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
