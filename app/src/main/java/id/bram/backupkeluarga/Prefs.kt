package id.bram.backupkeluarga

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject

data class WatchedFolder(val name: String, val path: String)

class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("backup_keluarga", Context.MODE_PRIVATE)

    var member: String
        get() = sp.getString("member", "") ?: ""
        set(v) { sp.edit().putString("member", v).apply() }

    var device: String
        get() = sp.getString("device", "") ?: ""
        set(v) { sp.edit().putString("device", v).apply() }

    var upn: String?
        get() = sp.getString("upn", null)
        set(v) { sp.edit().putString("upn", v).apply() }

    val accessToken: String? get() = sp.getString("access", null)
    val refreshToken: String? get() = sp.getString("refresh", null)
    val expiresAt: Long get() = sp.getLong("expires_at", 0L)

    var needsReauth: Boolean
        get() = sp.getBoolean("needs_reauth", false)
        set(v) { sp.edit().putBoolean("needs_reauth", v).commit() }

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifi_only", true)
        set(v) { sp.edit().putBoolean("wifi_only", v).apply() }

    var chargingOnly: Boolean
        get() = sp.getBoolean("charging_only", false)
        set(v) { sp.edit().putBoolean("charging_only", v).apply() }

    var lastSyncAt: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) { sp.edit().putLong("last_sync", v).apply() }

    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) { sp.edit().putString("last_error", v).apply() }

    var folders: List<WatchedFolder>
        get() {
            val raw = sp.getString("folders", null) ?: return listOf(defaultCamera())
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    WatchedFolder(o.getString("name"), o.getString("path"))
                }
            } catch (e: Exception) {
                listOf(defaultCamera())
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("name", it.name).put("path", it.path)) }
            sp.edit().putString("folders", arr.toString()).apply()
        }

    val isSignedIn: Boolean
        get() = refreshToken != null && member.isNotBlank() && device.isNotBlank()

    fun saveTokens(access: String, refresh: String?, expiresInSec: Long) {
        val e = sp.edit()
        e.putString("access", access)
        if (refresh != null) e.putString("refresh", refresh)
        e.putLong("expires_at", System.currentTimeMillis() + (expiresInSec - 120L) * 1000L)
        e.putBoolean("needs_reauth", false)
        e.commit()
    }

    fun clearAuth() {
        sp.edit()
            .remove("access")
            .remove("refresh")
            .remove("expires_at")
            .remove("upn")
            .putBoolean("needs_reauth", false)
            .commit()
    }

    companion object {
        fun defaultCamera() = WatchedFolder(
            "Kamera",
            Environment.getExternalStorageDirectory().absolutePath + "/DCIM/Camera"
        )

        /** Membuang karakter yang tidak boleh dipakai di nama folder OneDrive. */
        fun cleanName(s: String): String =
            s.replace(Regex("[\\\\/:*?\"<>|#%]"), "").trim().trimEnd('.').take(40).trim()
    }
}
