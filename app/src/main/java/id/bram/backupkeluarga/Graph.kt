package id.bram.backupkeluarga

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

data class RemoteItem(val id: String, val size: Long)

/** Pemanggilan Microsoft Graph (OneDrive) langsung, tanpa aplikasi OneDrive. */
object Graph {
    private const val BASE = "https://graph.microsoft.com/v1.0"
    private const val CHUNK = 320 * 1024 * 20 // 6,25 MB, wajib kelipatan 320 KiB
    private val JSON = "application/json".toMediaType()

    fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }

    /** Request dengan token; kalau 401 sekali, token diperbarui lalu dicoba lagi. */
    private suspend fun authed(
        context: Context,
        build: (Request.Builder) -> Request.Builder,
    ): Pair<Int, String> {
        var token = Auth.token(context)
        for (attempt in 0..1) {
            val req = build(Request.Builder()).header("Authorization", "Bearer $token").build()
            val result = withContext(Dispatchers.IO) {
                Http.executeWithRetry(req).use { it.code to (it.body?.string().orEmpty()) }
            }
            if (result.first == 401 && attempt == 0) {
                token = Auth.forceRefresh(context)
                continue
            }
            return result
        }
        throw AuthException("Akses ditolak")
    }

    suspend fun me(context: Context): JSONObject {
        val (code, body) = authed(context) {
            it.url("$BASE/me?\$select=displayName,userPrincipalName").get()
        }
        if (code != 200) throw HttpException(code, body)
        return JSONObject(body)
    }

    private suspend fun createSession(context: Context, remotePath: String, file: File): String {
        val item = JSONObject()
            .put("@microsoft.graph.conflictBehavior", "replace")
            .put(
                "fileSystemInfo",
                JSONObject().put("lastModifiedDateTime", Instant.ofEpochMilli(file.lastModified()).toString())
            )
        val payload = JSONObject().put("item", item).toString()
        val (code, body) = authed(context) {
            it.url("$BASE/me/drive/root:/${encodePath(remotePath)}:/createUploadSession")
                .post(payload.toRequestBody(JSON))
        }
        if (code !in 200..299) throw HttpException(code, body)
        return JSONObject(body).getString("uploadUrl")
    }

    /** Posisi byte berikutnya yang ditunggu server, atau null kalau sesi upload sudah tidak berlaku. */
    private fun querySession(url: String): Long? {
        val req = Request.Builder().url(url).get().build()
        Http.executeWithRetry(req).use { r ->
            if (r.code != 200) return null
            val ranges = JSONObject(r.body?.string().orEmpty()).optJSONArray("nextExpectedRanges")
                ?: return null
            if (ranges.length() == 0) return null
            return ranges.getString(0).substringBefore('-').toLongOrNull()
        }
    }

    /**
     * Upload satu file. File besar dikirim per potongan dan bisa dilanjutkan
     * (resumeUrl) kalau koneksi putus di tengah jalan.
     */
    suspend fun upload(
        context: Context,
        file: File,
        remotePath: String,
        resumeUrl: String?,
        saveUrl: (String?) -> Unit,
        progress: (Long, Long) -> Unit,
        allowRestart: Boolean = true,
    ): RemoteItem {
        val size = file.length()
        if (size == 0L) {
            val (code, body) = authed(context) {
                it.url("$BASE/me/drive/root:/${encodePath(remotePath)}:/content")
                    .put(ByteArray(0).toRequestBody(null))
            }
            if (code !in 200..299) throw HttpException(code, body)
            val j = JSONObject(body)
            return RemoteItem(j.getString("id"), j.optLong("size", 0L))
        }

        var url: String? = resumeUrl
        var pos = 0L
        if (url != null) {
            val existing = url
            val next = withContext(Dispatchers.IO) { querySession(existing) }
            if (next == null) url = null else pos = next
        }
        if (url == null) {
            url = createSession(context, remotePath, file)
            saveUrl(url)
            pos = 0L
        }
        val sessionUrl: String = url
        val result = withContext(Dispatchers.IO) {
            sendChunks(this, file, size, sessionUrl, pos, progress)
        }
        if (result != null) {
            saveUrl(null)
            return result
        }
        // Sesi upload kedaluwarsa di server: mulai ulang sekali dari awal.
        saveUrl(null)
        if (!allowRestart) throw java.io.IOException("Sesi upload berakhir, akan dicoba lagi")
        return upload(context, file, remotePath, null, saveUrl, progress, allowRestart = false)
    }

    private fun sendChunks(
        scope: CoroutineScope,
        file: File,
        size: Long,
        url: String,
        startPos: Long,
        progress: (Long, Long) -> Unit,
    ): RemoteItem? {
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(CHUNK)
            var pos = startPos
            progress(pos, size)
            while (true) {
                scope.ensureActive()
                val len = minOf(CHUNK.toLong(), size - pos).toInt()
                if (len <= 0) {
                    pos = querySession(url) ?: return null
                    continue
                }
                raf.seek(pos)
                raf.readFully(buf, 0, len)
                val req = Request.Builder().url(url)
                    .put(buf.toRequestBody(null, 0, len))
                    .header("Content-Range", "bytes $pos-${pos + len - 1}/$size")
                    .build()
                Http.executeWithRetry(req).use { r ->
                    val text = r.body?.string().orEmpty()
                    when (r.code) {
                        202 -> {
                            val ranges = try {
                                JSONObject(text).optJSONArray("nextExpectedRanges")
                            } catch (e: Exception) {
                                null
                            }
                            pos = ranges?.optString(0)?.substringBefore('-')?.toLongOrNull() ?: (pos + len)
                            progress(pos, size)
                        }
                        200, 201 -> {
                            val j = JSONObject(text)
                            progress(size, size)
                            return RemoteItem(j.getString("id"), j.optLong("size", size))
                        }
                        404 -> return null
                        416 -> pos = querySession(url) ?: return null
                        else -> throw HttpException(r.code, text)
                    }
                }
            }
        }
    }
}
