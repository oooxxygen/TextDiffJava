package com.textdiff.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FieldMapStoreTest {

    private static FieldMaps.ReportType type(String id, String file) {
        return new FieldMaps.ReportType(id, file, "对公", "bocs_dep", "t.csv", 1700000000L);
    }

    private static FieldMaps.ReportField field(String id, int idx, String name) {
        return new FieldMaps.ReportField(id, idx, name, "CHAR", "8");
    }

    @Test
    void importUpsertAndQuery(@TempDir Path dir) {
        try (FieldMapStore store = new FieldMapStore(dir, true)) {
            assertTrue(store.dbAvailable());
            store.importAll(
                    List.of(type("R1", "bocso.txt"), type("R2", "bocsoxc.txt")),
                    List.of(field("R1", 0, "客户号"), field("R1", 1, "余额"), field("R2", 0, "序号")),
                    List.of("t.csv", "f.csv"));

            assertEquals(2, store.allTypes().size());
            assertEquals("对公", store.typeByReport("R1").parmReportType());
            assertEquals("bocs_dep", store.typeByReport("R1").ownershipGroup());
            assertEquals(List.of("客户号", "余额"), store.fieldNamesFor("R1"));
            assertEquals(List.of("序号"), store.fieldNamesFor("R2"));
            assertEquals(List.of(), store.fieldNamesFor("NOPE"));
            assertEquals(3, store.fieldCount());
        }
    }

    @Test
    void reimportReplacesFieldsPerReport(@TempDir Path dir) {
        try (FieldMapStore store = new FieldMapStore(dir, true)) {
            store.importAll(List.of(type("R1", "f.txt")),
                    List.of(field("R1", 0, "旧列一"), field("R1", 1, "旧列二")), List.of("f.csv"));
            // 再次导入同 report_id：覆盖而非追加
            store.importAll(List.of(type("R1", "f.txt")),
                    List.of(field("R1", 0, "新列")), List.of("f2.csv"));
            assertEquals(List.of("新列"), store.fieldNamesFor("R1"));
            assertEquals(1, store.fieldCount());
        }
    }

    @Test
    void reportIdForFileExactThenStem(@TempDir Path dir) {
        try (FieldMapStore store = new FieldMapStore(dir, false)) {
            store.importAll(List.of(type("R1", "bocso.txt")), List.of(), List.of("t.csv"));
            assertEquals("R1", store.reportIdForFile("bocso.txt"));
            assertEquals("R1", store.reportIdForFile("/any/path/bocso.txt"));
            // 茎匹配（忽略扩展名）
            assertEquals("R1", store.reportIdForFile("bocso.dat"));
            assertNull(store.reportIdForFile("other.txt"));
        }
    }

    @Test
    void reportIdForFileWildcardPattern(@TempDir Path dir) {
        // report_file_name 常为通配模式：01A***0*.v01（*** 与 * 均为任意串）
        try (FieldMapStore store = new FieldMapStore(dir, false)) {
            store.importAll(List.of(type("R1", "01A***0*.v01")), List.of(), List.of("t.csv"));
            assertEquals("R1", store.reportIdForFile("01A3020D.v01"));
            assertEquals("R1", store.reportIdForFile("/x/01A39990.v01"));
            assertNull(store.reportIdForFile("01B3020D.v01"));
            assertNull(store.reportIdForFile("bocso.txt"));
        }
    }

    @Test
    void h2MirrorReceivesImportedRows(@TempDir Path dir) throws Exception {
        try (FieldMapStore store = new FieldMapStore(dir, true)) {
            store.importAll(
                    List.of(type("R1", "bocso.txt")),
                    List.of(field("R1", 0, "客户号"), field("R1", 1, "余额")),
                    List.of("t.csv", "f.csv"));
        }
        // 直接开 H2 验证镜像内容
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:file:" + dir.resolve("h2").resolve("textdiff").toAbsolutePath());
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT report_file_name, parm_report_type, ownership_group FROM report_type_parm "
                            + "WHERE report_id='R1'")) {
                assertTrue(rs.next());
                assertEquals("bocso.txt", rs.getString(1));
                assertEquals("对公", rs.getString(2));
                assertEquals("bocs_dep", rs.getString(3));
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT field_name, field_format, field_length FROM report_conf_field "
                            + "WHERE report_id='R1' AND col_index=1")) {
                assertTrue(rs.next());
                assertEquals("余额", rs.getString(1));
                assertEquals("CHAR", rs.getString(2));
                assertEquals("8", rs.getString(3));
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM report_conf_field WHERE report_id='R1'")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void reloadFromJsonlWhenH2Deleted(@TempDir Path dir) throws Exception {
        try (FieldMapStore store = new FieldMapStore(dir, true)) {
            store.importAll(List.of(type("R1", "bocso.txt")),
                    List.of(field("R1", 0, "客户号"), field("R1", 1, "余额")), List.of("f.csv"));
        }
        assertTrue(Files.exists(dir.resolve("field_types.jsonl")));
        assertTrue(Files.exists(dir.resolve("field_fields.jsonl")));
        // 删除 H2 目录模拟镜像损坏：文件层仍是事实来源
        deleteRecursively(dir.resolve("h2"));
        try (FieldMapStore reopened = new FieldMapStore(dir, true)) {
            assertTrue(reopened.dbAvailable()); // 镜像自愈重建
            assertEquals(1, reopened.allTypes().size());
            assertEquals(List.of("客户号", "余额"), reopened.fieldNamesFor("R1"));
        }
    }

    @Test
    void disabledH2IsFileOnly(@TempDir Path dir) {
        try (FieldMapStore store = new FieldMapStore(dir, false)) {
            assertFalse(store.dbAvailable());
            assertFalse(Files.exists(dir.resolve("h2")));
            store.importAll(List.of(type("R1", "f.txt")), List.of(field("R1", 0, "列")), List.of("f.csv"));
            assertEquals(List.of("列"), store.fieldNamesFor("R1"));
        }
    }

    private static void deleteRecursively(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.delete(f);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
