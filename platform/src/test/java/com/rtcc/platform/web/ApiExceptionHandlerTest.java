package com.rtcc.platform.web;

import com.rtcc.platform.artifact.ArtifactOperationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 错误体必须带着中文原因回到调用方。
 *
 * <p>起因：上传接口原先用 {@code ResponseStatusException}，它的 reason 被 Spring 吞掉 ——
 * {@code server.error.include-message} 默认 {@code never}，实测 400 的体里只有
 * {@code timestamp/status/error/path}，CI 拿到的是一个光秃秃的 400，
 * 而 Controller 里那句「拒绝脏构建的产物：……」一个字都到不了。
 *
 * <p><b>不能靠把那个开关打成 always 来解决</b>：它是全局的，会让平台所有接口的
 * 内部异常信息（数据库异常、内部路径）一并外泄，是安全倒退。
 * 正确做法是归队到平台既有风格 —— 自定义异常 → {@code @ExceptionHandler}
 * → {@code {"ok":false,"error":"<中文原因>"}}，与 {@code ProjectOperationException} 同一套。
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void 产物操作失败带着中文原因回给调用方() {
        ResponseEntity<Map<String, Object>> res = handler.onArtifactOperation(
                ArtifactOperationException.invalid("拒绝脏构建的产物：工作树脏时同一个 commit 可以对应多份不同的产物"));

        assertEquals(400, res.getStatusCode().value());
        assertEquals(false, res.getBody().get("ok"));
        assertTrue(res.getBody().get("error").toString().contains("脏构建"),
                "错误体里没有中文原因：" + res.getBody());
    }

    /** 平台自己存不下来与「你传的包有问题」必须分开：前者回 5xx，去查平台；后者回 400，去查包 */
    @Test
    void 平台自己失败时回五百而不是赖调用方() {
        ResponseEntity<Map<String, Object>> res =
                handler.onArtifactOperation(ArtifactOperationException.failed("产物目录写不进去"));

        assertEquals(500, res.getStatusCode().value());
        assertTrue(res.getBody().get("error").toString().contains("写不进去"),
                String.valueOf(res.getBody()));
    }

    /**
     * 超过 multipart 上限。不接管的话 {@code MaxUploadSizeExceededException} 落到默认错误页 = 500，
     * 上传方会以为是平台内部故障，而真正该做的是把包拆小或调大上限 ——
     * 计划的 Interfaces 一节承诺的也是 413。
     */
    @Test
    void 产物包超上限回四百一十三而不是五百() {
        ResponseEntity<Map<String, Object>> res =
                handler.onTooLarge(new MaxUploadSizeExceededException(209715200L));

        assertEquals(413, res.getStatusCode().value());
        assertEquals(false, res.getBody().get("ok"));
        assertTrue(res.getBody().get("error").toString().contains("上限"),
                "没说清是超了上限：" + res.getBody());
    }
}
