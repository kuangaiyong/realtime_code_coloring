import { api, LANG } from '../api.js';
import { BaselineField } from '../components/baseline-field.js';

const { computed, reactive, ref, onMounted } = Vue;

/**
 * 新建项目向导。
 *
 * <b>为什么是分步而不是一张大表单：</b>这套配置有二十来项，而且与语言强相关 ——
 * 选了 C++ 才需要 cpp-objects-dir。一次铺开会让人面对一堆不知道该不该填的字段。
 *
 * <b>为什么每一步都当场验：</b>这套配置错一项就采不到数据，而且错得静默 ——
 * 探针连不上顶多状态变红，但 classes-dir 指错目录只会让覆盖率莫名其妙偏低，
 * 界面上看不出是配置问题。向导是唯一能在「建项目」这个时点把这类错误挡下来的地方。
 *
 * 校验一律交给后端的 /api/projects/check：它拿这份配置去碰真实环境，
 * 逐项回答能不能跑起来。前端自己再实现一套判断，迟早会与后端跑偏，
 * 那时候的表现是「向导说没问题，建出来却没数据」。
 */

/** 与后端 ProjectRegistry.validate 的 ID_PATTERN 一致。不一致的话，向导放行、创建报 400 */
const ID_RE = /^[a-z0-9][a-z0-9_-]{0,63}$/;

/** 「还没验过这一步」的哨兵。next() 靠它决定要不要替用户先验一次 */
const NOT_CHECKED = '请先点「检查这一步」';

const STEPS = [
  { key: 'basic', title: '基本信息' },
  { key: 'repo', title: '代码仓库' },
  { key: 'instances', title: '被测实例' },
  { key: 'paths', title: '语言与产物' },
  { key: 'gate', title: '覆盖门禁' },
  { key: 'check', title: '完成自检' }
];

/** 各语言的默认探针端口，与 CLAUDE.md 里约定的一致 */
const PORTS = { java: 6300, go: 6400, cpp: 6500, rust: 6600 };

/** 第 4 步按第 3 步选到的语言动态显示。字段名与后端检查项的 name 一一对应 */
const PATH_FIELDS = {
  java: [
    ['classesDir', 'Java 产物目录', '被测服务的 .class 所在目录，缺了它解不出任何行号'],
    ['javaSourceRoot', 'Java 源码根', '用于渲染染色视图']
  ],
  go: [
    ['goSourceRoot', 'Go 源码根', ''],
    ['goModulePath', 'Go 模块路径', 'go.mod 里的 module 路径，覆盖数据的文件名以它为前缀']
  ],
  cpp: [
    ['cppSourceRoot', 'C++ 源码根', '平台侧的 gcov 要在这个目录下才找得到源码'],
    ['cppObjectsDir', 'C++ 对象目录', '.gcno 所在目录，相当于 Java 的 classes-dir']
  ],
  rust: [
    ['rustSourceRoot', 'Rust 源码根', ''],
    ['rustBinary', 'Rust 产物', '指向产物本身 —— 行号信息在它自带的 coverage mapping 里']
  ]
};

export const Wizard = {
  components: { BaselineField },
  emits: ['done', 'cancel'],
  setup(props, { emit }) {
    const step = ref(0);
    const busy = ref(false);
    /** 后端回的检查项，按 name 索引 */
    const items = ref({});
    const takenIds = ref([]);

    const draft = reactive({
      id: '', name: '', repoDir: '..', baseline: 'HEAD~1',
      classesDir: '', javaSourceRoot: '',
      goSourceRoot: '', goModulePath: '',
      cppSourceRoot: '', cppObjectsDir: '',
      rustSourceRoot: '', rustBinary: '',
      // 字段名必须与后端 ProjectConfig.Gate 一致：Spring 默认不报未知字段，
      // 拼错的话用户填的阈值会被静默丢弃，而向导这边还煞有介事地校验了 0~100
      gate: { incrementalThreshold: 80, overallThreshold: 0 },
      timeoutMs: 3000
    });
    const rows = reactive([{ lang: 'java', host: '127.0.0.1', port: 6300 }]);

    onMounted(async () => {
      try {
        const d = await api.get('/api/projects');
        takenIds.value = d.projects.map(p => p.id);
      } catch (e) { /* 取不到就只做格式校验，创建时后端还会再拦一次 */ }
    });

    const instances = computed(() =>
      rows.filter(r => r.host && r.port).map(r => r.lang + '://' + r.host + ':' + r.port));

    /** 第 3 步选到的语言集合，决定第 4 步显示哪些字段 */
    const languages = computed(() => [...new Set(rows.map(r => r.lang))]);
    const pathFields = computed(() =>
      languages.value.flatMap(l => (PATH_FIELDS[l] || []).map(f => [l, ...f])));

    function payload(extra) {
      return Object.assign({}, draft, { instances: instances.value }, extra || {});
    }

    /**
     * 打一次后端检查，把结果按 name 收进 items。
     *
     * 各步只送这一步需要的字段：第 2 步带上实例的话，要等它挨个去连探针（连不上时
     * 每个耗尽超时），而这一步问的只是「仓库路径对不对」。
     */
    async function check(body) {
      busy.value = true;
      try {
        const d = await api.post('/api/projects/check', body);
        const m = {};
        for (const it of d.items) m[it.name] = it;
        items.value = m;
        return d;
      } catch (e) {
        ElementPlus.ElMessage.error('检查失败：' + e.message);
        return null;
      } finally {
        busy.value = false;
      }
    }

    // ---- 各步的「当场验」----
    const basicError = computed(() => {
      if (!draft.name.trim()) return '项目名不能为空';
      if (!ID_RE.test(draft.id)) {
        return '标识只能用小写字母、数字、下划线和横杠，且以字母或数字开头';
      }
      if (takenIds.value.includes(draft.id)) return '标识 ' + draft.id + ' 已被占用';
      return null;
    });

    const gateError = computed(() => {
      const g = draft.gate;
      for (const [k, label] of [['incrementalThreshold', '增量阈值'], ['overallThreshold', '全量阈值']]) {
        const v = Number(g[k]);
        if (!(v >= 0 && v <= 100)) return label + '必须在 0~100 之间';
      }
      return null;
    });

    /** 这一步能不能往下走。返回 null 表示可以，否则是拦住的理由 */
    function blocker() {
      const k = STEPS[step.value].key;
      if (k === 'basic') return basicError.value;
      if (k === 'repo') return failed(['repoDir', 'baseline']);
      if (k === 'instances') {
        if (!instances.value.length) return '至少要有一个被测实例';
        return failed(['instances', 'versions', ...Object.keys(items.value).filter(n => n.startsWith('instance:'))]);
      }
      if (k === 'paths') return failed(pathFields.value.map(f => f[1]));
      if (k === 'gate') return gateError.value;
      return null;
    }

    /**
     * 这一组检查项里有没有不过的。
     *
     * <b>「没查过」的判据是一项都没有，而不是「缺了哪一项」</b>：后端只在相关时才给出
     * 某些项 —— 单实例没有「实例间版本一致」这一项，仓库路径本身就错时也不会再去验基线。
     * 按「缺任何一项」判会永远卡在「请先点检查」上，而人根本看不出该点什么。
     */
    function failed(names) {
      const present = names.filter(n => items.value[n]);
      if (!present.length) return NOT_CHECKED;
      const bad = present.filter(n => !items.value[n].ok);
      return bad.length ? bad.map(n => items.value[n].label + '：' + items.value[n].detail).join('；') : null;
    }

    async function checkCurrent() {
      const k = STEPS[step.value].key;
      // 换一步就把上一步的结果丢掉：自检表（第 6 步）列的是 items 的全部内容，
      // 混进上一步的陈旧项会让人对着一条早已不成立的失败发愁
      items.value = {};
      if (k === 'repo') {
        // 不带实例：这一步问的只是仓库路径，带上就要等它挨个去连探针
        await check({ repoDir: draft.repoDir, baseline: draft.baseline, instances: [], timeoutMs: draft.timeoutMs });
      } else if (k === 'instances' || k === 'paths' || k === 'check') {
        await check(payload());
      }
    }

    async function next() {
      const k = STEPS[step.value].key;
      // 需要后端确认的几步，每次点「下一步」都重验一遍，而不是「没验过才验」。
      //
      // 上一步的结果会误导这一步：第 2 步为了不去连探针，故意送 instances: []，
      // 后端据此给出一条「一个都没配」——这条留到第 3 步就成了一个永远解不开的拦截，
      // 而人明明已经填好了实例。何况草稿在两次点击之间本来就可能改过。
      if (['repo', 'instances', 'paths'].includes(k)) {
        await checkCurrent();
      }
      const why = blocker();
      if (why) {
        ElementPlus.ElMessage.warning(why);
        return;
      }
      step.value = Math.min(step.value + 1, STEPS.length - 1);
      if (STEPS[step.value].key === 'check') await checkCurrent();
    }

    function prev() {
      step.value = Math.max(step.value - 1, 0);
    }

    const allOk = computed(() => {
      const list = Object.values(items.value);
      return list.length > 0 && list.every(i => i.ok);
    });

    async function create() {
      busy.value = true;
      try {
        const cfg = await api.post('/api/projects', payload());
        // 建完先采一次，别等下一个轮询周期。
        // 不采的话进去看到的是一个还没有任何数据的项目：探针状态 UNKNOWN、
        // 门禁按设计拒判（409）—— 人刚建完就先看到一屏「无法判定」，
        // 会以为是自己配错了。失败也不拦着进项目，轮询很快会补上
        try {
          await api.post('/api/projects/' + encodeURIComponent(cfg.id) + '/collect');
        } catch (e) {
          ElementPlus.ElMessage.warning('项目已建好，但首次采集没成功：' + e.message);
        }
        ElementPlus.ElMessage.success('项目 ' + cfg.id + ' 已创建并立即生效');
        emit('done', cfg);
      } catch (e) {
        ElementPlus.ElMessage.error(e.message);
      } finally {
        busy.value = false;
      }
    }

    function addRow() {
      rows.push({ lang: 'java', host: '127.0.0.1', port: PORTS.java });
    }
    function removeRow(i) {
      rows.splice(i, 1);
    }
    function onLangChange(r) {
      r.port = PORTS[r.lang];
    }

    const checkItems = computed(() => Object.values(items.value));

    return { STEPS, LANG, PORTS, step, busy, draft, rows, instances, languages, pathFields,
      items, checkItems, basicError, gateError, blocker, checkCurrent, next, prev,
      allOk, create, addRow, removeRow, onLangChange, emit };
  },
  template: `
<div class="view" data-testid="view-wizard">
  <div class="card">
    <div class="card-head">
      <h2>新建项目</h2>
      <span class="sub">第 {{ step + 1 }} / {{ STEPS.length }} 步 · {{ STEPS[step].title }}</span>
    </div>

    <div class="steps" data-testid="wizard-steps">
      <div v-for="(s, i) in STEPS" :key="s.key" class="step" :class="{ live: i === step, done: i < step }">
        <span class="num">{{ i + 1 }}</span>{{ s.title }}
      </div>
    </div>

    <div class="ob">
      <!-- 1 基本信息 -->
      <template v-if="STEPS[step].key === 'basic'">
        <div class="note info">项目标识会落进 URL 路径、WebSocket 查询串和历史表的分区键，
          所以只收小写字母、数字、下划线和横杠。<b>建好之后不能改</b>。</div>
        <div class="fld"><label>项目名</label>
          <el-input v-model="draft.name" data-testid="wz-name" placeholder="给人看的名字，例如 订单服务" /></div>
        <div class="fld"><label>项目标识</label>
          <el-input v-model="draft.id" data-testid="wz-id" placeholder="order-svc" /></div>
        <div v-if="basicError" class="err" data-testid="wz-error">{{ basicError }}</div>
      </template>

      <!-- 2 代码仓库 -->
      <template v-else-if="STEPS[step].key === 'repo'">
        <div class="note info">平台读的是<b>平台这台机器上已经 clone 好的仓库</b>，
          不做远程拉取，因此不需要 Git 账号密码。</div>
        <div class="fld"><label>Git 仓库目录</label>
          <el-input v-model="draft.repoDir" data-testid="wz-repo" placeholder="/path/to/project" /></div>
        <!-- 「默认基线」这四个字不解释任何事情。它其实只回答一句话：跟哪个版本比 ——
             增量覆盖率的分母就是从那个版本到现在改过的可执行行 -->
        <div class="fld"><label>增量基线<br><small>跟哪个版本比</small></label>
          <baseline-field v-model="draft.baseline" :repo-dir="draft.repoDir" testid="wz-baseline" />
        </div>
        <div class="note info">增量覆盖率只统计<b>从这个版本到现在改过的代码</b>：
          填 <code>origin/main</code> 回答的是「我这个分支相对主干改的代码测了没」，
          填上一个 tag 回答的是「这次发版的新代码测了没」。
          它是<b>默认值</b> —— 门禁接口每次可以带 <code>baseline=</code> 覆盖它。</div>
      </template>

      <!-- 3 被测实例 -->
      <template v-else-if="STEPS[step].key === 'instances'">
        <div class="note info">平台<b>没有注册中心</b>，实例不会自己上报，地址要在这里填。
          同一个服务的多个实例都要填上 —— 负载均衡会把请求分到任意一个，只看其中一个必然少算。</div>
        <div class="tbl-wrap">
          <table class="tbl" data-testid="wz-instances">
            <thead><tr><th style="width:120px">语言</th><th style="width:320px">主机</th><th style="width:120px">端口</th><th></th></tr></thead>
            <tbody>
              <tr v-for="(r, i) in rows" :key="i" data-testid="wz-inst-row">
                <td>
                  <el-select v-model="r.lang" size="small" @change="onLangChange(r)">
                    <el-option v-for="(n, k) in LANG" :key="k" :value="k" :label="n" />
                  </el-select>
                </td>
                <!-- 钩子带上行号：实例行是动态增删的，靠 nth-child 猜位置，
                     删一行断言就跟着错位 -->
                <td><el-input v-model="r.host" size="small" :data-testid="'wz-inst-host-' + i" /></td>
                <td><el-input v-model.number="r.port" size="small" :data-testid="'wz-inst-port-' + i" /></td>
                <td><el-button size="small" text type="danger" :disabled="rows.length === 1"
                               @click="removeRow(i)">删除</el-button></td>
              </tr>
            </tbody>
          </table>
        </div>
        <el-button size="small" style="margin-top:10px" data-testid="wz-add-inst" @click="addRow">+ 添加实例</el-button>
      </template>

      <!-- 4 语言与产物 -->
      <template v-else-if="STEPS[step].key === 'paths'">
        <div class="note info">下面这些字段是<b>按上一步选到的语言</b>列出来的（{{ languages.map(l => LANG[l]).join('、') }}）。
          它们错了不会报错，只会让覆盖率莫名其妙偏低 —— 所以这一步一定要验。</div>
        <div v-for="f in pathFields" :key="f[1]" class="fld">
          <label>{{ LANG[f[0]] }} · {{ f[2] }}</label>
          <el-input v-model="draft[f[1]]" :data-testid="'wz-' + f[1]" :placeholder="f[3]" />
        </div>
      </template>

      <!-- 5 覆盖门禁 -->
      <template v-else-if="STEPS[step].key === 'gate'">
        <div class="note info">CI 合并前打 <code>/api/coverage/gate</code>，按 <code>passed</code> 放行或阻断。
          <b>全量阈值默认 0（不设门槛）</b>：存量代码的整体覆盖率通常一开始就不达标，
          拿它挡合并只会让人立刻把门禁关掉。真正有意义的是增量 —— 这次改的代码测没测。</div>
        <div class="fld"><label>增量覆盖率阈值 (%)</label>
          <el-input v-model.number="draft.gate.incrementalThreshold" data-testid="wz-gate-inc" /></div>
        <div class="fld"><label>全量覆盖率阈值 (%)</label>
          <el-input v-model.number="draft.gate.overallThreshold" data-testid="wz-gate-full" /></div>
        <div v-if="gateError" class="err" data-testid="wz-error">{{ gateError }}</div>
      </template>

      <!-- 6 完成自检 -->
      <template v-else>
        <div class="note risk">这一步拿上面填的配置<b>去碰真实环境</b>：连探针、看目录、跑 git。
          全过才建得成 —— 建出一个采不到数据的项目，比建不出来更难查。</div>
        <div class="tbl-wrap">
          <table class="tbl" data-testid="wz-check-table">
            <thead><tr><th style="width:220px">检查项</th><th style="width:80px">结果</th><th>说明</th></tr></thead>
            <tbody>
              <tr v-for="it in checkItems" :key="it.name" data-testid="wz-check-row">
                <td>{{ it.label }}</td>
                <td><span class="tag" :class="it.ok ? 'ok' : 'err'">{{ it.ok ? '通过' : '不通过' }}</span></td>
                <td style="white-space:normal">{{ it.detail }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!checkItems.length" class="empty">还没跑自检</div>
      </template>
    </div>

    <div class="wz-foot">
      <el-button data-testid="wz-cancel" @click="emit('cancel')">取消</el-button>
      <span class="spacer"></span>
      <el-button :disabled="step === 0" data-testid="wz-prev" @click="prev">上一步</el-button>
      <el-button v-if="['repo','instances','paths','check'].includes(STEPS[step].key)"
                 :loading="busy" data-testid="wz-check" @click="checkCurrent">检查这一步</el-button>
      <el-button v-if="step < STEPS.length - 1" type="primary" :loading="busy"
                 data-testid="wz-next" @click="next">下一步</el-button>
      <el-button v-else type="primary" :loading="busy" :disabled="!allOk"
                 :title="allOk ? '' : '自检没有全过，建出来也采不到数据'"
                 data-testid="wz-create" @click="create">创建项目</el-button>
    </div>
  </div>
</div>`
};
