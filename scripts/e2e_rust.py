"""
P4 端到端验收：Rust 接入。真实 Rust 服务、真实 LLVM 插桩、真实 llvm-cov 归一化，无 mock。

验证命题：
  1. Rust 的行级染色成立 —— 清零后全红，只调用一部分接口时，
     未走到的分支（含同一函数内的分支）保持未覆盖；
     LCOV 的 DA 只列可执行行，所以空行/注释天然是 EMPTY，无需另行剔除；
  2. 清零真的生效 —— 走的是 __llvm_profile_reset_counters() 加删 .profraw。
     少删 .profraw 的话，LLVM 的合并写入会把上一轮的覆盖带回来，界面上看不出任何异样；
  3. 四种语言共存于同一套口径 —— 一次 summary 同时给出 Java、Go、C++、Rust 的文件，
     路径都以仓库根为基准，可与 git diff 直接对齐；
  4. 多个 Rust 实例聚合成并集 —— 走的是第四条合并路径（llvm-profdata merge，
     在 .profraw 原生层面合并），合错了就是静默少算；
  5. 场景归因对 Rust 同样成立 —— 只在 Rust 上跑的场景不该染到其他语言的代码；
  6. 版本一致性校验覆盖 Rust —— Rust 源码相对产物漂移时，增量口径拒绝出报告并点名 Rust 文件；
  7. 同一个泛型被单态化成好几个实例时，方法明细按源码合并 —— 一个定义一条、任一实例跑过即为已调用、
     条数与文件级方法数相等；行也按源码合并，与 C++ 模板按实例分开计是有意的差异。

被测 Rust 服务的既有业务源码一行未改，连 Cargo.toml 都没动：探针是单独编译的 .o，
构建时经 -C link-arg 注入，靠 .CRT$XCU 段里的函数指针在 main 之前自动执行。

已知粒度差异（不是 bug）：llvm-cov 导出的 LCOV 里没有 BRDA 记录，
所以 Rust 与 Go 一样只有 COVERED/MISSED/EMPTY 三态，没有 Java / C++ 的 PARTIAL。
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
RUST = "http://localhost:18050"
RUST2 = "http://localhost:18051"
CPP = "http://localhost:18060"
GO = "http://localhost:18070"
JAVA = "http://localhost:18080"
RUSTFILE = "demo-service-rust/src/order.rs"
PRICING = "demo-service-rust/src/pricing.rs"
CPPFILE = "demo-service-cpp/order.cpp"
GOFILE = "demo-service-go/main.go"
JAVAFILE = "demo-service/src/main/java/com/shop/order/service/OrderService.java"
POLL_SEC = 25


def http(url, method="GET"):
    req = urllib.request.Request(url, method=method, data=b"" if method == "POST" else None)
    try:
    # 超时按平台的最坏情况取：一次采集要挨个 dump 8 个实例，探针挂掉时每个耗尽
    # timeout-ms（3s）＝24s，再叠加各语言的外部工具，还可能排在调度那一轮后面。
    # 凭手感填个 10s / 20s 只会换来一条与被测功能无关的假失败（已发生过两次）
        with urllib.request.urlopen(req, timeout=60) as r:
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
    print("P4 端到端验收 —— Rust 接入")
    print("=" * 78)

    s = must(*http(f"{PLATFORM}/api/coverage/summary"), what="/api/coverage/summary")
    by_lang = {}
    for i in s["instances"]:
        by_lang.setdefault(i["endpoint"].split("://")[0], []).append(i)
    print(f"\n  被测实例：" + "，".join(f"{k} {len(v)} 个" for k, v in sorted(by_lang.items())))
    for i in s["instances"]:
        print(f"    {i['endpoint']:<24s} {i['status']:<13s} {str(i['buildCommit'])[:8]}")
    if len(by_lang.get("rust", [])) < 2 or not by_lang.get("java") \
            or not by_lang.get("go") or not by_lang.get("cpp"):
        print("!! 需要至少 2 个 Rust 实例，且 Java / Go / C++ 实例同时在线")
        sys.exit(1)
    if any(i["status"] != "CONNECTED" for i in s["instances"]):
        print("!! 验收开始前要求全部实例在线")
        sys.exit(1)
    if s["versionError"]:
        print(f"!! 实例间版本不一致：{s['versionError']}")
        sys.exit(1)

    ok = True

    # ---- 3. 四种语言共存于同一套口径 ----
    paths = {f["path"] for f in s["files"]}
    missing = [p for p in (RUSTFILE, CPPFILE, GOFILE, JAVAFILE) if p not in paths]
    if missing:
        print(f"  [FAIL] 四种语言未共存，缺少：{missing}\n         实际：{sorted(paths)}")
        ok = False
    else:
        print(f"\n  [PASS] 一次 summary 同时给出四种语言的文件，共 {len(paths)} 个")
        for tag, p in (("Rust", RUSTFILE), ("C++ ", CPPFILE), ("Go  ", GOFILE), ("Java", JAVAFILE)):
            print(f"         {tag}: {p}")
    # llvm-cov 的 SF 是编译时的绝对路径，剥不干净就与 git diff 的输出对不上
    bad = [p for p in paths if p.startswith(("com/", "github.com/", "order.", "main.", "src/"))
           or ":" in p or p.startswith("/")]
    if bad:
        print(f"  [FAIL] 存在非仓库根基准的路径，无法与 git diff 对齐：{bad}")
        ok = False
    else:
        print("  [PASS] 所有路径均以仓库根为基准，可直接与 git diff 对齐")

    # ---- 2. 清零对 Rust 生效 ----
    # 光调 __llvm_profile_reset_counters() 是不够的：写入 .profraw 是合并语义，
    # 不把文件删掉，下一次 dump 会把上一轮的覆盖原样带回来 —— 而界面上完全看不出异样
    print("\n  >> 清零全部实例（Rust 侧走 __llvm_profile_reset_counters() + 删除 .profraw）")
    must(*http(f"{PLATFORM}/api/coverage/reset", "POST"), what="/api/coverage/reset")
    zero = wait_until(lambda: (lambda d: d if d["coveredLines"] == 0 else None)(detail(RUSTFILE)))
    if zero is None:
        print(f"  [FAIL] 清零后 Rust 文件仍有 {detail(RUSTFILE)['coveredLines']} 行被覆盖")
        ok = False
    else:
        print(f"  [PASS] 清零后 Rust 文件 0 行覆盖 / {zero['missedLines']} 行未覆盖")

    # ---- 1. 行级染色质量 ----
    print("\n  >> 只调用 Rust 的查询接口，且只查存在的订单")
    r = http(f"{RUST}/api/order/query?bizNo=R1002")[1]
    print(f"     GET {RUST}/api/order/query?bizNo=R1002 → {json.dumps(r, ensure_ascii=False)}")

    hit = wait_until(lambda: (lambda t: t if t[1] == "COVERED" else None)
                     (status_of(RUSTFILE, "Some(o.clone())")))
    if hit is None:
        print("  [FAIL] 调用查询接口后 Rust 代码未变绿")
        sys.exit(1)

    checks = [
        ("Some(o.clone())", ("COVERED",), "查询成功分支"),
        ("None => return None,", ("MISSED",), "同一函数内未走到的 not-found 分支"),
        ('return format!("NOT_REFUNDABLE:{}", o.status);', ("MISSED",), "未调用的退款接口"),
        ('return format!("DUPLICATE_CALLBACK:{}", biz_no);', ("MISSED",), "未调用的回调接口"),
    ]
    print()
    for needle, want, desc in checks:
        line, st = status_of(RUSTFILE, needle)
        mark = "[PASS]" if st in want else "[FAIL]"
        if st not in want:
            ok = False
        print(f"  {mark} L{line:<4d} {st:<8s} 期望 {'/'.join(want):<8s} —— {desc}")

    # LCOV 的 DA 只列可执行行，空行压根不会出现，天然是 EMPTY
    rows = detail(RUSTFILE)["rows"]
    states = {}
    for r0 in rows:
        states[r0["status"]] = states.get(r0["status"], 0) + 1
    empties = [r0["line"] for r0 in rows if not r0["text"].strip() and r0["status"] != "EMPTY"]
    if empties:
        print(f"  [FAIL] 空行被算进了可执行行：L{empties}")
        ok = False
    else:
        print(f"  [PASS] 空行一律为 EMPTY，与 Java 同一口径（逐态行数 {states}）")

    # ---- 4. 多个 Rust 实例聚合成并集 ----
    print(f"\n  >> 只在 Rust#2 上调用退款接口（R1001 是 CREATED，不可退款，与业务状态无关）")
    r = http(f"{RUST2}/api/order/refund?bizNo=R1001&amount=1", "POST")[1]
    print(f"     POST {RUST2}/api/order/refund?bizNo=R1001&amount=1 → {json.dumps(r, ensure_ascii=False)}")
    only2 = 'return format!("NOT_REFUNDABLE:{}", o.status);'
    merged = wait_until(lambda: (lambda t: t if t[1] == "COVERED" else None)
                        (status_of(RUSTFILE, only2)))
    q_line, q_st = status_of(RUSTFILE, "Some(o.clone())")
    if merged and q_st == "COVERED":
        print(f"  [PASS] Rust#1 独有的 L{q_line} 与 Rust#2 独有的 L{merged[0]} 同时已覆盖 "
              f"—— llvm-profdata merge 的聚合确实是并集")
    else:
        r2 = status_of(RUSTFILE, only2)
        print(f"  [FAIL] 多实例聚合丢了数据：#1 独有行 L{q_line}={q_st}，#2 独有行 L{r2[0]}={r2[1]}")
        ok = False

    # ---- 5. 场景归因对 Rust 成立 ----
    print("\n  >> 场景归因：一个场景只在 Rust 上跑，另一个只在 C++ 上跑")
    listing = must(*http(f"{PLATFORM}/api/scenario"), what="/api/scenario")
    if listing.get("active"):
        http(f"{PLATFORM}/api/scenario/stop", "POST")
        listing = must(*http(f"{PLATFORM}/api/scenario"), what="/api/scenario")
    used = {x["scenarioId"] for x in listing["scenarios"]}
    n = 1
    while f"rust-only-{n}" in used or f"cpp-only-rust-{n}" in used:
        n += 1
    s_rust, s_cpp = f"rust-only-{n}", f"cpp-only-rust-{n}"

    must(*http(f"{PLATFORM}/api/scenario/start?scenarioId={s_rust}", "POST"), what="start")
    http(f"{RUST}/api/order/refund?bizNo=NOPE&amount=1", "POST")
    must(*http(f"{PLATFORM}/api/scenario/stop", "POST"), what="stop")

    must(*http(f"{PLATFORM}/api/scenario/start?scenarioId={s_cpp}", "POST"), what="start")
    http(f"{CPP}/api/order/query?bizNo=NOPE")
    must(*http(f"{PLATFORM}/api/scenario/stop", "POST"), what="stop")

    rust_in_rust = detail(RUSTFILE, s_rust)["coveredLines"]
    cpp_in_rust = detail(CPPFILE, s_rust)["coveredLines"]
    java_in_rust = detail(JAVAFILE, s_rust)["coveredLines"]
    go_in_rust = detail(GOFILE, s_rust)["coveredLines"]
    rust_in_cpp = detail(RUSTFILE, s_cpp)["coveredLines"]
    cpp_in_cpp = detail(CPPFILE, s_cpp)["coveredLines"]
    print(f"    场景 {s_rust:<18s} Rust {rust_in_rust:>3d} 行 / C++ {cpp_in_rust:>3d} 行 / "
          f"Java {java_in_rust:>3d} 行 / Go {go_in_rust:>3d} 行")
    print(f"    场景 {s_cpp:<18s} Rust {rust_in_cpp:>3d} 行 / C++ {cpp_in_cpp:>3d} 行")
    if rust_in_rust > 0 and cpp_in_rust == 0 and java_in_rust == 0 and go_in_rust == 0:
        print("  [PASS] 只跑 Rust 的场景没有染到任何 C++ / Java / Go 代码")
    else:
        print(f"  [FAIL] 只跑 Rust 的场景越界了：C++ {cpp_in_rust} 行，"
              f"Java {java_in_rust} 行，Go {go_in_rust} 行")
        ok = False
    if cpp_in_cpp > 0 and rust_in_cpp == 0:
        print("  [PASS] 只跑 C++ 的场景没有染到任何 Rust 代码")
    else:
        print(f"  [FAIL] 只跑 C++ 的场景却染到了 Rust 代码（{rust_in_cpp} 行）")
        ok = False

    # ---- 6. Rust 源码漂移同样要拒绝出增量报告 ----
    print("\n  >> 改动 Rust 源码，模拟「产物是旧的、源码已经改了」")
    target = ROOT / RUSTFILE
    original = target.read_bytes()
    try:
        target.write_bytes(original + b"\n// drift\n")
        status, body = http(f"{PLATFORM}/api/coverage/summary?mode=incremental&baseline=HEAD~1")
        if status == 409 and RUSTFILE in body.get("error", ""):
            print(f"  [PASS] 拒绝出增量报告（HTTP 409）并点名 Rust 文件：{body['error']}")
        else:
            print(f"  [FAIL] Rust 源码已漂移，平台却返回 {status}：{body}")
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
        print("  [PASS] Rust 源码恢复后增量报告自动恢复可用")
    else:
        print(f"  [FAIL] Rust 源码已恢复，平台仍拒绝出报告：{status}")
        ok = False

    # ---- 7. 同一个泛型被单态化成好几个实例：方法明细按源码合并 ----
    # pricing.rs 的 clamp_to 以 i64（手续费接口默认走这个）与 Cents（只有带 unit=cents 才走）两种类型实例化。
    # rustc 给每个实例各编一份机器码、llvm-cov 各给一条函数记录，文件级方法数（FNF）却按源码只算一个。
    # 逐条出方法明细的话：文件那一行写 2 个方法，钻进去列出 3 条 —— clamp_to 一条已调用、一条未调用，
    # Cents 那条的名字里还混着类型名。行也按源码合并：clamp_to 里跑过的行就是已覆盖，没有部分覆盖 ——
    # 与 C++ 模板的口径不同是有意的（openspec：rust-normalization）
    print("\n  >> 泛型单态化：手续费接口只走 i64 那个实例（不带 unit=cents，Cents 实例一次都不跑）")

    def verdict(passed, good, bad):
        nonlocal ok
        print(f"  [PASS] {good}" if passed else f"  [FAIL] {bad}")
        ok = ok and passed

    def consistent_rust_view():
        """文件级方法数取自 summary、方法明细取自逐个文件的 detail，是几次独立的请求 —— 中间若换了一轮采集
        （实例瞬时掉线、数据变了），父子比对就会报出一个根本不存在的不一致。前后两次 summary 的文件级方法数相同
        且实例全在线，夹在中间取的明细才算数。重试的是取数，不是断言（CLAUDE.md §三 坑 4）"""
        def counts(s):
            return {f["path"]: (f["coveredMethods"], f["missedMethods"]) for f in s["files"] if f["path"].endswith(".rs")}
        for attempt in range(1, 4):
            before = must(*http(f"{PLATFORM}/api/coverage/summary"), what="/api/coverage/summary")
            details = {p: detail(p) for p in counts(before)}
            after = must(*http(f"{PLATFORM}/api/coverage/summary"), what="/api/coverage/summary")
            # 本节只依赖 Rust 的数据：别的语言瞬时掉线不该把这一节拖成「判不了」
            offline = [i["endpoint"] for i in after["instances"]
                       if i["endpoint"].startswith("rust://") and i["status"] != "CONNECTED"]
            if counts(before) == counts(after) and not offline:
                return after, details
            print(f"     第 {attempt} 次取数不自洽：" + (f"掉线 {offline}" if offline else "两次 summary 之间文件级方法数变了"))
            time.sleep(3)
        return None, None

    status, body = http(f"{RUST}/api/order/fee?amount=30000")
    print(f"     GET {RUST}/api/order/fee?amount=30000 → {status} {json.dumps(body, ensure_ascii=False)}")
    # 先等这次调用的数据真的到了：i64 那条路的调用行变绿
    i64_call = "clamp_to(amount / 100, 50, 5000)"
    arrived = status == 200 and body.get("data") == 300 and wait_until(
        lambda: True if any(r["text"].strip() == i64_call and r["status"] == "COVERED"
                            for r in detail(PRICING).get("rows", [])) else None)
    s, details = consistent_rust_view() if arrived else (None, None)
    pf = next((x for x in s["files"] if x["path"] == PRICING), None) if s else None
    if not arrived:
        # 平台自己的说法（归一化失败时 probeStatus 是 ANALYZE_ERROR、lastError 里有工具原话）比「没变绿」有用得多
        s0 = http(f"{PLATFORM}/api/coverage/summary")[1]
        print(f"  [FAIL] 判不了：手续费接口应返回 300（30000 的 1%，落在 50 与 5000 之间），实际 {status} {body}；"
              f"或调用之后 {PRICING} 里「{i64_call}」一直没变绿。"
              f"平台此刻 probeStatus={s0.get('probeStatus')}，lastError={s0.get('lastError')}")
        ok = False
    elif pf is None:
        print("  [FAIL] 判不了：连取 3 次，summary 与方法明细都不是同一份数据（原因见上面每一次）")
        ok = False
    else:
        d = details[PRICING]
        rows = {r["text"].strip(): r for r in d["rows"]}
        methods = d.get("methods") or []
        names = [m["name"] for m in methods]
        sig = rows.get("pub fn clamp_to<T: PartialOrd>(value: T, lo: T, hi: T) -> T {")
        # 按首行号筛而不是按名字：没合并时 Cents 那条的名字是 clamp_to::Cents，按名字筛会看不见它
        at_sig = [m for m in methods if sig and m["firstLine"] == sig["line"]]
        verdict(len(at_sig) == 1 and at_sig[0]["name"] == "demo_service_rust::pricing::clamp_to"
                and at_sig[0]["coveredLines"] == 1 and at_sig[0]["missedLines"] == 0,
                f"clamp_to 签名那一行（L{sig['line'] if sig else '?'}）在方法明细里只有一条、已调用"
                f"（Cents 那个实例没跑，也没多出一条未调用）",
                f"clamp_to 签名那一行（L{sig['line'] if sig else '?'}）应恰好一条已调用的 "
                f"demo_service_rust::pricing::clamp_to，实际 {at_sig}；全部方法 {names}")
        verdict(not any("Cents" in n for n in names),
                "方法名里没有混进类型实参 Cents",
                f"方法名里混进了类型实参：{[n for n in names if 'Cents' in n]}")
        verdict((pf["coveredMethods"], pf["missedMethods"]) == (2, 0),
                "pricing.rs 的文件级方法数：已调用 2（clamp_to、fee）、未调用 0 —— 按源码计，不随实例数变",
                f"pricing.rs 的方法应为已调用 2、未调用 0，实际 {pf['coveredMethods']}/{pf['missedMethods']}")

        # 父子对得上：每个 Rust 文件的方法明细条数 = 文件级方法数，已调用条数 = 文件级已调用数
        mismatched = []
        for f in (x for x in s["files"] if x["path"].endswith(".rs")):
            ms = details[f["path"]].get("methods") or []
            called = sum(1 for m in ms if m["coveredLines"] > 0)
            if (len(ms), called) != (f["coveredMethods"] + f["missedMethods"], f["coveredMethods"]):
                mismatched.append(f"{f['path']} 明细 {len(ms)} 条（已调用 {called}），"
                                  f"文件级 {f['coveredMethods'] + f['missedMethods']} 个（已调用 {f['coveredMethods']}）")
        verdict(not mismatched,
                "每个 Rust 文件的方法明细条数都等于文件级方法数，已调用条数也相等",
                f"方法明细与文件级方法数对不上：{mismatched}")

        want = {"value": "COVERED", "return lo;": "MISSED", "return hi;": "MISSED",
                "return clamp_to(Cents(amount / 100), Cents(50), Cents(5000)).0;": "MISSED"}
        got = {t: (rows.get(t) or {}).get("status") for t in want}
        verdict(got == want,
                "行按源码合并：clamp_to 里跑过的 value 是已覆盖（不判部分覆盖），"
                "return lo / return hi 与只有 Cents 才走的那行未覆盖",
                f"pricing.rs 的行状态应为 {want}，实际 {got}")

    print("\n" + "-" * 78)
    print("  验收结论：" + ("全部通过" if ok else "存在失败项"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
