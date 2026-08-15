package com.shop.order.service;

import com.shop.order.model.Order;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单核心逻辑。
 *
 * 这里刻意保留多个分支：只调用支付成功回调时，超时/失败分支不会被执行，
 * 平台上应当能看到它们保持未覆盖（红色）。
 */
@Service
public class OrderService {

    private final Map<String, Order> repo = new ConcurrentHashMap<>();

    public OrderService() {
        repo.put("A1001", new Order("A1001", 2999L, "CREATED"));
        repo.put("A1002", new Order("A1002", 15800L, "PAID"));
    }

    /** 接口 A：支付回调 */
    public String handleCallback(String bizNo, String status) {
        Order order = repo.get(bizNo);
        if (order == null) {
            return "ORDER_NOT_FOUND:" + bizNo;
        }
        if (isFinalState(order)) {
            return "DUPLICATE_CALLBACK:" + bizNo;
        }

        switch (status) {
            case "SUCCESS" -> {
                order.setStatus("PAID");
                order.setPaidAmount(order.getAmount());
            }
            case "TIMEOUT" -> {
                order.setStatus("EXPIRED");
                releaseStock(order);
            }
            case "FAILED" -> {
                order.setStatus("FAILED");
                notifyRisk(order);
            }
            default -> {
                return "UNKNOWN_STATUS:" + status;
            }
        }
        return order.getStatus();
    }

    /** 接口 B：订单查询 */
    public Order queryOrder(String bizNo) {
        Order order = repo.get(bizNo);
        if (order == null) {
            return null;
        }
        return order;
    }

    /** 接口 C：退款 */
    public String refund(String bizNo, long amount) {
        Order order = repo.get(bizNo);
        if (order == null) {
            return "ORDER_NOT_FOUND:" + bizNo;
        }
        if (!"PAID".equals(order.getStatus())) {
            return "NOT_REFUNDABLE:" + order.getStatus();
        }
        if (amount > order.getPaidAmount()) {
            return "AMOUNT_EXCEEDED";
        }
        order.setStatus("REFUNDED");
        return "REFUNDED:" + amount;
    }

    /** 接口 D：取消订单 */
    public String cancel(String bizNo, String reason) {
        Order order = repo.get(bizNo);
        if (order == null) {
            return "ORDER_NOT_FOUND:" + bizNo;
        }
        if (isFinalState(order)) {
            return "NOT_CANCELLABLE:" + order.getStatus();
        }
        order.setStatus("CANCELLED");
        order.setRemark(reason);
        return "CANCELLED:" + bizNo;
    }

    private boolean isFinalState(Order order) {
        return "PAID".equals(order.getStatus())
                || "REFUNDED".equals(order.getStatus())
                || "EXPIRED".equals(order.getStatus());
    }

    private void releaseStock(Order order) {
        order.setRemark("stock released");
    }

    private void notifyRisk(Order order) {
        order.setRemark("risk notified");
    }
}
