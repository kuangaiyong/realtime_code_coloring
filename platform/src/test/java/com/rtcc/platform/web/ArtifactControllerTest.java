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

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> c.upload("../" + 逃逸名, OK, "java", zip("a.class", "x")));

        assertEquals(400, e.getStatusCode().value());
        assertFalse(java.nio.file.Files.exists(根外), "产物被写到了产物根之外：" + 根外);
    }

    /** 同一个口子走删除更糟：deleteTree 会把根之外的整棵目录树删掉 */
    @Test
    void 拿project穿越的删除回四百(@TempDir Path root) {
        ArtifactController c = new ArtifactController(new ArtifactStore(root, 10));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> c.delete("../rtcc-escape", OK));
        assertEquals(400, e.getStatusCode().value());
    }

    /** 绝对路径比 ../ 更直接：Path.resolve 遇到绝对路径会把 base 整个替换掉 */
    @Test
    void 拿绝对路径当project的查询回四百(@TempDir Path root) {
        ArtifactController c = new ArtifactController(new ArtifactStore(root, 10));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> c.list(root.getParent().resolve("rtcc-escape").toString()));
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
