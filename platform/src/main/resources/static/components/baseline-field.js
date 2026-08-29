import { api } from '../api.js';

const { computed, ref, watch } = Vue;

/**
 * 「增量基线」输入控件：<b>可以从仓库里选，也可以直接输入</b>。
 *
 * <b>为什么这个字段值得一个专门的控件：</b>它是整套配置里最难填的一项。
 * 人填不出来往往不是不懂概念，是不知道<b>这个仓库里有什么可填</b> ——
 * 主干叫 main 还是 master、有没有打 tag、tag 叫什么，都得先去翻仓库。
 *
 * <b>候选一律来自真实仓库（GET /api/git/baselines），前端不写死任何一项。</b>
 * 写死 main 的话，主干叫 master 的仓库会拿到一个选了就报错的选项 ——
 * 比不给建议更糟，因为人会以为是平台坏了。
 *
 * <b>但必须仍然能自由输入</b>（allow-create）：`v1.2.0^`、某个 sha、
 * 上游仓库里的引用，这些都合法却不会出现在候选里。只给下拉等于把能力砍掉一半。
 */
const KIND_LABEL = {
  remote: '远端分支',
  branch: '本地分支',
  tag: '标签',
  relative: '相对引用'
};

/** 各类候选的排列顺序，与后端给出的顺序一致；分组渲染要靠它保证稳定 */
const KIND_ORDER = ['remote', 'branch', 'tag', 'relative'];

export const BaselineField = {
  props: {
    modelValue: { type: String, default: '' },
    /** 仓库路径。它一变就要重新取候选：换个仓库，能填的东西完全不同 */
    repoDir: { type: String, default: '' },
    testid: { type: String, default: 'baseline-field' }
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const candidates = ref([]);
    /** 因名字不合法被服务端滤掉的个数。不说的话人会对着缺了自己那个分支的列表发愁 */
    const skipped = ref(0);
    const loading = ref(false);
    const reason = ref('');

    /**
     * 取候选。<b>取不到不报错</b>：仓库路径还没填完、或者压根不是 git 仓库时都会走到
     * 这里，而那一刻人正在<b>填</b>仓库路径。弹个红色错误只会让人以为自己已经填错了，
     * 所以安静地退回纯手输入，把原因留在下面那行小字里。
     */
    let seq = 0;
    async function load() {
      const dir = String(props.repoDir || '').trim();
      const mine = ++seq;
      if (!dir) {
        candidates.value = [];
        reason.value = '';
        skipped.value = 0;
        // 必须显式复位：飞行中那次请求的 finally 会被下面的 mine !== seq 跳过，
        // 不复位的话下拉就永远转圈、提示行永远停在「正在读取…」
        loading.value = false;
        return;
      }
      loading.value = true;
      try {
        const d = await api.get('/api/git/baselines?repoDir=' + encodeURIComponent(dir));
        // 连着改仓库路径时前一次请求可能后到，用它的候选盖掉新的
        if (mine !== seq) return;
        candidates.value = d.available ? d.candidates : [];
        skipped.value = d.skipped || 0;
        reason.value = d.available ? '' : d.error;
      } catch (e) {
        if (mine !== seq) return;
        candidates.value = [];
        skipped.value = 0;
        reason.value = e.message;
      } finally {
        if (mine === seq) loading.value = false;
      }
    }

    /**
     * 防抖。仓库路径是<b>一个字符一个字符敲出来</b>的，不防抖的话每敲一下服务端就
     * fork 一个 git 进程 —— 一条 30 字符的路径等于 30 次，而这台机器同时还在跑
     * 3 秒一轮的采集（各语言的归一化本来就要起外部进程）。
     * 首次不延迟：进到这一步时路径通常已经填好了（向导上一步填的、设置页读出来的），
     * 等 400ms 才出候选会被当成「这里没有建议」。
     */
    let timer = null;
    watch(() => props.repoDir, (v, old) => {
      clearTimeout(timer);
      if (old === undefined) { load(); return; }
      timer = setTimeout(load, 400);
    }, { immediate: true });

    /** 按类分组渲染。混在一起时「main」和「v1.2.0」看不出是两种东西 */
    const groups = computed(() => KIND_ORDER
      .map(k => ({ kind: k, label: KIND_LABEL[k], items: candidates.value.filter(c => c.kind === k) }))
      .filter(g => g.items.length));

    const hint = computed(() => {
      if (loading.value) return '正在读取仓库里的分支与标签…';
      if (reason.value) return '读不出这个仓库的分支与标签（' + reason.value + '），可以直接输入';
      // 「路径还没填」与「填了但这个仓库没有可选的」是两回事：不分开的话，
      // 后者会把人打发去做一件他已经做完的事
      if (!String(props.repoDir || '').trim()) {
        return '先填上面的仓库目录，这里就会列出可选的分支与标签';
      }
      if (!candidates.value.length) {
        return skipped.value
          ? '这个仓库里的 ' + skipped.value + ' 个引用名字含平台不支持的字符，都没能列出来 —— 请直接输入'
          : '这个仓库里没有可选的分支或标签（可能只有一个提交），可以直接输入';
      }
      const base = '可以从仓库里选，也可以直接输入任意 git ref（分支、标签、sha、HEAD~1、v1.2.0^）';
      // 滤掉的必须说：分支叫 feature/添加登录 的人会对着一个没有自己那个分支的列表发愁
      return skipped.value
        ? base + '。另有 ' + skipped.value + ' 个引用因名字含平台不支持的字符未列出'
        : base;
    });

    return { candidates, groups, hint, loading, skipped, KIND_LABEL, emit };
  },
  template: `
<div class="baseline-field">
  <!-- filterable + allow-create：候选之外的 ref（某个 sha、v1.2.0^、上游仓库的引用）
       同样合法，只给下拉等于把能力砍掉一半 -->
  <el-select :model-value="modelValue" filterable allow-create default-first-option
             :loading="loading" :data-testid="testid"
             placeholder="跟哪个版本比，例如 origin/main"
             style="width:100%"
             @update:model-value="v => emit('update:modelValue', v)">
    <el-option-group v-for="g in groups" :key="g.kind" :label="g.label">
      <el-option v-for="c in g.items" :key="c.kind + '/' + c.ref" :value="c.ref" :label="c.ref">
        <span>{{ c.ref }}</span>
        <span style="float:right;color:var(--el-text-color-secondary);font-size:12px">{{ c.detail }}</span>
      </el-option>
    </el-option-group>
  </el-select>
  <div class="fld-hint" :data-testid="testid + '-hint'">{{ hint }}</div>
</div>`
};
