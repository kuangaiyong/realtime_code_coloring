package com.rtcc.platform.collector;

import com.rtcc.platform.config.CoverageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 增量口径的正确性直接决定用户会不会补错测试，因此这里建一个真实 git 仓库、
 * 跑真实 git 命令来验证，不构造假的 diff 文本。
 */
class GitServiceTest {

    @TempDir
    Path repo;

    private GitService git;
    private Path foo;
    private String baseline;

    private static final String FOO_V1 = """
            package com.demo;

            public class Foo {
                int a() { return 1; }
                int b() { return 2; }
            }
            """;

    @BeforeEach
    void buildRepo() throws Exception {
        Path srcRoot = repo.resolve("svc/src/main/java/com/demo");
        Files.createDirectories(srcRoot);
        foo = srcRoot.resolve("Foo.java");

        run("init", "-q");
        run("config", "user.email", "t@example.com");
        run("config", "user.name", "t");

        Files.writeString(foo, FOO_V1, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("README.md"), "仓库根下的文件，不该进入源码口径\n", StandardCharsets.UTF_8);
        run("add", "-A");
        run("commit", "-qm", "baseline");
        baseline = run("rev-parse", "HEAD").strip();

        CoverageProperties props = new CoverageProperties();
        props.setRepoDir(repo.toString());
        props.setJavaSourceRoot("svc/src/main/java");
        git = new GitService(props);
    }

    @Test
    void 只统计新增与修改的行号() throws Exception {
        // 第 5 行改了，第 6 行是新增的
        Files.writeString(foo, """
                package com.demo;

                public class Foo {
                    int a() { return 1; }
                    int b() { return 22; }
                    int c() { return 3; }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("README.md"), "改了根目录文件\n", StandardCharsets.UTF_8);
        run("commit", "-aqm", "change");
        String head = run("rev-parse", "HEAD").strip();

        Map<String, Set<Integer>> changed = git.changedLines(baseline, head);

        assertEquals(Set.of("svc/src/main/java/com/demo/Foo.java"), changed.keySet(),
                "源码根之外的文件不该进入增量口径");
        assertEquals(Set.of(5, 6), changed.get("svc/src/main/java/com/demo/Foo.java"));
    }

    @Test
    void 文件被删除时不产生可染色的行() throws Exception {
        run("rm", "-q", "svc/src/main/java/com/demo/Foo.java");
        run("commit", "-qm", "delete");
        String head = run("rev-parse", "HEAD").strip();

        assertTrue(git.changedLines(baseline, head).isEmpty(),
                "被删掉的文件没有可染色的载体，不该出现在增量结果里");
    }

    @Test
    void 产物与源码一致时无漂移() throws Exception {
        assertEquals(List.of(), git.sourceDrift(baseline));
    }

    @Test
    void 未提交的源码改动也算漂移() throws Exception {
        // 只写工作树不提交：这正是「产物是旧的、源码已经改了」的典型现场
        Files.writeString(foo, FOO_V1 + "// 本地改了一行\n", StandardCharsets.UTF_8);

        assertEquals(List.of("svc/src/main/java/com/demo/Foo.java"), git.sourceDrift(baseline));
    }

    @Test
    void 仓库其他位置的改动不算源码漂移() throws Exception {
        Files.writeString(repo.resolve("README.md"), "只动了平台自己的文件\n", StandardCharsets.UTF_8);

        assertEquals(List.of(), git.sourceDrift(baseline),
                "被测源码没变就不该阻断增量报告，否则任何一次无关提交都会误报");
    }

    /**
     * git 的 pathspec 为空不是「什么都不比」而是「整仓都比」。
     * 一个源码根都没配还照常放行的话，README、脚本的改动都会被算成「被测源码漂移」，
     * 增量口径从此永久 409，提示里点名的却是与被测服务毫无关系的文件。
     */
    @Test
    void 一个源码根都没配时直接报错而不是退化成整仓diff() throws Exception {
        CoverageProperties bare = new CoverageProperties();
        bare.setRepoDir(repo.toString());
        GitService noRoots = new GitService(bare);
        Files.writeString(repo.resolve("README.md"), "只动了与被测服务无关的文件\n", StandardCharsets.UTF_8);

        IOException e = assertThrows(IOException.class, () -> noRoots.sourceDrift(baseline));
        assertTrue(e.getMessage().contains("源码根"), e.getMessage());
        assertThrows(IOException.class, () -> noRoots.changedLines(baseline, baseline));
    }

    @Test
    void ref可以是分支名或相对引用() throws Exception {
        assertEquals(baseline, git.resolve("HEAD"));
    }

    @Test
    void 不存在的ref直接报错而不是返回空结果() {
        IOException e = assertThrows(IOException.class, () -> git.resolve("no-such-branch"));
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
    }

    /** baseline 来自用户输入，不能让它被 git 当成选项解析 */
    @Test
    void 拒绝会被当作选项的ref() {
        assertThrows(IOException.class, () -> git.resolve("--output=pwned"));
        assertThrows(IOException.class, () -> git.resolve("-c"));
    }

    private String run(String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args) + " 失败：" + out);
        return out;
    }
}
