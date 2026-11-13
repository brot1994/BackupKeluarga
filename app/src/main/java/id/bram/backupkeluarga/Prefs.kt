package id.bram.backupkeluarga

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Folder yang dibackup. move = mode "Pindahkan": file dihapus dari HP setelah aman di OneDrive,
 * kalau umurnya ≥ minAgeDays hari dan (kalau minSizeMb > 0) ukurannya ≥ minSizeMb MB.
 */
data class WatchedFolder(
    val name: String,
    val path: String,
    val move: Boolean = false,
    val minAgeDays: Int = 5,
    val minSizeMb: Int = 0,
) {
    /** Folder backup chat WhatsApp: tidak boleh dipindah, karena dibutuhkan untuk memulihkan chat. */
    val lockedCopy: Boolean get() = isLockedCopy(path)

    fun ruleText(): String =
        if (lockedCopy) "Selalu disalin (backup chat, tidak boleh dihapus)"
        else if (!move) "Tetap di HP, hanya disalin"
        else buildString {
            append("Hapus dari HP setelah $minAgeDays hari")
            if (minSizeMb > 0) append(", > $minSizeMb MB")
        }

    companion object {
        fun isLockedCopy(path: String): Boolean {
            val p = path.lowercase()
            return p.contains("/databases") || p.contains("/backups")
        }

        /**
         * Aturan bawaan untuk folder yang baru ditambahkan:
         * foto/video → Pindahkan 30 hari; dokumen, unduhan, backup chat, dan lainnya → Salin.
         */
        fun withDefaults(name: String, path: String): WatchedFolder {
            val p = path.lowercase()
            if (isLockedCopy(p)) return WatchedFolder(name, path, move = false)
            val docs = listOf("/documents", "/download", "/document")
            if (docs.any { p.contains(it) }) return WatchedFolder(name, path, move = false)
            val media = listOf(
                "/dcim", "/camera", "/screenshots", "/pictures", "/movies", "/video",
                "/whatsapp images", "/whatsapp video", "/whatsapp animated gifs",
            )
            return if (media.any { p.contains(it) }) WatchedFolder(name, path, move = true, minAgeDays = 30)
            else WatchedFolder(name, path, move = false)
        }
    }
}

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

    /** Tanya dulu sebelum menghapus file dari HP (bawaan: ya). */
    var askFirst: Boolean
        get() = sp.getBoolean("ask_first", true)
        set(v) { sp.edit().putBoolean("ask_first", v).apply() }

    /** Hapus otomatis hanya kalau ruang kosong HP di bawah angka ini (GB). 0 = tidak dibatasi. */
    var freeBelowGb: Int
        get() = sp.getInt("free_below_gb", 0)
        set(v) { sp.edit().putInt("free_below_gb", v).apply() }

    var readyNotice: String?
        get() = sp.getString("ready_notice", null)
        set(v) { sp.edit().putString("ready_notice", v).apply() }

    var quotaTotal: Long
        get() = sp.getLong("quota_total", 0L)
        set(v) { sp.edit().putLong("quota_total", v).apply() }

    var quotaRemaining: Long
        get() = sp.getLong("quota_remaining", 0L)
        set(v) { sp.edit().putLong("quota_remaining", v).apply() }

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
                    WatchedFolder(
                        name = o.getString("name"),
                        path = o.getString("path"),
                        move = o.optBoolean("move", false),
                        minAgeDays = o.optInt("min_age_days", 5),
                        minSizeMb = o.optInt("min_size_mb", 0),
                    )
                }
            } catch (e: Exception) {
                listOf(defaultCamera())
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject().put("name", it.name).put("path", it.path).put("move", it.move)
                        .put("min_age_days", it.minAgeDays).put("min_size_mb", it.minSizeMb)
                )
            }
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
