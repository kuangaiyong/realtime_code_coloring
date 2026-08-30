package com.rtcc.platform.web;

import com.rtcc.platform.collector.GitService;
import com.rtcc.platform.config.ProjectConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 直接问某个 git 仓库的只读信息，给新建向导与设置页的表单做填写建议。
 *
 * <p><b>为什么不挂在 /api/projects 下：</b>它接的是<b>仓库路径</b>而不是项目 id ——
 * 填这个表单的时候项目还不存在。硬挂过去会写成 {@code GET /api/projects/baselines}，
 * 与 {@code GET /api/projects/{id}} 抢同一个位置；而项目 id 的合法字符集
 * （{@code [a-z0-9][a-z0-9_-]*}）恰好允许有人把项目取名叫 baselines，
 * 那个项目从此就读不出配置了 —— 而且是静默的，页面上只表现为「设置页打不开」。
 */
@RestController
@RequestMapping("/api/git")
public class GitRefController {

    /**
     * 这个仓库里能拿来当增量基线的引用。
     *
     * <p><b>为什么建议必须来自真实仓库：</b>「增量基线」问的是「跟哪个版本比」，
     * 而人填不出来往往不是不懂这个概念，是不知道这个仓库里有什么可填。前端写死
     * {@code main} 的话，主干叫 {@code master} 的仓库会拿到一个选了就报错的选项 ——
     * 比不给建议更糟，因为人会以为是平台坏了。
     *
     * <p><b>取不到不是错误，是「这里没有建议可给」。</b>仓库路径还没填完、或者压根
     * 不是 git 仓库，都会走到这条路上，而那一刻用户正在<b>填</b>仓库路径。
     * 报一个红色错误只会让人以为自己已经填错了，所以回 {@code available:false} + 原因，
     * 让表单安静地退回纯手输入，别挡住人。
     */
    @GetMapping("/baselines")
    public Map<String, Object> baselines(@RequestParam String repoDir) {
        Map<String, Object> res = new LinkedHashMap<>();
        // 空路径必须自己挡掉：git -C "" 是空操作（实测），git 会拿平台进程自己的
        // 工作目录当仓库 —— 于是回一份 available:true 的候选，而它们属于另一个仓库。
        // 这是最坏的一种坏法：看上去完全正常
        if (repoDir == null || repoDir.isBlank()) {
            return unavailable(res, "还没填仓库目录");
        }
        ProjectConfig cfg = new ProjectConfig();
        cfg.setRepoDir(repoDir);
        try {
            GitService.Baselines b = new GitService(cfg).baselineCandidates();
            res.put("available", true);
            res.put("error", null);
            res.put("candidates", b.candidates());
            // 被滤掉的要说出来，否则分支叫 feature/添加登录 的人会对着一个
            // 没有自己那个分支的列表发愁，而列表本身看不出任何异样
            res.put("skipped", b.skipped());
            return res;
        } catch (Exception e) {
            // getMessage() 可能为 null（比如 NPE），回一个 null 原因等于什么都没说
            String why = e.getMessage();
            return unavailable(res, why == null || why.isBlank() ? e.toString() : why);
        }
    }

    private static Map<String, Object> unavailable(Map<String, Object> res, String why) {
        res.put("available", false);
        res.put("error", why);
        res.put("candidates", List.of());
        res.put("skipped", 0);
        return res;
    }
}
