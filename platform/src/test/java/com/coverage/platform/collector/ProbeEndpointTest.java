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
    void 解析主机与端口() {
        ProbeEndpoint ep = ProbeEndpoint.parse("localhost:6300");
        assertEquals("localhost", ep.host());
        assertEquals(6300, ep.port());
        assertEquals("localhost:6300", ep.toString());
    }

    @Test
    void 容忍配置里的多余空格() {
        assertEquals(new ProbeEndpoint("10.0.0.7", 6301), ProbeEndpoint.parse("  10.0.0.7 : 6301 "));
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
