# 代码实时染色平台 —— 项目约定

本文件是项目级约定，与全局 `~/.claude/CLAUDE.md` 并存；**冲突时以本文件为准**。

---

## 〇、输出语言（最高优先级，无例外）

**本项目的一切输出一律用中文**，包括：

- 最终答复；
- **工具调用之间的一句话说明**（「先看看这个文件」「重建后再跑一遍」这类过场话）；
- commit message、PR/Release 描述、代码注释、E2E 脚本里的打印文案。

例外只有**代码本身**：标识符、API 字段名、命令、日志与报错原文照抄不翻译，
但解释它们的话必须是中文。

> 起因：2026-08-18 的一轮前端开发里，最终答复都是中文，
> 工具调用之间的过场话却成串地用了英文。规则本来就在全局 CLAUDE.md 里，
> 是执行时漏掉了那些短句，所以在这里再钉一遍。

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
| 5 | 实时推送与染色渲染 | `scripts/e2e_verify.py`、`scripts/ws_verify.js`、`scripts/ui_verify.js` | 端到端延迟 **≤ 5s**（`ui_verify` 在真实 Chrome 里量：清零 → 调接口 → DOM 里真的出现绿行） |
| 6 | 场景边界归因 + 并发场景拒绝 | `scripts/e2e_scenario.py` | 两场景覆盖行集合互不越界；并发 start、进行中清零均返回 409 |
| 7 | 产物与源码版本一致性校验 | `scripts/e2e_incremental.py` | 源码漂移时返回 409 而非 200 |
| 8 | 多实例聚合 + 实例间版本校验 | `e2e_multi_instance.py`（Java）、`e2e_go.py`（Go）、`e2e_cpp.py`（C++）、`e2e_rust.py`（Rust） | 各实例覆盖取并集（`/api/coverage/instances` 按实例分别归一化，断言 单实例最大 ≤ 聚合 ≤ 相加 且 ≠ 相加）；掉线降级为 PARTIAL 并点名；实例间版本不一致时增量返回 409 |
| 9 | Go 采集与归一化（多语言共存） | `scripts/e2e_go.py` | Go 行级染色与 Java 同等质量；清零生效；路径以仓库根为基准；跨语言场景归因互不越界 |
| 10 | C++ 采集与归一化（多语言共存） | `scripts/e2e_cpp.py` | C++ 行级染色达到与 Java 同级的**四态**；清零生效（含删 .gcda）；三种语言共存于同一套口径；跨语言场景归因互不越界 |
| 11 | Rust 采集与归一化（多语言共存） | `scripts/e2e_rust.py` | Rust 行级染色成立；清零生效（含删 .profraw）；四种语言共存于同一套口径；跨语言场景归因互不越界 |
| 12 | 覆盖率门禁（判定与「判不了」分离） | `scripts/e2e_incremental.py`、`scripts/ws_verify.js` | 门禁给的数字与页面同一个四舍五入结果；空 diff 放行且 `actual` 为 null；源码漂移 / 基线不存在一律 409，绝不返回 200+`passed:false` |
| 13 | 项目管理与配置热生效 | `scripts/e2e_project.py` | 建项目 / 改配置**不重启**即生效；填错分 400（你填错了）/ 409（现在不能做）/ 503（平台依赖挂了）三类；场景进行中拒绝保存（409）；产物目录指错报 ANALYZE_ERROR 并点名；数据库不可用时采集、门禁照常，只有保存配置 503 |
| 14 | 多项目隔离 | `scripts/e2e_project.py`、`scripts/ws_verify.js` | 两个项目的覆盖快照 / 场景 / 趋势 / WebSocket 推送互不串；两项目文件集合不相交且并集等于默认项目；同一 commit 下趋势记录各自独立 |
| 15 | 页面上建出一个能采数的项目 + 把被测服务接进来 | `scripts/ui_verify.js` | 真实 Chrome 走完 6 步向导：每步当场验、第 6 步自检全过才建得成；建完**立刻**有覆盖数据（不是等下一个轮询周期）；用例自己收尾删项目，可重复跑。**接入侧**：命令按填的参数现算、不留待替换的占位符（`includes` 拼错是静默错误）；探针物料能下载且在下载处写明适用前提；接入页只生成配置不保存（保存入口只有「项目设置」一处） |
| 16 | 采集事件可追溯 | `scripts/ui_verify.js` | 真停一台实例：事件流里出现 PARTIAL 并**点名是哪台**，拉回来后出现 CONNECTED（区间闭合）；**只记状态变化**，不把每轮采集刷进去；库不可用时明确报原因而非回空列表 |
| 17 | 多语言指标能力差异如实呈现 | `scripts/ui_verify.js`、各语言 E2E | 四种语言各自该有的指标有、该缺的明确标为「不提供」而非补零（Go 与 Rust 的分支为 `null`，方法四种语言都有）；`branchesByLanguage` 里不出现拿不到分支的语言；分支不做跨语言汇总 |

> **17 项各自「为什么算核心」的完整论证见 `Skill(rtcc-core-features)`** ——
> 新增/改动核心功能、调整 E2E 断言、或判断某个新能力算不算核心功能时先读它。
> 其中几条最容易写错的边界（门禁 PARTIAL 时必须拒判、分母为 0 时必须放行、
> 多实例聚合必须在语言原生数据层合并而不能先退化成行状态）都在那里。

**分层验收的执行情况、Go 与 Java 的行级粒度差异、前端七个坑、采集耗时构成**
见 `Skill(rtcc-internals)`。

## 三、验证方式（全局规则 2：禁止 mock）

一条命令跑完全部验收：

```bash
bash scripts/run_local.sh verify
```

它会依次重启**全部八个**被测实例（2 个 Java + 2 个 Go + 2 个 C++ + 2 个 Rust）复位业务状态
→ 跑 9 套 E2E + 1 套真实 Chrome 前端验收（`ui_verify.js`，排在最后）。
全部为真实服务、真实探针、真实 git、真实 HTTP，**不允许用 mock / 桩 / 假数据通过验证**。

每种语言都起两个实例，是因为「多实例聚合」在四种语言下是四条代码路径（见 §二 的注）。

多实例用例需要摆布 Java 2 号实例，起停 JVM 由 `run_local.sh` 的
`demo2-stop|demo2-start|demo2-mismatch|demo2-dirty` 子命令负责。
项目用例还要摆布平台自身（验证数据库不可用时的降级），由
`platform-dbdown|platform-restart` 负责 —— 前者把平台指向一个没人监听的端口，
比停掉真数据库安全，后者还原。**`e2e_project.py` 必须排在最后**：
它会跑场景（start 会清零计数器），排在别的用例前面会洗掉它们的覆盖数据。

**验证必须在工作树干净（被测源码已提交）时跑**：源码一脏，被测实例自报的
sessionid 就带 `-dirty`，平台按设计拒绝出增量报告，增量与漂移相关的用例必然失败。
所以本项目的顺序是「先在 dev 提交、再跑 verify」，而不是反过来。

单测：`mvn -B test`（真实 socket、真实 git 仓库，同样无 mock）。

### 工具链依赖

平台侧需要 **JDK 17 + Maven + Go + GCC（MinGW-w64）+ Rust（rustup）**；
前端验收另需 **Node + 真实 Chrome**（`PUPPETEER_HOME` / `CHROME_BIN`，同样由 `run_local.sh` 注入，
别写死进脚本）。
后三个不只是被测服务要用 —— **平台自己**要调它们做归一化，
因为这些覆盖数据都是内部二进制格式，没有对外稳定契约，自行解析必然随版本崩：

| 语言 | 平台调用的工具 | 配置项（默认取 PATH） |
|---|---|---|
| Go | `go tool covdata textfmt`（行）、`covdata func`（方法） | `coverage.go-tool` |
| C++ | `gcov -t -r -b -c`、`gcov-tool merge` | `coverage.gcov-tool`、`coverage.gcov-merge-tool` |
| Rust | `llvm-profdata merge -sparse`、`llvm-cov export --format=lcov` | `coverage.llvm-profdata-tool`、`coverage.llvm-cov-tool` |

**Rust 的两个工具版本必须与 rustc 匹配**，系统上随便一个 LLVM 往往对不上，
会以「不认识的 profraw 版本」失败。取 rustup 的 `llvm-tools` 组件最稳，
它在 `<toolchain>/lib/rustlib/x86_64-pc-windows-gnu/bin/` 下。

**别把某台机器的绝对路径写死进 application.yml。** 本机的 MinGW-w64 装在
`~/devtools/mingw64`，rustup 在 `~/devtools/rustup`，
由 `run_local.sh` 的 `MINGW_HOME` / `RUSTUP_HOME` 放进 PATH。

### 项目配置的权威来源是数据库，不是 application.yml

首次启动时平台会把 `application.yml` 里的 `coverage.*` 种进 `project` 表（id 为
`default`）；**此后每次启动都从库里读，yml 的项目级配置不再生效**。改探针地址、
产物目录、门禁阈值要改库里那份 JSON（页面上改是下一步的事）。

只有**平台级**配置例外：`go-tool` / `gcov-tool` / `gcov-merge-tool` /
`llvm-profdata-tool` / `llvm-cov-tool` 这五个工具链路径始终读 yml —— 它们是部署
机器的属性，与项目无关。

数据库连不上时退回 yml 的那份在内存里跑：采集、染色、门禁照常，只有趋势不可用
（已实测：库不可用时 `probeStatus=CONNECTED`、8 实例、门禁 200，趋势
`available:false`）。**配置是核心能力的前提，不能随附加设施一起挂。**

### 采集耗时的构成

端到端延迟又逼近 5s 时先看 `Skill(rtcc-internals)`，那里有分项实测数字。
两个反直觉的点常驻在这里，因为它们会让人量错：

1. **`interval-ms` 是 `@Scheduled(fixedDelay)`**，语义是「上一轮**跑完**之后再等这么久」。
   配 3s、采集要 2.3s 时真实周期是 5.3s 而不是 3s，端到端最坏 ≈ 7.6s
2. **测延迟时别连着打**。连续多次「清零 → 调接口 → 轮询」会在 `collectLock` 上自己跟自己
   排队，量出来的数字比真实使用场景差一倍（实测连打 8 次全超 5s，
   同一份代码在 `verify` 里跑是 2.9s / 4.5s）

### 五个反复踩到的坑

1. **业务状态无法靠清零复位**。覆盖率计数器能清零，订单状态不能——订单进入终态后
   回不到 CREATED。E2E 若依赖业务状态，第二次跑必然走进别的分支而失败。
   对策：`verify` 开头重启被测服务；或让用例只走与状态无关的确定性分支。
2. **版本号要与 pom 同步**。`scripts/run_local.sh` 按版本号拼 jar 路径，
   改 pom 版本号时必须同步改脚本顶部的 `VERSION=`（脚本里只此一处，
   原先两处 jar 路径各写一遍，漏改一处只会报「文件不存在」，看不出根因）。
3. **E2E 的客户端超时要按平台最坏情况取，不能凭手感。**一次采集要挨个 dump 8 个实例，
   探针挂掉时每个耗尽 `timeout-ms`（3s）＝24s，再叠加各语言的外部工具，
   还可能排在调度那一轮后面 —— 所以统一取 60s。原先各脚本各填 10/15/20/25s，
   已经换来过两次与被测功能毫无关系的假失败（`/api/coverage/instances`、`/api/scenario/stop`），
   每次都要先花时间确认「不是这次改动引入的」。
4. **前提不成立时，断言必须报「判不了」，不能照判。**多实例聚合那条
   （`e2e_multi_instance.py` 的「单实例最大 ≤ 聚合 ≤ 相加」）踩过两次同一个坑：
   一次是两台没有共同覆盖的行，一次是<b>有一台瞬时取不到数</b> ——
   后者会被 `rows` 过滤掉，只剩一台时 `max == sum` 必然成立，
   于是报出「聚合不符合并集语义」，把人引向一个根本不存在的聚合 bug。
   **正确的做法是先把前提做实**：取不齐就重试几次（重试的是<b>取数</b>，不是断言本身），
   每次为什么没取到都打出来；重试完仍不齐就报「无法验证并集语义」并点名是哪台。
   这样瞬时抖动不会让流水线随机变红，而真的持续掉线照样看得见 ——
   与门禁那条「判定与判不了分离」是同一个原则。
   > 2026-09-02 记：这个瞬时取不到的根因**没查明**。16 次定向复现
   > （12 次连打 `/api/coverage/instances`、4 次重启一台后立刻取）全部正常，
   > 只在完整 `verify` 的负载下 4 轮撞到 1 次。所以上面改的是<b>诊断质量</b>，
   > 不是修掉了那个抖动 —— 别把这条当成「已修复」。
5. **`verify` 假定平台已经在跑**。它只重启被测实例，不启动平台；平台没起时
   第一套用例就以「连接被拒绝」失败。更麻烦的是它已经把 8 个被测实例拉起来了，
   而 Java 实例握着 `platform/target/classes/probe/jacocoagent.jar`，接着跑 `start` 会在
   `mvn clean` 删不掉这个 jar 上失败。**从全停状态恢复的顺序是
   `stop` → `start` → `verify`**，别直接跑 `verify`。

---

## 四、被测服务的启动约定

源码零改动，只加启动参数。四种语言的完整启动约定、探针编译方式与运行期硬事实
见 **`Skill(rtcc-probe-setup)`** —— 接被测服务、排查「采不到数据」、加新语言时读它。

常驻的三条跨语言红线：

- **`sessionid` / `COVERAGE_BUILD_ID` 是平台唯一能拿到的「实例自报构建版本」**，
  增量口径靠它校验行号能否对齐。不配则增量功能不可用（平台会明确报错，不会给出错位结果）
- **各实例的 sessionid 必须完全一致（含 `-dirty` 后缀）**：commit 相同但一台脏一台净，
  加载的是两份不同的字节码，平台判为版本冲突并拒绝出增量报告。
  **脏标记要按全部被测源码根一起判定**，各语言各算各的会被误判成「实例间版本不一致」
- **探针地址一律只绑回环**（`127.0.0.1`）。绑到所有网卡等于把「正在录的那个场景」
  交给同网段任何人随手作废 —— `/coverage/clear` 能清零计数器。
  平台侧写作 `go://` / `cpp://` / `rust://`，不写语言前缀默认 `java`
