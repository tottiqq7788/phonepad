// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod device_config;
mod input;
mod notify;
mod pairing;
mod platform;
mod receiver;
mod settings;
mod state;
mod tray;

use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
};

use crate::{
    device_config::DeviceConfig,
    input::InputController,
    pairing::PairingInfo,
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

            let path = device_config_path(app.handle());
            let loaded = DeviceConfig::load(&path);
            if loaded.should_persist {
                let _ = loaded.config.save(&path);
            }

            let input_controller = Arc::new(Mutex::new(
                InputController::new().map_err(|err| format!("无法初始化输入控制器: {err}"))?,
            ));

            app.manage(AppState {
                receiver: Mutex::new(None),
                settings: Arc::new(Mutex::new(ReceiverSettings::default())),
                device: Arc::new(Mutex::new(loaded.config)),
                device_config_path: path,
                should_exit: std::sync::atomic::AtomicBool::new(false),
                input_controller,
            });

            let app_handle = app.handle().clone();
            if let Err(err) = auto_start_receiver(&app_handle) {
                eprintln!("failed to auto-start receiver: {err}");
                notify::show_error(&app_handle, &format!("接收服务启动失败: {err}"));
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
