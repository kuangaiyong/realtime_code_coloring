package com.rtcc.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtcc.platform.artifact.ArtifactAuth;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 产物上传 / 删除的令牌准入，判法见 {@link ArtifactAuth}。
 *
 * <p><b>为什么是过滤器，而不是在控制器里判</b>：控制器拿到 {@code MultipartFile} 参数时，
 * DispatcherServlet 已经把整个 multipart 请求体收下、写成临时文件了（{@code checkMultipart}
 * 排在找处理器之前）。在那里才判，没带令牌的请求照样能让平台为每个请求写最多 200MB 临时文件。
 * 这里只读请求头与路径，Spring 这一层不解析请求体、不落任何临时文件。
 * <b>但流量省不掉</b>：拒掉之后 Tomcat 仍按 {@code server.tomcat.max-swallow-size}（200MB，
 * 理由见 application.yml）吞掉剩余请求体再收尾 —— 只占带宽与这一个工作线程，不落盘。
 *
 * <p><b>路径不按字符串前缀比</b>：MVC 认路径时会剥掉分号参数、解开百分号编码，
 * {@code /api;x=1/artifacts/…}、{@code /%61pi/artifacts/…} 在它眼里都是产物接口。
 * 只比前缀的话这几种写法就绕开了令牌，所以用与 MVC 同一套解析（{@link RequestPath}）逐段比。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ArtifactAuthFilter extends OncePerRequestFilter {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 列表只有 buildId 与语言，没有内容；页面将来要展示它也不该为此拿令牌 */
    private static final Set<String> READ_ONLY = Set.of("GET", "HEAD", "OPTIONS");

    private final ArtifactAuth auth;

    public ArtifactAuthFilter(ArtifactAuth auth) {
        this.auth = auth;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return READ_ONLY.contains(request.getMethod()) || !isArtifactPath(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        ArtifactAuth.Rejection no = auth.check(request.getHeader("Authorization"));
        if (no == null) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(no.status().value());
        if (no.status() == HttpStatus.UNAUTHORIZED) {
            // RFC 7235：401 必须告诉对方该用哪种认证方式
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        // 报错体由 ApiExceptionHandler 构造：CI 解析报错时不必区分是谁拒的，以后改形状也只改一处
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(JSON.writeValueAsBytes(ApiExceptionHandler.error(no.message())));
    }

    /** 已解码、已去掉分号参数之后，非空的前两段是 api / artifacts */
    static boolean isArtifactPath(HttpServletRequest request) {
        RequestPath path = RequestPath.parse(request.getRequestURI(), request.getContextPath());
        List<String> segs = new ArrayList<>(2);
        for (PathContainer.Element e : path.pathWithinApplication().elements()) {
            if (e instanceof PathContainer.PathSegment s && !s.valueToMatch().isEmpty()) {
                segs.add(s.valueToMatch());
                if (segs.size() == 2) {
                    break;
                }
            }
        }
        return segs.size() == 2 && "api".equals(segs.get(0)) && "artifacts".equals(segs.get(1));
    }
}
