package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ParserTest {
    private static final String DELIM = " | ";
    private static final String TP = "|||||";

    @Test
    void splitColumnsKeepsTrailingEmpty() {
        assertArrayEquals(new String[]{"a", "b", ""}, Parser.splitColumns("a | b | ", DELIM));
        assertArrayEquals(new String[]{""}, Parser.splitColumns("", DELIM));
    }

    @Test
    void parseDataSeparatesRowsTrailerAndEmpty() {
        Parser.TrailerData t = new Parser.TrailerData();
        List<String[]> data = Parser.parseData(Stream.of(
                "a | b | c",
                "",
                "x | y | z",
                "|||||RecNum=2",
                "|||||SysID=ABC"
        ), DELIM, TP, t);

        assertEquals(2, data.size());
        assertArrayEquals(new String[]{"a", "b", "c"}, data.get(0));
        assertArrayEquals(new String[]{"x", "y", "z"}, data.get(1));
        assertEquals(2, t.dataCount);
        assertEquals(1, t.emptyCount);
        assertEquals("2", t.meta.get("RecNum"));
        assertEquals("ABC", t.meta.get("SysID"));
        assertEquals(2, t.records.size());
    }

    @Test
    void trailerValueWithDelimiterNotSplitAndUnquoted() {
        Parser.TrailerData t = new Parser.TrailerData();
        Parser.parseData(Stream.of("|||||Sep=\" | \""), DELIM, TP, t);
        assertEquals(" | ", t.meta.get("Sep"));               // 去引号
        assertEquals("\" | \"", t.records.get(0).value());    // records 留原值
        assertEquals("Sep", t.records.get(0).key());
    }

    @Test
    void recnumCheckThreeStates() {
        Parser.TrailerData ok = new Parser.TrailerData();
        Parser.parseData(Stream.of("a | b", "c | d", "|||||RecNum=2"), DELIM, TP, ok);
        assertEquals(Boolean.TRUE, Parser.recnumCheck(ok));

        Parser.TrailerData bad = new Parser.TrailerData();
        Parser.parseData(Stream.of("a | b", "|||||RecNum=9"), DELIM, TP, bad);
        assertEquals(Boolean.FALSE, Parser.recnumCheck(bad));

        Parser.TrailerData none = new Parser.TrailerData();
        Parser.parseData(Stream.of("a | b"), DELIM, TP, none);
        assertNull(Parser.recnumCheck(none));
    }

    @Test
    void trailerBareKeyNoEquals() {
        Parser.TrailerData t = new Parser.TrailerData();
        Parser.parseData(Stream.of("|||||EOF"), DELIM, TP, t);
        assertEquals("", t.meta.get("EOF"));
        assertEquals("EOF", t.records.get(0).key());
    }
}
