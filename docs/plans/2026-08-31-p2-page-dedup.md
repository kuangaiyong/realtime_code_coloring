# P2：页面消重重排 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把散在多个视图里的同一件事各归一处，并让 P1 采到的分支与方法覆盖在页面上有落点。

**Architecture:** 菜单不动（仍 7 项）。`overview` 的三个模块各归其位：门禁卡撤掉（`gate` 页已有完整判定）、被测实例表并进 `onboard` 已有的自检表、覆盖率排行并进 `coloring` 的文件列表；`overview` 自身改成「三指标水位 + 趋势」。

**Tech Stack:** Vue 3 + Element Plus（UMD，零构建）· 原生 ES module · puppeteer + 真实 Chrome

**Spec:** `docs/specs/2026-08-31-branch-method-coverage-design.md`

## Global Constraints

- **改了 `static/` 必须重新 `mvn package`。** 平台从 jar 启动，classpath 里的静态资源不跟着源目录变；只重启平台看到的还是旧页面，而且看不出任何异样。**打包前必须先 `stop`** —— 平台正握着那个 jar，边跑边打会写出坏 jar，下次启动报「没有主清单属性」。
- **E2E 钩子一律用 `data-testid`**，不复用样式选择器。
- **`null` 一律用 `=== null` 判定**，不要用真值判断：`0` 和 `null` 在 JS 里都是 falsy，而它们在本项目里含义相反（「这行没有分支」vs「这门语言不提供」）。
- **菜单不改**：`ui_verify.js` 的 `nav-*` 断言全部保留。
- **只在 `dev` 分支提交**；一切输出用中文。
- 前端没有单测，TDD 的形式是「先改 `ui_verify.js` 的断言 → 跑一次看红 → 改前端 → 跑一次看绿」。但跑一次真实 Chrome 要几分钟，**因此三个视图改完后统一跑一次**，中间只用 `node --check` 保证语法。

## 规格的一处修正

规格 §4.5 把「新增三指标水位卡」列为 `overview` 的改动（属 P2），§6 的分期表却把它排在 P3。按 §6 严格做，P2 结束时 `overview` 只剩一张趋势图 —— 一个叫「总览看板」的视图空着，中间态没法交付。数据 P1 已全部就位，没有依赖阻碍，**因此水位卡并入 P2，P3 只剩源码区菱形标记**。最终形态不变。

## 两处规格没预见的约束（实测得出）

1. **`coloring` 左栏是固定 300px**（`app.css:109`），一行已有「文件名 + 变更类型 + 百分比」，**塞不下三组数**。因此改为两行式：第一行文件名与变更类型，第二行三组小数字。代价是文件多时列表长一倍，由已有的过滤与新增的排序兜着。
2. **`overview` 的实例表与 `onboard` 的自检表前四列完全重复**（探针地址 / 语言 / 状态 / 构建版本）。因此不是「把表搬过去」，而是**给 `onboard` 已有的表补上 `overview` 独有的三列**（该实例行覆盖率 / 已覆盖 / 未覆盖）与那个「加载各实例覆盖」按钮。

---

### Task 1: onboard 吸收各实例覆盖，overview 的实例表撤掉

**Files:**
- Modify: `platform/src/main/resources/static/views/onboard.js`
- Modify: `platform/src/main/resources/static/views/overview.js`

**Interfaces:**
- Consumes: `store.perInst` / `store.perInstAt` / `store.perInstLoading` / `loadPerInstance()`（已存在于 `store.js`，本任务只是换一个视图使用它们）。
- Produces: `onboard` 的 `ob-check-table` 由 5 列变为 8 列；`btn-per-inst` 这个 testid 从 `overview` 移到 `onboard`。

- [ ] **Step 1: 读 overview 的实例表，把三列与按钮的实现搬走**

```bash
sed -n '297,349p' platform/src/main/resources/static/views/overview.js
grep -n "perInstMap\|perInstOf\|langOf\|loadPerInstance" platform/src/main/resources/static/views/overview.js
```

`perInstMap` / `perInstOf` / `langOf` 三个辅助与 `loadPerInstance` 的引入都要迁到 `onboard.js`。`langOf` 若 `onboard.js` 里已有同名实现，用它自己的，不要搬来第二份。

- [ ] **Step 2: 在 onboard.js 的自检表上补三列与按钮**

`onboard.js` 的 `setup()` 里引入 `loadPerInstance` 与 `store.perInst`，新增 `perInstOf(endpoint)`；模板里在「接入自检」的 `card-head` 加按钮：

```html
      <el-button size="small" :loading="store.perInstLoading" data-testid="btn-per-inst"
                 title="对每个实例各跑一次归一化，开销与实例数成正比，所以不随轮询自动做"
                 @click="loadPerInstance">加载各实例覆盖</el-button>
      <span v-if="store.perInstAt" class="sub">取于 {{ store.perInstAt }}</span>
```

表头在「自报构建版本」之后、「下一步」之前插入三列：

```html
          <th>该实例行覆盖率</th><th>已覆盖</th><th>未覆盖</th>
```

数据行对应插入（`p` 为 `perInstOf(i.endpoint)`，未加载时为 null）：

```html
            <td class="mono">{{ perInstOf(i.endpoint) ? perInstOf(i.endpoint).ratio + '%' : '—' }}</td>
            <td class="mono">{{ perInstOf(i.endpoint) ? perInstOf(i.endpoint).coveredLines : '—' }}</td>
            <td class="mono">{{ perInstOf(i.endpoint) ? perInstOf(i.endpoint).missedLines : '—' }}</td>
```

- [ ] **Step 3: 从 overview.js 删掉整张实例表**

删除模板里 `<h2>被测实例</h2>` 所在的整个 `<div class="card">`（约 297-349 行），以及 setup 里只被它使用的 `instEmpty` / `perInstMap` / `perInstOf` / `langOf` 与 `loadPerInstance` 的 import。`inst` 这个 computed 仍被 `stats` 用来数在线实例，**先留着**，Task 2 再处理。

- [ ] **Step 4: 语法检查**

```bash
node --input-type=module --check < platform/src/main/resources/static/views/onboard.js && echo onboard OK
node --input-type=module --check < platform/src/main/resources/static/views/overview.js && echo overview OK
grep -c "btn-per-inst" platform/src/main/resources/static/views/onboard.js   # 应为 1
grep -c "btn-per-inst\|inst-row" platform/src/main/resources/static/views/overview.js  # 应为 0
```

- [ ] **Step 5: 提交**

```bash
git add platform/src/main/resources/static/views/onboard.js \
        platform/src/main/resources/static/views/overview.js
git commit -m "被测实例的状态归到「服务接入」一处

overview 的实例表与 onboard 的自检表前四列完全重复（探针地址/语言/状态/构建版本），
所以不是把表搬过去，而是给 onboard 已有的那张补上 overview 独有的三列
（该实例行覆盖率/已覆盖/未覆盖）与「加载各实例覆盖」按钮。

想知道「6301 现在到底怎么了」原先要跑四个视图，这是第一步。"
```

---

### Task 2: overview 改成三指标水位 + 趋势

**Files:**
- Modify: `platform/src/main/resources/static/views/overview.js`
- Modify: `platform/src/main/resources/static/app.css`

**Interfaces:**
- Consumes: P1 产出的 `summary` 字段 `branchesByLanguage`（形如 `{java:{covered,missed}, cpp:{...}}`，不提供分支的语言不出现）、`coveredMethods` / `missedMethods`（可为 null）。
- Produces: testid `stat-lines` / `stat-branches` / `stat-methods`；`btn-gate-detail`、`gate-verdict`、`gate-actual`、`stat-gate` 从此不在 `overview`（`gate` 页仍有 `gate-verdict-full` / `gate-verdict-incremental`）。

- [ ] **Step 1: 删掉门禁卡与排行卡**

删除模板里 `<h2>覆盖率门禁</h2>` 与 `<h2>覆盖率排行</h2>` 各自所在的整个 `<div class="card">`，以及 setup 里的 `gateNote` / `toGate` / `ranked` / `exportCsv` / `jumpTo` 与 `cell` / `stamp` 两个只服务于导出的顶层函数。`pctClass` 的 import 若因此不再被用到也一并删掉。

**排行的能力不是丢掉，是 Task 3 在 `coloring` 里重建。**

- [ ] **Step 2: 把 stats 改成三指标水位**

`stats` computed 整体替换为：

```java
    /**
     * 三指标水位。<b>分支按语言分行，不给跨语言总数</b> ——
     * 实测 C++ 一个 demo 有 239 条分支（已滤掉编译器生成的异常路径），而同规模的
     * Java 只有 28 条：C++ 里每个可能抛异常的操作都会生成分支，分母差一个数量级。
     * 汇总出来的百分比等于在报告 C++ 的异常处理路径覆盖率，与「我的 if 测到了吗」无关。
     *
     * 方法则跨语言汇总：「一个函数」这个口径在 Java / C++ / Rust 三者间大致一致。
     */
    const LANG = { java: 'Java', cpp: 'C++', go: 'Go', rust: 'Rust' };

    const branchRows = computed(() => {
      const by = (d.value && d.value.branchesByLanguage) || {};
      const rows = Object.keys(by).map(k => {
        const c = by[k].covered, m = by[k].missed, t = c + m;
        return { lang: LANG[k] || k, pct: t === 0 ? '—' : (Math.round(c * 1000 / t) / 10) + '%',
                 detail: c + '/' + t };
      });
      // 拿不到分支的语言不在 branchesByLanguage 里，但页面上必须说出来 ——
      // 整格空着会被读成「这几种语言分支全没测」
      const absent = ['go', 'rust'].filter(k => !(k in by) && hasLang(k));
      if (absent.length) {
        rows.push({ lang: absent.map(k => LANG[k]).join(' · '), pct: '不提供', detail: '', muted: true });
      }
      return rows;
    });

    /** 这个项目里有没有这门语言的文件。没有的话不必解释它为什么没有分支 */
    function hasLang(k) {
      const ext = { go: '.go', rust: '.rs', java: '.java', cpp: '.cpp' }[k];
      return files.value.some(f => f.path.endsWith(ext));
    }

    const methodStat = computed(() => {
      const s = d.value;
      if (!hasData(s) || s.coveredMethods === null || s.coveredMethods === undefined) {
        return { v: '—', sub: '' };
      }
      const t = s.coveredMethods + s.missedMethods;
      return {
        v: s.coveredMethods + '/' + t,
        sub: t === 0 ? '' : (Math.round(s.coveredMethods * 1000 / t) / 10) + '%'
      };
    });

    const lineStat = computed(() => {
      const s = d.value;
      const ok = hasData(s);
      const covered = files.value.reduce((a, f) => a + f.coveredLines, 0);
      const missed = files.value.reduce((a, f) => a + f.missedLines, 0);
      return {
        v: ok ? s.overallRatio : '—',
        unit: ok ? '%' : '',
        sub: ok ? covered + ' / ' + (covered + missed) + ' 行' : ''
      };
    });
```

`stats`、`gateNote`、`inst` 三个 computed 与 `scope` 里除口径外的用途一并删除；`scope` 保留（口径栏要用）。

- [ ] **Step 3: 模板换成三格水位卡**

把模板顶部的 `<div class="stats">…</div>` 整段替换为：

```html
  <div class="stats">
    <div class="stat">
      <div class="k">行覆盖 · {{ scope }}</div>
      <div class="v" data-testid="stat-lines">{{ lineStat.v }}<small v-if="lineStat.unit">{{ lineStat.unit }}</small></div>
      <div class="sub-line">{{ lineStat.sub }}</div>
    </div>
    <div class="stat">
      <!-- 分支按语言分行给。两种语言的分支百分比不能相互比较，鼠标悬停里说明原因 -->
      <div class="k" title="C++ 的分支由 gcov 给出，包含编译器为可能抛异常的操作生成的路径，分母天然比 Java 大。不要拿两种语言的分支百分比相互比较。">分支覆盖 <span class="hint">?</span></div>
      <div data-testid="stat-branches">
        <div v-if="!branchRows.length" class="v">—</div>
        <div v-for="r in branchRows" :key="r.lang" class="lang-row" :class="{ muted: r.muted }">
          <span class="lg">{{ r.lang }}</span>
          <span class="pv">{{ r.pct }}</span>
          <span class="dt">{{ r.detail }}</span>
        </div>
      </div>
    </div>
    <div class="stat">
      <div class="k">方法覆盖</div>
      <div class="v" data-testid="stat-methods">{{ methodStat.v }}</div>
      <div class="sub-line">{{ methodStat.sub }}</div>
    </div>
    <div class="stat">
      <div class="k">源文件</div>
      <div class="v">{{ files.length }}<small> 个</small></div>
      <div class="sub-line">{{ collectedAt }}</div>
    </div>
  </div>
```

- [ ] **Step 4: 补 CSS**

在 `app.css` 的 `.stat .note-line` 规则之后追加：

```css
/* 三指标水位卡：分支要按语言分行，一个大数字放不下 */
.stat .sub-line { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 4px; }
.stat .hint { display: inline-block; width: 13px; height: 13px; line-height: 13px; text-align: center;
  border-radius: 50%; background: var(--el-fill-color-dark); color: var(--el-text-color-secondary);
  font-size: 10px; cursor: help; }
.stat .lang-row { display: flex; align-items: baseline; gap: 8px; margin-top: 5px; font-family: var(--mono); }
.stat .lang-row .lg { font-size: 12px; color: var(--el-text-color-secondary); min-width: 42px; }
.stat .lang-row .pv { font-size: 17px; color: var(--el-text-color-primary); }
.stat .lang-row .dt { font-size: 12px; color: var(--el-text-color-secondary); }
/* 「不提供」那一行要弱化：它不是一个差的数字，是「这门语言没有这个概念」 */
.stat .lang-row.muted .pv { font-size: 13px; color: var(--el-text-color-secondary); }
```

- [ ] **Step 5: 语法检查并提交**

```bash
node --input-type=module --check < platform/src/main/resources/static/views/overview.js && echo OK
grep -c "btn-gate-detail\|rank-row\|btn-export" platform/src/main/resources/static/views/overview.js  # 应为 0
git add platform/src/main/resources/static/views/overview.js platform/src/main/resources/static/app.css
git commit -m "总览改成三指标水位 + 趋势，门禁卡与排行撤走

门禁卡撤掉：gate 页已有完整的三态判定与 CI 接入命令，同一个结论在两处各写一遍，
改起来必然有一处跟不上。排行的能力挪进 coloring 的文件列表（下一个提交）。

水位卡里分支按语言分行，不给跨语言总数：实测 C++ 一个 demo 有 239 条分支
（已滤掉编译器生成的异常路径），同规模的 Java 只有 28 条 —— 汇总出来的百分比
等于在报告 C++ 的异常处理路径覆盖率。拿不到分支的语言明确写「不提供」，
整格空着会被读成「这几种语言分支全没测」。"
```

---

### Task 3: coloring 的文件列表吸收排行

**Files:**
- Modify: `platform/src/main/resources/static/views/coloring.js`
- Modify: `platform/src/main/resources/static/app.css`

**Interfaces:**
- Consumes: `summary` 的 `files[]` 里 P1 新增的 `coveredBranches` / `missedBranches` / `coveredMethods` / `missedMethods`（可为 null）。
- Produces: testid `rank-by`（排序选择）、`btn-export`（导出 CSV，从 `overview` 迁来）；`file-item` 内新增 `.metrics` 行。

- [ ] **Step 1: 把排序与导出搬进 coloring.js 的 setup**

在 `setup()` 里新增（`cell` 与 `stamp` 两个函数从 `overview.js` 原样搬来放在文件顶层）：

```javascript
    /** 排序：默认按路径，另外两档是「找最该补的文件」用的 */
    const sorted = computed(() => {
      const list = files.value.slice();
      if (store.rankBy === 'ratio') return list.sort((a, b) => a.ratio - b.ratio);
      if (store.rankBy === 'missed') return list.sort((a, b) => b.missedLines - a.missedLines);
      return list.sort((a, b) => a.path.localeCompare(b.path));
    });

    /** 一个文件的三组数。null 表示这门语言不提供，必须与 0 分开显示 */
    function metricsOf(f) {
      const br = f.coveredBranches === null || f.coveredBranches === undefined
        ? null : f.coveredBranches + '/' + (f.coveredBranches + f.missedBranches);
      const me = f.coveredMethods === null || f.coveredMethods === undefined
        ? null : f.coveredMethods + '/' + (f.coveredMethods + f.missedMethods);
      return { br, me };
    }

    /**
     * 导出当前口径下的全部文件。<b>口径必须写进文件名</b>：增量口径下的
     * 47 行未覆盖和全量口径下的 47 行是完全不同的两件事，隔几天再打开就分不清了。
     * 三组数一并导出，「不提供」原样写成空 —— 写 0 会让表格的读者以为一个都没测。
     */
    function exportCsv() {
      const head = ['路径', '包名', '文件', '行覆盖率', '已覆盖行', '未覆盖行', '分支', '方法'];
      const rows = sorted.value.map(f => {
        const m = metricsOf(f);
        return [f.path, f.packageName, f.sourceFileName, f.ratio,
                f.coveredLines, f.missedLines, m.br || '', m.me || ''];
      });
      const csv = [head, ...rows].map(r => r.map(cell).join(',')).join('\r\n');
      const scope = store.mode === 'incremental' ? '增量口径' : '全量口径';
      const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = '覆盖率-' + scope + '-' + stamp() + '.csv';
      a.click();
      URL.revokeObjectURL(a.href);
    }
```

模板里把 `v-for="f in files"` 改为 `v-for="f in sorted"`，并把 `files.length` 的计数改用 `sorted.length`（两者恒等，改成同一个来源免得日后跑偏）。

- [ ] **Step 2: 文件列表改两行式**

`card-head` 里加排序与导出：

```html
      <el-select v-model="store.rankBy" size="small" style="width:104px" data-testid="rank-by">
        <el-option label="按路径" value="path" />
        <el-option label="覆盖率低" value="ratio" />
        <el-option label="未覆盖多" value="missed" />
      </el-select>
      <el-button size="small" data-testid="btn-export" @click="exportCsv">导出 CSV</el-button>
```

`file-item` 的内容改为两行 —— **左栏是固定 300px，一行放不下三组数**：

```html
      <button v-for="f in sorted" :key="f.path"
              class="file" :class="{ on: f.path === store.current }"
              data-testid="file-item" :data-path="f.path"
              @click="openFile(f.path)">
        <span class="row1">
          <span class="nm" :title="f.path">{{ f.sourceFileName }}</span>
          <span v-if="f.changeType" class="ct" :class="f.changeType.toLowerCase()"
                data-testid="change-type"
                :title="f.changeType === 'ADDED' ? '基线里没有这个文件，整份都是这次新写的'
                        : '基线里已有，这次只改了其中一部分行'">{{ CHANGE[f.changeType] }}</span>
          <span class="pc" :class="pctClass(f.ratio)">{{ f.ratio }}%</span>
        </span>
        <span class="row2" data-testid="file-metrics">
          <span>行 {{ f.coveredLines }}/{{ f.coveredLines + f.missedLines }}</span>
          <!-- 「不提供」必须写出来。留空会被读成 0，而这门语言压根没有这个指标 -->
          <span :class="{ na: !metricsOf(f).br }">支 {{ metricsOf(f).br || '不提供' }}</span>
          <span :class="{ na: !metricsOf(f).me }">法 {{ metricsOf(f).me || '不提供' }}</span>
        </span>
      </button>
```

- [ ] **Step 3: 补 CSS**

把 `.file` 改为纵向排列，并新增两行的样式（替换 `app.css:134` 那一行，其余 `.file .nm` / `.pc` / `.ct` 规则保留）：

```css
.file { display: flex; flex-direction: column; gap: 3px; padding: 7px 14px; cursor: pointer; border: none; background: none; width: 100%; text-align: left; font-family: inherit; font-size: 13px; color: var(--el-text-color-regular); }
.file .row1 { display: flex; align-items: center; gap: 8px; width: 100%; }
/* 三组数是<b>次要信息</b>：主视线仍是文件名与那个百分比，所以小一号、颜色更淡 */
.file .row2 { display: flex; gap: 10px; font-family: var(--mono); font-size: 11px; color: var(--el-text-color-secondary); }
.file .row2 .na { opacity: .55; }
```

- [ ] **Step 4: 语法检查并提交**

```bash
node --input-type=module --check < platform/src/main/resources/static/views/coloring.js && echo OK
git add platform/src/main/resources/static/views/coloring.js platform/src/main/resources/static/app.css
git commit -m "染色页的文件列表吸收排行：三组数、排序、导出 CSV

同一份文件覆盖数据原先在两处各渲染一遍（coloring 的文件列表、overview 的排行表），
加了分支与方法之后这个重复会从「难看」变成「冲突」——放排行是 8 列宽表，
放文件列表又与排行重了一遍。

左栏是固定 300px，一行塞不下三组数，因此改成两行式：文件名与百分比在上，
三组小数字在下。拿不到的指标写「不提供」而不是留空 —— 留空会被读成 0。"
```

---

### Task 4: ui_verify.js 断言迁移

搬走的东西，断言也要跟着搬。**不能只删不补**：删掉就等于这些能力从此没人守。

**Files:**
- Modify: `scripts/ui_verify.js`

- [ ] **Step 1: 逐条迁移**

| 原断言（行号约） | 处置 |
|---|---|
| 344 `inst-row` 行数 | 搬进 section 5（服务接入），改断言 `ob-check-row` 的三列有值 |
| 352 `rank-row` 行数 | 改为断言 `coloring` 的 `file-item` 数与 `sum.files.length` 一致 |
| 355-363 点排行跳染色 | **删除** —— 文件列表本来就在染色页，点它就是打开文件，已有断言覆盖 |
| 370-375 `btn-gate-detail` | **删除** —— 门禁卡撤了，`gate` 页的断言仍在（section 4g） |
| 379 `gate-verdict` 与接口一致 | **删除** —— 同上，`gate-verdict-full` / `-incremental` 仍守着 |
| 410-417 各实例覆盖多三列 | 搬进 section 5，选择器由 `inst-table` 改为 `ob-check-table` |
| 468 `btn-export` | 搬进 section 4 的染色页部分（`btn-export` 现在在 `coloring`） |

- [ ] **Step 2: 新增三条断言（P1 的成果第一次在页面上被验证）**

放在 section 4 的总览部分：

```javascript
    // 三指标水位：分支必须按语言分行，且拿不到分支的语言要明确写「不提供」
    const branchText = await page.evaluate(textOf, '[data-testid="stat-branches"]');
    if (!/Java/.test(branchText) || !/C\+\+/.test(branchText)) {
      fail(`分支水位没有按语言分行：「${branchText}」`);
    } else if (!/不提供/.test(branchText)) {
      // Go 与 Rust 拿不到分支。整格不写这四个字的话，读的人会以为它们分支全没测
      fail(`分支水位没有点明哪些语言不提供：「${branchText}」`);
    } else {
      pass(`分支水位按语言分行，并写明了不提供的语言：${branchText.replace(/\s+/g, ' ')}`);
    }

    // 方法覆盖是跨语言汇总的（与分支不同）：「一个函数」这个口径三种语言大致一致
    const methodText = await page.evaluate(textOf, '[data-testid="stat-methods"]');
    if (!/^\d+\/\d+$/.test(methodText.trim())) {
      fail(`方法水位不是「已覆盖/总数」的形式：「${methodText}」`);
    } else {
      pass(`方法水位汇总正确：${methodText.trim()}`);
    }
```

放在 section 4 的染色页部分（文件列表三组数）：

```javascript
    // Go 文件拿不到分支与方法，页面上必须写「不提供」而不是留空或补 0 ——
    // 这是 P1 那套 null 语义在页面上唯一能被看见的地方
    const goMetrics = await page.evaluate(() => {
      const btn = [...document.querySelectorAll('[data-testid="file-item"]')]
        .find(e => (e.getAttribute('data-path') || '').endsWith('.go'));
      const m = btn && btn.querySelector('[data-testid="file-metrics"]');
      return m ? m.innerText.replace(/\s+/g, ' ') : null;
    });
    if (!goMetrics) {
      fail('文件列表里找不到 Go 文件的三组数');
    } else if ((goMetrics.match(/不提供/g) || []).length !== 2) {
      fail(`Go 文件应有两项「不提供」（分支与方法），实际：${goMetrics}`);
    } else {
      pass(`Go 文件的分支与方法明确标为不提供，不是 0：${goMetrics}`);
    }
```

- [ ] **Step 3: 语法检查并提交**

```bash
node --check scripts/ui_verify.js && echo OK
git add scripts/ui_verify.js
git commit -m "ui_verify 断言跟着搬：实例表进服务接入，排行进染色页

搬走的能力断言也要跟着搬，不能只删不补 —— 删掉就等于这些能力从此没人守。
只有三条真的删除：点排行跳染色（文件列表本来就在染色页）、总览的门禁卡两条
（gate 页的三态断言仍在）。

新增三条守 P1 的 null 语义在页面上的表现：分支水位按语言分行且写明谁不提供、
方法水位是跨语言汇总、Go 文件的两项写「不提供」而不是 0。
这是 P1 那套 null 语义唯一能被人看见的地方。"
```

---

### Task 5: 打包与全量验收

- [ ] **Step 1: 先停再打包**

```bash
git status --short   # 必须为空
bash scripts/run_local.sh stop
bash scripts/run_local.sh start
```

**顺序不能反。** 平台正握着 jar，边跑边打会写出坏 jar，下次启动报「没有主清单属性」——看上去像构建配置出了问题，其实只是文件被占用。

- [ ] **Step 2: 全量验收**

```bash
bash scripts/run_local.sh verify 2>&1 | tee /tmp/p2-verify.log | tail -5
echo "PASS: $(grep -c '\[PASS\]' /tmp/p2-verify.log)  FAIL: $(grep -c '\[FAIL\]' /tmp/p2-verify.log)"
```

判据：**FAIL 为 0**，且 PASS 数不少于 P1 时的 204（搬走的断言要么迁移、要么有替代，新增三条，所以总数应为 204 附近或更多）。

- [ ] **Step 3: 人眼过一遍三个视图**

真实 Chrome 打开 `http://localhost:18090`，逐项确认：

- `overview` 只有「三指标水位 + 趋势」两块，没有门禁卡、没有实例表、没有排行；
- 分支那格是按语言分的行，Go·Rust 那行写着「不提供」且是弱化的；
- `coloring` 左栏每个文件两行，第二行三组数，Go 文件写「不提供」；
- `onboard` 的自检表是 8 列，点「加载各实例覆盖」后后三列出数。

- [ ] **Step 4: 同步 CLAUDE.md 并提交**

前端那节的第 5 条讲口径栏，不受影响；但要在 §二 核心功能表的 #5 / #16 备注里补一句「排行与实例表已归位」，并在前端那节新增一条：

```
7. **同一件事只在一个视图里出现**（2026-08-31 的消重重排）。被测实例的状态只在
   「服务接入」，文件覆盖数据只在「代码染色」，门禁结论只在「覆盖门禁」，
   「总览看板」只放三指标水位与趋势。此前实例状态散在四个视图里，
   想知道「6301 现在怎么了」要跑四个地方；而加上分支与方法之后，
   文件数据的那处重复会从「难看」变成「冲突」——放排行是 8 列宽表，
   放文件列表又重了一遍。
```

```bash
git add CLAUDE.md
git commit -m "P2 验收：页面消重完成，同一件事只在一个视图里出现"
```
