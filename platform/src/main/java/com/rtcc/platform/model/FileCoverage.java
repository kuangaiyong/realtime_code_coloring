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
        List<MethodCoverage> methods,
        List<LineCoverage> lines
) {
    public record LineCoverage(int line, String status,
                               Integer coveredBranches, Integer missedBranches) {}

    /**
     * 一个方法/函数的覆盖明细，供「包 → 类 → 方法」的钻取报表用。
     *
     * <b>methods 整体为 null 表示「这门语言不提供方法明细」，与空列表是两回事</b>：
     * 空列表说的是「取过了，这个文件确实一个方法都没有」。混成一个的话，
     * 报表在拿不到明细的语言上会显示「这个文件没有方法」—— 而真相是没去取。
     *
     * <b>coveredLines / missedLines 只对单个方法有意义，不能跨方法相加。</b>
     * JaCoCo 的方法行数是按类算的，同一行可以同时属于外部类与写在该行上的匿名类，
     * 于是各方法行数之和会大于文件的行数 —— 页面上绝不能出现方法行的小计或合计，
     * 那个数字会比文件级的大，而它不是 bug，解释起来却极费劲。
     * 文件级永远以 FileCoverage 自己的 coveredLines/missedLines 为准。
     *
     * firstLine 是「点方法跳到源码」的落点。四种语言的来源各不相同：
     * Java 取 IMethodCoverage.getFirstLine()，Go 取 covdata func 输出里的行号，
     * Rust 取 lcov 的 FN 记录，C++ 则因为 gcov 的 function 行不带行号，
     * 只能取它<b>后面第一条</b>源码行的行号（实测确认 function 行紧贴函数定义行之前）。
     */
    public record MethodCoverage(String name, int firstLine,
                                 int coveredLines, int missedLines,
                                 Integer coveredBranches, Integer missedBranches) {}
}
