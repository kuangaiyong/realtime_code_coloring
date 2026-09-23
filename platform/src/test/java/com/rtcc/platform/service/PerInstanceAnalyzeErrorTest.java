package com.rtcc.platform.service;

import com.rtcc.platform.artifact.ArtifactStore;
import com.rtcc.platform.collector.CoverageAnalyzer;
import com.rtcc.platform.collector.CppCoverageAnalyzer;
import com.rtcc.platform.collector.CppProbeClient;
import com.rtcc.platform.collector.GitService;
import com.rtcc.platform.collector.GoCoverageAnalyzer;
import com.rtcc.platform.collector.GoProbeClient;
import com.rtcc.platform.collector.ProbeClient;
import com.rtcc.platform.collector.RustCoverageAnalyzer;
import com.rtcc.platform.collector.RustProbeClient;
import com.rtcc.platform.config.CoverageProperties;
import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.history.CollectEvents;
import com.rtcc.platform.history.CoverageHistory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实例对比（perInstance）必须和聚合（doCollect）用同一把尺子分开「取不到」与「解不出」。
 *
 * <p>探针是真的：用 jacocoagent 以 tcpserver 模式拉起一个空转的 JVM，取数一定成功 ——
 * 于是这里每一行的失败都只能出在平台自己的归一化上。那样的行必须是 ANALYZE_ERROR，
 * 并照实带出这台自报的构建版本；标成 DISCONNECTED 会让人去查被测服务与探针端口，
 * 而该查的是平台这一侧。实测过：classes-dir 指错时聚合说 8 台全连上、整体 ANALYZE_ERROR，
 * 对比表却把 Java 两台报成探针不可达、版本为空。
 */
class PerInstanceAnalyzeErrorTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    static Path tmp;
    private static Process target;
    private static int port;
    /** 只放了 {@link Idle} 这一个类：classes-dir 指对时归一化结果就只有它，断言能写死 */
    private static Path targetClasses;

    /**
     * 被测 JVM 的主类。先让一个方法正常返回再挂起 —— JaCoCo 的探针打在出口上，
     * 停在 sleep 里的 main 永远等不到自己的探针，对照组就拿不到一行覆盖。
     */
    public static class Idle {
        public static void main(String[] args) throws InterruptedException {
            touch();
            Thread.sleep(Long.MAX_VALUE);
        }

        static void touch() {
        }
    }

    @BeforeAll
    static void startProbe() throws Exception {
        // 拷一份再用：被测 JVM 会一直握着 agent，直接用 target/classes 里那份，
        // 这个进程万一没收掉，下一次 mvn clean 就删不掉它（CLAUDE.md 第 5 个坑）
        Path agent = Files.copy(Path.of("target", "classes", "probe", "jacocoagent.jar"),
                tmp.resolve("jacocoagent.jar"));
        String rel = Idle.class.getName().replace('.', '/') + ".class";
        targetClasses = tmp.resolve("classes");
        Path cls = targetClasses.resolve(rel);
        Files.createDirectories(cls.getParent());
        Files.copy(Path.of(Idle.class.getResource("/" + rel).toURI()), cls);
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        // 自报一个脏构建：脏构建正是「取不到产物」最常见的原因，版本照实带出的断言才测得到 dirty
        target = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + agent + "=output=tcpserver,address=127.0.0.1,port=" + port
                        + ",sessionid=" + COMMIT + "-dirty",
                "-cp", targetClasses.toString(), Idle.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(tmp.resolve("target.log").toFile())
                .start();
        // 等到真能 dump 出数据才算探针就绪，而不是只等端口开
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            try {
                new ProbeClient().dump("127.0.0.1", port, false, 1000);
                return;
            } catch (IOException e) {
                if (!target.isAlive() || System.nanoTime() > deadline) {
                    fail("被测 JVM 的探针没起来：" + e + "\n" + Files.readString(tmp.resolve("target.log")));
                }
                Thread.sleep(100);
            }
        }
    }

    @AfterAll
    static void stopProbe() throws InterruptedException {
        // 要等它真退出：它握着 tmp 里的 agent，没退干净就轮到 @TempDir 清理，Windows 上会删不掉
        if (target != null) {
            target.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static ProjectConfig props(String classesDir) {
        ProjectConfig p = new ProjectConfig();
        p.setId("per-instance");
        p.setInstances(List.of("127.0.0.1:" + port));
        p.setTimeoutMs(3000);
        p.setClassesDir(classesDir);
        return p;
    }

    private static Map<String, Object> onlyRow(ProjectConfig props) {
        CoverageProperties platform = new CoverageProperties();
        // 实例对比不写库；这两个数据源只是构造要用，指向一个必然连不上的地址
        DriverManagerDataSource nowhere = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/nonexistent");
        ProjectRuntime runtime = new ProjectRuntime(new ProbeClient(), new CoverageAnalyzer(),
                new GoProbeClient(props), new GoCoverageAnalyzer(props, platform),
                new CppProbeClient(props), new CppCoverageAnalyzer(props, platform),
                new RustProbeClient(props), new RustCoverageAnalyzer(props, platform),
                new GitService(props), props,
                new ArtifactStore(tmp.resolve("artifacts"), 10), platform,
                new CoveragePublisher(), new CoverageHistory(nowhere), new CollectEvents(nowhere));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) runtime.perInstance().get("instances");
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    @Test
    void 配置都对时照常出这一台的覆盖() {
        // 对照组：探针与归一化都是好的。下面几条的 ANALYZE_ERROR 因此只能归到各自改坏的那一项上
        Map<String, Object> row = onlyRow(props(targetClasses.toString()));

        assertEquals("CONNECTED", row.get("status"), String.valueOf(row.get("error")));
        assertEquals(COMMIT, row.get("buildCommit"));
        assertEquals(1, row.get("fileCount"));
        assertTrue((Integer) row.get("coveredLines") > 0, "Idle.touch() 已经返回过，它那一行必定覆盖到了：" + row);
    }

    @Test
    void 产物目录指错时报分析失败而不是探针不可达() {
        Map<String, Object> row = onlyRow(props(tmp.resolve("no-such-classes-dir").toString()));

        assertEquals("ANALYZE_ERROR", row.get("status"), "探针取数成功，失败在平台侧：" + row);
        assertEquals(COMMIT, row.get("buildCommit"), "这台自报的版本已经读到了，必须照实带出");
        assertEquals(true, row.get("dirty"));
        assertTrue(String.valueOf(row.get("error")).contains("classes-dir 不是有效目录"),
                "要点名是哪一项配置，与聚合视图同一句话：" + row.get("error"));
        assertNull(row.get("coveredLines"), "解不出来时不能给覆盖数，0 会被当成真的跑了却没覆盖");
    }

    @Test
    void 产物目录指向文件时报分析失败而不是零覆盖() throws IOException {
        // JaCoCo 对认不出的文件不抛异常，静默分析出 0 个类。不拦的话这一行是 CONNECTED、0%，
        // 读起来是「这台什么都没覆盖」—— 而它明明跑过代码，这正是最要消灭的那种静默错误
        Path file = Files.writeString(tmp.resolve("not-a-dir.txt"), "x");

        Map<String, Object> row = onlyRow(props(file.toString()));

        assertEquals("ANALYZE_ERROR", row.get("status"), "不能给出一个静默的 0%：" + row);
        assertTrue(String.valueOf(row.get("error")).contains("classes-dir 不是有效目录"), String.valueOf(row.get("error")));
        assertNull(row.get("coveredLines"));
    }

    @Test
    void 按构建取产物却取不到时报分析失败并带出自报版本() {
        // 原先由 ArtifactUnavailable 单独接住的那一种。取数与归一化拆成两段之后，
        // 它和别的归一化失败走同一个分支 —— 这条守的是它的行为不能跟着变
        ProjectConfig p = props(targetClasses.toString());
        p.setArtifactSource("uploaded");

        Map<String, Object> row = onlyRow(p);

        assertEquals("ANALYZE_ERROR", row.get("status"), String.valueOf(row));
        assertEquals(COMMIT, row.get("buildCommit"));
        assertEquals(true, row.get("dirty"), "脏构建正是取不到产物最常见的原因，报成 false 等于抹掉根因");
        assertTrue(String.valueOf(row.get("error")).contains(COMMIT + "-dirty"), String.valueOf(row.get("error")));
    }
}
