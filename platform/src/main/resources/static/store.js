import { api } from './api.js';
import { langOf, tokenizeLines } from './syntax.js';

const { reactive, markRaw } = Vue;

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
  /**
   * 当前视图在 hash 里带的路径参数（#/p/<id>/<view>/<这一段>）。
   *
   * 由 syncRoute 写、视图自己读。<b>不从 app.js 当 prop 往下传</b>：
   * &lt;component :is&gt; 是所有视图共用的一处绑定，没声明这个 prop 的视图
   * 会把它落成根元素上的一个 attr —— 那是道无声的脏，DOM 里才看得见。
   */
  routeArg: '',

  // ---- 口径 ----
  mode: 'full',
  baseline: 'HEAD~1',

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
  /**
   * 报表点方法跳过来时要定位的行号，<b>用一次就清</b>。
   *
   * 不清的话每 3 秒一次的 WS 推送都会重跑 openFile，人正看着代码就被拽回那一行。
   */
  jumpToLine: null,
  /**
   * 服务接入页算好、要带进「项目设置」表单的配置值，<b>同样用一次就清</b>。
   *
   * 与 jumpToLine 是同一个手法、同一个理由：不清的话，此后每次进设置页都会被这份
   * 陈旧的值覆盖，而人不会知道自己刚改的值为什么又变回去了 —— 比不预填更糟。
   *
   * 只带<b>已填</b>的项：空值带过去会把设置页里本来有的值清掉。
   */
  pendingConfig: null,
  current: null,
  file: null,
  /** 上一次的逐行状态，用来让"刚刚变绿"这件事可见 */
  prevStatus: {},

  // ---- 看板 ----
  /**
   * 染色页文件列表的排序。默认按路径 —— 那是服务端给的顺序，也是人找文件时的心智模型；
   * 默认按覆盖率排的话，「我刚才看的那个文件在哪」每次都要重新找一遍。
   */
  rankBy: 'path',
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
  /** 正在录制的场景 id。页面上没有录制入口，但经 API 开着的场景要显示出来 */
  activeScenario: null,

  // ---- 采集心跳 ----
  /**
   * 看到平台的 lastCollectedAt 换成新值的那一刻（本地时间，ms），顶栏据此显示「上次采集 N 秒前」。
   * 记本地时间、不直接拿服务端时间相减：浏览器与平台的时钟未必对得上，差几秒就会显示成「-3 秒前」
   */
  lastCollectSeen: 0
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
  return store.mode === 'incremental'
    ? 'mode=incremental&baseline=' + encodeURIComponent(store.baseline.trim())
    : 'mode=full';
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

/** 上一次看到的 lastCollectedAt 原值：值变了才算「平台又采了一轮」 */
let beatValue = null;

function noteCollect(at) {
  if (!at || at === beatValue) return;
  // 第一次看到时不能当成「刚刚」：页面打开时平台可能已经采集失败了好一阵，lastCollectedAt 停在十分钟前，
  // 记成此刻就会在探针报红的同时写着「上次采集 刚刚」。这一次按服务端时间算（时钟有偏差也只错到下一轮采集），
  // 之后每次换新值都按本地时间记
  const first = beatValue === null;
  beatValue = at;
  const t = Date.parse(at);
  store.lastCollectSeen = first && t ? Math.min(Date.now(), t) : Date.now();
}

/** 把一份新的 summary 装进 store，并做那些"不是渲染"的副作用 */
function applySummary(d) {
  store.summary = d;
  noteCollect(d.lastCollectedAt);
  if ((d.instances || []).length) {
    store.lastGood = d;
  }

  // 口径一换，纵轴的含义就变了：把增量的 12% 和全量的 30% 画进同一条线是骗人的，
  // 所以换口径就把采样清掉重开
  if (d.mode !== store.trendKey) {
    store.trendKey = d.mode;
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

/**
 * 分词结果缓存。每次推送都会重取当前文件的 rows，但同一个构建里源码不变 ——
 * 不缓存就是每 3 秒把整份文件重新分词一遍。键里带上源码长度和哈希：
 * 换了构建、文件变了，键自然就变了。只缓存高亮；染色（status）每次都用接口给的新值。
 */
const tokenCache = new Map();
const TOKEN_CACHE_MAX = 20;

function hashOf(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return h;
}

function tokensFor(path, rows) {
  const text = rows.map(r => r.text).join('\n');
  const key = path + '\u0000' + text.length + '\u0000' + hashOf(text);
  let lines = tokenCache.get(key);
  if (lines) {
    // 最近用过的挪到最后：淘汰时从最前面删
    tokenCache.delete(key);
  } else {
    lines = tokenizeLines(text, langOf(path));
  }
  tokenCache.set(key, lines);
  if (tokenCache.size > TOKEN_CACHE_MAX) tokenCache.delete(tokenCache.keys().next().value);
  return lines;
}

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
    const tokens = tokensFor(path, d.rows);
    d.rows.forEach((r, i) => {
      // markRaw：token 只读，不需要 Vue 给几千个小对象挨个套响应式代理
      r.tokens = markRaw(tokens[i] || (r.text ? [{ type: '', text: r.text }] : []));
    });
    for (const r of d.rows) {
      // 上一次是未覆盖、这次变成已覆盖 → 高亮闪一下，让「变绿」这件事可见
      r.justCovered = store.prevStatus[r.line] === 'MISSED' && r.status === 'COVERED';
      next[r.line] = r.status;
    }
  }
  store.file = d;
  store.prevStatus = next;
  if (d.found) {
    try {
      localStorage.setItem(lastFileKey(), path);
    } catch (e) { /* 隐私模式下存不进去，只是下次不记得，不影响这一次 */ }
  }
}

/** 每个项目各记各的「上次看的文件」：两个项目的路径集合互不相交，混记就等于没记 */
function lastFileKey() {
  return 'rtcc-last-file:' + store.projectId;
}

/**
 * 换口径、进项目时选哪个文件：仍在范围内的当前文件 → 这个项目上次看的 → 第一个有覆盖的 → 第一个。
 *
 * 「第一个有覆盖的」排在「第一个」前面：按路径排第一的常是一个 0% 的文件（C++ 的 main.cpp），
 * 一进来迎面一整屏红，读起来像「什么都没测到」，而别的文件明明已经绿了一片
 */
function pickFile(files) {
  if (files.some(f => f.path === store.current)) return store.current;
  let last = null;
  try {
    last = localStorage.getItem(lastFileKey());
  } catch (e) { /* 读不到就当没记过 */ }
  if (last && files.some(f => f.path === last)) return last;
  const covered = files.find(f => f.coveredLines > 0);
  return covered ? covered.path : (files.length ? files[0].path : null);
}

/** 换口径后文件范围会变，原先选中的文件可能已不在范围内 */
export async function reload() {
  store.prevStatus = {};
  const d = await loadSummary();
  // 视图不可用时保留 current，恢复后仍回到用户原先看的那个文件
  if (!d) return;
  const path = pickFile(d.files);
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

/**
 * 有没有场景正在录制。
 *
 * 页面上不提供开始 / 结束场景的入口（场景归因是给 CI 与脚本用的，经 API 做），
 * 但「正在录制」必须显示：录制期间清零与保存配置都会被服务端回 409，
 * 不显示的话那两处看起来就是点了没反应。
 */
export async function loadActiveScenario() {
  const d = await api.get(url('/scenario'));
  store.activeScenario = d.active;
  return d;
}

/**
 * 定时跟一下场景状态。<b>这是页面上唯一会跟住它的地方</b>，撤掉录制入口之后没有别的。
 *
 * <b>为什么不搭 WebSocket 的车：</b>推送是「覆盖率<b>变化</b>时才推」。
 * start 会清零计数器（有变化，推得到），而 stop 不改变任何覆盖数字 ——
 * 没人调接口时就一条推送都不会来，页面于是永远停在「进行中」：红点解不开，
 * 而清零按钮绑着 :disabled="running"，永久点不动，tooltip 还写着
 * 「场景进行中不能清零」，只有整页刷新才恢复，而人不会想到去刷新。
 *
 * 代价是一个 3 秒的轮询。它只读服务端内存里的一个字段，与门禁那种
 * 「每次要起三个 git 子进程」不是一回事，所以这里可以轮询而门禁不行。
 *
 * <b>采集心跳搭同一个定时器</b>，理由同上：推送只在覆盖率变化时才来，没人调接口时
 * 拿「上次收到推送」当「上次采集」，数字会一直涨，把「平台在采、只是没变化」说成「平台卡住了」。
 * 取的是项目列表里这个项目的 lastCollectedAt —— 列表页本来就每 5 秒轮询这个接口，
 * 服务端只读内存里的几个标量。
 */
let followTimer = null;
function followProject() {
  // 换项目时必须先清掉：两个定时器同时写 store.activeScenario，
  // 页面会在两个项目的场景状态之间来回跳
  if (followTimer) clearInterval(followTimer);
  followTimer = setInterval(() => {
    loadActiveScenario().catch(() => { /* 取不到不该在页面上刷错误，下一轮再说 */ });
    loadBeat().catch(() => { /* 同上：取不到时数字照常往上涨，本身就是信号 */ });
  }, 3000);
}

async function loadBeat() {
  const d = await api.get('/api/projects');
  const p = (d.projects || []).find(x => x.id === store.projectId);
  if (p) noteCollect(p.lastCollectedAt);
}

export async function collectNow() {
  const d = await api.post(url('/collect'));
  // /collect 返回的是实时全量快照，增量视图下不能直接渲染
  if (store.mode === 'incremental') {
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
  store.activeScenario = null;
  // 心跳也要归零：留着上一个项目的时间，新项目会先显示成「刚刚采过」
  beatValue = null;
  store.lastCollectSeen = 0;
  connectWs();
  followProject();
  await loadActiveScenario().catch(() => { /* 取不到不该挡住染色 */ });
  await reload();
}
