//! Rust 被测服务。只有最小可用的 HTTP 服务端，够 E2E 打接口即可。
//!
//! 覆盖率相关的东西一律不在这里：探针是 coverage_agent.c 编出来的一个 .o，
//! 构建时用 -C link-arg 注入，靠 .CRT$XCU 段的函数指针在 main 之前自动执行。
//! 本文件不 mod 它、不 use 它、不调用它任何东西，Cargo.toml 里也没有它。
mod order;

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};

use order::Store;

/// 只取请求行的 target，body 一概不看：本服务的接口参数全在 query string 里
fn read_target(stream: &mut TcpStream) -> Option<String> {
    let mut buf = [0u8; 2048];
    let n = stream.read(&mut buf).ok()?;
    let req = String::from_utf8_lossy(&buf[..n]).to_string();
    let mut parts = req.split_whitespace();
    parts.next()?;
    parts.next().map(|s| s.to_string())
}

fn query_param(target: &str, key: &str) -> String {
    let qs = match target.split_once('?') {
        None => return String::new(),
        Some((_, qs)) => qs,
    };
    for pair in qs.split('&') {
        if let Some((k, v)) = pair.split_once('=') {
            if k == key {
                return v.to_string();
            }
        }
    }
    String::new()
}

fn json_escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

fn send_json(stream: &mut TcpStream, body: String) {
    let res = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\
         Content-Length: {}\r\n\r\n{}",
        body.len(),
        body
    );
    let _ = stream.write_all(res.as_bytes());
}

fn handle(stream: &mut TcpStream, target: &str, store: &Store) {
    let path = target.split('?').next().unwrap_or("");
    let biz_no = query_param(target, "bizNo");

    if path == "/api/order/callback" {
        let mut status = query_param(target, "status");
        if status.is_empty() {
            status = "SUCCESS".to_string();
        }
        let r = store.handle_callback(&biz_no, &status);
        send_json(stream, format!(r#"{{"ok":true,"data":"{}"}}"#, json_escape(&r)));
    } else if path == "/api/order/query" {
        match store.query_order(&biz_no) {
            None => send_json(stream, r#"{"ok":true,"data":"NOT_FOUND"}"#.to_string()),
            Some(o) => send_json(
                stream,
                format!(
                    r#"{{"ok":true,"data":{{"bizNo":"{}","amount":{},"paidAmount":{},"status":"{}","remark":"{}"}}}}"#,
                    json_escape(&o.biz_no), o.amount, o.paid_amount,
                    json_escape(&o.status), json_escape(&o.remark)
                ),
            ),
        }
    } else if path == "/api/order/refund" {
        let raw = query_param(target, "amount");
        let amount = if raw.is_empty() {
            0
        } else {
            // 金额不接受符号，解析失败（含溢出）直接拒绝，不绕回负数
            match raw.parse::<u32>() {
                Err(_) => {
                    send_json(stream, r#"{"ok":true,"data":"BAD_AMOUNT"}"#.to_string());
                    return;
                }
                Ok(v) => v as i64,
            }
        };
        let r = store.refund(&biz_no, amount);
        send_json(stream, format!(r#"{{"ok":true,"data":"{}"}}"#, json_escape(&r)));
    } else {
        send_json(stream, r#"{"ok":false,"data":"NO_ROUTE"}"#.to_string());
    }
}

fn main() {
    let mut port = 18050u16;
    for a in std::env::args() {
        if let Some(v) = a.strip_prefix("-addr=:") {
            port = v.parse().unwrap_or(port);
        }
    }

    // 先绑定监听成功再打就绪日志：反过来的话端口被占用时日志已经说「started」，
    // 等就绪的脚本会判成启动成功，真正的失败要等到后面某个用例莫名其妙地失败才暴露
    let listener = match TcpListener::bind(("0.0.0.0", port)) {
        Err(e) => {
            eprintln!("demo-service-rust listen :{} failed: {}", port, e);
            std::process::exit(1);
        }
        Ok(l) => l,
    };
    println!("demo-service-rust started on :{}", port);

    let store = Store::new();
    for stream in listener.incoming() {
        let mut stream = match stream {
            Err(_) => continue,
            Ok(s) => s,
        };
        if let Some(target) = read_target(&mut stream) {
            handle(&mut stream, &target, &store);
        }
    }
}
