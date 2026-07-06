#[cfg(target_os = "windows")]
pub fn keep_process_responsive() {
    use windows_sys::Win32::System::Power::{SetThreadExecutionState, ES_CONTINUOUS, ES_SYSTEM_REQUIRED};

    unsafe {
        SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED);
    }
}

#[cfg(not(target_os = "windows"))]
pub fn keep_process_responsive() {}
