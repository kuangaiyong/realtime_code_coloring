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
