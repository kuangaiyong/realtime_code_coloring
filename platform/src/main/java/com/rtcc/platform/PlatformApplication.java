package com.rtcc.platform;

import com.rtcc.platform.config.CoverageProperties;
import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.service.CoveragePublisher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CoverageProperties.class)
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }

    /**
     * 首次启动时种进数据库的那个项目。库里已经有项目之后，这份配置就只是
     * 数据库读不到时的兜底 —— 见 {@code ProjectStore#loadAll}。
     */
    @Bean
    public ProjectConfig defaultProjectConfig(CoverageProperties props) {
        return props.toProjectConfig(ProjectConfig.DEFAULT_ID, "默认项目");
    }

    @Configuration
    @EnableWebSocket
    static class WebSocketConfig implements WebSocketConfigurer {

        private final CoveragePublisher publisher;

        WebSocketConfig(CoveragePublisher publisher) {
            this.publisher = publisher;
        }

        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(publisher, "/ws/coverage").setAllowedOrigins("*");
        }
    }
}
