import { store, openFile, hasData } from '../store.js';
import { pctClass } from '../api.js';

const { computed, ref } = Vue;

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

    return { store, all, files, keyword, emptyHint, ratioText, openFile, pctClass, hasData, CHANGE };
  },
  template: `
<div class="view coloring" data-testid="view-coloring">
  <div class="card">
    <div class="card-head">
      <h2>源文件</h2>
      <span class="sub" data-testid="file-count">{{ files.length === all.length ? files.length + ' 个'
        : files.length + ' / ' + all.length + ' 个' }}</span>
    </div>
    <div class="filter">
      <el-input v-model="keyword" size="small" clearable placeholder="按文件名 / 包名 / 路径过滤"
                data-testid="file-filter" />
    </div>
    <div data-testid="file-list">
      <div v-if="!files.length" class="empty">{{ emptyHint }}</div>
      <button v-for="f in files" :key="f.path"
              class="file" :class="{ on: f.path === store.current }"
              data-testid="file-item" :data-path="f.path"
              @click="openFile(f.path)">
        <span class="nm" :title="f.path">{{ f.sourceFileName }}</span>
        <!-- 只有增量口径才有「新增 / 修改」可言，全量列的是产物里的全部文件；
             服务端也只在增量口径下给 changeType，这里跟着它走而不是自己判 -->
        <span v-if="f.changeType" class="ct" :class="f.changeType.toLowerCase()"
              data-testid="change-type"
              :title="f.changeType === 'ADDED' ? '基线里没有这个文件，整份都是这次新写的'
                      : '基线里已有，这次只改了其中一部分行'">{{ CHANGE[f.changeType] }}</span>
        <span class="pc" :class="pctClass(f.ratio)">{{ f.ratio }}%</span>
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
