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

    // 排行的用处是「找到该补的文件、然后去看它」。路由改成两级之后，只写 #/coloring
    // 匹配不上 #/p/<id>/<view>，点文件名会退回项目列表 —— 只数行数抓不到这种坏法
    await page.evaluate(() => document.querySelectorAll('.rank-name')[0].click());
    const jumped = await waitFor(page, () => !!document.querySelector('[data-testid="view-coloring"]'),
      null, 8000);
    if (jumped < 0) {
      fail(`点排行里的文件名没跳进染色视图（hash=${await page.evaluate(() => location.hash)}）`);
    } else {
      pass('点排行里的文件名跳进了染色视图');
    }
    await page.click('[data-testid="nav-overview"]');
    await waitFor(page, () => !!document.querySelector('[data-testid="view-overview"]'), null, 8000);

    // CI 抄的那条命令必须带项目：不带的话恒落在默认项目上，在别的项目页面照抄进 CI，
    // 判的是 default 的覆盖率，返回 200 且字段齐全，CI 侧看不出打错了项目
    const ciText = await page.evaluate(textOf, '.gate-ci');
    if (ciText && !ciText.includes('/api/projects/')) {
      fail(`门禁卡给 CI 的命令没带项目：${ciText.slice(0, 80)}`);
    } else if (ciText) {
      pass('门禁卡给 CI 的命令带上了项目路径');
    }

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

    // ---------- 4b · 门禁结论与覆盖率数字同卡 ----------
    // 看的人要的是「这个数字够不够」。这句话与下面那张门禁卡必须是同一个结论，
    // 一张卡说达标、另一张说不通过的话，没人知道该信哪个
    const gateResp2 = await fetch(`${PLATFORM}/api/coverage/gate?mode=full`);
    const gate2 = await gateResp2.json();
    const statGate = await page.evaluate(textOf, '[data-testid="stat-gate"]');
    if (!gateResp2.ok) {
      if (statGate !== '门禁判不了') fail(`接口拒判，覆盖率卡上却写着「${statGate}」`);
      else pass('门禁判不了时，覆盖率卡上明确写「门禁判不了」');
    } else {
      const want = '门槛 ' + gate2.threshold + '% ' + (gate2.passed ? '已达标' : '未达标');
      if (statGate !== want) fail(`覆盖率卡上写「${statGate}」，接口给的是「${want}」`);
      else pass(`门禁结论与覆盖率数字同卡且一致：${statGate}`);
    }

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

    // ---------- 4d · 导出 CSV ----------
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
      } else if (!/口径|全量|增量|场景/.test(csvFile)) {
        // 口径不写进文件名的话，隔几天再打开就分不清这是增量还是全量的数字
        fail(`CSV 文件名没有写明口径：${csvFile}`);
      } else {
        const cols = lines[1].split(',').length;
        if (cols !== 7) fail(`CSV 数据行有 ${cols} 列，应为 7 列：${lines[1]}`);
        else pass(`导出的 CSV 落盘可读：${csvFile}，${lines.length - 1} 行 × 7 列`);
      }
    }
    fs.rmSync(dlDir, { recursive: true, force: true });

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
      if (!ok) die(`向导里找不到字段 ${testid}`);
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
    await fill('wz-baseline', seed.baseline);
    await page.click('[data-testid="wz-next"]');            // 2 → 3（跑 git）
    if (await waitFor(page, () => /第 3 /.test(document.querySelector('.card-head .sub').innerText),
        null, 30000) < 0) die(`向导卡在第 2 步：${await stepText()}`);
    await fill('wz-inst-host-0', wzHost);
    await fill('wz-inst-port-0', wzPort);
    await page.click('[data-testid="wz-next"]');            // 3 → 4（连探针）
    if (await waitFor(page, () => /第 4 /.test(document.querySelector('.card-head .sub').innerText),
        null, 60000) < 0) die(`向导卡在第 3 步：${await stepText()}`);
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
      await fill('st-baseline', 'HEAD~2');
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

    // 用例要能重复跑：不删掉的话，下一次会撞「标识已被占用」而失败，
    // 而报出来的原因与被测功能毫无关系
    const delResp = await fetch(`${PLATFORM}/api/projects/${WZ_ID}`, { method: 'DELETE' });
    if (!delResp.ok) fail(`收尾删除向导建的项目失败（HTTP ${delResp.status}）`);
    else pass('向导建的项目已删除，用例可重复跑');

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
