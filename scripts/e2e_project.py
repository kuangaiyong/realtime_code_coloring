"""
端到端验收：项目管理与多项目隔离。真实平台、真实探针、真实数据库，无 mock。

验证命题：
  1. 配置在页面上填完就生效 —— 新建项目当场能采到数、改配置当场换口径，
     全程不重启平台。这是把配置从 application.yml 搬进数据库的全部理由；
  2. 填错了要当场说清是哪一类错 —— 「你填错了」（400）、「现在不能做」（409）、
     「平台自己的依赖挂了」（503）三件事的处置完全不同，混成一个码就得靠猜；
  3. 场景进行中拒绝保存配置 —— 那个场景的计数器窗口是在旧配置下开的，
     跨配置定格出来的归因没有意义。与「场景进行中拒绝清零」是同一条原则；
  4. 两个项目互不串 —— 覆盖快照、场景、趋势各归各的。串了的表现是
     「数字莫名其妙」，界面上看不出是串台，只能靠用例守；
  5. 配置错了要明说，不能静默 —— classes-dir 指错目录只会让覆盖率偏低，
     这类错必须变成 ANALYZE_ERROR 加一句点名的原因。

本脚本用现有的 8 个被测实例拆成两个项目：Java+Go 一个，C++ +Rust 一个。
两个项目的实例集合不相交，因此「文件集合不相交」才是个真断言。

放在验收序列最后：它会跑场景（start 会清零计数器），跑在别的用例之前会洗掉
它们的覆盖数据。
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLATFORM = "http://localhost:18090"
P1 = "p-jvmgo"
P2 = "p-cpprust"


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


def http(url, method="GET", body=None):
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    elif method in ("POST", "PUT", "DELETE"):
        data = b""
    req = urllib.request.Request(url, method=method, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw}


def must(status, body, what):
    if status != 200:
        print(f"!! {what} 返回 {status}: {body}")
        sys.exit(1)
    return body


def langs_of(summary):
    """一份 summary 覆盖到哪几种语言。按文件后缀判定，与平台内部的判定路径无关"""
    seen = set()
    for f in summary.get("files", []):
        p = f["path"]
        if p.endswith(".java"):
            seen.add("java")
        elif p.endswith(".go"):
            seen.add("go")
        elif p.endswith(".cpp") or p.endswith(".h"):
            seen.add("cpp")
        elif p.endswith(".rs"):
            seen.add("rust")
    return seen


def paths_of(summary):
    return {f["path"] for f in summary.get("files", [])}


def cleanup():
    # 必须把本脚本建过的每一个项目都删掉，包括中途用完就删的那两个。
    # 漏一个的话，脚本中途失败退出后残留在库里，下一次跑到那一步会撞 409，
    # 报出来的却是「gate 为空时建项目返回 409」这种与被测功能无关的失败
    for pid in (P1, P2, "gate-null-test", "db-down-test"):
        http(f"{PLATFORM}/api/projects/{pid}", method="DELETE")


def main():
    print("=" * 78)
    print("端到端验收 —— 项目管理与多项目隔离")
    print("=" * 78)
    print()
    ok = True

    def check(cond, good, bad):
        nonlocal ok
        if cond:
            print(f"  [PASS] {good}")
        else:
            print(f"  [FAIL] {bad}")
            ok = False

    # 上一轮若中途失败会留下项目，先清干净，否则这一轮建同名项目必然 409
    cleanup()

    # 跑之前平台上有哪些项目。判据必须是「我建的都删干净了、别人的一个没动」，
    # 不能是「只剩 default」—— 这个平台存在的意义就是让人建项目，
    # 页面上手工建过一个，这条断言就假失败，而失败信息一个字都不指向真正的原因
    preexisting = sorted(p["id"] for p in
                         must(*http(f"{PLATFORM}/api/projects"), what="项目列表")["projects"])

    base = must(*http(f"{PLATFORM}/api/projects/default"), what="读取默认项目配置")
    all_instances = base["instances"]
    jvmgo = [i for i in all_instances if i.startswith("java://") or i.startswith("go://")]
    cpprust = [i for i in all_instances if i.startswith("cpp://") or i.startswith("rust://")]
    print(f"  默认项目的 {len(all_instances)} 个实例拆成两组：")
    print(f"    {P1}   {jvmgo}")
    print(f"    {P2}  {cpprust}")
    print()
    if not jvmgo or not cpprust:
        print("!! 默认项目里没有四种语言的实例，本用例的前提不成立")
        sys.exit(1)

    def make(pid, name, instances):
        cfg = dict(base)
        cfg["id"] = pid
        cfg["name"] = name
        cfg["instances"] = instances
        return cfg

    # ---------------------------------------------------------------- 配置校验
    print("  >> 填错的配置必须当场拒绝，并分得清是哪一类错")
    bad_id = make("Bad Id!", "非法标识", jvmgo)
    st, body = http(f"{PLATFORM}/api/projects", method="POST", body=bad_id)
    check(st == 400 and "项目标识" in str(body.get("error", "")),
          f"非法项目标识返回 400 并说明规则（{body.get('error')}）",
          f"非法项目标识返回 {st}: {body}")

    no_inst = make(P1, "没有实例", [])
    st, body = http(f"{PLATFORM}/api/projects", method="POST", body=no_inst)
    check(st == 400 and "被测实例" in str(body.get("error", "")),
          f"没配实例返回 400 并说明后果（{body.get('error')}）",
          f"没配实例返回 {st}: {body}")

    bad_ep = make(P1, "探针地址写错", ["java://localhost"])
    st, body = http(f"{PLATFORM}/api/projects", method="POST", body=bad_ep)
    check(st == 400 and "探针地址" in str(body.get("error", "")),
          f"探针地址写错返回 400 并给出格式（{body.get('error')}）",
          f"探针地址写错返回 {st}: {body}")

    st, body = http(f"{PLATFORM}/api/projects/default", method="DELETE")
    check(st == 409 and "默认项目" in str(body.get("error", "")),
          f"删默认项目被拒（409）：{body.get('error')}",
          f"删默认项目返回 {st}: {body}")

    # 回归：探针超时填 0 会被 HttpClient 拒绝（Invalid duration: PT0S）。
    # 这个错原先要到「造采集器」那一步才抛，而配置那时已经写进库了 ——
    # 此后每次启动都在同一处失败，整个平台连同默认项目和 CI 门禁一起开不了机。
    # 所以这里不只要求它被拒，还要求它一行都没留在库里
    zero_timeout = make("brick-test", "超时填零", jvmgo)
    zero_timeout["timeoutMs"] = 0
    st, body = http(f"{PLATFORM}/api/projects", method="POST", body=zero_timeout)
    check(st == 400 and "超时" in str(body.get("error", "")),
          f"探针超时填 0 被拒（400）：{body.get('error')}",
          f"探针超时填 0 返回 {st}: {body}")
    st, _ = http(f"{PLATFORM}/api/projects/brick-test")
    check(st == 404, "被拒的配置没有留在库里（留下的话平台下次就起不来了）",
          f"被拒的配置竟然留下了：HTTP {st}")

    # 请求体里显式写 gate:null 时 Jackson 会把默认值抹掉，之后门禁接口必 NPE ——
    # CI 拿到的是 500，而按本项目的约定「判不了」只应该是 409
    no_gate = make("gate-null-test", "门禁为空", jvmgo)
    no_gate["gate"] = None
    st, _ = http(f"{PLATFORM}/api/projects", method="POST", body=no_gate)
    if st == 200:
        gst, _ = http(f"{PLATFORM}/api/projects/gate-null-test/coverage/gate?mode=full")
        check(gst in (200, 409), f"gate 为空时补了默认值，门禁接口正常（HTTP {gst}）",
              f"gate 为空导致门禁接口 {gst}（应为 200 或 409，绝不该是 500）")
        http(f"{PLATFORM}/api/projects/gate-null-test", method="DELETE")
    else:
        check(False, "", f"gate 为空时建项目返回 {st}")
    print()

    # ------------------------------------------------------ 新建项目当场可采数
    print("  >> 新建两个项目，不重启平台，当场采数")
    must(*http(f"{PLATFORM}/api/projects", method="POST", body=make(P1, "Java 与 Go", jvmgo)),
         what=f"新建 {P1}")
    must(*http(f"{PLATFORM}/api/projects", method="POST", body=make(P2, "C++ 与 Rust", cpprust)),
         what=f"新建 {P2}")

    st, body = http(f"{PLATFORM}/api/projects", method="POST", body=make(P1, "重名", jvmgo))
    check(st == 409 and "已存在" in str(body.get("error", "")),
          f"建同名项目被拒（409）：{body.get('error')}",
          f"建同名项目返回 {st}: {body}")

    s1 = must(*http(f"{PLATFORM}/api/projects/{P1}/collect", method="POST"), what=f"{P1} 采集")
    s2 = must(*http(f"{PLATFORM}/api/projects/{P2}/collect", method="POST"), what=f"{P2} 采集")
    check(s1["probeStatus"] == "CONNECTED" and s1["files"],
          f"{P1} 建好即采到数据：{len(s1['files'])} 个文件，整体 {s1['overallRatio']}%",
          f"{P1} 采不到数据：probeStatus={s1['probeStatus']} lastError={s1.get('lastError')}")
    check(s2["probeStatus"] == "CONNECTED" and s2["files"],
          f"{P2} 建好即采到数据：{len(s2['files'])} 个文件，整体 {s2['overallRatio']}%",
          f"{P2} 采不到数据：probeStatus={s2['probeStatus']} lastError={s2.get('lastError')}")
    print()

    # ---------------------------------------------------------------- 多项目隔离
    print("  >> 两个项目的覆盖数据互不越界")
    l1, l2 = langs_of(s1), langs_of(s2)
    check(l1 == {"java", "go"}, f"{P1} 只看得到 Java 与 Go：{sorted(l1)}",
          f"{P1} 看到了不属于它的语言：{sorted(l1)}")
    check(l2 == {"cpp", "rust"}, f"{P2} 只看得到 C++ 与 Rust：{sorted(l2)}",
          f"{P2} 看到了不属于它的语言：{sorted(l2)}")
    overlap = paths_of(s1) & paths_of(s2)
    check(not overlap, "两个项目的文件集合完全不相交",
          f"两个项目看到了同一批文件（串台）：{sorted(overlap)}")

    d = must(*http(f"{PLATFORM}/api/coverage/summary"), what="默认项目 summary")
    check(langs_of(d) == {"java", "go", "cpp", "rust"},
          f"默认项目不受影响，四种语言 {len(d['files'])} 个文件都在",
          f"默认项目的数据被新项目动了：{sorted(langs_of(d))}")
    # 光断言「采到了文件」太松：漏掉一个文件同样是「采到了」。
    # 拆出去的两个项目合起来必须与默认项目一字不差，才说明新项目的归一化没缺斤少两
    union = paths_of(s1) | paths_of(s2)
    check(union == paths_of(d),
          f"两个项目的文件并集与默认项目完全一致（{len(union)} 个）",
          f"拆开之后文件对不上，少了 {sorted(paths_of(d) - union)}，多了 {sorted(union - paths_of(d))}")
    print()

    # ------------------------------------------------------------ 场景互不串台
    print("  >> 场景归因按项目各算各的")
    must(*http(f"{PLATFORM}/api/projects/{P1}/scenario/start?scenarioId=proj-iso-1", method="POST"),
         what=f"{P1} 开始场景")
    a1 = must(*http(f"{PLATFORM}/api/projects/{P1}/scenario"), what=f"{P1} 场景列表")
    a2 = must(*http(f"{PLATFORM}/api/projects/{P2}/scenario"), what=f"{P2} 场景列表")
    ad = must(*http(f"{PLATFORM}/api/scenario"), what="默认项目场景列表")
    check(a1["active"] == "proj-iso-1", f"{P1} 的活跃场景是 proj-iso-1",
          f"{P1} 的活跃场景为 {a1['active']}")
    check(a2["active"] is None, f"{P2} 没有被带进场景（active 为 null）",
          f"{P2} 被串进了场景：active={a2['active']}")
    check(ad["active"] is None, "默认项目没有被带进场景（active 为 null）",
          f"默认项目被串进了场景：active={ad['active']}")

    # ---------------------------------------- 场景进行中拒绝保存配置（核心功能 #13）
    st, body = http(f"{PLATFORM}/api/projects/{P1}", method="PUT",
                    body=make(P1, "改名试试", jvmgo))
    check(st == 409 and "场景" in str(body.get("error", "")),
          f"场景进行中保存配置被拒（409）：{body.get('error')}",
          f"场景进行中保存配置返回 {st}: {body}")

    must(*http(f"{PLATFORM}/api/projects/{P1}/scenario/stop", method="POST"), what=f"{P1} 结束场景")
    print()

    # -------------------------------------------------------------- 改配置即生效
    print("  >> 改配置立即生效，不重启平台")
    only_java = [i for i in jvmgo if i.startswith("java://")]
    must(*http(f"{PLATFORM}/api/projects/{P1}", method="PUT",
               body=make(P1, "只剩 Java", only_java)),
         what=f"{P1} 改配置")
    after = must(*http(f"{PLATFORM}/api/projects/{P1}/collect", method="POST"), what=f"{P1} 改后采集")
    check(langs_of(after) == {"java"},
          f"{P1} 去掉 Go 实例后当场只剩 Java：{sorted(langs_of(after))}",
          f"{P1} 改配置没生效，仍看到 {sorted(langs_of(after))}")

    got = must(*http(f"{PLATFORM}/api/projects/{P1}"), what=f"{P1} 读回配置")
    check(got["instances"] == only_java and got["name"] == "只剩 Java",
          "读回的配置就是刚存的那份（已落库）",
          f"读回的配置对不上：{got['instances']} / {got['name']}")

    # 归档的场景不能因为改配置就消失 —— 那是测试过程的记录，与配置改没改无关
    kept = must(*http(f"{PLATFORM}/api/projects/{P1}/scenario"), what=f"{P1} 改配置后的场景列表")
    check(any(s["scenarioId"] == "proj-iso-1" for s in kept["scenarios"]),
          "改配置后已归档的场景仍在（热替换时交接过去了）",
          f"改配置把归档场景弄丢了：{kept['scenarios']}")
    print()

    # ------------------------------------------------------------ 自检逐项报告
    print("  >> 自检：拿配置去碰真实环境，逐项回答能不能跑起来")
    chk = must(*http(f"{PLATFORM}/api/projects/{P1}/check", method="POST"), what=f"{P1} 自检")
    bad_items = [i for i in chk["items"] if not i["ok"]]
    check(chk["ok"], f"配置正确时自检全过，共 {len(chk['items'])} 项",
          f"自检有不过的项：{[(i['name'], i['detail']) for i in bad_items]}")

    broken = make(P1, "产物目录指错", only_java)
    broken["classesDir"] = "../no-such-classes-dir"
    chk2 = must(*http(f"{PLATFORM}/api/projects/check", method="POST", body=broken),
                what="对未保存的配置自检")
    item = next((i for i in chk2["items"] if i["name"] == "classesDir"), None)
    check(chk2["ok"] is False and item is not None and item["ok"] is False,
          f"产物目录指错时自检点名了它：{item['detail'] if item else '（没有这一项）'}",
          f"产物目录指错却没被自检发现：{chk2}")
    print()

    # ------------------------------------------------ 配置错了要明说，不能静默
    print("  >> 产物目录指错时必须明确报错，而不是安静地给一个偏低的覆盖率")
    must(*http(f"{PLATFORM}/api/projects/{P1}", method="PUT", body=broken), what=f"{P1} 存入错误配置")
    bad_sum = must(*http(f"{PLATFORM}/api/projects/{P1}/collect", method="POST"),
                   what=f"{P1} 用错误配置采集")
    check(bad_sum["probeStatus"] == "ANALYZE_ERROR" and "classes-dir" in str(bad_sum.get("lastError")),
          f"探针连得上但产物目录不对时报 ANALYZE_ERROR 并点名：{bad_sum.get('lastError')}",
          f"产物目录不对却没报错：probeStatus={bad_sum['probeStatus']} lastError={bad_sum.get('lastError')}")
    print()

    # ---------------------------------------------------------------- 趋势隔离
    print("  >> 跨构建趋势按项目分开存")
    t1 = must(*http(f"{PLATFORM}/api/projects/{P2}/coverage/trend"), what=f"{P2} 趋势")
    td = must(*http(f"{PLATFORM}/api/coverage/trend"), what="默认项目趋势")
    if not t1["available"] or not td["available"]:
        print(f"  [跳过] 历史库不可用（{t1.get('error') or td.get('error')}），趋势隔离这一项无法验证")
    else:
        b2 = t1["builds"][-1] if t1["builds"] else None
        bd = td["builds"][-1] if td["builds"] else None
        check(b2 is not None and bd is not None,
              "两个项目各自都有趋势记录",
              f"趋势记录缺失：{P2}={b2} default={bd}")
        if b2 and bd:
            # 同一个 commit 下，两个项目盯的实例不同、覆盖行数必然不同。
            # 主键里没有 project_id 的话它们会写进同一行互相覆盖，这里就会读到同一个数字
            check(b2["buildCommit"] == bd["buildCommit"] and b2["coveredLines"] != bd["coveredLines"],
                  f"同一 commit 下两个项目的记录各自独立（{P2} {b2['coveredLines']} 行 / "
                  f"default {bd['coveredLines']} 行）",
                  f"两个项目的趋势记录串了：{P2}={b2} default={bd}")
    print()

    # ---------------------------------------------------------------- 删除项目
    print("  >> 删除项目")
    must(*http(f"{PLATFORM}/api/projects/{P1}", method="DELETE"), what=f"删除 {P1}")
    must(*http(f"{PLATFORM}/api/projects/{P2}", method="DELETE"), what=f"删除 {P2}")
    st, body = http(f"{PLATFORM}/api/projects/{P1}")
    check(st == 404, f"删掉之后再查返回 404：{body.get('error')}", f"删掉的项目还在：{st} {body}")

    lst = must(*http(f"{PLATFORM}/api/projects"), what="项目列表")
    ids = sorted(p["id"] for p in lst["projects"])
    check(ids == preexisting,
          f"两个项目都删干净了，平台上原有的 {len(preexisting)} 个项目一个没动：{ids}",
          f"删完之后项目列表是 {ids}，跑之前是 {preexisting}")

    final = must(*http(f"{PLATFORM}/api/coverage/summary"), what="默认项目 summary")
    check(final["probeStatus"] == "CONNECTED" and langs_of(final) == {"java", "go", "cpp", "rust"},
          f"默认项目与旧接口全程未受影响（{len(final['files'])} 个文件，"
          f"整体 {final['overallRatio']}%）",
          f"默认项目被搞坏了：probeStatus={final['probeStatus']} {sorted(langs_of(final))}")
    print()

    # ------------------------------------------------------------ 数据库不可用
    # 配置搬进数据库之后，数据库就成了配置的来源。它一挂，采集、染色、门禁
    # 不能跟着一起挂 —— 配置是核心能力的前提，不该被附加设施拖死。
    # 用「指向没人监听的端口」来构造，比停掉真数据库安全：后者会波及机器上别的服务
    print("  >> 数据库不可用时：核心能力照常，只有保存配置明确失败")
    try:
        run_local("platform-dbdown")
        time.sleep(6)
        down = must(*http(f"{PLATFORM}/api/coverage/summary"), what="库挂时 summary")
        check(down["probeStatus"] == "CONNECTED" and down["files"],
              f"库挂了照样采集（{len(down['files'])} 个文件，整体 {down['overallRatio']}%）",
              f"库一挂采集就停了：probeStatus={down['probeStatus']} lastError={down.get('lastError')}")

        st, _ = http(f"{PLATFORM}/api/coverage/gate?mode=full")
        check(st == 200, "库挂了门禁照常判（200）", f"库挂了门禁跟着挂：HTTP {st}")

        st, body = http(f"{PLATFORM}/api/projects", method="POST",
                        body=make("db-down-test", "库挂时新建", jvmgo))
        check(st == 503 and "数据库" in str(body.get("error", "")),
              f"保存配置明确失败（503）而不是假装存上了：{body.get('error')}",
              f"库挂时保存配置返回 {st}: {body}")

        lst = must(*http(f"{PLATFORM}/api/projects"), what="库挂时项目列表")
        ids = [p["id"] for p in lst["projects"]]
        check(ids == ["default"],
              "存失败后没有留下半个项目（内存里也不该有）",
              f"存失败却留下了项目：{ids}")

        # 库挂时删除也不能崩成 500。
        # 注意这里验不到「删非默认项目 → 503」那条路：库一挂，装载会退回 yml 种子，
        # 内存里只剩 default，而删 default 先被 409 拦住。那条路要等库在运行期挂掉
        # 才走得到，本地没法在不影响别的服务的前提下制造。代码已按同一套包装处理
        st, body = http(f"{PLATFORM}/api/projects/no-such-project", method="DELETE")
        check(st == 404, f"库挂时删不存在的项目仍是 404 而非 500：{body.get('error')}",
              f"库挂时删不存在的项目返回 {st}: {body}")
        st, body = http(f"{PLATFORM}/api/projects/default", method="DELETE")
        check(st == 409, f"库挂时删默认项目仍先被 409 拦住：{body.get('error')}",
              f"库挂时删默认项目返回 {st}: {body}")

        # 回归用例：库挂时改配置（PUT）不能把项目变成砖。
        # 原先的顺序是「先作废旧运行时 → 再写库」，写库失败时旧的已经被作废却仍留在
        # 注册表里，于是这个项目从此不采集、不推送（页面上只是「数字不动了」），
        # 而清零 / 开场景一律回 409「配置刚刚更新，请重试」—— 把人指向完全相反的方向。
        # 库挂只验 POST 是不够的：POST 的顺序本来就是对的（先写库再入册）
        cur = must(*http(f"{PLATFORM}/api/projects/default"), what="库挂时读默认项目")
        st, body = http(f"{PLATFORM}/api/projects/default", method="PUT", body=cur)
        check(st == 503 and "数据库" in str(body.get("error", "")),
              f"库挂时改配置明确失败（503）：{body.get('error')}",
              f"库挂时改配置返回 {st}: {body}")

        # 关键的一半：存失败之后，这个项目必须还活着
        time.sleep(4)
        after = must(*http(f"{PLATFORM}/api/coverage/summary"), what="改配置失败后 summary")
        check(after["probeStatus"] == "CONNECTED" and after["files"],
              f"改配置失败后项目照常采集（{len(after['files'])} 个文件）",
              f"改配置失败把项目变砖了：probeStatus={after['probeStatus']}")
        st, body = http(f"{PLATFORM}/api/coverage/reset", method="POST")
        check(st == 200,
              "改配置失败后清零仍然可用（没有被误报成「配置刚刚更新」）",
              f"改配置失败后清零返回 {st}: {body.get('error')}")

        trend = must(*http(f"{PLATFORM}/api/coverage/trend"), what="库挂时趋势")
        check(trend["available"] is False and trend.get("error"),
              f"趋势明确不可用并给出原因：{trend.get('error')}",
              f"库挂了趋势却说可用：{trend}")
    finally:
        # 无论上面成败都要把平台还回真实数据库，否则后续跑什么都不对
        run_local("platform-restart")
        time.sleep(6)
    restored = must(*http(f"{PLATFORM}/api/coverage/trend"), what="恢复后趋势")
    check(restored["available"] is True, "平台已还原到真实数据库，趋势恢复可用",
          f"平台没能还原：{restored.get('error')}")

    print()
    print("  验收结论：" + ("全部通过" if ok else "存在失败项"))
    if not ok:
        cleanup()
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
