# P1：IR 补分支与方法覆盖率 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 IR 除行覆盖外还带上分支与方法覆盖，四种语言各自填上能拿到的、明确留空拿不到的，API 增字段透出——前端一行不改。

**Architecture:** `FileCoverage` / `LineCoverage` 两个 record 各加字段，类型用 `Integer` 而非 `int`，`null` 表示「这门语言不提供」。三个 Analyzer（Java/C++/Rust）各改一处解析，Go 一行不动。`ProjectRuntime` 的 `restrictOne` 处理增量口径下的裁剪语义，`summary` / `fileDetail` 两处序列化增字段。

**Tech Stack:** Java 17 · Maven · JaCoCo `org.jacoco.core.analysis` · gcov（MinGW-w64）· llvm-cov / llvm-profdata（rustup llvm-tools）· JUnit 5

**Spec:** `docs/specs/2026-08-31-branch-method-coverage-design.md`

## Global Constraints

以下约束对每一个任务都生效，不再逐条重复：

- **`Integer` 而非 `int`，`null` 表示「这门语言不提供」。** `0/0`（有分支概念但这行没分支）与 `null`（根本没有分支概念）必须分得开——混成 0 会把 Go 说成「一个分支都没测」。
- **禁止 mock / 桩 / 假数据**（CLAUDE.md 规则 2）。测试喂的必须是真实字节码、真实 gcov 输出文本、真实 lcov 文本。
- **门禁接口一个字不改。** 只增字段、不改语义，`e2e_incremental.py` 与 CI 里那句 `curl -f` 不受影响。
- **P1 前端一行不改。** `platform/src/main/resources/static/` 下任何文件都不动。
- **只在 `dev` 分支提交**（CLAUDE.md 项目约定，只有 main + dev 两个分支）。
- **一切输出用中文**，包括 commit message、代码注释、测试方法名。例外只有标识符、API 字段名、命令、日志与报错原文。
- **改了 `static/` 才需要重新 `mvn package`**——本期不碰前端，因此单测阶段只需 `mvn -B test`。
- 每个任务结束时工作树必须干净（被测源码已提交），否则实例自报的 sessionid 带 `-dirty`，增量相关用例必然失败。

## 四种语言的能力矩阵（决定每个 Analyzer 填什么）

| | 分支 | 方法 |
|---|---|---|
| Java | 实际值（没有分支的行是 `0/0`，永不为 null） | 实际值 |
| C++ | 实际值（滤掉 `(throw)`） | 实际值 |
| Rust | **`null`** | 实际值 |
| Go | **`null`** | **`null`** |

---

### Task 1: IR 扩展与全部构造点适配

纯结构改动，不解析任何新数据。目标是字段存在、编译通过、现有 110 个单测一个不挂。

**Files:**
- Modify: `platform/src/main/java/com/rtcc/platform/model/FileCoverage.java`
- Modify: `platform/src/main/java/com/rtcc/platform/collector/CoverageAnalyzer.java:50,59`
- Modify: `platform/src/main/java/com/rtcc/platform/collector/CppCoverageAnalyzer.java:197,212`
- Modify: `platform/src/main/java/com/rtcc/platform/collector/GoCoverageAnalyzer.java:163,172`
- Modify: `platform/src/main/java/com/rtcc/platform/collector/RustCoverageAnalyzer.java:104,130`
- Modify: `platform/src/main/java/com/rtcc/platform/service/ProjectRuntime.java:1087`
- Test: `platform/src/test/java/com/rtcc/platform/model/FileCoverageTest.java`（新建）

**Interfaces:**
- Produces: `FileCoverage(String path, String packageName, String sourceFileName, int coveredLines, int missedLines, double ratio, Integer coveredBranches, Integer missedBranches, Integer coveredMethods, Integer missedMethods, List<LineCoverage> lines)` 与 `FileCoverage.LineCoverage(int line, String status, Integer coveredBranches, Integer missedBranches)`。后续所有任务都按这两个签名构造。

- [ ] **Step 1: 写失败的测试**

新建 `platform/src/test/java/com/rtcc/platform/model/FileCoverageTest.java`：

```java
package com.rtcc.platform.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IR 里「这门语言不提供这个指标」必须能与「有这个指标但值是 0」分开。
 *
 * 用 Integer 而不是 int，就是为了留出 null 这一档：Go 的 coverage profile 里
 * 压根没有分支这个概念，填 0 的话页面上会显示「0% 分支覆盖」——
 * 读的人会以为 Go 的分支一个都没测，而真相是那里没有分支这回事。
 * 这正是本项目最不能接受的那类静默错误：数字是假的，界面上却看不出是假的。
 */
class FileCoverageTest {

    @Test
    void 不提供该指标时字段为null而不是0() {
        FileCoverage go = new FileCoverage(
                "demo-service-go/main.go", "demo-service-go", "main.go",
                20, 44, 31.25,
                null, null, null, null,
                List.of(new FileCoverage.LineCoverage(12, "COVERED", null, null)));

        assertNull(go.coveredBranches(), "Go 没有分支概念，必须是 null 而不是 0");
        assertNull(go.missedBranches(), "Go 没有分支概念，必须是 null 而不是 0");
        assertNull(go.coveredMethods(), "Go 的 profile 里没有函数记录，必须是 null");
        assertNull(go.missedMethods(), "Go 的 profile 里没有函数记录，必须是 null");
        assertNull(go.lines().get(0).coveredBranches(), "行级同理");
    }

    @Test
    void 有该指标但这行没有分支时是零而不是null() {
        // Java 侧每一行都问得出「这行有几个分支」，答案可以是 0，但不会是「不知道」
        FileCoverage.LineCoverage plain = new FileCoverage.LineCoverage(42, "COVERED", 0, 0);

        assertEquals(0, plain.coveredBranches(), "这行确实没有分支，是 0 不是 null");
        assertEquals(0, plain.missedBranches());
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

```bash
mvn -B -pl platform test -Dtest=FileCoverageTest
```

预期：编译错误 `constructor FileCoverage in record FileCoverage cannot be applied to given types`——字段还不存在。

- [ ] **Step 3: 改 record**

`platform/src/main/java/com/rtcc/platform/model/FileCoverage.java` 整体替换为：

```java
package com.rtcc.platform.model;

import java.util.List;

/**
 * 单个源文件的覆盖结果。
 *
 * status 取值 EMPTY / MISSED / PARTIAL / COVERED，直接对应 JaCoCo 的四种行状态。
 * 注意：JaCoCo 的探针是布尔型的，只记录「是否执行过」，不记录执行次数，
 * 因此这里没有 hitCount 字段。
 *
 * <b>分支与方法用 Integer 而非 int，null 表示「这门语言不提供这个指标」。</b>
 * 0/0（有这个概念，但这一行没有分支）与 null（这门语言根本没有分支概念）
 * 必须分得开：混成 0 的话，页面上 Go 会显示「0% 分支覆盖」，
 * 读的人会以为 Go 的分支一个都没测，而真相是那里压根没有分支这回事。
 * 四种语言的能力差异见 docs/specs/2026-08-31-branch-method-coverage-design.md §2。
 */
public record FileCoverage(
        String path,
        String packageName,
        String sourceFileName,
        int coveredLines,
        int missedLines,
        double ratio,
        Integer coveredBranches,
        Integer missedBranches,
        Integer coveredMethods,
        Integer missedMethods,
        List<LineCoverage> lines
) {
    public record LineCoverage(int line, String status,
                               Integer coveredBranches, Integer missedBranches) {}
}
```

- [ ] **Step 4: 让 9 处构造点编译通过（本步不解析新数据，一律填 null）**

`CoverageAnalyzer.java:50` → `lines.add(new FileCoverage.LineCoverage(i, status, null, null));`

`CoverageAnalyzer.java:59` 的 `new FileCoverage(` 参数表里，在 `total == 0 ? 0d : covered * 100d / total,` 之后、`lines` 之前插入四个 `null,`：

```java
            result.put(path, new FileCoverage(
                    path,
                    sf.getPackageName().replace('/', '.'),
                    sf.getName(),
                    covered,
                    missed,
                    total == 0 ? 0d : covered * 100d / total,
                    null, null, null, null,
                    lines
            ));
```

`CppCoverageAnalyzer.java:197` → `current.add(new FileCoverage.LineCoverage(no, status(count), null, null));`

`CppCoverageAnalyzer.java:212` 的 `new FileCoverage(` 在 `lines.isEmpty() ? 0d : covered * 100d / lines.size(),` 之后插入 `null, null, null, null,`

`GoCoverageAnalyzer.java:163` → `ls.add(new FileCoverage.LineCoverage(e.getKey(), hit ? "COVERED" : "MISSED", null, null));`

`GoCoverageAnalyzer.java:172` 的 `new FileCoverage(` 在 `total == 0 ? 0d : covered * 100d / total,` 之后插入 `null, null, null, null,`

`RustCoverageAnalyzer.java:104` → 加 `, null, null` 两个参数

`RustCoverageAnalyzer.java:130` 的 `new FileCoverage(` 在 `covered * 100d / lines.size(),` 之后插入 `null, null, null, null,`

`ProjectRuntime.java:1087` 的 `restrictOne` —— **本步原样透传，增量语义留给 Task 5**：

```java
        return new FileCoverage(f.path(), f.packageName(), f.sourceFileName(), covered, missed, ratio,
                f.coveredBranches(), f.missedBranches(), f.coveredMethods(), f.missedMethods(), kept);
```

- [ ] **Step 5: 跑全部单测，确认 110 + 2 全过**

```bash
mvn -B test 2>&1 | grep -E "Tests run:|BUILD"
```

预期：`Tests run: 112, Failures: 0, Errors: 0` 与 `BUILD SUCCESS`。112 是原有 110 加本任务新增的 2 个。若有既有测试挂掉，说明某处构造点参数插错了位置——record 的参数是按位置绑定的，`ratio` 后面紧跟四个 null 的顺序不能错。

- [ ] **Step 6: 提交**

```bash
git add platform/src/main/java/com/rtcc/platform/model/FileCoverage.java \
        platform/src/main/java/com/rtcc/platform/collector/ \
        platform/src/main/java/com/rtcc/platform/service/ProjectRuntime.java \
        platform/src/test/java/com/rtcc/platform/model/FileCoverageTest.java
git commit -m "IR 加分支与方法字段，用 null 区分「不提供」与「值为 0」

四种语言的指标能力不对等：Go 没有分支也没有函数记录，Rust 拿不到分支。
用 Integer 而非 int，就是为了留出 null 这一档 —— 填 0 的话页面上 Go 会显示
「0% 分支覆盖」，读的人会以为 Go 的分支一个都没测，而那里压根没有分支这回事。

本次只改结构，三个 Analyzer 一律填 null，行为与改动前完全一致（112 个单测全过）。"
```

---

### Task 2: Java 侧解析分支与方法

**Files:**
- Modify: `platform/src/main/java/com/rtcc/platform/collector/CoverageAnalyzer.java`
- Test: `platform/src/test/java/com/rtcc/platform/collector/CoverageAnalyzerTest.java`

**Interfaces:**
- Consumes: Task 1 的 `FileCoverage` / `LineCoverage` 签名。
- Produces: Java 分析结果中 `coveredBranches` / `missedBranches` / `coveredMethods` / `missedMethods` 均非 null。

- [ ] **Step 1: 写失败的测试**

追加到 `CoverageAnalyzerTest.java`（在类内、最后一个 `}` 之前）：

```java
    /**
     * 分支覆盖是这次要补的核心指标。用平台自身的字节码做被分析对象，
     * 空 ExecutionDataStore 表示「探针一次都没命中」——此时分支必须是
     * 「有分母、分子为 0」，而不是 null：Java 侧每一行都问得出这个问题。
     */
    @Test
    void Java侧分支与方法永远有值而不是null() throws Exception {
        Map<String, FileCoverage> result = analyzer.analyze(new ExecutionDataStore(), classesDir(), null);

        FileCoverage f = result.get("com/rtcc/platform/collector/ProbeEndpoint.java");
        assertNotNull(f, "未分析到 ProbeEndpoint.java");

        assertNotNull(f.coveredBranches(), "Java 拿得到分支，不该是 null");
        assertNotNull(f.missedBranches(), "Java 拿得到分支，不该是 null");
        assertEquals(0, f.coveredBranches(), "没有执行数据时不该有已覆盖分支");
        assertTrue(f.missedBranches() > 0,
                "ProbeEndpoint 里有 if / switch，未覆盖分支数应大于 0，实际 " + f.missedBranches());

        assertNotNull(f.coveredMethods(), "Java 拿得到方法数，不该是 null");
        assertEquals(0, f.coveredMethods(), "没有执行数据时不该有已覆盖方法");
        assertTrue(f.missedMethods() > 0,
                "应识别出未覆盖的方法，实际 " + f.missedMethods());
    }

    /**
     * 行级分支是源码区菱形标记的数据来源。没有分支的行必须是 0/0 而不是 null ——
     * null 在前端表示「这门语言不提供」，会让整列菱形消失
     */
    @Test
    void 行级分支在没有分支的行上是零而不是null() throws Exception {
        Map<String, FileCoverage> result = analyzer.analyze(new ExecutionDataStore(), classesDir(), null);

        boolean sawBranchLine = false;
        for (FileCoverage f : result.values()) {
            for (FileCoverage.LineCoverage l : f.lines()) {
                assertNotNull(l.coveredBranches(),
                        "Java 的行级分支不该是 null：" + f.path() + ":" + l.line());
                assertNotNull(l.missedBranches(),
                        "Java 的行级分支不该是 null：" + f.path() + ":" + l.line());
                if (l.missedBranches() > 0) {
                    sawBranchLine = true;
                }
            }
        }
        assertTrue(sawBranchLine, "整个平台的字节码里应至少有一行带未覆盖分支");
    }
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn -B -pl platform test -Dtest=CoverageAnalyzerTest
```

预期：两个新测试 FAIL，报 `expected: not <null>`——当前一律填 null。

- [ ] **Step 3: 实现**

`CoverageAnalyzer.java` 的 `analyze` 方法体内，把行循环与结果构造改成：

```java
            List<FileCoverage.LineCoverage> lines = new ArrayList<>();
            int covered = 0, missed = 0;
            for (int i = sf.getFirstLine(); i <= sf.getLastLine(); i++) {
                ILine line = sf.getLine(i);
                String status = statusOf(line.getStatus());
                if (status == null) {
                    continue;
                }
                // 行级分支是源码区菱形标记的数据来源。没有分支的行拿到的是 0/0，
                // 不是 null —— null 在 IR 里的含义是「这门语言不提供」，而 Java 提供
                ICounter bc = line.getBranchCounter();
                lines.add(new FileCoverage.LineCoverage(i, status,
                        bc.getCoveredCount(), bc.getMissedCount()));
                if ("MISSED".equals(status)) {
                    missed++;
                } else {
                    covered++;
                }
            }

            int total = covered + missed;
            // 文件级的三组计数各取各的，不从行级累加：JaCoCo 已按源文件把外部类、
            // 内部类、匿名类聚合好了，自行累加会在同一行属于两个类时重复计入
            ICounter fileBranches = sf.getBranchCounter();
            ICounter fileMethods = sf.getMethodCounter();
            result.put(path, new FileCoverage(
                    path,
                    sf.getPackageName().replace('/', '.'),
                    sf.getName(),
                    covered,
                    missed,
                    total == 0 ? 0d : covered * 100d / total,
                    fileBranches.getCoveredCount(), fileBranches.getMissedCount(),
                    fileMethods.getCoveredCount(), fileMethods.getMissedCount(),
                    lines
            ));
```

`import org.jacoco.core.analysis.*;` 已在文件顶部，`ILine` 与 `ICounter` 都在这个包里，无需新增 import。

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn -B -pl platform test -Dtest=CoverageAnalyzerTest
```

预期：`Tests run: 7, Failures: 0`（原 5 个加新增 2 个）。

- [ ] **Step 5: 提交**

```bash
git add platform/src/main/java/com/rtcc/platform/collector/CoverageAnalyzer.java \
        platform/src/test/java/com/rtcc/platform/collector/CoverageAnalyzerTest.java
git commit -m "Java 侧解析分支与方法覆盖

JaCoCo 的 ILine.getBranchCounter() 与 ISourceFileCoverage 的
getBranchCounter()/getMethodCounter() 都是现成的，取出来即可。

文件级三组计数各取各的，不从行级累加 —— JaCoCo 已按源文件把外部类、内部类、
匿名类聚合好了，自行累加会在同一行同时属于两个类时（写在一行里的匿名内部类）
重复计入分母。"
```

---

### Task 3: C++ 侧解析分支与方法

gcov 要加 `-b -c` 才输出这两类明细。**`(throw)` 分支必须滤掉**：实测一个几百行的 demo 就有 359 条分支，其中 120 条是编译器为可能抛异常的操作生成的路径，而源码里真正的条件语句只有 32 处。

**Files:**
- Modify: `platform/src/main/java/com/rtcc/platform/collector/CppCoverageAnalyzer.java`
- Test: `platform/src/test/java/com/rtcc/platform/collector/CppCoverageAnalyzerTest.java`

**Interfaces:**
- Consumes: Task 1 的 record 签名。
- Produces: `CppCoverageAnalyzer.parse(String gcovOut, String root)` 从 `private` 改为**包级可见**，供测试直接喂真实 gcov 文本。

- [ ] **Step 1: 开测试缝并写失败的测试**

先把 `CppCoverageAnalyzer.java:175` 的方法签名去掉 `private`：

```java
    /** 包级可见是为了让测试直接喂真实的 gcov 输出文本 —— 起一次真实 gcov 要有 .gcno 与 .gcda */
    Map<String, FileCoverage> parse(String gcovOut, String root) throws IOException {
```

追加到 `CppCoverageAnalyzerTest.java`（类内、最后一个 `}` 之前）：

```java
    /**
     * gcov 加 -b -c 之后的真实输出片段。三处关键形态都在里面：
     * branch 行不带行号（跟在源码行之后）、(throw) 是编译器生成的异常路径、
     * function 行给出调用次数。
     */
    private static final String GCOV_WITH_BRANCHES = String.join("\n",
            "        -:    0:Source:order.cpp",
            "        -:    0:Graph:order.gcno",
            "function _ZN5Order3payEi called 3 returned 100% blocks executed 75%",
            "        3:   41:void Order::pay(int amount) {",
            "        3:   42:    if (amount > 0 && paid) {",
            "branch  0 taken 2 (fallthrough)",
            "branch  1 taken 0",
            "branch  2 never executed (throw)",
            "        2:   43:        settle();",
            "    #####:   44:    } else if (retry) {",
            "branch  0 never executed (fallthrough)",
            "branch  1 never executed",
            "        -:   45:",
            "function _ZN5Order6refundEv called 0 returned 0% blocks executed 0%",
            "    #####:   46:void Order::refund() {}");

    @Test
    void C加加的分支归到它前面那条源码行上() throws Exception {
        CppCoverageAnalyzer a = new CppCoverageAnalyzer(props(null), new CoverageProperties());

        Map<String, FileCoverage> r = a.parse(GCOV_WITH_BRANCHES, "demo-service-cpp");
        FileCoverage f = r.get("demo-service-cpp/order.cpp");
        assertNotNull(f, "没解析出文件：" + r.keySet());

        FileCoverage.LineCoverage l42 = f.lines().stream()
                .filter(l -> l.line() == 42).findFirst().orElseThrow();
        // gcov 的 branch 行不带行号，只能归到最近一条源码行上。归错的表现是
        // 「一条分支都没有」，与「这门语言不提供」长得一模一样
        assertEquals(1, l42.coveredBranches(), "taken 2 的那条算已覆盖");
        assertEquals(1, l42.missedBranches(), "taken 0 的那条算未覆盖；(throw) 那条不该计入");

        FileCoverage.LineCoverage l44 = f.lines().stream()
                .filter(l -> l.line() == 44).findFirst().orElseThrow();
        assertEquals(0, l44.coveredBranches());
        assertEquals(2, l44.missedBranches(), "两条 never executed 都算未覆盖");
    }

    /**
     * C++ 里每个可能抛异常的操作都会生成分支。实测一个 demo 有 359 条分支，
     * 其中 120 条是 (throw)，而源码里真正的条件语句只有 32 处。
     * 不滤掉的话，分支覆盖率报告的其实是异常处理路径的覆盖率。
     */
    @Test
    void 编译器生成的throw分支不计入() throws Exception {
        CppCoverageAnalyzer a = new CppCoverageAnalyzer(props(null), new CoverageProperties());

        FileCoverage f = a.parse(GCOV_WITH_BRANCHES, "demo-service-cpp")
                .get("demo-service-cpp/order.cpp");

        // 文本里一共 5 条 branch，其中 1 条是 (throw)
        assertEquals(4, f.coveredBranches() + f.missedBranches(),
                "5 条 branch 里应有 4 条计入，(throw) 那条要滤掉");
    }

    @Test
    void C加加的方法数来自function行的调用次数() throws Exception {
        CppCoverageAnalyzer a = new CppCoverageAnalyzer(props(null), new CoverageProperties());

        FileCoverage f = a.parse(GCOV_WITH_BRANCHES, "demo-service-cpp")
                .get("demo-service-cpp/order.cpp");

        assertEquals(1, f.coveredMethods(), "pay() called 3，算跑过");
        assertEquals(1, f.missedMethods(), "refund() called 0，算没跑过");
    }
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn -B -pl platform test -Dtest=CppCoverageAnalyzerTest
```

预期：三个新测试 FAIL。前两个报 `expected: <1> but was: <null>`，第三个同理——当前 `parse` 直接跳过所有非源码行。

- [ ] **Step 3: 实现**

改 `runGcov`，加 `-b`（输出分支）与 `-c`（分支给次数而不是百分比）：

```java
    private String runGcov(Path profileDir, List<Path> gcno) throws IOException {
        // -b 输出分支明细，-c 让分支给出执行次数而不是百分比（百分比在 0 次与
        // 未执行之间分不清）。二者是分支覆盖率的唯一来源
        List<String> cmd = new ArrayList<>(List.of(platform.getGcovTool(), "-t", "-r", "-b", "-c",
                "-o", profileDir.toAbsolutePath().toString()));
        gcno.forEach(p -> cmd.add(p.getFileName().toString()));
        Path cwd = Path.of(props.getRepoDir(), props.getCppSourceRoot());
        return exec(cmd, cwd, "gcov");
    }
```

在类里新增两个 pattern 常量（放在已有的 `ROW` 旁边）：

```java
    /** {@code branch  0 taken 2 (fallthrough)} / {@code branch  1 never executed} */
    private static final Pattern BRANCH = Pattern.compile("^branch\\s+\\d+\\s+(.+)$");
    /** {@code function _ZN5Order3payEi called 3 returned 100% blocks executed 75%} */
    private static final Pattern FUNCTION = Pattern.compile("^function\\s+\\S+\\s+called\\s+(\\d+)\\s.*$");
```

把 `parse` 整体替换为：

```java
    /** 包级可见是为了让测试直接喂真实的 gcov 输出文本 —— 起一次真实 gcov 要有 .gcno 与 .gcda */
    Map<String, FileCoverage> parse(String gcovOut, String root) throws IOException {
        Map<String, List<FileCoverage.LineCoverage>> byFile = new LinkedHashMap<>();
        // 逐文件的方法计数。function 行不带行号，只能按「当前是哪个文件」归集
        Map<String, int[]> methodsByFile = new LinkedHashMap<>();
        String currentPath = null;
        List<FileCoverage.LineCoverage> current = null;
        // gcov 的 branch 行不带行号，跟在它所属的源码行之后。必须记住最近一条源码行，
        // 否则分支全部落空 —— 而「一条分支都没有」与「这门语言不提供」长得一模一样
        int lastLineIdx = -1;

        for (String line : gcovOut.split("\r?\n")) {
            Matcher br = BRANCH.matcher(line);
            if (br.matches()) {
                String rest = br.group(1);
                // (throw) 是编译器为可能抛异常的操作生成的路径，不是源码里写的条件。
                // 实测一个 demo 有 359 条分支，120 条是 throw，而源码条件只有 32 处
                if (rest.contains("(throw)") || current == null || lastLineIdx < 0) {
                    continue;
                }
                boolean taken = rest.startsWith("taken") && !rest.startsWith("taken 0");
                FileCoverage.LineCoverage old = current.get(lastLineIdx);
                current.set(lastLineIdx, new FileCoverage.LineCoverage(
                        old.line(), old.status(),
                        old.coveredBranches() + (taken ? 1 : 0),
                        old.missedBranches() + (taken ? 0 : 1)));
                continue;
            }
            Matcher fn = FUNCTION.matcher(line);
            if (fn.matches()) {
                if (currentPath != null) {
                    int[] m = methodsByFile.computeIfAbsent(currentPath, k -> new int[2]);
                    if (Long.parseLong(fn.group(1)) > 0) {
                        m[0]++;
                    } else {
                        m[1]++;
                    }
                }
                continue;
            }

            Matcher m = ROW.matcher(line);
            if (!m.matches()) {
                continue; // call 明细等其余行本切片不用
            }
            String count = m.group(1).strip();
            int no = Integer.parseInt(m.group(2));
            if (no == 0) {
                String text = m.group(3);
                if (text.startsWith("Source:")) {
                    String src = text.substring("Source:".length()).strip().replace('\\', '/');
                    currentPath = root.replace('\\', '/') + "/" + src;
                    current = byFile.computeIfAbsent(currentPath, k -> new ArrayList<>());
                    lastLineIdx = -1;
                }
                continue;
            }
            if (current == null || "-".equals(count)) {
                lastLineIdx = -1; // 非可执行行，后面若跟着 branch 行也无处可归
                continue; // "-" 是非可执行行，与 JaCoCo 的 EMPTY 一样不进 IR
            }
            current.add(new FileCoverage.LineCoverage(no, status(count), 0, 0));
            lastLineIdx = current.size() - 1;
        }
        if (byFile.isEmpty()) {
            throw new IOException("gcov 没有输出任何源码的覆盖数据。"
                    + "请确认 coverage.cpp-source-root 指向编译时的工作目录（.gcno 里记的是相对源码名）");
        }

        Map<String, FileCoverage> result = new LinkedHashMap<>();
        byFile.forEach((path, lines) -> {
            if (lines.isEmpty()) {
                return; // 纯声明的头文件没有可执行行，列进来只会是一行 0/0 的噪声
            }
            int missed = (int) lines.stream().filter(l -> "MISSED".equals(l.status())).count();
            int covered = lines.size() - missed;
            int cb = lines.stream().mapToInt(FileCoverage.LineCoverage::coveredBranches).sum();
            int mb = lines.stream().mapToInt(FileCoverage.LineCoverage::missedBranches).sum();
            int[] fm = methodsByFile.getOrDefault(path, new int[2]);
            int slash = path.lastIndexOf('/');
            result.put(path, new FileCoverage(
                    path,
                    slash < 0 ? "" : path.substring(0, slash).replace('/', '.'),
                    slash < 0 ? path : path.substring(slash + 1),
                    covered, missed,
                    lines.isEmpty() ? 0d : covered * 100d / lines.size(),
                    cb, mb, fm[0], fm[1],
                    lines));
        });
        return result;
    }
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn -B -pl platform test -Dtest=CppCoverageAnalyzerTest
```

预期：`Tests run: 9, Failures: 0`（原 6 个加新增 3 个）。

- [ ] **Step 5: 提交**

```bash
git add platform/src/main/java/com/rtcc/platform/collector/CppCoverageAnalyzer.java \
        platform/src/test/java/com/rtcc/platform/collector/CppCoverageAnalyzerTest.java
git commit -m "C++ 侧解析分支与方法覆盖，滤掉编译器生成的 throw 分支

gcov 加 -b -c 才输出分支明细；-c 不能省，百分比在「0 次」与「未执行」之间分不清。

两个容易写错的地方：
1. branch 行不带行号，跟在它所属的源码行之后 —— 必须记住最近一条源码行，
   归错的表现是「一条分支都没有」，与「这门语言不提供」长得一模一样；
2. (throw) 是编译器为可能抛异常的操作生成的路径，不是源码里写的条件。实测一个
   几百行的 demo 有 359 条分支，其中 120 条是 throw，而源码里真正的条件语句
   只有 32 处 —— 不滤掉的话，这个数字报告的其实是异常处理路径的覆盖率。"
```

---

### Task 4: Rust 侧解析方法（分支保持 null）

实测 `BRF:0`：rustc stable 的 `-C instrument-coverage` 不生成分支数据（要 nightly 的 `-Z coverage-options=branch`）。但 `FNF`/`FNH` 是有值的。

**Files:**
- Modify: `platform/src/main/java/com/rtcc/platform/collector/RustCoverageAnalyzer.java`
- Test: `platform/src/test/java/com/rtcc/platform/collector/RustCoverageAnalyzerTest.java`

**Interfaces:**
- Consumes: Task 1 的 record 签名。`RustCoverageAnalyzer.parse(String lcov)` 已是包级可见，不需改动可见性。
- Produces: Rust 结果的方法计数非 null，分支计数恒为 null。

- [ ] **Step 1: 写失败的测试**

追加到 `RustCoverageAnalyzerTest.java`（类内、最后一个 `}` 之前）：

```java
    /**
     * llvm-cov export --format=lcov 的真实输出片段。
     * FNF/FNH 是函数总数与命中数；BRF:0 是实测结果 —— rustc stable 的
     * -C instrument-coverage 不生成分支数据，要 nightly 的 -Z coverage-options=branch
     */
    private String lcovWithFunctions(Path repo) {
        return String.join("\n",
                "SF:" + repo.resolve("demo-service-rust/src/order.rs"),
                "FN:20,_RNvCs2r1QDoXLnWk_17demo_service_rust11read_target",
                "FN:52,_RNvCs2r1QDoXLnWk_17demo_service_rust6handle",
                "FNDA:3,_RNvCs2r1QDoXLnWk_17demo_service_rust11read_target",
                "FNDA:0,_RNvCs2r1QDoXLnWk_17demo_service_rust6handle",
                "FNF:2",
                "FNH:1",
                "BRF:0",
                "BRH:0",
                "DA:20,3",
                "DA:21,0",
                "LF:2",
                "LH:1",
                "end_of_record");
    }

    @Test
    void Rust拿得到方法数但拿不到分支(@TempDir Path repo) throws Exception {
        RustCoverageAnalyzer a = new RustCoverageAnalyzer(props(repo, "x.exe"), new CoverageProperties());

        FileCoverage f = a.parse(lcovWithFunctions(repo)).get("demo-service-rust/src/order.rs");
        assertNotNull(f, "没解析出文件");

        assertEquals(1, f.coveredMethods(), "FNH:1 —— 一个函数跑过");
        assertEquals(1, f.missedMethods(), "FNF:2 减去 FNH:1");

        // rustc stable 压根不生成分支数据，必须是 null 而不是 0 ——
        // 填 0 会让页面显示「Rust 分支覆盖 0%」，读的人以为一个分支都没测
        assertNull(f.coveredBranches(), "stable 不生成分支数据，必须是 null");
        assertNull(f.missedBranches(), "stable 不生成分支数据，必须是 null");
        assertNull(f.lines().get(0).coveredBranches(), "行级同理");
    }
```

若测试类还没 import `FileCoverage`，它已在文件头部（第 5 行）；`@TempDir` 与 `Path` 也都已 import。

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn -B -pl platform test -Dtest=RustCoverageAnalyzerTest
```

预期：FAIL，`expected: <1> but was: <null>`。

- [ ] **Step 3: 实现**

在 `parse` 的循环里，`SF:` 分支后新增两个 `else if`，并用一个 map 记录逐文件的函数计数。完整改动：

在方法开头 `Map<String, List<FileCoverage.LineCoverage>> byFile = ...` 之后加：

```java
        // FNF/FNH 是文件级汇总，直接用；不从 FN/FNDA 逐条累加 ——
        // 泛型单态化会让同一个函数出现多条 FN 记录，逐条累加会把分母放大
        Map<String, int[]> fnByFile = new LinkedHashMap<>();
        String currentPath = null;
```

把 `SF:` 那一段改成同时记住 currentPath：

```java
            if (line.startsWith("SF:")) {
                files++;
                String rel = toRepoRelative(line.substring(3).strip(), repo);
                // 只统计 Rust 源码根之下的文件：依赖库的代码不是被测对象
                boolean wanted = rel != null && rel.startsWith(root + "/");
                currentPath = wanted ? rel : null;
                current = wanted ? byFile.computeIfAbsent(rel, k -> new ArrayList<>()) : null;
            } else if (line.startsWith("FNF:") && currentPath != null) {
                fnByFile.computeIfAbsent(currentPath, k -> new int[2])[0] =
                        Integer.parseInt(line.substring(4).strip());
            } else if (line.startsWith("FNH:") && currentPath != null) {
                fnByFile.computeIfAbsent(currentPath, k -> new int[2])[1] =
                        Integer.parseInt(line.substring(4).strip());
            } else if (line.startsWith("DA:") && current != null) {
```

`DA:` 那一段里构造 LineCoverage 时补两个 null：

```java
                    current.add(new FileCoverage.LineCoverage(
                            Integer.parseInt(kv[0].strip()), count > 0 ? "COVERED" : "MISSED",
                            null, null));
```

`end_of_record` 那一段同时清掉 currentPath：

```java
            } else if (line.startsWith("end_of_record")) {
                current = null;
                currentPath = null;
            }
```

最后的结果构造改成：

```java
        byFile.forEach((path, lines) -> {
            if (lines.isEmpty()) {
                return;
            }
            int missed = (int) lines.stream().filter(l -> "MISSED".equals(l.status())).count();
            int covered = lines.size() - missed;
            // fn[0]=FNF 总数，fn[1]=FNH 命中数
            int[] fn = fnByFile.getOrDefault(path, new int[2]);
            int slash = path.lastIndexOf('/');
            result.put(path, new FileCoverage(
                    path,
                    slash < 0 ? "" : path.substring(0, slash).replace('/', '.'),
                    slash < 0 ? path : path.substring(slash + 1),
                    covered, missed,
                    covered * 100d / lines.size(),
                    // 分支恒为 null：实测 BRF:0，rustc stable 的 -C instrument-coverage
                    // 不生成分支数据（要 nightly 的 -Z coverage-options=branch）。
                    // 填 0 会让页面显示「Rust 分支覆盖 0%」，读的人以为一个分支都没测
                    null, null,
                    fn[1], fn[0] - fn[1],
                    lines));
        });
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn -B -pl platform test -Dtest=RustCoverageAnalyzerTest
```

预期：`Tests run: 11, Failures: 0`（原 10 个加新增 1 个）。

- [ ] **Step 5: 提交**

```bash
git add platform/src/main/java/com/rtcc/platform/collector/RustCoverageAnalyzer.java \
        platform/src/test/java/com/rtcc/platform/collector/RustCoverageAnalyzerTest.java
git commit -m "Rust 侧解析方法覆盖，分支明确留 null

实测 BRF:0 —— rustc stable 的 -C instrument-coverage 不生成分支数据，
要 nightly 的 -Z coverage-options=branch。为一个诊断指标换掉整条工具链不划算，
所以分支恒为 null；填 0 会让页面显示「Rust 分支覆盖 0%」，读的人以为一个都没测。

方法数取 FNF/FNH 这两个文件级汇总，不从 FN/FNDA 逐条累加 ——
泛型单态化会让同一个函数出现多条 FN 记录，逐条累加会把分母放大。

顺带纠正 CLAUDE.md 的一处记录：那里写「llvm-cov 不输出 BRDA」，实际是
llvm-cov export 默认会导出（故有 --skip-branches 开关），只是 stable 没生成。"
```

---

### Task 5: 增量口径下的裁剪语义

`restrictOne` 把文件裁剪到只剩 diff 命中的行。Task 1 里它是原样透传的——有了真实数据之后必须修正：**分支从保留下来的行累加，方法置 null**。

方法置 null 的理由：一个方法通常只有几行落在 diff 里，「这个方法覆盖了没有」在增量口径下答不上来。透传全量的方法数会让人以为那是这次改动的方法覆盖。

**Files:**
- Modify: `platform/src/main/java/com/rtcc/platform/service/ProjectRuntime.java`（`restrictOne`）
- Test: `platform/src/test/java/com/rtcc/platform/service/ProjectRuntimeTest.java`

**Interfaces:**
- Consumes: Task 2/3/4 产出的带分支与方法的 `FileCoverage`。
- Produces: 增量口径下 `coveredMethods`/`missedMethods` 恒为 null；分支为保留行的累加值（若行级分支为 null 则为 null）。

- [ ] **Step 1: 写失败的测试**

追加到 `ProjectRuntimeTest.java`（类内、最后一个 `}` 之前）。先确认文件头有这几个 import，缺哪个补哪个：

```java
import com.rtcc.platform.model.FileCoverage;
import java.util.List;
import java.util.Map;
import java.util.Set;
```


```java
    /**
     * 增量口径下方法覆盖答不上来：一个方法通常只有几行落在 diff 里，
     * 透传全量的方法数会让人把它读成「这次改动的方法覆盖率」。
     * 分支则可以从保留下来的行累加 —— 那本来就是逐行的。
     */
    @Test
    void 增量裁剪后分支按保留行累加而方法置空() throws Exception {
        FileCoverage full = new FileCoverage(
                "a/B.java", "a", "B.java", 3, 1, 75d,
                6, 2, 4, 1,
                List.of(new FileCoverage.LineCoverage(10, "COVERED", 2, 0),
                        new FileCoverage.LineCoverage(11, "PARTIAL", 1, 1),
                        new FileCoverage.LineCoverage(12, "COVERED", 3, 1),
                        new FileCoverage.LineCoverage(13, "MISSED", 0, 0)));

        // 只保留 11、12 两行（模拟 git diff 只命中这两行）
        FileCoverage cut = ProjectRuntime.restrictOneForTest(full, Set.of(11, 12));

        assertEquals(2, cut.lines().size());
        assertEquals(4, cut.coveredBranches(), "11 行 1 个 + 12 行 3 个");
        assertEquals(2, cut.missedBranches(), "11 行 1 个 + 12 行 1 个");
        assertNull(cut.coveredMethods(), "增量口径下方法覆盖答不上来，必须置空");
        assertNull(cut.missedMethods(), "增量口径下方法覆盖答不上来，必须置空");
    }

    /** Go 的行级分支本来就是 null，累加不能把它变成 0 —— 那等于说「有分支但没测」 */
    @Test
    void 不提供分支的语言裁剪后仍是空而不是零() throws Exception {
        FileCoverage go = new FileCoverage(
                "demo-service-go/main.go", "demo-service-go", "main.go", 1, 1, 50d,
                null, null, null, null,
                List.of(new FileCoverage.LineCoverage(10, "COVERED", null, null),
                        new FileCoverage.LineCoverage(11, "MISSED", null, null)));

        FileCoverage cut = ProjectRuntime.restrictOneForTest(go, Set.of(10));

        assertNull(cut.coveredBranches(), "Go 没有分支概念，裁剪后仍该是 null");
        assertNull(cut.missedBranches(), "Go 没有分支概念，裁剪后仍该是 null");
    }
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn -B -pl platform test -Dtest=ProjectRuntimeTest
```

预期：编译失败 `cannot find symbol: method restrictOneForTest`。

- [ ] **Step 3: 实现**

`ProjectRuntime.java` 里把 `restrictOne` 的实现整体搬进一个包级静态方法，实例方法转调它——
裁剪是纯函数，为测它去起一整个 `ProjectRuntime`（要连探针、要 git 仓库）不值当：

```java
    private FileCoverage restrictOne(FileCoverage f, Set<Integer> wanted) {
        return restrictOneForTest(f, wanted);
    }

    /**
     * 把一个文件裁剪到只剩 wanted 里的行。静态且包级可见，供单测直接验证。
     *
     * 分支从保留下来的行累加；<b>方法置空</b> —— 一个方法通常只有几行落在 diff 里，
     * 「这个方法覆盖了没有」在增量口径下答不上来，透传全量的方法数会被读成
     * 「这次改动的方法覆盖率」。
     */
    static FileCoverage restrictOneForTest(FileCoverage f, Set<Integer> wanted) {
        List<FileCoverage.LineCoverage> kept = f.lines().stream()
                .filter(l -> wanted.contains(l.line()))
                .toList();
        int missed = (int) kept.stream().filter(l -> "MISSED".equals(l.status())).count();
        int covered = kept.size() - missed;
        double ratio = kept.isEmpty() ? 0d : covered * 100d / kept.size();
        // 原文件不提供分支时（Go / Rust）保持 null —— 累加成 0 等于把
        // 「没有分支这回事」说成「有分支但一个都没测」
        Integer cb = null, mb = null;
        if (f.coveredBranches() != null) {
            cb = kept.stream().mapToInt(l -> l.coveredBranches() == null ? 0 : l.coveredBranches()).sum();
            mb = kept.stream().mapToInt(l -> l.missedBranches() == null ? 0 : l.missedBranches()).sum();
        }
        return new FileCoverage(f.path(), f.packageName(), f.sourceFileName(), covered, missed, ratio,
                cb, mb, null, null, kept);
    }
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn -B -pl platform test -Dtest=ProjectRuntimeTest
```

预期：`Tests run: 10, Failures: 0`（原 8 个加新增 2 个）。

- [ ] **Step 5: 提交**

```bash
git add platform/src/main/java/com/rtcc/platform/service/ProjectRuntime.java \
        platform/src/test/java/com/rtcc/platform/service/ProjectRuntimeTest.java
git commit -m "增量口径下分支按保留行累加，方法置空

方法在增量口径下答不上来：一个方法通常只有几行落在 diff 里，透传全量的方法数
会被读成「这次改动的方法覆盖率」。分支则本来就是逐行的，可以累加。

不提供分支的语言（Go / Rust）裁剪后仍是 null —— 累加成 0 等于把
「没有分支这回事」说成「有分支但一个都没测」。"
```

---

### Task 6: API 增字段

**Files:**
- Modify: `platform/src/main/java/com/rtcc/platform/service/ProjectRuntime.java`（`summary` 的 files 段约 804-816 行、`fileDetail` 的 rows 段约 852-871 行）
- Test: `platform/src/test/java/com/rtcc/platform/service/ProjectRuntimeTest.java`

**Interfaces:**
- Consumes: Task 2-5 的 IR。
- Produces: `summary` 的 `files[]` 每项多 4 个键 `coveredBranches` / `missedBranches` / `coveredMethods` / `missedMethods`；顶层多 `branchesByLanguage`（`{java:{covered,missed}, cpp:{...}}`，只含提供分支的语言）与 `coveredMethods` / `missedMethods`。`fileDetail` 的 `rows[]` 每项多 `coveredBranches` / `missedBranches`。

- [ ] **Step 1: 写失败的测试**

追加到 `ProjectRuntimeTest.java`：

```java
    /**
     * 分支不做跨语言汇总。实测 C++ 一个 demo 有 359 条分支（滤掉 throw 后 239 条），
     * 而源码里真正的条件语句只有 32 处 —— 与 Java 差一个数量级。
     * 汇总出来的百分比等于在报告 C++ 的异常处理路径覆盖率。
     */
    @Test
    void 分支按语言分开汇总不给跨语言总数() {
        Map<String, FileCoverage> snap = Map.of(
                "demo-service/src/main/java/A.java", new FileCoverage(
                        "demo-service/src/main/java/A.java", "a", "A.java", 5, 5, 50d,
                        10, 6, 3, 1, List.of()),
                "demo-service-cpp/order.cpp", new FileCoverage(
                        "demo-service-cpp/order.cpp", "demo-service-cpp", "order.cpp", 5, 5, 50d,
                        27, 212, 4, 7, List.of()),
                "demo-service-go/main.go", new FileCoverage(
                        "demo-service-go/main.go", "demo-service-go", "main.go", 5, 5, 50d,
                        null, null, null, null, List.of()));

        Map<String, Map<String, Integer>> byLang = ProjectRuntime.branchesByLanguage(snap);

        assertEquals(10, byLang.get("java").get("covered"));
        assertEquals(6, byLang.get("java").get("missed"));
        assertEquals(27, byLang.get("cpp").get("covered"));
        assertEquals(212, byLang.get("cpp").get("missed"));
        assertFalse(byLang.containsKey("go"), "Go 不提供分支，不该出现在这张表里 —— "
                + "出现了就意味着页面上会显示一个 0%，而那是假的");
        assertFalse(byLang.containsKey("rust"), "Rust 不提供分支，同理");
    }
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn -B -pl platform test -Dtest=ProjectRuntimeTest
```

预期：编译失败 `cannot find symbol: method branchesByLanguage`。

- [ ] **Step 3: 实现**

在 `ProjectRuntime` 里新增静态方法（放在 `overallRatio` 附近）：

```java
    /**
     * 分支按语言分开汇总，<b>不给跨语言总数</b>。
     *
     * 实测 C++ 一个几百行的 demo 有 359 条分支（滤掉 (throw) 后仍有 239 条），
     * 而源码里真正的条件语句只有 32 处 —— C++ 里每个可能抛异常的操作都会生成分支，
     * 分母与 Java 差一个数量级。汇总出来的百分比等于在报告 C++ 的异常处理路径覆盖率，
     * 与「我的 if 测到了吗」没有关系。同一语言内部纵向可比，这已经是分支覆盖率真正的用法。
     *
     * 不提供分支的语言（Go / Rust）根本不进这张表 —— 进来就意味着页面上会显示一个 0%。
     */
    static Map<String, Map<String, Integer>> branchesByLanguage(Map<String, FileCoverage> snap) {
        Map<String, int[]> acc = new LinkedHashMap<>();
        snap.values().forEach(f -> {
            if (f.coveredBranches() == null) {
                return;
            }
            int[] a = acc.computeIfAbsent(languageOf(f.path()), k -> new int[2]);
            a[0] += f.coveredBranches();
            a[1] += f.missedBranches();
        });
        Map<String, Map<String, Integer>> res = new LinkedHashMap<>();
        acc.forEach((lang, a) -> res.put(lang, Map.of("covered", a[0], "missed", a[1])));
        return res;
    }

    /** 按源文件后缀判定语言。与 e2e 脚本里 langs_of 的判定口径保持一致 */
    private static String languageOf(String path) {
        if (path.endsWith(".java")) return "java";
        if (path.endsWith(".go")) return "go";
        if (path.endsWith(".rs")) return "rust";
        if (path.endsWith(".cpp") || path.endsWith(".h")) return "cpp";
        return "other";
    }
```

`summary` 的 files 段，在 `m.put("ratio", round(f.ratio()));` 之后插入：

```java
                    // null 表示这门语言不提供该指标，前端据此显示「不提供」而不是 0%
                    m.put("coveredBranches", f.coveredBranches());
                    m.put("missedBranches", f.missedBranches());
                    m.put("coveredMethods", f.coveredMethods());
                    m.put("missedMethods", f.missedMethods());
```

在 `res.put("files", files);` 之前插入方法汇总与分语言分支：

```java
        // 方法可以跨语言汇总：「一个函数」这个口径在 Java / C++ / Rust 三者间大致一致，
        // 不像分支那样差一个数量级。不提供的语言（Go）不计入分母
        int cm = 0, mm = 0;
        boolean anyMethods = false;
        for (FileCoverage f : snap.values()) {
            if (f.coveredMethods() != null) {
                cm += f.coveredMethods();
                mm += f.missedMethods();
                anyMethods = true;
            }
        }
        res.put("coveredMethods", anyMethods ? cm : null);
        res.put("missedMethods", anyMethods ? mm : null);
        res.put("branchesByLanguage", branchesByLanguage(snap));
```

`fileDetail` 的 rows 段，需要先把行级分支也按行号索引出来。把 `Map<Integer, String> statusByLine` 那一段改成：

```java
        Map<Integer, String> statusByLine = new HashMap<>();
        Map<Integer, FileCoverage.LineCoverage> byLine = new HashMap<>();
        cov.lines().forEach(l -> {
            statusByLine.put(l.line(), l.status());
            byLine.put(l.line(), l);
        });
```

在 `row.put("status", ...)` 之后插入：

```java
                // 源码区菱形标记的数据来源。没有这一行的记录（EMPTY 行）或这门语言
                // 不提供分支时都是 null —— 前端据此不画菱形，而不是画一个 0/0
                FileCoverage.LineCoverage lc = byLine.get(i + 1);
                row.put("coveredBranches", lc == null ? null : lc.coveredBranches());
                row.put("missedBranches", lc == null ? null : lc.missedBranches());
```

- [ ] **Step 4: 运行全部单测**

```bash
mvn -B test 2>&1 | grep -E "Tests run:|BUILD"
```

预期：`Tests run: 121, Failures: 0, Errors: 0` 与 `BUILD SUCCESS`（110 原有 + Task1 的 2 + Task2 的 2 + Task3 的 3 + Task4 的 1 + Task5 的 2 + 本任务 1）。

- [ ] **Step 5: 提交**

```bash
git add platform/src/main/java/com/rtcc/platform/service/ProjectRuntime.java \
        platform/src/test/java/com/rtcc/platform/service/ProjectRuntimeTest.java
git commit -m "API 透出分支与方法覆盖，分支按语言分开给

只增字段、不改语义，门禁接口一个字没动 —— CI 里那句 curl -f 与
e2e_incremental.py 的判定断言都不受影响。

分支不给跨语言总数：实测 C++ 一个 demo 有 359 条分支（滤掉 throw 后 239 条），
而源码里真正的条件语句只有 32 处，与 Java 差一个数量级。汇总出来的百分比等于
在报告 C++ 的异常处理路径覆盖率。不提供分支的语言根本不进 branchesByLanguage
这张表 —— 进来就意味着页面上会显示一个 0%，而那是假的。

方法则跨语言汇总：「一个函数」这个口径三种语言大致一致。"
```

---

### Task 7: 真实环境验收与性能实测（R1）

这一任务不写新代码，只跑真实环境并留下证据。**规格里 R1 是唯一的高风险**：C++ 归一化原本 164–171ms 已是四种语言里最慢的，加 `-b -c` 后 gcov 输出从几百行涨到上千行，而核心功能 #5 断言端到端染色延迟 ≤ 5s。

**Files:**
- Modify（仅在超时才改）: `platform/src/main/java/com/rtcc/platform/collector/CppCoverageAnalyzer.java`
- Modify: `CLAUDE.md`（§三 采集耗时表补 C++ 新数字；§四 Rust 一节纠正 BRDA 那句）

- [ ] **Step 1: 确认工作树干净后重启全环境**

```bash
git status --short   # 必须为空，否则实例自报的 sessionid 带 -dirty，增量用例必挂
bash scripts/run_local.sh stop
bash scripts/run_local.sh start
```

`start` 会先 `mvn clean package`，因此必须先 `stop`——平台正握着那个 jar，边跑边打会写出一个坏 jar，下次启动报「没有主清单属性」。

- [ ] **Step 2: 量 C++ 归一化的新耗时**

```bash
LOGGING_LEVEL_COM_RTCC_PLATFORM_SERVICE=DEBUG bash scripts/run_local.sh platform-restart
sleep 20
grep -oE "归一化 · C\+\+ [0-9]+ms|cpp[^ ]* [0-9]+ms" .run/platform.log | tail -10
```

用包名而不是类名——Spring 的宽松绑定会把环境变量降成小写，类名对不上。

判据：C++ 归一化耗时 < 1s。四种语言是并行的，总归一化时间等于最慢的那个，加上抓取 8 个实例的 34–47ms 与 3s 的 `interval-ms`，端到端最坏约 4.1s，仍在 5s 内。

- [ ] **Step 3: 若超过 1s，执行规格 R1 的降级**

**降级是整体的，不能只降一半。** 把 `CppCoverageAnalyzer` 的 `runGcov` 去掉 `-b -c`，`parse` 里行级分支一律填 null、只保留文件级方法计数（`function` 行不受 `-b` 影响，`-t -r` 下就有）。同时在规格文件 §4.5 记一笔：源码区菱形列对 C++ 不可用，P3 只做分语言水位卡。

- [ ] **Step 4: 跑全量验收**

```bash
bash scripts/run_local.sh verify 2>&1 | tee /tmp/p1-verify.log | tail -5
grep -c "\[PASS\]" /tmp/p1-verify.log; grep -c "\[FAIL\]" /tmp/p1-verify.log
```

判据：**FAIL 为 0**。P1 前端一行没改，所以 `ui_verify.js` 的断言应当全部原样通过——**这正是本期的核心判据：证明加字段没有破坏任何既有能力**。

若 `e2e_incremental.py` 挂了，第一嫌疑是 Task 5 的 `restrictOne`：分支累加或方法置空写错会改变增量口径下的文件集合。

- [ ] **Step 5: 抽查真实数据里四种语言的字段**

```bash
curl -s "http://localhost:18090/api/coverage/summary" | python -c "
import sys, json
d = json.load(sys.stdin)
print('branchesByLanguage:', json.dumps(d.get('branchesByLanguage'), ensure_ascii=False))
print('方法汇总:', d.get('coveredMethods'), '/', d.get('missedMethods'))
for f in d['files']:
    print('  %-56s 分支=%s/%s 方法=%s/%s' % (f['path'],
          f['coveredBranches'], f['missedBranches'],
          f['coveredMethods'], f['missedMethods']))
"
```

判据（照着规格 §2 的能力矩阵逐条核）：

- `.java` 文件四项都是数字；
- `.cpp` 文件四项都是数字，且分支分母明显大于同规模的 Java 文件（这是预期的，不是 bug）；
- `.rs` 文件分支为 `null`、方法为数字；
- `.go` 文件四项全为 `null`；
- `branchesByLanguage` 只有 `java` 与 `cpp` 两个键，**没有 `go`、没有 `rust`**。

- [ ] **Step 6: 同步 CLAUDE.md 并提交**

在 §三「采集耗时的构成」的表里更新 C++ 那一行的数字（用 Step 2 实测值），并在 §四 Rust 一节把「但它不输出 `BRDA` 分支记录，因此没有 PARTIAL」改为：

```
但 rustc stable 的 `-C instrument-coverage` 不生成分支数据（实测 `BRF:0`），
因此没有 PARTIAL —— 不是 lcov 格式不支持，`llvm-cov export` 默认就会导出分支
（故有 `--skip-branches` 开关），要分支得上 nightly 的 `-Z coverage-options=branch`。
```

同时在 §二核心功能清单加一行 #17（内容见规格 §5）。

```bash
git add CLAUDE.md
git commit -m "P1 验收：四种语言的指标能力差异如实落地

全量 verify FAIL 为 0，前端一行未改 —— 证明 IR 加字段没有破坏任何既有能力。
真实数据抽查：java/cpp 四项齐全，rust 分支为 null、方法有值，go 四项全 null，
branchesByLanguage 只有 java 与 cpp 两个键。

顺带纠正 §四 Rust 一节关于 BRDA 的记录（结论对、原因记错，照着查会走弯路），
并按规格 §5 加上核心功能 #17。"
```

---

## 自检记录

- **规格覆盖**：§4.1 IR → Task 1；§4.3 三个 Analyzer → Task 2/3/4；§4.2 C++ 滤 throw 与分支不汇总 → Task 3、Task 6；§4.4 API → Task 6；§7 R1/R2/R3 → Task 7 Step 2-3、Task 3 Step 1、Task 6（`null` 一路透出，前端在 P2/P3 处理）；§5 核心功能 #17 → Task 7 Step 6。§4.5 页面属 P2/P3，不在本计划内。
- **规格未覆盖而计划新增的**：增量口径下 `restrictOne` 的语义（Task 5）。规格没写这一处，但加了分支之后它必须有明确答案，否则增量口径会透传全量的方法数。
- **类型一致性**：`FileCoverage` 11 个参数、`LineCoverage` 4 个参数的签名在 Task 1 定义，Task 2-6 全部按此构造；`restrictOneForTest` 与 `branchesByLanguage` 均为包级静态，测试直接调用。
