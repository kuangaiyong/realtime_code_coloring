package com.rtcc.platform.service;

import com.rtcc.platform.collector.CoverageAnalyzer;
import com.rtcc.platform.collector.ProbeClient;
import com.rtcc.platform.config.CoverageProperties;
import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.config.ProjectStore;
import com.rtcc.platform.history.CoverageHistory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装不起来的项目必须仍然改得动、删得掉。
 *
 * <p>启动时造运行时可能失败（库里那行配置被人手工改坏、或旧版本写进去的字段
 * 新版本不认）。此时不能把它从注册表里彻底抹掉：库里那一行还在，而 API 里
 * 查不到、改不了、删不掉 —— 每次启动刷同一条 ERROR，人却没有任何出路。
 */
class ProjectLoadFailureTest {

    private static final String BROKEN = "broken-one";
    /** 桩工厂按<b>配置内容</b>判成不成，而不是按 id —— 真实场景是「配置改对了就能起来」 */
    private static final String BAD_NAME = "装不起来的项目";

    private static DataSource unreachable() {
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/nonexistent");
    }

    /** 库连不上，所以「不是 404」只会以 503 的形式出现 —— 这正是要断言的那一半 */
    private ProjectRegistry registry() {
        CoverageProperties platform = new CoverageProperties();
        ProjectConfig seed = platform.toProjectConfig(ProjectConfig.DEFAULT_ID, "默认项目");
        ProjectConfig broken = platform.toProjectConfig(BROKEN, BAD_NAME);

        ProjectStore store = new ProjectStore(unreachable()) {
            @Override
            public List<ProjectConfig> loadAll(ProjectConfig s) {
                return List.of(seed, broken);
            }
        };
        ProjectRuntimeFactory factory = new ProjectRuntimeFactory(
                new ProbeClient(), new CoverageAnalyzer(), platform, new CoveragePublisher(),
                new CoverageHistory(unreachable())) {
            @Override
            public ProjectRuntime create(ProjectConfig cfg) {
                if (BAD_NAME.equals(cfg.getName())) {
                    throw new IllegalStateException("这份配置造不出运行时");
                }
                return super.create(cfg);
            }
        };
        return new ProjectRegistry(seed, store, factory);
    }

    @Test
    void 装载失败不影响其它项目() {
        ProjectRegistry r = registry();
        assertTrue(r.find(ProjectConfig.DEFAULT_ID) != null, "默认项目该照常装载");
        // 没有运行时 —— 它确实采不了数据，这一点不能粉饰
        assertNull(r.find(BROKEN));
    }

    @Test
    void 装载失败的项目在列表里看得见() {
        // 看不见的话，人根本不知道库里还有这么一行，只能去翻启动日志
        assertTrue(registry().configs().stream().anyMatch(c -> BROKEN.equals(c.getId())),
                "装载失败的项目应当仍出现在项目列表里");
    }

    @Test
    void 装载失败的项目删得掉而不是404() {
        ProjectOperationException e = assertThrows(ProjectOperationException.class,
                () -> registry().delete(BROKEN));
        // 库连不上，所以停在 503「存不进去」；关键是不再是 404「没有这个项目」——
        // 那会让人以为库里那行不存在，于是永远删不掉它
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.status(), e.getMessage());
    }

    @Test
    void 装载失败的项目改得动而不是404() {
        // 换个名字＝这份配置桩工厂造得出来，模拟「人在页面上把配置改对了」
        ProjectConfig fixed = new CoverageProperties().toProjectConfig(BROKEN, "改好的配置");
        ProjectOperationException e = assertThrows(ProjectOperationException.class,
                () -> registry().update(BROKEN, fixed));
        // 新配置造得出运行时，于是一路走到写库才失败（库连不上）→ 503。
        // 关键是不再是 404「没有这个项目」——「把配置改对」是这个项目唯一的出路，
        // 回 404 等于把这条路堵死
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.status(), e.getMessage());
    }
}
