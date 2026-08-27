package com.rtcc.platform.service;

import com.rtcc.platform.collector.ProbeClient;
import com.rtcc.platform.config.ProjectConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /api/projects/check} 拿的是请求体里的配置，直接去碰真实环境，
 * 不走 {@link ProjectRegistry} 的入库前校验 —— 所以它得自己挡住会挂死的取值。
 *
 * <p>{@code timeoutMs} 被 {@code ProbeClient} 同时用作 {@code socket.connect} 与
 * {@code setSoTimeout} 的超时，这两处 <b>0 的语义都是「无限等待」</b>。
 * 对着一个连不上的地址检查一次，这个 Tomcat 工作线程就再也不回来了，
 * 而接入向导的「当场验」每点一下就打一次这个接口。
 */
class ProjectCheckerTimeoutTest {

    private static ProjectConfig cfg(int timeoutMs) {
        ProjectConfig c = new ProjectConfig();
        c.setId("check-test");
        c.setName("检查用");
        // 保留地址段（RFC 5737 TEST-NET-1），连不上且不会误打到真实机器
        c.setInstances(List.of("java://192.0.2.1:6300"));
        c.setRepoDir(".");
        c.setTimeoutMs(timeoutMs);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> res) {
        return (List<Map<String, Object>>) res.get("items");
    }

    @Test
    void 超时为零时当场点名而不是挂住() {
        ProjectChecker checker = new ProjectChecker(new ProbeClient());
        // 挂死的表现就是「这个调用永远不返回」，所以断言必须带抢占式超时；
        // 少了它，回归失败的样子是测试卡住而不是报错
        Map<String, Object> res = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> checker.check(cfg(0)),
                "timeoutMs=0 时检查没有返回 —— 这正是会占死一个工作线程的那条路");

        assertFalse((Boolean) res.get("ok"), "timeoutMs=0 不该被判为配置正常");
        assertTrue(items(res).stream().anyMatch(i -> "timeoutMs".equals(i.get("name"))
                        && Boolean.FALSE.equals(i.get("ok"))),
                "自检表必须点名是 timeoutMs 这一项错了：" + items(res));
    }

    @Test
    void 超时为负同样挡住() {
        ProjectChecker checker = new ProjectChecker(new ProbeClient());
        Map<String, Object> res = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> checker.check(cfg(-1)));
        assertTrue(items(res).stream().anyMatch(i -> "timeoutMs".equals(i.get("name"))
                && Boolean.FALSE.equals(i.get("ok"))));
    }
}
