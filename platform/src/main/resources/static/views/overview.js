import { store, loadBuildTrend, hasData, projectUrl } from '../store.js';
import { api } from '../api.js';

const { computed, ref, onMounted, watch } = Vue;

/**
 * 把一串 0–100 的数值算成折线的坐标。
 * 纵轴钉死 0–100%，不按数据范围缩放 —— 自动缩放会把 0.2% 的抖动画成断崖。
 */
function svgPoints(values) {
  const W = 600, H = 100;
  const n = values.length;
  const pts = values.map((r, i) => [(n === 1 ? 0 : i / (n - 1)) * W, H - r / 100 * H]);
  const line = pts.map(([x, y]) => x.toFixed(1) + ',' + y.toFixed(1)).join(' ');
  return {
    W, H, line,
    area: pts[0][0].toFixed(1) + ',' + H + ' ' + line + ' ' + pts[n - 1][0].toFixed(1) + ',' + H,
    cx: pts[n - 1][0].toFixed(1), cy: pts[n - 1][1].toFixed(1)
  };
}

/**
 * 横轴两端的刻度文字。
 *
 * 带不带日期由<b>整个区间</b>决定，不是每个刻度各判各的 —— 一端写「08/18 21:48」、
 * 另一端写「07:27」，读起来像是两种量纲。区间跨天就两端都带日期，当天内就都只写时分。
 */
function axisLabels(from, to) {
  const a = new Date(from), b = new Date(to);
  if (isNaN(a.getTime()) || isNaN(b.getTime())) return { from: '', to: '' };
  const hm = (d) => d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  if (a.toDateString() === b.toDateString()) {
    return { from: hm(a), to: hm(b) };
  }
  const md = (d) => d.toLocaleDateString([], { month: '2-digit', day: '2-digit' }) + ' ' + hm(d);
  return { from: md(a), to: md(b) };
}

/** 事件带取多少条、显示多少条。取多了每次进页面白拉，显示多了就成了第二个事件页 */
const EVENT_LIMIT = 200;
const EVENT_SHOWN = 3;

/** 与采集事件页同一套说法。两页对同一个状态给出不同的词，人会以为是两回事 */
const STATUS_TEXT = {
  PARTIAL: '部分实例掉线',
  DISCONNECTED: '探针不可达',
  ANALYZE_ERROR: '分析失败',
  CONFIG_ERROR: '配置有误',
  UNKNOWN: '尚未采集'
};

function statusText(s) {
  return STATUS_TEXT[s] || s;
}

/**
 * 总览看板：三指标水位 + 覆盖率变化。
 *
 * 门禁结论在「覆盖门禁」页、实例状态在「服务接入」页、文件明细在「代码染色」页 ——
 * 同一件事原先在两处各写一遍，改起来必然有一处跟不上。这一页只回答一个问题：
 * <b>这个项目整体测得怎么样</b>。
 */
export const Overview = {
  setup() {
    const d = computed(() => store.summary);
    const files = computed(() => (d.value && d.value.files) || []);
    // 看板的数字随口径变，不标出来会被当成全量：增量下的「未覆盖 12 行」
    // 和全量下的「未覆盖 12 行」是完全不同的两件事
    const scope = computed(() => {
      const s = d.value;
      if (!s) return '—';
      return s.mode === 'incremental' ? '增量口径' : '全量口径';
    });

    /** 语言显示名。分支要按语言分行，得把 java/cpp 这种键换成人读的写法 */
    const LANG_NAME = { java: 'Java', cpp: 'C++', go: 'Go', rust: 'Rust' };

    /** 这个项目里有没有这门语言的文件。没有就不必解释它为什么没有分支 */
    function hasLang(k) {
      const ext = { go: '.go', rust: '.rs', java: '.java', cpp: '.cpp' }[k];
      return files.value.some(f => f.path.endsWith(ext));
    }

    /**
     * 分支水位：<b>按语言分行，不给跨语言总数</b>。
     *
     * 实测 C++ 一个 demo 有 239 条分支（已滤掉编译器为异常路径生成的），
     * 而同规模的 Java 只有 28 条 —— C++ 里每个可能抛异常的操作都会生成分支，
     * 分母差一个数量级。汇总出来的百分比等于在报告 C++ 的异常处理路径覆盖率，
     * 与「我的 if 测到了吗」没有关系。同一语言内部纵向可比，这才是分支覆盖率的用法。
     */
    const branchRows = computed(() => {
      // 必须与 lineStat / methodStat 一样先判 hasData：实例全部掉线时服务端不更新
      // 快照，branchesByLanguage 仍是上一份的值 —— 不判的话同一屏里「行覆盖」显示
      // 「—」而「分支覆盖」照常给出百分比，人会以为只有行覆盖出了问题
      if (!hasData(d.value)) return [];
      const by = d.value.branchesByLanguage || {};
      const rows = Object.keys(by).map(k => {
        const c = by[k].covered, m = by[k].missed, t = c + m;
        return {
          lang: LANG_NAME[k] || k,
          pct: t === 0 ? '—' : (Math.round(c * 1000 / t) / 10) + '%',
          detail: c + '/' + t
        };
      });
      // 拿不到分支的语言不在 branchesByLanguage 里，但页面上必须说出来 ——
      // 整格不提它们，会被读成「这几种语言的分支全没测」
      const absent = ['go', 'rust'].filter(k => !(k in by) && hasLang(k));
      if (absent.length) {
        rows.push({ lang: absent.map(k => LANG_NAME[k]).join(' · '), pct: '不提供', detail: '', muted: true });
      }
      return rows;
    });

    /**
     * 方法水位。与分支不同，这个跨语言汇总 ——「一个函数」的口径四种语言大致一致。
     *
     * <b>增量口径下要说「不适用」而不是「—」</b>：服务端裁剪时把方法置 null
     * （一个方法通常只有几行落在 diff 里，「这个方法覆盖了没有」答不上来），
     * 而「—」在本页的既定含义是「探针够不到」，会把人指去查探针。
     */
    const methodStat = computed(() => {
      const s = d.value;
      if (!hasData(s)) return { v: '—', sub: '' };
      if (s.mode === 'incremental') {
        return { v: '不适用', sub: '增量口径下答不上来', muted: true };
      }
      if (s.coveredMethods === null || s.coveredMethods === undefined) {
        return { v: '—', sub: '' };
      }
      const t = s.coveredMethods + s.missedMethods;
      return {
        v: s.coveredMethods + ' / ' + t,
        sub: t === 0 ? '' : (Math.round(s.coveredMethods * 1000 / t) / 10) + '%'
      };
    });

    const lineStat = computed(() => {
      const s = d.value;
      // 探针够不到时顶栏显示「—」，这几个数字也必须一起变成「—」
      const ok = hasData(s);
      const covered = files.value.reduce((a, f) => a + f.coveredLines, 0);
      const missed = files.value.reduce((a, f) => a + f.missedLines, 0);
      return {
        v: ok ? s.overallRatio : '—',
        unit: ok ? '%' : '',
        sub: ok ? covered + ' / ' + (covered + missed) + ' 行' : ''
      };
    });

    const collectedAt = computed(() => d.value && d.value.lastCollectedAt
      ? '最后采集 ' + new Date(d.value.lastCollectedAt).toLocaleTimeString() : '');

    // ---- 曲线 ----
    const buildNote = '每个构建一个点，取该构建观测到的峰值';
    const sessionNote = '本次会话内的变化，非跨构建历史';

    const trendView = computed(() => {
      if (store.trendScope === 'build') {
        // 历史取不到时必须说明原因：回一张空图会被读成「这个项目一直没有覆盖」，
        // 而真实原因可能只是数据库没起
        if (store.buildTrendErr) {
          return { err: '覆盖率历史不可用：' + store.buildTrendErr };
        }
        const b = store.buildTrend;
        if (b.length < 2) {
          return { empty: (b.length ? '只有 1 个构建的记录，还连不成趋势' : '尚无构建记录')
            + '（' + buildNote + '）' };
        }
        const first = b[0], last = b[b.length - 1];
        const delta = Math.round((last.overallRatio - first.overallRatio) * 10) / 10;
        return {
          svg: svgPoints(b.map(x => x.overallRatio)),
          note: buildNote,
          // 横轴上只有 commit 短 sha 的话，看不出「这是上周的还是今天的」——
          // 而跨构建趋势的用处恰恰是回答「最近在变好还是变差」
          xAxis: { ...axisLabels(first.peakAt, last.peakAt), mid: b.length + ' 个构建' },
          meta: b.length + ' 个构建 · ' + first.buildCommit.substring(0, 8)
            + ' → ' + last.buildCommit.substring(0, 8)
            + '（' + (delta >= 0 ? '+' : '') + delta + '）'
        };
      }
      const t = store.trend;
      if (t.length < 2) {
        return { empty: '采样中…（' + sessionNote + '）', meta: t.length ? '已采 1 点' : '' };
      }
      const first = t[0], last = t[t.length - 1];
      const delta = Math.round((last.r - first.r) * 10) / 10;
      const secs = Math.round((last.t - first.t) / 1000);
      return {
        svg: svgPoints(t.map(p => p.r)),
        note: sessionNote,
        xAxis: { ...axisLabels(first.t, last.t), mid: t.length + ' 个采样点' },
        meta: scope.value + ' · ' + t.length + ' 点 / ' + secs + 's · '
          + first.r + '% → ' + last.r + '%（' + (delta >= 0 ? '+' : '') + delta + '）'
      };
    });

    // ---- 曲线下方的事件带 ----
    //
    // 这一页原先只有「测得怎么样」，答不出「那个坑是怎么回事」——
    // 而覆盖率掉下去的那一段，人第一个想知道的就是当时发生了什么。
    //
    // <b>不在曲线上打点</b>：横轴是「第几个点」而不是时间（svgPoints 按索引均分），
    // 两次构建间隔 1 小时和 3 天在图上一样宽。按时间比例标记会标到视觉上错误的位置，
    // 而图看着是对的 —— 正是本项目最忌讳的那种错。所以只在下方列出区间内的事件。
    const events = ref([]);
    const eventsErr = ref(null);

    /**
     * 事件只在状态<b>变化</b>时产生，跟着 3 秒轮询刷没有意义，还每轮多拉 200 条。
     * 进这一页时取一次，切口径时不用重取（区间变了，但事件集合没变）。
     */
    async function loadEvents() {
      try {
        const r = await api.get(projectUrl('/events?limit=' + EVENT_LIMIT));
        events.value = r.available ? r.events : [];
        eventsErr.value = r.available ? null : r.error;
      } catch (e) {
        events.value = [];
        eventsErr.value = e.message;
      }
    }
    onMounted(loadEvents);
    // 换项目要重取 —— 不换的话，这一页会把上一个项目的事件挂在新项目的曲线下面
    watch(() => store.projectId, loadEvents);

    /**
     * 当前曲线覆盖的时间区间。<b>两种口径的区间完全不同</b>，不跟着口径走的话，
     * 会话口径下（几分钟）会列出几周前的事件，读的人以为那个坑就是它造成的。
     */
    const trendRange = computed(() => {
      if (store.trendScope === 'build') {
        const b = store.buildTrend;
        if (b.length < 2) return null;
        return { from: new Date(b[0].peakAt).getTime(),
                 to: new Date(b[b.length - 1].peakAt).getTime() };
      }
      const t = store.trend;
      if (t.length < 2) return null;
      return { from: t[0].t, to: t[t.length - 1].t };
    });

    /** 落在区间内的异常事件，新的在前。CONNECTED 不列 —— 曲线掉下去时人要看的是异常 */
    const rangeEvents = computed(() => {
      const r = trendRange.value;
      if (!r) return [];
      return events.value.filter(e => {
        if (e.status === 'CONNECTED') return false;
        const at = new Date(e.at).getTime();
        return at >= r.from && at <= r.to;
      });
    });

    /**
     * 事件带的一句话。
     *
     * <b>「可能不全」必须说出来</b>：只取最近 EVENT_LIMIT 条，而跨构建区间可能横跨几周 ——
     * 区间内更早的事件已经不在这批里了。不说的话，人会把「列出来的」当成「全部的」，
     * 又是一个看着完整、其实不全的静默错误。
     */
    const eventBand = computed(() => {
      const r = trendRange.value;
      if (!r || eventsErr.value) return null;
      const list = rangeEvents.value;
      // 取回来的最后一条（最旧）仍晚于区间起点，说明区间更早的部分没被这批覆盖到
      const oldest = events.value.length ? new Date(events.value[events.value.length - 1].at).getTime() : null;
      const truncated = events.value.length >= EVENT_LIMIT && oldest !== null && oldest > r.from;
      return { list: list.slice(0, EVENT_SHOWN), total: list.length, truncated };
    });

    async function setTrendScope(next) {
      store.trendScope = next;
      // 跨构建数据来自历史表，不随 3 秒轮询变，切过去时取一次即可
      if (next === 'build') await loadBuildTrend();
    }

    /** 点事件带跳采集事件页。那一页有完整列表与筛选，这里只做入口 */
    function toEvents() {
      location.hash = '#/p/' + encodeURIComponent(store.projectId) + '/events';
    }

    return {
      store, files, scope, lineStat, branchRows, methodStat,
      collectedAt, trendView, setTrendScope,
      eventBand, eventsErr, toEvents, statusText, EVENT_LIMIT
    };
  },
  template: `
<div class="view" data-testid="view-overview">
  <div class="stats">
    <div class="stat">
      <div class="k">行覆盖 · {{ scope }}</div>
      <div class="v" data-testid="stat-lines">{{ lineStat.v }}<small v-if="lineStat.unit">{{ lineStat.unit }}</small></div>
      <div class="sub-line">{{ lineStat.sub }}</div>
    </div>
    <div class="stat">
      <!-- 分支按语言分行给。两种语言的分支百分比不能相互比较，原因写在悬停里 -->
      <div class="k">分支覆盖 <span class="hint"
        title="C++ 的分支由 gcov 给出，包含编译器为可能抛异常的操作生成的路径，分母天然比 Java 大。不要拿两种语言的分支百分比相互比较。">?</span></div>
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
      <!-- 「不适用」弱化显示：它不是一个差的数字，是这个口径下问不出这个问题 -->
      <div class="v" :class="{ muted: methodStat.muted }" data-testid="stat-methods">{{ methodStat.v }}</div>
      <div class="sub-line">{{ methodStat.sub }}</div>
    </div>
    <div class="stat">
      <div class="k">源文件</div>
      <div class="v">{{ files.length }}<small> 个</small></div>
      <div class="sub-line">{{ collectedAt }}</div>
    </div>
  </div>

  <div class="card">
    <div class="card-head">
      <h2>覆盖率变化</h2>
      <div class="seg">
        <button :class="{ on: store.trendScope === 'session' }"
                data-testid="trend-session" @click="setTrendScope('session')">本次会话</button>
        <button :class="{ on: store.trendScope === 'build' }"
                data-testid="trend-build" @click="setTrendScope('build')">跨构建</button>
      </div>
      <span class="sub">{{ trendView.meta || '' }}</span>
    </div>
    <div data-testid="trend-box">
      <div v-if="trendView.err" class="err">{{ trendView.err }}</div>
      <div v-else-if="trendView.empty" class="empty">{{ trendView.empty }}</div>
      <div v-else class="trend">
        <svg :viewBox="'0 0 ' + trendView.svg.W + ' ' + trendView.svg.H"
             preserveAspectRatio="none" aria-label="覆盖率变化曲线">
          <line class="grid" x1="0" y1="0" :x2="trendView.svg.W" y2="0"></line>
          <line class="grid" x1="0" :y1="trendView.svg.H / 2" :x2="trendView.svg.W" :y2="trendView.svg.H / 2"></line>
          <line class="grid" x1="0" :y1="trendView.svg.H" :x2="trendView.svg.W" :y2="trendView.svg.H"></line>
          <polygon class="area" :points="trendView.svg.area"></polygon>
          <polyline class="line" :points="trendView.svg.line"></polyline>
          <circle class="dot" :cx="trendView.svg.cx" :cy="trendView.svg.cy" r="3"></circle>
        </svg>
        <div class="trend-ax">
          <span>0%</span><span>纵轴固定 0–100% · {{ trendView.note }}</span><span>100%</span>
        </div>
        <div v-if="trendView.xAxis" class="trend-ax" data-testid="trend-xaxis">
          <span>{{ trendView.xAxis.from }}</span>
          <span>{{ trendView.xAxis.mid }}</span>
          <span>{{ trendView.xAxis.to }}</span>
        </div>

        <!-- 曲线掉下去的那一段，人第一个想知道的是当时发生了什么。
             <b>不在曲线上打点</b>：横轴是「第几个点」不是时间，按时间比例标会标错位置，
             而图看着是对的 —— 只在下方列出区间内的事件 -->
        <div v-if="eventBand" class="ev-band" data-testid="trend-events">
          <template v-if="eventBand.total">
            <div class="hd">
              <span>这段区间内 <b>{{ eventBand.total }}</b> 次异常</span>
              <!-- 只取了最近若干条，跨构建区间可能横跨几周 —— 不说的话，
                   人会把「列出来的」当成「全部的」 -->
              <span v-if="eventBand.truncated" class="cut" data-testid="trend-events-cut">
                仅统计最近 {{ EVENT_LIMIT }} 条事件，区间更早的部分未计入
              </span>
              <span class="grow"></span>
              <a href="#" data-testid="trend-events-all" @click.prevent="toEvents">查看全部 →</a>
            </div>
            <div v-for="(e, i) in eventBand.list" :key="e.at + i" class="row"
                 data-testid="trend-event-row" @click="toEvents">
              <span class="t mono">{{ new Date(e.at).toLocaleString() }}</span>
              <span class="s">{{ statusText(e.status) }}</span>
              <span class="i mono">{{ (e.instances || []).join('、') || '—' }}</span>
            </div>
          </template>
          <div v-else class="hd">
            <span>这段区间内没有异常</span>
            <span class="grow"></span>
            <a href="#" data-testid="trend-events-all" @click.prevent="toEvents">采集事件 →</a>
          </div>
        </div>
      </div>
    </div>
  </div>

</div>`
};
