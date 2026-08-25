package com.rtcc.platform.web;

import com.rtcc.platform.service.ProjectRegistry;
import com.rtcc.platform.service.ProjectRuntime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 带项目参数的覆盖率与场景接口。
 *
 * <p>与 {@link CoverageController} / {@link ScenarioController} 是同一批能力，
 * 区别只在「打哪个项目」：那两个不带项目参数，落在默认项目上，是已经在用的对外契约
 * （CI 里挡合并的那句 {@code curl -f /api/coverage/gate} 就打在上面），因此原样保留。
 *
 * <p>方法体都是一行转发，没有抽公共基类：Spring 的路径与参数绑定全在注解上，
 * 抽出去之后两套路径的注解仍要各写一遍，省不掉几行，却多一层要跳的间接。
 */
@RestController
@RequestMapping("/api/projects/{project}")
public class ProjectCoverageController {

    private final ProjectRegistry registry;

    public ProjectCoverageController(ProjectRegistry registry) {
        this.registry = registry;
    }

    private ProjectRuntime rt(String project) {
        return registry.get(project);
    }

    @GetMapping("/coverage/summary")
    public Map<String, Object> summary(@PathVariable String project,
                                       @RequestParam(defaultValue = ProjectRuntime.MODE_FULL) String mode,
                                       @RequestParam(required = false) String baseline,
                                       @RequestParam(required = false) String scenarioId) {
        return rt(project).summary(mode, baseline, scenarioId);
    }

    @GetMapping("/coverage/instances")
    public Map<String, Object> instances(@PathVariable String project) {
        return rt(project).perInstance();
    }

    @GetMapping("/coverage/trend")
    public Map<String, Object> trend(@PathVariable String project,
                                     @RequestParam(defaultValue = "50") int limit) {
        return rt(project).trend(Math.max(1, Math.min(limit, 500)));
    }

    @GetMapping("/coverage/gate")
    public Map<String, Object> gate(@PathVariable String project,
                                    @RequestParam(defaultValue = ProjectRuntime.MODE_INCREMENTAL) String mode,
                                    @RequestParam(required = false) String baseline) {
        return rt(project).gate(mode, baseline);
    }

    @GetMapping("/coverage/file")
    public Map<String, Object> file(@PathVariable String project,
                                    @RequestParam String path,
                                    @RequestParam(defaultValue = ProjectRuntime.MODE_FULL) String mode,
                                    @RequestParam(required = false) String baseline,
                                    @RequestParam(required = false) String scenarioId) {
        return rt(project).fileDetail(path, mode, baseline, scenarioId);
    }

    @PostMapping("/coverage/reset")
    public Map<String, Object> reset(@PathVariable String project) throws Exception {
        Map<String, Object> res = new LinkedHashMap<>();
        rt(project).reset();
        res.put("ok", true);
        return res;
    }

    @PostMapping("/scenario/start")
    public Map<String, Object> startScenario(@PathVariable String project,
                                             @RequestParam String scenarioId) throws Exception {
        return rt(project).startScenario(scenarioId);
    }

    @PostMapping("/scenario/stop")
    public Map<String, Object> stopScenario(@PathVariable String project) {
        return rt(project).stopScenario();
    }

    @GetMapping("/scenario")
    public Map<String, Object> scenarios(@PathVariable String project) {
        return rt(project).listScenarios();
    }
}
