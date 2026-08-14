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

  ws.close();
  console.log('-'.repeat(70));
  console.log('  推送链路验证：全部通过');
})();
