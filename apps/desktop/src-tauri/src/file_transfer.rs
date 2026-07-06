use std::{
    collections::HashMap,
    fs::{self, File},
    io::{Read, Write},
    net::{SocketAddr, TcpListener, TcpStream},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    thread::{self, JoinHandle},
    time::Duration,
};

use phonepad_protocol::{file_transfer_token, TCP_FILE_PORT};
use serde::Serialize;
use tauri::{AppHandle, Emitter};

use crate::{
    notify,
    platform,
    preferences::AppPreferences,
};

pub const FILE_MAGIC: [u8; 2] = *b"PF";
pub const FILE_PROTOCOL_VERSION: u8 = 1;
pub const FILE_HEADER_LEN: usize = 47;
pub const MAX_FILE_SIZE: u64 = 512 * 1024 * 1024;
pub const TRANSFER_ID_LEN: usize = 36;

#[derive(Debug, Clone)]
pub struct PendingFile {
    pub transfer_id: String,
    pub file_name: String,
    pub file_size: u64,
    pub final_path: PathBuf,
    pub part_path: PathBuf,
    pub received: u64,
    pub batch_id: String,
    pub file_index: u32,
    pub total_files: u32,
    pub token: u64,
}

#[derive(Debug, Clone, Default)]
struct BatchState {
    committed_files: u32,
    total_files: u32,
    saved_dir: Option<PathBuf>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileBeginResponse {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub upload_port: Option<u16>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub token: Option<u64>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileCommitResponse {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub saved_path: Option<String>,
}

pub struct FileTransferManager {
    pending: Mutex<HashMap<String, PendingFile>>,
    batches: Mutex<HashMap<String, BatchState>>,
}

impl FileTransferManager {
    pub fn new() -> Self {
        Self {
            pending: Mutex::new(HashMap::new()),
            batches: Mutex::new(HashMap::new()),
        }
    }

    pub fn begin_transfer(
        &self,
        secret: &str,
        transfer_id: &str,
        file_name: &str,
        file_size: u64,
        batch_id: &str,
        file_index: u32,
        total_files: u32,
        download_dir: &Path,
    ) -> Result<FileBeginResponse, String> {
        if transfer_id.len() != TRANSFER_ID_LEN {
            return Err("transferId 格式无效".into());
        }
        if file_size == 0 {
            return Err("文件大小无效".into());
        }
        if file_size > MAX_FILE_SIZE {
            return Err(format!("文件超过 {} MB 限制", MAX_FILE_SIZE / 1024 / 1024));
        }
        if total_files == 0 || file_index == 0 || file_index > total_files {
            return Err("文件批次信息无效".into());
        }

        let safe_name = sanitize_filename(file_name)?;
        fs::create_dir_all(download_dir).map_err(|err| err.to_string())?;
        let final_path = resolve_unique_path(download_dir, &safe_name);
        let part_path = download_dir.join(format!("{transfer_id}.part"));

        {
            let pending_map = self.pending.lock().unwrap();
            if pending_map.contains_key(transfer_id) {
                return Err("传输会话已存在".into());
            }
        }

        let token = file_transfer_token(secret, transfer_id);
        let pending = PendingFile {
            transfer_id: transfer_id.to_string(),
            file_name: safe_name,
            file_size,
            final_path,
            part_path,
            received: 0,
            batch_id: batch_id.to_string(),
            file_index,
            total_files,
            token,
        };

        self.pending
            .lock()
            .unwrap()
            .insert(transfer_id.to_string(), pending);
        self.batches
            .lock()
            .unwrap()
            .entry(batch_id.to_string())
            .and_modify(|batch| {
                batch.total_files = total_files;
            })
            .or_insert_with(|| BatchState {
                committed_files: 0,
                total_files,
                saved_dir: Some(download_dir.to_path_buf()),
            });

        Ok(FileBeginResponse {
            ok: true,
            error: None,
            upload_port: Some(TCP_FILE_PORT),
            token: Some(token),
        })
    }

    pub fn commit_transfer(
        &self,
        transfer_id: &str,
        download_dir: &Path,
        preferences: &AppPreferences,
        app: &AppHandle,
    ) -> FileCommitResponse {
        let pending = match self.pending.lock().unwrap().remove(transfer_id) {
            Some(pending) => pending,
            None => {
                return FileCommitResponse {
                    ok: false,
                    error: Some("传输会话不存在或已过期".into()),
                    saved_path: None,
                };
            }
        };

        if pending.received != pending.file_size {
            let batch_id = pending.batch_id.clone();
            let _ = fs::remove_file(&pending.part_path);
            self.cleanup_batch_if_idle(&batch_id);
            return FileCommitResponse {
                ok: false,
                error: Some("文件未完整接收".into()),
                saved_path: None,
            };
        }

        let final_path = pending.final_path.clone();
        if let Err(err) = fs::rename(&pending.part_path, &final_path) {
            let batch_id = pending.batch_id.clone();
            let _ = fs::remove_file(&pending.part_path);
            self.cleanup_batch_if_idle(&batch_id);
            return FileCommitResponse {
                ok: false,
                error: Some(format!("保存文件失败: {err}")),
                saved_path: None,
            };
        }

        let saved_path = final_path.to_string_lossy().to_string();
        let mut open_dir: Option<PathBuf> = None;
        {
            let mut batches = self.batches.lock().unwrap();
            if let Some(batch) = batches.get_mut(&pending.batch_id) {
                batch.committed_files += 1;
                batch.saved_dir = Some(download_dir.to_path_buf());
                if batch.committed_files >= batch.total_files {
                    open_dir = batch.saved_dir.clone();
                    batches.remove(&pending.batch_id);
                }
            }
        }

        let _ = app.emit(
            "receiver://file-saved",
            serde_json::json!({
                "savedPath": saved_path,
                "batchId": pending.batch_id,
                "fileIndex": pending.file_index,
                "totalFiles": pending.total_files,
            }),
        );

        if let Some(dir) = open_dir {
            if preferences.open_folder_after_transfer {
                if let Err(err) = platform::open_path_in_file_manager(&dir) {
                    notify::show_error(app, &format!("文件已保存，但打开文件夹失败: {err}"));
                } else {
                    notify::show_info(app, &format!("已接收 {} 个文件", pending.total_files));
                }
            }
        }

        FileCommitResponse {
            ok: true,
            error: None,
            saved_path: Some(saved_path),
        }
    }

    pub fn cancel_transfer(&self, transfer_id: &str) {
        let removed = {
            let mut pending = self.pending.lock().unwrap();
            pending.remove(transfer_id)
        };
        if let Some(pending) = removed {
            let _ = fs::remove_file(&pending.part_path);
            self.cleanup_batch_if_idle(&pending.batch_id);
        }
    }

    fn cleanup_batch_if_idle(&self, batch_id: &str) {
        let has_more = self
            .pending
            .lock()
            .unwrap()
            .values()
            .any(|item| item.batch_id == batch_id);
        if !has_more {
            self.batches.lock().unwrap().remove(batch_id);
        }
    }

    pub fn attach_received_bytes(&self, transfer_id: &str, bytes: u64) {
        if let Some(pending) = self.pending.lock().unwrap().get_mut(transfer_id) {
            pending.received += bytes;
        }
    }

    pub fn pending_snapshot(&self, transfer_id: &str) -> Option<PendingFile> {
        self.pending.lock().unwrap().get(transfer_id).cloned()
    }
}

pub fn sanitize_filename(input: &str) -> Result<String, String> {
    let trimmed = input.trim();
    if trimmed.is_empty() {
        return Err("文件名为空".into());
    }
    if trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains("..") {
        return Err("文件名包含非法字符".into());
    }
    let base = Path::new(trimmed)
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or_else(|| String::from("invalid filename"))?;
    if base.len() > 180 {
        return Err("文件名过长".into());
    }
    if base != trimmed {
        return Err("文件名包含非法字符".into());
    }
    Ok(base.to_string())
}

pub fn resolve_unique_path(dir: &Path, file_name: &str) -> PathBuf {
    let mut candidate = dir.join(file_name);
    if !candidate.exists() {
        return candidate;
    }

    let path = Path::new(file_name);
    let stem = path
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or("file");
    let ext = path
        .extension()
        .and_then(|value| value.to_str())
        .map(|value| format!(".{value}"))
        .unwrap_or_default();

    for index in 1..=999 {
        candidate = dir.join(format!("{stem} ({index}){ext}"));
        if !candidate.exists() {
            return candidate;
        }
    }
    dir.join(format!("{stem}-{}{ext}", uuid::Uuid::new_v4()))
}

pub fn spawn_file_transfer_loop(
    manager: Arc<FileTransferManager>,
    shutdown: Arc<std::sync::atomic::AtomicBool>,
) -> JoinHandle<()> {
    thread::spawn(move || {
        let listener = match TcpListener::bind(("0.0.0.0", TCP_FILE_PORT)) {
            Ok(listener) => listener,
            Err(err) => {
                eprintln!("failed to bind file transfer port: {err}");
                return;
            }
        };
        let _ = listener.set_nonblocking(true);

        while !shutdown.load(std::sync::atomic::Ordering::Relaxed) {
            match listener.accept() {
                Ok((stream, peer)) => {
                    let manager = manager.clone();
                    thread::spawn(move || {
                        if let Err(err) = handle_file_upload(stream, peer, manager) {
                            eprintln!("file upload failed from {peer}: {err}");
                        }
                    });
                }
                Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(80));
                }
                Err(err) => {
                    eprintln!("file transfer accept error: {err}");
                    thread::sleep(Duration::from_millis(200));
                }
            }
        }
    })
}

fn handle_file_upload(
    mut stream: TcpStream,
    _peer: SocketAddr,
    manager: Arc<FileTransferManager>,
) -> Result<(), String> {
    stream
        .set_read_timeout(Some(Duration::from_secs(120)))
        .map_err(|err| err.to_string())?;

    let mut header = [0u8; FILE_HEADER_LEN];
    read_exact(&mut stream, &mut header)?;

    if header[0..2] != FILE_MAGIC {
        return write_upload_response(&mut stream, false, Some("无效文件头"));
    }
    if header[2] != FILE_PROTOCOL_VERSION {
        return write_upload_response(&mut stream, false, Some("不支持的文件协议版本"));
    }

    let token = u64::from_le_bytes(header[3..11].try_into().unwrap());
    let transfer_id = String::from_utf8_lossy(&header[11..11 + TRANSFER_ID_LEN])
        .trim_end_matches('\0')
        .to_string();
    if transfer_id.len() != TRANSFER_ID_LEN {
        return write_upload_response(&mut stream, false, Some("transferId 无效"));
    }

    let pending = manager
        .pending_snapshot(&transfer_id)
        .ok_or_else(|| "传输会话不存在".to_string())?;
    if pending.token != token {
        return write_upload_response(&mut stream, false, Some("认证失败"));
    }

    let mut file = File::create(&pending.part_path).map_err(|err| err.to_string())?;
    let mut remaining = pending.file_size;
    let mut buffer = [0u8; 64 * 1024];

    while remaining > 0 {
        let chunk = remaining.min(buffer.len() as u64) as usize;
        let read = stream.read(&mut buffer[..chunk]).map_err(|err| err.to_string())?;
        if read == 0 {
            break;
        }
        file.write_all(&buffer[..read])
            .map_err(|err| err.to_string())?;
        manager.attach_received_bytes(&transfer_id, read as u64);
        remaining -= read as u64;
    }

    file.flush().map_err(|err| err.to_string())?;
    drop(file);

    let updated = manager
        .pending_snapshot(&transfer_id)
        .ok_or_else(|| "传输会话不存在".to_string())?;
    if updated.received != updated.file_size {
        manager.cancel_transfer(&transfer_id);
        return write_upload_response(&mut stream, false, Some("文件未完整接收"));
    }

    write_upload_response(&mut stream, true, None)
}

fn read_exact(stream: &mut TcpStream, buf: &mut [u8]) -> Result<(), String> {
    let mut offset = 0;
    while offset < buf.len() {
        let read = stream
            .read(&mut buf[offset..])
            .map_err(|err| err.to_string())?;
        if read == 0 {
            return Err("连接已关闭".into());
        }
        offset += read;
    }
    Ok(())
}

fn write_upload_response(
    stream: &mut TcpStream,
    ok: bool,
    error: Option<&str>,
) -> Result<(), String> {
    let payload = if ok {
        r#"{"ok":true}"#.to_string()
    } else {
        format!(
            r#"{{"ok":false,"error":"{}"}}"#,
            error.unwrap_or("上传失败").replace('"', "\\\"")
        )
    };
    stream
        .write_all(format!("{payload}\n").as_bytes())
        .map_err(|err| err.to_string())
}
mod tests {
    use super::*;

    #[test]
    fn sanitize_filename_rejects_path_segments() {
        assert!(sanitize_filename("../secret.txt").is_err());
        assert!(sanitize_filename("folder/file.txt").is_err());
        assert_eq!(sanitize_filename("photo.jpg").unwrap(), "photo.jpg");
    }

    #[test]
    fn resolve_unique_path_appends_suffix() {
        let dir = std::env::temp_dir().join(format!("phonepad-test-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&dir).unwrap();
        let first = dir.join("demo.txt");
        fs::write(&first, b"1").unwrap();
        let second = resolve_unique_path(&dir, "demo.txt");
        assert_ne!(first, second);
        assert!(second.to_string_lossy().contains("(1)"));
        let _ = fs::remove_file(first);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn begin_transfer_rejects_duplicate_transfer_id() {
        let manager = FileTransferManager::new();
        let dir = std::env::temp_dir().join(format!("phonepad-test-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&dir).unwrap();
        let transfer_id = "a".repeat(TRANSFER_ID_LEN);
        let batch_id = uuid::Uuid::new_v4().to_string();

        manager
            .begin_transfer(
                "secret",
                &transfer_id,
                "demo.txt",
                10,
                &batch_id,
                1,
                1,
                &dir,
            )
            .unwrap();

        let duplicate = manager.begin_transfer(
            "secret",
            &transfer_id,
            "demo.txt",
            10,
            &batch_id,
            1,
            1,
            &dir,
        );
        assert!(duplicate.is_err());
        assert!(duplicate.unwrap_err().contains("已存在"));

        manager.cancel_transfer(&transfer_id);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn cancel_transfer_cleans_up_batch_when_last_pending_removed() {
        let manager = FileTransferManager::new();
        let dir = std::env::temp_dir().join(format!("phonepad-test-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&dir).unwrap();
        let transfer_id = "b".repeat(TRANSFER_ID_LEN);
        let batch_id = uuid::Uuid::new_v4().to_string();

        manager
            .begin_transfer(
                "secret",
                &transfer_id,
                "demo.txt",
                10,
                &batch_id,
                1,
                1,
                &dir,
            )
            .unwrap();

        assert!(manager.batches.lock().unwrap().contains_key(&batch_id));
        manager.cancel_transfer(&transfer_id);
        assert!(!manager.batches.lock().unwrap().contains_key(&batch_id));

        let _ = fs::remove_dir_all(dir);
    }
}
