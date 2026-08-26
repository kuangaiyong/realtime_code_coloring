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

> **四种语言的多实例聚合走的是四条代码路径**，因此 #8 需要四个脚本各测一次：
> Java 在 exec 层按探针取或，Go 把多份 meta/counters 交给 `covdata` 按块求和，
> C++ 用 `gcov-tool merge` 合并 `.gcda`，Rust 用 `llvm-profdata merge` 合并 `.profraw`。
> 四者都是在语言自己的原生数据层面合并 ——
> 提前退化成行状态再合并会掉精度。合并出错的表现是「几行没变绿」，
> 静默少算，界面上看不出是 bug，只能靠用例守。
>
> **门禁（#12）算核心功能，是因为它的结论会被 CI 直接拿去挡合并。**
> 判定有三态而非两态：通过 / 不通过 / **判不了**。判不了走 409，
> 不通过走 200 + `passed:false` —— CI 那句 `curl -f` 分不出「覆盖不够」
> 和「平台自己挂了」，而这两件事一个该补测试、一个该找人看。
> 两个特别容易写错的边界：探针 PARTIAL 时必须拒判（掉线那台跑过的行会被算成没覆盖，
> 比例被压低，判出「不通过」而真正的原因一个字都不出现），
> 分母为 0 时必须放行（`overallRatio()` 此时返回 0，直接比阈值就把
> 「这次没改任何可执行代码」说成了「改了却一行没测」）。
>
> **#13 / #14 算核心功能，是因为它们的错法全是静默的。** 配置错一项就采不到数据，
> 而 `classes-dir` 指错目录只会让覆盖率莫名其妙偏低，界面上看不出是配置问题；
> 项目之间串台的表现是「数字对不上」，同样看不出是串台。
> 两个特别容易写错的边界：**配置变更必须整体替换 `ProjectRuntime`**
> （半新半旧的配置组合会算出错位的行号却照样返回 200），
> **数据库不可用时保存必须明确失败**（回一句「保存成功」，
> 人改完关掉页面，下次启动全变回去）。

新增/修改上述任一功能，必须同步新增或调整对应 E2E 用例，并在 commit 描述里
贴出可观察证据。

### P2 验收标准的执行情况（如实记录）

原定「接入新语言**不修改 Analyzer / Web 任何代码**」。实际：Web 一行未改，
**Analyzer 改了一处** —— `analyze()` 增加 `sourceRoot` 参数，使产出的路径以仓库根
为基准。这不是为迁就 Go 打的补丁，而是原设计缺陷的修正：IR 路径原本以「源码根」
为基准，而单一 `source-dir` 的假设在多语言下本就不成立 —— 两种语言各有源码根时，
「源码根相对路径」既无法唯一定位文件，也对不上 `git diff` 的输出。
GitService 因此不再剥前缀，逻辑反而更简单。

**结论：分层设计基本成立，但「IR 路径基准」这个契约在 P0 时定错了。**
后续接入 C++/Rust 时如果又要改 Analyzer，就必须重新审视分层是否真的成立。

### Go 与 Java 的行级粒度差异（已知限制，不是 bug）

JaCoCo 是**探针模型**：哪一行有字节码探针，哪一行才进 IR，空行/注释/花括号一律 EMPTY。
Go 是**块模型**：profile 给的是「起行.起列 → 止行.止列」的文本区间，
区间内的每一行都被视为同一个块的一部分。两者对不齐：

- **空行已剔除**：strip 后为空即判定为非可执行行，无需解析源码，不存在误判。
  不剔除的话，`git diff` 里的空行会平白挤进增量分母，把 Go 的比例冲淡；
- **块尾的 `}` 与块内注释仍计入**：要剔除就得真解析 Go 源码，代价与收益不成正比。
  因此同样一份代码，Go 的行数分母会比 Java 口径略大 —— 跨语言比较绝对数值时需知晓。

C++ 没有这个问题：gcov 直接给出「本行无代码」（输出里的 `-`），
而且用 `N*` 标出「跑过但行内还有块没跑到」，正是 JaCoCo 的 PARTIAL。

Rust 介于两者之间：`llvm-cov export --format=lcov` 的 `DA:` 记录只列可执行行，
空行与注释压根不出现，所以 EMPTY 是天然的、无需像 Go 那样另行剔除；
但它不输出 `BRDA` 分支记录，因此没有 PARTIAL。
**四种语言里 Go 与 Rust 是三态，Java 与 C++ 是四态。**

### P3 验收标准的执行情况（如实记录）

P2 定下的判据是「接入新语言不修改 Analyzer / Web 任何代码，只新增采集器与归一化适配器」。
**P3 做到了**：`CoverageAnalyzer`、`GoCoverageAnalyzer`、`GitService`、Web 一行未改，
新增的只有 `CppProbeClient` + `CppCoverageAnalyzer`，其余是接线
（`ProbeEndpoint` 认 `cpp://`、`CoverageProperties` 加配置项、`CoverageService` 加一个分支）。
这反过来说明 P2 时改 `analyze()` 的路径基准确实是在补原设计的缺陷，而不是分层不成立。

计划书 §4.3 原本给 C++ 设想的是 **LD_PRELOAD 注入构造函数** 或 **`kill -SIGUSR1` 触发
`__gcov_dump()`**，并把「LD_PRELOAD 是否可行」列为 P0 级 POC（遗留项 V1）。
实际走的是第三条路：**探针放在独立编译单元里，用全局对象的构造函数自动启动** ——
与 Go 的 build tag 探针文件同一个手法，业务代码不 include 也不调用任何东西。
它不依赖 POSIX（Windows 上根本没有 LD_PRELOAD 和 SIGUSR1），更干净，**V1 因此作废**。

### P4 验收标准的执行情况（如实记录）

判据仍是「接入新语言不修改 Analyzer / Web 任何代码」。**P4 做到了**：
`CoverageAnalyzer`、`GoCoverageAnalyzer`、`CppCoverageAnalyzer`、`GitService`、Web
一行未改，新增的只有 `RustProbeClient` + `RustCoverageAnalyzer`，其余是接线
（`ProbeEndpoint` 认 `rust://`、`CoverageProperties` 加配置项、`CoverageService` 加一个分支）。
连着 P3 一起看，分层在第三、第四种语言上都成立了 —— P2 那次改 `analyze()` 确实是
补原设计的缺陷，不是分层不成立。

计划书 §4.4 给 Rust 排的优先级是「A. `LLVM_PROFILE_FILE=%c` 连续模式（零改动）→
不通则退回 B. minicov（引入依赖 + 写调用代码，需重新向用户确认侵入性）」。
**两条都没走**：`%c` 官方文档自己写着 Windows 需要较大改动，而 minicov 要动 Cargo.toml
和业务代码，正是本项目要避免的。实际走的是与 Go / C++ 同一个手法 ——
**探针放在独立编译单元里，靠启动期自动执行的构造函数拉起**，
在 Rust 上具体是链进一个 C 目标文件、用 `.CRT$XCU` 段注册。
连 `Cargo.toml` 都不用动，比计划里任何一条都干净，**V2 / V3 因此一并作废**。

真正的意外在别处：**Windows 上 gnu 目标根本用不了 LLVM 覆盖率**（详见 §四 Rust 一节），
被迫引入 msvc 目标 + xwin + lld-link 这一套。这是平台侧的环境依赖，不是代码问题，
但换机器部署时会被绊住，所以记在这里。

---

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
| Go | `go tool covdata textfmt` | `coverage.go-tool` |
| C++ | `gcov -t -r`、`gcov-tool merge` | `coverage.gcov-tool`、`coverage.gcov-merge-tool` |
| Rust | `llvm-profdata merge -sparse`、`llvm-cov export --format=lcov` | `coverage.llvm-profdata-tool`、`coverage.llvm-cov-tool` |

**Rust 的两个工具版本必须与 rustc 匹配**，系统上随便一个 LLVM 往往对不上，
会以「不认识的 profraw 版本」失败。取 rustup 的 `llvm-tools` 组件最稳，
它在 `<toolchain>/lib/rustlib/x86_64-pc-windows-gnu/bin/` 下。

**别把某台机器的绝对路径写死进 application.yml。** 本机的 MinGW-w64 装在
`~/devtools/mingw64`，rustup 在 `~/devtools/rustup`，
由 `run_local.sh` 的 `MINGW_HOME` / `RUSTUP_HOME` 放进 PATH。

### 前端：零构建的 Vue 3 + Element Plus

`static/vendor/` 里是**提交进仓库的 UMD 产物**（vue / element-plus / icons），页面用浏览器
原生 ES module 组装（`app.js` + `store.js` + `views/*.js`），**不引入 node / npm / Vite**，
`pom.xml` 与 `run_local.sh` 一行不用改。平台面向内网，不能假设有外网 —— CDN 拿不到的表现是
「HTTP 200 的空白页」，最难排查的一种坏法。

三个反复会绊到的点：

1. **改了 `static/` 必须重新 `mvn package`**。平台是从 jar 启动的，classpath 里的静态资源
   不会跟着源目录变；只重启平台看到的还是旧页面，而且看不出任何异样。
2. **Vue 模板表达式只认白名单里的全局量**（`Math`、`Date` 之类）。模板里直接写 `location`
   会求值成 `undefined`，表现是「点了没反应」，只有控制台里才有一行 TypeError ——
   要用的全局量必须从 `setup()` 里暴露出去。
3. **E2E 钩子一律用 `data-testid`，不复用样式选择器**。旧前端的 24 个 `id=` 既是样式选择器
   又是测试契约，改个布局就断；而且改成 Vue 之后 HTML 源码里只剩一个挂载点，
   断言源码等于什么都没断言 —— 所以渲染断言必须开真实浏览器（`scripts/ui_verify.js`）。

**数据库凭据只在本机的 `.env.local` 里，该文件不入库**（见 `.gitignore`）。
`application.yml` 写的是 `${COVERAGE_DB_URL:…}` / `${COVERAGE_DB_USER:root}` /
`${COVERAGE_DB_PASSWORD:}` 三个占位符，由 `run_local.sh` 读 `.env.local` 注入。
**换机器部署时要照着重建这个文件**，否则跨构建趋势（`/api/coverage/trend`）
会以 `available:false` + 原因返回 —— 采集与染色不受影响，只是历史存不进去。

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

### 四个反复踩到的坑

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
4. **`verify` 假定平台已经在跑**。它只重启被测实例，不启动平台；平台没起时
   第一套用例就以「连接被拒绝」失败。更麻烦的是它已经把 8 个被测实例拉起来了，
   而 Java 实例握着 `platform/target/agent/jacocoagent.jar`，接着跑 `start` 会在
   `mvn clean` 删不掉这个 jar 上失败。**从全停状态恢复的顺序是
   `stop` → `start` → `verify`**，别直接跑 `verify`。

---

## 四、被测服务的启动约定

源码零改动，只加启动参数：

```
-javaagent:jacocoagent.jar=includes=com.shop.*,output=tcpserver,address=localhost,port=6300,sessionid=<40位commit>[-dirty]
```

`sessionid` 是平台唯一能拿到的「实例自报构建版本」，增量口径靠它校验行号能否对齐。
不配它则增量功能不可用（平台会明确报错，而不是给出错位的结果）。

多实例时每台用不同的探针端口，逐个填进平台的 `coverage.instances`。
**各实例的 sessionid 必须完全一致（含 `-dirty` 后缀）**：commit 相同但一台脏一台净，
加载的是两份不同的字节码，平台会判为版本冲突并拒绝出增量报告。

### Go

Go 编译为原生机器码，运行期没有可改写的中间表示，**必须带插桩重新编译一次**：

```bash
go build -cover -covermode=atomic -tags=goverage -o demo-go.exe .
COVERAGE_ADDR=127.0.0.1:6400 COVERAGE_BUILD_ID=<40位commit>[-dirty] ./demo-go.exe
```

但**既有业务源码一行不改**：探针 `coverage_agent.go` 由 build tag `goverage` 守卫、
与 `main` 同包，`init()` 自动执行，业务代码不 import 也不调用任何东西；
不带 tag 的生产构建里这个文件根本不参与编译。

- `-covermode=atomic` 是硬要求：`runtime/coverage.ClearCounters()` 只在 atomic 模式下可用，
  否则场景归因的清零会失败（Go 会明确报错，平台据此拒绝，不会静默把上一轮算进来）。
- `COVERAGE_BUILD_ID` 等价于 JaCoCo 的 `sessionid`，两种语言共用同一套版本校验。
  **脏标记要按全部被测源码根一起判定**：各语言各算各的话，只改了 Go 源码时
  Java 报 `<sha>`、Go 报 `<sha>-dirty`，平台判成「实例间版本不一致」，
  把人引去核对版本，而真正的原因是有未提交的改动。
- `COVERAGE_ADDR` **默认只绑回环**（`127.0.0.1:6400`），与 Java 侧的 `address=localhost` 对齐。
  写成 `:6400` 会绑到所有网卡，而 `/coverage/clear` 能清零计数器 ——
  等于把「正在录的那个场景」交给同网段的任何人随手作废。
- 探针地址在平台侧写作 `go://host:port`；不写语言前缀默认 `java`。

### C++

与 Go 同理，必须带插桩重新编译一次；**既有业务源码同样一行不改** ——
探针 `coverage_agent.cpp` 是独立编译单元，靠全局对象的构造函数（早于 `main` 执行）
自动启动，业务代码不 include 也不调用它任何东西；正常构建不编译这个文件。

```bash
g++ -std=c++17 --coverage -c order.cpp -o <绝对路径>/order.o   # 业务代码插桩
g++ -std=c++17            -c coverage_agent.cpp -o .../coverage_agent.o  # 探针不插桩
g++ -o demo-cpp.exe .../*.o --coverage -lws2_32

GCOV_PREFIX=<每实例一个目录> GCOV_PREFIX_STRIP=99 \
COVERAGE_DATA_DIR=<同一个目录> COVERAGE_ADDR=127.0.0.1:6500 \
COVERAGE_BUILD_ID=<40位commit>[-dirty] ./demo-cpp.exe
```

**gcov 运行期 API 的两条硬事实**（POC 实测得出，弄错就是静默错误的覆盖数据）：

1. `__gcov_dump()` **只生效一次**，之后必须 `__gcov_reset()` 重新武装，否则后续 dump
   什么都不写。而「没有 .gcda」与「计数器全零」在 gcov 输出里长得一模一样，全是 `#####`
   —— 这个坑第一次就踩到了，靠加一步「检查 .gcda 是否真的产生」才发现；
2. `.gcda` 写入时会与磁盘上已有内容**合并**。所以「dump + reset」交出的是累计值而非增量，
   轮询不丢历史；但真要清零就必须连 `.gcda` 一起删掉，光调 `__gcov_reset()` 是不够的。

其余约定：

- 编译的**工作目录必须是源码根**：`.gcno` 里记的是编译时的相对源码名，
  平台侧的 `gcov` 要在同一目录下才找得到源码（`coverage.cpp-source-root`）。
- **对象文件必须用绝对路径**：`.gcda` 的落点是编译期写死进产物的，用相对路径会跟着
  进程的工作目录跑。
- `GCOV_PREFIX` 是多实例的关键：不分开的话两个实例会往同一个 `.gcda` 互相覆盖，
  聚合出来的是「最后写的那一份」。`STRIP=99` 把目录层级剥光，目录才是平的。
  **已知限制**：剥平之后，不同目录下同名的 `.cpp` 会撞车。本 demo 无同名文件；
  真接大型工程时要改成保留目录结构，并相应改 `gcov -o` 的调用方式。
- `.gcno` 是编译期产物，平台靠 `coverage.cpp-objects-dir` 找到它 ——
  相当于 Java 的 `classes-dir`，缺了它解不出任何行号。
- 探针地址在平台侧写作 `cpp://host:port`。

### Rust

同样必须带插桩重新编译一次；**源码零改动做得比 Go / C++ 还彻底 —— 连 `Cargo.toml`
都不用动**：探针 `coverage_agent.c` 是单独用 gcc 编译的 `.o`，构建时经 `-C link-arg`
注入，靠 `.CRT$XCU` 段里的函数指针在 `main` 之前自动执行（MSVC 启动代码会遍历该段）。
业务代码里没有任何一处提到它，正常构建也不会带上它。

```bash
gcc -c coverage_agent.c -o <绝对路径>/coverage_agent.o -mno-stack-arg-probe -O1
RUSTFLAGS="-C instrument-coverage \
  -C link-arg=<绝对路径>/coverage_agent.o -C link-arg=ws2_32.lib \
  -L native=<xwin>/crt/lib/x86_64 -L native=<xwin>/sdk/lib/um/x86_64 \
  -L native=<xwin>/sdk/lib/ucrt/x86_64 \
  -C linker=<toolchain>/lib/rustlib/x86_64-pc-windows-gnu/bin/rust-lld.exe \
  -C linker-flavor=lld-link" \
  cargo build --release --target x86_64-pc-windows-msvc

LLVM_PROFILE_FILE=<每实例一个文件>.profraw COVERAGE_ADDR=127.0.0.1:6600 \
COVERAGE_BUILD_ID=<40位commit>[-dirty] ./demo-service-rust.exe
```

**Windows 上必须编成 `x86_64-pc-windows-msvc`，gnu 目标走不通**（实测结论，
别再试第二次）：

1. 官方只给 msvc 目标发 `profiler_builtins`，gnu 目标上 `-C instrument-coverage`
   直接 E0463；
2. 想用 `-Z build-std=...,profiler_builtins` 自建也不行 —— 它的 `build.rs` 要
   compiler-rt 源码（`RUST_COMPILER_RT_FOR_PROFILER`），备齐之后能编出来，
   但**链出来的 exe 根本加载不了**（"not a valid application for this OS platform"）：
   LLVM 把覆盖率元数据放在 MSVC 风格的 `$`-分组 COFF 段里，GNU ld 排不出正确的布局。

因此路线是「msvc 目标 + rustup 自带的 `rust-lld`（`lld-link` 模式）+ xwin 拉来的
CRT/SDK 导入库」，**不装 Visual Studio**。xwin 只下载库文件（约 630MB）。

**LLVM 运行期 API 的两条硬事实**（POC 实测得出，弄错就是静默错误的覆盖数据）：

1. `__llvm_profile_write_file()` **可以反复调用**，不像 gcov 的 `__gcov_dump()`
   那样只生效一次 —— 这一点比 C++ 省事；
2. 但它写入时会与磁盘上已有的 `.profraw` **合并**。所以每次 dump 前必须先把文件删掉，
   否则交回的是「历次累计 + 本次」的重复叠加；真要清零时，
   光调 `__llvm_profile_reset_counters()` 也不够，同样得连文件一起删。

其余约定：

- `LLVM_PROFILE_FILE` **每个实例必须指向不同的文件**：LLVM 运行时按这个路径落盘，
  共用一个文件的话两个实例互相覆盖，聚合出来的是「最后写的那一份」
  —— 与 C++ 侧 `GCOV_PREFIX` 要解决的是同一个问题。
  而且**必须是字面路径，不能用 `%p` / `%m` 之类的模式**：探针要按同一个字符串
  删掉旧文件（见上面第 2 条），模式由 LLVM 展开，探针删不到，
  交回的就成了历次累计的叠加 —— 而这在界面上看不出任何异样。
- 行号信息在产物自带的 coverage mapping 里，所以平台要配 `coverage.rust-binary`
  指向**产物本身**（相当于 Java 的 `classes-dir`、C++ 的 `.gcno`）。
- 探针用 MinGW 的 gcc 编译却要链进 MSVC ABI 的产物，因此 `-mno-stack-arg-probe`
  不能省（否则出现 MSVC 侧没有的 `___chkstk_ms` 符号），
  探针内部也全程只调 Win32 API，一句 CRT 都不碰。
- `COVERAGE_ADDR` 默认只绑回环（`127.0.0.1:6600`），与其余三种语言一致。
- 探针地址在平台侧写作 `rust://host:port`。
