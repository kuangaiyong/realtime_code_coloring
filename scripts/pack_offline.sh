#!/usr/bin/env bash
# 离线部署一键打包：在联网的开发机上备齐目标机（不能上外网）部署所需的物料，最后逐项自检。
#
#   bash scripts/pack_offline.sh [输出目录]        # 默认 /c/rtcc-offline
#   bash scripts/pack_offline.sh --check [目录]    # 只核对一份已有的离线包
#
# 包里有：仓库 bundle、工具链（devtools.tar.xz）、Git / MySQL / Chrome / Node.js / Python 安装包、
# 部署手册副本、README.txt、SHA256SUMS.txt。Maven 依赖、npm 包、pip 包不带 —— 目标机走私服与代理。
# 目标机上怎么用，见 docs/deploy-windows-offline.md。自检的回归用例：scripts/test_pack_offline.sh

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_OUT=/c/rtcc-offline
RTCC_TOOLS="${RTCC_TOOLS:-/c/Users/Administrator/devtools}"

# 安装包取本机验收所用的版本：验收结论只对这些版本成立。Chrome 例外 —— Google 只提供最新版的企业 MSI
GIT_VER=2.53.0.2
GIT_TAG=v2.53.0.windows.2
MYSQL_VER=8.4.8
NODE_VER=24.14.1
PY_VER=3.14.3

# 键|文件名|签名者（证书主体里必须出现的组织名）|下载地址（可多个，依次试）
INSTALLERS=(
  "Git|Git-${GIT_VER}-64-bit.exe|Johannes Schindelin|https://github.com/git-for-windows/git/releases/download/${GIT_TAG}/Git-${GIT_VER}-64-bit.exe"
  "MySQL|mysql-${MYSQL_VER}-winx64.msi|Oracle America, Inc.|https://cdn.mysql.com/Downloads/MySQL-${MYSQL_VER%.*}/mysql-${MYSQL_VER}-winx64.msi https://cdn.mysql.com/archives/mysql-${MYSQL_VER%.*}/mysql-${MYSQL_VER}-winx64.msi"
  "Chrome|googlechromestandaloneenterprise64.msi|Google LLC|https://dl.google.com/dl/chrome/install/googlechromestandaloneenterprise64.msi"
  "Node|node-v${NODE_VER}-x64.msi|OpenJS Foundation|https://nodejs.org/dist/v${NODE_VER}/node-v${NODE_VER}-x64.msi"
  "Python|python-${PY_VER}-amd64.exe|Python Software Foundation|https://www.python.org/ftp/python/${PY_VER}/python-${PY_VER}-amd64.exe"
)

# 工具链压缩包里必须有的成员：平台与四种语言的构建要直接调用的程序，以及链接 Rust msvc 目标要的导入库
REQUIRED=(
  '^devtools/jdk-[^/]+/bin/java\.exe$'
  '^devtools/apache-maven-[^/]+/bin/mvn$'
  '^devtools/go/bin/go\.exe$'
  '^devtools/mingw64/bin/g\+\+\.exe$'
  '^devtools/mingw64/bin/gcov\.exe$'
  '^devtools/mingw64/bin/gcov-tool\.exe$'
  '^devtools/rustup/settings\.toml$'
  '^devtools/rustup/toolchains/stable-x86_64-pc-windows-gnu/bin/rustc\.exe$'
  '^devtools/rustup/toolchains/stable-x86_64-pc-windows-gnu/lib/rustlib/x86_64-pc-windows-gnu/bin/llvm-profdata\.exe$'
  '^devtools/rustup/toolchains/stable-x86_64-pc-windows-gnu/lib/rustlib/x86_64-pc-windows-gnu/bin/llvm-cov\.exe$'
  '^devtools/rustup/toolchains/stable-x86_64-pc-windows-gnu/lib/rustlib/x86_64-pc-windows-gnu/bin/rust-lld\.exe$'
  '^devtools/rustup/toolchains/stable-x86_64-pc-windows-gnu/lib/rustlib/x86_64-pc-windows-msvc/lib/libstd-[^/]+\.rlib$'
  '^devtools/cargo/bin/cargo\.exe$'
  '^devtools/cargo/bin/rustc\.exe$'
  '^devtools/cargo/bin/rustup\.exe$'
  '^devtools/xwin/crt/lib/x86_64/msvcrt\.lib$'
  '^devtools/xwin/sdk/lib/um/x86_64/kernel32\.lib$'
  '^devtools/xwin/sdk/lib/ucrt/x86_64/ucrt\.lib$'
)

# 判据里的正则变回人看得懂的路径：^devtools/jdk-[^/]+/bin/java\.exe$ → jdk-*/bin/java.exe
label_of() { sed -E 's/^\^devtools\///; s/\$$//; s/\[\^\/\]\+/*/g; s/\\//g' <<< "$1"; }

_pass() { echo "[通过] $1"; }
_fail() { echo "[失败] $1：$2"; CHECK_FAILED=$((CHECK_FAILED + 1)); }
# 跳过的项是「判不了」，不是通过：只要有一项跳过，结论就不能写成全部通过
_skip() { echo "[跳过] $1：$2"; CHECK_SKIPPED=$((CHECK_SKIPPED + 1)); }

# 逐项核对一份离线包：有失败返回 1；没有失败、但有项没法核对返回 2；全部通过才返回 0。
# 不往包目录里写任何东西 —— 多出一个文件，第 9 项就该报失败
check_package() {
  local dir="${1%/}" tmp e key file signer name re out heads links line status subject want got unlisted
  local errexit=0 missing=() bad=() paths=()
  # 每一项自己判成败，不能让 set -e 在第一个不匹配的 grep 上把整个自检打断
  [[ $- == *e* ]] && errexit=1
  set +e
  CHECK_FAILED=0
  CHECK_SKIPPED=0
  tmp="$(mktemp -d)"
  echo "==> 自检：$dir"

  # 1. 该有的文件都在
  local expected=(realtime_code_coloring.bundle devtools.tar.xz installers/node-SHASUMS256.txt
                  deploy-windows-offline.md README.txt SHA256SUMS.txt)
  for e in "${INSTALLERS[@]}"; do IFS='|' read -r _ file _ <<< "$e"; expected+=("installers/$file"); done
  for file in "${expected[@]}"; do [ -f "$dir/$file" ] || missing+=("$file"); done
  if [ ${#missing[@]} -eq 0 ]; then _pass "该有的文件都在"; else _fail "该有的文件都在" "缺 ${missing[*]}"; fi

  # 2. 仓库 bundle：目标机要从它克隆出 main；只带部分历史的克隆不出来，增量也没法与 HEAD~1 比
  local bundle="$dir/realtime_code_coloring.bundle"
  if [ ! -f "$bundle" ]; then
    _skip "仓库 bundle 完整" "文件不在"
  elif ! out=$(LC_ALL=C git -C "$ROOT" bundle verify "$bundle" 2>&1); then
    _fail "仓库 bundle 完整" "$(head -1 <<< "$out")"
  elif ! grep -q 'records a complete history' <<< "$out"; then
    _fail "仓库 bundle 完整" "不是完整历史"
  # 引用清单先存下来再找：直接接 grep -q 的话，grep 找到就退出，git 还没写完的部分会写失败，
  # pipefail 下整条管道算失败，完好的 bundle 会被报成没有 main
  elif ! heads=$(LC_ALL=C git -C "$ROOT" bundle list-heads "$bundle") || ! grep -q ' refs/heads/main$' <<< "$heads"; then
    _fail "仓库 bundle 完整" "没有 main 分支"
  else
    _pass "仓库 bundle 完整"
  fi

  # 3–6. 一次列表供 3、4、5 用：能完整列出即压缩包完整（xz 解压时会校验 CRC）
  local tarball="$dir/devtools.tar.xz"
  if [ ! -f "$tarball" ]; then
    for name in "工具链压缩包完整" "关键程序都在包里" "包里没有符号链接" "包里的文件与原目录一致"; do _skip "$name" "文件不在"; done
  # --numeric-owner：属主、属组列印成编号。打包机器的属组名带空格（加入域的机器上是「Domain Users」）时，
  # 印成名字会多出一列，下面按列切出来的成员名就全错位了
  elif ! tar -tvJf "$tarball" --numeric-owner > "$tmp/list" 2> "$tmp/err"; then
    _fail "工具链压缩包完整" "$(head -1 "$tmp/err")"
    for name in "关键程序都在包里" "包里没有符号链接" "包里的文件与原目录一致"; do _skip "$name" "压缩包解不开"; done
  else
    _pass "工具链压缩包完整"
    # 成员名：去掉前 5 列（权限、属主、大小、日期、时间）和链接的去向
    sed -E 's/^([^ ]+ +){5}//; s/ (link to|->) .*$//' "$tmp/list" > "$tmp/names"
    # 不分大小写：Windows 上文件名本来就不分，Windows SDK 里实际是 kernel32.Lib（真打包时误报过一次「缺」）
    missing=()
    for re in "${REQUIRED[@]}"; do grep -Eiq "$re" "$tmp/names" || missing+=("$(label_of "$re")"); done
    if [ ${#missing[@]} -eq 0 ]; then _pass "关键程序都在包里"; else _fail "关键程序都在包里" "缺 ${missing[*]}"; fi

    # Windows 上解包建不出符号链接：cargo/bin 下那几个指向 rustup.exe 的链接一缺，cargo / rustc 就全没了
    links=$(grep '^l' "$tmp/list" | sed -E 's/^([^ ]+ +){5}//' | head -5 | tr '\n' ' ')
    if [ -z "$links" ]; then
      _pass "包里没有符号链接"
    else
      _fail "包里没有符号链接" "${links}（打包时要用 tar -h 把链接换成真实文件）"
    fi

    # 带 -h 比对：打包时由链接变成的硬链接不会报假差异，内容一变就报
    if [ ! -d "$RTCC_TOOLS" ]; then
      _skip "包里的文件与原目录一致" "原目录不在：$RTCC_TOOLS"
    elif tar -dhJf "$tarball" -C "$(dirname "$RTCC_TOOLS")" > "$tmp/diff" 2>&1; then
      _pass "包里的文件与原目录一致"
    else
      _fail "包里的文件与原目录一致" "$(head -3 "$tmp/diff" | tr '\n' ' ')（共 $(wc -l < "$tmp/diff") 处）"
    fi
  fi

  # 7. 安装包签名：既证明没下坏，也证明是那家厂商的。一次 PowerShell 核对全部，每行「文件名|状态|签名者」
  for e in "${INSTALLERS[@]}"; do
    IFS='|' read -r _ file _ <<< "$e"
    [ -f "$dir/installers/$file" ] && paths+=("$(cygpath -w "$dir/installers/$file")")
  done
  if [ ${#paths[@]} -eq 0 ]; then
    _skip "安装包签名有效" "一个安装包都不在"
  else
    PKG_PATHS="$(IFS='|'; echo "${paths[*]}")" powershell.exe -NoProfile -NonInteractive -Command \
      '$env:PKG_PATHS.Split("|") | ForEach-Object { $s = Get-AuthenticodeSignature -LiteralPath $_; "{0}|{1}|{2}" -f (Split-Path $_ -Leaf), $s.Status, $s.SignerCertificate.Subject }' \
      2>&1 | tr -d '\r' > "$tmp/sig"
    bad=()
    for e in "${INSTALLERS[@]}"; do
      IFS='|' read -r _ file signer _ <<< "$e"
      [ -f "$dir/installers/$file" ] || continue
      line=$(grep -F "$file|" "$tmp/sig" | head -1)
      status=$(cut -d'|' -f2 <<< "$line")
      subject=$(cut -d'|' -f3- <<< "$line")
      if [ "$status" != Valid ]; then
        bad+=("$file：签名状态 ${status:-读不出}")
      elif [[ "$subject" != *"$signer"* ]]; then
        bad+=("$file：签名者是「$subject」，应为 $signer")
      fi
    done
    if [ ${#bad[@]} -eq 0 ]; then _pass "安装包签名有效"; else _fail "安装包签名有效" "${bad[*]}"; fi
  fi

  # 8. Node.js 公布了机器可读的校验和，另外比一次
  local node_msi="node-v${NODE_VER}-x64.msi" node_sums="$dir/installers/node-SHASUMS256.txt"
  if [ ! -f "$dir/installers/$node_msi" ] || [ ! -f "$node_sums" ]; then
    _skip "Node.js 安装包与官方校验和一致" "文件不在"
  else
    # sha256sum 的两种标准写法都认：「哈希  文件名」与二进制模式的「哈希 *文件名」
    want=$(awk -v f="$node_msi" '{ n = $2; sub(/^\*/, "", n) } n == f { print $1 }' "$node_sums")
    got=$(sha256sum "$dir/installers/$node_msi" | cut -d' ' -f1)
    if [ -n "$want" ] && [ "$want" = "$got" ]; then
      _pass "Node.js 安装包与官方校验和一致"
    else
      _fail "Node.js 安装包与官方校验和一致" "官方 ${want:-（清单里没有 $node_msi）}，实际 $got"
    fi
  fi

  # 9. 拷到别处之后用它核对；包里每个文件都得在清单上，漏掉的那个拷坏了也查不出来
  if [ ! -f "$dir/SHA256SUMS.txt" ]; then
    _skip "SHA256SUMS 与包内文件一致" "文件不在"
  else
    bad=()
    if ! (cd "$dir" && sha256sum -c --quiet SHA256SUMS.txt) > "$tmp/sumcheck" 2>&1; then
      bad+=("$(grep -E 'FAILED|No such file' "$tmp/sumcheck" | head -3 | tr '\n' ' ')")
    fi
    (cd "$dir" && find . -type f ! -name SHA256SUMS.txt | sed 's|^\./||' | LC_ALL=C sort) > "$tmp/files"
    sed -E 's/^[0-9a-f]{64} [ *]//' "$dir/SHA256SUMS.txt" | LC_ALL=C sort > "$tmp/listed"
    unlisted=$(LC_ALL=C comm -23 "$tmp/files" "$tmp/listed" | tr '\n' ' ')
    [ -z "$unlisted" ] || bad+=("没列进清单：$unlisted")
    if [ ${#bad[@]} -eq 0 ]; then _pass "SHA256SUMS 与包内文件一致"; else _fail "SHA256SUMS 与包内文件一致" "${bad[*]}"; fi
  fi

  rm -rf "$tmp"
  local rc=0
  if [ "$CHECK_FAILED" -gt 0 ]; then
    echo "离线包自检：$CHECK_FAILED 项失败"; rc=1
  elif [ "$CHECK_SKIPPED" -gt 0 ]; then
    echo "离线包自检：没有失败，但有 $CHECK_SKIPPED 项没法核对（见上面的 [跳过]），不能当作通过"; rc=2
  else
    echo "离线包自检：全部通过"
  fi
  [ "$errexit" = 1 ] && set -e
  return $rc
}

die() { echo "!! $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

# 先查前提再动手：任何一条不满足都在碰输出目录之前退出
preflight() {
  local out="$1" cmd re parent avail missing=()
  # 不替用户删、也不覆盖：要不要删旧包由用户决定
  if [ -e "$out" ] && [ -n "$(ls -A "$out" 2>/dev/null)" ]; then
    die "输出目录已存在且不为空：$out（换一个目录，或者先自己删掉它）"
  fi
  for cmd in git tar xz curl sha256sum cygpath powershell.exe; do
    command -v "$cmd" > /dev/null || missing+=("$cmd")
  done
  [ ${#missing[@]} -eq 0 ] || die "缺命令：${missing[*]}（要在 Git Bash 里运行）"
  [ "$(basename "$RTCC_TOOLS")" = devtools ] || die "工具链目录要叫 devtools（包里的路径和手册都按这个名字写）：$RTCC_TOOLS"
  # 原目录里就缺的东西，压十几分钟也压不出来
  for re in "${REQUIRED[@]}"; do
    compgen -G "$(dirname "$RTCC_TOOLS")/devtools/$(label_of "$re")" > /dev/null || missing+=("$(label_of "$re")")
  done
  [ ${#missing[@]} -eq 0 ] || die "工具链目录 $RTCC_TOOLS 里缺：${missing[*]}"
  parent="$out"
  while [ ! -d "$parent" ]; do parent="$(dirname "$parent")"; done
  avail=$(df -Pk "$parent" | awk 'NR == 2 { print $4 }')
  [ "$avail" -ge $((2500 * 1024)) ] || die "$parent 所在的盘只剩 $((avail / 1024)) MB，打包至少要 2500 MB"
}

pack_devtools() {
  # -h：cargo/bin 下的 cargo.exe、rustc.exe 等是指向 rustup.exe 的符号链接，Windows 上解包建不出链接，
  #     不解引用的话目标机上 cargo / rustc 全部缺失（手册 2.2 踩过）
  # 排除项：nightly 与 rustup 的下载缓存用不到；xwin 的两个 10.0.26100 是指向自身所在目录的链接，解引用会绕成死循环
  tar -ch -C "$(dirname "$RTCC_TOOLS")" \
    --exclude='devtools/rustup/toolchains/nightly-*' \
    --exclude='devtools/rustup/update-hashes/nightly-*' \
    --exclude=devtools/rustup/downloads \
    --exclude=devtools/rustup/tmp \
    --exclude=devtools/compiler-rt \
    --exclude=devtools/rustup-init.exe \
    --exclude=devtools/xwin/sdk/include/10.0.26100 \
    --exclude=devtools/xwin/sdk/lib/10.0.26100 \
    devtools | xz -T0 -6 > "$OUT/devtools.tar.xz.part"
  mv "$OUT/devtools.tar.xz.part" "$OUT/devtools.tar.xz"
}

download_installers() {
  local e file urls url got
  for e in "${INSTALLERS[@]}"; do
    IFS='|' read -r _ file _ urls <<< "$e"
    got=0
    for url in $urls; do
      echo "    $file ← $url"
      if curl -fL --retry 3 --connect-timeout 30 -sS -o "$OUT/installers/$file.part" "$url"; then got=1; break; fi
    done
    [ "$got" = 1 ] || die "下载不到 $file（试过：$urls）"
    mv "$OUT/installers/$file.part" "$OUT/installers/$file"
  done
  curl -fL --retry 3 --connect-timeout 30 -sS -o "$OUT/installers/node-SHASUMS256.txt" \
    "https://nodejs.org/dist/v${NODE_VER}/SHASUMS256.txt"
}

# MSI 的 ProductVersion，只读打开、不安装。Chrome 企业版 MSI 里就是 Chrome 的版本号
msi_version() {
  P="$(cygpath -w "$1")" powershell.exe -NoProfile -NonInteractive -Command \
    '$i = New-Object -ComObject WindowsInstaller.Installer; $db = $i.GetType().InvokeMember("OpenDatabase", "InvokeMethod", $null, $i, @($env:P, 0)); $v = $db.GetType().InvokeMember("OpenView", "InvokeMethod", $null, $db, @("SELECT Property, Value FROM Property")); [void]$v.GetType().InvokeMember("Execute", "InvokeMethod", $null, $v, $null); while (($r = $v.GetType().InvokeMember("Fetch", "InvokeMethod", $null, $v, $null)) -ne $null) { if ($r.GetType().InvokeMember("StringData", "GetProperty", $null, $r, 1) -eq "ProductVersion") { $r.GetType().InvokeMember("StringData", "GetProperty", $null, $r, 2); break } }' \
    2>/dev/null | tr -d '\r'
}

write_readme() {
  local chrome
  chrome=$(msi_version "$OUT/installers/googlechromestandaloneenterprise64.msi")
  cat > "$OUT/README.txt" <<EOF
代码实时染色平台 $(git -C "$ROOT" describe --tags main 2>/dev/null) 离线部署包
打包于 $(date '+%Y-%m-%d %H:%M')，main 分支指向 $(git -C "$ROOT" rev-parse --short main)

包里有什么
  realtime_code_coloring.bundle   仓库，带完整 git 历史（手册 5.1）
  devtools.tar.xz                 工具链：JDK 17 / Maven / Go / MinGW-w64 / Rust / xwin（手册 4.1）
  installers\\                     安装包（手册第 3 节）
    Git-${GIT_VER}-64-bit.exe          Git for Windows ${GIT_VER}
    mysql-${MYSQL_VER}-winx64.msi             MySQL ${MYSQL_VER}
    googlechromestandaloneenterprise64.msi   Google Chrome 企业版 ${chrome:-（读不出版本）}
    node-v${NODE_VER}-x64.msi              Node.js ${NODE_VER}
    python-${PY_VER}-amd64.exe            Python ${PY_VER}
    node-SHASUMS256.txt                Node.js 官方校验和
  deploy-windows-offline.md       部署手册（与本包配套的版本，以它为准）
  SHA256SUMS.txt                  以上所有文件的校验和

不在包里、由目标机自己装的：Maven 依赖走 Maven 私服，puppeteer 走 npm 代理（手册 4.4、4.5）

到了目标机
  1. 先装 Git（installers 里的 Git-${GIT_VER}-64-bit.exe），打开 Git Bash，核对文件没拷坏：
       cd /c/rtcc-offline && sha256sum -c SHA256SUMS.txt
     每一行都应是 OK
  2. 从手册第 3 节往下做
EOF
}

# 清单先写在包外面：写在包里的话，find 会把还没写完的清单自己也算进去。
# 也不算已有的旧清单 —— 换了手册或 README 之后重算时它还在，算进去的话新清单必然对不上
write_sums() {
  local tmp
  tmp="$(mktemp)"
  (cd "$OUT" && find . -type f ! -name SHA256SUMS.txt | sed 's|^\./||' | LC_ALL=C sort | xargs -d '\n' sha256sum) > "$tmp"
  mv "$tmp" "$OUT/SHA256SUMS.txt"
}

pack() {
  OUT="${1%/}"
  local t0=$SECONDS
  preflight "$OUT"
  mkdir -p "$OUT/installers"
  trap '[ $? -eq 0 ] || echo "!! 这份包不能用：$OUT（删掉这个目录后重跑）" >&2' EXIT

  step "1/5 仓库 bundle"
  git -C "$ROOT" bundle create "$OUT/realtime_code_coloring.bundle" --all
  step "2/5 工具链：$RTCC_TOOLS（3.8 GB，压缩要十几分钟）"
  pack_devtools
  step "3/5 安装包"
  download_installers
  step "4/5 手册副本、README、校验和"
  cp "$ROOT/docs/deploy-windows-offline.md" "$OUT/"
  write_readme
  write_sums
  step "5/5 自检"
  check_package "$OUT" || die "自检没过"

  echo
  echo "==> 离线包：$OUT"
  (cd "$OUT" && find . -type f -printf '%s %P\n' | LC_ALL=C sort -k2 | awk '{ printf "    %9.1f MB  %s\n", $1 / 1048576, $2 }')
  echo "    合计 $(du -sh "$OUT" | cut -f1)，用时 $(( (SECONDS - t0) / 60 )) 分 $(( (SECONDS - t0) % 60 )) 秒"
  echo "    下一步：把整个目录拷到目标机，照 README.txt 与手册第 3 节往下做"
}

main() {
  case "${1:-}" in
    --check) check_package "${2:-$DEFAULT_OUT}" ;;
    -h|--help) sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
    *) pack "${1:-$DEFAULT_OUT}" ;;
  esac
}

# 被 source 时只定义常量和函数（回归用例要取安装包清单），不执行
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  set -euo pipefail
  main "$@"
fi
