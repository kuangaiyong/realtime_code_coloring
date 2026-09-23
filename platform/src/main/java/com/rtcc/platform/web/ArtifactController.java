package com.rtcc.platform.web;

import com.rtcc.platform.artifact.ArtifactKind;
import com.rtcc.platform.artifact.ArtifactOperationException;
import com.rtcc.platform.artifact.ArtifactStore;
import com.rtcc.platform.artifact.BadArtifactException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 被测服务的编译产物。给「平台够不着容器文件系统」那种部署用。
 *
 * <p>CI 构建完把产物按 buildId 推上来，平台按实例自报的 buildId 取回去解行号。
 * 详见 {@code docs/specs/2026-09-02-containerized-target-coverage-design.md}。
 *
 * <p><b>安全边界</b>：平台此前没有任何文件上传，这是第一处。探针端口只读、
 * 靠「绑回环 + 网络策略」兜着；这个接口能<b>写</b>平台磁盘，风险高一档。
 * 本方案仍假设平台只面向内网 —— 这条假设是显式的，不是默认的。
 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactController.class);

    private final ArtifactStore store;

    public ArtifactController(ArtifactStore store) {
        this.store = store;
    }

    /**
     * 上传一份产物。
     *
     * @param project 归到哪个项目，默认 default
     * @param buildId 40 位 commit sha。<b>不接受 -dirty</b>，理由见 ArtifactStore
     * @param lang    java / cpp / rust。Go 不需要产物
     * @param file    zip 包
     */
    @PostMapping("/{buildId}")
    public Map<String, Object> upload(@RequestParam(defaultValue = "default") String project,
                                      @PathVariable String buildId,
                                      @RequestParam String lang,
                                      @RequestParam("file") MultipartFile file) {
        requireValidProject(project);
        ArtifactKind kind = kindOf(lang);
        try {
            store.requireValidBuildId(buildId);
        } catch (IllegalArgumentException e) {
            throw ArtifactOperationException.invalid(e.getMessage());
        }
        // 空包存下去会变成一个空目录，而空目录与「没上传过」在上游长得一模一样
        if (file == null || file.isEmpty()) {
            throw ArtifactOperationException.invalid("产物包是空的，没有东西可存");
        }
        try (var in = file.getInputStream()) {
            store.save(project, buildId, kind, in);
        } catch (BadArtifactException e) {
            // 包本身不对（不是 zip、空包、Zip Slip）：换个包重传就好 —— 报出原因，别让人对着 500 猜
            throw ArtifactOperationException.invalid("产物包存不下来：" + e.getMessage());
        } catch (IOException e) {
            // 平台这边的 IO 故障：磁盘满、没有写权限、目录改名一直被占用。
            // 这种情况下调用方重传多少次都没用，报成 4xx 会把排查方向引到「包有问题」上去，
            // 而包是好的。平台自己的问题就要认，与 ProjectOperationException 的 503 同一个口径
            log.error("产物存盘失败（平台侧）：项目 {} / 构建 {} / {}", project, buildId, kind.dir(), e);
            throw ArtifactOperationException.failed("产物包存不下来（平台侧故障）：" + e.getMessage());
        } catch (Exception e) {
            log.error("产物存盘失败（未预料）：项目 {} / 构建 {} / {}", project, buildId, kind.dir(), e);
            throw ArtifactOperationException.failed("产物包存不下来：" + e);
        }
        log.info("已收下产物：项目 {} / 构建 {} / {}（{} 字节）",
                project, buildId, kind.dir(), file.getSize());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("project", project);
        res.put("buildId", buildId);
        res.put("lang", kind.dir());
        res.put("kept", store.builds(project));
        return res;
    }

    /**
     * 存了哪些构建。运维要能看出磁盘上到底有什么，不然清理就是盲的。
     *
     * <p><b>不认识的目录名跳过，不让整个接口挂掉</b>：{@code builds()} 返回的是磁盘上的
     * 任意子目录名，手工建的、别的工具落下的都在里面。实测过，不跳过的话整个接口回裸 500，
     * 而它恰恰是在「磁盘上真有意外东西」的时候最该出得来。
     * 跳过了几个要在 {@code skipped} 里点明，否则运维不知道盘上还躺着别的东西。
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "default") String project) {
        requireValidProject(project);
        List<Map<String, Object>> rows = new ArrayList<>();
        // builds() 已经只返回真正的构建；盘上那些不认识的目录由 strangers() 单独数，
        // 跳过了几个要点明，否则运维不知道盘上还躺着别的东西
        int skipped = store.strangers(project).size();
        for (String b : store.builds(project)) {
            List<String> kinds = new ArrayList<>();
            for (ArtifactKind k : ArtifactKind.values()) {
                if (store.find(project, b, k).isPresent()) {
                    kinds.add(k.dir());
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("buildId", b);
            m.put("kinds", kinds);
            rows.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("project", project);
        res.put("keep", store.keep());
        res.put("artifacts", rows);
        res.put("skipped", skipped);
        return res;
    }

    @DeleteMapping("/{buildId}")
    public Map<String, Object> delete(@RequestParam(defaultValue = "default") String project,
                                      @PathVariable String buildId) {
        requireValidProject(project);
        try {
            store.requireValidBuildId(buildId);
        } catch (IllegalArgumentException e) {
            throw ArtifactOperationException.invalid(e.getMessage());
        }
        try {
            store.remove(project, buildId);
        } catch (IOException e) {
            // 挪不开就是没删，不能说删掉了。store 的报错里已经写清产物原封未动、稍后重试，
            // 这里不再加「没删干净」之类的前缀 —— 那会让人以为盘上留了半截
            log.error("产物删除失败：项目 {} / 构建 {}", project, buildId, e);
            throw ArtifactOperationException.failed(e.getMessage());
        }
        return Map.of("ok", true, "project", project, "buildId", buildId);
    }

    /**
     * project 与 buildId 一样直接参与磁盘路径，三个入口都得先过这一关 ——
     * 只堵 buildId 等于没堵，详见 {@link ArtifactStore#requireValidProjectId}。
     */
    private void requireValidProject(String project) {
        try {
            store.requireValidProjectId(project);
        } catch (IllegalArgumentException e) {
            throw ArtifactOperationException.invalid(e.getMessage());
        }
    }

    private static ArtifactKind kindOf(String lang) {
        try {
            return ArtifactKind.of(lang);
        } catch (IllegalArgumentException e) {
            throw ArtifactOperationException.invalid(e.getMessage());
        }
    }
}
