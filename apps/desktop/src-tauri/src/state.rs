use std::{
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
};

use crate::{
    device_config::DeviceConfig,
    file_transfer::FileTransferManager,
    preferences::AppPreferences,
    receiver::{ReceiverHandle, ReceiverStatus},
    settings::ReceiverSettings,
};
use crate::input::InputController;

pub(crate) struct AppState {
    pub receiver: Mutex<Option<ReceiverHandle>>,
    pub settings: Arc<Mutex<ReceiverSettings>>,
    pub preferences: Arc<Mutex<AppPreferences>>,
    pub file_transfer: Arc<FileTransferManager>,
    pub device: Arc<Mutex<DeviceConfig>>,
    pub device_config_path: PathBuf,
    pub preferences_path: PathBuf,
    pub should_exit: AtomicBool,
    pub input_controller: Arc<Mutex<InputController>>,
}

impl AppState {
    pub fn receiver_running(&self) -> bool {
        self.receiver
            .lock()
            .unwrap()
            .as_ref()
            .map(|handle| handle.snapshot().running)
            .unwrap_or(false)
    }

    pub fn receiver_snapshot(&self) -> ReceiverStatus {
        self.receiver
            .lock()
            .unwrap()
            .as_ref()
            .map(|handle| handle.snapshot())
            .unwrap_or_default()
    }

    pub fn stop_receiver(&self) {
        let mut receiver = self.receiver.lock().unwrap();
        if let Some(handle) = receiver.take() {
            handle.stop();
        }
    }

    pub fn config_dir(&self) -> PathBuf {
        self.device_config_path
            .parent()
            .map(PathBuf::from)
            .unwrap_or_else(|| self.device_config_path.clone())
    }

    pub fn request_exit(&self) {
        self.should_exit.store(true, Ordering::SeqCst);
    }

    pub fn should_exit(&self) -> bool {
        self.should_exit.load(Ordering::SeqCst)
    }
}
