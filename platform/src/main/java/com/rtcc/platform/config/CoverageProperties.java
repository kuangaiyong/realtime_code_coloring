package com.rtcc.platform.config;

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

    /** C++：源码根相对仓库根的位置。也是 gcov 的工作目录 —— .gcno 里记的是编译时的相对源码名 */
    private String cppSourceRoot;
    /** C++：对象文件目录，.gcno 在这里。它是编译期产物，与探针交回的 .gcda 配套才解得出行号 */
    private String cppObjectsDir;
    /** C++：gcov 可执行文件。二进制格式没有稳定契约可自行解析，与 Go 调 covdata 同理 */
    private String gcovTool = "gcov";
    /** C++：多实例在 .gcda 层面合并所用的工具 */
    private String gcovMergeTool = "gcov-tool";

    /** Rust：源码根相对仓库根的位置，如 demo-service-rust */
    private String rustSourceRoot;
    /** Rust：被测产物。行号信息在它的 coverage mapping 里，相当于 Java 的 classes-dir */
    private String rustBinary;
    /** Rust：合并多实例 .profraw 并转成 profdata 的工具，版本须与 rustc 匹配 */
    private String llvmProfdataTool = "llvm-profdata";
    /** Rust：把 profdata 导成 LCOV 的工具 */
    private String llvmCovTool = "llvm-cov";

    /** 覆盖率门禁的阈值。CI 在合并前调 /api/coverage/gate，据此决定放行还是阻断 */
    private Gate gate = new Gate();

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
        if (cppSourceRoot != null && !cppSourceRoot.isBlank()) {
            roots.add(cppSourceRoot);
        }
        if (rustSourceRoot != null && !rustSourceRoot.isBlank()) {
            roots.add(rustSourceRoot);
        }
        return roots;
    }

    public Gate getGate() { return gate; }
    public void setGate(Gate gate) { this.gate = gate; }

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

    public String getCppSourceRoot() { return cppSourceRoot; }
    public void setCppSourceRoot(String cppSourceRoot) { this.cppSourceRoot = cppSourceRoot; }

    public String getCppObjectsDir() { return cppObjectsDir; }
    public void setCppObjectsDir(String cppObjectsDir) { this.cppObjectsDir = cppObjectsDir; }

    public String getGcovTool() { return gcovTool; }
    public void setGcovTool(String gcovTool) { this.gcovTool = gcovTool; }

    public String getGcovMergeTool() { return gcovMergeTool; }
    public void setGcovMergeTool(String gcovMergeTool) { this.gcovMergeTool = gcovMergeTool; }

    public String getRustSourceRoot() { return rustSourceRoot; }
    public void setRustSourceRoot(String rustSourceRoot) { this.rustSourceRoot = rustSourceRoot; }

    public String getRustBinary() { return rustBinary; }
    public void setRustBinary(String rustBinary) { this.rustBinary = rustBinary; }

    public String getLlvmProfdataTool() { return llvmProfdataTool; }
    public void setLlvmProfdataTool(String llvmProfdataTool) { this.llvmProfdataTool = llvmProfdataTool; }

    public String getLlvmCovTool() { return llvmCovTool; }
    public void setLlvmCovTool(String llvmCovTool) { this.llvmCovTool = llvmCovTool; }

    public String getRepoDir() { return repoDir; }
    public void setRepoDir(String repoDir) { this.repoDir = repoDir; }

    public String getBaseline() { return baseline; }
    public void setBaseline(String baseline) { this.baseline = baseline; }

    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    /**
     * 门禁阈值。只有两个数字，不做原型上那套多规则 + 优先级 —— 一个平台实例盯的就是
     * 一个服务，「哪条规则优先」在这里没有对应的现实。
     */
    public static class Gate {
        /** 增量行覆盖率下限（%）。这次改动的代码测没测到，是门禁最主要的用途 */
        private double incrementalThreshold = 80d;
        /** 全量行覆盖率下限（%）。0 表示不设门槛 —— 存量代码的覆盖率一时提不上来是常态 */
        private double overallThreshold = 0d;

        public double getIncrementalThreshold() { return incrementalThreshold; }
        public void setIncrementalThreshold(double incrementalThreshold) { this.incrementalThreshold = incrementalThreshold; }

        public double getOverallThreshold() { return overallThreshold; }
        public void setOverallThreshold(double overallThreshold) { this.overallThreshold = overallThreshold; }
    }
}
