package com.coverage.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** 覆盖率变化的实时推送通道 */
@Component
public class CoveragePublisher extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CoveragePublisher.class);

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("染色视图已连接，当前 {} 个会话", sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcast(Map<String, Object> payload) {
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
        for (WebSocketSession s : sessions) {
            try {
                if (!s.isOpen()) {
                    sessions.remove(s);
                    continue;
                }
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
