package com.rtcc.platform.web;

import com.rtcc.platform.artifact.ArtifactAuth;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 产物写接口的令牌准入。
 *
 * <p>上传与删除是平台上唯一能把任意字节写进磁盘、能删掉任意构建产物的两个入口。
 * 不设防时，内网里任何一台机器都能删掉产物让项目整轮 ANALYZE_ERROR，
 * 或推一份对不上的产物让行号错位而看不出来。
 *
 * <p>拒绝必须发生在<b>读请求体之前</b>：控制器拿到 MultipartFile 时整个请求体已经被收下、
 * 落成临时文件 —— 在那里才判，没带令牌的人照样能让平台为每个请求写 200MB 临时文件。
 * 所以这里的请求一碰请求体就直接抛错。
 */
class ArtifactAuthFilterTest {

    private static final String TOKEN = "t0ken-for-unit-test";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String UPLOAD = "/api/artifacts/" + SHA;

    /** 碰一下请求体就炸：证明判定只用了请求头与路径 */
    private static MockHttpServletRequest req(String method, String uri, String authorization) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri) {
            @Override public ServletInputStream getInputStream() { throw new AssertionError("判定前读了请求体"); }
            @Override public BufferedReader getReader() { throw new AssertionError("判定前读了请求体"); }
            @Override public String getParameter(String name) {
                throw new AssertionError("判定前解析了参数 —— multipart 会因此被整个收下");
            }
            @Override public Part getPart(String name) { throw new AssertionError("判定前解析了 multipart"); }
            @Override public Collection<Part> getParts() { throw new AssertionError("判定前解析了 multipart"); }
        };
        if (authorization != null) {
            r.addHeader("Authorization", authorization);
        }
        return r;
    }

    private record Outcome(int status, String body, boolean passedOn, String wwwAuthenticate) {}

    private static Outcome run(String configuredToken, MockHttpServletRequest r) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        FilterChain chain = (q, s) -> passed.set(true);
        new ArtifactAuthFilter(new ArtifactAuth(configuredToken)).doFilter(r, resp, chain);
        return new Outcome(resp.getStatus(), resp.getContentAsString(StandardCharsets.UTF_8),
                passed.get(), resp.getHeader("WWW-Authenticate"));
    }

    @Test
    void 平台没配令牌时上传与删除一律关闭并说清怎么开() throws Exception {
        // 没配就放行等于默认敞着：「补鉴权」只对记得配的人生效
        for (String unset : new String[]{"", "   ", null}) {
            for (String method : List.of("POST", "DELETE")) {
                Outcome o = run(unset, req(method, UPLOAD, "Bearer whatever"));
                assertEquals(403, o.status(), method + " token=[" + unset + "]");
                assertFalse(o.passedOn(), "没配令牌却放行了 " + method);
                assertTrue(o.body().contains("COVERAGE_ARTIFACT_TOKEN"), o.body());
                assertTrue(o.body().contains("\"ok\":false"), "响应体要沿用全站的 {ok:false, error} 形状：" + o.body());
            }
        }
    }

    @Test
    void 没带令牌回401并说清令牌放哪() throws Exception {
        for (String method : List.of("POST", "DELETE", "PUT")) {
            for (String bad : new String[]{null, "", "Basic dXNlcjpwYXNz", "Bearer", "Bearer   ", TOKEN}) {
                Outcome o = run(TOKEN, req(method, UPLOAD, bad));
                assertEquals(401, o.status(), method + " Authorization=[" + bad + "]");
                assertFalse(o.passedOn());
                // RFC 7235：401 必须带 WWW-Authenticate
                assertNotNull(o.wwwAuthenticate(), "401 没带 WWW-Authenticate");
                assertTrue(o.wwwAuthenticate().startsWith("Bearer"), o.wwwAuthenticate());
                assertTrue(o.body().contains("Authorization: Bearer"), o.body());
            }
        }
    }

    @Test
    void 令牌不对回401() throws Exception {
        for (String wrong : new String[]{"Bearer nope", "Bearer " + TOKEN + "x", "Bearer " + TOKEN.toUpperCase()}) {
            for (String method : List.of("POST", "DELETE")) {
                Outcome o = run(TOKEN, req(method, UPLOAD, wrong));
                assertEquals(401, o.status(), method + " " + wrong);
                assertFalse(o.passedOn());
                assertTrue(o.body().contains("不一致"), o.body());
            }
        }
    }

    @Test
    void 令牌对了放行() throws Exception {
        for (String method : List.of("POST", "DELETE")) {
            assertTrue(run(TOKEN, req(method, UPLOAD, "Bearer " + TOKEN)).passedOn(), method);
        }
        // scheme 不区分大小写（RFC 7235）
        assertTrue(run(TOKEN, req("POST", UPLOAD, "bearer " + TOKEN)).passedOn());
        // 平台侧的令牌常从文件或环境变量读进来，两端带个换行是常态
        assertTrue(run("  " + TOKEN + "\n", req("POST", UPLOAD, "Bearer " + TOKEN)).passedOn());
    }

    @Test
    void 读接口不要令牌() throws Exception {
        // 列表只有 buildId 与语言，没有内容；页面将来要展示它也不该为此拿令牌
        for (String method : List.of("GET", "HEAD", "OPTIONS")) {
            assertTrue(run(TOKEN, req(method, "/api/artifacts", null)).passedOn(), method);
        }
        // 平台没配令牌时，读接口同样照常
        assertTrue(run("", req("GET", "/api/artifacts", null)).passedOn());
    }

    @Test
    void 别的接口不受影响() throws Exception {
        for (String uri : List.of("/api/projects", "/api/projects/x/collect",
                "/api/artifactsX/" + SHA, "/api/probe/artifacts/java")) {
            assertTrue(run(TOKEN, req("POST", uri, null)).passedOn(), uri);
        }
    }

    @Test
    void 换一种路径写法绕不过令牌() throws Exception {
        // MVC 认这几种写法（分号参数会被剥掉、百分号编码会被解开），过滤器必须同样认得 ——
        // 只按字符串前缀比的话，这几条请求会绕开令牌直达上传接口
        for (String uri : List.of(
                "/api;x=1/artifacts/" + SHA,
                "/api/artifacts;v=2/" + SHA,
                "/%61pi/artifacts/" + SHA,
                "/api/%61rtifacts/" + SHA,
                "/api/artifacts")) {
            Outcome o = run(TOKEN, req("POST", uri, null));
            assertEquals(401, o.status(), uri);
            assertFalse(o.passedOn(), uri);
        }
    }
}
