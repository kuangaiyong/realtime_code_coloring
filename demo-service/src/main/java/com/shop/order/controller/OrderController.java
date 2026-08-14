package com.shop.order.controller;

import com.shop.order.model.Order;
import com.shop.order.service.OrderService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** 接口 A */
    @PostMapping("/callback")
    public Map<String, Object> callback(@RequestParam String bizNo,
                                        @RequestParam(defaultValue = "SUCCESS") String status) {
        String result = orderService.handleCallback(bizNo, status);
        return resp(result);
    }

    /** 接口 B */
    @GetMapping("/query")
    public Map<String, Object> query(@RequestParam String bizNo) {
        Order order = orderService.queryOrder(bizNo);
        if (order == null) {
            return resp("NOT_FOUND");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bizNo", order.getBizNo());
        body.put("amount", order.getAmount());
        body.put("status", order.getStatus());
        return resp(body);
    }

    /** 接口 C */
    @PostMapping("/refund")
    public Map<String, Object> refund(@RequestParam String bizNo,
                                      @RequestParam long amount) {
        return resp(orderService.refund(bizNo, amount));
    }

    private Map<String, Object> resp(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("data", data);
        return m;
    }
}
