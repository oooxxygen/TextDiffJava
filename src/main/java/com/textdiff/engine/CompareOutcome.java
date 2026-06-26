package com.textdiff.engine;

/** 一次对比的结果概要。对等 Python comparator.CompareOutcome。 */
public record CompareOutcome(
        Summary summary,
        String detectedEncodingA,
        String detectedEncodingB,
        boolean usedDiskFallback) {}
