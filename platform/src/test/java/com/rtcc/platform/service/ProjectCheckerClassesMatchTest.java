package com.rtcc.platform.service;

import com.rtcc.platform.collector.ProbeClient;
import com.rtcc.platform.config.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「产物目录存在，但根本不是这个工程」—— 自检必须能挡住这一种。
 *
 * <p>这个洞是真实踩出来的：{@code classes-dir} 的相对路径以<b>平台进程的工作目录</b>
 * 为基准（{@code new File(getClassesDir())}），而 {@code java-source-root} 以
 * <b>repo-dir</b> 为基准。于是「填 {@code target/classes}、以为是被测工程的产物」
 * 会落到平台自己的 {@code target/classes} 上 —— 而那个目录<b>确实存在</b>，
 * 逐项检查全过、项目照样建得出来。
 *
 * <p>之后的表现是覆盖率 IR 里装的全是平台自己的类，染色页满屏「源码读取失败」，
 * 没有任何一处告诉人问题出在配置上。判据必须从「这个目录存在吗」
 * 改成「<b>产物里的类，源码根下找不找得到</b>」。
 */
class ProjectCheckerClassesMatchTest {

    @TempDir
    Path tmp;

    private static Map<String, Object> item(Map<String, Object> res, String name) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.get("items");
        return items.stream().filter(i -> name.equals(i.get("name"))).findFirst().orElse(null);
    }

    /** 造一个 .class 文件。内容无所谓：这一项只按路径对应关系判，不解析字节码 */
    private void clazz(Path classesDir, String relPath) throws Exception {
        Path f = classesDir.resolve(relPath);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "not really bytecode", StandardCharsets.UTF_8);
    }

    private void source(Path srcRoot, String relPath) throws Exception {
        Path f = srcRoot.resolve(relPath);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "// src", StandardCharsets.UTF_8);
    }

    private ProjectConfig cfg(Path repo, String classesDir, String sourceRoot) {
        ProjectConfig c = new ProjectConfig();
        c.setId("cm");
        c.setName("产物源码对应");
        // 保留地址段（RFC 5737 TEST-NET-1）：这些用例不关心探针，连不上即可
        c.setInstances(List.of("java://192.0.2.1:6300"));
        c.setRepoDir(repo.toString());
        c.setClassesDir(classesDir);
        c.setJavaSourceRoot(sourceRoot);
        c.setTimeoutMs(200);
        return c;
    }

    @Test
    void 产物指向另一个工程时当场点名() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("demo-service"));
        source(repo.resolve("src/main/java"), "com/shop/order/OrderService.java");
        // 平台自己的产物：目录存在、里面全是别的工程的类 —— 正是用户踩到的那一种
        Path wrong = Files.createDirectories(tmp.resolve("platform/target/classes"));
        clazz(wrong, "com/rtcc/platform/service/ProjectOperationException.class");
        clazz(wrong, "com/rtcc/platform/web/ProjectController.class");

        Map<String, Object> res = new ProjectChecker(new ProbeClient())
                .check(cfg(repo, wrong.toString(), "src/main/java"));

        // 「目录存在」这一项照旧通过 —— 这正是它单独用不住的原因
        assertEquals(true, item(res, "classesDir").get("ok"),
                "产物目录本身是存在的，这一项本就该过");
        Map<String, Object> m = item(res, "classesMatchSource");
        assertFalse((Boolean) m.get("ok"),
                "产物里全是另一个工程的类，自检却放行了 —— 建出来只会满屏「源码读取失败」");
        String detail = String.valueOf(m.get("detail"));
        assertTrue(detail.contains(wrong.toFile().getAbsolutePath()),
                "没点名产物目录解析到了哪里，人无从判断自己填的相对路径落到了何处：" + detail);
        assertTrue(detail.contains("ProjectOperationException.java"),
                "没举出一个对不上的例子：" + detail);
    }

    @Test
    void 产物与源码对得上时通过() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("demo-service"));
        source(repo.resolve("src/main/java"), "com/shop/order/OrderService.java");
        Path classes = Files.createDirectories(repo.resolve("target/classes"));
        clazz(classes, "com/shop/order/OrderService.class");

        Map<String, Object> res = new ProjectChecker(new ProbeClient())
                .check(cfg(repo, classes.toString(), "src/main/java"));

        assertTrue((Boolean) item(res, "classesMatchSource").get("ok"),
                "配置是对的却被拦下：" + item(res, "classesMatchSource").get("detail"));
    }

    /**
     * 产物里混着少量没有源码的类是常态（生成代码、依赖被打进来、模块拆分）。
     * 按「有对不上的就失败」判会把好配置拦下来，所以判据是<b>一个都对不上</b>。
     */
    @Test
    void 只要有一个对得上就不算配错() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("demo-service"));
        source(repo.resolve("src/main/java"), "com/shop/order/OrderService.java");
        Path classes = Files.createDirectories(repo.resolve("target/classes"));
        clazz(classes, "com/shop/order/OrderService.class");
        clazz(classes, "com/shop/generated/QOrderEntity.class");   // 生成代码，没有源码
        clazz(classes, "com/vendor/Bundled.class");                // 被打进来的依赖

        Map<String, Object> res = new ProjectChecker(new ProbeClient())
                .check(cfg(repo, classes.toString(), "src/main/java"));

        assertTrue((Boolean) item(res, "classesMatchSource").get("ok"),
                "混着几个没有源码的类就被判成配错了：" + item(res, "classesMatchSource").get("detail"));
    }

    /**
     * 内部类的源码文件名是<b>外层类</b>的名字，拿 {@code Foo$Bar.java} 去找必然找不到。
     * 不跳过的话，一个满是内部类的产物会把判据变成掷骰子。
     */
    @Test
    void 内部类不参与判定() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("demo-service"));
        source(repo.resolve("src/main/java"), "com/shop/order/OrderService.java");
        Path classes = Files.createDirectories(repo.resolve("target/classes"));
        clazz(classes, "com/shop/order/OrderService.class");
        clazz(classes, "com/shop/order/OrderService$Inner.class");

        Map<String, Object> res = new ProjectChecker(new ProbeClient())
                .check(cfg(repo, classes.toString(), "src/main/java"));
        assertTrue((Boolean) item(res, "classesMatchSource").get("ok"));
    }

    /**
     * Kotlin 把 {@code Foo.kt} 里的顶层函数编成 {@code FooKt.class} ——
     * 源码文件名里没有那个 {@code Kt}。不脱后缀的话，一个全是顶层函数的 Kotlin 工程
     * 会一个都对不上，被这一项判成「产物与源码不是同一个工程」：
     * 本是防误配的检查，反倒把创建按钮锁死，而且把人引去找一个根本没问题的目录。
     */
    @Test
    void Kotlin顶层函数不算对不上() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("demo-service"));
        source(repo.resolve("src/main/kotlin"), "com/shop/order/Routes.kt");
        Path classes = Files.createDirectories(repo.resolve("target/classes"));
        clazz(classes, "com/shop/order/RoutesKt.class");

        Map<String, Object> res = new ProjectChecker(new ProbeClient())
                .check(cfg(repo, classes.toString(), "src/main/kotlin"));

        assertTrue((Boolean) item(res, "classesMatchSource").get("ok"),
                "配置完全正确的 Kotlin 工程被判成配错了：" + item(res, "classesMatchSource").get("detail"));
    }

    /** 产物目录里一个 .class 都没有：被测服务多半还没编译，与「指错目录」是两回事 */
    @Test
    void 产物目录是空的时说清是没编译() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("demo-service"));
        source(repo.resolve("src/main/java"), "com/shop/order/OrderService.java");
        Path empty = Files.createDirectories(repo.resolve("target/classes"));

        Map<String, Object> res = new ProjectChecker(new ProbeClient())
                .check(cfg(repo, empty.toString(), "src/main/java"));

        Map<String, Object> m = item(res, "classesMatchSource");
        assertFalse((Boolean) m.get("ok"));
        assertTrue(String.valueOf(m.get("detail")).contains("一个 .class 都没有"),
                String.valueOf(m.get("detail")));
    }

    /**
     * <b>repo-dir 填成仓库里的子目录是能跑通的 —— 正因为能跑通才危险。</b>
     *
     * git 在子目录里照常工作，{@code rev-parse HEAD} 也照常返回，所以「代码仓库」
     * 那一项会通过。但 {@code diff --name-only} 交出来的路径始终以<b>仓库根</b>为基准
     * （实测：在 demo-service 里跑，git 说的是 demo-service/src/main/java/...），
     * 而覆盖率 IR 里是 src/main/java/...，两边永远交不上。
     *
     * 结果不是报错，是增量范围恒为空：门禁每次都以「没有变更的可执行代码」<b>放行</b>，
     * CI 从此挡不住任何东西，而它看上去一切正常。
     */
    @Test
    void 仓库目录填成子目录时当场点名() throws Exception {
        Path repoRoot = tmp.resolve("myrepo");
        Path sub = Files.createDirectories(repoRoot.resolve("demo-service"));
        source(sub.resolve("src/main/java"), "com/shop/order/OrderService.java");
        git(repoRoot, "init", "-q");
        git(repoRoot, "config", "user.email", "t@example.com");
        git(repoRoot, "config", "user.name", "t");
        git(repoRoot, "add", "-A");
        git(repoRoot, "commit", "-qm", "one");

        // 仓库根本身：应当通过
        Map<String, Object> ok = new ProjectChecker(new ProbeClient())
                .check(cfg(repoRoot, sub.resolve("target/classes").toString(), "demo-service/src/main/java"));
        assertEquals(null, item(ok, "repoIsTopLevel"),
                "填的就是仓库根，不该报这一项：" + item(ok, "repoIsTopLevel"));

        // 子目录：必须点名，并说清该改成什么
        Map<String, Object> bad = new ProjectChecker(new ProbeClient())
                .check(cfg(sub, sub.resolve("target/classes").toString(), "src/main/java"));
        assertEquals(true, item(bad, "repoDir").get("ok"),
                "git 在子目录里照常工作，这一项本就该过 —— 这正是它单独用不住的原因");
        Map<String, Object> m = item(bad, "repoIsTopLevel");
        assertFalse((Boolean) m.get("ok"),
                "填成子目录却放行了 —— 增量范围会恒为空，门禁从此挡不住任何东西");
        String detail = String.valueOf(m.get("detail"));
        assertTrue(detail.contains(repoRoot.toFile().getCanonicalPath()),
                "没说清该改成哪个目录：" + detail);
        assertTrue(detail.contains("demo-service/src/main/java"),
                "没给出源码根该怎么跟着改：" + detail);
    }

    /**
     * <b>「这个相对路径你多半是按仓库目录填的」。</b>
     *
     * <p>同一份配置里两个字段两种基准：{@code classes-dir} 相对<b>平台进程的工作目录</b>，
     * 紧挨着的 {@code java-source-root} 相对 <b>repo-dir</b>。照着旁边那行的写法填
     * {@code demo-service/target/classes}，就被解析到平台自己目录底下 ——
     * 只回一句「不是有效目录」的话，人看到的是一条自己从没写过的路径，
     * 还得先想明白基准是谁才知道该改成什么。
     */
    @Test
    void 相对路径按仓库基准填时直接给出该填的绝对路径() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("myrepo"));
        source(repo.resolve("demo-service/src/main/java"), "com/shop/order/OrderService.java");
        // 相对路径要满足两条：在<b>任何</b>可能的工作目录下都不存在，在仓库目录下存在。
        // 写死 demo-service/target/classes 的话，从仓库根跑测试（IDE 里改过 working dir）
        // 就真能解析到，classesDir 判成 ok，用例失败而信息一个字都不指向真正的原因
        String rel = "nowhere-" + UUID.randomUUID() + "/target/classes";
        Path real = Files.createDirectories(repo.resolve(rel));

        Map<String, Object> res = new ProjectChecker(new ProbeClient())
                .check(cfg(repo, rel, "demo-service/src/main/java"));

        Map<String, Object> m = item(res, "classesDir");
        assertFalse((Boolean) m.get("ok"), "这个相对路径在平台目录下确实不存在，本就该判失败");
        String detail = String.valueOf(m.get("detail"));
        assertTrue(detail.contains(real.toFile().getCanonicalPath()),
                "没给出该填的绝对路径，人还得自己想明白基准是谁：" + detail);
    }

    /** 换个基准也找不到时不给提示：瞎猜一条路径出来，比只说「不是有效目录」更误导 */
    @Test
    void 换仓库基准也找不到时不乱猜() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("myrepo"));
        source(repo.resolve("demo-service/src/main/java"), "com/shop/order/OrderService.java");

        Map<String, Object> res = new ProjectChecker(new ProbeClient())
                .check(cfg(repo, "nowhere-" + UUID.randomUUID() + "/target/classes",
                        "demo-service/src/main/java"));

        Map<String, Object> m = item(res, "classesDir");
        // 先钉住「这一项判失败」：只断言不含提示串的话，任何让 dir() 恒判通过的回归
        // 都会让这条继续 PASS —— 那时 detail 是条绝对路径，自然也不含提示串
        assertFalse((Boolean) m.get("ok"), "两个基准下都找不到，本就该判失败");
        String detail = String.valueOf(m.get("detail"));
        assertFalse(detail.contains("你要填的多半是"),
                "两个基准下都不存在，却还是猜了一条路径出来：" + detail);
    }

    private void git(Path dir, String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of("git", "-C", dir.toString()));
        cmd.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args) + " 失败：" + out);
    }

    /**
     * 产物目录压根不存在时不重复报：{@code classesDir} 那一项已经点名了，
     * 这里再报一条只会让自检表上出现两条说同一件事的失败。
     */
    @Test
    void 目录本身就不存在时不重复报() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("demo-service"));
        source(repo.resolve("src/main/java"), "com/shop/order/OrderService.java");

        Map<String, Object> res = new ProjectChecker(new ProbeClient())
                .check(cfg(repo, tmp.resolve("nope").toString(), "src/main/java"));

        assertFalse((Boolean) item(res, "classesDir").get("ok"));
        assertEquals(null, item(res, "classesMatchSource"),
                "产物目录不存在时，这一项不该再报一条 —— 自检表上会出现两条说同一件事的失败");
    }
}
