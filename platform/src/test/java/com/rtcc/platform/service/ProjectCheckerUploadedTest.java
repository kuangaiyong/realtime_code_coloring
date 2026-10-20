package com.rtcc.platform.service;

import com.rtcc.platform.artifact.ArtifactAuth;
import com.rtcc.platform.artifact.ArtifactKind;
import com.rtcc.platform.artifact.ArtifactStore;
import com.rtcc.platform.collector.ProbeClient;
import com.rtcc.platform.config.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * uploaded 项目的自检：产物不在平台本机，而在按 buildId 索引的产物仓库里。
 *
 * <p>原先的自检不看产物来源，照样要求三条本地产物路径存在 —— uploaded 项目按设计根本没有它们，
 * 于是向导第 6 步永远过不去、创建按钮永远是灰的。反过来也不能简单地「跳过不查」：
 * 向导的承诺是「自检全过，建出来就有数据」，产物不在仓库里时建出来第一轮就是 ANALYZE_ERROR。
 *
 * <p>实例全连上之后的那一段（{@code checkUploaded}）直接喂「探到的版本」来测：
 * 本类其余用例与 {@link ProjectCheckerProbeOneTest} 同一个约定，实例一律连不上，
 * 连真实实例的全流程由 e2e_artifact.py（接口级）与 ui_verify.js（页面级）验。
 */
class ProjectCheckerUploadedTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String SHA2 = "89abcdef0123456789abcdef0123456789abcdef";
    private static final String TOKEN = "t0ken";
    /** 保留地址段（RFC 5737 TEST-NET-1）：连不上，也不会误打到真实机器 */
    private static final String JAVA = "java://192.0.2.1:6300";

    @TempDir
    Path tmp;

    private ArtifactStore store() {
        return new ArtifactStore(tmp.resolve("artifacts"), 10);
    }

    private ProjectChecker checker(ArtifactStore store, String token) {
        return new ProjectChecker(new ProbeClient(), store, new ArtifactAuth(token));
    }

    /** 一个有 Java 源码的仓库：com/shop/order/OrderService.java */
    private Path repo() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Path src = repo.resolve("src/main/java/com/shop/order/OrderService.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "// src", StandardCharsets.UTF_8);
        return repo;
    }

    private ProjectConfig uploaded(Path repo, String... instances) {
        ProjectConfig c = new ProjectConfig();
        c.setId("up");
        c.setName("uploaded 自检");
        c.setArtifactSource("uploaded");
        c.setInstances(List.of(instances));
        c.setRepoDir(repo.toString());
        c.setJavaSourceRoot("src/main/java");
        c.setTimeoutMs(200);
        return c;
    }

    /** 往产物仓库里存一份 Java 产物，条目就是这些 .class 路径（内容无所谓：核对只按路径对应） */
    private static void pushJava(ArtifactStore store, String buildId, String... classes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (String c : classes) {
                z.putNextEntry(new ZipEntry(c));
                z.write("not really bytecode".getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        store.save("up", buildId, ArtifactKind.JAVA, new ByteArrayInputStream(bos.toByteArray()));
    }

    private static Map<String, Object> item(Map<String, Object> res, String name) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.get("items");
        return items.stream().filter(i -> name.equals(i.get("name"))).findFirst().orElse(null);
    }

    private static ProjectChecker.CheckItem find(List<ProjectChecker.CheckItem> items, String name) {
        return items.stream().filter(i -> name.equals(i.name())).findFirst().orElse(null);
    }

    private static List<ProjectChecker.CheckItem> uploadedItems(ProjectChecker checker, ProjectConfig cfg,
                                                                  boolean allConnected, String... ids) {
        List<ProjectChecker.CheckItem> items = new ArrayList<>();
        checker.checkUploaded(cfg, Arrays.asList(ids), allConnected, items);
        return items;
    }

    // ---- 整份自检（实例连不上）----

    @Test
    void uploaded项目不再要求三条本地产物路径() throws Exception {
        Map<String, Object> res = checker(store(), TOKEN).check(
                uploaded(repo(), JAVA, "cpp://192.0.2.1:6500", "rust://192.0.2.1:6600"));
        for (String local : List.of("classesDir", "cppObjectsDir", "rustBinary", "classesMatchSource")) {
            assertNull(item(res, local), "uploaded 项目按设计没有本地产物，自检却还在查 " + local);
        }
        // 源码根照查：源码来自平台本机的 git 工作树，不跟着产物走
        assertNotNull(item(res, "javaSourceRoot"));
    }

    @Test
    void 平台没配令牌时产物上传接口这一项不通过() throws Exception {
        Map<String, Object> it = item(checker(store(), "").check(uploaded(repo(), JAVA)), "artifactUpload");
        assertNotNull(it, "uploaded 项目的自检里没有「产物上传接口」一项");
        assertEquals(false, it.get("ok"));
        String d = String.valueOf(it.get("detail"));
        // 要说清后果（CI 推不进产物）和出路（配哪个变量）
        assertTrue(d.contains("COVERAGE_ARTIFACT_TOKEN"), d);
        assertTrue(d.contains("推不进"), d);
    }

    @Test
    void 平台配了令牌时产物上传接口这一项通过() throws Exception {
        Map<String, Object> it = item(checker(store(), TOKEN).check(uploaded(repo(), JAVA)), "artifactUpload");
        assertNotNull(it);
        assertEquals(true, it.get("ok"));
    }

    @Test
    void 只有Go实例时也不出产物上传接口这一项() throws Exception {
        // 纯 Go 项目不推任何产物：平台没配令牌也不该因此拦住它的创建
        Map<String, Object> res = checker(store(), "").check(uploaded(repo(), "go://192.0.2.1:6400"));
        assertNull(item(res, "artifactUpload"), "纯 Go 项目不推产物，却被「产物上传接口」拦住");
    }

    @Test
    void local项目照旧查本地产物路径且不出新的两项() throws Exception {
        ProjectConfig c = uploaded(repo(), JAVA);
        c.setArtifactSource("local");
        c.setClassesDir(tmp.resolve("no-such-classes").toString());
        Map<String, Object> res = checker(store(), TOKEN).check(c);
        assertNotNull(item(res, "classesDir"), "local 项目的本地产物路径检查不能丢");
        assertNull(item(res, "artifactUpload"));
        assertNull(item(res, "artifacts"));
    }

    @Test
    void 产物来源拼错时单独点名() throws Exception {
        // 创建时会被 400 挡下；自检这里不说的话，向导会一路绿灯走到「创建」才撞墙
        ProjectConfig c = uploaded(repo(), JAVA);
        c.setArtifactSource("upload");
        Map<String, Object> it = item(checker(store(), TOKEN).check(c), "artifactSource");
        assertNotNull(it, "产物来源填错了，自检却没有单报一项");
        assertEquals(false, it.get("ok"));
        String d = String.valueOf(it.get("detail"));
        assertTrue(d.contains("local") && d.contains("uploaded"), d);
    }

    // ---- 全连上之后：按实例自报的构建取产物 ----

    @Test
    void 产物没推时不通过并点名构建语言与补救命令() throws Exception {
        ProjectChecker.CheckItem it = find(
                uploadedItems(checker(store(), TOKEN), uploaded(repo(), JAVA), true, SHA), "artifacts");
        assertNotNull(it, "全连上了却没有「产物仓库」一项");
        assertFalse(it.ok());
        assertTrue(it.detail().contains(SHA), it.detail());
        assertTrue(it.detail().contains("java"), it.detail());
        // 不带 ?project= 的补救命令会把包传进 default 项目、接口还回 200 —— 照提示做了却没用
        assertTrue(it.detail().contains("project=up"), it.detail());
    }

    @Test
    void 产物推上来之后通过并对取回的classes核对源码() throws Exception {
        ArtifactStore store = store();
        pushJava(store, SHA, "com/shop/order/OrderService.class");
        List<ProjectChecker.CheckItem> items =
                uploadedItems(checker(store, TOKEN), uploaded(repo(), JAVA), true, SHA);
        ProjectChecker.CheckItem art = find(items, "artifacts");
        assertNotNull(art);
        assertTrue(art.ok(), art.detail());
        ProjectChecker.CheckItem cm = find(items, "classesMatchSource");
        assertNotNull(cm, "取回的 Java 产物没做「是不是同一个工程」的核对");
        assertTrue(cm.ok(), cm.detail());
    }

    @Test
    void 推上来的产物与源码不是同一个工程时点名() throws Exception {
        // CI 推错了模块：与 local 下 classes-dir 指错工程是同一类静默错误
        ArtifactStore store = store();
        pushJava(store, SHA, "com/other/Thing.class", "com/other/Stuff.class");
        ProjectChecker.CheckItem cm = find(
                uploadedItems(checker(store, TOKEN), uploaded(repo(), JAVA), true, SHA), "classesMatchSource");
        assertNotNull(cm);
        assertFalse(cm.ok());
        // 产物来自仓库，再叫人「把 classes-dir 改成绝对路径」就指错了方向
        assertFalse(cm.detail().contains("classes-dir 填绝对路径"), cm.detail());
        assertTrue(cm.detail().contains("产物仓库"), cm.detail());
    }

    @Test
    void 推上来的包带前缀目录时照样认得出同一个工程() throws Exception {
        // CI 在仓库根执行 zip -r java.zip target/classes 打出来的就是这个形状。
        // 采集照常出数（JaCoCo 按字节码里的类名认，不看目录层级），自检不能反过来把它拦下 ——
        // 否则就是「自检说不行、采集却取得到」
        ArtifactStore store = store();
        pushJava(store, SHA, "target/classes/com/shop/order/OrderService.class");
        ProjectChecker.CheckItem cm = find(
                uploadedItems(checker(store, TOKEN), uploaded(repo(), JAVA), true, SHA), "classesMatchSource");
        assertNotNull(cm);
        assertTrue(cm.ok(), cm.detail());
    }

    @Test
    void 版本串两端带空白时与采集同样看作同一个版本() throws Exception {
        // 采集侧先 parseId（会去掉两端空白）再比 commit 与 dirty；自检若按原始字符串比，
        // 一台带换行一台不带就会被判成「版本不统一」，把一个能用的配置拦下
        ArtifactStore store = store();
        pushJava(store, SHA, "com/shop/order/OrderService.class");
        ProjectChecker.CheckItem it = find(uploadedItems(checker(store, TOKEN),
                uploaded(repo(), JAVA, "java://192.0.2.2:6300"), true, SHA, SHA + "\n"), "artifacts");
        assertNotNull(it);
        assertTrue(it.ok(), it.detail());
    }

    @Test
    void 脏构建不通过() throws Exception {
        ProjectChecker.CheckItem it = find(
                uploadedItems(checker(store(), TOKEN), uploaded(repo(), JAVA), true, SHA + "-dirty"), "artifacts");
        assertNotNull(it);
        assertFalse(it.ok());
        assertTrue(it.detail().contains("-dirty"), it.detail());
    }

    @Test
    void 各实例版本不一致时不通过() throws Exception {
        ProjectChecker.CheckItem it = find(uploadedItems(checker(store(), TOKEN),
                uploaded(repo(), JAVA, "java://192.0.2.2:6300"), true, SHA, SHA2), "artifacts");
        assertNotNull(it);
        assertFalse(it.ok());
        assertTrue(it.detail().contains("不一致"), it.detail());
    }

    @Test
    void 有实例没上报版本时不通过() throws Exception {
        ProjectChecker.CheckItem it = find(uploadedItems(checker(store(), TOKEN),
                uploaded(repo(), JAVA, "java://192.0.2.2:6300"), true, SHA, null), "artifacts");
        assertNotNull(it);
        assertFalse(it.ok());
        assertTrue(it.detail().contains("没上报"), it.detail());
    }

    @Test
    void 版本串不是commit时原样点名() throws Exception {
        // 交给通用那句「没上报或不一致」的话，人会去查一个根本不存在的版本不一致
        ProjectChecker.CheckItem it = find(
                uploadedItems(checker(store(), TOKEN), uploaded(repo(), JAVA), true, "v1.2.3"), "artifacts");
        assertNotNull(it);
        assertFalse(it.ok());
        assertTrue(it.detail().contains("v1.2.3"), it.detail());
        assertTrue(it.detail().contains("40 位"), it.detail());
    }

    @Test
    void 只有Go实例时不出产物仓库这一项() throws Exception {
        // Go 的覆盖数据是自包含的，不需要任何产物
        assertNull(find(uploadedItems(checker(store(), TOKEN),
                uploaded(repo(), "go://192.0.2.1:6400"), true, SHA), "artifacts"));
    }

    @Test
    void 实例没全连上时不出产物仓库这一项() throws Exception {
        // 连不上的实例根本没报版本，此时判「产物在不在」是拿一个不完整的版本集合下结论
        assertNull(find(uploadedItems(checker(store(), TOKEN), uploaded(repo(), JAVA), false, SHA), "artifacts"));
    }

    @Test
    void 只取有实例的那几种语言的产物() throws Exception {
        // 只配了 Java 与 Go：C++ / Rust 没推产物不该成为不通过的理由
        ArtifactStore store = store();
        pushJava(store, SHA, "com/shop/order/OrderService.class");
        ProjectChecker.CheckItem it = find(uploadedItems(checker(store, TOKEN),
                uploaded(repo(), JAVA, "go://192.0.2.1:6400"), true, SHA, SHA), "artifacts");
        assertNotNull(it);
        assertTrue(it.ok(), it.detail());
    }
}
