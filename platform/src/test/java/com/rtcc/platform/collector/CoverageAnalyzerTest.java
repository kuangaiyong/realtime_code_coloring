package com.rtcc.platform.collector;

import com.rtcc.platform.model.FileCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 归一化层的行为约定。用平台自身编译产物作为被分析对象，属真实字节码而非构造数据。
 */
class CoverageAnalyzerTest {

    private final CoverageAnalyzer analyzer = new CoverageAnalyzer();

    private File classesDir() {
        File dir = new File("target/classes");
        assertTrue(dir.isDirectory(), "需先执行编译，target/classes 不存在");
        return dir;
    }

    @Test
    void 无执行数据时全部可执行行标记为未覆盖() throws Exception {
        // 空的 ExecutionDataStore 表示「探针一次都没命中」
        Map<String, FileCoverage> result = analyzer.analyze(new ExecutionDataStore(), classesDir(), null);

        assertFalse(result.isEmpty(), "应至少分析出一个源文件");

        FileCoverage probeClient = result.get("com/rtcc/platform/collector/ProbeClient.java");
        assertNotNull(probeClient, "未分析到 ProbeClient.java");
        assertEquals(0, probeClient.coveredLines(), "没有执行数据时不应有已覆盖行");
        assertTrue(probeClient.missedLines() > 0, "应识别出可执行但未覆盖的行");
        assertEquals(0d, probeClient.ratio(), 0.001);
        assertEquals("com.rtcc.platform.collector", probeClient.packageName());
    }

    @Test
    void 不可执行行不出现在结果中() throws Exception {
        Map<String, FileCoverage> result = analyzer.analyze(new ExecutionDataStore(), classesDir(), null);

        // 只保留 MISSED/PARTIAL/COVERED；空行、注释、import 等不该占据分母
        result.values().forEach(f ->
                f.lines().forEach(l ->
                        assertNotEquals("EMPTY", l.status(),
                                "不可执行行不应进入行列表: " + f.path() + ":" + l.line())));
    }

    @Test
    void 行号按升序排列以便前端直接渲染() throws Exception {
        Map<String, FileCoverage> result = analyzer.analyze(new ExecutionDataStore(), classesDir(), null);

        result.values().forEach(f -> {
            int prev = 0;
            for (FileCoverage.LineCoverage l : f.lines()) {
                assertTrue(l.line() > prev,
                        "行号应严格升序且不重复: " + f.path() + " 在 " + l.line() + " 处乱序");
                prev = l.line();
            }
        });
    }

    /**
     * 回归：单行匿名内部类会让同一行同时属于 Outer 与 Outer$1。
     * 早前按 getClasses() 自行合并时，该行被计入两次，使覆盖率分母虚增。
     */
    @Test
    void 同一行被多个类共享时不重复计数() throws Exception {
        File testClasses = new File("target/test-classes");
        assertTrue(testClasses.isDirectory());

        Map<String, FileCoverage> result = analyzer.analyze(new ExecutionDataStore(), testClasses, null);

        FileCoverage f = result.get("com/rtcc/platform/collector/SingleLineAnonymousFixture.java");
        assertNotNull(f, "未分析到验证夹具");

        Set<Integer> distinct = new HashSet<>();
        f.lines().forEach(l -> assertTrue(distinct.add(l.line()),
                "行号 " + l.line() + " 重复出现，覆盖率分母会被虚增"));
        assertEquals(distinct.size(), f.coveredLines() + f.missedLines(),
                "统计口径应与去重后的行数一致");
    }

    @Test
    void 覆盖率等于已覆盖行占可执行行的比例() throws Exception {
        Map<String, FileCoverage> result = analyzer.analyze(new ExecutionDataStore(), classesDir(), null);

        result.values().forEach(f -> {
            int total = f.coveredLines() + f.missedLines();
            double expected = total == 0 ? 0d : f.coveredLines() * 100d / total;
            assertEquals(expected, f.ratio(), 0.001, f.path() + " 覆盖率计算不一致");
            assertEquals(total, f.lines().size(), f.path() + " 行数与统计口径不一致");
        });
    }

    /**
     * 分支覆盖是这次要补的核心指标。用平台自身的字节码做被分析对象，
     * 空 ExecutionDataStore 表示「探针一次都没命中」——此时分支必须是
     * 「有分母、分子为 0」，而不是 null：Java 侧每一行都问得出这个问题。
     */
    @Test
    void Java侧分支与方法永远有值而不是null() throws Exception {
        Map<String, FileCoverage> result = analyzer.analyze(new ExecutionDataStore(), classesDir(), null);

        FileCoverage f = result.get("com/rtcc/platform/collector/ProbeEndpoint.java");
        assertNotNull(f, "未分析到 ProbeEndpoint.java");

        assertNotNull(f.coveredBranches(), "Java 拿得到分支，不该是 null");
        assertNotNull(f.missedBranches(), "Java 拿得到分支，不该是 null");
        assertEquals(0, f.coveredBranches(), "没有执行数据时不该有已覆盖分支");
        assertTrue(f.missedBranches() > 0,
                "ProbeEndpoint 里有 if / switch，未覆盖分支数应大于 0，实际 " + f.missedBranches());

        assertNotNull(f.coveredMethods(), "Java 拿得到方法数，不该是 null");
        assertEquals(0, f.coveredMethods(), "没有执行数据时不该有已覆盖方法");
        assertTrue(f.missedMethods() > 0, "应识别出未覆盖的方法，实际 " + f.missedMethods());
    }

    /**
     * 行级分支是源码区菱形标记的数据来源。没有分支的行必须是 0/0 而不是 null ——
     * null 在前端表示「这门语言不提供」，会让整列菱形消失
     */
    @Test
    void 行级分支在没有分支的行上是零而不是null() throws Exception {
        Map<String, FileCoverage> result = analyzer.analyze(new ExecutionDataStore(), classesDir(), null);

        boolean sawBranchLine = false;
        for (FileCoverage f : result.values()) {
            for (FileCoverage.LineCoverage l : f.lines()) {
                assertNotNull(l.coveredBranches(),
                        "Java 的行级分支不该是 null：" + f.path() + ":" + l.line());
                assertNotNull(l.missedBranches(),
                        "Java 的行级分支不该是 null：" + f.path() + ":" + l.line());
                if (l.missedBranches() > 0) {
                    sawBranchLine = true;
                }
            }
        }
        assertTrue(sawBranchLine, "整个平台的字节码里应至少有一行带未覆盖分支");
    }

    /**
     * 方法明细与文件级计数必须对得上。
     *
     * 这是「文件级走 getSourceFiles、方法级走 getClasses」双路径最容易出错的地方：
     * getSourceFiles 本身就是 JaCoCo 按「包名 + 源文件名」把各个 class 聚出来的，
     * 所以 sf.getMethodCounter() 恒等于各类 methodCounter 之和 —— 只要归并的键一致，
     * 条数就该严丝合缝。对不上就说明归并键错了，而那会让报表里的方法凭空多出或少掉。
     */
    @Test
    void 方法条数与文件级计数一致() throws Exception {
        Map<String, FileCoverage> result = analyzer.analyze(new ExecutionDataStore(), classesDir(), null);

        for (FileCoverage f : result.values()) {
            assertNotNull(f.methods(), "Java 拿得到方法明细，不该是 null：" + f.path());
            assertEquals(f.coveredMethods() + f.missedMethods(), f.methods().size(),
                    "方法条数与文件级计数对不上：" + f.path());
        }
    }

    /**
     * 一个源文件编译出多个 class 时，方法要全部归到这一个源文件上。
     *
     * ProjectRuntime.java 在本仓库编译出 8 个 class（Snapshot / InstanceStatus /
     * Scenario 等 record 都是嵌套类型）。被测的 demo-service 只有 4 个源文件、
     * 0 个内部类，在它上面测不出这个风险 —— 所以这条用平台自己的字节码来守。
     */
    @Test
    void 内部类的方法归到所属源文件而不是丢掉() throws Exception {
        Map<String, FileCoverage> result = analyzer.analyze(new ExecutionDataStore(), classesDir(), null);

        FileCoverage f = result.get("com/rtcc/platform/service/ProjectRuntime.java");
        assertNotNull(f, "未分析到 ProjectRuntime.java");

        assertTrue(f.methods().size() > 20,
                "ProjectRuntime.java 有 8 个 class，方法数应远大于 20，实际 " + f.methods().size());
        // label() 是嵌套 record InstanceStatus 上<b>手写</b>的方法。
        // 不挑 record 的访问器来断言：那些是编译器生成的，JaCoCo 的 filter 会滤掉，
        // 用它们做判据会把「内部类没归进来」和「这类方法本就不计入」混为一谈
        assertTrue(f.methods().stream().anyMatch(m -> m.name().startsWith("label(")),
                "嵌套 record InstanceStatus 的 label() 没归进来，说明内部类的方法被丢了："
                        + f.methods().stream().map(FileCoverage.MethodCoverage::name).toList());
    }

    /** 每个方法都要有落点行号 —— 那是「点方法跳源码」唯一的依据 */
    @Test
    void 每个方法都带得出首行号() throws Exception {
        Map<String, FileCoverage> result = analyzer.analyze(new ExecutionDataStore(), classesDir(), null);

        for (FileCoverage f : result.values()) {
            for (FileCoverage.MethodCoverage m : f.methods()) {
                assertTrue(m.firstLine() > 0,
                        "方法没有首行号，点它跳不到源码：" + f.path() + " 的 " + m.name());
                assertNotNull(m.name(), "方法名不能为空：" + f.path());
            }
        }
    }

    /**
     * 方法名要给人看，不能直接摆字节码里的名字。
     *
     * {@code <init>} / {@code <clinit>} 是构造器与静态初始化块，原样显示等于让人
     * 自己去猜。JaCoCo 的 HTML 报告靠 org.jacoco.report 渲染它们，而本项目只依赖
     * org.jacoco.core（面向内网，不为一个显示名多拉一个包），所以自己认这两个特例。
     */
    @Test
    void 方法名不出现字节码里的尖括号名字() throws Exception {
        Map<String, FileCoverage> result = analyzer.analyze(new ExecutionDataStore(), classesDir(), null);

        java.util.List<String> bad = result.values().stream()
                .flatMap(f -> f.methods().stream())
                .map(FileCoverage.MethodCoverage::name)
                .filter(n -> n.startsWith("<"))
                .distinct().limit(5).toList();
        assertTrue(bad.isEmpty(), "方法名里还留着字节码的原始名：" + bad);

        // 顺带钉住重载能分辨：带参数段的方法名必须存在，否则 pay(int) 与 pay(long)
        // 在报表里会是两行一模一样的字
        java.util.List<String> sample = result.get("com/rtcc/platform/collector/ProbeEndpoint.java")
                .methods().stream().map(FileCoverage.MethodCoverage::name).toList();
        assertTrue(sample.stream().anyMatch(n -> n.contains("(")),
                "方法名没带参数段，重载分辨不了：" + sample);
    }
}
