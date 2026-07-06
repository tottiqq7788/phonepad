use std::process::Command;

use arboard::Clipboard;
use tauri::{
    image::Image,
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager,
};
use tauri_plugin_autostart::ManagerExt;

use crate::{
    pairing,
    receiver::{self, ReceiverStatus},
    state::AppState,
};

pub const TRAY_ID: &str = "phonepad-tray";

const MENU_SHOW: &str = "show_console";
const MENU_HIDE: &str = "hide_console";
const MENU_STATUS: &str = "receiver_status";
const MENU_TOGGLE_RECEIVER: &str = "toggle_receiver";
const MENU_COPY_PAIRING: &str = "copy_pairing";
const MENU_COPY_IP: &str = "copy_ip";
const MENU_COPY_DEVICE_ID: &str = "copy_device_id";
const MENU_REFRESH_PAIRING: &str = "refresh_pairing";
const MENU_OPEN_CONFIG: &str = "open_config";
const MENU_AUTOSTART: &str = "toggle_autostart";
const MENU_HELP: &str = "help";
const MENU_QUIT: &str = "quit";

pub fn setup_tray(app: &tauri::App) -> tauri::Result<()> {
    let menu = build_tray_menu(app.handle())?;
    let icon = tray_icon(app);

    let _tray = TrayIconBuilder::with_id(TRAY_ID)
        .icon(icon)
        .menu(&menu)
        .tooltip("PhonePad Receiver")
        .on_menu_event(handle_menu_event)
        .on_tray_icon_event(handle_tray_icon_event)
        .build(app)?;

    #[cfg(target_os = "macos")]
    if let Some(tray) = app.tray_by_id(TRAY_ID) {
        let _ = tray.set_show_menu_on_left_click(false);
    }

    Ok(())
}

pub fn refresh_tray_menu(app: &AppHandle) -> Result<(), String> {
    let menu = build_tray_menu(app).map_err(|err| err.to_string())?;
    let tray = app
        .tray_by_id(TRAY_ID)
        .ok_or_else(|| "tray icon not found".to_string())?;
    tray.set_menu(Some(menu)).map_err(|err| err.to_string())
}

fn tray_icon(_app: &tauri::App) -> Image<'static> {
    const SIZE: u32 = 32;
    let mut rgba = Vec::with_capacity((SIZE * SIZE * 4) as usize);
    for y in 0..SIZE {
        for x in 0..SIZE {
            let dx = x as i32 - 16;
            let dy = y as i32 - 16;
            if dx * dx + dy * dy <= 13 * 13 {
                rgba.extend_from_slice(&[58, 130, 246, 255]);
            } else {
                rgba.extend_from_slice(&[0, 0, 0, 0]);
            }
        }
    }
    Image::new_owned(rgba, SIZE, SIZE)
}

fn build_tray_menu(app: &AppHandle) -> tauri::Result<Menu<tauri::Wry>> {
    let state = app.state::<AppState>();
    let running = state.receiver_running();
    let status_text = if running {
        "接收状态：正在接收"
    } else {
        "接收状态：未启动"
    };
    let toggle_text = if running { "停止接收" } else { "启动接收" };
    let autostart_enabled = app.autolaunch().is_enabled().unwrap_or(false);
    let autostart_text = if autostart_enabled {
        "开机自启：开启"
    } else {
        "开机自启：关闭"
    };

    let show = MenuItem::with_id(app, MENU_SHOW, "打开控制台", true, None::<&str>)?;
    let hide = MenuItem::with_id(app, MENU_HIDE, "隐藏控制台", true, None::<&str>)?;
    let status = MenuItem::with_id(app, MENU_STATUS, status_text, false, None::<&str>)?;
    let toggle_receiver =
        MenuItem::with_id(app, MENU_TOGGLE_RECEIVER, toggle_text, true, None::<&str>)?;
    let copy_pairing = MenuItem::with_id(app, MENU_COPY_PAIRING, "复制配对链接", true, None::<&str>)?;
    let copy_ip = MenuItem::with_id(app, MENU_COPY_IP, "复制本机推荐 IP", true, None::<&str>)?;
    let copy_device_id = MenuItem::with_id(app, MENU_COPY_DEVICE_ID, "复制设备 ID", true, None::<&str>)?;
    let refresh_pairing =
        MenuItem::with_id(app, MENU_REFRESH_PAIRING, "刷新配对信息", true, None::<&str>)?;
    let open_config = MenuItem::with_id(app, MENU_OPEN_CONFIG, "打开配置目录", true, None::<&str>)?;
    let autostart = MenuItem::with_id(app, MENU_AUTOSTART, autostart_text, true, None::<&str>)?;
    let help = MenuItem::with_id(app, MENU_HELP, "帮助提示", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, MENU_QUIT, "退出 PhonePad", true, None::<&str>)?;

    Menu::with_items(
        app,
        &[
            &show,
            &hide,
            &PredefinedMenuItem::separator(app)?,
            &status,
            &toggle_receiver,
            &PredefinedMenuItem::separator(app)?,
            &copy_pairing,
            &copy_ip,
            &copy_device_id,
            &refresh_pairing,
            &open_config,
            &PredefinedMenuItem::separator(app)?,
            &autostart,
            &help,
            &PredefinedMenuItem::separator(app)?,
            &quit,
        ],
    )
}

fn handle_tray_icon_event(tray: &tauri::tray::TrayIcon, event: TrayIconEvent) {
    if let TrayIconEvent::Click {
        button: MouseButton::Left,
        button_state: MouseButtonState::Up,
        ..
    } = event
    {
        let app = tray.app_handle();
        show_console(app);
    }
}

fn handle_menu_event(app: &AppHandle, event: tauri::menu::MenuEvent) {
    match event.id.as_ref() {
        MENU_SHOW => show_console(app),
        MENU_HIDE => hide_console(app),
        MENU_TOGGLE_RECEIVER => {
            if let Err(err) = toggle_receiver(app) {
                eprintln!("tray toggle receiver failed: {err}");
            }
        }
        MENU_COPY_PAIRING => {
            if let Err(err) = copy_pairing_url(app) {
                eprintln!("copy pairing url failed: {err}");
            }
        }
        MENU_COPY_IP => {
            if let Err(err) = copy_recommended_ip(app) {
                eprintln!("copy recommended ip failed: {err}");
            }
        }
        MENU_COPY_DEVICE_ID => {
            if let Err(err) = copy_device_id(app) {
                eprintln!("copy device id failed: {err}");
            }
        }
        MENU_REFRESH_PAIRING => {
            if let Err(err) = refresh_tray_menu(app) {
                eprintln!("refresh tray menu failed: {err}");
            }
        }
        MENU_OPEN_CONFIG => {
            if let Err(err) = open_config_dir(app) {
                eprintln!("open config dir failed: {err}");
            }
        }
        MENU_AUTOSTART => {
            if let Err(err) = toggle_autostart(app) {
                eprintln!("toggle autostart failed: {err}");
            }
        }
        MENU_HELP => show_help(app),
        MENU_QUIT => quit_app(app),
        MENU_STATUS => {}
        _ => {}
    }
}

pub fn show_console(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

pub fn hide_console(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.hide();
    }
}

fn pairing_snapshot(app: &AppHandle) -> pairing::PairingInfo {
    let state = app.state::<AppState>();
    let last_client = state.receiver_snapshot().last_client;
    let device = state.device.lock().unwrap().clone();
    pairing::build_pairing_info(&device, last_client.as_deref())
}

fn copy_to_clipboard(text: &str) -> Result<(), String> {
    Clipboard::new()
        .map_err(|err| err.to_string())?
        .set_text(text)
        .map_err(|err| err.to_string())
}

fn copy_pairing_url(app: &AppHandle) -> Result<(), String> {
    let info = pairing_snapshot(app);
    copy_to_clipboard(&info.connection_url)
}

fn copy_recommended_ip(app: &AppHandle) -> Result<(), String> {
    let info = pairing_snapshot(app);
    let ip = if info.recommended_ip.is_empty() {
        info.local_ip
    } else {
        info.recommended_ip
    };
    if ip.is_empty() {
        return Err("未检测到可用网络地址".into());
    }
    copy_to_clipboard(&ip)
}

fn copy_device_id(app: &AppHandle) -> Result<(), String> {
    let info = pairing_snapshot(app);
    copy_to_clipboard(&info.device_id)
}

fn open_config_dir(app: &AppHandle) -> Result<(), String> {
    let state = app.state::<AppState>();
    let path = state.config_dir();
    #[cfg(target_os = "windows")]
    {
        Command::new("explorer")
            .arg(path)
            .spawn()
            .map_err(|err| err.to_string())?;
    }
    #[cfg(target_os = "macos")]
    {
        Command::new("open")
            .arg(path)
            .spawn()
            .map_err(|err| err.to_string())?;
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos")))]
    {
        Command::new("xdg-open")
            .arg(path)
            .spawn()
            .map_err(|err| err.to_string())?;
    }
    Ok(())
}

fn toggle_receiver(app: &AppHandle) -> Result<(), String> {
    let state = app.state::<AppState>();
    if state.receiver_running() {
        state.stop_receiver();
    } else {
        {
            let mut receiver_guard = state.receiver.lock().unwrap();
            if let Some(handle) = receiver_guard.take() {
                handle.stop();
            }
        }
        let handle = receiver::start(app.clone(), state.settings.clone(), state.device.clone())
            .map_err(|err| err.to_string())?;
        *state.receiver.lock().unwrap() = Some(handle);
    }
    refresh_tray_menu(app)
}

fn toggle_autostart(app: &AppHandle) -> Result<(), String> {
    let autostart = app.autolaunch();
    let enabled = autostart.is_enabled().map_err(|err| err.to_string())?;
    if enabled {
        autostart.disable().map_err(|err| err.to_string())?;
    } else {
        autostart.enable().map_err(|err| err.to_string())?;
    }
    refresh_tray_menu(app)
}

fn show_help(app: &AppHandle) {
    show_console(app);
    let _ = app.emit(
        "tray://help",
        "PhonePad Receiver 正在后台运行。Android 端请扫码配对；Windows 需放行 UDP 45454 / TCP 45455。",
    );
}

fn quit_app(app: &AppHandle) {
    let state = app.state::<AppState>();
    state.request_exit();
    state.stop_receiver();
    app.exit(0);
}

pub fn auto_start_receiver(app: &AppHandle) -> Result<ReceiverStatus, String> {
    let state = app.state::<AppState>();
    if state.receiver_running() {
        return Ok(state.receiver_snapshot());
    }

    {
        let mut receiver_guard = state.receiver.lock().unwrap();
        if let Some(handle) = receiver_guard.take() {
            handle.stop();
        }
    }

    let handle = receiver::start(app.clone(), state.settings.clone(), state.device.clone())
        .map_err(|err| err.to_string())?;
    let snapshot = handle.snapshot();
    *state.receiver.lock().unwrap() = Some(handle);
    Ok(snapshot)
}
