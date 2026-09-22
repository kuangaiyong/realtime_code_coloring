package com.rtcc.platform.web;

import org.jacoco.core.JaCoCo;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 探针物料的分发。
 *
 * <p>读的是<b>真实的 classpath 资源</b>（构建期由 maven 复制进 classes/probe/），
 * 不喂任何假文件 —— 这些用例真正要守的就是「构建配置漏了某一份」这件事，
 * 而漏掉的表现是页面上下载到一个用不了的东西，看不出是构建的问题。
 */
class ProbeArtifactControllerTest {

    private final ProbeArtifactController c = new ProbeArtifactController();

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows() {
        return (List<Map<String, Object>>) c.list().get("artifacts");
    }

    @Test
    void 四种语言的物料都列得出来() {
        assertEquals(List.of("java", "go", "cpp", "rust"),
                rows().stream().map(r -> r.get("id")).toList());
    }

    @Test
    void 四份物料都真的读得到且不是空文件() throws Exception {
        for (Map<String, Object> r : rows()) {
            String id = (String) r.get("id");
            ResponseEntity<Resource> resp = c.download(id);
            assertEquals(200, resp.getStatusCode().value(), id);
            Resource body = resp.getBody();
            assertNotNull(body, id);
            assertTrue(body.exists(), id + " 不在平台产物里，构建配置漏了它");
            // 0 字节的文件下载得下来却用不了，比 404 难查 —— 这里必须钉住非空
            assertTrue(body.contentLength() > 0, id + " 是个空文件");
        }
    }

    /**
     * 分发的 agent 与平台解析 exec 数据用的必须是同一个版本。
     * 对不上的表现是「接上了但解不出数据」—— 界面上看不出是版本的问题。
     */
    @Test
    void Java那份的版本取自JaCoCo自己而不是写死的字符串() {
        Map<String, Object> java = rows().get(0);
        assertEquals(JaCoCo.VERSION, java.get("version"));
        assertNotNull(JaCoCo.VERSION);
        // 另外三份是源码，跟着平台走，没有独立版本可言 —— 给个假版本比不给更误导
        rows().stream().filter(r -> !"java".equals(r.get("id")))
                .forEach(r -> assertNull(r.get("version"), r.get("id") + " 不该有版本号"));
    }

    @Test
    void 下载响应带文件名让浏览器直接存盘() {
        String cd = c.download("go").getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(cd);
        assertTrue(cd.contains("coverage_agent.go"), cd);
        assertTrue(cd.startsWith("attachment"), cd);
    }

    @Test
    void 认不出来的物料回404而不是500() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> c.download("python"));
        assertEquals(404, e.getStatusCode().value());
        // 报错要说清可用的是哪些，否则调用方只能去翻代码
        assertTrue(e.getReason().contains("java"), e.getReason());
    }

    /**
     * 交出去的必须是<b>探针源码本身</b>，不是别的什么文件。只断言「非空」的话，
     * 构建配置把路径写错、复制进来一个同名的空壳，用例照样绿。
     */
    @Test
    void 交出去的内容确实是那份探针源码() throws Exception {
        assertTrue(text("go").contains("package main"), "Go 探针必须与 main 同包");
        assertTrue(text("go").contains("goverage"), "Go 探针要由 build tag 守卫");
        assertTrue(text("cpp").contains("COVERAGE_ADDR"), "C++ 探针要认这个环境变量");
        assertTrue(text("rust").contains("CRT$XCU"), "Rust 探针靠这个段在 main 之前执行");
    }

    private String text(String id) throws Exception {
        try (var in = c.download(id).getBody().getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 「下载是只读操作」这条契约，靠的不是实现里没写改动代码，而是这个 controller
     * <b>够不着</b>项目配置与覆盖数据 —— 它一个依赖都不注入、一个可变字段都没有。
     * 哪天有人给它注入一个 ProjectRegistry，这条用例会立刻挂，
     * 那正是要拦下的时刻（能拿到 registry 就能改配置，而改了不会有任何用例发现）。
     */
    @Test
    void 这个接口够不着项目配置与覆盖数据() {
        for (Field f : ProbeArtifactController.class.getDeclaredFields()) {
            assertTrue(java.lang.reflect.Modifier.isStatic(f.getModifiers()),
                    "多出一个实例字段 " + f.getName() + "：物料下载不该持有任何运行时状态");
        }
        assertEquals(1, ProbeArtifactController.class.getDeclaredConstructors().length);
        assertEquals(0, ProbeArtifactController.class.getDeclaredConstructors()[0]
                        .getParameterCount(),
                "构造函数一旦有参数，就意味着它注入了别的服务 —— 只读性从此不再是结构保证的");
    }
}
