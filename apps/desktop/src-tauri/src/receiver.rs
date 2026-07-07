use std::{
    io::{Read, Write},
    net::{SocketAddr, TcpListener, UdpSocket},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread::{self, JoinHandle},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use enigo::Direction;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager};
use phonepad_protocol::{
    auth_token, discover_request_auth, ButtonAction, DiscoverRequest, DiscoverResponse, InputPacket,
    PacketKind, TCP_CONTROL_PORT, TCP_FILE_PORT, UDP_DISCOVERY_PORT, UDP_INPUT_PORT,
};

use crate::{
    device_config::DeviceConfig,
    file_transfer::{self, FileBeginResponse, FileCommitResponse, FileTransferManager},
    input::{normalize_key_repeat, parse_key_action, parse_key_event, reset_modifier_tracker, InputController},
    logging::{self, short_id},
    motion_aggregator::MotionAggregator,
    platform,
    preferences::AppPreferences,
    settings::ReceiverSettings,
    state::AppState,
};

const INPUT_APPLY_INTERVAL_MS: u64 = 16;

pub const MAX_TEXT_CHARS: usize = 4096;
pub const MAX_TEXT_BYTES: usize = 6000;
pub const MAX_CONTROL_REQUEST_BYTES: usize = 8192;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiverStatus {
    pub running: bool,
    pub udp_port: u16,
    pub tcp_port: u16,
    pub file_port: u16,
    pub discovery_port: u16,
    pub packets_received: u64,
    pub packets_dropped: u64,
    pub move_packets: u64,
    pub scroll_packets: u64,
    pub click_packets: u64,
    pub last_client: Option<String>,
    pub last_rtt_ms: Option<f64>,
    pub device_id: String,
    pub device_name: String,
}

impl Default for ReceiverStatus {
    fn default() -> Self {
        Self {
            running: false,
            udp_port: UDP_INPUT_PORT,
            tcp_port: TCP_CONTROL_PORT,
            file_port: TCP_FILE_PORT,
            discovery_port: UDP_DISCOVERY_PORT,
            packets_received: 0,
            packets_dropped: 0,
            move_packets: 0,
            scroll_packets: 0,
            click_packets: 0,
            last_client: None,
            last_rtt_ms: None,
            device_id: String::new(),
            device_name: String::new(),
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ControlRequest {
    #[serde(rename = "type")]
    request_type: String,
    device_id: String,
    secret: String,
    content: Option<String>,
    action: Option<String>,
    event: Option<String>,
    repeat: Option<u32>,
    transfer_id: Option<String>,
    file_name: Option<String>,
    file_size: Option<u64>,
    mime_type: Option<String>,
    batch_id: Option<String>,
    file_index: Option<u32>,
    total_files: Option<u32>,
}

#[derive(Debug, Serialize)]
struct ControlResponse {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

pub struct ReceiverHandle {
    shutdown: Arc<AtomicBool>,
    status: Arc<Mutex<ReceiverStatus>>,
    workers: Mutex<Vec<JoinHandle<()>>>,
}

impl ReceiverHandle {
    pub fn snapshot(&self) -> ReceiverStatus {
        self.status.lock().unwrap().clone()
    }

    pub fn stop(&self) {
        self.shutdown.store(true, Ordering::Relaxed);
        self.status.lock().unwrap().running = false;
        for handle in self.workers.lock().unwrap().drain(..) {
            let _ = handle.join();
        }
    }
}

pub fn validate_text_content(content: &str) -> Result<&str, String> {
    let trimmed = content.trim();
    if trimmed.is_empty() {
        return Err("文本为空".into());
    }
    if trimmed.chars().count() > MAX_TEXT_CHARS {
        return Err(format!("文本超过 {MAX_TEXT_CHARS} 字符限制"));
    }
    if trimmed.as_bytes().len() > MAX_TEXT_BYTES {
        return Err(format!("文本超过 {MAX_TEXT_BYTES} 字节限制"));
    }
    Ok(trimmed)
}

fn is_oversize_control_request(total: usize, buf: &[u8]) -> bool {
    total >= MAX_CONTROL_REQUEST_BYTES && !buf[..total].contains(&b'\n')
}

pub fn start(
    app: AppHandle,
    settings: Arc<Mutex<ReceiverSettings>>,
    device: Arc<Mutex<DeviceConfig>>,
    input: Arc<Mutex<InputController>>,
    preferences: Arc<Mutex<AppPreferences>>,
    file_transfer: Arc<FileTransferManager>,
) -> Result<ReceiverHandle, String> {
    let udp_socket = UdpSocket::bind(("0.0.0.0", UDP_INPUT_PORT)).map_err(|err| err.to_string())?;
    udp_socket
        .set_read_timeout(Some(Duration::from_millis(50)))
        .map_err(|err| err.to_string())?;

    let discovery_socket =
        UdpSocket::bind(("0.0.0.0", UDP_DISCOVERY_PORT)).map_err(|err| err.to_string())?;
    discovery_socket
        .set_read_timeout(Some(Duration::from_millis(250)))
        .map_err(|err| err.to_string())?;

    let tcp_listener =
        TcpListener::bind(("0.0.0.0", TCP_CONTROL_PORT)).map_err(|err| err.to_string())?;
    tcp_listener
        .set_nonblocking(true)
        .map_err(|err| err.to_string())?;

    let shutdown = Arc::new(AtomicBool::new(false));
    let device_snapshot = device.lock().unwrap().clone();
    let device_id_short = short_id(&device_snapshot.device_id);
    let status = Arc::new(Mutex::new(ReceiverStatus {
        running: true,
        device_id: device_snapshot.device_id,
        device_name: device_snapshot.device_name,
        ..ReceiverStatus::default()
    }));

    let motion = Arc::new(Mutex::new(MotionAggregator::default()));
    let input_handle = spawn_input_loop(
        app.clone(),
        udp_socket,
        settings.clone(),
        device.clone(),
        input.clone(),
        motion.clone(),
        shutdown.clone(),
        status.clone(),
    );
    let applier_handle = spawn_input_applier_loop(
        settings.clone(),
        input.clone(),
        motion,
        shutdown.clone(),
    );
    let discovery_handle =
        spawn_discovery_loop(discovery_socket, device.clone(), shutdown.clone());
    let control_handle = spawn_control_loop(
        app,
        tcp_listener,
        device,
        input,
        preferences,
        file_transfer.clone(),
        shutdown.clone(),
        status.clone(),
    );
    let file_handle = file_transfer::spawn_file_transfer_loop(file_transfer, shutdown.clone());

    logging::info(
        "connection",
        "receiver_started",
        &format!(
            "udp={UDP_INPUT_PORT} tcp={TCP_CONTROL_PORT} file={TCP_FILE_PORT} discovery={UDP_DISCOVERY_PORT} device_id={device_id_short}",
        ),
    );

    Ok(ReceiverHandle {
        shutdown,
        status,
        workers: Mutex::new(vec![
            input_handle,
            applier_handle,
            discovery_handle,
            control_handle,
            file_handle,
        ]),
    })
}

fn apply_pending_motion(
    motion: &Arc<Mutex<MotionAggregator>>,
    input: &Arc<Mutex<InputController>>,
    settings: &ReceiverSettings,
) {
    let (move_dx, move_dy, scroll_dx, scroll_dy) = {
        let mut aggregator = motion.lock().unwrap();
        let (move_dx, move_dy) = aggregator.take_move();
        let (scroll_dx, scroll_dy) = aggregator.take_scroll();
        (move_dx, move_dy, scroll_dx, scroll_dy)
    };

    if move_dx == 0 && move_dy == 0 && scroll_dx == 0 && scroll_dy == 0 {
        return;
    }

    if let Ok(mut controller) = input.lock() {
        if move_dx != 0 || move_dy != 0 {
            let _ = controller.move_mouse(move_dx, move_dy, settings);
        }
        if scroll_dx != 0 || scroll_dy != 0 {
            let _ = controller.scroll(scroll_dx, scroll_dy, settings);
        }
    }
}

fn spawn_input_applier_loop(
    settings: Arc<Mutex<ReceiverSettings>>,
    input: Arc<Mutex<InputController>>,
    motion: Arc<Mutex<MotionAggregator>>,
    shutdown: Arc<AtomicBool>,
) -> JoinHandle<()> {
    thread::spawn(move || {
        while !shutdown.load(Ordering::Relaxed) {
            thread::sleep(Duration::from_millis(INPUT_APPLY_INTERVAL_MS));

            let current_settings = settings.lock().unwrap().clone();
            apply_pending_motion(&motion, &input, &current_settings);
        }
    })
}

fn spawn_input_loop(
    app: AppHandle,
    socket: UdpSocket,
    settings: Arc<Mutex<ReceiverSettings>>,
    device: Arc<Mutex<DeviceConfig>>,
    input: Arc<Mutex<InputController>>,
    motion: Arc<Mutex<MotionAggregator>>,
    shutdown: Arc<AtomicBool>,
    status: Arc<Mutex<ReceiverStatus>>,
) -> JoinHandle<()> {
    thread::spawn(move || {
        let mut buf = [0u8; 64];
        let mut last_sequence = 0u32;
        let mut last_peer: Option<SocketAddr> = None;

        while !shutdown.load(Ordering::Relaxed) {
            let (len, peer) = match socket.recv_from(&mut buf) {
                Ok(packet) => packet,
                Err(err)
                    if err.kind() == std::io::ErrorKind::WouldBlock
                        || err.kind() == std::io::ErrorKind::TimedOut =>
                {
                    continue
                }
                Err(err) => {
                    let _ = app.emit("receiver://error", err.to_string());
                    continue;
                }
            };

            let packet = match InputPacket::decode(&buf[..len]) {
                Ok(packet) => packet,
                Err(_) => {
                    status.lock().unwrap().packets_dropped += 1;
                    continue;
                }
            };

            let device_config = device.lock().unwrap().clone();
            let expected_token = auth_token(&device_config.pairing_secret, packet.sequence);
            if packet.auth_token != expected_token {
                status.lock().unwrap().packets_dropped += 1;
                logging::debug(
                    "udp_input",
                    "auth_failed",
                    &format!("peer={peer} seq={}", packet.sequence),
                );
                continue;
            }

            if last_peer != Some(peer) {
                last_peer = Some(peer);
                last_sequence = 0;
                reset_modifier_tracker();
                motion.lock().unwrap().clear();
                if let Ok(mut controller) = input.lock() {
                    controller.reset_scroll_remainder();
                }
            }

            if packet.sequence == 1 && last_sequence > 0 {
                last_sequence = 0;
            }

            if packet.sequence <= last_sequence
                && last_sequence.wrapping_sub(packet.sequence) < 10_000
            {
                if packet.sequence <= 32 && last_sequence > 32 {
                    last_sequence = 0;
                } else {
                    status.lock().unwrap().packets_dropped += 1;
                    continue;
                }
            }
            if packet.sequence <= last_sequence {
                status.lock().unwrap().packets_dropped += 1;
                continue;
            }
            last_sequence = packet.sequence;

            {
                let mut snapshot = status.lock().unwrap();
                snapshot.packets_received += 1;
                snapshot.last_client = Some(peer.to_string());
                snapshot.last_rtt_ms = estimate_rtt(packet.timestamp_micros);
                snapshot.device_name = device_config.device_name.clone();
                match packet.kind {
                    PacketKind::Move => snapshot.move_packets += 1,
                    PacketKind::Scroll => snapshot.scroll_packets += 1,
                    PacketKind::Click | PacketKind::Button => snapshot.click_packets += 1,
                    _ => {}
                }
            }

            let current_settings = settings.lock().unwrap().clone();
            let result = match packet.kind {
                PacketKind::Move => {
                    motion.lock().unwrap().accumulate_move(packet.x, packet.y);
                    Ok(())
                }
                PacketKind::Scroll => {
                    motion.lock().unwrap().accumulate_scroll(packet.x, packet.y);
                    Ok(())
                }
                PacketKind::Click => {
                    apply_pending_motion(&motion, &input, &current_settings);
                    let mut controller = match input.lock() {
                        Ok(controller) => controller,
                        Err(_) => {
                            status.lock().unwrap().packets_dropped += 1;
                            continue;
                        }
                    };
                    match packet.button {
                        Some(button) => controller.click(button),
                        None => {
                            status.lock().unwrap().packets_dropped += 1;
                            continue;
                        }
                    }
                }
                PacketKind::Button => {
                    apply_pending_motion(&motion, &input, &current_settings);
                    let mut controller = match input.lock() {
                        Ok(controller) => controller,
                        Err(_) => {
                            status.lock().unwrap().packets_dropped += 1;
                            continue;
                        }
                    };
                    let action = packet.action.unwrap_or(ButtonAction::Click);
                    match packet.button {
                        Some(button) => controller.button(button, action),
                        None => {
                            status.lock().unwrap().packets_dropped += 1;
                            continue;
                        }
                    }
                }
                _ => Ok(()),
            };

            if let Err(err) = result {
                let _ = app.emit("receiver://error", err);
            }
        }
    })
}

fn spawn_discovery_loop(
    socket: UdpSocket,
    device: Arc<Mutex<DeviceConfig>>,
    shutdown: Arc<AtomicBool>,
) -> JoinHandle<()> {
    thread::spawn(move || {
        let mut buf = [0u8; 512];

        while !shutdown.load(Ordering::Relaxed) {
            let (len, peer) = match socket.recv_from(&mut buf) {
                Ok(packet) => packet,
                Err(err)
                    if err.kind() == std::io::ErrorKind::WouldBlock
                        || err.kind() == std::io::ErrorKind::TimedOut =>
                {
                    continue
                }
                Err(_) => continue,
            };

            let ip = crate::pairing::discovery_response_ip(peer);
            if ip.is_empty() {
                continue;
            }

            let device_config = device.lock().unwrap().clone();

            if let Ok(request) = serde_json::from_slice::<DiscoverRequest>(&buf[..len]) {
                if request.request_type != "discover" {
                    continue;
                }
                if request.device_id != device_config.device_id {
                    continue;
                }
                if request.auth != discover_request_auth(&device_config.pairing_secret, request.nonce)
                {
                    logging::warn(
                        "discovery",
                        "discover_auth_failed",
                        &format!("peer={ip} device_id={}", short_id(&request.device_id)),
                    );
                    continue;
                }
                logging::debug(
                    "discovery",
                    "discover_request",
                    &format!("peer={ip} device_id={}", short_id(&request.device_id)),
                );
                let payload = DiscoverResponse::new(
                    device_config.device_id.clone(),
                    device_config.device_name.clone(),
                    ip,
                    &device_config.pairing_secret,
                    request.nonce,
                );
                if let Ok(bytes) = serde_json::to_vec(&payload) {
                    let _ = socket.send_to(&bytes, peer);
                }
            }
        }
    })
}

fn read_control_request(stream: &mut std::net::TcpStream) -> std::io::Result<(String, bool)> {
    let mut buf = vec![0u8; MAX_CONTROL_REQUEST_BYTES];
    let mut total = 0usize;
    loop {
        match stream.read(&mut buf[total..]) {
            Ok(0) => break,
            Ok(n) => {
                total += n;
                if total >= buf.len() || buf[..total].contains(&b'\n') {
                    break;
                }
            }
            Err(err)
                if err.kind() == std::io::ErrorKind::WouldBlock
                    || err.kind() == std::io::ErrorKind::TimedOut =>
            {
                break
            }
            Err(err) => return Err(err),
        }
    }
    let oversize = is_oversize_control_request(total, &buf);
    Ok((String::from_utf8_lossy(&buf[..total]).trim().to_string(), oversize))
}

fn write_control_response(stream: &mut std::net::TcpStream, response: &ControlResponse) {
    if let Ok(payload) = serde_json::to_vec(response) {
        let _ = stream.write_all(&payload);
        let _ = stream.write_all(b"\n");
        let _ = stream.flush();
    }
}

fn handle_text_request(
    input: &mut InputController,
    content: Option<String>,
) -> ControlResponse {
    let raw = content.unwrap_or_default();
    let validated = match validate_text_content(&raw) {
        Ok(text) => text,
        Err(error) => {
            return ControlResponse {
                ok: false,
                error: Some(error),
            };
        }
    };

    match input.type_text(validated) {
        Ok(()) => ControlResponse {
            ok: true,
            error: None,
        },
        Err(error) => ControlResponse {
            ok: false,
            error: Some(error),
        },
    }
}

fn handle_key_request(
    input: &mut InputController,
    action: Option<String>,
    event: Option<String>,
    repeat: Option<u32>,
) -> ControlResponse {
    let action_name = action.unwrap_or_default();
    if action_name.is_empty() {
        return ControlResponse {
            ok: false,
            error: Some("缺少 action".into()),
        };
    }

    let key = match parse_key_action(&action_name) {
        Ok(key) => key,
        Err(error) => {
            return ControlResponse {
                ok: false,
                error: Some(error),
            };
        }
    };

    let direction = match parse_key_event(event.as_deref()) {
        Ok(direction) => direction,
        Err(error) => {
            return ControlResponse {
                ok: false,
                error: Some(error),
            };
        }
    };

    let result = match direction {
        Direction::Click => {
            let repeat_count = normalize_key_repeat(repeat);
            input.press_key(key, repeat_count)
        }
        other => input.key_event(key, other),
    };

    match result {
        Ok(()) => ControlResponse {
            ok: true,
            error: None,
        },
        Err(error) => ControlResponse {
            ok: false,
            error: Some(error),
        },
    }
}

fn resolved_download_dir(app: &AppHandle, preferences: &AppPreferences) -> std::path::PathBuf {
    let fallback = platform::default_download_dir().unwrap_or_else(|| {
        app.state::<AppState>().config_dir().join("downloads")
    });
    preferences.resolved_download_dir(&fallback)
}

fn write_json_response<T: Serialize>(stream: &mut std::net::TcpStream, payload: &T) {
    if let Ok(bytes) = serde_json::to_vec(payload) {
        let _ = stream.write_all(&bytes);
    }
}

fn handle_control_connection(
    mut stream: std::net::TcpStream,
    peer: SocketAddr,
    app: AppHandle,
    device: Arc<Mutex<DeviceConfig>>,
    _shutdown: Arc<AtomicBool>,
    status: Arc<Mutex<ReceiverStatus>>,
    input: Arc<Mutex<InputController>>,
    preferences: Arc<Mutex<AppPreferences>>,
    file_transfer: Arc<FileTransferManager>,
) {
    let _ = stream.set_read_timeout(Some(Duration::from_millis(500)));
    let (request_text, oversize) = read_control_request(&mut stream).unwrap_or_default();
    if oversize {
        logging::warn(
            "control",
            "request_oversize",
            &format!("peer={peer} max_bytes={MAX_CONTROL_REQUEST_BYTES}"),
        );
        write_control_response(
            &mut stream,
            &ControlResponse {
                ok: false,
                error: Some("请求体过大".into()),
            },
        );
        return;
    }
    if request_text.is_empty() {
        return;
    }
    let request = match serde_json::from_str::<ControlRequest>(&request_text) {
        Ok(request) => request,
        Err(_) => {
            logging::warn("control", "request_invalid_json", &format!("peer={peer}"));
            write_control_response(
                &mut stream,
                &ControlResponse {
                    ok: false,
                    error: Some("请求格式错误".into()),
                },
            );
            return;
        }
    };

    let device_config = device.lock().unwrap().clone();
    let authed_types = [
        "text",
        "key",
        "status",
        "fileBegin",
        "fileCommit",
        "fileCancel",
    ];
    if !device_config.validate_secret(&request.device_id, &request.secret) {
        if authed_types.contains(&request.request_type.as_str()) {
            logging::warn(
                "control",
                "auth_failed",
                &format!(
                    "peer={peer} type={} device_id={}",
                    request.request_type,
                    short_id(&request.device_id)
                ),
            );
            write_auth_failure(&mut stream);
        }
        return;
    }

    match request.request_type.as_str() {
        "status" => {
            let snapshot = {
                let mut snapshot = status.lock().unwrap().clone();
                snapshot.device_id = device_config.device_id.clone();
                snapshot.device_name = device_config.device_name.clone();
                snapshot
            };
            let payload = serde_json::to_vec(&snapshot).unwrap_or_default();
            let _ = stream.write_all(&payload);
            status.lock().unwrap().last_client = Some(peer.to_string());
            let _ = app.emit("receiver://status", snapshot);
        }
        "text" => {
            let char_count = request.content.as_ref().map(|value| value.chars().count()).unwrap_or(0);
            let response = match input.lock() {
                Ok(mut controller) => handle_text_request(&mut controller, request.content),
                Err(_) => ControlResponse {
                    ok: false,
                    error: Some("无法获取输入控制器".into()),
                },
            };
            let succeeded = response.ok;
            write_control_response(&mut stream, &response);
            logging::info(
                "text_input",
                "text_request",
                &format!(
                    "peer={peer} ok={succeeded} chars={char_count} error={}",
                    response.error.as_deref().unwrap_or("-")
                ),
            );
            if succeeded {
                status.lock().unwrap().last_client = Some(peer.to_string());
            }
        }
        "key" => {
            let key_action = request.action.as_deref().unwrap_or("-").to_string();
            let key_event = request.event.as_deref().unwrap_or("-").to_string();
            let key_repeat = request.repeat.unwrap_or(1);
            let response = match input.lock() {
                Ok(mut controller) => {
                    handle_key_request(&mut controller, request.action, request.event, request.repeat)
                }
                Err(_) => ControlResponse {
                    ok: false,
                    error: Some("无法获取输入控制器".into()),
                },
            };
            let succeeded = response.ok;
            write_control_response(&mut stream, &response);
            logging::info(
                "keyboard",
                "key_request",
                &format!(
                    "peer={peer} ok={succeeded} action={key_action} event={key_event} repeat={key_repeat} error={}",
                    response.error.as_deref().unwrap_or("-")
                ),
            );
            if succeeded {
                status.lock().unwrap().last_client = Some(peer.to_string());
            }
        }
        "fileBegin" => {
            let download_dir = {
                let prefs = preferences.lock().unwrap();
                resolved_download_dir(&app, &prefs)
            };
            let response = match (
                request.transfer_id.as_deref(),
                request.file_name.as_deref(),
                request.file_size,
                request.batch_id.as_deref(),
                request.file_index,
                request.total_files,
            ) {
                (
                    Some(transfer_id),
                    Some(file_name),
                    Some(file_size),
                    Some(batch_id),
                    Some(file_index),
                    Some(total_files),
                ) => match file_transfer.begin_transfer(
                    &request.secret,
                    transfer_id,
                    file_name,
                    file_size,
                    batch_id,
                    file_index,
                    total_files,
                    &download_dir,
                ) {
                    Ok(response) => response,
                    Err(error) => FileBeginResponse {
                        ok: false,
                        error: Some(error),
                        upload_port: None,
                        token: None,
                    },
                },
                _ => FileBeginResponse {
                    ok: false,
                    error: Some("缺少文件传输参数".into()),
                    upload_port: None,
                    token: None,
                },
            };
            logging::info(
                "file_transfer",
                if response.ok { "file_begin" } else { "file_begin_failed" },
                &format!(
                    "peer={peer} ok={} transfer_id={} file={} size={} error={}",
                    response.ok,
                    short_id(request.transfer_id.as_deref().unwrap_or("-")),
                    request.file_name.as_deref().unwrap_or("-"),
                    request.file_size.unwrap_or(0),
                    response.error.as_deref().unwrap_or("-")
                ),
            );
            if response.ok {
                status.lock().unwrap().last_client = Some(peer.to_string());
            }
            write_json_response(&mut stream, &response);
        }
        "fileCommit" => {
            let download_dir = {
                let prefs = preferences.lock().unwrap();
                resolved_download_dir(&app, &prefs)
            };
            let prefs = preferences.lock().unwrap().clone();
            let response = match request.transfer_id.as_deref() {
                Some(transfer_id) => file_transfer.commit_transfer(
                    transfer_id,
                    &download_dir,
                    &prefs,
                    &app,
                ),
                None => FileCommitResponse {
                    ok: false,
                    error: Some("缺少 transferId".into()),
                    saved_path: None,
                },
            };
            logging::info(
                "file_transfer",
                if response.ok { "file_commit" } else { "file_commit_failed" },
                &format!(
                    "peer={peer} ok={} transfer_id={} saved_path={} error={}",
                    response.ok,
                    short_id(request.transfer_id.as_deref().unwrap_or("-")),
                    response.saved_path.as_deref().unwrap_or("-"),
                    response.error.as_deref().unwrap_or("-")
                ),
            );
            if response.ok {
                status.lock().unwrap().last_client = Some(peer.to_string());
            }
            write_json_response(&mut stream, &response);
        }
        "fileCancel" => {
            if let Some(transfer_id) = request.transfer_id.as_deref() {
                logging::info(
                    "file_transfer",
                    "file_cancel",
                    &format!("peer={peer} transfer_id={}", short_id(transfer_id)),
                );
                file_transfer.cancel_transfer(transfer_id);
            }
            write_control_response(
                &mut stream,
                &ControlResponse {
                    ok: true,
                    error: None,
                },
            );
        }
        _ => {
            write_control_response(
                &mut stream,
                &ControlResponse {
                    ok: false,
                    error: Some("未知请求类型".into()),
                },
            );
        }
    }
}

fn write_auth_failure(stream: &mut std::net::TcpStream) {
    write_control_response(
        stream,
        &ControlResponse {
            ok: false,
            error: Some("认证失败".into()),
        },
    );
}

fn spawn_control_loop(
    app: AppHandle,
    listener: TcpListener,
    device: Arc<Mutex<DeviceConfig>>,
    input: Arc<Mutex<InputController>>,
    preferences: Arc<Mutex<AppPreferences>>,
    file_transfer: Arc<FileTransferManager>,
    shutdown: Arc<AtomicBool>,
    status: Arc<Mutex<ReceiverStatus>>,
) -> JoinHandle<()> {
    thread::spawn(move || {
        while !shutdown.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((stream, peer)) => {
                    let app = app.clone();
                    let device = device.clone();
                    let shutdown = shutdown.clone();
                    let status = status.clone();
                    let input = input.clone();
                    let preferences = preferences.clone();
                    let file_transfer = file_transfer.clone();
                    thread::spawn(move || {
                        handle_control_connection(
                            stream,
                            peer,
                            app,
                            device,
                            shutdown,
                            status,
                            input,
                            preferences,
                            file_transfer,
                        );
                    });
                }
                Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(80));
                }
                Err(_) => {
                    thread::sleep(Duration::from_millis(200));
                }
            }
        }
    })
}

fn estimate_rtt(timestamp_micros: u64) -> Option<f64> {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).ok()?.as_micros() as u64;
    if timestamp_micros == 0 || timestamp_micros > now {
        return None;
    }
    Some((now - timestamp_micros) as f64 / 1000.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::device_config::DeviceConfig;

    #[test]
    fn rejects_invalid_control_request_secret() {
        let device = DeviceConfig {
            device_id: "dev-1".into(),
            device_name: "PC".into(),
            pairing_secret: "secret".into(),
        };
        assert!(!device.validate_secret("dev-1", "wrong"));
        assert!(device.validate_secret("dev-1", "secret"));
    }

    #[test]
    fn rejects_empty_text_content() {
        assert!(validate_text_content("").is_err());
        assert!(validate_text_content("   ").is_err());
    }

    #[test]
    fn rejects_overlong_text_content() {
        let text = "a".repeat(MAX_TEXT_CHARS + 1);
        assert!(validate_text_content(&text).is_err());
    }

    #[test]
    fn accepts_valid_text_content() {
        assert_eq!(validate_text_content("  hello  ").unwrap(), "hello");
    }

    #[test]
    fn rejects_overlong_text_bytes() {
        let text = "你".repeat(MAX_TEXT_BYTES);
        assert!(validate_text_content(&text).is_err());
    }

    #[test]
    fn detects_oversize_control_request_without_newline() {
        let buf = vec![b'a'; MAX_CONTROL_REQUEST_BYTES];
        assert!(is_oversize_control_request(buf.len(), &buf));
        let mut with_newline = buf.clone();
        with_newline[MAX_CONTROL_REQUEST_BYTES - 1] = b'\n';
        assert!(!is_oversize_control_request(with_newline.len(), &with_newline));
    }

    #[test]
    fn parses_text_control_request() {
        let json = r#"{"type":"text","deviceId":"dev-1","secret":"secret","content":"你好"}"#;
        let request: ControlRequest = serde_json::from_str(json).unwrap();
        assert_eq!(request.request_type, "text");
        assert_eq!(request.content.as_deref(), Some("你好"));
    }

    #[test]
    fn parses_key_control_request() {
        let json = r#"{"type":"key","deviceId":"dev-1","secret":"secret","action":"backspace"}"#;
        let request: ControlRequest = serde_json::from_str(json).unwrap();
        assert_eq!(request.request_type, "key");
        assert_eq!(request.action.as_deref(), Some("backspace"));
    }

    #[test]
    fn parses_supported_key_actions() {
        use crate::input::{parse_key_action, parse_key_event};
        use enigo::{Key, Direction};

        assert!(matches!(parse_key_action("backspace"), Ok(Key::Backspace)));
        assert!(matches!(parse_key_action("delete"), Ok(Key::Delete)));
        assert!(matches!(parse_key_action("cursor_left"), Ok(Key::LeftArrow)));
        assert!(matches!(parse_key_action("cursor_right"), Ok(Key::RightArrow)));
        assert!(matches!(parse_key_action("a"), Ok(Key::Unicode('a'))));
        assert!(matches!(parse_key_action("ctrl"), Ok(Key::Control)));
        assert!(matches!(parse_key_action("enter"), Ok(Key::Return)));
        assert!(matches!(parse_key_event(None), Ok(Direction::Click)));
        assert!(matches!(parse_key_event(Some("down")), Ok(Direction::Press)));
        assert!(parse_key_event(Some("invalid")).is_err());
    }

    #[test]
    fn parses_key_control_request_with_event() {
        let json = r#"{"type":"key","deviceId":"dev-1","secret":"secret","action":"ctrl","event":"down"}"#;
        let request: ControlRequest = serde_json::from_str(json).unwrap();
        assert_eq!(request.event.as_deref(), Some("down"));
    }

    #[test]
    fn parses_key_control_request_with_repeat() {
        let json = r#"{"type":"key","deviceId":"dev-1","secret":"secret","action":"cursor_left","repeat":5}"#;
        let request: ControlRequest = serde_json::from_str(json).unwrap();
        assert_eq!(request.repeat, Some(5));
    }

    #[test]
    fn key_repeat_is_clamped() {
        use crate::input::{normalize_key_repeat, MAX_KEY_REPEAT};
        assert_eq!(normalize_key_repeat(None), 1);
        assert_eq!(normalize_key_repeat(Some(0)), 1);
        assert_eq!(normalize_key_repeat(Some(5)), 5);
        assert_eq!(normalize_key_repeat(Some(999)), MAX_KEY_REPEAT);
    }

    #[test]
    fn parses_file_begin_control_request() {
        let json = r#"{"type":"fileBegin","deviceId":"dev-1","secret":"secret","transferId":"550e8400-e29b-41d4-a716-446655440000","fileName":"photo.jpg","fileSize":1234,"batchId":"batch-1","fileIndex":1,"totalFiles":2}"#;
        let request: ControlRequest = serde_json::from_str(json).unwrap();
        assert_eq!(request.request_type, "fileBegin");
        assert_eq!(request.file_name.as_deref(), Some("photo.jpg"));
        assert_eq!(request.file_size, Some(1234));
        assert_eq!(request.total_files, Some(2));
    }

    #[test]
    fn discover_v2_request_auth_matches_protocol() {
        use phonepad_protocol::{discover_request_auth, discover_response_auth};

        let secret = "secret123";
        let nonce = 42u32;
        let request = DiscoverRequest {
            request_type: "discover".into(),
            version: 2,
            device_id: "dev-1".into(),
            nonce,
            auth: discover_request_auth(secret, nonce),
        };
        assert_eq!(request.auth, discover_request_auth(secret, nonce));
        let response = DiscoverResponse::new(
            "dev-1".into(),
            "PC".into(),
            "10.0.0.1".into(),
            secret,
            nonce,
        );
        assert_eq!(response.auth, discover_response_auth(secret, nonce));
    }
}
