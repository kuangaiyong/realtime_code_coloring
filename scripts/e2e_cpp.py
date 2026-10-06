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
  6. 版本一致性校验覆盖 C++ —— C++ 源码相对产物漂移时，增量口径拒绝出报告并点名 C++ 文件；
  7. 源码正被改写（为空 / 只写了一半）时报告与源码完整时相同 —— 归一化不读源码；
  8. 编译单元的 .gcno 坏了（gcov 崩溃 / 读不出函数）时整轮 ANALYZE_ERROR 并点名，绝不静默少一个文件。

被测 C++ 服务的既有业务源码一行未改：探针是独立编译单元，靠全局对象的构造函数
（早于 main 执行）自动启动，业务代码不 include 也不调用它任何东西。
"""
import json
import shutil
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
GCNO_PID = "cpp-gcno-e2e"


def http(url, method="GET", body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else (b"" if method == "POST" else None)
    req = urllib.request.Request(url, method=method, data=data)
    if body is not None:
        req.add_header("Content-Type", "application/json")
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


# order.cpp 里的函数数量下界。取实测值而不是精确值：源码增删函数时不必跟着改，
# 但漏解析一半（见下面 1b 的说明）一定会跌破这条线
CPP_MIN_METHODS = 4


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

    # ---- 1b. 方法数是真解析出来的，且没漏 ----
    # 这条是<b>守卫断言</b>：C++ 的方法数来自 gcov 输出里的 function 行，靠一条正则捕获。
    # 那条正则一旦失配，方法数会静默变小 —— 而页面上看不出任何异样。
    #
    # <b>必须钉数量下界，只查「大于 0」是不够的</b>：这一点是实测撞出来的。
    # 给 gcov 加 -m（输出 demangled 名）时，名字里带了空格，而正则用的是 \S+：
    # Store::Store() 这种无参函数仍能匹配，(anonymous namespace)::isFinalState(Order const&)
    # 就匹配不上。结果是 5 个方法只解析出 1 个，页面显示「1/1」——
    # 比全丢（0/0）更隐蔽，因为那个比例看着完全正常。
    cpp_file = next((f for f in s["files"] if f["path"] == CPPFILE), None)
    if cpp_file is None:
        print(f"  [FAIL] summary 里没有 {CPPFILE}")
        ok = False
    elif cpp_file["coveredMethods"] is None:
        print("  [FAIL] C++ 的方法数是 null —— C++ 拿得到函数信息，不该标成「不提供」")
        ok = False
    elif cpp_file["coveredMethods"] + cpp_file["missedMethods"] < CPP_MIN_METHODS:
        total = cpp_file["coveredMethods"] + cpp_file["missedMethods"]
        print(f"  [FAIL] C++ 只解析出 {total} 个方法，少于 {CPP_MIN_METHODS} 个 —— "
              "gcov 的 function 行有一部分没被认出来")
        ok = False
    else:
        total = cpp_file["coveredMethods"] + cpp_file["missedMethods"]
        print(f"  [PASS] C++ 的方法数解析完整：{cpp_file['coveredMethods']}/{total}"
              f"（不少于 {CPP_MIN_METHODS}）")

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

    # ---- 7. 源码正被改写的那一刻，报告不能缺文件、也不能少行 ----
    # 平台读的是工作树里的源码；git pull、切分支、编辑器保存都会改写它（很多是先截断再写）。
    # 2026-10-01 发版前的全量验收撞上过：一轮采集恰好在 order.cpp 被截断的那一刻跑 gcov，
    # 报告里少了 order.cpp、平台不报任何错（gcov 的文本格式是照着源码逐行印的，源码为空就一行不印）。
    # 这里不靠时序去撞：把源码真的留在「为空 / 只写了一半」，再立即采集一轮，报告必须与源码完整时相同
    print("\n  >> 源码正被改写（为空 / 只写了一半）时立即采集一轮：报告必须与源码完整时相同")
    keys = ("coveredLines", "missedLines", "coveredBranches", "missedBranches", "coveredMethods", "missedMethods")

    def collect_now():
        s = must(*http(f"{PLATFORM}/api/coverage/collect", "POST"), what="/api/coverage/collect")
        f = next((x for x in s["files"] if x["path"] == CPPFILE), None)
        return None if f is None else {k: f.get(k) for k in keys}

    # 先让 order.cpp 有一些非零计数（上面的场景用例刚清过零）：比的不只是「文件在不在、几行」，
    # 还有已覆盖行与已执行分支的数目 —— 全是 0 的两份报告相等，证明不了计数没被源码状态带偏。
    # 查询与业务状态无关，可重复调用
    status, _ = http(f"{CPP}/api/order/query?bizNo=C1001")
    src_file = ROOT / CPPFILE
    src_bytes = src_file.read_bytes()
    whole = collect_now()
    if status != 200:
        print(f"  [FAIL] 判不了：查询接口返回 {status}，{CPPFILE} 没被调到，比不出计数有没有被带偏")
        ok = False
    elif whole is None:
        print(f"  [FAIL] 判不了：源码完整时报告里就没有 {CPPFILE}")
        ok = False
    elif whole["coveredLines"] == 0:
        print(f"  [FAIL] 判不了：调过查询接口之后 {CPPFILE} 仍是 0 行已覆盖，比不出计数有没有被带偏")
        ok = False
    else:
        try:
            for label, content in (("为空", b""), ("只写了一半", src_bytes[:300])):
                src_file.write_bytes(content)
                got = collect_now()
                if got != whole:
                    print(f"  [FAIL] 源码{label}时报告变了：{CPPFILE} "
                          f"{'整个不见了' if got is None else got}，源码完整时是 {whole}")
                    ok = False
                else:
                    print(f"  [PASS] 源码{label}时报告与源码完整时相同（{whole['coveredLines'] + whole['missedLines']} 行、"
                          f"分支 {whole['coveredBranches']}/{whole['coveredBranches'] + whole['missedBranches']}）")
        finally:
            src_file.write_bytes(src_bytes)

    # ---- 8. 编译单元的 .gcno 坏了：整轮拒绝出报告并点名，绝不静默少一个文件 ----
    # gcov 读到坏的 .gcno，有两种情形光看退出码管不住（实测 gcov 16.2，把真实的 order.gcno 逐字节截断）：
    # 截在文件头里会段错误、stderr 一个字都没有；截在第一个函数记录之前则退出 0、只说一句 no functions found，
    # 这个编译单元的源码就从报告里静默消失了。产物仓库收进来的包、正被重新构建的对象目录都可能是这样。
    # 在默认项目上做会把它整轮打成 ANALYZE_ERROR、记进采集事件，所以另建一个只采 C++ 两台的临时项目，
    # 对象目录指向一份副本，只弄坏副本里的 order.gcno
    print("\n  >> 编译单元的 .gcno 坏了：整轮 ANALYZE_ERROR 并点名是哪个，绝不静默少一个文件")
    dcfg = must(*http(f"{PLATFORM}/api/projects/default"), what="/api/projects/default")
    obj_dir = (ROOT / "platform" / dcfg["cppObjectsDir"]).resolve()  # 平台的工作目录是 platform/
    full = (obj_dir / "order.gcno").read_bytes()
    # 第一个函数记录：标记 GCOV_TAG_FUNCTION（小端 00 00 00 01）后面紧跟一个非零的长度字。
    # 从 16 字节的文件头之后找，且不限 4 字节对齐 —— GCC 12 起字符串不再补齐，记录可能落在任意偏移；
    # 旧版补齐用的零字节加上标志字的 01 也能拼出这四个字节，但那里后面跟着的是 0，靠长度字排除
    tag = next((i for i in range(16, len(full) - 8) if full[i:i + 4] == b"\x00\x00\x00\x01"
                and 0 < int.from_bytes(full[i + 4:i + 8], "little") < 0x10000), -1)
    cases = [("只剩文件头（gcov 段错误，stderr 一个字都没有）", full[:12], None)]
    if tag > 0:
        cases.insert(0, ("截在第一个函数记录之前（gcov 退出 0，只说一句 no functions found）",
                         full[:tag], "读不出任何函数"))
    else:
        print("  [FAIL] 判不了：order.gcno 里找不到第一个函数记录的标记 —— .gcno 的格式变了，截法要跟着改")
        ok = False
    scratch = ROOT / ".run" / "e2e-cpp-gcno"
    shutil.rmtree(scratch, ignore_errors=True)
    shutil.copytree(obj_dir, scratch)
    project = f"{PLATFORM}/api/projects/{GCNO_PID}"
    http(project, "DELETE")  # 上一次没删干净也不影响这一次
    status, body = http(f"{PLATFORM}/api/projects", "POST", {
        "id": GCNO_PID, "name": "C++ 坏 gcno 端到端", "repoDir": dcfg["repoDir"], "artifactSource": "local",
        "instances": [i for i in dcfg["instances"] if i.startswith("cpp://")],
        "cppSourceRoot": dcfg["cppSourceRoot"], "cppObjectsDir": str(scratch)})
    if status != 200:
        print(f"  [FAIL] 判不了：建临时项目返回 {status}：{body}")
        ok = False
    else:
        try:
            for label, content, reason in cases:
                (scratch / "order.gcno").write_bytes(content)
                http(f"{project}/collect", "POST")
                s = must(*http(f"{project}/coverage/summary"), what="临时项目的 summary")
                err = s.get("lastError") or ""
                if s.get("probeStatus") == "ANALYZE_ERROR" and "order.gcno" in err and (reason is None or reason in err):
                    print(f"  [PASS] {label}：拒绝出报告并点名 —— {err}")
                else:
                    print(f"  [FAIL] {label}：probeStatus={s.get('probeStatus')}，lastError={err or '—'}，"
                          f"报告里的文件 {[f['path'] for f in s.get('files') or []]}")
                    ok = False
            (scratch / "order.gcno").write_bytes(full)
            http(f"{project}/collect", "POST")
            s = must(*http(f"{project}/coverage/summary"), what="临时项目的 summary")
            if s.get("probeStatus") == "CONNECTED" and any(f["path"] == CPPFILE for f in s.get("files") or []):
                print("  [PASS] 对照：换回完好的 order.gcno 立即恢复 —— 上面的报错是 .gcno 坏了，不是临时项目本身配错了")
            else:
                print(f"  [FAIL] 对照：order.gcno 完好时仍出不来 {CPPFILE}：probeStatus={s.get('probeStatus')}，"
                      f"lastError={s.get('lastError')}")
                ok = False
        finally:
            status, _ = http(project, "DELETE")
            if status != 200:
                print(f"  [FAIL] 收尾没删掉临时项目 {GCNO_PID}（DELETE 回 {status}），平台会一直采下去")
                ok = False
            shutil.rmtree(scratch, ignore_errors=True)

    print("\n" + "-" * 78)
    print("  验收结论：" + ("全部通过" if ok else "存在失败项"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
