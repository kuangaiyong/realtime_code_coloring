# 产物仓库（容器化接入的平台侧）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让平台能按 `buildId` 接收、存放、取用被测服务的编译产物，从而在「平台够不着容器文件系统」时仍解得出行号。

**Architecture:** 新增 `ArtifactStore`（按 `<root>/<projectId>/<buildId>/<lang>/` 存放，保留最近 N 个）与 `ArtifactController`（上传/查询/删除）。`ProjectConfig` 加 `artifactSource`：`local` 走现有的本地路径（默认，行为逐字不变），`uploaded` 从 store 解析路径。**四个 Analyzer 一行不改** —— Java 的路径本来就由外部传，C++/Rust 则通过「喂一份产物路径已替换的配置副本」实现。

**Tech Stack:** Java 17 / Spring Boot / JUnit 5 / 零构建前端（本计划不涉及前端）

**Spec:** `docs/specs/2026-09-02-containerized-target-coverage-design.md`

## Global Constraints

- **禁止 mock / 桩 / 假数据**。所有验证跑真实服务、真实文件系统、真实 HTTP。
- **基线不得下降**：全量 `bash scripts/run_local.sh verify` 必须 **≥ 233 条 PASS / 0 FAIL**；单测必须 **≥ 164 全过**。
- **每个任务红-绿**：先写会失败的测试、跑一次看它真的失败、再实现、再跑绿。
- 顺序固定 `stop` → `start` → `verify`；改了 `static/` 或资源目录必须重新 `mvn package`，**打包前先停平台**（它握着那个 jar）。
- 一切输出用中文，含代码注释、commit message、测试里的打印文案。
- 只在 `dev` 分支提交，不新建分支，不合并 `main`。
- `buildId` 格式为 `^[0-9a-f]{40}$`（`BuildVersion.SESSION_ID` 允许 `-dirty` 后缀，但**产物上传一律拒绝 `-dirty`**）。
- **本计划不含 §7.2 的容器端到端验证**（本机无 Docker）。计划完成后方案仍是「裸机上全绿、容器里未验」。

**Maven 命令前缀**（本机 JDK/Maven 不在 PATH）：

```bash
export JAVA_HOME=/c/Users/Administrator/devtools/jdk-17.0.20+8
export PATH="$JAVA_HOME/bin:/c/Users/Administrator/devtools/apache-maven-3.9.16/bin:$PATH"
```

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `platform/src/main/java/com/rtcc/platform/artifact/ArtifactStore.java`（新建） | 按 buildId 存取产物目录；保留最近 N 个；校验 buildId |
| `platform/src/main/java/com/rtcc/platform/artifact/ArtifactKind.java`（新建） | 三种产物（java/cpp/rust）的枚举与落盘子目录名 |
| `platform/src/main/java/com/rtcc/platform/web/ArtifactController.java`（新建） | 上传 / 查询 / 删除；大小上限与错误码 |
| `platform/src/main/java/com/rtcc/platform/config/ProjectConfig.java`（改） | 加 `artifactSource` |
| `platform/src/main/java/com/rtcc/platform/config/CoverageProperties.java`（改） | 加 `artifactSource`，并在 `toProjectConfig` 里带过去 |
| `platform/src/main/java/com/rtcc/platform/service/ProjectRuntimeFactory.java`（改） | `uploaded` 模式下给 C++/Rust 的 Analyzer 喂替换过的配置副本 |
| `platform/src/main/java/com/rtcc/platform/service/ProjectRuntime.java`（改） | Java 的 classesDir 按模式解析；取不到产物时 `ANALYZE_ERROR` 并点名 |
| `platform/src/main/resources/application.yml`（改） | 三个平台级配置项 |

新建包 `com.rtcc.platform.artifact` —— 产物存储与覆盖率归一化是两件事，不该混进 `collector`。

---

## Task 1: ArtifactStore 的路径解析与 buildId 校验

**Files:**
- Create: `platform/src/main/java/com/rtcc/platform/artifact/ArtifactKind.java`
- Create: `platform/src/main/java/com/rtcc/platform/artifact/ArtifactStore.java`
- Test: `platform/src/test/java/com/rtcc/platform/artifact/ArtifactStoreTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum ArtifactKind { JAVA("java"), CPP("cpp"), RUST("rust"); String dir(); static ArtifactKind of(String s) }`（`of` 认不出时抛 `IllegalArgumentException`）
  - `class ArtifactStore { ArtifactStore(Path root, int keep); Path dirOf(String projectId, String buildId, ArtifactKind kind); void requireValidBuildId(String buildId) }`
  - `requireValidBuildId` 对非 40 位 hex、含 `-dirty`、含 `..` 或分隔符的一律抛 `IllegalArgumentException`

- [ ] **Step 1: 写会失败的测试**

`platform/src/test/java/com/rtcc/platform/artifact/ArtifactStoreTest.java`：

```java
package com.rtcc.platform.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 产物按 buildId 索引 —— 这是整个容器方案的地基，不是实现细节。
 *
 * <p>按固定路径存的话，推上去的是 commit A 的产物、容器里跑的是 commit B，
 * 平台会拿 A 的字节码解 B 的探针数据，<b>算出行号错位却看起来完全正常的报告</b>。
 * 这正是本项目最不能犯的错。
 */
class ArtifactStoreTest {

    private static final String OK = "77842897548da30523c688d97389c6d33e84a2d5";

    @Test
    void 按项目与构建分目录(@TempDir Path root) {
        ArtifactStore store = new ArtifactStore(root, 10);

        Path p = store.dirOf("demo", OK, ArtifactKind.JAVA);

        assertEquals(root.resolve("demo").resolve(OK).resolve("java"), p);
    }

    /**
     * buildId 直接参与磁盘路径，不校验就是路径穿越 ——
     * 一个 ../../ 能让上传接口写到平台的任意位置。
     */
    @Test
    void 路径穿越一律拒绝(@TempDir Path root) {
        ArtifactStore store = new ArtifactStore(root, 10);

        for (String bad : new String[]{"../../etc", "..", "a/b", "a\\b", "", "  "}) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.requireValidBuildId(bad), "应拒绝：" + bad);
        }
    }

    /**
     * -dirty 意味着同一个 commit 可以对应无数份不同的产物。允许上传就等于允许
     * 「同一个 key 指向不同内容」，取出来的可能不是这个容器加载的那份 ——
     * 仍然是行号错位且看不出来。
     */
    @Test
    void 脏构建拒绝上传(@TempDir Path root) {
        ArtifactStore store = new ArtifactStore(root, 10);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.requireValidBuildId(OK + "-dirty"));
        assertTrue(e.getMessage().contains("dirty"), e.getMessage());
    }

    @Test
    void 合法的四十位sha放行(@TempDir Path root) {
        assertDoesNotThrow(() -> new ArtifactStore(root, 10).requireValidBuildId(OK));
    }

    @Test
    void 认不出来的语言明确报错() {
        assertEquals(ArtifactKind.CPP, ArtifactKind.of("cpp"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ArtifactKind.of("python"));
        // 报错要说清可用的是哪些，否则调用方只能去翻代码
        assertTrue(e.getMessage().contains("java"), e.getMessage());
    }

    /** Go 不占目录：它的 meta/counters 全从网络来，平台不需要任何产物 */
    @Test
    void Go不是一种产物() {
        assertThrows(IllegalArgumentException.class, () -> ArtifactKind.of("go"));
    }
}
```

- [ ] **Step 2: 跑测试，确认它失败**

```bash
cd platform && mvn -B test -Dtest=ArtifactStoreTest
```

Expected: 编译失败，`ArtifactStore`/`ArtifactKind` 不存在。

- [ ] **Step 3: 写实现**

`platform/src/main/java/com/rtcc/platform/artifact/ArtifactKind.java`：

```java
package com.rtcc.platform.artifact;

/**
 * 平台需要落盘保存的产物种类。
 *
 * <p><b>没有 GO</b>：Go 的 meta / counters 全从探针的网络接口来，是自包含的，
 * 平台不需要它的任何编译产物 —— 这也是四种语言里 Go 最容易容器化的原因。
 */
public enum ArtifactKind {

    /** Java：.class 目录树。JaCoCo 要靠字节码把探针 id 还原成行号 */
    JAVA("java"),
    /** C++：.gcno。编译期产物，记的是相对源码名 */
    CPP("cpp"),
    /** Rust：产物本身，行号在它自带的 coverage mapping 里 */
    RUST("rust");

    private final String dir;

    ArtifactKind(String dir) {
        this.dir = dir;
    }

    /** 落盘时的子目录名 */
    public String dir() {
        return dir;
    }

    public static ArtifactKind of(String s) {
        for (ArtifactKind k : values()) {
            if (k.dir.equalsIgnoreCase(s)) {
                return k;
            }
        }
        // 说清可用的是哪些 —— 只报「不认识」的话，调用方只能去翻代码。
        // 特别是 go：它不是漏了，是压根不需要产物
        throw new IllegalArgumentException("不认识的产物类型：" + s
                + "，可用的是 java / cpp / rust（Go 不需要产物，它的覆盖数据是自包含的）");
    }
}
```

`platform/src/main/java/com/rtcc/platform/artifact/ArtifactStore.java`：

```java
package com.rtcc.platform.artifact;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 按 buildId 存放被测服务的编译产物。
 *
 * <p><b>为什么以 buildId 为唯一索引</b>（整个容器方案的地基，不是实现细节）：
 * 按固定路径存的话，推上去的是 commit A 的产物、容器里跑的是 commit B，
 * 平台会拿 A 的字节码解 B 的探针数据，算出<b>行号错位却看起来完全正常</b>的报告。
 * 按 buildId 索引之后，取不到就是明确的「这个构建的产物没上传」。
 *
 * <p>而 buildId 恰好已经是实例自报的那个值（见 {@code BuildVersion}），
 * 两端天然对齐，不需要新的版本标识。
 */
public class ArtifactStore {

    /**
     * 只认 40 位小写 hex。<b>buildId 直接参与磁盘路径</b>，不校验就是路径穿越 ——
     * 一个 {@code ../../} 能让上传接口写到平台的任意位置。
     */
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");

    private final Path root;
    private final int keep;

    public ArtifactStore(Path root, int keep) {
        this.root = root;
        this.keep = keep;
    }

    public Path root() {
        return root;
    }

    public int keep() {
        return keep;
    }

    /** {@code <root>/<projectId>/<buildId>/<lang>/} */
    public Path dirOf(String projectId, String buildId, ArtifactKind kind) {
        requireValidBuildId(buildId);
        return root.resolve(projectId).resolve(buildId).resolve(kind.dir());
    }

    /**
     * 校验 buildId 能不能安全地当目录名用。
     *
     * <p>拒绝 {@code -dirty}：它意味着同一个 commit 可以对应无数份不同的产物，
     * 允许上传就等于允许「同一个 key 指向不同内容」，取出来的可能不是
     * 这个容器加载的那份 —— 仍然是行号错位且看不出来。
     */
    public void requireValidBuildId(String buildId) {
        if (buildId == null || buildId.isBlank()) {
            throw new IllegalArgumentException("buildId 不能为空");
        }
        if (buildId.endsWith("-dirty")) {
            throw new IllegalArgumentException("拒绝脏构建的产物：" + buildId
                    + "。工作树脏时同一个 commit 可以对应多份不同的产物，"
                    + "按它取回的可能不是被测进程加载的那一份，算出的行号会错位且看不出来");
        }
        if (!SHA.matcher(buildId).matches()) {
            throw new IllegalArgumentException("buildId 必须是 40 位小写十六进制，实际为：" + buildId);
        }
    }
}
```

- [ ] **Step 4: 跑测试，确认全绿**

```bash
cd platform && mvn -B test -Dtest=ArtifactStoreTest
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add platform/src/main/java/com/rtcc/platform/artifact platform/src/test/java/com/rtcc/platform/artifact
git commit -m "$(cat <<'EOF'
产物仓库：按 buildId 分目录，并挡住路径穿越与脏构建

buildId 直接参与磁盘路径，不校验就是路径穿越；而 -dirty 意味着同一个 commit
可以对应无数份产物，按它取回的可能不是被测进程加载的那一份 —— 行号错位且看不出来。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: 存入、取回与「保留最近 N 个」

**Files:**
- Modify: `platform/src/main/java/com/rtcc/platform/artifact/ArtifactStore.java`
- Test: `platform/src/test/java/com/rtcc/platform/artifact/ArtifactStoreKeepTest.java`

**Interfaces:**
- Consumes: Task 1 的 `ArtifactStore`、`ArtifactKind`
- Produces:
  - `void save(String projectId, String buildId, ArtifactKind kind, InputStream zip) throws IOException` —— 解压 zip 到目标目录，先清空该目录；存完调用保留策略
  - `Optional<Path> find(String projectId, String buildId, ArtifactKind kind)` —— 目录存在且非空才返回
  - `List<String> builds(String projectId)` —— 按目录 mtime 倒序（新的在前）
  - `int prune(String projectId)` —— 删掉超出 `keep` 的最旧构建，返回删了几个

- [ ] **Step 1: 写会失败的测试**

`platform/src/test/java/com/rtcc/platform/artifact/ArtifactStoreKeepTest.java`：

```java
package com.rtcc.platform.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 存入 / 取回 / 保留最近 N 个。
 *
 * <p>用真实的 zip 与真实的临时目录，不喂假流 —— 这几条守的正是
 * 「产物传过去之后还是不是原来那份」。
 */
class ArtifactStoreKeepTest {

    private static byte[] zipOf(String name, String content) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }

    private static String sha(int n) {
        return String.format("%040x", java.math.BigInteger.valueOf(n));
    }

    @Test
    void 存进去的内容取回来逐字一致(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        byte[] zip = zipOf("com/shop/Order.class", "字节码占位");

        store.save("demo", sha(1), ArtifactKind.JAVA, new ByteArrayInputStream(zip));

        Path dir = store.find("demo", sha(1), ArtifactKind.JAVA).orElseThrow();
        assertEquals("字节码占位",
                Files.readString(dir.resolve("com/shop/Order.class"), StandardCharsets.UTF_8));
    }

    /** 没上传过就是没上传过 —— 返回空目录会被上游读成「这个构建没有代码」 */
    @Test
    void 没存过的构建取不到(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);

        assertTrue(store.find("demo", sha(9), ArtifactKind.JAVA).isEmpty());
    }

    /** 重传同一个 buildId 要整体替换，不能与上一次的残留混在一起 */
    @Test
    void 重传时先清空旧内容(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        store.save("demo", sha(1), ArtifactKind.JAVA,
                new ByteArrayInputStream(zipOf("old.class", "旧")));

        store.save("demo", sha(1), ArtifactKind.JAVA,
                new ByteArrayInputStream(zipOf("new.class", "新")));

        Path dir = store.find("demo", sha(1), ArtifactKind.JAVA).orElseThrow();
        assertFalse(Files.exists(dir.resolve("old.class")), "旧内容没被清掉，两次构建的产物混在了一起");
        assertTrue(Files.exists(dir.resolve("new.class")));
    }

    /**
     * 保留最近 N 个。<b>不按天数</b>：一个长期不发布的服务会把自己正在跑的那份产物清掉，
     * 而那时平台会开始拒绝出报告 —— 一个由「太久没发版」引发的故障，没人查得到。
     */
    @Test
    void 超出保留数时删最旧的(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 3);
        for (int i = 1; i <= 5; i++) {
            store.save("demo", sha(i), ArtifactKind.JAVA,
                    new ByteArrayInputStream(zipOf("a.class", "第 " + i + " 次")));
            // mtime 的精度在有些文件系统上只有秒级，隔开一点才排得出先后
            Thread.sleep(1100);
        }

        List<String> left = store.builds("demo");
        assertEquals(3, left.size(), "保留数是 3，实际留下 " + left);
        assertTrue(store.find("demo", sha(5), ArtifactKind.JAVA).isPresent(), "最新的必须在");
        assertTrue(store.find("demo", sha(1), ArtifactKind.JAVA).isEmpty(), "最旧的应该被删了");
    }

    /** 另一个项目的产物不该被这个项目的保留策略牵连 */
    @Test
    void 保留策略按项目各算各的(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 1);
        store.save("a", sha(1), ArtifactKind.JAVA, new ByteArrayInputStream(zipOf("x", "1")));
        store.save("b", sha(2), ArtifactKind.JAVA, new ByteArrayInputStream(zipOf("x", "2")));

        assertTrue(store.find("a", sha(1), ArtifactKind.JAVA).isPresent());
        assertTrue(store.find("b", sha(2), ArtifactKind.JAVA).isPresent());
    }

    /**
     * zip 里带 ../ 的条目能写到目标目录之外（Zip Slip）。
     * 上传接口是<b>写</b>平台磁盘的，这条必须挡住。
     */
    @Test
    void zip里的路径穿越条目被拒(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        byte[] evil = zipOf("../../pwned.txt", "坏东西");

        assertThrows(java.io.IOException.class, () ->
                store.save("demo", sha(1), ArtifactKind.JAVA, new ByteArrayInputStream(evil)));
        assertFalse(Files.exists(root.resolve("pwned.txt")));
        assertFalse(Files.exists(root.getParent().resolve("pwned.txt")));
    }
}
```

- [ ] **Step 2: 跑测试，确认它失败**

```bash
cd platform && mvn -B test -Dtest=ArtifactStoreKeepTest
```

Expected: 编译失败，`save`/`find`/`builds` 不存在。

- [ ] **Step 3: 写实现**

在 `ArtifactStore` 里补上（`import java.io.*; java.nio.file.*; java.util.*; java.util.zip.*;`）：

```java
    /**
     * 解压一份产物到 {@code <root>/<projectId>/<buildId>/<lang>/}。
     *
     * <p><b>先清空目标目录</b>：重传同一个 buildId 时若与上一次的残留混在一起，
     * 解出来的是两次构建的并集 —— 又是一份看不出错的错数据。
     *
     * <p>存完顺手 {@link #prune}，不必另起一个清理任务。
     */
    public void save(String projectId, String buildId, ArtifactKind kind, InputStream zip)
            throws IOException {
        Path dir = dirOf(projectId, buildId, kind);
        deleteTree(dir);
        Files.createDirectories(dir);
        Path base = dir.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(zip)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                // Zip Slip：条目名里带 ../ 就能写到目标目录之外。
                // 上传接口是写平台磁盘的，这条必须挡住
                Path out = base.resolve(e.getName()).normalize();
                if (!out.startsWith(base)) {
                    throw new IOException("产物包里有指向目标目录之外的条目：" + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        // 目录的 mtime 决定保留顺序，显式刷一下：解压过程中它可能没被更新
        Files.setLastModifiedTime(dir.getParent(), java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
        prune(projectId);
    }

    /** 取不到就是没上传过。返回空目录会被上游读成「这个构建没有代码」 */
    public Optional<Path> find(String projectId, String buildId, ArtifactKind kind) {
        requireValidBuildId(buildId);
        Path dir = root.resolve(projectId).resolve(buildId).resolve(kind.dir());
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (var s = Files.list(dir)) {
            return s.findAny().isPresent() ? Optional.of(dir) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** 这个项目存过哪些构建，新的在前 */
    public List<String> builds(String projectId) {
        Path p = root.resolve(projectId);
        if (!Files.isDirectory(p)) {
            return List.of();
        }
        try (var s = Files.list(p)) {
            return s.filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong((Path d) -> d.toFile().lastModified()).reversed())
                    .map(d -> d.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 删掉超出 {@link #keep} 的最旧构建，返回删了几个。
     *
     * <p><b>按个数而不是按天数</b>：一个长期不发布的服务会把自己正在跑的那份产物清掉，
     * 而那时平台会开始拒绝出报告 —— 一个由「太久没发版」引发的故障，没人查得到。
     */
    public int prune(String projectId) {
        List<String> all = builds(projectId);
        int removed = 0;
        for (int i = keep; i < all.size(); i++) {
            deleteTree(root.resolve(projectId).resolve(all.get(i)));
            removed++;
        }
        return removed;
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件删不掉不该让整次上传失败：下次 prune 会再试
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
```

- [ ] **Step 4: 跑测试，确认全绿**

```bash
cd platform && mvn -B test -Dtest=ArtifactStoreKeepTest
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 红验证 —— 去掉 Zip Slip 防护，确认断言真抓得住**

把 `if (!out.startsWith(base)) throw ...` 那三行注释掉，重跑：

```bash
cd platform && mvn -B test -Dtest=ArtifactStoreKeepTest
```

Expected: `zip里的路径穿越条目被拒` FAIL。确认后把那三行放回去，再跑一次确认全绿。

- [ ] **Step 6: 提交**

```bash
git add platform/src/main/java/com/rtcc/platform/artifact platform/src/test/java/com/rtcc/platform/artifact
git commit -m "$(cat <<'EOF'
产物仓库：存入、取回、保留最近 N 个，并挡住 Zip Slip

重传先清空目标目录 —— 与上一次的残留混在一起，解出来的是两次构建的并集。
保留按个数不按天数：长期不发布的服务会把自己正在跑的那份产物清掉，
而那时平台开始拒绝出报告，是一个由「太久没发版」引发、没人查得到的故障。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 上传 / 查询 / 删除接口

**Files:**
- Create: `platform/src/main/java/com/rtcc/platform/web/ArtifactController.java`
- Modify: `platform/src/main/resources/application.yml`
- Test: `platform/src/test/java/com/rtcc/platform/web/ArtifactControllerTest.java`

**Interfaces:**
- Consumes: Task 2 的 `ArtifactStore`
- Produces:
  - `POST /api/artifacts/{buildId}?lang=java|cpp|rust`，body 为 multipart 字段 `file`
  - `GET /api/artifacts` → `{"artifacts":[{"projectId","buildId","kinds":[...]}]}`
  - `DELETE /api/artifacts/{buildId}`
  - 错误码：400（buildId 非法 / `-dirty` / 语言不认识）、413（超上限，由 Spring 的 multipart 上限产生）

- [ ] **Step 1: 写会失败的测试**

`platform/src/test/java/com/rtcc/platform/web/ArtifactControllerTest.java`：

```java
package com.rtcc.platform.web;

import com.rtcc.platform.artifact.ArtifactKind;
import com.rtcc.platform.artifact.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上传接口。<b>平台此前没有任何文件上传，这是第一处</b> ——
 * 它能<b>写</b>平台磁盘，比只读的探针端口风险高一档。
 */
class ArtifactControllerTest {

    private static final String OK = "77842897548da30523c688d97389c6d33e84a2d5";

    private static MockMultipartFile zip(String name, String content) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry(name));
            z.write(content.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return new MockMultipartFile("file", "a.zip", "application/zip", bos.toByteArray());
    }

    @Test
    void 上传后能查到也能取到(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        ArtifactController c = new ArtifactController(store);

        c.upload("demo", OK, "java", zip("Order.class", "字节码"));

        assertTrue(store.find("demo", OK, ArtifactKind.JAVA).isPresent());
        assertTrue(c.list("demo").get("artifacts").toString().contains(OK));
    }

    @Test
    void 脏构建回四百并说明原因(@TempDir Path root) throws Exception {
        ArtifactController c = new ArtifactController(new ArtifactStore(root, 10));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> c.upload("demo", OK + "-dirty", "java", zip("a", "b")));
        assertEquals(400, e.getStatusCode().value());
        assertTrue(e.getReason().contains("dirty"), e.getReason());
    }

    @Test
    void 路径穿越的buildId回四百(@TempDir Path root) throws Exception {
        ArtifactController c = new ArtifactController(new ArtifactStore(root, 10));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> c.upload("demo", "../../etc", "java", zip("a", "b")));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    void 认不出来的语言回四百(@TempDir Path root) throws Exception {
        ArtifactController c = new ArtifactController(new ArtifactStore(root, 10));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> c.upload("demo", OK, "python", zip("a", "b")));
        assertEquals(400, e.getStatusCode().value());
        assertTrue(e.getReason().contains("java"), e.getReason());
    }

    @Test
    void 空文件回四百而不是存下一个空目录(@TempDir Path root) {
        ArtifactController c = new ArtifactController(new ArtifactStore(root, 10));
        MockMultipartFile empty = new MockMultipartFile("file", "a.zip", "application/zip", new byte[0]);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> c.upload("demo", OK, "java", empty));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    void 删得掉(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        ArtifactController c = new ArtifactController(store);
        c.upload("demo", OK, "java", zip("a.class", "x"));

        c.delete("demo", OK);

        assertTrue(store.find("demo", OK, ArtifactKind.JAVA).isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试，确认它失败**

```bash
cd platform && mvn -B test -Dtest=ArtifactControllerTest
```

Expected: 编译失败，`ArtifactController` 不存在。

- [ ] **Step 3: 写实现**

`platform/src/main/java/com/rtcc/platform/web/ArtifactController.java`：

```java
package com.rtcc.platform.web;

import com.rtcc.platform.artifact.ArtifactKind;
import com.rtcc.platform.artifact.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/**
 * 被测服务的编译产物。给「平台够不着容器文件系统」那种部署用。
 *
 * <p>CI 构建完把产物按 buildId 推上来，平台按实例自报的 buildId 取回去解行号。
 * 详见 {@code docs/specs/2026-09-02-containerized-target-coverage-design.md}。
 *
 * <p><b>安全边界</b>：平台此前没有任何文件上传，这是第一处。探针端口只读、
 * 靠「绑回环 + 网络策略」兜着；这个接口能<b>写</b>平台磁盘，风险高一档。
 * 本方案仍假设平台只面向内网 —— 这条假设是显式的，不是默认的。
 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactController.class);

    private final ArtifactStore store;

    public ArtifactController(ArtifactStore store) {
        this.store = store;
    }

    /**
     * 上传一份产物。
     *
     * @param project 归到哪个项目，默认 default
     * @param buildId 40 位 commit sha。<b>不接受 -dirty</b>，理由见 ArtifactStore
     * @param lang    java / cpp / rust。Go 不需要产物
     * @param file    zip 包
     */
    @PostMapping("/{buildId}")
    public Map<String, Object> upload(@RequestParam(defaultValue = "default") String project,
                                      @PathVariable String buildId,
                                      @RequestParam String lang,
                                      @RequestParam("file") MultipartFile file) {
        ArtifactKind kind = kindOf(lang);
        try {
            store.requireValidBuildId(buildId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        }
        // 空包存下去会变成一个空目录，而空目录与「没上传过」在上游长得一模一样
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "产物包是空的，没有东西可存");
        }
        try (var in = file.getInputStream()) {
            store.save(project, buildId, kind, in);
        } catch (IOException e) {
            // Zip Slip 之类的坏包也走这里 —— 报出原因，别让人对着 500 猜
            throw new ResponseStatusException(BAD_REQUEST, "产物包存不下来：" + e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "产物包存不下来：" + e);
        }
        log.info("已收下产物：项目 {} / 构建 {} / {}（{} 字节）",
                project, buildId, kind.dir(), file.getSize());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("project", project);
        res.put("buildId", buildId);
        res.put("lang", kind.dir());
        res.put("kept", store.builds(project));
        return res;
    }

    /** 存了哪些构建。运维要能看出磁盘上到底有什么，不然清理就是盲的 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "default") String project) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String b : store.builds(project)) {
            List<String> kinds = new ArrayList<>();
            for (ArtifactKind k : ArtifactKind.values()) {
                if (store.find(project, b, k).isPresent()) {
                    kinds.add(k.dir());
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("buildId", b);
            m.put("kinds", kinds);
            rows.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("project", project);
        res.put("keep", store.keep());
        res.put("artifacts", rows);
        return res;
    }

    @DeleteMapping("/{buildId}")
    public Map<String, Object> delete(@RequestParam(defaultValue = "default") String project,
                                      @PathVariable String buildId) {
        try {
            store.requireValidBuildId(buildId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        }
        store.remove(project, buildId);
        return Map.of("ok", true, "project", project, "buildId", buildId);
    }

    private static ArtifactKind kindOf(String lang) {
        try {
            return ArtifactKind.of(lang);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        }
    }
}
```

在 `ArtifactStore` 里补 `remove`：

```java
    /** 删掉一个构建的全部产物 */
    public void remove(String projectId, String buildId) {
        requireValidBuildId(buildId);
        deleteTree(root.resolve(projectId).resolve(buildId));
    }
```

`application.yml` 里加（放在 `coverage:` 下）：

```yaml
  # 产物仓库。给「平台够不着容器文件系统」那种部署用 —— CI 按 buildId 把产物推上来。
  # 这三项是平台级配置（跟着部署机器走），与项目无关，所以在 yml 而不在库里
  artifact-root: ${COVERAGE_ARTIFACT_ROOT:./.artifacts}
  artifact-keep: 10
```

以及 multipart 上限（放在 `spring:` 下，与 `coverage:` 平级）：

```yaml
spring:
  servlet:
    multipart:
      # Spring 默认 1MB，一个 Rust 产物就顶穿。超过这个值框架直接回 413
      max-file-size: 200MB
      max-request-size: 200MB
```

- [ ] **Step 4: 跑测试，确认全绿**

```bash
cd platform && mvn -B test -Dtest=ArtifactControllerTest
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 真实 HTTP 验证（不是 mock）**

```bash
bash scripts/run_local.sh stop
cd platform && mvn -q -B package -DskipTests && cd ..
bash scripts/run_local.sh start
sleep 12

SHA=$(git rev-parse HEAD)
cd platform/target/classes && zip -qr /tmp/probe-classes.zip . && cd ../../..

# 合法上传 → 200
curl -s -X POST "http://localhost:18090/api/artifacts/$SHA?lang=java" \
     -F "file=@/tmp/probe-classes.zip" -w "\nHTTP %{http_code}\n"
# 脏构建 → 400
curl -s -X POST "http://localhost:18090/api/artifacts/$SHA-dirty?lang=java" \
     -F "file=@/tmp/probe-classes.zip" -w "\nHTTP %{http_code}\n"
# 路径穿越 → 400
curl -s -X POST "http://localhost:18090/api/artifacts/..%2F..%2Fetc?lang=java" \
     -F "file=@/tmp/probe-classes.zip" -w "\nHTTP %{http_code}\n"
# 查询
curl -s "http://localhost:18090/api/artifacts" | head -c 300
```

Expected: 依次 200 / 400 / 400；查询结果里有那个 sha。

- [ ] **Step 6: 提交**

```bash
git add platform/src/main/java/com/rtcc/platform/web/ArtifactController.java \
        platform/src/main/java/com/rtcc/platform/artifact/ArtifactStore.java \
        platform/src/main/resources/application.yml \
        platform/src/test/java/com/rtcc/platform/web/ArtifactControllerTest.java
git commit -m "$(cat <<'EOF'
产物上传接口：平台第一处文件上传，显式配上限与校验

Spring 默认 multipart 上限 1MB，一个 Rust 产物就顶穿，必须显式配。
探针端口只读、靠绑回环兜着；这个接口能写平台磁盘，风险高一档 ——
安全边界写进注释，是显式假设而不是默认。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `artifactSource` 配置项与 Bean 装配

**Files:**
- Modify: `platform/src/main/java/com/rtcc/platform/config/ProjectConfig.java`
- Modify: `platform/src/main/java/com/rtcc/platform/config/CoverageProperties.java`
- Create: `platform/src/main/java/com/rtcc/platform/artifact/ArtifactStoreConfig.java`
- Test: `platform/src/test/java/com/rtcc/platform/config/ArtifactSourceDefaultTest.java`

**Interfaces:**
- Consumes: Task 2 的 `ArtifactStore`
- Produces:
  - `ProjectConfig.getArtifactSource()` / `setArtifactSource(String)`，默认 `"local"`
  - `CoverageProperties.getArtifactRoot()` / `getArtifactKeep()`
  - `@Bean ArtifactStore artifactStore(CoverageProperties)`

- [ ] **Step 1: 写会失败的测试**

`platform/src/test/java/com/rtcc/platform/config/ArtifactSourceDefaultTest.java`：

```java
package com.rtcc.platform.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 产物来源默认 local —— <b>现有的裸机部署与 8 实例验证链路必须一行不动</b>。
 * 默认值一旦是 uploaded，所有现存项目会立刻开始「取不到产物」而拒绝出报告。
 */
class ArtifactSourceDefaultTest {

    @Test
    void 默认走本地路径() {
        assertEquals("local", new ProjectConfig().getArtifactSource());
        assertEquals("local", new CoverageProperties().getArtifactSource());
    }

    @Test
    void 平台配置能带进项目配置() {
        CoverageProperties p = new CoverageProperties();
        p.setArtifactSource("uploaded");

        assertEquals("uploaded", p.toProjectConfig().getArtifactSource());
    }
}
```

- [ ] **Step 2: 跑测试，确认它失败**

```bash
cd platform && mvn -B test -Dtest=ArtifactSourceDefaultTest
```

Expected: 编译失败，`getArtifactSource` 不存在。

- [ ] **Step 3: 写实现**

`ProjectConfig` 里加字段与读写方法（放在 `rustBinary` 之后）：

```java
    /**
     * 产物从哪来：{@code local}（配置里那几个本地路径，默认）或 {@code uploaded}
     * （按 buildId 从产物仓库取）。
     *
     * <p><b>默认必须是 local</b>：现有的裸机部署与 8 实例的全量验证链路都靠它，
     * 默认值一旦改成 uploaded，所有现存项目会立刻开始「取不到产物」而拒绝出报告。
     */
    private String artifactSource = "local";
```

```java
    public String getArtifactSource() { return artifactSource; }
    public void setArtifactSource(String artifactSource) { this.artifactSource = artifactSource; }

    /** 产物是否来自按 buildId 索引的仓库。判定集中在这里，免得各处各写各的字符串比较 */
    public boolean usesUploadedArtifacts() { return "uploaded".equalsIgnoreCase(artifactSource); }
```

`CoverageProperties` 同样加 `artifactSource`（默认 `"local"`）、`artifactRoot`（默认 `"./.artifacts"`）、`artifactKeep`（默认 `10`）三个字段与读写方法，并在 `toProjectConfig()` 里把 `artifactSource` 带过去（找到该方法，在设置 `rustBinary` 的相邻位置加一行 `cfg.setArtifactSource(artifactSource);`）。

`platform/src/main/java/com/rtcc/platform/artifact/ArtifactStoreConfig.java`：

```java
package com.rtcc.platform.artifact;

import com.rtcc.platform.config.CoverageProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 产物仓库是<b>平台级</b>设施：它的根目录与保留数跟着部署机器走，与项目无关 ——
 * 与 go-tool / gcov-tool 那几项工具链路径同一个道理，所以读 yml 而不读库。
 */
@Configuration
public class ArtifactStoreConfig {

    @Bean
    public ArtifactStore artifactStore(CoverageProperties props) {
        return new ArtifactStore(Path.of(props.getArtifactRoot()).toAbsolutePath().normalize(),
                props.getArtifactKeep());
    }
}
```

- [ ] **Step 4: 跑测试，确认全绿**

```bash
cd platform && mvn -B test -Dtest=ArtifactSourceDefaultTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add platform/src/main/java/com/rtcc/platform/config platform/src/main/java/com/rtcc/platform/artifact/ArtifactStoreConfig.java platform/src/test/java/com/rtcc/platform/config/ArtifactSourceDefaultTest.java
git commit -m "$(cat <<'EOF'
加 artifactSource 配置项，默认 local 保持现有行为逐字不变

默认值一旦是 uploaded，所有现存项目会立刻开始「取不到产物」而拒绝出报告 ——
裸机部署与 8 实例的全量验证链路都靠这个默认值。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: 按模式解析产物路径（四个 Analyzer 一行不改）

**Files:**
- Modify: `platform/src/main/java/com/rtcc/platform/service/ProjectRuntimeFactory.java`
- Modify: `platform/src/main/java/com/rtcc/platform/service/ProjectRuntime.java:417`（Java 的 classesDir）
- Modify: `platform/src/main/java/com/rtcc/platform/service/ProjectRuntime.java:545`（perInstance 里的同一处）
- Test: `platform/src/test/java/com/rtcc/platform/artifact/ArtifactResolveTest.java`

**Interfaces:**
- Consumes: Task 2 的 `ArtifactStore`、Task 4 的 `ProjectConfig.usesUploadedArtifacts()`
- Produces:
  - `ArtifactStore.resolveInto(ProjectConfig cfg, String buildId)` → 返回一份**副本**，其 `classesDir` / `cppObjectsDir` / `rustBinary` 已替换为解压目录；`local` 模式下原样返回入参
  - 取不到时抛 `IOException`，消息里含 buildId 与语言

> **为什么是「换一份配置副本」而不是「把路径传进去」**：三个 Analyzer 拿产物路径的方式并不一致 ——
> `CoverageAnalyzer.analyze(execStore, File classesDir, String sourceRoot)` 由外部传，
> 而 `CppCoverageAnalyzer.analyze(List<byte[]>)` 与 `RustCoverageAnalyzer.analyze(List<byte[]>)`
> **自己从构造时拿到的 `ProjectConfig` 里读**。后两者压根没有接收路径的入参。
> 详见 spec §4.2 的 2026-09-03 修正。

- [ ] **Step 1: 写会失败的测试**

`platform/src/test/java/com/rtcc/platform/artifact/ArtifactResolveTest.java`：

```java
package com.rtcc.platform.artifact;

import com.rtcc.platform.config.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 把配置里的本地产物路径换成「按 buildId 解压出来的目录」。
 *
 * <p><b>四个 Analyzer 一行不改</b>：C++ 与 Rust 的 Analyzer 自己从 ProjectConfig
 * 读产物路径，没有接收路径的入参 —— 所以换的是喂给它们的那份配置，不是它们本身。
 */
class ArtifactResolveTest {

    private static final String SHA = "77842897548da30523c688d97389c6d33e84a2d5";

    private static byte[] zip(String name) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry(name));
            z.write("x".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
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

    /** local 模式下必须原样返回 —— 现有的裸机部署与 8 实例验证链路都走这条 */
    @Test
    void 本地模式原样返回(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        ProjectConfig in = cfg("local");

        ProjectConfig out = store.resolveInto(in, SHA);

        assertSame(in, out, "local 模式不该复制配置，更不该改路径");
    }

    @Test
    void 上传模式把三个产物路径换成解压目录(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        store.save("demo", SHA, ArtifactKind.JAVA, new ByteArrayInputStream(zip("A.class")));
        store.save("demo", SHA, ArtifactKind.CPP, new ByteArrayInputStream(zip("a.gcno")));
        store.save("demo", SHA, ArtifactKind.RUST, new ByteArrayInputStream(zip("svc.exe")));

        ProjectConfig out = store.resolveInto(cfg("uploaded"), SHA);

        assertTrue(out.getClassesDir().endsWith("java"), out.getClassesDir());
        assertTrue(out.getCppObjectsDir().endsWith("cpp"), out.getCppObjectsDir());
        assertTrue(out.getRustBinary().contains(SHA), out.getRustBinary());
    }

    /** 换的是副本，原来那份不能被改 —— 它还在库里代表这个项目的配置 */
    @Test
    void 不改动传进来的那份配置(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        store.save("demo", SHA, ArtifactKind.JAVA, new ByteArrayInputStream(zip("A.class")));
        ProjectConfig in = cfg("uploaded");

        store.resolveInto(in, SHA);

        assertEquals("原来的/classes", in.getClassesDir(), "入参被就地改了");
    }

    /**
     * <b>取不到产物一律拒绝出报告。</b>跳过那门语言的话，界面上表现为
     * 「这些代码没被调用过」—— 与真相完全相反，而且看不出是缺产物。
     */
    @Test
    void 取不到产物时报错并点名(@TempDir Path root) {
        ArtifactStore store = new ArtifactStore(root, 10);

        IOException e = assertThrows(IOException.class,
                () -> store.resolveInto(cfg("uploaded"), SHA));
        assertTrue(e.getMessage().contains(SHA), e.getMessage());
    }

    /** 没有 buildId 时（实例没配 sessionid）同样不能猜一个路径出来 */
    @Test
    void 没有构建版本时明确报错(@TempDir Path root) {
        ArtifactStore store = new ArtifactStore(root, 10);

        IOException e = assertThrows(IOException.class,
                () -> store.resolveInto(cfg("uploaded"), null));
        assertTrue(e.getMessage().contains("构建版本"), e.getMessage());
    }
}
```

- [ ] **Step 2: 跑测试，确认它失败**

```bash
cd platform && mvn -B test -Dtest=ArtifactResolveTest
```

Expected: 编译失败，`resolveInto` 不存在。

- [ ] **Step 3: 写实现**

在 `ArtifactStore` 里补：

```java
    /**
     * 按模式解析出这次归一化该用的产物路径。
     *
     * <p>{@code local} 模式原样返回入参 —— 现有的裸机部署走的就是这条，一步都不多做。
     *
     * <p>{@code uploaded} 模式返回一份<b>副本</b>，其中三个产物路径被换成
     * 按 buildId 解压出来的目录。<b>为什么是换配置而不是传路径</b>：
     * {@code CppCoverageAnalyzer} 与 {@code RustCoverageAnalyzer} 自己从
     * {@code ProjectConfig} 读产物路径，没有接收路径的入参 ——
     * 换掉喂给它们的那份配置，它们就不必知道那个目录是配置里写的还是解压出来的。
     * 四个 Analyzer 因此一行不用改。
     *
     * @throws IOException 取不到产物时。<b>不降级、不跳过</b>：跳过那门语言的话，
     *                     界面上表现为「这些代码没被调用过」，与真相完全相反
     */
    public ProjectConfig resolveInto(ProjectConfig cfg, String buildId) throws IOException {
        if (!cfg.usesUploadedArtifacts()) {
            return cfg;
        }
        if (buildId == null || buildId.isBlank()) {
            throw new IOException("产物按构建版本索引，但这些实例没有上报构建版本"
                    + "（Java 的 sessionid / 其余语言的 COVERAGE_BUILD_ID）—— 无从知道该取哪一份产物");
        }
        String clean = buildId.endsWith("-dirty") ? buildId.substring(0, buildId.length() - 6) : buildId;
        ProjectConfig copy = cfg.copy();
        copy.setClassesDir(need(cfg, clean, ArtifactKind.JAVA, cfg.getClassesDir()));
        copy.setCppObjectsDir(need(cfg, clean, ArtifactKind.CPP, cfg.getCppObjectsDir()));
        copy.setRustBinary(need(cfg, clean, ArtifactKind.RUST, cfg.getRustBinary()));
        return copy;
    }

    /**
     * 这门语言原本就没配（这个项目不测它）时保持为空；配了却取不到，就是缺产物，报错。
     * Rust 那一项要的是产物<b>文件</b>而不是目录，取解压目录里的第一个文件。
     */
    private String need(ProjectConfig cfg, String buildId, ArtifactKind kind, String original)
            throws IOException {
        if (original == null || original.isBlank()) {
            return original;
        }
        Path dir = find(cfg.getId(), buildId, kind).orElseThrow(() -> new IOException(
                "缺少构建 " + buildId + " 的 " + kind.dir() + " 产物。"
                        + "请在构建后调 POST /api/artifacts/" + buildId + "?lang=" + kind.dir()
                        + " 把它推上来 —— 没有它解不出行号，"
                        + "而跳过这门语言会让界面显示成「这些代码没被调用过」"));
        if (kind == ArtifactKind.RUST) {
            try (var s = Files.list(dir)) {
                return s.filter(Files::isRegularFile).findFirst()
                        .orElseThrow(() -> new IOException("构建 " + buildId + " 的 rust 产物目录是空的"))
                        .toString();
            }
        }
        return dir.toString();
    }
```

在 `ProjectConfig` 里补一个浅拷贝（字段逐个复制；`instances` / `goExclude` 这类集合另建一份，免得两边共享同一个可变列表）：

```java
    /**
     * 复制一份，用于「产物路径按 buildId 替换」而不影响库里那份配置。
     *
     * <p>集合字段另建一份：共享同一个可变列表的话，改副本会连带改到原配置 ——
     * 而原配置正代表这个项目在库里的状态。
     */
    public ProjectConfig copy() {
        ProjectConfig c = new ProjectConfig();
        c.setId(id);
        c.setName(name);
        c.setInstances(new ArrayList<>(instances));
        c.setRepoDir(repoDir);
        c.setBaseline(baseline);
        c.setClassesDir(classesDir);
        c.setJavaSourceRoot(javaSourceRoot);
        c.setGoSourceRoot(goSourceRoot);
        c.setGoModulePath(goModulePath);
        c.setGoExclude(new ArrayList<>(goExclude));
        c.setCppSourceRoot(cppSourceRoot);
        c.setCppObjectsDir(cppObjectsDir);
        c.setRustSourceRoot(rustSourceRoot);
        c.setRustBinary(rustBinary);
        c.setArtifactSource(artifactSource);
        c.setIntervalMs(intervalMs);
        c.setTimeoutMs(timeoutMs);
        c.setGate(gate);
        return c;
    }
```

> 实现时以 `ProjectConfig` 里**实际存在的字段**为准，逐个复制，不要漏。
> 漏一个字段的后果是「uploaded 模式下那一项悄悄变回默认值」——
> 静默且难查，所以下一步要专门测它。

- [ ] **Step 4: 跑测试，确认全绿**

```bash
cd platform && mvn -B test -Dtest=ArtifactResolveTest
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 补一条「copy 没漏字段」的测试**

追加到 `ArtifactResolveTest`：

```java
    /**
     * copy() 漏掉任何一个字段，后果都是「uploaded 模式下那一项悄悄变回默认值」——
     * 比如 baseline 丢了，增量口径会拿错基线算出一份看不出错的报告。
     * 用反射逐个字段比对，加字段时这条会自动跟上。
     */
    @Test
    void 复制不漏任何字段() throws Exception {
        ProjectConfig src = new ProjectConfig();
        src.setId("p"); src.setName("n"); src.setRepoDir("r"); src.setBaseline("b");
        src.setClassesDir("c"); src.setJavaSourceRoot("j");
        src.setGoSourceRoot("g"); src.setGoModulePath("m");
        src.setCppSourceRoot("cs"); src.setCppObjectsDir("co");
        src.setRustSourceRoot("rs"); src.setRustBinary("rb");
        src.setArtifactSource("uploaded");
        src.setTimeoutMs(1234);

        ProjectConfig copy = src.copy();

        for (java.lang.reflect.Field f : ProjectConfig.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object a = f.get(src), b = f.get(copy);
            if (a instanceof java.util.List<?> la) {
                assertEquals(la, b, "集合字段 " + f.getName() + " 没复制");
            } else {
                assertEquals(a, b, "字段 " + f.getName() + " 没复制 —— "
                        + "uploaded 模式下它会悄悄变回默认值");
            }
        }
    }
```

跑：`cd platform && mvn -B test -Dtest=ArtifactResolveTest`，Expected: `Tests run: 6` 全绿。

- [ ] **Step 6: 接进 ProjectRuntime 与 Factory**

`ProjectRuntimeFactory`：把 `ArtifactStore` 注入进来，`create` 时不改（Analyzer 仍用原 cfg 造）；
真正的替换发生在**采集那一刻**，因为 buildId 要等实例自报之后才知道。

因此在 `ProjectRuntime` 里：
- 构造函数多收一个 `ArtifactStore store`；
- `doCollect` 中拿到 `unifiedVersion(reported)` 之后、进入归一化之前，用
  `ProjectConfig effective = store.resolveInto(props, buildIdOf(reported))` 得到有效配置；
- Java 那两处 `new File(props.getClassesDir())` 改为 `new File(effective.getClassesDir())`；
- C++/Rust 的 Analyzer 需要用 `effective` 重新造一个（`new CppCoverageAnalyzer(effective, platform)`），
  **仅在 `props.usesUploadedArtifacts()` 为真时**，否则沿用构造时注入的那个（零开销、行为逐字不变）；
- `resolveInto` 抛出的 `IOException` 落进现有的 `catch` → `setProbeStatus("ANALYZE_ERROR", describe(e))`，
  错误信息里已经含 buildId 与语言。

> **这一步改动面较大且牵涉并行归一化的代码，实现时务必：**
> 1. 先只做 `local` 路径（`resolveInto` 直接返回 `props`），跑一次全量 `verify` 确认 **233 条不掉**；
> 2. 再接 `uploaded` 分支。两步之间各提交一次。

- [ ] **Step 7: 跑全量验证**

```bash
bash scripts/run_local.sh stop
cd platform && mvn -q -B package -DskipTests && cd ..
bash scripts/run_local.sh start
bash scripts/run_local.sh verify 2>&1 | tail -5
```

Expected: **≥ 233 PASS / 0 FAIL**（默认 `local`，行为必须逐字不变）

- [ ] **Step 8: 提交**

```bash
git add platform/src/main/java/com/rtcc/platform platform/src/test/java/com/rtcc/platform
git commit -m "$(cat <<'EOF'
按 buildId 解析产物路径，四个 Analyzer 一行不改

C++ 与 Rust 的 Analyzer 自己从 ProjectConfig 读产物路径、没有接收路径的入参，
所以换的是喂给它们的那份配置副本，不是它们本身 —— 分层没被破坏。
（spec §4.2 原先写的「解析好再传」只对 Java 成立，已在 2026-09-03 修正。）

取不到产物一律拒绝出报告并点名缺哪个 buildId 的哪种产物：跳过那门语言的话，
界面上表现为「这些代码没被调用过」，与真相完全相反且看不出是缺产物。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: 端到端真实验证 —— uploaded 模式真能解出行号

**Files:**
- Create: `scripts/e2e_artifact.py`
- Modify: `scripts/run_local.sh`（把新用例加进 `verify` 序列）

**Interfaces:**
- Consumes: Task 3 的上传接口、Task 5 的 `uploaded` 模式
- Produces: 一个可重复跑的 E2E 用例

> **这一步是本计划里唯一能证明方案真的成立的东西。** 前五个任务都只证明了
> 「组件各自没错」；这一条证明「产物传过去、按 buildId 取回来、解出的行号是对的」。
> 它在裸机上做得到 —— 把 Java 的 classes 打包上传、建一个 `uploaded` 模式的项目、
> 让它对着同一批实例采集，断言覆盖数据与 `local` 模式**逐行一致**。
> §7.2 里真正验不了的只剩「容器里」这个条件本身。

- [ ] **Step 1: 写用例**

`scripts/e2e_artifact.py`（结构照抄 `scripts/e2e_project.py` 的 http 辅助与打印风格）：

要点（实现时逐条落成断言）：

1. `git rev-parse HEAD` 拿到干净的 40 位 sha（工作树必须干净，否则跳过并明确说明）；
2. 把 `demo-service/target/classes` 打成 zip，`POST /api/artifacts/<sha>?lang=java&project=artifact-e2e`；
3. 建项目 `artifact-e2e`：`artifactSource=uploaded`、只配 Java 两个实例、`classesDir` 故意填一个**不存在的本地路径**（证明用的是上传的那份，不是本地的）；
4. `POST /api/projects/artifact-e2e/collect`，断言 `probeStatus=CONNECTED` 且文件数 == 默认项目里 Java 文件数；
5. **逐行比对**：取 `artifact-e2e` 与 `default` 两个项目同一个 Java 文件的 `fileDetail`，断言 `rows` 的行号与状态**集合相等** —— 这是「解出的行号真的对」的唯一硬证据；
6. 删掉那份产物（`DELETE /api/artifacts/<sha>`），再采一次，断言 `probeStatus=ANALYZE_ERROR` 且 `lastError` 里**含那个 sha**；
7. 收尾：删项目、删产物，用例可重复跑。

- [ ] **Step 2: 跑它，确认它失败**

```bash
python scripts/e2e_artifact.py
```

Expected: 在第 4 或 5 步失败（此时 uploaded 模式刚接好，若有 bug 会在这里暴露）。
若一次就全绿，**回头确认第 3 步的 `classesDir` 真的填了不存在的路径** ——
否则这条用例可能在偷偷走 local 的数据。

- [ ] **Step 3: 修到全绿**

- [ ] **Step 4: 接进 verify 序列**

在 `scripts/run_local.sh` 的 `verify` 中，把 `e2e_artifact.py` 加在 `e2e_rust.py` 之后、
`e2e_project.py` **之前**（`e2e_project.py` 必须排最后，它跑场景会清零计数器）。

- [ ] **Step 5: 跑全量**

```bash
bash scripts/run_local.sh stop && bash scripts/run_local.sh start
bash scripts/run_local.sh verify 2>&1 | tail -5
```

Expected: **≥ 233 + 新增条数 PASS / 0 FAIL**

- [ ] **Step 6: 提交**

```bash
git add scripts/e2e_artifact.py scripts/run_local.sh
git commit -m "$(cat <<'EOF'
E2E：uploaded 模式解出的行号与 local 模式逐行一致

这是本方案唯一的硬证据 —— 前面几步只证明组件各自没错。
故意把 classesDir 填成不存在的路径，确保用的是上传那份而不是本地的；
再删掉产物，断言拒绝出报告且点名缺哪个 buildId。

容器里那一条仍未验（本机无 Docker），见 spec §7.2。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: 文档同步

**Files:**
- Modify: `CLAUDE.md`（§三 工具链依赖表下方、§二 核心功能清单）
- Modify: `docs/specs/2026-09-02-containerized-target-coverage-design.md`（§七 勾掉已验的）

- [ ] **Step 1: 更新 CLAUDE.md**

在 §三 加一小节「产物仓库（容器化部署时才用）」：说明 `artifact-source` 两种模式、
默认 `local`、产物按 buildId 索引、`-dirty` 不收、保留最近 N 个、
以及**上传接口能写平台磁盘、仍假设内网**这条安全边界。

核心功能清单：**不新增条目**。按「少了就不能用、错了就出严重故障」的判定，
`uploaded` 模式少了平台照常用（裸机部署不受影响）；但**错了会出静默错误**
（拿错 buildId 的产物 → 行号错位却看起来正常），所以把它并进 **#7（产物与源码版本一致性校验）**
的断言范围，并在注里写明判定过程。

- [ ] **Step 2: 更新 spec §七**

把 7.1 中已经真实验证过的逐条标注「✅ 已验证（Task N）」，
7.2 保持原样并再次强调「容器里那一条仍未验」。

- [ ] **Step 3: 跑最终全量**

```bash
bash scripts/run_local.sh stop && bash scripts/run_local.sh start
bash scripts/run_local.sh verify 2>&1 | tail -5
cd platform && mvn -B test 2>&1 | grep "Tests run:" | tail -1
```

Expected: verify **0 FAIL**；单测 **≥ 164 + 新增** 全过。

- [ ] **Step 4: 代码评审（项目规则 6）**

```
/code-review 评审本次全部改动（产物仓库）。重点：上传接口的安全边界、
Zip Slip 与路径穿越、ProjectConfig.copy 有无漏字段、local 模式行为是否逐字不变、
取不到产物时是否真的拒绝出报告而不是降级。
```

critical 问题修完再进下一步。

- [ ] **Step 5: 提交并推送**

```bash
git add -A && git commit -m "..." && git push origin dev
```

---

## 自查

**Spec 覆盖：**

| Spec 章节 | 对应任务 |
|---|---|
| §4.2 新增/改动的单元 | Task 1–5 |
| §4.3 存储布局 + buildId 校验 | Task 1、2 |
| §4.4 上传接口 + multipart 上限 + 安全边界 | Task 3 |
| §4.5 保留策略 | Task 2 |
| §五 配置 | Task 3、4 |
| §3.3 取不到产物拒绝出报告 | Task 5、6 |
| §3.4 拒绝 `-dirty` | Task 1、3 |
| §六 影响面（#7 / #13） | Task 7 |
| §7.1 全部七条 | Task 1–6 |
| §7.2 | **不做**，计划头部已声明 |

