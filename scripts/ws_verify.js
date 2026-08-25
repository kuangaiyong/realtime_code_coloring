/**
 * 验证实时推送链路：覆盖率变化时，服务端应主动把新数据推给染色视图，
 * 而不是依赖前端轮询。
 *
 * 步骤：连上 WebSocket → 调用此前未覆盖的接口 C（退款）→ 应在超时前收到推送，
 * 且推送内容中 refund 相关行的覆盖率高于调用前。
 */
const PLATFORM = 'http://localhost:18090';
const DEMO = 'http://localhost:18080';
const TIMEOUT_MS = 10000;

function fail(msg) { console.error('  [FAIL] ' + msg); process.exit(1); }

(async () => {
  console.log('='.repeat(70));
  console.log('实时推送链路验证');
  console.log('='.repeat(70));

  const before = await (await fetch(`${PLATFORM}/api/coverage/summary`)).json();
  const beforeRatio = before.overallRatio;
  console.log(`  调用前整体覆盖率: ${beforeRatio}%`);

  const ws = new WebSocket(`ws://localhost:18090/ws/coverage`);
  const got = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('超时未收到推送')), TIMEOUT_MS);
    ws.onmessage = (ev) => { clearTimeout(timer); resolve(JSON.parse(ev.data)); };
    ws.onerror = (e) => { clearTimeout(timer); reject(new Error('WebSocket 错误')); };
  });

  // 另开一个订阅了别的项目的会话。推送若不分项目，它会收到本项目的数据 ——
  // 页面拿着别人的覆盖率重绘，界面上看不出这是串台，只会觉得数字莫名跳变
  const OTHER = '__no_such_project__';
  const wsOther = new WebSocket(`ws://localhost:18090/ws/coverage?project=${OTHER}`);
  let leaked = null;
  wsOther.onmessage = (ev) => { leaked = leaked || ev.data; };
  // 两个连接的 onopen 都要在创建后立刻挂上：等完前一个再挂后一个的话，
  // 后一个可能已经 open 过了，事件早已过去，处理器再挂上去永远不触发
  const otherOpen = new Promise((r, j) => {
    wsOther.onopen = r;
    setTimeout(() => j(new Error('WebSocket 连接超时（跨项目会话）')), 5000);
  });

  await new Promise((r, j) => {
    ws.onopen = r;
    setTimeout(() => j(new Error('WebSocket 连接超时')), 5000);
  });
  await otherOpen;
  console.log(`  WebSocket 已连接（本项目 1 个会话，项目 ${OTHER} 1 个会话）`);

  // 触发一次新的覆盖：先让订单回到可退款状态，再调用退款接口
  await fetch(`${DEMO}/api/order/callback?bizNo=A1002&status=SUCCESS`, { method: 'POST' });
  const t0 = Date.now();
  const refundResp = await (await fetch(`${DEMO}/api/order/refund?bizNo=A1002&amount=100`, { method: 'POST' })).json();
  console.log(`  >> POST /api/order/refund  响应: ${JSON.stringify(refundResp)}`);

  let payload;
  try {
    payload = await got;
  } catch (e) {
    fail(e.message);
  }
  const latency = Date.now() - t0;

  console.log(`  收到服务端推送，延迟 ${latency} ms`);
  console.log(`  推送内容: 探针=${payload.probeStatus} 整体=${payload.overallRatio}% 文件数=${payload.files.length}`);

  if (payload.probeStatus !== 'CONNECTED') fail('推送中的探针状态异常: ' + payload.probeStatus);
  if (!(payload.overallRatio > beforeRatio)) {
    fail(`覆盖率未上升: ${beforeRatio}% -> ${payload.overallRatio}%`);
  }
  console.log(`  [PASS] 覆盖率由 ${beforeRatio}% 上升至 ${payload.overallRatio}%`);
  if (latency > 6000) fail(`推送延迟 ${latency}ms 过高`);
  console.log(`  [PASS] 推送延迟 ${latency}ms 在可接受范围`);

  // 本项目已经收到推送了，再宽限一会儿：过滤若失效，跨项目会话是同一轮循环里发的，
  // 不会比这更晚。宽限只是排除调度抖动，不是等它慢慢来
  await new Promise((r) => setTimeout(r, 1000));
  if (leaked) fail(`推送串到了项目 ${OTHER} 的会话上：${String(leaked).slice(0, 120)}`);
  console.log(`  [PASS] 推送未串到项目 ${OTHER} 的会话（该项目不存在，本就不该收到任何数据）`);
  wsOther.close();

  // 静态染色页面可访问
  const page = await fetch(`${PLATFORM}/`);
  const html = await page.text();
  if (page.status !== 200) fail('染色页面返回 ' + page.status);
  for (const marker of ['代码实时染色平台', '/ws/coverage', '/api/coverage/summary']) {
    if (!html.includes(marker)) fail(`页面缺少预期内容: ${marker}`);
  }
  console.log(`  [PASS] 染色页面可访问（${html.length} 字节，含 WebSocket 与 API 调用）`);

  // 总览看板与覆盖率排行。渲染发生在浏览器里，这里断言不了像素，
  // 但能断言两件真会坏的事：视图钩子在不在、以及看板赖以计算的字段在不在。
  // 后端哪天少返回一个字段，看板会安静地渲染成空白 —— 正是本项目最怕的静默失效
  for (const marker of ['data-view="dash"', 'id="viewDash"', 'id="dashStats"',
                        'id="instBox"', 'id="rankBox"', 'data-rank="ratio"', 'data-rank="missed"',
                        'id="trendBox"', 'id="trendMeta"', 'id="btnPerInst"',
                        'id="gateBox"', 'id="gateMeta"',
                        'data-view="onboard"', 'id="viewOnboard"', 'id="langTabs"',
                        'id="obPane"', 'id="obCheckBox"', 'id="obCheckMeta"',
                        'data-lang="java"', 'data-lang="go"', 'data-lang="cpp"', 'data-lang="rust"',
                        'data-trend="session"', 'data-trend="build"']) {
    if (!html.includes(marker)) fail(`看板视图缺少钩子: ${marker}`);
  }
  console.log('  [PASS] 看板、排行与曲线的视图钩子齐备');

  // 曲线画的是本次会话内的采样，不是跨构建历史（平台目前零持久化）。
  // 这句免责一旦被删掉，用户会把一段会话内的爬升读成「这个项目的覆盖率长期趋势」
  if (!html.includes('非跨构建历史')) fail('覆盖率曲线缺少「非跨构建历史」的声明');
  console.log('  [PASS] 覆盖率曲线声明了数据范围（会话内采样，非跨构建历史）');

  const dash = await (await fetch(`${PLATFORM}/api/coverage/summary`)).json();
  if (!Array.isArray(dash.instances) || dash.instances.length === 0) fail('summary 未返回 instances[]，看板的实例表会空白');
  for (const i of dash.instances) {
    for (const k of ['endpoint', 'status', 'buildCommit', 'dirty']) {
      if (!(k in i)) fail(`实例 ${i.endpoint} 缺少字段 ${k}`);
    }
    if (!/^(java|go|cpp|rust):\/\//.test(i.endpoint)) fail(`看板按 endpoint 前缀判定语言，但取到 ${i.endpoint}`);
    // 接入向导要把这个端口填进它给出的启动命令里。解析不出来的话命令是错的，
    // 而照着错命令起的服务连不上，人只会以为是自己配错了
    const hp = String(i.endpoint).split('://')[1] || '';
    const at = hp.lastIndexOf(':');
    if (at <= 0 || !/^\d+$/.test(hp.substring(at + 1))) {
      fail(`接入向导要从 ${i.endpoint} 里取出 host:port 填进启动命令，但解析不出端口`);
    }
  }
  console.log(`  [PASS] 实例表字段齐备，${dash.instances.length} 个实例的语言前缀均可识别`);

  if (!dash.lastCollectedAt || isNaN(new Date(dash.lastCollectedAt).getTime())) {
    fail(`lastCollectedAt 不可解析: ${dash.lastCollectedAt}`);
  }
  console.log(`  [PASS] 最后采集时间可解析（${dash.lastCollectedAt}）`);

  for (const f of dash.files) {
    for (const k of ['path', 'sourceFileName', 'packageName', 'ratio', 'coveredLines', 'missedLines']) {
      if (!(k in f)) fail(`文件 ${f.path} 缺少排行需要的字段 ${k}`);
    }
  }
  // 两种排序必须给出不同的问题的答案，否则留两个按钮没有意义
  const byRatio = dash.files.slice().sort((a, b) => a.ratio - b.ratio || b.missedLines - a.missedLines);
  const byMissed = dash.files.slice().sort((a, b) => b.missedLines - a.missedLines || a.ratio - b.ratio);
  for (let n = 1; n < byRatio.length; n++) {
    if (byRatio[n].ratio < byRatio[n - 1].ratio) fail('「覆盖率最低」排序不是非递减');
    if (byMissed[n].missedLines > byMissed[n - 1].missedLines) fail('「未覆盖行最多」排序不是非递增');
  }
  if (new Set(byRatio.map(f => f.path)).size !== dash.files.length) fail('排序后文件集合发生变化');
  console.log(`  [PASS] ${dash.files.length} 个文件两种排序均单调且不丢文件`
    + `（最低 ${byRatio[0].sourceFileName} ${byRatio[0].ratio}%`
    + ` / 缺口最大 ${byMissed[0].sourceFileName} ${byMissed[0].missedLines} 行）`);

  const covered = dash.files.reduce((a, f) => a + f.coveredLines, 0);
  const missed = dash.files.reduce((a, f) => a + f.missedLines, 0);
  const derived = Math.round((covered / (covered + missed)) * 1000) / 10;
  if (Math.abs(derived - dash.overallRatio) > 0.15) {
    fail(`看板的行数合计与服务端 overallRatio 对不上: 合计推出 ${derived}%，服务端 ${dash.overallRatio}%`);
  }
  console.log(`  [PASS] 看板行数合计（已覆盖 ${covered} / 未覆盖 ${missed}）与服务端 ${dash.overallRatio}% 自洽`);

  // 跨构建趋势。历史不可用时必须给出原因 —— 回一张空图会被读成
  // 「这个项目一直没有覆盖」，而真实原因可能只是数据库没起
  const tr = await (await fetch(`${PLATFORM}/api/coverage/trend`)).json();
  if (typeof tr.available !== 'boolean') fail('趋势接口未返回 available 标志');
  if (!tr.available) {
    if (!tr.error) fail('历史不可用却没有说明原因');
    console.log(`  [PASS] 历史不可用时明确给出了原因（${tr.error}）`);
  } else {
    if (!Array.isArray(tr.builds)) fail('趋势接口未返回 builds[]');
    for (const b of tr.builds) {
      if (!/^[0-9a-f]{40}$/.test(b.buildCommit)) fail(`历史里的 buildCommit 不是 40 位 sha: ${b.buildCommit}`);
      if (b.coveredLines < 0 || b.overallRatio < 0 || b.overallRatio > 100) {
        fail(`历史记录数值异常: ${JSON.stringify(b)}`);
      }
    }
    // 记录的是峰值，所以任一构建的已覆盖行不该为 0（真跑过才会入库）
    console.log(`  [PASS] 跨构建趋势可用，${tr.builds.length} 个构建记录，commit 与数值均合法`);
  }

  // 门禁卡片。渲染断言不了，但能断言卡片赖以显示的字段在不在 ——
  // 少一个字段，卡片会安静地显示成 undefined，而它写的是「能不能合并」
  const gateResp = await fetch(`${PLATFORM}/api/coverage/gate?mode=full`);
  const gate = await gateResp.json();
  // 先看状态码。拒判（409）时回的是 {ok,error}，直接去查字段会报「缺少字段 mode」——
  // 正是这次改动要消除的那种混淆，不能在验收脚本里自己再犯一次
  if (!gateResp.ok) fail(`门禁拒判（HTTP ${gateResp.status}）：${gate.error}`);
  for (const k of ['mode', 'metric', 'threshold', 'passed', 'reason', 'actual',
                   'coveredLines', 'missedLines']) {
    if (!(k in gate)) fail(`门禁结果缺少字段 ${k}`);
  }
  if (typeof gate.passed !== 'boolean') fail(`passed 必须是布尔，CI 直接拿它做分支：${gate.passed}`);
  // actual 为 null 是「没有可判定的可执行代码」，不是数值对不上：
  // null - x 会算成 -x，不挡掉就成了一条假失败
  if (gate.actual !== null && Math.abs(gate.actual - dash.overallRatio) > 0.001) {
    fail(`门禁给出 ${gate.actual}%，页面显示 ${dash.overallRatio}% —— 两个数不一样，没人能自己想明白`);
  }
  console.log(`  [PASS] 门禁字段齐备且与页面同数：${gate.metric} ${gate.actual}%`
    + ` 对阈值 ${gate.threshold}% → passed=${gate.passed}`);

  // 「判不了」必须是 4xx。与「不通过」同为 200 的话，CI 那句 curl -f
  // 会把平台自己出的问题当成覆盖不达标，开发被指去补测试
  const bad = await fetch(`${PLATFORM}/api/coverage/gate?mode=incremental&baseline=no-such-ref`);
  if (bad.status === 200) fail('基线不存在时门禁仍返回 200，判不了被当成了判定结果');
  const badBody = await bad.json();
  if (!badBody.error) fail('门禁拒判时没有说明原因');
  console.log(`  [PASS] 判不了时返回 HTTP ${bad.status} 而非 200（${badBody.error}）`);

  ws.close();
  console.log('-'.repeat(70));
  console.log('  推送链路验证：全部通过');
})();
