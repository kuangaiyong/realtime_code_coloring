package com.rtcc.platform.collector;

import com.rtcc.platform.config.CoverageProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 增量口径的事实来源：git。
 *
 * 对外暴露的路径一律是「相对源码根目录」的形式（如 com/shop/order/Foo.java），
 * 与覆盖率数据的 key 保持一致，调用方不必关心仓库里的实际布局。
 */
@Component
public class GitService {

    /** 只允许常规 ref 字面量，把用户输入挡在被 git 当作选项解析的可能性之外 */
    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/~^-]{0,99}");
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private final CoverageProperties props;

    public GitService(CoverageProperties props) {
        this.props = props;
    }

    /** 把任意 ref（分支名、tag、HEAD~3、缩写 sha）解析成 40 位 sha */
    public String resolve(String ref) throws IOException {
        if (ref == null || !SAFE_REF.matcher(ref).matches()) {
            throw new IOException("基线 ref 不合法：" + ref);
        }
        String sha = run("rev-parse", "--verify", "--quiet", ref + "^{commit}").strip();
        if (sha.isEmpty()) {
            throw new IOException("基线 ref 在仓库中不存在：" + ref);
        }
        return sha;
    }

    /**
     * baseline → target 之间，各源码根目录下每个文件的新增/修改行号（路径以仓库根为基准）。
     * 只看新侧行号，因为染色渲染的是新代码；删除的行没有可染色的载体。
     */
    public Map<String, Set<Integer>> changedLines(String baseSha, String targetSha) throws IOException {
        List<String> args = new ArrayList<>(
                List.of("diff", "-M", "--unified=0", "--no-color", baseSha, targetSha, "--"));
        args.addAll(sourceRoots());
        String diff = run(args.toArray(String[]::new));

        Map<String, Set<Integer>> result = new LinkedHashMap<>();
        Set<Integer> current = null;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("+++ ")) {
                String target = line.substring(4).strip();
                // 文件被删除时新侧是 /dev/null，没有可染色的行
                current = target.equals("/dev/null") ? null
                        : result.computeIfAbsent(target.substring(2), k -> new TreeSet<>());
                continue;
            }
            if (current == null || !line.startsWith("@@")) {
                continue;
            }
            Matcher m = HUNK.matcher(line);
            if (!m.find()) {
                continue;
            }
            int start = Integer.parseInt(m.group(1));
            int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            for (int i = 0; i < count; i++) {
                current.add(start + i);
            }
        }
        result.values().removeIf(Set::isEmpty);
        return result;
    }

    /**
     * 自 commitSha 起，源码根目录下已发生变化的文件（含未提交改动）。
     * 非空即意味着平台正在渲染的源码不是产物构建时的那一份，行号无从对齐。
     */
    public List<String> sourceDrift(String commitSha) throws IOException {
        List<String> args = new ArrayList<>(List.of("diff", "--name-only", commitSha, "--"));
        args.addAll(sourceRoots());
        String out = run(args.toArray(String[]::new));
        List<String> files = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (!line.isBlank()) {
                files.add(line.strip());
            }
        }
        return files;
    }

    /**
     * git 的 pathspec 为空时不是「什么都不比」，而是「整仓都比」。
     * 一个源码根都没配却照常放行的话，README、脚本的改动会被报成「被测源码漂移」，
     * 增量口径从此永久 409，而提示指向的却是完全无关的文件。
     */
    private List<String> sourceRoots() throws IOException {
        List<String> roots = props.getSourceRoots();
        if (roots.isEmpty()) {
            throw new IOException("未配置任何被测源码根（coverage.java-source-root / coverage.go-source-root），"
                    + "无法界定增量范围");
        }
        return roots;
    }

    private String run(String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", props.getRepoDir()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).start();

        // stderr 必须与 stdout 分开收：git 会把「LF will be replaced by CRLF」这类警告写到 stderr，
        // 一旦并进 stdout 就会被当成 diff 内容解析出不存在的文件。
        // 另起线程读走，避免 stderr 管道写满时两端互相阻塞。
        StringBuilder err = new StringBuilder();
        Thread drain = new Thread(() -> {
            try {
                err.append(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 进程已退出，拿不到剩余错误输出不影响主流程
            }
        });
        drain.setDaemon(true);
        drain.start();

        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code;
        try {
            code = p.waitFor();
            drain.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git 命令被中断");
        }
        // rev-parse --quiet 用退出码 1 表示「ref 不存在」，由调用方按空输出处理
        if (code != 0 && !(code == 1 && out.isBlank())) {
            throw new IOException("git " + String.join(" ", args) + " 执行失败：" + err.toString().strip());
        }
        return out;
    }
}
