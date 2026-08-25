package com.rtcc.platform.service;

import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.config.ProjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台上所有被测项目的注册表：持有各项目的 {@link ProjectRuntime}，并驱动它们采集。
 *
 * <p>配置来自 {@link ProjectStore}；库里没有任何项目时，用 application.yml 的那份
 * 种一个出来（见 {@code PlatformApplication#defaultProjectConfig}）。项目的增删改
 * 是下一步的事，本类先把「谁来持有运行时、谁来调度采集」这两件事从服务里分出来。
 */
@Service
public class ProjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistry.class);

    private final Map<String, ProjectRuntime> runtimes = new ConcurrentHashMap<>();
    /** 旧版 API（/api/coverage/*、/api/scenario/*）不带项目参数，落到这个项目上 */
    private final String defaultId;

    public ProjectRegistry(ProjectConfig seed, ProjectStore store, ProjectRuntimeFactory factory) {
        this.defaultId = seed.getId();
        for (ProjectConfig cfg : store.loadAll(seed)) {
            // 没有 id 的配置直接跳过。不跳的话 ConcurrentHashMap 会因 null key 抛 NPE，
            // Spring 上下文起不来 —— 一个项目的配置有问题不该让整个平台开不了机，
            // 这与 ProjectStore 里「解析失败就点名跳过」是同一条原则
            if (cfg.getId() == null || cfg.getId().isBlank()) {
                log.error("有一份项目配置没有 id，已跳过（name={}）", cfg.getName());
                continue;
            }
            runtimes.put(cfg.getId(), factory.create(cfg));
            log.info("已装载项目 {}（{}），被测实例 {} 个",
                    cfg.getId(), cfg.getName(), cfg.getInstances().size());
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

    public ProjectRuntime get(String id) {
        ProjectRuntime rt = runtimes.get(id);
        if (rt == null) {
            throw new IllegalArgumentException("没有这个项目：" + id);
        }
        return rt;
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
