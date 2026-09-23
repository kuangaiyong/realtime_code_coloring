# Windows 离线部署手册

> **适用版本**：v0.9.2（`ec30c61`）。手册里的版本号、目录大小、端口、耗时，
> 都取自 2026-09-23 在本仓库开发机上的实测。换了版本之后，请对照 `scripts/run_local.sh`
> 顶部和 `pom.xml` 重新核对。
>
> **前提**：目标机是 64 位 Windows（10 / 11 / Server），**不能访问外网**，
> 但能从内网镜像安装 Python / Java / npm 的依赖。
>
> **约定**：除特别说明外，所有命令都在 **Git Bash** 里执行，不要用 cmd 或 PowerShell。

---

## 0. 先读这一节

### 0.1 两种部署形态

| 形态 | 装什么 | 适合什么时候 |
|---|---|---|
| **A. 完整复刻（本手册主线）** | 平台 + 8 个演示被测服务（Java / Go / C++ / Rust 各 2 台）+ 一键验收 | 第一次部署，用来证明这台机器的环境装对了 |
| **B. 只用平台接你自己的服务** | 平台 + 所接语言对应的工具链 | A 跑通之后，接真实业务（见第 8 节） |

**建议先走通 A，再做 B。** A 最后的验收结果（259 PASS / 0 FAIL，11 套全部通过）
是「工具链版本对、路径对、数据库通」唯一可靠的证据。跳过它直接接业务服务，
出了问题会分不清是环境没装对，还是接入方式不对。

### 0.2 哪些东西必须从联网机器带过去

目标机能装的只是 Python / Java / npm 的依赖。下面这些不在这个范围里，**只能从联网机器带过去**：

| 东西 | 为什么非带不可 |
|---|---|
| Go 1.26.6 | `demo-service-go/go.mod` 要求 Go 1.26。本机 Go 版本不够时，Go 会尝试联网下载新工具链，离线必然失败 |
| MinGW-w64（GCC 16.2.0） | C++ 演示服务要用它编译；**平台自己**也要调 `gcov` / `gcov-tool` 来解析 C++ 的覆盖数据 |
| Rust 1.97.1 工具链（含 `llvm-tools` 组件与 msvc 目标） | Rust 演示服务要用它编译；平台要调 `llvm-profdata` / `llvm-cov`，而且**这两个工具的版本必须与 rustc 一致**。`llvm-tools` 是 rustup 的可选组件，常规的离线安装包里没有 |
| xwin（微软 CRT 与 Windows SDK 的导入库） | Windows 上 Rust 的覆盖率插桩只有 msvc 目标能用，有了这些库文件就不用装 Visual Studio |
| Git for Windows | 所有脚本都跑在 Git Bash 里；平台算增量覆盖率时要调 `git` |
| MySQL 8.4 | 项目配置、覆盖率趋势、采集事件都存在这里 |
| Google Chrome | 前端验收要开真实浏览器 |

**不需要带的**：

- 前端用到的 Vue、Element Plus、图标和字体都已打进平台 jar，**不连任何 CDN**；
- 验收用的 Python 脚本只用标准库，**不用 pip 装任何包**；
- Go 和 Rust 两个演示服务**都没有外部依赖**，不需要 Go 模块代理，也不需要 crates.io。

### 0.3 整体流程

```
联网机器（开发机）                         目标机（离线）
──────────────────                         ──────────────────
1. 打包仓库（git bundle）      ──拷贝──▶   4. 装 Git / MySQL / Chrome / Python / Node
2. 打包 devtools 工具链目录    ──拷贝──▶   5. 解开 devtools，配置环境变量
3. 下载几个安装包              ──拷贝──▶   6. 取出仓库、建库、写 .env.local
                                           7. bash scripts/run_local.sh start
                                           8. bash scripts/run_local.sh verify
                                              → 259 PASS / 0 FAIL，11 套全部通过
```

### 0.4 硬件与耗时

- **磁盘**：至少预留 10 GB。工具链解开后约 3.8 GB，传输用的打包文件本身也有约 3.8 GB，
  另外还有仓库、Maven 依赖、MySQL、Chrome。
- **内存**：实测平台 + 8 个被测实例 + MySQL 常驻约 1.1 GB；构建和前端验收时会更高，
  建议 8 GB 以上。
- **耗时**（开发机实测）：`start`（含全部构建）约 1 分 15 秒，`verify` 约 4 分钟。
  目标机第一次 `start` 要从内网镜像下载 Maven 依赖，会更久。

---

## 1. 组件与版本清单

| 组件 | 实测版本 | 用途 | 目标机怎么获得 |
|---|---|---|---|
| Git for Windows | 2.53.0 | Git Bash 运行脚本；平台调 `git` 算增量、读实例版本 | 带安装包 |
| JDK（Eclipse Temurin） | 17.0.20+8 | 运行平台与 Java 演示服务；构建 | 拷 devtools；或目标机自装任意 JDK 17 |
| Maven | 3.9.16 | 构建 | 拷 devtools；或自装 3.9.x。依赖走内网镜像 |
| MySQL | 8.4（LTS） | 项目配置 / 趋势 / 采集事件 | 带安装包 |
| Go | 1.26.6 | 构建 Go 演示服务；平台调 `go tool covdata` | 拷 devtools |
| MinGW-w64 | GCC 16.2.0（x86_64-ucrt-posix-seh） | 构建 C++ 演示服务；平台调 `gcov` / `gcov-tool`；编译 Rust 探针 | 拷 devtools |
| Rust | 1.97.1，工具链 `stable-x86_64-pc-windows-gnu`，另装 `x86_64-pc-windows-msvc` 目标和 `llvm-tools` 组件 | 构建 Rust 演示服务；平台调 `llvm-profdata` / `llvm-cov` | 拷 devtools（`rustup` 和 `cargo` 两个目录） |
| xwin 库文件 | 631 MB | 链接 Rust 的 msvc 目标 | 拷 devtools |
| Python | 3.14.3（任意 3.x 都行，只用标准库） | E2E 验收脚本 | 目标机自装 |
| Node.js | 24.14.1（**至少 22**：脚本用到内置的 `WebSocket`） | 推送验收、前端验收脚本 | 目标机自装 |
| puppeteer | 25.3.0 | 驱动 Chrome | npm 安装（要跳过浏览器下载，见 4.4） |
| Google Chrome | 153.0.8010.53 | 前端验收 | 带企业版离线安装包 |

Maven 依赖（Spring Boot 3.3.5、JaCoCo 0.8.12、MySQL 驱动等）在构建时自动下载，
目标机配好内网 Maven 镜像即可（见 4.5）。

---

## 2. 在联网机器上准备离线物料

下面的命令都在**开发机**的 Git Bash 里执行。物料统一放到 `C:\rtcc-offline`（Git Bash 里写作 `/c/rtcc-offline`），
最后把整个文件夹拷到目标机。

```bash
mkdir -p /c/rtcc-offline
```

### 2.1 打包仓库

```bash
cd /c/code/realtime_code_coloring
git bundle create /c/rtcc-offline/realtime_code_coloring.bundle --all
git bundle verify /c/rtcc-offline/realtime_code_coloring.bundle
```

`verify` 输出里有 `The bundle records a complete history`，就说明打包完整。

**为什么用 bundle，而不是直接压缩文件夹**：

- bundle 带着**完整的 git 历史**。平台算增量覆盖率默认与 `HEAD~1` 比，被测实例启动时
  还要读当前提交号作为自报版本，所以目标机上必须是一个完整的 git 仓库；
- bundle 不带构建产物（`target/`、`.run/`），体积小；
- 装着数据库凭据的 `.env.local` 已被 gitignore，不会跟着过去。它在目标机上重新写（见 5.2）。

### 2.2 打包工具链目录

开发机的工具链都装在 `C:\Users\Administrator\devtools` 下，是免安装的绿色目录，整个拷走就能用。
打包时去掉几项用不到的东西：

```bash
cd /c/Users/Administrator
tar -chf /c/rtcc-offline/devtools.tar \
  --exclude='devtools/rustup/toolchains/nightly-x86_64-pc-windows-gnu' \
  --exclude='devtools/rustup/update-hashes/nightly-x86_64-pc-windows-gnu' \
  --exclude='devtools/rustup/downloads' \
  --exclude='devtools/rustup/tmp' \
  --exclude='devtools/compiler-rt' \
  --exclude='devtools/rustup-init.exe' \
  --exclude='devtools/xwin/sdk/include/10.0.26100' \
  --exclude='devtools/xwin/sdk/lib/10.0.26100' \
  devtools
```

**`-h` 和最后两行排除项都不能省**（本机按手册演练时踩到过）：

- `cargo/bin` 下的 `cargo.exe`、`rustc.exe` 等 13 个程序，其实是指向 `rustup.exe` 的符号链接。
  不加 `-h` 的话，包里存的是链接本身，在 Windows 上解包时建不出来：
  `cargo`、`rustc` 全部缺失，Rust 根本编不了。加了 `-h`，包里存的就是真实文件；
- xwin 里的两个 `10.0.26100` 是指向自己所在目录的链接，构建用不到它们，
  不排除的话，解包会报「符号链接层级过多」。

打包进去的内容：

| 目录 | 大小 | 内容 |
|---|---|---|
| `jdk-17.0.20+8` | 304 MB | JDK 17 |
| `apache-maven-3.9.16` | 11 MB | Maven |
| `go` | 256 MB | Go 1.26.6 |
| `mingw64` | 942 MB | GCC 16.2.0、gcov、gcov-tool |
| `rustup` | 1.7 GB | 只剩 stable 工具链（含 msvc 目标的标准库、`llvm-tools`、`rust-lld`） |
| `cargo` | 61 MB | `cargo` / `rustc` / `rustup` 的入口程序 |
| `xwin` | 631 MB | MSVC CRT 与 Windows SDK 的导入库 |
| **合计** | **约 3.8 GB** | 不压缩；想更小可以改用 7-Zip 压缩 |

去掉的几项：

- `nightly` 工具链（1.7 GB）是早先验证 gnu 目标时留下的，现有构建用不到；
- `compiler-rt`、`rustup-init.exe` 同样用不到；
- `downloads`、`tmp` 是 rustup 的下载缓存。

打完包核对两件事。第一，关键的三个程序必须都在：

```bash
tar -tf /c/rtcc-offline/devtools.tar | grep -E "bin/(llvm-profdata|llvm-cov|rust-lld)\.exe$"
```

应该输出 3 行，分别以 `llvm-cov.exe`、`llvm-profdata.exe`、`rust-lld.exe` 结尾。

第二，`cargo.exe` 在包里必须是真实文件，不能是链接：

```bash
tar -tvf /c/rtcc-offline/devtools.tar devtools/cargo/bin/cargo.exe
```

正确的输出以 `h` 开头、以 `link to devtools/cargo/bin/cargo-clippy.exe` 结尾：`-h` 把 13 个同名链接
存成了一份文件加 12 个硬链接，包因此不会变大。如果以 `l` 开头、以 `-> rustup.exe` 结尾，
说明打包时漏了 `-h`，要重新打。

> 如果开发机的用户名不是 `Administrator`，或者工具链不在这个位置，把上面的
> `cd /c/Users/Administrator` 改成 `devtools` 目录的上一级。

### 2.3 下载安装包

在开发机上下载以下官方安装包，放进 `C:\rtcc-offline`：

| 安装包 | 下载地址 | 选哪个 |
|---|---|---|
| Git for Windows | https://git-scm.com/downloads/win | 64 位安装版（Setup） |
| MySQL 8.4 LTS | https://dev.mysql.com/downloads/mysql/ | 版本选 8.4 LTS，平台选 Windows，下载 MSI Installer |
| Google Chrome 企业版 | https://chromeenterprise.google/browser/download/ | Windows 64 位 MSI（离线安装包） |

如果目标机连 JDK、Node.js、Python 的安装包也拿不到，一并带上：

| 安装包 | 下载地址 |
|---|---|
| Node.js（22 或更高的 LTS） | https://nodejs.org/en/download |
| Python 3 | https://www.python.org/downloads/windows/ |

JDK 已经包含在 devtools 里，不必单独下载。

### 2.4 Maven 依赖（二选一）

- **目标机能连内网 Maven 镜像**：什么都不用带，到 4.5 配好镜像地址即可。
- **目标机连不上任何 Maven 仓库**：把开发机的本地仓库（约 91 MB）带过去：

  ```bash
  tar -cf /c/rtcc-offline/m2-repository.tar -C /c/Users/Administrator/.m2 repository
  ```

### 2.5 puppeteer（二选一）

- **目标机的 npm 能装包**：什么都不用带，到 4.4 安装。
- **目标机的 npm 装不了包**：在开发机上先装好，把整个文件夹带过去：

  ```bash
  mkdir -p /c/rtcc-offline/node-deps && cd /c/rtcc-offline/node-deps
  PUPPETEER_SKIP_DOWNLOAD=true npm install puppeteer@25.3.0
  ```

### 2.6 出发前核对

`C:\rtcc-offline` 里应该有：

- [ ] `realtime_code_coloring.bundle`
- [ ] `devtools.tar`（约 3.8 GB）
- [ ] Git for Windows、MySQL 8.4、Chrome 企业版三个安装包
- [ ] （按需）Node.js、Python 安装包
- [ ] （按需）`m2-repository.tar`
- [ ] （按需）`node-deps` 文件夹
- [ ] 本手册（它就在仓库的 `docs/` 里，也可以单独拷一份，方便离线查看）

---

## 3. 目标机：安装基础软件

### 3.1 Git for Windows

运行安装包，**选项都保持默认**。其中两项必须是默认值：

- **「Git from the command line and also from 3rd-party software」**：这一项会把 git 加进 Windows 的 PATH。
  平台（`java.exe`）是直接调用 `git` 的，只在 Git Bash 里能用是不够的；
- **换行符选项**保持默认（Checkout Windows-style, commit Unix-style）。开发机就是这个设置，
  脚本以 CRLF 换行检出后照样能跑。

装完后打开「Git Bash」核对：

```bash
git --version        # git version 2.x
which cygpath        # /usr/bin/cygpath（启动脚本要用它）
```

**本手册之后的命令都在 Git Bash 里执行。**

### 3.2 Python

用 python.org 的安装包安装，**勾选「Add python.exe to PATH」**。不要用 Microsoft Store 版。

然后**关掉 Windows 自带的 Python 应用执行别名**：在 Windows 设置里搜索「应用执行别名」，
把 `python.exe` 和 `python3.exe` 两项都关掉。

原因：Windows 在 PATH 里放了一个 0 字节的 `python` 占位程序（位于 `WindowsApps` 目录），
在 Git Bash 里执行它会报 `Permission denied`。启动脚本自己会跳过它，但手册里那几条
用 `python` 解析 JSON 的核对命令会被它拦住。

核对：

```bash
type -ap python      # 第一行不能是 .../WindowsApps/python
python --version     # Python 3.x
```

> 如果别名关不掉，也可以在 `~/.bashrc` 里写 `export PY=<python.exe 的 Git Bash 路径>`。
> 启动脚本会优先用 `PY`。

### 3.3 Node.js

安装 22 或更高版本。核对：

```bash
node -v    # v22.x 或更高（开发机是 v24.14.1）
npm -v
```

### 3.4 Google Chrome

运行企业版 MSI，它会装到 `C:\Program Files\Google\Chrome\Application\chrome.exe`。核对：

```bash
ls "/c/Program Files/Google/Chrome/Application/" | grep -E '^[0-9]+\.'
```

输出的是版本号文件夹，比如 `153.0.8010.53`。

> **不要用 `chrome.exe --version` 查版本**：在 Windows 上它会打开一个浏览器窗口，命令就卡在那里。
>
> 开发机遇到过一次：验收跑到一半，Chrome 恰好在后台自动更新、替换了自己，前端验收因此启动浏览器失败。
> 离线机器下载不到更新，所以不会发生；以后如果这台机器能上网了，建议关掉 Chrome 的自动更新。

### 3.5 MySQL 8.4

运行 MSI。安装完会弹出 MySQL Configurator，按下面配置：

- 端口：**3306**
- 设置 root 密码
- 配置成 Windows 服务并开机自动启动（默认服务名是 `MySQL84`）

然后建库和账号。**下面这一步请在 cmd 或 PowerShell 里执行**：在 Git Bash 窗口里，
MySQL 客户端提示输入密码时可能会卡住。

```bat
"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -h 127.0.0.1 -P 3306 -u root -p
```

登录后执行：

```sql
CREATE DATABASE code_coloring DEFAULT CHARACTER SET utf8mb4;
CREATE USER 'rtcc'@'127.0.0.1' IDENTIFIED BY '换成你自己的密码';
GRANT ALL PRIVILEGES ON code_coloring.* TO 'rtcc'@'127.0.0.1';
```

说明：

- **不用手工建表**。平台第一次启动时会自己建好 `project`、`build_coverage`、`collect_event`、
  `schema_migration` 四张表；
- 账号的主机写 `127.0.0.1`，和后面连接地址里的主机保持一致；
- 密码里**不要带单引号**，否则 5.2 里写 `.env.local` 会比较麻烦。

核对新账号能登录（同样在 cmd / PowerShell 里执行）：

```bat
"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -h 127.0.0.1 -P 3306 -u rtcc -p code_coloring -e "SELECT VERSION();"
```

输出 `8.4.x` 即可。

> **3399 端口必须空着**：验收里有一项「数据库挂掉时平台照常工作」，做法是让平台去连
> `127.0.0.1:3399` 这个没人监听的端口。如果这台机器上有程序占着 3399，那一项会出现误判。

---

## 4. 目标机：工具链与环境变量

### 4.1 解开 devtools

放到一个**不含空格、不含中文**的路径下，本手册统一用 `C:\rtcc\devtools`：

```bash
mkdir -p /c/rtcc
tar -xf /c/rtcc-offline/devtools.tar -C /c/rtcc
ls /c/rtcc/devtools
```

应该看到：`apache-maven-3.9.16  cargo  go  jdk-17.0.20+8  mingw64  rustup  xwin`

解包过程中**不应该出现任何报错**。如果看到 `Cannot create symlink to 'rustup.exe'`，
说明打包时漏了 `-h`（见 2.2），要回开发机重新打包。

### 4.2 配置环境变量（写进 `~/.bashrc`）

启动脚本给每个工具链都留了环境变量，**先读环境变量，没有才用开发机上的默认路径**。
目标机的路径和开发机不同，所以要把它们写进 Git Bash 的 `~/.bashrc`：

```bash
cat >> ~/.bashrc <<'EOF'

# ---- 代码染色平台：工具链位置（Git Bash 路径写法 /c/...，不要写成 C:\...）----
export RTCC_TOOLS=/c/rtcc/devtools
export JAVA_HOME=$RTCC_TOOLS/jdk-17.0.20+8
export MVN_HOME=$RTCC_TOOLS/apache-maven-3.9.16
export GO_HOME=$RTCC_TOOLS/go
export MINGW_HOME=$RTCC_TOOLS/mingw64
export RUSTUP_HOME=$RTCC_TOOLS/rustup
export CARGO_HOME=$RTCC_TOOLS/cargo
export XWIN_HOME=$RTCC_TOOLS/xwin
export PUPPETEER_HOME=/c/rtcc/node-deps/node_modules/puppeteer
export CHROME_BIN="/c/Program Files/Google/Chrome/Application/chrome.exe"
# go.mod 要求 Go 1.26；禁止 Go 在版本不够时联网下载工具链、联网拉模块
export GOTOOLCHAIN=local
export GOPROXY=off
# 让手动执行的 mvn / go / gcc 等命令也能找到（启动脚本自己也会拼一遍 PATH）
export PATH="$JAVA_HOME/bin:$MVN_HOME/bin:$GO_HOME/bin:$MINGW_HOME/bin:$RUSTUP_HOME/toolchains/stable-x86_64-pc-windows-gnu/lib/rustlib/x86_64-pc-windows-gnu/bin:$CARGO_HOME/bin:$PATH"
EOF
source ~/.bashrc
```

有三点要注意：

1. **必须用 Git Bash 的路径写法**（`/c/rtcc/...`）。写成 `C:\rtcc\...` 的话，盘符里的冒号会把 PATH 拆坏；
2. **这些变量不能放进 `.env.local`**。启动脚本是先拼好 PATH、再读 `.env.local` 的，写在那里已经来不及；
3. 第一次打开新的 Git Bash 窗口时，如果看到「Found ~/.bashrc but no ~/.bash_profile」之类的提示，
   是 Git Bash 在自动建 `~/.bash_profile`，属于正常现象。

### 4.3 Rust 工具链不需要额外操作

`rustup` 目录里的 `settings.toml` 已经把默认工具链设成了 `stable-x86_64-pc-windows-gnu`，
msvc 目标的标准库、`llvm-tools`、`rust-lld` 都在 stable 工具链里。
只要 `RUSTUP_HOME` 和 `CARGO_HOME` 指对了（4.2 已经设好），不需要再执行任何 `rustup` 命令。
离线机器上执行 `rustup update`、`rustup target add` 之类的命令也不会成功。

### 4.4 安装 puppeteer

```bash
mkdir -p /c/rtcc/node-deps && cd /c/rtcc/node-deps
# npm 如果要走内网镜像，先执行：npm config set registry <内网 npm 镜像地址>
PUPPETEER_SKIP_DOWNLOAD=true npm install puppeteer@25.3.0
ls node_modules/puppeteer/lib/puppeteer/puppeteer.js
```

**`PUPPETEER_SKIP_DOWNLOAD=true` 不能省**：puppeteer 安装时默认会去 Google 下载一个专用浏览器，
离线必然失败。我们用的是 3.4 装好的 Chrome，由 `CHROME_BIN` 指定。

如果是从开发机带过来的 `node-deps` 文件夹（2.5），直接把它放到 `C:\rtcc\node-deps` 即可。

### 4.5 Maven 仓库（二选一）

**方式一：连内网 Maven 镜像。** 新建或编辑 `C:\Users\<你的用户名>\.m2\settings.xml`：

```xml
<settings>
  <mirrors>
    <mirror>
      <id>internal</id>
      <mirrorOf>*</mirrorOf>
      <url>http://换成内网Maven镜像地址/</url>
    </mirror>
  </mirrors>
</settings>
```

**方式二：用开发机带来的本地仓库**（2.4）：

```bash
mkdir -p ~/.m2
tar -xf /c/rtcc-offline/m2-repository.tar -C ~/.m2
```

并在 `~/.m2/settings.xml` 里写上离线模式，免得 Maven 去连中央仓库：

```xml
<settings>
  <offline>true</offline>
</settings>
```

### 4.6 逐项核对

把下面整段粘进 Git Bash，每一行的输出都要和注释里的期望一致：

```bash
"$JAVA_HOME/bin/java" -version 2>&1 | head -1          # openjdk version "17.0.20"
"$MVN_HOME/bin/mvn" -v 2>&1 | head -1                  # Apache Maven 3.9.16
"$GO_HOME/bin/go" version                              # go version go1.26.6 windows/amd64
"$GO_HOME/bin/go" env GOTOOLCHAIN GOPROXY | tr '\n' ' '; echo   # local off
"$MINGW_HOME/bin/g++" --version | head -1              # ...16.2.0
"$MINGW_HOME/bin/gcov" --version | head -1             # gcov ...16.2.0
"$MINGW_HOME/bin/gcov-tool" --version 2>&1 | head -1   # gcov-tool.exe ...16.2.0
"$CARGO_HOME/bin/rustc" -V                             # rustc 1.97.1 (8bab26f4f 2026-07-14)
"$CARGO_HOME/bin/cargo" -V                             # cargo 1.97.1 (c980f4866 2026-06-30)
"$CARGO_HOME/bin/rustup" target list --installed       # 两行：x86_64-pc-windows-gnu / x86_64-pc-windows-msvc
ls "$RUSTUP_HOME/toolchains/stable-x86_64-pc-windows-gnu/lib/rustlib/x86_64-pc-windows-gnu/bin" \
  | grep -E "^(llvm-profdata|llvm-cov|rust-lld)\.exe$"  # 三行：llvm-cov.exe / llvm-profdata.exe / rust-lld.exe
ls "$XWIN_HOME/crt/lib/x86_64/msvcrt.lib" \
   "$XWIN_HOME/sdk/lib/um/x86_64/kernel32.lib" \
   "$XWIN_HOME/sdk/lib/ucrt/x86_64/ucrt.lib"           # 三个文件都存在
node -v                                                # v22 或更高
type -ap python | head -1                              # 不是 .../WindowsApps/python
git --version                                          # git version 2.x
ls "$PUPPETEER_HOME/lib/puppeteer/puppeteer.js"        # 文件存在
ls "$CHROME_BIN"                                       # 文件存在
```

有任何一行不对，先解决它再往下走。后面的构建和验收要十几分钟，
缺的东西会在跑到一半时才暴露出来。

---

## 5. 目标机：取出仓库、配置数据库连接

### 5.1 从 bundle 克隆

```bash
cd /c/rtcc
git clone /c/rtcc-offline/realtime_code_coloring.bundle realtime_code_coloring
cd realtime_code_coloring
git checkout main
git log -1 --oneline     # ec30c61 合并 dev：v0.9.2 —— ……
git status --short       # 没有输出 = 工作树干净
```

仓库放在哪里都可以：平台配置里的路径都是相对仓库写的。

### 5.2 写 `.env.local`（数据库连接）

在仓库根目录建 `.env.local`。它已被 gitignore，不会进 git：

```bash
cat > .env.local <<'EOF'
COVERAGE_DB_URL='jdbc:mysql://127.0.0.1:3306/code_coloring?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8'
COVERAGE_DB_USER='rtcc'
COVERAGE_DB_PASSWORD='换成 3.5 里设的密码'
EOF
```

**每个值都必须用单引号括起来。** 启动脚本是用 bash 读这个文件的，连接地址里的 `&`
在 bash 里是「放到后台执行」的意思。不加引号的话，整行会被拆成几条命令，
`COVERAGE_DB_URL` **根本不会被赋值，而且不报任何错**。平台随后会悄悄改用内置的默认地址
`127.0.0.1:3306/code_coloring`。数据库如果不在这个地址，你看到的只是
「趋势不可用」，根本看不出是这一行写错了。

写完核对一下，完整的连接地址要能原样打印出来：

```bash
( set -a; . ./.env.local; set +a; echo "$COVERAGE_DB_URL" )
```

输出必须以 `...&characterEncoding=utf8` 结尾。如果输出是空的，或者在 `useSSL=false` 处就截断了，
就是引号没加对。

---

## 6. 构建并启动（形态 A）

```bash
cd /c/rtcc/realtime_code_coloring
bash scripts/run_local.sh start
```

它会依次做这几件事：

1. `mvn clean package`：构建平台和 Java 演示服务，并跑完 246 条单测（第一次会下载 Maven 依赖）；
2. 带覆盖率插桩构建 Go 演示服务（`-cover -covermode=atomic -tags=goverage`）；
3. 带覆盖率插桩构建 C++ 演示服务（`--coverage`）；
4. 带覆盖率插桩构建 Rust 演示服务（msvc 目标 + `-C instrument-coverage`）；
5. 启动 8 个被测实例，每个都挂着探针；
6. 启动平台。

成功时最后几行是：

```
    demo-service#1     http://localhost:18080   探针 tcp://localhost:6300
    ……（8 个实例）
==> 启动染色平台
    platform      http://localhost:18090   ← 打开这个看染色
```

日志都在 `.run/` 下：平台是 `.run/platform.log`，各实例是 `.run/demo*.log`。

> 第一次运行时，Windows 防火墙可能会就 `java.exe`、`demo-go.exe` 等程序弹出询问。
> 只在本机访问的话，允许不允许都不影响：探针和平台都经由本机地址访问。
> 要让别的电脑也能打开平台页面，才需要放行 18090（先读第 11 节）。

启动之后核对两件事：

```bash
# 1) 8 个实例都连上了。刚启动的几秒里可能显示 UNKNOWN，那是第一轮采集还没跑完，等几秒再查
curl -s http://localhost:18090/api/coverage/summary | python -c "import sys,json; d=json.load(sys.stdin); print(d['probeStatus'], sum(i['status']=='CONNECTED' for i in d['instances']), '/', len(d['instances']))"
# 期望：CONNECTED 8 / 8

# 2) 数据库连通
curl -s http://localhost:18090/api/coverage/trend | python -c "import sys,json; print(json.load(sys.stdin)['available'])"
# 期望：True。如果是 False，去看第 10 节「趋势不可用」那一条
```

然后用 Chrome 打开 http://localhost:18090 ，就能看到代码染色页面。

---

## 7. 全量验收

```bash
bash scripts/run_local.sh verify > .run/verify.log 2>&1; echo "verify 退出码：$?"
tail -3 .run/verify.log
```

它会先重启全部 8 个被测实例（业务状态只能靠重启复位），然后依次跑 10 套端到端用例，
再跑 1 套真实 Chrome 的前端验收，开发机上约 4 分钟。

**判定成败：退出码必须是 0，并且下面几个数字全部对上**：

```bash
grep -c '验收结论：全部通过'     .run/verify.log   # 期望 9
grep -c '推送链路验证：全部通过' .run/verify.log   # 期望 1
grep -c '前端验收：全部通过'     .run/verify.log   # 期望 1
grep -c '\[PASS\]' .run/verify.log                # 期望 259
grep -c '\[FAIL\]' .run/verify.log                # 期望 0
```

三种结论文案加起来是 11 套，**先数套数，再看结论**。`verify` 遇到失败的用例会立即停下，
后面几套不再运行，所以套数少于 11 说明是中途中止了。这时去 `.run/verify.log` 里找第一个 `[FAIL]` 或报错。

不要把 `verify` 的输出接到 `| tail` 之类的管道后面再看退出码：那样拿到的是 `tail` 的退出码，
`verify` 失败了也会显示 0。上面这样直接重定向到文件，退出码才可信。

几条前提：

- **平台必须已经在运行**（执行过 `start`）。`verify` 只重启被测实例，不启动平台；
- **仓库工作树必须干净**。被测源码有未提交的改动时，实例自报的版本会带上 `-dirty`，
  平台会按设计拒绝出增量报告，增量相关的用例必然失败；
- 重启电脑之后，或者从全停状态恢复，顺序固定是 **`stop` → `start` → `verify`**。
  不要直接跑 `verify`：它会先把 8 个实例拉起来，这时 Java 实例已经占住了
  `platform/target/classes/probe/jacocoagent.jar`，接着再跑 `start` 时，`mvn clean` 会因为删不掉这个文件而失败。

**得到「11 套全部通过、259 PASS / 0 FAIL」，形态 A 的部署就完成了。**

---

## 8. 形态 B：接入你自己的被测服务

> **不要在接着真实业务的机器上跑 `verify`**：它会重启平台、清零计数器、临时建项目，
> 正在录的场景和正在采的数据都会被打断。

### 8.1 先想清楚：平台要和被测服务在同一台机器上

所有探针都**只绑本机地址**（`127.0.0.1` / `localhost`）。这是刻意的：探针有一个清零计数器的接口，
绑到所有网卡上，同网段的任何人都能随手把你正在录的场景作废。

所以平台只能采集**同一台机器上**的被测服务。不要为了跨机采集，把探针改成绑所有网卡。

### 8.2 单独启动平台

只用平台、不跑演示服务时：

```bash
cd /c/rtcc/realtime_code_coloring/platform
set -a; . ../.env.local; set +a
java -jar target/platform-0.9.2.jar
```

平台运行时需要：

- JDK 17；
- `git` 在 PATH 里（算增量覆盖率时用）；
- 采集哪种语言，就要让对应的工具在 PATH 里：Go 要 `go`；C++ 要 `gcov` 和 `gcov-tool`；
  Rust 要 `llvm-profdata` 和 `llvm-cov`，而且必须与编译被测服务的 rustc 同版本。
  4.2 配好的 PATH 已经全部包含。也可以用启动参数指定绝对路径，比如
  `--coverage.go-tool=<go.exe 路径>`（同类的还有 `--coverage.gcov-tool`、`--coverage.gcov-merge-tool`、
  `--coverage.llvm-profdata-tool`、`--coverage.llvm-cov-tool`）。

**项目配置存在数据库里，不在 `application.yml` 里。** 平台第一次启动时，会把 jar 里
`application.yml` 的配置写进数据库，作为 `default` 项目（指向那 8 个演示实例）；
之后每次启动都从数据库读。所以接入自己的服务要在页面上配，不要改 yml。

### 8.3 在页面上接入

1. 打开「项目管理」页，点「**+ 新建项目**」，按 6 步向导填写。每一步都会当场校验，
   第 6 步的自检全部通过才能创建；
2. 打开「**服务接入**」页：它会根据你填的参数，现场生成各语言被测服务的启动命令，
   并提供探针物料下载。这一页只生成配置，不负责保存；
3. 要修改配置，统一在「**项目设置**」页保存。

探针物料也可以直接下载：

| 语言 | 下载地址 | 得到的文件 |
|---|---|---|
| Java | http://localhost:18090/api/probe/artifacts/java | `jacocoagent.jar` |
| Go | http://localhost:18090/api/probe/artifacts/go | `coverage_agent.go` |
| C++ | http://localhost:18090/api/probe/artifacts/cpp | `coverage_agent.cpp` |
| Rust | http://localhost:18090/api/probe/artifacts/rust | `coverage_agent.c` |

### 8.4 各语言的要点

四种语言都做到了**业务源码零改动**，只是 Go、C++、Rust 要带插桩重新编译一次。
完整约定见 `.claude/skills/rtcc-probe-setup/SKILL.md` 和页面上的「接入帮助」。
这里只列最容易出错的几条：

- **所有语言**：被测实例要自报构建版本，也就是 40 位 commit 号，工作树脏时加 `-dirty`。
  Java 通过 `sessionid` 传，其余语言通过环境变量 `COVERAGE_BUILD_ID` 传。
  **同一个服务的多个实例必须报完全相同的值**；不报的话，增量覆盖率不可用。
- **Java**：启动时加 `-javaagent:jacocoagent.jar=includes=<你的包名>.*,output=tcpserver,address=localhost,port=<探针端口>,sessionid=<版本>`。
  平台侧的地址写 `java://localhost:<探针端口>`。项目的 classes 目录必须是**同一次构建**的产物。
- **Go**：把 `coverage_agent.go` 放进 `main` 包所在的目录，用
  `go build -cover -covermode=atomic -tags=goverage` 构建。`-covermode=atomic` 不能省，否则无法清零计数器。
  运行时设置 `COVERAGE_ADDR=127.0.0.1:<端口>`。平台侧写 `go://`。
- **C++**：在源码根目录下编译。业务代码加 `--coverage`，对象文件路径用**绝对路径**；
  `coverage_agent.cpp` 单独编译，**不加** `--coverage`；链接时加 `--coverage -lws2_32`。
  运行时每个实例要设三个变量：`GCOV_PREFIX=<这个实例独占的目录>`、`GCOV_PREFIX_STRIP=99`、
  `COVERAGE_DATA_DIR=<与 GCOV_PREFIX 相同的目录>`。**`COVERAGE_DATA_DIR` 不能省**：不设的话，
  探针会从当前目录开始，递归收集底下所有的 `.gcda`。两台实例在同一个工作目录下时，
  每台都会把两台的数据一起交上来，清零时也一起删掉，聚合结果串了实例却看不出异常。
  平台侧写 `cpp://`，并配好 `.gcno` 所在的目录。
- **Rust**：必须编成 `x86_64-pc-windows-msvc` 目标，gnu 目标走不通。链接器用 `rust-lld`，
  库文件用 xwin 的，具体参数照抄 `scripts/run_local.sh` 里的 `build_rust`。
  每个实例的 `LLVM_PROFILE_FILE` 必须指向不同的文件，而且只能写**字面路径**，不能用 `%p` 这类占位符。
  平台侧写 `rust://`，产物路径指向编出来的 exe。

### 8.5 容器化部署（产物仓库）

被测服务跑在容器里、平台够不着它的文件系统时，由 CI 在构建后把编译产物推给平台：
`POST /api/artifacts/<buildId>?project=<项目>&lang=<java|cpp|rust>`。
目前这种项目**只能通过 API 创建**，页面上的新建向导建不出来。上传接口**没有鉴权**，
只能在内网使用（见第 11 节）。

---

## 9. 日常运维

| 要做什么 | 命令 |
|---|---|
| 启动全部（会重新构建） | `bash scripts/run_local.sh start` |
| 停止全部 | `bash scripts/run_local.sh stop` |
| 全量验收 | `bash scripts/run_local.sh verify > .run/verify.log 2>&1` |
| 只重启平台（比如改了 `.env.local`） | `bash scripts/run_local.sh platform-restart` |

- **重启电脑之后**：MySQL 是 Windows 服务，会自己起来；平台和被测实例不会，按 `stop` → `start` 的顺序重新拉起。
- **数据库暂时不可用时**：平台照常启动，采集、染色、门禁都照常，只有覆盖率趋势不可用，保存项目配置会返回 503。
- **升级到新版本**：在开发机上重新打一个 bundle 拷过来，然后：

  ```bash
  cd /c/rtcc/realtime_code_coloring
  git pull --ff-only /c/rtcc-offline/realtime_code_coloring.bundle main
  bash scripts/run_local.sh stop
  bash scripts/run_local.sh start
  bash scripts/run_local.sh verify > .run/verify.log 2>&1
  ```

  如果新版本换了工具链版本（对照 `scripts/run_local.sh` 顶部和本手册第 1 节），要一并带新的工具链过去。

---

## 10. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `mvn: command not found`，退出码 127 | 手动执行 mvn 时没加载工具链环境变量。这不是测试失败，而是一条测试都没跑 | `source ~/.bashrc`；用 `run_local.sh` 就不会遇到 |
| `python: Permission denied`，或者 `verify` 半路中断 | 碰上了 Windows 的 Python 占位别名 | 按 3.2 关掉「应用执行别名」，或者 `export PY=<python 路径>` |
| Go 构建时出现 `go: downloading go1.26...` 并卡住，或者报 `go.mod requires go >= 1.26` | Go 版本不够，又在尝试联网下载工具链 | 用 devtools 里的 Go 1.26.6，并确认 `GOTOOLCHAIN=local` |
| 解包时出现 `Cannot create symlink to 'rustup.exe'`；之后 `cargo`、`rustc` 找不到 | 打包时漏了 `-h`，包里存的是符号链接，Windows 上还原不出来 | 回开发机按 2.2 重新打包，并做 2.2 末尾的两项核对 |
| Rust 报 `can't find crate for profiler_builtins` | 编成了 gnu 目标，或者 stable 工具链里缺 msvc 目标的标准库 | 用 `run_local.sh` 构建；确认 4.6 里 `target list` 有 msvc 那一行 |
| Rust 链接报找不到 `msvcrt.lib`、`kernel32.lib` 等 | `XWIN_HOME` 没指对 | 检查 4.6 里 xwin 那三个文件 |
| Rust 编出的 exe 报「not a valid application for this OS platform」 | 走了 gnu 目标的路线 | 只能用 msvc 目标（见 8.4） |
| `start` 时 `mvn clean` 删不掉 `jacocoagent.jar` | 还有 Java 被测实例在运行，占着这个文件 | 先 `stop`，再 `start` |
| `verify` 第一套就报「连接被拒绝」 | 平台没在运行 | 按 `stop` → `start` → `verify` 的顺序重来 |
| 增量、漂移相关的用例失败，日志里实例版本带 `-dirty` | 被测源码有未提交的改动 | 提交或还原改动，让工作树恢复干净 |
| 前端验收报 `Failed to launch the browser process` | `CHROME_BIN` 不对，或者 Chrome 正在更新自己 | 检查 `CHROME_BIN`；等 Chrome 更新完再跑。不要用 `chrome.exe --version` 排查 |
| `verify` 一开始就提示「puppeteer 不在 …」 | `PUPPETEER_HOME` 指错了 | 它要指向含有 `lib/puppeteer/puppeteer.js` 的那个 `puppeteer` 目录 |
| `npm install puppeteer` 卡住，或者报下载 Chrome 失败 | 没有跳过浏览器下载 | 加上 `PUPPETEER_SKIP_DOWNLOAD=true` 重装 |
| 趋势不可用（`available: false`），或者保存项目返回 503 | 平台连不上数据库 | 按顺序查：MySQL 服务是否在运行 → `.env.local` 的每个值是否都加了单引号（5.2 里的核对命令）→ 账号主机是否是 `127.0.0.1` |
| 某些日志或输出里的中文是乱码 | Windows 控制台用的是 GBK 编码，只影响显示 | 需要用管道处理 Python 的输出时，加 `PYTHONIOENCODING=utf-8` |
| 端口被占用（`Address already in use`） | 附录 A 里的某个端口被别的程序占了 | 用 `netstat -ano \| grep <端口>` 找到占用的进程并处理掉 |
| 端到端染色延迟偶尔超过 5 秒 | 机器太忙（比如杀毒软件正在扫描刚解开的工具链） | 等机器空闲再验；可以把 `C:\rtcc` 加进 Windows Defender 的排除项（可选） |

---

## 11. 安全边界

- **探针只绑本机地址**，任何时候都不要改成绑所有网卡（原因见 8.1）。
- **平台在 18090 端口上监听所有网卡**，而且**没有登录鉴权**。产物上传接口（`/api/artifacts`）
  能往平台的磁盘上写文件。所以平台只能部署在内网；需要让别的电脑访问时，
  用 Windows 防火墙把 18090 只放行给可信的机器。
- **`.env.local` 里是数据库密码**，已被 gitignore，不要提交。本仓库在 GitHub 上是公开的。
- **MySQL 账号只绑 `127.0.0.1`**，不要把 3306 端口开放到网络上。

---

## 附录 A：端口

| 端口 | 谁在用 | 监听范围 |
|---|---|---|
| 18090 | 染色平台（页面、API、WebSocket `/ws/coverage`） | 所有网卡 |
| 18080 / 18081 | Java 演示服务 1 / 2 号 | 所有网卡 |
| 6300 / 6301 | Java 探针（JaCoCo tcpserver） | 仅本机 |
| 18070 / 18071 | Go 演示服务 1 / 2 号 | 所有网卡 |
| 6400 / 6401 | Go 探针 | 仅本机 |
| 18060 / 18061 | C++ 演示服务 1 / 2 号 | 所有网卡 |
| 6500 / 6501 | C++ 探针 | 仅本机 |
| 18050 / 18051 | Rust 演示服务 1 / 2 号 | 所有网卡 |
| 6600 / 6601 | Rust 探针 | 仅本机 |
| 3306 | MySQL | 按 MySQL 的配置 |
| 3399 | **必须空着**：验收用它模拟「数据库连不上」 | — |

## 附录 B：环境变量

| 变量 | 写在哪里 | 作用 |
|---|---|---|
| `JAVA_HOME` / `MVN_HOME` / `GO_HOME` / `MINGW_HOME` / `RUSTUP_HOME` / `CARGO_HOME` / `XWIN_HOME` | `~/.bashrc` | 工具链位置。不设的话，脚本会用开发机上的默认路径 |
| `PUPPETEER_HOME` / `CHROME_BIN` | `~/.bashrc` | 前端验收用的 puppeteer 和 Chrome |
| `GOTOOLCHAIN=local` / `GOPROXY=off` | `~/.bashrc` | 禁止 Go 联网下载工具链和模块 |
| `PY` | `~/.bashrc`（可选） | 指定 Python 解释器，跳过自动查找 |
| `COVERAGE_DB_URL` / `COVERAGE_DB_USER` / `COVERAGE_DB_PASSWORD` | 仓库根目录的 `.env.local`（值要加单引号） | 数据库连接 |
| `COVERAGE_ARTIFACT_ROOT` | 可选 | 产物仓库的根目录，默认是 `platform/.artifacts` |

## 附录 C：部署完成后的目录结构

```
C:\rtcc\
├── devtools\                    工具链（从开发机拷来）
│   ├── jdk-17.0.20+8\
│   ├── apache-maven-3.9.16\
│   ├── go\
│   ├── mingw64\
│   ├── rustup\                  只含 stable 工具链
│   ├── cargo\
│   └── xwin\
├── node-deps\node_modules\puppeteer\
└── realtime_code_coloring\      仓库（从 bundle 克隆）
    ├── .env.local               数据库连接（不进 git）
    ├── .run\                    运行日志、C++/Rust 的覆盖数据、验收日志（不进 git）
    ├── platform\target\platform-0.9.2.jar
    ├── demo-service\  demo-service-go\  demo-service-cpp\  demo-service-rust\
    ├── scripts\run_local.sh     启动 / 停止 / 验收的唯一入口
    └── docs\deploy-windows-offline.md   本手册
```
