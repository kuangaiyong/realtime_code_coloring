package com.coverage.platform.collector;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.runtime.IRemoteCommandVisitor;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用一个模拟的 JaCoCo tcpserver 端点验证远程 dump 协议：
 * 握手能完成、dump 命令能送达、执行数据能取回、reset 标志能透传、
 * 探针不可达时抛异常而非静默返回空数据。
 *
 * 注意本测试的边界：模拟端与真实 agent 一样先构造 Writer，握手头会立即发出，
 * 因此客户端两种构造顺序都能连通 —— 这条测试并不能守住客户端的构造顺序。
 * 要覆盖互等场景，需要一个「先构造 Reader」的模拟端，目前没有这个必要。
 */
class ProbeClientTest {

    private static final long CLASS_ID = 0x1234abcdL;
    private static final String CLASS_NAME = "com/example/Sample";
    /** 真实 agent 由 sessionid 启动参数决定这个值，此处模拟被测方按约定填了构建 commit */
    private static final String SESSION_ID = "0123456789abcdef0123456789abcdef01234567";

    private ServerSocket serverSocket;
    private Thread serverThread;
    private final AtomicBoolean resetRequested = new AtomicBoolean(false);
    private final CountDownLatch commandReceived = new CountDownLatch(1);

    @BeforeEach
    void startFakeAgent() throws IOException {
        serverSocket = new ServerSocket(0);
        serverThread = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                // agent 侧同样是先 Writer 后 Reader，两端都先发出握手头才不会互等
                RemoteControlWriter writer = new RemoteControlWriter(socket.getOutputStream());
                RemoteControlReader reader = new RemoteControlReader(socket.getInputStream());

                reader.setRemoteCommandVisitor(new IRemoteCommandVisitor() {
                    @Override
                    public void visitDumpCommand(boolean dump, boolean reset) throws IOException {
                        resetRequested.set(reset);
                        if (dump) {
                            long now = System.currentTimeMillis();
                            writer.visitSessionInfo(new SessionInfo(SESSION_ID, now - 1000, now));
                            boolean[] probes = new boolean[]{true, false, true};
                            writer.visitClassExecution(new ExecutionData(CLASS_ID, CLASS_NAME, probes));
                        }
                        writer.sendCmdOk();
                        commandReceived.countDown();
                    }
                });
                while (reader.read()) {
                    // 读到连接结束
                }
            } catch (IOException ignored) {
                // 连接关闭属正常收尾
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @AfterEach
    void stop() throws IOException {
        serverSocket.close();
    }

    @Test
    void 能从探针取回执行数据() throws Exception {
        ProbeClient client = new ProbeClient();

        ProbeDump result = client.dump("localhost", serverSocket.getLocalPort(), false, 5000);

        assertTrue(commandReceived.await(5, TimeUnit.SECONDS), "探针未收到 dump 命令");
        ExecutionData data = result.exec().get(CLASS_ID);
        assertNotNull(data, "未取回类的执行数据");
        assertEquals(CLASS_NAME, data.getName());
        assertArrayEquals(new boolean[]{true, false, true}, data.getProbes());
        assertFalse(resetRequested.get(), "未要求清零时不应发送 reset");
    }

    /** 会话信息是平台唯一的 buildCommit 来源，丢了它增量口径就无从校验 */
    @Test
    void 会话信息随执行数据一并取回() throws Exception {
        ProbeClient client = new ProbeClient();

        ProbeDump result = client.dump("localhost", serverSocket.getLocalPort(), false, 5000);

        assertTrue(commandReceived.await(5, TimeUnit.SECONDS));
        assertEquals(1, result.sessions().size(), "未取回会话信息");
        assertEquals(SESSION_ID, result.sessions().get(0).getId());
    }

    @Test
    void 清零标志会传递给探针() throws Exception {
        ProbeClient client = new ProbeClient();

        client.dump("localhost", serverSocket.getLocalPort(), true, 5000);

        assertTrue(commandReceived.await(5, TimeUnit.SECONDS));
        assertTrue(resetRequested.get(), "reset=true 应透传到探针");
    }

    @Test
    void 探针不可达时抛出异常而非静默返回空数据() {
        ProbeClient client = new ProbeClient();

        // 端口 1 上不会有探针在监听
        assertThrows(IOException.class, () -> client.dump("localhost", 1, false, 1000));
    }
}
