package com.coverage.platform.collector;

/** 一个被测实例的探针地址 */
public record ProbeEndpoint(String host, int port) {

    public static ProbeEndpoint parse(String spec) {
        String s = spec == null ? "" : spec.trim();
        int i = s.lastIndexOf(':');
        if (i <= 0 || i == s.length() - 1) {
            throw new IllegalArgumentException("探针地址应形如 host:port，实际为：" + spec);
        }
        int port;
        try {
            port = Integer.parseInt(s.substring(i + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("探针端口不是数字：" + spec);
        }
        return new ProbeEndpoint(s.substring(0, i).trim(), port);
    }

    /** 同时作为界面与日志里这个实例的名字 */
    @Override
    public String toString() {
        return host + ":" + port;
    }
}
