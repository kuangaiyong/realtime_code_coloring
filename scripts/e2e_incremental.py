"""
P1 端到端验收：增量覆盖率口径 + 产物版本一致性校验。真实服务、真实探针、真实 git，无 mock。

验证命题：
  1. 平台给出的增量范围与 `git diff` 逐行一致（不是「差不多」，是集合相等）；
  2. 增量分母只含基线之后变动的可执行行，与全量分母显著不同；
  3. 只调用新接口的一部分分支时，新代码里没测到的行仍然是红的
     —— 增量染色的价值就在于此：告诉你「这次改的代码还差哪几行没测」；
  4. 门禁给出的判定与页面上的数字一致（同一份四舍五入），且「判不了」返回 409、
     不与「不通过」共用 200 —— CI 那边这两件事一个该找人看、一个该补测试；
  5. 渲染中的源码一旦与被测产物不是同一版本，平台拒绝出报告（HTTP 409），
     而不是返回一份行号错位、看上去却很正常的结果。
"""
import json
import math
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLATFORM = "http://localhost:18090"
DEMO = "http://localhost:18080"
SRC = "demo-service/src/main/java"
# 平台的增量范围覆盖全部源码根，本脚本要独立算出同一个范围才能做集合比对。
# 必须与 application.yml 的 java-source-root / go-source-root 保持一致 ——
# 少一个根，平台算出来的变更文件会被这里判成「多出来的」
SOURCE_ROOTS = [SRC, "demo-service-go", "demo-service-cpp", "demo-service-rust"]
SERVICE = "demo-service/src/main/java/com/shop/order/service/OrderService.java"
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
POLL_SEC = 15


def git(*args):
    r = subprocess.run(["git", "-C", str(ROOT), *args],
                       capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        print("!! git", " ".join(args), "失败:", r.stderr.strip())
        sys.exit(1)
    return r.stdout


def http(url, method="GET"):
    req = urllib.request.Request(url, method=method, data=b"" if method == "POST" else None)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)


def must(status, body, url):
    if status != 200:
        print(f"!! {url} 返回 {status}: {body}")
        sys.exit(1)
    return body


def summary(mode="full", baseline=None):
    url = f"{PLATFORM}/api/coverage/summary?mode={mode}"
    if baseline:
        url += "&baseline=" + urllib.parse.quote(baseline)
    return http(url)


def gate(mode="incremental", baseline=None):
    url = f"{PLATFORM}/api/coverage/gate?mode={mode}"
    if baseline:
        url += "&baseline=" + urllib.parse.quote(baseline)
    return http(url)


def file_detail(path, mode="full", baseline=None):
    url = f"{PLATFORM}/api/coverage/file?path={urllib.parse.quote(path)}&mode={mode}"
    if baseline:
        url += "&baseline=" + urllib.parse.quote(baseline)
    return http(url)


def diff_lines(base, target):
    """独立于平台再算一遍 `git diff` 的新增/修改行，用来核对平台的增量范围"""
    raw = git("diff", "-M", "--unified=0", "--no-color", base, target, "--", *SOURCE_ROOTS)
    res, cur = {}, None
    for line in raw.split("\n"):
        if line.startswith("+++ "):
            target_path = line[4:].strip()
            if target_path == "/dev/null":
                cur = None
            else:
                rel = target_path[2:]
                # 平台的 IR 路径与 git diff 一样以仓库根为基准，不再需要剥前缀
                cur = res.setdefault(rel, set())
            continue
        if cur is None or not line.startswith("@@"):
            continue
        m = HUNK.match(line)
        if not m:
            continue
        start = int(m.group(1))
        count = 1 if m.group(2) is None else int(m.group(2))
        cur.update(range(start, start + count))
    return {k: v for k, v in res.items() if v}


def wait_until(check):
    """平台按固定周期轮询探针，覆盖变化不会瞬时可见"""
    deadline = time.time() + POLL_SEC
    while time.time() < deadline:
        got = check()
        if got is not None:
            return got
        time.sleep(0.5)
    return None


def rows_of(path, baseline):
    body = must(*file_detail(path, "incremental", baseline),
                url=f"/api/coverage/file[{path}]")
    if not body.get("found"):
        print("!! 无法读取文件:", body)
        sys.exit(1)
    return body


def main():
    print("=" * 78)
    print("P1 端到端验收 —— 增量覆盖率与版本一致性")
    print("=" * 78)

    full = must(*summary(), url="/api/coverage/summary")
    if full["probeStatus"] != "CONNECTED":
        print(f"!! 探针未连接（{full['probeStatus']}）：{full.get('lastError')}")
        sys.exit(1)
    build = full["buildCommit"]
    if not build:
        print("!! 被测实例未上报 buildCommit，请确认启动参数带了 sessionid=$(git rev-parse HEAD)")
        sys.exit(1)

    # 基线取「被测源码最近一次变更之前的提交」，这样 diff 必然非空，验收才不是空转
    last_src_commit = git("log", "-1", "--format=%H", "--", SRC).strip()
    baseline = git("rev-parse", last_src_commit + "^").strip()
    print(f"\n  被测产物构建于 {build[:8]}，基线取 {baseline[:8]}")

    expected = diff_lines(baseline, build)
    if not expected:
        print(f"!! {baseline[:8]}..{build[:8]} 之间被测源码没有变更，本次验收无从展开。"
              f"\n   请确认被测服务是用最新提交构建并重启的。")
        sys.exit(1)
    print("  git diff 认定的变更行：")
    for p, lines in sorted(expected.items()):
        print(f"    {p}  {sorted(lines)}")

    ok = True

    # ---- 1. 平台的增量范围必须与 git diff 集合相等 ----
    status, inc = summary("incremental", baseline)
    inc = must(status, inc, url="/api/coverage/summary?mode=incremental")
    if inc["changedFiles"] != len(expected):
        print(f"  [FAIL] 平台认定 {inc['changedFiles']} 个文件有变更，git diff 认定 {len(expected)} 个")
        ok = False

    print()
    for f in inc["files"]:
        d = rows_of(f["path"], baseline)
        got = {r["line"] for r in d["rows"] if r.get("inDiff")}
        exp = expected.get(f["path"], set())
        if got == exp:
            print(f"  [PASS] {f['path']} 增量行与 git diff 完全一致（{len(got)} 行）")
        else:
            print(f"  [FAIL] {f['path']} 增量行不一致：平台多出 {sorted(got - exp)}，缺少 {sorted(exp - got)}")
            ok = False
        executable = [r for r in d["rows"] if r.get("inDiff") and r["status"] != "EMPTY"]
        if d["coveredLines"] + d["missedLines"] != len(executable):
            print(f"  [FAIL] {f['path']} 增量分母 {d['coveredLines'] + d['missedLines']} "
                  f"与可执行变更行数 {len(executable)} 不符")
            ok = False

    inc_paths = {f["path"] for f in inc["files"]}
    full_paths = {f["path"] for f in full["files"]}
    if inc_paths <= full_paths and len(inc_paths) < len(full_paths):
        print(f"  [PASS] 增量口径覆盖 {len(inc_paths)} 个文件，是全量口径 {len(full_paths)} 个文件的真子集")
    else:
        print(f"  [FAIL] 增量文件集合异常：增量 {sorted(inc_paths)}，全量 {sorted(full_paths)}")
        ok = False

    # ---- 2. 清零后新代码应当全红 ----
    print("\n  >> 清零计数器，此时新写的代码一行都没跑过")
    http(f"{PLATFORM}/api/coverage/reset", "POST")
    zero = wait_until(lambda: (lambda s, b: b if s == 200 and b["overallRatio"] == 0.0 else None)
                      (*summary("incremental", baseline)))
    if zero is None:
        _, cur = summary("incremental", baseline)
        print(f"  [FAIL] 清零后增量覆盖率仍为 {cur.get('overallRatio')}%")
        ok = False
    else:
        print(f"  [PASS] 清零后增量行覆盖率 0%（新代码 {sum(len(v) for v in expected.values())} 个变更行全部未覆盖）")

    # ---- 3. 只走新接口的部分分支，未走到的新代码必须仍是红的 ----
    for biz, expect_hit in (("NOPE", "ORDER_NOT_FOUND"), ("A1002", "NOT_CANCELLABLE")):
        _, resp = http(f"{DEMO}/api/order/cancel?bizNo={biz}", "POST")
        print(f"  >> POST /api/order/cancel?bizNo={biz} → {json.dumps(resp, ensure_ascii=False)}")
        if expect_hit not in str(resp):
            print(f"  [FAIL] 期望走到 {expect_hit} 分支，实际响应 {resp}")
            ok = False

    def new_rows():
        d = rows_of(SERVICE, baseline)
        rows = {r["line"]: (r["status"], r["text"].strip()) for r in d["rows"] if r.get("inDiff")}
        return rows, d

    hit = wait_until(lambda: (lambda t: t if any(s == "COVERED" for s, _ in t[0].values()) else None)(new_rows()))
    if hit is None:
        print("  [FAIL] 调用新接口后，增量视图里没有任何一行变绿")
        sys.exit(1)
    rows, detail = hit
    # 走到的分支变绿之后，剩下的红行就是「这次改动还没测到的代码」
    covered = wait_until(lambda: (lambda t: t if sum(1 for s, _ in t[0].values() if s == "COVERED") >= 4 else None)(new_rows()))
    rows, detail = covered if covered else (rows, detail)

    print(f"\n  新增代码的逐行状态（增量视图，共 {len(rows)} 个变更行）")
    for n in sorted(rows):
        st, tx = rows[n]
        mark = {"COVERED": "[绿] 已覆盖", "MISSED": "[红] 未覆盖",
                "PARTIAL": "[黄] 部分", "EMPTY": "     非可执行"}[st]
        print(f"    L{n:<4d} {mark:<14s} {tx[:62]}")

    still_red = [n for n, (st, tx) in rows.items() if st == "MISSED"]
    hit_lines = [n for n, (st, _) in rows.items() if st == "COVERED"]
    if hit_lines and still_red:
        print(f"  [PASS] 新代码里 {len(hit_lines)} 行已覆盖、{len(still_red)} 行仍未覆盖 —— "
              f"增量视图能指出这次改动还差哪几行没测")
    else:
        print(f"  [FAIL] 期望新代码里既有已覆盖也有未覆盖的行，实际：绿 {hit_lines} 红 {still_red}")
        ok = False

    _, inc2 = summary("incremental", baseline)
    _, full2 = summary()
    print(f"  [INFO] 增量行覆盖率 {inc2['overallRatio']}%（分母仅本次变更）"
          f" / 全量行覆盖率 {full2['overallRatio']}%")
    if inc2["overallRatio"] <= 0:
        print("  [FAIL] 调用新接口后增量覆盖率仍为 0")
        ok = False

    # ---- 4. 门禁判定 ----
    print("\n  >> 门禁：CI 合并前据此放行或阻断")
    g_full = must(*gate("full"), url="/api/coverage/gate?mode=full")
    # 页面显示 80.0% 却判不通过，是没人能自己想明白的事：两处必须是同一个数
    if g_full["actual"] == full2["overallRatio"]:
        print(f"  [PASS] 全量门禁给出的 {g_full['actual']}% 与页面显示的全量覆盖率一致")
    else:
        print(f"  [FAIL] 门禁 {g_full['actual']}% 与页面 {full2['overallRatio']}% 对不上")
        ok = False
    if g_full["threshold"] == 0 and g_full["passed"]:
        print(f"  [PASS] 全量阈值 0 即不设门槛，放行：{g_full['reason']}")
    else:
        print(f"  [FAIL] 全量阈值 {g_full['threshold']}，判定 {g_full['passed']}：{g_full['reason']}")
        ok = False

    g_inc = must(*gate("incremental", baseline), url="/api/coverage/gate?mode=incremental")
    total = g_inc["coveredLines"] + g_inc["missedLines"]
    if total <= 0:
        print(f"  [FAIL] 增量门禁分母为 0，与前面算出的 {len(expected)} 个变更文件矛盾")
        ok = False
    else:
        # 照抄平台的舍入方式（Math.round 是 floor(x+0.5)），
        # 用 Python 的 round 会在 .05 上按「四舍六入五成双」得出另一个数，判成假失败
        want = math.floor(g_inc["coveredLines"] * 1000.0 / total + 0.5) / 10.0
        if g_inc["actual"] == want and g_inc["passed"] == (want >= g_inc["threshold"]):
            print(f"  [PASS] 增量门禁 {g_inc['coveredLines']}/{total} = {g_inc['actual']}%"
                  f" 对阈值 {g_inc['threshold']}% → passed={g_inc['passed']}")
        else:
            print(f"  [FAIL] 增量门禁自相矛盾：{g_inc['coveredLines']}/{total} 应为 {want}%，"
                  f"实际 {g_inc['actual']}%，passed={g_inc['passed']}")
            ok = False
        if not g_inc["passed"]:
            # 「还差几行」要验的是性质而不是公式：补上这几行之后，
            # 按门禁自己那套四舍五入真能过阈值；少补一行则不能。
            # 照抄公式的话，平台改了舍入口径这条断言会跟着一起错
            m = re.search(r"还需覆盖 (\d+) 行", g_inc["reason"])
            if not m:
                print(f"  [FAIL] 不通过时没有给出「还需覆盖 N 行」：{g_inc['reason']}")
                ok = False
            else:
                n = int(m.group(1))
                after = math.floor((g_inc["coveredLines"] + n) * 1000.0 / total + 0.5) / 10.0
                before = math.floor((g_inc["coveredLines"] + n - 1) * 1000.0 / total + 0.5) / 10.0
                if n >= 1 and after >= g_inc["threshold"] and before < g_inc["threshold"]:
                    print(f"  [PASS] 不通过时给出可执行的下一步：{g_inc['reason']}"
                          f"（补满 {n} 行到 {after}%，少一行只有 {before}%）")
                else:
                    print(f"  [FAIL] 「还需覆盖 {n} 行」不成立：补满得 {after}%，"
                          f"少一行得 {before}%，阈值 {g_inc['threshold']}%")
                    ok = False

    # 分母为 0 必须放行：overallRatio() 在这种情况下返回 0，直接拿去比阈值
    # 就把「这次没改任何可执行代码」说成了「改了却一行没测」
    status, g_empty = gate("incremental", build)
    if status == 200 and g_empty["passed"] and g_empty["actual"] is None:
        print(f"  [PASS] 基线取产物自身（diff 为空）时放行且不给比例：{g_empty['reason']}")
    else:
        print(f"  [FAIL] 空 diff 的门禁应放行且 actual 为 null，实际 {status} {g_empty}")
        ok = False

    # ---- 5. 源码与产物不是同一版本时必须拒绝出报告 ----
    print("\n  >> 模拟「产物是旧的、源码已经改了」：直接改动被测源码文件")
    target = ROOT / SERVICE
    original = target.read_bytes()
    try:
        target.write_bytes(original + b"// drift\n")
        status, body = summary("incremental", baseline)
        if status == 409 and "OrderService.java" in body.get("error", ""):
            print(f"  [PASS] 平台拒绝出增量报告（HTTP 409）：{body['error']}")
        else:
            print(f"  [FAIL] 源码已漂移，平台却返回 {status}：{body}")
            ok = False
        status, body = gate("incremental", baseline)
        if status == 409:
            # 门禁若在这里返回 200+passed=false，CI 会把「平台判不了」当成「覆盖不达标」，
            # 开发照着去补测试，而真正的原因是产物与源码不是同一版本
            print(f"  [PASS] 门禁同样拒判（HTTP 409），未把判不了说成不通过：{body['error']}")
        else:
            print(f"  [FAIL] 源码已漂移，门禁却返回 {status}：{body}")
            ok = False
        status, body = summary()
        if status == 200:
            print("  [PASS] 全量口径不受影响，仍可查看（漂移只影响行号对齐的增量口径）")
        else:
            print(f"  [FAIL] 全量口径被误伤：{status} {body}")
            ok = False
    finally:
        target.write_bytes(original)

    status, _ = summary("incremental", baseline)
    if status == 200:
        print("  [PASS] 源码恢复后增量报告自动恢复可用")
    else:
        print(f"  [FAIL] 源码已恢复，平台仍拒绝出报告：{status}")
        ok = False

    print("\n" + "-" * 78)
    print("  验收结论：" + ("全部通过" if ok else "存在失败项"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
