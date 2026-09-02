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
        // 方法明细：FN 给「行号,符号」，FNDA 给「执行次数,符号」，两者按符号配对。
        // 按符号去重（LinkedHashMap 天然去重）—— 泛型单态化会让同一个函数出现多条 FN
        Map<String, Map<String, int[]>> fnDetail = new LinkedHashMap<>();
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
            } else if (line.startsWith("FN:") && currentPath != null) {
                // FN:<行号>,<符号>
                String[] kv = line.substring(3).split(",", 2);
                if (kv.length == 2) {
                    fnDetail.computeIfAbsent(currentPath, k -> new LinkedHashMap<>())
                            .computeIfAbsent(kv[1].strip(), k -> new int[2])[0] =
                            Integer.parseInt(kv[0].strip());
                }
            } else if (line.startsWith("FNDA:") && currentPath != null) {
                // FNDA:<执行次数>,<符号>
                String[] kv = line.substring(5).split(",", 2);
                if (kv.length == 2) {
                    int[] v = fnDetail.computeIfAbsent(currentPath, k -> new LinkedHashMap<>())
                            .computeIfAbsent(kv[1].strip(), k -> new int[2]);
                    v[1] = Long.parseLong(kv[0].strip()) > 0 ? 1 : 0;
                }
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
                    methodsOf(fnDetail.get(path)),
                    lines));
        });
        return result;
    }

    /** 把一个文件的 FN/FNDA 配对结果转成方法明细。没有记录时给空列表而不是 null */
    private static List<FileCoverage.MethodCoverage> methodsOf(Map<String, int[]> fns) {
        if (fns == null) {
            return List.of();
        }
        List<FileCoverage.MethodCoverage> out = new ArrayList<>();
        fns.forEach((sym, v) -> out.add(new FileCoverage.MethodCoverage(
                readableName(sym), v[0],
                // 行覆盖按「这个函数跑没跑过」记：lcov 的 FNDA 只给调用次数，
                // 拿不到函数的行范围。1/1 或 0/1 至少是真的，编一个行数才是错的
                v[1], 1 - v[1],
                // 分支恒为 null，理由同文件级：rustc stable 不生成分支数据
                null, null)));
        return out;
    }

    /**
     * 从 Rust 的 <b>v0 mangled</b> 符号里抽出可读的名字。
     *
     * 实测本项目的符号是 v0（{@code _R} 开头），不是 legacy 的 {@code _ZN...E} ——
     * 两者是完全不同的两套编码，别照着 legacy 的规则去解。
     *
     * <b>这不是完整的 demangle，只是抽出末尾那几段标识符给人看。</b>
     * v0 里标识符一律以「十进制长度 + 内容」编码，中间夹着标签字母与
     * {@code s<base62>_} 形式的消歧串；扫出所有长度前缀正确的段拼起来就够读了：
     * {@code _RNvCs2r1QDoXLnWk_17demo_service_rust11json_escape}
     * → {@code demo_service_rust::json_escape}。
     *
     * <b>抽不出来就原样返回符号</b>：这一步只影响可读性，不参与任何计算 ——
     * 显示得难看好过显示得不对。真要完整 demangle 得引一个库，
     * 而本项目对「内网离线可构建」是有立场的。
     */
    static String readableName(String sym) {
        if (sym == null || !sym.startsWith("_R")) {
            return sym;
        }
        List<String> parts = new ArrayList<>();
        int i = 2;
        while (i < sym.length()) {
            char c = sym.charAt(i);
            // s<base62>_ 是消歧串、B<base62>_ 是反向引用，两者里的数字都不是长度前缀。
            // 不跳过 B 的话，NtB4_5Store 会被当成「长度 4 的标识符 _5St」——
            // 名字里就冒出 _5St 这种噪声段（实测撞到过）
            if (c == 's' || c == 'B') {
                int j = i + 1;
                while (j < sym.length() && Character.isLetterOrDigit(sym.charAt(j))) {
                    j++;
                }
                if (j < sym.length() && sym.charAt(j) == '_') {
                    i = j + 1;
                    continue;
                }
            }
            if (!Character.isDigit(c)) {
                i++;
                continue;
            }
            int j = i;
            while (j < sym.length() && Character.isDigit(sym.charAt(j))) {
                j++;
            }
            int len = Integer.parseInt(sym.substring(i, j));
            if (len <= 0 || j + len > sym.length()) {
                i = j;
                continue;
            }
            String name = sym.substring(j, j + len);
            if (name.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_')) {
                parts.add(name);
            }
            i = j + len;
        }
        return parts.isEmpty() ? sym : String.join("::", parts);
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
