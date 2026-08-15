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

JAVA_HOME="${JAVA_HOME:-/c/Users/Administrator/devtools/jdk-17.0.20+8}"
MVN_HOME="${MVN_HOME:-/c/Users/Administrator/devtools/apache-maven-3.9.16}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$MVN_HOME/bin:$PATH"

LOG_DIR="$ROOT/.run"
mkdir -p "$LOG_DIR"

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

start_demo() {
  # sessionid 让被测实例把自己的构建版本带出来，平台据此校验增量口径能否对齐。
  # 工作树脏时打上 -dirty 标记：此时产物对不上任何一个提交，增量必须拒绝出报告。
  local commit dirty=""
  commit=$(git rev-parse HEAD)
  if [ -n "$(git status --porcelain -- demo-service/src)" ]; then dirty="-dirty"; fi

  # 注意：java.exe 是 Windows 程序，认不得 Git Bash 的 /c/... 路径，
  # 传给它的路径必须是相对路径或 Windows 路径。
  java -javaagent:platform/target/agent/jacocoagent.jar=includes=com.shop.*,output=tcpserver,address=localhost,port=6300,sessionid=$commit$dirty \
       -jar demo-service/target/demo-service-0.2.0.jar > "$LOG_DIR/demo.log" 2>&1 &
  echo $! > "$LOG_DIR/demo.pid"
  wait_ready "$LOG_DIR/demo.log" "Started DemoServiceApplication" "demo-service"
}

start() {
  echo "==> 构建"
  mvn -q -B clean package

  echo "==> 启动被测服务（源码零改动，仅挂载 JaCoCo 探针）"
  start_demo
  echo "    demo-service  http://localhost:18080   探针 tcp://localhost:6300"

  echo "==> 启动染色平台"
  ( cd "$ROOT/platform" && exec java -jar target/platform-0.2.0.jar ) > "$LOG_DIR/platform.log" 2>&1 &
  echo $! > "$LOG_DIR/platform.pid"
  wait_ready "$LOG_DIR/platform.log" "Started PlatformApplication" "platform"
  echo "    platform      http://localhost:18090   ← 打开这个看染色"
}

stop() {
  # 最后复核端口确实已释放，避免谎报成功
  kill_port 18080
  kill_port 18090
  rm -f "$LOG_DIR"/*.pid

  sleep 2
  local alive=""
  for port in 18080 18090; do
    if netstat -ano 2>/dev/null | grep -E "LISTENING" | grep -qE ":$port\b"; then
      alive="$alive $port"
    fi
  done
  if [ -n "$alive" ]; then
    echo "!! 以下端口仍被占用：$alive"
    exit 1
  fi
  echo "已停止（18080 / 18090 均已释放）"
}

verify() {
  # 订单一旦进入终态就回不到 CREATED，业务状态只能靠重启被测实例复位。
  # 少了这一步，P0 用例第二次跑就会走进「重复回调」分支而失败。
  echo "==> 重启被测服务，复位业务状态"
  kill_port 18080
  start_demo
  echo

  python scripts/e2e_verify.py
  echo
  node scripts/ws_verify.js
  echo
  python scripts/e2e_incremental.py
  echo
  # 放最后：场景 start 会清零计数器，跑在其他用例之前会把它们的覆盖数据洗掉
  python scripts/e2e_scenario.py
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  verify) verify ;;
  *) echo "用法: $0 {start|stop|verify}"; exit 1 ;;
esac
