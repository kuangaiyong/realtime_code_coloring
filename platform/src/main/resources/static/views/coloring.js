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

    return { store, all, files, keyword, emptyHint, ratioText, openFile, pctClass, hasData };
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
