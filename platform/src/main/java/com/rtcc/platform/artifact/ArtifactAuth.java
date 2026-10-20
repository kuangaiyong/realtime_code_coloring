package com.rtcc.platform.artifact;

import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 产物上传 / 删除接口的令牌。
 *
 * <p>这两个接口是平台上唯一能往磁盘写任意字节、能删掉任意构建产物的入口：不设防时，
 * 内网里任何一台机器都能删掉产物让项目整轮 ANALYZE_ERROR，或推一份对不上的产物让行号错位。
 *
 * <p><b>没配令牌就关闭，而不是放行</b>：放行等于默认敞着，「补鉴权」只对记得配的人生效。
 * 令牌取自平台的 {@code COVERAGE_ARTIFACT_TOKEN}（yml 键 {@code coverage.artifact-token}），
 * 请求头写成 {@code Authorization: Bearer <令牌>}。
 */
public final class ArtifactAuth {

    /** 拒绝时要点名的配置项 —— 调用方（通常是 CI）看到的只有这句话 */
    static final String ENV = "COVERAGE_ARTIFACT_TOKEN";

    /** null 表示平台没配令牌 */
    private final byte[] token;

    public ArtifactAuth(String token) {
        // 从文件或环境变量读进来的值两端常带空白或换行；全是空白按没配处理
        String t = token == null ? "" : token.strip();
        this.token = t.isEmpty() ? null : t.getBytes(StandardCharsets.UTF_8);
    }

    public boolean enabled() {
        return token != null;
    }

    /** @return null 表示放行；否则是拒绝的状态码与给调用方的原因 */
    public Rejection check(String authorizationHeader) {
        if (token == null) {
            return new Rejection(HttpStatus.FORBIDDEN,
                    "产物上传 / 删除接口没开：平台没有配置令牌（环境变量 " + ENV
                            + "，即 coverage.artifact-token）。配上之后，请求头带 Authorization: Bearer <令牌>");
        }
        String presented = bearer(authorizationHeader);
        if (presented == null) {
            return new Rejection(HttpStatus.UNAUTHORIZED,
                    "缺少令牌：产物上传 / 删除要在请求头带 Authorization: Bearer <令牌>，令牌即平台的 " + ENV);
        }
        // 定长比较：逐字节比到第一处不同就返回的话，响应时间会泄漏猜对了几位
        if (!MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            return new Rejection(HttpStatus.UNAUTHORIZED,
                    "令牌不对：Authorization: Bearer 后面的值与平台的 " + ENV + " 不一致");
        }
        return null;
    }

    /** 取 Bearer 后面的令牌。不是这种写法、或令牌为空，返回 null；scheme 不区分大小写（RFC 7235） */
    private static String bearer(String header) {
        if (header == null) {
            return null;
        }
        String h = header.strip();
        int sp = h.indexOf(' ');
        if (sp < 0 || !h.substring(0, sp).equalsIgnoreCase("Bearer")) {
            return null;
        }
        String t = h.substring(sp + 1).strip();
        return t.isEmpty() ? null : t;
    }

    public record Rejection(HttpStatus status, String message) {}
}
