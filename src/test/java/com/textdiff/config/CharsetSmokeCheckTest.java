package com.textdiff.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CharsetSmokeCheckTest {
    @Test
    void requiredCharsetsPresentOnFullJre() {
        // 完整 JDK 测试环境下应全部可用，不抛异常
        assertDoesNotThrow(CharsetSmokeCheck::verify);
    }

    @Test
    void missingCharsetReports() {
        var missing = CharsetSmokeCheck.missing(java.util.List.of("NO_SUCH_CHARSET_XYZ"));
        assertTrue(missing.contains("NO_SUCH_CHARSET_XYZ"));
    }
}
