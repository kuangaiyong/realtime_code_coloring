package com.rtcc.platform.collector;

import com.rtcc.platform.config.ProjectConfig;
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

        ProjectConfig props = new ProjectConfig();
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

        Map<String, Set<Integer>> changed = git.changedLines(baseline, head).lines();

        assertEquals(Set.of("svc/src/main/java/com/demo/Foo.java"), changed.keySet(),
                "源码根之外的文件不该进入增量口径");
        assertEquals(Set.of(5, 6), changed.get("svc/src/main/java/com/demo/Foo.java"));
    }

    @Test
    void 分得清新增文件与修改文件() throws Exception {
        // 一个改、一个新增：两者在 diff 里的区别只在基线侧是不是 /dev/null
        Files.writeString(foo, FOO_V1.replace("return 2;", "return 22;"), StandardCharsets.UTF_8);
        Files.writeString(foo.getParent().resolve("Bar.java"), """
                package com.demo;

                public class Bar {
                    int z() { return 9; }
                }
                """, StandardCharsets.UTF_8);
        run("add", "-A");
        run("commit", "-qm", "add and modify");
        String head = run("rev-parse", "HEAD").strip();

        GitService.Changes ch = git.changedLines(baseline, head);

        assertEquals(Set.of("svc/src/main/java/com/demo/Bar.java"), ch.addedPaths(),
                "基线里本来就有的文件被标成了新增，或新增的文件没被标出来");
        assertTrue(ch.lines().containsKey("svc/src/main/java/com/demo/Foo.java"),
                "改过的文件仍应在变更行里");
        assertTrue(ch.addedPaths().stream().allMatch(ch.lines()::containsKey),
                "addedPaths 里出现了一个 lines 里根本没有的路径，前端会拿它标一个不存在的文件");
    }

    /**
     * 改名 + 改内容：{@code -M} 下 git 给出的基线侧是<b>原路径</b>而不是 /dev/null。
     * 判成新增的话，一次重命名会让整个文件的每一行都变成「这次新写的代码」，
     * 增量分母凭空涨一大截，比例被压低 —— 而页面上只表现为「覆盖率怎么掉了」。
     */
    @Test
    void 改名不算新增() throws Exception {
        run("mv", "svc/src/main/java/com/demo/Foo.java", "svc/src/main/java/com/demo/Renamed.java");
        Files.writeString(foo.getParent().resolve("Renamed.java"),
                FOO_V1.replace("return 2;", "return 22;"), StandardCharsets.UTF_8);
        run("add", "-A");
        run("commit", "-qm", "rename and modify");
        String head = run("rev-parse", "HEAD").strip();

        GitService.Changes ch = git.changedLines(baseline, head);

        assertTrue(ch.lines().containsKey("svc/src/main/java/com/demo/Renamed.java"),
                "改名后的文件应按新路径进入增量范围：" + ch.lines().keySet());
        assertEquals(Set.of(), ch.addedPaths(),
                "改名被当成了新增，整个文件都会算进这次的增量分母");
    }

    @Test
    void 新增文件的每一行都算变更行() throws Exception {
        Path bar = foo.getParent().resolve("Bar.java");
        // 4 行代码 + 1 个空行：新增文件没有基线可比，整份都是新的
        Files.writeString(bar, """
                package com.demo;

                public class Bar {
                    int z() { return 9; }
                }
                """, StandardCharsets.UTF_8);
        run("add", "-A");
        run("commit", "-qm", "add file");
        String head = run("rev-parse", "HEAD").strip();

        GitService.Changes ch = git.changedLines(baseline, head);
        assertEquals(Set.of(1, 2, 3, 4, 5), ch.lines().get("svc/src/main/java/com/demo/Bar.java"));
        assertEquals(Set.of("svc/src/main/java/com/demo/Bar.java"), ch.addedPaths());
    }

    @Test
    void 文件被删除时不产生可染色的行() throws Exception {
        run("rm", "-q", "svc/src/main/java/com/demo/Foo.java");
        run("commit", "-qm", "delete");
        String head = run("rev-parse", "HEAD").strip();

        assertTrue(git.changedLines(baseline, head).lines().isEmpty(),
                "被删掉的文件没有可染色的载体，不该出现在增量结果里");
    }

    /**
     * 「增量基线」这个字段人填不出来，往往不是不懂概念，是不知道这个仓库里有什么可填。
     * 候选必须来自真实仓库：前端写死 main 的话，主干叫 master 的仓库会拿到一个
     * 选了就报错的选项 —— 比不给建议更糟，因为人会以为是平台坏了。
     */
    @Test
    void 基线候选来自真实仓库且每一项都能解析() throws Exception {
        // 补第二个提交，HEAD~1 才成立 —— buildRepo 只提交了一次
        Files.writeString(foo, FOO_V1.replace("return 2;", "return 22;"), StandardCharsets.UTF_8);
        run("commit", "-aqm", "second");
        run("branch", "release-1.0");
        run("tag", "v1.0.0");
        run("tag", "v1.1.0");

        List<GitService.BaselineRef> got = git.baselineCandidates().candidates();

        // 每一项都必须是选中即可用的：解析不了的候选比没有候选更糟
        for (GitService.BaselineRef r : got) {
            assertDoesNotThrow(() -> git.resolve(r.ref()),
                    "候选 " + r.ref() + " 解析不了，选了就报错");
        }
        List<String> refs = got.stream().map(GitService.BaselineRef::ref).toList();
        assertTrue(refs.contains("release-1.0"), "本地分支没进候选：" + refs);
        assertTrue(refs.contains("v1.1.0") && refs.contains("v1.0.0"), "tag 没进候选：" + refs);
        assertTrue(refs.contains("HEAD~1"), "「上一个提交」没进候选：" + refs);

        Map<String, String> kindOf = got.stream().collect(java.util.stream.Collectors.toMap(
                GitService.BaselineRef::ref, GitService.BaselineRef::kind, (a, b) -> a));
        assertEquals("branch", kindOf.get("release-1.0"));
        assertEquals("tag", kindOf.get("v1.0.0"));
        assertEquals("relative", kindOf.get("HEAD~1"));
    }

    /**
     * 只有一个提交的新仓库里没有「上一个提交」。照给不误的话，
     * 人选了它会撞上 409 拒判，而那是他能选到的唯一一个「相对」选项。
     */
    @Test
    void 只有一个提交时不给出HEAD前一个() throws Exception {
        // buildRepo 只提交了一次，此处 HEAD~1 本就不存在
        List<String> refs = git.baselineCandidates().candidates().stream()
                .map(GitService.BaselineRef::ref).toList();
        assertFalse(refs.contains("HEAD~1"),
                "仓库只有一个提交，却把 HEAD~1 当成候选给了出去：" + refs);
    }

    /**
     * {@code refs/remotes/origin/HEAD} 是符号引用，选它等于「跟远端此刻默认指向的那个
     * 分支比」，而它指哪个分支不写在这个名字里，人无从判断自己选的是什么。
     *
     * <p>坑在于 <b>git 把它缩写成「origin」而不是「origin/HEAD」</b>：按短名过滤
     * 一个都拦不住，而候选里多出来的那个「origin」看上去像个正经分支。
     * 这一条是先在真实仓库上跑出来、才发现按短名判是错的。
     */
    @Test
    void 不把远端的符号引用当成候选() throws Exception {
        // 真实的 clone 一定有 refs/remotes/origin/HEAD 这一条
        run("update-ref", "refs/remotes/origin/main", baseline);
        run("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main");

        List<String> refs = git.baselineCandidates().candidates().stream()
                .map(GitService.BaselineRef::ref).toList();
        assertTrue(refs.contains("origin/main"), "远端分支没进候选：" + refs);
        assertFalse(refs.contains("origin"), "origin/HEAD 缩写成的「origin」混进了候选：" + refs);
        assertFalse(refs.contains("origin/HEAD"), refs.toString());
    }

    /**
     * git 允许的分支名比 SAFE_REF 宽得多：{@code feature/添加登录}、
     * {@code wip+experiment}、{@code _internal} 都是合法分支（实测）。
     * 照单全收就会给出一个<b>平台自己推荐、选中却报「ref 不合法」</b>的选项 ——
     * 人只会认为是平台坏了。而滤掉之后必须报出个数，
     * 否则分支叫 feature/添加登录 的人会对着一个没有自己那个分支的列表发愁。
     */
    @Test
    void 名字不合法的引用不进候选且报出被滤掉的个数() throws Exception {
        run("branch", "feature/添加登录");
        run("branch", "wip+experiment");
        run("branch", "_internal");
        run("branch", "release-2.0");

        GitService.Baselines b = git.baselineCandidates();
        List<String> refs = b.candidates().stream().map(GitService.BaselineRef::ref).toList();

        for (GitService.BaselineRef r : b.candidates()) {
            assertDoesNotThrow(() -> git.resolve(r.ref()),
                    "候选 " + r.ref() + " 是平台自己推荐的，选中却解析不了");
        }
        assertTrue(refs.contains("release-2.0"), "合法分支被误滤：" + refs);
        assertEquals(3, b.skipped(),
                "被滤掉的个数没报准，界面上就无从解释「我的分支为什么不在列表里」：" + refs);
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
        ProjectConfig bare = new ProjectConfig();
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
