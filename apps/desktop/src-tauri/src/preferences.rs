use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppPreferences {
    pub download_dir: Option<String>,
    pub screenshot_dir: Option<String>,
    #[serde(default = "default_open_folder")]
    pub open_folder_after_transfer: bool,
}

fn default_open_folder() -> bool {
    true
}

impl Default for AppPreferences {
    fn default() -> Self {
        Self {
            download_dir: None,
            screenshot_dir: None,
            open_folder_after_transfer: true,
        }
    }
}

pub struct LoadedPreferences {
    pub preferences: AppPreferences,
    pub should_persist: bool,
}

impl AppPreferences {
    pub fn load(path: &Path) -> LoadedPreferences {
        if !path.exists() {
            return LoadedPreferences {
                preferences: Self::default(),
                should_persist: true,
            };
        }

        match std::fs::read_to_string(path) {
            Ok(raw) => match serde_json::from_str::<AppPreferences>(&raw) {
                Ok(preferences) => LoadedPreferences {
                    preferences,
                    should_persist: false,
                },
                Err(err) => {
                    eprintln!("failed to parse preferences.json: {err}");
                    LoadedPreferences {
                        preferences: Self::default(),
                        should_persist: true,
                    }
                }
            },
            Err(err) => {
                eprintln!("failed to read preferences.json: {err}");
                LoadedPreferences {
                    preferences: Self::default(),
                    should_persist: true,
                }
            }
        }
    }

    pub fn save(&self, path: &Path) -> Result<(), String> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|err| err.to_string())?;
        }
        let payload = serde_json::to_string_pretty(self).map_err(|err| err.to_string())?;
        std::fs::write(path, payload).map_err(|err| err.to_string())
    }

    pub fn resolved_download_dir(&self, fallback: &Path) -> PathBuf {
        self.download_dir
            .as_ref()
            .filter(|value| !value.trim().is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| fallback.to_path_buf())
    }

    pub fn resolved_screenshot_dir(&self, fallback: &Path) -> PathBuf {
        self.screenshot_dir
            .as_ref()
            .filter(|value| !value.trim().is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| fallback.to_path_buf())
    }
}
