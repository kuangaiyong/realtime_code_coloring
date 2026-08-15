package com.coverage.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "coverage")
public class CoverageProperties {

    /** 被测服务的探针地址 */
    private String host = "localhost";
    private int port = 6300;

    /** 被测服务的产物目录与源码目录，三者版本必须一致 */
    private String classesDir;
    private String sourceDir;

    /** 源码所在的 git 仓库根目录，增量口径由它计算 */
    private String repoDir = "..";
    /** 增量覆盖率的默认对比基线 */
    private String baseline = "HEAD~1";

    private long intervalMs = 3000;
    private int timeoutMs = 3000;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getClassesDir() { return classesDir; }
    public void setClassesDir(String classesDir) { this.classesDir = classesDir; }

    public String getSourceDir() { return sourceDir; }
    public void setSourceDir(String sourceDir) { this.sourceDir = sourceDir; }

    public String getRepoDir() { return repoDir; }
    public void setRepoDir(String repoDir) { this.repoDir = repoDir; }

    public String getBaseline() { return baseline; }
    public void setBaseline(String baseline) { this.baseline = baseline; }

    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
