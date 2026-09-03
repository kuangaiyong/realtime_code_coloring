package com.rtcc.platform.artifact;

/**
 * 平台需要落盘保存的产物种类。
 *
 * <p><b>没有 GO</b>：Go 的 meta / counters 全从探针的网络接口来，是自包含的，
 * 平台不需要它的任何编译产物 —— 这也是四种语言里 Go 最容易容器化的原因。
 */
public enum ArtifactKind {

    /** Java：.class 目录树。JaCoCo 要靠字节码把探针 id 还原成行号 */
    JAVA("java"),
    /** C++：.gcno。编译期产物，记的是相对源码名 */
    CPP("cpp"),
    /** Rust：产物本身，行号在它自带的 coverage mapping 里 */
    RUST("rust");

    private final String dir;

    ArtifactKind(String dir) {
        this.dir = dir;
    }

    /** 落盘时的子目录名 */
    public String dir() {
        return dir;
    }

    public static ArtifactKind of(String s) {
        for (ArtifactKind k : values()) {
            if (k.dir.equalsIgnoreCase(s)) {
                return k;
            }
        }
        // 说清可用的是哪些 —— 只报「不认识」的话，调用方只能去翻代码。
        // 特别是 go：它不是漏了，是压根不需要产物
        throw new IllegalArgumentException("不认识的产物类型：" + s
                + "，可用的是 java / cpp / rust（Go 不需要产物，它的覆盖数据是自包含的）");
    }
}
