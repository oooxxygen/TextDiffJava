package com.textdiff.engine;

import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/** AutoKeyIndex 大阈值（不落盘）路径满足契约。 */
class AutoKeyIndexInMemoryContractTest extends KeyIndexContract {
    @TempDir Path dir;

    @Override
    protected KeyIndex newIndex() {
        return new AutoKeyIndex(dir, Long.MAX_VALUE);
    }
}
