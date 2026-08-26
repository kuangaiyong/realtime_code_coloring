import { store, hasData, reload, setMode, collectNow, resetCounters,
         loadScenarios, toggleScenario, loadSummary, openFile, connectWs } from './store.js';
import { Coloring } from './views/coloring.js';
import { Overview } from './views/overview.js';
import { Onboard } from './views/onboard.js';

const { createApp, computed, ref, watchEffect } = Vue;

/**
 * 菜单由这一份路由配置生成，不手写 <li>：加一个视图只在这里加一行，
 * 侧边栏、路由、默认页三处不会各自跑偏。
 */
const ROUTES = [
  { path: 'coloring', name: '代码染色', icon: 'Document', comp: Coloring },
  { path: 'overview', name: '总览看板', icon: 'DataLine', comp: Overview },
  { path: 'onboard', name: '服务接入', icon: 'Connection', comp: Onboard }
];
const DEFAULT_ROUTE = 'coloring';

const App = {
  setup() {
    // ---------- 路由 ----------
    const route = ref(DEFAULT_ROUTE);
    function syncRoute() {
      const p = location.hash.replace(/^#\/?/, '');
      route.value = ROUTES.some(r => r.path === p) ? p : DEFAULT_ROUTE;
    }
    window.addEventListener('hashchange', syncRoute);
    syncRoute();
    // 必须从 setup 里暴露：Vue 模板表达式只认白名单里的全局量（Math、Date 之类），
    // 模板里直接写 location 会求值成 undefined —— 点菜单毫无反应，控制台之外看不出原因
    const navigate = (k) => { location.hash = '#/' + k; };
    const currentView = computed(() =>
      (ROUTES.find(r => r.path === route.value) || ROUTES[0]).comp);

    // ---------- 深色模式 ----------
    // Element Plus 认 <html class="dark">，而本平台的 CSS 全走 --el-* 变量，跟着一起翻。
    // 必须记住选择：每次打开又回到浅色的话，这个开关等于没有
    const dark = ref(false);
    try {
      dark.value = localStorage.getItem('rtcc-theme') === 'dark';
    } catch (e) { /* 隐私模式下读不到，按浅色走 */ }
    watchEffect(() => {
      document.documentElement.classList.toggle('dark', dark.value);
      try {
        localStorage.setItem('rtcc-theme', dark.value ? 'dark' : 'light');
      } catch (e) { /* 存不进去也不影响这一次会话 */ }
    });

    // ---------- 顶栏：探针状态 ----------
    // 探针不可达与平台配置错误是两回事，排查方向完全不同，界面上必须分开说
    const PROBE_TEXT = {
      CONNECTED: (live, total) => '探针已连接 · ' + live + '/' + total + ' 实例 · 3s 轮询',
      PARTIAL: (live, total) => '仅 ' + live + '/' + total + ' 个实例在线 · 覆盖数据不完整',
      ARCHIVED: () => '场景快照 · 数据已定格',
      DISCONNECTED: () => '探针未连接',
      ANALYZE_ERROR: () => '探针正常，分析失败',
      CONFIG_ERROR: () => '平台配置有误'
    };

    const probe = computed(() => {
      const d = store.summary;
      if (!d) return { cls: 'err', text: '探针未连接' };
      const inst = d.instances || [];
      const live = inst.filter(i => i.status === 'CONNECTED').length;
      const fn = PROBE_TEXT[d.probeStatus];
      return {
        cls: d.probeStatus === 'CONNECTED' || d.probeStatus === 'ARCHIVED' ? 'ok'
          : d.probeStatus === 'PARTIAL' ? 'warn' : 'err',
        text: fn ? fn(live, inst.length) : '状态未知'
      };
    });

    const overall = computed(() => {
      const d = store.summary;
      if (!hasData(d)) return '—';
      // 场景视图看的是已定格的归档数据，必须标出来，否则会被当成实时累计覆盖
      const scope = d.scenarioId ? '场景 ' + d.scenarioId + ' · ' : '';
      return d.mode === 'incremental'
        ? scope + '增量行覆盖率 ' + d.overallRatio + '% · 基线 ' + d.baselineCommit.substring(0, 8)
          + ' → 产物 ' + d.buildCommit.substring(0, 8)
        : scope + '整体行覆盖率 ' + d.overallRatio + '%';
    });

    // ---------- 场景 ----------
    const scenarioInput = ref('');
    const running = computed(() => !!store.activeScenario);

    async function onToggleScenario() {
      try {
        await toggleScenario(running.value ? '' : scenarioInput.value);
        scenarioInput.value = '';
      } catch (e) {
        store.banner = { level: 'err', text: e.message };
      }
    }

    async function onSelectScenario(v) {
      store.viewScenario = v;
      await reload();
    }

    async function onReset() {
      try {
        await resetCounters();
      } catch (e) {
        store.banner = { level: 'err', text: e.message };
      }
    }

    async function onCollect() {
      try {
        await collectNow();
      } catch (e) {
        store.banner = { level: 'err', text: e.message };
      }
    }

    // ---------- 首屏 ----------
    // 必须接住：默认项目没装载上时 /api/scenario 回 404，裸调只会留下一条
    // 未处理的 Promise rejection —— 场景下拉框静默空着，界面上没有任何交代
    loadScenarios().catch(e => {
      store.banner = { level: 'err', text: '场景列表取不到：' + e.message };
    });
    loadSummary().then(d => {
      if (d && d.files.length) openFile(d.files[0].path);
    });
    connectWs();

    return {
      ROUTES, route, currentView, navigate, store, probe, overall, dark,
      scenarioInput, running, onToggleScenario, onSelectScenario,
      setMode, onCollect, onReset
    };
  },
  template: `
<div class="layout">
  <aside class="aside">
    <div class="brand"><span class="dot"></span><span class="nm">代码实时染色平台</span></div>
    <el-menu :default-active="route" @select="navigate">
      <el-menu-item v-for="r in ROUTES" :key="r.path" :index="r.path" :data-testid="'nav-' + r.path">
        <el-icon><component :is="r.icon" /></el-icon>
        <span>{{ r.name }}</span>
      </el-menu-item>
    </el-menu>
    <div class="foot">项目 default<br>多项目切换在下一阶段</div>
  </aside>

  <div class="body">
    <header class="topbar">
      <h1>{{ (ROUTES.find(r => r.path === route) || ROUTES[0]).name }}</h1>
      <span class="pill" :class="probe.cls" data-testid="probe-pill"><i></i>{{ probe.text }}</span>
      <span class="overall" data-testid="overall">{{ overall }}</span>
      <div class="spacer"></div>
      <div class="seg">
        <button :class="{ on: store.mode === 'full' }" data-testid="mode-full"
                @click="setMode('full')">全量</button>
        <button :class="{ on: store.mode === 'incremental' }" data-testid="mode-incremental"
                @click="setMode('incremental')">增量</button>
      </div>
      <el-input v-if="store.mode === 'incremental'" v-model="store.baseline" size="small"
                style="width:150px" data-testid="baseline"
                title="增量基线 ref（分支名、tag 或 commit）"
                @change="setMode('incremental')" />
      <el-button size="small" data-testid="btn-collect" @click="onCollect">立即采集</el-button>
      <!-- 场景进行中清零会让归因数据只剩清零之后那一段，服务端会拒绝，这里先把入口关掉 -->
      <el-button size="small" type="primary" data-testid="btn-reset"
                 :disabled="running"
                 :title="running ? '场景进行中不能清零，否则该场景的归因数据会被截断' : ''"
                 @click="onReset">清零计数器</el-button>
      <el-button size="small" circle data-testid="btn-theme"
                 :title="dark ? '切回浅色' : '切到深色'" @click="dark = !dark">
        <el-icon><component :is="dark ? 'Sunny' : 'Moon'" /></el-icon>
      </el-button>
    </header>

    <div class="scenariobar">
      <label>数据源</label>
      <!-- 空串在 el-select 眼里就是「没选」，会落回 placeholder。所以 placeholder 必须
           写成实时口径本身，否则这里显示的是组件默认的英文 Select -->
      <el-select :model-value="store.viewScenario" size="small" style="width:290px"
                 placeholder="实时（累计覆盖）" data-testid="scenario-view" @change="onSelectScenario">
        <el-option value="" label="实时（累计覆盖）" />
        <el-option v-for="s in store.scenarios" :key="s.scenarioId" :value="s.scenarioId"
                   :label="'场景 ' + s.scenarioId + '（' + s.files + ' 文件 / ' + s.overallRatio + '%）'" />
      </el-select>
      <span class="spacer"></span>
      <span v-if="running" class="rec" data-testid="recording"><i></i>场景 {{ store.activeScenario }} 进行中</span>
      <el-input :model-value="running ? store.activeScenario : scenarioInput" size="small"
                style="width:200px" :disabled="running" data-testid="scenario-id"
                placeholder="场景 ID" title="给这一轮测试起个名字，例如 支付成功回归"
                @update:model-value="v => scenarioInput = v" />
      <el-button size="small" data-testid="btn-scenario" @click="onToggleScenario">
        {{ running ? '结束场景' : '开始场景' }}
      </el-button>
    </div>

    <div class="banner" data-testid="banner">
      <div v-if="store.banner" :class="store.banner.level" style="white-space:pre-line">{{ store.banner.text }}</div>
    </div>

    <component :is="currentView" />
  </div>
</div>`
};

const app = createApp(App);
app.use(ElementPlus);
// 图标全部注册成全局组件：路由配置里写的是图标名字符串，<component :is> 要靠它解析
for (const [name, comp] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, comp);
}
app.mount('#app');
