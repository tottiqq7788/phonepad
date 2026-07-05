package cn.phonepad.storage

import android.content.Context
import cn.phonepad.model.DeviceOnlineState
import cn.phonepad.model.PairedDevice
import org.json.JSONArray
import org.json.JSONObject

class PairedDeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("phonepad_devices", Context.MODE_PRIVATE)

    fun load(): List<PairedDevice> {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(item.toDevice())
                }
            }.sortedByDescending { it.lastConnectedAt }
        }.getOrDefault(emptyList())
    }

    fun save(devices: List<PairedDevice>) {
        val array = JSONArray()
        devices.forEach { device ->
            array.put(device.toJson())
        }
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    fun upsert(device: PairedDevice): List<PairedDevice> {
        val current = load().filterNot { it.id == device.id }.toMutableList()
        current.add(0, device)
        save(current)
        return current
    }

    fun updateOnlineStates(states: Map<String, DeviceOnlineState>): List<PairedDevice> {
        val updated = load().map { device ->
            device.copy(onlineState = states[device.id] ?: device.onlineState)
        }
        save(updated)
        return updated
    }

    private fun PairedDevice.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("host", host)
            .put("tcpPort", tcpPort)
            .put("udpPort", udpPort)
            .put("secret", secret)
            .put("lastConnectedAt", lastConnectedAt)
    }

    private fun JSONObject.toDevice(): PairedDevice {
        return PairedDevice(
            id = getString("id"),
            name = getString("name"),
            host = getString("host"),
            tcpPort = getInt("tcpPort"),
            udpPort = getInt("udpPort"),
            secret = getString("secret"),
            lastConnectedAt = optLong("lastConnectedAt"),
        )
    }

    companion object {
        private const val KEY_DEVICES = "paired_devices"
    }
}
