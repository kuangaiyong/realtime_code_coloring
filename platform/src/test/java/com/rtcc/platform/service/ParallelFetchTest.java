package com.rtcc.platform.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集时对每个被测实例各做一次阻塞 I/O，串行做的总耗时是各实例之和 ——
 * 8 个实例下端到端染色延迟有一半概率越过 5s 这条核心断言线（实测 6 次超 3 次）。
 * 这里守住并行化的两个前提：真的并行，以及<b>结果顺序不变</b>。
 */
class ParallelFetchTest {

    @Test
    void 结果按入参顺序返回() {
        // 顺序不能按完成先后：界面上的实例表是按配置顺序列的，
        // 每轮都在跳的话，人靠位置认「第二台那个」就认不住了
        List<Integer> delays = List.of(300, 10, 200, 5, 100);
        List<String> out = ParallelFetch.map(delays, d -> {
            sleep(d);
            return "t" + d;
        });
        assertEquals(List.of("t300", "t10", "t200", "t5", "t100"), out);
    }

    @Test
    void 真的并行而不是挨个来() {
        long t0 = System.nanoTime();
        List<String> out = ParallelFetch.map(List.of(1, 2, 3, 4, 5, 6, 7, 8), i -> {
            sleep(200);
            return "ok" + i;
        });
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(8, out.size());
        // 串行是 1600ms。放宽到 800ms 是给线程池首次拉起线程留余量，
        // 但仍远小于串行 —— 退回串行的话这条会立刻失败
        assertTrue(ms < 800, "8 个 200ms 的任务用了 " + ms + "ms，看起来没有并行");
    }

    @Test
    void 单个元素不绕线程池也要给出结果() {
        assertEquals(List.of("only"), ParallelFetch.map(List.of(1), i -> "only"));
        assertEquals(List.of(), ParallelFetch.map(List.of(), i -> "never"));
    }

    @Test
    void 任务抛异常时整批失败而不是静默少算一个() {
        // 单个实例不可达是在 fn 内部收成结果的一部分的；走到这里说明是 fn 自己
        // 没兜住的错。吞掉它，这一轮就会安静地少算一台实例的覆盖
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ParallelFetch.map(List.of(1, 2, 3), i -> {
                    if (i == 2) {
                        throw new IllegalArgumentException("炸了");
                    }
                    return "ok";
                }));
        assertTrue(String.valueOf(e.getMessage()).contains("炸了"), e.getMessage());
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
