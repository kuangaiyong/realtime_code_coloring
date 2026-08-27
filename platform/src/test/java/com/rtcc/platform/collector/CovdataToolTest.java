package com.rtcc.platform.collector;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * covdata 走独立二进制是<b>优化</b>，不是前提：环境里编不出来（没有 Go 源码、
 * 临时目录不可写、go 命令根本不在），Go 的覆盖也必须照常出得来。
 *
 * <p>这条守的是「宁可慢也不能不能用」—— 优化失败被写成硬依赖的话，
 * 表现是换台机器部署后 Go 的覆盖整个消失，而日志里只有一句编译报错。
 */
class CovdataToolTest {

    @Test
    void 找不到go时退回原来的调用形式() {
        // 用一个绝不可能存在的命令名：起子进程会直接抛 IOException
        String bogus = "definitely-not-a-real-go-binary-" + System.nanoTime();
        List<String> cmd = CovdataTool.command(bogus, "textfmt", "-i=in", "-o=out");
        assertEquals(List.of(bogus, "tool", "covdata", "textfmt", "-i=in", "-o=out"), cmd);
    }

    @Test
    void 失败的结论会被记住而不是每轮重试() {
        String bogus = "definitely-not-a-real-go-binary-" + System.nanoTime();
        CovdataTool.command(bogus, "textfmt");
        // 第二次必须直接命中缓存。不缓存的话每 3 秒一轮采集就白起一次子进程，
        // 而这条路本来就是「已经确定走不通」的
        assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> CovdataTool.command(bogus, "textfmt"),
                "失败结论没有被缓存，每轮采集都会重新去试一次");
    }

    @Test
    void 参数原样拼在后面() {
        String bogus = "definitely-not-a-real-go-binary-" + System.nanoTime();
        // 参数顺序错一位，covdata 会把 -o 当输入目录 —— 报错还算好的，
        // 更糟的是拿到一份空 profile 而退出码为 0
        assertEquals(List.of(bogus, "tool", "covdata", "textfmt", "-i=/tmp/a", "-o=/tmp/b"),
                CovdataTool.command(bogus, "textfmt", "-i=/tmp/a", "-o=/tmp/b"));
    }
}
