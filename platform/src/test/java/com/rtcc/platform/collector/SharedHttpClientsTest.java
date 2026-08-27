package com.rtcc.platform.collector;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 17 的 {@code HttpClient} 关不掉，每 new 一个就多一个 selector 线程和一个线程池。
 * 而本平台有两条会反复造它的路径：配置热替换（每保存一次换掉整个 ProjectRuntime）
 * 和接入向导的「当场验」。共用不成立的话，线程数只涨不落 —— 平台越跑越沉，
 * 而界面上看不出任何异样，正是本项目最怕的那种静默劣化。
 */
class SharedHttpClientsTest {

    @Test
    void 同一个超时拿到的是同一个客户端() {
        HttpClient a = SharedHttpClients.forConnectTimeout(3000);
        HttpClient b = SharedHttpClients.forConnectTimeout(3000);
        assertSame(a, b);
    }

    @Test
    void 同一秒内的超时归到同一档() {
        // 超时值可以来自 /api/projects/check 的请求体，取值空间无界。
        // 不归档的话这个池自己就成了泄漏源 —— 每个条目都是一个关不掉的客户端
        assertSame(SharedHttpClients.forConnectTimeout(2001),
                SharedHttpClients.forConnectTimeout(3000));
    }

    @Test
    void 条目数有确定的上界() {
        // 归档只是把「无界泄漏」变成「有界」，界还得足够小：每个客户端自带一个
        // selector 线程加一个执行器，上限定成几百就等于没解决问题
        Set<HttpClient> seen = new HashSet<>();
        for (int ms = 1; ms <= 60000; ms += 7) {
            seen.add(SharedHttpClients.forConnectTimeout(ms));
        }
        assertTrue(seen.size() <= 10, "取遍 1~60000ms 造出了 " + seen.size() + " 个客户端");
    }

    @Test
    void 差得远的超时仍然分开() {
        // 归档不能归成一个：连接超时是探针不通时多久放弃，
        // 归成一档会让配了 500ms 的项目实际等 10s
        assertNotSame(SharedHttpClients.forConnectTimeout(500),
                SharedHttpClients.forConnectTimeout(9000));
    }

    @Test
    void 超出上限的超时被钉在同一档() {
        // 上限之外全部落到同一个条目。10s 之外没有实际意义 —— TCP 连不上探针，
        // 等再久也是连不上，而请求整体超时是逐个请求另设的，不受这里影响
        assertSame(SharedHttpClients.forConnectTimeout(10000),
                SharedHttpClients.forConnectTimeout(Integer.MAX_VALUE));
    }

    @Test
    void 零与负数不会算出非法的超时() {
        // 校验挡在前面（timeoutMs 必须 > 0），但这个池是公共入口，
        // 自己也不能因为一个 0 就抛 IllegalArgumentException 把采集整条打断
        assertSame(SharedHttpClients.forConnectTimeout(0),
                SharedHttpClients.forConnectTimeout(-1));
        assertTrue(SharedHttpClients.forConnectTimeout(0).connectTimeout().isPresent());
    }
}
