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
        return projectDir(projectId).resolve(buildId).resolve(kind.dir());
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
     * 校验 projectId 能不能安全地当目录名用。
     *
     * <p><b>它与 buildId 是同一条路上的两个口子</b>：projectId 同样直接参与磁盘路径
     * （{@code <root>/<projectId>/...}）。只堵 buildId 等于没堵 —— 实测过，
     * {@code ?project=../../../../tmp/x} 会把产物写到产物根之外并照样回 200，
     * 同一个口子走删除接口就是把根之外的整棵目录树删掉。
     *
     * <p><b>为什么做包含性检查而不限定字符集</b>：项目 id 由用户自取，现网已经存在
     * 中文项目名，限字符集会把合法项目挡在外面。这里只要求「归一化之后仍落在根之下」，
     * 绝对路径也一并挡住 —— {@link Path#resolve} 遇到绝对路径会把 base 整个替换掉，
     * 那比 {@code ../} 更直接。
     */
    public void requireValidProjectId(String projectId) {
        projectDir(projectId);
    }

    /** {@code <root>/<projectId>/}。一切按 projectId 拼路径的地方都必须走这里，否则校验就是摆设 */
    private Path projectDir(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        Path base = root.toAbsolutePath().normalize();
        Path dir = base.resolve(projectId).normalize();
        if (!dir.startsWith(base) || dir.equals(base)) {
            throw new IllegalArgumentException("projectId 会指向产物根之外，拒绝：" + projectId);
        }
        return dir;
    }

    /**
     * 解压一份产物到 {@code <root>/<projectId>/<buildId>/<lang>/}。
     *
     * <p><b>先解压到同级临时目录，全部条目都处理完且没有异常才换入正式目录</b>：
     * 若直接边解压边写正式目录，Zip Slip（或 zip 中途损坏）在第 N 个条目才触发时，
     * 前 N-1 个合法条目已经落盘 —— {@link #find} 的判据只看「目录存在且非空」，
     * 会把这个半成品当成已经就绪的构建，后续拿着不完整的字节码去解探针数据，
     * 算出的覆盖率错得界面上看不出任何异样。若这次是对已有 buildId 的重传，更糟：
     * 旧产物一旦被清空就回不来，绝不能用半成品去顶替一份还在服役的好产物。
     *
     * <p>存完顺手 {@link #prune}，不必另起一个清理任务。
     */
    public void save(String projectId, String buildId, ArtifactKind kind, InputStream zip)
            throws IOException {
        Path dir = dirOf(projectId, buildId, kind);
        Path tmp = dir.getParent().resolve(kind.dir() + ".tmp-" + System.nanoTime());
        Files.createDirectories(tmp);
        try {
            Path base = tmp.toAbsolutePath().normalize();
            int entries = 0;
            try (ZipInputStream in = new ZipInputStream(zip)) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    entries++;
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
            // 零条目必须拒绝，不能存成一个空壳：ZipInputStream 读到<b>不是 zip</b> 的数据时，
            // 首次 getNextEntry() 就返回 null 而<b>不抛异常</b>，于是这里会「成功」地存下一个空目录、
            // 接口回 200 且该 buildId 进入 kept 与列表，而平台之后 find() 判它为空，
            // 对外说「这个构建没上传产物」。上传说成功、取用说没有 ——
            // 与「宁可 4xx 也不给一份静默错误的报告」是同一条原则。
            if (entries == 0) {
                throw new IOException("产物包里一个条目都没有：它要么不是 zip，要么是个空包");
            }
            // 解压全部成功后才清空旧内容、换入 —— 顺序不能反：先清后解的话，
            // 解压中途失败就会把旧产物的坑留在原地，什么都补不回来。
            requireDeleted(dir);
            Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ex) {
            deleteTree(tmp);
            // 上面的 Files.createDirectories(tmp) 顺带把 <buildId>/ 也建了出来。留着它的话，
            // builds() 会把这个空壳计入、mtime 还排在最前：CI 连推 N 次坏包，
            // 下一次成功上传触发的 prune 就把 N 个<b>真实构建</b>挤掉了 ——
            // 一个由「几次失败的上传」引发的故障，查起来完全不着边际。
            deleteIfEmpty(dir.getParent());
            throw ex;
        }
        // 目录的 mtime 决定保留顺序，显式刷一下：换入过程中它可能没被更新
        Files.setLastModifiedTime(dir.getParent(), java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
        prune(projectId);
    }

    /** 取不到就是没上传过。返回空目录会被上游读成「这个构建没有代码」 */
    public Optional<Path> find(String projectId, String buildId, ArtifactKind kind) {
        requireValidBuildId(buildId);
        Path dir = projectDir(projectId).resolve(buildId).resolve(kind.dir());
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
        Path p = projectDir(projectId);
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
            deleteTree(projectDir(projectId).resolve(all.get(i)));
            removed++;
        }
        return removed;
    }

    /** 删掉一个构建的全部产物 */
    public void remove(String projectId, String buildId) {
        requireValidBuildId(buildId);
        deleteTree(projectDir(projectId).resolve(buildId));
    }

    /**
     * {@code save()} 换入前的强制清空：删不干净就必须让 {@code save()} 失败，不能带着残留继续。
     *
     * <p>与下面 {@link #deleteTree}（{@link #prune} 用）刻意是两套不同的语义 ——
     * {@code prune()} 删的是要退休的旧构建，少删一个不影响正确性，下一轮还会再试；
     * 这里删的是即将被新内容占用的目录，删不干净却继续，解压结果就会摊在残留之上，
     * 新旧两次构建的产物混成并集，正是「重传要先清空」这一步本来要防的场景。
     */
    private static void requireDeleted(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    /**
     * 只删空目录，非空就原样留着 —— {@link Files#deleteIfExists} 对非空目录会抛
     * {@code DirectoryNotEmptyException}，正好就是这里要的语义。
     *
     * <p><b>为什么必须「只删空的」</b>：同一个 buildId 下可能已经有别的语言的正常产物
     * （java 传成功了、cpp 这次传坏了），把整个 {@code <buildId>/} 删掉就是拿一次失败的上传
     * 去毁掉一份还在服役的好产物。
     */
    private static void deleteIfEmpty(Path dir) {
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // 非空、或者删不掉，都不该盖掉调用方真正要抛的那个失败原因
        }
    }

    /** {@link #prune} 用的宽容删除：单个文件删不掉不该让清理整体失败，下一轮 prune 还会再试 */
    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件删不掉不该让整次操作失败：下次 prune 会再试
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
