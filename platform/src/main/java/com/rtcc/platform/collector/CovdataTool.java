package com.rtcc.platform.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 让 covdata 只被「现场编译」一次。
 *
 * <p><b>为什么需要：</b>Go 1.26 里 {@code covdata} 没有预编译产物 ——
 * {@code go tool} 的工具列表里没有它，{@code GOTOOLDIR} 下也没有对应的可执行文件，
 * 只有 {@code $GOROOT/src/cmd/covdata} 的源码。所以每次 {@code go tool covdata …}
 * 都要先走一遍 go 的构建缓存解析。把它 {@code go build} 成独立二进制之后，
 * 同样一条 {@code help} 的实测对比：
 *
 * <pre>
 *   机器空闲：go tool covdata  319~365ms   独立二进制   56~58ms
 *   机器繁忙：go tool covdata  3.1~3.9s    独立二进制  0.44~0.59s
 * </pre>
 *
 * <p>注意<b>省下的钱随机器负载放大</b>：空闲时约 280ms，忙时约 3s。而这是每 3 秒
 * 一轮采集都要付的，忙的时候正好把端到端染色延迟顶过 5s 那条核心断言线 ——
 * 也就是说，最需要它的时候它最贵。
 *
 * <p>一次性编译代价实测 2.6s（空闲）~8.6s（繁忙），产物按 Go 版本落在临时目录里，
 * 平台重启后复用。
 *
 * <p><b>按 Go 版本分目录不是洁癖</b>：covdata 读的是版本化的内部格式，
 * 升级 Go 之后拿旧二进制去解新数据会失败，而失败的样子是「Go 的覆盖突然没了」。
 *
 * <p>任何一步不成就退回 {@code go tool covdata}：慢，但能用。宁可慢也不能不能用。
 */
final class CovdataTool {

    private static final Logger log = LoggerFactory.getLogger(CovdataTool.class);

    /**
     * 按 {@code go} 命令本身分键。值为空串表示「这条路走不通，别再试」。
     *
     * <p>分键不只是为了可测：换一个 {@code go} 就该换一个 covdata，
     * 拿 A 版本编出来的二进制去解 B 版本的数据会失败。
     */
    private static final java.util.Map<String, String> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private CovdataTool() {
    }

    /**
     * 返回 covdata 可执行文件的绝对路径；返回空串表示只能退回 {@code go tool covdata}。
     *
     * @param goTool {@code go} 命令本身（平台级配置 {@code coverage.go-tool}）
     */
    static synchronized String path(String goTool) {
        String cached = CACHE.get(goTool);
        if (cached != null) {
            return cached;
        }
        String resolved = "";
        try {
            Ran ver = run(goTool, "env", "GOVERSION");
            String version = ver.ok() ? ver.output() : "";
            if (version.isBlank()) {
                log.info("取不到 Go 版本，Go 归一化退回 `{} tool covdata`（每轮采集多花约 3 秒）", goTool);
                CACHE.put(goTool, resolved);
                return resolved;
            }
            // 版本号里可能有斜杠之类的字符，落到目录名上要先清掉
            String safe = version.replaceAll("[^A-Za-z0-9._-]", "_");
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "rtcc-covdata-" + safe);
            Path exe = dir.resolve(System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "covdata.exe" : "covdata");
            if (Files.isRegularFile(exe)) {
                resolved = exe.toAbsolutePath().toString();
                log.info("复用已编译的 covdata：{}", resolved);
                CACHE.put(goTool, resolved);
                return resolved;
            }
            Files.createDirectories(dir);
            long t0 = System.nanoTime();
            Ran built = run(goTool, "build", "-o", exe.toAbsolutePath().toString(), "cmd/covdata");
            if (!Files.isRegularFile(exe)) {
                log.info("编译 covdata 未产出可执行文件（{}），Go 归一化退回 `{} tool covdata`"
                        + "（每轮采集多花约 3 秒）", built.output().isBlank() ? "无输出" : built.output(), goTool);
                CACHE.put(goTool, resolved);
                return resolved;
            }
            resolved = exe.toAbsolutePath().toString();
            log.info("已编译 covdata 到 {}（耗时 {}ms），此后 Go 归一化不再经过 go 的构建缓存解析",
                    resolved, (System.nanoTime() - t0) / 1_000_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.info("准备 covdata 失败（{}），Go 归一化退回 `{} tool covdata`（每轮采集多花约 3 秒）",
                    e.toString(), goTool);
        }
        CACHE.put(goTool, resolved);
        return resolved;
    }

    /** 一次子进程调用的结果。失败时的输出<b>必须留着</b>，它是唯一能说明原因的东西 */
    private record Ran(boolean ok, String output) {
    }

    /** 跑一条命令，把 stdout+stderr 一起收回来 */
    private static Ran run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        // 编译 covdata 实测约 8.6s，机器忙时更久；给足余量但不能无限等
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Ran(false, "超时未返回");
        }
        return new Ran(p.exitValue() == 0, out);
    }

    /** 拼出实际要执行的命令行：拿得到独立二进制就直接调，拿不到就退回 go tool */
    static List<String> command(String goTool, String... args) {
        String exe = path(goTool);
        if (exe.isEmpty()) {
            return concat(List.of(goTool, "tool", "covdata"), args);
        }
        return concat(List.of(exe), args);
    }

    private static List<String> concat(List<String> head, String... tail) {
        return java.util.stream.Stream.concat(head.stream(), java.util.Arrays.stream(tail)).toList();
    }
}
