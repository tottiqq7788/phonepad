# 删除旧的 totti-pad 副本（需先关闭占用该目录的程序，如 Cursor）
$target = "E:\totti\phonepad\totti-pad"

if (-not (Test-Path $target)) {
    Write-Host "totti-pad 已不存在，无需删除。"
    exit 0
}

Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

try {
    Remove-Item -Recurse -Force $target -ErrorAction Stop
    Write-Host "已成功删除: $target"
} catch {
    Write-Host "删除失败: $_"
    Write-Host "请关闭 Cursor 或其他占用 E:\totti\phonepad\totti-pad 的程序后重试。"
    exit 1
}
