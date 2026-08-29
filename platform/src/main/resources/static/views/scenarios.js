import { store, loadScenarios, toggleScenario, reload } from '../store.js';
import { pctClass } from '../api.js';

const { computed, ref, onMounted } = Vue;

/**
 * 测试场景 —— 「这一轮测试到底覆盖了什么」。
 *
 * <b>为什么从顶部横条挪到独立视图：</b>横条把两种不同的东西挤在了一起 ——
 * 「数据源」是<b>口径</b>（它决定下面每个数字的含义，必须常驻可见），
 * 「开始 / 结束场景」是<b>操作</b>（一次性动作，没有常驻的理由）。
 * 更要紧的是归档的场景只能从下拉框里瞥一眼，看不到它们各自覆盖了多少、多少文件。
 *
 * <b>录制的语义：</b>start 会把所有实例的计数器清零，stop 那一刻定格。
 * 所以一个场景的覆盖是「这段时间内独占跑到的代码」，不是累计值 ——
 * 这正是它能回答「这一轮测试覆盖了什么」的原因，也是它必须清零的原因。
 */
export const Scenarios = {
  setup() {
    const input = ref('');
    const busy = ref(false);
    const running = computed(() => !!store.activeScenario);

    onMounted(() => {
      loadScenarios().catch(e => {
        store.banner = { level: 'err', text: '场景列表取不到：' + e.message };
      });
    });

    async function toggle() {
      busy.value = true;
      try {
        await toggleScenario(running.value ? '' : input.value);
        input.value = '';
      } catch (e) {
        // 服务端的 409 会说清为什么（已有场景在跑 / 场景 ID 重复），原样带出来
        ElementPlus.ElMessage.error({ message: e.message, duration: 6000 });
      } finally {
        busy.value = false;
      }
    }

    /** 切过去看某个场景定格下来的独占覆盖。空串回到实时累计 */
    async function view(id) {
      store.viewScenario = id;
      await reload();
    }

    // 新录的排前面：出问题时人先看刚跑的那一轮
    const sorted = computed(() => store.scenarios.slice().sort(
      (a, b) => String(b.startedAt || '').localeCompare(String(a.startedAt || ''))));

    /** 这一轮录了多久。时长比「几点开始」更能说明这是一次冒烟还是一整轮回归 */
    function lasted(s) {
      if (!s.startedAt || !s.stoppedAt) return '进行中';
      const sec = Math.round((new Date(s.stoppedAt) - new Date(s.startedAt)) / 1000);
      if (sec < 60) return sec + ' 秒';
      if (sec < 3600) return Math.round(sec / 60) + ' 分钟';
      return (sec / 3600).toFixed(1) + ' 小时';
    }

    return { store, input, busy, running, toggle, view, sorted, lasted, pctClass };
  },
  template: `
<div class="view" data-testid="view-scenarios">
  <div class="card">
    <!-- 「进行中」的红点只在顶栏挂一份：这里再挂一个的话，同一个 data-testid
         在这一页会命中两次，而它是 E2E 的契约 -->
    <div class="card-head">
      <h2>录制场景</h2>
    </div>
    <div class="ob">
      <!-- 清零这件事必须写在动手之前：人以为只是「开始记录」，结果把之前跑出来的
           覆盖全抹了，而界面上只表现为覆盖率突然掉到接近 0 -->
      <div class="note risk">点「开始」会把<b>所有被测实例的计数器清零</b>，
        「结束」那一刻定格。所以一个场景记下的是这段时间内<b>独占</b>跑到的代码，
        而不是累计值 —— 这正是它能回答「这一轮测试覆盖了什么」的原因。
        场景进行中不能清零、也不能改配置（服务端会回 409）。</div>
      <div class="fld">
        <label>场景 ID</label>
        <el-input :model-value="running ? store.activeScenario : input" :disabled="running"
                  data-testid="scenario-id" placeholder="给这一轮测试起个名字，例如 支付成功回归"
                  @update:model-value="v => input = v" />
        <el-button :type="running ? 'danger' : 'primary'" :loading="busy"
                   data-testid="btn-scenario" @click="toggle">
          {{ running ? '结束场景' : '开始场景' }}
        </el-button>
      </div>
    </div>
  </div>

  <div class="card">
    <div class="card-head">
      <h2>已归档的场景</h2>
      <span class="sub" data-testid="scenario-count">{{ sorted.length }} 个</span>
    </div>
    <div v-if="!sorted.length" class="empty">还没有归档的场景 —— 上面录一个试试</div>
    <div v-else class="tbl-wrap">
      <table class="tbl" data-testid="scenario-table">
        <thead><tr>
          <th>场景</th><th style="width:110px">文件</th><th style="width:120px">行覆盖率</th>
          <th style="width:180px">录制时间</th><th style="width:100px">录了多久</th>
          <th style="width:150px">操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="s in sorted" :key="s.scenarioId" data-testid="scenario-row" :data-id="s.scenarioId">
            <td>
              <span class="mono">{{ s.scenarioId }}</span>
              <span v-if="store.viewScenario === s.scenarioId" class="tag ok"
                    style="margin-left:6px">正在看</span>
            </td>
            <td class="mono">{{ s.files }}</td>
            <td><span class="pc mono" :class="pctClass(s.overallRatio)">{{ s.overallRatio }}%</span></td>
            <td class="mono">{{ new Date(s.startedAt).toLocaleString() }}</td>
            <td class="mono">{{ lasted(s) }}</td>
            <td class="actions">
              <el-button size="small" :disabled="store.viewScenario === s.scenarioId"
                         data-testid="btn-view-scenario" @click="view(s.scenarioId)">查看覆盖</el-button>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="legend" style="border-bottom:none;border-top:1px solid var(--el-border-color-lighter)">
        点「查看覆盖」会把顶部的数据源切到这个场景，染色与看板随之显示它定格下来的那一份。
        <!-- 场景快照是过去某一轮的独占覆盖，不是当前构建的整体情况；
             拿它判门禁得出的结论与「这次能不能合并」无关 -->
        场景快照<b>不参与门禁判定</b>。
      </div>
    </div>
  </div>

  <div v-if="store.viewScenario" class="card">
    <div class="card-head"><h2>当前数据源</h2></div>
    <div class="ob">
      <div class="note info">正在看场景 <b>{{ store.viewScenario }}</b> 的独占覆盖（已定格）。
        <el-button size="small" style="margin-left:10px" data-testid="btn-back-live"
                   @click="view('')">回到实时累计覆盖</el-button></div>
    </div>
  </div>
</div>`
};
