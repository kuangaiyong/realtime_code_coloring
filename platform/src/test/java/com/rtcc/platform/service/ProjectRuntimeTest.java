package com.rtcc.platform.service;

import com.rtcc.platform.collector.CoverageAnalyzer;
import com.rtcc.platform.collector.CppCoverageAnalyzer;
import com.rtcc.platform.collector.CppProbeClient;
import com.rtcc.platform.collector.GitService;
import com.rtcc.platform.collector.GoCoverageAnalyzer;
import com.rtcc.platform.collector.GoProbeClient;
import com.rtcc.platform.collector.ProbeClient;
import com.rtcc.platform.collector.RustCoverageAnalyzer;
import com.rtcc.platform.collector.RustProbeClient;
import com.rtcc.platform.config.CoverageProperties;
import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.history.CoverageHistory;
import com.rtcc.platform.model.FileCoverage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景归因与覆盖率门禁中「必须拒绝」的那些路径。
 *
 * 这些分支不需要探针也不需要覆盖数据，正好在单测里守住；
 * 真正跑通一个场景（清零→执行→定格→归因隔离）依赖真实被测服务，
 * 由 scripts/e2e_scenario.py 端到端验证。
 */
class ProjectRuntimeTest {

    private ProjectRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        ProjectConfig props = new ProjectConfig();
        props.setId("test");
        // 工具链路径与项目无关，取平台默认值即可：这些用例在够不到探针时就返回了，
        // 根本走不到调 covdata / gcov / llvm-cov 那一步
        CoverageProperties platform = new CoverageProperties();
        // 取一个刚释放、确定没人监听的端口：本测试只关心够不到探针时的行为
        int dead;
        try (ServerSocket free = new ServerSocket(0)) {
            dead = free.getLocalPort();
        }
        props.setInstances(List.of("localhost:" + dead));
        props.setTimeoutMs(300);
        runtime = new ProjectRuntime(new ProbeClient(), new CoverageAnalyzer(),
                new GoProbeClient(props), new GoCoverageAnalyzer(props, platform),
                new CppProbeClient(props), new CppCoverageAnalyzer(props, platform),
                new RustProbeClient(props), new RustCoverageAnalyzer(props, platform),
                new GitService(props), props, new CoveragePublisher(),
                // 数据源指向一个必然连不上的地址：这些用例要证明的正是
                // 「历史写不进去也不影响其余行为」
                new CoverageHistory(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        "jdbc:mysql://127.0.0.1:1/nonexistent")),
                // 采集事件同理：写不进去也不该影响采集本身
                new com.rtcc.platform.history.CollectEvents(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:1/nonexistent")));
    }

    @Test
    void 没有活跃场景时结束场景直接报错() {
        ScenarioConflictException e = assertThrows(ScenarioConflictException.class,
                () -> runtime.stopScenario());
        assertTrue(e.getMessage().contains("没有进行中的场景"), e.getMessage());
    }

    @Test
    void 场景ID不能为空() {
        assertThrows(ScenarioConflictException.class, () -> runtime.startScenario(null));
        assertThrows(ScenarioConflictException.class, () -> runtime.startScenario("  "));
    }

    @Test
    void 清零失败时场景不能算已开始() {
        // 探针够不到 → 计数器没被清零。此时若把场景标记为已开始，
        // stop 归档下来的会是一段掺着历史数据的覆盖，却挂着这个场景的名字
        assertThrows(Exception.class, () -> runtime.startScenario("s1"));

        assertNull(runtime.listScenarios().get("active"), "清零失败后不应留下活跃场景");
        assertThrows(ScenarioConflictException.class, () -> runtime.stopScenario());
    }

    @Test
    void 查看不存在的场景报错而不是回退到实时数据() {
        // 回退到实时数据会让用户以为看到的是那个场景的独占覆盖
        assertThrows(ScenarioNotFoundException.class,
                () -> runtime.summary(ProjectRuntime.MODE_FULL, null, "never-existed"));
        assertThrows(ScenarioNotFoundException.class,
                () -> runtime.fileDetail("a/B.java", ProjectRuntime.MODE_FULL, null, "never-existed"));
    }

    @Test
    void 某台实例取不到时对比表仍出其余行而不是整体失败() {
        // 一台够不到就抛异常的话，对比视图会整张空掉 —— 其余实例的数据明明是真的。
        // 但那一行必须显式标成 DISCONNECTED 并带原因，留空会被读成「这台什么都没跑」
        java.util.Map<String, Object> res = runtime.perInstance();
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> rows =
                (java.util.List<java.util.Map<String, Object>>) res.get("instances");

        assertEquals(1, rows.size());
        assertEquals("DISCONNECTED", rows.get(0).get("status"));
        assertNotNull(rows.get(0).get("error"), "取不到必须说明原因");
        assertNull(rows.get(0).get("overallRatio"), "取不到时不能给出覆盖率，0% 会被当成真的跑了却没覆盖");
        assertNotNull(res.get("collectedAt"));
    }

    @Test
    void 门禁在探针够不到时拒绝判定而不是判不通过() {
        // 采集失败 → 覆盖数据是空的 → 比例算出来是 0% → 判「不通过」。
        // 那条结论会让人去补测试，而真正的原因（探针连不上）一个字都不会出现在结论里。
        // 所以这里必须抛（HTTP 409），让 CI 走「平台有问题」那条处置路径
        runtime.collect();

        for (String mode : new String[]{ProjectRuntime.MODE_FULL, ProjectRuntime.MODE_INCREMENTAL}) {
            GateUndecidableException e = assertThrows(GateUndecidableException.class,
                    () -> runtime.gate(mode, null));
            assertTrue(e.getMessage().contains("DISCONNECTED"),
                    "结论里必须点出探针状态，否则看的人无从下手：" + e.getMessage());
        }
    }

    @Test
    void 认不出的门禁口径拒判而不是默默按全量放行() {
        // 别的接口把认不出的 mode 当 full 处理，页面上还看得见回的是哪个口径。
        // 门禁的结论没人看：`?mode=incremantal` 拼错一个字母就落到 full，
        // 而 full 阈值默认 0 —— 门禁被整个旁路，还回 200 说「未设门槛」
        for (String bad : new String[]{"incremantal", "INCREMENTAL", "", "diff"}) {
            GateUndecidableException e = assertThrows(GateUndecidableException.class,
                    () -> runtime.gate(bad, null),
                    "mode=" + bad + " 必须拒判");
            assertTrue(e.getMessage().contains("认不出的口径"), e.getMessage());
        }
    }

    @Test
    void 未指定场景时看的是实时数据() {
        assertNull(runtime.summary(ProjectRuntime.MODE_FULL, null, null).get("scenarioId"));
        assertNull(runtime.summary(ProjectRuntime.MODE_FULL, null, "").get("scenarioId"));
    }

    /**
     * 增量口径下方法覆盖答不上来：一个方法通常只有几行落在 diff 里，
     * 透传全量的方法数会让人把它读成「这次改动的方法覆盖率」。
     * 分支则可以从保留下来的行累加 —— 那本来就是逐行的。
     */
    @Test
    void 增量裁剪后分支按保留行累加而方法置空() {
        FileCoverage full = new FileCoverage(
                "a/B.java", "a", "B.java", 3, 1, 75d,
                6, 2, 4, 1, null,
                List.of(new FileCoverage.LineCoverage(10, "COVERED", 2, 0),
                        new FileCoverage.LineCoverage(11, "PARTIAL", 1, 1),
                        new FileCoverage.LineCoverage(12, "COVERED", 3, 1),
                        new FileCoverage.LineCoverage(13, "MISSED", 0, 0)));

        // 只保留 11、12 两行（模拟 git diff 只命中这两行）
        FileCoverage cut = ProjectRuntime.restrictOneForTest(full, Set.of(11, 12));

        assertEquals(2, cut.lines().size());
        assertEquals(4, cut.coveredBranches(), "11 行 1 个 + 12 行 3 个");
        assertEquals(2, cut.missedBranches(), "11 行 1 个 + 12 行 1 个");
        assertNull(cut.coveredMethods(), "增量口径下方法覆盖答不上来，必须置空");
        assertNull(cut.missedMethods(), "增量口径下方法覆盖答不上来，必须置空");
    }

    /** Go 的行级分支本来就是 null，累加不能把它变成 0 —— 那等于说「有分支但没测」 */
    @Test
    void 不提供分支的语言裁剪后仍是空而不是零() {
        FileCoverage go = new FileCoverage(
                "demo-service-go/main.go", "demo-service-go", "main.go", 1, 1, 50d,
                null, null, null, null, null,
                List.of(new FileCoverage.LineCoverage(10, "COVERED", null, null),
                        new FileCoverage.LineCoverage(11, "MISSED", null, null)));

        FileCoverage cut = ProjectRuntime.restrictOneForTest(go, Set.of(10));

        assertNull(cut.coveredBranches(), "Go 没有分支概念，裁剪后仍该是 null");
        assertNull(cut.missedBranches(), "Go 没有分支概念，裁剪后仍该是 null");
    }

    /**
     * 分支不做跨语言汇总。实测 C++ 一个 demo 有 359 条分支（滤掉 throw 后 239 条），
     * 而源码里真正的条件语句只有 32 处 —— 与 Java 差一个数量级。
     * 汇总出来的百分比等于在报告 C++ 的异常处理路径覆盖率。
     */
    @Test
    void 分支按语言分开汇总不给跨语言总数() {
        Map<String, FileCoverage> snap = Map.of(
                "demo-service/src/main/java/A.java", new FileCoverage(
                        "demo-service/src/main/java/A.java", "a", "A.java", 5, 5, 50d,
                        10, 6, 3, 1, null, List.of()),
                "demo-service-cpp/order.cpp", new FileCoverage(
                        "demo-service-cpp/order.cpp", "demo-service-cpp", "order.cpp", 5, 5, 50d,
                        27, 212, 4, 7, null, List.of()),
                "demo-service-go/main.go", new FileCoverage(
                        "demo-service-go/main.go", "demo-service-go", "main.go", 5, 5, 50d,
                        null, null, null, null, null, List.of()));

        Map<String, Map<String, Integer>> byLang = ProjectRuntime.branchesByLanguage(snap);

        assertEquals(10, byLang.get("java").get("covered"));
        assertEquals(6, byLang.get("java").get("missed"));
        assertEquals(27, byLang.get("cpp").get("covered"));
        assertEquals(212, byLang.get("cpp").get("missed"));
        assertFalse(byLang.containsKey("go"), "Go 不提供分支，不该出现在这张表里 —— "
                + "出现了就意味着页面上会显示一个 0%，而那是假的");
        assertFalse(byLang.containsKey("rust"), "Rust 不提供分支，同理");
    }
}
