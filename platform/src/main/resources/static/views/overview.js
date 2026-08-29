import { store, loadBuildTrend, loadPerInstance, openFile, hasData } from '../store.js';
import { pctClass, LANG } from '../api.js';

const { computed } = Vue;

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

/** CSV 单元格。逗号、引号、换行都必须裹起来，否则一个包名里的逗号就把列错开了 */
function cell(v) {
  const t = String(v == null ? '' : v);
  return /[",\r\n]/.test(t) ? '"' + t.replace(/"/g, '""') + '"' : t;
}

/** 文件名里的时间戳。用本地时间：导出的人是按自己的钟去找那份文件的 */
function stamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' + p(d.getHours()) + p(d.getMinutes());
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

/** 总览看板：统计口径 → 门禁结论 → 覆盖率变化 → 被测实例 → 覆盖率排行 */
export const Overview = {
  setup() {
    const d = computed(() => store.summary);
    const files = computed(() => (d.value && d.value.files) || []);
    const inst = computed(() => (d.value && d.value.instances) || []);

    // 看板的数字随口径变，不标出来会被当成全量：增量下的「未覆盖 12 行」
    // 和全量下的「未覆盖 12 行」是完全不同的两件事
    const scope = computed(() => {
      const s = d.value;
      if (!s) return '—';
      return s.scenarioId ? '场景 ' + s.scenarioId
        : s.mode === 'incremental' ? '增量口径' : '全量口径';
    });

    /**
     * 门禁结论跟着覆盖率那个数字走。
     *
     * 看的人要的是「这个数字够不够」，而不是先记住 3.6%、再滚到下面一张卡里找阈值。
     * 三态照旧分开：判不了是黄的，与「不通过」的红分开 —— 前者该找人看平台，
     * 后者该补测试。
     */
    const gateNote = computed(() => {
      // 场景快照是过去某一轮的独占覆盖，本就不参与门禁，这里不能挂一句结论上去
      if (store.viewScenario) return null;
      if (store.gateError) return { text: '门禁判不了', level: 'w' };
      const g = store.gate;
      if (!g) return null;
      return g.passed
        ? { text: '门槛 ' + g.threshold + '% 已达标', level: 's' }
        : { text: '门槛 ' + g.threshold + '% 未达标', level: 'd' };
    });

    const stats = computed(() => {
      const s = d.value;
      // 探针够不到时顶栏显示「—」，看板这几个数字也必须一起变成「—」
      const ok = hasData(s);
      const covered = files.value.reduce((a, f) => a + f.coveredLines, 0);
      const missed = files.value.reduce((a, f) => a + f.missedLines, 0);
      const live = inst.value.filter(i => i.status === 'CONNECTED').length;
      return [
        { k: '统计口径', v: scope.value },
        { k: '行覆盖率', v: ok ? s.overallRatio : '—', unit: ok ? '%' : '', note: gateNote.value },
        { k: '已覆盖行', v: ok ? covered : '—' },
        { k: '未覆盖行', v: ok ? missed : '—' },
        { k: '源文件', v: files.value.length, unit: ' 个' },
        { k: '在线实例', v: inst.value.length ? live : '—', unit: inst.value.length ? '/' + inst.value.length : '' }
      ];
    });

    const collectedAt = computed(() => d.value && d.value.lastCollectedAt
      ? '最后采集 ' + new Date(d.value.lastCollectedAt).toLocaleTimeString() : '');

    // ---- 门禁 ----
    /** 详情在独立视图里。模板表达式里写不了 location，得从这儿暴露 */
    function toGate() {
      location.hash = '#/p/' + encodeURIComponent(store.projectId) + '/gate';
    }

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

    async function setTrendScope(next) {
      store.trendScope = next;
      // 跨构建数据来自历史表，不随 3 秒轮询变，切过去时取一次即可
      if (next === 'build') await loadBuildTrend();
    }

    // ---- 被测实例 ----
    const instEmpty = computed(() =>
      // 场景快照是定格数据，服务端不返回实例列表。写成「无实例」会被读成全部掉线
      d.value && d.value.probeStatus === 'ARCHIVED'
        ? '场景快照不含实例信息（数据已定格）' : '尚无实例');

    /** 各实例分别的覆盖：按 endpoint 索引，没加载过就是 null（表格少三列） */
    const perInstMap = computed(() =>
      store.perInst ? new Map(store.perInst.map(r => [r.endpoint, r])) : null);

    function langOf(endpoint) {
      const l = String(endpoint).split('://')[0];
      return LANG[l] || l;
    }

    function perInstOf(endpoint) {
      const m = perInstMap.value;
      if (!m) return null;
      const r = m.get(endpoint);
      return r && r.overallRatio !== null && r.overallRatio !== undefined ? r : undefined;
    }

    // ---- 排行 ----
    // 「覆盖率最低」与「未覆盖行最多」不是一回事：一个 0% 的小文件和一个 60% 却缺 500 行的
    // 大文件，该先补哪个取决于问的是哪个问题。所以两种排序都留着，而不是替用户选一个
    const ranked = computed(() => files.value.slice().sort(store.rankBy === 'missed'
      ? (a, b) => b.missedLines - a.missedLines || a.ratio - b.ratio
      : (a, b) => a.ratio - b.ratio || b.missedLines - a.missedLines));

    /**
     * 导出当前排行为 CSV。覆盖率报告要给人看、要贴进周报，光在页面上看不够。
     *
     * <b>口径必须写进文件名</b>：增量口径下的 47 行未覆盖和全量口径下的 47 行
     * 是完全不同的两件事，而导出的表格里看不出区别 —— 隔几天再打开就成了一份
     * 说不清是什么的数字。
     */
    function exportCsv() {
      const head = ['#', '文件', '包名', '路径', '行覆盖率(%)', '已覆盖行', '未覆盖行'];
      const rows = ranked.value.map((f, n) =>
        [n + 1, f.sourceFileName, f.packageName, f.path, f.ratio, f.coveredLines, f.missedLines]);
      const csv = [head, ...rows].map(r => r.map(cell).join(',')).join('\r\n');
      // BOM 不能省：没有它 Excel 会按本地代码页解，中文列名直接是乱码
      const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = '覆盖率-' + scope.value.replace(/\s/g, '') + '-' + stamp() + '.csv';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      // 不撤销的话这块内存要挂到页面关掉为止；导几次就是几份文件的量
      setTimeout(() => URL.revokeObjectURL(url), 0);
    }

    /** 排行的用处是「找到该补的文件、然后去看它」，所以点文件名直接跳进染色视图 */
    function jumpTo(path) {
      // 路由是两级的：只写 #/coloring 匹配不上 #/p/<id>/<view>，
      // 结果不是跳去染色而是退回项目列表
      location.hash = '#/p/' + encodeURIComponent(store.projectId) + '/coloring';
      openFile(path);
    }

    return {
      store, stats, collectedAt, trendView, setTrendScope,
      inst, instEmpty, perInstMap, perInstOf, langOf, loadPerInstance, toGate,
      ranked, jumpTo, pctClass, exportCsv
    };
  },
  template: `
<div class="view" data-testid="view-overview">
  <div class="stats">
    <div class="stat" v-for="s in stats" :key="s.k">
      <div class="k">{{ s.k }}</div>
      <div class="v">{{ s.v }}<small v-if="s.unit">{{ s.unit }}</small></div>
      <div v-if="s.note" class="note-line" :class="s.note.level" data-testid="stat-gate">{{ s.note.text }}</div>
    </div>
  </div>

  <!-- 门禁在这里只留一句结论：判定详情、增量与全量的对比、CI 接入命令都在
       「覆盖门禁」那一页。同一件事在两处各写一遍，改起来必然有一处跟不上 -->
  <div class="card">
    <div class="card-head">
      <h2>覆盖率门禁</h2>
      <el-button size="small" data-testid="btn-gate-detail" @click="toGate">查看详情</el-button>
    </div>
    <div v-if="store.viewScenario" class="empty">场景快照不参与门禁判定</div>
    <div v-else-if="store.gateError" class="gate undecided" data-testid="gate">
      <span class="verdict" data-testid="gate-verdict">无法判定</span>
      <span class="why">{{ store.gateError }}</span>
    </div>
    <div v-else-if="store.gate" class="gate" :class="store.gate.passed ? 'pass' : 'block'"
         data-testid="gate">
      <span class="verdict" data-testid="gate-verdict">{{ store.gate.passed ? '通过' : '不通过' }}</span>
      <span class="why">{{ store.gate.reason }}</span>
      <span class="num" data-testid="gate-actual">
        <template v-if="store.gate.actual === null">—</template>
        <template v-else>{{ store.gate.actual }}<small>%</small></template>
      </span>
    </div>
    <div v-else class="empty">等待判定…</div>
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
      </div>
    </div>
  </div>

  <div class="card">
    <div class="card-head">
      <h2>被测实例</h2>
      <el-button size="small" :loading="store.perInstLoading" data-testid="btn-per-inst"
                 title="对每个实例各跑一次归一化，开销与实例数成正比，所以不随轮询自动做"
                 @click="loadPerInstance">
        {{ store.perInst ? '重新加载各实例覆盖' : '加载各实例覆盖' }}
      </el-button>
      <span class="sub">{{ collectedAt }}</span>
    </div>
    <div v-if="!inst.length" class="empty">{{ instEmpty }}</div>
    <template v-else>
      <div class="tbl-wrap">
        <table class="tbl" data-testid="inst-table">
          <thead><tr>
            <th>探针地址</th><th>语言</th><th>状态</th><th>构建版本</th>
            <template v-if="perInstMap"><th>该实例行覆盖率</th><th>已覆盖</th><th>未覆盖</th></template>
            <th>说明</th>
          </tr></thead>
          <tbody>
            <tr v-for="i in inst" :key="i.endpoint" data-testid="inst-row" :data-endpoint="i.endpoint">
              <td class="mono">{{ i.endpoint }}</td>
              <td>{{ langOf(i.endpoint) }}</td>
              <td><span class="tag" :class="i.status === 'CONNECTED' ? 'ok' : 'err'">{{ i.status }}</span></td>
              <!-- dirty 单独标出来：版本不一致的横幅只说「有实例对不上」，
                   不点名的话得去翻各实例的启动参数才知道是哪一台脏了 -->
              <td class="mono">
                {{ i.buildCommit ? i.buildCommit.substring(0, 8) : '—' }}
                <span v-if="i.dirty" class="tag warn"
                      title="该实例构建时被测源码有未提交改动，增量口径不可用">dirty</span>
              </td>
              <template v-if="perInstMap">
                <template v-if="perInstOf(i.endpoint)">
                  <td><span class="pc mono" :class="pctClass(perInstOf(i.endpoint).overallRatio)">{{ perInstOf(i.endpoint).overallRatio }}%</span></td>
                  <td class="mono">{{ perInstOf(i.endpoint).coveredLines }}</td>
                  <td class="mono">{{ perInstOf(i.endpoint).missedLines }}</td>
                </template>
                <template v-else><td class="mono">—</td><td class="mono">—</td><td class="mono">—</td></template>
              </template>
              <td><span v-if="i.error" style="color:var(--el-color-danger)">{{ i.error }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="perInstMap" class="legend"
           style="border-bottom:none;border-top:1px solid var(--el-border-color-lighter)">
        各实例覆盖取自 {{ store.perInstAt }} 的定格值，不随轮询刷新；这几列<strong>不能相加当聚合用</strong>
        —— 两台各覆盖同一行的不同分支时，按实例算是 PARTIAL、合并算才是 COVERED。聚合值以顶栏为准。
      </div>
    </template>
  </div>

  <div class="card">
    <div class="card-head">
      <h2>覆盖率排行</h2>
      <el-button size="small" :disabled="!ranked.length" data-testid="btn-export"
                 title="导出当前口径下的全部文件；口径写在文件名里" @click="exportCsv">导出 CSV</el-button>
      <div class="seg" style="margin-left:auto">
        <button :class="{ on: store.rankBy === 'ratio' }"
                data-testid="rank-ratio" @click="store.rankBy = 'ratio'">覆盖率最低</button>
        <button :class="{ on: store.rankBy === 'missed' }"
                data-testid="rank-missed" @click="store.rankBy = 'missed'">未覆盖行最多</button>
      </div>
    </div>
    <div v-if="!ranked.length" class="empty">尚无数据</div>
    <div v-else class="tbl-wrap">
      <table class="tbl" data-testid="rank-table">
        <thead><tr>
          <th style="width:34px">#</th><th>文件</th><th style="width:170px">行覆盖率</th>
          <th style="width:84px">已覆盖</th><th style="width:84px">未覆盖</th>
        </tr></thead>
        <tbody>
          <tr v-for="(f, n) in ranked" :key="f.path">
            <td class="mono">{{ n + 1 }}</td>
            <td>
              <span class="rank-name" :title="f.path" @click="jumpTo(f.path)">{{ f.sourceFileName }}</span>
              <div style="font-size:12px;color:var(--el-text-color-secondary)">{{ f.packageName }}</div>
            </td>
            <td>
              <div style="display:flex;align-items:center;gap:8px">
                <div class="bar-track"><div class="bar-fill" :class="pctClass(f.ratio)" :style="{ width: f.ratio + '%' }"></div></div>
                <span class="pc mono" :class="pctClass(f.ratio)">{{ f.ratio }}%</span>
              </div>
            </td>
            <td class="mono">{{ f.coveredLines }}</td>
            <td class="mono">{{ f.missedLines }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</div>`
};
