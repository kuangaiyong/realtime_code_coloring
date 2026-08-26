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
 * 键先归到 100ms 一档：超时值可以来自 {@code /check} 的请求体，取值空间无界，
 * 不归档的话这个池本身就成了泄漏源 —— 归档之后条目数被 {@link #MAX_BUCKET} 钉死。
 */
final class SharedHttpClients {

    /** 连接超时最长按 30s 算，再长也没有意义：探针不通就是不通 */
    private static final int MAX_BUCKET = 300;

    private static final Map<Integer, HttpClient> CACHE = new ConcurrentHashMap<>();

    private SharedHttpClients() {
    }

    /** 取一个连接超时不短于 {@code timeoutMs} 的共享客户端。向上取整到 100ms 一档 */
    static HttpClient forConnectTimeout(int timeoutMs) {
        // 先钳再除：反过来写的话 timeoutMs 接近 Integer.MAX_VALUE 时 +99 会翻负，
        // 最终落到最小的那一档 —— 配了个超大超时反而只等 100ms，而且一声不吭
        int capped = Math.max(1, Math.min(MAX_BUCKET * 100, timeoutMs));
        int bucket = (capped + 99) / 100;
        return CACHE.computeIfAbsent(bucket, b -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(b * 100L))
                .build());
    }
}
