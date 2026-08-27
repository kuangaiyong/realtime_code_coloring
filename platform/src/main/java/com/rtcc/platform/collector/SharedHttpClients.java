package com.rtcc.platform.collector;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Go / C++ / Rust 三个探针客户端共用的 {@link HttpClient} 池。
 *
 * <p><b>为什么要有这么个东西：</b>Java 17 的 {@code HttpClient} <b>关不掉</b>
 * （{@code close()} 是 Java 21 才有的）。每 new 一个就多一个 selector 线程和一个线程池，
 * 只能等 GC。而本平台有两条会反复造客户端的路径：配置热替换每保存一次就整体换掉
 * {@code ProjectRuntime}（连带三个探针客户端），接入向导的「当场验」每点一下就打一次
 * {@code /api/projects/check}。不共用的话线程数只涨不落，表现是平台越跑越沉，
 * 而界面上看不出任何异样。
 *
 * <p>客户端上唯一跟项目走的配置是<b>连接超时</b>（请求整体超时是逐个请求设的，
 * 见各客户端的 {@code HttpRequest.timeout()}），所以按它做键就够了。
 * 键归到 1s 一档、最多 10 档：超时值可以来自 {@code /check} 的请求体，取值空间无界，
 * 不归档的话这个池本身就成了泄漏源。
 */
final class SharedHttpClients {

    /**
     * 归档粒度 1s、最多 10 档，也就是连接超时上限 10s。
     *
     * <p>粒度和上限都要收紧：每个 {@code HttpClient} 自带一个 selector 线程和一个执行器，
     * 而桶键来自项目配置里的 {@code timeoutMs}，取值空间大就等于把「无界泄漏」换成
     * 「上界很大的泄漏」。10s 之外没有实际意义 —— TCP 连不上探针，等再久也是连不上。
     */
    private static final int BUCKET_MS = 1000;
    private static final int MAX_BUCKET = 10;

    private static final Map<Integer, HttpClient> CACHE = new ConcurrentHashMap<>();

    private SharedHttpClients() {
    }

    /** 取一个共享客户端。连接超时向上取整到 1s 一档，上限 10s */
    static HttpClient forConnectTimeout(int timeoutMs) {
        // 先钳再除：反过来写的话 timeoutMs 接近 Integer.MAX_VALUE 时加上余数会翻负，
        // 最终落到最小的那一档 —— 配了个超大超时反而只等 1s，而且一声不吭
        int capped = Math.max(1, Math.min(MAX_BUCKET * BUCKET_MS, timeoutMs));
        int bucket = (capped + BUCKET_MS - 1) / BUCKET_MS;
        return CACHE.computeIfAbsent(bucket, b -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis((long) b * BUCKET_MS))
                .build());
    }
}
