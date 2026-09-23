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

    /** 两个条目的 zip：用来模拟「前面条目合法、后面条目才触发失败」的中途失败场景 */
    private static byte[] zipOfTwo(String name1, String content1, String name2, String content2) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry(name1));
            zip.write(content1.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(name2));
            zip.write(content2.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
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

    /**
     * 首次上传就中途失败（第一个条目合法、第二个才触发 Zip Slip）：
     * 前一个条目已经落盘，但这个构建整体没传成功，不能被当成「已经就绪」——
     * 上传方看到失败退出，平台这边却让 find() 返回一个目录，
     * 后续采集拿着不完整的字节码去解探针数据，算出的覆盖率界面上看不出任何异样。
     */
    @Test
    void 解压中途失败不留半成品(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        byte[] evil = zipOfTwo("legit.class", "看起来正常的一个条目", "../../pwned.txt", "坏东西");

        assertThrows(java.io.IOException.class, () ->
                store.save("demo", sha(1), ArtifactKind.JAVA, new ByteArrayInputStream(evil)));

        assertTrue(store.find("demo", sha(1), ArtifactKind.JAVA).isEmpty(),
                "解压中途失败却能被 find() 找到 —— 上传方以为失败了，平台却把半成品当成了已就绪的构建");
    }

    /**
     * 重传中途失败更危险：不能因为这次传坏了，就把还在服役的旧产物换没了。
     * 旧实现是先 deleteTree(dir) 清空、再边解压边写，失败时旧产物已经被删掉、
     * 新内容又没写完 —— 上传方以为失败，平台却连能采数据的旧构建都丢了。
     */
    @Test
    void 重传中途失败不破坏原有产物(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        store.save("demo", sha(1), ArtifactKind.JAVA,
                new ByteArrayInputStream(zipOf("good.class", "旧的好产物")));

        byte[] evil = zipOfTwo("legit.class", "看起来正常的一个条目", "../../pwned.txt", "坏东西");
        assertThrows(java.io.IOException.class, () ->
                store.save("demo", sha(1), ArtifactKind.JAVA, new ByteArrayInputStream(evil)));

        Path dir = store.find("demo", sha(1), ArtifactKind.JAVA).orElseThrow(
                () -> new AssertionError("重传失败却把旧产物弄丢了：上传方以为失败，平台连能采数据的旧构建都没了"));
        assertEquals("旧的好产物", Files.readString(dir.resolve("good.class"), StandardCharsets.UTF_8),
                "重传失败必须保住原有的好产物，不能用传了一半的半成品覆盖它");
        assertFalse(Files.exists(dir.resolve("legit.class")), "半成品的条目不该混进正式目录");
        assertFalse(Files.exists(root.resolve("pwned.txt")));
    }

    /**
     * 上一条守的是「解压中途失败」，这一条守的是<b>换入这一步本身失败</b> —— 两个不同的缺口。
     *
     * <p>旧实现在换入前先 {@code requireDeleted(dir)} <b>原地逐个删掉</b>旧文件，再 move 换入。
     * 触发路径在本项目里很现实：平台自己每 3 秒采集一轮，正读着
     * {@code .artifacts/<project>/<sha>/java/} 下的 class 文件，此时 CI 重传同一个 buildId ——
     * Windows 上被占用的文件删不掉，{@code requireDeleted} 删到一半抛异常，
     * 留下一个<b>非空的残缺目录</b>：清理只删空目录，因它非空而不动它，
     * {@code find()} 照样判它就绪，平台接着拿<b>不完整的 class</b> 去解探针数据，
     * 算出的覆盖率界面上看不出任何异样。
     *
     * <p>正确的语义是：换不进去就让旧产物<b>原样留着</b>（哪怕它是旧版本），
     * 绝不能变成新旧混合体，更不能悄悄变成残缺体。
     *
     * <p>删不掉是<b>真实</b>造出来的（置 DOS 只读位，Windows 上 {@code Files.delete} 会抛
     * {@code AccessDeniedException}），没有任何替身。
     * 一开始试过「开着 FileChannel 读它」来模拟采集占用，<b>复现不了</b> ——
     * JDK 在 Windows 上带 {@code FILE_SHARE_DELETE} 打开文件，开着照样删得掉。
     *
     * <p>断言钉的是<b>不变式</b>而不是某一次的失败方式：这个目录只能是
     * 「完整的旧产物」或「完整的新产物」，绝不能是两者的混合体或残缺体。
     * 换不进去时报错、旧产物原样留着，都被这条不变式覆盖。
     */
    @Test
    void 重传换不进去时绝不留下新旧混合体(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        // a-undeletable 排在 z-other 之前：逆序删会先删掉 z-other，再撞上删不掉的那个，
        // 「删到一半」这个场景才真的成立，而不是第一下就失败
        store.save("demo", sha(1), ArtifactKind.JAVA, new ByteArrayInputStream(
                zipOfTwo("a-undeletable.class", "旧产物里删不掉的那份", "z-other.class", "旧产物里的另一份")));
        Path dir = store.find("demo", sha(1), ArtifactKind.JAVA).orElseThrow();
        Files.setAttribute(dir.resolve("a-undeletable.class"), "dos:readonly", true);

        try {
            try {
                store.save("demo", sha(1), ArtifactKind.JAVA,
                        new ByteArrayInputStream(zipOf("new.class", "新产物")));
            } catch (java.io.IOException swapFailed) {
                // 允许这次重传失败。不允许的是「失败了还留下个残缺目录」—— 下面统一判
            }

            List<String> left;
            try (var s = Files.list(dir)) {
                left = s.map(p -> p.getFileName().toString()).sorted().toList();
            }
            assertTrue(left.equals(List.of("a-undeletable.class", "z-other.class"))
                            || left.equals(List.of("new.class")),
                    "换入不是原子的：目录既不是完整的旧产物也不是完整的新产物，而是 " + left
                            + " —— find() 照样判它就绪，平台会拿这份残缺产物去解探针数据，"
                            + "算出的覆盖率界面上看不出任何异样");
        } finally {
            // 只读位不清掉，JUnit 收尾删 @TempDir 时会失败
            try (var w = Files.walk(root)) {
                w.forEach(p -> {
                    try {
                        Files.setAttribute(p, "dos:readonly", false);
                    } catch (Exception ignored) {
                        // 尽力而为，清不掉的不该盖掉真正的断言结果
                    }
                });
            }
        }
    }

    /** 换入成功之后，旧产物必须真的被清掉，不能在旁边越积越多 */
    @Test
    void 重传成功后旧产物不留旁路残留(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        store.save("demo", sha(1), ArtifactKind.JAVA,
                new ByteArrayInputStream(zipOf("old.class", "旧的")));

        store.save("demo", sha(1), ArtifactKind.JAVA,
                new ByteArrayInputStream(zipOf("new.class", "新的")));

        Path dir = store.find("demo", sha(1), ArtifactKind.JAVA).orElseThrow();
        assertEquals("新的", Files.readString(dir.resolve("new.class"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(dir.resolve("old.class")), "旧内容没被换掉");
        try (var s = Files.list(root.resolve("demo").resolve(sha(1)))) {
            assertEquals(List.of("java"), s.map(p -> p.getFileName().toString()).sorted().toList(),
                    "构建目录下多出了换入过程留下的临时/旁路目录");
        }
    }

    /**
     * 只有目录条目的 zip 同样是「没有产物」。
     *
     * <p>按条目总数判空的话它能蒙混过关：目录条目让计数非零，于是换入一个
     * 只含空子目录的 {@code <lang>/}，而 {@code find()} 的判据是「存在且非空」——
     * 空子目录照样让它判为就绪，平台之后拿着一个没有任何 class 的目录去解探针数据。
     */
    @Test
    void 只有目录条目的zip不算产物(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("com/"));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("com/rtcc/"));
            z.closeEntry();
        }

        assertThrows(BadArtifactException.class, () -> store.save("demo", sha(1),
                ArtifactKind.JAVA, new ByteArrayInputStream(bos.toByteArray())));

        assertTrue(store.find("demo", sha(1), ArtifactKind.JAVA).isEmpty(),
                "只有目录的包被当成了一份就绪的产物");
        assertFalse(store.builds("demo").contains(sha(1)), "空壳占着 keep 配额");
    }

    /**
     * 盘上手工放的目录既不能占保留名额，也不能被清理悄悄删掉。
     *
     * <p>两头都会出错：算进 {@code builds()} 就会挤掉真产物（keep=3、盘上 2 个陌生目录，
     * 就只留得下 1 份真的）；而 {@code prune()} 反过来又会把运维放在那里的东西删了。
     */
    @Test
    void 陌生目录既不占保留名额也不会被清理删掉(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 3);
        Files.createDirectories(root.resolve("demo").resolve("手工放的备份"));
        Files.createDirectories(root.resolve("demo").resolve("tmp-别的工具落下的"));

        for (int i = 1; i <= 3; i++) {
            store.save("demo", sha(i), ArtifactKind.JAVA,
                    new ByteArrayInputStream(zipOf("a.class", "第" + i + "份")));
        }

        assertEquals(List.of(sha(3), sha(2), sha(1)), store.builds("demo"),
                "陌生目录占掉了 keep 名额，真产物被挤掉");
        assertEquals(List.of("tmp-别的工具落下的", "手工放的备份"),
                store.strangers("demo").stream().sorted().toList());
        assertTrue(Files.isDirectory(root.resolve("demo").resolve("手工放的备份")),
                "清理把运维放的目录删了");
        assertTrue(Files.isDirectory(root.resolve("demo").resolve("tmp-别的工具落下的")),
                "清理把别的工具的目录删了");
    }

    /** 目录下全部文件的相对路径，排好序 —— 用来断言「一个不少」 */
    private static List<String> files(Path dir) throws Exception {
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    /**
     * 删除撞上文件占用时必须<b>原封不动</b>，绝不能删成半截。
     *
     * <p>{@code find()} 只看「存在且非空」，半截目录会被当成一份就绪的产物 ——
     * 平台接着拿不完整的字节码去解探针数据，界面上看不出任何异样。
     * 而占用是真实会发生的：采集时 JaCoCo 用 {@code FileInputStream} 读 class，
     * Windows 上会占住文件，删「正在被采集的那个构建」就会撞上。
     * {@code e2e_artifact.py} 的 {@code drop_artifact} 就撞上过，靠重试 DELETE 糊了过去，
     * 失败那次与重试之间盘上一直是个半截目录。
     *
     * <p>占用用 JaCoCo 同样的方式真实造出来，没有替身。
     */
    @Test
    void 删除撞上占用时原封不动而不是删成半截(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        store.save("demo", sha(1), ArtifactKind.JAVA,
                new ByteArrayInputStream(zipOfTwo("A.class", "甲", "B.class", "乙")));
        Path dir = store.find("demo", sha(1), ArtifactKind.JAVA).orElseThrow();

        try (java.io.FileInputStream held = new java.io.FileInputStream(dir.resolve("B.class").toFile())) {
            java.io.IOException e = assertThrows(java.io.IOException.class,
                    () -> store.remove("demo", sha(1)), "文件被占着却说删掉了");
            // 要么整体删掉、要么整体没动 —— 绝不能是只剩被占住那个文件的中间态
            assertEquals(List.of("A.class", "B.class"), files(dir), "删成了半截，残缺目录会被当成就绪");
            assertTrue(e.getMessage().contains("原封未动"),
                    "要让调用方知道产物还完整、稍后重试即可：" + e.getMessage());
        }

        // 占用解除后删得掉，挪开的那个目录也不留下
        store.remove("demo", sha(1));
        assertTrue(store.find("demo", sha(1), ArtifactKind.JAVA).isEmpty());
        assertEquals(List.of(), store.strangers("demo"), "挪开删除用的目录没删掉");
    }

    /**
     * 文件本身删不掉（只读位这类不会自己消失的原因）时，<b>构建照样要从平台眼里消失</b>，
     * 残留只能待在一个 {@code find()} / {@code builds()} 都看不见的名字底下 ——
     * 它在列表里显示成陌生目录，运维看得见、占的只是磁盘。
     *
     * <p>原先这种情况报 500，而那份残缺目录<b>原地留着</b>被当成就绪：报了错也没用，
     * 半截产物照样被拿去算覆盖率。先整体挪开再删，才让「删到一半」不再有机会被读到。
     */
    @Test
    void 文件本身删不掉时构建照样消失残留挪到看不见的地方(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 10);
        store.save("demo", sha(1), ArtifactKind.JAVA,
                new ByteArrayInputStream(zipOf("stuck.class", "删不掉的那份")));
        Path dir = store.find("demo", sha(1), ArtifactKind.JAVA).orElseThrow();
        Files.setAttribute(dir.resolve("stuck.class"), "dos:readonly", true);

        try {
            store.remove("demo", sha(1));

            assertTrue(store.find("demo", sha(1), ArtifactKind.JAVA).isEmpty(), "残缺产物仍会被当成就绪");
            assertEquals(List.of(), store.builds("demo"), "删掉的构建还占着 keep 名额");
            assertEquals(1, store.strangers("demo").size(), "残留要落在列表看得见的地方");
        } finally {
            // 清掉只读位，好让 @TempDir 收尾（残留已经不在原路径了）
            try (var s = Files.walk(root)) {
                s.filter(p -> p.getFileName().toString().equals("stuck.class")).forEach(p -> {
                    try {
                        Files.setAttribute(p, "dos:readonly", false);
                    } catch (java.io.IOException ignored) {
                    }
                });
            }
        }
    }

    /**
     * 清理（{@code prune}）撞上占用时<b>跳过</b>，不能删成半截 —— 这条比删除接口更隐蔽：
     * 上传照样回 200，没有任何报错。
     *
     * <p>场景是 keep 调小 + 滚动发布：新构建一上传就清掉上一个，而旧实例还在跑上一个、
     * 采集正读着它的 class。删成半截的话，旧构建的名字仍是合法 buildId，{@code find()} 看得见，
     * 平台拿三个 class 里剩下的一个去解旧实例的探针数据。跳过则什么都没动，下一次上传时再清。
     */
    @Test
    void 清理撞上占用时跳过而不是删成半截(@TempDir Path root) throws Exception {
        ArtifactStore store = new ArtifactStore(root, 1);
        store.save("demo", sha(1), ArtifactKind.JAVA,
                new ByteArrayInputStream(zipOfTwo("A.class", "甲", "B.class", "乙")));
        Path old = store.find("demo", sha(1), ArtifactKind.JAVA).orElseThrow();
        Thread.sleep(1100);   // mtime 精度可能只有秒级，隔开才排得出先后

        try (java.io.FileInputStream held = new java.io.FileInputStream(old.resolve("B.class").toFile())) {
            store.save("demo", sha(2), ArtifactKind.JAVA,
                    new ByteArrayInputStream(zipOf("a.class", "新的")));
        }
        assertEquals(List.of("A.class", "B.class"), files(old), "旧构建被清成了半截，仍会被当成就绪");

        // 占用解除后，下一次上传时把它清掉，且不留挪开的目录
        Thread.sleep(1100);
        store.save("demo", sha(3), ArtifactKind.JAVA,
                new ByteArrayInputStream(zipOf("a.class", "更新的")));
        assertTrue(store.find("demo", sha(1), ArtifactKind.JAVA).isEmpty(), "占用解除后仍没清掉");
        assertEquals(List.of(sha(3)), store.builds("demo"));
        assertEquals(List.of(), store.strangers("demo"), "挪开删除用的目录没删掉");
    }
}
