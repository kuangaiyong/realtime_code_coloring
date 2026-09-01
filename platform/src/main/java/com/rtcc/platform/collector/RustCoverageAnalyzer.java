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

/**
 * 归一化层（Rust）：.profraw → 行级覆盖模型。
 *
 * 与 Go / C++ 同构 —— 二进制格式没有对外稳定契约，交给官方工具转成文本再解析：
 * {@code llvm-profdata merge} 在**原生数据层**合并多实例（与 Java 的 exec 取或、
 * Go 的 covdata 按块求和、C++ 的 gcov-tool merge 同一层次），
 * {@code llvm-cov export --format=lcov} 输出逐行执行次数。
 *
 * LCOV 正是预研报告 §5.1 建议的统一 IR 落地格式，这里终于用上了它的原生形态。
 * 行号信息全在 .profraw 与产物的 coverage mapping 里，所以必须配 rust-binary
 * —— 相当于 Java 的 classes-dir、C++ 的 .gcno。
 */
public class RustCoverageAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(RustCoverageAnalyzer.class);

    private final ProjectConfig props;
    /** 工具链可执行文件的路径是部署机器的属性，换机器才改，与项目无关，因此仍从平台配置取 */
    private final CoverageProperties platform;

    public RustCoverageAnalyzer(ProjectConfig props, CoverageProperties platform) {
        this.props = props;
        this.platform = platform;
    }

    /** @param dumps 各实例交回的 .profraw，一个实例一份 */
    public Map<String, FileCoverage> analyze(List<byte[]> dumps) throws IOException {
        if (dumps.isEmpty()) {
            return Map.of();
        }
        if (props.getRustSourceRoot() == null || props.getRustSourceRoot().isBlank()) {
            throw new IOException("未配置 coverage.rust-source-root，无法界定 Rust 源码范围");
        }
        String binary = props.getRustBinary();
        if (binary == null || binary.isBlank()) {
            throw new IOException("未配置 coverage.rust-binary，缺少产物就读不出 coverage mapping");
        }
        Path bin = Path.of(binary);
        if (!Files.isRegularFile(bin)) {
            // 产物是编译期的东西，与运行中的实例是否健康无关。缺了它 llvm-cov 什么都出不来，
            // 而空结果与「这些代码没被跑过」在界面上长得一模一样
            throw new IOException("rust-binary 不存在：" + bin.toAbsolutePath()
                    + "。请确认被测服务是以 `-C instrument-coverage` 构建的，且该路径指向它的产物");
        }

        Path work = Files.createTempDirectory("rustcov");
        try {
            List<String> merge = new ArrayList<>(List.of(platform.getLlvmProfdataTool(), "merge", "-sparse"));
            for (int i = 0; i < dumps.size(); i++) {
                Path raw = work.resolve("i" + i + ".profraw");
                Files.write(raw, dumps.get(i));
                merge.add(raw.toAbsolutePath().toString());
            }
            Path data = work.resolve("merged.profdata");
            merge.addAll(List.of("-o", data.toAbsolutePath().toString()));
            exec(merge, "llvm-profdata merge");

            String lcov = exec(List.of(platform.getLlvmCovTool(), "export",
                    "--instr-profile=" + data.toAbsolutePath(),
                    bin.toAbsolutePath().toString(), "--format=lcov"), "llvm-cov export");
            return parse(lcov);
        } finally {
            deleteTree(work);
        }
    }

    /**
     * LCOV 里的 SF 是编译时的绝对路径，要换成仓库相对路径才能与 git diff 对齐。
     * DA:行号,执行次数；没出现在 DA 里的行就是非可执行行，与 JaCoCo 的 EMPTY 一样不进 IR。
     */
    Map<String, FileCoverage> parse(String lcov) throws IOException {
        String repo = Path.of(props.getRepoDir()).toAbsolutePath().normalize()
                .toString().replace('\\', '/');
        String root = props.getRustSourceRoot().replace('\\', '/');

        Map<String, List<FileCoverage.LineCoverage>> byFile = new LinkedHashMap<>();
        // FNF/FNH 是文件级汇总，直接用；不从 FN/FNDA 逐条累加 ——
        // 泛型单态化会让同一个函数出现多条 FN 记录，逐条累加会把分母放大
        Map<String, int[]> fnByFile = new LinkedHashMap<>();
        String currentPath = null;
        List<FileCoverage.LineCoverage> current = null;
        int files = 0;
        for (String line : lcov.split("\r?\n")) {
            if (line.startsWith("SF:")) {
                files++;
                String rel = toRepoRelative(line.substring(3).strip(), repo);
                // 只统计 Rust 源码根之下的文件：依赖库的代码不是被测对象
                boolean wanted = rel != null && rel.startsWith(root + "/");
                currentPath = wanted ? rel : null;
                current = wanted ? byFile.computeIfAbsent(rel, k -> new ArrayList<>()) : null;
            } else if (line.startsWith("FNF:") && currentPath != null) {
                fnByFile.computeIfAbsent(currentPath, k -> new int[2])[0] =
                        Integer.parseInt(line.substring(4).strip());
            } else if (line.startsWith("FNH:") && currentPath != null) {
                fnByFile.computeIfAbsent(currentPath, k -> new int[2])[1] =
                        Integer.parseInt(line.substring(4).strip());
            } else if (line.startsWith("DA:") && current != null) {
                String[] kv = line.substring(3).split(",");
                if (kv.length >= 2) {
                    long count = Long.parseLong(kv[1].strip());
                    current.add(new FileCoverage.LineCoverage(
                            Integer.parseInt(kv[0].strip()), count > 0 ? "COVERED" : "MISSED",
                            null, null));
                }
            } else if (line.startsWith("end_of_record")) {
                current = null;
                currentPath = null;
            }
        }
        // llvm-cov 可以正常退出却什么都没输出。静默放过的话界面上 Rust 直接消失，
        // 与「Rust 代码一行都没被调用」长得完全一样 —— 这正是最该拒绝出报告的那类情况
        if (files == 0) {
            throw new IOException("llvm-cov 没有输出任何源码的覆盖数据，"
                    + "请确认被测服务是以 `-C instrument-coverage` 构建的");
        }
        if (byFile.isEmpty()) {
            throw new IOException("Rust 覆盖数据里没有一个文件位于 " + root + " 之下，"
                    + "coverage.rust-source-root 与实际源码位置可能对不上");
        }

        Map<String, FileCoverage> result = new LinkedHashMap<>();
        byFile.forEach((path, lines) -> {
            if (lines.isEmpty()) {
                return;
            }
            int missed = (int) lines.stream().filter(l -> "MISSED".equals(l.status())).count();
            int covered = lines.size() - missed;
            // fn[0]=FNF 函数总数，fn[1]=FNH 命中数
            int[] fn = fnByFile.getOrDefault(path, new int[2]);
            int slash = path.lastIndexOf('/');
            result.put(path, new FileCoverage(
                    path,
                    slash < 0 ? "" : path.substring(0, slash).replace('/', '.'),
                    slash < 0 ? path : path.substring(slash + 1),
                    covered, missed,
                    covered * 100d / lines.size(),
                    // 分支恒为 null：实测 BRF:0，rustc stable 的 -C instrument-coverage
                    // 不生成分支数据（要 nightly 的 -Z coverage-options=branch）。
                    // 填 0 会让页面显示「Rust 分支覆盖 0%」，读的人以为一个都没测
                    null, null,
                    fn[1], fn[0] - fn[1],
                    null,
                    lines));
        });
        return result;
    }

    /** 返回 null 表示这个文件不在仓库里（依赖库、标准库） */
    private String toRepoRelative(String abs, String repo) {
        String p = abs.replace('\\', '/');
        // Windows 上盘符大小写不稳定，路径比较必须忽略大小写
        if (p.length() > repo.length() + 1 && p.regionMatches(true, 0, repo, 0, repo.length())
                && p.charAt(repo.length()) == '/') {
            return p.substring(repo.length() + 1);
        }
        return null;
    }

    private String exec(List<String> cmd, String what) throws IOException {
        Process p = new ProcessBuilder(cmd).start();
        // stderr 另起线程读走，避免管道写满时两端互相阻塞
        StringBuilder err = new StringBuilder();
        Thread drain = new Thread(() -> {
            try {
                err.append(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });
        drain.setDaemon(true);
        drain.start();
        // LCOV 全文走 stdout，可能上千行，必须读完再等进程退出
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(what + " 超时未返回");
            }
            drain.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 " + what + " 被中断", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException(what + " 失败（exit " + p.exitValue() + "）：" + err.toString().trim()
                    + "。请确认平台所在环境有与 rustc 版本匹配的 llvm-tools"
                    + "（coverage.llvm-profdata-tool / llvm-cov-tool）");
        }
        return out;
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
