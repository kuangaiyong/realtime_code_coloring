import { store, loadPerInstance } from '../store.js';
import { esc, copyText, LANG, pctClass } from '../api.js';

const { computed, ref, watch } = Vue;

/**
 * 服务接入向导。
 *
 * 向导的价值不在于复述文档，而在于两点：命令里的端口就是本平台实际配置的那个，
 * 以及最后一步「连上没有」由平台自己回答。所以正文按 summary 里的 instances 现算，
 * 不写死任何端口。
 */
const OB = {
  java: {
    name: 'Java', port: 6300,
    note: ['info', '<b>无需重新编译，也无需改动源码。</b>JaCoCo 在类加载时做字节码插桩。' +
      '但探针必须随 JVM 一起启动 —— JaCoCo 官方明确不支持对已运行的进程动态挂载' +
      '（插桩会给类添加静态字段，违反 JVM 类重定义的约束）。'],
    cmd: hp => [
      ['cm', '# 工作树脏时必须带 -dirty：少了它，实例会自报一个干净的 commit，'],
      ['cm', '# 平台的版本一致性校验被绕过，算出来的是一份行号错位却看不出异常的增量报告'],
      ['', 'BUILD_ID=$(git rev-parse HEAD)$(git status --porcelain | grep -q . && echo -dirty)'],
      ['', ''],
      ['cm', '# includes 只填你自己的包，范围越小开销越小'],
      ['cm', '# sessionid 是实例自报的构建版本，不配它增量口径不可用'],
      ['cm', '# 参数串不折行：中间断开会被 shell 当成两条命令'],
      ['', 'java -javaagent:/path/to/jacocoagent.jar=includes=com.example.*,output=tcpserver,' +
           '@@address=' + hp.host + ',port=' + hp.port + '@@,sessionid=$BUILD_ID' +
           ' -jar your-service.jar']
    ],
    cfg: [
      ['classes-dir', '被测服务的 .class 产物。缺了它解不出任何行号'],
      ['java-source-root', '源码根，用于渲染染色视图']
    ],
    tips: []
  },
  go: {
    name: 'Go', port: 6400,
    note: ['risk', '<b>必须带插桩重新编译一次，但业务源码一行不改。</b>' +
      'Go 编译为原生机器码，运行期没有可改写的中间表示。探针 <code>coverage_agent.go</code> 与 main 同包、' +
      '由 build tag 守卫，<code>init()</code> 自动执行；不带 tag 的生产构建里它根本不参与编译。'],
    cmd: hp => [
      ['cm', '# 工作树脏时必须带 -dirty：少了它，实例会自报一个干净的 commit，'],
      ['cm', '# 平台的版本一致性校验被绕过，算出来的是一份行号错位却看不出异常的增量报告'],
      ['', 'BUILD_ID=$(git rev-parse HEAD)$(git status --porcelain | grep -q . && echo -dirty)'],
      ['', ''],
      ['cm', '# -covermode=atomic 是硬要求：场景清零用的 ClearCounters() 只在 atomic 模式下可用'],
      ['', 'go build -cover -covermode=atomic -tags=goverage -o your-service .'],
      ['', ''],
      ['cm', '# COVERAGE_ADDR 默认只绑回环。写成 :' + hp.port + ' 会绑到所有网卡，'],
      ['cm', '# 而 /coverage/clear 能清零计数器 —— 等于把正在录的场景交给同网段任何人随手作废'],
      ['', 'COVERAGE_ADDR=@@' + hp.host + ':' + hp.port + '@@ COVERAGE_BUILD_ID=$BUILD_ID ./your-service']
    ],
    cfg: [
      ['go-source-root', 'Go 源码根'],
      ['go-module-path', 'go.mod 里的 module 路径，用于把 profile 的包名映射回文件'],
      ['coverage.go-tool', '平台自己要调 <code>go tool covdata textfmt</code> 做归一化。' +
        '这项是<b>平台级</b>配置（跟着机器走），仍在 application.yml 里，默认取 PATH']
    ],
    tips: ['Go 是块模型而非探针模型：块尾的 <code>}</code> 与块内注释会计入行数，' +
      '同一份代码的行数分母比 Java 口径略大。空行已剔除，不会挤进增量分母。']
  },
  cpp: {
    name: 'C++', port: 6500,
    note: ['risk', '<b>必须重新编译；业务源码同样一行不改。</b>' +
      '探针 <code>coverage_agent.cpp</code> 是独立编译单元，靠全局对象的构造函数（早于 main 执行）自动启动，' +
      '业务代码不 include 也不调用它任何东西。不依赖 LD_PRELOAD，也不用 SIGUSR1，Windows 上同样可用。'],
    cmd: hp => [
      ['cm', '# 工作树脏时必须带 -dirty：少了它，实例会自报一个干净的 commit，'],
      ['cm', '# 平台的版本一致性校验被绕过，算出来的是一份行号错位却看不出异常的增量报告'],
      ['', 'BUILD_ID=$(git rev-parse HEAD)$(git status --porcelain | grep -q . && echo -dirty)'],
      ['', ''],
      ['cm', '# 业务代码插桩、探针不插桩。编译的工作目录必须是源码根（.gcno 记的是相对源码名），'],
      ['cm', '# 对象文件必须用绝对路径 —— .gcda 的落点是编译期写死进产物的'],
      ['', 'g++ -std=c++17 --coverage -c order.cpp -o /build/obj/order.o'],
      ['', 'g++ -std=c++17            -c coverage_agent.cpp -o /build/obj/coverage_agent.o'],
      ['cm', '# 探针要开 socket：Windows 上需显式链 -lws2_32，Linux 上不用'],
      ['', 'g++ -o your-service /build/obj/*.o --coverage -lws2_32'],
      ['', ''],
      ['cm', '# GCOV_PREFIX 每实例一个目录，否则两个实例往同一个 .gcda 互相覆盖，'],
      ['cm', '# 聚合出来的是「最后写的那一份」'],
      ['', 'GCOV_PREFIX=/var/cover/svc-1 GCOV_PREFIX_STRIP=99 COVERAGE_DATA_DIR=/var/cover/svc-1 \\'],
      ['', '  COVERAGE_ADDR=@@' + hp.host + ':' + hp.port + '@@ COVERAGE_BUILD_ID=$BUILD_ID ./your-service']
    ],
    cfg: [
      ['cpp-objects-dir', '.gcno 所在目录（编译期产物）。相当于 Java 的 classes-dir，缺了它解不出行号'],
      ['cpp-source-root', '源码根，平台侧的 gcov 要在这个目录下才找得到源码'],
      ['coverage.gcov-tool / gcov-merge-tool', '平台要调 <code>gcov -t -r</code> 与 <code>gcov-tool merge</code>。' +
        '这两项是<b>平台级</b>配置（跟着机器走），仍在 application.yml 里，默认取 PATH']
    ],
    tips: ['<code>GCOV_PREFIX_STRIP=99</code> 把目录层级剥光，不同目录下的同名 .cpp 会撞车。' +
      '接大型工程时要改成保留目录结构，并相应改 <code>gcov -o</code> 的调用方式。']
  },
  rust: {
    name: 'Rust', port: 6600,
    note: ['risk', '<b>必须重新编译；源码零改动做得比 Go / C++ 还彻底 —— 连 Cargo.toml 都不用动。</b>' +
      '探针是单独用 gcc 编译的 <code>.o</code>，构建时经 <code>-C link-arg</code> 注入，' +
      '靠 <code>.CRT$XCU</code> 段里的函数指针在 main 之前自动执行。既没走连续同步模式（%c），也没引入 minicov 依赖。'],
    cmd: hp => [
      ['cm', '# 工作树脏时必须带 -dirty：少了它，实例会自报一个干净的 commit，'],
      ['cm', '# 平台的版本一致性校验被绕过，算出来的是一份行号错位却看不出异常的增量报告'],
      ['', 'BUILD_ID=$(git rev-parse HEAD)$(git status --porcelain | grep -q . && echo -dirty)'],
      ['', ''],
      ['cm', '# 探针单独编成 .o 经 link-arg 注入；Cargo.toml 一行不动'],
      ['', 'gcc -c coverage_agent.c -o /build/obj/coverage_agent.o -mno-stack-arg-probe -O1'],
      ['cm', '# Windows 必须编成 msvc 目标（gnu 目标上 -C instrument-coverage 直接 E0463），'],
      ['cm', '# 因此还要 -L native= 指向 xwin 拉来的 CRT/SDK 导入库并换用 lld-link。'],
      ['cm', '# Linux 上把 --target 与 -L / -C linker 那几项去掉即可，其余不变'],
      ['', 'RUSTFLAGS="-C instrument-coverage -C link-arg=/build/obj/coverage_agent.o \\'],
      ['', '  -C link-arg=ws2_32.lib \\'],
      ['', '  -L native=<xwin>/crt/lib/x86_64 -L native=<xwin>/sdk/lib/um/x86_64 \\'],
      ['', '  -L native=<xwin>/sdk/lib/ucrt/x86_64 \\'],
      ['', '  -C linker=<toolchain>/lib/rustlib/x86_64-pc-windows-gnu/bin/rust-lld.exe \\'],
      ['', '  -C linker-flavor=lld-link" \\'],
      ['', '  cargo build --release --target x86_64-pc-windows-msvc'],
      ['', ''],
      ['cm', '# LLVM_PROFILE_FILE 每实例一个文件，且必须是字面路径 —— %p / %m 之类的模式由 LLVM 展开，'],
      ['cm', '# 探针按同一个字符串删不掉旧文件，交回的就成了历次累计的叠加，界面上看不出异样'],
      ['', 'LLVM_PROFILE_FILE=/var/cover/svc-1.profraw \\'],
      ['', '  COVERAGE_ADDR=@@' + hp.host + ':' + hp.port + '@@ COVERAGE_BUILD_ID=$BUILD_ID ./target/release/your-service']
    ],
    cfg: [
      ['rust-binary', '指向产物本身 —— 行号信息在它自带的 coverage mapping 里'],
      ['rust-source-root', 'Rust 源码根'],
      ['coverage.llvm-profdata-tool / llvm-cov-tool', '版本必须与 rustc 匹配，' +
        '系统上随便一个 LLVM 往往对不上，会以「不认识的 profraw 版本」失败。取 rustup 的 llvm-tools 组件最稳。' +
        '这两项是<b>平台级</b>配置（跟着机器走），仍在 application.yml 里']
    ],
    tips: ['Windows 上必须编成 <code>x86_64-pc-windows-msvc</code>：官方只给 msvc 目标发 profiler_builtins，' +
      'gnu 目标上 <code>-C instrument-coverage</code> 直接 E0463。',
      'Rust 是三态（无 PARTIAL）：<code>llvm-cov export</code> 不输出 BRDA 分支记录。']
  }
};

const OB_HINT = {
  java: '确认启动参数带了 <code>-javaagent:...=output=tcpserver</code> 且端口一致，探针随 JVM 启动、不能事后挂载',
  go: '确认服务是用 <code>-cover -covermode=atomic -tags=goverage</code> 重新编译并重启过的',
  cpp: '确认服务是用 <code>--coverage</code> 重新编译、且链接了 coverage_agent 的那个产物',
  rust: '确认服务是用 <code>-C instrument-coverage</code> 重新编译、且注入了探针 .o 的那个产物'
};

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

    const cmdLines = computed(() => o.value.cmd(hp.value));

    /**
     * 命令块走 v-html 而非模板插值：@@ 之间那段是从平台配置里取出来的实际值，
     * 要标成蓝色让人知道哪里是真的、哪里是占位；而 <pre> 里的换行不能交给
     * Vue 的模板编译（whitespace: condense 会动它），只能自己拼好 HTML。
     * 拼进去的每一段都过 esc()。
     */
    const cmdHtml = computed(() => cmdLines.value.map(([cls, t]) => {
      const body = esc(t).split('@@')
        .map((x, n) => n % 2 ? '<span class="val">' + x + '</span>' : x).join('');
      return cls ? '<span class="' + cls + '">' + body + '</span>' : body;
    }).join('\n'));

    const rawCmd = computed(() => cmdLines.value.map(([, t]) => t.replace(/@@/g, '')).join('\n'));

    const epNote = computed(() => eps.value.length
      ? '蓝色部分取自本平台的项目配置，是实际在监听的地址。'
        + (eps.value.length > 1 ? '本平台还配了 '
          + eps.value.slice(1).map(e => e.host + ':' + e.port).join('、')
          + '，第二个实例把地址换成它。' : '')
      : '本平台目前没有配置 ' + o.value.name + ' 实例，上面用的是默认端口。'
        + '实际接入时端口由你定，填进项目配置即可。');

    const ymlSnippet = computed(() => 'coverage:\n  instances:\n    - "'
      + (lang.value === 'java' ? '' : lang.value + '://') + hp.value.host + ':' + hp.value.port + '"');

    async function doCopy() {
      copyLabel.value = await copyText(rawCmd.value) ? '已复制' : '复制失败，请手动选中';
      setTimeout(() => { copyLabel.value = '复制'; }, 1600);
    }

    // ---- 接入自检 ----
    // 视图不可用时故意用 lastGood：触发「不可用」的典型原因就是增量返回 409
    // （某台实例脏了、或实例间版本不一致），而这张表正是唯一能点名「是哪一台」的地方
    const src = computed(() => store.summary || store.lastGood);
    const inst = computed(() => (src.value && src.value.instances) || []);

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
    <!-- 四步里只有最后一步是平台能自己判定的，前三步在被测方那边做完平台无从得知，
         所以不做「已完成」态 —— 打勾会变成一句没有依据的断言 -->
    <div class="steps">
      <div class="step"><span class="num">1</span>选择语言</div>
      <div class="step"><span class="num">2</span>改启动 / 构建参数</div>
      <div class="step"><span class="num">3</span>重启被测服务</div>
      <div class="step live"><span class="num">4</span>回这一页看探针连上没有</div>
    </div>
    <div class="lang-tabs">
      <button v-for="(v, k) in { java: 'Java', go: 'Go', cpp: 'C++', rust: 'Rust' }" :key="k"
              :class="{ on: lang === k }" :data-testid="'ob-lang-' + k" @click="lang = k">{{ v }}</button>
    </div>
    <div class="ob">
      <div class="note" :class="o.note[0]" v-html="o.note[1]"></div>

      <h3>1 · 改启动 / 构建参数</h3>
      <div class="snip-wrap">
        <button class="copy" @click="doCopy">{{ copyLabel }}</button>
        <pre class="snippet" data-testid="ob-cmd" v-html="cmdHtml"></pre>
      </div>
      <div style="font-size:12px;color:var(--el-text-color-secondary);margin-top:6px">{{ epNote }}</div>

      <h3>2 · 把地址填进项目配置</h3>
      <div class="note info">平台<b>没有注册中心</b>，实例不会自己上报 —— 地址写在项目配置的
        <code>instances</code> 里。首次启动时以 <code>application.yml</code> 为种子写进数据库，
        <b>此后以库里那份为准，改完即时生效、不必重启平台</b>。不写语言前缀默认按 <code>java</code> 解析。</div>
      <div class="snip-wrap"><pre class="snippet">{{ ymlSnippet }}</pre></div>

      <h3>3 · 平台侧还要配这几项</h3>
      <ul>
        <li v-for="c in o.cfg" :key="c[0]"><code>{{ c[0] }}</code> —— <span v-html="c[1]"></span></li>
      </ul>

      <h3>4 · 这门语言特有的坑</h3>
      <ul>
        <li v-if="!o.tips.length">没有额外的坑 —— Java 是四种语言里唯一产物也不用动的。</li>
        <li v-for="(t, n) in o.tips" :key="n" v-html="t"></li>
        <li><b>各实例的构建版本必须完全一致（含 <code>-dirty</code> 后缀）。</b>
          commit 相同但一台脏一台净，加载的是两份不同的字节码，平台会判为版本冲突并拒绝出增量报告。
          脏标记要按全部被测源码根一起判定，各语言各算各的会把人引去核对版本。</li>
        <li><b>探针端口没有任何鉴权。</b>只绑内网地址，靠网络策略限制来源；本平台只面向测试环境。</li>
      </ul>
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
              <td v-html="todo(i)"></td>
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
