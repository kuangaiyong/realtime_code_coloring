import { api } from '../api.js';
import { store, projectUrl } from '../store.js';

const { computed, ref, onMounted, onUnmounted } = Vue;

/**
 * 采集事件 —— 回答「昨天半夜那次为什么没数据」。
 *
 * <b>为什么需要这一页：</b>平台此前只有一个 `lastError` 挂在当前快照上，
 * 下一轮采集成功就被冲掉了。掉线、版本冲突、产物目录被删 —— 这些事发生过又恢复了，
 * 事后一点痕迹都没有，而覆盖率数字上的那个坑还在，没人解释得了。
 *
 * <b>为什么只有这么几条：</b>服务端只在状态<b>变化</b>时记一条。3 秒一轮的轮询全记下来
 * 是一天 28800 行，「连上了 / 掉了 / 又连上了」这三条才是人要看的。
 */
const STATUS = {
  CONNECTED: { text: '正常', cls: 'ok' },
  PARTIAL: { text: '部分实例掉线', cls: 'warn' },
  DISCONNECTED: { text: '探针不可达', cls: 'err' },
  ANALYZE_ERROR: { text: '分析失败', cls: 'err' },
  CONFIG_ERROR: { text: '配置有误', cls: 'err' },
  UNKNOWN: { text: '尚未采集', cls: 'warn' }
};

export const Events = {
  setup() {
    const rows = ref([]);
    const available = ref(true);
    const reason = ref(null);
    const loading = ref(true);

    async function load() {
      try {
        const d = await api.get(projectUrl('/events?limit=200'));
        rows.value = d.events;
        available.value = d.available;
        reason.value = d.error;
      } catch (e) {
        available.value = false;
        reason.value = e.message;
      } finally {
        loading.value = false;
      }
    }

    // 事件只在状态变化时产生，不必盯得太紧；15 秒足够让人看到刚发生的那一条
    let timer = null;
    onMounted(() => { load(); timer = setInterval(load, 15000); });
    onUnmounted(() => clearInterval(timer));

    function statusOf(s) {
      return STATUS[s] || { text: s, cls: 'warn' };
    }

    /**
     * 每条事件所引入的状态<b>持续了多久</b> —— 「掉了 3 分钟」比两个时间戳更容易读。
     *
     * <b>时长属于较旧的那一条，不是较新的那一条。</b>列表是倒序的：rows[i] 比
     * rows[i+1] 新，两者的时间差是 rows[i+1] 引入的那个状态持续到被 rows[i] 顶替为止。
     * 挂到 rows[i] 上就恰好说反了 —— 掉线持续 14 秒会被写成「恢复正常 · 14 秒」，
     * 而掉线那行显示的是它之前那段正常的时长。这一页唯一要回答的就是「掉了多久」。
     */
    const withGap = computed(() => {
      const list = rows.value;
      const now = Date.now();
      return list.map((e, i) => {
        const prev = list[i - 1];      // 更新的那一条，即这个状态被谁顶替
        const end = prev ? new Date(prev.at).getTime() : now;
        return Object.assign({}, e, {
          lasted: humanize(end - new Date(e.at).getTime()),
          // 最新一条还在持续中，标出来 —— 否则会被读成「已经结束了」
          ongoing: !prev
        });
      });
    });

    return { withGap, rows, available, reason, loading, statusOf, store };
  },
  template: `
<div class="view" data-testid="view-events">
  <div class="card">
    <div class="card-head">
      <h2>采集事件</h2>
      <span class="sub">只记状态变化，不是每轮一条</span>
    </div>
    <div class="ob" style="padding-bottom:0">
      <div class="note info">这里回答的是「<b>那段时间为什么没数据</b>」。
        采集每 3 秒一轮，全记下来一天两万多条，真正的转折点会被淹掉 ——
        所以只在状态变了的时候落一条，中间稳定的那几千轮不记。</div>
    </div>
    <div v-if="!available" class="err">
      采集事件不可用：{{ reason }}<br>
      这只影响事后追溯，采集与染色不受影响。
    </div>
    <div v-else-if="loading" class="empty">加载中…</div>
    <div v-else-if="!rows.length" class="empty">还没有状态变化 —— 平台起来之后一直是同一个状态</div>
    <div v-else class="tbl-wrap">
      <table class="tbl" data-testid="event-table">
        <thead><tr>
          <th style="width:190px">时间</th><th style="width:140px">变成</th>
          <th style="width:110px">持续</th><th>原因</th>
        </tr></thead>
        <tbody>
          <tr v-for="(e, i) in withGap" :key="e.at + i" data-testid="event-row" :data-status="e.status">
            <td class="mono">{{ new Date(e.at).toLocaleString() }}</td>
            <td><span class="tag" :class="statusOf(e.status).cls">{{ statusOf(e.status).text }}</span></td>
            <td class="mono">{{ e.lasted }}{{ e.ongoing ? '（至今）' : '' }}</td>
            <td style="white-space:normal">{{ e.detail || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</div>`
};

/** 把毫秒说成人话。精确到秒没有意义 —— 人要的是「几分钟」还是「几小时」 */
function humanize(ms) {
  const s = Math.round(ms / 1000);
  if (s < 60) return s + ' 秒';
  if (s < 3600) return Math.round(s / 60) + ' 分钟';
  if (s < 86400) return (s / 3600).toFixed(1) + ' 小时';
  return (s / 86400).toFixed(1) + ' 天';
}
