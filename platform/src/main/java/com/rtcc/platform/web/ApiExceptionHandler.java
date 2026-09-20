package com.rtcc.platform.web;

import com.rtcc.platform.artifact.ArtifactOperationException;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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

    /**
     * 产物的上传 / 查询 / 删除做不了。状态码由异常自己带，与
     * {@link #onProjectOperation} 同一套 ——「你传的包或参数有问题」（400）与
     * 「平台这边存不下来」（5xx）处置完全不同，混成一个码 CI 就只能猜。
     */
    @ExceptionHandler(ArtifactOperationException.class)
    public ResponseEntity<Map<String, Object>> onArtifactOperation(ArtifactOperationException e) {
        return ResponseEntity.status(e.status()).body(error(e));
    }

    /**
     * 产物包超过 multipart 上限。<b>不接管的话它落到默认错误页 = 500</b>，
     * 上传方会以为平台内部故障而去查平台，真正该做的却是把包拆小或调大上限。
     *
     * <p><b>不要在这里印 {@code e.getMaxUploadSize()}</b>：Tomcat 抛这个异常时并不带上限值，
     * 实测拿到的是 {@code -1}，印出来就是「当前 -1 字节」这种把人带偏的胡话。
     * 一个错的数字比没有数字更糟，所以只点名该去看哪个配置项。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> onTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error("产物包超过平台的上传上限（由 spring.servlet.multipart.max-file-size 决定）："
                        + "请拆小产物，或调大这个上限"));
    }

    private Map<String, Object> error(Exception e) {
        return error(e.getMessage());
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", false);
        res.put("error", message);
        return res;
    }
}
