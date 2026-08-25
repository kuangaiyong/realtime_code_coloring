"""
P1 端到端验收：场景级归因。真实服务、真实探针、真实 HTTP，无 mock。

验证命题：
  1. 场景边界成立 —— start 清零、stop 定格，归档下来的就是这段窗口的独占覆盖；
  2. 归因真的隔离 —— 两个场景各跑一个接口，各自只染到自己那个接口的代码。
     这是「染色」区别于「看个覆盖率数字」的核心：能回答「这些行是被哪个场景跑到的」；
  3. 破坏归因前提的操作一律拒绝（并发场景、进行中清零、重复 ID、空场景 stop），
     而不是给出一份看着正常、其实已被污染的报告；
  4. 场景与增量口径正交 —— 「场景 × 增量」能回答「这个场景覆盖了本次改动的哪几行」。

两个场景选用与业务状态无关的调用，使脚本可重复执行：无论前面的用例把订单
改成什么状态，「查询」与「退款」这两个接口方法是否被进入都不会改变。
接口归属按 OrderController 里的方法行区间判定，不写死行号。
"""
import json
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLATFORM = "http://localhost:18090"
DEMO = "http://localhost:18080"
SRC = "demo-service/src/main/java"
CONTROLLER = "demo-service/src/main/java/com/shop/order/controller/OrderController.java"
MAPPING = re.compile(r'@(?:Get|Post)Mapping\("/(\w+)"\)')


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


def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


def rows_of(scenario_id, path=CONTROLLER):
    url = (f"{PLATFORM}/api/coverage/file?path={urllib.parse.quote(path)}"
           f"&mode=full&scenarioId={urllib.parse.quote(scenario_id)}")
    body = must(*http(url), what=f"场景 {scenario_id} 的 {path}")
    if not body.get("found"):
        print(f"!! 场景 {scenario_id} 读不到 {path}: {body}")
        sys.exit(1)
    return body["rows"]


def method_ranges(rows):
    """切出各接口方法的行区间：从 @XxxMapping("/名字") 到下一个方法开始之前"""
    marks = []
    for r in rows:
        t = r["text"].strip()
        m = MAPPING.match(t)
        if m:
            marks.append((m.group(1), r["line"]))
        elif t.startswith("private Map<String, Object> resp("):
            marks.append((None, r["line"]))  # 公共辅助方法，作为最后一个方法的右边界
    out = {}
    for i, (name, start) in enumerate(marks):
        if name is None:
            continue
        end = (marks[i + 1][1] - 1) if i + 1 < len(marks) else rows[-1]["line"]
        out[name] = (start, end)
    return out


def covered_in(rows, rng):
    return {r["line"] for r in rows if r["status"] == "COVERED" and rng[0] <= r["line"] <= rng[1]}


def expect_reject(status, body, what, want=409):
    if status == want:
        print(f"  [PASS] {what} → HTTP {status}：{body.get('error')}")
        return True
    print(f"  [FAIL] {what} 期望 HTTP {want}，实际 {status}：{body}")
    return False


def main():
    print("=" * 78)
    print("P1 端到端验收 —— 场景级归因")
    print("=" * 78)

    base = must(*http(f"{PLATFORM}/api/coverage/summary"), what="/api/coverage/summary")
    if base["probeStatus"] != "CONNECTED":
        print(f"!! 探针未连接（{base['probeStatus']}）：{base.get('lastError')}")
        sys.exit(1)

    # 上一次跑剩下的活跃场景会让 start 直接冲突，先收尾
    live = must(*http(f"{PLATFORM}/api/scenario"), what="/api/scenario")
    if live.get("active"):
        print(f"  >> 清理上一轮遗留的活跃场景 {live['active']}")
        http(f"{PLATFORM}/api/scenario/stop", "POST")
        # 收尾会把它转成已归档，必须重新取一次列表，
        # 否则用旧列表挑出来的 ID 可能正好撞上刚归档的那个
        live = must(*http(f"{PLATFORM}/api/scenario"), what="/api/scenario")

    used = {s["scenarioId"] for s in live["scenarios"]}
    n = 1
    while f"query-only-{n}" in used or f"refund-only-{n}" in used:
        n += 1
    s_query, s_refund = f"query-only-{n}", f"refund-only-{n}"

    ok = True

    # ---- 场景一：只调用查询接口 ----
    print(f"\n  >> 场景 {s_query}：start → 只调用接口 B（查询）→ stop")
    must(*http(f"{PLATFORM}/api/scenario/start?scenarioId={s_query}", "POST"), what="start")

    zero = must(*http(f"{PLATFORM}/api/coverage/summary"), what="start 后的实时快照")
    if zero["overallRatio"] == 0.0:
        print("  [PASS] start 已清零计数器，场景从零开始计覆盖")
    else:
        print(f"  [FAIL] start 后覆盖率应为 0，实际 {zero['overallRatio']}%")
        ok = False

    # 场景进行中，任何会截断归因窗口的操作都必须被拒绝
    ok &= expect_reject(*http(f"{PLATFORM}/api/scenario/start?scenarioId=another", "POST"),
                        what="场景进行中再开一个场景")
    ok &= expect_reject(*http(f"{PLATFORM}/api/coverage/reset", "POST"),
                        what="场景进行中清零计数器")
    # start 已经把计数器清零，此刻的比例只是这个场景窗口内的覆盖。
    # 门禁若照常判，会回「不通过 · 还需覆盖 N 行」，把人指去补测试，
    # 而真正的原因是有人正在录场景
    ok &= expect_reject(*http(f"{PLATFORM}/api/coverage/gate?mode=full"),
                        what="场景进行中做门禁判定")
    # 进行中的场景不在归档表里，若不先看 active 就会被报成「不存在」，
    # 把用户引去排查场景 ID 是不是拼错了
    ok &= expect_reject(*http(f"{PLATFORM}/api/coverage/summary?scenarioId={s_query}"),
                        what="查看尚未结束的场景")

    for biz in ("A1002", "NOPE"):
        _, r = http(f"{DEMO}/api/order/query?bizNo={biz}")
        print(f"     GET  /api/order/query?bizNo={biz} → {json.dumps(r, ensure_ascii=False)}")
    must(*http(f"{PLATFORM}/api/scenario/stop", "POST"), what="stop")

    # ---- 场景二：只调用退款接口 ----
    print(f"\n  >> 场景 {s_refund}：start → 只调用接口 C（退款）→ stop")
    must(*http(f"{PLATFORM}/api/scenario/start?scenarioId={s_refund}", "POST"), what="start")
    _, r = http(f"{DEMO}/api/order/refund?bizNo=NOPE&amount=1", "POST")
    print(f"     POST /api/order/refund?bizNo=NOPE&amount=1 → {json.dumps(r, ensure_ascii=False)}")
    must(*http(f"{PLATFORM}/api/scenario/stop", "POST"), what="stop")

    # ---- 归因隔离：各自只染到自己那个接口 ----
    q_rows, r_rows = rows_of(s_query), rows_of(s_refund)
    methods = method_ranges(q_rows)
    for need in ("query", "refund", "cancel"):
        if need not in methods:
            print(f"!! 未能从 OrderController 中识别出接口方法 {need}，实际识别到 {methods}")
            sys.exit(1)
    print(f"\n  OrderController 接口方法行区间：" +
          "  ".join(f"{k} L{v[0]}-{v[1]}" for k, v in sorted(methods.items())))

    print("\n  归因结果（各场景在 OrderController 中跑到的行）")
    for name, rows in ((s_query, q_rows), (s_refund, r_rows)):
        hits = {m: sorted(covered_in(rows, rng)) for m, rng in sorted(methods.items())}
        print(f"    {name}:  " + "  ".join(f"{m}={v if v else '—'}" for m, v in hits.items()))

    q_hit, q_leak = covered_in(q_rows, methods["query"]), covered_in(q_rows, methods["refund"])
    r_hit, r_leak = covered_in(r_rows, methods["refund"]), covered_in(r_rows, methods["query"])
    if q_hit and not q_leak:
        print(f"  [PASS] {s_query} 跑到了查询接口 {len(q_hit)} 行，退款接口 0 行")
    else:
        print(f"  [FAIL] {s_query} 归因不纯：查询 {sorted(q_hit)}，越界到退款 {sorted(q_leak)}")
        ok = False
    if r_hit and not r_leak:
        print(f"  [PASS] {s_refund} 跑到了退款接口 {len(r_hit)} 行，查询接口 0 行")
    else:
        print(f"  [FAIL] {s_refund} 归因不纯：退款 {sorted(r_hit)}，越界到查询 {sorted(r_leak)}")
        ok = False

    q_all = {r["line"] for r in q_rows if r["status"] == "COVERED"}
    r_all = {r["line"] for r in r_rows if r["status"] == "COVERED"}
    if q_all != r_all:
        print(f"  [PASS] 两个场景的覆盖行集合不同（各自独占 {len(q_all - r_all)} / {len(r_all - q_all)} 行）"
              f"—— 归因确实隔离，而不是两次都返回同一份累计覆盖")
    else:
        print("  [FAIL] 两个场景的覆盖行集合完全相同，说明 start 的清零没有生效")
        ok = False

    # ---- 拒绝路径：重复 ID / 无活跃场景 stop / 未知场景 ----
    print("\n  破坏归因前提的操作")
    ok &= expect_reject(*http(f"{PLATFORM}/api/scenario/start?scenarioId={s_query}", "POST"),
                        what="复用已归档的场景 ID")
    ok &= expect_reject(*http(f"{PLATFORM}/api/scenario/stop", "POST"),
                        what="没有活跃场景时 stop")
    ok &= expect_reject(*http(f"{PLATFORM}/api/coverage/summary?scenarioId=never-existed"),
                        what="查看不存在的场景", want=404)

    # ---- 场景 × 增量：这个场景覆盖了本次改动的哪几行 ----
    baseline = git("rev-parse", git("log", "-1", "--format=%H", "--", SRC) + "^")
    status, inc = http(f"{PLATFORM}/api/coverage/summary?mode=incremental"
                       f"&baseline={baseline}&scenarioId={urllib.parse.quote(s_query)}")
    if status == 200:
        print(f"\n  [PASS] 场景 × 增量可组合：场景 {s_query} 相对基线 {baseline[:8]} 的增量行覆盖率 "
              f"{inc['overallRatio']}%（{inc['changedFiles']} 个变更文件）")
        print("         —— 回答的是「这个场景覆盖了本次改动的哪几行」")
    else:
        print(f"  [FAIL] 场景 × 增量组合失败：{status} {inc}")
        ok = False

    listing = must(*http(f"{PLATFORM}/api/scenario"), what="/api/scenario")
    print(f"\n  已归档场景 {len(listing['scenarios'])} 个，当前活跃：{listing['active']}")
    for s in listing["scenarios"][-4:]:
        print(f"    {s['scenarioId']:<18s} {s['files']} 文件 / {s['overallRatio']:>5}%  "
              f"{s['startedAt'][11:19]} → {s['stoppedAt'][11:19]}")
    if listing["active"] is not None:
        print("  [FAIL] 所有场景都已结束，active 却不为空")
        ok = False

    print("\n" + "-" * 78)
    print("  验收结论：" + ("全部通过" if ok else "存在失败项"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
