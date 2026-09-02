/**
 * 四种语言的接入定义 —— 「服务接入」与「接入帮助」两个视图<b>共用这一份</b>。
 *
 * 为什么单独成文件：接入页只用 fields / cmd（要操作的东西），帮助页只用
 * note / cfg / tips（要读的东西），但它们描述的是同一件事。各存一份的话，
 * 改了命令却忘了改说明，页面上就会出现两套互相矛盾的接入步骤 ——
 * 而人照着哪一套做都可能接不上，且看不出是文档过期了。
 *
 * 零构建下多一个文件就多一次请求，本项目的惯例是「不到必要不拆」；
 * 这里的必要性就是上面那条：两个视图必须看到同一份定义。
 */

/**
 * 把「产物路径」渲染成可以直接执行的形式。
 *
 * 相对路径要加 <code>./</code>（POSIX shell 不搜当前目录），<b>绝对路径不能加</b> ——
 * 无条件拼的话，用户按同页其它字段的口径填了绝对路径，就会生成
 * <code>.//srv/app/svc</code> 这种东西，照抄执行找不到文件。
 * 高亮标记要先剥再判，否则带标记的绝对路径会被当成相对路径。
 */
/**
 * 高亮标记。<b>刻意用一个输入框里打不出来的控制字符</b>，而不是可见的字符串：
 * 标记是靠「奇数段普通、偶数段高亮」数出来的，用户只要填一个含该串的路径，
 * 此后所有段落就会错位，而复制时那句「剥掉全部标记」还会把他自己填的字符一并删掉 ——
 * 交出去的是一条被悄悄改坏的命令，正是这一页要消灭的那类错误。
 */
export const MARK = String.fromCharCode(1);

export function run(pathWithMark) {
  const bare = pathWithMark.split(MARK).join('');
  // POSIX 绝对路径、~、Windows 盘符路径，以及已经带了 ./ 或 ../ 的，都原样用。
  // 分隔符两种都要认：这一页的用户有一半在 Windows 上
  const asIs = /^([/~]|[A-Za-z]:[\\/]|\.{1,2}[\\/])/.test(bare);
  return asIs ? pathWithMark : './' + pathWithMark;
}

/**
 * 服务接入向导。
 *
 * 向导的价值不在于复述文档，而在于三点：命令里的端口就是本平台实际配置的那个，
 * 命令里的其余参数由用户自己填、当场算进去，以及最后一步「连上没有」由平台自己回答。
 * 所以正文按 summary 里的 instances 与表单现算，不写死任何端口，也不留需要手工替换的占位符。
 *
 * <b>fields 里的 side 是这一页最要紧的一处区分</b>：
 *
 * - <code>target</code> —— 这个路径在<b>被测服务那台机器</b>上（agent 路径、产物名、
 *   对象目录）。平台<b>不能</b>校验它：去 exists() 一下，本机恰好也有同名目录就报「存在」，
 *   没有就报「不存在」，<b>两种回答都可能是错的，而错的那种看起来完全正常</b>；
 * - <code>platform</code> —— 这个路径在<b>平台这台机器</b>上（classes-dir、各语言 source-root），
 *   它会进项目配置。校验交给「项目设置」保存时的 /api/projects/check，这一页不做。
 *
 * 带 cfgField 的字段就是要进项目配置的那些，值同时用于生成配置片段与跳设置页预填。
 * optional 的字段不计入「填齐了没有」（Rust 的 xwin / toolchain 只有 Windows 要填）。
 */
export const OB = {
  java: {
    name: 'Java', port: 6300,
    fields: [
      { key: 'pkg', label: '业务包名前缀', side: 'target', ph: 'com.example',
        hint: '填进 <code>includes</code>。<b>填宽了</b>框架类会进分母、覆盖率莫名偏低；' +
          '<b>填窄了</b>被测代码根本不插桩 —— 两种错都看不出是这里填的' },
      { key: 'agentJar', label: 'JaCoCo agent 路径', side: 'target', ph: '/path/to/jacocoagent.jar',
        hint: '被测服务那台机器上的路径。上面可以直接下载这份 agent，版本与平台解析用的一致' },
      { key: 'appJar', label: '被测服务的 jar', side: 'target', ph: 'your-service.jar',
        hint: '被测服务那台机器上的路径' },
      { key: 'classesDir', label: 'Java 产物目录', side: 'platform', cfgField: 'classesDir',
        cfgKey: 'classes-dir', ph: '/srv/app/target/classes',
        hint: '<b>平台</b>这台机器上的路径。缺了它解不出任何行号' },
      { key: 'javaSourceRoot', label: 'Java 源码根', side: 'platform', cfgField: 'javaSourceRoot',
        cfgKey: 'java-source-root', ph: 'demo-service/src/main/java',
        hint: '<b>平台</b>这台机器上的路径，用于渲染染色视图' }
    ],
    note: ['info', '<b>无需重新编译，也无需改动源码。</b>JaCoCo 在类加载时做字节码插桩。' +
      '但探针必须随 JVM 一起启动 —— JaCoCo 官方明确不支持对已运行的进程动态挂载' +
      '（插桩会给类添加静态字段，违反 JVM 类重定义的约束）。'],
    cmd: (hp, f) => [
      ['cm', '# 工作树脏时必须带 -dirty：少了它，实例会自报一个干净的 commit，'],
      ['cm', '# 平台的版本一致性校验被绕过，算出来的是一份行号错位却看不出异常的增量报告'],
      ['', 'BUILD_ID=$(git rev-parse HEAD)$(git status --porcelain | grep -q . && echo -dirty)'],
      ['', ''],
      ['cm', '# includes 只填你自己的包，范围越小开销越小'],
      ['cm', '# sessionid 是实例自报的构建版本，不配它增量口径不可用'],
      ['cm', '# 参数串不折行：中间断开会被 shell 当成两条命令'],
      ['', 'java -javaagent:' + f('agentJar') + '=includes=' + f('pkg') + '.*,output=tcpserver,' +
           MARK + 'address=' + hp.host + ',port=' + hp.port + MARK + ',sessionid=$BUILD_ID' +
           ' -jar ' + f('appJar')]
    ],
    cfg: [
      ['classes-dir', '被测服务的 .class 产物。缺了它解不出任何行号'],
      ['java-source-root', '源码根，用于渲染染色视图']
    ],
    tips: []
  },
  go: {
    name: 'Go', port: 6400,
    fields: [
      { key: 'output', label: '产物文件名', side: 'target', ph: 'your-service',
        hint: '被测服务那台机器上的产物路径，构建与启动两处用的是同一个' },
      { key: 'goSourceRoot', label: 'Go 源码根', side: 'platform', cfgField: 'goSourceRoot',
        cfgKey: 'go-source-root', ph: 'demo-service-go',
        hint: '<b>平台</b>这台机器上的路径' },
      { key: 'goModulePath', label: 'go.mod 的 module 路径', side: 'platform',
        cfgField: 'goModulePath', cfgKey: 'go-module-path', ph: 'github.com/you/your-service',
        hint: '把 profile 里的 import path 换算成仓库相对路径。' +
          '与被测模块对不上时，报告会是「Go 一个文件都没有」—— 与「Go 代码没被调用过」长得一样' }
    ],
    note: ['risk', '<b>必须带插桩重新编译一次，但业务源码一行不改。</b>' +
      'Go 编译为原生机器码，运行期没有可改写的中间表示。探针 <code>coverage_agent.go</code> 与 main 同包、' +
      '由 build tag 守卫，<code>init()</code> 自动执行；不带 tag 的生产构建里它根本不参与编译。'],
    cmd: (hp, f) => [
      ['cm', '# 工作树脏时必须带 -dirty：少了它，实例会自报一个干净的 commit，'],
      ['cm', '# 平台的版本一致性校验被绕过，算出来的是一份行号错位却看不出异常的增量报告'],
      ['', 'BUILD_ID=$(git rev-parse HEAD)$(git status --porcelain | grep -q . && echo -dirty)'],
      ['', ''],
      ['cm', '# -covermode=atomic 是硬要求：场景清零用的 ClearCounters() 只在 atomic 模式下可用'],
      ['', 'go build -cover -covermode=atomic -tags=goverage -o ' + f('output') + ' .'],
      ['', ''],
      ['cm', '# COVERAGE_ADDR 默认只绑回环。写成 :' + hp.port + ' 会绑到所有网卡，'],
      ['cm', '# 而 /coverage/clear 能清零计数器 —— 等于把正在录的场景交给同网段任何人随手作废'],
      ['', 'COVERAGE_ADDR=' + MARK + hp.host + ':' + hp.port + MARK + ' COVERAGE_BUILD_ID=$BUILD_ID ' + run(f('output'))]
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
    fields: [
      { key: 'sources', label: '业务源文件', side: 'target', ph: 'order.cpp main.cpp',
        hint: '空格分隔。这些要插桩（加 <code>--coverage</code>），探针不插桩 —— ' +
          '给探针也加上的话，测的是探针自己' },
      { key: 'objDir', label: '对象文件目录', side: 'target', ph: '/build/obj',
        hint: '<b>必须是绝对路径</b>：.gcda 的落点是编译期写死进产物的，' +
          '相对路径会跟着进程的工作目录跑' },
      { key: 'output', label: '产物名', side: 'target', ph: 'your-service' },
      { key: 'dataDir', label: '覆盖数据目录', side: 'target', ph: '/var/cover/svc-1',
        hint: '<b>每个实例一个目录</b>。不分开的话两个实例往同一个 .gcda 互相覆盖，' +
          '聚合出来的是「最后写的那一份」' },
      { key: 'cppObjectsDir', label: '.gcno 所在目录', side: 'platform', cfgField: 'cppObjectsDir',
        cfgKey: 'cpp-objects-dir', ph: '/build/obj',
        hint: '<b>平台</b>这台机器上的路径。相当于 Java 的 classes-dir，缺了它解不出行号 —— ' +
          '与上面那个对象目录常常指同一处，但那是被测方的视角，这是平台的视角' },
      { key: 'cppSourceRoot', label: 'C++ 源码根', side: 'platform', cfgField: 'cppSourceRoot',
        cfgKey: 'cpp-source-root', ph: 'demo-service-cpp',
        hint: '<b>平台</b>这台机器上的路径，平台侧的 gcov 要在这个目录下才找得到源码' }
    ],
    note: ['risk', '<b>必须重新编译；业务源码同样一行不改。</b>' +
      '探针 <code>coverage_agent.cpp</code> 是独立编译单元，靠全局对象的构造函数（早于 main 执行）自动启动，' +
      '业务代码不 include 也不调用它任何东西。不依赖 LD_PRELOAD，也不用 SIGUSR1，Windows 上同样可用。'],
    cmd: (hp, f) => [
      ['cm', '# 工作树脏时必须带 -dirty：少了它，实例会自报一个干净的 commit，'],
      ['cm', '# 平台的版本一致性校验被绕过，算出来的是一份行号错位却看不出异常的增量报告'],
      ['', 'BUILD_ID=$(git rev-parse HEAD)$(git status --porcelain | grep -q . && echo -dirty)'],
      ['', ''],
      ['cm', '# 业务代码插桩、探针不插桩。编译的工作目录必须是源码根（.gcno 记的是相对源码名），'],
      ['cm', '# 对象文件必须用绝对路径 —— .gcda 的落点是编译期写死进产物的'],
      // 逐个源文件一条编译命令：g++ -c 接多个源文件时不能再给 -o。
      // 先剥掉高亮标记再切分 —— 标记是成对的，切开之后就配不上了
      ...f('sources').split(MARK).join('').split(/\s+/).filter(s => s).map(src => ['',
        'g++ -std=c++17 --coverage -c ' + src + ' -o ' + f('objDir') + '/'
          + src.replace(/^.*[\\/]/, '').replace(/\.[^.]+$/, '') + '.o']),
      ['', 'g++ -std=c++17            -c coverage_agent.cpp -o ' + f('objDir') + '/coverage_agent.o'],
      ['cm', '# 探针要开 socket：Windows 上需显式链 -lws2_32，Linux 上不用'],
      ['', 'g++ -o ' + f('output') + ' ' + f('objDir') + '/*.o --coverage -lws2_32'],
      ['', ''],
      ['cm', '# GCOV_PREFIX 每实例一个目录，否则两个实例往同一个 .gcda 互相覆盖，'],
      ['cm', '# 聚合出来的是「最后写的那一份」'],
      ['', 'GCOV_PREFIX=' + f('dataDir') + ' GCOV_PREFIX_STRIP=99 COVERAGE_DATA_DIR=' + f('dataDir') + ' \\'],
      ['', '  COVERAGE_ADDR=' + MARK + hp.host + ':' + hp.port + MARK + ' COVERAGE_BUILD_ID=$BUILD_ID ' + run(f('output'))]
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
    fields: [
      { key: 'objDir', label: '探针 .o 的落点目录', side: 'target', ph: '/build/obj',
        hint: '被测方机器上的绝对路径。探针单独编成 .o 后经 <code>-C link-arg</code> 注入' },
      { key: 'profraw', label: '.profraw 文件路径', side: 'target', ph: '/var/cover/svc-1.profraw',
        hint: '<b>每个实例一个文件，且必须是字面路径</b> —— <code>%p</code> / <code>%m</code> ' +
          '之类的模式由 LLVM 展开，探针按同一个字符串删不掉旧文件，' +
          '交回的就成了历次累计的叠加，而界面上看不出异样' },
      { key: 'output', label: '产物路径', side: 'target', ph: './target/release/your-service' },
      { key: 'xwin', label: 'xwin 目录（仅 Windows）', side: 'target', ph: '<xwin>', optional: true,
        hint: 'Windows 上要编成 msvc 目标，用它拉来的 CRT/SDK 导入库。Linux 上留空即可' },
      { key: 'toolchain', label: 'rustup toolchain 目录（仅 Windows）', side: 'target',
        ph: '<toolchain>', optional: true,
        hint: '取其中的 rust-lld 作链接器。Linux 上留空即可' },
      { key: 'rustBinary', label: '产物路径（平台侧）', side: 'platform', cfgField: 'rustBinary',
        cfgKey: 'rust-binary', ph: 'demo-service-rust/target/.../your-service.exe',
        hint: '<b>平台</b>这台机器上能读到的产物本身 —— 行号信息在它自带的 coverage mapping 里' },
      { key: 'rustSourceRoot', label: 'Rust 源码根', side: 'platform', cfgField: 'rustSourceRoot',
        cfgKey: 'rust-source-root', ph: 'demo-service-rust',
        hint: '<b>平台</b>这台机器上的路径' }
    ],
    note: ['risk', '<b>必须重新编译；源码零改动做得比 Go / C++ 还彻底 —— 连 Cargo.toml 都不用动。</b>' +
      '探针是单独用 gcc 编译的 <code>.o</code>，构建时经 <code>-C link-arg</code> 注入，' +
      '靠 <code>.CRT$XCU</code> 段里的函数指针在 main 之前自动执行。既没走连续同步模式（%c），也没引入 minicov 依赖。'],
    cmd: (hp, f) => [
      ['cm', '# 工作树脏时必须带 -dirty：少了它，实例会自报一个干净的 commit，'],
      ['cm', '# 平台的版本一致性校验被绕过，算出来的是一份行号错位却看不出异常的增量报告'],
      ['', 'BUILD_ID=$(git rev-parse HEAD)$(git status --porcelain | grep -q . && echo -dirty)'],
      ['', ''],
      ['cm', '# 探针单独编成 .o 经 link-arg 注入；Cargo.toml 一行不动'],
      ['', 'gcc -c coverage_agent.c -o ' + f('objDir') + '/coverage_agent.o -mno-stack-arg-probe -O1'],
      ['cm', '# Windows 必须编成 msvc 目标（gnu 目标上 -C instrument-coverage 直接 E0463），'],
      ['cm', '# 因此还要 -L native= 指向 xwin 拉来的 CRT/SDK 导入库并换用 lld-link。'],
      ['cm', '# Linux 上把 --target 与 -L / -C linker 那几项去掉即可，其余不变'],
      ['', 'RUSTFLAGS="-C instrument-coverage -C link-arg=' + f('objDir') + '/coverage_agent.o \\'],
      ['', '  -C link-arg=ws2_32.lib \\'],
      ['', '  -L native=' + f('xwin') + '/crt/lib/x86_64 -L native=' + f('xwin') + '/sdk/lib/um/x86_64 \\'],
      ['', '  -L native=' + f('xwin') + '/sdk/lib/ucrt/x86_64 \\'],
      ['', '  -C linker=' + f('toolchain') + '/lib/rustlib/x86_64-pc-windows-gnu/bin/rust-lld.exe \\'],
      ['', '  -C linker-flavor=lld-link" \\'],
      ['', '  cargo build --release --target x86_64-pc-windows-msvc'],
      ['', ''],
      ['cm', '# LLVM_PROFILE_FILE 每实例一个文件，且必须是字面路径 —— %p / %m 之类的模式由 LLVM 展开，'],
      ['cm', '# 探针按同一个字符串删不掉旧文件，交回的就成了历次累计的叠加，界面上看不出异样'],
      ['', 'LLVM_PROFILE_FILE=' + f('profraw') + ' \\'],
      ['', '  COVERAGE_ADDR=' + MARK + hp.host + ':' + hp.port + MARK + ' COVERAGE_BUILD_ID=$BUILD_ID ' + run(f('output'))]
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

export const OB_HINT = {
  java: '确认启动参数带了 <code>-javaagent:...=output=tcpserver</code> 且端口一致，探针随 JVM 启动、不能事后挂载',
  go: '确认服务是用 <code>-cover -covermode=atomic -tags=goverage</code> 重新编译并重启过的',
  cpp: '确认服务是用 <code>--coverage</code> 重新编译、且链接了 coverage_agent 的那个产物',
  rust: '确认服务是用 <code>-C instrument-coverage</code> 重新编译、且注入了探针 .o 的那个产物'
};
