package com.rtcc.platform.service;

import com.rtcc.platform.config.ProjectConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台上所有被测项目的注册表：持有各项目的 {@link ProjectRuntime}，并驱动它们采集。
 *
 * <p>目前只有一个项目，配置来自 application.yml 的种子（见
 * {@code PlatformApplication#defaultProjectConfig}）。项目的增删改与配置落库是
 * 下一步的事，本类先把「谁来持有运行时、谁来调度采集」这两件事从服务里分出来。
 */
@Service
public class ProjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistry.class);

    private final Map<String, ProjectRuntime> runtimes = new ConcurrentHashMap<>();
    /** 旧版 API（/api/coverage/*、/api/scenario/*）不带项目参数，落到这个项目上 */
    private final String defaultId;

    public ProjectRegistry(ProjectConfig seed, ProjectRuntimeFactory factory) {
        this.defaultId = seed.getId();
        runtimes.put(seed.getId(), factory.create(seed));
        log.info("已装载项目 {}（{}），被测实例 {} 个",
                seed.getId(), seed.getName(), seed.getInstances().size());
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

    public List<ProjectConfig> configs() {
        return runtimes.values().stream().map(ProjectRuntime::config).toList();
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
