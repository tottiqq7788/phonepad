# 任务：PhonePad 上传 GitHub Release

## 项目信息

| 项 | 值 |
|---|---|
| 项目名称 | PhonePad |
| 本地路径 | `E:\totti\phonepad\phonepad` |
| 当前版本 | 见 `package.json`（如 `0.5.0`） |
| GitHub 仓库 | https://github.com/tottiqq7788/phonepad |
| Release 页面 | https://github.com/tottiqq7788/phonepad/releases |

## 认证方式

Release 上传使用 **GitHub CLI（`gh`）**，需已登录且具备 `repo` 权限。

```powershell
gh auth status
```

若未登录：

```powershell
gh auth login
```

---

请将当前版本的 **Windows 安装包** 与 **Android APK** 上传到 GitHub Release。**macOS 包默认不上传**（除非用户明确要求）。

> 前置条件：对应版本的 Git 标签（如 `v0.5.0`）已推送到 `origin`。若尚未提交/打标签，请先执行「提交git」快捷任务。

## 必须严格执行以下全部流程

### 1. 进入项目目录并读取版本号

```powershell
cd E:\totti\phonepad\phonepad
$version = (Get-Content package.json | ConvertFrom-Json).version
$tag = "v$version"
Write-Host "当前版本: $version  标签: $tag"
```

> Git 仓库根目录是 `E:\totti\phonepad\phonepad`，不是上级 `E:\totti\phonepad`。

### 2. 确认远程标签存在

```powershell
git fetch origin --tags
git tag -l $tag
```

若标签不存在，停止并提示用户先完成「提交git」流程（commit + tag + push）。

### 3. 确认或构建安装包

检查构建产物是否存在；**不存在则先构建**（与「打包安装」任务相同）：

| 平台 | 路径 |
|------|------|
| Windows NSIS | `target\release\bundle\nsis\PhonePad Receiver_<版本>_x64-setup.exe` |
| Android Debug APK | `apps\android\app\build\outputs\apk\debug\app-debug.apk` |

```powershell
$setup = "E:\totti\phonepad\phonepad\target\release\bundle\nsis\PhonePad Receiver_$version`_x64-setup.exe"
$apkSrc = "E:\totti\phonepad\phonepad\apps\android\app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $setup)) {
    Write-Host "Windows 安装包不存在，开始构建..."
    npm run lint
    npm run desktop:build
}

if (-not (Test-Path $apkSrc)) {
    Write-Host "Android APK 不存在，开始构建..."
    npm run android:assemble
}

if (-not (Test-Path $setup)) { throw "Windows 安装包仍不存在: $setup" }
if (-not (Test-Path $apkSrc)) { throw "Android APK 仍不存在: $apkSrc" }
```

**注意**：必须使用带版本号的 setup 路径（如 `PhonePad Receiver_0.5.0_x64-setup.exe`），**不要**用 `Get-ChildItem ... | Select-Object -First 1` 取目录中第一个文件（会误选旧版本）。

### 4. 准备 Release 资产文件名

将 Android APK 复制为 Release 友好名称（便于用户识别）：

```powershell
$apk = "E:\totti\phonepad\phonepad\target\release\PhonePad-$version-android-debug.apk"
New-Item -ItemType Directory -Force -Path (Split-Path $apk) | Out-Null
Copy-Item $apkSrc $apk -Force
```

### 5. 创建或更新 GitHub Release

先检查 Release 是否已存在：

```powershell
gh release view $tag 2>$null
$releaseExists = $LASTEXITCODE -eq 0
```

**Release 不存在** — 创建并上传：

```powershell
$notes = @"
## PhonePad $version

### 安装说明
- **Windows**：运行 NSIS 安装包（``PhonePad Receiver_$version`_x64-setup.exe``）
- **Android**：安装 ``PhonePad-$version-android-debug.apk``（Debug 包，包名 ``cn.phonepad.debug``）

### 更新内容
（从最近一次 ``git log -1`` 或提交说明中摘录主要变更）
"@

# 将 git 最近一次 tag 对应提交说明写入 notes（可选）
$lastMsg = git log -1 --format=%B
if ($lastMsg) { $notes = $lastMsg }

gh release create $tag `
  --title $tag `
  --notes $notes `
  "$setup" `
  "$apk"
```

**Release 已存在** — 仅补传/覆盖资产：

```powershell
gh release upload $tag "$setup" "$apk" --clobber
```

若需更新 Release 说明：

```powershell
gh release edit $tag --notes $notes
```

### 6. 验证上传结果

```powershell
gh release view $tag
gh release view $tag --web
```

必须确认资产列表中包含：

- Windows：`PhonePad.Receiver_<版本>_x64-setup.exe`（GitHub 可能将空格显示为 `.`）
- Android：`PhonePad-<版本>-android-debug.apk`

### 7. 完成后展示结果

必须包含：

- 版本号与标签（如 `v0.5.0`）
- Release URL
- 已上传的文件名与本地路径
- 本次是「新建 Release」还是「补传资产」
- 若失败，说明原因（标签缺失、gh 未登录、构建失败等）

---

## 默认上传范围

| 平台 | 是否上传 | 说明 |
|------|----------|------|
| Windows（NSIS） | **是** | 桌面接收端安装包 |
| Android（Debug APK） | **是** | 开发/测试用，包名 `cn.phonepad.debug` |
| macOS（.dmg / .app） | **否** | 用户未要求时不构建、不上传 |

若用户要求上传 **Android Release 签名包**，先执行 `npm run android:assemble:release`，再上传 `apps\android\app\build\outputs\apk\release\` 下对应 APK。

---

## 常用命令速查

| 操作 | 命令 |
|------|------|
| 查看 gh 登录状态 | `gh auth status` |
| 查看 Release | `gh release view v0.5.0` |
| 打开 Release 页 | `gh release view v0.5.0 --web` |
| 创建 Release | `gh release create v0.5.0 --title "v0.5.0" --notes "..." file1 file2` |
| 补传资产 | `gh release upload v0.5.0 file1 file2 --clobber` |
| 删除 Release（慎用） | `gh release delete v0.5.0` |
| 列出所有 Release | `gh release list` |

---

## 输出要求（给 AI / 自动化任务）

- 全程中文
- 不提问、不等待确认
- 直接执行上传流程
- 结构清晰，结果一目了然
- 默认只上传 Windows + Android，不上传 macOS

执行人：拖地
