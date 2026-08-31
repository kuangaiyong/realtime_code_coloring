package com.rtcc.platform.model;

import java.util.List;

/**
 * 单个源文件的覆盖结果。
 *
 * status 取值 EMPTY / MISSED / PARTIAL / COVERED，直接对应 JaCoCo 的四种行状态。
 * 注意：JaCoCo 的探针是布尔型的，只记录「是否执行过」，不记录执行次数，
 * 因此这里没有 hitCount 字段。
 *
 * <b>分支与方法用 Integer 而非 int，null 表示「这门语言不提供这个指标」。</b>
 * 0/0（有这个概念，但这一行没有分支）与 null（这门语言根本没有分支概念）
 * 必须分得开：混成 0 的话，页面上 Go 会显示「0% 分支覆盖」，
 * 读的人会以为 Go 的分支一个都没测，而真相是那里压根没有分支这回事。
 * 四种语言的能力差异见 docs/specs/2026-08-31-branch-method-coverage-design.md §2。
 */
public record FileCoverage(
        String path,
        String packageName,
        String sourceFileName,
        int coveredLines,
        int missedLines,
        double ratio,
        Integer coveredBranches,
        Integer missedBranches,
        Integer coveredMethods,
        Integer missedMethods,
        List<LineCoverage> lines
) {
    public record LineCoverage(int line, String status,
                               Integer coveredBranches, Integer missedBranches) {}
}
