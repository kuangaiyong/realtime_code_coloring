package com.rtcc.platform.collector;

import com.rtcc.platform.config.ProjectConfig;

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
public class GitService {

    /** 只允许常规 ref 字面量，把用户输入挡在被 git 当作选项解析的可能性之外 */
    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/~^-]{0,99}");
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private final ProjectConfig props;

    public GitService(ProjectConfig props) {
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
     * 一次 diff 的结果。
     *
     * <p><b>为什么把「哪些是新增文件」和行号一起返回，而不是另开一个方法：</b>
     * 两样都来自同一份 diff 输出，分开取就要再起一个 git 子进程 —— 而增量判定本来
     * 就要起三个（查源码漂移、解析基线、算变更行，均无缓存），为一个展示用的标签
     * 再加一个，代价落在每一次判定上。
     *
     * <p><b>「新增」的判据是 git 说基线侧是 /dev/null</b>，因此它跟着 {@code -M} 的改名检测走：
     * 改名 + 改内容时基线侧给的是原路径，不算新增。但 {@code -M} <b>不是无条件的</b> ——
     * 改动文件数超过 {@code diff.renameLimit} 时 git 会静默放弃精确检测（只往 stderr 写一句
     * warning），一次改名退化成 delete + add。那时新路径会被标成新增，
     * <b>而这与增量口径本身的算法是一致的</b>：同一份 diff 里，那个文件的每一行也确实
     * 全都进了变更行集合。标签不会和下面的数字互相矛盾，只是两者一起把改名当成了新写。
     * 不为此加 {@code -c diff.renameLimit=0}：不设上限的改名检测在大 diff 上很慢，
     * 而这条路径每一次增量判定都要走。
     *
     * @param lines      每个文件的新侧变更行号
     * @param addedPaths 其中在基线里根本不存在的那些
     */
    public record Changes(Map<String, Set<Integer>> lines, Set<String> addedPaths) {}

    /**
     * baseline → target 之间，各源码根目录下每个文件的新增/修改行号（路径以仓库根为基准）。
     * 只看新侧行号，因为染色渲染的是新代码；删除的行没有可染色的载体。
     */
    public Changes changedLines(String baseSha, String targetSha) throws IOException {
        List<String> args = new ArrayList<>(
                List.of("diff", "-M", "--unified=0", "--no-color", baseSha, targetSha, "--"));
        args.addAll(sourceRoots());
        String diff = run(args.toArray(String[]::new));

        Map<String, Set<Integer>> result = new LinkedHashMap<>();
        Set<String> added = new LinkedHashSet<>();
        Set<Integer> current = null;
        // 基线侧是 /dev/null 的就是新增文件。这一行紧挨在 +++ 之前，记住上一条即可
        boolean fromNull = false;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("--- ")) {
                fromNull = line.substring(4).strip().equals("/dev/null");
                continue;
            }
            if (line.startsWith("+++ ")) {
                String target = line.substring(4).strip();
                // 文件被删除时新侧是 /dev/null，没有可染色的行
                if (target.equals("/dev/null")) {
                    current = null;
                } else {
                    String path = target.substring(2);
                    current = result.computeIfAbsent(path, k -> new TreeSet<>());
                    if (fromNull) {
                        added.add(path);
                    }
                }
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
        // 上一句会丢掉「新增的文件里一行变更都没解析出来」这类条目，标记必须跟着丢，
        // 否则 addedPaths 里会留下一个 lines 里根本不存在的路径
        added.retainAll(result.keySet());
        return new Changes(result, added);
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
