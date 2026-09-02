package com.rtcc.platform.collector;

import com.rtcc.platform.config.CoverageProperties;
import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.model.FileCoverage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Go 归一化里「宁可不出报告」的那几条路径。
 *
 * 真正跑通一次 Go 采集（插桩构建 → 抓 meta/counters → covdata → 行级染色）依赖
 * 真实 Go 服务，由 scripts/e2e_go.py 端到端验证；这里守住的是配置不全时的行为 ——
 * 它最容易被漏掉，因为出错的形态是「Go 一个文件都没有」，与「Go 代码没被调用过」
 * 在界面上长得完全一样。
 */
class GoCoverageAnalyzerTest {

    /** 一份内容无所谓的 dump：这些用例在碰 covdata 之前就该失败 */
    // 显式给出类型实参：否则 List.of 会把 byte[][] 当成 byte[] 的可变参数展开
    private static final List<byte[][]> ONE_DUMP =
            List.<byte[][]>of(new byte[][]{new byte[]{1}, new byte[]{2}});

    @Test
    void 没有Go实例时不做任何事() throws Exception {
        assertEquals(0, new GoCoverageAnalyzer(new ProjectConfig(), new CoverageProperties()).analyze(List.of()).size());
    }

    @Test
    void 未配置模块路径时拒绝出报告而不是返回空结果() {
        ProjectConfig props = new ProjectConfig();
        props.setGoSourceRoot("demo-service-go");
        // go-module-path 缺失 → profile 里的 import path 一个也换算不成仓库相对路径

        IOException e = assertThrows(IOException.class,
                () -> new GoCoverageAnalyzer(props, new CoverageProperties()).analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains("go-module-path"), e.getMessage());
    }

    @Test
    void 未配置源码根时同样拒绝出报告() {
        ProjectConfig props = new ProjectConfig();
        props.setGoModulePath("example.com/demo");

        IOException e = assertThrows(IOException.class,
                () -> new GoCoverageAnalyzer(props, new CoverageProperties()).analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains("go-source-root"), e.getMessage());
    }

    // ── 方法明细 ────────────────────────────────────────────────────────────
    //
    // 下面两段是从真实运行中的 demo-service-go 抓下来的 covdata 输出（去掉了共同的
    // import path 前缀，由 go() 拼回去），不是编造的样本。前缀单列出来是因为
    // toRepoRelative 要拿它做匹配，写错了整个文件就被判成「不是被测模块的代码」。

    private static final String MODULE = "github.com/kuangaiyong/realtime_code_coloring/demo-service-go";
    private static final String PKG = MODULE + "/";

    private static String go(String... lines) {
        return Arrays.stream(lines)
                .map(l -> l.startsWith("mode:") || l.startsWith("total") ? l : PKG + l)
                .collect(Collectors.joining("\n"));
    }

    /** covdata textfmt 的真实输出 */
    private static final String TEXTFMT = go(
            "mode: atomic",
            "coverage_agent.go:23.13,25.16 2 0",
            "coverage_agent.go:25.16,30.3 1 0",
            "coverage_agent.go:32.2,36.78 2 0",
            "coverage_agent.go:36.78,38.3 1 198",
            "coverage_agent.go:40.2,40.80 1 0",
            "coverage_agent.go:40.80,42.47 2 199",
            "coverage_agent.go:42.47,46.4 1 0",
            "coverage_agent.go:49.2,49.84 1 0",
            "coverage_agent.go:49.84,51.51 2 199",
            "coverage_agent.go:51.51,53.4 1 0",
            "coverage_agent.go:56.2,56.81 1 0",
            "coverage_agent.go:56.81,57.50 1 0",
            "coverage_agent.go:57.50,62.4 2 0",
            "coverage_agent.go:63.3,63.31 1 1",
            "coverage_agent.go:66.2,66.12 1 0",
            "coverage_agent.go:66.12,68.56 2 0",
            "coverage_agent.go:68.56,70.4 1 0",
            "main.go:28.24,33.2 1 0",
            "main.go:35.34,37.2 1 0",
            "main.go:40.61,44.9 4 0",
            "main.go:44.9,46.3 1 0",
            "main.go:47.2,47.21 1 0",
            "main.go:47.21,49.3 1 0",
            "main.go:50.2,50.16 1 0",
            "main.go:51.17,53.26 2 0",
            "main.go:54.17,56.30 2 0",
            "main.go:57.16,59.29 2 0",
            "main.go:60.10,61.36 1 0",
            "main.go:63.2,63.17 1 0",
            "main.go:67.49,71.9 4 0",
            "main.go:71.9,73.3 1 0",
            "main.go:74.2,74.10 1 0",
            "main.go:78.59,82.9 4 0",
            "main.go:82.9,84.3 1 0",
            "main.go:85.2,85.24 1 0",
            "main.go:85.24,87.3 1 0",
            "main.go:88.2,88.27 1 0",
            "main.go:88.27,90.3 1 0",
            "main.go:91.2,92.19 2 0",
            "main.go:95.47,98.2 2 0",
            "main.go:100.13,107.85 5 0",
            "main.go:107.85,110.19 3 0",
            "main.go:110.19,112.4 1 0",
            "main.go:113.3,113.59 1 0",
            "main.go:116.2,116.82 1 0",
            "main.go:116.82,118.15 2 0",
            "main.go:118.15,121.4 2 0",
            "main.go:122.3,122.16 1 0",
            "main.go:125.2,125.83 1 0",
            "main.go:125.83,127.48 2 0",
            "main.go:127.48,131.18 2 0",
            "main.go:131.18,134.5 2 0",
            "main.go:135.4,135.21 1 0",
            "main.go:137.3,137.63 1 0",
            "main.go:142.2,143.16 2 0",
            "main.go:143.16,145.3 1 0",
            "main.go:146.2,147.32 2 0");

    /** covdata func 的真实输出。制表符分隔，末尾还有一行 total */
    private static final String FUNC = go(
            "coverage_agent.go:23:\tinit\t\t\t26.1%",
            "main.go:28:\t\tNewStore\t\t0.0%",
            "main.go:35:\t\tisFinalState\t\t0.0%",
            "main.go:40:\t\t*Store.HandleCallback\t0.0%",
            "main.go:67:\t\t*Store.QueryOrder\t0.0%",
            "main.go:78:\t\t*Store.Refund\t0.0%",
            "main.go:95:\t\trespond\t\t\t0.0%",
            "main.go:100:\t\tmain\t\t\t0.0%",
            "total\t\t\t\t\t\t(statements)\t\t6.7%");

    private ProjectConfig goProps() {
        ProjectConfig p = new ProjectConfig();
        p.setGoModulePath(MODULE);
        p.setGoSourceRoot("demo-service-go");
        return p;
    }

    private GoCoverageAnalyzer analyzer(ProjectConfig p) {
        return new GoCoverageAnalyzer(p, new CoverageProperties());
    }

    @Test
    void 函数表解析出名字与首行号并跳过total行() {
        var funcs = analyzer(goProps()).parseFuncs(FUNC, MODULE, "demo-service-go");

        List<GoCoverageAnalyzer.Func> main = funcs.get("demo-service-go/main.go");
        assertNotNull(main, "没解析出 main.go 的函数表：" + funcs.keySet());
        assertEquals(7, main.size(), "main.go 有 7 个函数，多出来的多半是把 total 行也算进去了");
        // 名字里带 * 和 . ，按「非空白串」以外的写法很容易在这儿丢掉接收者
        assertEquals("*Store.HandleCallback", main.get(2).name());
        assertEquals(78, main.stream().filter(f -> f.name().equals("*Store.Refund"))
                .findFirst().orElseThrow().firstLine());
    }

    /**
     * 探针文件测的是它自己，计进去只会稀释被测代码的覆盖率 —— 块数据那条路径早就滤掉了它，
     * 函数表这条新路径必须一起滤，否则报表里会凭空多出一个 init。
     */
    @Test
    void 被排除的探针文件不进函数表() {
        var funcs = analyzer(goProps()).parseFuncs(FUNC, MODULE, "demo-service-go");
        assertFalse(funcs.containsKey("demo-service-go/coverage_agent.go"),
                "coverage_agent.go 在 go-exclude 里，函数表也不该带上它：" + funcs.keySet());
    }

    /**
     * 方法的覆盖行数是从块数据自己算的，不是拿 covdata func 那个百分比换算的
     * （那个口径是语句，换不过来）。自己算的好处是与文件级同源，父子必然对得上 ——
     * 报表钻进去发现方法行数加起来不等于文件行数，会被当成平台算错了。
     */
    @Test
    void 方法行数加总等于文件行数() throws Exception {
        Map<String, FileCoverage> r = parseWithFuncs(goProps());
        FileCoverage f = r.get("demo-service-go/main.go");
        assertNotNull(f, "没解析出 main.go：" + r.keySet());

        assertNotNull(f.methods(), "Go 现在拿得到方法明细");
        int sum = f.methods().stream().mapToInt(m -> m.coveredLines() + m.missedLines()).sum();
        assertEquals(f.coveredLines() + f.missedLines(), sum,
                "方法行数加总与文件行数对不上，说明有行没归属或归重了");
    }

    /**
     * 归属靠「首行号<b>不大于</b>本行的最后一个函数」。respond 占 95~98 行，
     * 四行都不是空行（真实源码），所以它恰好 4 行。
     *
     * <p>与上面那条加总用例是互补的，两条都得留着 —— 实测过：把边界误写成严格小于，
     * 挂的是<b>加总</b>那条（每个函数的首行都成了上一个函数的，最前面那行没了主，
     * 加总少 1），而这一条纹丝不动（respond 丢了 95、得了 100，还是 4 行）。
     * 整体平移错一位则反过来：加总照样相等，只有这一条看得出来。
     */
    @Test
    void 行按首行号归到正确的函数名下() throws Exception {
        FileCoverage f = parseWithFuncs(goProps()).get("demo-service-go/main.go");

        FileCoverage.MethodCoverage respond = f.methods().stream()
                .filter(m -> m.name().equals("respond")).findFirst().orElseThrow();
        assertEquals(95, respond.firstLine());
        assertEquals(4, respond.coveredLines() + respond.missedLines(),
                "respond 占 95~98 行；数不对说明归属越过了它与 main 的边界");
    }

    /**
     * Go 有方法、没有分支。两者过去都是 null，现在只有分支还是 null ——
     * 给方法补个 0 或给分支补个 0，在页面上都会被读成「一个都没测」。
     */
    @Test
    void Go有方法计数但仍然没有分支() throws Exception {
        FileCoverage f = parseWithFuncs(goProps()).get("demo-service-go/main.go");

        assertEquals(7, f.coveredMethods() + f.missedMethods(), "7 个函数都该计入");
        assertEquals(0, f.coveredMethods(), "这份真实数据里 main.go 一个函数都没跑到");
        assertNull(f.coveredBranches(), "Go 的 profile 里没有分支这个概念，不能补 0");
        assertNull(f.missedBranches());
    }

    /**
     * 有跑过的行 → 方法算跑过。真实数据里 main.go 全是 0，把探针文件放回来才有跑过的函数
     * （它的 init 里那几个 HandleFunc 闭包被平台每 3 秒轮询打到）。
     */
    @Test
    void 有行跑过的函数算已覆盖() throws Exception {
        ProjectConfig p = goProps();
        p.setGoExclude(List.of()); // 只有这一个用例把探针文件放回来
        FileCoverage f = parseWithFuncs(p).get("demo-service-go/coverage_agent.go");
        assertNotNull(f, "把 exclude 清空后该解析得出探针文件");

        FileCoverage.MethodCoverage init = f.methods().stream()
                .filter(m -> m.name().equals("init")).findFirst().orElseThrow();
        assertTrue(init.coveredLines() > 0, "init 里有跑过的行");
        assertEquals(1, f.coveredMethods(), "有跑过的行就算跑过");
        assertEquals(0, f.missedMethods());
    }

    /**
     * covdata func 只给首行号、不给结束行，所以一个函数的行区间上界只能取「下一个函数的首行」。
     * 夹在两个具名函数之间的<b>包级</b>可执行代码（{@code var f = func(){…}}）
     * 会落进它上面那个函数的区间里。真机上复现过：一个从没执行的具名函数，
     * 因为下面跟着一个跑过的包级闭包，按行数推就成了「已覆盖」。
     *
     * <p>所以「跑过没跑过」必须取 covdata 给的百分比。下面这段是那个场景的最小复现 ——
     * 函数表是真实的，只把 respond 那个块的执行次数改成 1，模拟「它区间里有行跑过、
     * 而它自己 0.0%」。行数偏大是看得见的粒度问题，覆盖判定错了却看不出来。
     */
    @Test
    void 跑过没跑过取百分比而不是归属出来的行数() throws Exception {
        List<String> withHit = TEXTFMT.lines()
                .map(l -> l.endsWith("main.go:95.47,98.2 2 0")
                        ? l.replace("95.47,98.2 2 0", "95.47,98.2 2 1") : l)
                .toList();
        GoCoverageAnalyzer a = analyzer(goProps());
        FileCoverage f = a.parse(withHit, MODULE, "demo-service-go",
                a.parseFuncs(FUNC, MODULE, "demo-service-go")).get("demo-service-go/main.go");

        FileCoverage.MethodCoverage respond = f.methods().stream()
                .filter(m -> m.name().equals("respond")).findFirst().orElseThrow();
        assertTrue(respond.coveredLines() > 0, "前提：归属到 respond 名下确实有跑过的行");
        assertEquals(0, f.coveredMethods(),
                "covdata 说这七个函数都是 0.0%，就一个都没跑过 —— "
                        + "按归属出来的行数推会说成 1 个，那是把没跑过的说成跑过");
    }

    @Test
    void 百分比大于零才算跑过() {
        var funcs = analyzer(goProps()).parseFuncs(FUNC, MODULE, "demo-service-go");
        assertTrue(funcs.get("demo-service-go/main.go").stream().noneMatch(
                GoCoverageAnalyzer.Func::hit), "这份真实数据里 main.go 的七个函数都是 0.0%");

        ProjectConfig p = goProps();
        p.setGoExclude(List.of());
        var withAgent = analyzer(p).parseFuncs(FUNC, MODULE, "demo-service-go");
        assertTrue(withAgent.get("demo-service-go/coverage_agent.go").get(0).hit(),
                "init 是 26.1%，算跑过");
    }

    private Map<String, FileCoverage> parseWithFuncs(ProjectConfig p) throws IOException {
        GoCoverageAnalyzer a = analyzer(p);
        return a.parse(TEXTFMT.lines().toList(), MODULE, "demo-service-go",
                a.parseFuncs(FUNC, MODULE, "demo-service-go"));
    }
}
