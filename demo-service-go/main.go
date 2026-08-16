// Go 被测服务，与 Java 版 demo-service 保持同样的形状：
// 几个带分支的接口，只调用其中一部分时，未走到的分支应当在平台上保持未覆盖。
package main

import (
	"encoding/json"
	"flag"
	"log"
	"net"
	"net/http"
	"strconv"
	"sync"
)

type Order struct {
	BizNo      string `json:"bizNo"`
	Amount     int64  `json:"amount"`
	PaidAmount int64  `json:"paidAmount"`
	Status     string `json:"status"`
	Remark     string `json:"remark"`
}

type Store struct {
	mu   sync.Mutex
	repo map[string]*Order
}

func NewStore() *Store {
	return &Store{repo: map[string]*Order{
		"G1001": {BizNo: "G1001", Amount: 2999, Status: "CREATED"},
		"G1002": {BizNo: "G1002", Amount: 15800, Status: "PAID"},
	}}
}

func isFinalState(o *Order) bool {
	return o.Status == "PAID" || o.Status == "REFUNDED" || o.Status == "EXPIRED"
}

// 接口 A：支付回调
func (s *Store) HandleCallback(bizNo, status string) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	o, ok := s.repo[bizNo]
	if !ok {
		return "ORDER_NOT_FOUND:" + bizNo
	}
	if isFinalState(o) {
		return "DUPLICATE_CALLBACK:" + bizNo
	}
	switch status {
	case "SUCCESS":
		o.Status = "PAID"
		o.PaidAmount = o.Amount
	case "TIMEOUT":
		o.Status = "EXPIRED"
		o.Remark = "stock released"
	case "FAILED":
		o.Status = "FAILED"
		o.Remark = "risk notified"
	default:
		return "UNKNOWN_STATUS:" + status
	}
	return o.Status
}

// 接口 B：订单查询
func (s *Store) QueryOrder(bizNo string) *Order {
	s.mu.Lock()
	defer s.mu.Unlock()
	o, ok := s.repo[bizNo]
	if !ok {
		return nil
	}
	return o
}

// 接口 C：退款
func (s *Store) Refund(bizNo string, amount int64) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	o, ok := s.repo[bizNo]
	if !ok {
		return "ORDER_NOT_FOUND:" + bizNo
	}
	if o.Status != "PAID" {
		return "NOT_REFUNDABLE:" + o.Status
	}
	if amount > o.PaidAmount {
		return "AMOUNT_EXCEEDED"
	}
	o.Status = "REFUNDED"
	return "REFUNDED"
}

func respond(w http.ResponseWriter, data any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "data": data})
}

func main() {
	addr := flag.String("addr", ":18070", "业务端口")
	flag.Parse()

	store := NewStore()
	mux := http.NewServeMux()

	mux.HandleFunc("/api/order/callback", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		status := q.Get("status")
		if status == "" {
			status = "SUCCESS"
		}
		respond(w, store.HandleCallback(q.Get("bizNo"), status))
	})

	mux.HandleFunc("/api/order/query", func(w http.ResponseWriter, r *http.Request) {
		o := store.QueryOrder(r.URL.Query().Get("bizNo"))
		if o == nil {
			respond(w, "NOT_FOUND")
			return
		}
		respond(w, o)
	})

	mux.HandleFunc("/api/order/refund", func(w http.ResponseWriter, r *http.Request) {
		var amount int64
		if v := r.URL.Query().Get("amount"); v != "" {
			// ParseUint 而非 ParseInt：金额不接受符号，且溢出会报错而不是绕回负数
			// —— 手写的逐位累加在超长数字串上会溢出成负值，直接绕过 AMOUNT_EXCEEDED
			n, err := strconv.ParseUint(v, 10, 63)
			if err != nil {
				respond(w, "BAD_AMOUNT")
				return
			}
			amount = int64(n)
		}
		respond(w, store.Refund(r.URL.Query().Get("bizNo"), amount))
	})

	// 先监听成功再打就绪日志。反过来的话端口被占用时日志已经写着「started」，
	// 等就绪的脚本会判成启动成功，真正的失败要等到后面某个用例莫名其妙地失败才暴露
	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		log.Fatalf("demo-service-go listen %s failed: %v", *addr, err)
	}
	log.Printf("demo-service-go started on %s", *addr)
	log.Fatal(http.Serve(ln, mux))
}
