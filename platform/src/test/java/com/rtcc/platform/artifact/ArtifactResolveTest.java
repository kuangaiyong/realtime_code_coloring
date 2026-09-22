package com.rtcc.platform.artifact;

import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.model.BuildVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把配置里的本地产物路径换成「按 buildId 解压出来的目录」。
 *
 * <p><b>四个 Analyzer 一行不改</b>：C++ 与 Rust 的 Analyzer 自己从 ProjectConfig
 * 读产物路径，没有接收路径的入参 —— 所以换的是喂给它们的那份配置，不是它们本身。
 * 换完还得用它重造那两个 Analyzer，那一步在 {@code ProjectRuntime} 里。
 */
class ArtifactResolveTest {

    private static final String SHA = "77842897548da30523c688d97389c6d33e84a2d5";
    private static final BuildVersion CLEAN = new BuildVersion(SHA, false);

    private static byte[] zip(String... names) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (String name : names) {
                z.putNextEntry(new ZipEntry(name));
                z.write("x".getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static ProjectConfig cfg(String source) {
        ProjectConfig c = new ProjectConfig();
        c.setId("demo");
        c.setArtifactSource(source);
        c.setClassesDir("原来的/classes");
        c.setCppObjectsDir("原来的/obj");
        c.setRustBinary("原来的/svc.exe");
        return c;
    }

    private static void put(ArtifactStore store, ArtifactKind kind, String... names) throws Exception {
        store.save("demo", SHA, kind, new ByteArrayInputStream(zip(names)));
    }

    /** local 模式下必须原样返回 —— 现有的裸机部署与 8 实例验收链路走的都是这条 */
    @Test
    void 本地模式原样返回(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        ProjectConfig in = cfg("local");

        ProjectConfig out = store.resolveInto(in, CLEAN, EnumSet.allOf(ArtifactKind.class));

        // ProjectRuntime 正是靠这个对象同一性判断「要不要重造 C++/Rust 的 Analyzer」。
        // 这里一旦改成返回副本，local 模式每轮都会白造两个 Analyzer
        assertSame(in, out, "local 模式不该复制配置，更不该改路径");
    }

    @Test
    void 上传模式把三个产物路径换成解压目录(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        put(store, ArtifactKind.JAVA, "com/shop/A.class");
        put(store, ArtifactKind.CPP, "main.gcno");
        put(store, ArtifactKind.RUST, "svc.exe");

        ProjectConfig out = store.resolveInto(cfg("uploaded"), CLEAN,
                EnumSet.allOf(ArtifactKind.class));

        assertTrue(out.getClassesDir().endsWith("java"), out.getClassesDir());
        assertTrue(out.getCppObjectsDir().endsWith("cpp"), out.getCppObjectsDir());
        // Rust 要的是产物文件本身而不是目录 —— RustCoverageAnalyzer 用 isRegularFile 判它
        assertTrue(out.getRustBinary().endsWith("svc.exe"), out.getRustBinary());
        assertTrue(out.getClassesDir().contains(SHA), out.getClassesDir());
    }

    /**
     * 换的是副本，传进来的那份<b>一个字段都不许就地改</b>。
     *
     * <p>它不是副本：{@code ProjectRuntime} 持有的 props 就是 {@code ProjectRegistry}
     * 注册表里那个活对象，也是 {@code GET /api/projects} 原样吐回去的那一份。
     * 就地改的话，用户在项目设置里会看到平台内部的解压目录，而且改一次污染到底 ——
     * 下一轮采集读到的「原始配置」已经是被改过的了。
     */
    @Test
    void 不改动传进来的那份配置(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        put(store, ArtifactKind.JAVA, "A.class");
        put(store, ArtifactKind.CPP, "main.gcno");
        put(store, ArtifactKind.RUST, "svc.exe");
        ProjectConfig in = cfg("uploaded");

        store.resolveInto(in, CLEAN, EnumSet.allOf(ArtifactKind.class));

        assertEquals("原来的/classes", in.getClassesDir(), "入参被就地改了");
        assertEquals("原来的/obj", in.getCppObjectsDir(), "入参被就地改了");
        assertEquals("原来的/svc.exe", in.getRustBinary(), "入参被就地改了");
    }

    /**
     * <b>取不到产物一律拒绝出报告。</b>跳过那门语言的话，界面上表现为
     * 「这些代码没被调用过」—— 与真相完全相反，而且看不出是缺产物。
     * 消息里必须同时有 buildId 和是哪门语言，否则运维不知道该推哪一个包。
     */
    @Test
    void 取不到产物时报错并点名(@TempDir Path root) {
        ArtifactStore store = new ArtifactStore(root, 10);

        IOException e = assertThrows(IOException.class,
                () -> store.resolveInto(cfg("uploaded"), CLEAN, EnumSet.of(ArtifactKind.JAVA)));

        assertTrue(e.getMessage().contains(SHA), e.getMessage());
        assertTrue(e.getMessage().contains("java"), e.getMessage());
    }

    /**
     * <b>只解析这一轮真有实例的那几种语言</b>，不是「配置里填了路径」的那几种。
     *
     * <p>按后者做会引入一个真实回归：C++ 两台实例都掉线时 cppDumps 为空、C++ 归一化
     * 根本不会跑，却因为「缺 cpp 产物」把整轮打成 ANALYZE_ERROR ——
     * 把平台既定的「少几台就降级成 PARTIAL、照常出报告」变成「完全没有报告」。
     * Go 永远不在这个集合里：它的覆盖数据自包含，不需要任何产物。
     */
    @Test
    void 只要这一轮用得上的语言(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        put(store, ArtifactKind.JAVA, "A.class");   // 只传了 java，cpp / rust 都没传

        // 这一轮只有 Java 实例：照常解析，不该因为「cpp 产物没传」失败
        ProjectConfig out = store.resolveInto(cfg("uploaded"), CLEAN, EnumSet.of(ArtifactKind.JAVA));
        assertTrue(out.getClassesDir().contains(SHA), out.getClassesDir());
        // 没解析的那两项保持原值，不会被清空
        assertEquals("原来的/obj", out.getCppObjectsDir());
        assertEquals("原来的/svc.exe", out.getRustBinary());

        // 而这一轮真有 C++ 实例时，缺 cpp 产物就必须拒绝
        IOException e = assertThrows(IOException.class, () -> store.resolveInto(
                cfg("uploaded"), CLEAN, EnumSet.of(ArtifactKind.JAVA, ArtifactKind.CPP)));
        assertTrue(e.getMessage().contains("cpp"), e.getMessage());
    }

    /**
     * 一个产物都用不上的那一轮，连版本都不该要求。
     *
     * <p>纯 Go 项目（或某一轮只剩 Go 实例连着）配成 uploaded 时，工作树一脏、
     * 或实例没配 sessionid，就会被两道版本闸门打成整轮 ANALYZE_ERROR ——
     * 而它压根不需要任何产物。与「只解析真有实例的语言」是同一条理由：
     * 不需要的东西不该有能力把这一轮打挂。
     */
    @Test
    void 这一轮一个产物都用不上时连版本都不要求(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        ProjectConfig in = cfg("uploaded");

        // 脏构建、没版本，两种都不该拦 —— 因为根本没有要取的产物
        assertSame(in, store.resolveInto(in, new BuildVersion(SHA, true), EnumSet.noneOf(ArtifactKind.class)));
        assertSame(in, store.resolveInto(in, null, EnumSet.noneOf(ArtifactKind.class)));
    }

    /**
     * 缺产物的补救命令必须带上 {@code ?project=}。
     *
     * <p>上传接口的 project 默认是 default，非默认项目照着一条不带它的命令做，
     * 包会传进 default 项目、接口还回 200，下一轮采集仍报这同一句话 ——
     * 照提示做了却没用，而且看不出为什么。
     */
    @Test
    void 补救命令点名是哪个项目(@TempDir Path root) {
        ArtifactStore store = new ArtifactStore(root, 10);

        IOException e = assertThrows(IOException.class,
                () -> store.resolveInto(cfg("uploaded"), CLEAN, EnumSet.of(ArtifactKind.JAVA)));

        assertTrue(e.getMessage().contains("project=demo"), e.getMessage());
    }

    /**
     * {@code zip -r target/release/demo} 打出来的包（文件在子目录里）要认得出来。
     *
     * <p>save() 统计条目时把嵌套路径下的文件一并算进去了，所以这种包上传会成功；
     * 取用时若只看顶层就会说「一个文件都没有」—— 上传说成功、取用说没有，
     * 正是这个类反复要消灭的那种自相矛盾。
     */
    @Test
    void rust产物在子目录里也找得到(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        put(store, ArtifactKind.RUST, "target/release/demo-service-rust.exe");

        ProjectConfig out = store.resolveInto(cfg("uploaded"), CLEAN, EnumSet.of(ArtifactKind.RUST));

        assertTrue(out.getRustBinary().endsWith("demo-service-rust.exe"), out.getRustBinary());
    }

    /**
     * 不认识的产物来源<b>在使用点</b>也要拒绝，不能当成 local。
     *
     * <p>{@code ProjectRegistry.validate} 只挂在 create / update 上，而本项目改配置的
     * 正规方式之一是直接改库里那份 JSON（见 CLAUDE.md §三），yml 种子同样不过 validate。
     * 从那些路进来的坏值若被 {@code usesUploadedArtifacts()} 当成 local，
     * 容器化部署上打错一个字母，平台就会拿本机路径的产物去解另一个 buildId 的探针数据 ——
     * 行号错位而界面上一切正常。入口有好几个，用的地方只有这一个，所以堵在这里才堵得全。
     */
    @Test
    void 不认识的产物来源在使用点也拒绝(@TempDir Path root) {
        ArtifactStore store = new ArtifactStore(root, 10);

        for (String bad : List.of("upload", "uploded", " uploaded", "remote", "")) {
            IOException e = assertThrows(IOException.class,
                    () -> store.resolveInto(cfg(bad), CLEAN, EnumSet.of(ArtifactKind.JAVA)),
                    "artifactSource=[" + bad + "] 被当成了 local");
            assertTrue(e.getMessage().contains("产物来源"), e.getMessage());
        }
    }

    /**
     * {@code artifact-keep} 配成 0 或负数时必须在造仓库那一刻就炸。
     *
     * <p>不拦的话 {@code prune} 会把<b>刚刚换入的那个构建</b>一起删掉：上传接口照样回 200、
     * {@code kept} 是空的，下一轮采集却说「这个构建没上传产物」——
     * 上传说成功、取用说没有，正是这个类反复要消灭的那种自相矛盾。
     * 它是平台级配置（yml，改了要重启），写错就该在启动时说清楚是哪一项。
     */
    @Test
    void 保留数必须至少为一(@TempDir Path root) {
        for (int bad : new int[]{0, -1}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new ArtifactStore(root, bad), "keep=" + bad);
            assertTrue(e.getMessage().contains("artifact-keep"), e.getMessage());
        }
        // 1 是合法的下界：只留最新的那一个
        assertEquals(1, new ArtifactStore(root, 1).keep());
    }

    /** 没有构建版本时（实例没配 sessionid，或各实例版本不一致）同样不能猜一个路径出来 */
    @Test
    void 没有构建版本时明确报错(@TempDir Path root) {
        ArtifactStore store = new ArtifactStore(root, 10);

        IOException e = assertThrows(IOException.class, () -> store.resolveInto(
                cfg("uploaded"), null, EnumSet.of(ArtifactKind.JAVA)));

        assertTrue(e.getMessage().contains("构建版本"), e.getMessage());
    }

    /**
     * 脏构建<b>拒绝</b>，不许剥掉 -dirty 去取干净 commit 的那一份。
     *
     * <p>BuildVersion 把 -dirty 拆成了独立的布尔位，commit() 给出的是干净的 40 位 sha，
     * 照它去取一定取得到 —— 取回的却是<b>另一份字节码</b>。这正是
     * requireValidBuildId 在上传侧拒绝 -dirty 所要防的那件事，读取侧不能从背面绕开它。
     */
    @Test
    void 脏构建拒绝而不是剥掉后缀去取(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        put(store, ArtifactKind.JAVA, "A.class");   // 干净 commit 的那份产物确实在

        IOException e = assertThrows(IOException.class, () -> store.resolveInto(
                cfg("uploaded"), new BuildVersion(SHA, true), EnumSet.of(ArtifactKind.JAVA)));

        assertTrue(e.getMessage().contains("dirty"), e.getMessage());
    }

    /**
     * Rust 产物包里多于一个文件时报错，而不是<b>取第一个</b>。
     *
     * <p>Files.list 的顺序没有任何保证，包里混进 .pdb 之类时「取第一个」会随机选中错的那个，
     * 而 llvm-cov 拿到非产物文件后报的错离真正的原因很远。
     */
    @Test
    void rust产物包里多于一个文件时说清楚(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        put(store, ArtifactKind.RUST, "svc.exe", "svc.pdb");

        IOException e = assertThrows(IOException.class, () -> store.resolveInto(
                cfg("uploaded"), CLEAN, EnumSet.of(ArtifactKind.RUST)));

        assertTrue(e.getMessage().contains("svc.exe"), e.getMessage());
        assertTrue(e.getMessage().contains("svc.pdb"), e.getMessage());
    }

    /**
     * copy() 漏掉任何一个字段，后果都是「uploaded 模式下那一项悄悄变回默认值」——
     * 比如 baseline 丢了，增量口径会拿错基线算出一份看不出错的报告。
     * 用反射逐个字段比对，将来加字段时这条会自动跟上。
     */
    @Test
    void 复制不漏任何字段() throws Exception {
        ProjectConfig src = new ProjectConfig();
        src.setId("p");
        src.setName("n");
        src.setInstances(List.of("java://h:1", "go://h:2"));
        src.setRepoDir("r");
        src.setBaseline("b");
        src.setClassesDir("c");
        src.setJavaSourceRoot("j");
        src.setGoSourceRoot("g");
        src.setGoModulePath("m");
        src.setGoExclude(List.of("x.go"));
        src.setCppSourceRoot("cs");
        src.setCppObjectsDir("co");
        src.setRustSourceRoot("rs");
        src.setRustBinary("rb");
        src.setArtifactSource("uploaded");
        src.setIntervalMs(4321);
        src.setTimeoutMs(1234);
        src.getGate().setIncrementalThreshold(55d);
        src.getGate().setOverallThreshold(33d);

        ProjectConfig copy = src.copy();
        // 先证明 fixture 本身是全的。少了这一步，这条用例并不像它看起来那样「自动跟上」：
        // 新加一个字段却忘了在上面 setXxx 的话，src 与 copy 会同为默认值，
        // 下面的逐字段比对照样全过 —— 而 copy() 是不是漏了它，恰恰一点都没验到
        ProjectConfig untouched = new ProjectConfig();
        for (Field f : ProjectConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            if (f.get(src) instanceof ProjectConfig.Gate g) {
                assertNotEquals(80d, g.getIncrementalThreshold(), "gate 的阈值没在 fixture 里改过");
                continue;
            }
            assertNotEquals(f.get(untouched), f.get(src),
                    "字段 " + f.getName() + " 没在这条用例的 fixture 里设过值 —— "
                            + "它与默认值相同，于是 copy() 漏掉它也测不出来");
        }

        for (Field f : ProjectConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            Object a = f.get(src);
            Object b = f.get(copy);
            if (a instanceof ProjectConfig.Gate ga) {
                // Gate 没有 equals，逐个数字比
                ProjectConfig.Gate gb = (ProjectConfig.Gate) b;
                assertEquals(ga.getIncrementalThreshold(), gb.getIncrementalThreshold(),
                        "门禁增量阈值没复制");
                assertEquals(ga.getOverallThreshold(), gb.getOverallThreshold(),
                        "门禁全量阈值没复制");
            } else {
                assertEquals(a, b, "字段 " + f.getName() + " 没复制 —— "
                        + "uploaded 模式下它会悄悄变回默认值");
            }
        }
    }

    /**
     * 可变字段不能与原配置共享同一个对象：共享的话改副本会连带改到注册表里那份，
     * 与就地改是同一个后果，只是更难发现。
     */
    @Test
    void 可变字段是各自一份() {
        ProjectConfig src = new ProjectConfig();
        src.setInstances(new ArrayList<>(List.of("java://h:1")));

        ProjectConfig copy = src.copy();
        copy.getInstances().add("java://h:2");
        copy.getGate().setIncrementalThreshold(1d);
        copy.getGoExclude().add("y.go");

        assertEquals(List.of("java://h:1"), src.getInstances());
        assertEquals(80d, src.getGate().getIncrementalThreshold());
        assertEquals(List.of("coverage_agent.go"), src.getGoExclude());
    }
}
