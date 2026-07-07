use enigo::Key;
use phonepad_protocol::{GestureKind, GesturePhase};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GestureAction {
    Chord(Vec<Key>),
    Screenshot,
    ShowDesktop,
    PinchZoom { direction: PinchDirection },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PinchDirection {
    In,
    Out,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DesktopPlatform {
    Windows,
    MacOs,
    Other,
}

pub fn detect_platform() -> DesktopPlatform {
    #[cfg(target_os = "windows")]
    {
        DesktopPlatform::Windows
    }
    #[cfg(target_os = "macos")]
    {
        DesktopPlatform::MacOs
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos")))]
    {
        DesktopPlatform::Other
    }
}

pub fn should_execute_gesture(phase: GesturePhase) -> bool {
    matches!(phase, GesturePhase::End)
}

pub fn map_gesture(
    kind: GestureKind,
    fingers: u8,
    phase: GesturePhase,
    platform: DesktopPlatform,
) -> Option<GestureAction> {
    if !should_execute_gesture(phase) {
        return None;
    }

    match kind {
        GestureKind::Pinch => None,
        GestureKind::SwipeUp => match fingers {
            3 | 4 => Some(task_view_action(platform)),
            _ => None,
        },
        GestureKind::SwipeDown => match fingers {
            3 => Some(GestureAction::Screenshot),
            4 => Some(show_desktop_action(platform)),
            _ => None,
        },
        GestureKind::SwipeLeft => match fingers {
            4 => Some(switch_space_left(platform)),
            _ => None,
        },
        GestureKind::SwipeRight => match fingers {
            4 => Some(switch_space_right(platform)),
            _ => None,
        },
    }
}

pub fn map_pinch(amount: i16, sensitivity: f64) -> Option<PinchDirection> {
    let threshold = (24.0 / sensitivity.max(0.2)).round() as i16;
    if amount >= threshold {
        Some(PinchDirection::Out)
    } else if amount <= -threshold {
        Some(PinchDirection::In)
    } else {
        None
    }
}

fn task_view_action(platform: DesktopPlatform) -> GestureAction {
    match platform {
        DesktopPlatform::Windows => GestureAction::Chord(vec![Key::Meta, Key::Tab]),
        DesktopPlatform::MacOs => GestureAction::Chord(vec![Key::Control, Key::UpArrow]),
        DesktopPlatform::Other => GestureAction::Chord(vec![Key::Control, Key::UpArrow]),
    }
}

fn show_desktop_action(platform: DesktopPlatform) -> GestureAction {
    let _ = platform;
    GestureAction::ShowDesktop
}

fn switch_space_left(platform: DesktopPlatform) -> GestureAction {
    match platform {
        DesktopPlatform::Windows => {
            GestureAction::Chord(vec![Key::Control, Key::Meta, Key::LeftArrow])
        }
        DesktopPlatform::MacOs => GestureAction::Chord(vec![Key::Control, Key::LeftArrow]),
        DesktopPlatform::Other => GestureAction::Chord(vec![Key::Control, Key::LeftArrow]),
    }
}

fn switch_space_right(platform: DesktopPlatform) -> GestureAction {
    match platform {
        DesktopPlatform::Windows => {
            GestureAction::Chord(vec![Key::Control, Key::Meta, Key::RightArrow])
        }
        DesktopPlatform::MacOs => GestureAction::Chord(vec![Key::Control, Key::RightArrow]),
        DesktopPlatform::Other => GestureAction::Chord(vec![Key::Control, Key::RightArrow]),
    }
}

pub fn pinch_zoom_keys(platform: DesktopPlatform, direction: PinchDirection) -> Vec<Key> {
    match (platform, direction) {
        (DesktopPlatform::MacOs, PinchDirection::In) => vec![Key::Meta, Key::Unicode('-')],
        (DesktopPlatform::MacOs, PinchDirection::Out) => vec![Key::Meta, Key::Unicode('=')],
        (_, PinchDirection::In) => vec![Key::Control],
        (_, PinchDirection::Out) => vec![Key::Control],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_three_finger_swipe_up_to_task_view() {
        let action = map_gesture(
            GestureKind::SwipeUp,
            3,
            GesturePhase::End,
            DesktopPlatform::Windows,
        );
        assert_eq!(
            action,
            Some(GestureAction::Chord(vec![Key::Meta, Key::Tab]))
        );
    }

    #[test]
    fn maps_three_finger_swipe_down_to_screenshot() {
        let action = map_gesture(
            GestureKind::SwipeDown,
            3,
            GesturePhase::End,
            DesktopPlatform::Windows,
        );
        assert_eq!(action, Some(GestureAction::Screenshot));
    }

    #[test]
    fn maps_four_finger_swipe_down_to_show_desktop() {
        let action = map_gesture(
            GestureKind::SwipeDown,
            4,
            GesturePhase::End,
            DesktopPlatform::MacOs,
        );
        assert_eq!(action, Some(GestureAction::ShowDesktop));
    }

    #[test]
    fn maps_four_finger_horizontal_swipes_on_macos() {
        let left = map_gesture(
            GestureKind::SwipeLeft,
            4,
            GesturePhase::End,
            DesktopPlatform::MacOs,
        );
        let right = map_gesture(
            GestureKind::SwipeRight,
            4,
            GesturePhase::End,
            DesktopPlatform::MacOs,
        );
        assert_eq!(
            left,
            Some(GestureAction::Chord(vec![Key::Control, Key::LeftArrow]))
        );
        assert_eq!(
            right,
            Some(GestureAction::Chord(vec![Key::Control, Key::RightArrow]))
        );
    }

    #[test]
    fn ignores_non_end_phases() {
        assert!(map_gesture(
            GestureKind::SwipeUp,
            3,
            GesturePhase::Update,
            DesktopPlatform::Windows,
        )
        .is_none());
    }

    #[test]
    fn pinch_threshold_respects_sensitivity() {
        assert_eq!(map_pinch(30, 1.0), Some(PinchDirection::Out));
        assert_eq!(map_pinch(-30, 1.0), Some(PinchDirection::In));
        assert_eq!(map_pinch(10, 1.0), None);
    }
}
