package com.rtcc.platform.service;

import com.rtcc.platform.collector.ProbeEndpoint;
import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.config.ProjectStore;
import com.rtcc.platform.history.CollectEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 平台上所有被测项目的注册表：持有各项目的 {@link ProjectRuntime}，并驱动它们采集。
 *
 * <p>配置来自 {@link ProjectStore}；库里没有任何项目时，用 application.yml 的那份
 * 种一个出来（见 {@code PlatformApplication#defaultProjectConfig}）。
 *
 * <p><b>配置变更走「造一个新的 ProjectRuntime 整体顶替旧的」，不原地改字段。</b>
 * 与 {@code Snapshot} 必须整体替换是同一个道理：半新半旧的配置组合
 * （新的 classes-dir 配旧的 source-root）会算出错位的行号，却照样返回 200，
 * 界面上完全看不出这份报告是错的。
 */
@Service
public class ProjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistry.class);

    /**
     * 项目标识的取值范围。它同时出现在 URL 路径、WebSocket 查询串和历史表的分区键上，
     * 收紧成小写字母数字加横杠下划线，省掉三处各自的转义麻烦
     */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final Map<String, ProjectRuntime> runtimes = new ConcurrentHashMap<>();
    /** 各项目当前生效的配置。与 runtimes 一起改，改动都在 writeLock 里 */
    private final Map<String, ProjectConfig> configs = new ConcurrentHashMap<>();
    private final ProjectStore store;
    private final ProjectRuntimeFactory factory;
    private final CollectEvents events;
    /** 旧版 API（/api/coverage/*、/api/scenario/*）不带项目参数，落到这个项目上 */
    private final String defaultId;
    /**
     * 增删改互斥。都是人工触发的低频操作，用一把锁换取显而易见的正确性 ——
     * 两个请求同时建同名项目时，「先查不存在、再写入」之间的空档足够让两次都通过检查
     */
    private final Object writeLock = new Object();

    public ProjectRegistry(ProjectConfig seed, ProjectStore store, ProjectRuntimeFactory factory,
                           CollectEvents events) {
        this.store = store;
        this.factory = factory;
        this.events = events;
        this.defaultId = seed.getId();
        for (ProjectConfig cfg : store.loadAll(seed)) {
            // 没有 id 的配置直接跳过。不跳的话 ConcurrentHashMap 会因 null key 抛 NPE，
            // Spring 上下文起不来 —— 一个项目的配置有问题不该让整个平台开不了机，
            // 这与 ProjectStore 里「解析失败就点名跳过」是同一条原则
            if (cfg.getId() == null || cfg.getId().isBlank()) {
                log.error("有一份项目配置没有 id，已跳过（name={}）", cfg.getName());
                continue;
            }
            // 造运行时同样可能失败（如 timeout-ms 被改成 0，HttpClient 会拒绝这个时长）。
            // 不兜住的话异常冲出构造函数 → Spring 上下文起不来 → 整个平台连同默认项目
            // 和 CI 门禁一起开不了机，而日志里只有一句异常，看不出是哪条项目配置的锅
            try {
                runtimes.put(cfg.getId(), factory.create(cfg));
                configs.put(cfg.getId(), cfg);
                log.info("已装载项目 {}（{}），被测实例 {} 个",
                        cfg.getId(), cfg.getName(),
                        cfg.getInstances() == null ? 0 : cfg.getInstances().size());
            } catch (Exception e) {
                // 配置仍要放进 configs：不放的话这个项目在 API 眼里根本不存在，
                // 而库里那一行还在 —— 改不了也删不掉，每次启动刷同一条 ERROR。
                // 放进去之后 list 能看见它（运行时缺席），PUT 能把配置改对、DELETE 能删掉它
                configs.put(cfg.getId(), cfg);
                log.error("项目 {} 装载失败，采集不会进行；配置仍可在页面上修改或删除：{}",
                        cfg.getId(), e.toString());
            }
        }
        // 不在这里退到「第一个装载成功的项目」：CI 的门禁打的就是不带项目参数的旧地址，
        // 悄悄换一个项目去判，等于拿另一份代码的覆盖率决定这次能不能合并。
        // 宁可让旧地址明确报错，也不给一个静默错误的结论
        if (!runtimes.containsKey(defaultId)) {
            log.error("默认项目 {} 没有装载上，不带项目参数的旧接口（/api/coverage/*、"
                    + "/api/scenario/*、/ws/coverage）将全部报错。已装载的项目：{}",
                    defaultId, runtimes.keySet());
        }
    }

    /** 不指定项目时用的那一个 */
    public ProjectRuntime current() {
        return get(defaultId);
    }

    /** 拿不到就返回 null。列表接口用它 —— 并发删掉一个项目不该让整张列表都列不出来 */
    public ProjectRuntime find(String id) {
        return runtimes.get(id);
    }

    public ProjectRuntime get(String id) {
        ProjectRuntime rt = runtimes.get(id);
        if (rt == null) {
            throw ProjectOperationException.notFound("没有这个项目：" + id);
        }
        return rt;
    }

    /** 不带项目参数的旧接口落在哪个项目上 */
    public String defaultId() {
        return defaultId;
    }

    /** 全部项目的配置，按 id 排序，使列表页的顺序稳定 */
    public List<ProjectConfig> configs() {
        return configs.values().stream()
                .sorted(Comparator.comparing(ProjectConfig::getId))
                .toList();
    }

    public ProjectConfig config(String id) {
        ProjectConfig cfg = configs.get(id);
        if (cfg == null) {
            throw ProjectOperationException.notFound("没有这个项目：" + id);
        }
        return cfg;
    }

    /** 新建一个项目。配置当场生效，不必重启平台 */
    public ProjectConfig create(ProjectConfig cfg) {
        validate(cfg);
        synchronized (writeLock) {
            if (runtimes.containsKey(cfg.getId())) {
                throw ProjectOperationException.conflict("项目 " + cfg.getId() + " 已存在");
            }
            // 顺序要紧：先造出运行时，造得出来才写库。反过来的话，一份能通过校验
            // 却造不出运行时的配置会留在库里，此后每次启动都在同一处失败 —— 平台变砖
            ProjectRuntime rt = build(cfg);
            persist(cfg);
            runtimes.put(cfg.getId(), rt);
            configs.put(cfg.getId(), cfg);
        }
        log.info("已新建项目 {}（{}），被测实例 {} 个",
                cfg.getId(), cfg.getName(), cfg.getInstances().size());
        return cfg;
    }

    /**
     * 改配置，当场生效。
     *
     * <p>做法是<b>用新配置造一个新的 ProjectRuntime 整体顶替</b>，而不是原地改字段：
     * 采集正跑到一半时字段被换掉，会拿新的 classes-dir 去解旧的 dump，
     * 算出的行号是错位的，而结果照样 200 返回。
     *
     * <p>已归档的场景交接给新实例；<b>有场景进行中则拒绝保存</b> ——
     * 那个场景的计数器窗口是在旧配置下开的，跨配置定格出来的归因没有意义。
     * 这与「场景进行中拒绝清零」是同一条原则。
     */
    public ProjectConfig update(String id, ProjectConfig cfg) {
        cfg.setId(id);
        validate(cfg);
        synchronized (writeLock) {
            // 认 configs 而不是 runtimes：装载失败的项目没有运行时，但配置还在，
            // 而「把配置改对」正是它唯一的出路
            if (!configs.containsKey(id)) {
                throw ProjectOperationException.notFound("没有这个项目：" + id);
            }
            ProjectRuntime old = runtimes.get(id);
            // 先把新的造出来：造不出来就整个作罢，旧实例原样留着继续服务
            ProjectRuntime fresh = build(cfg);
            // 在场景锁内原子地「确认没有进行中的场景」+「作废旧实例」。
            // 分两步做会有空档，正好在空档里开始的场景会挂在被丢弃的旧实例上
            try {
                // 装载失败的项目没有旧运行时可作废，也就没有场景冲突可言
                if (old != null) {
                    old.retireIfIdle();
                }
            } catch (ScenarioConflictException e) {
                throw ProjectOperationException.conflict(e.getMessage());
            }
            try {
                persist(cfg);
            } catch (RuntimeException e) {
                // 写库失败时旧实例还留在注册表里继续对外服务，必须把作废撤回来。
                // 不撤的话这个项目从此不采集、不推送，页面上只是「数字不动了」，
                // 而清零 / 开场景全部回 409「配置刚刚更新，请重试」——
                // 真实原因是数据库挂了，那句提示把人引向完全相反的方向
                if (old != null) {
                    old.unretire();
                }
                throw e;
            }
            if (old != null) {
                fresh.adoptScenariosFrom(old);
            }
            runtimes.put(id, fresh);
            configs.put(id, cfg);
        }
        log.info("项目 {}（{}）配置已更新并立即生效，被测实例 {} 个",
                cfg.getId(), cfg.getName(), cfg.getInstances().size());
        return cfg;
    }

    /** 删除一个项目 */
    public void delete(String id) {
        synchronized (writeLock) {
            // 同 update：装载失败的项目只在 configs 里，也必须删得掉
            if (!configs.containsKey(id)) {
                throw ProjectOperationException.notFound("没有这个项目：" + id);
            }
            // 默认项目是 /api/coverage/*、/api/scenario/*、/ws/coverage 落脚的地方，
            // CI 里挡合并的那句 curl 打的就是它。删掉之后那些地址会全部报错，
            // 而调用方看到的只是「平台挂了」，根本联想不到是有人删了项目
            if (defaultId.equals(id)) {
                throw ProjectOperationException.conflict(
                        "项目 " + id + " 是默认项目，不带项目参数的旧接口都落在它上面，不能删除");
            }
            try {
                store.delete(id);
            } catch (Exception e) {
                // 与 persist 同一条原则：「平台的依赖挂了」不能报成 500，
                // 500 会被读成平台自己有 bug，排查方向完全不同
                throw ProjectOperationException.unavailable(
                        "项目没能从数据库删除，本次删除未生效：" + e.getMessage());
            }
            // 与 update 同一条原则：正在跑的那一轮采集完成后，仍会顶着这个已删除的
            // 项目 id 写趋势表、往订阅该 id 的会话推数据。从注册表里摘掉拦不住它，
            // 因为调度那一轮拿的是摘掉之前取到的实例引用
            ProjectRuntime rt = runtimes.get(id);
            if (rt != null) {
                rt.retire();
            }
            runtimes.remove(id);
            configs.remove(id);
            // 项目都没了，它的采集事件没有留着的理由；删不掉也不该让删项目失败
            events.forget(id);
        }
        log.info("已删除项目 {}", id);
    }

    /**
     * 按配置造一个运行时。校验挡不住的错（如某个字段的取值被底层库拒绝）在这里暴露，
     * 归入「你填错了」而不是 500 —— 这些错都是配置本身的问题，改一下就能过
     */
    private ProjectRuntime build(ProjectConfig cfg) {
        try {
            return factory.create(cfg);
        } catch (Exception e) {
            throw ProjectOperationException.invalid("这份配置建不出可用的采集器：" + e);
        }
    }

    /** 写库失败与「你填错了」必须分开：一个该找人看平台，一个该改了再存 */
    private void persist(ProjectConfig cfg) {
        try {
            store.save(cfg);
        } catch (Exception e) {
            throw ProjectOperationException.unavailable(
                    "配置没能存进数据库，本次修改未生效：" + e.getMessage());
        }
    }

    /**
     * 存进去之前先把明摆着不能用的配置挡掉。
     *
     * <p>只校验「不看就一定错」的那几项。路径存不存在、探针连不连得上要碰真实环境，
     * 归 {@code /api/projects/check}，不放在保存这条路径上：产物还没构建时路径本来就
     * 不存在，不该因此拦着人把配置先存下来。
     */
    private void validate(ProjectConfig cfg) {
        if (cfg.getId() == null || !ID_PATTERN.matcher(cfg.getId()).matches()) {
            throw ProjectOperationException.invalid(
                    "项目标识只能是小写字母、数字、下划线、横杠，且以字母或数字开头，最长 64 位，实际为：" + cfg.getId());
        }
        if (cfg.getName() == null || cfg.getName().isBlank()) {
            throw ProjectOperationException.invalid("项目名不能为空");
        }
        if (cfg.getInstances() == null || cfg.getInstances().isEmpty()) {
            throw ProjectOperationException.invalid("至少要配一个被测实例，否则这个项目采不到任何数据");
        }
        for (String spec : cfg.getInstances()) {
            try {
                ProbeEndpoint.parse(spec);
            } catch (IllegalArgumentException e) {
                throw ProjectOperationException.invalid(e.getMessage());
            }
        }
        if (cfg.getRepoDir() == null || cfg.getRepoDir().isBlank()) {
            throw ProjectOperationException.invalid("代码仓库目录不能为空");
        }
        // 0 或负数会被 HttpClient 直接拒绝（Invalid duration），而那是在造采集器的时候
        // 才抛出来的。挡在这里，错误信息才说得清是哪个字段填错了
        if (cfg.getTimeoutMs() <= 0) {
            throw ProjectOperationException.invalid(
                    "探针读取超时必须大于 0 毫秒，实际为：" + cfg.getTimeoutMs());
        }
        // 请求体里显式写 "gate": null 时 Jackson 会把默认值抹掉，之后门禁接口必 NPE ——
        // CI 拿到的是 500，而按本项目的约定「判不了」只应该是 409
        if (cfg.getGate() == null) {
            cfg.setGate(new ProjectConfig.Gate());
        }
        for (double th : new double[]{cfg.getGate().getIncrementalThreshold(),
                cfg.getGate().getOverallThreshold()}) {
            if (th < 0 || th > 100) {
                throw ProjectOperationException.invalid("门禁阈值只能是 0 到 100，实际为：" + th);
            }
        }
    }

    /**
     * 驱动每个项目采集一轮。
     *
     * <p>逐个项目单独兜异常：一个项目的意外失败若让循环中断，排在它后面的项目这一轮
     * 就整个没采到，而界面上只会表现为「覆盖率不动了」，看不出是被别的项目连累的。
     *
     * <p>眼下是串行，与单项目时期的行为完全一致。项目多起来之后要换成线程池 ——
     * gcov / llvm-cov / covdata 都是外部进程，一个慢项目会拖住排在后面的所有项目。
     */
    @Scheduled(fixedDelayString = "${coverage.interval-ms:3000}")
    public void collectAll() {
        for (Map.Entry<String, ProjectRuntime> e : runtimes.entrySet()) {
            try {
                e.getValue().collect();
            } catch (Exception ex) {
                log.error("项目 {} 采集失败：{}", e.getKey(), ex.toString());
            }
        }
    }
}
