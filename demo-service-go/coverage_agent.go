//go:build goverage

// 覆盖率探针。只在 `-tags=goverage` 构建时才编译进去，生产构建里这个文件根本不存在。
//
// 它与 main.go 同属 package main，因此无需业务代码 import 或调用任何东西 ——
// init() 会被自动执行。Go 不像 Java 那样能在运行期插桩，必须重新编译；
// 但「不改动任何一行既有源码」这一条是能做到的。
//
// 对齐 Java 侧的形状：
//   GET  /coverage/id        自报构建版本（相当于 JaCoCo 的 sessionid）
//   GET  /coverage/meta      覆盖率元数据（文件名、函数、代码块位置，构建后不变）
//   GET  /coverage/counters  当前计数器快照
//   POST /coverage/clear     清零计数器（要求 -covermode=atomic）
package main

import (
	"log"
	"net/http"
	"os"
	"runtime/coverage"
)

func init() {
	addr := os.Getenv("COVERAGE_ADDR")
	if addr == "" {
		// 与 Java 侧的 address=localhost 对齐，默认只绑回环。
		// ":6400" 会绑到所有网卡，而 /coverage/clear 能清零计数器 ——
		// 等于把「正在录的那个场景」交给同网段的任何人随手作废
		addr = "127.0.0.1:6400"
	}

	mux := http.NewServeMux()

	// 构建版本由启动参数带入，与 Java 侧的 sessionid 同一个约定：
	// 运行中的进程自报它加载的是哪个版本，磁盘上的产物可能早已被重新构建过
	mux.HandleFunc("/coverage/id", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(os.Getenv("COVERAGE_BUILD_ID")))
	})

	mux.HandleFunc("/coverage/meta", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/octet-stream")
		if err := coverage.WriteMeta(w); err != nil {
			// 已经开始写 body 了改不了状态码，但错误必须留在日志里，
			// 否则平台只会拿到一段截断的数据，报出来的是「格式不对」这种无关提示
			log.Printf("coverage: WriteMeta failed: %v", err)
		}
	})

	mux.HandleFunc("/coverage/counters", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/octet-stream")
		if err := coverage.WriteCounters(w); err != nil {
			log.Printf("coverage: WriteCounters failed: %v", err)
		}
	})

	mux.HandleFunc("/coverage/clear", func(w http.ResponseWriter, r *http.Request) {
		if err := coverage.ClearCounters(); err != nil {
			// 最常见的原因是没用 -covermode=atomic。这时清零是静默失效的，
			// 场景归因会把上一轮的覆盖算进来，必须让调用方看到失败
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		_, _ = w.Write([]byte("ok"))
	})

	go func() {
		log.Printf("coverage agent listening on %s (build id: %s)", addr, os.Getenv("COVERAGE_BUILD_ID"))
		if err := http.ListenAndServe(addr, mux); err != nil {
			log.Printf("coverage agent stopped: %v", err)
		}
	}()
}
