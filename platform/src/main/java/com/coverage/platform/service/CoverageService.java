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
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * 一个测试场景独占的覆盖：start 时清零计数器，stop 时抓取快照，
     * 两者之间被执行到的代码就全部属于这个场景。data 在进行中时为 null。
     */
    private record Scenario(String id, Instant startedAt, Instant stoppedAt, Snapshot data) {}

    // 首期只在内存里留存，平台重启即丢。场景归因要验证的是「reset→执行→dump」这条链路，
    // 与存到哪里正交；持久化按 §7.1 另作一个切片，不塞进这里。
    private final Map<String, Scenario> scenarios = new ConcurrentHashMap<>();
    private volatile Scenario active;
    // start/stop/reset 三者互斥。都是人工触发的低频操作，用锁换取显而易见的正确性
    private final Object scenarioLock = new Object();
    /**
     * 采集必须串行。轮询线程与请求线程都会调 collect()，
     * 先开始但慢一步的那次会把后开始的结果覆盖掉 —— 抓到的是旧数据，写进去却在最后，
     * 场景 stop 时归档的就成了缺了收尾动作的快照。
     * 加锁顺序固定为 scenarioLock → collectLock，不存在反向路径。
     */
    private final Object collectLock = new Object();

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
        synchronized (collectLock) {
            doCollect();
        }
    }

    private void doCollect() {
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
        return summary(MODE_FULL, null, null);
    }

    public Map<String, Object> summary(String mode, String baseline, String scenarioId) {
        Snapshot s = sourceOf(scenarioId);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("probeStatus", probeStatus);
        res.put("lastError", lastError);
        res.put("lastCollectedAt", lastCollectedAt == null ? null : lastCollectedAt.toString());
        res.put("mode", mode);
        res.put("scenarioId", blankToNull(scenarioId));
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
    public Map<String, Object> fileDetail(String path, String mode, String baseline, String scenarioId) {
        Snapshot s = sourceOf(scenarioId);
        FileCoverage cov = s.files().get(path);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("path", path);
        res.put("mode", mode);
        res.put("scenarioId", blankToNull(scenarioId));
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
        synchronized (scenarioLock) {
            Scenario s = active;
            if (s != null) {
                // 场景进行中清零，会让 stop 抓到的数据只剩清零之后那一段，
                // 归档出来却仍标着这个场景的名字——典型的静默错误
                throw new ScenarioConflictException(
                        "场景 " + s.id() + " 正在进行中，此时清零会让它的归因数据只剩清零之后的部分。请先结束该场景");
            }
            resetCounters();
        }
    }

    private void resetCounters() throws Exception {
        // 整段与轮询采集互斥：否则一次先开始的轮询可能在清零之后才写回它清零前抓到的数据，
        // 计数器已归零，界面上却还挂着清零前的覆盖率
        synchronized (collectLock) {
            probeClient.dump(props.getHost(), props.getPort(), true, props.getTimeoutMs());
            // 清空覆盖数据但保留版本：被测实例没重启，产物版本没变，
            // 丢掉它会让并发的增量请求误报「未上报 buildCommit」
            state.set(new Snapshot(Collections.emptyMap(), state.get().version()));
            doCollect();
        }
    }

    /**
     * 开始一个场景：清零计数器，此后执行到的代码都归这个场景。
     * 同一实例同时只允许一个活跃场景 —— 两个场景共用一份计数器，数据必然互相污染。
     */
    public Map<String, Object> startScenario(String id) throws Exception {
        if (id == null || id.isBlank()) {
            throw new ScenarioConflictException("scenarioId 不能为空");
        }
        synchronized (scenarioLock) {
            if (active != null) {
                throw new ScenarioConflictException(
                        "场景 " + active.id() + " 正在进行中，同一被测实例同时只允许一个活跃场景。请先调用 /api/scenario/stop");
            }
            if (scenarios.containsKey(id)) {
                throw new ScenarioConflictException("场景 " + id + " 已归档，请换一个 scenarioId，避免覆盖已有的归因结果");
            }
            // 先清零再宣告开始：清零失败时场景不能算已开始，否则 stop 会归档一段掺着历史数据的覆盖
            resetCounters();
            active = new Scenario(id, Instant.now(), null, null);
            log.info("场景 {} 开始，计数器已清零", id);
            return describe(active);
        }
    }

    /** 结束当前场景，把这段窗口内的覆盖归档下来 */
    public Map<String, Object> stopScenario() {
        synchronized (scenarioLock) {
            Scenario s = active;
            if (s == null) {
                throw new ScenarioConflictException("当前没有进行中的场景");
            }
            // 主动抓一次，否则归档的是最多落后一个轮询周期的数据，
            // 场景末尾刚跑到的代码会被漏掉。抓取与读取必须在同一个锁里，
            // 否则中间插进来的轮询写回会让归档的又不是刚抓到的那一份
            Snapshot fresh;
            synchronized (collectLock) {
                doCollect();
                if (!"CONNECTED".equals(probeStatus)) {
                    // 抓不到最新数据就归档，存下来的是探针出问题之前的快照，却挂着这个场景的名字。
                    // 场景保持活跃，修好探针后可以重试 stop
                    throw new ScenarioConflictException("场景 " + s.id() + " 结束时无法取到最新覆盖数据（"
                            + probeStatus + "：" + lastError + "）。场景仍在进行中，修复探针后重试 stop");
                }
                fresh = state.get();
            }
            Scenario done = new Scenario(s.id(), s.startedAt(), Instant.now(), fresh);
            scenarios.put(done.id(), done);
            active = null;
            log.info("场景 {} 结束，独占覆盖 {} 个文件", done.id(), done.data().files().size());
            return describe(done);
        }
    }

    /** 已归档的场景列表 + 当前活跃场景 */
    public Map<String, Object> listScenarios() {
        Map<String, Object> res = new LinkedHashMap<>();
        Scenario cur = active;
        res.put("active", cur == null ? null : cur.id());
        res.put("scenarios", scenarios.values().stream()
                .sorted(Comparator.comparing(Scenario::startedAt))
                .map(this::describe)
                .toList());
        return res;
    }

    private Map<String, Object> describe(Scenario s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenarioId", s.id());
        m.put("startedAt", s.startedAt().toString());
        m.put("stoppedAt", s.stoppedAt() == null ? null : s.stoppedAt().toString());
        m.put("running", s.data() == null);
        if (s.data() != null) {
            m.put("files", s.data().files().size());
            m.put("overallRatio", round(overallRatio(s.data().files())));
        }
        return m;
    }

    /** 响应里的 scenarioId 要如实说明数据来自哪个场景，空串不是场景 */
    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * 选数据源：不指定场景就看实时快照，指定了就看那个场景归档下来的独占覆盖。
     * 场景与全量/增量口径正交 —— 「场景 × 增量」正好回答「这个场景覆盖了本次改动的哪几行」。
     */
    private Snapshot sourceOf(String scenarioId) {
        if (blankToNull(scenarioId) == null) {
            return state.get();
        }
        Scenario s = scenarios.get(scenarioId);
        if (s != null) {
            return s.data();
        }
        // 进行中的场景只存在于 active，还没进归档表。不看这一眼的话，
        // 正在录的场景会被报成「不存在」，把用户引去排查场景 ID 是不是拼错了
        Scenario cur = active;
        if (cur != null && cur.id().equals(scenarioId)) {
            throw new ScenarioConflictException("场景 " + scenarioId + " 尚未结束，覆盖数据要到 stop 时才定格");
        }
        throw new ScenarioNotFoundException("场景 " + scenarioId + " 不存在（未开始过，或平台重启后已丢失）");
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
