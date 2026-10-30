#!/usr/bin/env bash
# 离线打包自检（pack_offline.sh --check）的回归用例：在迷你离线包里逐条打进一种缺陷，
# 确认自检在对应那一项上报失败；完好的对照包则每一项都通过。
#
# 为什么不打一份真包来测：真包要联网下 400 MB 安装包、压 3.8 GB 工具链，十几分钟，不适合当回归。
# 每条判据都能用迷你包证明它不是恒绿。安装包签名那一项拿本机已装的程序冒充安装包 ——
# 它们与安装包是同一家签名，而判据看的正是「签名有效、签名者对」。
#
#   bash scripts/test_pack_offline.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACK="$ROOT/scripts/pack_offline.sh"
# 只为取版本号与安装包清单；被 source 时脚本不会打包
source "$PACK"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
pass=0
fail=0
ok()  { echo "  [PASS] $*"; pass=$((pass + 1)); }
bad() { echo "  [FAIL] $*"; fail=$((fail + 1)); }
undecidable() { echo "判不了：$*"; exit 2; }

# 本机已装、与各安装包同一家签名的程序
declare -A STAND_IN=(
  [Git]="/c/Program Files/Git/git-bash.exe"
  [MySQL]="/c/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe"
  [Chrome]="${CHROME_BIN:-/c/Program Files/Google/Chrome/Application/chrome.exe}"
  [Node]="/c/Program Files/nodejs/node.exe"
  [Python]="${PY:-/c/Users/Administrator/AppData/Local/Python/pythoncore-3.14-64/python.exe}"
)
for k in "${!STAND_IN[@]}"; do [ -f "${STAND_IN[$k]}" ] || undecidable "缺签名对照程序 ${STAND_IN[$k]}"; done

# 迷你工具链目录：关键程序都在（内容无所谓），cargo/bin 下照真目录建两条指向 rustup.exe 的符号链接。
# kernel32.Lib 照 Windows SDK 的实际大小写：判据区分大小写的话，真包上会把它误报成缺失（2026-10-30 真打包时踩到）
make_source() {
  local d="$1/devtools" rl=rustup/toolchains/stable-x86_64-pc-windows-gnu/lib/rustlib p
  for p in jdk-17.0.0/bin/java.exe apache-maven-3.9.0/bin/mvn go/bin/go.exe \
           mingw64/bin/g++.exe mingw64/bin/gcov.exe mingw64/bin/gcov-tool.exe rustup/settings.toml \
           rustup/toolchains/stable-x86_64-pc-windows-gnu/bin/rustc.exe \
           "$rl/x86_64-pc-windows-gnu/bin/llvm-profdata.exe" "$rl/x86_64-pc-windows-gnu/bin/llvm-cov.exe" \
           "$rl/x86_64-pc-windows-gnu/bin/rust-lld.exe" "$rl/x86_64-pc-windows-msvc/lib/libstd-0123456789abcdef.rlib" \
           xwin/crt/lib/x86_64/msvcrt.lib xwin/sdk/lib/um/x86_64/kernel32.Lib xwin/sdk/lib/ucrt/x86_64/ucrt.lib \
           cargo/bin/rustup.exe; do
    mkdir -p "$d/$(dirname "$p")"
    echo "$p" > "$d/$p"
  done
  for p in cargo.exe rustc.exe; do
    (cd "$d/cargo/bin" && MSYS=winsymlinks:nativestrict ln -s rustup.exe "$p") 2>/dev/null
    [ -L "$d/cargo/bin/$p" ] || undecidable "建不出符号链接（要管理员或开发者模式）"
  done
}

# 包内全部文件（除 SHA256SUMS.txt 自己）的校验和。先删再写：用例的包是硬链接复制出来的，原地改会改到对照包
resum() {
  rm -f "$1/SHA256SUMS.txt"
  (cd "$1" && find . -type f ! -name SHA256SUMS.txt | sed 's|^\./||' | sort | xargs -d '\n' sha256sum) > "$WORK/sums.tmp"
  mv "$WORK/sums.tmp" "$1/SHA256SUMS.txt"
}

# 原地改之前先断开硬链接，免得改到对照包
detach() { cp "$1" "$1.tmp" && rm "$1" && mv "$1.tmp" "$1"; }

SRC="$WORK/src"
make_source "$SRC"
MINI_REPO="$WORK/repo"
git init -q -b main "$MINI_REPO"
for m in one two; do git -C "$MINI_REPO" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "$m"; done

CTRL="$WORK/ctrl"
mkdir -p "$CTRL/installers"
git -C "$MINI_REPO" bundle create -q "$CTRL/realtime_code_coloring.bundle" --all
tar -ch -C "$SRC" devtools | xz -T0 > "$CTRL/devtools.tar.xz"
for e in "${INSTALLERS[@]}"; do
  IFS='|' read -r key file _ <<< "$e"
  cp "${STAND_IN[$key]}" "$CTRL/installers/$file"
done
NODE_MSI="node-v${NODE_VER}-x64.msi"
(cd "$CTRL/installers" && sha256sum "$NODE_MSI") > "$CTRL/installers/node-SHASUMS256.txt"
echo 手册 > "$CTRL/deploy-windows-offline.md"
echo 说明 > "$CTRL/README.txt"
resum "$CTRL"

# 跑一次自检；输出与退出码放在包外面 —— 包里多出一个文件，SHA256SUMS 那一项就该报失败
run_check() {  # $1 = 用例名  $2 = 包目录  [$3 = 原工具链目录]
  RTCC_TOOLS="${3:-$SRC/devtools}" bash "$PACK" --check "$2" > "$WORK/$1.out" 2>&1
  echo $? > "$WORK/$1.rc"
}

expect_fail() {  # $1 = 用例名  $2 = 该报失败的判据  [$3 = 失败那一行必须点名的字样]
  local out="$WORK/$1.out"
  if [ "$(cat "$WORK/$1.rc")" = 0 ]; then bad "$1：自检以 0 退出"; sed 's/^/      /' "$out"; return; fi
  if ! grep -qF "[失败] $2" "$out"; then bad "$1：没有「[失败] $2」"; sed 's/^/      /' "$out"; return; fi
  if [ -n "${3:-}" ] && ! grep -F "[失败] $2" "$out" | grep -qF "$3"; then
    bad "$1：「[失败] $2」那一行没点名「$3」"; sed 's/^/      /' "$out"; return
  fi
  ok "$1"
}

new_case() { cp -rl "$CTRL" "$WORK/$1"; echo "$WORK/$1"; }

echo "==> 离线打包自检用例"

# 0. 对照：完好的包每一项都通过 —— 没有它，下面的「报失败」可能只是自检一律报失败
run_check 对照 "$CTRL"
n_ok=$(grep -cF '[通过]' "$WORK/对照.out")
if [ "$(cat "$WORK/对照.rc")" = 0 ] && [ "$n_ok" = 9 ] && ! grep -qF '[失败]' "$WORK/对照.out" \
   && tail -1 "$WORK/对照.out" | grep -qF '离线包自检：全部通过'; then
  ok "对照：9 项都通过、以 0 退出"
else
  bad "对照：应当 9 项都通过、以 0 退出（实际通过 $n_ok 项，退出码 $(cat "$WORK/对照.rc")）"
  sed 's/^/      /' "$WORK/对照.out"
fi

# 1. 打包漏了 -h：链接原样进包，Windows 上解包建不出来，cargo / rustc 全部缺失（手册 2.2 踩过）
c=$(new_case 符号链接); rm "$c/devtools.tar.xz"
tar -c -C "$SRC" devtools | xz -T0 > "$c/devtools.tar.xz"; resum "$c"
run_check 符号链接 "$c"; expect_fail 符号链接 "包里没有符号链接" "cargo.exe"

# 2. 排除项写宽了，把平台要调的 llvm-cov 也排掉了
c=$(new_case 缺关键程序); rm "$c/devtools.tar.xz"
tar -ch --exclude='*/llvm-cov.exe' -C "$SRC" devtools | xz -T0 > "$c/devtools.tar.xz"; resum "$c"
run_check 缺关键程序 "$c"; expect_fail 缺关键程序 "关键程序都在包里" "llvm-cov"

# 3. 打包之后原目录里的文件又变了（比如工具链被升级）：包里的已不是验收用的那一份
cp -r "$SRC" "$WORK/src3"
c=$(new_case 原目录变了); rm "$c/devtools.tar.xz"
tar -ch -C "$WORK/src3" devtools | xz -T0 > "$c/devtools.tar.xz"; resum "$c"
echo 升级过 >> "$WORK/src3/devtools/go/bin/go.exe"
run_check 原目录变了 "$c" "$WORK/src3/devtools"; expect_fail 原目录变了 "包里的文件与原目录一致" "go.exe"

# 4. 压缩包被截断（拷贝中断、U 盘写满）
c=$(new_case 压缩包截断); f="$c/devtools.tar.xz"
head -c $(( $(stat -c %s "$f") / 2 )) "$f" > "$WORK/half.tmp"; rm "$f"; mv "$WORK/half.tmp" "$f"; resum "$c"
run_check 压缩包截断 "$c"; expect_fail 压缩包截断 "工具链压缩包完整"

# 5. 安装包下坏了（或被改过）几个字节：签名校验必须拦住
c=$(new_case 安装包被改); f="$c/installers/Git-${GIT_VER}-64-bit.exe"; detach "$f"
before=$(sha256sum < "$f"); printf 'ZZZZ' | dd of="$f" bs=1 seek=4096 conv=notrunc status=none
[ "$(sha256sum < "$f")" != "$before" ] || undecidable "改字节没改出差异"
resum "$c"
run_check 安装包被改 "$c"; expect_fail 安装包被改 "安装包签名有效" "Git-${GIT_VER}-64-bit.exe"

# 6. 签名有效、但不是那家厂商签的（下错了文件、或来自不可信的镜像）
c=$(new_case 签名者不对); f="$c/installers/Git-${GIT_VER}-64-bit.exe"
rm "$f"; cp "${STAND_IN[Python]}" "$f"; resum "$c"
run_check 签名者不对 "$c"; expect_fail 签名者不对 "安装包签名有效" "Python Software Foundation"

# 7. Node.js 安装包与官方校验和对不上
c=$(new_case 校验和不符); f="$c/installers/node-SHASUMS256.txt"
rm "$f"; printf '%064d  %s\n' 0 "$NODE_MSI" > "$f"; resum "$c"
run_check 校验和不符 "$c"; expect_fail 校验和不符 "Node.js 安装包与官方校验和一致"

# 8. 拷到 U 盘之后某个文件坏了：SHA256SUMS 要点名是哪个
c=$(new_case 拷贝损坏); detach "$c/README.txt"; echo x >> "$c/README.txt"
run_check 拷贝损坏 "$c"; expect_fail 拷贝损坏 "SHA256SUMS 与包内文件一致" "README.txt"

# 9. 清单漏了文件：漏掉的那个拷坏了也查不出来
c=$(new_case 清单漏文件); grep -v 'realtime_code_coloring.bundle' "$c/SHA256SUMS.txt" > "$WORK/sums.tmp"
rm "$c/SHA256SUMS.txt"; mv "$WORK/sums.tmp" "$c/SHA256SUMS.txt"
run_check 清单漏文件 "$c"; expect_fail 清单漏文件 "SHA256SUMS 与包内文件一致" "realtime_code_coloring.bundle"

# 10. bundle 只带了最后一个提交：目标机上克隆不出来，增量也没法与 HEAD~1 比
c=$(new_case bundle缺历史); rm "$c/realtime_code_coloring.bundle"
git -C "$MINI_REPO" bundle create -q "$c/realtime_code_coloring.bundle" main~1..main; resum "$c"
run_check bundle缺历史 "$c"; expect_fail bundle缺历史 "仓库 bundle 完整"

# 11. 少了一个安装包
c=$(new_case 少安装包); rm "$c/installers/python-${PY_VER}-amd64.exe"; resum "$c"
run_check 少安装包 "$c"; expect_fail 少安装包 "该有的文件都在" "python-${PY_VER}-amd64.exe"

# 12. 打包模式：输出目录里已有东西就拒绝，一个文件都不动（不替用户删旧包）
d="$WORK/已有包"; mkdir -p "$d"; echo 旧的 > "$d/旧文件.txt"
snapshot() { (cd "$d" && find . | sort && sha256sum 旧文件.txt); }
before=$(snapshot)
RTCC_TOOLS="$SRC/devtools" bash "$PACK" "$d" > "$WORK/不覆盖.out" 2>&1; rc=$?
if [ "$rc" != 0 ] && [ "$(snapshot)" = "$before" ] && grep -qF '不为空' "$WORK/不覆盖.out"; then
  ok "输出目录不为空时拒绝、什么都不动"
else
  bad "输出目录不为空时应当拒绝、什么都不动（退出码 $rc）"; sed 's/^/      /' "$WORK/不覆盖.out"
fi

# 13. 在没有原工具链目录的机器上 --check：比对那一项判不了，结论不能是「全部通过」，退出码 2（不是 0，也不是 1）
run_check 判不了 "$CTRL" "$WORK/没有这个目录/devtools"
if [ "$(cat "$WORK/判不了.rc")" = 2 ] && grep -qF '[跳过] 包里的文件与原目录一致' "$WORK/判不了.out" \
   && ! grep -qF '[失败]' "$WORK/判不了.out" && ! grep -qF '全部通过' "$WORK/判不了.out"; then
  ok "有项没法核对时不报全部通过、以 2 退出"
else
  bad "有项没法核对时应当不报全部通过、以 2 退出（退出码 $(cat "$WORK/判不了.rc")）"; sed 's/^/      /' "$WORK/判不了.out"
fi

# 14. 打包机器的属组名带空格（加入域的机器上是「Domain Users」）：tar -tv 的列数变了，成员名不能解析错位。
#     属主、属组的编号取原文件自己的，免得比对那一项报 Uid / Gid 不同
c=$(new_case 属组带空格); rm "$c/devtools.tar.xz"
ids=$(stat -c '%u %g' "$SRC/devtools/go/bin/go.exe")
tar -ch --owner="Some User:${ids% *}" --group="Domain Users:${ids#* }" -C "$SRC" devtools | xz -T0 > "$c/devtools.tar.xz"
resum "$c"
# 列表先存下来再找：pipefail 下接 head / grep -q，tar 写不完会让整条管道算失败
listing=$(tar -tvJf "$c/devtools.tar.xz")
grep -qF 'Some User/Domain Users' <<< "$listing" || undecidable "造不出属组带空格的包"
run_check 属组带空格 "$c"
if [ "$(cat "$WORK/属组带空格.rc")" = 0 ] && [ "$(grep -cF '[通过]' "$WORK/属组带空格.out")" = 9 ]; then
  ok "属组名带空格的包照样 9 项都通过"
else
  bad "属组名带空格的包应当 9 项都通过（退出码 $(cat "$WORK/属组带空格.rc")）"; sed 's/^/      /' "$WORK/属组带空格.out"
fi

# 15. 换了包里的手册或 README 之后重算清单：旧的 SHA256SUMS.txt 还在，不能把它自己也算进新清单 ——
#     新清单里记着旧清单的哈希，写完必然对不上（2026-10-30 给真包换手册时踩到）
c=$(new_case 重算清单); detach "$c/README.txt"; echo 改过 >> "$c/README.txt"
( OUT="$c"; write_sums )
run_check 重算清单 "$c"
if grep -qF '[通过] SHA256SUMS 与包内文件一致' "$WORK/重算清单.out"; then
  ok "包里已有清单时重算，新清单照样对得上"
else
  bad "包里已有清单时重算，新清单应当对得上"; grep -F 'SHA256SUMS' "$WORK/重算清单.out" | sed 's/^/      /'
fi

echo "    $pass PASS / $fail FAIL"
if [ "$fail" = 0 ]; then echo "打包自检用例：全部通过"; else echo "打包自检用例：$fail 条失败"; exit 1; fi
