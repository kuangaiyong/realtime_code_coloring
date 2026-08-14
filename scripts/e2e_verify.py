"""
P0 端到端验收：真实服务、真实探针、真实 HTTP 请求，无 mock。

验证命题：
  1. 调用接口 A（支付回调 SUCCESS）后，handleCallback 的对应代码行由未覆盖变为已覆盖；
  2. 未被调用的接口 B（查询）、C（退款）对应代码行保持未覆盖；
  3. 同一方法内未走到的分支（TIMEOUT / FAILED）保持未覆盖 —— 这是行级染色相对
     「接口级覆盖」的价值所在；
  4. 从请求发出到平台反映出变化的延迟不超过 5 秒。
"""
import json
import sys
import time
import urllib.parse
import urllib.request

PLATFORM = "http://localhost:18090"
DEMO = "http://localhost:18080"
TARGET = "com/shop/order/service/OrderService.java"
MAX_LATENCY_SEC = 5.0


def get(url):
    with urllib.request.urlopen(url, timeout=10) as r:
        return json.load(r)


def post(url):
    req = urllib.request.Request(url, method="POST", data=b"")
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)


def line_status(path=TARGET):
    d = get(f"{PLATFORM}/api/coverage/file?path={urllib.parse.quote(path)}")
    if not d.get("found"):
        print("!! 无法读取文件:", d)
        sys.exit(1)
    return {r["line"]: (r["status"], r["text"]) for r in d["rows"]}, d


def find_lines(rows, needle):
    return sorted(n for n, (_, text) in rows.items() if needle in text)


def show(rows, nums, title):
    print(f"\n  {title}")
    for n in nums:
        st, tx = rows[n]
        mark = {"COVERED": "[绿] 已覆盖", "MISSED": "[红] 未覆盖",
                "PARTIAL": "[黄] 部分", "EMPTY": "     非可执行"}[st]
        print(f"    L{n:<4d} {mark:<14s} {tx.strip()[:62]}")


def main():
    print("=" * 78)
    print("P0 端到端验收 —— 代码实时染色最小闭环")
    print("=" * 78)

    # 清零，确保从干净基线开始
    post(f"{PLATFORM}/api/coverage/reset")
    time.sleep(1)

    before, _ = line_status()

    # 只观察「可执行行」。方法签名行、switch 的 case 标签行不产生字节码探针
    # （跳转表落在 switch 那一行），JaCoCo 将其标为 EMPTY，断言它们没有意义。
    def executable(needles):
        out = []
        for nd in needles:
            out += [n for n in find_lines(before, nd) if before[n][0] != "EMPTY"]
        return sorted(set(out))

    # 各取该分支/方法体内独有的语句，避免跨方法重名
    a_lines = executable(['order.setStatus("PAID")', "order.setPaidAmount("])
    unused_branch = executable(['order.setStatus("EXPIRED")', "releaseStock(order)",
                                'order.setStatus("FAILED")', "notifyRisk(order)",
                                "UNKNOWN_STATUS"])
    bc_lines = executable(["return null;",                    # queryOrder 独有
                           "NOT_REFUNDABLE", "AMOUNT_EXCEEDED",
                           'order.setStatus("REFUNDED")'])    # refund 独有

    show(before, a_lines + unused_branch + bc_lines, "调用前 —— 全部未覆盖")

    for grp, name in ((a_lines, "接口A"), (unused_branch, "未走分支"), (bc_lines, "接口B/C")):
        assert grp, f"未能定位 {name} 的代码行"
        for n in grp:
            assert before[n][0] == "MISSED", f"L{n} 期望 MISSED，实际 {before[n][0]}"
    print("\n  基线确认：以上代码行均为未覆盖")

    # ---- 只调用接口 A，且只走 SUCCESS 分支 ----
    print(f"\n  >> POST {DEMO}/api/order/callback?bizNo=A1001&status=SUCCESS")
    t0 = time.time()
    resp = post(f"{DEMO}/api/order/callback?bizNo=A1001&status=SUCCESS")
    print(f"     被测服务响应: {json.dumps(resp, ensure_ascii=False)}")

    # 轮询等待平台反映变化，记录真实延迟
    latency = None
    while time.time() - t0 < 15:
        after, _ = line_status()
        if all(after[n][0] == "COVERED" for n in a_lines):
            latency = time.time() - t0
            break
        time.sleep(0.3)

    if latency is None:
        print("\n  !! 失败：15 秒内接口 A 的代码行仍未变为已覆盖")
        show(after, a_lines, "当前状态")
        sys.exit(1)

    show(after, a_lines, "调用后 —— 接口 A 的代码行")
    show(after, unused_branch, "调用后 —— 同方法内未走到的分支")
    show(after, bc_lines, "调用后 —— 未调用的接口 B / C")

    print("\n" + "-" * 78)
    ok = True

    for n in a_lines:
        if after[n][0] != "COVERED":
            print(f"  [FAIL] L{n} 接口A 未变绿：{after[n][0]}")
            ok = False
    print(f"  [PASS] 接口 A 的 {len(a_lines)} 行全部变为已覆盖" if ok else "")

    bad = [n for n in unused_branch if after[n][0] != "MISSED"]
    if bad:
        print(f"  [FAIL] 未走到的分支被误标为已覆盖：{bad}")
        ok = False
    else:
        print(f"  [PASS] 同方法内 TIMEOUT/FAILED 分支保持未覆盖（{len(unused_branch)} 行）")

    bad = [n for n in bc_lines if after[n][0] != "MISSED"]
    if bad:
        print(f"  [FAIL] 未调用的接口 B/C 被误标为已覆盖：{bad}")
        ok = False
    else:
        print(f"  [PASS] 未调用的接口 B / C 保持未覆盖（{len(bc_lines)} 行）")

    if latency > MAX_LATENCY_SEC:
        print(f"  [FAIL] 端到端延迟 {latency:.2f}s 超过 {MAX_LATENCY_SEC}s")
        ok = False
    else:
        print(f"  [PASS] 端到端延迟 {latency:.2f}s，未超过 {MAX_LATENCY_SEC}s")

    s = get(f"{PLATFORM}/api/coverage/summary")
    svc = next(f for f in s["files"] if f["sourceFileName"] == "OrderService.java")
    print(f"  [INFO] OrderService.java 覆盖率 {svc['ratio']}%  "
          f"(已覆盖 {svc['coveredLines']} 行 / 未覆盖 {svc['missedLines']} 行)")
    print(f"  [INFO] 整体行覆盖率 {s['overallRatio']}%")

    print("-" * 78)
    print("  验收结论：" + ("全部通过" if ok else "存在失败项"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
