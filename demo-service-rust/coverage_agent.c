/*
 * 覆盖率探针（Rust 被测服务用）。
 *
 * 它不是 crate 依赖，也不是 Rust 源码里的一个 mod —— 而是单独用 gcc 编成 .o，
 * 构建时经 `-C link-arg=coverage_agent.o` 注入。启动靠 .CRT$XCU 段里的函数指针：
 * MSVC 的启动例程会在 main 之前逐个调用它们。因此**业务源码一行不改，
 * Cargo.toml 也不动** —— 与 Go 的 build tag 探针文件、C++ 的独立编译单元同一个手法。
 *
 * 计划书 §4.4 原本给 Rust 设想的三条路（%c 连续模式 / minicov 加依赖 / 外部触发
 * __llvm_profile_write_file）都不理想：连续模式在 Windows 上不可用，minicov 要改源码
 * 加依赖，外部触发在 Windows 上没有 LD_PRELOAD。这条是第四条，且不依赖 POSIX。
 *
 * 全文只用 Win32 API，一个 CRT 函数都不碰：这个 .o 要链进 MSVC ABI 的产物，
 * 而它是 MinGW gcc 编的，两边的 CRT 包装并不通用。
 *
 * 对齐 Java / Go / C++ 侧的形状：
 *   GET  /coverage/id      自报构建版本（相当于 JaCoCo 的 sessionid）
 *   GET  /coverage/dump    落盘并交出 .profraw
 *   POST /coverage/clear   清零计数器
 *
 * 关于 LLVM profile 运行期 API 的两条硬事实（POC 实测，弄错就是静默错误的覆盖数据）：
 *   1. __llvm_profile_write_file() **可以重复调用**（这点比 gcov 好，gcov 的 dump
 *      只生效一次，必须 reset 才能重新武装）；
 *   2. 但它是**合并写入** —— 不先删掉旧的 .profraw，写出来的是新旧之和，
 *      清零之后再 dump 会原样带回上一轮的覆盖，而界面上看不出任何异样。
 */
/* winsock2.h 必须排在 windows.h 前面，否则 windows.h 会先拉进旧版 winsock */
#include <winsock2.h>
#include <windows.h>

extern int  __llvm_profile_write_file(void);
extern void __llvm_profile_reset_counters(void);

#define BUF 4096

static int sl(const char* s) {
    int n = 0;
    while (s[n]) n++;
    return n;
}

static int starts_with(const char* s, const char* p) {
    while (*p) {
        if (*s++ != *p++) return 0;
    }
    return 1;
}

/* 无符号十进制转字符串，返回长度。用不了 CRT 的 sprintf */
static int u_to_str(unsigned long long v, char* out) {
    char tmp[24];
    int n = 0;
    if (v == 0) tmp[n++] = '0';
    while (v > 0) {
        tmp[n++] = (char)('0' + (v % 10));
        v /= 10;
    }
    for (int i = 0; i < n; i++) out[i] = tmp[n - 1 - i];
    out[n] = 0;
    return n;
}

static void say(const char* s) {
    DWORD n = 0;
    WriteFile(GetStdHandle(STD_ERROR_HANDLE), s, (DWORD)sl(s), &n, NULL);
}

static void send_all(SOCKET c, const char* data, int len) {
    int sent = 0;
    while (sent < len) {
        int n = send(c, data + sent, len - sent, 0);
        if (n <= 0) return;
        sent += n;
    }
}

static void respond(SOCKET c, int code, const char* type, const char* body, int len) {
    char head[512];
    int h = 0;
    const char* status = (code == 200) ? "200 OK" : "500 Error";
    const char* parts[6] = {"HTTP/1.1 ", status, "\r\nContent-Type: ", type,
                            "\r\nConnection: close\r\nContent-Length: ", NULL};
    for (int i = 0; parts[i]; i++) {
        const char* p = parts[i];
        while (*p) head[h++] = *p++;
    }
    h += u_to_str((unsigned long long)len, head + h);
    head[h++] = '\r'; head[h++] = '\n'; head[h++] = '\r'; head[h++] = '\n';
    send_all(c, head, h);
    send_all(c, body, len);
}

static void respond_text(SOCKET c, int code, const char* body) {
    respond(c, code, "text/plain", body, sl(body));
}

/* .profraw 的落点由 LLVM_PROFILE_FILE 决定，探针要按同一个路径删和读 */
static int profraw_path(char* out, int cap) {
    DWORD n = GetEnvironmentVariableA("LLVM_PROFILE_FILE", out, (DWORD)cap);
    return (n > 0 && n < (DWORD)cap);
}

/*
 * 落盘并交出 .profraw。
 * 必须先删旧文件：write_file 是合并语义，不删的话清零之后仍会带回上一轮的覆盖。
 */
static char* dump_profraw(unsigned long* out_len, const char** err) {
    char path[BUF];
    *out_len = 0;
    if (!profraw_path(path, sizeof(path))) {
        *err = "LLVM_PROFILE_FILE not set; cannot locate .profraw";
        return NULL;
    }
    DeleteFileA(path);
    if (__llvm_profile_write_file() != 0) {
        *err = "__llvm_profile_write_file failed";
        return NULL;
    }
    HANDLE h = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                           NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) {
        *err = "profraw not produced; is the binary built with -C instrument-coverage?";
        return NULL;
    }
    LARGE_INTEGER size;
    if (!GetFileSizeEx(h, &size) || size.QuadPart <= 0) {
        CloseHandle(h);
        *err = "profraw is empty";
        return NULL;
    }
    char* buf = (char*)HeapAlloc(GetProcessHeap(), 0, (SIZE_T)size.QuadPart);
    if (!buf) {
        CloseHandle(h);
        *err = "out of memory reading profraw";
        return NULL;
    }
    DWORD got = 0, total = 0;
    while (total < (DWORD)size.QuadPart &&
           ReadFile(h, buf + total, (DWORD)size.QuadPart - total, &got, NULL) && got > 0) {
        total += got;
    }
    CloseHandle(h);
    *out_len = total;
    return buf;
}

static void clear_counters(void) {
    char path[BUF];
    __llvm_profile_reset_counters();
    /* 光清内存计数器不够：write_file 是合并语义，旧文件不删就会把上一轮带回来 */
    if (profraw_path(path, sizeof(path))) {
        DeleteFileA(path);
    }
}

static void serve(SOCKET c) {
    char req[BUF];
    int used = 0;
    while (used < BUF - 1) {
        int n = recv(c, req + used, BUF - 1 - used, 0);
        if (n <= 0) return;
        used += n;
        req[used] = 0;
        int done = 0;
        for (int i = 0; i + 1 < used; i++) {
            if (req[i] == '\r' && req[i + 1] == '\n') { done = 1; break; }
        }
        if (done) break;
    }
    req[used] = 0;

    /* 请求行形如 "GET /coverage/dump HTTP/1.1"，跳到第一个空格之后 */
    int i = 0;
    while (req[i] && req[i] != ' ') i++;
    if (!req[i]) return;
    const char* path = req + i + 1;

    if (starts_with(path, "/coverage/id")) {
        char id[256];
        DWORD n = GetEnvironmentVariableA("COVERAGE_BUILD_ID", id, sizeof(id));
        if (n == 0 || n >= sizeof(id)) id[0] = 0;
        respond(c, 200, "text/plain", id, sl(id));
    } else if (starts_with(path, "/coverage/dump")) {
        unsigned long len = 0;
        const char* err = NULL;
        char* body = dump_profraw(&len, &err);
        if (!body) {
            /* 交出半份或空数据比报错更糟：平台会当成「这些代码没被跑过」照常出报告 */
            respond_text(c, 500, err ? err : "dump failed");
        } else {
            respond(c, 200, "application/octet-stream", body, (int)len);
            HeapFree(GetProcessHeap(), 0, body);
        }
    } else if (starts_with(path, "/coverage/clear")) {
        clear_counters();
        respond_text(c, 200, "ok");
    } else {
        respond_text(c, 404, "no such endpoint");
    }
}

static DWORD WINAPI agent_loop(LPVOID param) {
    SOCKET srv = (SOCKET)(ULONG_PTR)param;
    for (;;) {
        SOCKET c = accept(srv, NULL, NULL);
        if (c == INVALID_SOCKET) continue;
        serve(c);
        closesocket(c);
    }
}

static void agent_init(void) {
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        say("coverage agent: WSAStartup failed\n");
        return;
    }
    char spec[128];
    DWORD n = GetEnvironmentVariableA("COVERAGE_ADDR", spec, sizeof(spec));
    if (n == 0 || n >= sizeof(spec)) {
        spec[0] = 0;
    }
    /* 默认只绑回环，与 Java 侧 address=localhost 对齐：/coverage/clear 能清零计数器，
       暴露到网络上等于把「正在录的那个场景」交给任何人随手作废 */
    unsigned long addr = 0x0100007F; /* 127.0.0.1，网络字节序 */
    int port = 6600;
    if (spec[0]) {
        int colon = -1;
        for (int i = 0; spec[i]; i++) if (spec[i] == ':') colon = i;
        if (colon >= 0) {
            port = 0;
            for (int i = colon + 1; spec[i]; i++) {
                if (spec[i] < '0' || spec[i] > '9') { port = 0; break; }
                port = port * 10 + (spec[i] - '0');
            }
            spec[colon] = 0;
            if (spec[0] == 0) addr = 0; /* 空 host 表示绑全部网卡 */
            else addr = inet_addr(spec);
            if (addr == INADDR_NONE) addr = 0x0100007F;
        }
    }
    if (port <= 0) port = 6600;

    SOCKET srv = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    struct sockaddr_in sa;
    for (unsigned i = 0; i < sizeof(sa); i++) ((char*)&sa)[i] = 0;
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = addr;
    sa.sin_port = htons((unsigned short)port);
    if (bind(srv, (struct sockaddr*)&sa, sizeof(sa)) != 0 || listen(srv, 8) != 0) {
        say("coverage agent: listen failed\n");
        return;
    }
    say("coverage agent listening\n");
    CreateThread(NULL, 0, agent_loop, (LPVOID)(ULONG_PTR)srv, 0, NULL);
}

/* MSVC 的启动例程按 .CRT$XC* 段里的函数指针逐个调用。
   GCC 的 __attribute__((constructor)) 走 .ctors，MSVC 链接时根本不会执行 */
__attribute__((section(".CRT$XCU"), used))
static void (*p_agent_init)(void) = agent_init;
