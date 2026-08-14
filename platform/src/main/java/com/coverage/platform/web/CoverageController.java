package com.coverage.platform.web;

import com.coverage.platform.service.CoverageService;
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

    /** 覆盖率总览：探针状态 + 各文件覆盖摘要 */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }

    /** 单文件源码与逐行染色状态 */
    @GetMapping("/file")
    public Map<String, Object> file(@RequestParam String path) {
        return service.fileDetail(path);
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
        } catch (Exception e) {
            res.put("ok", false);
            res.put("error", e.getMessage());
        }
        return res;
    }
}
