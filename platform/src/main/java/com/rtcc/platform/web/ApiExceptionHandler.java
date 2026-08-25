package com.rtcc.platform.web;

import com.rtcc.platform.service.GateUndecidableException;
import com.rtcc.platform.service.IncrementalUnavailableException;
import com.rtcc.platform.service.ProjectOperationException;
import com.rtcc.platform.service.ScenarioConflictException;
import com.rtcc.platform.service.ScenarioNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /** 门禁的判定前提被破坏（探针数据不完整、实例间版本不一致等） */
    @ExceptionHandler(GateUndecidableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> onGateUndecidable(GateUndecidableException e) {
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

    /**
     * 项目的增删改做不了。状态码由异常自己带 ——「你填错了」（400）、「现在不能做」（409）、
     * 「平台自己的依赖挂了」（503）三件事页面上的处置完全不同，混成一个码就得去猜。
     */
    @ExceptionHandler(ProjectOperationException.class)
    public ResponseEntity<Map<String, Object>> onProjectOperation(ProjectOperationException e) {
        return ResponseEntity.status(e.status()).body(error(e));
    }

    private Map<String, Object> error(Exception e) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", false);
        res.put("error", e.getMessage());
        return res;
    }
}
