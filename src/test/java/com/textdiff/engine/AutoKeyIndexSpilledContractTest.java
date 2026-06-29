package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** AutoKeyIndex 阈值=1（首次 put 即落盘）路径满足同一契约。 */
class AutoKeyIndexSpilledContractTest extends KeyIndexContract {
    @TempDir Path dir;

    @Override
    protected KeyIndex newIndex() {
        return new AutoKeyIndex(dir, 1);
    }

    @Test
    void actuallySpills() {
        try (KeyIndex idx = newIndex()) {
            idx.put("k", new String[]{"v"});
            assertTrue(idx.spilled());
        }
    }
}
