#include "order.h"

namespace {

bool isFinalState(const Order& o) {
    return o.status == "PAID" || o.status == "REFUNDED" || o.status == "EXPIRED";
}

}  // namespace

Store::Store() {
    repo_["C1001"] = Order{"C1001", 2999, 0, "CREATED", ""};
    repo_["C1002"] = Order{"C1002", 15800, 0, "PAID", ""};
}

std::string Store::handleCallback(const std::string& bizNo, const std::string& status) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = repo_.find(bizNo);
    if (it == repo_.end()) {
        return "ORDER_NOT_FOUND:" + bizNo;
    }
    Order& o = it->second;
    if (isFinalState(o)) {
        return "DUPLICATE_CALLBACK:" + bizNo;
    }
    if (status == "SUCCESS") {
        o.status = "PAID";
        o.paidAmount = o.amount;
    } else if (status == "TIMEOUT") {
        o.status = "EXPIRED";
        o.remark = "stock released";
    } else if (status == "FAILED") {
        o.status = "FAILED";
        o.remark = "risk notified";
    } else {
        return "UNKNOWN_STATUS:" + status;
    }
    return o.status;
}

bool Store::queryOrder(const std::string& bizNo, Order& out) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = repo_.find(bizNo);
    if (it == repo_.end()) {
        return false;
    }
    out = it->second;
    return true;
}

std::string Store::refund(const std::string& bizNo, long long amount) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = repo_.find(bizNo);
    if (it == repo_.end()) {
        return "ORDER_NOT_FOUND:" + bizNo;
    }
    Order& o = it->second;
    if (o.status != "PAID") {
        return "NOT_REFUNDABLE:" + o.status;
    }
    if (amount > o.paidAmount) {
        return "AMOUNT_EXCEEDED";
    }
    o.status = "REFUNDED";
    return "REFUNDED";
}
