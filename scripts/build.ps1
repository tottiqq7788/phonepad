param(
    [ValidateSet("desktop", "android", "all")]
    [string]$Target = "all"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Build-Desktop {
    Write-Host "Building desktop receiver..."
    Set-Location $Root
    npm install
    npm run build --workspace apps/desktop
    npm run tauri:build --workspace apps/desktop
    Write-Host "Desktop artifacts: apps/desktop/src-tauri/target/release/bundle/"
}

function Build-Android {
    Write-Host "Building Android APK..."
    Set-Location (Join-Path $Root "apps/android")
    if (-not (Test-Path ".\gradlew.bat")) {
        gradle wrapper
    }
    .\gradlew.bat assembleDebug
    Write-Host "Android APK: apps/android/app/build/outputs/apk/debug/"
}

switch ($Target) {
    "desktop" { Build-Desktop }
    "android" { Build-Android }
    "all" {
        Build-Desktop
        Build-Android
    }
}
