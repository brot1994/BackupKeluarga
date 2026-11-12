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
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

data class RemoteItem(val id: String, val size: Long)

/** File foto/video di OneDrive. rel = "<perangkat>/<folder>/.../<nama>" relatif terhadap folder pemakai. */
data class RemoteFile(
    val id: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val isVideo: Boolean,
    val rel: String,
) {
    val device: String get() = rel.substringBefore('/')
}

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

    data class RemoteInfo(val size: Long, val quickXor: String?)

    /** Info file di OneDrive untuk verifikasi; null kalau file sudah tidak ada. */
    suspend fun itemInfo(context: Context, id: String): RemoteInfo? {
        val (code, body) = authed(context) { it.url("$BASE/me/drive/items/$id?\$select=id,size,file").get() }
        if (code == 404) return null
        if (code != 200) throw HttpException(code, body)
        val j = JSONObject(body)
        val hash = j.optJSONObject("file")?.optJSONObject("hashes")?.optString("quickXorHash")
        return RemoteInfo(j.optLong("size", -1L), hash?.ifEmpty { null })
    }

    private suspend fun createSession(context: Context, remotePath: String, file: File, conflict: String): String {
        val item = JSONObject()
            .put("@microsoft.graph.conflictBehavior", conflict)
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
        conflict: String = "replace",
    ): RemoteItem {
        val size = file.length()
        if (size == 0L) {
            val (code, body) = authed(context) {
                it.url("$BASE/me/drive/root:/${encodePath(remotePath)}:/content?@microsoft.graph.conflictBehavior=$conflict")
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
            url = createSession(context, remotePath, file, conflict)
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
        return upload(context, file, remotePath, null, saveUrl, progress, allowRestart = false, conflict = conflict)
    }

    // ---------------- Membaca isi OneDrive (galeri) ----------------

    private val RETRY = Any()

    /** GET dengan token; isi respons diproses oleh [handle] (berjalan di thread IO). */
    private suspend fun <T> authedRaw(context: Context, url: String, handle: (Response) -> T): T {
        var token = Auth.token(context)
        var attempt = 0
        while (true) {
            val req = Request.Builder().url(url).get().header("Authorization", "Bearer $token").build()
            val first = attempt == 0
            val result: Any? = withContext(Dispatchers.IO) {
                Http.executeWithRetry(req).use { r ->
                    if (r.code == 401 && first) RETRY else handle(r)
                }
            }
            if (result === RETRY) {
                token = Auth.forceRefresh(context)
                attempt++
                continue
            }
            @Suppress("UNCHECKED_CAST")
            return result as T
        }
    }

    private suspend fun itemIdByPath(context: Context, path: String): String? {
        val (code, body) = authed(context) {
            it.url("$BASE/me/drive/root:/${encodePath(path)}?\$select=id").get()
        }
        if (code == 404) return null
        if (code != 200) throw HttpException(code, body)
        return JSONObject(body).getString("id")
    }

    private fun parseTime(s: String?): Long? =
        if (s.isNullOrBlank()) null else try {
            Instant.parse(s).toEpochMilli()
        } catch (e: Exception) {
            null
        }

    /** Semua foto & video di bawah folder [rootPath] (termasuk subfolder). */
    suspend fun listMediaRecursive(context: Context, rootPath: String): List<RemoteFile> {
        val rootId = itemIdByPath(context, rootPath) ?: return emptyList()
        val out = ArrayList<RemoteFile>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootId to "")
        val select = "id,name,size,file,folder,fileSystemInfo,photo"
        while (queue.isNotEmpty()) {
            val (folderId, relDir) = queue.removeFirst()
            var url: String? = "$BASE/me/drive/items/$folderId/children?\$top=500&\$select=$select"
            while (url != null) {
                val pageUrl: String = url
                val (code, body) = authed(context) { it.url(pageUrl).get() }
                if (code != 200) throw HttpException(code, body)
                val j = JSONObject(body)
                val arr = j.optJSONArray("value") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name")
                    if (o.has("folder")) {
                        queue.add(o.getString("id") to "$relDir$name/")
                        continue
                    }
                    if (!o.has("file")) continue
                    val mime = o.optJSONObject("file")?.optString("mimeType").orEmpty()
                    val isVideo = mime.startsWith("video/") || Media.isVideo(name)
                    val isImage = mime.startsWith("image/") || Media.isImage(name)
                    if (!isVideo && !isImage) continue
                    val modified = parseTime(o.optJSONObject("fileSystemInfo")?.optString("lastModifiedDateTime"))
                        ?: parseTime(o.optJSONObject("photo")?.optString("takenDateTime"))
                        ?: 0L
                    out += RemoteFile(
                        id = o.getString("id"),
                        name = name,
                        size = o.optLong("size", 0L),
                        modified = modified,
                        isVideo = isVideo,
                        rel = "$relDir$name",
                    )
                }
                url = j.optString("@odata.nextLink").ifEmpty { null }
            }
        }
        return out
    }

    private fun saveBody(r: Response, dest: File, progress: ((Long, Long) -> Unit)?): Boolean {
        if (r.code != 200) return false
        val body = r.body ?: return false
        val total = body.contentLength()
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        body.byteStream().use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastPct = -1L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (progress != null && total > 0) {
                        val pct = done * 100 / total
                        if (pct != lastPct) {
                            lastPct = pct
                            progress(done, total)
                        }
                    }
                }
            }
        }
        dest.delete()
        return tmp.renameTo(dest)
    }

    /** Unduh isi file OneDrive ke [dest]. */
    suspend fun downloadTo(
        context: Context,
        id: String,
        dest: File,
        progress: ((Long, Long) -> Unit)? = null,
    ): Boolean = authedRaw(context, "$BASE/me/drive/items/$id/content") { r -> saveBody(r, dest, progress) }

    /** Unduh gambar kecil (thumbnail) buatan OneDrive. */
    suspend fun thumbnailTo(context: Context, id: String, dest: File): Boolean =
        authedRaw(context, "$BASE/me/drive/items/$id/thumbnails/0/medium/content") { r -> saveBody(r, dest, null) }

    /** Alamat unduh sementara (tanpa login) untuk memutar video langsung dari OneDrive. */
    suspend fun downloadUrl(context: Context, id: String): String? {
        val (code, body) = authed(context) {
            it.url("$BASE/me/drive/items/$id?select=id,@microsoft.graph.downloadUrl").get()
        }
        if (code != 200) return null
        return JSONObject(body).optString("@microsoft.graph.downloadUrl").ifEmpty { null }
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
