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
 * Rust 归一化里「宁可不出报告」的那几条路径，以及 LCOV 的解析口径。
 *
 * 真正跑通一次 Rust 采集（-C instrument-coverage 构建 → __llvm_profile_write_file →
 * llvm-profdata/llvm-cov → 行级染色）依赖真实服务，由 scripts/e2e_rust.py 端到端验证；
 * 这里守住的是拿不到有效数据时的行为 —— 它最容易被漏掉，因为出错的形态是
 * 「Rust 一个文件都没有」，与「Rust 代码没被调用过」在界面上长得完全一样。
 */
class RustCoverageAnalyzerTest {

    private static final List<byte[]> ONE_DUMP = List.of(new byte[]{1, 2, 3});

    private ProjectConfig props(Path repo, String binary) {
        ProjectConfig p = new ProjectConfig();
        p.setRepoDir(repo.toString());
        p.setRustSourceRoot("demo-service-rust");
        p.setRustBinary(binary);
        return p;
    }

    @Test
    void 没有Rust实例时不做任何事() throws Exception {
        assertEquals(0, new RustCoverageAnalyzer(new ProjectConfig(), new CoverageProperties()).analyze(List.of()).size());
    }

    @Test
    void 未配置源码根时拒绝出报告() {
        ProjectConfig p = new ProjectConfig();
        p.setRustBinary("whatever.exe");

        IOException e = assertThrows(IOException.class,
                () -> new RustCoverageAnalyzer(p, new CoverageProperties()).analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains("rust-source-root"), e.getMessage());
    }

    @Test
    void 未配置产物时拒绝出报告(@TempDir Path repo) {
        IOException e = assertThrows(IOException.class,
                () -> new RustCoverageAnalyzer(props(repo, null), new CoverageProperties()).analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains("rust-binary"), e.getMessage());
    }

    /**
     * 行号信息全在产物自带的 coverage mapping 里（相当于 Java 的 classes-dir、C++ 的 .gcno）。
     * 产物缺失时 llvm-cov 什么都出不来，而空结果与「这些代码没被跑过」在界面上一模一样。
     */
    @Test
    void 产物不存在时拒绝出报告(@TempDir Path repo) {
        IOException e = assertThrows(IOException.class,
                () -> new RustCoverageAnalyzer(props(repo, repo.resolve("nope.exe").toString()), new CoverageProperties())
                        .analyze(ONE_DUMP));
        assertTrue(e.getMessage().contains("rust-binary 不存在"), e.getMessage());
    }

    @Test
    void LCOV逐行状态与仓库相对路径(@TempDir Path repo) throws Exception {
        String abs = repo.toAbsolutePath().normalize().toString().replace('\\', '/');
        Map<String, FileCoverage> got = new RustCoverageAnalyzer(props(repo, "x"), new CoverageProperties()).parse("""
                SF:%s/demo-service-rust/src/order.rs
                DA:10,3
                DA:11,0
                DA:12,1
                end_of_record
                """.formatted(abs));

        FileCoverage f = got.get("demo-service-rust/src/order.rs");
        assertNotNull(f, "SF 的绝对路径应被换算成仓库相对路径，否则与 git diff 对不上：" + got.keySet());
        assertEquals(2, f.coveredLines());
        assertEquals(1, f.missedLines());
        assertEquals("MISSED", f.lines().get(1).status(), "执行次数为 0 的行不能算跑过");
        // DA 里没出现的行是非可执行行，不进 IR —— 与 JaCoCo 的 EMPTY 同一口径
        assertEquals(3, f.lines().size());
    }

    /**
     * Windows 上同一个路径的盘符大小写并不稳定（llvm-cov 给的与 Java 拿到的可能不一致）。
     * 大小写敏感地比一次，结果是整个 Rust 目录被判成「不在仓库里」而全部丢弃。
     */
    @Test
    void 盘符大小写不同也认得出是同一个仓库(@TempDir Path repo) throws Exception {
        String abs = repo.toAbsolutePath().normalize().toString().replace('\\', '/');
        String flipped = Character.isUpperCase(abs.charAt(0))
                ? Character.toLowerCase(abs.charAt(0)) + abs.substring(1)
                : Character.toUpperCase(abs.charAt(0)) + abs.substring(1);

        Map<String, FileCoverage> got = new RustCoverageAnalyzer(props(repo, "x"), new CoverageProperties()).parse("""
                SF:%s/demo-service-rust/src/order.rs
                DA:1,1
                end_of_record
                """.formatted(flipped));

        assertTrue(got.containsKey("demo-service-rust/src/order.rs"), got.keySet().toString());
    }

    /** 依赖库与标准库不是被测对象，混进来只会稀释覆盖率 */
    @Test
    void 只统计源码根之下的文件(@TempDir Path repo) throws Exception {
        String abs = repo.toAbsolutePath().normalize().toString().replace('\\', '/');
        Map<String, FileCoverage> got = new RustCoverageAnalyzer(props(repo, "x"), new CoverageProperties()).parse("""
                SF:%s/demo-service-rust/src/order.rs
                DA:1,1
                end_of_record
                SF:/rustc/deadbeef/library/std/src/io/mod.rs
                DA:1,1
                end_of_record
                SF:%s/demo-service-go/main.go
                DA:1,1
                end_of_record
                """.formatted(abs, abs));

        assertEquals(List.of("demo-service-rust/src/order.rs"), List.copyOf(got.keySet()));
    }

    /**
     * llvm-cov 可以正常退出却什么都不输出（比如产物没带插桩）。
     * 静默放过的话界面上 Rust 直接消失，与「Rust 代码一行都没被调用」长得完全一样。
     */
    @Test
    void 一个文件都没输出时拒绝出报告(@TempDir Path repo) {
        IOException e = assertThrows(IOException.class,
                () -> new RustCoverageAnalyzer(props(repo, "x"), new CoverageProperties()).parse(""));
        assertTrue(e.getMessage().contains("instrument-coverage"), e.getMessage());
    }

    @Test
    void 没有一个文件落在源码根下时拒绝出报告(@TempDir Path repo) {
        String abs = repo.toAbsolutePath().normalize().toString().replace('\\', '/');
        IOException e = assertThrows(IOException.class,
                () -> new RustCoverageAnalyzer(props(repo, "x"), new CoverageProperties()).parse("""
                        SF:%s/demo-service-go/main.go
                        DA:1,1
                        end_of_record
                        """.formatted(abs)));
        assertTrue(e.getMessage().contains("rust-source-root"), e.getMessage());
    }

    /** 产物存在但不是插桩产物时，报错要指向真正的原因，而不是让人去查探针 */
    @Test
    void 产物存在时才会去调llvm工具(@TempDir Path repo) throws Exception {
        Path bin = repo.resolve("demo.exe");
        Files.writeString(bin, "not a real binary");
        ProjectConfig p = props(repo, bin.toString());
        // llvm 工具的路径是平台级配置：它跟着部署机器走，不跟着项目走
        CoverageProperties platform = new CoverageProperties();
        platform.setLlvmProfdataTool(repo.resolve("no-such-llvm-profdata").toString());

        // 走到了调工具这一步（而不是提前因产物缺失退出），说明前置校验的顺序是对的
        assertThrows(IOException.class, () -> new RustCoverageAnalyzer(p, platform).analyze(ONE_DUMP));
    }
}
