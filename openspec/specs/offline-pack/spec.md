# offline-pack Specification

## Purpose

在联网的开发机上一条命令备齐离线部署所需的物料（仓库、工具链、安装包），并在交出之前逐项自检。目标机不能上网，
在那里发现包里缺东西时已经回不来了 —— 所以宁可当场拒绝交出一份有问题的包，也不交出一份看起来完整、其实缺东西的包。

## Requirements

### Requirement: 一条命令备齐离线物料

`bash scripts/pack_offline.sh [输出目录]` SHALL 在输出目录（默认 `/c/rtcc-offline`）下备齐：仓库 bundle（完整历史）、
工具链压缩包 `devtools.tar.xz`、`installers/` 下的五个官方安装包（Git、MySQL、Chrome、Node.js、Python）及 Node.js 官方校验和、
部署手册副本、`README.txt`、`SHA256SUMS.txt`，最后自检，全部通过才以 0 退出。
包里 SHALL NOT 带 Maven 依赖、npm 包、pip 包 —— 目标机走私服与代理。

#### Scenario: 在开发机上打一份完整的包

- **WHEN** 在开发机的 Git Bash 里执行 `bash scripts/pack_offline.sh`，且 `/c/rtcc-offline` 不存在
- **THEN** 脚本以 0 退出，最后一行是 `离线包自检：全部通过`
- **AND** 输出目录里有 `realtime_code_coloring.bundle`、`devtools.tar.xz`、`installers/` 下 5 个安装包与 `node-SHASUMS256.txt`、`deploy-windows-offline.md`、`README.txt`、`SHA256SUMS.txt`
- **AND** 没有 Maven 本地仓库、`node_modules` 之类的依赖包

#### Scenario: 安装包是本机验收所用的版本

- **WHEN** 打包完成
- **THEN** Git 是 2.53.0.2、MySQL 是 8.4.8、Node.js 是 24.14.1、Python 是 3.14.3；Chrome 的实际版本写进 `README.txt`

### Requirement: 自检不过就拒绝交出，并点名是哪一项

自检 SHALL 逐项输出 `[通过]` / `[失败]` / `[跳过]`。任何一项失败 SHALL 以退出码 1 结束，并写明是哪个文件、哪一项；
没有失败、但有项没法核对（`[跳过]`）时 SHALL 以退出码 2 结束，且 SHALL NOT 报「全部通过」—— 判不了不等于通过。
`bash scripts/pack_offline.sh --check [目录]` SHALL 对一份已有的包跑同一套自检。

#### Scenario: 完好的包每一项都通过

- **WHEN** 对一份完好的包执行 `--check`，包括打包机器的属组名带空格（加入域的机器上是「Domain Users」）、
  包里文件名的大小写与关键程序清单不同（Windows SDK 里实际是 `kernel32.Lib`）这两种情况
- **THEN** 每一项都是 `[通过]`，最后一行是 `离线包自检：全部通过`，退出码为 0

#### Scenario: 有项没法核对

- **WHEN** 在没有原工具链目录的机器上执行 `--check`（「包里的文件与原目录一致」这一项没法比）
- **THEN** 这一项是 `[跳过]`，结论写明有几项没法核对、不能当作通过，退出码为 2 —— 不是 0，也不报「全部通过」

#### Scenario: 包里有符号链接

- **WHEN** 工具链压缩包里有符号链接成员（打包时漏了 `-h`：Windows 上解包建不出来，`cargo` / `rustc` 会全部缺失）
- **THEN** `[失败] 包里没有符号链接`，并点名是哪个成员；退出码非 0

#### Scenario: 缺关键程序

- **WHEN** 工具链压缩包里没有 `llvm-cov.exe`
- **THEN** `[失败] 关键程序都在包里`，并点名 `llvm-cov`

#### Scenario: 包与原目录不一致

- **WHEN** 打包之后原工具链目录里有文件变了
- **THEN** `[失败] 包里的文件与原目录一致`，并点名是哪个文件

#### Scenario: 压缩包坏了

- **WHEN** `devtools.tar.xz` 被截断
- **THEN** `[失败] 工具链压缩包完整`

#### Scenario: 安装包被改过或签名者不对

- **WHEN** 某个安装包被改了一个字节，或者签名有效但签名者不是对应厂商
- **THEN** `[失败] 安装包签名有效`，并点名是哪个文件、状态或签名者是什么

#### Scenario: Node.js 安装包与官方校验和对不上

- **WHEN** Node.js 安装包的 SHA-256 与 `node-SHASUMS256.txt` 里的那一行不一致
- **THEN** `[失败] Node.js 安装包与官方校验和一致`

#### Scenario: 拷贝后文件坏了，或校验和清单漏了文件

- **WHEN** 包里某个文件与 `SHA256SUMS.txt` 记的不一致，或者某个文件根本没列在里面
- **THEN** `[失败] SHA256SUMS 与包内文件一致`，并点名是哪个文件

#### Scenario: bundle 缺历史

- **WHEN** bundle 只带了部分历史（需要别的提交作前提）
- **THEN** `[失败] 仓库 bundle 完整`

#### Scenario: 少了文件

- **WHEN** 包里少了某个安装包
- **THEN** `[失败] 该有的文件都在`，并点名缺的是哪个

### Requirement: 不覆盖已有的东西

输出目录已存在且不为空时，打包 SHALL 在动手之前拒绝，以非 0 退出，SHALL NOT 改动、删除或新增其中任何文件。

#### Scenario: 输出目录里已有文件

- **WHEN** 输出目录里已经有文件（比如上一次打的包）
- **THEN** 脚本以非 0 退出并说明原因，目录里的文件原样不动，也没有新增文件

### Requirement: 解出来的包能在新路径上跑通全量验收

把 `devtools.tar.xz` 解到一个新目录、从 bundle 克隆出 main，只用解出来的工具链（PATH 里没有原工具链、Go 与 Cargo 离线）
SHALL 能跑通 `start` 与全量 `verify`。

#### Scenario: 换个路径、离线跑全量验收

- **WHEN** 把包解到 `C:\rtcc-verify`，从 bundle 克隆 main，按手册 4.2 把工具链变量指向解出来的目录，PATH 里去掉原工具链，设 `GOTOOLCHAIN=local`、`GOPROXY=off`、`CARGO_NET_OFFLINE=true`，执行 `start` 与 `verify`
- **THEN** 11 套全部通过（`验收结论：全部通过` ×9、`推送链路验证：全部通过` ×1、`前端验收：全部通过` ×1），≥ 352 PASS / 0 FAIL
- **AND** 平台与各被测实例的程序都是从 `C:\rtcc-verify` 下启动的
