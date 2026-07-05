use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct FocusSnapshot {
    pub editable: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub app_name: Option<String>,
}

pub trait FocusDetector: Send {
    fn snapshot(&mut self) -> FocusSnapshot;
}

struct StubFocusDetector;

impl FocusDetector for StubFocusDetector {
    fn snapshot(&mut self) -> FocusSnapshot {
        FocusSnapshot::default()
    }
}

pub fn create_detector() -> Box<dyn FocusDetector> {
    #[cfg(windows)]
    {
        if let Ok(detector) = WindowsFocusDetector::new() {
            return Box::new(detector);
        }
    }
    #[cfg(target_os = "macos")]
    {
        if let Ok(detector) = MacFocusDetector::new() {
            return Box::new(detector);
        }
    }
    Box::new(StubFocusDetector)
}

#[cfg(windows)]
mod windows_impl {
    use super::{FocusDetector, FocusSnapshot};
    use std::ffi::OsString;
    use std::os::windows::ffi::OsStringExt;
    use windows::Win32::Foundation::HWND;
    use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED};
    use windows::Win32::System::Threading::{AttachThreadInput, GetCurrentThreadId};
    use windows::Win32::UI::Accessibility::{
        CUIAutomation, IUIAutomation, IUIAutomationElement, UIA_ComboBoxControlTypeId, UIA_DocumentControlTypeId,
        UIA_EditControlTypeId,
    };
    use windows::Win32::UI::Input::KeyboardAndMouse::GetFocus;
    use windows::Win32::UI::WindowsAndMessaging::{
        GetClassNameW, GetForegroundWindow, GetWindowTextW, GetWindowThreadProcessId,
    };

    pub struct WindowsFocusDetector {
        com_initialized: bool,
    }

    impl WindowsFocusDetector {
        pub fn new() -> Result<Self, String> {
            let hr = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) };
            Ok(Self {
                com_initialized: hr.is_ok() && hr == windows::core::HRESULT(0),
            })
        }

        fn hwnd_class(hwnd: HWND) -> String {
            let mut buffer = [0u16; 256];
            let len = unsafe { GetClassNameW(hwnd, &mut buffer) };
            if len <= 0 {
                return String::new();
            }
            OsString::from_wide(&buffer[..len as usize])
                .to_string_lossy()
                .into_owned()
        }

        fn hwnd_text(hwnd: HWND) -> String {
            let mut buffer = [0u16; 512];
            let len = unsafe { GetWindowTextW(hwnd, &mut buffer) };
            if len <= 0 {
                return String::new();
            }
            OsString::from_wide(&buffer[..len as usize])
                .to_string_lossy()
                .into_owned()
        }

        fn focused_hwnd() -> Option<HWND> {
            let foreground = unsafe { GetForegroundWindow() };
            if foreground.0.is_null() {
                return None;
            }
            let fg_thread = unsafe { GetWindowThreadProcessId(foreground, None) };
            let current_thread = unsafe { GetCurrentThreadId() };
            let attached = if fg_thread != current_thread {
                unsafe { AttachThreadInput(current_thread, fg_thread, true).as_bool() }
            } else {
                false
            };
            let focus = unsafe { GetFocus() };
            if attached {
                let _ = unsafe { AttachThreadInput(current_thread, fg_thread, false) };
            }
            if focus.0.is_null() {
                None
            } else {
                Some(focus)
            }
        }

        fn foreground_app_name() -> Option<String> {
            let name = Self::hwnd_text(unsafe { GetForegroundWindow() }).trim().to_string();
            if name.is_empty() { None } else { Some(name) }
        }

        fn uia_editable(&self) -> Option<bool> {
            (|| {
                let automation: IUIAutomation =
                    unsafe { CoCreateInstance(&CUIAutomation, None, CLSCTX_INPROC_SERVER).ok()? };
                let element: IUIAutomationElement = unsafe { automation.GetFocusedElement().ok()? };
                let control_type = unsafe { element.CurrentControlType().ok()? }.0;
                let focusable = unsafe { element.CurrentIsKeyboardFocusable().ok()?.as_bool() };
                let password = unsafe { element.CurrentIsPassword().ok()?.as_bool() };
                let editable_types = [
                    UIA_EditControlTypeId.0,
                    UIA_DocumentControlTypeId.0,
                    UIA_ComboBoxControlTypeId.0,
                ];
                Some(focusable && !password && editable_types.contains(&control_type))
            })()
        }

        fn class_editable(hwnd: HWND) -> bool {
            let class_name = Self::hwnd_class(hwnd).to_ascii_lowercase();
            if class_name.is_empty() {
                return false;
            }
            const EDITABLE: &[&str] = &[
                "edit",
                "richedit",
                "riched20w",
                "riched50w",
                "scintilla",
                "chrome_omniboxview",
                "internet autocomplete",
            ];
            EDITABLE.iter().any(|needle| class_name.contains(needle))
        }
    }

    impl FocusDetector for WindowsFocusDetector {
        fn snapshot(&mut self) -> FocusSnapshot {
            let app_name = Self::foreground_app_name();
            if let Some(editable) = self.uia_editable() {
                return FocusSnapshot { editable, app_name };
            }
            let hwnd = match Self::focused_hwnd() {
                Some(hwnd) => hwnd,
                None => return FocusSnapshot { editable: false, app_name },
            };
            FocusSnapshot {
                editable: Self::class_editable(hwnd),
                app_name,
            }
        }
    }

    impl Drop for WindowsFocusDetector {
        fn drop(&mut self) {
            if self.com_initialized {
                unsafe { windows::Win32::System::Com::CoUninitialize() };
            }
        }
    }
}

#[cfg(windows)]
use windows_impl::WindowsFocusDetector;

#[cfg(target_os = "macos")]
mod macos_impl {
    use super::{FocusDetector, FocusSnapshot};
    use core_foundation::string::CFString;
    use core_foundation_sys::base::{kCFAllocatorDefault, Boolean, CFRelease, CFTypeRef};
    use core_foundation_sys::string::CFStringRef;
    use std::ffi::CString;
    use std::ptr;

    type AXUIElementRef = CFTypeRef;

    #[link(name = "ApplicationServices", kind = "framework")]
    extern "C" {
        fn AXUIElementCreateSystemWide() -> AXUIElementRef;
        fn AXUIElementCopyAttributeValue(
            element: AXUIElementRef,
            attribute: CFStringRef,
            value: *mut CFTypeRef,
        ) -> i32;
        fn AXIsProcessTrusted() -> Boolean;
    }

    const K_AX_FOCUSED_UI_ELEMENT: &str = "AXFocusedUIElement";
    const K_AX_ROLE: &str = "AXRole";
    const K_AX_SUBROLE: &str = "AXSubrole";

    fn cfstring(value: &str) -> CFStringRef {
        let c = CString::new(value).unwrap();
        unsafe {
            core_foundation_sys::string::CFStringCreateWithCString(
                kCFAllocatorDefault,
                c.as_ptr(),
                core_foundation_sys::string::kCFStringEncodingUTF8,
            )
        }
    }

    fn read_ax_string(element: CFTypeRef, attribute: &str) -> Option<String> {
        unsafe {
            let key = cfstring(attribute);
            let mut value: CFTypeRef = ptr::null_mut();
            if AXUIElementCopyAttributeValue(element, key, &mut value) != 0 || value.is_null() {
                CFRelease(key as _);
                return None;
            }
            CFRelease(key as _);
            let cf = CFString::wrap_under_create_rule(value as CFStringRef);
            Some(cf.to_string())
        }
    }

    fn is_editable_role(role: &str, subrole: &str) -> bool {
        matches!(
            role,
            "AXTextField" | "AXTextArea" | "AXComboBox" | "AXSearchField"
        ) || subrole.contains("TextArea") || subrole.contains("Editable")
    }

    pub struct MacFocusDetector {
        system: AXUIElementRef,
    }

    impl MacFocusDetector {
        pub fn new() -> Result<Self, String> {
            if unsafe { AXIsProcessTrusted() } == 0 {
                return Err("accessibility not trusted".into());
            }
            let system = unsafe { AXUIElementCreateSystemWide() };
            if system.is_null() {
                return Err("failed to create AX system element".into());
            }
            Ok(Self { system })
        }
    }

    impl FocusDetector for MacFocusDetector {
        fn snapshot(&mut self) -> FocusSnapshot {
            unsafe {
                let key = cfstring(K_AX_FOCUSED_UI_ELEMENT);
                let mut focused: CFTypeRef = ptr::null_mut();
                if AXUIElementCopyAttributeValue(self.system, key, &mut focused) != 0 || focused.is_null() {
                    CFRelease(key as _);
                    return FocusSnapshot::default();
                }
                CFRelease(key as _);
                let role = read_ax_string(focused, K_AX_ROLE).unwrap_or_default();
                let subrole = read_ax_string(focused, K_AX_SUBROLE).unwrap_or_default();
                CFRelease(focused);
                FocusSnapshot {
                    editable: is_editable_role(&role, &subrole),
                    app_name: None,
                }
            }
        }
    }

    impl Drop for MacFocusDetector {
        fn drop(&mut self) {
            if !self.system.is_null() {
                unsafe { CFRelease(self.system as _) };
            }
        }
    }
}

#[cfg(target_os = "macos")]
use macos_impl::MacFocusDetector;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn focus_snapshot_serializes() {
        let snap = FocusSnapshot {
            editable: true,
            app_name: Some("Notepad".into()),
        };
        let json = serde_json::to_string(&snap).unwrap();
        assert!(json.contains("editable"));
    }
}
