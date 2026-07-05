# 故障排查

## Android 搜不到接收端

1. 确认桌面端已点击“启动接收”
2. 确认手机和电脑在同一 Wi‑Fi
3. 尝试手动输入电脑 IP
4. Windows 检查防火墙是否拦截 UDP 45456
5. 某些路由器会阻止广播，改用手动 IP 或二维码

## 能连接但鼠标不动

1. macOS：检查辅助功能权限
2. Windows：以普通用户运行即可，无需管理员
3. 确认桌面端“输入统计”中移动包数在增加
4. 重启桌面接收服务

## 延迟高、不跟手

1. 切换到 5GHz Wi‑Fi
2. 关闭 VPN / Mihomo 等代理
3. 调低桌面端加速度，适当提高灵敏度
4. 观察 RTT 是否 > 30ms

## Android 安装失败

1. 确认 JDK 17 和 Android SDK 已安装
2. 运行 `gradle wrapper` 生成 wrapper
3. 检查 `local.properties` 中 `sdk.dir` 是否指向 Android SDK

## 桌面端打包失败

### Windows

- 安装 Rust 和 WebView2
- 安装 Visual Studio Build Tools（C++ 桌面开发）

### macOS

- 必须在 macOS 上构建 `.dmg`
- 配置代码签名（可选，用于分发）

## 端口被占用

如果 45454/45455/45456 被占用，需结束旧进程后重启接收端。

```powershell
Get-NetTCPConnection -LocalPort 45455 -ErrorAction SilentlyContinue
```
