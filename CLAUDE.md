# 代码实时染色平台 —— 项目约定

本文件是项目级约定，与全局 `~/.claude/CLAUDE.md` 并存；**冲突时以本文件为准**。

---

## 一、分支策略（覆盖全局规则 3 / 7）

本项目**只有两个分支**：

| 分支 | 用途 |
|---|---|
| `main` | 主干，永不直接 commit |
| `dev` | 唯一开发分支，所有功能、优化、bug 修复都在这里做 |

**硬性约定：**

1. **不再新建任何其他分支**。不按功能、不按阶段、不按 bug 拆分支；
   `dev/xxx`、`feature/xxx`、`hotfix/xxx` 一律不再创建。
2. **只在 `dev` 上写代码**。新功能、需求优化、bug 修复，全部在 `dev` 分支提交。
3. **合并 main 必须由用户明确指示**。模型不得自行决定合并时机——
   用户说「合并到 main」才执行 `git checkout main && git merge --no-ff dev`，
   否则代码就一直留在 `dev` 上，可以多次 commit、多次推送 `origin/dev`。
4. 合并一律用 `--no-ff`，保留合并节点便于追溯发布边界。
5. 版本号建议仍需用户确认后才打 tag / 建 Release（见全局规则 4 步骤 5、6）。

> 起因：早期按阶段拆了 `dev/p0-...`、`dev/p1-incremental-coverage`、
> `dev/p1-scenario-attribution` 三个分支，合并顺序与命名开销都不值当。
> 2026-08-15 全部并入 main 后删除，改为单一 `dev`。

---

## 二、核心功能清单（全局规则 5 要求：E2E 覆盖率 100%）

**判定标准**：少了就不能用、错了就出严重故障的能力。本平台的产品价值全部依赖
「覆盖数据可信」，因此**所有「拒绝出报告」的判断路径同样属于核心功能**——
一份静默错误的覆盖报告比没有报告更糟。

| # | 核心功能 | E2E 脚本 | 关键断言 |
|---|---|---|---|
| 1 | 探针数据采集（tcpserver 远程 dump） | `scripts/e2e_verify.py` | 打接口 A 后其代码行变绿 |
| 2 | 归一化为行级 IR | `scripts/e2e_verify.py` | 逐行 COVERED/MISSED/PARTIAL/EMPTY 状态正确 |
| 3 | 全量覆盖率计算 | `scripts/e2e_verify.py` | 未调用的接口 B/C 保持未覆盖 |
| 4 | 增量覆盖率计算 | `scripts/e2e_incremental.py` | 平台增量行集合与 `git diff` **集合相等** |
| 5 | 实时推送与染色渲染 | `scripts/e2e_verify.py`、`scripts/ws_verify.js` | 端到端延迟 **≤ 5s** |
| 6 | 场景边界归因 + 并发场景拒绝 | `scripts/e2e_scenario.py` | 两场景覆盖行集合互不越界；并发 start、进行中清零均返回 409 |
| 7 | 产物与源码版本一致性校验 | `scripts/e2e_incremental.py` | 源码漂移时返回 409 而非 200 |

新增/修改上述任一功能，必须同步新增或调整对应 E2E 用例，并在 commit 描述里
贴出可观察证据。

**尚未覆盖**：多实例聚合（P1 剩余项）——接入时需补第 8 条及其 E2E。

---

## 三、验证方式（全局规则 2：禁止 mock）

一条命令跑完全部验收：

```bash
bash scripts/run_local.sh verify
```

它会依次重启被测服务复位业务状态 → 跑 4 套 E2E。全部为真实服务、真实探针、
真实 git、真实 HTTP，**不允许用 mock / 桩 / 假数据通过验证**。

单测：`mvn -B test`（真实 socket、真实 git 仓库，同样无 mock）。

### 两个反复踩到的坑

1. **业务状态无法靠清零复位**。覆盖率计数器能清零，订单状态不能——订单进入终态后
   回不到 CREATED。E2E 若依赖业务状态，第二次跑必然走进别的分支而失败。
   对策：`verify` 开头重启被测服务；或让用例只走与状态无关的确定性分支。
2. **版本号散落在 jar 路径里**。`scripts/run_local.sh` 引用
   `platform/target/platform-<版本>.jar`，改 pom 版本号时必须同步改它，
   否则启动脚本找不到产物。

---

## 四、被测服务的启动约定

源码零改动，只加启动参数：

```
-javaagent:jacocoagent.jar=includes=com.shop.*,output=tcpserver,address=localhost,port=6300,sessionid=<40位commit>[-dirty]
```

`sessionid` 是平台唯一能拿到的「实例自报构建版本」，增量口径靠它校验行号能否对齐。
不配它则增量功能不可用（平台会明确报错，而不是给出错位的结果）。
