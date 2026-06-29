package com.textdiff.engine;

class InMemoryKeyIndexTest extends KeyIndexContract {
    @Override
    protected KeyIndex newIndex() {
        return new InMemoryKeyIndex();
    }
}
