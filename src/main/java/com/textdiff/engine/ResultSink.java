package com.textdiff.engine;

import java.util.ArrayList;
import java.util.List;

/** 结果接收接口。comparator 逐行调用 addRow，便于流式落盘。对等 Python comparator.ResultSink。 */
public interface ResultSink {
    void addRow(RowDiff row);

    /** 内存收集，供测试与小数据使用。 */
    final class ListSink implements ResultSink {
        public final List<RowDiff> rows = new ArrayList<>();

        @Override
        public void addRow(RowDiff row) {
            rows.add(row);
        }
    }
}
