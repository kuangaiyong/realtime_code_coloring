"""
P3 端到端验收：C++ 接入。真实 C++ 服务、真实 gcov 插桩、真实 gcov 归一化，无 mock。

验证命题：
  1. C++ 的行级染色与 Java 同等质量 —— 清零后全红，只调用一部分接口时，
     未走到的分支（含同一函数内的分支）保持未覆盖；且 gcov 给得出「非可执行行」，
     所以 C++ 能做到 COVERED/MISSED/PARTIAL/EMPTY 四态，与 JaCoCo 对齐（Go 只有三态）；
  2. 清零真的生效 —— 走的是 __gcov_reset() 加删 .gcda。少删 .gcda 的话，
     gcov 的合并语义会把上一轮的覆盖带回来，而界面上看不出任何异样；
  3. 三种语言共存于同一套口径 —— 一次 summary 同时给出 Java、Go、C++ 的文件，
     路径都以仓库根为基准，可与 git diff 直接对齐；
  4. 多个 C++ 实例聚合成并集 —— 走的是第三条合并路径（gcov-tool merge，
     在 .gcda 原生层面合并），合错了就是静默少算；
  5. 场景归因对 C++ 同样成立 —— 只在 C++ 上跑的场景不该染到 Java/Go 的代码；
  6. 版本一致性校验覆盖 C++ —— C++ 源码相对产物漂移时，增量口径拒绝出报告并点名 C++ 文件。

被测 C++ 服务的既有业务源码一行未改：探针是独立编译单元，靠全局对象的构造函数
（早于 main 执行）自动启动，业务代码不 include 也不调用它任何东西。
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
CPP = "http://localhost:18060"
CPP2 = "http://localhost:18061"
GO = "http://localhost:18070"
JAVA = "http://localhost:18080"
CPPFILE = "demo-service-cpp/order.cpp"
GOFILE = "demo-service-go/main.go"
JAVAFILE = "demo-service/src/main/java/com/shop/order/service/OrderService.java"
POLL_SEC = 25


def http(url, method="GET"):
    req = urllib.request.Request(url, method=method, data=b"" if method == "POST" else None)
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
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
    """按整行代码文本定位，不写死行号"""
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
    print("P3 端到端验收 —— C++ 接入")
    print("=" * 78)

    s = must(*http(f"{PLATFORM}/api/coverage/summary"), what="/api/coverage/summary")
    by_lang = {}
    for i in s["instances"]:
        by_lang.setdefault(i["endpoint"].split("://")[0], []).append(i)
    print(f"\n  被测实例：" + "，".join(f"{k} {len(v)} 个" for k, v in sorted(by_lang.items())))
    for i in s["instances"]:
        print(f"    {i['endpoint']:<24s} {i['status']:<13s} {str(i['buildCommit'])[:8]}")
    if len(by_lang.get("cpp", [])) < 2 or not by_lang.get("java") or not by_lang.get("go"):
        print("!! 需要至少 2 个 C++ 实例，且 Java / Go 实例同时在线")
        sys.exit(1)
    if any(i["status"] != "CONNECTED" for i in s["instances"]):
        print("!! 验收开始前要求全部实例在线")
        sys.exit(1)
    if s["versionError"]:
        print(f"!! 实例间版本不一致：{s['versionError']}")
        sys.exit(1)

    ok = True

    # ---- 3. 三种语言共存于同一套口径 ----
    paths = {f["path"] for f in s["files"]}
    missing = [p for p in (CPPFILE, GOFILE, JAVAFILE) if p not in paths]
    if missing:
        print(f"  [FAIL] 三种语言未共存，缺少：{missing}\n         实际：{sorted(paths)}")
        ok = False
    else:
        print(f"\n  [PASS] 一次 summary 同时给出三种语言的文件，共 {len(paths)} 个")
        for tag, p in (("C++ ", CPPFILE), ("Go  ", GOFILE), ("Java", JAVAFILE)):
            print(f"         {tag}: {p}")
    bad = [p for p in paths if p.startswith(("com/", "github.com/", "order.cpp", "main.cpp"))]
    if bad:
        print(f"  [FAIL] 存在非仓库根基准的路径，无法与 git diff 对齐：{bad}")
        ok = False
    else:
        print("  [PASS] 所有路径均以仓库根为基准，可直接与 git diff 对齐")

    # ---- 2. 清零对 C++ 生效 ----
    # 光调 __gcov_reset() 是不够的：.gcda 写入是合并语义，不把文件删掉，
    # 下一次 dump 会把上一轮的覆盖原样带回来 —— 而界面上完全看不出异样
    print("\n  >> 清零全部实例（C++ 侧走 __gcov_reset() + 删除 .gcda）")
    must(*http(f"{PLATFORM}/api/coverage/reset", "POST"), what="/api/coverage/reset")
    zero = wait_until(lambda: (lambda d: d if d["coveredLines"] == 0 else None)(detail(CPPFILE)))
    if zero is None:
        print(f"  [FAIL] 清零后 C++ 文件仍有 {detail(CPPFILE)['coveredLines']} 行被覆盖")
        ok = False
    else:
        print(f"  [PASS] 清零后 C++ 文件 0 行覆盖 / {zero['missedLines']} 行未覆盖")

    # ---- 1. 行级染色质量 ----
    print("\n  >> 只调用 C++ 的查询接口，且只查存在的订单")
    r = http(f"{CPP}/api/order/query?bizNo=C1002")[1]
    print(f"     GET {CPP}/api/order/query?bizNo=C1002 → {json.dumps(r, ensure_ascii=False)}")

    hit = wait_until(lambda: (lambda t: t if t[1] in ("COVERED", "PARTIAL") else None)
                     (status_of(CPPFILE, "out = it->second;")))
    if hit is None:
        print("  [FAIL] 调用查询接口后 C++ 代码未变绿")
        sys.exit(1)

    checks = [
        ("out = it->second;", ("COVERED", "PARTIAL"), "查询成功分支"),
        ("return false;", ("MISSED",), "同一函数内未走到的 not-found 分支"),
        ('return "NOT_REFUNDABLE:" + o.status;', ("MISSED",), "未调用的退款接口"),
        ('return "DUPLICATE_CALLBACK:" + bizNo;', ("MISSED",), "未调用的回调接口"),
    ]
    print()
    for needle, want, desc in checks:
        line, st = status_of(CPPFILE, needle)
        mark = "[PASS]" if st in want else "[FAIL]"
        if st not in want:
            ok = False
        print(f"  {mark} L{line:<4d} {st:<8s} 期望 {'/'.join(want):<16s} —— {desc}")

    # gcov 明确给得出「非可执行行」（输出里的 -），这一点 Go 的块模型做不到
    rows = detail(CPPFILE)["rows"]
    states = {}
    for r0 in rows:
        states[r0["status"]] = states.get(r0["status"], 0) + 1
    empties = [r0["line"] for r0 in rows if not r0["text"].strip() and r0["status"] != "EMPTY"]
    if empties:
        print(f"  [FAIL] 空行被算进了可执行行：L{empties}")
        ok = False
    else:
        print(f"  [PASS] 空行一律为 EMPTY，与 Java 同一口径（逐态行数 {states}）")

    # ---- 4. 多个 C++ 实例聚合成并集 ----
    print(f"\n  >> 只在 C++#2 上调用退款接口（C1001 是 CREATED，不可退款，与业务状态无关）")
    r = http(f"{CPP2}/api/order/refund?bizNo=C1001&amount=1", "POST")[1]
    print(f"     POST {CPP2}/api/order/refund?bizNo=C1001&amount=1 → {json.dumps(r, ensure_ascii=False)}")
    only2 = 'return "NOT_REFUNDABLE:" + o.status;'
    merged = wait_until(lambda: (lambda t: t if t[1] in ("COVERED", "PARTIAL") else None)
                        (status_of(CPPFILE, only2)))
    q_line, q_st = status_of(CPPFILE, "out = it->second;")
    if merged and q_st in ("COVERED", "PARTIAL"):
        print(f"  [PASS] C++#1 独有的 L{q_line} 与 C++#2 独有的 L{merged[0]} 同时已覆盖 "
              f"—— gcov-tool merge 的聚合确实是并集")
    else:
        r2 = status_of(CPPFILE, only2)
        print(f"  [FAIL] 多实例聚合丢了数据：#1 独有行 L{q_line}={q_st}，#2 独有行 L{r2[0]}={r2[1]}")
        ok = False

    # ---- 5. 场景归因对 C++ 成立 ----
    print("\n  >> 场景归因：一个场景只在 C++ 上跑，另一个只在 Java 上跑")
    listing = must(*http(f"{PLATFORM}/api/scenario"), what="/api/scenario")
    if listing.get("active"):
        http(f"{PLATFORM}/api/scenario/stop", "POST")
        listing = must(*http(f"{PLATFORM}/api/scenario"), what="/api/scenario")
    used = {x["scenarioId"] for x in listing["scenarios"]}
    n = 1
    while f"cpp-only-{n}" in used or f"java-only-cpp-{n}" in used:
        n += 1
    s_cpp, s_java = f"cpp-only-{n}", f"java-only-cpp-{n}"

    must(*http(f"{PLATFORM}/api/scenario/start?scenarioId={s_cpp}", "POST"), what="start")
    http(f"{CPP}/api/order/refund?bizNo=NOPE&amount=1", "POST")
    must(*http(f"{PLATFORM}/api/scenario/stop", "POST"), what="stop")

    must(*http(f"{PLATFORM}/api/scenario/start?scenarioId={s_java}", "POST"), what="start")
    http(f"{JAVA}/api/order/query?bizNo=NOPE")
    must(*http(f"{PLATFORM}/api/scenario/stop", "POST"), what="stop")

    cpp_in_cpp = detail(CPPFILE, s_cpp)["coveredLines"]
    java_in_cpp = detail(JAVAFILE, s_cpp)["coveredLines"]
    go_in_cpp = detail(GOFILE, s_cpp)["coveredLines"]
    cpp_in_java = detail(CPPFILE, s_java)["coveredLines"]
    java_in_java = detail(JAVAFILE, s_java)["coveredLines"]
    print(f"    场景 {s_cpp:<16s} C++ {cpp_in_cpp:>3d} 行 / Java {java_in_cpp:>3d} 行 / Go {go_in_cpp:>3d} 行")
    print(f"    场景 {s_java:<16s} C++ {cpp_in_java:>3d} 行 / Java {java_in_java:>3d} 行")
    if cpp_in_cpp > 0 and java_in_cpp == 0 and go_in_cpp == 0:
        print("  [PASS] 只跑 C++ 的场景没有染到任何 Java / Go 代码")
    else:
        print(f"  [FAIL] 只跑 C++ 的场景越界了：Java {java_in_cpp} 行，Go {go_in_cpp} 行")
        ok = False
    if java_in_java > 0 and cpp_in_java == 0:
        print("  [PASS] 只跑 Java 的场景没有染到任何 C++ 代码")
    else:
        print(f"  [FAIL] 只跑 Java 的场景却染到了 C++ 代码（{cpp_in_java} 行）")
        ok = False

    # ---- 6. C++ 源码漂移同样要拒绝出增量报告 ----
    print("\n  >> 改动 C++ 源码，模拟「产物是旧的、源码已经改了」")
    target = ROOT / CPPFILE
    original = target.read_bytes()
    try:
        target.write_bytes(original + b"\n// drift\n")
        status, body = http(f"{PLATFORM}/api/coverage/summary?mode=incremental&baseline=HEAD~1")
        if status == 409 and CPPFILE in body.get("error", ""):
            print(f"  [PASS] 拒绝出增量报告（HTTP 409）并点名 C++ 文件：{body['error']}")
        else:
            print(f"  [FAIL] C++ 源码已漂移，平台却返回 {status}：{body}")
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
        print("  [PASS] C++ 源码恢复后增量报告自动恢复可用")
    else:
        print(f"  [FAIL] C++ 源码已恢复，平台仍拒绝出报告：{status}")
        ok = False

    print("\n" + "-" * 78)
    print("  验收结论：" + ("全部通过" if ok else "存在失败项"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
