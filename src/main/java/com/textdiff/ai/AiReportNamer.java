package com.textdiff.ai;

import com.textdiff.engine.CompareConfig;
import com.textdiff.engine.Rules;
import com.textdiff.store.FieldMapStore;
import com.textdiff.store.FieldMaps;
import com.textdiff.store.JobRecord;

import java.nio.file.Path;

/**
 * AI 分析产物命名：[归属组]文件昵称_实际文件名（一级标题同名）。
 * 归属组取源系统字段配置 bat_report_type_parm 的 ownership_group；无映射时退回对比配置的组名。
 * 落盘文件名对 Windows 非法字符（* 等）做替换，标题保留原文。
 */
public final class AiReportNamer {
    private AiReportNamer() {}

    /** 一级标题（同样用作导出文件名主体，仅文件名侧额外净化）。 */
    public static String title(JobRecord job, FieldMapStore maps) {
        String group = ownershipGroup(job, maps);
        String name = job.fileA == null ? "" : Path.of(job.fileA).getFileName().toString();
        String nick = job.nickname == null ? "" : job.nickname.strip();
        String base;
        if (nick.isBlank()) {
            base = name;
        } else if (!name.isBlank() && nick.toLowerCase().endsWith(name.toLowerCase())) {
            base = nick; // 昵称已含实际文件名（如「配置 · 文件名」），避免重复拼接
        } else {
            base = nick + "_" + name;
        }
        return group.isBlank() ? base : "[" + group + "]" + base;
    }

    /** 导出文件名：标题净化为合法文件名（保留中文与常用符号，替换 \\/:*?"<>|）。 */
    public static String fileName(JobRecord job, FieldMapStore maps) {
        return fileNameOf(title(job, maps));
    }

    public static String fileNameOf(String title) {
        return title.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String ownershipGroup(JobRecord job, FieldMapStore maps) {
        if (maps != null && job.fileA != null && !job.fileA.isBlank()) {
            String reportId = maps.reportIdForFile(job.fileA);
            FieldMaps.ReportType t = reportId == null ? null : maps.typeByReport(reportId);
            if (t != null && t.ownershipGroup() != null && !t.ownershipGroup().isBlank()) {
                return t.ownershipGroup().strip();
            }
        }
        try { // 兜底：对比配置行携带的组名（GRP 段）
            CompareConfig cfg = Rules.parseLegacy(job.configLine, " | ", "|||||");
            return cfg.group == null ? "" : cfg.group.strip();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
