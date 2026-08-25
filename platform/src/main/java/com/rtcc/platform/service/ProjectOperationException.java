package com.rtcc.platform.service;

import org.springframework.http.HttpStatus;

/**
 * 项目的增删改做不了，并说明是哪一类做不了。
 *
 * <p>这里没有沿用「一种语义一个异常类」的写法（如 {@link ScenarioConflictException}），
 * 是因为项目操作的失败原因有四类且都很薄：不存在（404）、与当前状态冲突（409）、
 * 配置本身非法（400）、数据库写不进去（503）。四个空壳类换不来任何表达力，
 * 而调用方真正要区分的就是这个状态码 —— 页面据它决定是「改一下再存」还是「找人看平台」。
 */
public class ProjectOperationException extends RuntimeException {

    private final HttpStatus status;

    private ProjectOperationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** 没有这个项目 */
    public static ProjectOperationException notFound(String message) {
        return new ProjectOperationException(HttpStatus.NOT_FOUND, message);
    }

    /** 现在这个状态下不能做（id 已被占用、场景进行中、要删的是默认项目） */
    public static ProjectOperationException conflict(String message) {
        return new ProjectOperationException(HttpStatus.CONFLICT, message);
    }

    /** 配置本身填错了，改了才能存 */
    public static ProjectOperationException invalid(String message) {
        return new ProjectOperationException(HttpStatus.BAD_REQUEST, message);
    }

    /** 平台自己的依赖不可用（数据库写不进去），与「你填错了」必须分开 —— 处置完全不同 */
    public static ProjectOperationException unavailable(String message) {
        return new ProjectOperationException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
