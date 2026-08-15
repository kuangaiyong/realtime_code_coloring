package com.coverage.platform.collector;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 通过 JaCoCo 的 tcpserver 通道远程抓取执行数据。
 *
 * 被测 JVM 只需带 output=tcpserver 启动，全程无需停机，源码与产物均不改动。
 */
@Component
public class ProbeClient {

    /**
     * @param reset 抓取后是否清零计数器；清零后下一次抓到的就是「这段时间内新增的覆盖」
     */
    public ProbeDump dump(String host, int port, boolean reset, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            // RemoteControlWriter 构造时写出握手头，RemoteControlReader 构造时阻塞读取对端握手头。
            // 先构造 Writer：己方的头先发出，无论对端按什么顺序构造都不会互等。
            // （JaCoCo agent 侧同样是先 Writer 后 Reader，故实测两种顺序都能连通；
            //  这里固定为先 Writer，是为了不依赖对端实现细节。）
            RemoteControlWriter writer = new RemoteControlWriter(socket.getOutputStream());
            RemoteControlReader reader = new RemoteControlReader(socket.getInputStream());

            ExecutionDataStore execStore = new ExecutionDataStore();
            SessionInfoStore sessionStore = new SessionInfoStore();
            reader.setExecutionDataVisitor(execStore);
            // 会话 ID 由被测方的 sessionid 启动参数指定，是平台唯一能拿到的「实例自报版本」
            reader.setSessionInfoVisitor(sessionStore);

            writer.visitDumpCommand(true, reset);

            // read() 返回 false 表示对端在发送完毕前关闭了连接
            if (!reader.read()) {
                throw new IOException("探针在返回执行数据前关闭了连接");
            }
            return new ProbeDump(execStore, sessionStore.getInfos());
        }
    }
}
