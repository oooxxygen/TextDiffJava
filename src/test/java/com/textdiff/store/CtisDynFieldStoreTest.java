package com.textdiff.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CTIS 动态栏位配置存储：全量替换 + 查询。 */
class CtisDynFieldStoreTest {

    private static CtisDynFields.Apply apply(String text, String col, String dyn) {
        return new CtisDynFields.Apply(text, col, dyn, null, 0);
    }

    private static CtisDynFields.Item item(String dyn, String code, String name) {
        return new CtisDynFields.Item(dyn, code, name, "否", "X(8)", "", "d", "", null, 0);
    }

    @Test
    void 导入全量替换_重启重载(@TempDir Path dir) {
        CtisDynFieldStore store = new CtisDynFieldStore(dir.resolve("store"), false);
        store.importAll(List.of(apply("BPAF", "107", "D1"), apply("Bpaf", "108", "D1")),
                List.of(item("D1", "1B", "交易真实日期"), item("D1", "1C", "交易真实时间")),
                "a.xlsx");
        assertEquals(2, store.appliesFor("bpaf").size()); // 忽略大小写精确匹配
        assertTrue(store.appliesFor("XX").isEmpty());
        assertEquals(2, store.itemsFor("D1").size());
        assertEquals(0, store.itemsFor("无").size());
        assertEquals(2, store.itemCount());
        assertEquals(List.of("D1"), store.dynNames());
        assertEquals("a.xlsx", store.lastSourceFile());
        assertTrue(store.lastImportedAt() > 0);
        store.close();

        // 重启重载（JSONL 事实来源）
        try (CtisDynFieldStore reopen = new CtisDynFieldStore(dir.resolve("store"), false)) {
            assertEquals(2, reopen.appliesFor("BPAF").size());
            assertEquals("交易真实日期", reopen.itemsFor("D1").get("1B").itemName());
        }
    }

    @Test
    void 再次导入整体替换旧数据(@TempDir Path dir) {
        try (CtisDynFieldStore store = new CtisDynFieldStore(dir.resolve("store"), false)) {
            store.importAll(List.of(apply("BPAF", "107", "D1")),
                    List.of(item("D1", "1B", "交易真实日期")), "a.xlsx");
            store.importAll(List.of(apply("CCAF", "108", "D2")),
                    List.of(item("D2", "2a", "TYPE1")), "b.xlsx");
            assertTrue(store.appliesFor("BPAF").isEmpty());
            assertEquals(1, store.appliesFor("CCAF").size());
            assertEquals(1, store.itemCount());
            assertEquals("b.xlsx", store.lastSourceFile());
        }
    }
}
