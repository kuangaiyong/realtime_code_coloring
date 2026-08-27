import { api, pctClass } from '../api.js';

const { computed, ref, onMounted, onUnmounted } = Vue;

/**
 * 项目列表 —— 平台的首页。
 *
 * <b>为什么列表要带上配置列（仓库目录 / 基线 / 实例数），而不只是状态：</b>
 * 多项目下最常问的一句话是「这个项目的基线配的是哪个」。只给状态的话，
 * 每问一次都要点进去看一眼；列出来就不用。
 *
 * <b>为什么「采不到数据」要摆在第一屏：</b>建好了却没数据是这类平台最常见的困惑，
 * 而它的成因几乎都在配置里。列表上直接把探针状态和原因摊开，比让人自己去翻日志强。
 */
const STATUS = {
  CONNECTED: { text: '正常', cls: 'ok' },
  PARTIAL: { text: '部分实例掉线', cls: 'warn' },
  ARCHIVED: { text: '场景快照', cls: 'ok' },
  DISCONNECTED: { text: '探针不可达', cls: 'err' },
  ANALYZE_ERROR: { text: '分析失败', cls: 'err' },
  CONFIG_ERROR: { text: '配置有误', cls: 'err' }
};

export const Projects = {
  emits: ['open', 'create', 'edit'],
  setup(props, { emit }) {
    const rows = ref([]);
    const defaultId = ref('default');
    const keyword = ref('');
    const loading = ref(false);
    const error = ref(null);

    async function load() {
      loading.value = true;
      try {
        const d = await api.get('/api/projects');
        rows.value = d.projects;
        defaultId.value = d.defaultId;
        error.value = null;
      } catch (e) {
        error.value = e.message;
      } finally {
        loading.value = false;
      }
    }

    // 列表上的探针状态是活的：配置刚改完、服务刚重启，人会盯着这一屏等它变绿
    let timer = null;
    onMounted(() => {
      load();
      timer = setInterval(load, 5000);
    });
    onUnmounted(() => clearInterval(timer));

    const filtered = computed(() => {
      const k = keyword.value.trim().toLowerCase();
      if (!k) return rows.value;
      return rows.value.filter(r =>
        r.id.toLowerCase().includes(k)
        || String(r.name || '').toLowerCase().includes(k)
        || String(r.repoDir || '').toLowerCase().includes(k));
    });

    function statusOf(r) {
      // 装载失败的项目没有运行时，服务端给不出探针状态。这不是「探针不可达」——
      // 是这个项目压根没跑起来，得去改配置，方向完全不同
      if (!r.probeStatus) return { text: '未装载', cls: 'err' };
      return STATUS[r.probeStatus] || { text: r.probeStatus, cls: 'warn' };
    }

    async function remove(r) {
      try {
        await ElementPlus.ElMessageBox.confirm(
          `删除项目「${r.name}」（${r.id}）？它的覆盖快照与场景归档会一并消失，趋势历史保留。`,
          '删除项目', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
      } catch (e) {
        return; // 点了取消
      }
      try {
        await api.del('/api/projects/' + encodeURIComponent(r.id));
        ElementPlus.ElMessage.success('已删除 ' + r.id);
        await load();
      } catch (e) {
        // 服务端的 4xx 都附带一句为什么，原样带出来 —— 换成「删除失败」等于把线索丢了
        ElementPlus.ElMessage.error(e.message);
      }
    }

    return { rows, filtered, keyword, loading, error, defaultId, statusOf, remove, load,
      pctClass, emit };
  },
  template: `
<div class="view" data-testid="view-projects">
  <div class="card">
    <div class="card-head">
      <h2>项目列表</h2>
      <span class="sub" data-testid="project-count">
        {{ filtered.length === rows.length ? rows.length + ' 个' : filtered.length + ' / ' + rows.length + ' 个' }}
      </span>
      <el-input v-model="keyword" size="small" clearable style="width:220px;margin-left:12px"
                placeholder="按名称 / 标识 / 仓库过滤" data-testid="project-filter" />
      <el-button size="small" type="primary" data-testid="btn-new-project"
                 @click="emit('create')">+ 新建项目</el-button>
    </div>

    <div v-if="error" class="err">项目列表取不到：{{ error }}</div>
    <div v-else-if="!rows.length" class="empty">{{ loading ? '加载中…' : '还没有任何项目' }}</div>
    <div v-else-if="!filtered.length" class="empty">没有匹配「{{ keyword }}」的项目</div>
    <div v-else class="tbl-wrap">
      <table class="tbl" data-testid="project-table">
        <thead><tr>
          <th>项目</th><th>Git 仓库目录</th><th>基线</th><th style="width:70px">实例</th>
          <th style="width:130px">状态</th><th style="width:90px">覆盖率</th>
          <th style="width:130px">最后采集</th><th style="width:230px">操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="r in filtered" :key="r.id" data-testid="project-row" :data-id="r.id">
            <td>
              <span class="rank-name" data-testid="project-open" @click="emit('open', r)">{{ r.name }}</span>
              <span v-if="r.id === defaultId" class="tag ok" style="margin-left:6px"
                    title="不带项目参数的旧接口都落在它上面，CI 门禁打的就是这个">默认</span>
              <div class="mono" style="color:var(--el-text-color-secondary)">{{ r.id }}</div>
            </td>
            <td class="mono">{{ r.repoDir || '—' }}</td>
            <td class="mono">{{ r.baseline || '—' }}</td>
            <td class="mono">{{ r.instanceCount }}</td>
            <td>
              <span class="tag" :class="statusOf(r).cls">{{ statusOf(r).text }}</span>
              <!-- 原因必须跟着状态走：只说「配置有误」而不说哪一项，等于让人去翻日志 -->
              <div v-if="r.lastError" class="mono" style="color:var(--el-color-danger);white-space:normal"
                   :title="r.lastError">{{ r.lastError.slice(0, 40) }}{{ r.lastError.length > 40 ? '…' : '' }}</div>
            </td>
            <td>
              <span v-if="r.overallRatio === null || r.overallRatio === undefined" class="mono">—</span>
              <span v-else class="pc mono" :class="pctClass(r.overallRatio)">{{ r.overallRatio }}%</span>
            </td>
            <td class="mono">{{ r.lastCollectedAt ? new Date(r.lastCollectedAt).toLocaleTimeString() : '—' }}</td>
            <td class="actions">
              <el-button size="small" data-testid="btn-open" @click="emit('open', r)">进入</el-button>
              <el-button size="small" data-testid="btn-edit" @click="emit('edit', r)">配置</el-button>
              <!-- 默认项目删不掉，服务端也会拦（409）。这里先把按钮禁掉，
                   免得人点了才被告知不行 -->
              <el-button size="small" type="danger" plain data-testid="btn-delete"
                         :disabled="r.id === defaultId"
                         :title="r.id === defaultId ? '默认项目不能删除：不带项目参数的旧接口都落在它上面' : ''"
                         @click="remove(r)">删除</el-button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</div>`
};
