use enigo::{Axis, Button, Coordinate, Direction, Enigo, Keyboard, Mouse, Settings};
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
