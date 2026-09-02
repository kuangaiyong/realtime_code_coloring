import { store } from '../store.js';
// 与「服务接入」页共用同一份语言定义，见 onboard-data.js 的说明
import { OB } from './onboard-data.js';

const { computed, ref, watch } = Vue;

/**
 * 接入帮助 —— 「服务接入」页搬出来的那些说明。
 *
 * <b>为什么要分成两页：</b>接入页原先是一屏文档裹着一个表单 ——
 * 4 处风险提示、19 处字段说明、4 处语言坑、1 段配置说明，两千多字。
 * 要接入的人在那一屏里找不到「我该填什么」，而想读明白原理的人
 * 也不需要每次都从表单缝里读。
 *
 * <b>分法是按「要操作的」与「要读的」</b>：接入页只留能点能填的东西
 * （表单、命令、配置片段、物料下载、自检表），这一页承接全部说明。
 *
 * <b>唯一的例外是那些「填错了看不出来」的提示</b>：它们留在接入页，
 * 但降级成字段旁的 ⓘ 悬停。理由与它们本身是同一条 —— 人在<b>填</b>的那一刻
 * 看不到，等于没写。搬到这一页就成了「读过的人才知道」，
 * 而这一页存在的理由恰恰是不必读完才能接入。
 *
 * 这一页<b>不是 scoped</b>：它不显示任何覆盖数字，口径栏不该出现在这里。
 */
export const Help = {
  setup() {
    const lang = ref('java');

    // 深链接：接入页每种语言旁那个「查看详细说明」跳的是 #/p/<id>/help/<lang>。
    // 不接住 routeArg 的话，从接入页跳过来永远落在 Java 那一节，
    // 而人是带着「我要看 Rust 的」这个意图点过来的。
    //
    // <b>必须 watch，不能只在 onMounted 里读一次</b>：本来就在帮助页时再点一个
    // 别的语言，hash 变了但组件不重新挂载 —— onMounted 版本只在「整页加载」这一条
    // 路径上生效，先打开过帮助页的人反而跳不动，而那恰恰是更常见的走法。
    // immediate 保证首次挂载也照样跑。
    watch(() => store.routeArg, (arg) => {
      if (arg && OB[arg]) lang.value = arg;
    }, { immediate: true });

    const o = computed(() => OB[lang.value]);

    /** 这门语言的字段说明。接入页只留 ⓘ，完整版在这里按「被测机器 / 平台机器」分组列全 */
    const targetFields = computed(() =>
      (o.value.fields || []).filter(f => f.side === 'target'));
    const platformFields = computed(() =>
      (o.value.fields || []).filter(f => f.side === 'platform'));

    return { lang, o, OB, targetFields, platformFields };
  },
  template: `
<div class="view" data-testid="view-help">
  <div class="card">
    <div class="card-head">
      <h2>接入帮助</h2>
      <span class="sub">接入页只放要填的，原理与坑都在这里</span>
    </div>
    <div class="lang-tabs">
      <button v-for="(v, k) in { java: 'Java', go: 'Go', cpp: 'C++', rust: 'Rust' }" :key="k"
              :class="{ on: lang === k }" :data-testid="'hp-lang-' + k" @click="lang = k">{{ v }}</button>
    </div>
    <div class="ob" data-testid="hp-body">
      <div class="note" :class="o.note[0]" v-html="o.note[1]"></div>

      <h3>要填的参数</h3>
      <!-- 两类路径必须分开讲。平台去 exists() 一个被测机器上的路径，
           本机恰好也有同名目录就报「存在」，没有就报「不存在」——
           两种回答都可能是错的，而错的那种看起来完全正常 -->
      <h4 class="side-h target">在<b>被测服务那台机器</b>上（平台校验不了，也不该校验）</h4>
      <ul>
        <li v-for="f in targetFields" :key="f.key">
          <code>{{ f.label }}</code>
          <span v-if="f.optional" class="opt">可选</span>
          <span v-if="f.hint"> —— <span v-html="f.hint"></span></span>
        </li>
      </ul>
      <h4 class="side-h platform">在<b>平台这台机器</b>上（会进项目配置，保存时由 /api/projects/check 校验）</h4>
      <ul>
        <li v-for="f in platformFields" :key="f.key">
          <code>{{ f.cfgKey }}</code> —— <span v-html="f.hint"></span>
        </li>
      </ul>

      <h3>把地址填进项目配置</h3>
      <div class="note info">平台<b>没有注册中心</b>，实例不会自己上报 —— 地址写在项目配置的
        <code>instances</code> 里。首次启动时以 <code>application.yml</code> 为种子写进数据库，
        <b>此后以库里那份为准，改完即时生效、不必重启平台</b>。不写语言前缀默认按 <code>java</code> 解析。
        <br>「服务接入」页只<b>算</b>出这份配置给你看，<b>不保存</b> ——
        保存入口只有「项目设置」一处，两个地方都能改的话，改出不同的值时没人看得出是哪一处生效了。</div>

      <h3>平台侧还要配这几项</h3>
      <ul>
        <li v-for="c in o.cfg" :key="c[0]"><code>{{ c[0] }}</code> —— <span v-html="c[1]"></span></li>
      </ul>

      <h3>这门语言特有的坑</h3>
      <ul>
        <li v-if="!o.tips.length">没有额外的坑 —— Java 是四种语言里唯一产物也不用动的。</li>
        <li v-for="(t, n) in o.tips" :key="n" v-html="t"></li>
      </ul>

      <h3>四种语言都适用的两条</h3>
      <ul>
        <li><b>各实例的构建版本必须完全一致（含 <code>-dirty</code> 后缀）。</b>
          commit 相同但一台脏一台净，加载的是两份不同的字节码，平台会判为版本冲突并拒绝出增量报告。
          脏标记要按全部被测源码根一起判定，各语言各算各的会把人引去核对版本。</li>
        <li><b>探针端口没有任何鉴权。</b>只绑内网地址，靠网络策略限制来源；本平台只面向测试环境。</li>
      </ul>
    </div>
  </div>
</div>`
};
