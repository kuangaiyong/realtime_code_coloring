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
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public ProjectChecker(ProbeClient javaProbe) {
        this.javaProbe = javaProbe;
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
        // timeoutMs 必须先判，而且判不过就不能去连实例：
        // ProbeClient 把它同时用作 socket.connect 与 setSoTimeout 的超时，而这两处
        // 0 的语义都是「无限等待」—— 对着一个连不上的地址打一次这个接口，
        // 这个 Tomcat 工作线程就再也不回来了，而向导页每点一次「当场验」就打一次
        if (cfg.getTimeoutMs() <= 0) {
            items.add(new CheckItem("timeoutMs", "探针读取超时", false,
                    "必须大于 0，当前是 " + cfg.getTimeoutMs()
                            + "；0 在 socket 层的语义是「无限等待」，探针不可达时这次检查永远不会返回"));
        } else {
            checkInstances(cfg, items);
        }

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
        // 填成子目录是能跑通的 —— 正因为能跑通才危险，见 repoIsTopLevel
        if (!repoIsTopLevel(git, repo, items)) {
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
            dir(items, cfg, "classesDir", "Java 产物目录（classes-dir）", cfg.getClassesDir());
            sourceRoot(items, cfg, "javaSourceRoot", "Java 源码根", cfg.getJavaSourceRoot());
            classesMatchSource(items, cfg);
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
            dir(items, cfg, "cppObjectsDir", "C++ 对象目录（.gcno 所在）", cfg.getCppObjectsDir());
        }
        if (languages.contains(ProbeEndpoint.RUST)) {
            sourceRoot(items, cfg, "rustSourceRoot", "Rust 源码根", cfg.getRustSourceRoot());
            file(items, cfg, "rustBinary", "Rust 产物（行号信息在它里面）", cfg.getRustBinary());
        }
    }

    private void dir(List<CheckItem> items, ProjectConfig cfg, String name, String label, String path) {
        if (path == null || path.isBlank()) {
            items.add(new CheckItem(name, label, false, "没填"));
            return;
        }
        File f = new File(path);
        items.add(new CheckItem(name, label, f.isDirectory(),
                f.isDirectory() ? f.getAbsolutePath()
                        : "不是有效目录：" + f.getAbsolutePath() + repoRelativeHint(cfg, path, true)));
    }

    private void file(List<CheckItem> items, ProjectConfig cfg, String name, String label, String path) {
        if (path == null || path.isBlank()) {
            items.add(new CheckItem(name, label, false, "没填"));
            return;
        }
        File f = new File(path);
        items.add(new CheckItem(name, label, f.isFile(),
                f.isFile() ? f.getAbsolutePath()
                        : "文件不存在：" + f.getAbsolutePath() + repoRelativeHint(cfg, path, false)));
    }

    /**
     * <b>「你多半是把它当成相对仓库目录来填了」。</b>
     *
     * <p>产物类字段（{@code classes-dir} / {@code cpp-objects-dir} / {@code rust-binary}）
     * 的相对基准是<b>平台进程的工作目录</b>，而紧挨着的源码根字段相对 <b>repo-dir</b>。
     * 同一份配置里两种基准，种子配置本身就长这样：{@code classes-dir:
     * ../demo-service/target/classes} 配 {@code java-source-root: demo-service/src/main/java}。
     * 照着旁边那行的写法填 {@code demo-service/target/classes}，就被解析到平台自己目录底下去了。
     *
     * <p><b>基准不能改</b>：库里存着的正是「相对平台目录」的那种写法，改了会被重新
     * 解释成另一个目录 —— 既有项目当场采不到数，还不报错。所以这里只做一件事：
     * 换个基准试一下，试得到就把该粘的绝对路径直接给出来，人不必再去猜基准是谁。
     */
    private String repoRelativeHint(ProjectConfig cfg, String path, boolean wantDir) {
        String repo = cfg.getRepoDir();
        if (repo == null || repo.isBlank() || new File(path).isAbsolute()) {
            return "";
        }
        File candidate = new File(repo, path);
        if (wantDir ? !candidate.isDirectory() : !candidate.isFile()) {
            return "";
        }
        String shown;
        try {
            // repo-dir 常是 ..，直接 getAbsolutePath 会得到一串带 \..\ 的路径，
            // 粘进表单虽然能用，但没法一眼看出指向哪儿
            shown = candidate.getCanonicalPath();
        } catch (IOException e) {
            shown = candidate.getAbsolutePath();
        }
        return "。这个相对路径以平台安装目录为基准，不是上面填的仓库目录 —— "
                + "换成仓库目录去找是有的，你要填的多半是 " + shown;
    }

    /**
     * <b>repo-dir 必须是 git 仓库的根，不能是它下面的某个子目录。</b>
     *
     * <p>填子目录是能跑通的：git 在子目录里照常工作，{@code rev-parse HEAD} 也照常返回，
     * 所以上面那一项会通过。<b>正因为能跑通才危险</b> —— {@code diff --name-only}
     * 交出来的路径始终以仓库根为基准（实测：在 {@code demo-service} 里跑，git 说的是
     * {@code demo-service/src/main/java/...}），而覆盖率 IR 里的路径是
     * {@code src/main/java/...}，两边永远交不上。
     *
     * <p>结果不是报错，是<b>增量范围恒为空</b>：门禁每次都回「基线之后没有变更的
     * 可执行代码」并<b>放行</b>（分母为 0 时按设计放行），CI 从此挡不住任何东西，
     * 而它看上去一切正常。
     *
     * @return 是否可以继续往下检查
     */
    private boolean repoIsTopLevel(GitService git, File repo, List<CheckItem> items) {
        String name = "repoIsTopLevel";
        String label = "仓库目录是否为仓库根";
        try {
            File top = new File(git.topLevel()).getCanonicalFile();
            // 两边都取 canonical 再比、再 relativize。repoDir 常常是相对路径
            // （向导的默认值就是 ..），拿它去 relativize 一个绝对路径会抛
            // IllegalArgumentException，被下面的 catch 吞成一句「查不出仓库根」——
            // 那条写清楚了该怎么改的指引一个字都出不来
            File canonicalRepo = repo.getCanonicalFile();
            if (top.equals(canonicalRepo)) {
                return true;
            }
            String sub = top.toPath().relativize(canonicalRepo.toPath()).toString().replace('\\', '/');
            items.add(new CheckItem(name, label, false,
                    "填的是仓库里的一个子目录，不是仓库根。git 交出来的变更路径始终以仓库根为基准，"
                            + "与覆盖率里的路径对不上，增量范围会恒为空 —— 门禁每次都会以"
                            + "「没有变更的可执行代码」放行，看不出任何异样。"
                            + "请把仓库目录改成 " + top.getAbsolutePath()
                            + "，并把各语言的源码根改成相对它的路径（例如 "
                            + sub + "/src/main/java）"));
            return false;
        } catch (Exception e) {
            items.add(new CheckItem(name, label, false, "查不出仓库根：" + describe(e)));
            return false;
        }
    }

    /** 抽样多少个 class 去对源码。一个都对不上才判失败，所以不必全扫 */
    private static final int SAMPLE_CLASSES = 30;

    /**
     * 一个 class 可能来自哪些源码文件。
     *
     * <p>JaCoCo 认的是字节码，所以「Java 产物目录」里完全可能是 Kotlin / Groovy 编出来的。
     * 只按 {@code .java} 找的话，一个配置完全正确的 Kotlin 工程会被判成「产物与源码
     * 不是同一个工程」，创建按钮从此点不动 —— 这一项本来是防止误配的，反倒成了误伤。
     */
    private static final String[] SOURCE_EXT = { ".java", ".kt", ".groovy", ".scala" };

    /**
     * <b>产物目录里的类，在源码根下找不找得到。</b>
     *
     * <p>这一项补的是一个「目录存在，但根本不是这个工程」的洞。两个字段的相对基准不同 ——
     * {@code classes-dir} 相对<b>平台进程的工作目录</b>（见 {@code ProjectRuntime}
     * 里的 {@code new File(getClassesDir())}），{@code java-source-root} 相对
     * <b>repo-dir</b>。于是「填 {@code target/classes}、以为是被测工程的产物」
     * 会落到平台自己的 {@code target/classes} 上，而那个目录<b>确实存在</b>，
     * 逐项检查全过、项目照样建得出来。
     *
     * <p>之后的表现是：覆盖率 IR 里装的全是平台自己的类，源码路径按 repo-dir 拼出来
     * 一个不存在的文件，染色页满屏「源码读取失败」—— 而没有任何一处告诉人问题在配置上。
     * 这正是本类存在的理由，却恰好是它原先漏掉的一种。
     *
     * <p>判据是<b>一个都对不上</b>而不是「有对不上的」：产物里混着少量没有源码的类是常态
     * （生成代码、依赖被打进来、模块拆分），按后者判会把好配置拦下来。
     */
    private void classesMatchSource(List<CheckItem> items, ProjectConfig cfg) {
        String name = "classesMatchSource";
        String label = "产物与源码是否同一个工程";
        File classes = cfg.getClassesDir() == null ? null : new File(cfg.getClassesDir());
        // 空白串也要当成「没填」：new File(parent, "") 会 resolve 回 parent 本身，
        // 于是这一项会多报一条「多半指向了不同的工程」，
        // 把「你还没填源码根」说成「你填错了工程」，指错排查方向
        String srcRel = cfg.getJavaSourceRoot();
        File src = srcRel == null || srcRel.isBlank() ? null
                : new File(cfg.getRepoDir() == null ? "" : cfg.getRepoDir(), srcRel);
        if (classes == null || !classes.isDirectory() || src == null || !src.isDirectory()) {
            // 上面两项已经把「没填 / 目录不存在」点名了，这里再报一次只是噪音
            return;
        }
        List<String> sampled = new ArrayList<>();
        collectClasses(classes, sampled);
        if (sampled.isEmpty()) {
            items.add(new CheckItem(name, label, false,
                    "产物目录里一个 .class 都没有：" + classes.getAbsolutePath()
                            + "。被测服务多半还没编译，或这个目录指错了"));
            return;
        }
        String example = null;
        for (String rel : sampled) {
            // Kotlin 把 Foo.kt 里的顶层函数编成 FooKt.class —— 源码文件名里没有那个 Kt。
            // 不脱掉它，一个全是顶层函数的 Kotlin 工程会一个都对不上
            List<String> bases = rel.endsWith("Kt")
                    ? List.of(rel, rel.substring(0, rel.length() - 2)) : List.of(rel);
            for (String base : bases) {
                for (String ext : SOURCE_EXT) {
                    if (new File(src, base + ext).isFile()) {
                        items.add(new CheckItem(name, label, true,
                                "产物里的类能在源码根下找到，例如 " + base + ext));
                        return;
                    }
                }
            }
            if (example == null) {
                example = rel + SOURCE_EXT[0];
            }
        }
        // 纯文本：这段话会被前端按文本插值渲染（自检表 {{ it.detail }}、
        // 拦截提示走 ElMessage），带上 <b> 只会把标签本身显示出来
        items.add(new CheckItem(name, label, false,
                "抽查了 " + sampled.size() + " 个类，在源码根下一个都找不到，"
                        + "这两个路径多半指向了不同的工程。产物目录解析到 " + classes.getAbsolutePath()
                        + "（相对路径以平台安装目录为基准，不是上面填的仓库目录），源码根解析到 "
                        + src.getAbsolutePath() + "。例如产物里有 " + example + "，而源码根下没有它。"
                        + "建议 classes-dir 填绝对路径"));
    }

    /**
     * 收集若干个顶层类的<b>不带扩展名</b>的相对路径（{@code com/x/Foo.class} → {@code com/x/Foo}）。
     *
     * <p>跳过内部类（名字里带 {@code $}）：它们的源码文件名是外层类的名字，
     * 拿它去找必然找不到，会把判据变成噪音。
     *
     * <p><b>按层遍历而不是深度优先。</b>深度优先会一头扎进第一个包里取满 30 个 ——
     * 那个包恰好全是生成代码（{@code com/x/generated/Q*}）时，一个配置正确的工程
     * 会被判成配错。按层走天然铺开在各个顶层包上。
     */
    private void collectClasses(File root, List<String> out) {
        Deque<File> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && out.size() < SAMPLE_CLASSES) {
            File[] children = queue.poll().listFiles();
            if (children == null) {
                continue;
            }
            for (File f : children) {
                if (f.isDirectory()) {
                    queue.add(f);
                } else if (f.getName().endsWith(".class") && !f.getName().contains("$")
                        && out.size() < SAMPLE_CLASSES) {
                    String rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
                    out.add(rel.substring(0, rel.length() - ".class".length()));
                }
            }
        }
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

    /**
     * 探<b>一台</b>已配置的实例，当场回报它接上没有。
     *
     * <p>与 {@link #check} 的区别是范围：那个要碰仓库、碰产物目录、挨个连全部实例；
     * 这个只连一台，给「接入自检表上点某一行的『测这一台』」用 ——
     * 人刚改完启动参数重启了服务，等下一个 3 秒轮询周期才知道成没成，
     * 这段等待里最常见的动作是反复刷新页面。
     *
     * <p><b>endpoint 只用来在这个项目已配置的实例里选一条，绝不按字面地址去连。</b>
     * 接受任意 host:port 的话，这个接口就成了一台可以从平台发起的内网端口探测器 ——
     * 平台通常部署在能连到全部测试环境的位置，而探针端口本来就没有鉴权。
     * 比较前两边都过一遍 {@link ProbeEndpoint#parse}，否则配置里写
     * {@code localhost:6300}（省略语言前缀）时，请求里写全的那个会被判成「不在里面」。
     *
     * <p><b>只读</b>：取版本走的是 {@link #buildId}，Java 侧的 dump 传 {@code reset=false}，
     * 不会把被测实例的计数器清掉 —— 清掉的话，点一下「测这一台」就洗掉了别人正在看的覆盖数据。
     */
    public Map<String, Object> probeOne(ProjectConfig cfg, String endpoint) {
        // 与 check 里同一条理由：timeoutMs 会同时用作 socket.connect 与 setSoTimeout 的超时，
        // 而 0 在这两处的语义都是「无限等待」—— 对着一个连不上的地址打一次，
        // 这个 Tomcat 工作线程就再也不回来了。必须先判再连
        if (cfg.getTimeoutMs() <= 0) {
            throw ProjectOperationException.invalid(
                    "探针读取超时必须大于 0 毫秒，当前是 " + cfg.getTimeoutMs()
                            + "；0 在 socket 层的语义是「无限等待」，探针不可达时这次探测永远不会返回");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw ProjectOperationException.invalid("没有指定要探测哪一台实例");
        }
        ProbeEndpoint want;
        try {
            want = ProbeEndpoint.parse(endpoint);
        } catch (IllegalArgumentException e) {
            throw ProjectOperationException.invalid(e.getMessage());
        }
        ProbeEndpoint ep = instances(cfg).stream()
                .map(spec -> {
                    try {
                        return ProbeEndpoint.parse(spec);
                    } catch (IllegalArgumentException e) {
                        return null; // 配置里那条本来就是坏的，check 会点名，这里跳过
                    }
                })
                .filter(x -> x != null && x.toString().equals(want.toString()))
                .findFirst()
                .orElseThrow(() -> ProjectOperationException.invalid(
                        endpoint + " 不是项目 " + cfg.getId() + " 配置里的实例。"
                                + "这个接口只探已配置的实例，不接受任意地址"));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("endpoint", ep.toString());
        try {
            String id = buildId(ep, cfg);
            res.put("connected", true);
            // buildId 为 null 是一种独立的坏法：探针在跑，但没配 sessionid /
            // COVERAGE_BUILD_ID，增量口径不可用。它不是「没连上」，
            // 调用方要能把这两件事分开说 —— 混成一句「已连上」的话，
            // 人正是为了增量才来接入的，却看不出还差一步
            res.put("buildId", id);
            res.put("dirty", id != null && id.endsWith("-dirty"));
            res.put("error", null);
        } catch (Exception e) {
            res.put("connected", false);
            res.put("buildId", null);
            res.put("dirty", false);
            // 点名具体原因，不是一句「失败」—— 只说失败等于让人去翻日志
            res.put("error", describe(e));
        }
        return res;
    }

    private String buildId(ProbeEndpoint ep, ProjectConfig cfg) throws Exception {
        // 直接 new：这三个客户端已经不各持一个 HttpClient 了（共用连接池，见
        // SharedHttpClients），造一个就只是建个对象，不必再为此缓存 ——
        // 而那层缓存的键里带着来自请求体的 timeoutMs，取值空间无界，自己就是泄漏源
        if (ProbeEndpoint.GO.equals(ep.language())) {
            return new GoProbeClient(cfg).buildId(ep);
        }
        if (ProbeEndpoint.CPP.equals(ep.language())) {
            return new CppProbeClient(cfg).buildId(ep);
        }
        if (ProbeEndpoint.RUST.equals(ep.language())) {
            return new RustProbeClient(cfg).buildId(ep);
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
