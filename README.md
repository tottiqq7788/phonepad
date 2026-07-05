# PhonePad

将 Android 手机变成 Windows / macOS 的低延迟触控板。

## 架构

- `apps/desktop`：Tauri 桌面接收端（Windows `.exe` / macOS `.dmg`）
- `apps/android`：Android 输入端（`.apk`）
- `crates/protocol`：共享二进制协议

通信方式：

- UDP 45454：高频输入包（移动、滚动、点击）
- TCP 45455：控制与状态查询
- UDP 45456：局域网发现

## 第一版手势

| 手势 | 行为 |
|------|------|
| 单指滑动 | 移动鼠标 |
| 单指单击 | 左键 |
| 双指单击 | 右键 |
| 双指滑动 | 滚动 |

## 快速开始

### 桌面端

前置依赖：Node.js 20+、Rust stable、Tauri 依赖（Windows 需 WebView2，macOS 需 Xcode CLT）。

```powershell
cd E:\totti\phonepad\phonepad
npm install
npm run desktop:dev
```

打包：

```powershell
npm run desktop:build
```

产物位于 `apps/desktop/src-tauri/target/release/bundle/`。

### Android 端

前置依赖：JDK 17、Android SDK、Gradle 8.10+。

```powershell
cd E:\totti\phonepad\phonepad\apps\android
gradle wrapper
.\gradlew.bat assembleDebug
```

APK 位于 `apps/android/app/build/outputs/apk/debug/`。

## 使用步骤

1. 在 Windows 或 macOS 上启动 **PhonePad Receiver**，点击“启动接收”。
2. 手机和电脑连接同一 Wi‑Fi（推荐 5GHz）。
3. 打开 Android App，点击“搜索接收端”或手动输入电脑 IP。
4. 连接成功后进入全屏触控板。

## 文档

- [安装说明](docs/install.md)
- [协议说明](docs/protocol.md)
- [延迟测试](docs/latency-test.md)
- [故障排查](docs/troubleshooting.md)

## 与 mobile-touchpad 的差异

- 原生 Android App，不再依赖浏览器 + Hammer.js + Socket.IO
- UDP 增量包，不排队重传
- Rust + enigo 控制鼠标，跨平台打包更稳定
- 震动反馈模拟点击确认感
- 局域网发现 + 二维码配对
