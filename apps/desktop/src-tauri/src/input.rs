use enigo::{Axis, Button, Coordinate, Direction, Enigo, Key, Keyboard, Mouse, Settings};
use phonepad_protocol::{ButtonAction, MouseButton};

use crate::settings::ReceiverSettings;

pub struct InputController {
    enigo: Enigo,
    scroll_remainder_x: f64,
    scroll_remainder_y: f64,
}

impl InputController {
    pub fn new() -> Result<Self, String> {
        let enigo = Enigo::new(&Settings::default()).map_err(|err| err.to_string())?;
        Ok(Self {
            enigo,
            scroll_remainder_x: 0.0,
            scroll_remainder_y: 0.0,
        })
    }

    pub fn move_mouse(&mut self, dx: i16, dy: i16, settings: &ReceiverSettings) -> Result<(), String> {
        let (x, y) = scale_motion(dx, dy, settings.sensitivity, settings.acceleration);
        if x == 0 && y == 0 {
            return Ok(());
        }
        self.enigo
            .move_mouse(x, y, Coordinate::Rel)
            .map_err(|err| err.to_string())
    }

    pub fn scroll(&mut self, dx: i16, dy: i16, settings: &ReceiverSettings) -> Result<(), String> {
        self.scroll_remainder_x += f64::from(dx) * settings.scroll_sensitivity / 8.0;
        self.scroll_remainder_y += f64::from(-dy) * settings.scroll_sensitivity / 8.0;
        let horizontal = self.scroll_remainder_x.trunc() as i32;
        let vertical = self.scroll_remainder_y.trunc() as i32;

        if horizontal != 0 {
            self.scroll_remainder_x -= f64::from(horizontal);
            self.enigo
                .scroll(horizontal, Axis::Horizontal)
                .map_err(|err| err.to_string())?;
        }
        if vertical != 0 {
            self.scroll_remainder_y -= f64::from(vertical);
            self.enigo
                .scroll(vertical, Axis::Vertical)
                .map_err(|err| err.to_string())?;
        }
        Ok(())
    }

    pub fn click(&mut self, button: MouseButton) -> Result<(), String> {
        self.enigo
            .button(to_enigo_button(button), Direction::Click)
            .map_err(|err| err.to_string())
    }

    pub fn button(&mut self, button: MouseButton, action: ButtonAction) -> Result<(), String> {
        let direction = match action {
            ButtonAction::Down => Direction::Press,
            ButtonAction::Up => Direction::Release,
            ButtonAction::Click => Direction::Click,
        };
        self.enigo
            .button(to_enigo_button(button), direction)
            .map_err(|err| err.to_string())
    }

    pub fn type_text(&mut self, text: &str) -> Result<(), String> {
        if text.is_empty() {
            return Ok(());
        }
        self.enigo.text(text).map_err(|err| err.to_string())
    }

    pub fn press_key(&mut self, key: Key, repeat: u32) -> Result<(), String> {
        for _ in 0..repeat {
            self.enigo
                .key(key, Direction::Click)
                .map_err(|err| err.to_string())?;
        }
        Ok(())
    }

    pub fn key_event(&mut self, key: Key, direction: Direction) -> Result<(), String> {
        self.enigo.key(key, direction).map_err(|err| err.to_string())
    }
}

pub const MAX_KEY_REPEAT: u32 = 20;

pub fn normalize_key_repeat(repeat: Option<u32>) -> u32 {
    repeat.unwrap_or(1).clamp(1, MAX_KEY_REPEAT)
}

pub fn parse_key_event(event: Option<&str>) -> Result<Direction, String> {
    match event.unwrap_or("click") {
        "down" | "press" => Ok(Direction::Press),
        "up" | "release" => Ok(Direction::Release),
        "click" => Ok(Direction::Click),
        other => Err(format!("未知按键事件: {other}")),
    }
}

pub fn parse_key_action(action: &str) -> Result<Key, String> {
    if action.len() == 1 {
        let ch = action.chars().next().unwrap();
        return Ok(Key::Unicode(ch));
    }

    match action {
        "backspace" => Ok(Key::Backspace),
        "delete" => Ok(Key::Delete),
        "enter" | "return" => Ok(Key::Return),
        "tab" => Ok(Key::Tab),
        "esc" | "escape" => Ok(Key::Escape),
        "space" => Ok(Key::Space),
        "ctrl" | "control" | "lcontrol" => Ok(Key::Control),
        "shift" | "lshift" => Ok(Key::Shift),
        "alt" | "lmenu" => Ok(Key::Alt),
        "meta" | "win" | "windows" | "lwin" => Ok(Key::Meta),
        "cursor_left" | "left" => Ok(Key::LeftArrow),
        "cursor_right" | "right" => Ok(Key::RightArrow),
        "cursor_up" | "up" => Ok(Key::UpArrow),
        "cursor_down" | "down" => Ok(Key::DownArrow),
        "home" => Ok(Key::Home),
        "end" => Ok(Key::End),
        "page_up" | "pageup" => Ok(Key::PageUp),
        "page_down" | "pagedown" => Ok(Key::PageDown),
        "caps_lock" | "capslock" => Ok(Key::CapsLock),
        "minus" | "dash" => Ok(Key::Unicode('-')),
        "equal" | "equals" => Ok(Key::Unicode('=')),
        "left_bracket" | "lbracket" => Ok(Key::Unicode('[')),
        "right_bracket" | "rbracket" => Ok(Key::Unicode(']')),
        "backslash" => Ok(Key::Unicode('\\')),
        "semicolon" => Ok(Key::Unicode(';')),
        "quote" | "apostrophe" => Ok(Key::Unicode('\'')),
        "comma" => Ok(Key::Unicode(',')),
        "period" | "dot" => Ok(Key::Unicode('.')),
        "slash" | "forward_slash" => Ok(Key::Unicode('/')),
        "grave" | "backtick" => Ok(Key::Unicode('`')),
        "f1" => Ok(Key::F1),
        "f2" => Ok(Key::F2),
        "f3" => Ok(Key::F3),
        "f4" => Ok(Key::F4),
        "f5" => Ok(Key::F5),
        "f6" => Ok(Key::F6),
        "f7" => Ok(Key::F7),
        "f8" => Ok(Key::F8),
        "f9" => Ok(Key::F9),
        "f10" => Ok(Key::F10),
        "f11" => Ok(Key::F11),
        "f12" => Ok(Key::F12),
        other => Err(format!("未知按键动作: {other}")),
    }
}

fn to_enigo_button(button: MouseButton) -> Button {
    match button {
        MouseButton::Left => Button::Left,
        MouseButton::Right => Button::Right,
        MouseButton::Middle => Button::Middle,
    }
}

fn scale_motion(dx: i16, dy: i16, sensitivity: f64, acceleration: f64) -> (i32, i32) {
    let magnitude = f64::from(dx).hypot(f64::from(dy));
    let accel = 1.0 + (magnitude / 18.0).min(6.0) * acceleration;
    (
        (f64::from(dx) * sensitivity * accel).round() as i32,
        (f64::from(dy) * sensitivity * accel).round() as i32,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_letter_and_modifier_keys() {
        assert!(matches!(parse_key_action("a"), Ok(Key::Unicode('a'))));
        assert!(matches!(parse_key_action("A"), Ok(Key::Unicode('A'))));
        assert!(matches!(parse_key_action("ctrl"), Ok(Key::Control)));
        assert!(matches!(parse_key_action("meta"), Ok(Key::Meta)));
        assert!(matches!(parse_key_action("enter"), Ok(Key::Return)));
        assert!(matches!(parse_key_action("down"), Ok(Key::DownArrow)));
    }

    #[test]
    fn parses_key_events() {
        assert!(matches!(parse_key_event(None), Ok(Direction::Click)));
        assert!(matches!(parse_key_event(Some("down")), Ok(Direction::Press)));
        assert!(matches!(parse_key_event(Some("up")), Ok(Direction::Release)));
        assert!(parse_key_event(Some("invalid")).is_err());
    }
}
