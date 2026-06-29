package com.textdiff.engine.offheap;

import com.textdiff.engine.KeyIndex;
import com.textdiff.engine.KeyIndexContract;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** 用同一 KeyIndexContract 验证 OffHeapKeyIndex 与 InMemory 行为一致。 */
class OffHeapKeyIndexContractTest extends KeyIndexContract {
    @TempDir
    Path dir;

    @Override
    protected KeyIndex newIndex() {
        return new OffHeapKeyIndex(dir);
    }
}
