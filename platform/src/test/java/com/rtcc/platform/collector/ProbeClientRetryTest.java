package com.rtcc.platform.collector;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.runtime.IRemoteCommandVisitor;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JaCoCo tcpserver 的 accept backlog 只有 1：被测 JVM 繁忙、上一次 dump 的连接
 * 尚未收尾时，下一次 dump 的 SYN 被内核 RST，平台侧表现为 Connection refused。
 *
 * <p>实测负载下每约 160 次探针连接命中 1 次，而它会把一台<b>健康的</b>实例
 * 误报成掉线 —— 3 秒一轮的采集撞上时整台标 PARTIAL，把人引去查一台没问题的机器。
 * 这与本项目「宁可拒绝出报告，也不出一份静默错误的报告」是同一类错误。
 *
 * <p>用 {@code new ServerSocket(0, 1)} 复现真实 agent 的行为：把 backlog 占住，
 * 之后的连接就会被内核拒绝。全程真实 socket、真实 JaCoCo 协议，无 mock。
 */
class ProbeClientRetryTest {

    private static final String SESSION_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final long CLASS_ID = 0x1234abcdL;

    @Test
    void 首次连接被拒后重试成功() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1);
             Socket squatter = new Socket()) {
            int port = server.getLocalPort();
            // 占住 backlog：连上但不让服务端 accept，此后的连接会被 RST
            squatter.connect(new InetSocketAddress("127.0.0.1", port), 2000);

            Thread agent = new Thread(() -> {
                try {
                    // 先让 ProbeClient 的第一次连接确实撞上拒绝，再腾出队列
                    Thread.sleep(50);
                    server.accept().close();            // 收掉占位的那个
                    try (Socket s = server.accept()) {  // 这次是 ProbeClient 的重试
                        serveOneDump(s);
                    }
                } catch (Exception ignored) {
                    // 连接收尾异常不该让测试线程噪声化，断言由主线程负责
                }
            });
            agent.setDaemon(true);
            agent.start();

            ProbeDump dump = new ProbeClient().dump("127.0.0.1", port, false, 2000);

            assertNotNull(dump, "重试应当拿到数据，而不是把健康实例判成掉线");
            assertEquals(SESSION_ID, dump.sessions().get(0).getId());
        }
    }

    @Test
    void 重试后仍被拒时错误信息注明已重试() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1);
             Socket squatter = new Socket()) {
            int port = server.getLocalPort();
            squatter.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            // 全程不 accept，两次连接都会被拒

            IOException e = assertThrows(IOException.class,
                    () -> new ProbeClient().dump("127.0.0.1", port, false, 2000));
            // 「重试过仍连不上」与「一次都没试成」在排查时是两件事，日志里必须分得开
            assertTrue(e.getMessage().contains("重试"),
                    "错误信息应注明已重试，实际为：" + e.getMessage());
        }
    }

    /**
     * 超时不重试 —— 这条守的是端到端延迟口径。
     *
     * <p>对超时也重试的话，单轮采集最坏耗时从 8×3s=24s 翻倍到 48s，
     * 还会顶破 E2E 统一的 60s 客户端超时。用耗时判断是因为两种情况抛的是同一个异常类型。
     *
     * <p>地址取 RFC 5737 保留的测试网段 192.0.2.0/24，不会有主机响应。
     * 若本机网络对它立即返回不可达（而非静默丢包），这条会以耗时过短的形式失败 ——
     * 那说明它没在测想测的东西，要换一个真正会超时的地址，不要放宽断言。
     */
    @Test
    void 连接超时不触发重试() {
        long t0 = System.currentTimeMillis();
        assertThrows(IOException.class,
                () -> new ProbeClient().dump("192.0.2.1", 6300, false, 1000));
        long took = System.currentTimeMillis() - t0;
        assertTrue(took < 1800,
                "超时不应重试（重试会到约 2000ms），实际耗时 " + took + "ms");
    }

    /** 模拟 agent 侧：与真实 agent 一样先构造 Writer，两端都先发握手头才不会互等 */
    private static void serveOneDump(Socket socket) throws IOException {
        RemoteControlWriter writer = new RemoteControlWriter(socket.getOutputStream());
        RemoteControlReader reader = new RemoteControlReader(socket.getInputStream());
        reader.setRemoteCommandVisitor(new IRemoteCommandVisitor() {
            @Override
            public void visitDumpCommand(boolean dump, boolean reset) throws IOException {
                if (dump) {
                    long now = System.currentTimeMillis();
                    writer.visitSessionInfo(new SessionInfo(SESSION_ID, now - 1000, now));
                    writer.visitClassExecution(new ExecutionData(
                            CLASS_ID, "com/example/Sample", new boolean[]{true, false, true}));
                }
                writer.sendCmdOk();
            }
        });
        while (reader.read()) {
            // 读到连接结束
        }
    }
}
