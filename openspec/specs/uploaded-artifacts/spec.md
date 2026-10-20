## Purpose

让容器化部署（由 CI 按构建版本把编译产物推给平台）能在页面上从头走通：建项目、改配置、拿到推产物的命令。同时守住产物仓库唯一的写入口，不让内网里任何一台机器都能推产物、删产物。

## Requirements

### Requirement: 产物的上传与删除要令牌

系统 SHALL 要求产物上传与产物删除请求在请求头携带 `Authorization: Bearer <令牌>`，令牌与平台配置的产物令牌一致才受理。产物列表 SHALL NOT 要求令牌。被拒的请求 SHALL 在平台读取请求体之前被拒绝，且 SHALL NOT 在产物仓库留下任何东西。平台没有配置产物令牌时，上传与删除 SHALL 一律拒绝，而不是放行。

#### Scenario: 平台没配令牌时写接口关闭

- **WHEN** 平台的产物令牌为空，有人上传或删除产物
- **THEN** 返回 403
- **AND** 错误信息说明平台没有配置产物令牌，并给出配置项名称（`COVERAGE_ARTIFACT_TOKEN`）

#### Scenario: 没带令牌时拒绝

- **WHEN** 平台配了令牌，上传请求没带 `Authorization` 请求头，或请求头不是 `Bearer <令牌>` 的写法
- **THEN** 返回 401，并带 `WWW-Authenticate: Bearer` 响应头
- **AND** 错误信息说明令牌要放在 `Authorization: Bearer` 里

#### Scenario: 令牌不对时拒绝

- **WHEN** 上传或删除请求带了 `Authorization: Bearer <错的令牌>`
- **THEN** 返回 401，错误信息说明令牌与平台配置的不一致

#### Scenario: 被拒的上传不留下任何东西

- **WHEN** 一个不带令牌的上传请求被拒
- **THEN** 之后查这个项目的产物列表，里面没有这个构建

#### Scenario: 换一种路径写法绕不过令牌

- **WHEN** 上传请求的路径带分号参数（如 `/api;x=1/artifacts/<buildId>`）或经过百分号编码（如 `/%61pi/artifacts/<buildId>`），且没带令牌
- **THEN** 同样被拒绝

#### Scenario: 列表不要令牌

- **WHEN** 不带令牌查询某项目的产物列表
- **THEN** 返回 200 与列表内容

#### Scenario: 带对令牌照常受理

- **WHEN** 上传请求带了 `Authorization: Bearer <平台的令牌>`（scheme 大小写不论）
- **THEN** 按原有规则受理（校验 buildId、包内容、解压上限），成功时返回 200

### Requirement: uploaded 项目的自检按实例自报的构建核对产物仓库

对产物来源为 uploaded 的项目，项目自检 SHALL NOT 要求本地产物路径（Java 产物目录、C++ 对象目录、Rust 产物）存在，也 SHALL NOT 对本地 Java 产物目录做「产物与源码是否同一个工程」的核对。全部实例都连上之后，自检 SHALL 按实例自报的构建版本，从产物仓库取出该项目有实例的那几种语言的产物（Go 不需要产物）。取产物所走的规则 SHALL 与采集时一致。取不到时 SHALL 判为不通过，并说明缺哪个构建、哪种语言，给出带项目参数的补救方法。

#### Scenario: 不再要求本地产物路径

- **WHEN** 对一个 uploaded 项目跑自检，它的三条本地产物路径都没填或都不存在
- **THEN** 检查项里没有这三条路径，也没有针对本地产物目录的「是否同一个工程」

#### Scenario: 产物还没推时不通过并点名

- **WHEN** 全部实例已连上、自报同一个干净的构建版本，而产物仓库里没有这个项目这个构建的 Java 产物
- **THEN** 「产物仓库」一项不通过
- **AND** 说明里包含该构建版本、语言 `java`，以及带 `project=<项目标识>` 的上传方法

#### Scenario: 产物推上来之后通过

- **WHEN** 产物仓库里有这个项目这个构建的全部所需产物
- **THEN** 「产物仓库」一项通过

#### Scenario: 推上来的 Java 产物与源码不是同一个工程

- **WHEN** 推上来的 Java 产物里的类，在 Java 源码根下一个都找不到
- **THEN** 「产物与源码是否同一个工程」一项不通过，并说明产物取自产物仓库（不提示去改本地产物目录）

#### Scenario: 脏构建不通过

- **WHEN** 实例自报的构建版本带 `-dirty`
- **THEN** 「产物仓库」一项不通过，说明产物仓库不收未提交改动的构建

#### Scenario: 版本拿不准时不通过

- **WHEN** 有实例没有自报构建版本，或各实例自报的版本不一致
- **THEN** 「产物仓库」一项不通过，说明无从知道该取哪一份产物

#### Scenario: 版本串不是 commit 时点名

- **WHEN** 各实例都自报了同一个版本串，但它不是 40 位 commit sha（可带 `-dirty`）
- **THEN** 「产物仓库」一项不通过，说明里原样给出这个版本串，并说明产物仓库按 commit 索引

#### Scenario: 只有 Go 实例时不出这一项

- **WHEN** uploaded 项目只配了 Go 实例
- **THEN** 自检里没有「产物仓库」一项

#### Scenario: 实例没全连上时不出这一项

- **WHEN** 有实例连不上
- **THEN** 自检里没有「产物仓库」一项（连不上的实例本身已经各自不通过）

### Requirement: 自检说出产物上传接口开着没有

对产物来源为 uploaded、且配有需要产物的语言（Java、C++、Rust）实例的项目，项目自检 SHALL 报告平台的产物上传接口是否可用。平台没有配置产物令牌时，该项 SHALL 不通过，并说明 CI 推不进产物。只配了 Go 实例的项目不推任何产物，SHALL NOT 出这一项。

#### Scenario: 没配令牌时不通过

- **WHEN** 平台没有配置产物令牌，对一个 uploaded 项目跑自检
- **THEN** 「产物上传接口」一项不通过，说明里包含 `COVERAGE_ARTIFACT_TOKEN`

#### Scenario: 配了令牌时通过

- **WHEN** 平台配了产物令牌
- **THEN** 「产物上传接口」一项通过

#### Scenario: 只有 Go 实例时不出这一项

- **WHEN** 平台没有配置产物令牌，对一个只配了 Go 实例的 uploaded 项目跑自检
- **THEN** 自检里没有「产物上传接口」一项

#### Scenario: local 项目不出这一项

- **WHEN** 对产物来源为 local 的项目跑自检
- **THEN** 自检里没有「产物上传接口」与「产物仓库」两项，本地产物路径照旧检查

### Requirement: 产物来源填错时自检单独点名

自检 SHALL 在产物来源既不是 local 也不是 uploaded 时单报一项不通过，并给出两个合法取值。

#### Scenario: 拼错的产物来源

- **WHEN** 对产物来源为 `upload` 的配置跑自检
- **THEN** 「产物来源」一项不通过，说明里包含 `local` 与 `uploaded`

### Requirement: 新建向导能建出 uploaded 项目

新建向导 SHALL 在「语言与产物」一步提供产物来源的选择（产物在平台本机 / CI 按构建推送），默认为前者。选择 CI 推送后，该步 SHALL NOT 再要求本地产物路径，SHALL 按已配实例的语言（Java、C++、Rust）给出推产物的命令。命令中的项目标识、平台地址、语言 SHALL 已经填好，构建版本取自 `git rev-parse HEAD`，令牌取自 CI 环境变量 `COVERAGE_ARTIFACT_TOKEN`，上传地址 SHALL 用双引号括起。产物没推时该步 SHALL 拦住并点名缺的构建。照页面给的命令推上产物后，向导 SHALL 能走完全部步骤，建出的项目 SHALL 立刻有覆盖数据。

#### Scenario: 选了 CI 推送后不再要本地产物路径

- **WHEN** 用户在第 4 步选择「CI 按构建推送」，且配了 Java 实例
- **THEN** 页面不再显示 Java 产物目录输入框，Java 源码根仍然显示
- **AND** 页面给出 Java 推产物的命令，包含 `project=<项目标识>`、`lang=java`、`/api/artifacts/$(git rev-parse HEAD)`、`Authorization: Bearer $COVERAGE_ARTIFACT_TOKEN`，上传地址在双引号内

#### Scenario: 产物没推时拦住并点名

- **WHEN** 产物仓库里还没有实例当前构建的 Java 产物，用户点「下一步」
- **THEN** 向导停在第 4 步，提示里包含该构建版本

#### Scenario: 照页面命令推完后建得出来、立刻有数据

- **WHEN** 在项目工作目录下原样执行页面给出的 Java 推产物命令（CI 环境里有 JDK、curl 与令牌变量），然后继续向导
- **THEN** 命令执行成功，第 4 步放行，第 6 步自检全部通过，且其中没有本地产物路径一项
- **AND** 点「创建项目」后，项目的文件列表立刻有数据

### Requirement: 项目设置能看、能改产物来源

项目设置页 SHALL 显示并允许修改产物来源。产物来源为 uploaded 时 SHALL NOT 显示本地产物路径输入框。保存 SHALL 保留产物来源原值，不得把 uploaded 项目改回 local。

#### Scenario: uploaded 项目的设置页

- **WHEN** 打开一个 uploaded 项目的设置页
- **THEN** 产物来源显示为 uploaded，Java 产物目录输入框不显示

#### Scenario: 保存不丢产物来源

- **WHEN** 在 uploaded 项目的设置页修改其他字段并保存
- **THEN** 保存后该项目的产物来源仍为 uploaded
