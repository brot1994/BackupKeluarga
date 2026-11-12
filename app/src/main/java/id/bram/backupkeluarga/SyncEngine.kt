package id.bram.backupkeluarga

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Status upload yang sedang berjalan, untuk ditampilkan di layar dan notifikasi. */
object SyncState {
    data class Ui(
        val running: Boolean = false,
        val currentName: String? = null,
        val currentSent: Long = 0L,
        val currentTotal: Long = 0L,
        val done: Int = 0,
        val total: Int = 0,
        val message: String? = null,
    )

    val flow = MutableStateFlow(Ui())
}

object SyncEngine {
    private val mutex = Mutex()

    data class Outcome(val uploaded: Int, val failed: Int, val authError: Boolean)

    /** Mode v0.1: hanya SALIN (file di HP tidak pernah dihapus). */
    suspend fun run(context: Context): Outcome = mutex.withLock {
        val ctx = context.applicationContext
        val prefs = Prefs(ctx)
        val db = Db.get(ctx)
        if (!prefs.isSignedIn || prefs.needsReauth) {
            return@withLock Outcome(0, 0, prefs.needsReauth)
        }

        var uploaded = 0
        var failed = 0
        var authError = false
        val failedThisRun = HashSet<String>()
        SyncState.flow.value = SyncState.Ui(running = true, message = "Memeriksa file baru…")

        try {
            val folders = prefs.folders
            withContext(Dispatchers.IO) { db.pruneQueue(folders.map { it.name }.toSet()) }

            passes@ for (pass in 1..3) {
                val list = withContext(Dispatchers.IO) { candidates(db, folders) }
                    .filter { it.second.absolutePath !in failedThisRun }
                if (list.isEmpty()) break

                for ((index, pair) in list.withIndex()) {
                    coroutineContext.ensureActive()
                    val (folder, file) = pair
                    val path = file.absolutePath
                    if (!file.exists()) continue

                    val size = file.length()
                    val mtime = file.lastModified()
                    val rel = file.relativeTo(File(folder.path)).invariantSeparatorsPath
                    val remote = "${Config.ROOT_FOLDER}/${prefs.member}/${prefs.device}/${folder.name}/$rel"

                    val rec = withContext(Dispatchers.IO) { db.get(path) }
                    val resume = if (rec != null && rec.size == size && rec.mtime == mtime) rec.uploadUrl else null
                    withContext(Dispatchers.IO) { db.markPending(path, folder.name, size, mtime, resume) }

                    SyncState.flow.value = SyncState.Ui(
                        running = true, currentName = file.name, currentSent = 0L,
                        currentTotal = size, done = index, total = list.size,
                    )

                    try {
                        var lastUi = 0L
                        val item = Graph.upload(
                            ctx, file, remote, resume,
                            saveUrl = { url -> db.setUploadUrl(path, url) },
                            progress = { sent, total ->
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastUi > 400 || sent >= total) {
                                    lastUi = now
                                    SyncState.flow.value = SyncState.flow.value.copy(
                                        currentSent = sent, currentTotal = total
                                    )
                                }
                            },
                        )
                        if (item.size != size) {
                            throw IOException("Ukuran di OneDrive tidak cocok (${item.size} vs $size)")
                        }
                        withContext(Dispatchers.IO) { db.markUploaded(path, item.id, size, mtime) }
                        uploaded++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AuthException) {
                        prefs.needsReauth = true
                        authError = true
                        prefs.lastError = e.message
                        withContext(Dispatchers.IO) { db.markFailed(path, e.message ?: "Login") }
                        break@passes
                    } catch (e: Exception) {
                        failed++
                        failedThisRun += path
                        val msg = e.message ?: e.javaClass.simpleName
                        prefs.lastError = "${file.name}: $msg"
                        withContext(Dispatchers.IO) { db.markFailed(path, msg) }
                    }
                }
            }
            if (failed == 0 && !authError) prefs.lastError = null
        } finally {
            prefs.lastSyncAt = System.currentTimeMillis()
            SyncState.flow.value = SyncState.Ui()
        }
        Outcome(uploaded, failed, authError)
    }

    /** File yang belum ter-upload atau sudah berubah sejak upload terakhir, terbaru lebih dulu. */
    private fun candidates(db: Db, folders: List<WatchedFolder>): List<Pair<WatchedFolder, File>> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Pair<WatchedFolder, File>>()
        for (folder in folders) {
            val root = File(folder.path)
            if (!root.isDirectory) continue
            root.walkTopDown()
                .onEnter { it == root || !it.name.startsWith(".") }
                .filter { it.isFile && !it.name.startsWith(".") }
                // file yang baru saja berubah mungkin masih ditulis; tunggu putaran berikutnya
                .filter { now - it.lastModified() > 15_000L }
                .forEach { f ->
                    val rec = db.get(f.absolutePath)
                    val needs = rec == null || rec.status != Db.UPLOADED ||
                        rec.size != f.length() || rec.mtime != f.lastModified()
                    if (needs) out += folder to f
                }
        }
        out.sortByDescending { it.second.lastModified() }
        return out
    }
}
