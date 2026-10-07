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
 * C++ 归一化里「宁可不出报告」的那几条路径，以及 gcov JSON 输出的解析。
 *
 * 真正跑通一次 C++ 采集（--coverage 构建 → __gcov_dump → gcov → 行级染色）依赖
 * 真实服务，由 scripts/e2e_cpp.py 端到端验证（含「源码正被改写时报告不变」那条）；
 * 这里守住的是拿不到有效数据时的行为 —— 它最容易被漏掉，因为出错的形态是
 * 「C++ 少了一个文件」，与「C++ 这个文件没被调用过」在界面上长得完全一样。
 *
 * 下面的 JSON 片段取自 {@code gcov --json-format -t -r -b -c -m} 的真实输出，只留了解析用得到的字段。
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

    private CppCoverageAnalyzer analyzer() {
        return new CppCoverageAnalyzer(props(null), new CoverageProperties());
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

    /*
     * 下面几条喂的是把真实的 order.gcno 截断后 gcov 16.2 实际给出的东西（退出码、stdout、stderr）。
     * 少了的那个编译单元，它的源码会从报告里整个消失 —— 看上去像是项目里没有这个文件，没人会怀疑是平台丢了它
     */
    private static final List<String> UNITS = List.of("main.gcno", "order.gcno");
    private static final java.util.Set<String> BOTH_HAVE_DATA = java.util.Set.of("main.gcno", "order.gcno");
    private static final String MAIN_DOC = "{\"format_version\":\"2\",\"gcc_version\":\"16.2.0\",\"data_file\":\"main.gcno\","
            + "\"files\":[{\"file\":\"main.cpp\",\"functions\":[],\"lines\":[{\"line_number\":1,\"count\":1,"
            + "\"unexecuted_block\":false,\"branches\":[]}]}]}";

    /** 截在文件头里：gcov 段错误，stderr 一个字都没有，order.gcno 那份 JSON 没回来 */
    @Test
    void gcov崩在某个编译单元上时整轮拒绝出报告并点名是哪个() {
        IOException e = assertThrows(IOException.class, () -> CppCoverageAnalyzer.requireAllUnits(
                new CppCoverageAnalyzer.Run(139, MAIN_DOC + "\n", ""), UNITS, BOTH_HAVE_DATA));
        assertTrue(e.getMessage().contains("order.gcno"), "要点名停在哪个编译单元上：" + e.getMessage());
        assertFalse(e.getMessage().contains("main.gcno"), "main.gcno 有结果，不该被点名：" + e.getMessage());
    }

    /** 崩在半路时最后一份可能只写了一半 —— 读不了的那份当作没回来，而不是让解析异常盖掉点名 */
    @Test
    void 只写了一半的那份JSON算没回来() {
        String half = "{\"format_version\":\"2\",\"data_file\":\"order.gcno\",\"files\":[{\"file\":\"ord";
        IOException e = assertThrows(IOException.class, () -> CppCoverageAnalyzer.requireAllUnits(
                new CppCoverageAnalyzer.Run(139, MAIN_DOC + "\n" + half, ""), UNITS, BOTH_HAVE_DATA));
        assertTrue(e.getMessage().contains("order.gcno"), e.getMessage());
    }

    /** 截在第一个函数记录之前：gcov <b>退出 0</b>，那份 JSON 照常回来、一个文件都没有，只在 stderr 说一句 */
    @Test
    void 有覆盖数据却读不出函数的编译单元整轮拒绝出报告() {
        String err = "C:/x/order.gcno:no functions found\n";
        IOException e = assertThrows(IOException.class, () -> CppCoverageAnalyzer.requireAllUnits(
                new CppCoverageAnalyzer.Run(0, MAIN_DOC + "\n{\"data_file\":\"order.gcno\",\"files\":[]}\n", err),
                UNITS, BOTH_HAVE_DATA));
        assertTrue(e.getMessage().contains("order.gcno"), e.getMessage());
        assertTrue(e.getMessage().contains("读不出任何函数"), "要说清是哪种坏法：" + e.getMessage());
    }

    /** 真没有函数的编译单元（只有声明、常量）gcov 也说 no functions found，但它编不出计数器、没有 .gcda —— 不能误伤 */
    @Test
    void 真没有函数的编译单元照常出报告() throws Exception {
        String err = "C:/x/empty.gcno:no functions found\nC:/x/empty.gcda:cannot open data file, assuming not executed\n";
        CppCoverageAnalyzer.requireAllUnits(
                new CppCoverageAnalyzer.Run(0, MAIN_DOC + "\n{\"data_file\":\"empty.gcno\",\"files\":[]}\n", err),
                List.of("main.gcno", "empty.gcno"), java.util.Set.of("main.gcno"));
    }

    /**
     * 数据目录里留着旧构建的 .gcda、而这个编译单元的函数在新构建里已经全挪走了：gcov 同样说 no functions found，
     * 但还会报 stamp mismatch、以 5 退出。这一轮照样不出报告，原因得是 gcov 说的那个，不能说成 .gcno 被截断
     */
    @Test
    void 旧构建留下的gcda不说成gcno被截断() {
        String err = "C:/x/empty.gcno:no functions found\nC:/x/empty.gcda:stamp mismatch with notes file\n";
        IOException e = assertThrows(IOException.class, () -> CppCoverageAnalyzer.requireAllUnits(
                new CppCoverageAnalyzer.Run(5, MAIN_DOC + "\n{\"data_file\":\"empty.gcno\",\"files\":[]}\n", err),
                List.of("main.gcno", "empty.gcno"), java.util.Set.of("main.gcno", "empty.gcno")));
        assertTrue(e.getMessage().contains("stamp mismatch"), e.getMessage());
        assertFalse(e.getMessage().contains("截断"), "不是截断，别把人引去查产物：" + e.getMessage());
    }

    /** 每份都回来了、gcov 自己在 stderr 里点了名（not a gcov notes file、corrupted ……）：照原样报出来 */
    @Test
    void gcov自己点了名的失败原样报出来() {
        String err = "C:/x/order.gcno:not a gcov notes file\n";
        IOException e = assertThrows(IOException.class, () -> CppCoverageAnalyzer.requireAllUnits(
                new CppCoverageAnalyzer.Run(2, MAIN_DOC + "\n{\"data_file\":\"order.gcno\",\"files\":[]}\n", err),
                UNITS, BOTH_HAVE_DATA));
        assertTrue(e.getMessage().contains("exit 2") && e.getMessage().contains("order.gcno:not a gcov notes file"),
                e.getMessage());
    }

    /**
     * 每份 JSON 都会给 include 进来的头文件列一个条目，纯声明的头文件是一个空条目（实测 demo 的两份都带
     * {"file":"order.h","lines":[]}）。源码全被 -r 滤掉时剩下的就只有这些空条目 —— 要报错，不能交出一份空报告
     */
    @Test
    void 只剩空的头文件条目时拒绝出报告() {
        String json = "{\"data_file\":\"main.gcno\",\"files\":[{\"file\":\"order.h\",\"functions\":[],\"lines\":[]}]}";

        IOException e = assertThrows(IOException.class, () -> analyzer().parse(json, "demo-service-cpp"));
        assertTrue(e.getMessage().contains("没有输出任何源码的覆盖数据"), e.getMessage());
    }

    /** 计数与「行内有没有块没跑到」决定了四种染色，映射错了整张图就是错的 */
    @Test
    void 行计数映射到四态染色() throws Exception {
        String json = "{\"data_file\":\"order.gcno\",\"files\":[{\"file\":\"order.cpp\",\"functions\":[],\"lines\":["
                + "{\"line_number\":1,\"count\":0,\"unexecuted_block\":true,\"branches\":[]},"
                + "{\"line_number\":2,\"count\":0,\"unexecuted_block\":false,\"branches\":[]},"
                + "{\"line_number\":3,\"count\":3,\"unexecuted_block\":true,\"branches\":[]},"
                + "{\"line_number\":4,\"count\":3,\"unexecuted_block\":false,\"branches\":[]},"
                + "{\"line_number\":5,\"unexecuted_block\":false,\"branches\":[]}]}]}";

        Map<Integer, String> st = new java.util.HashMap<>();
        analyzer().parse(json, "demo-service-cpp").get("demo-service-cpp/order.cpp")
                .lines().forEach(l -> st.put(l.line(), l.status()));
        assertEquals("MISSED", st.get(1), "从未执行");
        assertEquals("MISSED", st.get(2), "计数为 0 就是没跑过（只能由异常路径到达的块也是 0）");
        assertEquals("PARTIAL", st.get(3), "跑过，但行内还有块没跑到");
        assertEquals("COVERED", st.get(4), "全跑到了");
        assertEquals("MISSED", st.get(5), "拿不到计数（换了版本、字段改了名）按没跑过算 —— 把没跑过的说成跑过是这个平台最不能犯的错");
    }

    /**
     * 分支挂在它所在的行上；(throw) 是编译器为可能抛异常的操作生成的路径。
     * 实测一个 demo 有 359 条分支，其中 120 条是 throw，而源码里真正的条件语句只有 32 处。
     */
    private static final String GCOV_WITH_BRANCHES = "{\"data_file\":\"order.gcno\",\"files\":[{\"file\":\"order.cpp\","
            + "\"functions\":["
            + "{\"name\":\"_ZN5Order3payEi\",\"demangled_name\":\"Order::pay(int)\",\"start_line\":41,\"end_line\":45,\"execution_count\":3},"
            + "{\"name\":\"_ZN5Order6refundEv\",\"demangled_name\":\"Order::refund()\",\"start_line\":46,\"end_line\":47,\"execution_count\":0}],"
            + "\"lines\":["
            + "{\"line_number\":41,\"count\":3,\"unexecuted_block\":false,\"function_name\":\"_ZN5Order3payEi\",\"branches\":[]},"
            + "{\"line_number\":42,\"count\":3,\"unexecuted_block\":true,\"function_name\":\"_ZN5Order3payEi\",\"branches\":["
            + "{\"count\":2,\"throw\":false,\"fallthrough\":true},"
            + "{\"count\":0,\"throw\":false,\"fallthrough\":false},"
            + "{\"count\":0,\"throw\":true,\"fallthrough\":false}]},"
            + "{\"line_number\":43,\"count\":2,\"unexecuted_block\":false,\"function_name\":\"_ZN5Order3payEi\",\"branches\":[]},"
            + "{\"line_number\":44,\"count\":0,\"unexecuted_block\":true,\"function_name\":\"_ZN5Order3payEi\",\"branches\":["
            + "{\"count\":0,\"throw\":false,\"fallthrough\":true},"
            + "{\"count\":0,\"throw\":false,\"fallthrough\":false}]},"
            + "{\"line_number\":46,\"count\":0,\"unexecuted_block\":true,\"function_name\":\"_ZN5Order6refundEv\",\"branches\":[]}]}]}";

    @Test
    void C加加的分支归到它所在的源码行上() throws Exception {
        Map<String, FileCoverage> r = analyzer().parse(GCOV_WITH_BRANCHES, "demo-service-cpp");
        FileCoverage f = r.get("demo-service-cpp/order.cpp");
        assertNotNull(f, "没解析出文件：" + r.keySet());

        FileCoverage.LineCoverage l42 = f.lines().stream()
                .filter(l -> l.line() == 42).findFirst().orElseThrow();
        // 归错的表现是「一条分支都没有」，与「这门语言不提供」长得一模一样
        assertEquals(1, l42.coveredBranches(), "执行过 2 次的那条算已覆盖");
        assertEquals(1, l42.missedBranches(), "0 次的那条算未覆盖；(throw) 那条不该计入");

        FileCoverage.LineCoverage l44 = f.lines().stream()
                .filter(l -> l.line() == 44).findFirst().orElseThrow();
        assertEquals(0, l44.coveredBranches());
        assertEquals(2, l44.missedBranches(), "两条没执行过的都算未覆盖");
    }

    /** 不滤掉的话，分支覆盖率报告的其实是异常处理路径的覆盖率 */
    @Test
    void 编译器生成的throw分支不计入() throws Exception {
        FileCoverage f = analyzer().parse(GCOV_WITH_BRANCHES, "demo-service-cpp")
                .get("demo-service-cpp/order.cpp");

        // 片段里一共 5 条分支，其中 1 条是 throw
        assertEquals(4, f.coveredBranches() + f.missedBranches(),
                "5 条分支里应有 4 条计入，throw 那条要滤掉");
    }

    @Test
    void C加加的方法数来自函数的调用次数() throws Exception {
        FileCoverage f = analyzer().parse(GCOV_WITH_BRANCHES, "demo-service-cpp")
                .get("demo-service-cpp/order.cpp");

        assertEquals(1, f.coveredMethods(), "pay() 调用 3 次，算跑过");
        assertEquals(1, f.missedMethods(), "refund() 调用 0 次，算没跑过");
    }

    /**
     * gcov 给的 demangled 名里<b>带空格</b>（Order const&），STL 类型还会展开成一长串模板。
     * refund 的起始行（50）故意不是可执行行，首行号要落到它之后的第一条可执行行上。
     */
    private static final String GCOV_DEMANGLED = "{\"data_file\":\"order.gcno\",\"files\":[{\"file\":\"order.cpp\","
            + "\"functions\":["
            + "{\"name\":\"_ZN12_GLOBAL__N_1L12isFinalStateERK5Order\","
            + "\"demangled_name\":\"(anonymous namespace)::isFinalState(Order const&)\",\"start_line\":5,\"end_line\":7,\"execution_count\":0},"
            + "{\"name\":\"_ZN5Store6refundERKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEEx\","
            + "\"demangled_name\":\"Store::refund(std::__cxx11::basic_string<char, std::char_traits<char>, "
            + "std::allocator<char> > const&, long long)\",\"start_line\":50,\"end_line\":53,\"execution_count\":3}],"
            + "\"lines\":["
            + "{\"line_number\":5,\"count\":0,\"unexecuted_block\":true,"
            + "\"function_name\":\"_ZN12_GLOBAL__N_1L12isFinalStateERK5Order\",\"branches\":[]},"
            + "{\"line_number\":6,\"count\":0,\"unexecuted_block\":true,"
            + "\"function_name\":\"_ZN12_GLOBAL__N_1L12isFinalStateERK5Order\",\"branches\":[]},"
            + "{\"line_number\":51,\"count\":3,\"unexecuted_block\":false,"
            + "\"function_name\":\"_ZN5Store6refundERKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEEx\",\"branches\":[]},"
            + "{\"line_number\":52,\"count\":3,\"unexecuted_block\":false,"
            + "\"function_name\":\"_ZN5Store6refundERKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEEx\",\"branches\":[]}]}]}";

    @Test
    void 带空格的demangled函数名也要解析出来() throws Exception {
        FileCoverage f = analyzer().parse(GCOV_DEMANGLED, "demo-service-cpp").get("demo-service-cpp/order.cpp");
        assertNotNull(f, "没解析出文件");
        assertEquals(2, f.coveredMethods() + f.missedMethods(),
                "方法数只剩 " + (f.coveredMethods() + f.missedMethods()));
        assertEquals(1, f.coveredMethods(), "refund 调用 3 次，算跑过");
    }

    @Test
    void 方法首行号取起始行起的第一条可执行行() throws Exception {
        FileCoverage f = analyzer().parse(GCOV_DEMANGLED, "demo-service-cpp").get("demo-service-cpp/order.cpp");
        assertNotNull(f.methods(), "C++ 拿得到方法明细");
        FileCoverage.MethodCoverage refund = f.methods().stream()
                .filter(m -> m.name().startsWith("Store::refund")).findFirst().orElseThrow();
        assertEquals(51, refund.firstLine(), "起始行 50 不是可执行行，首行号该取它之后的第一条可执行行");
        assertEquals(2, refund.coveredLines(), "函数范围内的可执行行要累计到它名下");
    }

    /**
     * STL 类型在 demangled 名里会展开成一长串模板，摆进报表会把表格撑爆。
     * std::string 是其中最常见的一个，缩回去。
     */
    @Test
    void 常见的STL模板缩写成短名() throws Exception {
        FileCoverage f = analyzer().parse(GCOV_DEMANGLED, "demo-service-cpp").get("demo-service-cpp/order.cpp");
        String refund = f.methods().stream()
                .filter(m -> m.name().startsWith("Store::refund")).findFirst().orElseThrow().name();

        assertFalse(refund.contains("basic_string"),
                "basic_string 的完整模板没缩写，报表里这一列会撑爆：" + refund);
        assertEquals("Store::refund(std::string const&, long long)", refund);
    }

    /*
     * 同一段源码编进好几个函数实例时按源码口径计数（openspec：cpp-normalization）。下面两段取自真实 gcov 16.2 的输出，
     * 只裁到解析用得到的字段：hx 实验里 a.cpp 调了 twice(1)、b.cpp 只引用不调用，链接器留 a 的那份；
     * tpl 实验里同一编译单元的 pick<int> 被调用、pick<long> 只被实例化
     */
    private static final String GCOV_INLINE_TWO_UNITS = String.join("\n",
            "{\"data_file\":\"a.gcno\",\"files\":[{\"file\":\"a.cpp\",\"functions\":[{\"name\":\"main\""
                + ",\"demangled_name\":\"main\",\"start_line\":3,\"end_line\":5,\"execution_count\":1}]"
                + ",\"lines\":[{\"line_number\":3,\"count\":1,\"unexecuted_block\":false,\"function_name\":\"main\""
                + ",\"branches\":[]},{\"line_number\":4,\"count\":1,\"unexecuted_block\":true,\"function_name\":\"main\""
                + ",\"branches\":[{\"count\":0,\"throw\":false},{\"count\":1,\"throw\":false}]}]},{\"file\":\"inl.h\""
                + ",\"functions\":[{\"name\":\"_Z5twicei\",\"demangled_name\":\"twice(int)\",\"start_line\":2"
                + ",\"end_line\":6,\"execution_count\":1}],\"lines\":[{\"line_number\":2,\"count\":1"
                + ",\"unexecuted_block\":false,\"function_name\":\"_Z5twicei\",\"branches\":[]},{\"line_number\":3"
                + ",\"count\":1,\"unexecuted_block\":false,\"function_name\":\"_Z5twicei\",\"branches\":[{\"count\":1"
                + ",\"throw\":false},{\"count\":0,\"throw\":false}]},{\"line_number\":4,\"count\":1"
                + ",\"unexecuted_block\":false,\"function_name\":\"_Z5twicei\",\"branches\":[]},{\"line_number\":5"
                + ",\"count\":0,\"unexecuted_block\":true,\"function_name\":\"_Z5twicei\",\"branches\":[]}]}]}",
            "{\"data_file\":\"b.gcno\",\"files\":[{\"file\":\"b.cpp\",\"functions\":[{\"name\":\"_Z8b_unusedi\""
                + ",\"demangled_name\":\"b_unused(int)\",\"start_line\":2,\"end_line\":2,\"execution_count\":0}"
                + ",{\"name\":\"_Z6b_usedv\",\"demangled_name\":\"b_used()\",\"start_line\":3,\"end_line\":3"
                + ",\"execution_count\":1}],\"lines\":[{\"line_number\":2,\"count\":0,\"unexecuted_block\":true"
                + ",\"function_name\":\"_Z8b_unusedi\",\"branches\":[]},{\"line_number\":3,\"count\":1"
                + ",\"unexecuted_block\":false,\"function_name\":\"_Z6b_usedv\",\"branches\":[]}]},{\"file\":\"inl.h\""
                + ",\"functions\":[{\"name\":\"_Z5twicei\",\"demangled_name\":\"twice(int)\",\"start_line\":2"
                + ",\"end_line\":6,\"execution_count\":0}],\"lines\":[{\"line_number\":2,\"count\":0"
                + ",\"unexecuted_block\":true,\"function_name\":\"_Z5twicei\",\"branches\":[]},{\"line_number\":3"
                + ",\"count\":0,\"unexecuted_block\":true,\"function_name\":\"_Z5twicei\",\"branches\":[{\"count\":0"
                + ",\"throw\":false},{\"count\":0,\"throw\":false}]},{\"line_number\":4,\"count\":0"
                + ",\"unexecuted_block\":true,\"function_name\":\"_Z5twicei\",\"branches\":[]},{\"line_number\":5"
                + ",\"count\":0,\"unexecuted_block\":true,\"function_name\":\"_Z5twicei\",\"branches\":[]}]}]}");

    private static final String GCOV_TEMPLATE_TWO_INSTANCES = String.join("\n",
            "{\"data_file\":\"tpl.gcno\",\"files\":[{\"file\":\"tpl.cpp\""
                + ",\"functions\":[{\"name\":\"_Z4pickIlET_S0_\",\"demangled_name\":\"long pick<long>(long)\""
                + ",\"start_line\":2,\"end_line\":6,\"execution_count\":0},{\"name\":\"_Z4pickIiET_S0_\""
                + ",\"demangled_name\":\"int pick<int>(int)\",\"start_line\":2,\"end_line\":6,\"execution_count\":1}"
                + ",{\"name\":\"main\",\"demangled_name\":\"main\",\"start_line\":7,\"end_line\":9,\"execution_count\":1}"
                + ",{\"name\":\"_Z5neverv\",\"demangled_name\":\"never()\",\"start_line\":10,\"end_line\":10"
                + ",\"execution_count\":0}],\"lines\":[{\"line_number\":2,\"count\":0,\"unexecuted_block\":true"
                + ",\"function_name\":\"_Z4pickIlET_S0_\",\"branches\":[]},{\"line_number\":3,\"count\":0"
                + ",\"unexecuted_block\":true,\"function_name\":\"_Z4pickIlET_S0_\",\"branches\":[{\"count\":0"
                + ",\"throw\":false},{\"count\":0,\"throw\":false}]},{\"line_number\":4,\"count\":0"
                + ",\"unexecuted_block\":true,\"function_name\":\"_Z4pickIlET_S0_\",\"branches\":[]},{\"line_number\":5"
                + ",\"count\":0,\"unexecuted_block\":true,\"function_name\":\"_Z4pickIlET_S0_\",\"branches\":[]}"
                + ",{\"line_number\":2,\"count\":1,\"unexecuted_block\":false,\"function_name\":\"_Z4pickIiET_S0_\""
                + ",\"branches\":[]},{\"line_number\":3,\"count\":1,\"unexecuted_block\":false"
                + ",\"function_name\":\"_Z4pickIiET_S0_\",\"branches\":[{\"count\":1,\"throw\":false},{\"count\":0"
                + ",\"throw\":false}]},{\"line_number\":4,\"count\":1,\"unexecuted_block\":false"
                + ",\"function_name\":\"_Z4pickIiET_S0_\",\"branches\":[]},{\"line_number\":5,\"count\":0"
                + ",\"unexecuted_block\":true,\"function_name\":\"_Z4pickIiET_S0_\",\"branches\":[]},{\"line_number\":7"
                + ",\"count\":1,\"unexecuted_block\":false,\"function_name\":\"main\",\"branches\":[]},{\"line_number\":8"
                + ",\"count\":1,\"unexecuted_block\":true,\"function_name\":\"main\",\"branches\":[{\"count\":0"
                + ",\"throw\":false},{\"count\":1,\"throw\":false}]},{\"line_number\":10,\"count\":0"
                + ",\"unexecuted_block\":true,\"function_name\":\"_Z5neverv\",\"branches\":[]}]}]}");

    private static Map<Integer, FileCoverage.LineCoverage> byLine(FileCoverage f) {
        Map<Integer, FileCoverage.LineCoverage> m = new java.util.HashMap<>();
        f.lines().forEach(l -> m.put(l.line(), l));
        return m;
    }

    private static FileCoverage.MethodCoverage method(FileCoverage f, String name) {
        return f.methods().stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("方法明细里没有 " + name + "：" + f.methods()));
    }

    /**
     * 被两个编译单元 include 的内联函数：链接器只留 a 的那份，b 的副本计数全是 0、每行都标着「有块没跑到」。
     * 按 gcov 文本格式汇总段的口径合（计数相加、取或、分支并列），跑全了的行永远是部分覆盖、分支翻倍、
     * 同一个函数既算跑过又算没跑过
     */
    @Test
    void 被几个编译单元引用的内联函数按源码只计一次() throws Exception {
        FileCoverage h = analyzer().parse(GCOV_INLINE_TWO_UNITS, "demo-service-cpp").get("demo-service-cpp/inl.h");
        assertNotNull(h, "头文件没解析出来");
        Map<Integer, FileCoverage.LineCoverage> by = byLine(h);
        assertEquals(4, h.lines().size(), "同一行只能出现一次：" + h.lines());
        assertEquals("COVERED", by.get(2).status(), "跑过的那份里这一行没有没跑到的块 —— 零计数的副本不能把它拉成部分覆盖");
        assertEquals("COVERED", by.get(3).status());
        assertEquals("COVERED", by.get(4).status());
        assertEquals("MISSED", by.get(5).status(), "return 0 确实没跑过");
        assertEquals(1, by.get(3).coveredBranches(), "x > 0 走了真分支");
        assertEquals(1, by.get(3).missedBranches(), "源码里这一行只有 2 个分支，零计数副本的 2 个不能再加进来");
        assertEquals(1, h.coveredMethods(), "同一个函数只算一个，且算跑过");
        assertEquals(0, h.missedMethods(), "零计数的副本不能变成一个「没跑过的方法」");
        assertEquals(1, h.methods().size(), "方法明细里只出现一次：" + h.methods());
        FileCoverage.MethodCoverage m = method(h, "twice(int)");
        assertEquals(2, m.firstLine());
        assertEquals(3, m.coveredLines());
        assertEquals(1, m.missedLines());
    }

    /**
     * 同一编译单元里模板实例化了两次：pick<int> 被调用，pick<long> 只被实例化。同一个行号在 JSON 里出现两次、
     * 各带自己的所属函数。不同实例是各自的机器码：pick<long> 在这些行上没跑过，行就只算部分覆盖，分支并列；
     * 方法按实例分列，各看各的行
     */
    @Test
    void 实例化了几次的模板行按源码合并方法按实例分列() throws Exception {
        FileCoverage f = analyzer().parse(GCOV_TEMPLATE_TWO_INSTANCES, "demo-service-cpp")
                .get("demo-service-cpp/tpl.cpp");
        Map<Integer, FileCoverage.LineCoverage> by = byLine(f);
        assertEquals("PARTIAL", by.get(2).status(), "pick<long> 在这一行上从没跑过 —— 不能说成跑全了");
        assertEquals("PARTIAL", by.get(4).status(), "pick<int> 跑过 return x，pick<long> 没跑过");
        assertEquals("MISSED", by.get(5).status());
        assertEquals(1, by.get(3).coveredBranches(), "pick<int> 走了真分支");
        assertEquals(3, by.get(3).missedBranches(), "不同实例的分支并列：pick<long> 的 2 条加上 pick<int> 没走的 1 条");
        assertEquals(2, f.coveredMethods(), "pick<int> 与 main");
        assertEquals(2, f.missedMethods(), "pick<long> 与 never() —— 模板的实例各算一个方法");
        FileCoverage.MethodCoverage pl = method(f, "long pick<long>(long)");
        FileCoverage.MethodCoverage pi = method(f, "int pick<int>(int)");
        assertEquals(2, pl.firstLine());
        assertEquals(0, pl.coveredLines(), "pick<long> 从没跑过，它自己的行全部未覆盖");
        assertEquals(4, pl.missedLines());
        assertEquals(2, pi.firstLine());
        assertEquals(3, pi.coveredLines());
        assertEquals(1, pi.missedLines());
    }

    /** lam 实验（真实 gcov 16.2 输出）：outer 里定义了一个 lambda，lambda 结束后 outer 还有 4 行 */
    private static final String GCOV_LAMBDA = String.join("\n",
            "{\"data_file\":\"lam.gcno\",\"files\":[{\"file\":\"lam.cpp\",\"functions\":[{\"name\":\"_Z7computev\""
                + ",\"demangled_name\":\"compute()\",\"start_line\":1,\"end_line\":1,\"execution_count\":1}"
                + ",{\"name\":\"_Z5outeri\",\"demangled_name\":\"outer(int)\",\"start_line\":3,\"end_line\":13"
                + ",\"execution_count\":1},{\"name\":\"_ZZ5outeriENKUliE_clEi\""
                + ",\"demangled_name\":\"outer(int)::{lambda(int)#1}::operator()(int) const\",\"start_line\":4"
                + ",\"end_line\":8,\"execution_count\":1},{\"name\":\"main\",\"demangled_name\":\"main\",\"start_line\":14"
                + ",\"end_line\":14,\"execution_count\":1}],\"lines\":[{\"line_number\":1,\"count\":1"
                + ",\"unexecuted_block\":false,\"function_name\":\"_Z7computev\",\"branches\":[]},{\"line_number\":3"
                + ",\"count\":1,\"unexecuted_block\":false,\"function_name\":\"_Z5outeri\",\"branches\":[]}"
                + ",{\"line_number\":4,\"count\":1,\"unexecuted_block\":false,\"function_name\":\"_ZZ5outeriENKUliE_clEi\""
                + ",\"branches\":[]},{\"line_number\":5,\"count\":1,\"unexecuted_block\":false"
                + ",\"function_name\":\"_ZZ5outeriENKUliE_clEi\",\"branches\":[{\"count\":1,\"throw\":false},{\"count\":0"
                + ",\"throw\":false}]},{\"line_number\":6,\"count\":1,\"unexecuted_block\":false"
                + ",\"function_name\":\"_ZZ5outeriENKUliE_clEi\",\"branches\":[]},{\"line_number\":7,\"count\":0"
                + ",\"unexecuted_block\":true,\"function_name\":\"_ZZ5outeriENKUliE_clEi\",\"branches\":[]}"
                + ",{\"line_number\":9,\"count\":1,\"unexecuted_block\":false,\"function_name\":\"_Z5outeri\""
                + ",\"branches\":[]},{\"line_number\":10,\"count\":1,\"unexecuted_block\":false"
                + ",\"function_name\":\"_Z5outeri\",\"branches\":[{\"count\":0,\"throw\":false},{\"count\":1"
                + ",\"throw\":false}]},{\"line_number\":11,\"count\":0,\"unexecuted_block\":true"
                + ",\"function_name\":\"_Z5outeri\",\"branches\":[]},{\"line_number\":12,\"count\":1"
                + ",\"unexecuted_block\":false,\"function_name\":\"_Z5outeri\",\"branches\":[]},{\"line_number\":14"
                + ",\"count\":1,\"unexecuted_block\":false,\"function_name\":\"main\",\"branches\":[]}]}]}");

    /**
     * lambda 定义在 outer 里：lambda 体内的行归 lambda，lambda 之后的行回到 outer。
     * 按「起始行到下一个函数起始行」近似的话，outer 只剩签名那一行，lambda 把 outer 的后半段全算走了
     */
    @Test
    void lambda体内的行只算lambda之后的行算回外层函数() throws Exception {
        FileCoverage f = analyzer().parse(GCOV_LAMBDA, "demo-service-cpp").get("demo-service-cpp/lam.cpp");
        FileCoverage.MethodCoverage outer = method(f, "outer(int)");
        FileCoverage.MethodCoverage lambda = method(f, "outer(int)::{lambda(int)#1}::operator()(int) const");
        assertEquals(3, outer.firstLine(), "外层函数的首行是它自己的签名行");
        assertEquals(4, outer.coveredLines(), "3、9、10、12 行");
        assertEquals(1, outer.missedLines(), "11 行 r = 100 没跑过");
        assertEquals(4, lambda.firstLine());
        assertEquals(3, lambda.coveredLines(), "4、5、6 行");
        assertEquals(1, lambda.missedLines(), "7 行 return v 没跑过");
    }

    /** twol 实验（真实 gcov 16.2 输出）：第 2 行定义了两个 lambda，第一个调了两次、走遍两个分支，第二个从没调用 */
    private static final String GCOV_TWO_LAMBDAS_ONE_LINE = String.join("\n",
            "{\"data_file\":\"twol.gcno\",\"files\":[{\"file\":\"twol.cpp\",\"functions\":[{\"name\":\"main\""
                + ",\"demangled_name\":\"main\",\"start_line\":1,\"end_line\":4,\"execution_count\":1}"
                + ",{\"name\":\"_ZZ4mainENKUliE_clEi\",\"demangled_name\":\"main::{lambda(int)#1}::operator()(int) const\""
                + ",\"start_line\":2,\"end_line\":2,\"execution_count\":2},{\"name\":\"_ZZ4mainENKUliE0_clEi\""
                + ",\"demangled_name\":\"main::{lambda(int)#2}::operator()(int) const\",\"start_line\":2,\"end_line\":2"
                + ",\"execution_count\":0}],\"lines\":[{\"line_number\":1,\"count\":1,\"unexecuted_block\":false"
                + ",\"function_name\":\"main\",\"branches\":[]},{\"line_number\":2,\"count\":2,\"unexecuted_block\":false"
                + ",\"function_name\":\"_ZZ4mainENKUliE_clEi\",\"branches\":[{\"count\":1,\"throw\":false},{\"count\":1"
                + ",\"throw\":false}]},{\"line_number\":2,\"count\":0,\"unexecuted_block\":true"
                + ",\"function_name\":\"_ZZ4mainENKUliE0_clEi\",\"branches\":[{\"count\":0,\"throw\":false},{\"count\":0"
                + ",\"throw\":false},{\"count\":0,\"throw\":false},{\"count\":0,\"throw\":false}]},{\"line_number\":3"
                + ",\"count\":1,\"unexecuted_block\":true,\"function_name\":\"main\",\"branches\":[{\"count\":0"
                + ",\"throw\":false},{\"count\":1,\"throw\":false}]}]}]}");

    /**
     * 同一行的两个 lambda：gcov 把起始行相同的函数分成一组、各输出一遍这一行。第一个跑全了这一行，第二个从没调用 ——
     * 只看跑过的实例的话这一行会被判成跑全了，可第二个 lambda 在这一行上的代码从没跑过（第二轮代码评审指出）
     */
    @Test
    void 同一行的两个lambda只调用了一个时这一行是部分覆盖() throws Exception {
        FileCoverage f = analyzer().parse(GCOV_TWO_LAMBDAS_ONE_LINE, "demo-service-cpp").get("demo-service-cpp/twol.cpp");
        FileCoverage.LineCoverage l2 = byLine(f).get(2);
        assertEquals("PARTIAL", l2.status(), "第二个 lambda 从没跑过，这一行不能算跑全了");
        assertEquals(2, l2.coveredBranches(), "第一个 lambda 的两个分支都走过");
        assertEquals(4, l2.missedBranches(), "第二个 lambda 的 4 个分支并列计入，不能按位置叠到第一个头上");
        assertEquals(2, f.coveredMethods(), "main 与第一个 lambda");
        assertEquals(1, f.missedMethods(), "第二个 lambda");
        assertEquals(0, method(f, "main::{lambda(int)#2}::operator()(int) const").coveredLines());
    }

    /** stc 实验（真实 gcov 16.2 输出）：sh.h 的 static 函数 clamp01，c1 以 -1 调、c2 以 5 调，两份副本修饰名相同、都跑过 */
    private static final String GCOV_STATIC_TWO_COPIES = String.join("\n",
            "{\"data_file\":\"c1.gcno\",\"files\":[{\"file\":\"c1.cpp\",\"functions\":[{\"name\":\"_Z2f1v\""
                + ",\"demangled_name\":\"f1()\",\"start_line\":2,\"end_line\":2,\"execution_count\":1}]"
                + ",\"lines\":[{\"line_number\":2,\"count\":1,\"unexecuted_block\":false,\"function_name\":\"_Z2f1v\""
                + ",\"branches\":[]}]},{\"file\":\"sh.h\",\"functions\":[{\"name\":\"_ZL7clamp01i\""
                + ",\"demangled_name\":\"clamp01(int)\",\"start_line\":2,\"end_line\":6,\"execution_count\":1}]"
                + ",\"lines\":[{\"line_number\":2,\"count\":1,\"unexecuted_block\":false,\"function_name\":\"_ZL7clamp01i\""
                + ",\"branches\":[]},{\"line_number\":3,\"count\":1,\"unexecuted_block\":false"
                + ",\"function_name\":\"_ZL7clamp01i\",\"branches\":[{\"count\":1,\"throw\":false},{\"count\":0"
                + ",\"throw\":false}]},{\"line_number\":4,\"count\":0,\"unexecuted_block\":true"
                + ",\"function_name\":\"_ZL7clamp01i\",\"branches\":[{\"count\":0,\"throw\":false},{\"count\":0"
                + ",\"throw\":false}]},{\"line_number\":5,\"count\":0,\"unexecuted_block\":true"
                + ",\"function_name\":\"_ZL7clamp01i\",\"branches\":[]}]}]}",
            "{\"data_file\":\"c2.gcno\",\"files\":[{\"file\":\"c2.cpp\",\"functions\":[{\"name\":\"main\""
                + ",\"demangled_name\":\"main\",\"start_line\":3,\"end_line\":3,\"execution_count\":1}]"
                + ",\"lines\":[{\"line_number\":3,\"count\":1,\"unexecuted_block\":true,\"function_name\":\"main\""
                + ",\"branches\":[{\"count\":0,\"throw\":false},{\"count\":1,\"throw\":false}]}]},{\"file\":\"sh.h\""
                + ",\"functions\":[{\"name\":\"_ZL7clamp01i\",\"demangled_name\":\"clamp01(int)\",\"start_line\":2"
                + ",\"end_line\":6,\"execution_count\":1}],\"lines\":[{\"line_number\":2,\"count\":1"
                + ",\"unexecuted_block\":false,\"function_name\":\"_ZL7clamp01i\",\"branches\":[]},{\"line_number\":3"
                + ",\"count\":1,\"unexecuted_block\":true,\"function_name\":\"_ZL7clamp01i\",\"branches\":[{\"count\":0"
                + ",\"throw\":false},{\"count\":1,\"throw\":false}]},{\"line_number\":4,\"count\":1"
                + ",\"unexecuted_block\":false,\"function_name\":\"_ZL7clamp01i\",\"branches\":[{\"count\":1"
                + ",\"throw\":false},{\"count\":0,\"throw\":false}]},{\"line_number\":5,\"count\":0"
                + ",\"unexecuted_block\":true,\"function_name\":\"_ZL7clamp01i\",\"branches\":[]}]}]}");

    /**
     * 同一个函数的两份副本都跑过：c1 那份走进了第 3 行 if 的条件体，c2 那份没走进（有块没跑到）。
     * 副本按位置合并：第 3 行有一份里有块没跑到，算部分覆盖；两份各走了一个方向，分支合起来都走过
     */
    @Test
    void 同一个函数的两份副本都跑过时按位置合并() throws Exception {
        FileCoverage h = analyzer().parse(GCOV_STATIC_TWO_COPIES, "demo-service-cpp").get("demo-service-cpp/sh.h");
        Map<Integer, FileCoverage.LineCoverage> by = byLine(h);
        assertEquals("PARTIAL", by.get(3).status(), "c2 那份没走进条件体");
        assertEquals(2, by.get(3).coveredBranches(), "c1 走了真分支、c2 走了假分支");
        assertEquals(0, by.get(3).missedBranches());
        assertEquals("COVERED", by.get(4).status(), "c2 那份跑全了这一行；c1 那份没跑到这里，同一函数的零计数副本不参与");
        assertEquals("MISSED", by.get(5).status());
        assertEquals(1, h.coveredMethods(), "同一个函数只算一个");
        assertEquals(0, h.missedMethods());
    }

    /**
     * lam 实验的输出按 GCC 9、10 的标法改写：那两版的 gcov 给每行标的是「最后一个开始的函数」，没有嵌套栈，
     * lambda 结束之后的 9–12 行也标成了 lambda（按 GCC 9 源码推出的取法 —— 本机没有 GCC 9 可实跑）
     */
    private static final String GCOV_LAMBDA_GCC9 = String.join("\n",
            "{\"data_file\":\"lam.gcno\",\"files\":[{\"file\":\"lam.cpp\",\"functions\":[{\"name\":\"_Z7computev\""
                + ",\"demangled_name\":\"compute()\",\"start_line\":1,\"end_line\":1,\"execution_count\":1}"
                + ",{\"name\":\"_Z5outeri\",\"demangled_name\":\"outer(int)\",\"start_line\":3,\"end_line\":13"
                + ",\"execution_count\":1},{\"name\":\"_ZZ5outeriENKUliE_clEi\""
                + ",\"demangled_name\":\"outer(int)::{lambda(int)#1}::operator()(int) const\",\"start_line\":4"
                + ",\"end_line\":8,\"execution_count\":1},{\"name\":\"main\",\"demangled_name\":\"main\",\"start_line\":14"
                + ",\"end_line\":14,\"execution_count\":1}],\"lines\":[{\"line_number\":1,\"count\":1"
                + ",\"unexecuted_block\":false,\"function_name\":\"_Z7computev\",\"branches\":[]},{\"line_number\":3"
                + ",\"count\":1,\"unexecuted_block\":false,\"function_name\":\"_Z5outeri\",\"branches\":[]}"
                + ",{\"line_number\":4,\"count\":1,\"unexecuted_block\":false,\"function_name\":\"_ZZ5outeriENKUliE_clEi\""
                + ",\"branches\":[]},{\"line_number\":5,\"count\":1,\"unexecuted_block\":false"
                + ",\"function_name\":\"_ZZ5outeriENKUliE_clEi\",\"branches\":[{\"count\":1,\"throw\":false},{\"count\":0"
                + ",\"throw\":false}]},{\"line_number\":6,\"count\":1,\"unexecuted_block\":false"
                + ",\"function_name\":\"_ZZ5outeriENKUliE_clEi\",\"branches\":[]},{\"line_number\":7,\"count\":0"
                + ",\"unexecuted_block\":true,\"function_name\":\"_ZZ5outeriENKUliE_clEi\",\"branches\":[]}"
                + ",{\"line_number\":9,\"count\":1,\"unexecuted_block\":false,\"function_name\":\"_ZZ5outeriENKUliE_clEi\""
                + ",\"branches\":[]},{\"line_number\":10,\"count\":1,\"unexecuted_block\":false"
                + ",\"function_name\":\"_ZZ5outeriENKUliE_clEi\",\"branches\":[{\"count\":0,\"throw\":false},{\"count\":1"
                + ",\"throw\":false}]},{\"line_number\":11,\"count\":0,\"unexecuted_block\":true"
                + ",\"function_name\":\"_ZZ5outeriENKUliE_clEi\",\"branches\":[]},{\"line_number\":12,\"count\":1"
                + ",\"unexecuted_block\":false,\"function_name\":\"_ZZ5outeriENKUliE_clEi\",\"branches\":[]}"
                + ",{\"line_number\":14,\"count\":1,\"unexecuted_block\":false,\"function_name\":\"main\""
                + ",\"branches\":[]}]}]}");

    /** 所属函数标错时（标成了范围不包含这一行的函数），按函数的起止行归给包含它的最内层函数 */
    @Test
    void 所属函数标错时按范围归给最内层的函数() throws Exception {
        FileCoverage f = analyzer().parse(GCOV_LAMBDA_GCC9, "demo-service-cpp").get("demo-service-cpp/lam.cpp");
        FileCoverage.MethodCoverage outer = method(f, "outer(int)");
        FileCoverage.MethodCoverage lambda = method(f, "outer(int)::{lambda(int)#1}::operator()(int) const");
        assertEquals(4, outer.coveredLines(), "9、10、12 行虽然标成了 lambda，仍是 outer 的（加上签名行 3）");
        assertEquals(1, outer.missedLines(), "11 行");
        assertEquals(3, lambda.coveredLines(), "lambda 只有它自己范围里的 4、5、6 行");
        assertEquals(1, lambda.missedLines(), "7 行");
    }
}
