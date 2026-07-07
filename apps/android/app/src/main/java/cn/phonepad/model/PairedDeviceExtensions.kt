package cn.phonepad.model

fun List<PairedDevice>.onlineSwitchable(activeDeviceId: String): List<PairedDevice> =
    filter { device ->
        device.id == activeDeviceId || device.onlineState == DeviceOnlineState.Online
    }

fun List<PairedDevice>.countOnlineSwitchable(activeDeviceId: String): Int =
    count { device ->
        device.id == activeDeviceId || device.onlineState == DeviceOnlineState.Online
    }
