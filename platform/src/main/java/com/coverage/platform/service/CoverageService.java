package com.coverage.platform.service;

import com.coverage.platform.collector.CoverageAnalyzer;
import com.coverage.platform.collector.ProbeClient;
import com.coverage.platform.config.CoverageProperties;
import com.coverage.platform.model.FileCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class CoverageService {

    private static final Logger log = LoggerFactory.getLogger(CoverageService.class);

    private final ProbeClient probeClient;
    private final CoverageAnalyzer analyzer;
    private final CoverageProperties props;
    private final CoveragePublisher publisher;

    private final AtomicReference<Map<String, FileCoverage>> snapshot =
            new AtomicReference<>(Collections.emptyMap());
    private volatile String probeStatus = "UNKNOWN";
    private volatile String lastError;
    private volatile Instant lastCollectedAt;

    public CoverageService(ProbeClient probeClient, CoverageAnalyzer analyzer,
                           CoverageProperties props, CoveragePublisher publisher) {
        this.probeClient = probeClient;
        this.analyzer = analyzer;
        this.props = props;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${coverage.interval-ms:3000}")
    public void collect() {
        ExecutionDataStore exec;
        try {
            exec = probeClient.dump(props.getHost(), props.getPort(), false, props.getTimeoutMs());
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
            Map<String, FileCoverage> fresh = analyzer.analyze(exec, classesDir);

            Map<String, FileCoverage> previous = snapshot.get();
            snapshot.set(fresh);
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
        Map<String, FileCoverage> snap = snapshot.get();
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

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("probeStatus", probeStatus);
        res.put("lastError", lastError);
        res.put("lastCollectedAt", lastCollectedAt == null ? null : lastCollectedAt.toString());
        res.put("overallRatio", round(overallRatio(snap)));
        res.put("files", files);
        return res;
    }

    /** 返回单文件的源码与逐行状态，供染色视图渲染 */
    public Map<String, Object> fileDetail(String path) {
        FileCoverage cov = snapshot.get().get(path);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("path", path);
        if (cov == null) {
            res.put("found", false);
            return res;
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
        snapshot.set(Collections.emptyMap());
        collect();
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
