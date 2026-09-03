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
