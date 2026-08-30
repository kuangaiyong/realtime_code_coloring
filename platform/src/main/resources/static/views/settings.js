import { api, LANG } from '../api.js';
import { BaselineField } from '../components/baseline-field.js';

const { computed, reactive, ref, onMounted } = Vue;

/**
 * 项目设置 —— 阶段 2 那套「改完即生效」的能力在页面上的入口。
 *
 * <b>为什么不复用向导的分步形态：</b>向导解决的是「第一次配，不知道该填什么」；
 * 改配置解决的是「就想调一个阈值」。让人为了改一个数走六步是折磨。
 * 所以这里一屏铺开、分组显示，随改随存。
 *
 * <b>为什么保存之前必须能先自检：</b>配置错了不会报错，只会让覆盖率莫名其妙偏低。
 * 存完才发现不对，中间那段时间的数据已经是错的了。
 *
 * 三类失败要分开说，它们的下一步动作完全不同：
 *   400 你填错了 → 改表单；409 现在不能做（有场景在跑）→ 先结束场景；
 *   503 平台依赖挂了 → 找人看数据库，跟你填的没关系。
 */
const GROUPS = [
  { title: '基本', fields: [['name', '项目名', 'text']] },
  // baseline 不走这张表：它要按仓库列出候选，见模板里的 baseline-field
  { title: '代码仓库', fields: [['repoDir', 'Git 仓库目录', 'text']] },
  { title: '采集', fields: [['timeoutMs', '探针读取超时 (ms)', 'number']] }
];

/** 语言相关的字段只在配了该语言的实例时才显示，与向导第 4 步同一套判据 */
const PATH_FIELDS = {
  java: [['classesDir', 'Java 产物目录'], ['javaSourceRoot', 'Java 源码根']],
  go: [['goSourceRoot', 'Go 源码根'], ['goModulePath', 'Go 模块路径']],
  cpp: [['cppSourceRoot', 'C++ 源码根'], ['cppObjectsDir', 'C++ 对象目录']],
  rust: [['rustSourceRoot', 'Rust 源码根'], ['rustBinary', 'Rust 产物']]
};

export const Settings = {
  components: { BaselineField },
  props: { projectId: { type: String, required: true } },
  emits: ['saved', 'back'],
  setup(props, { emit }) {
    const cfg = reactive({ gate: {} });
    const rows = reactive([]);
    const loading = ref(true);
    const busy = ref(false);
    const loadError = ref(null);
    const items = ref([]);

    onMounted(async () => {
      try {
        const d = await api.get('/api/projects/' + encodeURIComponent(props.projectId));
        Object.assign(cfg, d);
        cfg.gate = Object.assign({ incrementalThreshold: 80, overallThreshold: 0 }, d.gate || {});
        rows.splice(0, rows.length, ...(d.instances || []).map(spec => {
          const [lang, hp] = String(spec).includes('://') ? spec.split('://') : ['java', spec];
          const at = hp.lastIndexOf(':');
          return { lang, host: hp.slice(0, at), port: Number(hp.slice(at + 1)) };
        }));
      } catch (e) {
        loadError.value = e.message;
      } finally {
        loading.value = false;
      }
    });

    const instances = computed(() =>
      rows.filter(r => r.host && r.port).map(r => r.lang + '://' + r.host + ':' + r.port));
    const languages = computed(() => [...new Set(rows.map(r => r.lang))]);
    const pathFields = computed(() =>
      languages.value.flatMap(l => (PATH_FIELDS[l] || []).map(f => [l, ...f])));

    function payload() {
      return Object.assign({}, cfg, { instances: instances.value });
    }

    async function check() {
      busy.value = true;
      try {
        const d = await api.post('/api/projects/check', payload());
        items.value = d.items;
        ElementPlus.ElMessage[d.ok ? 'success' : 'warning'](
          d.ok ? '自检全过' : '自检有不通过项，见下表');
      } catch (e) {
        ElementPlus.ElMessage.error('自检失败：' + e.message);
      } finally {
        busy.value = false;
      }
    }

    async function save() {
      busy.value = true;
      try {
        await api.put('/api/projects/' + encodeURIComponent(props.projectId), payload());
        ElementPlus.ElMessage.success('已保存，新配置立即生效（不必重启平台）');
        emit('saved', props.projectId);
      } catch (e) {
        // 服务端的每个 4xx/5xx 都附带一句为什么，原样带出来；
        // 换成「保存失败」等于把「先结束场景」这种可操作的提示丢掉
        ElementPlus.ElMessage.error({ message: e.message, duration: 6000 });
      } finally {
        busy.value = false;
      }
    }

    function addRow() { rows.push({ lang: 'java', host: '127.0.0.1', port: 6300 }); }
    function removeRow(i) { rows.splice(i, 1); }

    return { GROUPS, LANG, cfg, rows, loading, busy, loadError, items,
      instances, languages, pathFields, check, save, addRow, removeRow, emit };
  },
  template: `
<div class="view" data-testid="view-settings">
  <div v-if="loadError" class="card"><div class="err">项目配置取不到：{{ loadError }}</div></div>
  <div v-else-if="loading" class="card"><div class="empty">加载中…</div></div>
  <template v-else>
    <div class="card">
      <div class="card-head">
        <h2>项目设置</h2>
        <span class="sub mono">{{ cfg.id }}</span>
      </div>
      <div class="ob">
        <div class="note info">改完<b>立即生效，不需要重启平台</b>。
          有场景正在进行时保存会被拒（409）—— 那个场景的计数器窗口是在旧配置下开的，
          跨配置定格出来的归因没有意义。</div>

        <template v-for="g in GROUPS" :key="g.title">
          <h3>{{ g.title }}</h3>
          <div v-for="f in g.fields" :key="f[0]" class="fld">
            <label>{{ f[1] }}</label>
            <el-input v-if="f[2] === 'number'" v-model.number="cfg[f[0]]" :data-testid="'st-' + f[0]" />
            <el-input v-else v-model="cfg[f[0]]" :data-testid="'st-' + f[0]" />
          </div>
          <!-- 「默认基线」四个字不解释任何事情：它只回答「跟哪个版本比」，
               而增量覆盖率的分母就是从那个版本到现在改过的可执行行 -->
          <div v-if="g.title === '代码仓库'" class="fld">
            <label>增量基线<br><small>跟哪个版本比</small></label>
            <baseline-field v-model="cfg.baseline" :repo-dir="cfg.repoDir" testid="st-baseline" />
          </div>
        </template>

        <h3>被测实例</h3>
        <div class="tbl-wrap">
          <table class="tbl" data-testid="st-instances">
            <thead><tr><th style="width:120px">语言</th><th style="width:320px">主机</th><th style="width:120px">端口</th><th></th></tr></thead>
            <tbody>
              <tr v-for="(r, i) in rows" :key="i" data-testid="st-inst-row">
                <td>
                  <el-select v-model="r.lang" size="small">
                    <el-option v-for="(n, k) in LANG" :key="k" :value="k" :label="n" />
                  </el-select>
                </td>
                <td><el-input v-model="r.host" size="small" :data-testid="'st-inst-host-' + i" /></td>
                <td><el-input v-model.number="r.port" size="small" :data-testid="'st-inst-port-' + i" /></td>
                <td><el-button size="small" text type="danger" :disabled="rows.length === 1"
                               @click="removeRow(i)">删除</el-button></td>
              </tr>
            </tbody>
          </table>
        </div>
        <el-button size="small" style="margin-top:10px" data-testid="st-add-inst" @click="addRow">+ 添加实例</el-button>

        <h3>语言与产物<span style="font-weight:normal;color:var(--el-text-color-secondary)">
          （按上面配到的语言显示：{{ languages.map(l => LANG[l]).join('、') }}）</span></h3>
        <div v-for="f in pathFields" :key="f[1]" class="fld">
          <label>{{ LANG[f[0]] }} · {{ f[2] }}</label>
          <el-input v-model="cfg[f[1]]" :data-testid="'st-' + f[1]" />
        </div>

        <h3>覆盖门禁</h3>
        <div class="fld"><label>增量覆盖率阈值 (%)</label>
          <el-input v-model.number="cfg.gate.incrementalThreshold" data-testid="st-gate-inc" /></div>
        <div class="fld"><label>全量覆盖率阈值 (%)</label>
          <el-input v-model.number="cfg.gate.overallThreshold" data-testid="st-gate-full" /></div>
      </div>

      <div class="wz-foot">
        <el-button data-testid="st-back" @click="emit('back')">返回</el-button>
        <span class="spacer"></span>
        <el-button :loading="busy" data-testid="st-check" @click="check">自检一下</el-button>
        <el-button type="primary" :loading="busy" data-testid="st-save" @click="save">保存并生效</el-button>
      </div>
    </div>

    <div v-if="items.length" class="card">
      <div class="card-head"><h2>自检结果</h2></div>
      <div class="tbl-wrap">
        <table class="tbl" data-testid="st-check-table">
          <thead><tr><th style="width:220px">检查项</th><th style="width:80px">结果</th><th>说明</th></tr></thead>
          <tbody>
            <tr v-for="it in items" :key="it.name">
              <td>{{ it.label }}</td>
              <td><span class="tag" :class="it.ok ? 'ok' : 'err'">{{ it.ok ? '通过' : '不通过' }}</span></td>
              <td style="white-space:normal">{{ it.detail }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </template>
</div>`
};
