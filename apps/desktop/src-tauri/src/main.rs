mod input;
mod pairing;
mod receiver;
mod settings;

use std::sync::{Arc, Mutex};

use pairing::PairingInfo;
use receiver::{ReceiverHandle, ReceiverStatus};
use settings::ReceiverSettings;
use tauri::{Manager, State};

struct AppState {
    receiver: Mutex<Option<ReceiverHandle>>,
    settings: Arc<Mutex<ReceiverSettings>>,
}

#[tauri::command]
fn start_receiver(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    settings: Option<ReceiverSettings>,
) -> Result<ReceiverStatus, String> {
    if let Some(settings) = settings {
        *state.settings.lock().unwrap() = settings;
    }

    if let Some(handle) = state.receiver.lock().unwrap().as_ref() {
        return Ok(handle.snapshot());
    }

    let handle = receiver::start(app, state.settings.clone())?;
    let snapshot = handle.snapshot();
    *state.receiver.lock().unwrap() = Some(handle);
    Ok(snapshot)
}

#[tauri::command]
fn stop_receiver(state: State<'_, AppState>) -> ReceiverStatus {
    let mut receiver = state.receiver.lock().unwrap();
    if let Some(handle) = receiver.as_ref() {
        handle.stop();
    }
    *receiver = None;
    ReceiverStatus::default()
}

#[tauri::command]
fn receiver_status(state: State<'_, AppState>) -> ReceiverStatus {
    state
        .receiver
        .lock()
        .unwrap()
        .as_ref()
        .map(|handle| handle.snapshot())
        .unwrap_or_default()
}

#[tauri::command]
fn update_settings(state: State<'_, AppState>, settings: ReceiverSettings) -> ReceiverStatus {
    *state.settings.lock().unwrap() = settings;
    receiver_status(state)
}

#[tauri::command]
fn pairing_info(state: State<'_, AppState>) -> PairingInfo {
    let last_client = state
        .receiver
        .lock()
        .unwrap()
        .as_ref()
        .map(|handle| handle.snapshot())
        .and_then(|status| status.last_client);
    pairing::build_pairing_info(last_client.as_deref())
}

fn main() {
    tauri::Builder::default()
        .manage(AppState {
            receiver: Mutex::new(None),
            settings: Arc::new(Mutex::new(ReceiverSettings::default())),
        })
        .setup(|app| {
            #[cfg(target_os = "macos")]
            {
                let window = app.get_webview_window("main").unwrap();
                let _ = window.set_title("PhonePad Receiver - 请授予辅助功能权限");
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            start_receiver,
            stop_receiver,
            receiver_status,
            update_settings,
            pairing_info
        ])
        .run(tauri::generate_context!())
        .expect("failed to run PhonePad Receiver");
}
