package com.coverage.platform.service;

import com.coverage.platform.collector.CoverageAnalyzer;
import com.coverage.platform.collector.GitService;
import com.coverage.platform.collector.ProbeClient;
import com.coverage.platform.collector.ProbeDump;
import com.coverage.platform.config.CoverageProperties;
import com.coverage.platform.model.BuildVersion;
import com.coverage.platform.model.FileCoverage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class CoverageService {

    private static final Logger log = LoggerFactory.getLogger(CoverageService.class);

    public static final String MODE_FULL = "full";
    public static final String MODE_INCREMENTAL = "incremental";

    private final ProbeClient probeClient;
    private final CoverageAnalyzer analyzer;
    private final GitService git;
    private final CoverageProperties props;
    private final CoveragePublisher publisher;

    /**
     * 覆盖数据与它所属的构建版本必须整体替换。
     * 拆成两个字段各自更新的话，被测服务重启换版本的那一刻，请求可能读到
     * 「新数据 + 旧版本」的组合，据此算出的增量报告行号错位却仍是 200。
     */
    private record Snapshot(Map<String, FileCoverage> files, BuildVersion version) {}

    private final AtomicReference<Snapshot> state =
            new AtomicReference<>(new Snapshot(Collections.emptyMap(), null));
    private volatile String probeStatus = "UNKNOWN";
    private volatile String lastError;
    private volatile Instant lastCollectedAt;

    public CoverageService(ProbeClient probeClient, CoverageAnalyzer analyzer, GitService git,
                           CoverageProperties props, CoveragePublisher publisher) {
        this.probeClient = probeClient;
        this.analyzer = analyzer;
        this.git = git;
        this.props = props;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${coverage.interval-ms:3000}")
    public void collect() {
        ProbeDump dump;
        try {
            dump = probeClient.dump(props.getHost(), props.getPort(), false, props.getTimeoutMs());
        } catch (Exception e) {
            // 只有这一段的失败才真正说明探针不可达
            if (!"DISCONNECTED".equals(probeStatus)) {
                log.warn("采集失败（被测服务未启动或探针未就绪）：{}", e.getMessage());
            }
            probeStatus = "DISCONNECTED";
            lastError = e.getMessage();
            return;
        }

        try {
            File classesDir = new File(props.getClassesDir());
            if (!classesDir.isDirectory()) {
                // 探针是好的，问题出在平台侧配置。混同为「探针未连接」会让人去查被测服务，方向完全错
                throw new IllegalStateException("classes-dir 不是有效目录：" + classesDir.getAbsolutePath());
            }
            Map<String, FileCoverage> fresh = analyzer.analyze(dump.exec(), classesDir);

            Map<String, FileCoverage> previous = state.get().files();
            state.set(new Snapshot(fresh, BuildVersion.parse(dump.sessions())));
            probeStatus = "CONNECTED";
            lastError = null;
            lastCollectedAt = Instant.now();

            if (changed(previous, fresh)) {
                log.info("覆盖率发生变化，已推送：{} 个文件，整体 {}%",
                        fresh.size(), String.format("%.1f", overallRatio(fresh)));
                publisher.broadcast(summary());
            }
        } catch (Exception e) {
            if (!"ANALYZE_ERROR".equals(probeStatus)) {
                log.error("探针连接正常，但覆盖数据分析失败（请检查平台的 classes-dir 配置）：{}", e.getMessage());
            }
            probeStatus = "ANALYZE_ERROR";
            lastError = e.getMessage();
        }
    }

    private boolean changed(Map<String, FileCoverage> before, Map<String, FileCoverage> after) {
        if (before.size() != after.size()) {
            return true;
        }
        for (Map.Entry<String, FileCoverage> e : after.entrySet()) {
            FileCoverage old = before.get(e.getKey());
            if (old == null || old.coveredLines() != e.getValue().coveredLines()) {
                return true;
            }
        }
        return false;
    }

    public Map<String, Object> summary() {
        return summary(MODE_FULL, null);
    }

    public Map<String, Object> summary(String mode, String baseline) {
        Snapshot s = state.get();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("probeStatus", probeStatus);
        res.put("lastError", lastError);
        res.put("lastCollectedAt", lastCollectedAt == null ? null : lastCollectedAt.toString());
        res.put("mode", mode);
        res.put("buildCommit", s.version() == null ? null : s.version().commit());

        Map<String, FileCoverage> snap = s.files();
        if (MODE_INCREMENTAL.equals(mode)) {
            String ref = baseline == null || baseline.isBlank() ? props.getBaseline() : baseline;
            Scope scope = incrementalScope(s.version(), ref);
            res.put("baseline", ref);
            res.put("baselineCommit", scope.baselineCommit());
            res.put("changedFiles", scope.lines().size());
            snap = restrict(snap, scope.lines());
        }

        List<Map<String, Object>> files = new ArrayList<>();
        snap.values().stream()
                .sorted(Comparator.comparing(FileCoverage::path))
                .forEach(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", f.path());
                    m.put("packageName", f.packageName());
                    m.put("sourceFileName", f.sourceFileName());
                    m.put("coveredLines", f.coveredLines());
                    m.put("missedLines", f.missedLines());
                    m.put("ratio", round(f.ratio()));
                    files.add(m);
                });
        res.put("overallRatio", round(overallRatio(snap)));
        res.put("files", files);
        return res;
    }

    /** 返回单文件的源码与逐行状态，供染色视图渲染 */
    public Map<String, Object> fileDetail(String path, String mode, String baseline) {
        Snapshot s = state.get();
        FileCoverage cov = s.files().get(path);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("path", path);
        res.put("mode", mode);
        if (cov == null) {
            res.put("found", false);
            return res;
        }

        Set<Integer> inDiff = null;
        if (MODE_INCREMENTAL.equals(mode)) {
            String ref = baseline == null || baseline.isBlank() ? props.getBaseline() : baseline;
            Scope scope = incrementalScope(s.version(), ref);
            res.put("baseline", ref);
            inDiff = scope.lines().getOrDefault(path, Set.of());
            cov = restrictOne(cov, inDiff);
        }

        Map<Integer, String> statusByLine = new HashMap<>();
        cov.lines().forEach(l -> statusByLine.put(l.line(), l.status()));

        List<Map<String, Object>> rows = new ArrayList<>();
        Path src = Path.of(props.getSourceDir(), path);
        try {
            List<String> srcLines = Files.readAllLines(src, StandardCharsets.UTF_8);
            for (int i = 0; i < srcLines.size(); i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("line", i + 1);
                row.put("text", srcLines.get(i));
                row.put("status", statusByLine.getOrDefault(i + 1, "EMPTY"));
                if (inDiff != null) {
                    // 基线之后没动过的行仍要显示，但在增量视图里应被淡化而非染色
                    row.put("inDiff", inDiff.contains(i + 1));
                }
                rows.add(row);
            }
            res.put("found", true);
        } catch (Exception e) {
            res.put("found", false);
            res.put("error", "源码读取失败：" + src + " —— " + e.getMessage());
        }
        res.put("ratio", round(cov.ratio()));
        res.put("coveredLines", cov.coveredLines());
        res.put("missedLines", cov.missedLines());
        res.put("rows", rows);
        return res;
    }

    /** 清零被测服务的计数器，用于「只看这一轮测试覆盖了什么」 */
    public void reset() throws Exception {
        probeClient.dump(props.getHost(), props.getPort(), true, props.getTimeoutMs());
        // 清空覆盖数据但保留版本：被测实例没重启，产物版本没变，
        // 丢掉它会让并发的增量请求误报「未上报 buildCommit」
        state.set(new Snapshot(Collections.emptyMap(), state.get().version()));
        collect();
    }

    private record Scope(String baselineCommit, Map<String, Set<Integer>> lines) {}

    /**
     * 算出「基线之后变动的行」这一分母。
     * 任何一步对不上都直接抛错：增量结果一旦行号错位，看上去依然是一份正常报告。
     */
    private Scope incrementalScope(BuildVersion bv, String baseline) {
        if (bv == null) {
            throw new IncrementalUnavailableException(
                    "被测实例未上报 buildCommit。请在 JaCoCo agent 启动参数中加上 sessionid=$(git rev-parse HEAD)");
        }
        if (bv.dirty()) {
            throw new IncrementalUnavailableException(
                    "被测产物构建于有未提交改动的工作树（" + bv.shortCommit() + "-dirty），增量口径无法与任何提交对齐");
        }
        try {
            List<String> drift = git.sourceDrift(bv.commit());
            if (!drift.isEmpty()) {
                throw new IncrementalUnavailableException("被测产物构建于 " + bv.shortCommit()
                        + "，但以下源码此后已变更，行号无法对齐，请重新构建并重启被测服务：" + drift);
            }
            String baseSha = git.resolve(baseline);
            return new Scope(baseSha, git.changedLines(baseSha, bv.commit()));
        } catch (IOException e) {
            throw new IncrementalUnavailableException("读取 git 增量信息失败：" + e.getMessage());
        }
    }

    private Map<String, FileCoverage> restrict(Map<String, FileCoverage> snap, Map<String, Set<Integer>> scope) {
        Map<String, FileCoverage> out = new LinkedHashMap<>();
        scope.forEach((path, wanted) -> {
            FileCoverage f = snap.get(path);
            if (f == null) {
                return;
            }
            FileCoverage r = restrictOne(f, wanted);
            // 变更的全是空行/注释时没有可执行行，计入分母只会稀释增量覆盖率
            if (!r.lines().isEmpty()) {
                out.put(path, r);
            }
        });
        return out;
    }

    private FileCoverage restrictOne(FileCoverage f, Set<Integer> wanted) {
        List<FileCoverage.LineCoverage> kept = f.lines().stream()
                .filter(l -> wanted.contains(l.line()))
                .toList();
        int missed = (int) kept.stream().filter(l -> "MISSED".equals(l.status())).count();
        int covered = kept.size() - missed;
        double ratio = kept.isEmpty() ? 0d : covered * 100d / kept.size();
        return new FileCoverage(f.path(), f.packageName(), f.sourceFileName(), covered, missed, ratio, kept);
    }

    private double overallRatio(Map<String, FileCoverage> snap) {
        int covered = 0, missed = 0;
        for (FileCoverage f : snap.values()) {
            covered += f.coveredLines();
            missed += f.missedLines();
        }
        int total = covered + missed;
        return total == 0 ? 0d : covered * 100d / total;
    }

    private double round(double v) {
        return Math.round(v * 10) / 10d;
    }
}
