import { store, hasData, reload, setMode, collectNow, resetCounters,
         setProject } from './store.js';
import { Coloring } from './views/coloring.js';
import { Overview } from './views/overview.js';
import { Onboard } from './views/onboard.js';
import { Projects } from './views/projects.js';
import { Wizard } from './views/wizard.js';
import { Settings } from './views/settings.js';
import { Events } from './views/events.js';
import { Gate } from './views/gate.js';
import { Report } from './views/report.js';
import { Help } from './views/help.js';

const { createApp, computed, ref, watchEffect } = Vue;

/**
 * 项目内的视图。菜单由这一份配置生成，不手写 <li>：加一个视图只在这里加一行，
 * 侧边栏、路由、默认页三处不会各自跑偏。
 */
const ROUTES = [
  // 顺序按「先看什么」排：代码染色是这个平台的核心价值，总览是同一份数据的汇总，
  // 门禁是基于它们的判断，最后才是接入 / 诊断 / 配置这些不常用的。
  // scoped 标记「这一页会显示覆盖数字」——全量/增量口径与清零只在它们上面出现
  { path: 'coloring', name: '代码染色', icon: 'Document', comp: Coloring, scoped: true },
  { path: 'overview', name: '总览看板', icon: 'DataLine', comp: Overview, scoped: true },
  { path: 'gate', name: '覆盖门禁', icon: 'CircleCheck', comp: Gate, scoped: true },
  { path: 'report', name: '覆盖率报表', icon: 'Histogram', comp: Report, scoped: true },
  { path: 'onboard', name: '服务接入', icon: 'Connection', comp: Onboard },
  // 接入页搬出来的说明。不 scoped —— 它一个覆盖数字都不显示，口径栏不该出现在这里
  { path: 'help', name: '接入帮助', icon: 'QuestionFilled', comp: Help },
  { path: 'events', name: '采集事件', icon: 'Warning', comp: Events },
  { path: 'settings', name: '项目设置', icon: 'Setting', comp: Settings }
];
const DEFAULT_ROUTE = 'coloring';

const App = {
  setup() {
    // ---------- 路由 ----------
    // 两级：#/projects 是项目列表（首页），#/p/<id>/<view> 是某个项目内的视图。
    // 项目 id 落在路径里，所以刷新、加书签、把链接发给同事都能回到同一个地方 ——
    // 存在内存里的话，同事打开你发的链接看到的是他自己上次那个项目
    const route = ref(DEFAULT_ROUTE);
    const inProject = ref(false);
    const isNew = ref(false);
    /**
     * 数据已经装载过的项目 id。
     *
     * 判据必须是「这个项目的数据装过没有」，不能是「id 变没变」——
     * store.projectId 一开始就是 'default'，于是从列表进入 default 时 id 没变，
     * 装载被静默跳过：页面停在「探针未连接 / 0 个文件」，而项目其实是好的。
     */
    let loadedProject = null;

    function syncRoute() {
      const hash = location.hash.replace(/^#\/?/, '');
      isNew.value = hash === 'new';
      const m = hash.match(/^p\/([^/]+)(?:\/(.*))?$/);
      if (!m) {
        inProject.value = false;
        return;
      }
      isNew.value = false;
      const id = decodeURIComponent(m[1]);
      // hash 的第三段起是<b>视图自己的参数</b>（如报表钻到哪个包 / 哪个文件）。
      // 原先这里拿整串去与 ROUTES 全等比较，于是 #/p/id/report/com.foo 这种多级地址
      // 会被静默判成非法、无声跳回染色页 —— 不报错，只是「点了没反应」
      const rest = m[2] || '';
      const slash = rest.indexOf('/');
      const view = (slash < 0 ? rest : rest.substring(0, slash)) || DEFAULT_ROUTE;
      const known = ROUTES.some(r => r.path === view);
      inProject.value = true;
      route.value = known ? view : DEFAULT_ROUTE;
      // 视图不认识时参数也要清掉：留着的话，回落到染色页后那个参数还挂在 store 上，
      // 下次进报表会莫名其妙停在一个不相干的包上
      store.routeArg = known && slash >= 0 ? rest.substring(slash + 1) : '';
      // 换项目要整批换数据，不能只改 id：见 store.setProject
      if (loadedProject !== id) {
        loadedProject = id;
        setProject(id);
      }
    }
    window.addEventListener('hashchange', syncRoute);
    // 必须从 setup 里暴露：Vue 模板表达式只认白名单里的全局量（Math、Date 之类），
    // 模板里直接写 location 会求值成 undefined —— 点菜单毫无反应，控制台之外看不出原因
    const navigate = (k) => {
      location.hash = '#/p/' + encodeURIComponent(store.projectId) + '/' + k;
    };
    const toList = () => { location.hash = '#/projects'; };
    // 同 navigate：模板表达式里写 location 会求值成 undefined，必须从这里暴露出去
    const toNew = () => { location.hash = '#/new'; };
    // 列表里点「配置」直接进那个项目的设置页 —— 阶段 2 那套「改完即生效」
    // 在页面上唯一的入口
    const openSettings = (r) => {
      store.projectName = r.name;
      location.hash = '#/p/' + encodeURIComponent(r.id) + '/settings';
    };
    const openProject = (r) => {
      store.projectName = r.name;
      location.hash = '#/p/' + encodeURIComponent(r.id) + '/' + DEFAULT_ROUTE;
    };
    const currentRoute = computed(() => ROUTES.find(r => r.path === route.value) || ROUTES[0]);
    const currentView = computed(() => inProject.value
      ? currentRoute.value.comp
      : (isNew.value ? Wizard : Projects));
    /**
     * 口径栏（全量 / 增量、基线、采集与清零）只在<b>会显示覆盖数字</b>的视图上出现。
     *
     * 原先它挂在每个视图上，但在「项目设置」「采集事件」「服务接入」这三页，
     * 换口径不改变页面上的任何东西 —— 摆着只会让人以为它对这一页有影响。
     */
    const scoped = computed(() => inProject.value && currentRoute.value.scoped === true);

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
      return d.mode === 'incremental'
        ? '增量行覆盖率 ' + d.overallRatio + '% · 基线 ' + d.baselineCommit.substring(0, 8)
          + ' → 产物 ' + d.buildCommit.substring(0, 8)
        : '整体行覆盖率 ' + d.overallRatio + '%';
    });

    // 场景只能经 API 开始 / 结束（页面上不再有入口），但进行中这件事必须显示出来 ——
    // 它会让清零和保存配置被服务端回 409，不显示的话那两处看起来就是「点了没反应」
    const running = computed(() => !!store.activeScenario);

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
    // 先认路由：直接打开 #/p/xxx/coloring（书签、同事发来的链接）时，
    // 要进的是那个项目而不是默认项目
    // 直接打开 #/p/xxx/coloring（书签、同事发来的链接）时要进那个项目，
    // 而装载由 syncRoute 统一负责 —— 它认的是 loadedProject，不会重复装
    syncRoute();

    return {
      ROUTES, route, inProject, isNew, currentView, currentRoute, scoped,
      navigate, toList, toNew, openSettings, openProject,
      store, probe, overall, dark,
      running,
      setMode, onCollect, onReset
    };
  },
  template: `
<div class="layout">
  <aside class="aside">
    <div class="brand"><span class="dot"></span><span class="nm">代码实时染色平台</span></div>
    <!-- 一级只有项目列表；进了项目才换成项目内菜单。两套菜单不并存，
         否则「概览」到底是平台的还是这个项目的，说不清 -->
    <el-menu v-if="!inProject" default-active="projects">
      <el-menu-item index="projects" data-testid="nav-projects">
        <el-icon><component is="Folder" /></el-icon><span>项目管理</span>
      </el-menu-item>
    </el-menu>
    <template v-else>
      <div class="backlink" data-testid="nav-back" @click="toList">
        <el-icon><component is="ArrowLeft" /></el-icon><span>全部项目</span>
      </div>
      <div class="proj-name" :title="store.projectId">{{ store.projectName || store.projectId }}</div>
      <el-menu :default-active="route" @select="navigate">
        <el-menu-item v-for="r in ROUTES" :key="r.path" :index="r.path" :data-testid="'nav-' + r.path">
          <el-icon><component :is="r.icon" /></el-icon>
          <span>{{ r.name }}</span>
        </el-menu-item>
      </el-menu>
    </template>
    <div class="grow"></div>
    <div class="foot">项目 {{ inProject ? store.projectId : '—' }}</div>
  </aside>

  <div class="body">
    <header class="topbar">
      <h1>{{ inProject ? currentRoute.name : (isNew ? '新建项目' : '项目管理') }}</h1>
      <!-- 口径、采集、清零都是「对某个项目」的动作，列表页上没有落点 -->
      <template v-if="inProject">
      <span class="pill" :class="probe.cls" data-testid="probe-pill"><i></i>{{ probe.text }}</span>
      <span class="overall" data-testid="overall">{{ overall }}</span>
      <!-- 「正在录制」跟着 inProject 而不是 scoped：录制期间清零会被拒、改配置会被拒，
           而后者是在「项目设置」页上做的 —— 只挂在显示覆盖数字的那几页，
           它就恰好在最需要它的那一页不显示 -->
      <span v-if="running" class="pill err rec" data-testid="recording"><i></i>场景 {{ store.activeScenario }} 进行中</span>
      </template>
      <div class="spacer"></div>
      <template v-if="scoped">
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
      </template>
      <el-button size="small" circle data-testid="btn-theme"
                 :title="dark ? '切回浅色' : '切到深色'" @click="dark = !dark">
        <el-icon><component :is="dark ? 'Sunny' : 'Moon'" /></el-icon>
      </el-button>
    </header>

    <div class="banner" data-testid="banner">
      <div v-if="store.banner" :class="store.banner.level" style="white-space:pre-line">{{ store.banner.text }}</div>
    </div>

    <!-- projectId 显式传下去：设置页要按 id 取配置，靠组件自己去读 store
         会在切项目的那一瞬间读到上一个项目的 id -->
    <component :is="currentView" :project-id="store.projectId"
               @open="openProject"
               @create="toNew"
               @edit="openSettings"
               @cancel="toList"
               @back="toList"
               @saved="toList"
               @done="openProject" />
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
