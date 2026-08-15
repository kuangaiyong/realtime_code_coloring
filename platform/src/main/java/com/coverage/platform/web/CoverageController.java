package com.coverage.platform.web;

import com.coverage.platform.service.CoverageService;
import com.coverage.platform.service.IncrementalUnavailableException;
import org.springframework.http.HttpStatus;
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

    /** 覆盖率总览：探针状态 + 各文件覆盖摘要。mode=incremental 时只统计基线之后变动的行 */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = CoverageService.MODE_FULL) String mode,
                                       @RequestParam(required = false) String baseline) {
        return service.summary(mode, baseline);
    }

    /** 单文件源码与逐行染色状态 */
    @GetMapping("/file")
    public Map<String, Object> file(@RequestParam String path,
                                    @RequestParam(defaultValue = CoverageService.MODE_FULL) String mode,
                                    @RequestParam(required = false) String baseline) {
        return service.fileDetail(path, mode, baseline);
    }

    /**
     * 增量口径算不出可信结果时返回 409 而不是 200+空数据：
     * 前者会让调用方停下来看错误，后者会被当成「这次改动一行都没覆盖」。
     */
    @ExceptionHandler(IncrementalUnavailableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> onIncrementalUnavailable(IncrementalUnavailableException e) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", false);
        res.put("error", e.getMessage());
        return res;
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
