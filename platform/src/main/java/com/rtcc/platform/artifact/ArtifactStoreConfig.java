package com.rtcc.platform.artifact;

import com.rtcc.platform.config.CoverageProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 产物仓库是<b>平台级</b>设施：它的根目录与保留数跟着部署机器走，与项目无关 ——
 * 与 go-tool / gcov-tool 那几项工具链路径同一个道理，所以读 yml 而不读库。
 *
 * <p><b>为什么这个类跟着上传接口一起进来</b>：{@link ArtifactStore} 没有无参构造，
 * 组件扫描扫不出它。上传接口一旦存在，缺了这个 bean 整个平台就起不来
 * （{@code Parameter 0 of constructor ... required a bean of type ArtifactStore}），
 * 而单测一条都发现不了 —— 现有 189 条没有一条加载 Spring 上下文。
 * 计划把它排在下一个任务，那样中间会留下一个「单测全绿但平台起不来」的提交。
 */
@Configuration
public class ArtifactStoreConfig {

    @Bean
    public ArtifactStore artifactStore(CoverageProperties props) {
        String root = props.getArtifactRoot();
        // 空字符串不会回落到默认值：环境变量设了但为空（COVERAGE_ARTIFACT_ROOT=）时，
        // Spring 认为这个属性<b>有值</b>，于是 Path.of("") 经 toAbsolutePath()
        // 变成平台的<b>工作目录</b> —— 产物会散进仓库，四个被测源码根随之变脏，
        // 实例自报 sessionid 带上 -dirty，增量口径整个不可用。
        // 一个「环境变量少填了个值」引发的故障，表现在一个毫不相干的地方
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException(
                    "coverage.artifact-root 不能为空（检查 COVERAGE_ARTIFACT_ROOT 是不是设了但没给值）"
                            + "：空值会让产物落进平台的工作目录");
        }
        return new ArtifactStore(Path.of(root).toAbsolutePath().normalize(),
                props.getArtifactKeep());
    }
}
