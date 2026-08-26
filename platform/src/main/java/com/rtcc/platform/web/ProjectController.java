package com.rtcc.platform.web;

import com.rtcc.platform.config.ProjectConfig;
import com.rtcc.platform.service.ProjectChecker;
import com.rtcc.platform.service.ProjectRegistry;
import com.rtcc.platform.service.ProjectRuntime;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目的增删改查与配置自检。
 *
 * <p>配置改完<b>当场生效</b>，不需要重启平台 —— 这正是把配置从 application.yml
 * 搬进数据库的目的。生效方式见 {@link ProjectRegistry#update}。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRegistry registry;
    private final ProjectChecker checker;

    public ProjectController(ProjectRegistry registry, ProjectChecker checker) {
        this.registry = registry;
        this.checker = checker;
    }

    /**
     * 项目列表。每项带上运行时状态，使列表页一眼能看出哪个项目采不到数据 ——
     * 「建好了却没数据」是这类平台最常见的困惑，把它放在第一屏。
     */
    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = registry.configs().stream().map(cfg -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", cfg.getId());
            m.put("name", cfg.getName());
            // 同构造函数里的写法：库里出现一行 instances 为 null 的配置（手工改库、
            // 或版本升级留下的），不兜住的话整张列表 500，而不是只有那一个项目异常
            m.put("instanceCount", cfg.getInstances() == null ? 0 : cfg.getInstances().size());
            m.put("isDefault", cfg.getId().equals(registry.defaultId()));
            // 用 find 而不是 get：另一个请求恰好在这中间删掉某个项目时，
            // get 会抛 404，整张列表一个项目都列不出来
            ProjectRuntime rt = registry.find(cfg.getId());
            Map<String, Object> summary = rt == null ? Map.of() : rt.summary();
            m.put("probeStatus", summary.get("probeStatus"));
            m.put("overallRatio", summary.get("overallRatio"));
            m.put("lastError", summary.get("lastError"));
            m.put("lastCollectedAt", summary.get("lastCollectedAt"));
            return m;
        }).toList();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("projects", rows);
        res.put("defaultId", registry.defaultId());
        return res;
    }

    @GetMapping("/{id}")
    public ProjectConfig get(@PathVariable String id) {
        return registry.config(id);
    }

    @PostMapping
    public ProjectConfig create(@RequestBody ProjectConfig cfg) {
        return registry.create(cfg);
    }

    /** 改配置。立即生效；有场景进行中时返回 409 —— 理由见 {@link ProjectRegistry#update} */
    @PutMapping("/{id}")
    public ProjectConfig update(@PathVariable String id, @RequestBody ProjectConfig cfg) {
        return registry.update(id, cfg);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        registry.delete(id);
        return Map.of("ok", true);
    }

    /**
     * 拿一份配置去碰真实环境，逐项回答能不能跑起来。
     *
     * <p>接的是<b>请求体里的配置</b>而不是某个已存在的项目，所以新建向导每一步都能
     * 当场验、设置页存之前也能先试一下。只读，不改任何东西。
     */
    @PostMapping("/check")
    public Map<String, Object> check(@RequestBody ProjectConfig cfg) {
        return checker.check(cfg);
    }

    /** 对已存在的项目跑一遍自检。向导最后一步与设置页的「重新自检」用它 */
    @PostMapping("/{id}/check")
    public Map<String, Object> checkExisting(@PathVariable String id) {
        return checker.check(registry.config(id));
    }

    /** 立即采集一次，不等轮询周期。向导建完项目后马上要看到数据 */
    @PostMapping("/{id}/collect")
    public Map<String, Object> collect(@PathVariable String id) {
        ProjectRuntime rt = registry.get(id);
        rt.collect();
        return rt.summary();
    }
}
