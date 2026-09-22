package com.rtcc.platform.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解压膨胀的两道闸。
 *
 * <p><b>上传包本身的 200MB 上限拦不住这个</b>：一个高压缩比的包压着几十 KB、
 * 解开能把平台磁盘写满。而平台的磁盘是共享的 —— 写满之后挂掉的不只是产物仓库，
 * 是整个平台（趋势入库、gcov 的临时文件、日志，全都要写盘）。
 *
 * <p>两道闸分别挡两类膨胀，缺一不可：字节数挡「少数巨大条目」，
 * 条目数挡「海量空条目」—— 后者总字节几乎为零，字节闸一点都拦不住。
 *
 * <p>用真实的 zip、真实的临时目录，不喂假流。
 */
class ArtifactUnzipLimitTest {

    private static final String OK = "77842897548da30523c688d97389c6d33e84a2d5";

    /**
     * 造一个真实的高压缩比包：内容是 {@code size} 字节的零，压缩后只有千分之一左右。
     * 这正是 zip bomb 的形态 —— 不需要任何特制工具，一段全零就够了。
     */
    private static byte[] highRatioZip(String name, long size) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry(name));
            byte[] chunk = new byte[64 * 1024];
            long written = 0;
            while (written < size) {
                int n = (int) Math.min(chunk.length, size - written);
                zip.write(chunk, 0, n);
                written += n;
            }
            zip.closeEntry();
        }
        return bos.toByteArray();
    }

    /** 造一个 n 条目的包，每个条目都是空的 —— 总字节数几乎为零，字节闸拦不住 */
    private static byte[] manyEntriesZip(int n) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            for (int i = 0; i < n; i++) {
                zip.putNextEntry(new ZipEntry("c/" + i + ".class"));
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static byte[] normalZip(String name, String content) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }

    /** 记下从压缩流真正读了多少字节 —— 用来证明中止发生在「读完整个条目」之前 */
    private static final class Counting extends FilterInputStream {
        private long read;

        Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int c = super.read();
            if (c >= 0) {
                read++;
            }
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                read += n;
            }
            return n;
        }
    }

    /** buildId 目录下不许留下任何东西：既没有正式目录，也没有解压到一半的 .tmp- */
    private static void assertNothingLeftBehind(ArtifactStore store, Path root) throws IOException {
        Path dir = store.dirOf("demo", OK, ArtifactKind.JAVA);
        assertFalse(Files.exists(dir), "失败的上传不该留下正式产物目录：" + dir);
        Path parent = dir.getParent();
        if (Files.exists(parent)) {
            try (Stream<Path> s = Files.list(parent)) {
                List<Path> left = s.toList();
                assertTrue(left.isEmpty(),
                        "失败的上传在 " + parent + " 下留了残骸：" + left
                                + " —— find() 只看「存在且非空」，残骸会被当成就绪产物");
            }
        }
    }

    @Test
    void 解压后超过字节上限当场中止(@TempDir Path root) throws Exception {
        // 上限 1MB，包里是 50MB 的零（压缩后只有几十 KB，轻松穿过 200MB 的上传上限）
        ArtifactStore store = new ArtifactStore(root, 10, 1024 * 1024, 100_000);
        byte[] bomb = highRatioZip("Big.class", 50L * 1024 * 1024);

        BadArtifactException e = assertThrows(BadArtifactException.class,
                () -> store.save("demo", OK, ArtifactKind.JAVA, new ByteArrayInputStream(bomb)));

        // 报错要点名是哪一道闸，否则运维只能去翻代码才知道该调哪一项
        assertTrue(e.getMessage().contains("artifact-max-unzipped-bytes"), e.getMessage());
        assertTrue(e.getMessage().contains("Big.class"),
                "要点名是哪个条目撑破的：" + e.getMessage());
        assertNothingLeftBehind(store, root);
    }

    /**
     * <b>这条才是真正的防护</b>：撞上上限时必须<b>当场</b>停，而不是把整个条目写完再报错。
     *
     * <p>{@code Files.copy(in, out)} 那种写法也能让上面那条用例通过 —— 它照样抛、
     * 照样清理，只是在 50MB 全部落盘<b>之后</b>。一个声明 10GB 的条目就足以把磁盘写满，
     * 而「事后报错」对此毫无帮助。
     *
     * <p>判据：从压缩流读走的字节数。解压出 1MB 只需要读约 1KB 压缩数据，
     * 而整个包有几十 KB —— 真读完了就说明是写完才判的。
     */
    @Test
    void 撞上上限时没有把整个条目读完写完(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10, 1024 * 1024, 100_000);
        byte[] bomb = highRatioZip("Big.class", 50L * 1024 * 1024);
        Counting counting = new Counting(new ByteArrayInputStream(bomb));

        assertThrows(BadArtifactException.class,
                () -> store.save("demo", OK, ArtifactKind.JAVA, counting));

        assertTrue(counting.read < bomb.length / 4L,
                "撞上 1MB 上限后仍从压缩流读走了 " + counting.read + " / " + bomb.length
                        + " 字节 —— 说明是把整个条目写完才判的，"
                        + "那样一个足够大的条目在被发现之前就已经把磁盘写满了");
    }

    @Test
    void 条目数超过上限当场中止(@TempDir Path root) throws Exception {
        // 字节上限给得很宽松：这一包的总字节数几乎为零，要挡住它只能靠条目数这道闸
        ArtifactStore store = new ArtifactStore(root, 10, 1024L * 1024 * 1024, 100);
        byte[] many = manyEntriesZip(500);

        BadArtifactException e = assertThrows(BadArtifactException.class,
                () -> store.save("demo", OK, ArtifactKind.JAVA, new ByteArrayInputStream(many)));

        assertTrue(e.getMessage().contains("artifact-max-entries"), e.getMessage());
        assertNothingLeftBehind(store, root);
    }

    /**
     * 正常产物不能被误伤。实测本仓库最大的一份真实产物是平台自己的 100 个文件 2.8MB，
     * 默认上限（1 GiB / 10 万条目）比它宽 380 倍。
     */
    @Test
    void 正常产物在默认上限下照常存得下(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);

        store.save("demo", OK, ArtifactKind.JAVA,
                new ByteArrayInputStream(normalZip("A.class", "cafebabe")));

        Path f = store.dirOf("demo", OK, ArtifactKind.JAVA).resolve("A.class");
        assertTrue(Files.exists(f), "正常产物被误伤了");
        assertEquals("cafebabe", Files.readString(f));
        assertEquals(ArtifactStore.DEFAULT_MAX_UNZIPPED_BYTES, store.maxUnzippedBytes());
        assertEquals(ArtifactStore.DEFAULT_MAX_ENTRIES, store.maxEntries());
    }

    /**
     * 重传一个膨胀包时，<b>已经在服役的那份旧产物必须完好无损</b>。
     *
     * <p>这是 {@code save} 最要紧的一条保证：绝不能用半成品去顶替一份好产物。
     * 新加的两道闸都在「挪开旧产物」之前触发，所以旧的根本没被动过 ——
     * 但这件事必须有用例守着，否则哪天有人把上限检查挪到换入之后，
     * 一个 bomb 就能把正在用的产物换成空气，而平台只会说「这个构建没上传产物」。
     */
    @Test
    void 膨胀包被拒时旧产物原封不动(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10, 1024 * 1024, 100_000);
        store.save("demo", OK, ArtifactKind.JAVA,
                new ByteArrayInputStream(normalZip("Good.class", "原来那份")));

        byte[] bomb = highRatioZip("Big.class", 50L * 1024 * 1024);
        assertThrows(BadArtifactException.class,
                () -> store.save("demo", OK, ArtifactKind.JAVA, new ByteArrayInputStream(bomb)));

        Path good = store.dirOf("demo", OK, ArtifactKind.JAVA).resolve("Good.class");
        assertTrue(Files.exists(good), "旧产物被一个被拒绝的上传搞没了");
        assertEquals("原来那份", Files.readString(good));
        // 连 bomb 的那个条目名都不该出现
        assertFalse(Files.exists(store.dirOf("demo", OK, ArtifactKind.JAVA).resolve("Big.class")));
    }

    /**
     * 默认值写在三个地方：这里的常量、{@code CoverageProperties} 的字段、application.yml。
     * 前两处漂移了不会有任何提示 —— yml 一旦不配这两项（或换一份不带它们的配置文件），
     * 实际生效的就是 {@code CoverageProperties} 那份，而文档与注释说的是常量这份。
     */
    @Test
    void 两处默认值必须一致() {
        com.rtcc.platform.config.CoverageProperties props =
                new com.rtcc.platform.config.CoverageProperties();

        assertEquals(ArtifactStore.DEFAULT_MAX_UNZIPPED_BYTES, props.getArtifactMaxUnzippedBytes(),
                "CoverageProperties 的默认值与 ArtifactStore 的常量漂移了");
        assertEquals(ArtifactStore.DEFAULT_MAX_ENTRIES, props.getArtifactMaxEntries(),
                "CoverageProperties 的默认值与 ArtifactStore 的常量漂移了");
    }

    /**
     * 两个上限配成 0 或负数会让<b>每一次上传都失败</b>，报的却是「解压后超过上限」——
     * 一句与真正原因（配置写错了）毫不相干的话。与 artifact-keep 同样，宁可起不来。
     */
    @Test
    void 上限配成零或负数时起不来并点名(@TempDir Path root) {
        for (long bad : new long[]{0, -1}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new ArtifactStore(root, 10, bad, 100_000), "maxUnzippedBytes=" + bad);
            assertTrue(e.getMessage().contains("artifact-max-unzipped-bytes"), e.getMessage());
        }
        for (int bad : new int[]{0, -1}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new ArtifactStore(root, 10, 1024, bad), "maxEntries=" + bad);
            assertTrue(e.getMessage().contains("artifact-max-entries"), e.getMessage());
        }
    }
}
