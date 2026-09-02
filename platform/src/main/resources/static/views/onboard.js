import { store, loadPerInstance } from '../store.js';
import { api, esc, copyText, LANG, pctClass } from '../api.js';
// 四种语言的接入定义与「接入帮助」页共用一份，见 onboard-data.js 的说明
import { MARK, run, OB, OB_HINT } from './onboard-data.js';

const { computed, ref, watch } = Vue;


export const Onboard = {
  setup() {
    const lang = ref('java');
    const copyLabel = ref('复制');

    // 只在实例列表真的有内容时才更新。场景快照与配置报错时 instances 是空的，
    // 那不代表「没配这门语言」—— 跟着清空的话，向导会把真实端口换成默认端口，
    // 照抄下去连不上，而页面上看不出任何异样
    const liveInstances = ref([]);
    watch(() => store.summary && store.summary.instances, (v) => {
      if (v && v.length) liveInstances.value = v;
    }, { immediate: true });

    const o = computed(() => OB[lang.value]);

    /** 从最近一份非空实例列表里挑出该语言实际配置的地址，没有就给个占位 */
    const eps = computed(() => liveInstances.value
      .filter(i => String(i.endpoint).split('://')[0] === lang.value)
      .map(i => {
        const hp = String(i.endpoint).split('://')[1] || '';
        const at = hp.lastIndexOf(':');
        return { host: hp.substring(0, at), port: hp.substring(at + 1) };
      }));

    // 端口取本平台实际配置的那个。没配这门语言时用默认端口，并说明这是默认值，
    // 免得照抄之后发现平台根本没在听这个端口
    const hp = computed(() => eps.value.length
      ? eps.value[0] : { host: '127.0.0.1', port: String(o.value.port) });

    // ---- 接入参数表单 ----
    //
    // 存浏览器本地而不是服务端：这是「我这次接入时填的东西」，不是项目配置。
    // 存到服务端就等于开了第二个配置入口，而配置的保存入口只应该有「项目设置」一处。
    // 按项目 id + 语言分键：同一个人往往要给几个项目各接一遍，串了比不存还糟。
    const LS_KEY = 'rtcc.onboard.form';

    /**
     * localStorage 读写一律包起来：隐私窗口、禁用站点数据的浏览器里
     * <b>访问器本身就会抛</b>，不是返回空 —— 不兜住的话整个视图白屏。
     * 兜住之后退回内存态，页面照常可用，只是刷新后要重填。
     */
    function lsGet() {
      try {
        return JSON.parse(localStorage.getItem(LS_KEY) || '{}');
      } catch (e) {
        return {};
      }
    }
    function lsSet(all) {
      try {
        localStorage.setItem(LS_KEY, JSON.stringify(all));
      } catch (e) { /* 存不进去就只在内存里活着，不影响这一页能不能用 */ }
    }

    // 内存态是权威，localStorage 只是它的备份 —— 反过来的话，存不进去时页面就废了
    const forms = ref(lsGet());
    const formKey = computed(() => store.projectId + '|' + lang.value);
    const form = computed(() => forms.value[formKey.value] || {});

    function setField(key, val) {
      const all = Object.assign({}, forms.value);
      all[formKey.value] = Object.assign({}, all[formKey.value], { [key]: val });
      forms.value = all;
      lsSet(all);
    }

    /**
     * 取一个参数的值：填了用填的，没填回落到示例占位符。
     *
     * <b>没填时必须仍给出完整命令</b>：人第一次进这一页往往是来「看看要做什么」的，
     * 空着就把命令藏起来或者留一串空洞，等于把这一页原本的价值也弄丢了。
     */
    function valueOf(key) {
      const fd = (o.value.fields || []).find(x => x.key === key);
      const v = (form.value[key] || '').trim();
      if (!v) return fd ? fd.ph : '';
      // 填过的包上 MARK，渲染时是蓝色；没填的原样给示例值，保持灰色。
      // 这样一眼能看出「还有哪几处是要替换的」—— 与端口那处用的是同一套标记，
      // 语义也一致：蓝色 = 已经确定的值，灰色 = 待你替换的示例。
      // 复制时标记会被剥掉，所以粘出去的始终是纯命令；而 MARK 是个输入框里
      // 打不出来的控制字符，用户填的值再古怪也撞不上它（见 MARK 的说明）
      return MARK + v + MARK;
    }

    const cmdLines = computed(() => o.value.cmd(hp.value, valueOf));

    /**
     * 命令块走 v-html 而非模板插值：MARK 之间那段是已经确定的值（平台配置里的地址、
     * 或用户自己填的参数），
     * 要标成蓝色让人知道哪里是真的、哪里是占位；而 <pre> 里的换行不能交给
     * Vue 的模板编译（whitespace: condense 会动它），只能自己拼好 HTML。
     * 拼进去的每一段都过 esc()。
     */
    const cmdHtml = computed(() => cmdLines.value.map(([cls, t]) => {
      const body = esc(t).split(MARK)
        .map((x, n) => n % 2 ? '<span class="val">' + x + '</span>' : x).join('');
      return cls ? '<span class="' + cls + '">' + body + '</span>' : body;
    }).join('\n'));

    // 复制出去的是纯命令：剥掉高亮标记，不含任何页面用于渲染的东西
    const rawCmd = computed(() =>
      cmdLines.value.map(([, t]) => t.split(MARK).join('')).join('\n'));

    const epNote = computed(() => eps.value.length
      ? '蓝色部分取自本平台的项目配置，是实际在监听的地址。'
        + (eps.value.length > 1 ? '本平台还配了 '
          + eps.value.slice(1).map(e => e.host + ':' + e.port).join('、')
          + '，第二个实例把地址换成它。' : '')
      : '本平台目前没有配置 ' + o.value.name + ' 实例，上面用的是默认端口。'
        + '实际接入时端口由你定，填进项目配置即可。');

    /**
     * 平台侧要填的那份配置，按同一份表单算出来。
     *
     * <b>这一页只生成，不保存</b>：配置的保存入口只应该有「项目设置」一处。
     * 在这里也能存的话，实例地址与 classes-dir 就有两个地方能改，
     * 而两处改出不同的值时，没有任何地方看得出是哪一处生效了。
     */
    const cfgFields = computed(() => (o.value.fields || []).filter(fd => fd.cfgField));

    const ymlSnippet = computed(() => {
      const lines = ['coverage:', '  instances:',
        '    - "' + (lang.value === 'java' ? '' : lang.value + '://')
          + hp.value.host + ':' + hp.value.port + '"'];
      // 没填的项也列出来，值留空 —— 漏掉的话，人照抄完还差几项却不知道差在哪
      for (const fd of cfgFields.value) {
        lines.push('  ' + fd.cfgKey + ': ' + ((form.value[fd.key] || '').trim() || '<还没填>'));
      }
      return lines.join('\n');
    });

    /** 已填的平台侧配置，跳设置页时带过去。空值不带 —— 带过去会把设置页里已有的值清掉 */
    const pendingCfg = computed(() => {
      const out = {};
      for (const fd of cfgFields.value) {
        const v = (form.value[fd.key] || '').trim();
        if (v) out[fd.cfgField] = v;
      }
      return out;
    });

    // ---- 探针物料 ----
    // 平台面向内网，不能假设有外网 —— 这是用户唯一拿得到这些文件的途径。
    // 前提说明由后端给（见 ProbeArtifactController）：不满足前提的人下载完接不上、
    // 还不知道为什么，而页面改版不该把这句话弄丢
    const artifacts = ref([]);
    api.get('/api/probe/artifacts')
      .then(d => { artifacts.value = d.artifacts || []; })
      .catch(() => { /* 取不到就不显示这一节，不挡住整页 */ });

    const artifact = computed(() => artifacts.value.find(a => a.id === lang.value) || null);

    // ---- 单实例就地探测 ----
    // 人刚改完启动参数重启了服务，等下一个 3 秒轮询周期才知道成没成，
    // 这段等待里最常见的动作是反复刷新页面
    const probing = ref('');
    const probed = ref({});

    async function probeOne(endpoint) {
      probing.value = endpoint;
      try {
        const d = await api.post(
          '/api/projects/' + encodeURIComponent(store.projectId) + '/instances/probe',
          { endpoint });
        probed.value = Object.assign({}, probed.value, { [endpoint]: d });
      } catch (e) {
        probed.value = Object.assign({}, probed.value,
          { [endpoint]: { connected: false, error: e.message } });
      } finally {
        probing.value = '';
      }
    }

    /** 探测结果的一句话。没探过就返回 null，让那一格空着而不是写「未知」 */
    function probeText(endpoint) {
      const r = probed.value[endpoint];
      if (!r) return null;
      if (!r.connected) return { ok: false, text: '仍未连上：' + (r.error || '未给出原因') };
      if (!r.buildId) return { ok: false, text: '连上了，但没上报构建版本 —— 增量口径仍不可用' };
      return { ok: true, text: '已连上，构建版本 ' + String(r.buildId).substring(0, 8)
        + (r.dirty ? '（dirty）' : '') };
    }

    /**
     * 这门语言的参数是不是都填齐了。只看<b>页面自己知道的</b>东西 ——
     * 值对不对平台无从判断（多数路径在被测那台机器上，见 fields 的 side 说明），
     * 所以这一步的完成态只说明「你填完了」，不说明「填对了」。
     */
    // optional 的项不计入：Rust 的 xwin / toolchain 只有 Windows 要填，
    // 而它们的提示自己写着「Linux 上留空即可」—— 都算必填的话，
    // Linux 用户这一步永远点不亮，而页面还不说漏的是哪一项
    const allFilled = computed(() =>
      (o.value.fields || []).every(fd => fd.optional || (form.value[fd.key] || '').trim()));

    /** 还差哪几项没填 —— 第 2 步不亮时要说得出原因，否则人只能一个个框去找 */
    const missingFields = computed(() => (o.value.fields || [])
      .filter(fd => !fd.optional && !(form.value[fd.key] || '').trim())
      .map(fd => fd.label));

    const cfgCopyLabel = ref('复制配置');
    async function copyCfg() {
      cfgCopyLabel.value = await copyText(ymlSnippet.value) ? '已复制' : '复制失败，请手动选中';
      setTimeout(() => { cfgCopyLabel.value = '复制配置'; }, 1600);
    }

    /**
     * 去「项目设置」填这份配置，把已填的值带过去。
     *
     * 走 store 上的一次性字段（与报表点方法跳染色页用 store.jumpToLine 同一个手法），
     * <b>设置页读完必须立即清空</b> —— 不清的话，此后每次进设置页都会被这份陈旧的值
     * 覆盖，而人不会知道自己刚改的值为什么又变回去了。
     */
    function toSettings() {
      store.pendingConfig = Object.keys(pendingCfg.value).length ? pendingCfg.value : null;
      location.hash = '#/p/' + encodeURIComponent(store.projectId) + '/settings';
    }

    async function doCopy() {
      copyLabel.value = await copyText(rawCmd.value) ? '已复制' : '复制失败，请手动选中';
      setTimeout(() => { copyLabel.value = '复制'; }, 1600);
    }

    // ---- 接入自检 ----
    // 视图不可用时故意用 lastGood：触发「不可用」的典型原因就是增量返回 409
    // （某台实例脏了、或实例间版本不一致），而这张表正是唯一能点名「是哪一台」的地方
    const src = computed(() => store.summary || store.lastGood);
    const inst = computed(() => (src.value && src.value.instances) || []);

    /** 这门语言至少有一台实例真的连上了 —— 这一条由平台自己回答，不看页面输入 */
    const anyConnected = computed(() => inst.value.some(i =>
      i.status === 'CONNECTED' && String(i.endpoint).split('://')[0] === lang.value));

    const checkMeta = computed(() => inst.value.length
      ? inst.value.filter(i => i.status === 'CONNECTED').length + '/' + inst.value.length + ' 已连上' : '');

    const checkEmpty = computed(() => {
      const s = src.value;
      if (s && s.probeStatus === 'CONFIG_ERROR') {
        return { warn: '平台配置有误，实例一个都没解析出来：', detail: s.lastError || '（平台未给出原因）' };
      }
      return { empty: s && s.probeStatus === 'ARCHIVED'
        ? '场景快照不含实例信息（数据已定格）' : '项目配置的 instances 里没有配置任何实例' };
    });

    function langOf(endpoint) {
      const l = String(endpoint).split('://')[0];
      return LANG[l] || l;
    }

    /** 「下一步做什么」是这张表的全部价值，所以每一种没接上的原因都要给出具体动作 */
    function todo(i) {
      const l = String(i.endpoint).split('://')[0];
      if (i.status !== 'CONNECTED') {
        return '没连上（' + esc(i.status) + '）。' + (OB_HINT[l] || '')
          + (i.error ? '<br><span class="mono">' + esc(i.error) + '</span>' : '');
      }
      if (!i.buildCommit) {
        return '已接入，但没上报构建版本 —— 补上 '
          + (l === 'java' ? '<code>sessionid</code>' : '<code>COVERAGE_BUILD_ID</code>')
          + '，否则增量口径不可用';
      }
      if (i.dirty) {
        return '已接入，但被测源码有未提交的改动（<code>-dirty</code>）。'
          + '产物对不上任何一个提交，平台会拒绝出增量报告 —— 提交后重新构建并重启';
      }
      return '<span style="color:var(--el-color-success)">已接入，无需再做什么</span>';
    }

    /**
     * 各实例分别的覆盖。<b>按需拉取</b>：对每个实例各跑一次归一化，
     * 开销与实例数成正比，所以不随轮询自动做。没加载过时后三列显示「—」。
     *
     * 这三列原先在总览页的「被测实例」表上，而那张表的前四列与本表完全重复。
     * 同一批实例的状态散在两个视图里，想知道「6301 现在怎么了」得两边都看。
     */
    const perInstMap = computed(() =>
      store.perInst ? new Map(store.perInst.map(r => [r.endpoint, r])) : null);

    function perInstOf(endpoint) {
      const m = perInstMap.value;
      if (!m) return null;
      const r = m.get(endpoint);
      return r && r.overallRatio !== null && r.overallRatio !== undefined ? r : undefined;
    }

    return {
      lang, o, cmdHtml, epNote, ymlSnippet, copyLabel, doCopy,
      form, setField, cfgCopyLabel, copyCfg, toSettings, missingFields,
      artifact, allFilled, anyConnected, probing, probeOne, probeText,
      src, inst, checkMeta, checkEmpty, langOf, todo,
      store, perInstMap, perInstOf, loadPerInstance, pctClass
    };
  },
  template: `
<div class="view" data-testid="view-onboard">
  <div class="card">
    <div class="card-head">
      <h2>接入向导</h2>
      <span class="sub">源码零改动，只调启动参数或构建参数</span>
    </div>
    <!-- 完成态只给「平台能观测到的」那几步：第 1、2 步依页面自身的输入，
         第 4 步依平台真的探到的结果。
         <b>第 3 步「重启被测服务」永远不打勾</b> —— 它发生在被测方，平台无从得知，
         打勾会变成一句没有依据的断言，而人会据此以为自己已经做过了 -->
    <div class="steps" data-testid="ob-steps">
      <div class="step done" data-testid="ob-step-1"><span class="num">1</span>选择语言</div>
      <div class="step" :class="{ done: allFilled }" data-testid="ob-step-2"
           :title="missingFields.length ? '还差：' + missingFields.join('、') : '参数已填齐'">
        <span class="num">2</span>填参数、改启动 / 构建参数
        <!-- 不亮时要说得出还差哪几项，否则人只能一个个输入框去找 -->
        <span v-if="missingFields.length" class="miss" data-testid="ob-missing">
          还差 {{ missingFields.length }} 项
        </span>
      </div>
      <div class="step" data-testid="ob-step-3"><span class="num">3</span>重启被测服务</div>
      <div class="step" :class="anyConnected ? 'done' : 'live'" data-testid="ob-step-4">
        <span class="num">4</span>回这一页看探针连上没有
      </div>
    </div>
    <div class="lang-tabs">
      <button v-for="(v, k) in { java: 'Java', go: 'Go', cpp: 'C++', rust: 'Rust' }" :key="k"
              :class="{ on: lang === k }" :data-testid="'ob-lang-' + k" @click="lang = k">{{ v }}</button>
      <!-- 风险提示、语言坑、平台侧配置说明都搬到「接入帮助」了。
           这一页只留能点能填的东西，但必须留一条通往说明的路 ——
           不留的话，人在这里遇到不懂的参数就没有下一步了 -->
      <a class="help-link" :href="'#/p/' + store.projectId + '/help/' + lang" data-testid="ob-help-link">
        {{ o.name }} 详细说明 →
      </a>
    </div>
    <div class="ob">
      <!-- 平台面向内网，不能假设有外网 —— 这是拿到这些文件唯一的途径。
           <b>适用前提留一句话在下载处</b>（完整版在帮助页）：不满足前提的人
           下载完接不上、还不知道为什么，而那句话搬走就等于没写 —— 与字段的 ⓘ 同一条理由 -->
      <h3 v-if="artifact">0 · 先拿探针</h3>
      <div v-if="artifact" class="ob-artifact" data-testid="ob-artifact">
        <a class="dl" :href="'/api/probe/artifacts/' + artifact.id" data-testid="ob-download">
          下载 {{ artifact.file }}
        </a>
        <span class="meta mono" v-if="artifact.version">版本 {{ artifact.version }}</span>
        <span class="meta mono" v-if="artifact.size">{{ Math.round(artifact.size / 1024) }} KB</span>
        <el-tooltip effect="dark" placement="top" :content="artifact.prerequisite">
          <span class="pre" data-testid="ob-artifact-pre">{{ artifact.prerequisite }}</span>
        </el-tooltip>
      </div>

      <h3>1 · 填参数，命令自己算出来</h3>
      <div class="ob-form" data-testid="ob-form">
        <div class="ob-field" v-for="fd in o.fields" :key="fd.key" :data-field="fd.key">
          <label>
            {{ fd.label }}
            <span class="side" :class="fd.side">{{ fd.side === 'target' ? '被测机器' : '平台机器' }}</span>
            <!-- 说明降级成 ⓘ 而不是搬走：这几条讲的是「填错了看不出来」
                 （includes 填宽了覆盖率莫名偏低、objDir 必须绝对路径…），
                 人在<b>填</b>的那一刻看不到就等于没写。raw-content 是因为
                 hint 里有 <code>/<b> 标签 -->
            <el-tooltip v-if="fd.hint" effect="dark" placement="top" raw-content :content="fd.hint">
              <span class="fi" :data-testid="'ob-hint-' + fd.key">ⓘ</span>
            </el-tooltip>
          </label>
          <input type="text" :placeholder="fd.ph" :value="form[fd.key] || ''"
                 :data-testid="'ob-in-' + fd.key"
                 @input="setField(fd.key, $event.target.value)">
        </div>
      </div>
      <div class="snip-wrap">
        <button class="copy" @click="doCopy">{{ copyLabel }}</button>
        <pre class="snippet" data-testid="ob-cmd" v-html="cmdHtml"></pre>
      </div>
      <div style="font-size:12px;color:var(--el-text-color-secondary);margin-top:6px">{{ epNote }}</div>

      <h3>2 · 把这份配置填进项目设置</h3>
      <!-- 「为什么没有注册中心」「为什么只有项目设置能存」这些原理都在帮助页。
           这里只留一句边界 —— 它解释的是<b>眼前这个按钮为什么不叫「保存」</b> -->
      <div class="snip-wrap">
        <button class="copy" data-testid="ob-copy-cfg" @click="copyCfg">{{ cfgCopyLabel }}</button>
        <pre class="snippet" data-testid="ob-cfg">{{ ymlSnippet }}</pre>
      </div>
      <div class="cfg-foot">
        <el-button size="small" type="primary" plain data-testid="ob-to-settings" @click="toSettings">
          去「项目设置」填，并带上已填的值
        </el-button>
        <span class="tip">这一页只算给你看，不保存</span>
      </div>
    </div>
  </div>

  <div class="card">
    <div class="card-head">
      <h2>接入自检</h2>
      <el-button size="small" :loading="store.perInstLoading" data-testid="btn-per-inst"
                 title="对每个实例各跑一次归一化，开销与实例数成正比，所以不随轮询自动做"
                 @click="loadPerInstance">
        {{ store.perInst ? '重新加载各实例覆盖' : '加载各实例覆盖' }}
      </el-button>
      <span class="sub">{{ checkMeta }}</span>
    </div>
    <template v-if="!inst.length">
      <div v-if="checkEmpty.warn" class="warn">
        {{ checkEmpty.warn }}<br><span class="mono">{{ checkEmpty.detail }}</span>
      </div>
      <div v-else class="empty">{{ checkEmpty.empty }}</div>
    </template>
    <template v-else>
      <!-- 版本不一致要单独说：它不是「没连上」，恰恰是全连上了才会出现，
           而后果比掉一台更严重 —— 聚合会静默丢弃对不上的那部分 -->
      <div v-if="src.versionError" class="warn" style="margin-bottom:0">{{ src.versionError }}</div>
      <div class="tbl-wrap">
        <table class="tbl" data-testid="ob-check-table">
          <thead><tr>
            <th>探针地址</th><th>语言</th><th>接上没有</th><th>自报构建版本</th>
            <template v-if="perInstMap"><th>该实例行覆盖率</th><th>已覆盖</th><th>未覆盖</th></template>
            <th>下一步</th>
          </tr></thead>
          <tbody>
            <tr v-for="i in inst" :key="i.endpoint" data-testid="ob-check-row" :data-endpoint="i.endpoint">
              <td class="mono">{{ i.endpoint }}</td>
              <td>{{ langOf(i.endpoint) }}</td>
              <td><span class="tag" :class="i.status === 'CONNECTED' ? 'ok' : 'err'">{{ i.status === 'CONNECTED' ? '已连上' : '未连上' }}</span></td>
              <td class="mono">
                {{ i.buildCommit ? i.buildCommit.substring(0, 8) : '—' }}
                <span v-if="i.dirty" class="tag err">dirty</span>
              </td>
              <template v-if="perInstMap">
                <template v-if="perInstOf(i.endpoint)">
                  <td><span class="pc mono" :class="pctClass(perInstOf(i.endpoint).overallRatio)">{{ perInstOf(i.endpoint).overallRatio }}%</span></td>
                  <td class="mono">{{ perInstOf(i.endpoint).coveredLines }}</td>
                  <td class="mono">{{ perInstOf(i.endpoint).missedLines }}</td>
                </template>
                <template v-else><td class="mono">—</td><td class="mono">—</td><td class="mono">—</td></template>
              </template>
              <td>
                <div v-html="todo(i)"></div>
                <!-- 改完启动参数重启完服务，等下一个 3 秒轮询周期才知道成没成 ——
                     那段等待里最常见的动作是反复刷新页面 -->
                <div class="probe-row">
                  <el-button size="small" text type="primary" :data-testid="'ob-probe-' + i.endpoint"
                             :loading="probing === i.endpoint" @click="probeOne(i.endpoint)">
                    测这一台
                  </el-button>
                  <span v-if="probeText(i.endpoint)" class="probe-res"
                        :class="probeText(i.endpoint).ok ? 'ok' : 'err'"
                        :data-testid="'ob-probe-res-' + i.endpoint">
                    {{ probeText(i.endpoint).text }}
                  </span>
                </div>
              </td>
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
</div>`
};
