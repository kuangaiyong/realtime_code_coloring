// 覆盖率探针。只在覆盖率构建里参与编译链接，正常构建根本不编译这个文件。
//
// 它是一个独立的编译单元，靠全局对象的构造函数（在 main 之前执行）自动启动，
// 因此**既有业务源码一行不改**——业务代码不 include 它，也不调用它任何东西。
// 这与 Go 侧用 build tag 守卫探针文件是同一个手法；计划书里原本设想的
// LD_PRELOAD 注入 / SIGUSR1 信号在 Windows 上都不存在，而这个办法跨平台。
//
// 对齐 Java / Go 侧的形状：
//   GET  /coverage/id      自报构建版本（相当于 JaCoCo 的 sessionid）
//   GET  /coverage/dump    落盘并交出全部 .gcda
//   POST /coverage/clear   清零计数器
//
// 关于 gcov 运行期 API 的两条硬事实（POC 实测，弄错就是静默错误的覆盖数据）：
//   1. __gcov_dump() **只生效一次**，之后必须 __gcov_reset() 重新武装，
//      否则后续 dump 什么都不写——而「没有 .gcda」与「计数器全零」在 gcov
//      的输出里长得一模一样，全是 #####；
//   2. .gcda 写入时会与磁盘上已有内容**合并**，所以「dump + reset」交出的是
//      累计值而非增量，轮询不会丢历史；真要清零就得连 .gcda 一起删掉。
#include <winsock2.h>
#include <ws2tcpip.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

extern "C" void __gcov_dump(void);
extern "C" void __gcov_reset(void);

namespace {

namespace fs = std::filesystem;

// dump 与 clear 都动同一份计数器，必须串行
std::mutex g_gcov_mu;

std::string envOr(const char* key, const char* fallback) {
    const char* v = std::getenv(key);
    return (v == nullptr || *v == '\0') ? fallback : v;
}

std::string dataDir() {
    return envOr("COVERAGE_DATA_DIR", ".");
}

std::vector<fs::path> gcdaFiles() {
    std::vector<fs::path> out;
    std::error_code ec;
    for (auto& e : fs::recursive_directory_iterator(dataDir(), ec)) {
        if (!ec && e.is_regular_file() && e.path().extension() == ".gcda") {
            out.push_back(e.path());
        }
    }
    return out;
}

void sendAll(SOCKET c, const char* data, size_t len) {
    size_t sent = 0;
    while (sent < len) {
        int n = send(c, data + sent, static_cast<int>(len - sent), 0);
        if (n <= 0) {
            return;
        }
        sent += static_cast<size_t>(n);
    }
}

void respond(SOCKET c, int code, const std::string& type, const std::string& body) {
    std::string head = "HTTP/1.1 " + std::to_string(code) + (code == 200 ? " OK" : " Error")
                       + "\r\nContent-Type: " + type + "\r\nConnection: close\r\nContent-Length: "
                       + std::to_string(body.size()) + "\r\n\r\n";
    sendAll(c, head.data(), head.size());
    sendAll(c, body.data(), body.size());
}

void putU32(std::string& out, uint32_t v) {
    out += static_cast<char>((v >> 24) & 0xff);
    out += static_cast<char>((v >> 16) & 0xff);
    out += static_cast<char>((v >> 8) & 0xff);
    out += static_cast<char>(v & 0xff);
}

/**
 * 落盘并把全部 .gcda 打成一个响应体。
 * 一个 C++ 服务通常有多个编译单元，就有多份 .gcda，所以要能带文件名交出多份；
 * 格式：重复 { u32 名字长度 | 名字 | u32 内容长度 | 内容 }，大端。
 */
std::string dumpAll(std::string& err) {
    std::lock_guard<std::mutex> lock(g_gcov_mu);
    __gcov_dump();
    __gcov_reset();  // 不 reset 的话，下一次 dump 会静默地什么都不写

    std::string body;
    fs::path root(dataDir());
    for (const fs::path& p : gcdaFiles()) {
        std::ifstream in(p, std::ios::binary);
        if (!in) {
            err = "无法读取 " + p.string();
            return "";
        }
        std::string data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        std::string name = fs::relative(p, root).generic_string();
        putU32(body, static_cast<uint32_t>(name.size()));
        body += name;
        putU32(body, static_cast<uint32_t>(data.size()));
        body += data;
    }
    if (body.empty()) {
        err = "目录 " + dataDir() + " 下没有任何 .gcda，请确认服务是以 --coverage 构建的";
    }
    return body;
}

void clearAll() {
    std::lock_guard<std::mutex> lock(g_gcov_mu);
    __gcov_reset();
    // 光清内存计数器不够：.gcda 写入是合并语义，不删掉的话下次 dump 又把旧数据带回来
    std::error_code ec;
    for (const fs::path& p : gcdaFiles()) {
        fs::remove(p, ec);
    }
}

void serve(SOCKET c) {
    std::string req;
    char buf[2048];
    while (req.find("\r\n") == std::string::npos) {
        int n = recv(c, buf, sizeof(buf), 0);
        if (n <= 0) {
            return;
        }
        req.append(buf, n);
    }
    size_t first = req.find(' ');
    size_t second = req.find(' ', first + 1);
    if (first == std::string::npos || second == std::string::npos) {
        return;
    }
    std::string path = req.substr(first + 1, second - first - 1);

    if (path == "/coverage/id") {
        respond(c, 200, "text/plain", envOr("COVERAGE_BUILD_ID", ""));
    } else if (path == "/coverage/dump") {
        std::string err;
        std::string body = dumpAll(err);
        if (!err.empty()) {
            // 交出半份或空数据比报错更糟：平台会当成「这些代码没被跑过」照常出报告
            respond(c, 500, "text/plain", err);
        } else {
            respond(c, 200, "application/octet-stream", body);
        }
    } else if (path == "/coverage/clear") {
        clearAll();
        respond(c, 200, "text/plain", "ok");
    } else {
        respond(c, 404, "text/plain", "no such endpoint");
    }
}

void loop(SOCKET srv) {
    while (true) {
        SOCKET c = accept(srv, nullptr, nullptr);
        if (c == INVALID_SOCKET) {
            continue;
        }
        serve(c);
        closesocket(c);
    }
}

struct Agent {
    Agent() {
        // main() 里的 WSAStartup 还没执行（静态初始化早于 main），自己来一次。
        // WSAStartup 是引用计数的，重复调用无害
        WSADATA wsa;
        if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
            std::fprintf(stderr, "coverage agent: WSAStartup failed\n");
            return;
        }
        std::string spec = envOr("COVERAGE_ADDR", "127.0.0.1:6500");
        size_t colon = spec.rfind(':');
        std::string host = colon == std::string::npos ? "127.0.0.1" : spec.substr(0, colon);
        int port = colon == std::string::npos ? 6500 : std::atoi(spec.c_str() + colon + 1);

        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(static_cast<u_short>(port));
        // 默认只绑回环，与 Java 侧的 address=localhost 一致：/coverage/clear 能清零
        // 计数器，暴露到网络上等于把「正在录的那个场景」交给任何人随手作废
        addr.sin_addr.s_addr = host.empty() ? htonl(INADDR_ANY) : inet_addr(host.c_str());
        if (addr.sin_addr.s_addr == INADDR_NONE) {
            addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        }

        SOCKET srv = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (bind(srv, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 || listen(srv, 8) != 0) {
            std::fprintf(stderr, "coverage agent: listen %s failed (%d)\n",
                         spec.c_str(), WSAGetLastError());
            return;
        }
        std::fprintf(stderr, "coverage agent listening on %s (build id: %s, data dir: %s)\n",
                     spec.c_str(), envOr("COVERAGE_BUILD_ID", "-").c_str(), dataDir().c_str());
        std::fflush(stderr);
        std::thread(loop, srv).detach();
    }
};

Agent g_agent;

}  // namespace
