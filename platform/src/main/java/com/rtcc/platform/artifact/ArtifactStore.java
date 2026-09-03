package com.rtcc.platform.artifact;

import java.nio.file.Path;
import java.util.regex.Pattern;

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
}
