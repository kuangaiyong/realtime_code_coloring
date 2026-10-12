package com.rtcc.platform.service;

import com.rtcc.platform.artifact.ArtifactAuth;
import com.rtcc.platform.artifact.ArtifactStore;
import com.rtcc.platform.collector.ProbeClient;

import java.nio.file.Path;

/** 测试里造自检对象 */
final class ProjectCheckers {

    private ProjectCheckers() {
    }

    /**
     * 给 local 项目的用例用：产物仓库与令牌都用不上。
     * {@link ArtifactStore} 的构造函数不碰磁盘，所以这个目录连建都不会建
     */
    static ProjectChecker local() {
        return new ProjectChecker(new ProbeClient(),
                new ArtifactStore(Path.of("target", "artifacts-unused"), 10), new ArtifactAuth(""));
    }
}
