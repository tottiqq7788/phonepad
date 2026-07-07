package cn.phonepad.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.phonepad.model.DeviceOnlineState
import cn.phonepad.model.PairedDevice
import cn.phonepad.protocol.Protocol
import org.json.JSONArray
import org.json.JSONObject

class PairedDeviceStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = createSecurePrefs(appContext)

    init {
        migrateLegacyPrefsIfNeeded()
    }

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
        prefs.edit().putString(KEY_DEVICES, array.toString()).commit()
    }

    fun upsert(device: PairedDevice): List<PairedDevice> {
        val current = load().filterNot { it.id == device.id }.toMutableList()
        current.add(0, device)
        save(current)
        return current
    }

    fun remove(deviceId: String): List<PairedDevice> {
        val updated = load().filterNot { it.id == deviceId }
        save(updated)
        return updated
    }

    fun updateOnlineStates(states: Map<String, DeviceOnlineState>): List<PairedDevice> {
        val updated = load().map { device ->
            device.copy(onlineState = states[device.id] ?: device.onlineState)
        }
        save(updated)
        return updated
    }

    private fun migrateLegacyPrefsIfNeeded() {
        if (prefs.contains(KEY_DEVICES)) {
            return
        }
        val legacy = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = legacy.getString(KEY_DEVICES, null) ?: return
        prefs.edit().putString(KEY_DEVICES, raw).apply()
        legacy.edit().remove(KEY_DEVICES).apply()
    }

    private fun PairedDevice.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("host", host)
            .put("tcpPort", tcpPort)
            .put("udpPort", udpPort)
            .put("secret", secret)
            .put("discoveryPort", discoveryPort)
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
            discoveryPort = optInt("discoveryPort", Protocol.UDP_DISCOVERY_PORT),
            lastConnectedAt = optLong("lastConnectedAt"),
        )
    }

    companion object {
        private const val PREFS_NAME = "phonepad_devices_secure"
        private const val LEGACY_PREFS_NAME = "phonepad_devices"
        private const val KEY_DEVICES = "paired_devices"

        private fun createSecurePrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
