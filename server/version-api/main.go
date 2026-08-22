package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
)

// 版本数据。CI 发版时写入 version.json。
type VersionInfo struct {
	Version string `json:"version"`
	URL     string `json:"url"`
	Note    string `json:"note"`
	Force   bool   `json:"force,omitempty"`
}

var (
	version VersionInfo
	token   string
)

func main() {
	port := flag.Int("port", 9102, "listen port")
	tokenEnv := os.Getenv("TOKEN")
	flag.Parse()
	token = tokenEnv

	data, err := os.ReadFile("version.json")
	if err != nil {
		log.Printf("[WARN] version.json 未找到，使用默认值")
		version = VersionInfo{Version: "0.0.0", URL: "/", Note: "未配置"}
	} else {
		if err = json.Unmarshal(data, &version); err != nil {
			log.Fatalf("version.json 解析失败: %v", err)
		}
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/api/version", withToken(hVersion))
	apkDir := os.Getenv("APK_DIR")
	if apkDir == "" {
		apkDir = "/home/admin/arkpet_apk"
	}
	mux.Handle("/download/", http.StripPrefix("/download/",
		http.FileServer(http.Dir(apkDir))))

	log.Printf("ark-pet version api listening on :%d (version=%s)", *port, version.Version)
	log.Fatal(http.ListenAndServe(fmt.Sprintf(":%d", *port), mux))
}

func withToken(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if token != "" && r.Header.Get("Authorization") != "Bearer "+token {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		next(w, r)
	}
}

func hVersion(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(version); err != nil {
		log.Printf("encode version fail: %v", err)
	}
}
