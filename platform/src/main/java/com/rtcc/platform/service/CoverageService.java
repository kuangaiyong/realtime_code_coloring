package com.rtcc.platform.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 不带项目参数的那套 API 所对应的服务：把调用转给默认项目。
 *
 * <p>为什么留着这一层而不是让 Controller 直接用 {@link ProjectRegistry}：
 * {@code /api/coverage/*} 与 {@code /api/scenario/*} 是已经在用的对外契约 ——
 * CI 里挡合并的那句 {@code curl -f /api/coverage/gate} 就打在上面。多项目落地后
 * 新接口会带项目参数，而这一层继续代表「默认项目」，使旧地址原样可用，
 * 不必要求所有调用方同时改。
 */
@Service
public class CoverageService {

    public static final String MODE_FULL = ProjectRuntime.MODE_FULL;
    public static final String MODE_INCREMENTAL = ProjectRuntime.MODE_INCREMENTAL;

    private final ProjectRegistry registry;

    public CoverageService(ProjectRegistry registry) {
        this.registry = registry;
    }

    private ProjectRuntime rt() {
        return registry.current();
    }

    public void collect() {
        rt().collect();
    }

    public Map<String, Object> summary() {
        return rt().summary();
    }

    public Map<String, Object> summary(String mode, String baseline, String scenarioId) {
        return rt().summary(mode, baseline, scenarioId);
    }

    public Map<String, Object> perInstance() {
        return rt().perInstance();
    }

    public Map<String, Object> trend(int limit) {
        return rt().trend(limit);
    }

    public Map<String, Object> gate(String mode, String baseline) {
        return rt().gate(mode, baseline);
    }

    public Map<String, Object> fileDetail(String path, String mode, String baseline, String scenarioId) {
        return rt().fileDetail(path, mode, baseline, scenarioId);
    }

    public void reset() throws Exception {
        rt().reset();
    }

    public Map<String, Object> startScenario(String id) throws Exception {
        return rt().startScenario(id);
    }

    public Map<String, Object> stopScenario() {
        return rt().stopScenario();
    }

    public Map<String, Object> listScenarios() {
        return rt().listScenarios();
    }
}
