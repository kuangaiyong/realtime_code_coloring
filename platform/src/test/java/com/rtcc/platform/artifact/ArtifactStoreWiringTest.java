package com.rtcc.platform.artifact;

import com.rtcc.platform.config.CoverageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ArtifactStore} 这个 bean 装不出来，整个平台就起不来。这类缺陷单测发现不了 ——
 * 上一个任务实测过：当时 189 条单测没有一条加载 Spring 上下文，于是留下过一个
 * 「单测全绿但平台起不来」的提交。本任务把取值来源从 {@code @Value} 换成
 * {@link CoverageProperties}，动的正是同一处装配，所以在这里补上。
 *
 * <p>用 {@code ApplicationContextRunner} 纯内存起一个最小上下文：不开端口、不连库、
 * 不起调度，但走的是真实的 Spring 属性绑定与依赖解析。
 */
class ArtifactStoreWiringTest {

    @Configuration
    @EnableConfigurationProperties(CoverageProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class, ArtifactStoreConfig.class);

    @Test
    void 按平台配置装出产物仓库() {
        runner.withPropertyValues("coverage.artifact-root=build/x", "coverage.artifact-keep=7")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    ArtifactStore store = ctx.getBean(ArtifactStore.class);
                    // 相对路径必须在装配时就定死成绝对路径：工作目录一变就找不到产物了
                    assertTrue(store.root().isAbsolute(), store.root().toString());
                    assertEquals(Path.of("build/x").toAbsolutePath().normalize(), store.root());
                    assertEquals(7, store.keep());
                });
    }

    @Test
    void 两项都不配也装得出来() {
        // yml 里那两个键被删掉时平台仍要起得来，且默认值与原先那两个 @Value 逐字一致
        runner.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            ArtifactStore store = ctx.getBean(ArtifactStore.class);
            assertEquals(Path.of("./.artifacts").toAbsolutePath().normalize(), store.root());
            assertEquals(10, store.keep());
        });
    }
}
