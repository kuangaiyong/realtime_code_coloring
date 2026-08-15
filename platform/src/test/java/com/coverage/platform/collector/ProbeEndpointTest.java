package com.coverage.platform.collector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 探针地址解析。
 *
 * 配错了要在解析这一步就报出来：拖到采集时才失败，表现是「探针连不上」，
 * 会把人引去查被测服务，而真正的问题在平台的配置文件里。
 */
class ProbeEndpointTest {

    @Test
    void 不写语言时默认为java() {
        // 只有 Java 时写的配置不该因为接入 Go 而失效
        ProbeEndpoint ep = ProbeEndpoint.parse("localhost:6300");
        assertEquals(ProbeEndpoint.JAVA, ep.language());
        assertEquals("localhost", ep.host());
        assertEquals(6300, ep.port());
        assertEquals("java://localhost:6300", ep.toString());
    }

    @Test
    void 解析语言前缀() {
        ProbeEndpoint go = ProbeEndpoint.parse("go://10.0.0.9:6400");
        assertEquals(ProbeEndpoint.GO, go.language());
        assertEquals("10.0.0.9", go.host());
        assertEquals(6400, go.port());
    }

    @Test
    void 不支持的语言直接报错() {
        // 装作支持却采不到数据，界面上表现为「这个服务一行都没覆盖」，比报错难查得多
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProbeEndpoint.parse("rust://localhost:6500"));
        assertTrue(e.getMessage().contains("rust"), e.getMessage());
    }

    @Test
    void 容忍配置里的多余空格() {
        assertEquals(new ProbeEndpoint("java", "10.0.0.7", 6301), ProbeEndpoint.parse("  10.0.0.7 : 6301 "));
    }

    @Test
    void 缺少端口直接报错() {
        assertThrows(IllegalArgumentException.class, () -> ProbeEndpoint.parse("localhost"));
        assertThrows(IllegalArgumentException.class, () -> ProbeEndpoint.parse("localhost:"));
        assertThrows(IllegalArgumentException.class, () -> ProbeEndpoint.parse(":6300"));
        assertThrows(IllegalArgumentException.class, () -> ProbeEndpoint.parse(null));
    }

    @Test
    void 端口不是数字直接报错() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProbeEndpoint.parse("localhost:abc"));
        assertTrue(e.getMessage().contains("localhost:abc"), e.getMessage());
    }
}
