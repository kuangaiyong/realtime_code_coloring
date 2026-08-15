package com.coverage.platform.web;

import com.coverage.platform.service.IncrementalUnavailableException;
import com.coverage.platform.service.ScenarioConflictException;
import com.coverage.platform.service.ScenarioNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 算不出可信结果时一律用错误状态码回绝，不返回 200+空数据。
 *
 * 覆盖率报告的坏处在于：一份错的报告和一份对的报告长得一模一样。
 * 空数据会被读成「这次一行都没覆盖」，错位的行号会被读成「这几行没测到」，
 * 用户据此补测，错得毫无察觉。所以宁可让调用方拿到 4xx 停下来。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 增量口径的行号对不齐（产物与源码版本不一致等） */
    @ExceptionHandler(IncrementalUnavailableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> onIncrementalUnavailable(IncrementalUnavailableException e) {
        return error(e);
    }

    /** 场景归因的前提被破坏（并发场景、进行中清零等） */
    @ExceptionHandler(ScenarioConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> onScenarioConflict(ScenarioConflictException e) {
        return error(e);
    }

    @ExceptionHandler(ScenarioNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> onScenarioNotFound(ScenarioNotFoundException e) {
        return error(e);
    }

    private Map<String, Object> error(Exception e) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", false);
        res.put("error", e.getMessage());
        return res;
    }
}
