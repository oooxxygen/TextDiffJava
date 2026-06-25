package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompareConfigTest {

    @Test
    void defaults() {
        CompareConfig c = new CompareConfig();
        assertEquals(" | ", c.delimiter);
        assertEquals("|||||", c.trailerPrefix);
        assertEquals("auto", c.encodingA);
        assertEquals("auto", c.encodingB);
        assertEquals("A", c.sourceA);
        assertEquals("B", c.sourceB);
        assertEquals("*", c.fileGlob);
        assertTrue(c.keyColumns.isEmpty());
        assertTrue(c.skipSet().isEmpty());
    }

    @Test
    void skipSetMergesOmitAndIgnoreDeduped() {
        CompareConfig c = new CompareConfig();
        c.omitColumns = new ArrayList<>(List.of(0, 1, 2));
        c.ignoreColumns = new ArrayList<>(List.of(2, 3));
        assertEquals(Set.of(0, 1, 2, 3), c.skipSet());
    }

    @Test
    void rowDiffFactories() {
        RowDiff e = RowDiff.equal("k", Status.SECTION_DATA, new String[]{"a"});
        assertEquals(Status.EQUAL, e.status);
        assertEquals(0, e.diffCols.length);

        RowDiff d = RowDiff.diff("k", Status.SECTION_DATA,
                new String[]{"a"}, new String[]{"b"}, new int[]{0});
        assertEquals(Status.DIFF, d.status);
        assertArrayEquals(new int[]{0}, d.diffCols);

        assertEquals(Status.UNMATCHED_A, RowDiff.onlyA("k", Status.SECTION_DATA, new String[]{"a"}).status);
        assertEquals(Status.UNMATCHED_B, RowDiff.onlyB("k", Status.SECTION_DATA, new String[]{"b"}).status);
    }
}
