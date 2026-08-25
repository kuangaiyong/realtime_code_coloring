package com.rtcc.platform.service;

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
import com.rtcc.platform.history.CoverageHistory;
import org.springframework.stereotype.Component;

/**
 * 按项目配置造出一个 {@link ProjectRuntime}。
 *
 * <p>采集与归一化组件之所以要每个项目各造一份，是因为它们读的是项目级配置 ——
 * 仓库路径、产物目录、源码根。做成共享单例的话，两个项目的归一化会互相用错对方的路径，
 * 而错误的表现是「某些文件解不出行号」这类看不出根因的静默偏差。
 *
 * <p>不依赖项目配置的三个组件（{@link ProbeClient}、{@link CoverageAnalyzer}、
 * {@link CoverageHistory}）仍是共享单例，没有必要重复造。
 */
@Component
public class ProjectRuntimeFactory {

    private final ProbeClient probeClient;
    private final CoverageAnalyzer analyzer;
    /** 工具链可执行文件的路径：跟着部署机器走，与项目无关，因此所有项目共用这一份 */
    private final CoverageProperties platform;
    private final CoveragePublisher publisher;
    private final CoverageHistory history;

    public ProjectRuntimeFactory(ProbeClient probeClient, CoverageAnalyzer analyzer,
                                 CoverageProperties platform, CoveragePublisher publisher,
                                 CoverageHistory history) {
        this.probeClient = probeClient;
        this.analyzer = analyzer;
        this.platform = platform;
        this.publisher = publisher;
        this.history = history;
    }

    public ProjectRuntime create(ProjectConfig cfg) {
        return new ProjectRuntime(probeClient, analyzer,
                new GoProbeClient(cfg), new GoCoverageAnalyzer(cfg, platform),
                new CppProbeClient(cfg), new CppCoverageAnalyzer(cfg, platform),
                new RustProbeClient(cfg), new RustCoverageAnalyzer(cfg, platform),
                new GitService(cfg), cfg, publisher, history);
    }
}
