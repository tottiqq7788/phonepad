# 安装说明

## 桌面接收端（Windows / macOS）

### Windows

1. 安装 [Node.js 20+](https://nodejs.org/)
2. 安装 [Rust](https://www.rust-lang.org/tools/install)
3. 安装 [WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/)
4. 克隆项目并安装依赖：

```powershell
cd E:\totti\phonepad\phonepad
npm install
```

5. 开发运行：

```powershell
npm run desktop:dev
```

6. 打包 `.exe`：

```powershell
npm run desktop:build
```

安装包输出：`apps/desktop/src-tauri/target/release/bundle/nsis/`。

首次运行时请允许防火墙放行：

- UDP `45454`
- TCP `45455`
- UDP `45456`

### macOS

1. 安装 Node.js、Rust、Xcode Command Line Tools
2. 安装依赖并运行：

```bash
cd phonepad
npm install
npm run desktop:dev
```

3. 打包 `.dmg`：

```bash
npm run desktop:build
```

4. 在 **系统设置 → 隐私与安全性 → 辅助功能** 中允许 PhonePad Receiver 控制电脑。

## Android 输入端

### 开发环境

- JDK 17
- Android SDK（compileSdk 35）
- Gradle 8.10+

### 构建 APK

```powershell
cd apps/android
gradle wrapper
.\gradlew.bat assembleDebug
```

Release 包：

```powershell
.\gradlew.bat assembleRelease
```

### 安装到手机

将 `app/build/outputs/apk/debug/app-debug.apk` 传到手机安装，或通过 USB：

```powershell
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 网络要求

- 手机和电脑必须在同一局域网
- 推荐使用 5GHz Wi‑Fi
- 避免 VPN / 代理劫持局域网流量
