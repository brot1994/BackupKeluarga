package id.bram.backupkeluarga

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Mode "Pindahkan": menghapus file dari HP yang SUDAH aman di OneDrive.
 * Sebelum menghapus, setiap file dicek ulang ke OneDrive (masih ada, ukuran sama, hash sama).
 */
object Cleaner {
    private const val DAY = 86_400_000L
    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB
    private val mutex = Mutex()

    data class Candidate(
        val path: String,
        val folder: String,
        val name: String,
        val size: Long,
        val mtime: Long,
        val remoteId: String,
        val isVideo: Boolean,
    )

    data class Result(val freed: Int, val bytes: Long, val skipped: Int, val firstError: String?)

    fun freeSpace(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    /** File yang memenuhi aturan folder "Pindahkan" dan sudah ter-upload utuh. */
    fun eligible(context: Context): List<Candidate> {
        val prefs = Prefs(context)
        val db = Db.get(context)
        val now = System.currentTimeMillis()
        val out = ArrayList<Candidate>()
        for (folder in prefs.folders) {
            if (!folder.move || folder.lockedCopy) continue
            val root = File(folder.path)
            if (!root.isDirectory) continue
            root.walkTopDown()
                .onEnter { it == root || !it.name.startsWith(".") }
                .filter { it.isFile && !it.name.startsWith(".") }
                .forEach { f ->
                    val size = f.length()
                    val mtime = f.lastModified()
                    if (now - mtime < folder.minAgeDays * DAY) return@forEach
                    if (folder.minSizeMb > 0 && size < folder.minSizeMb * MB) return@forEach
                    val rec = db.get(f.absolutePath) ?: return@forEach
                    val remoteId = rec.remoteId ?: return@forEach
                    if (rec.status != Db.UPLOADED || rec.size != size || rec.mtime != mtime) return@forEach
                    out += Candidate(f.absolutePath, folder.name, f.name, size, mtime, remoteId, Media.isVideo(f.name))
                }
        }
        out.sortByDescending { it.size }
        return out
    }

    /** Hapus file-file ini dari HP setelah diverifikasi satu per satu. */
    suspend fun free(
        context: Context,
        list: List<Candidate>,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result = mutex.withLock {
        val ctx = context.applicationContext
        val db = Db.get(ctx)
        var freed = 0
        var bytes = 0L
        var skipped = 0
        var firstError: String? = null

        for ((i, c) in list.withIndex()) {
            coroutineContext.ensureActive()
            try {
                val f = File(c.path)
                if (!f.exists() || f.length() != c.size || f.lastModified() != c.mtime) {
                    skipped++
                    continue
                }
                val remote = Graph.itemInfo(ctx, c.remoteId)
                if (remote == null) {
                    // Di OneDrive sudah tidak ada: jangan hapus, upload ulang di sinkron berikutnya.
                    withContext(Dispatchers.IO) { db.delete(c.path) }
                    skipped++
                    if (firstError == null) firstError = "${c.name} tidak ada di OneDrive, akan diupload ulang"
                    continue
                }
                if (remote.size != c.size) {
                    skipped++
                    if (firstError == null) firstError = "${c.name}: ukuran di OneDrive berbeda"
                    continue
                }
                val remoteHash = remote.quickXor
                if (remoteHash != null) {
                    val localHash = withContext(Dispatchers.IO) { QuickXorHash.ofFile(f) }
                    if (localHash != remoteHash) {
                        skipped++
                        if (firstError == null) firstError = "${c.name}: isi file berbeda dengan OneDrive"
                        continue
                    }
                }
                val deleted = withContext(Dispatchers.IO) { f.delete() }
                if (deleted) {
                    MediaScannerConnection.scanFile(ctx, arrayOf(c.path), null, null)
                    withContext(Dispatchers.IO) { db.markFreed(c.path) }
                    freed++
                    bytes += c.size
                } else {
                    skipped++
                    if (firstError == null) firstError = "${c.name}: gagal dihapus"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                firstError = e.message
                break
            } catch (e: Exception) {
                skipped++
                if (firstError == null) firstError = "${c.name}: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                progress(i + 1, list.size)
            }
        }
        Result(freed, bytes, skipped, firstError)
    }

    /** Dipanggil setelah sinkron selesai. */
    suspend fun autoRun(context: Context) {
        val ctx = context.applicationContext
        val prefs = Prefs(ctx)
        val list = withContext(Dispatchers.IO) { eligible(ctx) }
        if (list.isEmpty()) {
            prefs.readyNotice = null
            return
        }
        val total = list.sumOf { it.size }

        if (prefs.askFirst) {
            val sig = "${list.size}:$total"
            if (prefs.readyNotice != sig) {
                prefs.readyNotice = sig
                Notifier.readyToFree(ctx, list.size, total)
            }
            return
        }
        val limitGb = prefs.freeBelowGb
        if (limitGb > 0 && freeSpace() >= limitGb * GB) return

        val r = free(ctx, list)
        if (r.freed > 0) Notifier.freed(ctx, r.freed, r.bytes)
    }
}
