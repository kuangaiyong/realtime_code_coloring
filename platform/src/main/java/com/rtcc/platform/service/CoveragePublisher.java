package com.rtcc.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtcc.platform.config.ProjectConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 覆盖率变化的实时推送通道。
 *
 * <p>会话按项目分：一个项目的覆盖推送若发给正在看另一个项目的页面，
 * 页面会拿着别人的数据重绘，而界面上看不出这是串台 —— 只会觉得覆盖率莫名跳变。
 */
@Component
public class CoveragePublisher extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CoveragePublisher.class);

    /** 会话 → 它订阅的项目。会话数就是开着的页面数，逐个过滤的开销可以忽略 */
    private final Map<WebSocketSession, String> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String project = projectOf(session.getUri());
        sessions.put(session, project);
        log.info("染色视图已连接（项目 {}），当前 {} 个会话", project, sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        sessions.remove(session);
    }

    /**
     * 会话订阅哪个项目：看连接地址上的 {@code ?project=xxx}。
     * 不带这个参数就算默认项目 —— 旧地址 {@code /ws/coverage} 因此原样可用。
     *
     * <p>必须取 {@code getRawQuery()} 而不是 {@code getQuery()}：后者已经解过一次码，
     * 再解一次会把 {@code %2B} 变成空格、把 {@code %26} 变成真的 {@code &}
     * 从而在下面被当成参数分隔符切断。两种情况都会把会话订阅到一个不存在的项目上，
     * 表现为「页面连上了、状态正常，数字却永远不动」。
     */
    static String projectOf(URI uri) {
        String query = uri == null ? null : uri.getRawQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0 && "project".equals(part.substring(0, eq))) {
                    String id = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                    if (!id.isBlank()) {
                        return id;
                    }
                }
            }
        }
        return ProjectConfig.DEFAULT_ID;
    }

    public void broadcast(String projectId, Map<String, Object> payload) {
        String target = projectId == null ? ProjectConfig.DEFAULT_ID : projectId;
        if (sessions.isEmpty()) {
            return;
        }
        TextMessage msg;
        try {
            msg = new TextMessage(mapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("覆盖率数据序列化失败：{}", e.getMessage());
            return;
        }
        // 逐个会话独立处理：某个连接发送失败（如对端已断但尚未探测到）
        // 不能影响其余连接收到本次推送
        for (Map.Entry<WebSocketSession, String> entry : sessions.entrySet()) {
            WebSocketSession s = entry.getKey();
            // 已断的会话不分项目一律摘掉：对端掉电这类断开不会触发 afterConnectionClosed，
            // 只按目标项目遍历的话，一个没有覆盖变化的项目的死会话会永远留在表里
            if (!s.isOpen()) {
                sessions.remove(s);
                continue;
            }
            if (!target.equals(entry.getValue())) {
                continue;
            }
            try {
                synchronized (s) {
                    s.sendMessage(msg);
                }
            } catch (Exception e) {
                log.warn("向会话 {} 推送失败，已摘除：{}", s.getId(), e.getMessage());
                sessions.remove(s);
            }
        }
    }
}
