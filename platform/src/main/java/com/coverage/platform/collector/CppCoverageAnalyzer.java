package com.coverage.platform.collector;

import com.coverage.platform.config.CoverageProperties;
import com.coverage.platform.model.FileCoverage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 归一化层（C++）：.gcno + .gcda → 行级覆盖模型。
 *
 * 与 Go 侧同构 —— 二进制格式没有对外稳定契约，交给官方工具转成文本再解析：
 * {@code gcov -t -r} 输出每行的执行次数，{@code gcov-tool merge} 在**原生数据层**
 * 合并多实例（与 Java 的 exec 探针取或、Go 的 covdata 按块求和是同一层次的操作，
 * 精度不会因为提前退化成行状态而损失）。
 *
 * 探针交回的字节流格式（大端）：重复 { u32 名字长度 | 名字 | u32 内容长度 | 内容 }。
 * 一个 C++ 服务通常有多个编译单元，就有多份 .gcda，所以要带文件名传多份。
 */
@Component
public class CppCoverageAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CppCoverageAnalyzer.class);

    /** gcov -t 的每一行：{@code <计数>:<行号>:<源码>}，行号 0 的是 Source/Graph/Data 这些元信息 */
    private static final Pattern ROW = Pattern.compile("^\\s*([^:]+):\\s*(\\d+):(.*)$");

    private final CoverageProperties props;

    public CppCoverageAnalyzer(CoverageProperties props) {
        this.props = props;
    }

    /** @param dumps 各实例交回的字节流，一个实例一份 */
    public Map<String, FileCoverage> analyze(List<byte[]> dumps) throws IOException {
        if (dumps.isEmpty()) {
            return Map.of();
        }
        String root = props.getCppSourceRoot();
        if (root == null || root.isBlank()) {
            throw new IOException("未配置 coverage.cpp-source-root，无法定位 C++ 源码");
        }
        if (props.getCppObjectsDir() == null || props.getCppObjectsDir().isBlank()) {
            throw new IOException("未配置 coverage.cpp-objects-dir，缺少 .gcno 就解不出行号");
        }
        Path objDir = Path.of(props.getCppObjectsDir());
        List<Path> gcno = listBySuffix(objDir, ".gcno");
        if (gcno.isEmpty()) {
            // .gcno 是编译期产物，与运行中的实例是否健康无关。缺了它 gcov 什么都出不来，
            // 而空结果与「这些代码没被跑过」在界面上长得一模一样
            throw new IOException("cpp-objects-dir 下没有任何 .gcno：" + objDir.toAbsolutePath()
                    + "。请确认被测服务是以 `g++ --coverage` 构建的，且该目录指向它的对象文件目录");
        }

        Path work = Files.createTempDirectory("cppcov");
        try {
            List<Path> perInstance = new ArrayList<>();
            for (int i = 0; i < dumps.size(); i++) {
                Path dir = Files.createDirectory(work.resolve("i" + i));
                unpack(dumps.get(i), dir);
                copyAll(gcno, dir);
                perInstance.add(dir);
            }
            Path profile = perInstance.size() == 1 ? perInstance.get(0) : merge(perInstance, work, gcno);
            return parse(runGcov(profile, gcno), root);
        } finally {
            deleteTree(work);
        }
    }

    /** 把探针交回的字节流还原成一个个 .gcda 文件 */
    private void unpack(byte[] payload, Path dir) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int files = 0;
        while (buf.remaining() > 0) {
            if (buf.remaining() < 4) {
                throw new IOException("探针交回的覆盖数据被截断（文件名长度字段不完整）");
            }
            int nameLen = buf.getInt();
            if (nameLen < 0 || buf.remaining() < nameLen + 4) {
                throw new IOException("探针交回的覆盖数据被截断（文件名或长度字段不完整）");
            }
            byte[] name = new byte[nameLen];
            buf.get(name);
            int dataLen = buf.getInt();
            if (dataLen < 0 || buf.remaining() < dataLen) {
                throw new IOException("探针交回的覆盖数据被截断（内容长度不足）");
            }
            byte[] data = new byte[dataLen];
            buf.get(data);
            // 文件名来自被测实例，不能直接当路径用：只取末段，挡住 ../ 之类的穿越
            Path out = dir.resolve(Path.of(new String(name, StandardCharsets.UTF_8)).getFileName().toString());
            Files.write(out, data);
            files++;
        }
        if (files == 0) {
            throw new IOException("探针交回了空的覆盖数据（一份 .gcda 都没有）");
        }
    }

    /** 多实例在 .gcda 层面合并，逐个折叠：gcov-tool 一次只吃两个目录 */
    private Path merge(List<Path> dirs, Path work, List<Path> gcno) throws IOException {
        Path acc = dirs.get(0);
        for (int i = 1; i < dirs.size(); i++) {
            Path out = work.resolve("m" + i);
            exec(List.of(props.getGcovMergeTool(), "merge",
                    acc.toAbsolutePath().toString(), dirs.get(i).toAbsolutePath().toString(),
                    "-o", out.toAbsolutePath().toString()), null, "gcov-tool merge");
            copyAll(gcno, out);
            acc = out;
        }
        return acc;
    }

    /**
     * {@code -t} 让 profile 走标准输出，不在源码树里留下 .gcov 文件；
     * {@code -r} 只输出相对路径的源码，把系统头文件挡在外面。
     * 工作目录必须是 C++ 源码根：.gcno 里记的源码名是编译时的相对名。
     */
    private String runGcov(Path profileDir, List<Path> gcno) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(props.getGcovTool(), "-t", "-r",
                "-o", profileDir.toAbsolutePath().toString()));
        gcno.forEach(p -> cmd.add(p.getFileName().toString()));
        Path cwd = Path.of(props.getRepoDir(), props.getCppSourceRoot());
        return exec(cmd, cwd, "gcov");
    }

    private String exec(List<String> cmd, Path cwd, String what) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        Process p = pb.start();
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
        // profile 全文走 stdout，可能上千行，必须读完再等进程退出
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
                    + "。请确认平台所在环境已安装 GCC 工具链（coverage.gcov-tool / gcov-merge-tool）");
        }
        return out;
    }

    private Map<String, FileCoverage> parse(String gcovOut, String root) throws IOException {
        Map<String, List<FileCoverage.LineCoverage>> byFile = new LinkedHashMap<>();
        List<FileCoverage.LineCoverage> current = null;
        for (String line : gcovOut.split("\r?\n")) {
            Matcher m = ROW.matcher(line);
            if (!m.matches()) {
                continue; // branch/call 明细行，本切片不用
            }
            String count = m.group(1).strip();
            int no = Integer.parseInt(m.group(2));
            if (no == 0) {
                String text = m.group(3);
                if (text.startsWith("Source:")) {
                    String src = text.substring("Source:".length()).strip().replace('\\', '/');
                    current = byFile.computeIfAbsent(
                            root.replace('\\', '/') + "/" + src, k -> new ArrayList<>());
                }
                continue;
            }
            if (current == null || "-".equals(count)) {
                continue; // "-" 是非可执行行，与 JaCoCo 的 EMPTY 一样不进 IR
            }
            current.add(new FileCoverage.LineCoverage(no, status(count)));
        }
        if (byFile.isEmpty()) {
            throw new IOException("gcov 没有输出任何源码的覆盖数据。"
                    + "请确认 coverage.cpp-source-root 指向编译时的工作目录（.gcno 里记的是相对源码名）");
        }

        Map<String, FileCoverage> result = new LinkedHashMap<>();
        byFile.forEach((path, lines) -> {
            int missed = (int) lines.stream().filter(l -> "MISSED".equals(l.status())).count();
            int covered = lines.size() - missed;
            int slash = path.lastIndexOf('/');
            result.put(path, new FileCoverage(
                    path,
                    slash < 0 ? "" : path.substring(0, slash).replace('/', '.'),
                    slash < 0 ? path : path.substring(slash + 1),
                    covered, missed,
                    lines.isEmpty() ? 0d : covered * 100d / lines.size(),
                    lines));
        });
        return result;
    }

    /**
     * gcov 的计数字段一共四种形态：
     * {@code #####}/{@code =====} 没跑过（后者是只能由异常路径到达的块），
     * {@code N*} 跑过但行内还有块没跑到 —— 正是 JaCoCo 的 PARTIAL，
     * {@code N} 全跑到了。C++ 因此能做到与 Java 同级的四态染色。
     */
    String status(String count) {
        if (count.startsWith("#") || count.startsWith("=")) {
            return "MISSED";
        }
        return count.endsWith("*") ? "PARTIAL" : "COVERED";
    }

    private List<Path> listBySuffix(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    private void copyAll(List<Path> files, Path dir) throws IOException {
        for (Path f : files) {
            Files.copy(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
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
