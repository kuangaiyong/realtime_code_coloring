package com.rtcc.platform.collector;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 通过 JaCoCo 的 tcpserver 通道远程抓取执行数据。
 *
 * 被测 JVM 只需带 output=tcpserver 启动，全程无需停机，源码与产物均不改动。
 */
@Component
public class ProbeClient {

    /** 撞上连接收尾窗口后等多久再试。窗口以毫秒计，等这一下足够，多了只会在真故障时拖慢每一轮 */
    private static final int RETRY_DELAY_MS = 100;

    /**
     * @param reset 抓取后是否清零计数器；清零后下一次抓到的就是「这段时间内新增的覆盖」
     */
    public ProbeDump dump(String host, int port, boolean reset, int timeoutMs) throws IOException {
        try (Socket socket = connectWithRetry(host, port, timeoutMs)) {
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

    /**
     * 连接探针，<b>被拒绝时重试一次</b>。
     *
     * <p>JaCoCo tcpserver 的 accept backlog 只有 1：被测 JVM 繁忙、上一次 dump 的连接
     * 尚未收尾时，这一次的 SYN 会被内核 RST。平台侧看到的是 Connection refused，
     * 而那台实例其实是健康的 —— 直接判掉线会把人引去查一台没问题的机器，
     * 3 秒一轮的采集撞上时还会把整台标成 PARTIAL。
     *
     * <p><b>只重试连接被拒，不重试超时</b>：超时是实例真掉线的信号，
     * 对它重试会让单轮采集最坏耗时从 8×3s=24s 翻倍，威胁端到端延迟口径。
     * 两者天然分得开 —— SocketTimeoutException 继承自 InterruptedIOException，
     * 不是 ConnectException 的子类，不会被这里的 catch 捕获。
     */
    private static Socket connectWithRetry(String host, int port, int timeoutMs) throws IOException {
        try {
            return connectOnce(host, port, timeoutMs);
        } catch (ConnectException first) {
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("连接探针时被中断：" + host + ":" + port, e);
            }
            try {
                return connectOnce(host, port, timeoutMs);
            } catch (ConnectException second) {
                // 「重试过仍连不上」与「一次都没试成」在排查时是两件事，日志里要分得开
                ConnectException e = new ConnectException(
                        second.getMessage() + "（已重试 1 次）：" + host + ":" + port);
                e.initCause(second);
                throw e;
            }
        }
    }

    /** 连接失败也要把 socket 关掉，否则重试会漏掉一个文件描述符 */
    private static Socket connectOnce(String host, int port, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return socket;
        } catch (IOException e) {
            // close 自己抛异常时不能盖掉连接失败的原因 —— 盖掉之后 connectWithRetry 的
            // catch(ConnectException) 就认不出这是「被拒绝」，重试会静默失效
            try {
                socket.close();
            } catch (IOException closeFailed) {
                e.addSuppressed(closeFailed);
            }
            throw e;
        }
    }
}
