package com.rtcc.platform.artifact;

import java.io.IOException;

/**
 * 上传的包本身有问题：不是 zip、空包、只有目录没有文件、条目指向目标目录之外。
 *
 * <p><b>为什么要与平台侧的 IO 故障分开</b>：磁盘满、没有写权限、目录改名一直被占用
 * 也都是 {@link IOException}，但它们是<b>平台这边</b>出了问题。两者若共用一个状态码，
 * CI 上传失败时排查方向会被引到错误的一侧 —— 包明明是好的，却让人反复去查包；
 * 或者平台磁盘满了，却让人以为是构建产物不对。
 *
 * <p>口径与平台既有的 {@code ProjectOperationException} 一致：
 * 「你填错了」（4xx）和「平台自己的依赖挂了」（5xx）分开报。
 */
public class BadArtifactException extends IOException {

    public BadArtifactException(String message) {
        super(message);
    }
}
