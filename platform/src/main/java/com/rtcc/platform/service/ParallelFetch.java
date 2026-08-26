package com.rtcc.platform.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * 把「对每个被测实例各做一次阻塞 I/O」这件事并行掉。
 *
 * <p><b>为什么需要：</b>一次采集要挨个 dump 全部实例，每次 dump 都得等被测进程把覆盖数据
 * 落盘再序列化回来。串行做的话总耗时是各实例之和 —— 8 个实例实测 1.5~4s，加上最多 3s 的
 * 轮询等待，端到端染色延迟有一半的概率越过 5s 这条线（实测 6 次超 3 次）。
 * 并行之后总耗时变成「取最大」而不是「求和」。
 *
 * <p>顺带解决另一件事：这段 I/O 是在 {@code collectLock} 里做的，缩短它就缩短了锁的持有
 * 时间 —— 「结束场景」「立即采集」这类请求排在轮询后面干等的时间跟着变短。
 *
 * <p>线程池是<b>进程级</b>的：配置热替换会整体换掉 {@code ProjectRuntime}，
 * 每个运行时各建一个池的话，换一次配置就漏一个池（与 HttpClient 关不掉是同一类问题）。
 * 用 cached 池而不设上限：任务只有阻塞 I/O、互不嵌套，且同一项目的采集本就被
 * {@code collectLock} 串起来，并发度天然被实例数框住；空闲线程 60s 后自行回收。
 * 线程设为守护线程，平台退出时不必等它们。
 */
final class ParallelFetch {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "probe-fetch");
        t.setDaemon(true);
        return t;
    });

    private ParallelFetch() {
    }

    /**
     * 并行地对每个元素求值，<b>按入参顺序</b>返回结果。
     *
     * <p>顺序必须保持：界面上的实例表是按配置顺序列的，按完成先后返回会让这张表
     * 每轮都在跳，而人正是靠位置去认「第二台那个」。
     */
    static <I, O> List<O> map(List<I> items, Function<I, O> fn) {
        // 只有一个实例时绕线程池纯属浪费，还多一次上下文切换
        if (items.size() <= 1) {
            List<O> out = new ArrayList<>(items.size());
            for (I item : items) {
                out.add(fn.apply(item));
            }
            return out;
        }
        List<Future<O>> futures = new ArrayList<>(items.size());
        for (I item : items) {
            futures.add(POOL.submit(() -> fn.apply(item)));
        }
        List<O> out = new ArrayList<>(items.size());
        for (Future<O> f : futures) {
            try {
                out.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("采集被中断", e);
            } catch (ExecutionException e) {
                // 单个实例的失败在 fn 内部就被收成了结果的一部分，走到这里说明是
                // fn 自己没兜住的错（如 OOM）。吞掉的话这一轮会静默少算一台实例
                throw new IllegalStateException("采集任务异常终止：" + e.getCause(), e.getCause());
            }
        }
        return out;
    }
}
