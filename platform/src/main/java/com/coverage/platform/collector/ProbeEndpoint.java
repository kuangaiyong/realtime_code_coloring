package com.coverage.platform.collector;

/**
 * 一个被测实例的探针地址，形如 {@code [语言://]host:port}。
 *
 * 语言决定用哪个采集器与哪个归一化适配器；不写时默认 java，
 * 保持与只有 Java 时的配置兼容。
 */
public record ProbeEndpoint(String language, String host, int port) {

    public static final String JAVA = "java";
    public static final String GO = "go";
    public static final String CPP = "cpp";
    public static final String RUST = "rust";

    public static ProbeEndpoint parse(String spec) {
        String s = spec == null ? "" : spec.trim();
        String lang = JAVA;
        int scheme = s.indexOf("://");
        if (scheme > 0) {
            lang = s.substring(0, scheme).toLowerCase();
            s = s.substring(scheme + 3).trim();
            if (!JAVA.equals(lang) && !GO.equals(lang) && !CPP.equals(lang) && !RUST.equals(lang)) {
                throw new IllegalArgumentException("不支持的被测语言：" + lang + "（目前支持 java、go、cpp、rust）");
            }
        }
        int i = s.lastIndexOf(':');
        if (i <= 0 || i == s.length() - 1) {
            throw new IllegalArgumentException("探针地址应形如 [语言://]host:port，实际为：" + spec);
        }
        int port;
        try {
            port = Integer.parseInt(s.substring(i + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("探针端口不是数字：" + spec);
        }
        return new ProbeEndpoint(lang, s.substring(0, i).trim(), port);
    }

    /** 同时作为界面与日志里这个实例的名字 */
    @Override
    public String toString() {
        return language + "://" + host + ":" + port;
    }
}
