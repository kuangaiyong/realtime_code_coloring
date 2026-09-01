import { store, openFile, hasData } from '../store.js';
import { pctClass } from '../api.js';

const { computed, ref } = Vue;

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

/** 代码染色：左边文件列表，右边逐行染色的源码 */
export const Coloring = {
  setup() {
    const all = computed(() => (store.summary && store.summary.files) || []);

    // demo 只有 9 个文件，感觉不出必要性；真接工程时这里是几百上千个类，
    // 没有过滤就只能靠滚动条找
    const keyword = ref('');
    const files = computed(() => {
      const k = keyword.value.trim().toLowerCase();
      if (!k) return all.value;
      return all.value.filter(f =>
        f.sourceFileName.toLowerCase().includes(k)
        || String(f.packageName || '').toLowerCase().includes(k)
        || f.path.toLowerCase().includes(k));
    });

    const emptyHint = computed(() => {
      if (!store.summary) return '等待采集…';
      // 过滤为空与「本来就没有」是两回事：不分开的话，搜错一个字会被读成「这个口径下没有代码」
      if (keyword.value.trim() && all.value.length) return '没有匹配「' + keyword.value.trim() + '」的文件';
      // 文件列表来自产物分析，跑没跑过都在里面，所以空列表只可能是增量口径把范围筛空了，
      // 与看的是实时还是某个场景无关
      return store.summary.mode === 'incremental' ? '基线之后没有变更的可执行代码' : '尚无数据';
    });

    const ratioText = computed(() => {
      const f = store.file;
      if (!f || !f.found) return '';
      return f.ratio + '% · 已覆盖 ' + f.coveredLines + ' 行 / 未覆盖 ' + f.missedLines + ' 行';
    });

    /**
     * 「这个文件是这次新写的，还是在已有文件上改的」。
     *
     * 值得标出来，是因为两者该看的东西不同：新增文件整份都是这次的责任，
     * 一片红说明这个类根本没被测到；而修改文件里的红只是这次改动的那几行没测，
     * 文件其余部分的覆盖情况与这次无关 —— 不区分的话，一屏红里看不出哪些是真该补的。
     */
    const CHANGE = { ADDED: '新增', MODIFIED: '修改' };

    /**
     * 排序。「覆盖率最低」与「未覆盖行最多」不是一回事：一个 0% 的小文件和一个 60%
     * 却缺 500 行的大文件，该先补哪个取决于问的是哪个问题，所以两种都留着。
     *
     * 默认按路径 —— 这是文件列表原本的顺序（服务端就是按 path 排的），
     * 换成别的会让「我刚才看的那个文件在哪」变成每次都要重新找。
     */
    const sorted = computed(() => {
      const list = files.value.slice();
      if (store.rankBy === 'ratio') {
        return list.sort((a, b) => a.ratio - b.ratio || b.missedLines - a.missedLines);
      }
      if (store.rankBy === 'missed') {
        return list.sort((a, b) => b.missedLines - a.missedLines || a.ratio - b.ratio);
      }
      return list.sort((a, b) => a.path.localeCompare(b.path));
    });

    /**
     * 一个文件的分支与方法。<b>null 表示这门语言不提供，必须与 0 分开</b> ——
     * Go 拿不到这两项，显示成 0/0 会被读成「一个都没测」，而那里压根没有这个概念。
     * 判定一律用 == null，不用真值判断：0 和 null 在 JS 里都是 falsy，含义却相反。
     */
    function metricsOf(f) {
      return {
        br: f.coveredBranches == null ? null : f.coveredBranches + '/' + (f.coveredBranches + f.missedBranches),
        me: f.coveredMethods == null ? null : f.coveredMethods + '/' + (f.coveredMethods + f.missedMethods)
      };
    }

    /**
     * 导出当前口径下的全部文件。覆盖率报告要给人看、要贴进周报，光在页面上看不够。
     *
     * <b>口径必须写进文件名</b>：增量口径下的 47 行未覆盖和全量口径下的 47 行是完全
     * 不同的两件事，而表格里看不出区别 —— 隔几天再打开就成了一份说不清是什么的数字。
     * 拿不到的指标导成空单元格而不是 0：写 0 会让读表的人以为一个都没测。
     */
    function exportCsv() {
      const head = ['#', '文件', '包名', '路径', '行覆盖率(%)', '已覆盖行', '未覆盖行', '分支', '方法'];
      const rows = sorted.value.map((f, n) => {
        const m = metricsOf(f);
        return [n + 1, f.sourceFileName, f.packageName, f.path, f.ratio,
                f.coveredLines, f.missedLines, m.br || '', m.me || ''];
      });
      const csv = [head, ...rows].map(r => r.map(cell).join(',')).join('\r\n');
      // BOM 不能省：没有它 Excel 会按本地代码页解，中文列名直接是乱码
      const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = '覆盖率-' + (store.mode === 'incremental' ? '增量口径' : '全量口径')
        + '-' + stamp() + '.csv';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      // 不撤销的话这块内存要挂到页面关掉为止；导几次就是几份文件的量
      setTimeout(() => URL.revokeObjectURL(url), 0);
    }

    return { store, all, files, sorted, keyword, emptyHint, ratioText, openFile,
             pctClass, hasData, CHANGE, metricsOf, exportCsv };
  },
  template: `
<div class="view coloring" data-testid="view-coloring">
  <div class="card">
    <div class="card-head">
      <h2>源文件</h2>
      <span class="sub" data-testid="file-count">{{ files.length === all.length ? files.length + ' 个'
        : files.length + ' / ' + all.length + ' 个' }}</span>
      <el-select v-model="store.rankBy" size="small" style="width:96px" data-testid="rank-by">
        <el-option label="按路径" value="path" />
        <el-option label="覆盖率低" value="ratio" />
        <el-option label="未覆盖多" value="missed" />
      </el-select>
      <el-button size="small" data-testid="btn-export"
                 title="导出当前口径下的全部文件；口径写在文件名里" @click="exportCsv">导出</el-button>
    </div>
    <div class="filter">
      <el-input v-model="keyword" size="small" clearable placeholder="按文件名 / 包名 / 路径过滤"
                data-testid="file-filter" />
    </div>
    <div data-testid="file-list">
      <div v-if="!files.length" class="empty">{{ emptyHint }}</div>
      <!-- 两行式：左栏是固定 300px，一行放不下三组数。第一行是找文件用的
           （名字 + 那个百分比），第二行才是三组明细 -->
      <button v-for="f in sorted" :key="f.path"
              class="file" :class="{ on: f.path === store.current }"
              data-testid="file-item" :data-path="f.path"
              @click="openFile(f.path)">
        <span class="row1">
          <span class="nm" :title="f.path">{{ f.sourceFileName }}</span>
          <!-- 只有增量口径才有「新增 / 修改」可言，全量列的是产物里的全部文件；
               服务端也只在增量口径下给 changeType，这里跟着它走而不是自己判 -->
          <span v-if="f.changeType" class="ct" :class="f.changeType.toLowerCase()"
                data-testid="change-type"
                :title="f.changeType === 'ADDED' ? '基线里没有这个文件，整份都是这次新写的'
                        : '基线里已有，这次只改了其中一部分行'">{{ CHANGE[f.changeType] }}</span>
          <span class="pc" :class="pctClass(f.ratio)">{{ f.ratio }}%</span>
        </span>
        <span class="row2" data-testid="file-metrics">
          <span>行 {{ f.coveredLines }}/{{ f.coveredLines + f.missedLines }}</span>
          <!-- 「不提供」必须写出来。留空或补 0 会被读成「一个都没测」，
               而这门语言压根没有这个指标 -->
          <span :class="{ na: !metricsOf(f).br }">支 {{ metricsOf(f).br || '不提供' }}</span>
          <span :class="{ na: !metricsOf(f).me }">法 {{ metricsOf(f).me || '不提供' }}</span>
        </span>
      </button>
    </div>
  </div>

  <div class="card">
    <div class="card-head">
      <h2 data-testid="current-path">{{ store.current || '未选择文件' }}</h2>
      <span class="sub" data-testid="current-ratio">{{ ratioText }}</span>
    </div>
    <div class="legend">
      <span><i style="background:var(--el-color-success)"></i>已覆盖</span>
      <span><i style="background:var(--el-color-warning)"></i>部分分支</span>
      <span><i style="background:var(--el-color-danger)"></i>未覆盖</span>
      <span><i style="background:var(--el-border-color)"></i>非可执行行</span>
      <span style="margin-left:auto">探针为布尔型，只记录是否执行，不记录执行次数</span>
    </div>
    <div class="src" data-testid="source">
      <div v-if="!store.file" class="empty">在左侧选择一个文件</div>
      <div v-else-if="!store.file.found" class="err">{{ store.file.error || '未找到该文件' }}</div>
      <template v-else>
        <div v-for="r in store.file.rows" :key="r.line"
             class="ln"
             :class="r.inDiff === false ? 'out' : [r.status, { flash: r.justCovered }]"
             :data-testid="'line-' + r.line" :data-status="r.inDiff === false ? 'OUT' : r.status">
          <span class="no">{{ r.line }}</span><span class="tx">{{ r.text }}</span>
        </div>
      </template>
    </div>
  </div>
</div>`
};
