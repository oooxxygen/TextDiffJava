package com.textdiff.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 对比配置分组存储：upsert 替换 / 合并导入 / 删除 / 忽略大小写成员匹配 / 重启重载 / H2 镜像。 */
class CompareGroupStoreTest {

    @TempDir Path dir;

    @Test
    void upsertDeleteAndCaseInsensitiveMembers() {
        try (CompareGroupStore s = new CompareGroupStore(dir, false)) {
            s.upsert("日常对比配置", List.of("INXH", "INVM", "INVM_FACC"), "手工维护");
            assertEquals(1, s.all().size());
            assertEquals(List.of("日常对比配置"), s.names());
            assertEquals(3, s.group("日常对比配置").members().size());
            // 成员匹配忽略大小写
            assertTrue(s.membersOf("日常对比配置").contains("INXH"));
            assertEquals(3, s.membersOf("日常对比配置").size());
            // 分组名忽略大小写查询
            assertNotNull(s.group("日常对比配置".toUpperCase()));
            // 同名 upsert 整体替换
            s.upsert("日常对比配置", List.of("INXH"), "手工维护");
            assertEquals(1, s.group("日常对比配置").members().size());
            // 删除
            assertTrue(s.delete("日常对比配置"));
            assertFalse(s.delete("日常对比配置"));
            assertTrue(s.all().isEmpty());
            // 空名/空成员拒绝
            assertThrows(IllegalArgumentException.class, () -> s.upsert(" ", List.of("A"), "x"));
            assertThrows(IllegalArgumentException.class, () -> s.upsert("G", List.of(" ", ""), "x"));
        }
    }

    @Test
    void importMergeAndReload() {
        try (CompareGroupStore s = new CompareGroupStore(dir, false)) {
            s.upsert("G1", List.of("A", "B"), "手工维护");
            List<String> changed = s.importAll(List.of(
                    new CompareGroup("G2", List.of("C", "D"), null, 0),
                    new CompareGroup("G1", List.of("A", "B", "E"), null, 0)), "groups.xlsx");
            assertEquals(List.of("G2", "G1"), changed); // 名称升序输出
            assertEquals(2, s.all().size());
            assertEquals(List.of("A", "B", "E"), s.group("G1").members()); // 同名替换（合并语义）
            assertEquals(List.of("C", "D"), s.group("G2").members());
        }
        // 重启重载：JSONL 事实来源
        try (CompareGroupStore s2 = new CompareGroupStore(dir, false)) {
            assertEquals(2, s2.all().size());
            assertEquals(List.of("A", "B", "E"), s2.group("G1").members());
            assertEquals("groups.xlsx", s2.group("G2").sourceFile());
        }
    }

    @Test
    void h2MirrorAvailable() {
        try (CompareGroupStore s = new CompareGroupStore(dir, true)) {
            assertTrue(s.dbAvailable());
            s.upsert("G", List.of("A"), "t.xlsx");
            assertTrue(s.dbAvailable()); // 写入后仍可用（镜像成功未降级）
            assertEquals(List.of("A"), s.group("G").members());
        }
    }
}
