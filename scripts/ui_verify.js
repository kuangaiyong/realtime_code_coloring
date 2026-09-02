/**
 * 前端验收：真实 Chrome、真实渲染、真实 WebSocket 推送。
 *
 * <b>为什么必须开浏览器，而不是像以前那样断言 HTML 源码里的钩子：</b>
 * 改成 Vue 之后，页面源码只剩一个挂载点，所有元素都是运行时生成的。
 * 断言源码等于什么都没断言 —— 组件写错、模块加载失败、数据字段少一个，
 * 源码里全都看不出来，页面却已经是空白或 undefined。
 *
 * 断言分两类：
 *   1. 结构与数据自洽 —— 页面上显示的数字必须与 API 返回的一致（不是「有个数字」）；
 *   2. 实时链路 —— 清零 → 调被测接口 → 等 DOM 里真的出现绿色行，全程计时 ≤ 5s。
 *
 * 机器相关的两个路径走环境变量，由 run_local.sh 注入，不写死在脚本里。
 */
const fs = require('fs');
const { execFileSync } = require('child_process');
const os = require('os');
const path = require('path');

const PLATFORM = 'http://localhost:18090';
const DEMO = 'http://localhost:18080';

const PUPPETEER_HOME = process.env.PUPPETEER_HOME
  || 'C:/Users/Administrator/AppData/Roaming/npm/node_modules/@mermaid-js/mermaid-cli/node_modules/puppeteer';
const CHROME_BIN = process.env.CHROME_BIN
  || 'C:/Program Files/Google/Chrome/Application/chrome.exe';

/** 染色链路的端到端预算。平台轮询 3s，留出一轮的余量 */
const COLOR_BUDGET_MS = 5000;

let failed = false;
function pass(msg) { console.log('  [PASS] ' + msg); }
function fail(msg) { console.error('  [FAIL] ' + msg); failed = true; }
/**
 * 抛而不是 process.exit：exit 不跑 finally，浏览器关不掉。
 * verify 在开发期反复跑，Windows 上这些 chrome.exe 不会自己退出，越积越多。
 */
function die(msg) { throw new Error(msg); }
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/** 轮询页面直到条件成立。返回等了多久 —— 染色链路的延迟就是这么量的 */
async function waitFor(page, fn, arg, timeout) {
  const t0 = Date.now();
  for (;;) {
    let v;
    try {
      v = await page.evaluate(fn, arg);
    } catch (e) {
      v = null;
    }
    if (v) return Date.now() - t0;
    if (Date.now() - t0 > timeout) return -1;
    await sleep(80);
  }
}

const textOf = (sel) => document.querySelector(sel) ? document.querySelector(sel).innerText.trim() : null;
const countOf = (sel) => document.querySelectorAll(sel).length;

/**
 * 往「增量基线」那个可选可输入的下拉里填一个值。
 *
 * 它不是输入框：data-testid 挂在 .el-select 根节点上，fill() 那套
 * （直接对着 data-testid 调 HTMLInputElement 的 value setter）在它身上会抛错。
 * 走 filterable + allow-create 的路径 —— 输进去，再点中下拉里那一项，
 * 候选里没有的 ref 也能这样填进去。
 */
async function pickBaseline(page, testid, value) {
  await page.evaluate((t) => {
    const root = document.querySelector(`[data-testid="${t}"]`);
    if (!root) throw new Error('找不到基线控件 ' + t);
    root.querySelector('.el-select__wrapper').click();
  }, testid);
  await sleep(400);
  await page.evaluate((args) => {
    const inp = document.querySelector(`[data-testid="${args.t}"] input.el-select__input`);
    const set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    set.call(inp, args.v);
    inp.dispatchEvent(new Event('input', { bubbles: true }));
  }, { t: testid, v: value });
  await sleep(600);
  const picked = await page.evaluate(() => {
    const li = [...document.querySelectorAll('.el-select-dropdown__item')]
      .filter(e => e.offsetParent !== null)[0];
    if (!li) return false;
    li.click();
    return true;
  });
  if (!picked) die(`基线下拉里一个可选项都没有，填不进 ${value}`);
  await sleep(300);
}

/** 下拉里当前列出的候选（要先展开）。返回 [{ref, kind}] */
async function baselineOptions(page, testid) {
  await page.evaluate((t) => {
    document.querySelector(`[data-testid="${t}"] .el-select__wrapper`).click();
  }, testid);
  await sleep(700);
  const out = await page.evaluate(() => {
    const vis = (sel) => [...document.querySelectorAll(sel)].filter(e => e.offsetParent !== null);
    return {
      groups: vis('.el-select-group__title').map(e => e.innerText.trim()),
      refs: vis('.el-select-dropdown__item').map(e => e.innerText.trim().split(/\s+/)[0])
    };
  });
  await page.keyboard.press('Escape');
  await sleep(300);
  return out;
}

(async () => {
  console.log('='.repeat(70));
  console.log('前端验收（真实 Chrome）');
  console.log('='.repeat(70));

  // 平台刚重启过时还没采到第一轮数据，页面上什么都没有，断言会假失败
  let ready = null;
  for (let n = 0; n < 40; n++) {
    try {
      const d = await (await fetch(`${PLATFORM}/api/coverage/summary`)).json();
      if (d.probeStatus === 'CONNECTED' && d.files.length) { ready = d; break; }
    } catch (e) { /* 平台还没起来 */ }
    await sleep(500);
  }
  if (!ready) die('平台 20s 内没有进入 CONNECTED，前端无从验起');
  console.log(`  平台就绪：${ready.instances.length} 实例 / ${ready.files.length} 文件 / ${ready.overallRatio}%`);

  const puppeteer = (await import(`file:///${PUPPETEER_HOME}/lib/puppeteer/puppeteer.js`)).default;
  const browser = await puppeteer.launch({
    executablePath: CHROME_BIN,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
    defaultViewport: { width: 1600, height: 1000 }
  });

  try {
    const page = await browser.newPage();
    // 模块加载失败、组件渲染报错在页面上往往只表现为「某块空着」，
    // 不收集控制台就等于把最有价值的线索丢掉
    const errors = [];
    // 「加载资源失败」这类 console 消息的正文里没有 URL，只能从 location() 取 ——
    // 按正文过滤是滤不中的
    const noise = (u) => String(u || '').includes('favicon');
    page.on('console', m => {
      // 非 2xx 的响应会让 Chrome 打一条「Failed to load resource」。那不一定是缺陷 ——
      // 门禁「判不了」按设计就是 409，页面也正确显示成「无法判定」。
      // HTTP 层的问题交给下面的 response 钩子按状态码分类，这里只留真正的脚本错误
      if (m.type() === 'error' && !noise(m.location() && m.location().url)
          && !m.text().startsWith('Failed to load resource')) {
        errors.push('console: ' + m.text());
      }
    });
    page.on('pageerror', e => errors.push('pageerror: ' + e.message));
    page.on('requestfailed', r => { if (!noise(r.url())) errors.push('requestfailed: ' + r.url()); });

    // 5xx 一定是缺陷；4xx 里只有门禁的「判不了」是设计如此，其余都该暴露出来。
    // 用一句「有没有报错」糊过去的话，要么放过真问题，要么被设计内的 409 搞成假失败
    const httpProblems = [];
    page.on('response', (r) => {
      const u = r.url().replace(PLATFORM, '');
      if (noise(u) || r.status() < 400) return;
      // 门禁三态里的「判不了」走 409，这是刻意的（见 CLAUDE.md 核心功能 #12）
      if (r.status() === 409 && u.includes('/coverage/gate')) return;
      httpProblems.push('HTTP ' + r.status() + ' ' + r.request().method() + ' ' + u);
    });

    await page.goto(PLATFORM + '/', { waitUntil: 'networkidle2', timeout: 30000 });

    // ---------- 1 · 首页是项目列表 ----------
    const mounted = await waitFor(page, () => !document.querySelector('#app .boot')
      && !!document.querySelector('[data-testid="view-projects"]'), null, 15000);
    if (mounted < 0) {
      const boot = await page.evaluate(() => document.querySelector('#app').innerText.slice(0, 300));
      die('Vue 没能挂载到项目列表，页面停在：' + boot + ' 控制台：' + errors.slice(0, 5).join(' | '));
    }
    pass(`应用在 ${mounted}ms 内挂载完成，首页是项目列表`);

    // 列表要能一眼看出「这个项目的基线配的哪个」「采不采得到数据」，
    // 只给名字的话每问一次都得点进去看
    const projects = await (await fetch(`${PLATFORM}/api/projects`)).json();
    const listRows = await page.evaluate(countOf, '[data-testid="project-row"]');
    if (listRows !== projects.projects.length) {
      fail(`项目列表 ${listRows} 行，API 给了 ${projects.projects.length} 个项目`);
    } else {
      pass(`项目列表 ${listRows} 行，与 API 一致`);
    }
    const defRow = await page.evaluate((id) => {
      const tr = document.querySelector('[data-testid="project-row"][data-id="' + id + '"]');
      return tr ? [...tr.children].map(td => td.innerText.trim()) : null;
    }, projects.defaultId);
    if (!defRow) {
      fail(`列表里找不到默认项目 ${projects.defaultId}`);
    } else {
      const cfg = projects.projects.find(p => p.id === projects.defaultId);
      // 仓库目录与基线必须真的显示出来，而不是占个「—」
      if (!defRow[1] || defRow[1] === '—' || !defRow[2] || defRow[2] === '—') {
        fail(`列表没显示仓库目录/基线：${defRow.slice(0, 3).join(' | ')}`);
      } else if (defRow[3] !== String(cfg.instanceCount)) {
        fail(`列表实例数写 ${defRow[3]}，API 给的是 ${cfg.instanceCount}`);
      } else {
        pass(`列表带出了配置：仓库 ${defRow[1]} / 基线 ${defRow[2]} / ${defRow[3]} 个实例`);
      }
    }

    // 默认项目删不掉（服务端 409），按钮必须先禁掉 —— 让人点了才被告知不行是坏体验
    const delDisabled = await page.evaluate((id) => {
      const tr = document.querySelector('[data-testid="project-row"][data-id="' + id + '"]');
      const b = tr && tr.querySelector('[data-testid="btn-delete"]');
      return b ? b.disabled : null;
    }, projects.defaultId);
    if (delDisabled !== true) fail(`默认项目的删除按钮没有禁用（disabled=${delDisabled}）`);
    else pass('默认项目的删除按钮已禁用，与服务端的 409 一致');

    // ---------- 1b · 进入项目 ----------
    await page.click('[data-testid="project-row"][data-id="' + projects.defaultId
      + '"] [data-testid="btn-open"]');
    const entered = await waitFor(page, () => !!document.querySelector('[data-testid="view-coloring"]')
      && document.querySelectorAll('[data-testid="file-item"]').length > 0, null, 20000);
    if (entered < 0) {
      const h = await page.evaluate(() => location.hash);
      die('点「进入」后没能进到染色视图并加载出文件（hash=' + h + '）');
    }
    const entryHash = await page.evaluate(() => location.hash);
    if (!entryHash.startsWith('#/p/')) fail(`进入项目后地址是 ${entryHash}，项目 id 没有落进路径`);
    else pass(`进入项目并加载出数据（${entered}ms，地址 ${entryHash}）`);

    for (const t of ['nav-coloring', 'nav-overview', 'nav-onboard', 'probe-pill', 'overall',
                     'mode-full', 'mode-incremental', 'btn-collect', 'btn-reset', 'banner']) {
      const n = await page.evaluate(countOf, `[data-testid="${t}"]`);
      if (n !== 1) fail(`骨架钩子 ${t} 出现 ${n} 次，应为 1 次`);
    }
    pass('侧边栏、顶栏、口径栏的骨架钩子齐备');

    // ---------- 2 · 顶栏显示的数字与 API 一致 ----------
    const sum = await (await fetch(`${PLATFORM}/api/coverage/summary`)).json();
    const probeText = await page.evaluate(textOf, '[data-testid="probe-pill"]');
    if (!probeText.includes('探针已连接') || !probeText.includes(`${sum.instances.length}/${sum.instances.length}`)) {
      fail(`探针状态与 API 不符：页面「${probeText}」，API ${sum.probeStatus} ${sum.instances.length} 实例`);
    } else {
      pass(`探针状态与 API 一致：${probeText}`);
    }

    const overallText = await page.evaluate(textOf, '[data-testid="overall"]');
    const shown = parseFloat((overallText.match(/([\d.]+)%/) || [])[1]);
    // 页面与 API 之间隔着一轮采集，允许有一轮的差，但不能是「显示了个别的数」
    if (!(Math.abs(shown - sum.overallRatio) < 3)) {
      fail(`顶栏显示 ${shown}%，API 给的是 ${sum.overallRatio}% —— 差得太多，不是一轮采集的抖动`);
    } else {
      pass(`顶栏覆盖率与 API 一致：${overallText}`);
    }

    // ---------- 3 · 染色视图 ----------
    const fileCount = await page.evaluate(countOf, '[data-testid="file-item"]');
    if (fileCount !== sum.files.length) {
      fail(`文件列表 ${fileCount} 项，API 给了 ${sum.files.length} 个文件`);
    } else {
      pass(`文件列表 ${fileCount} 项，与 API 一致`);
    }

    const firstPath = await page.evaluate(textOf, '[data-testid="current-path"]');
    if (!firstPath || firstPath === '未选择文件') {
      fail('首屏没有自动打开第一个文件，用户进来看到的是空白源码区');
    } else {
      pass(`首屏自动打开了 ${firstPath}`);
    }

    // 四态是本平台的核心口径。渲染成别的状态值 = CSS 选不中 = 该行不染色，
    // 而页面上看起来只是「这行没跑到」，看不出是 bug
    const statuses = await page.evaluate(() => {
      const s = new Set();
      document.querySelectorAll('[data-testid^="line-"]').forEach(el => s.add(el.dataset.status));
      return [...s];
    });
    const legal = ['COVERED', 'MISSED', 'PARTIAL', 'EMPTY', 'OUT'];
    const illegal = statuses.filter(s => !legal.includes(s));
    if (illegal.length) fail(`源码行出现非法状态：${illegal.join(', ')}`);
    else pass(`源码逐行状态均在四态内（本文件出现 ${statuses.join('/')}）`);

    // 点另一个文件必须真的换掉源码，而不是只换高亮
    const second = sum.files[1] || sum.files[0];
    await page.click(`[data-testid="file-item"][data-path="${second.path}"]`);
    const switched = await waitFor(page, (p) =>
      document.querySelector('[data-testid="current-path"]').innerText.trim() === p,
      second.path, 5000);
    if (switched < 0) fail(`点了 ${second.sourceFileName} 但源码区没换过去`);
    else pass(`点击切换文件生效（${second.sourceFileName}，${switched}ms）`);

    // ---------- 3b · 文件过滤 ----------
    // demo 只有 9 个文件，真接工程时这里是几百上千个类，没有过滤就只能靠滚动条找
    await page.type('[data-testid="file-filter"]', 'order');
    await sleep(500);
    const filtered = await page.evaluate(countOf, '[data-testid="file-item"]');
    const countText = await page.evaluate(textOf, '[data-testid="file-count"]');
    if (!(filtered > 0 && filtered < fileCount)) {
      fail(`过滤「order」后剩 ${filtered} 项，总数 ${fileCount} —— 过滤没起作用`);
    } else if (countText !== filtered + ' / ' + fileCount + ' 个') {
      // 只显示过滤后的数量会让人以为「这个口径下只有这些文件」
      fail(`过滤时计数写「${countText}」，应写「${filtered} / ${fileCount} 个」`);
    } else {
      pass(`文件过滤生效：${fileCount} → ${filtered}，计数写明「${countText}」`);
    }
    // 清掉，后面的断言仍按全量来
    await page.evaluate(() => {
      const el = document.querySelector('[data-testid="file-filter"]');
      el.value = '';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await sleep(400);
    if (await page.evaluate(countOf, '[data-testid="file-item"]') !== fileCount) {
      fail('清空过滤词后文件列表没有恢复');
    }

    // ---------- 3c · 深色模式 ----------
    // 页面的颜色全走 --el-* 变量，开关只是给 <html> 加个 class；
    // 但必须记住选择 —— 每次打开又回到浅色的话，这个开关等于没有
    await page.click('[data-testid="btn-theme"]');
    await sleep(400);
    const darkOn = await page.evaluate(() => ({
      cls: document.documentElement.classList.contains('dark'),
      stored: localStorage.getItem('rtcc-theme'),
      bg: getComputedStyle(document.body).backgroundColor
    }));
    if (!darkOn.cls || darkOn.stored !== 'dark') {
      fail(`点了深色开关但 html.dark=${darkOn.cls} / localStorage=${darkOn.stored}`);
    } else {
      pass(`深色模式生效并记住了选择（body 背景 ${darkOn.bg}）`);
    }
    await page.click('[data-testid="btn-theme"]');
    await sleep(300);
    if (await page.evaluate(() => document.documentElement.classList.contains('dark'))) {
      fail('再点一次没能切回浅色');
    }

    // ---------- 4 · 总览看板 ----------
    await page.click('[data-testid="nav-overview"]');
    const onDash = await waitFor(page, () => !!document.querySelector('[data-testid="view-overview"]'),
      null, 5000);
    if (onDash < 0) die('切不到总览看板');
    pass('侧边栏可切到总览看板');

    const statCount = await page.evaluate(countOf, '.stats .stat');
    if (statCount !== 4) fail(`看板水位卡 ${statCount} 张，应为 4 张（行 / 分支 / 方法 / 源文件）`);
    else pass('看板 4 张水位卡齐备');

    // 分支必须按语言分行，且拿不到分支的语言要明确写「不提供」。
    // 这是 P1 那套 null 语义在页面上唯一能被人看见的地方 —— 补 0 的话，
    // 读的人会以为 Go / Rust 的分支一个都没测，而它们压根没有这个指标
    const branchText = (await page.evaluate(textOf, '[data-testid="stat-branches"]')).replace(/\s+/g, ' ');
    if (!/Java/.test(branchText) || !/C\+\+/.test(branchText)) {
      fail(`分支水位没有按语言分行：「${branchText}」`);
    } else if (!/不提供/.test(branchText)) {
      fail(`分支水位没有点明哪些语言不提供分支：「${branchText}」`);
    } else {
      pass(`分支水位按语言分行，并写明了不提供的语言：${branchText}`);
    }

    // 方法与分支不同，是跨语言汇总的 ——「一个函数」这个口径三种语言大致一致
    const methodText = (await page.evaluate(textOf, '[data-testid="stat-methods"]')).trim();
    if (!/^\d+ \/ \d+$/.test(methodText)) {
      fail(`方法水位不是「已覆盖 / 总数」的形式：「${methodText}」`);
    } else {
      pass(`方法水位跨语言汇总：${methodText}`);
    }

    // 这句免责一旦丢了，一段会话内的爬升会被读成「这个项目的长期趋势」
    const trendText = await page.evaluate(textOf, '[data-testid="trend-box"]');
    if (!trendText.includes('非跨构建历史')) {
      fail('会话内曲线没有声明「非跨构建历史」');
    } else {
      pass('会话内曲线声明了数据范围（非跨构建历史）');
    }

    // 跨构建：有数据画图、没数据也必须说清为什么，绝不能安静地空着 ——
    // 空图会被读成「这个项目一直没有覆盖」，而真实原因可能只是库没起
    await page.click('[data-testid="trend-build"]');
    await sleep(1200);
    const buildTrend = await page.evaluate(() => {
      const box = document.querySelector('[data-testid="trend-box"]');
      return { text: box.innerText.trim(), svg: !!box.querySelector('svg') };
    });
    if (!buildTrend.svg && !buildTrend.text) fail('跨构建曲线既没画图也没给原因，是一块静默的空白');
    else pass(`跨构建曲线有交代：${buildTrend.svg ? '已画出曲线' : '给出了原因「' + buildTrend.text.slice(0, 60) + '」'}`);
    await page.click('[data-testid="trend-session"]');

    // ---------- 4c · 趋势横轴要有时间 ----------
    // 只有 commit 短 sha 的话，看不出「这是上周的还是今天的」，而跨构建趋势
    // 的用处恰恰是回答「最近在变好还是变差」
    await page.click('[data-testid="trend-build"]');
    await sleep(1500);
    const xAxis = await page.evaluate(() => {
      const el = document.querySelector('[data-testid="trend-xaxis"]');
      return el ? [...el.children].map(c => c.innerText.trim()) : null;
    });
    if (!xAxis) {
      // 只有 0/1 个构建时画不出曲线，也就没有横轴 —— 那是对的，不算失败
      const t = await page.evaluate(textOf, '[data-testid="trend-box"]');
      pass(`跨构建曲线还画不出来，横轴一并省略（${t.slice(0, 40)}）`);
    } else {
      // 先断言两端真的有字。peakAt 解析不出来时 axisLabels 返回两个空串，
      // 只比「格式一不一致」的话，一条空白横轴会被报成通过
      const [from, mid, to] = xAxis;
      const dated = [from, to].filter(x => /^\d{2}\/\d{2}/.test(x)).length;
      if (!from || !to) {
        fail(`跨构建横轴两端是空的（「${from}」/「${to}」）—— 时间没渲染出来`);
      } else if (dated === 1) {
        fail(`横轴两端格式不一致：${from} / ${to} —— 一端带日期一端不带，读起来像两种量纲`);
      } else {
        pass(`跨构建横轴带上了时间：${from} → ${to}（${mid}）`);
      }
    }
    await page.click('[data-testid="trend-session"]');

    // ---------- 4d · 染色页的文件列表：三组数与导出 ----------
    // 排行表撤掉之后，「一眼比较多个文件」这件事由文件列表承担，所以它得带上三组数。
    // 导出按钮也跟着搬了过来 —— 它导的就是这张列表
    await page.click('[data-testid="nav-coloring"]');
    if (await waitFor(page, () => !!document.querySelector('[data-testid="view-coloring"]'),
        null, 8000) < 0) die('切不到代码染色视图');

    const listRows2 = await page.evaluate(countOf, '[data-testid="file-item"]');
    if (listRows2 !== sum.files.length) {
      fail(`文件列表 ${listRows2} 行，API 给了 ${sum.files.length} 个文件`);
    } else {
      pass(`文件列表 ${listRows2} 行，与 API 一致（原先这条由总览的排行表守着）`);
    }

    // Go 拿不到分支，页面上必须写「不提供」而不是留空或补 0 ——
    // 补 0 会被读成「一个都没测」，而 Go 的 coverage profile 里压根没有分支这个概念。
    // 方法则相反：自 covdata func 接进来之后 Go 是有的，这里必须是个真数字 ——
    // 「有指标却写不提供」和「没指标却补 0」是同一类错，都在骗读表的人
    const goMetrics = await page.evaluate(() => {
      const btn = [...document.querySelectorAll('[data-testid="file-item"]')]
        .find(e => (e.getAttribute('data-path') || '').endsWith('.go'));
      const m = btn && btn.querySelector('[data-testid="file-metrics"]');
      return m ? m.innerText.replace(/\s+/g, ' ') : null;
    });
    if (!goMetrics) {
      fail('文件列表里找不到 Go 文件的三组数');
    } else if ((goMetrics.match(/不提供/g) || []).length !== 1) {
      fail(`Go 文件应恰有一项「不提供」（分支），实际：${goMetrics}`);
    } else if (!/支 不提供/.test(goMetrics)) {
      fail(`Go 的「不提供」应落在分支那一项上，实际：${goMetrics}`);
    } else if (!/法 \d+\/\d+/.test(goMetrics)) {
      fail(`Go 的方法应是个真数字（covdata func 给得出），实际：${goMetrics}`);
    } else {
      pass(`Go 文件：分支标为不提供、方法给出真数字，两者都没补 0：${goMetrics}`);
    }

    // ---------- 4d-2 · 导出 CSV ----------
    // 真的下载下来读一遍。只断言「按钮点得动」的话，导出一份空表或者串列的表
    // 同样能通过，而这份表是要贴进周报的
    const dlDir = fs.mkdtempSync(path.join(os.tmpdir(), 'rtcc-csv-'));
    const cdp = await browser.target().createCDPSession();
    await cdp.send('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: dlDir, eventsEnabled: true });
    await page.click('[data-testid="btn-export"]');
    let csvFile = null;
    for (let n = 0; n < 60 && !csvFile; n++) {
      csvFile = fs.readdirSync(dlDir).find(f => f.endsWith('.csv')) || null;
      if (!csvFile) await sleep(100);
    }
    if (!csvFile) {
      fail('点了「导出 CSV」但没有文件落盘');
    } else {
      const text = fs.readFileSync(path.join(dlDir, csvFile), 'utf8');
      const lines = text.replace(/^\ufeff/, '').trim().split(/\r?\n/);
      if (lines.length !== sum.files.length + 1) {
        fail(`CSV 有 ${lines.length} 行，应为表头 1 行 + ${sum.files.length} 个文件`);
      } else if (!/口径|全量|增量/.test(csvFile)) {
        // 口径不写进文件名的话，隔几天再打开就分不清这是增量还是全量的数字
        fail(`CSV 文件名没有写明口径：${csvFile}`);
      } else {
        const cols = lines[1].split(',').length;
        if (cols !== 9) {
          fail(`CSV 数据行有 ${cols} 列，应为 9 列（末两列是分支与方法）：${lines[1]}`);
        } else {
          // 拿不到的指标必须导成空单元格，不能是 0 —— 贴进周报后没人记得
          // 「Go 那个 0 其实是没有这个指标」，而空格一眼就看得出是没有数据
          const goRow = lines.find(l => l.includes('.go,') || l.includes('.go '));
          if (!goRow) {
            // 找不到就 fail，不能空过：这是核心功能 #17 在 CSV 侧唯一的守卫，
            // 静默通过等于这条防线从此形同虚设
            fail(`CSV 里找不到 Go 文件那一行，无从验证「不提供」是否导成了空：${lines[1]}`);
          } else if (goRow.split(',')[7].trim() !== '') {
            fail(`Go 行的分支应导成空（不是 0），实际是「${goRow.split(',')[7]}」：${goRow}`);
          } else if (!/^\d+\/\d+$/.test(goRow.split(',')[8].trim())) {
            // 方法这一列反过来：Go 有这个指标，导成空会让读表的人以为拿不到
            fail(`Go 行的方法应是个真数字，实际是「${goRow.split(',')[8]}」：${goRow}`);
          } else {
            pass(`导出的 CSV 落盘可读：${csvFile}，${lines.length - 1} 行 × 9 列，`
              + 'Go 的分支导成空而不是 0，方法导出真数字');
          }
        }
      }
    }
    fs.rmSync(dlDir, { recursive: true, force: true });

    // ---------- 4d-3 · 覆盖率报表：包 → 源文件 → 方法 三级钻取 ----------
    // 这一页存在的理由是「哪个包 / 哪个文件 / 哪个方法欠测」。只断言页面能打开
    // 证明不了它能用 —— 必须真的钻到底，再从方法跳回源码
    await page.click('[data-testid="nav-report"]');
    if (await waitFor(page, () => !!document.querySelector('[data-testid="pkg-row"]'),
        null, 15000) < 0) die('报表页打不开或没有包');

    const pkgRows = await page.evaluate(countOf, '[data-testid="pkg-row"]');
    const wantPkgs = new Set(sum.files.map(f => f.packageName || '(默认包)')).size;
    if (pkgRows !== wantPkgs) fail(`报表一级列出 ${pkgRows} 个包，按 API 的 packageName 应为 ${wantPkgs} 个`);
    else pass(`报表一级：${pkgRows} 个包，与 API 的 packageName 一致`);

    // 二级：点包名进文件列表，并且 hash 要带上包名 —— 深链接是这一页可分享的前提。
    // 原先路由拿整串与 ROUTES 全等比较，任何多级地址都会被静默判非法、跳回染色页
    await page.click('[data-testid="pkg-row"][data-pkg="com.shop.order.service"] [data-testid="pkg-name"]');
    if (await waitFor(page, () => !!document.querySelector('[data-testid="file-row"]'), null, 8000) < 0) {
      fail('点包名没进到源文件一层');
    } else {
      const h = await page.evaluate(() => location.hash);
      if (!h.includes('report/com.shop.order.service')) {
        fail(`钻进包之后 hash 是 ${h}，包名没落进地址 —— 刷新或转发链接就回不到这一层`);
      } else {
        pass(`报表二级：点包名进到源文件，且地址带上了包名（${h}）`);
      }
    }

    // 三级：方法明细。名字必须是人读的，行号必须有 —— 那是跳源码唯一的落点
    await page.click('[data-testid="file-row"] [data-testid="file-name"]');
    if (await waitFor(page, () => !!document.querySelector('[data-testid="method-row"]'), null, 15000) < 0) {
      fail('点源文件没进到方法一层');
    } else {
      const names = await page.evaluate(() =>
        [...document.querySelectorAll('[data-testid="method-row"]')].map(r => r.dataset.name));
      const bad = names.filter(n => n.startsWith('<') || n.includes('Ljava/'));
      if (bad.length) {
        fail(`方法名还是字节码的原始形态：${bad.slice(0, 3).join('、')}`);
      } else if (!names.some(n => n === 'refund(String, long)')) {
        fail(`方法名没渲染成人读的签名，实际：${names.slice(0, 4).join('、')}`);
      } else {
        pass(`报表三级：${names.length} 个方法，名字已渲染成人读签名（如 refund(String, long)）`);
      }
    }

    // 点方法跳回染色页并定位。定位标记必须只用一次 —— 挂在数据 watch 上不清的话，
    // 每 3 秒一次的推送会把正在看代码的人反复拽回目标行
    const jumpLine = await page.evaluate(() => {
      const r = [...document.querySelectorAll('[data-testid="method-row"]')]
        .find(x => x.dataset.name === 'refund(String, long)');
      r.querySelector('[data-testid="method-name"]').click();
      return r.innerText.match(/L(\d+)/)[1];
    });
    if (await waitFor(page, () => !!document.querySelector('.ln.hit'), null, 15000) < 0) {
      fail('点方法后染色页没有定位到那一行');
    } else {
      const at = await page.evaluate(() => document.querySelector('.ln.hit').innerText.trim().split(/\s/)[0]);
      if (at !== jumpLine) fail(`点的是 L${jumpLine}，染色页却定位到 ${at}`);
      else pass(`点方法跳回染色页并定位到 L${at}`);
      await sleep(4000);
      const still = await page.evaluate(countOf, '.ln.hit');
      if (still !== 1) fail(`等了一轮推送后定位标记有 ${still} 处，应恒为 1 处（标记没清或被重复加）`);
      else pass('定位标记在一轮推送后仍只有一处，没有把人反复拽回目标行');
    }

    // Go 曾经是四种语言里唯一「钻到底是空的」那个：covdata textfmt 的输出里没有函数
    // 信息，报表三级会是一片空白。接上 covdata func 之后四种语言都到得了底 ——
    // 没有这条断言的话，Go 哪天退回「不提供」不会有任何用例挂，页面上也看不出异样
    await page.click('[data-testid="nav-report"]');
    if (await waitFor(page, () => !!document.querySelector(
        '[data-testid="pkg-row"][data-pkg="demo-service-go"]'), null, 15000) < 0) {
      fail('报表里找不到 Go 那个包');
    } else {
      await page.click('[data-testid="pkg-row"][data-pkg="demo-service-go"] [data-testid="pkg-name"]');
      if (await waitFor(page, () => !!document.querySelector('[data-testid="file-row"]'), null, 8000) < 0) {
        fail('Go 的包点不进源文件一层');
      } else {
        await page.click('[data-testid="file-row"] [data-testid="file-name"]');
        if (await waitFor(page, () => !!document.querySelector('[data-testid="method-row"]'), null, 15000) < 0) {
          fail('Go 的源文件钻不到方法一层 —— covdata func 没接上');
        } else {
          const goNames = await page.evaluate(() =>
            [...document.querySelectorAll('[data-testid="method-row"]')].map(r => r.dataset.name));
          // 接收者是 Go 方法名里唯一能区分 Store.Refund 与某个同名自由函数的东西，
          // 丢了它报表上就会出现两行一模一样的名字
          if (!goNames.some(n => n === '*Store.Refund')) {
            fail(`Go 方法名没带接收者，实际：${goNames.slice(0, 5).join('、')}`);
          } else {
            pass(`Go 也钻得到方法一层：${goNames.length} 个函数，名字带接收者（如 *Store.Refund）`);
          }
        }
      }
    }

    // ---------- 4e · 信息架构：口径栏只在会显示数字的视图上 ----------
    // 挂在每个视图上是原先的做法，但在「项目设置」「采集事件」「服务接入」这三页，
    // 全量 / 增量口径不改变页面上的任何东西，摆着只会让人以为它对这一页有影响
    const scopedBar = async (view) => {
      await page.click(`[data-testid="nav-${view}"]`);
      // 等这一页真的挂上来再量。固定 sleep 在机器忙的时候会量到上一页的口径栏，
      // 换来一次与被测功能无关的假失败
      if (await waitFor(page, (v) => !!document.querySelector(`[data-testid="view-${v}"]`),
          view, 8000) < 0) die(`打不开 ${view} 视图`);
      return page.evaluate(() => !!document.querySelector('[data-testid="mode-full"]'));
    };
    const barOn = [];
    const barOff = [];
    for (const v of ['coloring', 'overview', 'gate', 'report']) {
      if (await scopedBar(v)) barOn.push(v); else barOff.push(v + '(缺)');
    }
    for (const v of ['onboard', 'events', 'settings']) {
      if (await scopedBar(v)) barOff.push(v + '(多)');
    }
    if (barOff.length) {
      fail(`口径栏出现的位置不对：${barOff.join('、')}`);
    } else {
      pass(`口径栏只出现在会显示数字的 ${barOn.length} 个视图上，设置/事件/接入页没有`);
    }

    // ---------- 4f · 场景进行中：页面上唯一的解释 ----------
    // 页面上没有开始 / 结束场景的入口 —— 场景归因是给 CI 与脚本用的，所以这里也经 API 开。
    // 但「进行中」必须显示出来：录制期间清零与保存配置都会被服务端回 409，
    // 不显示的话那两处看起来就是「点了没反应」，而这个提示是页面上唯一的解释。
    //
    // <b>两个方向都要验，而且不许靠刷新页面蒙混过去。</b>开了不显示，是少一个提示；
    // 结束了解不开，是把清零按钮永久废掉（它绑 :disabled="running"），后者严重得多。
    // 页面靠每轮推送跟这个状态（store.connectWs），所以两边都等得到，最多滞后一轮采集
    const SC_ID = 'ui-verify-' + Date.now().toString(36);
    const SC_API = `${PLATFORM}/api/projects/${projects.defaultId}/scenario`;
    // 一轮采集是 interval-ms + 跑这一轮，机器忙时要好几秒，给足余量
    const SC_WAIT = 30000;
    const resetDisabled = () => {
      const e = document.querySelector('[data-testid="btn-reset"]');
      const b = e && (e.tagName === 'BUTTON' ? e : e.querySelector('button'));
      return !!b && b.disabled;
    };

    // 提示要在<b>项目设置</b>那一页看得见：保存配置被 409 挡下就发生在那一页。
    // 它要是挂进口径栏（只在会显示覆盖数字的页上），恰好就在最需要它的那一页不显示。
    // 4e 最后停在 settings，这里不再切一次
    /**
     * 开场景，失败重试一次。
     *
     * <b>重试不是为了掩盖失败，是为了不把别人的缺陷算到这条用例头上。</b>
     * start 要给 8 台实例清零，走的是 ProjectRuntime.resetCounters；JaCoCo 的 tcpserver
     * backlog 很小，平台自己的调度采集或接入自检正连着同一个探针端口时，这一次连接
     * 会被直接拒掉（Connection refused），IOException 一路逃到 Spring 变成裸 500。
     * 这是平台侧既有的缺陷（探针连不上该是 503 加一句点名哪台，而不是 500），
     * 与本用例要验的「页面跟不跟得住场景状态」无关 —— e2e_scenario.py 才是管 start 的。
     * 两次都失败才算真失败：那时多半不是撞车，而是探针真的没了。
     */
    async function startScenario() {
      for (let n = 0; n < 2; n++) {
        const r = await fetch(`${SC_API}/start?scenarioId=${SC_ID}`, { method: 'POST' });
        if (r.ok) return;
        const why = (await r.text().catch(() => '')).slice(0, 200);
        if (n === 0) {
          console.log(`  ..  起场景失败（HTTP ${r.status}），等一轮采集过去再试一次`);
          await sleep(5000);
          continue;
        }
        // 起不了场景就没什么可验的了：接着往下跑，下面每一条都会在
        // 「本来就没有场景」的状态下空过，一屏 [PASS] 什么都没断言
        die(`连着两次都起不了场景（HTTP ${r.status}）：${why}`);
      }
    }
    await startScenario();
    const recOn = await waitFor(page,
      () => document.querySelectorAll('[data-testid="recording"]').length === 1, null, SC_WAIT);
    if (recOn < 0) fail('场景开了，「项目设置」页上却一直没出现「进行中」—— 那一页保存配置会被 409 挡下');
    else pass(`场景 ${SC_ID} 开了 ${(recOn / 1000).toFixed(1)}s 后，「项目设置」页上出现「进行中」`);

    // 清零按钮在录制期间必须禁用：不禁用的话点下去只会被服务端 409，
    // 而那条错误只闪一下，人看到的是「清零好像没生效」
    await page.click('[data-testid="nav-coloring"]');
    if (await waitFor(page, () => !!document.querySelector('[data-testid="view-coloring"]'),
        null, 8000) < 0) die('打不开代码染色视图');
    if (await waitFor(page, resetDisabled, null, SC_WAIT) < 0) {
      fail('场景进行中，清零按钮却没有禁用 —— 点下去只会被服务端 409');
    } else {
      pass('场景进行中时清零按钮已禁用，与服务端的 409 一致');
    }

    // 场景结束后，页面必须<b>自己</b>跟上。这里刻意不刷新：
    // 只在进入项目时取一次的话，红点会一直挂着、清零按钮永久点不动，
    // tooltip 还写着「场景进行中不能清零」—— 只有整页刷新才解得开，而人不会想到去刷新。
    // 顺带也把场景收干净：留着的话下一次 verify 里 e2e_scenario.py 的 start
    // 会撞「已有场景在跑」，级联出一串与本次改动无关的假失败
    const scStop = await fetch(`${SC_API}/stop`, { method: 'POST' });
    if (!scStop.ok) die(`经 API 结束场景失败（HTTP ${scStop.status}），后面的清零断言必然假失败`);
    // 判据必须整个写在这个函数体里：page.evaluate 是把函数序列化过去执行的，
    // 闭包里的 resetDisabled 在浏览器侧压根不存在，引用它只会静默变成一次求值失败
    const recOff = await waitFor(page, () => {
      if (document.querySelectorAll('[data-testid="recording"]').length !== 0) return false;
      const e = document.querySelector('[data-testid="btn-reset"]');
      const b = e && (e.tagName === 'BUTTON' ? e : e.querySelector('button'));
      return !!b && !b.disabled;
    }, null, SC_WAIT);
    if (recOff < 0) {
      const still = await page.evaluate(countOf, '[data-testid="recording"]');
      fail(`场景已结束，页面却没跟上（仍有 ${still} 个「进行中」/ 清零按钮仍禁用）—— `
        + '不刷新就解不开的话，这个控件对人来说就是废的');
    } else {
      pass(`场景结束后 ${(recOff / 1000).toFixed(1)}s 内页面自己跟上：提示消失、清零按钮恢复可用`);
    }
    const leftOver = await (await fetch(SC_API)).json();
    if (leftOver.active) die(`场景 ${leftOver.active} 没停掉，会拖累下一次 verify 的 e2e_scenario`);

    // ---------- 4g · 覆盖门禁：两种口径并排 ----------
    // 全量说的是存量水位，增量说的是「这次改的代码测没测」——
    // 只给一个数字的话，人会拿存量的不达标去挡这次合并
    await page.click('[data-testid="nav-gate"]');
    if (await waitFor(page, () => document.querySelectorAll('[data-testid="gate-card"]').length === 2,
        null, 30000) < 0) {
      const n = await page.evaluate(countOf, '[data-testid="gate-card"]');
      fail(`门禁页给出 ${n} 张判定卡，应为全量与增量各一张`);
    } else {
      const verdicts = await page.evaluate(() => ['full', 'incremental'].map(m => {
        const e = document.querySelector('[data-testid="gate-verdict-' + m + '"]');
        return e ? e.innerText.trim() : null;
      }));
      const legal = ['通过', '不通过', '无法判定'];
      if (!verdicts.every(v => legal.includes(v))) {
        fail(`门禁结论不在三态之内：${verdicts.join(' / ')}`);
      } else {
        pass(`门禁页两种口径并排：全量「${verdicts[0]}」/ 增量「${verdicts[1]}」`);
      }
      const ci = await page.evaluate(textOf, '[data-testid="gate-ci"]');
      if (!ci || !ci.includes('/api/projects/')) {
        fail(`门禁页给 CI 的命令没带项目：${ci}`);
      } else {
        pass('门禁页给 CI 的命令带上了项目路径');
      }

      // 那条命令是给人抄进流水线的模板，必须跟着顶栏当前的口径与基线走，
      // 而不是上一次判定时冻结下来的那份 —— 抄到的基线与框里显示的不一致，
      // 流水线判的就是另一个基线，而这在页面上一点看不出来。
      // 同时：卡片仍是按旧基线判的（改基线不重判，否则每敲一个字符起三个 git 进程），
      // 所以必须当场点破，不能留一个不知道按什么判出来的数字
      await page.click('[data-testid="mode-incremental"]');
      if (await waitFor(page, () => !!document.querySelector('[data-testid="baseline"]'),
          null, 20000) < 0) {
        fail('切到增量口径后顶栏没有出现基线输入框');
      } else {
        await fill('baseline', 'HEAD~2');
        await sleep(400);
        const ciInc = await page.evaluate(textOf, '[data-testid="gate-ci"]');
        const drift = await page.evaluate(countOf, '[data-testid="baseline-drift"]');
        if (!/baseline=HEAD~2/.test(String(ciInc))) {
          fail(`顶栏基线改成 HEAD~2 后，给 CI 的命令还是旧的：${ciInc}`);
        } else if (drift !== 1) {
          fail('基线改过了而判定还是旧的，页面没点破 —— 卡片就成了个不知道按什么判出来的数字');
        } else {
          pass('给 CI 的命令跟着顶栏基线走，并点破了「这个结论是按旧基线判的」');
        }
        // 还原：后面的染色延迟断言要按全量口径来
        await fill('baseline', 'HEAD~1');
        await page.click('[data-testid="mode-full"]');
        await sleep(1500);
      }

      // 这一页刻意不轮询：增量判定每次要起三个 git 子进程（查漂移、解析基线、算变更行，
      // 均无缓存），定时刷会在人只是把页面开着的时候持续烧 CPU。
      // 代价是结论可能不是最新的，所以「判定于几点」必须写出来 ——
      // 不标的话，人会拿一个半小时前的结论去决定合不合并
      const stamp1 = await page.evaluate(textOf, '[data-testid="judged-at"]');
      if (!/判定于 /.test(String(stamp1))) {
        fail(`门禁页没有标出判定时间：「${stamp1}」—— 它不自动刷新，不标就会被当成实时的`);
      } else {
        await sleep(12000);
        const stamp2 = await page.evaluate(textOf, '[data-testid="judged-at"]');
        if (stamp2 !== stamp1) {
          fail(`门禁页在自动刷新（${stamp1} → ${stamp2}）—— 每轮三个 git 子进程，不该定时刷`);
        } else {
          // 「改由重新判定触发」不能只写在 pass 文案里 —— 那个按钮得真按一下，
          // 否则 load() 在第二次调用时坏掉，这里一个字都不会报
          await page.click('[data-testid="btn-rejudge"]');
          const advanced = await waitFor(page, (prev) => {
            const e = document.querySelector('[data-testid="judged-at"]');
            return !!e && /判定于 /.test(e.innerText) && e.innerText.trim() !== prev;
          }, String(stamp1).trim(), 30000);
          if (advanced < 0) {
            const now = await page.evaluate(textOf, '[data-testid="judged-at"]');
            fail(`点了「重新判定」但判定时间没动（仍是「${now}」）—— 这一页不轮询，它是唯一的刷新入口`);
          } else {
            const cards = await page.evaluate(countOf, '[data-testid="gate-card"]');
            if (cards !== 2) fail(`重新判定后只剩 ${cards} 张判定卡`);
            else pass(`门禁页不自动刷新（${stamp1} 12 秒未变），「重新判定」点一下就重判（${advanced}ms，两种口径都在）`);
          }
        }
      }
    }

    // ---------- 4h · 增量列表的「新增 / 修改」 ----------
    // 值得标出来，是因为两者该看的东西不同：新增文件整份都是这次的责任，一片红说明
    // 这个类根本没被测到；修改文件里的红只是这次改的那几行没测，文件其余部分与这次无关。
    //
    // 基线在运行时算出来而不是写死 sha：本项目的设计就是「被测源码零改动」，
    // 既有新增又有修改的区间在历史里很少，写死一个迟早会连不上真实历史。
    const SRC_ROOTS = ['demo-service/src', 'demo-service-go', 'demo-service-cpp', 'demo-service-rust'];
    const git = (...a) => execFileSync('git', a, { encoding: 'utf8' }).trim();

    // <b>右端必须是探针自报的构建 commit，不是 HEAD。</b>平台算的是 baseline → buildCommit；
    // 拿 HEAD 当标准答案的话，被测实例没跟着最新代码重编时，页面上每一行都会落进
    // 「不在 git 的变更集里」，把「产物过期」报成「平台标错了」，排查方向当场跑偏
    const CT_TARGET = sum.buildCommit;

    /**
     * 找一个<b>既有新增、又有修改</b>的基线。
     *
     * 只取「最近一次修改过被测源码的提交」是不够的：本项目的设计是被测源码零改动，
     * 历史上纯新增的提交远多于修改，只要以后有人提一次纯修改，那一条就成了最近的 M 提交，
     * 区间里只剩「修改」—— 下面「两种都要出现」的断言当场失败，而平台一点毛病没有。
     * 所以往回扫，取第一个两种都齐的区间（越往回区间越大，越容易齐，取最新的那个即最小）。
     */
    let CT_BASE = null;
    let statusRows = null;
    for (const c of git('log', '--diff-filter=M', '--format=%H', '-n', '30', '--', ...SRC_ROOTS)
        .split('\n').filter(Boolean)) {
      let rows;
      try {
        rows = git('diff', '-M', '--name-status', c + '^', CT_TARGET, '--', ...SRC_ROOTS)
          .split('\n').filter(Boolean);
      } catch (e) {
        continue;   // 根提交没有父，跳过
      }
      const kinds = new Set(rows.map(r => r[0]));
      // R（改名）在平台侧同样归入「修改」，与 A 相对
      if (kinds.has('A') && (kinds.has('M') || kinds.has('R'))) {
        CT_BASE = c + '^';
        statusRows = rows;
        break;
      }
    }
    if (!CT_BASE) {
      fail('历史上找不到「既有新增又有修改」的区间，「新增 / 修改」这一条无从验证');
    } else {
      // git 的判定就是这一条的标准答案：平台标错了，人补测试就会补错地方
      const expect = new Map();
      for (const row of statusRows) {
        const cols = row.split('\t');
        if (cols.length < 2) continue;
        // 改名给的是 R100<TAB>旧路径<TAB>新路径，取最后一列才是新侧路径
        expect.set(cols[cols.length - 1], cols[0][0] === 'A' ? '新增' : '修改');
      }

      // 顶栏在增量口径下会写出「基线 <8 位 sha> → 产物 <8 位>」，用它判断
      // 这一次请求有没有真的落地。只等「有文件」是不够的 —— 上一轮全量的 9 行
      // 还挂在 DOM 上，会被当成结果读走；连着几次 setMode 的响应还会互相超车
      const overallHas = (txt) => waitFor(page, (t) => {
        const e = document.querySelector('[data-testid="overall"]');
        return !!e && e.innerText.includes(t);
      }, txt, 30000);

      await page.click('[data-testid="nav-coloring"]');
      await waitFor(page, () => !!document.querySelector('[data-testid="view-coloring"]'), null, 8000);
      await page.click('[data-testid="mode-incremental"]');
      await waitFor(page, () => !!document.querySelector('[data-testid="baseline"]'), null, 20000);
      if (await overallHas('增量') < 0) fail('切到增量口径后顶栏没跟着换成增量');
      await fill('baseline', CT_BASE);
      // fill 只发 input 事件，@change 不会触发；再点一次口径按钮把它送进去
      await page.click('[data-testid="mode-incremental"]');
      const baseShort = git('rev-parse', CT_BASE).slice(0, 8);
      const listed = await overallHas('基线 ' + baseShort);
      if (listed < 0) {
        const now = await page.evaluate(textOf, '[data-testid="overall"]');
        fail(`以 ${CT_BASE} 为基线的增量数据没加载出来（顶栏仍是「${now}」）`);
      } else {
        const rows = await page.evaluate(() =>
          [...document.querySelectorAll('[data-testid="file-item"]')].map(b => {
            const t = b.querySelector('[data-testid="change-type"]');
            return { path: b.dataset.path, tag: t ? t.innerText.trim() : null };
          }));
        const wrong = [];
        const counts = { 新增: 0, 修改: 0 };
        for (const r of rows) {
          if (!expect.has(r.path)) { wrong.push(`${r.path} 不在 git 的变更集里`); continue; }
          if (r.tag !== expect.get(r.path)) {
            wrong.push(`${r.path} 页面标「${r.tag}」，git 说是「${expect.get(r.path)}」`);
          } else {
            counts[r.tag]++;
          }
        }
        if (wrong.length) {
          fail(`增量列表的变更类型与 git 不一致：${wrong.slice(0, 3).join('；')}`);
        } else if (!counts['新增'] || !counts['修改']) {
          // 两种都出现过才算验到：全是同一种的话，标签写死成那一个也能过
          fail(`这一轮只出现了一种变更类型（新增 ${counts['新增']} / 修改 ${counts['修改']}），断言等于没做`);
        } else {
          pass(`增量列表的变更类型与 git 逐个一致：${counts['新增']} 个新增 / ${counts['修改']} 个修改`);
        }
      }

      // 全量口径下不该有这个标签：那时列的是产物里的全部文件，
      // 给它们一律标上「修改」是在说一件没发生的事
      await fill('baseline', 'HEAD~1');
      await page.click('[data-testid="mode-full"]');
      // 同样要等口径真的换回来：不等的话下面数到的是增量那一轮残留的列表，
      // 而且后面的用例会在一个还停在增量口径的页面上跑
      if (await overallHas('整体行覆盖率') < 0) die('切不回全量口径，后面的断言都会跑在错的口径上');
      const strayTags = await page.evaluate(countOf, '[data-testid="change-type"]');
      if (strayTags) {
        fail(`全量口径下仍标着 ${strayTags} 个「新增 / 修改」—— 那时根本没有变更类型可言`);
      } else {
        pass('全量口径下不标变更类型：那时列的是产物里的全部文件，不是这次改的');
      }
      await sleep(1500);
    }

    // ---------- 5 · 服务接入 ----------
    await page.click('[data-testid="nav-onboard"]');
    const onOb = await waitFor(page, () => !!document.querySelector('[data-testid="view-onboard"]'),
      null, 5000);
    if (onOb < 0) die('切不到服务接入');

    // 向导的全部价值在于「命令里的端口是本平台实际配置的那个」。
    // 退化成默认端口的话，照抄下去连不上，而页面上看不出任何异样
    for (const lang of ['java', 'go', 'cpp', 'rust']) {
      const ep = sum.instances.find(i => String(i.endpoint).startsWith(lang + '://'));
      if (!ep) continue;
      const port = ep.endpoint.slice(ep.endpoint.lastIndexOf(':') + 1);
      await page.click(`[data-testid="ob-lang-${lang}"]`);
      const ok = await waitFor(page, (p) => {
        const el = document.querySelector('[data-testid="ob-cmd"]');
        return el && el.innerText.includes(p);
      }, port, 5000);
      if (ok < 0) {
        const got = await page.evaluate(textOf, '[data-testid="ob-cmd"]');
        fail(`${lang} 的接入命令里没有实际端口 ${port}，取到的是：${got.slice(0, 120)}`);
      } else {
        pass(`${lang} 的接入命令用的是平台实际配置的端口 ${port}`);
      }
    }

    const obRows = await page.evaluate(countOf, '[data-testid="ob-check-row"]');
    if (obRows !== sum.instances.length) fail(`接入自检表 ${obRows} 行，应为 ${sum.instances.length} 行`);
    else pass(`接入自检表 ${obRows} 行，每个实例都点了名`);

    // 各实例覆盖是按需拉取的，拉完表格要真的多出三列。
    // 这三列原先在总览的「被测实例」表上，而那张表与本表的前四列完全重复 ——
    // 同一批实例的状态散在两个视图里，想知道「6301 现在怎么了」得两边都看
    const colsBefore = await page.evaluate(countOf, '[data-testid="ob-check-table"] thead th');
    await page.click('[data-testid="btn-per-inst"]');
    const grew = await waitFor(page, (n) =>
      document.querySelectorAll('[data-testid="ob-check-table"] thead th').length === n + 3,
      colsBefore, 120000);
    if (grew < 0) {
      fail('点了「加载各实例覆盖」但自检表没有多出三列');
    } else {
      pass(`各实例覆盖已加载，自检表由 ${colsBefore} 列增至 ${colsBefore + 3} 列（${(grew / 1000).toFixed(1)}s）`);
    }

    // ---------- 6 · 实时染色链路（本脚本的核心断言） ----------
    await page.click('[data-testid="nav-coloring"]');
    await waitFor(page, () => !!document.querySelector('[data-testid="view-coloring"]'), null, 5000);
    const target = sum.files.find(f => f.sourceFileName === 'OrderController.java');
    if (!target) die('找不到 OrderController.java，无法验证染色链路');
    await page.click(`[data-testid="file-item"][data-path="${target.path}"]`);
    await waitFor(page, (p) =>
      document.querySelector('[data-testid="current-path"]').innerText.trim() === p, target.path, 5000);

    const coveredCount = () =>
      document.querySelectorAll('[data-testid^="line-"][data-status="COVERED"]').length;

    // 先把这个文件染绿，再清零。少了这一步，清零断言会在「本来就没有绿行」时
    // 空过 —— 前面的用例刚跑完场景，恰恰就是这种状态，那句 [PASS] 什么都没验证
    await fetch(`${DEMO}/api/order/query?bizNo=A1001`);
    const primed = await waitFor(page, () =>
      document.querySelectorAll('[data-testid^="line-"][data-status="COVERED"]').length > 0,
      null, 15000);
    if (primed < 0) die(`调了接口但 ${target.sourceFileName} 没有出现已覆盖行，链路本身就是断的`);
    const before = await page.evaluate(coveredCount);

    // 清零之后这个文件必须一行绿的都不剩 —— 否则下面测的就不是「新覆盖」
    await page.click('[data-testid="btn-reset"]');
    const cleared = await waitFor(page, () =>
      document.querySelectorAll('[data-testid^="line-"][data-status="COVERED"]').length === 0,
      null, 20000);
    if (cleared < 0) {
      const left = await page.evaluate(coveredCount);
      die(`清零后 ${target.sourceFileName} 仍有 ${left} 行是已覆盖，清零没生效`);
    }
    pass(`清零生效：${target.sourceFileName} 的 ${before} 行已覆盖被清回 0（${(cleared / 1000).toFixed(1)}s）`);

    const t0 = Date.now();
    const resp = await (await fetch(`${DEMO}/api/order/query?bizNo=A1001`)).json();
    console.log(`  >> GET ${DEMO}/api/order/query?bizNo=A1001  响应: ${JSON.stringify(resp)}`);
    const painted = await waitFor(page, () =>
      document.querySelectorAll('[data-testid^="line-"][data-status="COVERED"]').length > 0,
      null, 15000);
    if (painted < 0) {
      die('调了被测接口，但浏览器里的源码一行都没变绿 —— 实时链路断了');
    }
    const latency = Date.now() - t0;
    const greens = await page.evaluate(coveredCount);
    if (latency > COLOR_BUDGET_MS) {
      fail(`染色延迟 ${latency}ms 超出 ${COLOR_BUDGET_MS}ms 预算`);
    } else {
      pass(`调接口 → 浏览器里 ${greens} 行变绿，端到端 ${latency}ms ≤ ${COLOR_BUDGET_MS}ms`);
    }

    // ---------- 6b · 走完向导建出一个能采数的项目 ----------
    // 这是方案 A 的核心承诺：向导最后一步强制自检，不通过就建不出来。
    // 只断言「点得动」没有意义 —— 要证明的是建出来的项目<b>真的采得到数据</b>，
    // 因为「建好了却没数据」正是这类平台最常见、也最难查的那种失败
    const WZ_ID = 'ui-verify-wizard';
    // 上一次跑到一半失败可能留下它，先清掉 —— 否则这次会撞「标识已被占用」
    await fetch(`${PLATFORM}/api/projects/${WZ_ID}`, { method: 'DELETE' }).catch(() => {});

    const seed = await (await fetch(`${PLATFORM}/api/projects/default`)).json();
    const javaEp = (seed.instances || []).find(x => String(x).startsWith('java://')) || 'java://localhost:6300';
    const hostPort = javaEp.split('://')[1];
    const wzHost = hostPort.slice(0, hostPort.lastIndexOf(':'));
    const wzPort = hostPort.slice(hostPort.lastIndexOf(':') + 1);

    /** el-input 是受控组件，直接改 value 不会触发 Vue 更新，得走原生 setter + input 事件 */
    async function fill(testid, value) {
      const ok = await page.evaluate((sel, val) => {
        const el = document.querySelector(sel);
        if (!el) return false;
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(el, val);
        el.dispatchEvent(new Event('input', { bubbles: true }));
        return true;
      }, `[data-testid="${testid}"]`, value);
      if (!ok) die(`页面上找不到输入框 ${testid}`);
    }
    const stepText = () => page.evaluate(() =>
      document.querySelector('.card-head .sub') ? document.querySelector('.card-head .sub').innerText : '');

    await page.goto(PLATFORM + '/#/new', { waitUntil: 'networkidle2', timeout: 30000 });
    if (await waitFor(page, () => !!document.querySelector('[data-testid="view-wizard"]'), null, 10000) < 0) {
      die('打不开新建项目向导');
    }
    await fill('wz-name', '前端验收建的项目');
    await fill('wz-id', WZ_ID);
    await page.click('[data-testid="wz-next"]');            // 1 → 2
    await sleep(600);
    await fill('wz-repo', seed.repoDir);

    // 「增量基线」是整套配置里最难填的一项：人填不出来往往不是不懂这个概念，
    // 是不知道<b>这个仓库里有什么可填</b>。所以候选必须来自真实仓库 ——
    // 前端写死 main 的话，主干叫 master 的仓库会拿到一个选了就报错的选项，
    // 比不给建议更糟，因为人会以为是平台坏了
    await sleep(1200);   // 候选是跟着仓库路径异步取的
    const opts = await baselineOptions(page, 'wz-baseline');

    // 标准答案<b>问平台要</b>，不在 node 这边跑 git：repoDir 是「..」这种相对路径，
    // 它相对的是<b>平台进程</b>的工作目录，而 node 跑在仓库根 —— 两个基准不同，
    // 自己解析必然指到别的地方（这一条是先跑出假失败才发现的）。
    // 「候选一定解析得了」这条性质由单测守（那里仓库路径是绝对的、无歧义）；
    // 这里守的是另一半：界面列出来的，就是平台给出来的那些
    const fromApi = await (await fetch(
      `${PLATFORM}/api/git/baselines?repoDir=${encodeURIComponent(seed.repoDir)}`)).json();
    const apiRefs = new Set((fromApi.candidates || []).map(c => c.ref));
    const bad = opts.refs.filter(r => !apiRefs.has(r));
    if (!opts.refs.length) {
      fail('基线下拉一个候选都没列出来，人又回到了「不知道该填什么」');
    } else if (bad.length) {
      fail(`界面上的基线候选不是平台给的：${bad.join('、')} —— 前端自己编的选项没人保证能用`);
    } else if (opts.refs.length !== apiRefs.size) {
      fail(`平台给了 ${apiRefs.size} 个候选，界面只列出 ${opts.refs.length} 个`);
    } else if (opts.refs.includes('origin')) {
      // git 把 refs/remotes/origin/HEAD 缩写成「origin」，它是符号引用，
      // 选它等于「跟远端此刻默认指向的那个分支比」，而那是哪个分支不写在名字里
      fail('基线候选里混进了 origin（origin/HEAD 的缩写），选它无从判断在跟谁比');
    } else if (!opts.groups.length) {
      fail(`基线候选没有分组：${opts.refs.join('、')} —— 混在一起看不出分支和标签是两种东西`);
    } else {
      pass(`基线可从仓库里选：${opts.refs.length} 个候选（与平台给出的完全一致）、按「${opts.groups.join('/')}」分组`);
    }

    // 另一半：候选之外的 ref 同样合法（某个 sha、v1.2.0^、上游仓库的引用），
    // 只给下拉等于把能力砍掉一半
    await pickBaseline(page, 'wz-baseline', 'HEAD~2');
    const typed = await page.evaluate(() =>
      document.querySelector('[data-testid="wz-baseline"]').innerText.trim());
    if (!typed.includes('HEAD~2')) {
      fail(`候选里没有的 ref 填不进去（框里是「${typed}」）`);
    } else {
      pass('候选之外的 ref 也能直接输入：HEAD~2 已填入');
    }

    await pickBaseline(page, 'wz-baseline', seed.baseline);
    await page.click('[data-testid="wz-next"]');            // 2 → 3（跑 git）
    if (await waitFor(page, () => /第 3 /.test(document.querySelector('.card-head .sub').innerText),
        null, 30000) < 0) die(`向导卡在第 2 步：${await stepText()}`);
    await fill('wz-inst-host-0', wzHost);
    await fill('wz-inst-port-0', wzPort);
    await page.click('[data-testid="wz-next"]');            // 3 → 4（连探针）
    if (await waitFor(page, () => /第 4 /.test(document.querySelector('.card-head .sub').innerText),
        null, 60000) < 0) die(`向导卡在第 3 步：${await stepText()}`);
    // 先走一遍<b>真实踩到过的那条错路</b>：产物目录指向平台自己的 target/classes。
    // 它确实存在，所以「目录是否有效」这一项照旧通过 —— 光靠它挡不住。
    // 建出来的项目会满屏「源码读取失败」，而没有任何一处说得清问题在配置上，
    // 而向导本就是唯一能在这个时点挡下来的地方
    await fill('wz-classesDir', 'target/classes');   // 相对平台安装目录 → 平台自己的产物
    await fill('wz-javaSourceRoot', seed.javaSourceRoot);
    await page.click('[data-testid="wz-next"]');
    // 等「验完了」而不是定长 sleep：这一步后端要真去连全部探针，
    // 超过定长就会把「还没验完」读成「被拦住」而误报 pass（自检表只在第 6 步有，
    // 这一步的结论是走 ElMessage 弹出来的）
    // 不能用 offsetParent 判 .el-message 是否可见：它是 position: fixed，
    // 而 fixed 元素的 offsetParent 按规范就是 null（这一条是跑出来才发现的）
    const settled = await waitFor(page, () =>
      /第 5 /.test(document.querySelector('.card-head .sub').innerText)
      || document.querySelectorAll('.el-message').length > 0,
      null, 60000);
    if (settled < 0) {
      fail('填了错的产物目录后，向导既没前进也没给出提示 —— 人不知道发生了什么');
    } else if (/第 5 /.test(String(await stepText()))) {
      fail('产物目录指向了另一个工程（平台自己的 target/classes），向导却放行了 —— '
        + '建出来只会满屏「源码读取失败」，而没有一处说得清问题在配置上');
    } else {
      const why = await page.evaluate(() => [...document.querySelectorAll('.el-message')]
        .map(e => e.innerText.trim()).join(' | '));
      // 拦住了还不够，得说清是哪一项：只说「路径无效」会把人引去找一个没问题的目录
      if (!/同一个工程|一个都找不到/.test(why)) {
        fail(`向导拦住了，但没说清为什么：「${why}」—— 目录本身是有效的，人会去找一个没问题的目录`);
      } else {
        pass('产物目录指向另一个工程时向导当场拦住，并说清了是「产物与源码不是同一个工程」');
      }
      await page.evaluate(() => document.querySelectorAll('.el-message').forEach(e => e.remove()));
    }

    await fill('wz-classesDir', seed.classesDir);
    await fill('wz-javaSourceRoot', seed.javaSourceRoot);
    await page.click('[data-testid="wz-next"]');            // 4 → 5（验路径）
    if (await waitFor(page, () => /第 5 /.test(document.querySelector('.card-head .sub').innerText),
        null, 60000) < 0) die(`向导卡在第 4 步：${await stepText()}`);
    // 特意填一个非默认值：字段名与后端对不上的话，Spring 默认不报未知字段，
    // 这个值会被静默丢弃 —— 而向导这边还煞有介事地校验过 0~100
    await fill('wz-gate-full', '42');
    await page.click('[data-testid="wz-next"]');            // 5 → 6（跑完整自检）
    if (await waitFor(page, () => document.querySelectorAll('[data-testid="wz-check-row"]').length > 0,
        null, 60000) < 0) die(`向导没能跑出自检表：${await stepText()}`);

    const checkRows = await page.evaluate(() =>
      [...document.querySelectorAll('[data-testid="wz-check-row"]')]
        .map(r => ({ name: r.children[0].innerText.trim(), ok: r.children[1].innerText.trim() === '通过' })));
    const notOk = checkRows.filter(r => !r.ok);
    if (notOk.length) {
      fail(`向导自检有不通过项：${notOk.map(r => r.name).join('、')}`);
    } else {
      pass(`向导第 6 步自检 ${checkRows.length} 项全通过`);
    }

    const createDisabled = await page.evaluate(() =>
      document.querySelector('[data-testid="wz-create"]').disabled);
    if (createDisabled) {
      fail('自检全过，创建按钮却是禁用的');
    } else {
      await page.click('[data-testid="wz-create"]');
      // 建完向导会先采一次再进项目：这一步就是要证明「建出来的项目真的采得到数据」
      const got = await waitFor(page, () => !!document.querySelector('[data-testid="view-coloring"]')
        && document.querySelectorAll('[data-testid="file-item"]').length > 0, null, 60000);
      if (got < 0) {
        const h = await page.evaluate(() => location.hash);
        fail(`创建后没能进到项目并采出数据（hash=${h}）`);
      } else {
        const n = await page.evaluate(countOf, '[data-testid="file-item"]');
        const h = await page.evaluate(() => location.hash);
        if (!h.includes(WZ_ID)) fail(`创建后进的不是新项目：${h}`);
        else pass(`向导建出的项目立刻就有数据：${n} 个文件（${(got / 1000).toFixed(1)}s，${h}）`);
      }
    }

    // 向导里填的全量阈值必须真的存住
    const created = await (await fetch(`${PLATFORM}/api/projects/${WZ_ID}`)).json();
    if (!created.gate || created.gate.overallThreshold !== 42) {
      fail(`向导填的全量阈值 42 没存住，存进去的是 ${JSON.stringify(created.gate)}`
        + ' —— 字段名与后端对不上时会被静默丢弃');
    } else {
      pass('向导填的门禁阈值真的存进了项目配置（overallThreshold=42）');
    }

    // ---------- 6c · 设置页把「改完即生效」接上 ----------
    // 这是阶段 2 那套热生效能力在页面上唯一的入口。按钮接空函数的话，
    // 后端能力在 UI 上够不着，而页面上看不出少了什么
    await page.goto(PLATFORM + '/#/p/' + WZ_ID + '/settings', { waitUntil: 'networkidle2', timeout: 30000 });
    if (await waitFor(page, () => !!document.querySelector('[data-testid="st-inst-row"]'), null, 15000) < 0) {
      fail('打不开项目设置页');
    } else {
      const instRows = await page.evaluate(countOf, '[data-testid="st-inst-row"]');
      if (instRows !== created.instances.length) {
        fail(`设置页列出 ${instRows} 个实例，配置里有 ${created.instances.length} 个`);
      } else {
        pass(`设置页读出了 ${instRows} 个实例`);
      }
      // 设置页的基线也是同一个可选可输入的控件，不是输入框
      const stOpts = await baselineOptions(page, 'st-baseline');
      if (!stOpts.refs.length) {
        fail('设置页的基线下拉一个候选都没有 —— 改配置时同样需要知道这个仓库里有什么可填');
      } else {
        pass(`设置页的基线同样能从仓库里选：${stOpts.refs.length} 个候选`);
      }
      await pickBaseline(page, 'st-baseline', 'HEAD~2');
      await page.click('[data-testid="st-save"]');
      const saved = await waitFor(page, () => !document.querySelector('[data-testid="view-settings"]'),
        null, 30000);
      const after = await (await fetch(`${PLATFORM}/api/projects/${WZ_ID}`)).json();
      if (after.baseline !== 'HEAD~2') {
        fail(`设置页保存后基线仍是 ${after.baseline}，改动没生效`);
      } else {
        pass(`设置页改基线立即生效：HEAD~1 → ${after.baseline}（${(saved / 1000).toFixed(1)}s）`);
      }
    }

    // 删之前必须真的重载一次页面。保存成功时 settings 已经 emit('saved') 跳回了列表页，
    // 但<b>回列表页并不关 WebSocket</b>（connectWs 只在 setProject 里调），
    // 那条连接仍挂在这个项目上，onmessage 照样对它打 /coverage/gate 与 /coverage/file ——
    // 项目一删就全是 404，会被结尾「全程没有意外的 4xx」抓个正着，且只在时序赶巧时出现。
    // reload 之后 hash 是 #/projects，syncRoute 判定不在项目内，压根不会再建连接
    await page.reload({ waitUntil: 'networkidle2', timeout: 30000 });
    if (await waitFor(page, () => !!document.querySelector('[data-testid="view-projects"]'),
        null, 15000) < 0) die('删项目前没能退回项目列表');

    // 用例要能重复跑：不删掉的话，下一次会撞「标识已被占用」而失败，
    // 而报出来的原因与被测功能毫无关系
    const delResp = await fetch(`${PLATFORM}/api/projects/${WZ_ID}`, { method: 'DELETE' });
    if (!delResp.ok) fail(`收尾删除向导建的项目失败（HTTP ${delResp.status}）`);
    else pass('向导建的项目已删除，用例可重复跑');

    // ---------- 6d · 采集事件：真的停一台实例，看它记不记得住 ----------
    // 这一页存在的理由是「事后追溯」：lastError 只挂在当前快照上，下一轮成功就被冲掉。
    // 只断言「页面能打开」证明不了这件事 —— 必须真造一次掉线再恢复
    // limit 必须与页面用的那个一致（views/events.js 取 200）：不一致的话，
    // default 的事件一旦超过接口的上限，页面渲染出的行数就永远多于接口返回的条数，
    // 「页面与接口对得上」这条断言会变成永远等不到
    const EV_LIMIT = 200;
    async function eventsFromApi() {
      return (await fetch(`${PLATFORM}/api/projects/default/events?limit=${EV_LIMIT}`)).json();
    }
    /** 本次窗口之前就已经存在的事件，用来把「新增的」和「历史遗留的」分开 */
    const evSince = (list, floorIso) =>
      (list || []).filter(e => !floorIso || new Date(e.at).getTime() > new Date(floorIso).getTime());
    const evBefore = await eventsFromApi();
    // 这一刻之前的事件都算历史：default 项目删不掉，它的事件只增不减，
    // 不划一条线的话，下面的断言会匹配到上一次 verify 留下的掉线/恢复组合，
    // 在真失败之上再叠一条假成功
    const evFloor = (evBefore.events || []).length ? evBefore.events[0].at : null;
    if (!evBefore.available) {
      // 库不可用时这一页本就该说清原因而不是回空列表，那是另一条设计路径
      pass(`采集事件不可用时给出了原因（${evBefore.error}）`);
    } else {
      const bash = process.env.SHELL_BASH || 'bash';
      const runLocal = (cmd) => execFileSync(bash, ['scripts/run_local.sh', cmd],
        { cwd: process.cwd(), stdio: 'pipe', timeout: 180000 });
      try {
        runLocal('demo2-stop');
        // 掉线要被下一轮采集看见：轮询 3s + 一轮采集，给足 40 秒
        let partial = null;
        for (let n = 0; n < 40 && !partial; n++) {
          const d = await eventsFromApi();
          partial = evSince(d.events, evFloor).find(e => e.status === 'PARTIAL');
          if (!partial) await sleep(1000);
        }
        if (!partial) {
          fail('停掉一台实例后，采集事件里没有出现 PARTIAL —— 事后追溯不到这次掉线');
        } else if (!String(partial.detail || '').includes('6301')) {
          fail(`事件记下了 PARTIAL，但没点名是哪台实例：${partial.detail}`);
        } else {
          pass(`停掉一台实例后记下了事件，并点名了是哪台：${String(partial.detail).slice(0, 46)}…`);
        }
      } finally {
        // 无论上面成败都要把实例拉回来，否则后续跑什么都不对
        runLocal('demo2-start');
      }
      let restored = null;
      for (let n = 0; n < 40 && !restored; n++) {
        const d = await eventsFromApi();
        // 只在本次新增的那几条里找，别把上一次 verify 留下的组合认成这次的
        const list = evSince(d.events, evFloor);
        // 新的在前：恢复那条必须排在 PARTIAL 之前
        const iPartial = list.findIndex(e => e.status === 'PARTIAL');
        if (iPartial > 0 && list[iPartial - 1].status === 'CONNECTED') restored = list[iPartial - 1];
        if (!restored) await sleep(1000);
      }
      if (!restored) fail('实例拉回来之后，采集事件里没有出现「恢复正常」');
      else pass('实例恢复后也记下了一条，掉线区间在事件流里是闭合的');

      // 只记变化，不是每轮一条。
      //
      // 断的必须是「本次窗口新增了几条」，不能断表的绝对行数：collect_event 只在删项目时
      // 清理，而 default 按设计删不掉 —— 行数只增不减，跑几轮 verify 之后必然越过任何
      // 固定阈值，报出来的却是「看起来是每轮都记」，把人指向完全错误的方向。
      const evAfter = await eventsFromApi();
      const added = evSince(evAfter.events, evFloor).length;
      // 这段窗口里停了一次、起了一次，掐头去尾也就三四条；每轮都记的话是几十条
      if (added > 8) {
        fail(`这段窗口里新增了 ${added} 条采集事件 —— 看起来是每轮都记，而不是只记状态变化`);
      } else {
        pass(`这段窗口新增 ${added} 条采集事件：只记状态变化，没有把每轮采集刷进去`);
      }

      // 页面与接口对得上
      await page.goto(PLATFORM + '/#/p/default/events', { waitUntil: 'networkidle2', timeout: 30000 });
      // 等的是「行渲染出来」而不是「视图元素出现」：数据还在路上时视图已经在了，
      // 立刻去数行数只会数到 0 —— 这种抢跑会让断言变成随机失败
      const shown = await waitFor(page, (n) =>
        document.querySelectorAll('[data-testid="event-row"]').length === n,
        evAfter.events.length, 15000);
      if (shown < 0) {
        const got = await page.evaluate(countOf, '[data-testid="event-row"]');
        fail(`事件页列出 ${got} 条，接口给了 ${evAfter.events.length} 条`);
      } else {
        pass(`事件页列出 ${evAfter.events.length} 条，与接口一致`);
      }
    }

    // ---------- 7 · 全程无脚本错误 ----------
    // 组件报错时 Vue 会跳过那一块继续渲染，页面「看着挺正常」，只少了一块
    if (errors.length) {
      fail('浏览器控制台有 ' + errors.length + ' 条脚本错误：' + errors.slice(0, 6).join(' | '));
    } else {
      pass('全程浏览器控制台无脚本错误');
    }
    if (httpProblems.length) {
      fail('页面打出了 ' + httpProblems.length + ' 个意料之外的失败请求：'
        + httpProblems.slice(0, 6).join(' | '));
    } else {
      pass('全程没有 5xx，也没有门禁拒判之外的 4xx');
    }
  } finally {
    await browser.close();
  }

  console.log('-'.repeat(70));
  if (failed) {
    console.log('  前端验收：存在失败项');
    process.exit(1);
  }
  console.log('  前端验收：全部通过');
})().catch(e => {
  // die() 抛上来的：此时 finally 已经把浏览器关掉了。这里只负责把话说清楚 ——
  // 交给 Node 默认处理会打一坨栈，真正那句原因反而要翻半天
  console.error('  [FAIL] ' + e.message);
  console.log('-'.repeat(70));
  console.log('  前端验收：存在失败项');
  process.exit(1);
});
