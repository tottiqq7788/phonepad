use serde::{Deserialize, Serialize};

pub const MAGIC: [u8; 2] = *b"TP";
pub const VERSION: u8 = 1;
pub const PACKET_LEN: usize = 24;
pub const UDP_INPUT_PORT: u16 = 45454;
pub const TCP_CONTROL_PORT: u16 = 45455;
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
pub struct InputPacket {
    pub kind: PacketKind,
    pub sequence: u32,
    pub timestamp_micros: u64,
    pub x: i16,
    pub y: i16,
    pub button: Option<MouseButton>,
    pub action: Option<ButtonAction>,
    pub fingers: u8,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProtocolError {
    InvalidLength(usize),
    InvalidMagic,
    UnsupportedVersion(u8),
    UnknownKind(u8),
    UnknownButton(u8),
    UnknownButtonAction(u8),
}

impl InputPacket {
    pub fn movement(kind: PacketKind, sequence: u32, timestamp_micros: u64, x: i16, y: i16, fingers: u8) -> Self {
        Self {
            kind,
            sequence,
            timestamp_micros,
            x,
            y,
            button: None,
            action: None,
            fingers,
        }
    }

    pub fn click(sequence: u32, timestamp_micros: u64, button: MouseButton) -> Self {
        Self {
            kind: PacketKind::Click,
            sequence,
            timestamp_micros,
            x: 0,
            y: 0,
            button: Some(button),
            action: Some(ButtonAction::Click),
            fingers: 0,
        }
    }

    pub fn button(sequence: u32, timestamp_micros: u64, button: MouseButton, action: ButtonAction) -> Self {
        Self {
            kind: PacketKind::Button,
            sequence,
            timestamp_micros,
            x: 0,
            y: 0,
            button: Some(button),
            action: Some(action),
            fingers: 0,
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
        out[20] = self.button.map(|button| button as u8).unwrap_or(0);
        out[21] = self.action.map(|action| action as u8).unwrap_or(0);
        out[22] = self.fingers;
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
        let button = if input[20] == 0 { None } else { Some(MouseButton::try_from(input[20])?) };
        let action = if input[21] == 0 { None } else { Some(ButtonAction::try_from(input[21])?) };

        Ok(Self {
            kind,
            sequence,
            timestamp_micros,
            x,
            y,
            button,
            action,
            fingers: input[22],
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_move_packet() {
        let packet = InputPacket::movement(PacketKind::Move, 42, 123_456, -7, 9, 1);
        let decoded = InputPacket::decode(&packet.encode()).unwrap();
        assert_eq!(decoded, packet);
    }

    #[test]
    fn round_trips_click_packet() {
        let packet = InputPacket::click(43, 123_999, MouseButton::Right);
        let decoded = InputPacket::decode(&packet.encode()).unwrap();
        assert_eq!(decoded, packet);
    }
}
