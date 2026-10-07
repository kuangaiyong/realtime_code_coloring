# live-coloring Specification

## Purpose
染色页让「代码实时染色」被看见：调一下被测接口，几秒内就能看出是哪个文件、哪几行刚被测到，不必先猜对该打开哪个文件；四态染色一眼可辨且不只靠颜色，危险操作要确认，这些实时体验都不能拖慢端到端染色链路。

## Requirements

### Requirement: 推送带出本轮新亮起的行

覆盖率发生变化时，平台推送给染色视图的数据 SHALL 在顶层带出 `changes`：本轮与上一轮相比，状态**变好**的行
（未覆盖→已覆盖、未覆盖→部分分支、部分分支→已覆盖），按文件列出。系统 SHALL NOT 把没有变好的行放进 `changes`，
也 SHALL NOT 在推送的 file 对象上增加字段；REST 的 summary 响应 SHALL NOT 包含 `changes`。

这条同样守「宁可不报也不错报」：把旧行说成「刚亮起」，会让人以为自己刚才的操作测到了它。

#### Scenario: 调用新接口后推送列出被覆盖的行
- **WHEN** 订阅推送后调用此前未覆盖的退款接口 `POST /api/order/refund`
- **THEN** 收到的推送顶层有 `changes` 数组，其中有一项的 `path` 以 `OrderController.java` 或 `OrderService.java` 结尾且 `lines` 非空
- **AND** 用 `/api/coverage/file?path=<该 path>` 取回的这些行，状态都是 `COVERED` 或 `PARTIAL`
- **AND** 该项的 `delta` 等于它 `lines` 的长度（未截断时）

#### Scenario: 覆盖下降时不报新行
- **WHEN** 清零计数器（各行由已覆盖变回未覆盖）
- **THEN** 这次推送里 `changes` 为空数组

#### Scenario: 平台刚启动的第一轮不把已覆盖行算新
- **WHEN** 平台重启后完成第一轮采集并推送
- **THEN** 该推送里 `changes` 为空数组

#### Scenario: 掉线实例恢复时不把它的旧覆盖算新
- **WHEN** 某实例上一轮没取到数（瞬时掉线、被拒），这一轮又取到了 —— 它的 JVM 没重启，计数器还在
- **THEN** 这一轮的推送照常发出（染色要恢复），但 `changes` 为空数组：那些行早就被测到过，只是上一轮没看见
  （G2 后补：设计时只想到了「第一轮」，漏了「中途恢复」；CLAUDE.md 记载的两种瞬时取不到数都会触发它）

#### Scenario: 只有分支变好也要推送
- **WHEN** 某行由部分分支变为已覆盖，而所有文件的已覆盖行数都没变
- **THEN** 平台仍然推送，且该行出现在 `changes` 里

#### Scenario: 推送体有上限
- **WHEN** 一轮里有超过 20 个文件、或某文件超过 200 行变好
- **THEN** `changes` 最多 20 项、每项 `lines` 最多 200 个；被截断的项 `truncated` 为 true，`delta` 仍是精确行数

### Requirement: 跟随模式

染色页 SHALL 提供跟随模式开关（默认开，按浏览器记住）。开着时，收到带 `changes` 的推送后：若当前文件在 `changes` 里，
SHALL 留在当前文件并滚到它的首条新亮起的行；否则 SHALL 打开 `delta` 最大的文件并滚到其首条新亮起的行。
用户在染色页最近 10 秒内操作过（点击、滚动、按键）时 SHALL 暂停跟随并显示可恢复的「跟随已暂停」提示。

#### Scenario: 当前文件无关时自动切到刚被覆盖的文件
- **WHEN** 跟随模式开着且未暂停，当前打开的是 `main.cpp`，随后调用退款接口
- **THEN** 15 秒内 `current-path` 变为 `changes` 中 delta 最大的那个文件
- **AND** 该文件至少有一行带「新亮起」标记，且那一行在源码区的可视范围内

#### Scenario: 当前文件本身在变化里时不切走
- **WHEN** 跟随模式开着，当前打开的是 `OrderController.java`，调用的接口会覆盖它的新行
- **THEN** `current-path` 保持为 `OrderController.java`

#### Scenario: 用户刚操作过时暂停
- **WHEN** 用户刚点击了文件列表里的某个文件，10 秒内收到带 `changes` 的推送
- **THEN** 当前文件不变，页面显示「跟随已暂停」及「继续」按钮
- **AND** 点「继续」后，下一次推送重新按跟随规则切换

#### Scenario: 关掉后永不自动切换
- **WHEN** 用户关掉跟随模式并刷新页面
- **THEN** 开关仍为关；之后的推送不会改变当前文件

### Requirement: 实时动态条

染色页 SHALL 在顶部显示最近的覆盖变化（最多 8 条），每条包含文件名、新亮起行数 `+N 行` 与相对时间；点击某条 SHALL 打开该文件并滚到其首条新亮起的行。没有变化时 SHALL 显示等待提示而不是空白。

#### Scenario: 推送后出现对应条目
- **WHEN** 调用退款接口
- **THEN** 动态条出现一条 `data-path` 为被覆盖文件的条目，文字含该文件名与 `+N 行`（N 等于推送中该文件的 delta）

#### Scenario: 点击条目直达
- **WHEN** 点击动态条里的某条
- **THEN** `current-path` 变为该条的文件，且首条新亮起的行在可视范围内

#### Scenario: 空状态
- **WHEN** 进入染色页后还没有收到任何带 `changes` 的推送
- **THEN** 动态条显示「等待下一次覆盖变化」一类的提示

### Requirement: 文件列表变化高亮与新亮起标记

收到 `changes` 后，文件列表中对应的文件 SHALL 被高亮约 4 秒并显示 `+N`。源码中新亮起的行 SHALL 带持续标记，直到用户清除、清零或切换项目。增量口径下 diff 之外的行 SHALL NOT 被标记或闪烁。

#### Scenario: 列表高亮出现并消退
- **WHEN** 推送带有某文件的 changes
- **THEN** 1 秒内该文件的列表项带 `data-changed="1"` 并显示 `+N`
- **AND** 约 6 秒后 `data-changed` 不再为 1

#### Scenario: 新亮起标记可清除
- **WHEN** 某文件有新亮起标记，用户点击「清除标记」
- **THEN** 该文件不再有任何带 `data-new="1"` 的行

#### Scenario: 清零会清掉标记
- **WHEN** 确认清零计数器
- **THEN** 所有文件的新亮起标记清空

### Requirement: 源码缩略条

源码区 SHALL 显示整份文件的缩略条，按行画出四态并标出当前可视范围；点击缩略条 SHALL 滚动到对应位置。缩略条 SHALL 暴露各状态行数，且与源码中实际渲染的行状态一致。

#### Scenario: 计数与源码一致
- **WHEN** 打开任一文件
- **THEN** 缩略条的 `data-covered / data-missed / data-partial` 分别等于源码中 `data-status` 为 COVERED / MISSED / PARTIAL 的行数，`data-lines` 等于总行数

#### Scenario: 点击跳转
- **WHEN** 在缩略条的底部点击
- **THEN** 源码区滚动到接近文件末尾的位置

### Requirement: 四态染色一眼可辨且不只靠颜色

已覆盖、未覆盖、部分分支三态的行 SHALL 用彼此明显不同的底色，并各带一个不依赖颜色的标识（符号或纹理）。部分分支行 SHALL 在悬停提示中给出分支覆盖数。行文字与底色的对比度 SHALL ≥ 4.5:1（浅色与深色主题）。

#### Scenario: 三态可区分
- **WHEN** 打开同时含三态的文件
- **THEN** 三态行的计算底色两两不同，且每一态的行内都有对应的状态符号元素

#### Scenario: 部分分支显示分支数
- **WHEN** 查看一条部分分支行
- **THEN** 其悬停提示为「分支 a/b 已覆盖」形式，a、b 与文件详情接口该行的 coveredBranches、coveredBranches+missedBranches 相等

#### Scenario: 对比度达标
- **WHEN** 分别在浅色与深色主题下读取三态行的文字色与底色
- **THEN** 两者对比度都不低于 4.5:1

### Requirement: 语法高亮不改变源码与染色

源码 SHALL 按语言（Java / Go / C++ / Rust）语法高亮。高亮 SHALL NOT 改变每行的文本内容，SHALL NOT 改变行的 `data-status`；语言不认识、高亮库没加载上或分词失败时 SHALL 按纯文本显示，SHALL NOT 让文件打不开。渲染 SHALL NOT 以 HTML 字符串注入源码。

#### Scenario: 有高亮且文本不变
- **WHEN** 打开 `OrderController.java`
- **THEN** 源码行内存在语法 token 元素，且每一行的文本与文件详情接口返回的 `text` 逐字相同

#### Scenario: 跨行注释正确切分
- **WHEN** 对 `/* a\nb */ int x;` 按 Java 分词并按行切分
- **THEN** 得到两行，两行里注释部分的 token 类型都是 comment

#### Scenario: 高亮库没加载上时退回纯文本
- **WHEN** 只剩页面预设的 `window.Prism = { manual: true }`（prism-core 没加载上），对 `A.java` 取语言并分词
- **THEN** 语言为 null，每行退回一个普通文本 token，不抛错（评审发现的缺口：原先这里抛 TypeError，染色页打不开）

### Requirement: 危险操作需要确认

「清零计数器」 SHALL 在二次确认后才执行；仅点击按钮 SHALL NOT 清零。场景进行中该按钮 SHALL 保持禁用（既有行为）。

#### Scenario: 不确认不清零
- **WHEN** 点击清零按钮但不点确认，等待 4 秒
- **THEN** 当前文件的已覆盖行数不变

#### Scenario: 确认后清零
- **WHEN** 点击清零按钮并点确认
- **THEN** 20 秒内当前文件的已覆盖行数变为 0

### Requirement: 采集是否在进行一目了然

项目内顶栏 SHALL 显示距平台上次采集成功的秒数并持续更新。推送只在覆盖率变化时才来，
所以这个数 SHALL 取自平台的 `lastCollectedAt`（经轻量轮询跟住），SHALL NOT 用「上次收到推送」顶替 ——
后者在没人调接口时会一直涨，把「平台在采、只是没变化」说成「平台卡住了」。

#### Scenario: 覆盖不变时计时也在跳
- **WHEN** 停留在染色页 15 秒，期间不调任何被测接口（没有推送）
- **THEN** 顶栏「上次采集 N 秒前」的 N 发生过变化，且始终不超过 12 秒（采集周期约 5s + 心跳间隔 3s，留余量）

### Requirement: 界面缺陷修复

状态标签 SHALL 只占一行；新建向导 SHALL 在用户碰过字段或点「下一步」之后才显示校验错误；向导页的侧栏「项目管理」SHALL 可返回项目列表；
染色页在 1280×720 下 SHALL 完整显示当前文件的覆盖率信息；文件列表 SHALL 是独立的滚动容器。

#### Scenario: 事件标签不折行
- **WHEN** 打开采集事件页
- **THEN** 每个状态标签的高度不超过一行文字的高度

#### Scenario: 向导不预先报错
- **WHEN** 打开新建项目向导、尚未输入任何内容
- **THEN** 页面上没有错误提示；点「下一步」后才出现「项目名不能为空」

#### Scenario: 向导里侧栏可返回
- **WHEN** 在新建项目向导里点击侧栏「项目管理」
- **THEN** 回到项目列表

#### Scenario: 窄屏不截断当前文件信息
- **WHEN** 视口为 1280×720 打开 `OrderController.java`
- **THEN** 源码区标题栏里覆盖率那段文字没有被截断（内容宽度不超过容器宽度）

#### Scenario: 文件列表独立滚动
- **WHEN** 打开染色页
- **THEN** 文件列表是自己的滚动容器，高度不超过视口

### Requirement: 门禁结论优先、帮助并入接入、文件墙

覆盖门禁页 SHALL 把两张结论放在首屏、原理说明默认折叠；接入帮助 SHALL 作为服务接入页的页签出现，且原深链接 SHALL 仍可直达；
染色页 SHALL 提供文件墙视图，格子与文件一一对应、颜色档位与覆盖率档位一致。

#### Scenario: 门禁先给结论
- **WHEN** 打开覆盖门禁页
- **THEN** 两张结论卡的结论字样在首屏内，原理说明默认折叠

#### Scenario: 帮助是接入页的页签且深链接有效
- **WHEN** 打开 `#/p/default/help/rust`
- **THEN** 服务接入页的帮助页签处于选中态，Rust 一节处于选中态

#### Scenario: 文件墙与文件列表一致
- **WHEN** 切到文件墙
- **THEN** 格子数等于文件数，每格颜色档位与该文件覆盖率的档位一致，点一格即打开该文件

### Requirement: 改版不拖慢实时链路

清零后调用被测接口到浏览器出现已覆盖行的端到端延迟 SHALL 保持 ≤ 5000ms；对最大真实源文件分词 SHALL ≤ 100ms。

#### Scenario: 端到端延迟不回退
- **WHEN** 清零后调用被测接口
- **THEN** 浏览器里出现已覆盖行的端到端延迟 ≤ 5000ms

#### Scenario: 分词开销
- **WHEN** 对当前最大的真实源文件分词并按行切分
- **THEN** 耗时 ≤ 100ms
