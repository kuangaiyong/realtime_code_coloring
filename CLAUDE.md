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
| 8 | 多实例聚合 + 实例间版本校验 | `scripts/e2e_multi_instance.py`（Java）、`scripts/e2e_go.py`（Go） | 各实例覆盖取并集；掉线降级为 PARTIAL 并点名；实例间版本不一致时增量返回 409 |
| 9 | Go 采集与归一化（多语言共存） | `scripts/e2e_go.py` | Go 行级染色与 Java 同等质量；清零生效；两种语言共存于同一套口径且路径均以仓库根为基准；跨语言场景归因互不越界 |

> **两种语言的多实例聚合走的是两条代码路径**，因此 #8 需要两个脚本各测一次：
> Java 在 exec 层按探针取或，Go 把多份 meta/counters 一起交给 `covdata` 按块求和。
> 合并出错的表现是「几行没变绿」——静默少算，界面上看不出是 bug，只能靠用例守。

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

---

## 三、验证方式（全局规则 2：禁止 mock）

一条命令跑完全部验收：

```bash
bash scripts/run_local.sh verify
```

它会依次重启**全部四个**被测实例（2 个 Java + 2 个 Go）复位业务状态 → 跑 6 套 E2E。
全部为真实服务、真实探针、真实 git、真实 HTTP，**不允许用 mock / 桩 / 假数据通过验证**。

每种语言都起两个实例，是因为「多实例聚合」在两种语言下是两条代码路径（见 §二 的注）。

多实例用例需要摆布 Java 2 号实例，起停 JVM 由 `run_local.sh` 的
`demo2-stop|demo2-start|demo2-mismatch|demo2-dirty` 子命令负责。

**验证必须在工作树干净（被测源码已提交）时跑**：源码一脏，被测实例自报的
sessionid 就带 `-dirty`，平台按设计拒绝出增量报告，增量与漂移相关的用例必然失败。
所以本项目的顺序是「先在 dev 提交、再跑 verify」，而不是反过来。

单测：`mvn -B test`（真实 socket、真实 git 仓库，同样无 mock）。

### 工具链依赖

平台侧需要 **JDK 17 + Maven + Go**。Go 不只是被测服务需要 —— **平台自己**要调
`go tool covdata textfmt` 做归一化：covmeta/covcounters 是 Go 内部二进制格式，
没有对外稳定契约，自行解析必然随版本崩。
默认取 PATH 里的 `go`；装在非常规位置时用 `coverage.go-tool` 指定绝对路径
（**别把某台机器的绝对路径写死进 application.yml**）。

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
