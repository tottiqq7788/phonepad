# 协议说明

PhonePad 使用 **UDP 二进制包** 传输高频触控输入，使用 **TCP JSON** 传输控制与文本输入。

## 端口

| 端口 | 协议 | 用途 |
|------|------|------|
| 45454 | UDP | 输入包（鼠标移动/滚动/点击） |
| 45455 | TCP | 控制、状态查询、文本输入、文件传输协商 |
| 45456 | UDP | 局域网发现（可选） |
| 45457 | TCP | 文件二进制上传 |

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

键盘模式支持 `event` 字段，用于修饰键按住/松开：

```json
{"type":"key","deviceId":"...","secret":"...","action":"ctrl","event":"down"}
{"type":"key","deviceId":"...","secret":"...","action":"c","event":"click"}
{"type":"key","deviceId":"...","secret":"...","action":"ctrl","event":"up"}
```

`event` 取值：

| event | 说明 |
|-------|------|
| `click` | 单击（默认，省略 `event` 时同此） |
| `down` / `press` | 按下 |
| `up` / `release` | 松开 |

`repeat` 仅对 `click` 有效。

支持的 `action`（输入模式）：

| action | 说明 |
|--------|------|
| `backspace` | 退格删除光标前字符 |
| `delete` | 删除光标后字符 |
| `cursor_left` | 光标左移 |
| `cursor_right` | 光标右移 |

键盘模式额外支持：`a`–`z`、`0`–`9`、符号键、`enter`、`tab`、`space`、`esc`、方向键、`home`/`end`/`page_up`/`page_down`、`f1`–`f12`、`ctrl`/`shift`/`alt`/`meta`（Win）等。

响应格式与文本输入相同（`{"ok":true}` / `{"ok":false,"error":"..."}`）。

### 文件传输

输入模式支持从 Android 选择图片或文件发送到桌面端。流程为 **控制协商 + 二进制数据通道**：

1. `fileBegin`（TCP 45455）申请上传会话
2. 二进制上传（TCP 45457）
3. `fileCommit`（TCP 45455）完成落盘

#### fileBegin

请求：

```json
{
  "type": "fileBegin",
  "deviceId": "...",
  "secret": "...",
  "transferId": "550e8400-e29b-41d4-a716-446655440000",
  "fileName": "photo.jpg",
  "fileSize": 1234567,
  "mimeType": "image/jpeg",
  "batchId": "550e8400-e29b-41d4-a716-446655440001",
  "fileIndex": 1,
  "totalFiles": 3
}
```

响应：

```json
{
  "ok": true,
  "uploadPort": 45457,
  "token": "7123182144640608305"
}
```

`token` 为十进制字符串，值为 `fnv1a64(secret + transferId_utf8)`。必须使用字符串传输，避免 JSON 数字精度丢失。

#### 二进制上传（TCP 45457）

连接后先发送 **47 字节** 文件头：

| 偏移 | 长度 | 字段 |
|------|------|------|
| 0 | 2 | Magic `PF` |
| 2 | 1 | Version = 1 |
| 3 | 8 | token (u64 LE) |
| 11 | 36 | transferId（UUID 字符串，不足补 0） |

随后发送文件原始字节，长度为 `fileBegin` 中的 `fileSize`。

桌面端响应一行 JSON：

```json
{"ok":true}
```

#### fileCommit

请求：

```json
{
  "type": "fileCommit",
  "deviceId": "...",
  "secret": "...",
  "transferId": "550e8400-e29b-41d4-a716-446655440000"
}
```

响应：

```json
{
  "ok": true,
  "savedPath": "C:\\Users\\你\\Documents\\photo.jpg"
}
```

同一 `batchId` 的最后一个文件提交成功后，若桌面端开启“传输完成后自动打开文件夹”，将打开保存目录（Windows 资源管理器 / macOS 访达）。

#### fileCancel

请求：

```json
{
  "type": "fileCancel",
  "deviceId": "...",
  "secret": "...",
  "transferId": "..."
}
```

用于取消尚未完成的传输会话。

限制：

- 单文件最大 **512 MB**
- 文件名会做路径净化，禁止 `..` 和路径分隔符
- 重名文件自动追加 `(1)` 等后缀

默认保存目录为系统“文档”目录，可在桌面端控制台配置。

## 配对 URL

扫码配对格式（推荐，不含 `host`）：

```
phonepad://pair?tcp=45455&udp=45454&discovery=45456&id=<deviceId>&name=<deviceName>&secret=<pairingSecret>
```

Android 扫码后通过局域网 **Discovery V2** 动态获取桌面端可达 IP，再保存设备信息并连接。

### 旧格式兼容

旧版二维码包含 `host`，Android 可直接连接，无需发现：

```
phonepad://pair?host=192.168.1.12&tcp=45455&udp=45454&id=<deviceId>&name=<deviceName>&secret=<pairingSecret>
```

| 参数 | 必填 | 说明 |
|------|------|------|
| `id` | 是 | 桌面端设备 ID |
| `secret` | 是 | 配对密钥 |
| `name` | 否 | 设备显示名 |
| `tcp` | 否 | TCP 控制端口，默认 45455 |
| `udp` | 否 | UDP 输入端口，默认 45454 |
| `discovery` | 否 | 发现端口，默认 45456 |
| `host` | 否（旧格式） | 桌面端 IP，有则跳过发现 |

后续 UDP/TCP 请求均使用该 `deviceId` 与 `secret` 认证。

## 发现协议

### Discovery V2（推荐）

Android 向局域网广播 UDP 请求（目标 `255.255.255.255:45456` 及各网卡广播地址）：

```json
{
  "type": "discover",
  "version": 2,
  "deviceId": "...",
  "nonce": 123456,
  "auth": "<fnv1a64(secret + nonce_le_bytes)>"
}
```

桌面端校验 `deviceId` 与 `auth`，回复 JSON（`auth` 使用 `nonce + 1`）：

```json
{
  "type": "discoverResponse",
  "deviceId": "...",
  "name": "PhonePad Receiver",
  "ip": "10.40.184.10",
  "tcpPort": 45455,
  "udpPort": 45454,
  "discoveryPort": 45456,
  "auth": "<fnv1a64(secret + (nonce+1)_le_bytes)>"
}
```

响应中的 `ip` 为**对该 Android 客户端可达**的本机地址（按请求来源 peer 计算）。

Android 校验响应 `deviceId` 与 `auth` 后，使用 `ip` 连接 TCP/UDP。

### Discovery V1（兼容）

Android 向 `255.255.255.255:45456` 发送裸字符串：

```
PHONEPAD_DISCOVER_V1
```

桌面端回复 JSON（无认证）：

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

新 Android 主流程使用 V2 发现；V1 保留供旧客户端兼容。
