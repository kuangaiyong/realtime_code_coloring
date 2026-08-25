package com.rtcc.platform.collector;

import com.rtcc.platform.config.CoverageProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

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
        assertEquals(0, new GoCoverageAnalyzer(new CoverageProperties()).analyze(List.of()).size());
    }

    @Test
    void 未配置模块路径时拒绝出报告而不是返回空结果() {
        CoverageProperties props = new CoverageProperties();
        props.setGoSourceRoot("demo-service-go");
        // go-module-path 缺失 → profile 里的 import path 一个也换算不成仓库相对路径

        IOException e = assertThrows(IOException.class,
                () -> new GoCoverageAnalyzer(props).analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains("go-module-path"), e.getMessage());
    }

    @Test
    void 未配置源码根时同样拒绝出报告() {
        CoverageProperties props = new CoverageProperties();
        props.setGoModulePath("example.com/demo");

        IOException e = assertThrows(IOException.class,
                () -> new GoCoverageAnalyzer(props).analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains("go-source-root"), e.getMessage());
    }
}
