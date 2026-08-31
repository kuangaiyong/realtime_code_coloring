package com.rtcc.platform.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IR 里「这门语言不提供这个指标」必须能与「有这个指标但值是 0」分开。
 *
 * 用 Integer 而不是 int，就是为了留出 null 这一档：Go 的 coverage profile 里
 * 压根没有分支这个概念，填 0 的话页面上会显示「0% 分支覆盖」——
 * 读的人会以为 Go 的分支一个都没测，而真相是那里没有分支这回事。
 * 这正是本项目最不能接受的那类静默错误：数字是假的，界面上却看不出是假的。
 */
class FileCoverageTest {

    @Test
    void 不提供该指标时字段为null而不是0() {
        FileCoverage go = new FileCoverage(
                "demo-service-go/main.go", "demo-service-go", "main.go",
                20, 44, 31.25,
                null, null, null, null,
                List.of(new FileCoverage.LineCoverage(12, "COVERED", null, null)));

        assertNull(go.coveredBranches(), "Go 没有分支概念，必须是 null 而不是 0");
        assertNull(go.missedBranches(), "Go 没有分支概念，必须是 null 而不是 0");
        assertNull(go.coveredMethods(), "Go 的 profile 里没有函数记录，必须是 null");
        assertNull(go.missedMethods(), "Go 的 profile 里没有函数记录，必须是 null");
        assertNull(go.lines().get(0).coveredBranches(), "行级同理");
    }

    @Test
    void 有该指标但这行没有分支时是零而不是null() {
        // Java 侧每一行都问得出「这行有几个分支」，答案可以是 0，但不会是「不知道」
        FileCoverage.LineCoverage plain = new FileCoverage.LineCoverage(42, "COVERED", 0, 0);

        assertEquals(0, plain.coveredBranches(), "这行确实没有分支，是 0 不是 null");
        assertEquals(0, plain.missedBranches());
    }
}
