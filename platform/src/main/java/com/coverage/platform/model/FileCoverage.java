package com.coverage.platform.model;

import java.util.List;

/**
 * 单个源文件的行级覆盖结果。
 *
 * status 取值 EMPTY / MISSED / PARTIAL / COVERED，直接对应 JaCoCo 的四种行状态。
 * 注意：JaCoCo 的探针是布尔型的，只记录「是否执行过」，不记录执行次数，
 * 因此这里没有 hitCount 字段。
 */
public record FileCoverage(
        String path,
        String packageName,
        String sourceFileName,
        int coveredLines,
        int missedLines,
        double ratio,
        List<LineCoverage> lines
) {
    public record LineCoverage(int line, String status) {}
}
