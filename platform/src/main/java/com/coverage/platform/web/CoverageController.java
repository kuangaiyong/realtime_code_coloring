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

    /**
     * 各实例分别的覆盖情况，用于「哪台实例跑到了什么」的对比。
     *
     * 按需触发而非随轮询返回：它要对每个实例各调一次外部归一化工具，开销与实例数成正比。
     * 注意各实例的行状态不可相加当聚合用（见 CoverageService#perInstance），
     * 聚合值请取 /summary。
     */
    @GetMapping("/instances")
    public Map<String, Object> instances() {
        return service.perInstance();
    }

    /**
     * 跨构建覆盖率趋势：每个构建一个点，取该构建观测到的峰值。
     *
     * available=false 时必须把 error 一并显示出来 —— 回一张空图会被读成
     * 「这个项目一直没有覆盖」，而真实原因可能只是数据库没起。
     */
    @GetMapping("/trend")
    public Map<String, Object> trend(@RequestParam(defaultValue = "50") int limit) {
        return service.trend(Math.max(1, Math.min(limit, 500)));
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
