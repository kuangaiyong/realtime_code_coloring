package com.rtcc.platform.history;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件里「涉及哪几台实例」那一列的拼装。
 *
 * <p>这一列存的是 VARCHAR(500)，而它会喂给页面的筛选下拉。
 * <b>拦腰截断的后果是静默的</b>：留下 {@code rust://some-host:6} 这种半截地址，
 * 它照常出现在下拉里、看起来像一台真实存在的实例，按它筛却什么都筛不出来。
 * 所以宁可少记几台，也不能记出一个不存在的地址。
 */
class CollectEventsJoinTest {

    @Test
    void 没有实例时给null而不是空串() {
        assertNull(CollectEvents.joinCapped(null));
        assertNull(CollectEvents.joinCapped(List.of()));
        // 全是空白项时同样要回 null —— 存一个空串进去，读出来会被 split 成 [""]，
        // 页面上就多一个空白的筛选项
        assertNull(CollectEvents.joinCapped(List.of("", "   ")));
    }

    @Test
    void 正常数量原样拼起来() {
        assertEquals("java://localhost:6300,java://localhost:6301",
                CollectEvents.joinCapped(List.of("java://localhost:6300", "java://localhost:6301")));
    }

    /** 本项目的 8 实例是常态，必须一个都不丢 */
    @Test
    void 八个实例一个都不丢() {
        List<String> eps = List.of(
                "java://localhost:6300", "java://localhost:6301",
                "go://localhost:6400", "go://localhost:6401",
                "cpp://localhost:6500", "cpp://localhost:6501",
                "rust://localhost:6600", "rust://localhost:6601");
        String joined = CollectEvents.joinCapped(eps);

        assertEquals(eps.size(), joined.split(",").length, "八个实例远没到上限，不该丢");
        assertTrue(joined.length() < 500);
    }

    /**
     * 超长时丢掉尾部几个，但<b>留下的每一个都必须是完整地址</b>。
     * 直接 substring 到 500 的话，最后那个会变成 {@code rust://some-host-name.internal:6}。
     */
    @Test
    void 超长时按完整边界丢弃而不是拦腰截断() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add("rust://some-host-name.internal:" + (6600 + i));
        }
        String joined = CollectEvents.joinCapped(many);

        assertTrue(joined.length() <= 500, "超过了列宽，数据库会自己截断，那就白做了：" + joined.length());
        assertTrue(joined.split(",").length < many.size(), "这份输入必然超长，应该丢掉了一些");
        for (String ep : joined.split(",")) {
            assertTrue(many.contains(ep),
                    "留下了一个不在原列表里的地址，说明是拦腰截断的：" + ep);
        }
    }

    @Test
    void 混在中间的空白项被跳过而不是拼成两个逗号() {
        String joined = CollectEvents.joinCapped(
                java.util.Arrays.asList("java://a:1", "", null, "java://b:2"));

        assertEquals("java://a:1,java://b:2", joined);
        // 空串会被 split 成一个空元素，页面上就是一个点不动的筛选项
        for (String ep : joined.split(",")) {
            assertFalse(ep.isBlank());
        }
    }
}
