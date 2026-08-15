package com.coverage.platform.web;

import com.coverage.platform.service.CoverageService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 场景级归因：把「哪些代码被这个测试场景跑到了」这个问题变得可回答。
 *
 * 归档下来的场景可以直接用 /api/coverage/summary?scenarioId=xxx 查看，
 * 与全量/增量口径正交组合，无需另做一套渲染。
 */
@RestController
@RequestMapping("/api/scenario")
public class ScenarioController {

    private final CoverageService service;

    public ScenarioController(CoverageService service) {
        this.service = service;
    }

    /** 开始场景：清零计数器，此后执行到的代码归这个场景 */
    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam String scenarioId) throws Exception {
        return service.startScenario(scenarioId);
    }

    /** 结束场景：定格这段窗口内的覆盖并归档 */
    @PostMapping("/stop")
    public Map<String, Object> stop() {
        return service.stopScenario();
    }

    /** 已归档场景列表 + 当前活跃场景 */
    @GetMapping
    public Map<String, Object> list() {
        return service.listScenarios();
    }
}
