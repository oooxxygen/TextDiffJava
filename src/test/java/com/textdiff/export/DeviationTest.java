package com.textdiff.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 偏离度双指标：内容偏离度=有差异条数/已匹配键值的记录数（完全匹配+有差异）；条数偏离度=|B−A|/A总条数；阈值 10/20/50 分级。 */
class DeviationTest {

    @Test
    void contentMetricRatioAndGrades() {
        // 有差异 20 / 已匹配键值 100（完全匹配 80 + 有差异 20）= 20% → 务必关注（黄）
        Deviation.Metric m = Deviation.content(20, 80);
        assertEquals(0.2, m.ratio(), 1e-9);
        assertEquals("务必关注", m.grade());
        assertEquals("20.0%", m.percent());

        assertEquals("正常", Deviation.content(0, 100).grade());
        assertEquals("微量偏移", Deviation.content(10, 90).grade());   // 恰 10%
        assertEquals("微量偏移", Deviation.content(19, 81).grade());
        assertEquals("务必关注", Deviation.content(20, 80).grade());   // 恰 20%
        assertEquals("严重问题", Deviation.content(50, 50).grade());   // 恰 50%
        assertEquals("严重问题", Deviation.content(3, 0).grade());     // 仅差异无完全匹配 → 100%
    }

    @Test
    void contentNoMatchedShowsDash() {
        // 已匹配键值记录数 = 0（diff+equal=0）：内容偏离度无定义 → ratio=NaN、grade「—」、percent「—」
        Deviation.Metric m = Deviation.content(0, 0);
        assertTrue(Double.isNaN(m.ratio()));
        assertEquals("—", m.grade());
        assertEquals("—", m.percent());
        Deviation.Metric only = Deviation.content(0, 0);
        assertEquals("—", only.grade());
    }

    @Test
    void countMetricRatioAndGrades() {
        assertEquals(0.25, Deviation.count(80, 100).ratio(), 1e-9);  // |100-80|/80
        assertEquals("务必关注", Deviation.count(80, 100).grade());
        assertEquals("正常", Deviation.count(100, 108).grade());     // 8% < 10%
        assertEquals("微量偏移", Deviation.count(100, 110).grade());   // 恰 10%
        assertEquals("严重问题", Deviation.count(4, 10).grade());      // 150%
        assertEquals("正常", Deviation.count(100, 100).grade());
    }

    @Test
    void badgeClassMapping() {
        assertEquals("b-purple", Deviation.badgeClass("微量偏移"));
        assertEquals("b-yellow", Deviation.badgeClass("务必关注"));
        assertEquals("b-red", Deviation.badgeClass("严重问题"));
        assertEquals("b-green", Deviation.badgeClass("正常"));
    }
}
