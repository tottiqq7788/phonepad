use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiverSettings {
    pub sensitivity: f64,
    pub acceleration: f64,
    pub scroll_sensitivity: f64,
    #[serde(default = "default_true")]
    pub gesture_enabled: bool,
    #[serde(default = "default_false")]
    pub pinch_zoom_enabled: bool,
    #[serde(default = "default_gesture_sensitivity")]
    pub gesture_sensitivity: f64,
}

fn default_true() -> bool {
    true
}

fn default_false() -> bool {
    false
}

fn default_gesture_sensitivity() -> f64 {
    1.0
}

impl Default for ReceiverSettings {
    fn default() -> Self {
        Self {
            sensitivity: 1.0,
            acceleration: 0.18,
            scroll_sensitivity: 1.0,
            gesture_enabled: true,
            pinch_zoom_enabled: false,
            gesture_sensitivity: 1.0,
        }
    }
}
