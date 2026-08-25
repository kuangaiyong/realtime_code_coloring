"""
P1 端到端验收：多实例聚合。两个真实被测实例、两个真实探针，无 mock。

验证命题：
  1. 聚合是并集 —— 只在实例 A 上跑过的代码和只在实例 B 上跑过的代码，
     在平台上都是绿的。少了这一步，负载均衡下的覆盖率必然少算；
  2. 缺失是可见的 —— 某个实例不可达时照常出报告（其余实例的数据仍是真的），
     但状态降级为 PARTIAL 并点名是哪台机器缺席，不能让人以为看到的是全量；
  3. 版本不一致直接拒绝增量报告 —— 各实例加载的字节码不同时，JaCoCo 会按
     class id 静默丢弃对不上的那部分，跑过的行照样显示成红的。这种错必须挡住。

实例 2 的起停由 run_local.sh 负责，本脚本只调用它。
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLATFORM = "http://localhost:18090"
DEMO1 = "http://localhost:18080"
DEMO2 = "http://localhost:18081"
CONTROLLER = "demo-service/src/main/java/com/shop/order/controller/OrderController.java"
POLL_SEC = 20


def http(url, method="GET"):
    req = urllib.request.Request(url, method=method, data=b"" if method == "POST" else None)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)


def must(status, body, what):
    if status != 200:
        print(f"!! {what} 返回 {status}: {body}")
        sys.exit(1)
    return body


def bash_exe():
    """
    必须拿到 Git Bash 的绝对路径。

    Windows 的 CreateProcess 在搜 PATH 之前先搜 System32，那里的 bash.exe 是
    WSL 入口；直接写 "bash" 会被它截胡，报「WSL 没有已安装的发行版」。
    """
    cands = [os.environ.get("BASH_EXE"), shutil.which("bash")]
    git = shutil.which("git")
    if git:
        for parent in Path(git).resolve().parents:
            cands += [str(parent / "bin" / "bash.exe"), str(parent / "usr" / "bin" / "bash.exe")]
    for c in cands:
        if c and "system32" not in c.lower() and Path(c).exists():
            return c
    print("!! 找不到 Git Bash，可用 BASH_EXE 环境变量指定")
    sys.exit(1)


def run_local(cmd):
    r = subprocess.run([bash_exe(), "scripts/run_local.sh", cmd], cwd=str(ROOT),
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print(f"!! run_local.sh {cmd} 失败:\n{r.stdout}\n{r.stderr}")
        sys.exit(1)


def summary(mode="full", baseline=None):
    url = f"{PLATFORM}/api/coverage/summary?mode={mode}"
    if baseline:
        url += "&baseline=" + urllib.parse.quote(baseline)
    return http(url)


def covered_lines(path=CONTROLLER):
    body = must(*http(f"{PLATFORM}/api/coverage/file?path={urllib.parse.quote(path)}"),
                what=f"/api/coverage/file[{path}]")
    return {r["line"] for r in body["rows"] if r["status"] == "COVERED"}


def wait_until(check, secs=POLL_SEC):
    """平台按固定周期轮询探针，变化不会瞬时可见"""
    deadline = time.time() + secs
    while time.time() < deadline:
        got = check()
        if got is not None:
            return got
        time.sleep(0.5)
    return None


def method_line(needle, path=CONTROLLER):
    """找出某个接口方法体里的一行，用它判断这个接口有没有被跑到"""
    body = must(*http(f"{PLATFORM}/api/coverage/file?path={urllib.parse.quote(path)}"),
                what="/api/coverage/file")
    for r in body["rows"]:
        if needle in r["text"] and r["status"] != "EMPTY":
            return r["line"]
    print(f"!! 在 {path} 里找不到可执行行：{needle}")
    sys.exit(1)


def main():
    print("=" * 78)
    print("P1 端到端验收 —— 多实例聚合")
    print("=" * 78)

    base = must(*summary(), what="/api/coverage/summary")
    eps = base.get("instances", [])
    if len(eps) < 2:
        print(f"!! 平台只配了 {len(eps)} 个实例，多实例验收无从展开：{eps}")
        sys.exit(1)
    print(f"\n  平台配置的被测实例：")
    for i in eps:
        print(f"    {i['endpoint']:<18s} {i['status']:<13s} buildCommit={str(i['buildCommit'])[:8]}")
    if any(i["status"] != "CONNECTED" for i in eps):
        print("!! 验收开始前要求两个实例都在线")
        sys.exit(1)
    if base["probeStatus"] != "CONNECTED":
        print(f"!! 探针状态应为 CONNECTED，实际 {base['probeStatus']}")
        sys.exit(1)

    ok = True

    # ---- 1. 聚合是并集 ----
    print("\n  >> 清零两个实例，分别只在一台上调用一个接口")
    must(*http(f"{PLATFORM}/api/coverage/reset", "POST"), what="/api/coverage/reset")

    refund_line = method_line("orderService.refund(")
    query_line = method_line("orderService.queryOrder(")

    http(f"{DEMO1}/api/order/query?bizNo=A1002")
    hit1 = wait_until(lambda: True if query_line in covered_lines() else None)
    if hit1:
        print(f"  [PASS] 实例 #1 调用查询接口后，L{query_line} 变绿")
    else:
        print(f"  [FAIL] 实例 #1 调用查询接口后 L{query_line} 仍未覆盖")
        ok = False

    before = covered_lines()
    if refund_line in before:
        print(f"  [FAIL] 尚未调用退款接口，L{refund_line} 却已是覆盖状态")
        ok = False
    else:
        print(f"  [PASS] 此时退款接口 L{refund_line} 仍是未覆盖（没有任何实例跑过它）")

    http(f"{DEMO2}/api/order/refund?bizNo=NOPE&amount=1", "POST")
    hit2 = wait_until(lambda: True if refund_line in covered_lines() else None)
    after = covered_lines()
    if hit2 and query_line in after and refund_line in after:
        print(f"  [PASS] 只在实例 #2 上调用退款接口后 L{refund_line} 也变绿，"
              f"且 L{query_line} 仍绿 —— 聚合确实是两台的并集")
    else:
        print(f"  [FAIL] 聚合结果不含两台各自跑到的行：查询 L{query_line} in={query_line in after}，"
              f"退款 L{refund_line} in={refund_line in after}")
        ok = False

    # ---- 2. 实例缺席必须可见 ----
    # 先在两台都健康时录一个场景，用于验证「事后掉线不该污染已定格的归档数据」
    live_list = must(*http(f"{PLATFORM}/api/scenario"), what="/api/scenario")
    if live_list.get("active"):
        http(f"{PLATFORM}/api/scenario/stop", "POST")
        live_list = must(*http(f"{PLATFORM}/api/scenario"), what="/api/scenario")
    used = {s["scenarioId"] for s in live_list["scenarios"]}
    n = 1
    while f"healthy-snapshot-{n}" in used:
        n += 1
    snap_id = f"healthy-snapshot-{n}"
    must(*http(f"{PLATFORM}/api/scenario/start?scenarioId={snap_id}", "POST"), what="start")
    http(f"{DEMO1}/api/order/query?bizNo=A1002")
    must(*http(f"{PLATFORM}/api/scenario/stop", "POST"), what="stop")
    print(f"\n  >> 已在两台健康时录下场景 {snap_id}（其数据此刻起不再变化）")

    print("\n  >> 停掉实例 #2，模拟一台机器掉线")
    run_local("demo2-stop")
    part = wait_until(lambda: (lambda s, b: b if s == 200 and b["probeStatus"] == "PARTIAL" else None)(*summary()))
    if part is None:
        _, cur = summary()
        print(f"  [FAIL] 一台掉线后 probeStatus 应为 PARTIAL，实际 {cur['probeStatus']}")
        ok = False
    else:
        down = [i for i in part["instances"] if i["status"] != "CONNECTED"]
        print(f"  [PASS] probeStatus=PARTIAL，仍返回 200（其余实例的数据依然真实）")
        print(f"  [PASS] 点名缺席实例：{[i['endpoint'] for i in down]}")
        print(f"         提示：{part['lastError']}")
        if not down or "6301" not in part["lastError"]:
            print(f"  [FAIL] 错误提示没有点明是哪台实例缺席")
            ok = False

        # PARTIAL 下门禁必须拒判：掉线那台跑过的行会被算成没覆盖，比例被压低，
        # 判出来是「不通过」，而真正的原因（有实例缺席）一个字都不会出现在结论里
        g_status, g_body = http(f"{PLATFORM}/api/coverage/gate?mode=full")
        if g_status == 409 and "PARTIAL" in g_body.get("error", ""):
            print(f"  [PASS] PARTIAL 时门禁拒判（HTTP 409）而非判「不通过」：{g_body['error'][:60]}…")
        else:
            print(f"  [FAIL] PARTIAL 时门禁应返回 409，实际 {g_status}：{g_body}")
            ok = False

    # 归档数据是 stop 那一刻定格的，事后掉线与它无关。
    # 若照搬实时状态，这里会给一份本就完整的数据扣上「不完整」的警告
    arch = must(*http(f"{PLATFORM}/api/coverage/summary?scenarioId={urllib.parse.quote(snap_id)}"),
                what=f"场景 {snap_id} 的汇总")
    if arch["probeStatus"] == "ARCHIVED" and not arch["lastError"]:
        print(f"  [PASS] 场景 {snap_id} 仍标为 ARCHIVED 且无「数据不完整」警告 —— "
              f"事后掉线没有污染已定格的归档结果")
    else:
        print(f"  [FAIL] 归档场景被实时探针状态污染：probeStatus={arch['probeStatus']}，"
              f"lastError={arch['lastError']}")
        ok = False

    # ---- 3. 实例间版本不一致必须拒绝增量报告 ----
    print("\n  >> 用一个对不上任何提交的版本重启实例 #2，制造实例间版本不一致")
    run_local("demo2-mismatch")
    conflict = wait_until(lambda: (lambda s, b: b if s == 200 and b.get("versionError") else None)(*summary()))
    if conflict is None:
        _, cur = summary()
        print(f"  [FAIL] 版本不一致未被识别：versionError={cur.get('versionError')}，"
              f"instances={[(i['endpoint'], str(i['buildCommit'])[:8]) for i in cur.get('instances', [])]}")
        ok = False
    else:
        print(f"  [PASS] 平台识别出版本冲突：{conflict['versionError']}")
        commits = {str(i["buildCommit"])[:8] for i in conflict["instances"] if i["buildCommit"]}
        print(f"         两台自报版本：{sorted(commits)}")

        status, body = summary("incremental", "HEAD~1")
        if status == 409:
            print(f"  [PASS] 增量口径拒绝出报告（HTTP 409）：{body['error'][:60]}…")
        else:
            print(f"  [FAIL] 版本不一致时增量口径仍返回 {status}")
            ok = False

        status, _ = summary()
        if status == 200:
            print("  [PASS] 全量口径仍可查看（版本不一致只影响需要对齐行号的增量口径）")
        else:
            print(f"  [FAIL] 全量口径被误伤：{status}")
            ok = False

    # ---- 3b. 同一提交但一脏一净：最容易被漏判成「版本一致」的那种不一致 ----
    print("\n  >> 让实例 #2 报同一个提交但带 -dirty：commit 相同，字节码不同")
    run_local("demo2-dirty")
    dirty = wait_until(lambda: (lambda s, b: b if s == 200 and b.get("versionError") else None)(*summary()))
    if dirty is None:
        _, cur = summary()
        status, body = summary("incremental", "HEAD~1")
        print(f"  [FAIL] 一脏一净未被判为版本冲突：versionError={cur.get('versionError')}；"
              f"增量返回 {status}：{body.get('error')}")
        print("         （只比 commit 会漏掉这种，提示会错误地引向「未上报 sessionid」）")
        ok = False
    else:
        print(f"  [PASS] 识别为版本冲突：{dirty['versionError']}")
        flags = [(i["endpoint"], str(i["buildCommit"])[:8], i["dirty"]) for i in dirty["instances"]]
        print(f"         逐实例 dirty 标记：{flags}")
        if not any(f[2] for f in flags):
            print("  [FAIL] 没有任何实例被标记为 dirty，界面上看不出差异在哪")
            ok = False

    # ---- 4. 恢复 ----
    print("\n  >> 恢复实例 #2 到正确版本")
    run_local("demo2-start")
    back = wait_until(lambda: (lambda s, b: b if s == 200 and b["probeStatus"] == "CONNECTED"
                               and not b.get("versionError") else None)(*summary()))
    if back is None:
        _, cur = summary()
        print(f"  [FAIL] 恢复后状态未回到 CONNECTED：{cur['probeStatus']} / {cur.get('versionError')}")
        ok = False
    else:
        print("  [PASS] 两实例版本一致后自动恢复 CONNECTED")
        status, _ = summary("incremental", "HEAD~1")
        if status == 200:
            print("  [PASS] 增量口径随之恢复可用")
        else:
            print(f"  [FAIL] 恢复后增量口径仍不可用：{status}")
            ok = False

    print("\n" + "-" * 78)
    # ---- 按实例分别归一化（实例对比视图的数据源） ----
    print()
    print("  >> 各实例分别归一化：GET /api/coverage/instances")
    agg_before = sum(f["coveredLines"] for f in must(*summary(), what="summary")["files"])
    st, per = http(f"{PLATFORM}/api/coverage/instances")
    if st != 200:
        print(f"  [FAIL] 取各实例覆盖失败：{st} {per}")
        ok = False
    else:
        rows = [r for r in per["instances"] if r["coveredLines"] is not None]
        for r in per["instances"]:
            print(f"    {r['endpoint']:<24s} {r['status']:<12s} "
                  f"{r['overallRatio']}%  已覆盖 {r['coveredLines']}")
        if len(per["instances"]) < 2 or not rows:
            print("  [FAIL] 至少应有两个实例分别给出覆盖")
            ok = False
        else:
            # 聚合是并集：不会比任何单台少（否则多实例白做），
            # 也不会等于简单相加（同一行被多台覆盖只能算一次）
            mx = max(r["coveredLines"] for r in rows)
            sm = sum(r["coveredLines"] for r in rows)
            if mx <= agg_before <= sm and agg_before != sm:
                print(f"  [PASS] 聚合 {agg_before} 行落在并集区间内"
                      f"（单实例最大 {mx} ≤ 聚合 ≤ 相加 {sm}，且不等于相加）")
            else:
                print(f"  [FAIL] 聚合 {agg_before} 不符合并集语义：最大 {mx} / 相加 {sm}")
                ok = False

        # 按实例取数走的是非破坏性接口，多取这一次不能把热路径的累计弄丢
        time.sleep(8)
        agg_after = sum(f["coveredLines"] for f in must(*summary(), what="summary")["files"])
        if agg_after >= agg_before:
            print(f"  [PASS] 额外取数未损坏聚合累计（{agg_before} → {agg_after}）")
        else:
            print(f"  [FAIL] 按实例取数把聚合弄丢了 {agg_before - agg_after} 行")
            ok = False

    print()
    print("  验收结论：" + ("全部通过" if ok else "存在失败项"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
