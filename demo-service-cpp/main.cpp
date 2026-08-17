// C++ 被测服务。只有最小可用的 HTTP 服务端，够 E2E 打接口即可。
// 覆盖率相关的东西一律不在这里：探针是独立编译单元 coverage_agent.cpp，
// 靠全局对象的构造函数自动启动，本文件不 include 也不调用它任何东西。
#include <winsock2.h>
#include <ws2tcpip.h>

#include <cstdio>
#include <cstdlib>
#include <string>

#include "order.h"

namespace {

Store g_store;

std::string queryParam(const std::string& target, const std::string& key) {
    size_t q = target.find('?');
    if (q == std::string::npos) {
        return "";
    }
    std::string qs = target.substr(q + 1);
    size_t pos = 0;
    while (pos < qs.size()) {
        size_t amp = qs.find('&', pos);
        std::string pair = qs.substr(pos, amp == std::string::npos ? std::string::npos : amp - pos);
        size_t eq = pair.find('=');
        if (eq != std::string::npos && pair.substr(0, eq) == key) {
            return pair.substr(eq + 1);
        }
        if (amp == std::string::npos) {
            break;
        }
        pos = amp + 1;
    }
    return "";
}

std::string jsonEscape(const std::string& s) {
    std::string out;
    for (char c : s) {
        if (c == '"' || c == '\\') {
            out += '\\';
        }
        out += c;
    }
    return out;
}

void sendJson(SOCKET c, const std::string& body) {
    std::string res = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n"
                      "Content-Length: " + std::to_string(body.size()) + "\r\n\r\n" + body;
    send(c, res.data(), static_cast<int>(res.size()), 0);
}

/** 只取请求行的 target，body 一概不看：本服务的接口参数全在 query string 里 */
std::string readTarget(SOCKET c) {
    std::string req;
    char buf[2048];
    while (req.find("\r\n") == std::string::npos) {
        int n = recv(c, buf, sizeof(buf), 0);
        if (n <= 0) {
            return "";
        }
        req.append(buf, n);
    }
    size_t first = req.find(' ');
    size_t second = req.find(' ', first + 1);
    if (first == std::string::npos || second == std::string::npos) {
        return "";
    }
    return req.substr(first + 1, second - first - 1);
}

void handle(SOCKET c, const std::string& target) {
    std::string path = target.substr(0, target.find('?'));
    std::string bizNo = queryParam(target, "bizNo");

    if (path == "/api/order/callback") {
        std::string status = queryParam(target, "status");
        if (status.empty()) {
            status = "SUCCESS";
        }
        sendJson(c, R"({"ok":true,"data":")" + jsonEscape(g_store.handleCallback(bizNo, status)) + "\"}");
    } else if (path == "/api/order/query") {
        Order o;
        if (!g_store.queryOrder(bizNo, o)) {
            sendJson(c, R"({"ok":true,"data":"NOT_FOUND"})");
            return;
        }
        sendJson(c, R"({"ok":true,"data":{"bizNo":")" + jsonEscape(o.bizNo)
                        + R"(","amount":)" + std::to_string(o.amount)
                        + R"(,"paidAmount":)" + std::to_string(o.paidAmount)
                        + R"(,"status":")" + jsonEscape(o.status)
                        + R"(","remark":")" + jsonEscape(o.remark) + "\"}}");
    } else if (path == "/api/order/refund") {
        std::string raw = queryParam(target, "amount");
        long long amount = 0;
        if (!raw.empty()) {
            // 只接受纯数字，且交给 strtoll 处理溢出：手写逐位累加会绕回负数
            char* end = nullptr;
            amount = std::strtoll(raw.c_str(), &end, 10);
            if (*end != '\0' || raw[0] == '-' || raw[0] == '+') {
                sendJson(c, R"({"ok":true,"data":"BAD_AMOUNT"})");
                return;
            }
        }
        sendJson(c, R"({"ok":true,"data":")" + jsonEscape(g_store.refund(bizNo, amount)) + "\"}");
    } else {
        sendJson(c, R"({"ok":false,"data":"NO_ROUTE"})");
    }
}

}  // namespace

int main(int argc, char** argv) {
    int port = 18060;
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        if (a.rfind("-addr=:", 0) == 0) {
            port = std::atoi(a.c_str() + 7);
        }
    }

    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        std::fprintf(stderr, "demo-service-cpp WSAStartup failed\n");
        return 1;
    }
    SOCKET srv = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(static_cast<u_short>(port));
    // 先绑定监听成功再打就绪日志：反过来的话端口被占用时日志已经说「started」，
    // 等就绪的脚本会判成启动成功，真正的失败要等到后面某个用例莫名其妙地失败才暴露
    if (bind(srv, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 || listen(srv, 16) != 0) {
        std::fprintf(stderr, "demo-service-cpp listen :%d failed (%d)\n", port, WSAGetLastError());
        return 1;
    }
    std::printf("demo-service-cpp started on :%d\n", port);
    std::fflush(stdout);

    while (true) {
        SOCKET c = accept(srv, nullptr, nullptr);
        if (c == INVALID_SOCKET) {
            continue;
        }
        std::string target = readTarget(c);
        if (!target.empty()) {
            handle(c, target);
        }
        closesocket(c);
    }
}
