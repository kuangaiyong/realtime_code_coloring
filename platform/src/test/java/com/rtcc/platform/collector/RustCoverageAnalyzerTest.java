package com.rtcc.platform.collector;

import com.rtcc.platform.config.CoverageProperties;
import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.model.FileCoverage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        // 泛型单态化会让同一个函数出现多条 FN 记录 —— 按源码定义合并后父子才对得上，
        // 见「泛型的各个实例在方法明细里按源码定义合并」
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

    /**
     * LLVM 的 counter expression 会做减法（else 分支的次数 = 父计数 − if 分支计数）。
     * dump 发生在业务线程两次递增之间时，抓到的两个计数器不一致，减法在 u64 下**下溢**，
     * 输出 18446744073709551615（{@code u64::MAX}，即 −1 的补码）。
     *
     * <p>实测：负载下每约 8 次 {@code /api/coverage/instances} 调用命中 1 次，
     * 而 {@code Long.parseLong} 解析不了超过 {@code Long.MAX_VALUE} 的无符号值 ——
     * 一行算不出来，抛出的异常让**整台 Rust 实例的覆盖数据全部丢失**，
     * 界面上与「这台什么都没跑」长得一模一样。
     *
     * <p>取舍：这一行既不能算 COVERED（下溢意味着次数根本没算出来），
     * 也不能算 MISSED（那是在说一件没发生的事），所以**不进 IR** ——
     * 既不进分子也不进分母，与非可执行行同一口径，覆盖率不被污染。
     */
    @Test
    void 计数器下溢的行跳过而不是让整份数据失败(@TempDir Path repo) throws Exception {
        String abs = repo.toAbsolutePath().normalize().toString().replace('\\', '/');
        Map<String, FileCoverage> got = new RustCoverageAnalyzer(props(repo, "x"), new CoverageProperties()).parse("""
                SF:%s/demo-service-rust/src/order.rs
                DA:10,3
                DA:11,18446744073709551615
                DA:12,0
                end_of_record
                """.formatted(abs));

        FileCoverage f = got.get("demo-service-rust/src/order.rs");
        assertNotNull(f, "一行算不出来，不该让整台实例的数据全丢：" + got.keySet());
        assertEquals(1, f.coveredLines(), "只有 DA:10 是真跑过的");
        assertEquals(1, f.missedLines(), "只有 DA:12 是真没跑过的");
        assertEquals(2, f.lines().size(), "下溢那一行不进 IR，既不算覆盖也不算未覆盖");
    }

    /**
     * 跳过下溢行是为了不让一行连累整份数据，但**跳到一行不剩时必须拒绝出报告**：
     * 回一个空结果，界面上与「Rust 一行都没跑过」长得完全一样，
     * 而真实原因是这批数据根本不可用 —— 本项目最忌讳的那种坏法。
     */
    @Test
    void 全部行都算不出来时拒绝出报告而不是回空(@TempDir Path repo) {
        String abs = repo.toAbsolutePath().normalize().toString().replace('\\', '/');
        IOException e = assertThrows(IOException.class,
                () -> new RustCoverageAnalyzer(props(repo, "x"), new CoverageProperties()).parse("""
                        SF:%s/demo-service-rust/src/order.rs
                        DA:10,18446744073709551615
                        DA:11,18446744073709551615
                        end_of_record
                        """.formatted(abs)));

        assertTrue(e.getMessage().contains("算不出来"), e.getMessage());
    }

    /**
     * FNDA 的执行次数同样是 u64，但与 DA 不同：它下溢时<b>不能跳过，要拒绝出报告并说清原因</b>。
     *
     * <p>为什么不照 DA 跳过：FNF/FNH 取自 llvm-cov 的汇总，而 llvm-cov 按无符号比较，
     * 会把 u64::MAX 算成「已执行」（2026-09-23 用同构的 LH 实测过：23 个下溢轮全部如此）。
     * 跳过这条 FNDA 只会让方法明细少一条、文件级方法数却照样把它算成已覆盖 ——
     * 父子对不上，文件级被悄悄抬高，比拒绝出报告更糟。
     *
     * <p>为什么现状也不行：原先是 {@code Long.parseLong} 直接抛 NumberFormatException，
     * 页面上 ANALYZE_ERROR 的全部内容就是一句 {@code For input string: "18446744073709551615"}，
     * 而 CLAUDE.md 里这条线索指向的是早已修好的 DA —— 排查的人会被引到错的地方。
     *
     * <p>实测当前工具链下 FNDA 取自函数入口的物理计数器，不会下溢（负载下 DA 32/40 轮下溢，
     * FNDA 520 个样本 0 次）。这条守的是<b>将来</b> rustc 改了计数器分配时，报错能一眼看懂。
     * 2⁶³ 是有符号解析恰好开始出错的边界，一并守住。
     */
    @Test
    void 方法执行次数下溢时拒绝出报告并说清原因(@TempDir Path repo) {
        String abs = repo.toAbsolutePath().normalize().toString().replace('\\', '/');
        for (String bad : List.of("18446744073709551615", "9223372036854775808")) {
            IOException e = assertThrows(IOException.class,
                    () -> new RustCoverageAnalyzer(props(repo, "x"), new CoverageProperties()).parse("""
                            SF:%s/demo-service-rust/src/order.rs
                            FN:10,_RNvCs2r1QDoXLnWk_17demo_service_rust6handle
                            FNDA:%s,_RNvCs2r1QDoXLnWk_17demo_service_rust6handle
                            FNF:1
                            FNH:1
                            DA:10,3
                            DA:11,0
                            end_of_record
                            """.formatted(abs, bad)),
                    "FNDA=" + bad + " 没被拒绝");

            // 这句话就是页面上 ANALYZE_ERROR 的全部内容（describe 直接取 getMessage），
            // 必须让人不翻代码就知道是哪条记录、多半是什么原因
            assertTrue(e.getMessage().contains("FNDA:" + bad), "要带出原始记录：" + e.getMessage());
            assertTrue(e.getMessage().contains("rustc"), "要点出多半是工具链变了：" + e.getMessage());
        }
    }

    /**
     * 实验 crate 的真实 lcov：14 种写法、52 条 FN 记录，llvm-cov 自己的汇总是 FNF 29 / FNH 27。
     *
     * <p>怎么来的（2026-10-21）：src/test/resources/rust-generics/generics.rs 作为一个名为 rtcc_generics2 的 crate 的
     * src/main.rs，照 run_local.sh 的 build_rust 参数构建（x86_64-pc-windows-msvc、-C instrument-coverage、release、
     * rust-lld + xwin，rustc 1.97.1，不链探针），直接运行一次，再 llvm-profdata merge -sparse、
     * llvm-cov export --format=lcov。只改了一处：SF 的绝对路径换成 {@code @REPO@} 占位，读的时候换成临时仓库。
     */
    private String lcovGenerics(Path repo) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/rust-generics/generics.lcov")) {
            assertNotNull(in, "测试资源 rust-generics/generics.lcov 不在 classpath 上");
            String abs = repo.toAbsolutePath().normalize().toString().replace('\\', '/');
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("@REPO@", abs);
        }
    }

    private static String names(List<FileCoverage.MethodCoverage> ms) {
        return ms.stream().map(m -> "L" + m.firstLine() + " " + m.name()).toList().toString();
    }

    @Test
    void 泛型的各个实例在方法明细里按源码定义合并(@TempDir Path repo) throws Exception {
        FileCoverage f = new RustCoverageAnalyzer(props(repo, "x.exe"), new CoverageProperties())
                .parse(lcovGenerics(repo)).get("demo-service-rust/src/generics.rs");
        assertNotNull(f, "没解析出文件");
        List<FileCoverage.MethodCoverage> ms = f.methods();

        // 文件那一行写「27/29 个方法」，点进去也得是 29 条、27 条已调用 —— 逐符号出的话是 52 条
        assertEquals(29, f.coveredMethods() + f.missedMethods(), "文件级方法数取 FNF，不变");
        assertEquals(27, f.coveredMethods(), "文件级已调用取 FNH，不变");
        assertEquals(29, ms.size(), "方法明细条数应等于 FNF：" + names(ms));
        assertEquals(27, ms.stream().filter(m -> m.coveredLines() > 0).count(), "已调用条数应等于 FNH：" + names(ms));

        // 每个泛型定义只有一条
        for (String n : List.of("rtcc_generics2::larger", "rtcc_generics2::pick", "rtcc_generics2::describe",
                "rtcc_generics2::ident", "rtcc_generics2::arr_len", "rtcc_generics2::sum_all",
                "rtcc_generics2::Wrapper::show", "rtcc_generics2::Greet::hi", "rtcc_generics2::Tagged::tag",
                "rtcc_generics2::Wrapper::core::fmt::Display::fmt")) {
            assertEquals(1, ms.stream().filter(m -> m.name().equals(n)).count(), n + " 应恰好一条：" + names(ms));
        }
        // 现有扫描法给出的那些名字不能再出现：类型实参、实现类型、const 值都不该进名字
        for (String old : List.of("rtcc_generics2::pick::Order", "rtcc_generics2::describe::alloc::string::String",
                "rtcc_generics2::arr_len::_E", "rtcc_generics2::flag::_", "rtcc_generics2::A::Greet::hi",
                "rtcc_generics2::Order::Tagged::tag")) {
            assertTrue(ms.stream().noneMatch(m -> m.name().equals(old)), "还在用旧名字 " + old + "：" + names(ms));
        }
        // 同一行（第 25 行）上两个不同的闭包仍是两条 —— 只按「行号 + 名字」合并会把它们并成一条
        assertEquals(2, ms.stream().filter(m -> m.firstLine() == 25).count(), names(ms));
        // 没被执行的泛型：被引用的（never_called，有 u8 实例）与完全没被引用的（never_referenced，只有占位实例）都是一条未调用
        for (String n : List.of("rtcc_generics2::never_called", "rtcc_generics2::never_referenced")) {
            List<FileCoverage.MethodCoverage> hit = ms.stream().filter(m -> m.name().equals(n)).toList();
            assertEquals(1, hit.size(), n + "：" + names(ms));
            assertEquals(0, hit.get(0).coveredLines(), n + " 没被执行");
        }
        // 行覆盖不受影响：仍逐行取 DA（122 行，其中 115 行跑过）
        assertEquals(122, f.coveredLines() + f.missedLines());
        assertEquals(115, f.coveredLines());
    }

    @Test
    void 只要有一个实例跑过就算已调用(@TempDir Path repo) throws Exception {
        // 真实符号 larger::<i32> 与 larger::<f64>，把 i32 那个的执行次数改成 0：
        // 演示服务的 clamp_to 就是这种情形 —— 手续费接口只跑 i64 那个实例。
        // 两种先后顺序都要试：不能是「第一个实例说了算」，也不能是「最后一个实例说了算」
        String i32 = "_RINvCsoL4aAQfs_14rtcc_generics26largerlEB2_";
        String f64 = "_RINvCsoL4aAQfs_14rtcc_generics26largerdEB2_";
        String abs = repo.toAbsolutePath().normalize().toString().replace('\\', '/');
        for (List<String> order : List.of(List.of(i32, f64), List.of(f64, i32))) {
            StringBuilder lcov = new StringBuilder("SF:" + abs + "/demo-service-rust/src/generics.rs\n");
            order.forEach(sym -> lcov.append("FN:3,").append(sym).append('\n'));
            order.forEach(sym -> lcov.append("FNDA:").append(sym.equals(f64) ? 1 : 0).append(',').append(sym).append('\n'));
            lcov.append("FNF:1\nFNH:1\nDA:3,1\nDA:4,1\nend_of_record\n");
            FileCoverage f = new RustCoverageAnalyzer(props(repo, "x"), new CoverageProperties())
                    .parse(lcov.toString()).get("demo-service-rust/src/generics.rs");

            assertEquals(1, f.methods().size(), "两个实例应并成一条：" + names(f.methods()));
            FileCoverage.MethodCoverage m = f.methods().get(0);
            assertEquals("rtcc_generics2::larger", m.name());
            assertEquals(3, m.firstLine());
            assertEquals(1, m.coveredLines(), "有一个实例跑过就是已调用（顺序 " + order + "）");
            assertEquals(0, m.missedLines(), "没跑过的那个实例不能让这一条变成「未调用」（顺序 " + order + "）");
        }
    }

    /**
     * 认不出结构的符号（这里在真实符号后面接了认不得的尾巴，模拟 rustc 将来的新写法）：
     * 宁可多一条，也不把两个不知道是什么的东西硬并成一个；其余方法、行覆盖照常。
     */
    private String lcovUnknownSymbols(Path repo) {
        String abs = repo.toAbsolutePath().normalize().toString().replace('\\', '/');
        return """
                SF:%s/demo-service-rust/src/x.rs
                FN:5,_RNvCs2r1QDoXLnWk_17demo_service_rust4main
                FN:9,_RNvCs2r1QDoXLnWk_17demo_service_rust4mainZZ1
                FN:9,_RNvCs2r1QDoXLnWk_17demo_service_rust4mainZZ2
                FNDA:1,_RNvCs2r1QDoXLnWk_17demo_service_rust4main
                FNDA:1,_RNvCs2r1QDoXLnWk_17demo_service_rust4mainZZ1
                FNDA:0,_RNvCs2r1QDoXLnWk_17demo_service_rust4mainZZ2
                FNF:2
                FNH:2
                DA:5,1
                DA:9,1
                end_of_record
                """.formatted(abs);
    }

    @Test
    void 认不出结构的符号单独成一条且其余照常(@TempDir Path repo) throws Exception {
        FileCoverage f = new RustCoverageAnalyzer(props(repo, "x"), new CoverageProperties())
                .parse(lcovUnknownSymbols(repo)).get("demo-service-rust/src/x.rs");
        assertNotNull(f, "认不出的符号不能让整份数据失败");
        assertEquals(2, f.lines().size(), "行覆盖不受影响");
        assertEquals(3, f.methods().size(), names(f.methods()));
        assertTrue(f.methods().stream().noneMatch(m -> m.name() == null || m.name().isBlank()), names(f.methods()));
    }

    @Test
    void 条数对不上时说得出是哪个文件和两边的数字(@TempDir Path repo) throws Exception {
        // 明细 3 条（认不出的两条各自一条）而 FNF 是 2：页面上会是「2/2 个方法」点进去 3 条
        String why = RustCoverageAnalyzer.methodCountMismatch(new RustCoverageAnalyzer(props(repo, "x"),
                new CoverageProperties()).parse(lcovUnknownSymbols(repo)).values());
        assertNotNull(why, "对不上却没说");
        assertTrue(why.contains("demo-service-rust/src/x.rs"), why);
        assertTrue(why.contains("明细 3 条") && why.contains("FNF 2"), "要有两边的数字：" + why);
    }

    @Test
    void 对得上时不报(@TempDir Path repo) throws Exception {
        RustCoverageAnalyzer a = new RustCoverageAnalyzer(props(repo, "x.exe"), new CoverageProperties());
        assertNull(RustCoverageAnalyzer.methodCountMismatch(a.parse(lcovGenerics(repo)).values()));
        // 没有任何方法记录的文件：明细为空、FNF 缺省为 0，也是对得上的
        String abs = repo.toAbsolutePath().normalize().toString().replace('\\', '/');
        assertNull(RustCoverageAnalyzer.methodCountMismatch(a.parse("""
                SF:%s/demo-service-rust/src/order.rs
                DA:10,3
                end_of_record
                """.formatted(abs)).values()));
    }
}
