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

  await new Promise((r, j) => {
    ws.onopen = r;
    setTimeout(() => j(new Error('WebSocket 连接超时')), 5000);
  });
  console.log('  WebSocket 已连接');

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
                        'id="trendBox"', 'id="trendMeta"', 'id="btnPerInst"']) {
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

  ws.close();
  console.log('-'.repeat(70));
  console.log('  推送链路验证：全部通过');
})();
