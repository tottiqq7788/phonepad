# 任务：PhonePad Git 提交与推送

## 项目信息

| 项 | 值 |
|---|---|
| 项目名称 | PhonePad |
| 本地路径 | `E:\totti\phonepad\phonepad` |
| 默认分支 | `main` |
| 当前版本 | `0.1.0`（见 `package.json`） |
| 远程名称 | `origin` |
| 远程地址（SSH） | `git@github.com:tottiqq7788/phonepad.git` |
| 远程地址（HTTPS） | `https://github.com/tottiqq7788/phonepad.git` |
| GitHub 仓库页 | https://github.com/tottiqq7788/phonepad |

## 提交人身份（已配置）

| 项 | 值 |
|---|---|
| user.name | 拖地 |
| user.email | 1013525341@qq.com |

验证命令：

```powershell
git config --global user.name
git config --global user.email
```

## 认证方式

本仓库使用 **SSH** 推送（`git@github.com:...`），需确保本机已配置 GitHub SSH 密钥。

```powershell
ssh -T git@github.com
```

若未配置 SSH，可改用 HTTPS 并凭 Git 凭据管理器登录 GitHub。

---

## 必须严格执行以下全部流程

### 1. 进入项目目录

```powershell
cd E:\totti\phonepad\phonepad
```

> Git 仓库根目录是 `E:\totti\phonepad\phonepad`，不是上级 `E:\totti\phonepad`。

### 2. 读取当前版本标签（语义化版本 x.y.z），自动升级版本号

- 小修改 / Bug 修复 → 修订号 +1（如 `0.1.0` → `0.1.1`）
- 新增功能 → 次版本 +1（如 `0.1.0` → `0.2.0`）
- 破坏性更新 → 主版本 +1（如 `0.1.0` → `1.0.0`）

查看现有标签：

```powershell
git tag -l --sort=-v:refname
```

### 3. 检测未提交变更，分类清晰列出

```powershell
git status
git diff --stat
git diff --cached --stat
```

分类说明：

- 新增文件
- 修改文件
- 删除文件
- 代码行数变更统计

**不要提交的内容**（已在 `.gitignore` 中排除）：

- `node_modules/`
- `target/`
- `apps/android/app/build/`
- `apps/desktop/dist/`
- `local.properties`、密钥文件等

### 4. 生成标准 Git 提交信息

格式：

```
vX.Y.Z | 本次更新概要

✅ 新增：
🔧 优化：
🐛 修复：
📝 文档：
🗑️ 删除：
```

### 5. 一键执行：提交 → 拉取 → 推送

```powershell
cd E:\totti\phonepad\phonepad

git add .
git commit -m "v0.1.1 | 本次更新概要"
git tag v0.1.1

git fetch origin
git pull --no-rebase origin main
git push origin main
git push origin --tags
```

首次推送新仓库（仅需一次）：

```powershell
cd E:\totti\phonepad\phonepad
git init
git add .
git commit -m "Initial commit: PhonePad desktop receiver and Android touchpad."
git branch -M main
git remote add origin git@github.com:tottiqq7788/phonepad.git
git push -u origin main
```

### 6. 提交完成后，展示【近期 Git 版本总览】

```powershell
git log --oneline -10
git tag -l --sort=-v:refname | Select-Object -First 10
git log --tags --simplify-by-decoration --pretty="format:%h %ai %d %s" -10
```

要求：

- 列出最近 10 个版本标签或提交
- 显示版本号 + 提交时间 + 提交说明
- 按时间从新到旧排序

---

## 常用命令速查

| 操作 | 命令 |
|------|------|
| 查看状态 | `git status` |
| 查看远程 | `git remote -v` |
| 查看分支 | `git branch -vv` |
| 拉取最新 | `git pull origin main` |
| 推送代码 | `git push origin main` |
| 推送标签 | `git push origin --tags` |
| 撤销暂存 | `git restore --staged .` |

---

## 输出要求（给 AI / 自动化任务）

- 全程中文
- 不提问、不等待确认
- 直接给出最终结果 + 可执行命令
- 结构清晰，一目了然

执行人：拖地
