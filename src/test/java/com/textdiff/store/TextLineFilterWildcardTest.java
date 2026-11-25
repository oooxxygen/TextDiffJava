package com.textdiff.store;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 普通文本比对文件名筛选：正则∨通配双解释 + 逻辑名∨原始名双名（v0.9.18 反馈回归）。 */
class TextLineFilterWildcardTest {

    private static TextLineSavedConfig cfg(String regex) {
        return new TextLineSavedConfig("n", "", regex, "", "auto", "auto", "", "", "", 0L);
    }

    @Test
    void userCase_globHitsRawName() { // 01A*0D.sx1.gz.* 应命中 01A1020D.sx1.gz.202609271
        TextLineSavedConfig c = cfg("01A*0D.sx1.gz.*");
        assertTrue(c.matches("01A1020D.sx1.gz", "01A1020D.sx1.gz.202609271"));
    }

    @Test
    void globHitsLogicalNameToo() { // 不带尾缀的通配也应命中逻辑名（剥日期后缀后仍含 0D.sx1.gz.？——本例逻辑名以 .gz 结尾）
        TextLineSavedConfig c = cfg("01A*0D.sx1*");
        assertTrue(c.matches("01A1020D.sx1.gz"));
    }

    @Test
    void regexStillWorks() { // 正则用户不受影响
        assertTrue(cfg("^01A\\d{3}0D\\.sx1\\.gz$").matches("01A1020D.sx1.gz"));
        assertFalse(cfg("^01A\\d{3}0D\\.sx1\\.gz$").matches("01A999D.sx1.gz"));
    }

    @Test
    void legacySimpleGlob() { // 原 *ATMP* 行为保持
        assertTrue(cfg("*ATMP*").matches("TRXATMP0D.sx1.gz"));
    }

    @Test
    void nonMatchExcluded() {
        assertFalse(cfg("01A*0D.sx1.gz.*").matches("01B1020D.sx1.gz", "01B1020D.sx1.gz.202609271"));
    }

    @Test
    void blankMatchesAll() {
        assertTrue(cfg("").matches("anything", "anything.202609271"));
    }

    @Test
    void invalidPatternRejectedByValidator() {
        assertThrows(IllegalArgumentException.class, () -> TextLineSavedConfig.compileFileFilter("[invalid"));
    }

    @Test
    void invalidRegexValidGlobStillUsableInMatching() { // 正则非法但通配合法：校验通过（兜底），匹配可用
        assertTrue(cfg("[01A*").matches("[01A1020D.sx1.gz", "[01A1020D.sx1.gz.202609271"));
        assertNotNull(TextLineSavedConfig.compileFileFilter("[01A*"));
    }

    @Test
    void bothInterpretationsInvalidRejected() { // 正则与通配均无效才拒绝
        assertThrows(IllegalArgumentException.class, () -> TextLineSavedConfig.compileFileFilter("[invalid("));
    }
}
