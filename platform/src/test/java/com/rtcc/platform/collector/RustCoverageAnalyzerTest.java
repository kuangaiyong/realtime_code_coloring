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

    /**
     * llvm-cov export --format=lcov 的真实输出片段。
     * FNF/FNH 是函数总数与命中数；BRF:0 是实测结果 —— rustc stable 的
     * -C instrument-coverage 不生成分支数据，要 nightly 的 -Z coverage-options=branch
     */
    private String lcovWithFunctions(Path repo) {
        return String.join("\n",
                "SF:" + repo.resolve("demo-service-rust/src/order.rs"),
                "FN:20,_RNvCs2r1QDoXLnWk_17demo_service_rust11read_target",
                "FN:52,_RNvCs2r1QDoXLnWk_17demo_service_rust6handle",
                "FNDA:3,_RNvCs2r1QDoXLnWk_17demo_service_rust11read_target",
                "FNDA:0,_RNvCs2r1QDoXLnWk_17demo_service_rust6handle",
                "FNF:2",
                "FNH:1",
                "BRF:0",
                "BRH:0",
                "DA:20,3",
                "DA:21,0",
                "LF:2",
                "LH:1",
                "end_of_record");
    }

    @Test
    void Rust拿得到方法数但拿不到分支(@TempDir Path repo) throws Exception {
        RustCoverageAnalyzer a = new RustCoverageAnalyzer(props(repo, "x.exe"), new CoverageProperties());

        FileCoverage f = a.parse(lcovWithFunctions(repo)).get("demo-service-rust/src/order.rs");
        assertNotNull(f, "没解析出文件");

        assertEquals(1, f.coveredMethods(), "FNH:1 —— 一个函数跑过");
        assertEquals(1, f.missedMethods(), "FNF:2 减去 FNH:1");

        // rustc stable 压根不生成分支数据，必须是 null 而不是 0 ——
        // 填 0 会让页面显示「Rust 分支覆盖 0%」，读的人以为一个分支都没测
        assertNull(f.coveredBranches(), "stable 不生成分支数据，必须是 null");
        assertNull(f.missedBranches(), "stable 不生成分支数据，必须是 null");
        assertNull(f.lines().get(0).coveredBranches(), "行级同理");
    }

    /**
     * 真实的 lcov 片段：带 FN / FNDA 记录。符号是 <b>v0 mangling</b>（_R 开头），
     * 不是 legacy 的 _ZN...E —— 实测确认，两者是完全不同的两套编码。
     */
    private String lcovWithMethods(Path repo) {
        return String.join("\n",
                "SF:" + repo.resolve("demo-service-rust/src/order.rs"),
                "FN:20,_RNvCs2r1QDoXLnWk_17demo_service_rust11read_target",
                "FN:38,_RNvCs2r1QDoXLnWk_17demo_service_rust11json_escape",
                "FN:52,_RNCNvCs2r1QDoXLnWk_17demo_service_rust11read_target0B3_",
                "FNDA:3,_RNvCs2r1QDoXLnWk_17demo_service_rust11read_target",
                "FNDA:0,_RNvCs2r1QDoXLnWk_17demo_service_rust11json_escape",
                "FNDA:0,_RNCNvCs2r1QDoXLnWk_17demo_service_rust11read_target0B3_",
                "FNF:3", "FNH:1", "BRF:0", "BRH:0",
                "DA:20,3", "DA:21,0", "DA:38,0",
                "end_of_record");
    }

    @Test
    void Rust方法明细带得出可读的函数名(@TempDir Path repo) throws Exception {
        RustCoverageAnalyzer a = new RustCoverageAnalyzer(props(repo, "x.exe"), new CoverageProperties());

        FileCoverage f = a.parse(lcovWithMethods(repo)).get("demo-service-rust/src/order.rs");
        assertNotNull(f.methods(), "Rust 拿得到方法明细");
        assertEquals(3, f.methods().size(), "三条 FN 记录应产出三个方法");

        java.util.List<String> names = f.methods().stream()
                .map(FileCoverage.MethodCoverage::name).toList();
        // v0 符号原样摆出来对人毫无用处，必须抽出末尾那几段可读的标识符
        assertTrue(names.stream().noneMatch(n -> n.startsWith("_R")),
                "方法名还是原始的 v0 符号：" + names);
        assertTrue(names.stream().anyMatch(n -> n.contains("json_escape")),
                "没抽出函数名：" + names);
    }

    @Test
    void Rust方法条数与文件级计数一致(@TempDir Path repo) throws Exception {
        RustCoverageAnalyzer a = new RustCoverageAnalyzer(props(repo, "x.exe"), new CoverageProperties());

        FileCoverage f = a.parse(lcovWithMethods(repo)).get("demo-service-rust/src/order.rs");
        // 父子必须对得上：报表上一层写 3 个方法、钻进去只有 2 条，页面无法自圆其说。
        // 泛型单态化会让同一个函数出现多条 FN 记录，真出现重复时这条会挂 ——
        // 那时要么把文件级计数也改成从去重列表派生，要么 Rust 不进方法明细，
        // 必须显式二选一，不能让父子悄悄对不上
        assertEquals(f.coveredMethods() + f.missedMethods(), f.methods().size(),
                "方法明细条数与文件级计数对不上");
    }

    /**
     * 真实符号（从本机 llvm-cov 输出里取的）。impl 里的方法会带上 backref：
     * {@code NtB4_5Store} —— B4_ 是反向引用，不跳过的话 4 会被当成长度前缀，
     * 名字里冒出 _5St 这种噪声段。这条实测撞到过。
     */
    @Test
    void v0符号里的反向引用不能被当成长度前缀() {
        assertEquals("demo_service_rust::order::Store::query_order",
                RustCoverageAnalyzer.readableName(
                        "_RNvMs_NtCs2r1QDoXLnWk_17demo_service_rust5orderNtB4_5Store11query_order"));
        assertEquals("demo_service_rust::order::Order::new",
                RustCoverageAnalyzer.readableName(
                        "_RNvMNtCs2r1QDoXLnWk_17demo_service_rust5orderNtB2_5Order3new"));
        assertEquals("demo_service_rust::order::is_final_state",
                RustCoverageAnalyzer.readableName(
                        "_RNvNtCs2r1QDoXLnWk_17demo_service_rust5order14is_final_state"));
    }

    /** 抽不出来就原样返回：这一步只影响可读性，不参与任何计算 */
    @Test
    void 不是v0符号时原样返回() {
        assertEquals("plain_name", RustCoverageAnalyzer.readableName("plain_name"));
        assertEquals("_ZN4test3fooE", RustCoverageAnalyzer.readableName("_ZN4test3fooE"));
    }

    @Test
    void Rust方法的首行号取FN记录里的行号(@TempDir Path repo) throws Exception {
        RustCoverageAnalyzer a = new RustCoverageAnalyzer(props(repo, "x.exe"), new CoverageProperties());

        FileCoverage f = a.parse(lcovWithMethods(repo)).get("demo-service-rust/src/order.rs");
        FileCoverage.MethodCoverage m = f.methods().stream()
                .filter(x -> x.name().contains("json_escape")).findFirst().orElseThrow();
        assertEquals(38, m.firstLine(), "FN:38 给的就是首行号，不必像 C++ 那样按位置猜");
        assertNull(m.coveredBranches(), "Rust 拿不到分支，方法级同样是 null");
    }
}
