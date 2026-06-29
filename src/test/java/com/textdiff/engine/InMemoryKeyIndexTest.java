package com.textdiff.engine;

class InMemoryKeyIndexTest extends KeyIndexContract {
    @Override
    KeyIndex newIndex() {
        return new InMemoryKeyIndex();
    }
}
