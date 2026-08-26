#!/usr/bin/env bash
# 本地起一套完整环境：被测服务（挂探针）+ 染色平台
#
#   bash scripts/run_local.sh start   启动
#   bash scripts/run_local.sh stop    停止
#   bash scripts/run_local.sh verify  跑端到端验收
#
# JDK / Maven 若不在 PATH，通过 JAVA_HOME、MVN_HOME 指定。
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)

# 版本号在本脚本里只出现这一处。原先两处 jar 路径各写一遍，改 pom 版本号漏改一处
# 只会报「文件不存在」，看不出根因是版本没跟上（CLAUDE.md 第二个坑）
VERSION=0.5.0

JAVA_HOME="${JAVA_HOME:-/c/Users/Administrator/devtools/jdk-17.0.20+8}"
MVN_HOME="${MVN_HOME:-/c/Users/Administrator/devtools/apache-maven-3.9.16}"
GO_HOME="${GO_HOME:-/c/Users/Administrator/devtools/go}"
GO="$GO_HOME/bin/go.exe"
# 平台自己也要调 gcov / gcov-tool 做归一化，所以工具链要进 PATH，不只是构建时用
MINGW_HOME="${MINGW_HOME:-/c/Users/Administrator/devtools/mingw64}"
# Rust 同理：平台要调 llvm-profdata / llvm-cov。这两个工具的版本必须与 rustc 匹配，
# 所以取 rustup 自带的 llvm-tools，而不是系统上任何一个 LLVM
RUSTUP_HOME="${RUSTUP_HOME:-/c/Users/Administrator/devtools/rustup}"
CARGO_HOME="${CARGO_HOME:-/c/Users/Administrator/devtools/cargo}"
RUST_TOOLCHAIN="$RUSTUP_HOME/toolchains/stable-x86_64-pc-windows-gnu"
RUST_LLVM_BIN="$RUST_TOOLCHAIN/lib/rustlib/x86_64-pc-windows-gnu/bin"
# Windows 上 LLVM 覆盖率只有 msvc 目标能用（见 CLAUDE.md），而 msvc 目标要链接
# 微软的 CRT 与 SDK 导入库。xwin 只拉这些库文件，不装 Visual Studio
XWIN_HOME="${XWIN_HOME:-/c/Users/Administrator/devtools/xwin}"
# 前端验收要开真实 Chrome。这两个也是「跟着机器走」的路径，与工具链同等对待
PUPPETEER_HOME="${PUPPETEER_HOME:-/c/Users/Administrator/AppData/Roaming/npm/node_modules/@mermaid-js/mermaid-cli/node_modules/puppeteer}"
CHROME_BIN="${CHROME_BIN:-/c/Program Files/Google/Chrome/Application/chrome.exe}"
export JAVA_HOME RUSTUP_HOME CARGO_HOME PUPPETEER_HOME CHROME_BIN
export PATH="$JAVA_HOME/bin:$MVN_HOME/bin:$GO_HOME/bin:$MINGW_HOME/bin:$RUST_LLVM_BIN:$CARGO_HOME/bin:$PATH"

# 数据库凭据从 .env.local 读（已 gitignore）。没有这个文件也要能跑：
# 覆盖率历史连不上就退化成「趋势不可用」，采集与染色不受影响
if [ -f "$ROOT/.env.local" ]; then
  set -a; . "$ROOT/.env.local"; set +a
fi

LOG_DIR="$ROOT/.run"
mkdir -p "$LOG_DIR"

# 脏标记必须按「全部被测源码根」判定，两种语言用同一个范围。
# 各算各的话，只改了 Go 源码时 Java 实例报 <sha>、Go 实例报 <sha>-dirty，
# 平台判成「实例间版本不一致」，把人引去核对版本，而真正的原因是有未提交的改动
SOURCE_ROOTS="demo-service/src demo-service-go demo-service-cpp demo-service-rust"

# C++ 的对象文件目录。.gcda 落在哪里是编译时把对象文件路径写死进产物决定的，
# 所以这里必须用绝对路径：用相对路径的话，.gcda 会跟着进程的工作目录跑
CPP_OBJ=$(cygpath -m "$ROOT/.run/cpp-obj")
# Rust 探针的对象文件与各实例的 .profraw
RUST_RUN=$(cygpath -m "$ROOT/.run/rust")

# 等待服务就绪；启动失败或超时都要报错退出，不能无限等下去
wait_ready() {
  local log=$1 marker=$2 name=$3 waited=0
  while true; do
    if grep -q "$marker" "$log" 2>/dev/null; then return 0; fi
    if grep -qE "APPLICATION FAILED|failed to init|Error occurred|Address already in use" "$log" 2>/dev/null; then
      echo "!! $name 启动失败:"; tail -12 "$log"; exit 1
    fi
    if [ $waited -ge 90 ]; then
      echo "!! $name 90 秒内未就绪:"; tail -12 "$log"; exit 1
    fi
    sleep 1; waited=$((waited + 1))
  done
}

# 按监听端口找到真实进程并终止。注意 $! 记录的是 Git Bash 的 job PID，
# kill 它并不能终止 Windows 上的 java.exe。
kill_port() {
  local pid
  pid=$(netstat -ano 2>/dev/null | grep -E "LISTENING" | grep -E ":$1\b" | awk '{print $NF}' | head -1 || true)
  if [ -n "${pid:-}" ]; then
    taskkill //F //PID "$pid" >/dev/null 2>&1 || true
  fi
}

# 起一个被测实例：$1=HTTP 端口 $2=探针端口 $3=日志/进程名 $4=sessionid（可选）
start_demo() {
  local http=$1 probe=$2 name=$3 sid=${4:-}

  # sessionid 让被测实例把自己的构建版本带出来，平台据此校验增量口径能否对齐。
  # 工作树脏时打上 -dirty 标记：此时产物对不上任何一个提交，增量必须拒绝出报告。
  if [ -z "$sid" ]; then
    local commit dirty=""
    commit=$(git rev-parse HEAD)
    if [ -n "$(git status --porcelain -- $SOURCE_ROOTS)" ]; then dirty="-dirty"; fi
    sid="$commit$dirty"
  fi

  # 注意：java.exe 是 Windows 程序，认不得 Git Bash 的 /c/... 路径，
  # 传给它的路径必须是相对路径或 Windows 路径。
  java -javaagent:platform/target/agent/jacocoagent.jar=includes=com.shop.*,output=tcpserver,address=localhost,port=$probe,sessionid=$sid \
       -jar "demo-service/target/demo-service-$VERSION.jar" --server.port=$http > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$LOG_DIR/$name.pid"
  wait_ready "$LOG_DIR/$name.log" "Started DemoServiceApplication" "$name"
}

# Go 被测服务。源码零改动：探针文件由 build tag 守卫、与 main 同包，
# init() 自动执行，业务代码不 import 也不调用任何东西。
# 但 Go 编译为原生机器码，运行期无法插桩，必须带 -cover 重新构建一次。
# $1=HTTP 端口 $2=探针端口 $3=日志/进程名
start_demo_go() {
  local http=$1 probe=$2 name=$3
  local commit dirty=""
  commit=$(git rev-parse HEAD)
  if [ -n "$(git status --porcelain -- $SOURCE_ROOTS)" ]; then dirty="-dirty"; fi

  COVERAGE_ADDR="127.0.0.1:$probe" COVERAGE_BUILD_ID="$commit$dirty" \
    ./.run/demo-go.exe -addr=:$http > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$LOG_DIR/$name.pid"
  wait_ready "$LOG_DIR/$name.log" "demo-service-go started" "$name"
}

build_go() {
  ( cd "$ROOT/demo-service-go" && \
    "$GO" build -cover -covermode=atomic -tags=goverage -o "$ROOT/.run/demo-go.exe" . )
}

# C++ 被测服务。同样是源码零改动：探针 coverage_agent.cpp 是独立编译单元，
# 靠全局对象的构造函数（早于 main）自动启动，业务代码不 include 也不调用它。
# 正常构建根本不编译这个文件，也就没有任何开销。
#
# 编译的工作目录必须是源码根：.gcno 里记的是编译时的相对源码名，
# 平台侧的 gcov 要在同一个目录下才找得到源码。
# 对象文件用绝对路径：.gcda 的落点是编译期写死进产物的。
build_cpp() {
  mkdir -p "$ROOT/.run/cpp-obj"
  rm -f "$ROOT"/.run/cpp-obj/*.gcno "$ROOT"/.run/cpp-obj/*.gcda
  cd "$ROOT/demo-service-cpp"
  g++ -std=c++17 -O0 -g --coverage -c order.cpp -o "$CPP_OBJ/order.o"
  g++ -std=c++17 -O0 -g --coverage -c main.cpp  -o "$CPP_OBJ/main.o"
  # 探针不插桩：它测的是自己，不是被测代码
  g++ -std=c++17 -O0 -c coverage_agent.cpp -o "$CPP_OBJ/coverage_agent.o"
  g++ -o "$ROOT/.run/demo-cpp.exe" \
      "$CPP_OBJ/order.o" "$CPP_OBJ/main.o" "$CPP_OBJ/coverage_agent.o" --coverage -lws2_32
  cd "$ROOT"
}

# 起一个 C++ 被测实例：$1=HTTP 端口 $2=探针端口 $3=日志/进程名 $4=实例序号
#
# GCOV_PREFIX 是多实例的关键：.gcda 的落点在编译期就写死进产物了，
# 两个实例不分开就会往同一个文件互相覆盖，聚合出来的是「最后写的那一份」。
# STRIP=99 把原路径的目录层级全剥掉，只留文件名，目录才是平的。
start_demo_cpp() {
  local http=$1 probe=$2 name=$3 idx=$4
  local commit dirty="" data
  commit=$(git rev-parse HEAD)
  if [ -n "$(git status --porcelain -- $SOURCE_ROOTS)" ]; then dirty="-dirty"; fi
  rm -rf "$ROOT/.run/cpp-gcda-$idx"
  mkdir -p "$ROOT/.run/cpp-gcda-$idx"
  data=$(cygpath -m "$ROOT/.run/cpp-gcda-$idx")

  GCOV_PREFIX="$data" GCOV_PREFIX_STRIP=99 COVERAGE_DATA_DIR="$data" \
  COVERAGE_ADDR="127.0.0.1:$probe" COVERAGE_BUILD_ID="$commit$dirty" \
    ./.run/demo-cpp.exe -addr=:$http > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$LOG_DIR/$name.pid"
  wait_ready "$LOG_DIR/$name.log" "demo-service-cpp started" "$name"
}

# Rust 被测服务。源码零改动做得比 Go / C++ 还彻底 —— 连 Cargo.toml 都不用动：
# 探针是单独用 gcc 编译的 .o，靠 -C link-arg 注入，用 .CRT$XCU 段里的函数指针
# 在 main 之前自动执行。业务代码里没有任何一处提到它。
#
# 必须编成 msvc 目标：LLVM 的覆盖率元数据放在 MSVC 风格的分组 COFF 段里，
# GNU ld 排不出来，链出来的 exe 根本加载不了；官方也只给 msvc 目标发 profiler_builtins。
# 链接器用 rustup 自带的 rust-lld（lld-link 模式），库文件用 xwin 拉下来的，不装 VS。
build_rust() {
  mkdir -p "$ROOT/.run/rust"
  cd "$ROOT/demo-service-rust"
  # 探针用 MinGW 的 gcc 编译，产物却要链进 MSVC ABI 的 exe：
  # -mno-stack-arg-probe 去掉 MinGW 特有的 ___chkstk_ms 调用（MSVC 侧没有这个符号）。
  # 探针内部也因此全程只调 Win32 API，一句 CRT 都不碰
  gcc -c coverage_agent.c -o "$RUST_RUN/coverage_agent.o" -mno-stack-arg-probe -O1
  RUSTFLAGS="-C instrument-coverage -C link-arg=$RUST_RUN/coverage_agent.o -C link-arg=ws2_32.lib -L native=$(cygpath -m "$XWIN_HOME")/crt/lib/x86_64 -L native=$(cygpath -m "$XWIN_HOME")/sdk/lib/um/x86_64 -L native=$(cygpath -m "$XWIN_HOME")/sdk/lib/ucrt/x86_64 -C linker=$(cygpath -m "$RUST_LLVM_BIN")/rust-lld.exe -C linker-flavor=lld-link" \
    cargo build --release --target x86_64-pc-windows-msvc
  cd "$ROOT"
}

# 起一个 Rust 被测实例：$1=HTTP 端口 $2=探针端口 $3=日志/进程名 $4=实例序号
#
# LLVM_PROFILE_FILE 必须每实例一个：LLVM 运行时按这个路径落盘，
# 两个实例共用一个文件会互相覆盖，聚合出来的是「最后写的那一份」
# —— 与 C++ 侧 GCOV_PREFIX 要解决的是同一个问题。
start_demo_rust() {
  local http=$1 probe=$2 name=$3 idx=$4
  local commit dirty=""
  commit=$(git rev-parse HEAD)
  if [ -n "$(git status --porcelain -- $SOURCE_ROOTS)" ]; then dirty="-dirty"; fi
  rm -f "$ROOT/.run/rust/demo-$idx.profraw"

  LLVM_PROFILE_FILE="$RUST_RUN/demo-$idx.profraw" \
  COVERAGE_ADDR="127.0.0.1:$probe" COVERAGE_BUILD_ID="$commit$dirty" \
    ./demo-service-rust/target/x86_64-pc-windows-msvc/release/demo-service-rust.exe -addr=:$http \
    > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$LOG_DIR/$name.pid"
  wait_ready "$LOG_DIR/$name.log" "demo-service-rust started" "$name"
}

# 四种语言各起两个实例：同一服务多实例部署时，负载均衡会把请求分到任意一台，
# 只看其中一台必然少算。四种语言的合并走的是四条不同的代码路径
# （Java 在 exec 层取或，Go 交给 covdata 按块求和，C++ 用 gcov-tool merge，
# Rust 用 llvm-profdata merge），都要有实例可摆布才验得了
start_all_demos() {
  start_demo 18080 6300 demo
  start_demo 18081 6301 demo2
  start_demo_go 18070 6400 demo-go
  start_demo_go 18071 6401 demo-go2
  start_demo_cpp 18060 6500 demo-cpp  1
  start_demo_cpp 18061 6501 demo-cpp2 2
  start_demo_rust 18050 6600 demo-rust  1
  start_demo_rust 18051 6601 demo-rust2 2
}

start() {
  echo "==> 构建 Java"
  mvn -q -B clean package
  echo "==> 构建 Go（带覆盖率插桩）"
  build_go
  echo "==> 构建 C++（带覆盖率插桩）"
  build_cpp
  echo "==> 构建 Rust（带覆盖率插桩）"
  build_rust

  echo "==> 启动被测服务（源码均零改动）"
  start_all_demos
  echo "    demo-service#1     http://localhost:18080   探针 tcp://localhost:6300"
  echo "    demo-service#2     http://localhost:18081   探针 tcp://localhost:6301"
  echo "    demo-service-go#1  http://localhost:18070   探针 http://localhost:6400"
  echo "    demo-service-go#2  http://localhost:18071   探针 http://localhost:6401"
  echo "    demo-service-cpp#1 http://localhost:18060   探针 http://localhost:6500"
  echo "    demo-service-cpp#2 http://localhost:18061   探针 http://localhost:6501"
  echo "    demo-service-rust#1 http://localhost:18050  探针 http://localhost:6600"
  echo "    demo-service-rust#2 http://localhost:18051  探针 http://localhost:6601"

  echo "==> 启动染色平台"
  start_platform
  echo "    platform      http://localhost:18090   ← 打开这个看染色"
}

# 起平台。数据库地址取当前环境里的 COVERAGE_DB_URL，
# 因此 platform-dbdown 只要覆盖这个变量即可，不必另抄一份启动命令
start_platform() {
  kill_port 18090
  sleep 2
  ( cd "$ROOT/platform" && exec java -jar "target/platform-$VERSION.jar" ) > "$LOG_DIR/platform.log" 2>&1 &
  echo $! > "$LOG_DIR/platform.pid"
  wait_ready "$LOG_DIR/platform.log" "Started PlatformApplication" "platform"
}

ALL_PORTS="18050 18051 18060 18061 18070 18071 18080 18081 18090"

stop() {
  # 最后复核端口确实已释放，避免谎报成功
  for port in $ALL_PORTS; do kill_port $port; done
  rm -f "$LOG_DIR"/*.pid

  sleep 2
  local alive=""
  for port in $ALL_PORTS; do
    if netstat -ano 2>/dev/null | grep -E "LISTENING" | grep -qE ":$port\b"; then
      alive="$alive $port"
    fi
  done
  if [ -n "$alive" ]; then
    echo "!! 以下端口仍被占用：$alive"
    exit 1
  fi
  echo "已停止（$ALL_PORTS 均已释放）"
}

# python 要固定成一个具体路径。PATH 里排在前面的
# %LOCALAPPDATA%/Microsoft/WindowsApps/python 是个 0 字节的 Store 别名，
# 执行时报 Permission denied；bash 有时跳过它、有时不跳。撞上的那一次，
# verify 会在中途被 set -e 掐断，后面几套用例根本没跑，输出看上去却像正常收尾。
# 逐行读而不是 for ... in $(...)：后者按空格分词，
# 装在 "/c/Program Files/..." 下的 python 会被拆成 /c/Program
resolve_python() {
  [ -n "${PY:-}" ] && return 0
  while IFS= read -r cand; do
    case "$cand" in */WindowsApps/*) continue ;; esac
    PY="$cand"; return 0
  done < <(type -ap python python3 2>/dev/null || true)
  echo "!! 找不到可用的 python（Store 别名不算）"
  return 1
}

# 前端验收要 node + puppeteer + 真实 Chrome。必须在开跑之前探一次：
# 它排在 verify 的最后一步，缺依赖的话会在前面九套用例跑完十几分钟之后才炸，
# 而那时候人早走开了。PUPPETEER_HOME 的默认值指向 mermaid-cli 的嵌套 node_modules，
# npm 的提升策略一变这个路径就没了 —— 正是最需要早说的那种缺失
resolve_ui_deps() {
  command -v node >/dev/null 2>&1 || { echo "!! 找不到 node，前端验收跑不了"; return 1; }
  [ -f "$PUPPETEER_HOME/lib/puppeteer/puppeteer.js" ] || {
    echo "!! puppeteer 不在 $PUPPETEER_HOME —— 用 PUPPETEER_HOME 指到正确位置"; return 1; }
  [ -f "$CHROME_BIN" ] || { echo "!! 找不到 Chrome：$CHROME_BIN —— 用 CHROME_BIN 指过去"; return 1; }
  return 0
}

verify() {
  # 只有 verify 用得上 python。放在顶层的话，没装 python 的机器连 stop 都跑不了
  resolve_python
  resolve_ui_deps

  # 订单一旦进入终态就回不到 CREATED，业务状态只能靠重启被测实例复位。
  # 少了这一步，P0 用例第二次跑就会走进「重复回调」分支而失败。
  echo "==> 重启全部被测实例，复位业务状态"
  for port in 18050 18051 18060 18061 18070 18071 18080 18081; do kill_port $port; done
  # 等端口真正释放，否则新进程可能撞上「Address already in use」
  sleep 2
  start_all_demos
  echo

  "$PY" scripts/e2e_verify.py
  echo
  node scripts/ws_verify.js
  echo
  "$PY" scripts/e2e_incremental.py
  echo
  # 放最后：场景 start 会清零计数器，跑在其他用例之前会把它们的覆盖数据洗掉
  "$PY" scripts/e2e_scenario.py
  echo
  "$PY" scripts/e2e_multi_instance.py
  echo
  "$PY" scripts/e2e_go.py
  echo
  "$PY" scripts/e2e_cpp.py
  echo
  "$PY" scripts/e2e_rust.py
  echo
  # 放最后：它会建项目、跑场景（start 会清零计数器），排在别的用例前面会洗掉它们的数据
  "$PY" scripts/e2e_project.py
  echo
  # 放在 e2e_project 之后：它同样要点「清零计数器」才能验染色链路，
  # 而 e2e_project 结尾会重启平台，正好由 ui_verify 自己等平台就绪
  node scripts/ui_verify.js
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  verify) verify ;;
  # 以下三条供 e2e_multi_instance.py 摆布 2 号实例：起停 JVM 属于脚本的活，不塞进 Python
  demo2-stop) kill_port 18081 ;;
  demo2-start) kill_port 18081; sleep 2; start_demo 18081 6301 demo2 ;;
  # 用一个与仓库任何提交都对不上的版本重启 2 号实例，构造「实例间版本不一致」
  demo2-mismatch) kill_port 18081; sleep 2; start_demo 18081 6301 demo2 "$(printf '%040d' 1)" ;;
  # 同一提交但标记为 dirty：commit 相同、字节码不同，最容易被漏判成「版本一致」
  demo2-dirty) kill_port 18081; sleep 2; start_demo 18081 6301 demo2 "$(git rev-parse HEAD)-dirty" ;;
  # 以下两条供 e2e_project.py 摆布平台自身：验证数据库不可用时的降级
  # 指向一个没人监听的端口，比停掉真数据库安全 —— 后者会波及机器上别的服务
  platform-dbdown)
    # 必须 export：平台是在子 shell 里 exec 起来的，只有导出的变量才传得进去
    export COVERAGE_DB_URL="jdbc:mysql://127.0.0.1:3399/code_coloring?useSSL=false&connectTimeout=2000"
    export COVERAGE_DB_PASSWORD="nope"
    start_platform ;;
  platform-restart) start_platform ;;
  *) echo "用法: $0 {start|stop|verify|demo2-stop|demo2-start|demo2-mismatch|demo2-dirty|platform-dbdown|platform-restart}"; exit 1 ;;
esac
