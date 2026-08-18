package com.coverage.platform.service;

import com.coverage.platform.collector.CoverageAnalyzer;
import com.coverage.platform.collector.GitService;
import com.coverage.platform.collector.ProbeClient;
import com.coverage.platform.collector.ProbeDump;
import com.coverage.platform.collector.ProbeEndpoint;
import com.coverage.platform.collector.CppCoverageAnalyzer;
import com.coverage.platform.collector.CppProbeClient;
import com.coverage.platform.collector.GoCoverageAnalyzer;
import com.coverage.platform.collector.GoProbeClient;
import com.coverage.platform.collector.RustCoverageAnalyzer;
import com.coverage.platform.collector.RustProbeClient;
import com.coverage.platform.config.CoverageProperties;
import com.coverage.platform.model.BuildVersion;
import com.coverage.platform.model.FileCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
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
import java.util.stream.Collectors;

@Service
public class CoverageService {

    private static final Logger log = LoggerFactory.getLogger(CoverageService.class);

    public static final String MODE_FULL = "full";
    public static final String MODE_INCREMENTAL = "incremental";

    private final ProbeClient probeClient;
    private final CoverageAnalyzer analyzer;
    private final GoProbeClient goProbe;
    private final GoCoverageAnalyzer goAnalyzer;
    private final CppProbeClient cppProbe;
    private final CppCoverageAnalyzer cppAnalyzer;
    private final RustProbeClient rustProbe;
    private final RustCoverageAnalyzer rustAnalyzer;
    private final GitService git;
    private final CoverageProperties props;
    private final CoveragePublisher publisher;

    /**
     * 覆盖数据与它所属的构建版本必须整体替换。
     * 拆成两个字段各自更新的话，被测服务重启换版本的那一刻，请求可能读到
     * 「新数据 + 旧版本」的组合，据此算出的增量报告行号错位却仍是 200。
     *
     * versionError 记录「多实例之间版本对不上」这种拿不出统一版本的原因，
     * 与 version==null（压根没上报）要分开讲，否则错误提示会把人引向错误的排查方向。
     */
    private record Snapshot(Map<String, FileCoverage> files, BuildVersion version, String versionError) {}

    /**
     * 单个被测实例的采集状态，用于回答「少的那部分覆盖是哪台机器上的」。
     * version 保留 dirty 标记：同一提交的干净产物与脏产物是两份不同的字节码，
     * 只比 commit 会把它们当成同一个版本。
     */
    private record InstanceStatus(String endpoint, String status, BuildVersion version, String error) {
        String label() {
            return version == null ? null : version.shortCommit() + (version.dirty() ? "-dirty" : "");
        }
    }

    private final AtomicReference<Snapshot> state =
            new AtomicReference<>(new Snapshot(Collections.emptyMap(), null, null));
    private volatile String probeStatus = "UNKNOWN";
    private volatile String lastError;
    private volatile Instant lastCollectedAt;
    private volatile List<InstanceStatus> instances = List.of();

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

    public CoverageService(ProbeClient probeClient, CoverageAnalyzer analyzer,
                           GoProbeClient goProbe, GoCoverageAnalyzer goAnalyzer,
                           CppProbeClient cppProbe, CppCoverageAnalyzer cppAnalyzer,
                           RustProbeClient rustProbe, RustCoverageAnalyzer rustAnalyzer, GitService git,
                           CoverageProperties props, CoveragePublisher publisher) {
        this.probeClient = probeClient;
        this.analyzer = analyzer;
        this.goProbe = goProbe;
        this.goAnalyzer = goAnalyzer;
        this.cppProbe = cppProbe;
        this.cppAnalyzer = cppAnalyzer;
        this.rustProbe = rustProbe;
        this.rustAnalyzer = rustAnalyzer;
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
        List<ProbeEndpoint> endpoints;
        try {
            endpoints = endpoints();
        } catch (RuntimeException e) {
            // 地址配错了要说出来。让异常从 @Scheduled 里抛出去，只会进服务端日志，
            // 界面上永远停在「状态未知」且没有任何提示，看不出问题在平台自己的配置里
            if (!"CONFIG_ERROR".equals(probeStatus)) {
                log.error("被测实例地址配置有误（coverage.instances）：{}", e.getMessage());
            }
            probeStatus = "CONFIG_ERROR";
            lastError = e.getMessage();
            return;
        }
        // 同语言的实例先在各自的原生数据层面合并：Java 走 exec 的探针取或，
        // Go 交给 covdata 按块求和，C++ 走 gcov-tool merge，Rust 走 llvm-profdata merge。
        // 若提前退化成行状态再合并，两台各覆盖一半分支时
        // 结果会从 COVERED 掉成 PARTIAL，精度平白损失。
        // 跨语言才在 IR 层合并 —— 各语言的文件路径互不相交，直接并起来即可
        ExecutionDataStore javaMerged = new ExecutionDataStore();
        boolean anyJava = false;
        List<byte[][]> goDumps = new ArrayList<>();
        List<byte[]> cppDumps = new ArrayList<>();
        List<byte[]> rustDumps = new ArrayList<>();
        List<InstanceStatus> statuses = new ArrayList<>();
        List<BuildVersion> reported = new ArrayList<>();

        for (ProbeEndpoint ep : endpoints) {
            try {
                BuildVersion v;
                if (ProbeEndpoint.GO.equals(ep.language())) {
                    // 版本先取、数据后收，且三次抓取全部成功才入列。
                    // 反过来的话，buildId 失败时这台已被标成 DISCONNECTED，
                    // 它的覆盖却已经并进了聚合结果，而版本又没进 unifiedVersion 的校验
                    // —— 实例间版本不一致会被这条缝绕过去
                    v = BuildVersion.parseId(goProbe.buildId(ep));
                    // meta 与 counters 要一并取：counters 单独存在没有意义，
                    // 块的位置信息全在 meta 里
                    byte[] meta = goProbe.meta(ep);
                    byte[] counters = goProbe.counters(ep);
                    goDumps.add(new byte[][]{meta, counters});
                } else if (ProbeEndpoint.CPP.equals(ep.language())) {
                    v = BuildVersion.parseId(cppProbe.buildId(ep));
                    // dump 会顺带清零并重新武装计数器：gcov 的 __gcov_dump() 只生效一次，
                    // 不 reset 的话下一次什么都不写。累计不会丢 —— .gcda 写入是合并语义
                    cppDumps.add(cppProbe.dump(ep));
                } else if (ProbeEndpoint.RUST.equals(ep.language())) {
                    v = BuildVersion.parseId(rustProbe.buildId(ep));
                    // 与 C++ 不同，__llvm_profile_write_file() 可以反复调用，不必 reset 重新武装；
                    // 但它同样是合并语义，所以探针每次落盘前会先删掉旧的 .profraw，交回的是当前累计值
                    rustDumps.add(rustProbe.dump(ep));
                } else {
                    ProbeDump dump = probeClient.dump(ep.host(), ep.port(), false, props.getTimeoutMs());
                    for (ExecutionData d : dump.exec().getContents()) {
                        javaMerged.visitClassExecution(d);
                    }
                    anyJava = true;
                    v = BuildVersion.parse(dump.sessions());
                }
                reported.add(v);
                statuses.add(new InstanceStatus(ep.toString(), "CONNECTED", v, null));
            } catch (Exception e) {
                // 单个实例不可达不该让整批采集失败：其余实例的数据仍然是真实的，
                // 只是这一份聚合结果少了它那部分，必须在状态里说清楚是哪一台
                statuses.add(new InstanceStatus(ep.toString(), "DISCONNECTED", null, describe(e)));
            }
        }
        instances = List.copyOf(statuses);

        long connected = statuses.stream().filter(s -> "CONNECTED".equals(s.status())).count();
        if (connected == 0) {
            if (!"DISCONNECTED".equals(probeStatus)) {
                log.warn("采集失败，{} 个实例全部不可达（被测服务未启动或探针未就绪）", endpoints.size());
            }
            probeStatus = "DISCONNECTED";
            lastError = statuses.isEmpty() ? "未配置任何被测实例（coverage.instances）"
                    : statuses.get(0).error();
            return;
        }

        try {
            Map<String, FileCoverage> fresh = new LinkedHashMap<>();
            if (anyJava) {
                File classesDir = new File(props.getClassesDir());
                if (!classesDir.isDirectory()) {
                    // 探针是好的，问题出在平台侧配置。混同为「探针未连接」会让人去查被测服务，方向完全错
                    throw new IllegalStateException("classes-dir 不是有效目录：" + classesDir.getAbsolutePath());
                }
                fresh.putAll(analyzer.analyze(javaMerged, classesDir, props.getJavaSourceRoot()));
            }
            if (!goDumps.isEmpty()) {
                fresh.putAll(goAnalyzer.analyze(goDumps));
            }
            if (!cppDumps.isEmpty()) {
                fresh.putAll(cppAnalyzer.analyze(cppDumps));
            }
            if (!rustDumps.isEmpty()) {
                fresh.putAll(rustAnalyzer.analyze(rustDumps));
            }

            Map<String, FileCoverage> previous = state.get().files();
            state.set(new Snapshot(fresh, unifiedVersion(reported), versionConflict(statuses)));
            // 少一台实例，聚合结果就少一部分覆盖：那些行会显示成红色，但其实别的机器跑到了。
            // 这不是「数据不可用」而是「数据不完整」，所以照常出报告，但状态必须与全连上区分开
            probeStatus = connected == endpoints.size() ? "CONNECTED" : "PARTIAL";
            lastError = connected == endpoints.size() ? null
                    : statuses.stream().filter(s -> !"CONNECTED".equals(s.status()))
                            .map(s -> s.endpoint() + "（" + s.error() + "）")
                            .collect(Collectors.joining("、", "以下实例不可达，聚合结果缺少它们的覆盖：", ""));
            lastCollectedAt = Instant.now();

            if (changed(previous, fresh)) {
                log.info("覆盖率发生变化，已推送：{} 个文件，整体 {}%",
                        fresh.size(), String.format("%.1f", overallRatio(fresh)));
                publisher.broadcast(summary());
            }
        } catch (Exception e) {
            if (!"ANALYZE_ERROR".equals(probeStatus)) {
                // 不在这里猜是哪项配置的问题：Java 走 classes-dir、Go 走 go-tool，
                // 写死一个只会把另一种语言的故障引去查错地方。异常自己会说明白
                log.error("探针连接正常，但覆盖数据分析失败：{}", describe(e));
            }
            probeStatus = "ANALYZE_ERROR";
            lastError = describe(e);
        }
    }

    /**
     * 连接类异常常常没有 message（如 ConnectException），直接取会让界面上显示
     * 「go://localhost:6400（null）」——看着像平台的 bug，其实是对方没起来。
     */
    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    /**
     * 按实例分别归一化，供「实例对比」视图按需调用。
     *
     * 为什么不放进 3 秒一轮的热路径：doCollect 是「先在原生数据层合并、再归一化」，
     * 这里是反过来的「先按实例归一化」。两者不能互相替代 ——
     * 两台各覆盖同一行的不同分支时，前者得到 COVERED，后者两边都是 PARTIAL。
     * 所以**各实例的行状态不能拿来当聚合用**，聚合值仍以 /summary 为准；
     * 这个接口只回答「哪台实例跑到了什么」。
     *
     * 取数走的是与 doCollect 同一批非破坏性接口（Java 的 dump 传 reset=false、
     * Go 读 counters、C++/Rust 的 dump 是累计语义），多取这一次不会把覆盖弄丢。
     * 但它对每个实例各调一次外部工具（gcov / llvm-cov / covdata），
     * 开销与实例数成正比 —— 这正是它按需触发、而不进热路径的原因。
     */
    public Map<String, Object> perInstance() {
        synchronized (collectLock) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ProbeEndpoint ep : endpoints()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("endpoint", ep.toString());
                row.put("language", ep.language());
                try {
                    Map<String, FileCoverage> files;
                    BuildVersion v;
                    if (ProbeEndpoint.GO.equals(ep.language())) {
                        v = BuildVersion.parseId(goProbe.buildId(ep));
                        // 显式类型见证：List.of 的可变参数会把 byte[][] 拆成 List<byte[]>
                        files = goAnalyzer.analyze(List.<byte[][]>of(
                                new byte[][]{goProbe.meta(ep), goProbe.counters(ep)}));
                    } else if (ProbeEndpoint.CPP.equals(ep.language())) {
                        v = BuildVersion.parseId(cppProbe.buildId(ep));
                        files = cppAnalyzer.analyze(List.of(cppProbe.dump(ep)));
                    } else if (ProbeEndpoint.RUST.equals(ep.language())) {
                        v = BuildVersion.parseId(rustProbe.buildId(ep));
                        files = rustAnalyzer.analyze(List.of(rustProbe.dump(ep)));
                    } else {
                        ProbeDump dump = probeClient.dump(ep.host(), ep.port(), false, props.getTimeoutMs());
                        v = BuildVersion.parse(dump.sessions());
                        files = analyzer.analyze(dump.exec(), new File(props.getClassesDir()),
                                props.getJavaSourceRoot());
                    }
                    int covered = 0, missed = 0;
                    for (FileCoverage f : files.values()) {
                        covered += f.coveredLines();
                        missed += f.missedLines();
                    }
                    row.put("status", "CONNECTED");
                    row.put("buildCommit", v == null ? null : v.commit());
                    row.put("dirty", v != null && v.dirty());
                    row.put("overallRatio", round(overallRatio(files)));
                    row.put("coveredLines", covered);
                    row.put("missedLines", missed);
                    row.put("fileCount", files.size());
                    row.put("error", null);
                } catch (Exception e) {
                    // 一台取不到不该让整张对比表失败：其余实例的数据仍然是真的。
                    // 但这一行必须显式标成取不到，不能留空让人读成「这台什么都没跑」
                    row.put("status", "DISCONNECTED");
                    row.put("buildCommit", null);
                    row.put("dirty", false);
                    row.put("overallRatio", null);
                    row.put("coveredLines", null);
                    row.put("missedLines", null);
                    row.put("fileCount", null);
                    row.put("error", describe(e));
                }
                rows.add(row);
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("instances", rows);
            res.put("collectedAt", Instant.now().toString());
            return res;
        }
    }

    private List<ProbeEndpoint> endpoints() {
        return props.getInstances().stream().map(ProbeEndpoint::parse).toList();
    }

    /**
     * 全部连上的实例都报同一个版本，才算这批数据有统一版本。
     * 只要有一台没报或报得不一样，就拿不出可信的版本，增量口径必须停下。
     */
    private BuildVersion unifiedVersion(List<BuildVersion> reported) {
        if (reported.isEmpty() || reported.contains(null)) {
            return null;
        }
        BuildVersion first = reported.get(0);
        return reported.stream().allMatch(v -> v.commit().equals(first.commit()) && v.dirty() == first.dirty())
                ? first : null;
    }

    /**
     * 实例之间版本不一致时，说清楚是哪台报了哪个版本。
     *
     * 这种情况下聚合结果是错的且看不出来：JaCoCo 按 class id 匹配字节码，
     * 版本对不上的那台实例的执行数据会被静默丢弃 —— 它跑过的行照样显示成红的。
     */
    private String versionConflict(List<InstanceStatus> statuses) {
        // 按「commit + dirty」分组。只按 commit 分的话，同一提交的干净产物与脏产物会被
        // 当成同一个版本：冲突不上报，版本又统一不了，最后落到「未上报 sessionid」那句
        // 完全无关的提示上，排查直接走进死胡同
        Map<String, List<String>> byVersion = new LinkedHashMap<>();
        for (InstanceStatus s : statuses) {
            if ("CONNECTED".equals(s.status()) && s.version() != null) {
                byVersion.computeIfAbsent(s.label(), k -> new ArrayList<>()).add(s.endpoint());
            }
        }
        if (byVersion.size() <= 1) {
            return null;
        }
        String detail = byVersion.entrySet().stream()
                .map(e -> e.getKey() + " ← " + String.join("、", e.getValue()))
                .collect(Collectors.joining("；"));
        return "各被测实例的产物版本不一致（" + detail + "）。它们加载的字节码不同，"
                + "聚合覆盖会静默丢弃对不上的那部分，请统一版本后重启被测服务";
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
        res.put("lastCollectedAt", lastCollectedAt == null ? null : lastCollectedAt.toString());
        res.put("mode", mode);
        res.put("scenarioId", blankToNull(scenarioId));
        res.put("buildCommit", s.version() == null ? null : s.version().commit());
        res.put("versionError", s.versionError());
        if (blankToNull(scenarioId) == null) {
            res.put("probeStatus", probeStatus);
            res.put("lastError", lastError);
            // 逐实例列出来，少的那部分覆盖是哪台机器上的一眼可见
            res.put("instances", instances.stream().map(i -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("endpoint", i.endpoint());
                m.put("status", i.status());
                m.put("buildCommit", i.version() == null ? null : i.version().commit());
                m.put("dirty", i.version() != null && i.version().dirty());
                m.put("error", i.error());
                return m;
            }).toList());
        } else {
            // 场景数据在 stop 那一刻就定格了，探针此刻是否健康与它无关。
            // 照搬实时状态的话，事后挂掉一台实例，会给一份本就完整的归档数据扣上
            // 「覆盖数据不完整」的警告，把人从可信的结论上劝走
            res.put("probeStatus", "ARCHIVED");
            res.put("lastError", null);
            res.put("instances", List.of());
        }

        Map<String, FileCoverage> snap = s.files();
        if (MODE_INCREMENTAL.equals(mode)) {
            String ref = baseline == null || baseline.isBlank() ? props.getBaseline() : baseline;
            Scope scope = incrementalScope(s, ref);
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
            Scope scope = incrementalScope(s, ref);
            res.put("baseline", ref);
            inDiff = scope.lines().getOrDefault(path, Set.of());
            cov = restrictOne(cov, inDiff);
        }

        Map<Integer, String> statusByLine = new HashMap<>();
        cov.lines().forEach(l -> statusByLine.put(l.line(), l.status()));

        List<Map<String, Object>> rows = new ArrayList<>();
        // IR 里的路径以仓库根为基准，多语言各自的源码根都在其下
        Path src = Path.of(props.getRepoDir(), path);
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
            // 每个实例都要清零。漏掉一台，它上面的历史覆盖会在下次采集时并进来，
            // 场景归因就会把别人早先跑过的代码算成这个场景跑的。
            // 任一台失败就抛出：宁可让调用方重试，也不能留下半清零的集群
            for (ProbeEndpoint ep : endpoints()) {
                if (ProbeEndpoint.GO.equals(ep.language())) {
                    goProbe.clear(ep);
                } else if (ProbeEndpoint.CPP.equals(ep.language())) {
                    cppProbe.clear(ep);
                } else if (ProbeEndpoint.RUST.equals(ep.language())) {
                    rustProbe.clear(ep);
                } else {
                    probeClient.dump(ep.host(), ep.port(), true, props.getTimeoutMs());
                }
            }
            // 清空覆盖数据但保留版本：被测实例没重启，产物版本没变，
            // 丢掉它会让并发的增量请求误报「未上报 buildCommit」
            Snapshot prev = state.get();
            state.set(new Snapshot(Collections.emptyMap(), prev.version(), prev.versionError()));
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
    private Scope incrementalScope(Snapshot snapshot, String baseline) {
        if (snapshot.versionError() != null) {
            // 版本不一致要单独报。落到下面那句「请加 sessionid」会把人引去改启动参数，
            // 而真正该做的是把各实例的产物统一
            throw new IncrementalUnavailableException(snapshot.versionError());
        }
        BuildVersion bv = snapshot.version();
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
