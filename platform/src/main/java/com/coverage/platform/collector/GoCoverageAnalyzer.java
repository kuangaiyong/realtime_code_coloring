package com.coverage.platform.collector;

import com.coverage.platform.config.CoverageProperties;
import com.coverage.platform.model.FileCoverage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
@Component
public class GoCoverageAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GoCoverageAnalyzer.class);

    /** file.go:12.34,15.2 3 1 */
    private static final Pattern BLOCK = Pattern.compile(
            "^(.+):(\\d+)\\.\\d+,(\\d+)\\.\\d+ \\d+ (\\d+)$");

    private final CoverageProperties props;

    public GoCoverageAnalyzer(CoverageProperties props) {
        this.props = props;
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
        Path dir = Files.createTempDirectory("gocov");
        try {
            for (int i = 0; i < dumps.size(); i++) {
                byte[][] d = dumps.get(i);
                // covdata 靠文件名前缀识别两类文件，后面的哈希/进程号它不校验，
                // 因此平台可以自行命名，不必与被测实例共享文件系统
                Files.write(dir.resolve(String.format("covmeta.%016x", i)), d[0]);
                Files.write(dir.resolve(String.format("covcounters.%016x.%d.1", i, i)), d[1]);
            }
            Path out = dir.resolve("profile.txt");
            runCovdata(dir, out);
            return parse(Files.readAllLines(out, StandardCharsets.UTF_8));
        } finally {
            deleteTree(dir);
        }
    }

    private void runCovdata(Path in, Path out) throws IOException {
        List<String> cmd = List.of(props.getGoTool(), "tool", "covdata", "textfmt",
                "-i=" + in.toAbsolutePath(), "-o=" + out.toAbsolutePath());
        Process p = new ProcessBuilder(cmd).start();
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

    private Map<String, FileCoverage> parse(List<String> lines) {
        // 先按文件累计每行的最大执行次数：一行可能落在多个块里（如 if 与其分支同一行），
        // 只要有一个块跑过，这一行就是跑过的
        Map<String, Map<Integer, Long>> byFile = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher m = BLOCK.matcher(line.strip());
            if (!m.matches()) {
                continue; // 首行是 "mode: atomic"
            }
            String path = toRepoRelative(m.group(1));
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

        Map<String, FileCoverage> result = new LinkedHashMap<>();
        byFile.forEach((path, counts) -> {
            List<FileCoverage.LineCoverage> ls = new ArrayList<>();
            int covered = 0, missed = 0;
            for (Map.Entry<Integer, Long> e : counts.entrySet()) {
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
     * profile 里的文件名是完整 import path，换成仓库相对路径才能与 git diff 对齐。
     * 返回 null 表示这个文件不该统计。
     */
    private String toRepoRelative(String goPath) {
        String module = props.getGoModulePath();
        String root = props.getGoSourceRoot();
        if (module == null || module.isBlank() || root == null || root.isBlank()) {
            log.warn("未配置 go-module-path / go-source-root，无法定位 Go 源码：{}", goPath);
            return null;
        }
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
