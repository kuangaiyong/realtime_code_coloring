package com.rtcc.platform.web;

import com.rtcc.platform.artifact.ArtifactKind;
import com.rtcc.platform.artifact.ArtifactOperationException;
import com.rtcc.platform.artifact.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
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

    /** 造一个内容压不动的真 zip，好让截断一定落在数据流中间而不是恰好切在条目边界 */
    private static byte[] 压不动的zip() throws Exception {
        byte[] 内容 = new byte[200_000];
        new Random(42).nextBytes(内容);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("big.class"));
            z.write(内容);
            z.closeEntry();
        }
        return bos.toByteArray();
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

        ArtifactOperationException e = assertThrows(ArtifactOperationException.class,
                () -> c.upload("demo", OK + "-dirty", "java", zip("a", "b")));
        assertEquals(400, e.status().value());
        assertTrue(e.getMessage().contains("dirty"), e.getMessage());
    }

    @Test
    void 路径穿越的buildId回四百(@TempDir Path root) throws Exception {
        ArtifactController c = new ArtifactController(new ArtifactStore(root, 10));

        ArtifactOperationException e = assertThrows(ArtifactOperationException.class,
                () -> c.upload("demo", "../../etc", "java", zip("a", "b")));
        assertEquals(400, e.status().value());
    }

    @Test
    void 认不出来的语言回四百(@TempDir Path root) throws Exception {
        ArtifactController c = new ArtifactController(new ArtifactStore(root, 10));

        ArtifactOperationException e = assertThrows(ArtifactOperationException.class,
                () -> c.upload("demo", OK, "python", zip("a", "b")));
        assertEquals(400, e.status().value());
        assertTrue(e.getMessage().contains("java"), e.getMessage());
    }

    @Test
    void 空文件回四百而不是存下一个空目录(@TempDir Path root) {
        ArtifactController c = new ArtifactController(new ArtifactStore(root, 10));
        MockMultipartFile empty = new MockMultipartFile("file", "a.zip", "application/zip", new byte[0]);

        ArtifactOperationException e = assertThrows(ArtifactOperationException.class,
                () -> c.upload("demo", OK, "java", empty));
        assertEquals(400, e.status().value());
    }

    /**
     * buildId 堵住了路径穿越，<b>project 是同一条路上的另一个口子</b> ——
     * 它同样直接参与磁盘路径。实测过：不校验时
     * {@code ?project=../../../../tmp/x} 会把 97 个文件写到产物根之外并回 200。
     */
    @Test
    void 拿project穿越的上传回四百且根外什么都没写(@TempDir Path root) throws Exception {
        ArtifactController c = new ArtifactController(new ArtifactStore(root, 10));
        // 逃逸目标落在 @TempDir 之外，JUnit 不会替它收尾，用固定名字会被上一轮的残留掩盖断言
        //（实测过：见红那轮真写出了一个 rtcc-escape/，下一轮跑就变成了假失败）
        String 逃逸名 = "rtcc-escape-" + root.getFileName();
        Path 根外 = root.getParent().resolve(逃逸名);

        ArtifactOperationException e = assertThrows(ArtifactOperationException.class,
                () -> c.upload("../" + 逃逸名, OK, "java", zip("a.class", "x")));

        assertEquals(400, e.status().value());
        assertFalse(Files.exists(根外), "产物被写到了产物根之外：" + 根外);
    }

    /** 同一个口子走删除更糟：deleteTree 会把根之外的整棵目录树删掉 */
    @Test
    void 拿project穿越的删除回四百(@TempDir Path root) {
        ArtifactController c = new ArtifactController(new ArtifactStore(root, 10));

        ArtifactOperationException e = assertThrows(ArtifactOperationException.class,
                () -> c.delete("../rtcc-escape", OK));
        assertEquals(400, e.status().value());
    }

    /** 绝对路径比 ../ 更直接：Path.resolve 遇到绝对路径会把 base 整个替换掉 */
    @Test
    void 拿绝对路径当project的查询回四百(@TempDir Path root) {
        ArtifactController c = new ArtifactController(new ArtifactStore(root, 10));

        ArtifactOperationException e = assertThrows(ArtifactOperationException.class,
                () -> c.list(root.getParent().resolve("rtcc-escape").toString()));
        assertEquals(400, e.status().value());
    }

    /**
     * 不是 zip 的垃圾数据。<b>{@code ZipInputStream} 首次 {@code getNextEntry()} 直接返回 null
     * 而不抛异常</b>，于是 {@code save()} 以「零个条目」正常走完、把一个空壳原子换入，接口回 200 ——
     * 实测过：体里 {@code kept} 含该 buildId，列表里这个构建赫然在列（只是 {@code kinds:[]}）。
     * 上传方以为成功、平台存了个空壳，与「拒绝出报告」是同一条原则的两面。
     */
    @Test
    void 不是zip的垃圾回四百且不留空壳(@TempDir Path root) {
        ArtifactStore store = new ArtifactStore(root, 10);
        ArtifactController c = new ArtifactController(store);
        MockMultipartFile 垃圾 = new MockMultipartFile("file", "a.zip", "application/zip",
                "这压根不是一个 zip 包".getBytes(StandardCharsets.UTF_8));

        ArtifactOperationException e = assertThrows(ArtifactOperationException.class,
                () -> c.upload("demo", OK, "java", 垃圾));

        assertEquals(400, e.status().value());
        assertFalse(store.builds("demo").contains(OK),
                "空壳占着 keep 配额，会把真产物挤掉：" + store.builds("demo"));
        assertFalse(Files.exists(root.resolve("demo").resolve(OK)), "磁盘上留下了空的构建目录");
    }

    /**
     * 解压中途失败（zip 被截断）同样不能留下空的 {@code <buildId>/} ——
     * {@code Files.createDirectories(tmp)} 顺带把它建出来了，而失败分支只清 tmp。
     * 它会被 {@code builds()} 计入且 mtime 排在最前：CI 连推 N 次坏包，
     * 下一次成功上传触发的 prune 就把 N 个真实构建挤掉了。
     */
    @Test
    void 解压失败也不留空的构建目录(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        ArtifactController c = new ArtifactController(store);
        byte[] 完整 = 压不动的zip();
        byte[] 截断 = java.util.Arrays.copyOf(完整, 1000);
        MockMultipartFile 坏包 = new MockMultipartFile("file", "a.zip", "application/zip", 截断);

        ArtifactOperationException e = assertThrows(ArtifactOperationException.class,
                () -> c.upload("demo", OK, "java", 坏包));

        assertEquals(400, e.status().value());
        assertFalse(store.builds("demo").contains(OK),
                "坏包留下的空壳占着 keep 配额：" + store.builds("demo"));
        assertFalse(Files.exists(root.resolve("demo").resolve(OK)), "磁盘上留下了空的构建目录");
    }

    /** 同一个 buildId 下已有别的语言时，这次失败不能把人家一起清掉 */
    @Test
    void 某个语言上传失败不牵连同构建的其他语言(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        ArtifactController c = new ArtifactController(store);
        c.upload("demo", OK, "java", zip("Order.class", "字节码"));

        MockMultipartFile 垃圾 = new MockMultipartFile("file", "a.zip", "application/zip",
                "这压根不是一个 zip 包".getBytes(StandardCharsets.UTF_8));
        assertThrows(ArtifactOperationException.class, () -> c.upload("demo", OK, "cpp", 垃圾));

        assertTrue(store.find("demo", OK, ArtifactKind.JAVA).isPresent(), "java 那份被牵连删掉了");
        assertTrue(store.find("demo", OK, ArtifactKind.CPP).isEmpty());
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
