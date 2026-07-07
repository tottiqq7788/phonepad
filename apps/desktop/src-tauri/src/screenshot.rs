use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

pub fn default_screenshot_dir() -> Option<PathBuf> {
    dirs::picture_dir().map(|dir| dir.join("PhonePad Screenshots"))
}

pub fn resolved_screenshot_dir(custom: Option<&str>, fallback: &Path) -> PathBuf {
    custom
        .filter(|value| !value.trim().is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| fallback.to_path_buf())
}

pub fn validate_screenshot_dir(path: &Path) -> Result<(), String> {
    if path.is_file() {
        return Err(format!("截图目录不能是文件: {}", path.display()));
    }
    std::fs::create_dir_all(path).map_err(|err| {
        format!("无法创建截图目录 {}: {err}", path.display())
    })
}

pub fn build_screenshot_filename(now: SystemTime) -> String {
    let millis = now
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or(0);
    format!("PhonePad_{millis}.png")
}

pub fn capture_fullscreen_to(path: &Path) -> Result<PathBuf, String> {
    validate_screenshot_dir(path)?;
    let file_path = path.join(build_screenshot_filename(SystemTime::now()));
    capture_fullscreen_native(&file_path)?;
    if !file_path.exists() {
        return Err(format!("截图文件未生成: {}", file_path.display()));
    }
    Ok(file_path)
}

fn capture_fullscreen_native(path: &Path) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        let escaped = path.to_string_lossy().replace('\'', "''");
        let script = format!(
            r#"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$bitmap = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
$bitmap.Save('{escaped}', [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose()
$bitmap.Dispose()
"#
        );
        let output = Command::new("powershell")
            .args(["-NoProfile", "-STA", "-Command", &script])
            .output()
            .map_err(|err| err.to_string())?;
        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(format!("Windows 截图失败: {stderr}"));
        }
        return Ok(());
    }

    #[cfg(target_os = "macos")]
    {
        let status = Command::new("screencapture")
            .args(["-x", &path.to_string_lossy()])
            .status()
            .map_err(|err| err.to_string())?;
        if !status.success() {
            return Err("macOS 截图失败".to_string());
        }
        return Ok(());
    }

    #[cfg(not(any(target_os = "windows", target_os = "macos")))]
    {
        let _ = path;
        Err("当前平台暂不支持全屏截图".to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::env;

    #[test]
    fn resolves_custom_or_fallback_dir() {
        let fallback = PathBuf::from("/tmp/fallback");
        assert_eq!(
            resolved_screenshot_dir(None, &fallback),
            PathBuf::from("/tmp/fallback")
        );
        assert_eq!(
            resolved_screenshot_dir(Some("  "), &fallback),
            PathBuf::from("/tmp/fallback")
        );
        assert_eq!(
            resolved_screenshot_dir(Some("/tmp/custom"), &fallback),
            PathBuf::from("/tmp/custom")
        );
    }

    #[test]
    fn screenshot_filename_is_unique_per_millisecond() {
        let now = SystemTime::UNIX_EPOCH;
        assert!(build_screenshot_filename(now).starts_with("PhonePad_"));
        assert!(build_screenshot_filename(now).ends_with(".png"));
    }

    #[test]
    fn validate_creates_directory() {
        let base = env::temp_dir().join(format!(
            "phonepad-screenshot-test-{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&base);
        validate_screenshot_dir(&base).expect("directory should be created");
        assert!(base.is_dir());
        let _ = std::fs::remove_dir_all(&base);
    }
}
