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
 * 通过 HTTP 抓取 Go 被测实例的覆盖数据。
 *
 * Go 编译为原生机器码，运行期没有可改写的中间表示，只能编译期插桩
 * （{@code go build -cover -covermode=atomic -tags=goverage}）。
 * 但「既有源码一行不改」仍然做得到：探针文件用 build tag 守卫、与 main 同包，
 * init() 自动执行，业务代码不需要 import 或调用任何东西。
 */
public class GoProbeClient {

    private final ProjectConfig props;
    private final HttpClient http;

    public GoProbeClient(ProjectConfig props) {
        this.props = props;
        this.http = SharedHttpClients.forConnectTimeout(props.getTimeoutMs());
    }

    /** 元数据（文件、函数、代码块位置），构建后不变 */
    public byte[] meta(ProbeEndpoint ep) throws IOException {
        return bytes(ep, "/coverage/meta");
    }

    /** 计数器快照 */
    public byte[] counters(ProbeEndpoint ep) throws IOException {
        return bytes(ep, "/coverage/counters");
    }

    /** 实例自报的构建版本，与 Java 侧的 sessionid 同一个约定 */
    public String buildId(ProbeEndpoint ep) throws IOException {
        return new String(bytes(ep, "/coverage/id"), StandardCharsets.UTF_8).trim();
    }

    /** 清零计数器。要求被测方以 -covermode=atomic 构建，否则 Go 运行时会拒绝 */
    public void clear(ProbeEndpoint ep) throws IOException {
        send(ep, "/coverage/clear", "POST");
    }

    private byte[] bytes(ProbeEndpoint ep, String path) throws IOException {
        return send(ep, path, "GET");
    }

    private byte[] send(ProbeEndpoint ep, String path, String method) throws IOException {
        URI uri = URI.create("http://" + ep.host() + ":" + ep.port() + path);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
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
            // 清零失败最常见的原因是没用 -covermode=atomic，Go 会在这里返回 500。
            // 静默放过的话，场景归因会把上一轮的覆盖算进这一轮
            throw new IOException("探针返回 " + res.statusCode() + "：" + uri + " —— "
                    + new String(res.body(), StandardCharsets.UTF_8).trim());
        }
        return res.body();
    }
}
