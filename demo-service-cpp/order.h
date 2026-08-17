// 与 Java 版 OrderService、Go 版 Store 保持同样的形状：
// 几个带分支的接口，只调用其中一部分时，未走到的分支应当在平台上保持未覆盖。
#pragma once

#include <map>
#include <mutex>
#include <string>

struct Order {
    std::string bizNo;
    long long amount = 0;
    long long paidAmount = 0;
    std::string status;
    std::string remark;
};

class Store {
public:
    Store();

    // 接口 A：支付回调
    std::string handleCallback(const std::string& bizNo, const std::string& status);
    // 接口 B：订单查询。找不到时返回 false
    bool queryOrder(const std::string& bizNo, Order& out);
    // 接口 C：退款
    std::string refund(const std::string& bizNo, long long amount);

private:
    std::mutex mu_;
    std::map<std::string, Order> repo_;
};
