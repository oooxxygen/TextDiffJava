package com.textdiff.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 拆分规则配置存储：upsert/删除/JSONL 重载往返（昵称唯一，原子重写）。 */
class SplitConfigStoreTest {

    @TempDir Path dir;

    private static SplitConfig cfg(String nick, String logic) {
        return new SplitConfig(nick, " | ", "auto", "|||||",
                List.of(new SplitConfig.Rule("养老", logic,
                        List.of(new SplitConfig.Cond(2, "eq", "养老"),
                                new SplitConfig.Cond(3, "range", "1~100")))),
                "t.xlsx", 0);
    }

    @Test
    void 保存更新与删除持久化往返() {
        Path storeDir = dir.resolve("store");
        try (SplitConfigStore s = new SplitConfigStore(storeDir)) {
            s.save(cfg("INCT0101-按险种", "all"));
            s.save(cfg("INCT0202-按渠道", "any"));
            assertEquals(2, s.all().size());
            // 同昵称 upsert
            s.save(cfg("INCT0101-按险种", "any"));
            assertEquals(2, s.all().size());
            assertEquals("any", s.get("INCT0101-按险种").rules().get(0).logic());
            assertTrue(s.delete("INCT0202-按渠道"));
            assertFalse(s.delete("INCT0202-按渠道"));
        }
        // 重载：落库内容还原（含紧凑构造器的分隔符/编码规范化）
        try (SplitConfigStore s = new SplitConfigStore(storeDir)) {
            assertEquals(1, s.all().size());
            SplitConfig c = s.get("INCT0101-按险种");
            assertEquals("any", c.rules().get(0).logic());
            assertEquals(2, c.rules().get(0).conditions().size());
            assertEquals(3, c.rules().get(0).conditions().get(1).col());
            assertEquals(" | ", c.delimiter());
            assertEquals("auto", c.encoding());
        }
        // 空白昵称不接受（compact 构造 strip 为空串 → get 恒 null 语义）
        try (SplitConfigStore s = new SplitConfigStore(dir.resolve("store2"))) {
            SplitConfig blank = s.save(new SplitConfig("  ", " | ", "auto", "|||||",
                    List.of(), "", 0));
            assertEquals("", blank.nickname());
        }
    }
}
