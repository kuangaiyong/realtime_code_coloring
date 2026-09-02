package com.rtcc.platform.collector;

import com.rtcc.platform.config.CoverageProperties;
import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.model.FileCoverage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C++ 归一化里「宁可不出报告」的那几条路径，以及 gcov 计数标记的映射。
 *
 * 真正跑通一次 C++ 采集（--coverage 构建 → __gcov_dump → gcov → 行级染色）依赖
 * 真实服务，由 scripts/e2e_cpp.py 端到端验证；这里守住的是拿不到有效数据时的行为 ——
 * 它最容易被漏掉，因为出错的形态是「C++ 一个文件都没有」，与「C++ 代码没被调用过」
 * 在界面上长得完全一样。
 */
class CppCoverageAnalyzerTest {

    private static final List<byte[]> ONE_DUMP = List.of(new byte[]{1, 2, 3});

    private ProjectConfig props(Path objects) {
        ProjectConfig p = new ProjectConfig();
        p.setCppSourceRoot("demo-service-cpp");
        if (objects != null) {
            p.setCppObjectsDir(objects.toString());
        }
        return p;
    }

    @Test
    void 没有C加加实例时不做任何事() throws Exception {
        assertEquals(0, new CppCoverageAnalyzer(new ProjectConfig(), new CoverageProperties()).analyze(List.of()).size());
    }

    @Test
    void 未配置源码根时拒绝出报告() {
        ProjectConfig p = new ProjectConfig();
        p.setCppObjectsDir("whatever");

        IOException e = assertThrows(IOException.class,
                () -> new CppCoverageAnalyzer(p, new CoverageProperties()).analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains("cpp-source-root"), e.getMessage());
    }

    @Test
    void 未配置对象目录时拒绝出报告() {
        IOException e = assertThrows(IOException.class,
                () -> new CppCoverageAnalyzer(props(null), new CoverageProperties()).analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains("cpp-objects-dir"), e.getMessage());
    }

    /**
     * .gcno 是编译期产物，与探针是否健康无关。缺了它 gcov 解不出任何行号，
     * 而空结果与「这些代码没被跑过」在界面上一模一样，必须直接报错。
     */
    @Test
    void 对象目录里没有gcno时拒绝出报告(@TempDir Path empty) {
        IOException e = assertThrows(IOException.class,
                () -> new CppCoverageAnalyzer(props(empty), new CoverageProperties()).analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains(".gcno"), e.getMessage());
    }

    /**
     * 探针交回的字节流是自定义分帧的。截断了还照常解析的话，
     * 少掉的那个编译单元会整体消失，报告上表现为「这些代码没被跑过」。
     */
    @Test
    void 探针数据被截断时报错而不是当成没跑过(@TempDir Path objects) throws Exception {
        Files.writeString(objects.resolve("order.gcno"), "占位：本用例走不到 gcov 那一步");

        IOException e = assertThrows(IOException.class,
                () -> new CppCoverageAnalyzer(props(objects), new CoverageProperties()).analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains("截断"), e.getMessage());
    }

    /** gcov 的四种计数标记决定了四种染色，映射错了整张图就是错的 */
    @Test
    void gcov计数标记映射到四态染色() {
        CppCoverageAnalyzer a = new CppCoverageAnalyzer(new ProjectConfig(), new CoverageProperties());
        assertEquals("MISSED", a.status("#####"), "从未执行");
        assertEquals("MISSED", a.status("====="), "只能由异常路径到达，同样没跑过");
        assertEquals("PARTIAL", a.status("3*"), "跑过，但行内还有块没跑到");
        assertEquals("COVERED", a.status("3"), "全跑到了");
        assertEquals("MISSED", a.status("???"), "认不出来的标记按没跑过算 —— "
                + "把没跑过的说成跑过是这个平台最不能犯的错");
    }

    /**
     * gcov 加 -b -c 之后的真实输出片段。三处关键形态都在里面：
     * branch 行不带行号（跟在源码行之后）、(throw) 是编译器生成的异常路径、
     * function 行给出调用次数。
     */
    private static final String GCOV_WITH_BRANCHES = String.join("\n",
            "        -:    0:Source:order.cpp",
            "        -:    0:Graph:order.gcno",
            "function _ZN5Order3payEi called 3 returned 100% blocks executed 75%",
            "        3:   41:void Order::pay(int amount) {",
            "        3:   42:    if (amount > 0 && paid) {",
            "branch  0 taken 2 (fallthrough)",
            "branch  1 taken 0",
            "branch  2 never executed (throw)",
            "        2:   43:        settle();",
            "    #####:   44:    } else if (retry) {",
            "branch  0 never executed (fallthrough)",
            "branch  1 never executed",
            "        -:   45:",
            "function _ZN5Order6refundEv called 0 returned 0% blocks executed 0%",
            "    #####:   46:void Order::refund() {}");

    @Test
    void C加加的分支归到它前面那条源码行上() throws Exception {
        CppCoverageAnalyzer a = new CppCoverageAnalyzer(props(null), new CoverageProperties());

        Map<String, FileCoverage> r = a.parse(GCOV_WITH_BRANCHES, "demo-service-cpp");
        FileCoverage f = r.get("demo-service-cpp/order.cpp");
        assertNotNull(f, "没解析出文件：" + r.keySet());

        FileCoverage.LineCoverage l42 = f.lines().stream()
                .filter(l -> l.line() == 42).findFirst().orElseThrow();
        // gcov 的 branch 行不带行号，只能归到最近一条源码行上。归错的表现是
        // 「一条分支都没有」，与「这门语言不提供」长得一模一样
        assertEquals(1, l42.coveredBranches(), "taken 2 的那条算已覆盖");
        assertEquals(1, l42.missedBranches(), "taken 0 的那条算未覆盖；(throw) 那条不该计入");

        FileCoverage.LineCoverage l44 = f.lines().stream()
                .filter(l -> l.line() == 44).findFirst().orElseThrow();
        assertEquals(0, l44.coveredBranches());
        assertEquals(2, l44.missedBranches(), "两条 never executed 都算未覆盖");
    }

    /**
     * C++ 里每个可能抛异常的操作都会生成分支。实测一个 demo 有 359 条分支，
     * 其中 120 条是 (throw)，而源码里真正的条件语句只有 32 处。
     * 不滤掉的话，分支覆盖率报告的其实是异常处理路径的覆盖率。
     */
    @Test
    void 编译器生成的throw分支不计入() throws Exception {
        CppCoverageAnalyzer a = new CppCoverageAnalyzer(props(null), new CoverageProperties());

        FileCoverage f = a.parse(GCOV_WITH_BRANCHES, "demo-service-cpp")
                .get("demo-service-cpp/order.cpp");

        // 文本里一共 5 条 branch，其中 1 条是 (throw)
        assertEquals(4, f.coveredBranches() + f.missedBranches(),
                "5 条 branch 里应有 4 条计入，(throw) 那条要滤掉");
    }

    @Test
    void C加加的方法数来自function行的调用次数() throws Exception {
        CppCoverageAnalyzer a = new CppCoverageAnalyzer(props(null), new CoverageProperties());

        FileCoverage f = a.parse(GCOV_WITH_BRANCHES, "demo-service-cpp")
                .get("demo-service-cpp/order.cpp");

        assertEquals(1, f.coveredMethods(), "pay() called 3，算跑过");
        assertEquals(1, f.missedMethods(), "refund() called 0，算没跑过");
    }

    /**
     * 加了 -m 之后 gcov 给的是 demangled 名，形态与 mangled 完全不同：
     * 里面<b>带空格</b>（Order const&），而且 STL 类型会展开成一长串模板。
     * 这是真实输出的片段。
     */
    private static final String GCOV_DEMANGLED = String.join("\n",
            "        -:    0:Source:order.cpp",
            "function (anonymous namespace)::isFinalState(Order const&) called 0 returned 0% blocks executed 0%",
            "    #####:    5:bool isFinalState(const Order& o) {",
            "    #####:    6:    return o.status == \"PAID\";",
            "function Store::refund(std::__cxx11::basic_string<char, std::char_traits<char>, std::allocator<char> > const&, long long) called 3 returned 100% blocks executed 80%",
            "        3:   51:bool Store::refund(const std::string& bizNo, long long amount) {",
            "        3:   52:    return true;",
            "        -:   53:}");

    @Test
    void 带空格的demangled函数名也要解析出来() throws Exception {
        CppCoverageAnalyzer a = new CppCoverageAnalyzer(props(null), new CoverageProperties());

        FileCoverage f = a.parse(GCOV_DEMANGLED, "demo-service-cpp").get("demo-service-cpp/order.cpp");
        assertNotNull(f, "没解析出文件");
        // 用「非空白串」匹配函数名的话，isFinalState(Order const&) 因为带空格会被丢掉，
        // 只剩下无参的那些 —— 页面显示「1/1」，比全丢更隐蔽
        assertEquals(2, f.coveredMethods() + f.missedMethods(),
                "带空格的 demangled 名没被认出来，方法数只剩 " + (f.coveredMethods() + f.missedMethods()));
        assertEquals(1, f.coveredMethods(), "refund called 3，算跑过");
    }

    @Test
    void 方法首行号取function行之后的第一条源码行() throws Exception {
        CppCoverageAnalyzer a = new CppCoverageAnalyzer(props(null), new CoverageProperties());

        FileCoverage f = a.parse(GCOV_DEMANGLED, "demo-service-cpp").get("demo-service-cpp/order.cpp");
        assertNotNull(f.methods(), "C++ 拿得到方法明细");
        // gcov 的 function 行不带行号，实测确认它紧贴函数定义行之前
        FileCoverage.MethodCoverage refund = f.methods().stream()
                .filter(m -> m.name().startsWith("Store::refund")).findFirst().orElseThrow();
        assertEquals(51, refund.firstLine(), "首行号该取 function 行之后的第一条源码行");
        assertEquals(2, refund.coveredLines(), "函数范围内的源码行要累计到它名下");
    }

    /**
     * STL 类型在 demangled 名里会展开成一长串模板，摆进报表会把表格撑爆。
     * std::string 是其中最常见的一个，缩回去。
     */
    @Test
    void 常见的STL模板缩写成短名() throws Exception {
        CppCoverageAnalyzer a = new CppCoverageAnalyzer(props(null), new CoverageProperties());

        FileCoverage f = a.parse(GCOV_DEMANGLED, "demo-service-cpp").get("demo-service-cpp/order.cpp");
        String refund = f.methods().stream()
                .filter(m -> m.name().startsWith("Store::refund")).findFirst().orElseThrow().name();

        assertFalse(refund.contains("basic_string"),
                "basic_string 的完整模板没缩写，报表里这一列会撑爆：" + refund);
        assertEquals("Store::refund(std::string const&, long long)", refund);
    }
}
