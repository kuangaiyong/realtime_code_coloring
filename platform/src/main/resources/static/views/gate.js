import { store, projectUrl } from '../store.js';
import { api, copyText } from '../api.js';

const { computed, ref, onMounted, watch } = Vue;

/**
 * 覆盖门禁 —— 这个平台分量最重的一个答案：这次能不能合并。
 *
 * <b>为什么值得一整屏，而不是看板里的一张卡：</b>它的结论会被 CI 直接拿去挡合并，
 * 而挤在看板中间要滚半屏才看得到，CI 接入说明更是只剩一行小字。
 *
 * <b>判定是三态，不是两态：</b>通过 / 不通过 / <b>判不了</b>。
 * 「判不了」走 409，「不通过」走 200 + passed:false —— CI 那句 curl -f 分不出
 * 「覆盖不够」和「平台自己挂了」，而这两件事一个该补测试、一个该找人看。
 *
 * <b>为什么把增量与全量并排：</b>两者回答的是不同的问题。全量说的是这个代码库的
 * 存量水位（通常一开始就不达标，拿它挡合并只会让人立刻把门禁关掉），
 * 增量说的是「这次改的代码测没测」—— 真正该挡的是后者。
 */
export const Gate = {
  setup() {
    const rows = ref([]);
    const loading = ref(true);
    const judgedAt = ref('');
    const copyLabel = ref('复制');

    /** 一种口径的查询串。判定与「接进 CI」共用一个 —— 两处各拼各的迟早会拼得不一样 */
    function queryFor(mode) {
      return mode === 'incremental'
        ? 'mode=incremental&baseline=' + encodeURIComponent(store.baseline.trim())
        : 'mode=full';
    }

    /** 一种口径的判定结果。拒判（4xx）不是错误，是三态里的一态，要原样呈现 */
    async function judge(mode) {
      const url = projectUrl('/coverage/gate?') + queryFor(mode);
      // 记下判的是哪个基线：顶栏改了基线并不会重判（那会变成每敲一个字符跑三个 git 进程），
      // 不记的话卡片就成了一个不知道按什么判出来的数字
      const baseline = mode === 'incremental' ? store.baseline.trim() : null;
      try {
        const d = await api.get(url);
        return { mode, url, baseline, verdict: d.passed ? 'pass' : 'block', data: d };
      } catch (e) {
        return { mode, url, baseline, verdict: 'undecided', reason: e.message, status: e.status };
      }
    }

    async function load() {
      // 场景快照是过去某一轮的独占覆盖，不是当前构建的整体情况，本就不参与判定
      if (store.viewScenario) {
        rows.value = [];
        loading.value = false;
        return;
      }
      loading.value = true;
      rows.value = await Promise.all([judge('full'), judge('incremental')]);
      judgedAt.value = new Date().toLocaleTimeString();
      loading.value = false;
    }

    /**
     * <b>不轮询。</b>门禁结论是个决策点，不是需要盯着跳的仪表盘。
     *
     * 说准一点：store 本来就会在每次 WS 推送（≈ 每轮采集）后按<b>当前口径</b>
     * 打一次 /coverage/gate，那份结论喂给总览的门禁卡。这一页刻意不再叠一条 ——
     * 它要的是<b>两种口径</b>，定时刷等于把增量判定也变成 3 秒一轮，
     * 而增量每次要起三个 git 子进程（查源码漂移、解析基线、算变更行，均无缓存），
     * 于是人只是把页面开着，机器就一直在跑 git。
     *
     * 代价是结论可能不是最新的 —— 所以必须把「判定于几点」标出来。
     * 不标的话，人会拿一个半小时前的结论去决定合不合并。
     */
    onMounted(load);

    /**
     * 数据源换了要重判。不重判的话：在场景快照下进这一页 → rows 是空的 →
     * 顶栏切回实时，组件并没有重新挂载，页面就永久停在「判定中…」且一张卡都没有。
     * 这不违背上面的「不轮询」—— 它由人的动作触发，与点「重新判定」同类。
     */
    watch(() => store.viewScenario, load);

    const LABEL = { full: '全量口径', incremental: '增量口径' };
    const WHY = {
      full: '这个代码库的存量水位。阈值默认 0（不设门槛）—— 存量覆盖率通常一开始就不达标，'
        + '拿它挡合并只会让人立刻把门禁整个关掉。',
      incremental: '这次改的代码测没测。真正该挡合并的是它 —— 存量欠的账不该由这次改动来还。'
    };
    const VERDICT = {
      pass: { text: '通过', cls: 'pass' },
      block: { text: '不通过', cls: 'block' },
      undecided: { text: '无法判定', cls: 'undecided' }
    };

    /**
     * CI 里真正要打的那条命令。<b>跟着顶栏当前的口径与基线走，而不是上一次判定的存根</b>：
     * 它是给人抄进流水线的模板，抄到的基线与框里显示的不一致，流水线判的就是另一个基线。
     * 上一次判定用的是哪个基线，写在那张卡上（见 baseline-drift）。
     */
    const ciCmd = computed(() =>
      'curl -sf "http://<平台地址>' + projectUrl('/coverage/gate?') + queryFor(store.mode)
      + '" | jq -e .passed');

    /** 顶栏的基线改过了，而卡片还是按旧基线判的 —— 不点破就是个不知道怎么来的数字 */
    function drifted(r) {
      return r.mode === 'incremental' && r.baseline !== null
        && r.baseline !== store.baseline.trim();
    }

    async function doCopy() {
      copyLabel.value = await copyText(ciCmd.value) ? '已复制' : '复制失败，请手动选中';
      setTimeout(() => { copyLabel.value = '复制'; }, 1600);
    }

    return { store, rows, loading, judgedAt, LABEL, WHY, VERDICT, ciCmd, copyLabel,
             doCopy, load, drifted };
  },
  template: `
<div class="view" data-testid="view-gate">
  <div v-if="store.viewScenario" class="card">
    <div class="card-head"><h2>覆盖门禁</h2></div>
    <div class="empty" data-testid="gate-archived">
      正在看场景 {{ store.viewScenario }} 的快照，不参与门禁判定 ——
      它是过去某一轮的独占覆盖，与「这次能不能合并」无关。
    </div>
  </div>

  <template v-else>
    <div class="card">
      <div class="card-head">
        <h2>判定</h2>
        <!-- 不轮询，所以「判定于几点」必须写出来：不标的话，人会拿一个半小时前的
             结论去决定合不合并 -->
        <span class="sub" data-testid="judged-at">{{ judgedAt ? '判定于 ' + judgedAt : '判定中…' }}</span>
        <el-button size="small" :loading="loading" data-testid="btn-rejudge" @click="load">重新判定</el-button>
      </div>
      <div class="ob" style="padding-bottom:12px">
        <div class="note info">这一页<b>不自动刷新</b>：门禁结论是个决策点，不是需要盯着跳的数字，
          而增量判定每次要起三个 git 子进程。代码或覆盖变了之后，点「重新判定」。</div>
      </div>
    </div>

    <div v-for="r in rows" :key="r.mode" class="card" data-testid="gate-card" :data-mode="r.mode">
      <div class="card-head">
        <h2>{{ LABEL[r.mode] }}</h2>
        <span class="sub" v-if="r.data">{{ r.data.metric }} · 阈值 {{ r.data.threshold }}%<template
          v-if="r.baseline"> · 基线 {{ r.baseline }}</template></span>
      </div>

      <div class="gate" :class="VERDICT[r.verdict].cls">
        <span class="verdict" :data-testid="'gate-verdict-' + r.mode">{{ VERDICT[r.verdict].text }}</span>
        <span class="why">{{ r.data ? r.data.reason : r.reason }}</span>
        <span class="num" v-if="r.data">
          <template v-if="r.data.actual === null">—</template>
          <template v-else>{{ r.data.actual }}<small>%</small></template>
        </span>
      </div>

      <div class="ob" style="padding-top:0">
        <!-- 顶栏改基线只触发 setMode→reload，不会重判这一页（重判 = 每敲一个字符起三个
             git 进程）。所以必须把「这个结论是按哪个基线判的」摆出来 -->
        <div v-if="drifted(r)" class="note risk" data-testid="baseline-drift">
          顶栏的基线已经改成 <b>{{ store.baseline.trim() }}</b>，而这个结论还是按
          <b>{{ r.baseline }}</b> 判的。点上面的「重新判定」。
        </div>
        <div class="note info">{{ WHY[r.mode] }}</div>
        <div v-if="r.verdict === 'undecided'" class="note risk">
          <b>「判不了」不是「不通过」。</b>
          <!-- 连不上平台时压根没有响应，status 是 undefined —— 这里回退成「4xx」的话，
               就是把一件不知道的事说成知道的，而这两种情况该找的人不一样 -->
          <template v-if="r.status">服务端回的是 HTTP {{ r.status }} 而不是 200 ——
            前者该找人看平台（探针掉线、源码漂移、基线不存在），后者才该补测试。</template>
          <template v-else>这次连服务端的回应都没拿到（平台没起、或被网络挡了），
            与「这次改的代码覆盖不够」是两回事。</template>
          CI 里那句 <code>curl -f</code> 分不出这些，所以必须靠状态码分开。
        </div>
        <div v-else-if="r.data" class="mono" style="color:var(--el-text-color-secondary)">
          已覆盖 {{ r.data.coveredLines }} 行 / 未覆盖 {{ r.data.missedLines }} 行
          <template v-if="r.data.actual === null">
            · 分母为 0 时放行：这次没改任何可执行代码，不是「改了却一行没测」
          </template>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head"><h2>接进 CI</h2></div>
      <div class="ob">
        <div class="note info">按 <code>passed</code> 放行或阻断。
          <b>判不了时返回 409，别把它当成不通过</b> —— 那说明平台侧有问题，
          而不是这次改动的覆盖不够。</div>
        <div class="snip-wrap">
          <button class="copy" data-testid="btn-copy-ci" @click="doCopy">{{ copyLabel }}</button>
          <pre class="snippet" data-testid="gate-ci">{{ ciCmd }}</pre>
        </div>
        <div style="font-size:12px;color:var(--el-text-color-secondary);margin-top:6px">
          命令里的口径与基线都跟着顶栏走（改了立刻反映在这里，不必先重新判定），
          项目 id 已经带在路径里 —— 不带的话它恒落在默认项目上，
          在别的项目页面照抄进 CI，判的是另一份代码。
        </div>
      </div>
    </div>
  </template>
</div>`
};
