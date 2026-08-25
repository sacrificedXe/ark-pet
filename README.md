# arknights-pet（明日方舟桌宠）

Android 9.0+ 悬浮窗桌宠，明日方舟基建小人动画（云迹等皮肤，Spine/WebP 序列帧），双击桌宠弹出会话框，与 AstroBot（QQ 机器人，可部署于阿里云）通过 WebSocket 联动；内置 MCP 工具引擎（应用/相机/文件/屏幕/系统/触摸）与 Shizuku/无障碍双通道，支持版本热更新（`server/version-api`）。

## 主要特性

- 悬浮窗桌宠：拖拽、双击会话框、长按切换皮肤、角色动作状态机（待机/工作/睡眠/互动等，WebP 动画）
- 与 AstroBot 联通：`app/src/main/java/com/arkpet/net/WsClient.kt`（默认 9100 端口），消息/命令双向
- MCP 工具引擎：`app/src/main/java/com/arkpet/mcp/tools/`（App/Camera/File/Screen/System/Touch）
- 高级权限通道：Shizuku（`shizuku/ShizukuShell.kt`）+ 无障碍（`accessibility/PetAccessibilityService.kt`）
- 自动更新：`updater/`（BootReceiver / UpdateChecker / UpdateWorker）+ `server/version-api`（Go）
- MAA 明日方舟助手桥接：`maa/MaaBridge.kt`

## 云编译

推送到 `main` 分支后由 GitHub Actions 自动编译 Debug APK：

- 工作流：`.github/workflows/build-apk.yml`
- 产物：`app/build/outputs/apk/debug/arknights-pet-debug.apk`（Artifact：`arknights-pet-debug-apk`）

## 本地构建

```bash
./gradlew assembleDebug
```

- 最低系统：Android 9.0（minSdk 28）
- applicationId / namespace：`com.arkpet`（与既有更新通道、安装身份保持一致，未随改名变更）
- 当前版本：0.4.6（versionCode 11）

## 目录

```
app/src/main/java/com/arkpet/
├── core/RoleManager.kt        # 角色/皮肤管理
├── net/WsClient.kt            # AstroBot WebSocket 客户端
├── mcp/tools/                 # MCP 工具引擎
├── overlay/PetOverlayService.kt
├── maa/MaaBridge.kt           # MAA 桥接
├── shizuku/ShizukuShell.kt
├── updater/                   # 热更新
└── util/                      # 日志/诊断
server/version-api/            # 版本更新服务（Go）
scripts/                       # 动画资源处理脚本
```

## 声明

动画/美术资源版权归鹰角网络及相关权利人所有，本项目仅作个人学习与自用，不内置任何付费内容分发。
