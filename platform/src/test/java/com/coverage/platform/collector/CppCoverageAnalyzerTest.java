package com.coverage.platform.collector;

import com.coverage.platform.config.CoverageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    private CoverageProperties props(Path objects) {
        CoverageProperties p = new CoverageProperties();
        p.setCppSourceRoot("demo-service-cpp");
        if (objects != null) {
            p.setCppObjectsDir(objects.toString());
        }
        return p;
    }

    @Test
    void 没有C加加实例时不做任何事() throws Exception {
        assertEquals(0, new CppCoverageAnalyzer(new CoverageProperties()).analyze(List.of()).size());
    }

    @Test
    void 未配置源码根时拒绝出报告() {
        CoverageProperties p = new CoverageProperties();
        p.setCppObjectsDir("whatever");

        IOException e = assertThrows(IOException.class,
                () -> new CppCoverageAnalyzer(p).analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains("cpp-source-root"), e.getMessage());
    }

    @Test
    void 未配置对象目录时拒绝出报告() {
        IOException e = assertThrows(IOException.class,
                () -> new CppCoverageAnalyzer(props(null)).analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains("cpp-objects-dir"), e.getMessage());
    }

    /**
     * .gcno 是编译期产物，与探针是否健康无关。缺了它 gcov 解不出任何行号，
     * 而空结果与「这些代码没被跑过」在界面上一模一样，必须直接报错。
     */
    @Test
    void 对象目录里没有gcno时拒绝出报告(@TempDir Path empty) {
        IOException e = assertThrows(IOException.class,
                () -> new CppCoverageAnalyzer(props(empty)).analyze(ONE_DUMP));
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
                () -> new CppCoverageAnalyzer(props(objects)).analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains("截断"), e.getMessage());
    }

    /** gcov 的四种计数标记决定了四种染色，映射错了整张图就是错的 */
    @Test
    void gcov计数标记映射到四态染色() {
        CppCoverageAnalyzer a = new CppCoverageAnalyzer(new CoverageProperties());
        assertEquals("MISSED", a.status("#####"), "从未执行");
        assertEquals("MISSED", a.status("====="), "只能由异常路径到达，同样没跑过");
        assertEquals("PARTIAL", a.status("3*"), "跑过，但行内还有块没跑到");
        assertEquals("COVERED", a.status("3"), "全跑到了");
    }
}
