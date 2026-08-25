package com.rtcc.platform.collector;

import com.rtcc.platform.config.CoverageProperties;
import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.model.FileCoverage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 归一化层（Go）：covmeta + covcounters → 行级覆盖模型。
 *
 * covmeta/covcounters 是 Go 内部的二进制格式，没有对外稳定契约，自行解析必然随版本崩。
 * 因此借 {@code go tool covdata textfmt} 转成经典的 profile 文本再解析 ——
 * 代价是染色平台的部署环境需要装 Go 工具链。
 *
 * profile 每行形如：{@code 导入路径/文件.go:起行.起列,止行.止列 语句数 执行次数}
 */
public class GoCoverageAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GoCoverageAnalyzer.class);

    /** file.go:12.34,15.2 3 1 */
    private static final Pattern BLOCK = Pattern.compile(
            "^(.+):(\\d+)\\.\\d+,(\\d+)\\.\\d+ \\d+ (\\d+)$");

    private final ProjectConfig props;
    /** 工具链可执行文件的路径是部署机器的属性，换机器才改，与项目无关，因此仍从平台配置取 */
    private final CoverageProperties platform;

    public GoCoverageAnalyzer(ProjectConfig props, CoverageProperties platform) {
        this.props = props;
        this.platform = platform;
    }

    /**
     * @param dumps 各实例的 (meta, counters) 原始字节。多实例的数据一并交给 covdata，
     *              由它按块求和 —— 与 Java 侧在 exec 层合并同理，都是在语言自己的
     *              数据层面合并，精度不会因为提前退化成行状态而损失
     */
    public Map<String, FileCoverage> analyze(List<byte[][]> dumps) throws IOException {
        if (dumps.isEmpty()) {
            return Map.of();
        }
        String module = props.getGoModulePath();
        String root = props.getGoSourceRoot();
        // 少了这两项，profile 里的 import path 一个都换算不成仓库相对路径，
        // 结果会是一份「Go 一个文件都没有」的报告 —— 与「Go 代码没被调用过」长得一模一样。
        // 在动手抓数据、起子进程之前就拦下，也省得为一次注定失败的归一化白跑一趟 covdata
        if (module == null || module.isBlank() || root == null || root.isBlank()) {
            throw new IOException("未配置 coverage.go-module-path / coverage.go-source-root，"
                    + "无法把 Go 覆盖数据里的 import path 换算成仓库相对路径");
        }
        Path dir = Files.createTempDirectory("gocov");
        try {
            for (int i = 0; i < dumps.size(); i++) {
                byte[][] d = dumps.get(i);
                // covdata 靠文件名前缀识别两类文件，后面的哈希/进程号它不校验，
                // 因此平台可以自行命名，不必与被测实例共享文件系统。
                // Locale.ROOT 不能省：%d 会按默认区域设置本地化数字，
                // 平台跑在阿拉伯语/泰语环境下时文件名里会出现非 ASCII 数字
                Files.write(dir.resolve(String.format(Locale.ROOT, "covmeta.%016x", i)), d[0]);
                Files.write(dir.resolve(String.format(Locale.ROOT, "covcounters.%016x.%d.1", i, i)), d[1]);
            }
            Path out = dir.resolve("profile.txt");
            runCovdata(dir, out);
            return parse(Files.readAllLines(out, StandardCharsets.UTF_8), module, root);
        } finally {
            deleteTree(dir);
        }
    }

    private void runCovdata(Path in, Path out) throws IOException {
        List<String> cmd = List.of(platform.getGoTool(), "tool", "covdata", "textfmt",
                "-i=" + in.toAbsolutePath(), "-o=" + out.toAbsolutePath());
        // profile 走 -o 落文件，stdout 本该是空的；但只要它写了东西而没人读，
        // 管道写满就会双方对着阻塞，直到 30 秒超时才被强杀。丢弃即可
        Process p = new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        // stderr 单独收走，避免管道写满时两端互相阻塞
        StringBuilder err = new StringBuilder();
        Thread drain = new Thread(() -> {
            try {
                err.append(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });
        drain.setDaemon(true);
        drain.start();
        try {
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("go tool covdata 超时未返回");
            }
            drain.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 go tool covdata 被中断", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("go tool covdata 失败（exit " + p.exitValue() + "）："
                    + err.toString().trim() + "。请确认平台所在环境已安装 Go 工具链（coverage.go-tool）");
        }
    }

    private Map<String, FileCoverage> parse(List<String> lines, String module, String root)
            throws IOException {
        // 先按文件累计每行的最大执行次数：一行可能落在多个块里（如 if 与其分支同一行），
        // 只要有一个块跑过，这一行就是跑过的
        Map<String, Map<Integer, Long>> byFile = new LinkedHashMap<>();
        int blocks = 0;
        for (String line : lines) {
            Matcher m = BLOCK.matcher(line.strip());
            if (!m.matches()) {
                continue; // 首行是 "mode: atomic"
            }
            blocks++;
            String path = toRepoRelative(m.group(1), module, root);
            if (path == null) {
                continue;
            }
            int from = Integer.parseInt(m.group(2));
            int to = Integer.parseInt(m.group(3));
            long count = Long.parseLong(m.group(4));
            Map<Integer, Long> counts = byFile.computeIfAbsent(path, k -> new TreeMap<>());
            for (int i = from; i <= to; i++) {
                counts.merge(i, count, Math::max);
            }
        }
        // covdata 可以退出码为 0 却什么都没产出。静默放过的话，界面上 Go 直接消失，
        // 与「Go 代码一行都没被调用」长得完全一样 —— 这正是最该拒绝出报告的那类情况
        if (blocks == 0) {
            throw new IOException("go tool covdata 没有产出任何覆盖块，"
                    + "请确认被测服务是以 `go build -cover -covermode=atomic` 构建的");
        }
        if (byFile.isEmpty()) {
            throw new IOException("Go 覆盖数据里没有一个文件位于模块 " + module + " 之下，"
                    + "coverage.go-module-path 与被测模块的 import path 可能对不上");
        }

        Map<String, FileCoverage> result = new LinkedHashMap<>();
        byFile.forEach((path, counts) -> {
            Set<Integer> blank = blankLines(path);
            List<FileCoverage.LineCoverage> ls = new ArrayList<>();
            int covered = 0, missed = 0;
            for (Map.Entry<Integer, Long> e : counts.entrySet()) {
                if (blank.contains(e.getKey())) {
                    continue;
                }
                // Go 给的是执行次数，而 IR 只区分跑没跑过，与 JaCoCo 的布尔探针对齐。
                // Go 没有 PARTIAL 这一档：块要么进过要么没进过
                boolean hit = e.getValue() > 0;
                ls.add(new FileCoverage.LineCoverage(e.getKey(), hit ? "COVERED" : "MISSED"));
                if (hit) {
                    covered++;
                } else {
                    missed++;
                }
            }
            int total = covered + missed;
            int slash = path.lastIndexOf('/');
            result.put(path, new FileCoverage(
                    path,
                    slash < 0 ? "" : path.substring(0, slash).replace('/', '.'),
                    slash < 0 ? path : path.substring(slash + 1),
                    covered, missed,
                    total == 0 ? 0d : covered * 100d / total,
                    ls));
        });
        return result;
    }

    /**
     * Go 的覆盖块给的是「起行→止行」的文本区间，区间里的空行会被一并算成可执行行。
     * Java 侧空行是 EMPTY（根本不进 IR），Go 侧若照单全收，两种语言的行覆盖率就不是
     * 同一个口径；增量口径尤其明显 —— diff 里的空行会平白挤进分母，把比例冲淡。
     * 空行判定不需要解析 Go 源码，strip 后为空即可，不存在误判。
     *
     * （块尾的右花括号与块内注释仍会计入：那要真解析 Go 源码才能剔除，
     *   属于 Go 块模型与 JaCoCo 探针模型的固有粒度差异，已记在 CLAUDE.md）
     */
    private Set<Integer> blankLines(String path) {
        try {
            List<String> src = Files.readAllLines(
                    Path.of(props.getRepoDir(), path), StandardCharsets.UTF_8);
            Set<Integer> blank = new HashSet<>();
            for (int i = 0; i < src.size(); i++) {
                if (src.get(i).isBlank()) {
                    blank.add(i + 1);
                }
            }
            return blank;
        } catch (IOException e) {
            // 读不到源码就照单全收：宁可多算几行空行，也不能凭空丢掉真实的覆盖数据
            log.warn("读取 Go 源码失败，空行将被计入覆盖：{}（{}）", path, e.getMessage());
            return Set.of();
        }
    }

    /**
     * profile 里的文件名是完整 import path，换成仓库相对路径才能与 git diff 对齐。
     * 返回 null 表示这个文件不该统计。
     */
    private String toRepoRelative(String goPath, String module, String root) {
        if (!goPath.startsWith(module + "/")) {
            // 依赖库的代码不是被测对象，也没有对应的源码可染色
            return null;
        }
        String rel = goPath.substring(module.length() + 1);
        // 探针文件测的是它自己，计进去只会稀释被测代码的覆盖率
        for (String ex : props.getGoExclude()) {
            if (rel.equals(ex) || rel.endsWith("/" + ex)) {
                return null;
            }
        }
        return root.replace('\\', '/') + "/" + rel;
    }

    private void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.warn("临时目录清理失败：{}", dir);
        }
    }
}
