"""
P2 端到端验收：Go 接入。真实 Go 服务、真实 -cover 插桩、真实 covdata 归一化，无 mock。

验证命题：
  1. Go 的行级染色与 Java 同等质量 —— 清零后全红，只调用一部分接口时，
     未走到的分支（含同一函数内的分支）保持未覆盖；
  2. 清零真的生效 —— 走的是 runtime/coverage.ClearCounters，
     它要求 -covermode=atomic，配错时 Go 会拒绝而不是静默不清零；
  3. 两种语言共存于同一套口径 —— 一次 summary 同时给出 Java 与 Go 的文件，
     路径都以仓库根为基准，可与 git diff 直接对齐；
  4. 多个 Go 实例聚合成并集 —— Go 走的是与 Java 完全不同的合并路径
     （Java 在 exec 层取或，Go 把多份 meta/counters 交给 covdata 按块求和），
     合错了就是静默少算：界面上只是几行没变绿，看不出是 bug；
  5. 场景归因对 Go 同样成立 —— 只在 Go 上跑的场景不该染到 Java 的代码，反之亦然；
  6. 版本一致性校验覆盖 Go —— Go 源码相对产物漂移时，增量口径拒绝出报告并点名 Go 文件，
     而不是给出一份行号错位、看着却很正常的结果。

被测 Go 服务的既有源码一行未改：探针文件由 build tag 守卫、与 main 同包。
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLATFORM = "http://localhost:18090"
GO = "http://localhost:18070"
GO2 = "http://localhost:18071"
JAVA = "http://localhost:18080"
GOFILE = "demo-service-go/main.go"
JAVAFILE = "demo-service/src/main/java/com/shop/order/service/OrderService.java"
POLL_SEC = 20


def http(url, method="GET"):
    req = urllib.request.Request(url, method=method, data=b"" if method == "POST" else None)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)


def must(status, body, what):
    if status != 200:
        print(f"!! {what} 返回 {status}: {body}")
        sys.exit(1)
    return body


def detail(path, scenario=None):
    url = f"{PLATFORM}/api/coverage/file?path={urllib.parse.quote(path)}"
    if scenario:
        url += "&scenarioId=" + urllib.parse.quote(scenario)
    return must(*http(url), what=f"/api/coverage/file[{path}]")


def status_of(path, needle, scenario=None):
    """按整行代码文本定位，不写死行号。
    必须整行相等而非子串包含：`return o` 会先撞上 `return o.Status`。"""
    for r in detail(path, scenario)["rows"]:
        if r["text"].strip() == needle:
            return r["line"], r["status"]
    print(f"!! 在 {path} 里找不到整行为「{needle}」的代码")
    sys.exit(1)


def wait_until(check, secs=POLL_SEC):
    deadline = time.time() + secs
    while time.time() < deadline:
        got = check()
        if got is not None:
            return got
        time.sleep(0.5)
    return None


def main():
    print("=" * 78)
    print("P2 端到端验收 —— Go 接入")
    print("=" * 78)

    s = must(*http(f"{PLATFORM}/api/coverage/summary"), what="/api/coverage/summary")
    go_eps = [i for i in s["instances"] if i["endpoint"].startswith("go://")]
    java_eps = [i for i in s["instances"] if i["endpoint"].startswith("java://")]
    print(f"\n  被测实例：Java {len(java_eps)} 个，Go {len(go_eps)} 个")
    for i in s["instances"]:
        print(f"    {i['endpoint']:<24s} {i['status']:<13s} {str(i['buildCommit'])[:8]}")
    if len(go_eps) < 2 or not java_eps:
        print("!! 需要至少 2 个 Go 实例与 1 个 Java 实例同时在线（多实例聚合命题要用到）")
        sys.exit(1)
    if any(i["status"] != "CONNECTED" for i in s["instances"]):
        print(f"!! 验收开始前要求全部实例在线：{[(i['endpoint'], i['status']) for i in s['instances']]}")
        sys.exit(1)
    if s["versionError"]:
        print(f"!! 实例间版本不一致：{s['versionError']}")
        sys.exit(1)

    ok = True

    # ---- 3. 两种语言共存于同一套口径 ----
    paths = {f["path"] for f in s["files"]}
    if GOFILE in paths and JAVAFILE in paths:
        print(f"\n  [PASS] 一次 summary 同时给出两种语言的文件，共 {len(paths)} 个")
        print(f"         Go  : {GOFILE}")
        print(f"         Java: {JAVAFILE}")
    else:
        print(f"  [FAIL] 两种语言未共存：{sorted(paths)}")
        ok = False
    bad = [p for p in paths if p.startswith("com/") or p.startswith("github.com/")]
    if bad:
        print(f"  [FAIL] 存在非仓库根基准的路径，无法与 git diff 对齐：{bad}")
        ok = False
    else:
        print("  [PASS] 所有路径均以仓库根为基准，可直接与 git diff 对齐")

    # ---- 2. 清零对 Go 生效 ----
    print("\n  >> 清零全部实例（Go 侧走 runtime/coverage.ClearCounters）")
    must(*http(f"{PLATFORM}/api/coverage/reset", "POST"), what="/api/coverage/reset")
    zero = wait_until(lambda: (lambda d: d if d["coveredLines"] == 0 else None)(detail(GOFILE)))
    if zero is None:
        print(f"  [FAIL] 清零后 Go 文件仍有 {detail(GOFILE)['coveredLines']} 行被覆盖")
        ok = False
    else:
        print(f"  [PASS] 清零后 Go 文件 0 行覆盖 / {zero['missedLines']} 行未覆盖")
        print("         （ClearCounters 要求 -covermode=atomic，配错时 Go 会报错而非静默失效）")

    # ---- 1. 行级染色质量 ----
    print("\n  >> 只调用 Go 的查询接口，且只查存在的订单")
    r = http(f"{GO}/api/order/query?bizNo=G1002")[1]
    print(f"     GET {GO}/api/order/query?bizNo=G1002 → {json.dumps(r, ensure_ascii=False)}")

    hit = wait_until(lambda: (lambda t: t if t[1] == "COVERED" else None)
                     (status_of(GOFILE, "return o")))
    if hit is None:
        print("  [FAIL] 调用查询接口后 Go 代码未变绿")
        sys.exit(1)

    checks = [
        ("return o", "COVERED", "查询成功分支"),
        ("return nil", "MISSED", "同一函数内未走到的 not-found 分支"),
        ('return "NOT_REFUNDABLE:" + o.Status', "MISSED", "未调用的退款接口"),
        ('return "DUPLICATE_CALLBACK:" + bizNo', "MISSED", "未调用的回调接口"),
    ]
    print()
    for needle, want, desc in checks:
        line, st = status_of(GOFILE, needle)
        mark = "[PASS]" if st == want else "[FAIL]"
        if st != want:
            ok = False
        print(f"  {mark} L{line:<4d} {st:<8s} 期望 {want:<8s} —— {desc}")

    # Go 的覆盖块是「起行→止行」的文本区间，空行夹在中间也会被算成可执行行。
    # Java 侧空行是 EMPTY，两边口径必须一致：否则 diff 里的空行会挤进增量分母
    blanks = [r["line"] for r in detail(GOFILE)["rows"]
              if not r["text"].strip() and r["status"] != "EMPTY"]
    if blanks:
        print(f"  [FAIL] 空行被算进了 Go 的可执行行，与 Java 口径不一致：L{blanks}")
        ok = False
    else:
        print(f"  [PASS] 空行一律为 EMPTY，与 Java 同一口径（不会挤进增量分母）")

    # ---- 4. 多个 Go 实例聚合成并集 ----
    # 上一步只在 Go#1 上调了查询，Go#2 至此一行未跑。现在只打 Go#2 的退款接口：
    # 两台各自独有的行必须同时为绿。少任何一边，都说明 covdata 合并把一份 dump 吞了
    print(f"\n  >> 只在 Go#2 上调用退款接口（G1001 是 CREATED，不可退款，与业务状态无关）")
    r = http(f"{GO2}/api/order/refund?bizNo=G1001&amount=1", "POST")[1]
    print(f"     POST {GO2}/api/order/refund?bizNo=G1001&amount=1 → {json.dumps(r, ensure_ascii=False)}")
    only2 = 'return "NOT_REFUNDABLE:" + o.Status'
    merged = wait_until(lambda: (lambda t: t if t[1] == "COVERED" else None)(status_of(GOFILE, only2)))
    q_line, q_st = status_of(GOFILE, "return o")
    if merged and q_st == "COVERED":
        print(f"  [PASS] Go#1 独有的 L{q_line} 与 Go#2 独有的 L{merged[0]} 同时为 COVERED "
              f"—— 多实例聚合确实是并集")
    else:
        r2 = status_of(GOFILE, only2)
        print(f"  [FAIL] 多实例聚合丢了数据：Go#1 独有行 L{q_line}={q_st}，"
              f"Go#2 独有行 L{r2[0]}={r2[1]}")
        ok = False

    # ---- 5. 场景归因对 Go 成立 ----
    print("\n  >> 场景归因：一个场景只在 Go 上跑，另一个只在 Java 上跑")
    listing = must(*http(f"{PLATFORM}/api/scenario"), what="/api/scenario")
    if listing.get("active"):
        http(f"{PLATFORM}/api/scenario/stop", "POST")
        listing = must(*http(f"{PLATFORM}/api/scenario"), what="/api/scenario")
    used = {x["scenarioId"] for x in listing["scenarios"]}
    n = 1
    while f"go-only-{n}" in used or f"java-only-{n}" in used:
        n += 1
    s_go, s_java = f"go-only-{n}", f"java-only-{n}"

    must(*http(f"{PLATFORM}/api/scenario/start?scenarioId={s_go}", "POST"), what="start")
    http(f"{GO}/api/order/refund?bizNo=NOPE&amount=1", "POST")
    must(*http(f"{PLATFORM}/api/scenario/stop", "POST"), what="stop")

    must(*http(f"{PLATFORM}/api/scenario/start?scenarioId={s_java}", "POST"), what="start")
    http(f"{JAVA}/api/order/query?bizNo=NOPE")
    must(*http(f"{PLATFORM}/api/scenario/stop", "POST"), what="stop")

    go_in_go = detail(GOFILE, s_go)["coveredLines"]
    java_in_go = detail(JAVAFILE, s_go)["coveredLines"]
    go_in_java = detail(GOFILE, s_java)["coveredLines"]
    java_in_java = detail(JAVAFILE, s_java)["coveredLines"]
    print(f"    场景 {s_go:<12s} Go 覆盖 {go_in_go:>3d} 行 / Java 覆盖 {java_in_go:>3d} 行")
    print(f"    场景 {s_java:<12s} Go 覆盖 {go_in_java:>3d} 行 / Java 覆盖 {java_in_java:>3d} 行")
    if go_in_go > 0 and java_in_go == 0:
        print(f"  [PASS] 只跑 Go 的场景没有染到任何 Java 代码")
    else:
        print(f"  [FAIL] 只跑 Go 的场景却染到了 Java 代码（{java_in_go} 行）")
        ok = False
    if java_in_java > 0 and go_in_java == 0:
        print(f"  [PASS] 只跑 Java 的场景没有染到任何 Go 代码")
    else:
        print(f"  [FAIL] 只跑 Java 的场景却染到了 Go 代码（{go_in_java} 行）")
        ok = False

    # ---- 6. Go 源码漂移同样要拒绝出增量报告 ----
    # 版本校验原本只在 Java 源码上验证过。Go 走的是另一条采集链路，
    # 若 sourceDrift 的范围没把 Go 源码根算进去，漂移会被漏判，
    # 得到的是一份行号错位、看上去却完全正常的增量报告
    print("\n  >> 改动 Go 源码，模拟「产物是旧的、源码已经改了」")
    target = ROOT / GOFILE
    original = target.read_bytes()
    try:
        target.write_bytes(original + b"\n// drift\n")
        status, body = http(f"{PLATFORM}/api/coverage/summary?mode=incremental&baseline=HEAD~1")
        if status == 409 and GOFILE in body.get("error", ""):
            print(f"  [PASS] 拒绝出增量报告（HTTP 409）并点名 Go 文件：{body['error']}")
        else:
            print(f"  [FAIL] Go 源码已漂移，平台却返回 {status}：{body}")
            ok = False
        status, _ = http(f"{PLATFORM}/api/coverage/summary")
        if status == 200:
            print("  [PASS] 全量口径不受影响（漂移只影响需要对齐行号的增量口径）")
        else:
            print(f"  [FAIL] 全量口径被误伤：{status}")
            ok = False
    finally:
        target.write_bytes(original)

    status, _ = http(f"{PLATFORM}/api/coverage/summary?mode=incremental&baseline=HEAD~1")
    if status == 200:
        print("  [PASS] Go 源码恢复后增量报告自动恢复可用")
    else:
        print(f"  [FAIL] Go 源码已恢复，平台仍拒绝出报告：{status}")
        ok = False

    print("\n" + "-" * 78)
    print("  验收结论：" + ("全部通过" if ok else "存在失败项"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
