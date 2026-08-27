package com.rtcc.platform.service;

import com.rtcc.platform.collector.CoverageAnalyzer;
import com.rtcc.platform.collector.CppCoverageAnalyzer;
import com.rtcc.platform.collector.CppProbeClient;
import com.rtcc.platform.collector.GitService;
import com.rtcc.platform.collector.GoCoverageAnalyzer;
import com.rtcc.platform.collector.GoProbeClient;
import com.rtcc.platform.collector.ProbeClient;
import com.rtcc.platform.collector.RustCoverageAnalyzer;
import com.rtcc.platform.collector.RustProbeClient;
import com.rtcc.platform.config.CoverageProperties;
import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.history.CollectEvents;
import com.rtcc.platform.history.CoverageHistory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集事件<b>只在状态变化时</b>落一条。
 *
 * <p>这条规则是这张表能不能用的前提：采集是 3 秒一轮的常驻轮询，每轮记一条是一天
 * 28800 行 —— 表会被灌爆，而「连上了 / 掉了 / 又连上了」这几个真正的转折点会被淹掉。
 * 破坏它不会有任何报错，只会让事件页变得没法看，所以必须有用例守着。
 *
 * <p>E2E（{@code ui_verify.js}）已经在真实环境里验过一次掉线-恢复，这里补的是
 * 「连采十轮只记一条」这半边 —— 那半边在 E2E 里只能靠数量粗略推断。
 */
class CollectEventRecordingTest {

    /** 记下每一次 record 调用，替代真实数据库 */
    private static final class Recorder extends CollectEvents {
        private final List<String> recorded = new ArrayList<>();

        Recorder(DataSource ds) {
            super(ds);
        }

        @Override
        public void record(String projectId, String status, String detail) {
            recorded.add(status);
        }
    }

    private static DataSource unreachable() {
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/nonexistent");
    }

    private static ProjectRuntime runtimeOn(int deadPort, Recorder recorder) {
        ProjectConfig props = new ProjectConfig();
        props.setId("evt");
        props.setInstances(List.of("localhost:" + deadPort));
        props.setTimeoutMs(300);
        CoverageProperties platform = new CoverageProperties();
        return new ProjectRuntime(new ProbeClient(), new CoverageAnalyzer(),
                new GoProbeClient(props), new GoCoverageAnalyzer(props, platform),
                new CppProbeClient(props), new CppCoverageAnalyzer(props, platform),
                new RustProbeClient(props), new RustCoverageAnalyzer(props, platform),
                new GitService(props), props, new CoveragePublisher(),
                new CoverageHistory(unreachable()), recorder);
    }

    /** 一个刚释放、确定没人监听的端口：这些用例只关心「够不到探针」这条路 */
    private static int deadPort() throws Exception {
        try (ServerSocket free = new ServerSocket(0)) {
            return free.getLocalPort();
        }
    }

    @Test
    void 连采十轮只记一条() throws Exception {
        Recorder rec = new Recorder(unreachable());
        ProjectRuntime rt = runtimeOn(deadPort(), rec);
        for (int i = 0; i < 10; i++) {
            rt.collect();
        }
        // 探针一直连不上，状态从头到尾都是 DISCONNECTED —— 只该有第一次那一条
        assertEquals(List.of("DISCONNECTED"), rec.recorded,
                "每轮都记的话，3 秒一轮就是一天 28800 行，真正的转折点会被淹掉");
    }

    @Test
    void 状态真的变了才记() throws Exception {
        Recorder rec = new Recorder(unreachable());
        ProjectConfig props = new ProjectConfig();
        props.setId("evt");
        // 地址填成解析不出来的形式：走的是 CONFIG_ERROR 这条路，与 DISCONNECTED 不同
        props.setInstances(List.of("这不是一个合法地址"));
        props.setTimeoutMs(300);
        CoverageProperties platform = new CoverageProperties();
        ProjectRuntime rt = new ProjectRuntime(new ProbeClient(), new CoverageAnalyzer(),
                new GoProbeClient(props), new GoCoverageAnalyzer(props, platform),
                new CppProbeClient(props), new CppCoverageAnalyzer(props, platform),
                new RustProbeClient(props), new RustCoverageAnalyzer(props, platform),
                new GitService(props), props, new CoveragePublisher(),
                new CoverageHistory(unreachable()), rec);
        rt.collect();
        rt.collect();
        assertEquals(List.of("CONFIG_ERROR"), rec.recorded);

        // 换成一个连不上但地址合法的实例：状态从 CONFIG_ERROR 变成 DISCONNECTED，
        // 这一次必须记下来 —— 两者的排查方向完全不同，混为一谈等于没记
        props.setInstances(List.of("localhost:" + deadPort()));
        rt.collect();
        assertEquals(List.of("CONFIG_ERROR", "DISCONNECTED"), rec.recorded);
    }

    @Test
    void 顶替旧实例时不伪造一条恢复事件() throws Exception {
        int dead = deadPort();
        Recorder oldRec = new Recorder(unreachable());
        ProjectRuntime old = runtimeOn(dead, oldRec);
        old.collect();
        assertEquals(List.of("DISCONNECTED"), oldRec.recorded);

        // 配置热替换走「造新实例整体顶替」。新实例从 UNKNOWN 起步，
        // 不把状态接过来的话，它第一次采集就会记一条变化 ——
        // 而实际上什么都没变，只是有人改了配置
        Recorder freshRec = new Recorder(unreachable());
        ProjectRuntime fresh = runtimeOn(dead, freshRec);
        fresh.adoptScenariosFrom(old);
        fresh.collect();
        assertTrue(freshRec.recorded.isEmpty(),
                "改配置不是一次状态变化，却记下了：" + freshRec.recorded);
    }
}
