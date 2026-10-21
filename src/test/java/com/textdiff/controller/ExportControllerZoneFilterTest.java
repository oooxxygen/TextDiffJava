package com.textdiff.controller;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 导出 zone 过滤与结果页分区一致：trailer 分区收尾部区全量，数据分区不含尾部行。 */
class ExportControllerZoneFilterTest {

    private static RowDiff row(String status, String section) {
        return new RowDiff("k", status, section, new String[]{"a"}, new String[]{"b"},
                new int[]{0}, "a", "b");
    }

    @Test
    void trailerZoneTakesAllTrailerRowsOnly() {
        var f = ExportController.zoneFilter("trailer");
        assertTrue(f.test(row(Status.EQUAL, Status.SECTION_TRAILER)));
        assertTrue(f.test(row(Status.DIFF, Status.SECTION_TRAILER)));
        assertTrue(f.test(row(Status.UNMATCHED_A, Status.SECTION_TRAILER)));
        assertTrue(f.test(row(Status.UNMATCHED_B, Status.SECTION_TRAILER)));
        assertFalse(f.test(row(Status.EQUAL, Status.SECTION_DATA)));
        assertFalse(f.test(row(Status.DIFF, Status.SECTION_DATA)));
    }

    @Test
    void dataZonesExcludeTrailerRows() {
        assertFalse(ExportController.zoneFilter("equal").test(row(Status.EQUAL, Status.SECTION_TRAILER)));
        assertTrue(ExportController.zoneFilter("equal").test(row(Status.EQUAL, Status.SECTION_DATA)));
        assertFalse(ExportController.zoneFilter("diff").test(row(Status.DIFF, Status.SECTION_TRAILER)));
        assertTrue(ExportController.zoneFilter("diff").test(row(Status.DIFF, Status.SECTION_DATA)));
        assertFalse(ExportController.zoneFilter("unmatched").test(row(Status.UNMATCHED_A, Status.SECTION_TRAILER)));
        assertTrue(ExportController.zoneFilter("unmatched").test(row(Status.UNMATCHED_B, Status.SECTION_DATA)));
        assertTrue(ExportController.zoneFilter("all").test(row(Status.EQUAL, Status.SECTION_TRAILER)));
    }
}
