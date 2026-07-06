use tauri::{AppHandle, Emitter};
use tauri_plugin_notification::NotificationExt;

pub fn show_error(app: &AppHandle, message: &str) {
    let _ = app.emit("receiver://error", message);
    if let Err(err) = app
        .notification()
        .builder()
        .title("PhonePad Receiver")
        .body(message)
        .show()
    {
        eprintln!("failed to show notification: {err}");
    }
}

pub fn show_info(app: &AppHandle, message: &str) {
    if let Err(err) = app
        .notification()
        .builder()
        .title("PhonePad Receiver")
        .body(message)
        .show()
    {
        eprintln!("failed to show notification: {err}");
    }
}
