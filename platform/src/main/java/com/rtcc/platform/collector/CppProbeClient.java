package com.rtcc.platform.collector;

import com.rtcc.platform.config.CoverageProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 通过 HTTP 抓取 C++ 被测实例的覆盖数据。
 *
 * C++ 与 Go 一样编译为原生机器码，只能编译期插桩（{@code g++ --coverage}）。
 * 「既有源码一行不改」同样做得到：探针是独立编译单元，靠全局对象的构造函数
 * 自动启动，业务代码不 include 也不调用任何东西。
 *
 * 与 Go 的区别在于 gcov 的运行期 API 把数据写成**磁盘上的 .gcda 文件**，
 * 而不是交给一个 writer。所以探针要先落盘，再把文件读出来带上文件名交回，
 * 一个 C++ 服务通常有多个编译单元，就有多份 .gcda。
 */
@Component
public class CppProbeClient {

    private final CoverageProperties props;
    private final HttpClient http;

    public CppProbeClient(CoverageProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .build();
    }

    /** 实例自报的构建版本，与 Java 侧的 sessionid 同一个约定 */
    public String buildId(ProbeEndpoint ep) throws IOException {
        return new String(send(ep, "/coverage/id", "GET"), StandardCharsets.UTF_8).trim();
    }

    /** 落盘并取回全部 .gcda，格式见 {@link CppCoverageAnalyzer} */
    public byte[] dump(ProbeEndpoint ep) throws IOException {
        return send(ep, "/coverage/dump", "GET");
    }

    /** 清零计数器：__gcov_reset() 之外还要删掉 .gcda，否则合并语义会把旧数据带回来 */
    public void clear(ProbeEndpoint ep) throws IOException {
        send(ep, "/coverage/clear", "POST");
    }

    private byte[] send(ProbeEndpoint ep, String path, String method) throws IOException {
        URI uri = URI.create("http://" + ep.host() + ":" + ep.port() + path);
        HttpRequest req = HttpRequest.newBuilder(uri)
                // dump 要读磁盘上的多份 .gcda，比取一个计数器慢，给它宽一点的读超时
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
