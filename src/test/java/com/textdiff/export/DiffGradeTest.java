package com.textdiff.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 数量偏离总体评判分级（10% 保持关注 / 20% 差异明显 / 50% 严重问题）。 */
class DiffGradeTest {

    @Test
    void 分级阈值() {
        assertEquals("正常", DiffGrade.of(1000, 1050).label());        // 偏离 ~4.9%
        assertEquals("保持关注", DiffGrade.of(1000, 1150).label());    // 偏离 ~13.9%
        assertEquals("差异明显", DiffGrade.of(1000, 1350).label());    // 偏离 ~29.8%
        assertEquals("严重问题", DiffGrade.of(1000, 1700).label());    // 偏离 ~51.9%
        assertEquals("严重问题", DiffGrade.of(0, 100).label());        // 全量缺失（偏离 100%）
        assertEquals("正常", DiffGrade.of(500, 500).label());
        assertEquals("正常", DiffGrade.of(0, 0).label());
    }

    @Test
    void 状态情况描述含数量差与偏离率() {
        DiffGrade.Grade g = DiffGrade.of(900, 1100);
        assertTrue(g.desc().contains("数量差 200"));
        assertTrue(g.desc().contains("20.0%"));   // 200 / ((900+1100)/2) = 20%
    }
}
