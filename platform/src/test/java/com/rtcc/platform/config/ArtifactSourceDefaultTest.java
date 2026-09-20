package com.rtcc.platform.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 产物来源默认 local —— <b>现有的裸机部署与 8 实例验证链路必须一行不动</b>。
 * 默认值一旦是 uploaded，所有现存项目会立刻开始「取不到产物」而拒绝出报告。
 */
class ArtifactSourceDefaultTest {

    @Test
    void 默认走本地路径() {
        assertEquals("local", new ProjectConfig().getArtifactSource());
        assertEquals("local", new CoverageProperties().getArtifactSource());
        assertFalse(new ProjectConfig().usesUploadedArtifacts());
    }

    @Test
    void 平台配置能带进项目配置() {
        CoverageProperties p = new CoverageProperties();
        p.setArtifactSource("uploaded");

        ProjectConfig cfg = p.toProjectConfig("demo", "测试项目");
        assertEquals("uploaded", cfg.getArtifactSource());
        assertTrue(cfg.usesUploadedArtifacts());
    }

    @Test
    void 产物根与保留数留在平台级不进项目配置() {
        // 与工具链路径（go-tool / gcov-tool / llvm-*）同一条口径：产物仓库的根目录与
        // 保留数跟着部署机器走，与项目无关。一旦搬成项目级，改 yml 就不再生效
        // （项目配置的权威来源是数据库），而页面上又没有它们的入口 —— 谁也改不动。
        for (String getter : new String[]{"getArtifactRoot", "getArtifactKeep"}) {
            assertThrows(NoSuchMethodException.class,
                    () -> ProjectConfig.class.getMethod(getter),
                    getter + " 不该出现在项目配置上：产物仓库是平台级设施");
        }

        // 而平台配置上必须有：ArtifactStoreConfig 要靠这两项造 ArtifactStore bean。
        // 默认值与它原先那两个 @Value 的默认值逐字一致，换取值来源不等于换行为。
        CoverageProperties p = new CoverageProperties();
        assertEquals("./.artifacts", p.getArtifactRoot());
        assertEquals(10, p.getArtifactKeep());
    }
}
