package com.rtcc.platform.service;

import com.rtcc.platform.collector.CppProbeClient;
import com.rtcc.platform.collector.GitService;
import com.rtcc.platform.collector.GoProbeClient;
import com.rtcc.platform.collector.ProbeClient;
import com.rtcc.platform.collector.ProbeDump;
import com.rtcc.platform.collector.ProbeEndpoint;
import com.rtcc.platform.collector.RustProbeClient;
import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.model.BuildVersion;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 拿一份项目配置去碰真实环境，逐项回答「这么填能不能跑起来」。
 *
 * <p>存在的理由：这套配置<b>错了是静默的</b>。探针连不上顶多状态变红，
 * 但 classes-dir 指错目录只会让覆盖率莫名其妙偏低，界面上看不出是配置问题。
 * 建项目时把这类错误当场挡下来，是唯一比事后查更省事的时点。
 *
 * <p>只读、无副作用，可以对着一份<b>还没保存的</b>配置跑 —— 向导每一步的
 * 「当场校验」和设置页的「测一下」打的都是它。探针那一项会真的连上去读一次
 * 构建版本，这与平台每 3 秒做的事完全一样，不改变被测实例的任何状态。
 */
@Component
public class ProjectChecker {

    private final ProbeClient javaProbe;
    /**
     * 按「语言 + 超时」缓存探针客户端。
     *
     * <p>Go / C++ / Rust 的客户端各自持有一个 {@code HttpClient}，而 Java 17 的
     * {@code HttpClient} 关不掉 —— 每造一个就多一个 selector 线程和一个线程池，
     * 只能等 GC。向导「每一步都能当场验」会反复打这个接口，
     * 不缓存的话线程数会按「实例数 × 调用次数」往上堆。
     *
     * <p>键里带上超时是因为客户端构造时就把它固定进去了；这三个客户端从项目配置里
     * 也只读这一项，所以同超时的可以安全共用。
     */
    private final Map<String, Object> probeCache = new ConcurrentHashMap<>();

    public ProjectChecker(ProbeClient javaProbe) {
        this.javaProbe = javaProbe;
    }

    @SuppressWarnings("unchecked")
    private <T> T probe(String lang, ProjectConfig cfg, Function<ProjectConfig, T> make) {
        return (T) probeCache.computeIfAbsent(lang + ":" + cfg.getTimeoutMs(), k -> make.apply(cfg));
    }

    /** 一项检查的结果。name 供页面定位到具体表单项，detail 是给人看的那句话 */
    public record CheckItem(String name, String label, boolean ok, String detail) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("label", label);
            m.put("ok", ok);
            m.put("detail", detail);
            return m;
        }
    }

    public Map<String, Object> check(ProjectConfig cfg) {
        List<CheckItem> items = new ArrayList<>();
        checkRepo(cfg, items);
        checkPaths(cfg, items);
        checkInstances(cfg, items);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", items.stream().allMatch(CheckItem::ok));
        res.put("items", items.stream().map(CheckItem::toMap).toList());
        return res;
    }

    /** 仓库与基线：增量口径全靠这两项，指错了「本次改动」就是错的 */
    private void checkRepo(ProjectConfig cfg, List<CheckItem> items) {
        // 这个接口按设计就是给「还没填完的配置」用的：没填就报成不通过，
        // 而不是 500 —— 向导里一个 500 会被读成平台故障
        if (cfg.getRepoDir() == null || cfg.getRepoDir().isBlank()) {
            items.add(new CheckItem("repoDir", "代码仓库", false, "没填"));
            return;
        }
        File repo = new File(cfg.getRepoDir());
        if (!repo.isDirectory()) {
            items.add(new CheckItem("repoDir", "代码仓库", false,
                    "目录不存在：" + repo.getAbsolutePath()));
            return;
        }
        GitService git = new GitService(cfg);
        try {
            String head = git.resolve("HEAD");
            items.add(new CheckItem("repoDir", "代码仓库", true,
                    repo.getAbsolutePath() + "，HEAD 为 " + head.substring(0, Math.min(8, head.length()))));
        } catch (Exception e) {
            items.add(new CheckItem("repoDir", "代码仓库", false,
                    "不是可用的 git 仓库：" + describe(e)));
            return;
        }
        try {
            String base = git.resolve(cfg.getBaseline());
            items.add(new CheckItem("baseline", "增量基线", true,
                    cfg.getBaseline() + " → " + base.substring(0, Math.min(8, base.length()))));
        } catch (Exception e) {
            items.add(new CheckItem("baseline", "增量基线", false,
                    "解析不出这个基线：" + describe(e)));
        }
    }

    /**
     * 各语言的产物与源码根。这几项是「静默错」的重灾区：
     * 产物目录指错了解不出行号，源码根指错了路径对不上 git diff。
     */
    private void checkPaths(ProjectConfig cfg, List<CheckItem> items) {
        Set<String> languages = new LinkedHashSet<>();
        for (String spec : instances(cfg)) {
            try {
                languages.add(ProbeEndpoint.parse(spec).language());
            } catch (IllegalArgumentException ignored) {
                // 地址本身填错了，checkInstances 那里会点名，这里不重复报
            }
        }
        if (languages.contains(ProbeEndpoint.JAVA)) {
            dir(items, "classesDir", "Java 产物目录（classes-dir）", cfg.getClassesDir());
            sourceRoot(items, cfg, "javaSourceRoot", "Java 源码根", cfg.getJavaSourceRoot());
        }
        if (languages.contains(ProbeEndpoint.GO)) {
            sourceRoot(items, cfg, "goSourceRoot", "Go 源码根", cfg.getGoSourceRoot());
            if (cfg.getGoModulePath() == null || cfg.getGoModulePath().isBlank()) {
                items.add(new CheckItem("goModulePath", "Go 模块路径", false,
                        "没填。覆盖数据里的文件名以它为前缀，缺了就换算不出仓库相对路径"));
            } else {
                items.add(new CheckItem("goModulePath", "Go 模块路径", true, cfg.getGoModulePath()));
            }
        }
        if (languages.contains(ProbeEndpoint.CPP)) {
            sourceRoot(items, cfg, "cppSourceRoot", "C++ 源码根", cfg.getCppSourceRoot());
            dir(items, "cppObjectsDir", "C++ 对象目录（.gcno 所在）", cfg.getCppObjectsDir());
        }
        if (languages.contains(ProbeEndpoint.RUST)) {
            sourceRoot(items, cfg, "rustSourceRoot", "Rust 源码根", cfg.getRustSourceRoot());
            file(items, "rustBinary", "Rust 产物（行号信息在它里面）", cfg.getRustBinary());
        }
    }

    private void dir(List<CheckItem> items, String name, String label, String path) {
        if (path == null || path.isBlank()) {
            items.add(new CheckItem(name, label, false, "没填"));
            return;
        }
        File f = new File(path);
        items.add(new CheckItem(name, label, f.isDirectory(),
                f.isDirectory() ? f.getAbsolutePath() : "不是有效目录：" + f.getAbsolutePath()));
    }

    private void file(List<CheckItem> items, String name, String label, String path) {
        if (path == null || path.isBlank()) {
            items.add(new CheckItem(name, label, false, "没填"));
            return;
        }
        File f = new File(path);
        items.add(new CheckItem(name, label, f.isFile(),
                f.isFile() ? f.getAbsolutePath() : "文件不存在：" + f.getAbsolutePath()));
    }

    /** 源码根是相对仓库根的，单独看这个字符串没意义，必须拼上仓库根再判 */
    private void sourceRoot(List<CheckItem> items, ProjectConfig cfg, String name, String label, String rel) {
        if (rel == null || rel.isBlank()) {
            items.add(new CheckItem(name, label, false, "没填"));
            return;
        }
        File f = new File(cfg.getRepoDir(), rel);
        items.add(new CheckItem(name, label, f.isDirectory(),
                f.isDirectory() ? f.getAbsolutePath() : "仓库里没有这个目录：" + f.getAbsolutePath()));
    }

    /**
     * 逐个探针连上去读一次构建版本，并核对各实例的版本是否一致。
     *
     * <p>版本一致性单列一项：commit 相同但一台带 -dirty，加载的是两份不同的字节码，
     * 增量口径会被平台拒绝。这个错误在别处只会表现为「增量报告用不了」，
     * 在这里才说得清是哪两台对不上。
     */
    private void checkInstances(ProjectConfig cfg, List<CheckItem> items) {
        Set<String> versions = new LinkedHashSet<>();
        boolean allConnected = true;
        List<String> specs = instances(cfg);
        if (specs.isEmpty()) {
            items.add(new CheckItem("instances", "被测实例", false,
                    "一个都没配，这个项目采不到任何数据"));
            return;
        }
        for (String spec : specs) {
            ProbeEndpoint ep;
            try {
                ep = ProbeEndpoint.parse(spec);
            } catch (IllegalArgumentException e) {
                items.add(new CheckItem("instance:" + spec, "被测实例 " + spec, false, e.getMessage()));
                allConnected = false;
                continue;
            }
            try {
                String id = buildId(ep, cfg);
                versions.add(id == null ? "(未配置构建版本)" : id);
                items.add(new CheckItem("instance:" + ep, "被测实例 " + ep, true,
                        "已连接，构建版本 " + (id == null ? "未配置（增量口径将不可用）" : id)));
            } catch (Exception e) {
                allConnected = false;
                items.add(new CheckItem("instance:" + ep, "被测实例 " + ep, false, describe(e)));
            }
        }
        // 连不上的实例根本没报版本，此时谈「版本是否一致」会得出「一致」的假结论
        if (allConnected && specs.size() > 1) {
            items.add(new CheckItem("versions", "实例间构建版本一致", versions.size() == 1,
                    versions.size() == 1 ? "全部为 " + versions.iterator().next()
                            : "各实例报的版本不一致：" + versions + "，增量口径会被拒绝"));
        }
    }

    private static List<String> instances(ProjectConfig cfg) {
        return cfg.getInstances() == null ? List.of() : cfg.getInstances();
    }

    private String buildId(ProbeEndpoint ep, ProjectConfig cfg) throws Exception {
        if (ProbeEndpoint.GO.equals(ep.language())) {
            return this.<GoProbeClient>probe("go", cfg, GoProbeClient::new).buildId(ep);
        }
        if (ProbeEndpoint.CPP.equals(ep.language())) {
            return this.<CppProbeClient>probe("cpp", cfg, CppProbeClient::new).buildId(ep);
        }
        if (ProbeEndpoint.RUST.equals(ep.language())) {
            return this.<RustProbeClient>probe("rust", cfg, RustProbeClient::new).buildId(ep);
        }
        // Java 侧的版本在 dump 的 session 信息里，没有单独的「只读版本」接口。
        // reset 传 false：检查不能把被测实例的计数器清掉
        ProbeDump dump = javaProbe.dump(ep.host(), ep.port(), false, cfg.getTimeoutMs());
        BuildVersion v = BuildVersion.parse(dump.sessions());
        // 拼回 sessionid 的原样，与另外三种语言直接报出来的串对齐 ——
        // 两边格式不一致的话，明明同一个构建也会被判成「实例间版本不一致」
        return v == null ? null : v.commit() + (v.dirty() ? "-dirty" : "");
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
