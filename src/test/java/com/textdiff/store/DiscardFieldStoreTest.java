package com.textdiff.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 关注字段监测存储：全量替换 / 清空 / 昵称匹配（忽略大小写）/ 重启重载 / H2 镜像可用性。 */
class DiscardFieldStoreTest {

    @TempDir Path dir;

    @Test
    void importAllReplacesAndMatchesCaseInsensitive() {
        try (DiscardFieldStore s = new DiscardFieldStore(dir, false)) {
            s.importAll(List.of(
                    new DiscardField("INCT0101", 27, "MERCH_COUNTRY_CODE", "国家码", null, 0),
                    new DiscardField("INCT0101", 44, "BREAK_RULE_IND_BEF_IMG", "银行信息", null, 0)), "a.xlsx");
            assertEquals(2, s.all().size());
            assertEquals(List.of("INCT0101"), s.nicknames());
            assertEquals("a.xlsx", s.lastSourceFile());
            assertTrue(s.lastImportedAt() > 0);

            // 忽略大小写命中 + 栏位序号升序
            List<DiscardField> hit = s.forNickname("inct0101");
            assertEquals(2, hit.size());
            assertEquals(27, hit.get(0).colSeq());
            assertEquals(44, hit.get(1).colSeq());
            assertTrue(s.forNickname("OTHER").isEmpty());

            // 全量替换：第二次导入整体覆盖
            s.importAll(List.of(new DiscardField("CRDD0020", 3, "X", "Y", null, 0)), "b.xlsx");
            assertEquals(1, s.all().size());
            assertEquals("b.xlsx", s.lastSourceFile());
            assertTrue(s.forNickname("INCT0101").isEmpty());
        }
    }

    @Test
    void clearAndPersistenceReload() {
        try (DiscardFieldStore s = new DiscardFieldStore(dir, false)) {
            s.importAll(List.of(new DiscardField("N1", 1, "F", "M", null, 0)), "a.xlsx");
            s.clear();
            assertEquals(0, s.all().size());
            assertTrue(s.isEmpty());
            assertEquals("", s.lastSourceFile());
        }
        try (DiscardFieldStore s2 = new DiscardFieldStore(dir, false)) {
            // 重载后仍为空
            assertTrue(s2.isEmpty());
            s2.importAll(List.of(new DiscardField("N1", 5, "F", "M", null, 0)), "a.xlsx");
        }
        try (DiscardFieldStore s3 = new DiscardFieldStore(dir, false)) {
            // JSONL 真相源重启重载
            assertEquals(1, s3.all().size());
            assertEquals(5, s3.all().get(0).colSeq());
        }
    }

    @Test
    void h2MirrorAvailable() {
        try (DiscardFieldStore s = new DiscardFieldStore(dir, true)) {
            assertTrue(s.dbAvailable());
            s.importAll(List.of(new DiscardField("N1", 1, "F", "M", null, 0)), "a.xlsx");
            assertTrue(s.dbAvailable(), "正常导入不应触发降级");
        }
    }
}
