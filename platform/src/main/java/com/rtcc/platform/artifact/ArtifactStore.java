package com.rtcc.platform.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
}
