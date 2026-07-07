// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod device_config;
mod file_transfer;
mod gesture;
mod input;
mod logging;
mod notify;
mod pairing;
mod platform;
mod preferences;
mod receiver;
mod screenshot;
mod settings;
mod state;
mod tray;

use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
};

use crate::{
    device_config::DeviceConfig,
    file_transfer::FileTransferManager,
    input::InputController,
    pairing::PairingInfo,
    preferences::AppPreferences,
    receiver::ReceiverStatus,
    settings::ReceiverSettings,
    state::AppState,
};
use tauri::{Manager, State, WindowEvent};
#[cfg(target_os = "macos")]
use tauri::RunEvent;
use tauri_plugin_autostart::ManagerExt;
use tray::{auto_start_receiver, refresh_tray_menu, setup_tray};

fn device_config_path(app: &tauri::AppHandle) -> PathBuf {
    app.path()
        .app_config_dir()
        .unwrap_or_else(|_| PathBuf::from("."))
        .join("device.json")
}

fn preferences_path(app: &tauri::AppHandle) -> PathBuf {
    app.path()
        .app_config_dir()
        .unwrap_or_else(|_| PathBuf::from("."))
        .join("preferences.json")
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct PreferencesInfo {
    download_dir: String,
    default_download_dir: String,
    screenshot_dir: String,
    default_screenshot_dir: String,
    open_folder_after_transfer: bool,
    using_default_download_dir: bool,
    using_default_screenshot_dir: bool,
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

    {
        let mut receiver_guard = state.receiver.lock().unwrap();
        if let Some(handle) = receiver_guard.as_ref() {
            if handle.snapshot().running {
                let snapshot = handle.snapshot();
                drop(receiver_guard);
                if let Err(err) = refresh_tray_menu(&app) {
                    eprintln!("refresh tray menu failed: {err}");
                }
                return Ok(snapshot);
            }
            handle.stop();
            *receiver_guard = None;
        }
    }

    let handle = receiver::start(
        app.clone(),
        state.settings.clone(),
        state.device.clone(),
        state.input_controller.clone(),
        state.preferences.clone(),
        state.file_transfer.clone(),
    )?;
    let snapshot = handle.snapshot();
    *state.receiver.lock().unwrap() = Some(handle);
    if let Err(err) = refresh_tray_menu(&app) {
        eprintln!("refresh tray menu failed: {err}");
    }
    Ok(snapshot)
}

#[tauri::command]
fn stop_receiver(app: tauri::AppHandle, state: State<'_, AppState>) -> ReceiverStatus {
    state.stop_receiver();
    if let Err(err) = refresh_tray_menu(&app) {
        eprintln!("refresh tray menu failed: {err}");
    }
    ReceiverStatus::default()
}

#[tauri::command]
fn receiver_status(state: State<'_, AppState>) -> ReceiverStatus {
    state.receiver_snapshot()
}

#[tauri::command]
fn update_settings(state: State<'_, AppState>, settings: ReceiverSettings) -> ReceiverStatus {
    *state.settings.lock().unwrap() = settings;
    state.receiver_snapshot()
}

#[tauri::command]
fn pairing_info(state: State<'_, AppState>) -> PairingInfo {
    let last_client = state.receiver_snapshot().last_client;
    pairing::build_pairing_info(&state.device.lock().unwrap(), last_client.as_deref())
}

#[tauri::command]
fn device_info(state: State<'_, AppState>) -> DeviceConfig {
    state.device.lock().unwrap().clone()
}

#[tauri::command]
fn update_device_name(state: State<'_, AppState>, name: String) -> Result<DeviceConfig, String> {
    let mut device = state.device.lock().unwrap();
    device.update_name(name)?;
    device.save(&state.device_config_path)?;
    Ok(device.clone())
}

#[tauri::command]
fn autostart_status(app: tauri::AppHandle) -> Result<bool, String> {
    app.autolaunch().is_enabled().map_err(|err| err.to_string())
}

#[tauri::command]
fn set_autostart(app: tauri::AppHandle, enabled: bool) -> Result<bool, String> {
    let autostart = app.autolaunch();
    if enabled {
        autostart.enable().map_err(|err| err.to_string())?;
    } else {
        autostart.disable().map_err(|err| err.to_string())?;
    }
    if let Err(err) = refresh_tray_menu(&app) {
        eprintln!("refresh tray menu failed: {err}");
    }
    autostart.is_enabled().map_err(|err| err.to_string())
}

fn build_preferences_info(state: &AppState) -> PreferencesInfo {
    let default_download_dir = platform::default_download_dir()
        .unwrap_or_else(|| state.config_dir().join("downloads"));
    let default_screenshot_dir = platform::default_screenshot_dir()
        .unwrap_or_else(|| state.config_dir().join("screenshots"));
    let preferences = state.preferences.lock().unwrap().clone();
    let resolved_download = preferences.resolved_download_dir(&default_download_dir);
    let resolved_screenshot = preferences.resolved_screenshot_dir(&default_screenshot_dir);
    PreferencesInfo {
        download_dir: resolved_download.to_string_lossy().to_string(),
        default_download_dir: default_download_dir.to_string_lossy().to_string(),
        screenshot_dir: resolved_screenshot.to_string_lossy().to_string(),
        default_screenshot_dir: default_screenshot_dir.to_string_lossy().to_string(),
        open_folder_after_transfer: preferences.open_folder_after_transfer,
        using_default_download_dir: preferences.download_dir.is_none(),
        using_default_screenshot_dir: preferences.screenshot_dir.is_none(),
    }
}

#[tauri::command]
fn preferences_info(state: State<'_, AppState>) -> PreferencesInfo {
    build_preferences_info(&state)
}

#[tauri::command]
fn update_preferences(
    state: State<'_, AppState>,
    download_dir: Option<String>,
    screenshot_dir: Option<String>,
    open_folder_after_transfer: bool,
) -> Result<PreferencesInfo, String> {
    {
        let mut preferences = state.preferences.lock().unwrap();
        preferences.download_dir = download_dir
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty());
        preferences.screenshot_dir = screenshot_dir
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty());
        preferences.open_folder_after_transfer = open_folder_after_transfer;
        preferences.save(&state.preferences_path)?;
    }
    Ok(build_preferences_info(&state))
}

#[tauri::command]
fn pick_download_dir(state: State<'_, AppState>) -> Result<PreferencesInfo, String> {
    let prefs = state.preferences.lock().unwrap().clone();
    let picked = platform::pick_folder_dialog().ok_or_else(|| "未选择目录".to_string())?;
    {
        let mut preferences = state.preferences.lock().unwrap();
        preferences.download_dir = Some(picked.to_string_lossy().to_string());
        preferences.screenshot_dir = prefs.screenshot_dir;
        preferences.open_folder_after_transfer = prefs.open_folder_after_transfer;
        preferences.save(&state.preferences_path)?;
    }
    Ok(build_preferences_info(&state))
}

#[tauri::command]
fn reset_download_dir(state: State<'_, AppState>) -> Result<PreferencesInfo, String> {
    let prefs = state.preferences.lock().unwrap().clone();
    {
        let mut preferences = state.preferences.lock().unwrap();
        preferences.download_dir = None;
        preferences.screenshot_dir = prefs.screenshot_dir;
        preferences.open_folder_after_transfer = prefs.open_folder_after_transfer;
        preferences.save(&state.preferences_path)?;
    }
    Ok(build_preferences_info(&state))
}

#[tauri::command]
fn pick_screenshot_dir(state: State<'_, AppState>) -> Result<PreferencesInfo, String> {
    let prefs = state.preferences.lock().unwrap().clone();
    let picked = platform::pick_folder_dialog().ok_or_else(|| "未选择目录".to_string())?;
    {
        let mut preferences = state.preferences.lock().unwrap();
        preferences.screenshot_dir = Some(picked.to_string_lossy().to_string());
        preferences.download_dir = prefs.download_dir;
        preferences.open_folder_after_transfer = prefs.open_folder_after_transfer;
        preferences.save(&state.preferences_path)?;
    }
    Ok(build_preferences_info(&state))
}

#[tauri::command]
fn reset_screenshot_dir(state: State<'_, AppState>) -> Result<PreferencesInfo, String> {
    let prefs = state.preferences.lock().unwrap().clone();
    {
        let mut preferences = state.preferences.lock().unwrap();
        preferences.screenshot_dir = None;
        preferences.download_dir = prefs.download_dir;
        preferences.open_folder_after_transfer = prefs.open_folder_after_transfer;
        preferences.save(&state.preferences_path)?;
    }
    Ok(build_preferences_info(&state))
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_notification::init())
        .plugin(
            tauri_plugin_autostart::Builder::new()
                .app_name("PhonePad Receiver")
                .build(),
        )
        .setup(|app| {
            platform::keep_process_responsive();

            let config_dir = app
                .path()
                .app_config_dir()
                .unwrap_or_else(|_| PathBuf::from("."));
            logging::init(config_dir.join("logs"));
            logging::info(
                "app",
                "app_start",
                &format!(
                    "version={} config_dir={}",
                    env!("CARGO_PKG_VERSION"),
                    config_dir.display()
                ),
            );

            let path = device_config_path(app.handle());
            let prefs_path = preferences_path(app.handle());
            let loaded = DeviceConfig::load(&path);
            if loaded.should_persist {
                let _ = loaded.config.save(&path);
            }
            let loaded_preferences = AppPreferences::load(&prefs_path);
            if loaded_preferences.should_persist {
                let _ = loaded_preferences.preferences.save(&prefs_path);
            }

            let input_controller = Arc::new(Mutex::new(
                InputController::new().map_err(|err| format!("无法初始化输入控制器: {err}"))?,
            ));
            let file_transfer = Arc::new(FileTransferManager::new());

            app.manage(AppState {
                receiver: Mutex::new(None),
                settings: Arc::new(Mutex::new(ReceiverSettings::default())),
                preferences: Arc::new(Mutex::new(loaded_preferences.preferences)),
                file_transfer,
                device: Arc::new(Mutex::new(loaded.config)),
                device_config_path: path,
                preferences_path: prefs_path,
                should_exit: std::sync::atomic::AtomicBool::new(false),
                input_controller,
            });

            let app_handle = app.handle().clone();
            if let Err(err) = auto_start_receiver(&app_handle) {
                logging::error("app", "auto_start_receiver_failed", &format!("reason={err}"));
                notify::show_error(&app_handle, &format!("接收服务启动失败: {err}"));
            } else {
                logging::info("app", "auto_start_receiver", "result=ok");
            }

            setup_tray(app)?;

            if let Some(window) = app.get_webview_window("main") {
                let window_handle = app.handle().clone();
                window.on_window_event(move |event| {
                    if let WindowEvent::CloseRequested { api, .. } = event {
                        let state = window_handle.state::<AppState>();
                        if !state.should_exit() {
                            api.prevent_close();
                            tray::hide_console(&window_handle);
                        }
                    }
                });
            }

            #[cfg(target_os = "macos")]
            {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.set_title("PhonePad Receiver - 请授予辅助功能权限");
                }
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            start_receiver,
            stop_receiver,
            receiver_status,
            update_settings,
            pairing_info,
            device_info,
            update_device_name,
            autostart_status,
            set_autostart,
            preferences_info,
            update_preferences,
            pick_download_dir,
            reset_download_dir,
            pick_screenshot_dir,
            reset_screenshot_dir,
        ])
        .build(tauri::generate_context!())
        .expect("failed to run PhonePad Receiver")
        .run(|app_handle, event| {
            #[cfg(target_os = "macos")]
            if let RunEvent::Reopen { .. } = event {
                tray::show_console(app_handle);
            }
            #[cfg(not(target_os = "macos"))]
            let _ = (app_handle, event);
        });
}
