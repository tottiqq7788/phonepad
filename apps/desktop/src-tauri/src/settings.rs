use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiverSettings {
    pub sensitivity: f64,
    pub acceleration: f64,
    pub scroll_sensitivity: f64,
}

impl Default for ReceiverSettings {
    fn default() -> Self {
        Self {
            sensitivity: 1.0,
            acceleration: 0.18,
            scroll_sensitivity: 1.0,
        }
    }
}
