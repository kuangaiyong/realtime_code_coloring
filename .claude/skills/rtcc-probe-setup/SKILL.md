---
name: rtcc-probe-setup
description: 四种语言被测服务的启动约定与探针编译方式：Java 的 javaagent 参数、Go 的 build tag 探针与 -covermode=atomic、C++ 的 gcov 运行期两条硬事实（__gcov_dump 只生效一次、.gcda 会合并）、Rust 的 msvc 目标与 LLVM 两条硬事实。接被测服务、排查「采不到数据」、加新语言时读这份。
---

## 四、被测服务的启动约定

源码零改动，只加启动参数：

```
-javaagent:jacocoagent.jar=includes=com.shop.*,output=tcpserver,address=localhost,port=6300,sessionid=<40位commit>[-dirty]
```

`sessionid` 是平台唯一能拿到的「实例自报构建版本」，增量口径靠它校验行号能否对齐。
不配它则增量功能不可用（平台会明确报错，而不是给出错位的结果）。

多实例时每台用不同的探针端口，逐个填进平台的 `coverage.instances`。
**各实例的 sessionid 必须完全一致（含 `-dirty` 后缀）**：commit 相同但一台脏一台净，
加载的是两份不同的字节码，平台会判为版本冲突并拒绝出增量报告。

### Go

Go 编译为原生机器码，运行期没有可改写的中间表示，**必须带插桩重新编译一次**：

```bash
go build -cover -covermode=atomic -tags=goverage -o demo-go.exe .
COVERAGE_ADDR=127.0.0.1:6400 COVERAGE_BUILD_ID=<40位commit>[-dirty] ./demo-go.exe
```

但**既有业务源码一行不改**：探针 `coverage_agent.go` 由 build tag `goverage` 守卫、
与 `main` 同包，`init()` 自动执行，业务代码不 import 也不调用任何东西；
不带 tag 的生产构建里这个文件根本不参与编译。

- `-covermode=atomic` 是硬要求：`runtime/coverage.ClearCounters()` 只在 atomic 模式下可用，
  否则场景归因的清零会失败（Go 会明确报错，平台据此拒绝，不会静默把上一轮算进来）。
- `COVERAGE_BUILD_ID` 等价于 JaCoCo 的 `sessionid`，两种语言共用同一套版本校验。
  **脏标记要按全部被测源码根一起判定**：各语言各算各的话，只改了 Go 源码时
  Java 报 `<sha>`、Go 报 `<sha>-dirty`，平台判成「实例间版本不一致」，
  把人引去核对版本，而真正的原因是有未提交的改动。
- `COVERAGE_ADDR` **默认只绑回环**（`127.0.0.1:6400`），与 Java 侧的 `address=localhost` 对齐。
  写成 `:6400` 会绑到所有网卡，而 `/coverage/clear` 能清零计数器 ——
  等于把「正在录的那个场景」交给同网段的任何人随手作废。
- 探针地址在平台侧写作 `go://host:port`；不写语言前缀默认 `java`。

### C++

与 Go 同理，必须带插桩重新编译一次；**既有业务源码同样一行不改** ——
探针 `coverage_agent.cpp` 是独立编译单元，靠全局对象的构造函数（早于 `main` 执行）
自动启动，业务代码不 include 也不调用它任何东西；正常构建不编译这个文件。

```bash
g++ -std=c++17 --coverage -c order.cpp -o <绝对路径>/order.o   # 业务代码插桩
g++ -std=c++17            -c coverage_agent.cpp -o .../coverage_agent.o  # 探针不插桩
g++ -o demo-cpp.exe .../*.o --coverage -lws2_32

GCOV_PREFIX=<每实例一个目录> GCOV_PREFIX_STRIP=99 \
COVERAGE_DATA_DIR=<同一个目录> COVERAGE_ADDR=127.0.0.1:6500 \
COVERAGE_BUILD_ID=<40位commit>[-dirty] ./demo-cpp.exe
```

**gcov 运行期 API 的两条硬事实**（POC 实测得出，弄错就是静默错误的覆盖数据）：

1. `__gcov_dump()` **只生效一次**，之后必须 `__gcov_reset()` 重新武装，否则后续 dump
   什么都不写。而「没有 .gcda」与「计数器全零」在 gcov 输出里长得一模一样，全是 `#####`
   —— 这个坑第一次就踩到了，靠加一步「检查 .gcda 是否真的产生」才发现；
2. `.gcda` 写入时会与磁盘上已有内容**合并**。所以「dump + reset」交出的是累计值而非增量，
   轮询不丢历史；但真要清零就必须连 `.gcda` 一起删掉，光调 `__gcov_reset()` 是不够的。

其余约定：

- 编译的**工作目录必须是源码根**：`.gcno` 里记的是编译时的相对源码名，
  平台侧的 `gcov` 要在同一目录下才找得到源码（`coverage.cpp-source-root`）。
- **对象文件必须用绝对路径**：`.gcda` 的落点是编译期写死进产物的，用相对路径会跟着
  进程的工作目录跑。
- `GCOV_PREFIX` 是多实例的关键：不分开的话两个实例会往同一个 `.gcda` 互相覆盖，
  聚合出来的是「最后写的那一份」。`STRIP=99` 把目录层级剥光，目录才是平的。
  **已知限制**：剥平之后，不同目录下同名的 `.cpp` 会撞车。本 demo 无同名文件；
  真接大型工程时要改成保留目录结构，并相应改 `gcov -o` 的调用方式。
- `.gcno` 是编译期产物，平台靠 `coverage.cpp-objects-dir` 找到它 ——
  相当于 Java 的 `classes-dir`，缺了它解不出任何行号。
- 探针地址在平台侧写作 `cpp://host:port`。

### Rust

同样必须带插桩重新编译一次；**源码零改动做得比 Go / C++ 还彻底 —— 连 `Cargo.toml`
都不用动**：探针 `coverage_agent.c` 是单独用 gcc 编译的 `.o`，构建时经 `-C link-arg`
注入，靠 `.CRT$XCU` 段里的函数指针在 `main` 之前自动执行（MSVC 启动代码会遍历该段）。
业务代码里没有任何一处提到它，正常构建也不会带上它。

```bash
gcc -c coverage_agent.c -o <绝对路径>/coverage_agent.o -mno-stack-arg-probe -O1
RUSTFLAGS="-C instrument-coverage \
  -C link-arg=<绝对路径>/coverage_agent.o -C link-arg=ws2_32.lib \
  -L native=<xwin>/crt/lib/x86_64 -L native=<xwin>/sdk/lib/um/x86_64 \
  -L native=<xwin>/sdk/lib/ucrt/x86_64 \
  -C linker=<toolchain>/lib/rustlib/x86_64-pc-windows-gnu/bin/rust-lld.exe \
  -C linker-flavor=lld-link" \
  cargo build --release --target x86_64-pc-windows-msvc

LLVM_PROFILE_FILE=<每实例一个文件>.profraw COVERAGE_ADDR=127.0.0.1:6600 \
COVERAGE_BUILD_ID=<40位commit>[-dirty] ./demo-service-rust.exe
```

**Windows 上必须编成 `x86_64-pc-windows-msvc`，gnu 目标走不通**（实测结论，
别再试第二次）：

1. 官方只给 msvc 目标发 `profiler_builtins`，gnu 目标上 `-C instrument-coverage`
   直接 E0463；
2. 想用 `-Z build-std=...,profiler_builtins` 自建也不行 —— 它的 `build.rs` 要
   compiler-rt 源码（`RUST_COMPILER_RT_FOR_PROFILER`），备齐之后能编出来，
   但**链出来的 exe 根本加载不了**（"not a valid application for this OS platform"）：
   LLVM 把覆盖率元数据放在 MSVC 风格的 `$`-分组 COFF 段里，GNU ld 排不出正确的布局。

因此路线是「msvc 目标 + rustup 自带的 `rust-lld`（`lld-link` 模式）+ xwin 拉来的
CRT/SDK 导入库」，**不装 Visual Studio**。xwin 只下载库文件（约 630MB）。

**LLVM 运行期 API 的两条硬事实**（POC 实测得出，弄错就是静默错误的覆盖数据）：

1. `__llvm_profile_write_file()` **可以反复调用**，不像 gcov 的 `__gcov_dump()`
   那样只生效一次 —— 这一点比 C++ 省事；
2. 但它写入时会与磁盘上已有的 `.profraw` **合并**。所以每次 dump 前必须先把文件删掉，
   否则交回的是「历次累计 + 本次」的重复叠加；真要清零时，
   光调 `__llvm_profile_reset_counters()` 也不够，同样得连文件一起删。

其余约定：

- `LLVM_PROFILE_FILE` **每个实例必须指向不同的文件**：LLVM 运行时按这个路径落盘，
  共用一个文件的话两个实例互相覆盖，聚合出来的是「最后写的那一份」
  —— 与 C++ 侧 `GCOV_PREFIX` 要解决的是同一个问题。
  而且**必须是字面路径，不能用 `%p` / `%m` 之类的模式**：探针要按同一个字符串
  删掉旧文件（见上面第 2 条），模式由 LLVM 展开，探针删不到，
  交回的就成了历次累计的叠加 —— 而这在界面上看不出任何异样。
- 行号信息在产物自带的 coverage mapping 里，所以平台要配 `coverage.rust-binary`
  指向**产物本身**（相当于 Java 的 `classes-dir`、C++ 的 `.gcno`）。
- 探针用 MinGW 的 gcc 编译却要链进 MSVC ABI 的产物，因此 `-mno-stack-arg-probe`
  不能省（否则出现 MSVC 侧没有的 `___chkstk_ms` 符号），
  探针内部也全程只调 Win32 API，一句 CRT 都不碰。
- `COVERAGE_ADDR` 默认只绑回环（`127.0.0.1:6600`），与其余三种语言一致。
- 探针地址在平台侧写作 `rust://host:port`。
