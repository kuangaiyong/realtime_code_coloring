package com.rtcc.platform.service;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 推送通道按项目分发的前提：从连接地址上认出这个会话订阅的是哪个项目。
 * 认错了就是串台 —— 页面拿着别的项目的数据重绘，界面上看不出异常。
 */
class CoveragePublisherTest {

    @Test
    void 不带参数的旧地址归入默认项目() {
        assertEquals("default", CoveragePublisher.projectOf(URI.create("ws://localhost:18090/ws/coverage")));
        assertEquals("default", CoveragePublisher.projectOf(null));
    }

    @Test
    void 带项目参数时按参数走() {
        assertEquals("order-svc",
                CoveragePublisher.projectOf(URI.create("ws://localhost:18090/ws/coverage?project=order-svc")));
    }

    @Test
    void 项目参数可以不在第一个位置() {
        assertEquals("order-svc",
                CoveragePublisher.projectOf(URI.create("ws://localhost:18090/ws/coverage?t=1&project=order-svc")));
    }

    @Test
    void 前缀相同的其它参数不能被当成项目() {
        // projectId=... 不是 project=...，认错了会把这个会话订阅到一个不存在的项目上，
        // 表现为「页面连上了却永远收不到推送」
        assertEquals("default",
                CoveragePublisher.projectOf(URI.create("ws://localhost:18090/ws/coverage?projectId=order-svc")));
    }

    @Test
    void 空的项目参数按默认项目算() {
        assertEquals("default",
                CoveragePublisher.projectOf(URI.create("ws://localhost:18090/ws/coverage?project=")));
    }

    @Test
    void 项目参数按URL解码() {
        assertEquals("订单服务",
                CoveragePublisher.projectOf(URI.create(
                        "ws://localhost:18090/ws/coverage?project=%E8%AE%A2%E5%8D%95%E6%9C%8D%E5%8A%A1")));
    }

    @Test
    void 只解码一次() {
        // 取 getQuery() 而不是 getRawQuery() 的话，这里会被解成 "a b"（%2B 先变 +、+ 再变空格），
        // 会话就订阅到一个不存在的项目上。中文那个用例查不出这个错 ——
        // 解码后的中文串再解一次是不动点
        assertEquals("a+b",
                CoveragePublisher.projectOf(URI.create("ws://localhost:18090/ws/coverage?project=a%2Bb")));
        // 同理，%26 若被提前解成真的 & ，下面按 & 切分参数时这个 id 会被拦腰截断
        assertEquals("a&b",
                CoveragePublisher.projectOf(URI.create("ws://localhost:18090/ws/coverage?project=a%26b")));
    }
}
