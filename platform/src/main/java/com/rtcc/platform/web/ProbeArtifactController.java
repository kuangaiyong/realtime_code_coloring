package com.rtcc.platform.web;

import org.jacoco.core.JaCoCo;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 接入所需的探针物料。
 *
 * <p><b>为什么平台要自己分发这些文件：</b>本平台面向内网，不能假设有外网 ——
 * 这是用户唯一拿得到 JaCoCo agent 与三种语言探针源码的途径。此前页面上一个入口都没有，
 * 人得自己去仓库里翻，而翻错版本的表现是「接上了但解不出数据」，看不出是版本的问题。
 *
 * <p><b>版本不是写死的，是运行时从 JaCoCo 自己读的</b>（{@link JaCoCo#VERSION}）——
 * 分发的 agent 必须与平台解析 exec 数据用的是同一个版本，写死一个字符串的话，
 * 升级依赖时这两处会悄悄分叉。
 *
 * <p>物料在构建期被复制进 {@code classes/probe/}（见 platform/pom.xml），
 * 因此不依赖平台运行时所在机器上有没有本项目的源码目录。
 *
 * <p><b>全部是只读操作</b>：不碰任何项目配置、覆盖数据与场景状态。
 */
@RestController
@RequestMapping("/api/probe/artifacts")
public class ProbeArtifactController {

    /** classpath 下的物料目录。由 maven 在 process-resources 阶段填充 */
    private static final String DIR = "probe/";

    /**
     * 一份物料。
     *
     * <p>{@code prerequisite} 是<b>后端</b>给的而不是前端写死的：契约是「提供下载处必须写明
     * 前提」，而不满足前提的人下载完接不上、还不知道为什么。放在后端，页面改版也丢不掉这句话。
     */
    private record Artifact(String id, String lang, String file, String contentType,
                            String prerequisite) {
    }

    private static final List<Artifact> ARTIFACTS = List.of(
            new Artifact("java", "Java", "jacocoagent.jar", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    "无需重新编译，也无需改动源码；但探针必须随 JVM 一起启动 —— "
                            + "JaCoCo 官方明确不支持对已运行的进程动态挂载。"
                            + "这份 agent 的版本与平台解析 exec 数据用的是同一个，别换成别处下载的。"),
            new Artifact("go", "Go", "coverage_agent.go", MediaType.TEXT_PLAIN_VALUE,
                    "放进被测服务的 main 包目录，与 main.go 同包 —— Go 的包就是目录，"
                            + "放到别处它根本不参与编译。由 build tag goverage 守卫，"
                            + "不带 tag 的生产构建里这个文件不存在。"
                            + "被测对象不是可执行程序（如库工程）时不适用。"),
            new Artifact("cpp", "C++", "coverage_agent.cpp", MediaType.TEXT_PLAIN_VALUE,
                    "作为独立编译单元参与链接，业务代码不 include 也不调用它 —— "
                            + "靠全局对象的构造函数在 main 之前自动启动。"
                            + "它自己不要插桩（不加 --coverage），否则测的是探针自己；"
                            + "Windows 上链接时需显式加 -lws2_32。"),
            new Artifact("rust", "Rust", "coverage_agent.c", MediaType.TEXT_PLAIN_VALUE,
                    "这是一个 C 文件，单独编译成 .o 后经 -C link-arg 链进 Rust 产物，"
                            + "靠 .CRT$XCU 段在 main 之前自动执行 —— 连 Cargo.toml 都不用动。"
                            + "用 MinGW 的 gcc 编译时 -mno-stack-arg-probe 不能省，"
                            + "否则会出现 MSVC 侧没有的 ___chkstk_ms 符号。"));

    /** 有哪些物料可下载。页面据此渲染下载入口与每份物料的前提说明 */
    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = ARTIFACTS.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("lang", a.lang());
            m.put("file", a.file());
            // 只有 Java 那份有版本可言：另外三份是源码，跟着平台走
            m.put("version", "java".equals(a.id()) ? JaCoCo.VERSION : null);
            m.put("prerequisite", a.prerequisite());
            m.put("size", sizeOf(a));
            return m;
        }).toList();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("artifacts", rows);
        return res;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        Artifact a = ARTIFACTS.stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "没有这份物料：" + id + "，可用的是 java / go / cpp / rust"));
        ClassPathResource res = new ClassPathResource(DIR + a.file());
        // 构建配置漏了某一份时，这里必须明确报出来 —— 交出一个 0 字节的文件，
        // 表现是「下载下来了但用不了」，比 404 难查得多
        if (!res.exists()) {
            throw new ResponseStatusException(NOT_FOUND,
                    "物料 " + a.file() + " 不在平台产物里，平台构建可能不完整");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + a.file() + "\"")
                .contentType(MediaType.parseMediaType(a.contentType()))
                .body(res);
    }

    /** 列表里的大小是给人看的参考值，取不到就给 null，不因此让整个列表失败 */
    private Long sizeOf(Artifact a) {
        try {
            ClassPathResource res = new ClassPathResource(DIR + a.file());
            return res.exists() ? res.contentLength() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
