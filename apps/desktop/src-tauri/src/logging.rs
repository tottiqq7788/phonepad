use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::Mutex,
    time::{SystemTime, UNIX_EPOCH},
};

const LOG_FILE_NAME: &str = "phonepad-desktop.log";
const MAX_LOG_BYTES: u64 = 2 * 1024 * 1024;
const MAX_ROTATED_FILES: usize = 5;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LogLevel {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
}

impl LogLevel {
    fn as_str(self) -> &'static str {
        match self {
            LogLevel::Trace => "TRACE",
            LogLevel::Debug => "DEBUG",
            LogLevel::Info => "INFO",
            LogLevel::Warn => "WARN",
            LogLevel::Error => "ERROR",
        }
    }
}

struct LoggerState {
    log_dir: PathBuf,
    file: File,
}

static LOGGER: Mutex<Option<LoggerState>> = Mutex::new(None);

pub fn init(log_dir: PathBuf) {
    let _ = fs::create_dir_all(&log_dir);
    rotate_if_needed(&log_dir);
    let path = log_dir.join(LOG_FILE_NAME);
    let file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .unwrap_or_else(|err| panic!("failed to open log file {path:?}: {err}"));
    let mut guard = LOGGER.lock().unwrap();
    *guard = Some(LoggerState { log_dir, file });
}

pub fn log_dir() -> Option<PathBuf> {
    LOGGER.lock().unwrap().as_ref().map(|state| state.log_dir.clone())
}

pub fn log_event(level: LogLevel, module: &str, event: &str, fields: &str) {
    let line = format!(
        "{} {} {} {} {}",
        timestamp(),
        level.as_str(),
        module,
        event,
        fields
    );
    eprintln!("{line}");
    let mut guard = LOGGER.lock().unwrap();
    let Some(state) = guard.as_mut() else {
        return;
    };
    let _ = writeln!(state.file, "{line}");
    let _ = state.file.flush();
    if state.file.metadata().map(|meta| meta.len()).unwrap_or(0) >= MAX_LOG_BYTES {
        drop(guard);
        if let Some(dir) = log_dir() {
            rotate_if_needed(&dir);
            init(dir);
        }
    }
}

pub fn info(module: &str, event: &str, fields: &str) {
    log_event(LogLevel::Info, module, event, fields);
}

pub fn warn(module: &str, event: &str, fields: &str) {
    log_event(LogLevel::Warn, module, event, fields);
}

pub fn error(module: &str, event: &str, fields: &str) {
    log_event(LogLevel::Error, module, event, fields);
}

pub fn debug(module: &str, event: &str, fields: &str) {
    log_event(LogLevel::Debug, module, event, fields);
}

pub fn short_id(value: &str) -> String {
    if value.len() <= 8 {
        return value.to_string();
    }
    format!("{}…", &value[..8])
}

fn timestamp() -> String {
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or(0);
    millis.to_string()
}

fn rotate_if_needed(log_dir: &Path) {
    let current = log_dir.join(LOG_FILE_NAME);
    if !current.exists() {
        return;
    }
    let size = fs::metadata(&current).map(|meta| meta.len()).unwrap_or(0);
    if size < MAX_LOG_BYTES {
        return;
    }
    for index in (1..MAX_ROTATED_FILES).rev() {
        let from = if index == 1 {
            current.clone()
        } else {
            log_dir.join(format!("{LOG_FILE_NAME}.{index_minus}", index_minus = index - 1))
        };
        let to = log_dir.join(format!("{LOG_FILE_NAME}.{index}"));
        if to.exists() {
            let _ = fs::remove_file(&to);
        }
        if from.exists() {
            let _ = fs::rename(&from, &to);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn short_id_truncates_long_values() {
        assert_eq!(short_id("abcdefgh"), "abcdefgh");
        assert_eq!(short_id("1234567890"), "12345678…");
    }

    #[test]
    fn init_and_log_writes_file() {
        let dir = std::env::temp_dir().join(format!(
            "phonepad-log-test-{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|duration| duration.as_millis())
                .unwrap_or(0)
        ));
        init(dir.clone());
        info("app", "test_event", "key=value");
        let content = fs::read_to_string(dir.join(LOG_FILE_NAME)).expect("log file should exist");
        assert!(content.contains("test_event"));
        let _ = fs::remove_dir_all(dir);
    }
}
