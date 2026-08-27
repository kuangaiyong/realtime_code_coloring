import { api } from './api.js';

const { reactive } = Vue;

/**
 * 三个视图共用的状态与动作。
 *
 * <b>为什么要有这一层，而不是各视图自己去取数：</b>看板与染色用的必须是<b>同一份</b>
 * summary。各取一次不只是浪费 —— 两份数据可能来自不同轮采集，于是顶栏写着 17.3%、
 * 看板写着 16.8%，而两个数字都"对"，没人能看出哪个是当下的真相。
 *
 * 口径（全量 / 增量 / 某个场景）同理：它决定了下面每一个数字的含义，
 * 必须只有一处真相。
 */
export const store = reactive({
  /**
   * 当前看的是哪个项目。
   *
   * 它决定了下面每一次取数打的是哪条地址 —— 换项目而漏换某一处，页面上会出现
   * 「A 项目的文件列表配 B 项目的覆盖率」，两边都是真数字，看不出是串台。
   * 所以取数一律经 url() 拼地址，不在各处手写路径。
   */
  projectId: 'default',
  projectName: '',

  // ---- 口径 ----
  mode: 'full',
  baseline: 'HEAD~1',
  /** 看哪一份数据：空串是实时累计覆盖，否则是某个已归档场景的独占覆盖 */
  viewScenario: '',

  // ---- 数据 ----
  summary: null,
  /**
   * 最近一份"取得到数据"的 summary。
   *
   * 接入自检表专用：触发"视图不可用"的典型原因就是增量返回 409（某台实例脏了、
   * 或实例间版本不一致），而这张表正是唯一能点名"是哪一台"的地方。
   * 跟着一起清掉，等于把诊断信息一起收走。
   */
  lastGood: null,
  gate: null,
  gateError: null,
  banner: null,

  // ---- 染色 ----
  current: null,
  file: null,
  /** 上一次的逐行状态，用来让"刚刚变绿"这件事可见 */
  prevStatus: {},

  // ---- 看板 ----
  rankBy: 'ratio',
  /** 会话内的覆盖率采样。这不是跨构建历史 —— 刷新页面即从头开始，界面上必须写明 */
  trend: [],
  trendKey: '',
  trendScope: 'session',
  buildTrend: [],
  buildTrendErr: null,
  /** 各实例分别的覆盖。按需拉取，且是拉取那一刻的定格值 */
  perInst: null,
  perInstAt: '',
  perInstLoading: false,

  // ---- 场景 ----
  scenarios: [],
  activeScenario: null
});

/** 把项目 id 拼进地址。id 由用户自取，必须转义 —— 它会落进 URL 路径 */
function url(path) {
  return '/api/projects/' + encodeURIComponent(store.projectId) + path;
}

/** 视图里也要拼这个地址（例如总览页展示给 CI 抄的那条命令），导出同一份实现 */
export { url as projectUrl };

/** 有数据可显示。PARTIAL 少了某台实例那部分，但其余仍是真的，照常显示 */
export function hasData(d) {
  return !!d && (d.probeStatus === 'CONNECTED' || d.probeStatus === 'PARTIAL'
    || d.probeStatus === 'ARCHIVED');
}

function params() {
  const p = store.mode === 'incremental'
    ? 'mode=incremental&baseline=' + encodeURIComponent(store.baseline.trim())
    : 'mode=full';
  return store.viewScenario ? p + '&scenarioId=' + encodeURIComponent(store.viewScenario) : p;
}

export { params };

/**
 * 服务端算不出可信结果时返回 4xx。此时必须清空视图：
 * 留着上一次的染色结果，用户会以为那就是本次的答案。
 *
 * 看板同样要清：一屏可信的数字比染色更容易被当成结论。
 * 曲线也要作废 —— 留着上一段走势会被读成"刚刚还在涨"。
 * 门禁比其它数字更像"结论"，留着上一次的会被直接拿去决定合不合并。
 */
function unavailable(msg) {
  store.banner = { level: 'err', text: '当前视图不可用：' + msg };
  store.summary = null;
  store.file = null;
  store.trend = [];
  store.gate = null;
  store.gateError = null;
}

/** 把一份新的 summary 装进 store，并做那些"不是渲染"的副作用 */
function applySummary(d) {
  store.summary = d;
  if ((d.instances || []).length) {
    store.lastGood = d;
  }

  // 口径一换，纵轴的含义就变了：把增量的 12% 和全量的 30% 画进同一条线是骗人的，
  // 所以换口径 / 换场景就把采样清掉重开
  const key = d.mode + '|' + (d.scenarioId || '');
  if (key !== store.trendKey) {
    store.trendKey = key;
    store.trend = [];
  }
  if (hasData(d)) {
    store.trend.push({ t: Date.now(), r: d.overallRatio });
    if (store.trend.length > 240) store.trend.shift();
  }

  // 版本不一致时聚合结果会静默少算，比缺一台实例更危险，所以排在最前面说
  if (d.versionError) {
    store.banner = { level: 'err', text: d.versionError };
  } else if (d.probeStatus === 'PARTIAL' && d.lastError) {
    store.banner = { level: 'warn', text: d.lastError +
      '\n这些实例跑过的代码在下面会显示成未覆盖，别据此判断「没测到」。' };
  } else if (d.probeStatus === 'CONFIG_ERROR' && d.lastError) {
    store.banner = { level: 'err', text: '被测实例地址配置有误：' + d.lastError +
      '\n问题在平台侧的项目配置，不在被测服务。' };
  } else if (d.probeStatus === 'DISCONNECTED' && d.lastError) {
    store.banner = { level: 'err', text: '探针不可达：' + d.lastError +
      '\n请确认被测服务已带探针参数启动，且探针端口可达。' };
  } else if (d.probeStatus === 'ANALYZE_ERROR' && d.lastError) {
    store.banner = { level: 'err', text: '覆盖数据分析失败：' + d.lastError +
      '\n探针连接正常，问题在平台侧 —— 请检查项目配置里的产物目录是否指向被测服务的编译产物。' };
  } else {
    store.banner = null;
  }
}

export async function loadSummary() {
  try {
    const d = await api.get(url('/coverage/summary?') + params());
    applySummary(d);
    // 不 await：门禁要跑一次 git diff，让它挡在染色前面会拖慢整屏刷新
    loadGate();
    return d;
  } catch (e) {
    unavailable(e.message);
    return null;
  }
}

/**
 * 门禁与 summary 分开取：summary 拿不到判定所需的阈值与"判不了"的原因，
 * 而门禁的 409 不该把整个视图清空 —— 判不了门禁，染色仍然是好的。
 * 代价是两次请求可能落在不同轮采集上，卡片的比例会比同屏的数字差一轮，
 * 下一次推送即自愈；换口径时的乱序则必须挡掉，见 gateSeq。
 */
let gateSeq = 0;
export async function loadGate() {
  const seq = ++gateSeq;
  if (store.viewScenario) {
    // 场景快照是过去某一轮的独占覆盖，不是当前构建的整体情况；
    // 拿它判门禁，得出的结论与"这次能不能合并"无关
    store.gate = null;
    store.gateError = null;
    return;
  }
  try {
    const d = await api.get(url('/coverage/gate?') + params());
    if (seq !== gateSeq) return;
    store.gate = d;
    store.gateError = null;
  } catch (e) {
    // 换口径时前一次请求可能后到，用它的结论覆盖新口径的，卡片会停在错的答案上
    if (seq !== gateSeq) return;
    // 409 是"判不了"，与"不通过"分开显示：前者要找人看平台，后者要补测试
    store.gate = null;
    store.gateError = e.message;
  }
}

/**
 * 与 gateSeq 同一个道理：连点两个文件时两个请求并发，先点的那个若后到，
 * 就会用 A 的源码盖掉 B 的 —— 标题写着 B 的路径，正文却是 A 的行。
 * 这种错法在界面上完全看不出来，只会觉得「这文件怎么是这些代码」。
 */
let fileSeq = 0;
export async function openFile(path) {
  const seq = ++fileSeq;
  // 换文件就丢弃上一份行状态，否则会拿 A 文件第 N 行的状态去比 B 文件第 N 行，
  // 把一条从未变化的行误报成「刚刚被覆盖」
  if (path !== store.current) store.prevStatus = {};
  store.current = path;
  let d;
  try {
    d = await api.get(url('/coverage/file?path=') + encodeURIComponent(path) + '&' + params());
  } catch (e) {
    // 过期请求的错误同样要丢掉：拿它去清空视图，会把刚打开的那个好文件一起清掉
    if (seq !== fileSeq) return;
    unavailable(e.message);
    return;
  }
  if (seq !== fileSeq) return;
  const next = {};
  if (d.found) {
    for (const r of d.rows) {
      // 上一次是未覆盖、这次变成已覆盖 → 高亮闪一下，让「变绿」这件事可见
      r.justCovered = store.prevStatus[r.line] === 'MISSED' && r.status === 'COVERED';
      next[r.line] = r.status;
    }
  }
  store.file = d;
  store.prevStatus = next;
}

/** 换口径或换数据源后文件范围会变，原先选中的文件可能已不在范围内 */
export async function reload() {
  store.prevStatus = {};
  const d = await loadSummary();
  // 视图不可用时保留 current，恢复后仍回到用户原先看的那个文件
  if (!d) return;
  const keep = d.files.some(f => f.path === store.current);
  const path = keep ? store.current : (d.files.length ? d.files[0].path : null);
  store.current = null;
  if (path) {
    await openFile(path);
  } else {
    store.file = null;
  }
}

/** 重取当前口径的数据，并保持选中的文件 */
export async function refresh() {
  const d = await loadSummary();
  if (d && store.current) await openFile(store.current);
  return d;
}

export async function setMode(next) {
  store.mode = next;
  await reload();
}

export async function loadBuildTrend() {
  try {
    const d = await api.get(url('/coverage/trend'));
    store.buildTrend = d.available ? d.builds : [];
    store.buildTrendErr = d.available ? null : (d.error || '历史不可用');
  } catch (e) {
    store.buildTrend = [];
    store.buildTrendErr = e.message;
  }
}

export async function loadPerInstance() {
  store.perInstLoading = true;
  try {
    const d = await api.get(url('/coverage/instances'));
    store.perInst = d.instances;
    store.perInstAt = new Date(d.collectedAt).toLocaleTimeString();
    await refresh();
  } catch (e) {
    store.banner = { level: 'err', text: '各实例覆盖取数失败：' + e.message };
  } finally {
    store.perInstLoading = false;
  }
}

/** 场景归因：start 清零 → 跑测试 → stop 定格。列表与按钮状态都以服务端为准 */
export async function loadScenarios() {
  const d = await api.get(url('/scenario'));
  store.scenarios = d.scenarios;
  store.activeScenario = d.active;
  return d;
}

export async function toggleScenario(id) {
  const starting = !store.activeScenario;
  const target = starting
    ? url('/scenario/start?scenarioId=') + encodeURIComponent(id.trim())
    : url('/scenario/stop');
  const d = await api.post(target);
  store.banner = null;
  // 场景一结束就切过去看它覆盖了什么 —— 这正是录制这一轮的目的
  store.viewScenario = starting ? '' : d.scenarioId;
  await loadScenarios();
  await reload();
  return d;
}

export async function collectNow() {
  const d = await api.post(url('/collect'));
  // /collect 返回的是实时全量快照，增量视图和场景视图下都不能直接渲染
  if (store.mode === 'incremental' || store.viewScenario) {
    await refresh();
    return;
  }
  applySummary(d);
  loadGate();
  if (store.current) await openFile(store.current);
}

export async function resetCounters() {
  await api.post(url('/coverage/reset'));
  store.prevStatus = {};
  await refresh();
}

/**
 * 当前这条推送连接。换项目时要先把旧的关掉 ——
 * 留着的话，A 项目的推送会把正在看 B 项目的页面重绘成 A 的数字，
 * 而两边都是真数字，界面上看不出这是串台。
 */
let ws = null;
/** 主动关闭时置位：否则 onclose 里的自动重连会把刚关掉的那条又拉起来 */
let wsClosing = false;

/** 覆盖率变化时由服务端主动推送，避免前端空轮询 */
export function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
  // 项目 id 走查询串，服务端按它过滤会话（见 CoveragePublisher.projectOf）
  const target = proto + location.host + '/ws/coverage?project='
    + encodeURIComponent(store.projectId);
  wsClosing = true;
  if (ws) ws.close();
  wsClosing = false;
  ws = new WebSocket(target);
  const mine = ws;
  ws.onmessage = (ev) => {
    // 换项目时旧连接可能还有一条消息在路上，认准是不是当前这条
    if (mine !== ws) return;
    // 场景视图看的是 stop 时已定格的数据，实时推送与它无关，重渲染只会把它冲掉
    if (store.viewScenario) return;
    // 推送内容是全量口径，增量视图下改为按当前口径重取
    if (store.mode === 'incremental') {
      refresh();
      return;
    }
    applySummary(JSON.parse(ev.data));
    loadGate();
    if (store.current) openFile(store.current);
  };
  ws.onclose = () => {
    if (wsClosing || mine !== ws) return;
    setTimeout(connectWs, 3000);
  };
}

/**
 * 切到另一个项目。
 *
 * 必须把上一个项目的数据全部清掉再取新的：留着的话，新项目还没采到数据的那一瞬间，
 * 页面上显示的是上一个项目的覆盖率和文件列表 —— 而它们看起来完全正常。
 */
export async function setProject(id, name) {
  // 名字可能已经由列表页带过来了（点「进入」那一下就有），别用 id 把它盖掉；
  // 深链接进来时没有名字，先用 id 顶着。
  // 注意判据要用「名字是不是已经有了」，不能用「projectId 变没变」——
  // 列表页是先写 projectName、再改 hash 的，此刻 projectId 还是上一个值，
  // 按后者判恒为 false，刚写进去的名字每次都会被 id 顶掉
  const keep = store.projectName;
  store.projectId = id;
  store.projectName = name || keep || id;
  store.summary = null;
  store.lastGood = null;
  store.file = null;
  store.current = null;
  store.prevStatus = {};
  store.gate = null;
  store.gateError = null;
  store.banner = null;
  store.trend = [];
  store.trendKey = '';
  store.buildTrend = [];
  store.buildTrendErr = null;
  store.perInst = null;
  store.perInstAt = '';
  store.scenarios = [];
  store.activeScenario = null;
  store.viewScenario = '';
  connectWs();
  await loadScenarios().catch(() => { /* 列表取不到不该挡住染色 */ });
  await reload();
}
