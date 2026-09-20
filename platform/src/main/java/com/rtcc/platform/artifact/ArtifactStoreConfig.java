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
        return new ArtifactStore(Path.of(props.getArtifactRoot()).toAbsolutePath().normalize(),
                props.getArtifactKeep());
    }
}
