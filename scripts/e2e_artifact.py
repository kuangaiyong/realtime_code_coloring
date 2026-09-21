"""端到端验收：uploaded 模式（产物按 buildId 从产物仓库取）真能解出行号。

这是整个容器化方案唯一的硬证据。此前几步各自只证明了「组件没错」：
上传接口存得下、产物仓库取得出、配置校验拦得住 —— 都没有回答那个真正的问题：
**按 buildId 取回来的产物，解出的行号到底对不对。**

验证命题：
  1. uploaded 模式解出的覆盖与 local 模式<b>逐行一致</b> —— 逐个文件比对
     (行号, 状态, 分支数) 的集合。行号差一位、分支数差一个都会被抓住；
  2. 用的确实是上传的那份，不是本地路径 —— 项目的 classesDir / cppObjectsDir /
     rustBinary 三条<b>全部填成不存在的路径</b>。走 local 的话三种语言一个都出不来数；
  3. 索引键真的是 buildId —— 仓库里同时放一份<b>诱饵构建</b>（另一个 sha、内容是垃圾）。
     实现若是「取仓库里现有的那一份」而不是「按实例自报的版本取」，诱饵就会被用上；
  4. C++ 与 Rust 的归一化器真的换了配置 —— 它们自己从 ProjectConfig 读产物路径、
     analyze 没有接收路径的入参，所以平台必须用解析出来的配置重造它们。
     少做这一步，这两种语言会去读上面那些不存在的路径而报错；
  5. 取不到产物一律拒绝出报告并点名 —— 删掉产物后必须是 ANALYZE_ERROR，
     且错误里带着那个 buildId。跳过那门语言的话，界面上表现为
     「这些代码没被调用过」，与真相完全相反，而且看不出是缺产物。

Go 不在产物范围内：它的覆盖数据（meta + counters）全从探针的网络接口来，
是自包含的，平台不需要它的任何编译产物。但它仍在这个项目的实例列表里 ——
「一个项目里有的语言要产物、有的不要」正是最容易写错的那种组合。

**本机没有 Docker，所以「平台够不着容器文件系统」这个前提本身验不了**（见 spec §7.2）。
这里验的是它的全部技术内容：产物经 HTTP 推上来、按 buildId 索引、取回去解出正确行号。
容器与裸机的差别只剩产物怎么到平台这台机器上，而那一段正是上传接口。

**本用例是全仓第一个让平台同时带两个活项目采集的用例。** 于是它比别的用例更容易撞上
「某台实例瞬时取不到数」——JaCoCo tcpserver 的 accept backlog 只有 1，两个项目交替 dump
同一批端口时被 RST 的概率明显变高。因此凡是要比对覆盖数据的地方都先把前提做实：
取不到一轮全连上的快照就重试，重试完仍不齐就报「判不了」并点名是哪台，
绝不拿一份缺了实例的数据去比 —— 那会报出一个根本不存在的行号错位。
"""
import io
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLATFORM = "http://localhost:18090"
PID = "artifact-e2e"
# 诱饵：一个不是任何实例自报的构建。内容是垃圾，被取用就一定出错
DECOY = "0123456789abcdef0123456789abcdef01234567"

ok = True


def http(url, method="GET", body=None, ctype="application/json"):
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
    elif method == "POST":
        data = b""
    req = urllib.request.Request(url, method=method, data=data)
    if body is not None:
        req.add_header("Content-Type", ctype)
    try:
        # 超时按平台最坏情况取，与其余 E2E 一致：一次采集要挨个 dump 8 个实例，
        # 探针挂掉时每个耗尽 timeout-ms，再叠加各语言的外部工具
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.load(e)
        except Exception:
            return e.code, {}


def check(cond, msg):
    global ok
    print(("  [PASS] " if cond else "  [FAIL] ") + msg)
    if not cond:
        ok = False
    return cond


def zip_tree(root_dir):
    """把一棵目录打成 zip，条目名是相对 root_dir 的路径"""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for dirpath, _dirs, names in os.walk(root_dir):
            for n in names:
                full = os.path.join(dirpath, n)
                z.write(full, os.path.relpath(full, root_dir))
    return buf.getvalue()


def zip_files(pairs):
    """pairs: [(磁盘路径, zip 内的条目名)]"""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for full, name in pairs:
            z.write(full, name)
    return buf.getvalue()


def zip_bytes(name, content):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(name, content)
    return buf.getvalue()


def upload(build, kind, blob):
    """multipart/form-data，字段名 file。project 在这个接口上是查询参数，不是路径段"""
    boundary = "----rtccArtifactE2E"
    head = (
        "--" + boundary + "\r\n"
        'Content-Disposition: form-data; name="file"; filename="' + kind + '.zip"\r\n'
        "Content-Type: application/zip\r\n\r\n"
    ).encode("utf-8")
    tail = ("\r\n--" + boundary + "--\r\n").encode("utf-8")
    url = f"{PLATFORM}/api/artifacts/{build}?project={PID}&lang={kind}"
    return http(url, "POST", head + blob + tail,
                "multipart/form-data; boundary=" + boundary)


def summary(project):
    return http(f"{PLATFORM}/api/projects/{project}/coverage/summary")


def detail(project, path):
    url = (f"{PLATFORM}/api/projects/{project}/coverage/file"
           f"?path={urllib.parse.quote(path)}")
    return http(url)


def collect(project):
    return http(f"{PLATFORM}/api/projects/{project}/collect", "POST")


def drop_artifact(build):
    """删一个构建的产物。

    Windows 上删不掉正被占用的文件：平台可能正拿着解压出来的 class / rust 产物做归一化
    （jacoco 读 .class、llvm-cov 开着产物），此时 remove 删不干净会抛错回 500。
    这是瞬时的，重试即可 —— 与 ArtifactStore.moveAtomic 重试的是同一类占用。
    """
    status, body = 0, {}
    for i in range(4):
        status, body = http(f"{PLATFORM}/api/artifacts/{build}?project={PID}", "DELETE")
        if status == 200:
            return status, body
        time.sleep(0.5 * (i + 1))
    return status, body


def connected(project, tries=4):
    """采一轮，并要求<b>全部实例都连上</b>，否则重试取数。

    为什么必须这样：文件集合由产物决定，与哪台实例连上无关 —— 一台实例瞬时取不到数
    不会改变文件集合，只会让它跑过的那些行变红。拿这种快照去逐行比对，
    报出来的会是「行号对不上」，把人引向一个根本不存在的产物 bug。
    重试的是<b>取数</b>，不是断言本身；重试完仍不齐就返回 None，由调用方报「判不了」。
    """
    s = {}
    for attempt in range(1, tries + 1):
        collect(project)
        status, s = summary(project)
        if status == 200 and s.get("probeStatus") == "CONNECTED":
            return s
        bad = [i["endpoint"] for i in (s.get("instances") or [])
               if i.get("status") != "CONNECTED"]
        print(f"         第 {attempt} 次取数：{project} 的 probeStatus="
              f"{s.get('probeStatus')}，没连上的 {bad or '—'}；"
              f"{s.get('lastError') or ''}"[:170])
        if attempt < tries:
            time.sleep(3)
    return None


def cleanup(build):
    """删项目与产物，使用例可重复跑。

    删不掉项目要说出来：残留的活项目会让后续每一条用例都在双项目负载下跑，
    而 ui_verify 断言的是端到端 ≤5s —— 那条会莫名其妙变红，且看不出与这里有关。
    """
    status, _ = http(f"{PLATFORM}/api/projects/{PID}", "DELETE")
    for b in (build, DECOY):
        if b:
            drop_artifact(b)
    return status


def rowkey(rows):
    """逐行比对的比较键：行号 + 状态 + 分支数。

    不比 text —— 两边读的是同一个源文件，比它只是在验文件系统。
    分支数必须比：产物对不上时 Java 的 PARTIAL 判定会跟着错，而只看三态看不出来。
    """
    return {(r["line"], r["status"], r.get("coveredBranches"), r.get("missedBranches"))
            for r in rows}


def main():
    print("=" * 78)
    print("产物仓库端到端验收：uploaded 模式解出的行号与 local 逐行一致")
    print("=" * 78)

    # ---- 前提：拿到一个干净的、全实例一致的构建版本 ----
    #
    # 取的是<b>平台从实例那里收到的版本</b>，而不是这里跑一次 git —— 那才是真相：
    # 运行中的进程加载的是它启动那一刻的字节码，磁盘上的 HEAD 可能早就往前走了。
    # 自己跑 git status 还有个实际问题：这个脚本文件本身就会让工作树变脏，
    # 于是用例把自己判成「判不了」；而 run_local.sh 判 -dirty 只看四个被测源码根。
    #
    # 版本拿不到 / 不一致 / 是脏的，这条用例都是「判不了」而不是「判不过」——
    # 产物仓库按设计拒绝脏构建（同一个 commit 能对应无数份不同的产物），
    # 两者的下一步动作完全不同
    status, base = summary("default")
    build = base.get("buildCommit")
    verr = base.get("versionError")
    bad = [i["endpoint"] for i in base.get("instances") or [] if i.get("dirty")]
    if status != 200 or not build or verr or bad:
        print("\n  [FAIL] 拿不到一个干净且各实例一致的构建版本，无法验证（不是功能坏了）：")
        print(f"         buildCommit={build} versionError={verr}")
        if bad:
            print(f"         自报脏构建的实例：{bad}")
            print("         被测源码有未提交的改动，先提交再跑")
        print("\n" + "-" * 78)
        print("  验收结论：存在失败项")
        sys.exit(1)
    print(f"\n被测实例自报的构建：{build}")

    # 实例列表照搬默认项目，不在这里写死：逐行比对要求两边采的是同一批计数器，
    # 而配置的权威来源是库里那一份。写死一份的话，两者一旦不同，
    # 破坏方式恰好落在行状态而不是文件集合上 —— 表现成一个不存在的行号错位
    status, dcfg = http(f"{PLATFORM}/api/projects/default")
    instances = dcfg.get("instances") or []
    if status != 200 or not instances:
        print(f"\n  [FAIL] 读不到默认项目的实例列表（{status}），无法比对")
        print("\n" + "-" * 78)
        print("  验收结论：存在失败项")
        sys.exit(1)
    print(f"默认项目的实例：{len(instances)} 个")

    cleanup(build)   # 上一次没删干净也不影响这一次

    try:
        run(build, instances)
    finally:
        left = cleanup(build)
        check(left == 200,
              f"收尾把临时项目删掉了（残留的话后续用例都会在双项目负载下跑）：DELETE 回 {left}")

    print("\n" + "-" * 78)
    print("  验收结论：" + ("全部通过" if ok else "存在失败项"))
    sys.exit(0 if ok else 1)


def run(build, instances):
    # ---- 1. 建一个 uploaded 项目，三条本地产物路径全指向不存在的位置 ----
    print("\n>> 1. 建 uploaded 项目，三条本地产物路径全填不存在的")
    cfg = {
        "id": PID,
        "name": "产物仓库端到端",
        "instances": instances,
        "artifactSource": "uploaded",
        "repoDir": "..",
        "baseline": "HEAD~1",
        # 这三条是这条用例的全部鉴别力所在：走 local 的话三种语言一个都出不来数
        "classesDir": "../NOPE-classes-must-not-exist",
        "cppObjectsDir": "../NOPE-cpp-obj-must-not-exist",
        "rustBinary": "../NOPE/must-not-exist.exe",
        # 源码根是真的 —— 源码来自 git 工作树，本来就不跟着产物走
        "javaSourceRoot": "demo-service/src/main/java",
        "goSourceRoot": "demo-service-go",
        "goModulePath": "github.com/kuangaiyong/realtime_code_coloring/demo-service-go",
        "cppSourceRoot": "demo-service-cpp",
        "rustSourceRoot": "demo-service-rust",
    }
    status, body = http(f"{PLATFORM}/api/projects", "POST", cfg)
    if not check(status == 200, f"建项目返回 {status}"):
        print(f"         {body}")
        return

    # ---- 2. 还没传产物：必须拒绝出报告并点名 ----
    print("\n>> 2. 还没传产物时必须拒绝出报告并点名缺哪个构建的哪种产物")
    collect(PID)
    status, s = summary(PID)
    err = s.get("lastError") or ""
    check(s.get("probeStatus") == "ANALYZE_ERROR",
          f"probeStatus = {s.get('probeStatus')}（要的是 ANALYZE_ERROR，不是 CONNECTED）")
    # 这两条同时把「local 模式下 classes-dir 无效」那种同样报 ANALYZE_ERROR 的情形排除掉：
    # 那条错误里既没有 buildId，也没有补救命令
    check(build in err, "错误里点名了 buildId")
    check(f"project={PID}" in err,
          f"补救命令带上了 ?project={PID}（不带的话包会传进 default 项目还回 200）")
    check(not s.get("files"), "没有凭空出一份文件列表")
    print(f"         {err[:150]}")

    # ---- 3. 传三种语言的真实产物，外加一份诱饵 ----
    print("\n>> 3. 上传三种语言的真实产物（Go 不需要产物，不传），外加一个诱饵构建")
    java_zip = zip_tree(ROOT / "demo-service" / "target" / "classes")
    gcno_dir = ROOT / ".run" / "cpp-obj"
    cpp_zip = zip_files([(str(p), p.name) for p in sorted(gcno_dir.glob("*.gcno"))])
    rust_exe = ROOT / "demo-service-rust" / "target" / "x86_64-pc-windows-msvc" / "release" \
        / "demo-service-rust.exe"
    # 刻意放进子目录：CI 用 `zip -r target/release/xxx` 打出来的就是这个形状，
    # 只看顶层的话会报「一个文件都没有」，而磁盘上明明有
    rust_zip = zip_files([(str(rust_exe), "release/" + rust_exe.name)])

    for kind, blob in (("java", java_zip), ("cpp", cpp_zip), ("rust", rust_zip)):
        status, body = upload(build, kind, blob)
        if not check(status == 200, f"上传 {kind} 产物（{len(blob)} 字节）返回 {status}"):
            print(f"         {body}")

    # 诱饵：同一个项目下的另一个构建，内容是垃圾。
    # 仓库里只有一份产物的话，「按 buildId 精确取」与「取现有的那一份」长得一模一样
    status, _ = upload(DECOY, "java", zip_bytes("Decoy.class", b"not a class file"))
    check(status == 200, f"上传诱饵构建 {DECOY[:8]} 的 java 产物返回 {status}")

    # ---- 4. 三种语言都要出数 ----
    print("\n>> 4. 采一轮：三种语言都出数（本地路径全是假的，出得来就只能是走了产物仓库）")
    up = connected(PID)
    # 文案刻意中性：取不到可能是实例瞬时掉线（判不了），也可能是产物根本取不到（判不过），
    # 两者的下一步动作完全不同。上面每次重试都打了 probeStatus、没连上的是哪台、
    # 以及平台给的原因 —— 由那几行说清是哪一种，这里不替它下结论
    if not check(up is not None,
                 "取到一轮可用的快照（取不到的原因见上面每次重试打出的那几行）"):
        return
    langs = {}
    for f in up.get("files") or []:
        for prefix, lang in (("demo-service-cpp/", "cpp"), ("demo-service-rust/", "rust"),
                             ("demo-service-go/", "go"), ("demo-service/", "java")):
            if f["path"].startswith(prefix):
                langs[lang] = langs.get(lang, 0) + 1
                break
    print(f"         文件数 {len(up.get('files') or [])}，按语言 {langs}")
    for lang in ("java", "cpp", "rust"):
        check(langs.get(lang, 0) > 0,
              f"{lang} 出了 {langs.get(lang, 0)} 个文件 —— 它的归一化器确实换了配置")
    check(langs.get("go", 0) > 0, "Go 照常出数（它不需要产物，不该被这套机制连累）")
    # 诱饵就躺在同一个项目的仓库里。取用了它的话 JaCoCo 解不出 demo-service 的类，
    # 上面那条 java 断言会当场塌掉 —— 走到这里就说明索引键真的是实例自报的 buildId
    check(langs.get("java", 0) > 0,
          f"诱饵构建 {DECOY[:8]} 没被取用（索引键确实是实例自报的 buildId）")

    # ---- 5. 逐行比对：这条用例的全部意义 ----
    print("\n>> 5. 与 local 模式逐行比对（行号 + 状态 + 分支数，集合相等）")
    # 前提要做实两层：两边都得是「全部实例都连上」的快照，且文件集合相同。
    # 只验文件集合是不够的 —— 它由产物决定，与哪台实例连上无关
    same = None
    for attempt in range(1, 4):
        base = connected("default")
        up = connected(PID)
        if base is None or up is None:
            print(f"         第 {attempt} 次没能同时取到两边的全连快照，重试")
            time.sleep(3)
            continue
        bpaths = sorted(f["path"] for f in base.get("files") or [])
        upaths = sorted(f["path"] for f in up.get("files") or [])
        if bpaths and bpaths == upaths:
            same = bpaths
            break
        print(f"         第 {attempt} 次两边文件集合不一致，重试取数："
              f"local {len(bpaths)} 个 / uploaded {len(upaths)} 个")
        time.sleep(3)

    if not check(same is not None,
                 "两边都取到全连快照且文件集合相同（不满足就无从比对，原因见上面几行）"):
        return
    check(len(same) > 0, f"共 {len(same)} 个文件参与逐行比对")

    mismatched = []
    for path in same:
        _, b = detail("default", path)
        _, u = detail(PID, path)
        if not b.get("found") or not u.get("found"):
            mismatched.append((path, "取不到文件明细"))
            continue
        bk, uk = rowkey(b["rows"]), rowkey(u["rows"])
        if bk != uk:
            only_b = sorted(bk - uk)[:3]
            only_u = sorted(uk - bk)[:3]
            mismatched.append((path, f"local 独有 {only_b} / uploaded 独有 {only_u}"))
    for path, why in mismatched:
        print(f"         {path}：{why}")
    check(not mismatched,
          f"{len(same)} 个文件逐行一致 —— 按 buildId 取回的产物解出的行号是对的")

    # ---- 6. 产物被删掉：拒绝出报告并点名 ----
    print("\n>> 6. 删掉这个构建的产物后必须拒绝出报告，而不是当成「这些代码没被调用过」")
    status, body = drop_artifact(build)
    if not check(status == 200, f"删产物返回 {status}"):
        print(f"         {body}")
    # 先打一次必然失败的采集：它会在 collectLock 上等在途的那一轮跑完。
    # 不等的话，一轮「删除前已经解析过产物、还在跑归一化」的调度采集会在
    # 基准读完之后才刷新 lastCollectedAt，最后那条断言就假红了
    collect(PID)
    _, s = summary(PID)
    collected_at = s.get("lastCollectedAt")
    collect(PID)
    _, s = summary(PID)
    err = s.get("lastError") or ""
    check(s.get("probeStatus") == "ANALYZE_ERROR",
          f"产物没了之后 probeStatus = {s.get('probeStatus')}")
    check(build in err, "错误里点名了是哪个构建缺产物")
    # 注意<b>不能</b>断言 files 为空：平台失败时刻意保留上一次成功的快照
    #（界面显示旧数据 + 错误横幅），清空反而会让人以为这些代码没被跑过。
    # 要验的是「没把这次失败当成一次成功的采集」，而 lastCollectedAt 只在成功路径上刷新
    check(s.get("lastCollectedAt") == collected_at,
          f"采集时间没有前进 —— 这次失败没有被记成一次成功的采集（仍是 {collected_at}）")
    print(f"         {err[:150]}")

    # ---- 7. local 项目不受影响 ----
    print("\n>> 7. 默认项目（local）自始至终不受影响")
    base = connected("default")
    if check(base is not None, "默认项目仍能取到全连快照"):
        check(len(base.get("files") or []) > 0,
              f"默认项目仍有 {len(base.get('files') or [])} 个文件")


if __name__ == "__main__":
    main()
