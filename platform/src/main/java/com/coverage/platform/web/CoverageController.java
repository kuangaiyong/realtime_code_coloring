package com.coverage.platform.web;

import com.coverage.platform.service.CoverageService;
import com.coverage.platform.service.ScenarioConflictException;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/coverage")
public class CoverageController {

    private final CoverageService service;

    public CoverageController(CoverageService service) {
        this.service = service;
    }

    /**
     * 覆盖率总览：探针状态 + 各文件覆盖摘要。
     * mode=incremental 只统计基线之后变动的行；scenarioId 指定时看该场景的独占覆盖。
     */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = CoverageService.MODE_FULL) String mode,
                                       @RequestParam(required = false) String baseline,
                                       @RequestParam(required = false) String scenarioId) {
        return service.summary(mode, baseline, scenarioId);
    }

    /** 单文件源码与逐行染色状态 */
    @GetMapping("/file")
    public Map<String, Object> file(@RequestParam String path,
                                    @RequestParam(defaultValue = CoverageService.MODE_FULL) String mode,
                                    @RequestParam(required = false) String baseline,
                                    @RequestParam(required = false) String scenarioId) {
        return service.fileDetail(path, mode, baseline, scenarioId);
    }

    /** 立即采集一次，不等待轮询周期 */
    @PostMapping("/collect")
    public Map<String, Object> collect() {
        service.collect();
        return service.summary();
    }

    /** 清零计数器：之后采到的即为「这一轮测试新覆盖的代码」 */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        Map<String, Object> res = new LinkedHashMap<>();
        try {
            service.reset();
            res.put("ok", true);
        } catch (ScenarioConflictException e) {
            // 场景进行中被清零，归因数据就废了。这种错误必须走 409 让调用方停下来，
            // 塞进 200 的响应体里，脚本里一句 http(...) 不看 body 就漏过去了
            throw e;
        } catch (Exception e) {
            res.put("ok", false);
            res.put("error", e.getMessage());
        }
        return res;
    }
}
