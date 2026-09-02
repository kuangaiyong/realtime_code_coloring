package com.rtcc.platform.service;

import com.rtcc.platform.collector.ProbeClient;
import com.rtcc.platform.config.ProjectConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「测这一台」这个接口。它比 {@code /check} 窄：只连一台，给接入自检表上那行按钮用。
 *
 * <p>这里守的两件事都不是功能，是<b>边界</b>：不接受任意地址（否则平台成了内网端口
 * 探测器），以及超时为 0 时先拒绝（否则一个工作线程永远回不来）。
 * 功能本身由 {@code scripts/ui_verify.js} 拿真实实例端到端验。
 */
class ProjectCheckerProbeOneTest {

    private final ProjectChecker checker = new ProjectChecker(new ProbeClient());

    private static ProjectConfig cfg(int timeoutMs, String... instances) {
        ProjectConfig c = new ProjectConfig();
        c.setId("probe-one-test");
        c.setName("探一台用");
        c.setInstances(List.of(instances));
        c.setRepoDir(".");
        c.setTimeoutMs(timeoutMs);
        return c;
    }

    /** 保留地址段（RFC 5737 TEST-NET-1），连不上且不会误打到真实机器 */
    private static final String UNREACHABLE = "java://192.0.2.1:6300";

    /**
     * 平台通常部署在能连到全部测试环境的位置，而探针端口本来就没有鉴权 ——
     * 接受任意 host:port 的话，这个接口就是一台可以从平台发起的内网端口探测器。
     */
    @Test
    void 不在配置里的地址一律拒绝而不是去连它() {
        ProjectConfig c = cfg(3000, UNREACHABLE);

        ProjectOperationException e = assertThrows(ProjectOperationException.class,
                () -> checker.probeOne(c, "java://10.0.0.1:9999"));
        assertTrue(e.getMessage().contains("只探已配置的实例"), e.getMessage());
    }

    /**
     * 拒绝必须发生在<b>发起连接之前</b>。先连再判的话，端口扫描的效果已经达成了 ——
     * 而且响应时间的长短本身就泄漏了「那个端口有没有人听」。
     */
    @Test
    void 拒绝发生在连接之前而不是连完再判() {
        ProjectConfig c = cfg(3000, UNREACHABLE);

        // 真去连一个连不上的地址要耗满 3000ms 超时；这里必须快得多
        assertTimeoutPreemptively(Duration.ofMillis(800),
                () -> assertThrows(ProjectOperationException.class,
                        () -> checker.probeOne(c, "java://192.0.2.99:6300")));
    }

    /**
     * timeoutMs 被同时用作 socket.connect 与 setSoTimeout 的超时，两处 0 的语义
     * 都是「无限等待」—— 对着一个连不上的地址探一次，这个工作线程就再也不回来了。
     */
    @Test
    void 超时为零时当场点名而不是挂住() {
        ProjectConfig c = cfg(0, UNREACHABLE);

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            ProjectOperationException e = assertThrows(ProjectOperationException.class,
                    () -> checker.probeOne(c, UNREACHABLE));
            assertTrue(e.getMessage().contains("无限等待"), e.getMessage());
        });
    }

    /** 连不上要回一份带原因的结果，而不是抛异常变成 500 —— 探针没起来是常态，不是平台故障 */
    @Test
    void 连不上时给出具体原因而不是五百() {
        ProjectConfig c = cfg(500, UNREACHABLE);

        Map<String, Object> r = checker.probeOne(c, UNREACHABLE);
        assertEquals(false, r.get("connected"));
        assertNotNull(r.get("error"), "只说一句失败等于让人去翻日志");
        assertFalse(String.valueOf(r.get("error")).isBlank());
        assertEquals(UNREACHABLE, r.get("endpoint"));
    }

    /**
     * 配置里省略语言前缀是允许的（不写默认 java）。两边不各自规范化的话，
     * 页面把自检表上那个带前缀的地址发回来，会被判成「不在配置里」——
     * 而那个地址正是平台自己给出去的。
     */
    @Test
    void 配置里省略语言前缀时仍认得出是同一台() {
        ProjectConfig c = cfg(500, "192.0.2.1:6300"); // 没写 java://

        Map<String, Object> r = checker.probeOne(c, "java://192.0.2.1:6300");
        assertEquals(false, r.get("connected"), "连不上是预期的，这里要的是它没被判成「不在配置里」");
    }

    @Test
    void 没指定要探哪一台时明确拒绝() {
        ProjectConfig c = cfg(3000, UNREACHABLE);

        assertThrows(ProjectOperationException.class, () -> checker.probeOne(c, null));
        assertThrows(ProjectOperationException.class, () -> checker.probeOne(c, "  "));
    }
}
