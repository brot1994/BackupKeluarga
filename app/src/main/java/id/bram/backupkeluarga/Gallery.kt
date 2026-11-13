package id.bram.backupkeluarga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** BOTH = di HP & aman di OneDrive · LOCAL = baru di HP (belum ter-upload) · CLOUD = hanya di OneDrive */
enum class Where { BOTH, LOCAL, CLOUD }

data class GalleryItem(
    val key: String,
    val name: String,
    val date: Long,
    val isVideo: Boolean,
    val localPath: String?,
    val remoteId: String?,
    val size: Long,
    val where: Where,
    val device: String,
)

object Media {
    private val IMAGE = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "dng")
    private val VIDEO = setOf("mp4", "mov", "3gp", "mkv", "webm", "m4v", "avi")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isImage(name: String) = ext(name) in IMAGE
    fun isVideo(name: String) = ext(name) in VIDEO
    fun isMedia(name: String) = isImage(name) || isVideo(name)
    fun mime(name: String, video: Boolean): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(name))
            ?: if (video) "video/*" else "image/*"
}

object GalleryRepo {
    val items = MutableStateFlow<List<GalleryItem>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private val mutex = Mutex()
    private var remote: List<RemoteFile>? = null
    private var remoteAt = 0L

    private data class LocalMedia(
        val path: String,
        val name: String,
        val size: Long,
        val mtime: Long,
        val isVideo: Boolean,
        val rel: String,
    )

    /** Muat galeri: isi HP langsung, isi OneDrive diperbarui maksimal tiap 2 menit (atau saat dipaksa). */
    suspend fun refresh(context: Context, forceRemote: Boolean = false) = mutex.withLock {
        val ctx = context.applicationContext
        val prefs = Prefs(ctx)
        loading.value = true
        try {
            if (remote == null) remote = withContext(Dispatchers.IO) { loadCache(ctx, prefs.member) }
            val local = withContext(Dispatchers.IO) { scanLocal(prefs) }
            val cached = remote.orEmpty()
            items.value = withContext(Dispatchers.IO) { merge(ctx, prefs, local, cached) }

            if (forceRemote || System.currentTimeMillis() - remoteAt > 120_000L) {
                val fresh = Graph.listMediaRecursive(ctx, "${Config.ROOT_FOLDER}/${prefs.member}")
                remote = fresh
                remoteAt = System.currentTimeMillis()
                withContext(Dispatchers.IO) { saveCache(ctx, prefs.member, fresh) }
                items.value = withContext(Dispatchers.IO) { merge(ctx, prefs, local, fresh) }
            }
            error.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = "Gagal memuat dari OneDrive: ${e.message ?: "periksa internet"}"
        } finally {
            loading.value = false
        }
    }

    private fun scanLocal(prefs: Prefs): List<LocalMedia> {
        val out = ArrayList<LocalMedia>()
        for (folder in prefs.folders) {
            val root = File(folder.path)
            if (!root.isDirectory) continue
            root.walkTopDown()
                .onEnter { it == root || (!it.name.startsWith(".") && !FolderSuggestions.isRestoreDir(it.absolutePath)) }
                .filter { it.isFile && !it.name.startsWith(".") && Media.isMedia(it.name) }
                .forEach { f ->
                    val rel = f.relativeTo(root).invariantSeparatorsPath
                    out += LocalMedia(
                        f.absolutePath, f.name, f.length(), f.lastModified(), Media.isVideo(f.name),
                        "${prefs.device}/${folder.name}/$rel",
                    )
                }
        }
        return out
    }

    private fun merge(ctx: Context, prefs: Prefs, local: List<LocalMedia>, remote: List<RemoteFile>): List<GalleryItem> {
        val db = Db.get(ctx)
        val byRel = remote.associateBy { it.rel }
        val linked = HashSet<String>()
        val out = ArrayList<GalleryItem>(local.size + remote.size)

        for (l in local) {
            val rec = db.get(l.path)
            var remoteId: String? = null
            if (rec != null && rec.status == Db.UPLOADED && rec.size == l.size && rec.mtime == l.mtime) {
                remoteId = rec.remoteId
            }
            if (remoteId == null) {
                // cadangan: cocokkan lewat lokasi & ukuran (mis. setelah aplikasi dipasang ulang)
                val r = byRel[l.rel]
                if (r != null && r.size == l.size) remoteId = r.id
            }
            if (remoteId != null) linked += remoteId
            out += GalleryItem(
                key = "L:${l.path}",
                name = l.name,
                date = l.mtime,
                isVideo = l.isVideo,
                localPath = l.path,
                remoteId = remoteId,
                size = l.size,
                where = if (remoteId != null) Where.BOTH else Where.LOCAL,
                device = prefs.device,
            )
        }
        for (r in remote) {
            if (r.id in linked) continue
            out += GalleryItem(
                key = "R:${r.id}",
                name = r.name,
                date = r.modified,
                isVideo = r.isVideo,
                localPath = null,
                remoteId = r.id,
                size = r.size,
                where = Where.CLOUD,
                device = r.device,
            )
        }
        out.sortByDescending { it.date }
        return out
    }

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "remote_cache.json")

    private fun loadCache(ctx: Context, member: String): List<RemoteFile> = try {
        val j = JSONObject(cacheFile(ctx).readText())
        if (j.optString("member") != member) emptyList() else {
            val arr = j.getJSONArray("items")
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                RemoteFile(
                    o.getString("id"), o.getString("name"), o.getLong("size"),
                    o.getLong("modified"), o.getBoolean("video"), o.getString("rel"),
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveCache(ctx: Context, member: String, list: List<RemoteFile>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("size", it.size)
                    .put("modified", it.modified).put("video", it.isVideo).put("rel", it.rel)
            )
        }
        try {
            cacheFile(ctx).writeText(JSONObject().put("member", member).put("items", arr).toString())
        } catch (e: Exception) {
            // cache hanya untuk mempercepat; abaikan kalau gagal
        }
    }
}

/** Gambar kecil untuk grid galeri, dengan cache di memori dan di disk. */
object Thumbs {
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val remoteGate = Semaphore(4)
    private val localGate = Semaphore(3)

    fun cached(key: String): Bitmap? = mem.get(key)

    suspend fun load(context: Context, item: GalleryItem): Bitmap? {
        mem.get(item.key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            val local = item.localPath?.let { File(it) }?.takeIf { it.exists() }
            if (local != null) {
                localGate.withPermit { localThumb(local, item.isVideo) }
            } else {
                val id = item.remoteId
                if (id == null) null else remoteGate.withPermit { remoteThumb(context, id) }
            }
        }
        if (bmp != null) mem.put(item.key, bmp)
        return bmp
    }

    private fun localThumb(f: File, video: Boolean): Bitmap? = try {
        val size = Size(320, 320)
        if (video) ThumbnailUtils.createVideoThumbnail(f, size, null)
        else ThumbnailUtils.createImageThumbnail(f, size, null)
    } catch (e: Exception) {
        null
    }

    private suspend fun remoteThumb(context: Context, id: String): Bitmap? {
        val f = File(context.cacheDir, "thumbs/$id.jpg")
        if (!f.exists()) {
            val ok = try {
                Graph.thumbnailTo(context, id, f)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!ok) return null
        }
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    /** Gambar ukuran layar untuk penampil (mengikuti orientasi EXIF). */
    suspend fun loadFull(context: Context, item: GalleryItem): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val local = item.localPath?.let { File(it) }?.takeIf { it.exists() }
            val file = if (local != null) local else {
                val id = item.remoteId ?: return@withContext null
                val f = File(context.cacheDir, "full/$id")
                if (!f.exists() && !Graph.downloadTo(context, id, f)) return@withContext null
                f
            }
            val max = 2560
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                var s = 1
                while (info.size.width / s > max || info.size.height / s > max) s *= 2
                decoder.setTargetSampleSize(s)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
