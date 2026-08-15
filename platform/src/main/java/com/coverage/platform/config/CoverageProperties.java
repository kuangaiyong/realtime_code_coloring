package com.coverage.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "coverage")
public class CoverageProperties {

    /**
     * 被测实例的探针地址，形如 [语言://]host:port，可配多个（不写语言默认 java）。
     *
     * 同一服务的多个实例各自持有一份计数器，只有把它们并起来才是这个服务的真实覆盖：
     * 负载均衡会把请求分到任意一个实例，只看其中一个必然少算。
     */
    private List<String> instances = new ArrayList<>(List.of("localhost:6300"));

    /** 源码所在的 git 仓库根目录。IR 里的文件路径一律以它为基准 */
    private String repoDir = "..";
    /** 增量覆盖率的默认对比基线 */
    private String baseline = "HEAD~1";

    /** Java：被测产物的 class 目录，与源码版本必须一致 */
    private String classesDir;
    /** Java：源码根相对仓库根的位置，如 demo-service/src/main/java */
    private String javaSourceRoot;

    /** Go：模块目录相对仓库根的位置，如 demo-service-go */
    private String goSourceRoot;
    /** Go：模块的 import path，覆盖数据里的文件名以它为前缀，需剥掉后换成仓库相对路径 */
    private String goModulePath;
    /** Go：go 可执行文件。归一化要调 `go tool covdata`，二进制格式没有稳定契约可自行解析 */
    private String goTool = "go";
    /** Go：不参与统计的文件后缀，默认排除探针自身 —— 它测的是自己，不是被测代码 */
    private List<String> goExclude = new ArrayList<>(List.of("coverage_agent.go"));

    private long intervalMs = 3000;
    private int timeoutMs = 3000;

    /** 各语言的源码根，用于界定 git diff 的范围 */
    public List<String> getSourceRoots() {
        List<String> roots = new ArrayList<>();
        if (javaSourceRoot != null && !javaSourceRoot.isBlank()) {
            roots.add(javaSourceRoot);
        }
        if (goSourceRoot != null && !goSourceRoot.isBlank()) {
            roots.add(goSourceRoot);
        }
        return roots;
    }

    public List<String> getInstances() { return instances; }
    public void setInstances(List<String> instances) { this.instances = instances; }

    public String getClassesDir() { return classesDir; }
    public void setClassesDir(String classesDir) { this.classesDir = classesDir; }

    public String getJavaSourceRoot() { return javaSourceRoot; }
    public void setJavaSourceRoot(String javaSourceRoot) { this.javaSourceRoot = javaSourceRoot; }

    public String getGoSourceRoot() { return goSourceRoot; }
    public void setGoSourceRoot(String goSourceRoot) { this.goSourceRoot = goSourceRoot; }

    public String getGoModulePath() { return goModulePath; }
    public void setGoModulePath(String goModulePath) { this.goModulePath = goModulePath; }

    public String getGoTool() { return goTool; }
    public void setGoTool(String goTool) { this.goTool = goTool; }

    public List<String> getGoExclude() { return goExclude; }
    public void setGoExclude(List<String> goExclude) { this.goExclude = goExclude; }

    public String getRepoDir() { return repoDir; }
    public void setRepoDir(String repoDir) { this.repoDir = repoDir; }

    public String getBaseline() { return baseline; }
    public void setBaseline(String baseline) { this.baseline = baseline; }

    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
