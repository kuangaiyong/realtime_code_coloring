//! 与 Java 版 OrderService、Go 版 Store、C++ 版 Store 保持同样的形状：
//! 几个带分支的接口，只调用其中一部分时，未走到的分支应当在平台上保持未覆盖。
use std::collections::HashMap;
use std::sync::Mutex;

#[derive(Clone)]
pub struct Order {
    pub biz_no: String,
    pub amount: i64,
    pub paid_amount: i64,
    pub status: String,
    pub remark: String,
}

impl Order {
    fn new(biz_no: &str, amount: i64, status: &str) -> Order {
        Order {
            biz_no: biz_no.to_string(),
            amount,
            paid_amount: 0,
            status: status.to_string(),
            remark: String::new(),
        }
    }
}

pub struct Store {
    repo: Mutex<HashMap<String, Order>>,
}

fn is_final_state(o: &Order) -> bool {
    o.status == "PAID" || o.status == "REFUNDED" || o.status == "EXPIRED"
}

impl Store {
    pub fn new() -> Store {
        let mut repo = HashMap::new();
        repo.insert("R1001".to_string(), Order::new("R1001", 2999, "CREATED"));
        repo.insert("R1002".to_string(), Order::new("R1002", 15800, "PAID"));
        Store { repo: Mutex::new(repo) }
    }

    /// 接口 A：支付回调
    pub fn handle_callback(&self, biz_no: &str, status: &str) -> String {
        let mut repo = self.repo.lock().unwrap();
        let o = match repo.get_mut(biz_no) {
            None => return format!("ORDER_NOT_FOUND:{}", biz_no),
            Some(o) => o,
        };
        if is_final_state(o) {
            return format!("DUPLICATE_CALLBACK:{}", biz_no);
        }
        if status == "SUCCESS" {
            o.status = "PAID".to_string();
            o.paid_amount = o.amount;
        } else if status == "TIMEOUT" {
            o.status = "EXPIRED".to_string();
            o.remark = "stock released".to_string();
        } else if status == "FAILED" {
            o.status = "FAILED".to_string();
            o.remark = "risk notified".to_string();
        } else {
            return format!("UNKNOWN_STATUS:{}", status);
        }
        o.status.clone()
    }

    /// 接口 B：订单查询
    pub fn query_order(&self, biz_no: &str) -> Option<Order> {
        let repo = self.repo.lock().unwrap();
        let o = match repo.get(biz_no) {
            None => return None,
            Some(o) => o,
        };
        Some(o.clone())
    }

    /// 接口 C：退款
    pub fn refund(&self, biz_no: &str, amount: i64) -> String {
        let mut repo = self.repo.lock().unwrap();
        let o = match repo.get_mut(biz_no) {
            None => return format!("ORDER_NOT_FOUND:{}", biz_no),
            Some(o) => o,
        };
        if o.status != "PAID" {
            return format!("NOT_REFUNDABLE:{}", o.status);
        }
        if amount > o.paid_amount {
            return "AMOUNT_EXCEEDED".to_string();
        }
        o.status = "REFUNDED".to_string();
        "REFUNDED".to_string()
    }
}
