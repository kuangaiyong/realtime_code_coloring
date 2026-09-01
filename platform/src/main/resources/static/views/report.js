import { store, openFile, hasData, projectUrl } from '../store.js';
import { api, pctClass } from '../api.js';

const { computed, ref, watch } = Vue;

/** 一格进度条 + 百分比。比例是「已覆盖 / 总数」，分母为 0 时不画条，写「—」 */
function bar(covered, total) {
  if (covered === null || covered === undefined) return null;
  const pct = total === 0 ? null : Math.round(covered * 1000 / total) / 10;
  return { pct, covered, total, missed: total - covered };
}

/**
 * 覆盖率报表：包 → 源文件 → 方法 三级钻取。
 *
 * <b>为什么与「代码染色」分开：</b>染色回答「这一行测没测」，报表回答
 * 「哪个包 / 哪个文件 / 哪个方法欠测」。后者要的是能一层层收起来的汇总，
 * 前者要的是一屏源码 —— 挤在一个视图里，两边都得让步。
 * 点到方法这一层就跳去染色页，那是它该接手的地方。
 *
 * <b>第二级叫「源文件」而不是「类」</b>：四种语言里三种没有类的概念，
 * 而 fileDetail、git diff、染色页全是按文件路径走的。叫成「类」的话，
 * Java 用户会问内部类为什么不单独一行 —— 而给内部类单独一行，就要回到
 * 那条被明确否掉的 getClasses() 合并路线（见 CoverageAnalyzer 的注释）。
 */
export const Report = {
  setup() {
    const files = computed(() => (store.summary && store.summary.files) || []);
    const ok = computed(() => hasData(store.summary));
    /** 这份数据是不是增量口径。取 summary.mode 而非 store.mode，理由同 coloring.js */
    const incremental = computed(() => !!store.summary && store.summary.mode === 'incremental');

    // ---- 钻取层级 ----
    // 状态放 hash 里（#/p/<id>/report/<包名>/<文件路径>），刷新与转发链接都能回到原处。
    // syncRoute 把第三段起的部分写进 store.routeArg，这里解出来
    const pkg = computed(() => {
      const a = store.routeArg;
      if (!a) return null;
      const slash = a.indexOf('/');
      return decodeURIComponent(slash < 0 ? a : a.substring(0, slash));
    });
    const filePath = computed(() => {
      const a = store.routeArg;
      const slash = a.indexOf('/');
      return slash < 0 ? null : decodeURIComponent(a.substring(slash + 1));
    });

    function go(arg) {
      location.hash = '#/p/' + encodeURIComponent(store.projectId) + '/report'
        + (arg ? '/' + arg : '');
    }
    const openPkg = (p) => go(encodeURIComponent(p));
    const openFileRow = (f) =>
      go(encodeURIComponent(f.packageName) + '/' + encodeURIComponent(f.path));
    const backToPkgs = () => go('');
    const backToFiles = () => go(encodeURIComponent(pkg.value));

    // ---- 第一级：按包聚合 ----
    /**
     * 包级比例必须是 sum(covered) / sum(total)，<b>不是各文件 ratio 求平均</b> ——
     * 后者会让一个 3 行的小文件与一个 800 行的大文件在包的数字里等权，
     * 算出来的百分比谁也对不上。
     *
     * 分支与方法遇到 null 的子项：不进分母；全部子项都是 null 时整格才是 null。
     * 这与 summary 里跨语言汇总的写法一致 —— null 是「不提供」，不是 0。
     */
    const packages = computed(() => {
      const by = new Map();
      for (const f of files.value) {
        const key = f.packageName || '(默认包)';
        let a = by.get(key);
        if (!a) {
          a = { name: key, files: 0, cl: 0, ml: 0, cb: null, mb: null, cm: null, mm: null };
          by.set(key, a);
        }
        a.files++;
        a.cl += f.coveredLines;
        a.ml += f.missedLines;
        if (f.coveredBranches !== null && f.coveredBranches !== undefined) {
          a.cb = (a.cb || 0) + f.coveredBranches;
          a.mb = (a.mb || 0) + f.missedBranches;
        }
        if (f.coveredMethods !== null && f.coveredMethods !== undefined) {
          a.cm = (a.cm || 0) + f.coveredMethods;
          a.mm = (a.mm || 0) + f.missedMethods;
        }
      }
      // 服务端按 path 的码点序给文件，这里也用码点序排包名 —— 用 localeCompare
      // 会得到与服务端不一样的顺序（染色页已经为这个踩过一次）
      return [...by.values()].sort((x, y) => (x.name < y.name ? -1 : x.name > y.name ? 1 : 0));
    });

    // ---- 第二级：某个包下的文件 ----
    const filesInPkg = computed(() =>
      files.value.filter(f => (f.packageName || '(默认包)') === pkg.value));

    // ---- 第三级：某个文件的方法 ----
    const methods = ref(null);
    const methodsErr = ref('');
    const loadingMethods = ref(false);

    /**
     * 方法明细走 fileDetail，与染色页打开文件是同一个接口 ——
     * 口径三件套与竞态守卫都已经在那条路上，不必另开一个。
     */
    let seq = 0;
    async function loadMethods(path) {
      const mine = ++seq;
      loadingMethods.value = true;
      methodsErr.value = '';
      try {
        const q = store.mode === 'incremental'
          ? 'mode=incremental&baseline=' + encodeURIComponent(store.baseline.trim())
          : 'mode=full';
        const d = await api.get(projectUrl('/coverage/file?path=')
          + encodeURIComponent(path) + '&' + q);
        if (mine !== seq) return;
        methods.value = d.methods;
      } catch (e) {
        if (mine !== seq) return;
        methods.value = null;
        methodsErr.value = e.message;
      } finally {
        if (mine === seq) loadingMethods.value = false;
      }
    }

    watch(filePath, (p) => {
      if (p) loadMethods(p); else { methods.value = null; methodsErr.value = ''; }
    }, { immediate: true });
    // 换口径要重取：增量口径下服务端会把方法置 null
    watch(() => store.mode, () => { if (filePath.value) loadMethods(filePath.value); });

    const currentFile = computed(() =>
      files.value.find(f => f.path === filePath.value) || null);

    /**
     * 点方法 → 跳染色页并定位到它的首行。
     *
     * 定位标记只用一次：openFile 每轮 WS 推送都会重跑，若把滚动挂在数据的 watch 上
     * 而不清标记，人正看着代码就会每 3 秒被拽回那一行。
     */
    function toSource(m) {
      store.jumpToLine = m.firstLine;
      openFile(filePath.value);
      location.hash = '#/p/' + encodeURIComponent(store.projectId) + '/coloring';
    }

    return { store, ok, incremental, packages, filesInPkg, pkg, filePath, currentFile,
             methods, methodsErr, loadingMethods, bar, pctClass,
             openPkg, openFileRow, backToPkgs, backToFiles, toSource };
  },
  template: `
<div class="view" data-testid="view-report">
  <div class="card">
    <div class="card-head">
      <h2>覆盖率报表</h2>
      <span class="crumb" data-testid="report-crumb">
        <a @click="backToPkgs" :class="{ on: !pkg }">全部包</a>
        <template v-if="pkg"><i>›</i><a @click="backToFiles" :class="{ on: !filePath }">{{ pkg }}</a></template>
        <template v-if="filePath"><i>›</i><a class="on">{{ currentFile ? currentFile.sourceFileName : filePath }}</a></template>
      </span>
      <span class="sub">{{ store.summary && store.summary.mode === 'incremental' ? '增量口径' : '全量口径' }}</span>
    </div>

    <div v-if="!ok" class="empty">等待采集…</div>

    <!-- 第一级：包 -->
    <div v-else-if="!pkg" class="tbl-wrap">
      <table class="tbl" data-testid="report-table">
        <thead><tr>
          <th>包</th><th>源文件</th><th>行覆盖</th><th>未覆盖行</th><th>分支</th><th>方法</th>
        </tr></thead>
        <tbody>
          <tr v-for="p in packages" :key="p.name" data-testid="pkg-row" :data-pkg="p.name">
            <td><a class="drill" data-testid="pkg-name" @click="openPkg(p.name)">{{ p.name }}</a></td>
            <td class="mono">{{ p.files }}</td>
            <td><span class="pc mono" :class="pctClass(bar(p.cl, p.cl + p.ml).pct || 0)">{{ bar(p.cl, p.cl + p.ml).pct }}%</span>
              <span class="meter"><i :style="{ width: (bar(p.cl, p.cl + p.ml).pct || 0) + '%' }"></i></span></td>
            <td class="mono">{{ p.ml }}</td>
            <!-- 拿不到的指标写「不提供」，不补 0 —— 补 0 会被读成「一个都没测」 -->
            <td class="mono" :class="{ na: p.cb === null }">{{ p.cb === null ? '不提供' : p.cb + '/' + (p.cb + p.mb) }}</td>
            <td class="mono" :class="{ na: p.cm === null }">{{ p.cm === null ? '不提供' : p.cm + '/' + (p.cm + p.mm) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 第二级：包下的源文件 -->
    <div v-else-if="!filePath" class="tbl-wrap">
      <table class="tbl" data-testid="report-table">
        <thead><tr>
          <th>源文件</th><th>行覆盖</th><th>未覆盖行</th><th>分支</th><th>方法</th>
        </tr></thead>
        <tbody>
          <tr v-for="f in filesInPkg" :key="f.path" data-testid="file-row" :data-path="f.path">
            <td><a class="drill" data-testid="file-name" @click="openFileRow(f)">{{ f.sourceFileName }}</a></td>
            <td><span class="pc mono" :class="pctClass(f.ratio)">{{ f.ratio }}%</span>
              <span class="meter"><i :style="{ width: f.ratio + '%' }"></i></span></td>
            <td class="mono">{{ f.missedLines }}</td>
            <td class="mono" :class="{ na: f.coveredBranches === null }">{{ f.coveredBranches === null ? '不提供' : f.coveredBranches + '/' + (f.coveredBranches + f.missedBranches) }}</td>
            <td class="mono" :class="{ na: f.coveredMethods === null }">{{ f.coveredMethods === null ? '不提供' : f.coveredMethods + '/' + (f.coveredMethods + f.missedMethods) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 第三级：文件里的方法 -->
    <template v-else>
      <div v-if="loadingMethods" class="empty">取方法明细中…</div>
      <div v-else-if="methodsErr" class="err">{{ methodsErr }}</div>
      <!-- 增量口径下服务端把方法置 null：一个方法通常只有几行落在 diff 里，
           「这个方法覆盖了没有」答不上来。这与「这门语言不提供」是两回事，
           所以文案必须分开写 -->
      <div v-else-if="incremental && methods === null" class="empty" data-testid="methods-na">
        增量口径下答不上来：一个方法通常只有几行落在这次改动里，
        「这个方法覆盖了没有」没有意义。切到全量口径再看。
      </div>
      <div v-else-if="methods === null" class="empty" data-testid="methods-na">
        这门语言暂不提供方法明细。
      </div>
      <div v-else-if="!methods.length" class="empty">这个文件里没有方法。</div>
      <div v-else class="tbl-wrap">
        <table class="tbl" data-testid="report-table">
          <thead><tr><th>方法</th><th>行号</th><th>行覆盖</th><th>分支</th></tr></thead>
          <tbody>
            <tr v-for="m in methods" :key="m.name + ':' + m.firstLine"
                data-testid="method-row" :data-name="m.name">
              <td><a class="drill" data-testid="method-name" @click="toSource(m)">{{ m.name }}</a></td>
              <td class="mono">L{{ m.firstLine }}</td>
              <td class="mono">{{ m.coveredLines }}/{{ m.coveredLines + m.missedLines }}</td>
              <td class="mono" :class="{ na: m.coveredBranches === null }">{{ m.coveredBranches === null ? '不提供' : m.coveredBranches + '/' + (m.coveredBranches + m.missedBranches) }}</td>
            </tr>
          </tbody>
        </table>
        <!-- 方法的行数不能相加：JaCoCo 按类算，同一行可同属外部类与写在该行的匿名类，
             各方法之和会大于文件行数。所以这里不放小计，只说明一句 -->
        <div class="legend" style="border-bottom:none;border-top:1px solid var(--el-border-color-lighter)">
          方法的行数<strong>不可相加</strong>：同一行可能同时属于外部类与写在该行上的匿名类，
          逐方法相加会大于文件的行数。文件级数字以上一层为准。
        </div>
      </div>
    </template>
  </div>
</div>`
};
