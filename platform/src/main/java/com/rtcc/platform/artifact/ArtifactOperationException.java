package com.rtcc.platform.artifact;

import org.springframework.http.HttpStatus;

/**
 * 产物的上传 / 查询 / 删除做不了，并说明是哪一类做不了。
 *
 * <p>沿用 {@code ProjectOperationException} 的写法，理由也一样：失败原因都很薄，
 * 一种语义一个空壳类换不来表达力，而调用方（CI）真正要区分的就是这个状态码 ——
 * 400 是「你传的包或参数有问题，改了再传」，5xx 是「平台这边存不下来，找人看平台」。
 *
 * <p><b>为什么不用 {@code ResponseStatusException}</b>：它的 reason 会被 Spring 吞掉 ——
 * {@code server.error.include-message} 默认 {@code never}，错误体里只剩
 * {@code timestamp/status/error/path}，写得再清楚的中文原因也一个字都到不了 CI。
 * 而把那个开关打成 {@code always} 是<b>全局</b>的，等于让平台所有接口的内部异常信息
 * （数据库异常、内部路径）一并外泄，是安全倒退。
 */
public class ArtifactOperationException extends RuntimeException {

    private final HttpStatus status;

    private ArtifactOperationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** 传上来的东西有问题：buildId 不合法、project 会指向产物根之外、语言不认识、包是空的或根本不是 zip */
    public static ArtifactOperationException invalid(String message) {
        return new ArtifactOperationException(HttpStatus.BAD_REQUEST, message);
    }

    /** 平台自己存不下来。与「你传错了」必须分开 —— 一个该去查包，一个该去查平台 */
    public static ArtifactOperationException failed(String message) {
        return new ArtifactOperationException(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
