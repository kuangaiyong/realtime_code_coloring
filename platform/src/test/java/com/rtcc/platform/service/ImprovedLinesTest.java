package com.rtcc.platform.service;

import com.rtcc.platform.model.FileCoverage;
import com.rtcc.platform.model.FileCoverage.LineCoverage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 推送里的 changes：本轮与上一轮相比，哪些行的状态变好了。
 *
 * 跟随模式、实时动态条、新亮起标记全靠它。多报一行，就是告诉用户「你刚才的操作测到了它」——
 * 而那是假话；少报一行，用户刚测到的代码不亮。所以「不该报的不报」与「该报的报全」同样要守。
 */
class ImprovedLinesTest {

    /** 按行号给状态；null 表示非可执行行（不进 lines，与真实 IR 一致） */
    private static FileCoverage file(String path, String... statusByLine) {
        List<LineCoverage> lines = new ArrayList<>();
        int covered = 0, missed = 0;
        for (int i = 0; i < statusByLine.length; i++) {
            String s = statusByLine[i];
            if (s == null) {
                continue;
            }
            lines.add(new LineCoverage(i + 1, s, null, null));
            if ("MISSED".equals(s)) {
                missed++;
            } else {
                covered++;
            }
        }
        double ratio = covered + missed == 0 ? 0 : covered * 100.0 / (covered + missed);
        return new FileCoverage(path, "pkg", path.substring(path.lastIndexOf('/') + 1),
                covered, missed, ratio, null, null, null, null, null, lines);
    }

    private static Map<String, FileCoverage> snap(FileCoverage... files) {
        Map<String, FileCoverage> m = new LinkedHashMap<>();
        for (FileCoverage f : files) {
            m.put(f.path(), f);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> linesOf(Map<String, Object> change) {
        return (List<Integer>) change.get("lines");
    }

    @Test
    void 三种变好都报而没变的不报() {
        var prev = snap(file("a/A.java", "MISSED", "MISSED", "PARTIAL", "COVERED", null));
        var now = snap(file("a/A.java", "COVERED", "PARTIAL", "COVERED", "COVERED", null));

        var ch = ProjectRuntime.improvedLines(prev, now);

        assertEquals(1, ch.size());
        assertEquals("a/A.java", ch.get(0).get("path"));
        assertEquals(List.of(1, 2, 3), linesOf(ch.get(0)), "未覆盖→已覆盖、未覆盖→部分、部分→已覆盖；第 4 行一直已覆盖，不报");
        assertEquals(3, ch.get(0).get("delta"));
        assertEquals(false, ch.get(0).get("truncated"));
    }

    @Test
    void 变差不报所以清零那次推送是空的() {
        var prev = snap(file("a/A.java", "COVERED", "PARTIAL", "COVERED"));
        var now = snap(file("a/A.java", "MISSED", "MISSED", "PARTIAL"));

        assertTrue(ProjectRuntime.improvedLines(prev, now).isEmpty());
    }

    @Test
    void 第一轮以及上一轮没有的文件不报() {
        var now = snap(file("a/A.java", "COVERED", "COVERED"), file("b/B.java", "COVERED"));
        assertTrue(ProjectRuntime.improvedLines(Map.of(), now).isEmpty(),
                "平台刚启动的第一轮不能把已覆盖行都算成新亮起 —— 那会一上来全屏闪、跟随乱跳");

        var prev = snap(file("a/A.java", "MISSED", "COVERED"));
        var ch = ProjectRuntime.improvedLines(prev, now);
        assertEquals(1, ch.size(), "b/B.java 上一轮不存在，不能整份算新");
        assertEquals("a/A.java", ch.get(0).get("path"));
        assertEquals(List.of(1), linesOf(ch.get(0)));
    }

    @Test
    void 按变好的行数从多到少排() {
        var prev = snap(file("a/A.java", "MISSED"), file("b/B.java", "MISSED", "MISSED", "MISSED"));
        var now = snap(file("a/A.java", "COVERED"), file("b/B.java", "COVERED", "COVERED", "COVERED"));

        var ch = ProjectRuntime.improvedLines(prev, now);

        assertEquals(List.of("b/B.java", "a/A.java"), ch.stream().map(c -> c.get("path")).toList());
    }

    @Test
    void 超过上限时截断但delta仍是精确行数() {
        String[] before = new String[250];
        String[] after = new String[250];
        Arrays.fill(before, "MISSED");
        Arrays.fill(after, "COVERED");
        FileCoverage[] prevFiles = new FileCoverage[25];
        FileCoverage[] nowFiles = new FileCoverage[25];
        for (int i = 0; i < 25; i++) {
            prevFiles[i] = file("p/F" + i + ".java", i == 0 ? before : new String[]{"MISSED"});
            nowFiles[i] = file("p/F" + i + ".java", i == 0 ? after : new String[]{"COVERED"});
        }

        var ch = ProjectRuntime.improvedLines(snap(prevFiles), snap(nowFiles));

        assertEquals(20, ch.size(), "推送体不能无界增长：最多 20 个文件");
        assertEquals("p/F0.java", ch.get(0).get("path"));
        assertEquals(200, linesOf(ch.get(0)).size(), "每个文件最多 200 行");
        assertEquals(250, ch.get(0).get("delta"), "动态条上的 +N 要准，截断的只是行号列表");
        assertEquals(true, ch.get(0).get("truncated"));
    }

    @Test
    void 只有分支变好也要推送() {
        var prev = snap(file("a/A.java", "PARTIAL", "COVERED"));
        var now = snap(file("a/A.java", "COVERED", "COVERED"));
        // 前后已覆盖行数都是 2：旧的判定据此认为「没变化」不推送，页面上的分支数就一直是旧的
        assertEquals(prev.get("a/A.java").coveredLines(), now.get("a/A.java").coveredLines());

        assertTrue(ProjectRuntime.pushNeeded(prev, now, ProjectRuntime.improvedLines(prev, now)));
    }

    @Test
    void 什么都没变时不推送() {
        var prev = snap(file("a/A.java", "PARTIAL", "COVERED"));
        var now = snap(file("a/A.java", "PARTIAL", "COVERED"));

        assertFalse(ProjectRuntime.pushNeeded(prev, now, ProjectRuntime.improvedLines(prev, now)));
    }

    @Test
    void 已覆盖行数下降时照常推送() {
        var prev = snap(file("a/A.java", "COVERED", "COVERED"));
        var now = snap(file("a/A.java", "MISSED", "MISSED"));

        assertTrue(ProjectRuntime.pushNeeded(prev, now, ProjectRuntime.improvedLines(prev, now)),
                "清零必须推送，否则页面停在清零前的绿");
    }

    @Test
    void 上一轮没取到的实例这一轮回来了不算新亮起() {
        // 上一轮 b 瞬时没取到（被拒、超时），这一轮又取到了 —— 它的 JVM 没重启、计数器一直在，
        // 它跑过的那些行是失而复得，不是刚测到的。报成新亮起，跟随会乱跳、动态条冒出一串假的 +N
        assertTrue(ProjectRuntime.rejoined(Set.of("java://a:1"), List.of("java://a:1", "java://b:2")));
        assertFalse(ProjectRuntime.rejoined(Set.of("java://a:1", "java://b:2"), List.of("java://a:1", "java://b:2")),
                "两轮的来源相同：照常报");
        assertFalse(ProjectRuntime.rejoined(Set.of("java://a:1", "java://b:2"), List.of("java://a:1")),
                "少了一台不算回来：剩下那台刚测到的行照常报");
    }
}
