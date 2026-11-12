package id.bram.backupkeluarga

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Login Microsoft dengan "device code": HP menampilkan kode, Bram menyetujui
 * dari HP-nya sendiri di microsoft.com/devicelogin. Password tidak pernah
 * disimpan di HP keluarga, hanya token.
 */
object Auth {
    private const val BASE = "https://login.microsoftonline.com/${Config.TENANT_ID}/oauth2/v2.0"
    private val mutex = Mutex()

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int,
    )

    sealed interface Poll {
        data object Pending : Poll
        data object SlowDown : Poll
        data object Done : Poll
        data class Failed(val reason: String) : Poll
    }

    private fun post(url: String, form: FormBody): Pair<Int, JSONObject> {
        val req = Request.Builder().url(url).post(form).build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject().put("error", "bad_response").put("error_description", text.take(200))
            }
            return resp.code to json
        }
    }

    suspend fun requestDeviceCode(): DeviceCode = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", Config.CLIENT_ID)
            .add("scope", Config.SCOPES)
            .build()
        val (code, j) = post("$BASE/devicecode", form)
        if (code != 200) {
            throw IOException(j.optString("error_description", "Gagal meminta kode ($code)").lineSequence().first())
        }
        DeviceCode(
            deviceCode = j.getString("device_code"),
            userCode = j.getString("user_code"),
            verificationUri = j.optString("verification_uri", Config.VERIFY_URL),
            expiresIn = j.optInt("expires_in", 900),
            interval = j.optInt("interval", 5),
        )
    }

    suspend fun poll(context: Context, dc: DeviceCode): Poll = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("client_id", Config.CLIENT_ID)
            .add("device_code", dc.deviceCode)
            .build()
        val (code, j) = try {
            post("$BASE/token", form)
        } catch (e: IOException) {
            return@withContext Poll.Pending
        }
        if (code == 200) {
            Prefs(context).saveTokens(
                j.getString("access_token"),
                j.optString("refresh_token").ifEmpty { null },
                j.optLong("expires_in", 3600L),
            )
            return@withContext Poll.Done
        }
        when (j.optString("error")) {
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown
            "authorization_declined" -> Poll.Failed("Persetujuan ditolak.")
            "expired_token" -> Poll.Failed("Kode sudah kedaluwarsa.")
            "bad_verification_code" -> Poll.Failed("Kode tidak dikenali.")
            else -> Poll.Failed(
                j.optString("error_description", "Login gagal ($code)").lineSequence().first()
            )
        }
    }

    /** Token akses yang masih berlaku; diperbarui otomatis kalau sudah habis. */
    suspend fun token(context: Context): String = mutex.withLock {
        val p = Prefs(context)
        val at = p.accessToken
        if (at != null && System.currentTimeMillis() < p.expiresAt) at else refresh(p)
    }

    suspend fun forceRefresh(context: Context): String = mutex.withLock { refresh(Prefs(context)) }

    private suspend fun refresh(p: Prefs): String = withContext(Dispatchers.IO) {
        val rt = p.refreshToken ?: throw AuthException("Belum login")
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", Config.CLIENT_ID)
            .add("refresh_token", rt)
            .add("scope", Config.SCOPES)
            .build()
        val (code, j) = post("$BASE/token", form)
        if (code == 200) {
            val access = j.getString("access_token")
            p.saveTokens(access, j.optString("refresh_token").ifEmpty { null }, j.optLong("expires_in", 3600L))
            return@withContext access
        }
        val err = j.optString("error")
        if (err == "invalid_grant" || err == "interaction_required" ||
            err == "invalid_client" || err == "unauthorized_client"
        ) {
            p.needsReauth = true
            throw AuthException("Login perlu disetujui ulang")
        }
        throw IOException("Gagal memperbarui login ($code $err)")
    }
}
