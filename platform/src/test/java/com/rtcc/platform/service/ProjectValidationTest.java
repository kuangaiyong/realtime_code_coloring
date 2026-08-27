package com.rtcc.platform.service;

import com.rtcc.platform.config.CoverageProperties;
import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.config.ProjectStore;
import com.rtcc.platform.history.CollectEvents;
import com.rtcc.platform.history.CoverageHistory;
import com.rtcc.platform.collector.CoverageAnalyzer;
import com.rtcc.platform.collector.ProbeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目配置的入库前校验。
 *
 * <p>这里刻意用一个<b>连不上的数据源</b>：本测试要证明的是「配置在写库之前就被挡下来了」，
 * 所以凡是校验没拦住的用例，都会因为写库失败而暴露出来 —— 若某项该拦没拦，
 * 拿到的会是 503 而不是 400，一眼可辨。
 */
class ProjectValidationTest {

    private ProjectRegistry registry;

    @BeforeEach
    void setUp() {
        CoverageProperties platform = new CoverageProperties();
        ProjectConfig seed = platform.toProjectConfig(ProjectConfig.DEFAULT_ID, "默认项目");
        ProjectStore store = new ProjectStore(
                new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/nonexistent"));
        ProjectRuntimeFactory factory = new ProjectRuntimeFactory(
                new ProbeClient(), new CoverageAnalyzer(), platform, new CoveragePublisher(),
                new CoverageHistory(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/nonexistent")),
                new CollectEvents(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/nonexistent")));
        // 库连不上时 loadAll 退回种子，因此这里拿到的是一个只有 default 的注册表
        registry = new ProjectRegistry(seed, store, factory, new CollectEvents(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/nonexistent")));
    }

    private ProjectConfig cfg(String id, String name, List<String> instances) {
        ProjectConfig c = new ProjectConfig();
        c.setId(id);
        c.setName(name);
        c.setInstances(instances);
        return c;
    }

    private ProjectOperationException create(ProjectConfig c) {
        return assertThrows(ProjectOperationException.class, () -> registry.create(c));
    }

    @Test
    void 项目标识非法一律拒绝() {
        // id 同时出现在 URL 路径、WebSocket 查询串和历史表分区键上，放宽就得在三处各自转义
        for (String bad : List.of("Bad Id!", "有中文", "-开头是横杠", "UPPER", "a/b", "")) {
            ProjectOperationException e = create(cfg(bad, "名字", List.of("localhost:6300")));
            assertEquals(HttpStatus.BAD_REQUEST, e.status(), "id=" + bad);
            assertTrue(e.getMessage().contains("项目标识"), e.getMessage());
        }
    }

    @Test
    void 合法的项目标识放行() {
        // 不能拿 default 举例：它本来就在注册表里，走到的是「已存在」而不是写库
        for (String good : List.of("order-svc", "a", "a_b-c9", "9lives")) {
            ProjectOperationException e = create(cfg(good, "名字", List.of("localhost:6300")));
            // 走到写库才失败，说明校验这一关是过了的
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.status(),
                    "id=" + good + " 不该被校验拦住：" + e.getMessage());
        }
    }

    @Test
    void 项目名不能为空() {
        ProjectOperationException e = create(cfg("p1", "  ", List.of("localhost:6300")));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        assertTrue(e.getMessage().contains("项目名"), e.getMessage());
    }

    @Test
    void 一个实例都没配时拒绝() {
        // 放行的话这个项目会安静地永远显示 0 个文件，看不出是配置没填
        ProjectOperationException e = create(cfg("p1", "名字", List.of()));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        assertTrue(e.getMessage().contains("被测实例"), e.getMessage());
    }

    @Test
    void 探针地址写错时点明格式() {
        ProjectOperationException e = create(cfg("p1", "名字", List.of("localhost")));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        assertTrue(e.getMessage().contains("host:port"), e.getMessage());

        ProjectOperationException e2 = create(cfg("p1", "名字", List.of("php://localhost:1")));
        assertEquals(HttpStatus.BAD_REQUEST, e2.status());
        assertTrue(e2.getMessage().contains("不支持的被测语言"), e2.getMessage());
    }

    @Test
    void 建同名项目返回冲突而不是覆盖() {
        // 静默覆盖会让先建的那个项目连同它的场景归档一起消失
        ProjectOperationException e = create(cfg("default", "重名", List.of("localhost:6300")));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertTrue(e.getMessage().contains("已存在"), e.getMessage());
    }

    @Test
    void 默认项目不能删() {
        ProjectOperationException e = assertThrows(ProjectOperationException.class,
                () -> registry.delete(ProjectConfig.DEFAULT_ID));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertTrue(e.getMessage().contains("默认项目"), e.getMessage());
    }

    @Test
    void 改不存在的项目返回404() {
        ProjectOperationException e = assertThrows(ProjectOperationException.class,
                () -> registry.update("no-such", cfg("no-such", "名字", List.of("localhost:6300"))));
        assertEquals(HttpStatus.NOT_FOUND, e.status());
    }

    @Test
    void 探针超时为零或负数时拒绝() {
        // 回归：HttpClient 拒绝非正的 connectTimeout（Invalid duration: PT0S）。
        // 这个错原先要到「造采集器」那一步才抛，而那时配置已经写进库了 ——
        // 此后每次启动都在同一处失败，整个平台连同默认项目和 CI 门禁一起开不了机
        for (int bad : new int[]{0, -1}) {
            ProjectConfig c = cfg("p1", "名字", List.of("localhost:6300"));
            c.setTimeoutMs(bad);
            ProjectOperationException e = create(c);
            assertEquals(HttpStatus.BAD_REQUEST, e.status(), "timeoutMs=" + bad);
            assertTrue(e.getMessage().contains("超时"), e.getMessage());
        }
    }

    @Test
    void 门禁配置为空时补上默认值而不是留个空指针() {
        // 请求体里显式写 "gate": null 时 Jackson 会把默认值抹掉，
        // 之后门禁接口必 NPE —— CI 拿到 500，而「判不了」按约定只应该是 409
        ProjectConfig c = cfg("p1", "名字", List.of("localhost:6300"));
        c.setGate(null);
        // 走到写库才失败，说明 gate 已被补上，没被校验拦住、也没抛 NPE
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, create(c).status());
        assertEquals(80d, c.getGate().getIncrementalThreshold());
    }

    @Test
    void 门禁阈值超出百分比范围时拒绝() {
        for (double bad : new double[]{-1, 101}) {
            ProjectConfig c = cfg("p1", "名字", List.of("localhost:6300"));
            c.getGate().setIncrementalThreshold(bad);
            ProjectOperationException e = create(c);
            assertEquals(HttpStatus.BAD_REQUEST, e.status(), "threshold=" + bad);
            assertTrue(e.getMessage().contains("门禁阈值"), e.getMessage());
        }
    }

    @Test
    void 写库失败时报503而不是400() {
        // 「你填错了」与「平台自己的依赖挂了」处置完全不同：一个该改了再存，一个该找人看平台
        ProjectOperationException e = create(cfg("p1", "名字", List.of("localhost:6300")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.status());
        assertTrue(e.getMessage().contains("未生效"), e.getMessage());
    }

    @Test
    void 写库失败后不留下半个项目() {
        create(cfg("p1", "名字", List.of("localhost:6300")));
        // 存不进去却在内存里生效，重启就消失 —— 人会以为平台把配置吃了
        assertThrows(ProjectOperationException.class, () -> registry.get("p1"));
        assertEquals(List.of(ProjectConfig.DEFAULT_ID),
                registry.configs().stream().map(ProjectConfig::getId).toList());
    }
}
