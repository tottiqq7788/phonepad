use std::{
    fs,
    path::PathBuf,
};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceConfig {
    pub device_id: String,
    pub device_name: String,
    pub pairing_secret: String,
}

pub struct LoadResult {
    pub config: DeviceConfig,
    pub should_persist: bool,
}

impl Default for DeviceConfig {
    fn default() -> Self {
        Self {
            device_id: Uuid::new_v4().to_string(),
            device_name: default_device_name(),
            pairing_secret: generate_secret(),
        }
    }
}

impl DeviceConfig {
    pub fn load(path: &PathBuf) -> LoadResult {
        if !path.exists() {
            return LoadResult {
                config: DeviceConfig::default(),
                should_persist: true,
            };
        }

        let raw = match fs::read_to_string(path) {
            Ok(raw) => raw,
            Err(_) => {
                return LoadResult {
                    config: DeviceConfig::default(),
                    should_persist: false,
                };
            }
        };

        match serde_json::from_str::<DeviceConfig>(&raw) {
            Ok(config) => LoadResult {
                config,
                should_persist: false,
            },
            Err(_) => {
                let backup = path.with_extension("json.bak");
                let _ = fs::copy(path, &backup);
                LoadResult {
                    config: DeviceConfig::default(),
                    should_persist: false,
                }
            }
        }
    }

    pub fn save(&self, path: &PathBuf) -> Result<(), String> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|err| err.to_string())?;
        }
        let payload = serde_json::to_string_pretty(self).map_err(|err| err.to_string())?;
        fs::write(path, payload).map_err(|err| err.to_string())
    }

    pub fn update_name(&mut self, name: String) -> Result<(), String> {
        let trimmed = name.trim();
        if trimmed.is_empty() {
            return Err("设备名称不能为空".into());
        }
        if trimmed.chars().count() > 32 {
            return Err("设备名称不能超过 32 个字符".into());
        }
        self.device_name = trimmed.to_string();
        Ok(())
    }

    pub fn validate_secret(&self, device_id: &str, secret: &str) -> bool {
        self.device_id == device_id && self.pairing_secret == secret
    }
}

fn default_device_name() -> String {
    hostname::get()
        .ok()
        .and_then(|name| name.into_string().ok())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "PhonePad Receiver".into())
}

fn generate_secret() -> String {
    Uuid::new_v4().simple().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_path(name: &str) -> PathBuf {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("phonepad-{name}-{stamp}.json"))
    }

    #[test]
    fn load_creates_new_config_when_missing() {
        let path = temp_path("missing");
        let _ = fs::remove_file(&path);
        let result = DeviceConfig::load(&path);
        assert!(result.should_persist);
        assert!(!result.config.device_id.is_empty());
    }

    #[test]
    fn load_does_not_persist_when_corrupted() {
        let path = temp_path("corrupt");
        fs::write(&path, "{not-json").unwrap();
        let result = DeviceConfig::load(&path);
        assert!(!result.should_persist);
        assert!(path.with_extension("json.bak").exists());
        let _ = fs::remove_file(&path);
        let _ = fs::remove_file(path.with_extension("json.bak"));
    }
}
