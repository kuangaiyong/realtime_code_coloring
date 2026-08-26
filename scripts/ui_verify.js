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
      if (m.type() === 'error' && !noise(m.location() && m.location().url)) {
        errors.push('console: ' + m.text());
      }
    });
    page.on('pageerror', e => errors.push('pageerror: ' + e.message));
    page.on('requestfailed', r => { if (!noise(r.url())) errors.push('requestfailed: ' + r.url()); });

    await page.goto(PLATFORM + '/', { waitUntil: 'networkidle2', timeout: 30000 });

    // ---------- 1 · 应用真的挂载起来了 ----------
    const mounted = await waitFor(page, () => !document.querySelector('#app .boot')
      && !!document.querySelector('[data-testid="view-coloring"]'), null, 15000);
    if (mounted < 0) {
      const boot = await page.evaluate(() => document.querySelector('#app').innerText.slice(0, 300));
      die(`Vue 没能挂载，页面停在：${boot}\n  控制台：${errors.slice(0, 5).join(' | ')}`);
    }
    pass(`应用在 ${mounted}ms 内挂载完成，默认进入代码染色视图`);

    for (const t of ['nav-coloring', 'nav-overview', 'nav-onboard', 'probe-pill', 'overall',
                     'mode-full', 'mode-incremental', 'btn-collect', 'btn-reset',
                     'scenario-view', 'scenario-id', 'btn-scenario', 'banner']) {
      const n = await page.evaluate(countOf, `[data-testid="${t}"]`);
      if (n !== 1) fail(`骨架钩子 ${t} 出现 ${n} 次，应为 1 次`);
    }
    pass('侧边栏、顶栏、场景条的骨架钩子齐备');

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

    // ---------- 4 · 总览看板 ----------
    await page.click('[data-testid="nav-overview"]');
    const onDash = await waitFor(page, () => !!document.querySelector('[data-testid="view-overview"]'),
      null, 5000);
    if (onDash < 0) die('切不到总览看板');
    pass('侧边栏可切到总览看板');

    const statCount = await page.evaluate(countOf, '.stats .stat');
    if (statCount !== 6) fail(`看板统计卡 ${statCount} 张，应为 6 张`);
    else pass('看板 6 张统计卡齐备');

    const instRows = await page.evaluate(countOf, '[data-testid="inst-row"]');
    if (instRows !== sum.instances.length) {
      fail(`实例表 ${instRows} 行，API 给了 ${sum.instances.length} 个实例`);
    } else {
      pass(`实例表 ${instRows} 行，与 API 一致`);
    }

    const rankRows = await page.evaluate(countOf, '[data-testid="rank-table"] tbody tr');
    if (rankRows !== sum.files.length) fail(`排行表 ${rankRows} 行，应为 ${sum.files.length} 行`);
    else pass(`排行表 ${rankRows} 行，与文件数一致`);

    // 门禁三态：通过 / 不通过 / 无法判定。渲染成别的字样就说明分支没接上
    const verdict = await page.evaluate(textOf, '[data-testid="gate-verdict"]');
    if (!['通过', '不通过', '无法判定'].includes(verdict)) {
      fail(`门禁结论渲染成「${verdict}」，不在三态之内`);
    } else {
      const gateResp = await fetch(`${PLATFORM}/api/coverage/gate?mode=full`);
      const gate = await gateResp.json();
      const want = !gateResp.ok ? '无法判定' : (gate.passed ? '通过' : '不通过');
      if (verdict !== want) fail(`页面写「${verdict}」，接口给的是「${want}」—— 这是「能不能合并」的结论，不能对不上`);
      else pass(`门禁结论与接口一致：${verdict}`);
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

    // 各实例覆盖是按需拉取的，拉完表格要真的多出三列
    const colsBefore = await page.evaluate(countOf, '[data-testid="inst-table"] thead th');
    await page.click('[data-testid="btn-per-inst"]');
    const grew = await waitFor(page, (n) =>
      document.querySelectorAll('[data-testid="inst-table"] thead th').length === n + 3,
      colsBefore, 120000);
    if (grew < 0) fail('点了「加载各实例覆盖」但表格没有多出三列');
    else pass(`各实例覆盖已加载，表格由 ${colsBefore} 列增至 ${colsBefore + 3} 列（${(grew / 1000).toFixed(1)}s）`);

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

    // ---------- 7 · 全程无脚本错误 ----------
    // 组件报错时 Vue 会跳过那一块继续渲染，页面「看着挺正常」，只少了一块
    if (errors.length) {
      fail(`浏览器控制台有 ${errors.length} 条错误：\n      ` + errors.slice(0, 6).join('\n      '));
    } else {
      pass('全程浏览器控制台无错误、无失败请求');
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
