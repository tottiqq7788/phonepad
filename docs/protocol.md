# 协议说明

PhonePad 使用固定 24 字节二进制包，避免 JSON / WebSocket 开销。

## 端口

| 端口 | 协议 | 用途 |
|------|------|------|
| 45454 | UDP | 输入包 |
| 45455 | TCP | 控制与状态 |
| 45456 | UDP | 局域网发现 |

## 包格式（Little Endian）

| 偏移 | 长度 | 字段 |
|------|------|------|
| 0 | 2 | Magic `TP` |
| 2 | 1 | Version = 1 |
| 3 | 1 | Kind |
| 4 | 4 | Sequence |
| 8 | 8 | Timestamp (micros) |
| 16 | 2 | X (dx) |
| 18 | 2 | Y (dy) |
| 20 | 1 | Button |
| 21 | 1 | Action |
| 22 | 1 | Fingers |
| 23 | 1 | Reserved |

## Kind 类型

| 值 | 名称 | 说明 |
|----|------|------|
| 1 | Move | 单指移动 |
| 2 | Scroll | 双指滚动 |
| 3 | Click | 点击 |
| 4 | Button | 按下/释放 |
| 5 | Ping | 预留 |
| 6 | Pong | 预留 |

## 发现协议

Android 向 `255.255.255.255:45456` 发送：

```
PHONEPAD_DISCOVER_V1
```

桌面端回复 JSON：

```json
{
  "name": "PhonePad Receiver",
  "ip": "192.168.1.12",
  "udpPort": 45454,
  "tcpPort": 45455,
  "discoveryPort": 45456,
  "running": true
}
```

## 控制通道

Android 通过 TCP 连接 `host:45455`，发送 `HELLO\n`，桌面端返回当前 `ReceiverStatus` JSON。

## 配对 URL

```
phonepad://192.168.1.12:45455
```

桌面端 UI 会将其渲染为二维码，供 Android 扫码（后续版本可直接解析）。
