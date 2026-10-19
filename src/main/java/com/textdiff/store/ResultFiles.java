package com.textdiff.store;

import com.textdiff.engine.ResultSink;
import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.engine.Summary;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 结果目录（results/{jobId}/）读写：
 * result.jsonl（RowDiff 流式追加，事实来源，重启可重载）、summary.json、meta.json。
 * 分页 = 流式切片过滤，内存恒定。
 */
public final class ResultFiles {
    public static final String RESULT_JSONL = "result.jsonl";
    public static final String SUMMARY_JSON = "summary.json";
    public static final String META_JSON = "meta.json";

    private ResultFiles() {}

    /** RowDiff → JSONL 追加写（实现 {@link ResultSink}，Comparator 逐行调用）。需 close。 */
    public static final class JsonlSink implements ResultSink, AutoCloseable {
        private final BufferedWriter w;
        public long zoneEqual, zoneDiff, zoneUnmatched, zoneTrailer; // meta zone_counts 直接取用

        private JsonlSink(BufferedWriter w) {
            this.w = w;
        }

        public static JsonlSink create(Path resultFile) {
            try {
                Files.createDirectories(resultFile.getParent());
                return new JsonlSink(Files.newBufferedWriter(resultFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            } catch (IOException e) {
                throw new UncheckedIOException("结果文件打开失败: " + resultFile, e);
            }
        }

        @Override
        public void addRow(RowDiff row) {
            if (Status.SECTION_TRAILER.equals(row.section)) {
                zoneTrailer++;
            } else if (Status.EQUAL.equals(row.status)) {
                zoneEqual++;
            } else if (Status.DIFF.equals(row.status)) {
                zoneDiff++;
            } else {
                zoneUnmatched++;
            }
            try {
                w.write(Json.write(toDto(row)));
                w.newLine();
            } catch (IOException e) {
                throw new UncheckedIOException("结果行写入失败", e);
            }
        }

        @Override
        public void close() {
            try {
                w.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** zone 过滤语义：null/"all" = 全部；"unmatched" = unmatched_a + unmatched_b；其余按 Status 原值。 */
    static boolean zoneMatches(String zone, RowDiff row) {
        if (zone == null || zone.isBlank() || "all".equals(zone)) return true;
        if ("unmatched".equals(zone)) {
            return com.textdiff.engine.Status.UNMATCHED_A.equals(row.status)
                    || com.textdiff.engine.Status.UNMATCHED_B.equals(row.status);
        }
        return zone.equals(row.status);
    }

    /** 无附加过滤的共享谓词：readPage 据此走免解析快路径。 */
    public static final java.util.function.Predicate<RowDiff> NO_FILTER = r -> true;

    /** 流式分页切片：total 为过滤后的总行数（供前端分页控件）。 */
    public static Page readPage(Path resultFile, String zone, long offset, long limit) {
        return readPage(resultFile, zone, offset, limit, NO_FILTER);
    }

    /**
     * 读路径按需裁剪：
     * ① zone=all 且无附加过滤 → 窗口外行只计数不解析（大 JSONL 分页从全文件解析降为 O(offset+limit)）；
     * ② 带 zone 过滤 → 先做 status 针串预过滤再解析，命不中的行零解析成本。
     */
    public static Page readPage(Path resultFile, String zone, long offset, long limit,
                                java.util.function.Predicate<RowDiff> extra) {
        List<RowDiff> rows = new ArrayList<>();
        long total = 0;
        boolean noFilter = extra == NO_FILTER;
        boolean allZone = zone == null || zone.isBlank() || "all".equals(zone);
        List<String> needles = statusNeedles(zone);
        try (Stream<String> lines = Files.lines(resultFile, StandardCharsets.UTF_8)) {
            var it = lines.iterator();
            if (allZone && noFilter) {
                while (it.hasNext()) {
                    String line = it.next();
                    if (total >= offset && rows.size() < limit) {
                        try {
                            rows.add(fromDto(Json.read(line, RowDto.class)));
                        } catch (RuntimeException e) {
                            continue; // 半行损坏容错
                        }
                    }
                    total++;
                }
                return new Page(zone, offset, limit, total, rows);
            }
            while (it.hasNext()) {
                String line = it.next();
                if (needles != null && !containsAny(line, needles)) continue;
                RowDiff row;
                try {
                    row = fromDto(Json.read(line, RowDto.class));
                } catch (RuntimeException e) {
                    continue; // 半行损坏容错
                }
                if (!noFilter && !extra.test(row)) continue;
                if (total >= offset && rows.size() < limit) rows.add(row);
                total++;
            }
        } catch (java.nio.file.NoSuchFileException e) {
            return new Page(zone, offset, limit, 0, List.of());
        } catch (IOException e) {
            throw new UncheckedIOException("结果读取失败: " + resultFile, e);
        }
        return new Page(zone, offset, limit, total, rows);
    }

    private static boolean containsAny(String line, List<String> needles) {
        for (String n : needles) {
            if (line.contains(n)) return true;
        }
        return false;
    }

    /**
     * zone → JSONL 行内 status 字段针串（Json.write 紧凑输出；字符串值内引号必转义，针串不会误命中）。
     * all/空 返回 null（不过滤）。
     */
    private static List<String> statusNeedles(String zone) {
        if (zone == null || zone.isBlank() || "all".equals(zone)) return null;
        return switch (zone) {
            case "unmatched" -> List.of("\"status\":\"unmatched_a\"", "\"status\":\"unmatched_b\"");
            case "equal" -> List.of("\"status\":\"equal\"");
            case "diff" -> List.of("\"status\":\"diff\"");
            default -> List.of("\"status\":\"" + zone + "\"");
        };
    }

    public record Page(String zone, long offset, long limit, long total, List<RowDiff> rows) {}

    /** 全量/差异导出与 AI 特征提取共用：流式遍历，调用方负责关闭。 */
    public static Stream<RowDiff> stream(Path resultFile) {
        try {
            return Files.lines(resultFile, StandardCharsets.UTF_8)
                    .mapMulti((line, down) -> {
                        try {
                            down.accept(fromDto(Json.read(line, RowDto.class)));
                        } catch (RuntimeException ignored) {
                        }
                    });
        } catch (java.nio.file.NoSuchFileException e) {
            return Stream.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("结果读取失败: " + resultFile, e);
        }
    }

    public static void writeSummary(Path dir, Summary summary) {
        writeJson(dir.resolve(SUMMARY_JSON), summary);
    }

    public static Summary readSummary(Path dir) {
        return readJson(dir.resolve(SUMMARY_JSON), Summary.class);
    }

    public static void writeMeta(Path dir, JobMeta meta) {
        writeJson(dir.resolve(META_JSON), meta);
    }

    public static JobMeta readMeta(Path dir) {
        return readJson(dir.resolve(META_JSON), JobMeta.class);
    }

    /** Summary 快照 → meta 中的统计字段（新作业完成时调用）。 */
    public static JobMeta buildMeta(com.textdiff.store.JobRecord job, String encA, String encB, Summary s) {
        JobMeta m = new JobMeta();
        m.jobId = job.id;
        m.batchId = job.batchId;
        m.nickname = job.nickname;
        m.configLine = job.configLine;
        m.fileA = job.fileA;
        m.fileB = job.fileB;
        m.encodingA = encA;
        m.encodingB = encB;
        m.status = job.status;
        m.error = job.error;
        m.keyWarning = job.keyWarning;
        m.aiStatus = job.aiStatus;
        m.createdAt = job.createdAt;
        m.startedAt = job.startedAt;
        m.finishedAt = job.finishedAt;
        if (s != null) {
            m.totalA = s.totalA;
            m.totalB = s.totalB;
            m.equal = s.equal;
            m.diff = s.diff;
            m.onlyA = s.onlyA;
            m.onlyB = s.onlyB;
            m.keyDupA = s.keyDupA;
            m.keyDupB = s.keyDupB;
            m.dupKeySamples = s.dupKeySamples;
            if (s.dupTopA != null) {
                m.dupTopA = s.dupTopA.stream().map(k -> new JobMeta.DupKey(k.key(), k.count())).toList();
            }
            if (s.dupTopB != null) {
                m.dupTopB = s.dupTopB.stream().map(k -> new JobMeta.DupKey(k.key(), k.count())).toList();
            }
            m.diffColFreq = s.diffColFreq;
            m.recnumCheckA = s.recnumCheckA;
            m.recnumCheckB = s.recnumCheckB;
            if (s.trailerFields != null) {
                m.trailerFields = new java.util.LinkedHashMap<>();
                for (var e : s.trailerFields.entrySet()) {
                    m.trailerFields.put(e.getKey(),
                            new JobMeta.SummaryTrailerField(e.getValue().a(), e.getValue().b(), e.getValue().equal()));
                }
            }
        }
        return m;
    }

    /**
     * RowDiff 的可反序列化 DTO（RowDiff 为 final 字段无默认构造器，Jackson 原生支持 record）。
     * JSONL 行格式即 DTO 格式。
     */
    record RowDto(String key, String status, String section, String[] aCols, String[] bCols, int[] diffCols,
                  String aRaw, String bRaw) {
        static RowDto of(RowDiff r) {
            return new RowDto(r.key, r.status, r.section, r.aCols, r.bCols, r.diffCols, r.aRaw, r.bRaw);
        }

        RowDiff toRowDiff() {
            return new RowDiff(key, status, section, aCols, bCols, diffCols, aRaw, bRaw);
        }
    }

    static RowDto toDto(RowDiff row) {
        return RowDto.of(row);
    }

    static RowDiff fromDto(RowDto dto) {
        return dto.toRowDiff();
    }

    /** 元数据 JSON 落盘：临时文件 + 原子替换（读侧轮询永不见空/半截文件）。 */
    private static void writeJson(Path file, Object o) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(o), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入失败: " + file, e);
        }
    }

    private static <T> T readJson(Path file, Class<T> type) {
        try {
            return Json.read(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败: " + file, e);
        }
    }
}
