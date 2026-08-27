package com.rtcc.platform.collector;

import com.rtcc.platform.config.ProjectConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 通过 HTTP 抓取 Rust 被测实例的覆盖数据。
 *
 * Rust 与 Go / C++ 一样只能编译期插桩（{@code -C instrument-coverage}）。
 * 「既有源码一行不改」同样做得到，而且连 Cargo.toml 都不用动：探针是单独编译的
 * .o，构建时经 {@code -C link-arg} 注入，靠 .CRT$XCU 段的函数指针在 main 之前自动执行。
 *
 * 交回的是一份 .profraw（LLVM 的原始计数器快照），一个进程一份，
 * 比 C++ 的多份 .gcda 简单。
 */
public class RustProbeClient {

    private final ProjectConfig props;
    private final HttpClient http;

    public RustProbeClient(ProjectConfig props) {
        this.props = props;
        this.http = SharedHttpClients.forConnectTimeout(props.getTimeoutMs());
    }

    /** 实例自报的构建版本，与 Java 侧的 sessionid 同一个约定 */
    public String buildId(ProbeEndpoint ep) throws IOException {
        return new String(send(ep, "/coverage/id", "GET"), StandardCharsets.UTF_8).trim();
    }

    /** 落盘并取回 .profraw */
    public byte[] dump(ProbeEndpoint ep) throws IOException {
        return send(ep, "/coverage/dump", "GET");
    }

    /** 清零计数器：__llvm_profile_reset_counters() 之外还要删掉 .profraw，写入是合并语义 */
    public void clear(ProbeEndpoint ep) throws IOException {
        send(ep, "/coverage/clear", "POST");
    }

    private byte[] send(ProbeEndpoint ep, String path, String method) throws IOException {
        URI uri = URI.create("http://" + ep.host() + ":" + ep.port() + path);
        HttpRequest req = HttpRequest.newBuilder(uri)
                // dump 要落盘再读回来，比取一个计数器慢，给它宽一点的读超时
                .timeout(Duration.ofMillis(Math.max(props.getTimeoutMs(), 5000)))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<byte[]> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("抓取被中断：" + uri, e);
        }
        if (res.statusCode() != 200) {
            // 探针宁可报错也不交半份数据：交出去平台会当成「这些代码没被跑过」照常出报告
            throw new IOException("探针返回 " + res.statusCode() + "：" + uri + " —— "
                    + new String(res.body(), StandardCharsets.UTF_8).trim());
        }
        return res.body();
    }
}
