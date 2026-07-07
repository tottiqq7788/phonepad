use serde::{Deserialize, Serialize};

pub const MAGIC: [u8; 2] = *b"TP";
pub const VERSION: u8 = 2;
pub const PACKET_LEN: usize = 32;
pub const UDP_INPUT_PORT: u16 = 45454;
pub const TCP_CONTROL_PORT: u16 = 45455;
pub const TCP_FILE_PORT: u16 = 45457;
pub const UDP_DISCOVERY_PORT: u16 = 45456;
pub const DISCOVERY_REQUEST: &[u8] = b"PHONEPAD_DISCOVER_V1";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[repr(u8)]
pub enum PacketKind {
    Move = 1,
    Scroll = 2,
    Click = 3,
    Button = 4,
    Ping = 5,
    Pong = 6,
    Gesture = 7,
}

impl TryFrom<u8> for PacketKind {
    type Error = ProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Move),
            2 => Ok(Self::Scroll),
            3 => Ok(Self::Click),
            4 => Ok(Self::Button),
            5 => Ok(Self::Ping),
            6 => Ok(Self::Pong),
            7 => Ok(Self::Gesture),
            _ => Err(ProtocolError::UnknownKind(value)),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[repr(u8)]
pub enum MouseButton {
    Left = 1,
    Right = 2,
    Middle = 3,
}

impl TryFrom<u8> for MouseButton {
    type Error = ProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Left),
            2 => Ok(Self::Right),
            3 => Ok(Self::Middle),
            _ => Err(ProtocolError::UnknownButton(value)),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[repr(u8)]
pub enum ButtonAction {
    Down = 1,
    Up = 2,
    Click = 3,
}

impl TryFrom<u8> for ButtonAction {
    type Error = ProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Down),
            2 => Ok(Self::Up),
            3 => Ok(Self::Click),
            _ => Err(ProtocolError::UnknownButtonAction(value)),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[repr(u8)]
pub enum GestureKind {
    SwipeLeft = 1,
    SwipeRight = 2,
    SwipeUp = 3,
    SwipeDown = 4,
    Pinch = 5,
}

impl TryFrom<u8> for GestureKind {
    type Error = ProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::SwipeLeft),
            2 => Ok(Self::SwipeRight),
            3 => Ok(Self::SwipeUp),
            4 => Ok(Self::SwipeDown),
            5 => Ok(Self::Pinch),
            _ => Err(ProtocolError::UnknownGestureKind(value)),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[repr(u8)]
pub enum GesturePhase {
    Begin = 1,
    Update = 2,
    End = 3,
    Cancel = 4,
}

impl TryFrom<u8> for GesturePhase {
    type Error = ProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Begin),
            2 => Ok(Self::Update),
            3 => Ok(Self::End),
            4 => Ok(Self::Cancel),
            _ => Err(ProtocolError::UnknownGesturePhase(value)),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct InputPacket {
    pub kind: PacketKind,
    pub sequence: u32,
    pub timestamp_micros: u64,
    pub x: i16,
    pub y: i16,
    pub button: Option<MouseButton>,
    pub action: Option<ButtonAction>,
    pub gesture_kind: Option<GestureKind>,
    pub gesture_phase: Option<GesturePhase>,
    pub fingers: u8,
    pub auth_token: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProtocolError {
    InvalidLength(usize),
    InvalidMagic,
    UnsupportedVersion(u8),
    UnknownKind(u8),
    UnknownButton(u8),
    UnknownButtonAction(u8),
    UnknownGestureKind(u8),
    UnknownGesturePhase(u8),
}

impl InputPacket {
    pub fn movement(
        kind: PacketKind,
        sequence: u32,
        timestamp_micros: u64,
        x: i16,
        y: i16,
        fingers: u8,
        auth_token: u64,
    ) -> Self {
        Self {
            kind,
            sequence,
            timestamp_micros,
            x,
            y,
            button: None,
            action: None,
            gesture_kind: None,
            gesture_phase: None,
            fingers,
            auth_token,
        }
    }

    pub fn click(
        sequence: u32,
        timestamp_micros: u64,
        button: MouseButton,
        auth_token: u64,
    ) -> Self {
        Self {
            kind: PacketKind::Click,
            sequence,
            timestamp_micros,
            x: 0,
            y: 0,
            button: Some(button),
            action: Some(ButtonAction::Click),
            gesture_kind: None,
            gesture_phase: None,
            fingers: 0,
            auth_token,
        }
    }

    pub fn button(
        sequence: u32,
        timestamp_micros: u64,
        button: MouseButton,
        action: ButtonAction,
        auth_token: u64,
    ) -> Self {
        Self {
            kind: PacketKind::Button,
            sequence,
            timestamp_micros,
            x: 0,
            y: 0,
            button: Some(button),
            action: Some(action),
            gesture_kind: None,
            gesture_phase: None,
            fingers: 0,
            auth_token,
        }
    }

    pub fn gesture(
        sequence: u32,
        timestamp_micros: u64,
        gesture_kind: GestureKind,
        gesture_phase: GesturePhase,
        fingers: u8,
        auth_token: u64,
        amount: i16,
    ) -> Self {
        Self {
            kind: PacketKind::Gesture,
            sequence,
            timestamp_micros,
            x: amount,
            y: 0,
            button: None,
            action: None,
            gesture_kind: Some(gesture_kind),
            gesture_phase: Some(gesture_phase),
            fingers,
            auth_token,
        }
    }

    pub fn encode(self) -> [u8; PACKET_LEN] {
        let mut out = [0u8; PACKET_LEN];
        out[0..2].copy_from_slice(&MAGIC);
        out[2] = VERSION;
        out[3] = self.kind as u8;
        out[4..8].copy_from_slice(&self.sequence.to_le_bytes());
        out[8..16].copy_from_slice(&self.timestamp_micros.to_le_bytes());
        out[16..18].copy_from_slice(&self.x.to_le_bytes());
        out[18..20].copy_from_slice(&self.y.to_le_bytes());
        out[20] = match self.kind {
            PacketKind::Gesture => self.gesture_kind.map(|value| value as u8).unwrap_or(0),
            _ => self.button.map(|button| button as u8).unwrap_or(0),
        };
        out[21] = match self.kind {
            PacketKind::Gesture => self.gesture_phase.map(|value| value as u8).unwrap_or(0),
            _ => self.action.map(|action| action as u8).unwrap_or(0),
        };
        out[22] = self.fingers;
        out[24..32].copy_from_slice(&self.auth_token.to_le_bytes());
        out
    }

    pub fn decode(input: &[u8]) -> Result<Self, ProtocolError> {
        if input.len() < PACKET_LEN {
            return Err(ProtocolError::InvalidLength(input.len()));
        }
        if input[0..2] != MAGIC {
            return Err(ProtocolError::InvalidMagic);
        }
        if input[2] != VERSION {
            return Err(ProtocolError::UnsupportedVersion(input[2]));
        }

        let kind = PacketKind::try_from(input[3])?;
        let sequence = u32::from_le_bytes(input[4..8].try_into().unwrap());
        let timestamp_micros = u64::from_le_bytes(input[8..16].try_into().unwrap());
        let x = i16::from_le_bytes(input[16..18].try_into().unwrap());
        let y = i16::from_le_bytes(input[18..20].try_into().unwrap());
        let (button, action, gesture_kind, gesture_phase) = if kind == PacketKind::Gesture {
            let gesture_kind = if input[20] == 0 {
                None
            } else {
                Some(GestureKind::try_from(input[20])?)
            };
            let gesture_phase = if input[21] == 0 {
                None
            } else {
                Some(GesturePhase::try_from(input[21])?)
            };
            (None, None, gesture_kind, gesture_phase)
        } else {
            let button = if input[20] == 0 {
                None
            } else {
                Some(MouseButton::try_from(input[20])?)
            };
            let action = if input[21] == 0 {
                None
            } else {
                Some(ButtonAction::try_from(input[21])?)
            };
            (button, action, None, None)
        };
        let auth_token = u64::from_le_bytes(input[24..32].try_into().unwrap());

        Ok(Self {
            kind,
            sequence,
            timestamp_micros,
            x,
            y,
            button,
            action,
            gesture_kind,
            gesture_phase,
            fingers: input[22],
            auth_token,
        })
    }
}

pub fn auth_token(secret: &str, sequence: u32) -> u64 {
    let mut bytes = secret.as_bytes().to_vec();
    bytes.extend_from_slice(&sequence.to_le_bytes());
    fnv1a64(&bytes)
}

pub fn discover_request_auth(secret: &str, nonce: u32) -> i64 {
    auth_token(secret, nonce) as i64
}

pub fn file_transfer_token(secret: &str, transfer_id: &str) -> u64 {
    let mut bytes = secret.as_bytes().to_vec();
    bytes.extend_from_slice(transfer_id.as_bytes());
    fnv1a64(&bytes)
}

pub fn discover_response_auth(secret: &str, nonce: u32) -> i64 {
    auth_token(secret, nonce.wrapping_add(1)) as i64
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoverRequest {
    #[serde(rename = "type")]
    pub request_type: String,
    #[serde(default)]
    pub version: u32,
    pub device_id: String,
    pub nonce: u32,
    pub auth: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoverResponse {
    #[serde(rename = "type")]
    pub response_type: String,
    pub device_id: String,
    pub name: String,
    pub ip: String,
    pub tcp_port: u16,
    pub udp_port: u16,
    pub discovery_port: u16,
    pub auth: i64,
}

impl DiscoverResponse {
    pub fn new(
        device_id: String,
        name: String,
        ip: String,
        secret: &str,
        request_nonce: u32,
    ) -> Self {
        Self {
            response_type: "discoverResponse".into(),
            device_id,
            name,
            ip,
            tcp_port: TCP_CONTROL_PORT,
            udp_port: UDP_INPUT_PORT,
            discovery_port: UDP_DISCOVERY_PORT,
            auth: discover_response_auth(secret, request_nonce),
        }
    }
}

fn fnv1a64(data: &[u8]) -> u64 {
    let mut hash: u64 = 0xcbf29ce484222325;
    for byte in data {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_move_packet() {
        let token = auth_token("secret123", 42);
        let packet = InputPacket::movement(PacketKind::Move, 42, 123_456, -7, 9, 1, token);
        let decoded = InputPacket::decode(&packet.encode()).unwrap();
        assert_eq!(decoded, packet);
    }

    #[test]
    fn move_packet_matches_android_golden_vector() {
        let token = auth_token("secret123", 42);
        let packet = InputPacket::movement(PacketKind::Move, 42, 123_456, -7, 9, 1, token);
        assert_eq!(packet.encode()[0..4], [0x54, 0x50, 0x02, 0x01]);
        assert_eq!(packet.encode()[24..32], token.to_le_bytes());
    }

    #[test]
    fn round_trips_gesture_packet() {
        let token = auth_token("secret123", 44);
        let packet = InputPacket::gesture(
            44,
            123_456,
            GestureKind::SwipeDown,
            GesturePhase::End,
            3,
            token,
            0,
        );
        let decoded = InputPacket::decode(&packet.encode()).unwrap();
        assert_eq!(decoded, packet);
        assert_eq!(decoded.gesture_kind, Some(GestureKind::SwipeDown));
        assert_eq!(decoded.gesture_phase, Some(GesturePhase::End));
    }

    #[test]
    fn auth_token_golden_vector() {
        assert_eq!(auth_token("secret123", 42), 0x23B3FBC2_5869_9E45);
    }

    #[test]
    fn click_packet_matches_android_golden_vector() {
        let token = auth_token("secret123", 43);
        let packet = InputPacket::click(43, 123_999, MouseButton::Right, token);
        let bytes = packet.encode();
        assert_eq!(bytes[3], PacketKind::Click as u8);
        assert_eq!(bytes[20], MouseButton::Right as u8);
        assert_eq!(bytes[24..32], token.to_le_bytes());
    }

    #[test]
    fn file_transfer_token_is_deterministic() {
        assert_eq!(
            file_transfer_token("secret", "transfer-1"),
            file_transfer_token("secret", "transfer-1")
        );
        assert_ne!(
            file_transfer_token("secret", "transfer-1"),
            file_transfer_token("secret", "transfer-2")
        );
    }

    #[test]
    fn discover_v2_auth_round_trip() {
        let secret = "secret123";
        let nonce = 42u32;
        let request_auth = discover_request_auth(secret, nonce);
        assert_eq!(request_auth, auth_token(secret, nonce) as i64);
        let response_auth = discover_response_auth(secret, nonce);
        assert_eq!(response_auth, auth_token(secret, nonce.wrapping_add(1)) as i64);
        assert_ne!(request_auth, response_auth);
    }

    #[test]
    fn discover_v2_auth_serializes_signed_json() {
        let secret = "pair-secret";
        let mut high_bit_nonce = None;
        for nonce in 1..10_000u32 {
            if auth_token(secret, nonce) > i64::MAX as u64 {
                high_bit_nonce = Some(nonce);
                break;
            }
        }
        let nonce = high_bit_nonce.expect("expected a nonce with auth > i64::MAX");
        let request = DiscoverRequest {
            request_type: "discover".into(),
            version: 2,
            device_id: "dev-1".into(),
            nonce,
            auth: discover_request_auth(secret, nonce),
        };
        assert!(request.auth < 0);
        let json = serde_json::to_string(&request).unwrap();
        let parsed: DiscoverRequest = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.auth, request.auth);
    }

    #[test]
    fn discover_v2_request_json() {
        let secret = "secret123";
        let nonce = 7u32;
        let request = DiscoverRequest {
            request_type: "discover".into(),
            version: 2,
            device_id: "dev-1".into(),
            nonce,
            auth: discover_request_auth(secret, nonce),
        };
        let json = serde_json::to_string(&request).unwrap();
        assert!(json.contains("\"type\":\"discover\""));
        assert!(json.contains("\"deviceId\":\"dev-1\""));
        let parsed: DiscoverRequest = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, request);
    }
}
