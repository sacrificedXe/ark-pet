# 初雪桌宠 · 版本 API

单文件 Go 服务，部署在宿主机 `39.104.27.214:9102`，与 WS(9100)/文件服务(9101) 分开。

## 接口

```
GET /api/version
{
  "version": "0.3.0",
  "url": "http://39.104.27.214:9101/download/ark-pet.apk",
  "note": "新增气泡输入框、角色树、行为开关、自动更新",
  "force": false
}
```

- `version` 必须严格大于桌面端 versionName 才触发更新
- `force=true`：启动即强制更新（桌宠端会弹不可取消对话框）
- `url` 直链 APK；桌宠端下载后走系统安装器（FileProvider 已配）

## 部署步骤

```bash
# 1. 编译（需要 Go 1.21+）
cd server/version-api && go build -o version_api .

# 2. 以 systemd 托管
sudo tee /etc/systemd/system/arkpet-version-api.service <<'EOF'
[Unit]
Description=ArkPet Version API
After=network.target

[Service]
ExecStart=/usr/local/bin/version_api
Restart=always
Environment=PORT=9102
Environment=APK_DIR=/home/admin/arkpet_apk
Environment=TOKEN=your_admin_token_here

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl enable --now arkpet-version-api

# 3. CI 发版时更新 APK 和版本号（见 .github/workflows/publish_version.yml）
```

## CI 配套 workflow

`.github/workflows/publish_version.yml` 在 debug 包成功后：
1. `scp app/build/outputs/apk/debug/app-debug.apk admin@39.104.27.214:/home/admin/arkpet_apk/ark-pet.apk`
2. 更新 `server/version-api/version.json` 并提交（版本由 CI 环境变量注入 `VERSION_NAME`）
3. 触发 reload

## 文件上传约定

- APK 目录：`/home/admin/arkpet_apk/ark-pet.apk`（CI 每次发版覆盖）
- 服务以静态文件吐该路径
