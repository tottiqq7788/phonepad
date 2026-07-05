# 协议说明

PhonePad 使用 **UDP 二进制包** 传输高频触控输入，使用 **TCP JSON** 传输控制与文本输入。

## 端口

| 端口 | 协议 | 用途 |
|------|------|------|
| 45454 | UDP | 输入包（鼠标移动/滚动/点击） |
| 45455 | TCP | 控制、状态查询、文本输入 |
| 45456 | UDP | 局域网发现（可选） |

## UDP 输入包（Version 2）

固定 **32 字节** 二进制包，Little Endian。

| 偏移 | 长度 | 字段 |
|------|------|------|
| 0 | 2 | Magic `TP` |
| 2 | 1 | Version = 2 |
| 3 | 1 | Kind |
| 4 | 4 | Sequence |
| 8 | 8 | Timestamp (micros) |
| 16 | 2 | X (dx) |
| 18 | 2 | Y (dy) |
| 20 | 1 | Button |
| 21 | 1 | Action |
| 22 | 1 | Fingers |
| 23 | 1 | Reserved |
| 24 | 8 | authToken (FNV-1a64) |

### authToken

```
authToken = fnv1a64(pairing_secret + sequence_le_bytes)
```

只有扫码获取 `secret` 的设备才能发送有效 UDP 输入包。

### Kind 类型

| 值 | 名称 | 说明 |
|----|------|------|
| 1 | Move | 单指移动 |
| 2 | Scroll | 双指滚动 |
| 3 | Click | 点击 |
| 4 | Button | 按下/释放 |
| 5 | Ping | 预留 |
| 6 | Pong | 预留 |

## TCP 控制通道

Android 通过 TCP 连接 `host:45455`，发送一行 JSON（以 `\n` 结尾），桌面端返回 JSON 响应。

所有控制请求必须携带配对信息：

- `deviceId`：桌面端设备 ID
- `secret`：扫码获取的配对密钥

### 状态查询

请求：

```json
{"type":"status","deviceId":"...","secret":"..."}
```

响应：`ReceiverStatus` JSON（含 `running`、`packetsReceived`、`lastRttMs` 等）。

### 文本输入

请求：

```json
{"type":"text","deviceId":"...","secret":"...","content":"你好 world"}
```

响应：

```json
{"ok":true}
```

失败：

```json
{"ok":false,"error":"文本为空"}
```

限制：

- 单条文本最多 **4096 字符**（trim 后）
- 单条文本 UTF-8 不超过 **6000 字节**
- 控制请求体最大 **8192 字节**

桌面端收到合法文本后，会在当前焦点位置通过系统键盘 API 输入文字。

### 按键控制

请求：

```json
{"type":"key","deviceId":"...","secret":"...","action":"backspace"}
```

可选 `repeat` 字段（默认 1，最大 20），用于批量光标移动：

```json
{"type":"key","deviceId":"...","secret":"...","action":"cursor_left","repeat":5}
```

支持的 `action`：

| action | 说明 |
|--------|------|
| `backspace` | 退格删除光标前字符 |
| `delete` | 删除光标后字符 |
| `cursor_left` | 光标左移 |
| `cursor_right` | 光标右移 |

响应格式与文本输入相同（`{"ok":true}` / `{"ok":false,"error":"..."}`）。

### 焦点订阅（自动输入模式）

Android 连接成功后发起持久 TCP 订阅，桌面端在焦点变化时推送状态。

请求：

```json
{"type":"focusSubscribe","deviceId":"...","secret":"..."}
```

响应（单行 JSON）：

```json
{"ok":true}
```

随后桌面端在可编辑焦点状态变化时持续写入一行 JSON：

```json
{"type":"focusState","editable":true,"appName":"Notepad"}
```

| 字段 | 说明 |
|------|------|
| `editable` | 当前焦点是否为可编辑输入控件 |
| `appName` | 可选，前台应用名称 |

Android 收到 `editable=true` 且已连接时自动进入输入模式；焦点离开时不强制退出。macOS 依赖辅助功能权限，检测失败时静默降级为手动进入。

## 配对 URL

扫码配对格式：

```
phonepad://pair?host=192.168.1.12&tcp=45455&udp=45454&id=<deviceId>&name=<deviceName>&secret=<pairingSecret>
```

Android 解析后保存设备信息，后续 UDP/TCP 请求均使用该 `deviceId` 与 `secret` 认证。

## 发现协议（可选）

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
  "running": true,
  "deviceId": "..."
}
```

当前 Android 主流程以扫码配对为准。
