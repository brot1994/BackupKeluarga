package id.bram.backupkeluarga

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** Catatan file mana saja yang sudah ter-upload, supaya tidak dikirim dua kali. */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context, "backup.db", null, 2) {

    data class Rec(
        val path: String,
        val folder: String,
        val size: Long,
        val mtime: Long,
        val status: Int,
        val remoteId: String?,
        val uploadUrl: String?,
        val uploadedAt: Long,
        val error: String?,
        val freedAt: Long,
    )

    data class Stats(val uploaded: Int, val uploadedBytes: Long, val pending: Int, val failed: Int)

    companion object {
        const val PENDING = 0
        const val UPLOADED = 1
        const val FAILED = 2

        private val COLS = arrayOf(
            "path", "folder", "size", "mtime", "status",
            "remote_id", "upload_url", "uploaded_at", "error", "freed_at"
        )

        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE files(" +
                "path TEXT PRIMARY KEY, folder TEXT NOT NULL, size INTEGER NOT NULL, " +
                "mtime INTEGER NOT NULL, status INTEGER NOT NULL, remote_id TEXT, " +
                "upload_url TEXT, uploaded_at INTEGER NOT NULL DEFAULT 0, error TEXT, " +
                "freed_at INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_status ON files(status, uploaded_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE files ADD COLUMN freed_at INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun Cursor.toRec() = Rec(
        path = getString(0),
        folder = getString(1),
        size = getLong(2),
        mtime = getLong(3),
        status = getInt(4),
        remoteId = str(5),
        uploadUrl = str(6),
        uploadedAt = getLong(7),
        error = str(8),
        freedAt = getLong(9),
    )

    fun get(path: String): Rec? =
        readableDatabase.query("files", COLS, "path=?", arrayOf(path), null, null, null).use {
            if (it.moveToFirst()) it.toRec() else null
        }

    fun markPending(path: String, folder: String, size: Long, mtime: Long, uploadUrl: String?, freedAt: Long = 0L) {
        val cv = ContentValues().apply {
            put("path", path)
            put("folder", folder)
            put("size", size)
            put("mtime", mtime)
            put("status", PENDING)
            put("upload_url", uploadUrl)
            put("uploaded_at", 0L)
            put("freed_at", freedAt)
        }
        writableDatabase.insertWithOnConflict("files", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setUploadUrl(path: String, url: String?) {
        val cv = ContentValues().apply { put("upload_url", url) }
        writableDatabase.update("files", cv, "path=?", arrayOf(path))
    }

    fun markUploaded(path: String, remoteId: String, size: Long, mtime: Long) {
        val cv = ContentValues().apply {
            put("status", UPLOADED)
            put("remote_id", remoteId)
            putNull("upload_url")
            putNull("error")
            put("size", size)
            put("mtime", mtime)
            put("uploaded_at", System.currentTimeMillis())
            put("freed_at", 0L)
        }
        writableDatabase.update("files", cv, "path=?", arrayOf(path))
    }

    fun markFailed(path: String, error: String) {
        val cv = ContentValues().apply {
            put("status", FAILED)
            put("error", error.take(300))
        }
        writableDatabase.update("files", cv, "path=?", arrayOf(path))
    }

    fun markFreed(path: String) {
        val cv = ContentValues().apply { put("freed_at", System.currentTimeMillis()) }
        writableDatabase.update("files", cv, "path=?", arrayOf(path))
    }

    fun delete(path: String) {
        writableDatabase.delete("files", "path=?", arrayOf(path))
    }

    /** Jumlah file & byte yang sudah dibebaskan dari HP (hanya ada di OneDrive). */
    fun freedTotals(): Pair<Int, Long> =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(size),0) FROM files WHERE freed_at>0", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else 0 to 0L }

    fun stats(): Stats {
        var uploaded = 0
        var bytes = 0L
        var pending = 0
        var failed = 0
        readableDatabase.rawQuery(
            "SELECT status, COUNT(*), COALESCE(SUM(size),0) FROM files GROUP BY status", null
        ).use { c ->
            while (c.moveToNext()) {
                when (c.getInt(0)) {
                    UPLOADED -> { uploaded = c.getInt(1); bytes = c.getLong(2) }
                    PENDING -> pending = c.getInt(1)
                    FAILED -> failed = c.getInt(1)
                }
            }
        }
        return Stats(uploaded, bytes, pending, failed)
    }

    fun recent(limit: Int): List<Rec> =
        readableDatabase.query(
            "files", COLS, "status=?", arrayOf(UPLOADED.toString()),
            null, null, "uploaded_at DESC", limit.toString()
        ).use { c ->
            val out = ArrayList<Rec>()
            while (c.moveToNext()) out += c.toRec()
            out
        }

    fun countUploaded(folder: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM files WHERE folder=? AND status=?",
            arrayOf(folder, UPLOADED.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Hapus catatan antrean untuk file yang sudah tidak ada atau foldernya sudah dilepas. */
    fun pruneQueue(validFolders: Set<String>) {
        val stale = ArrayList<String>()
        readableDatabase.query(
            "files", arrayOf("path", "folder"), "status<>?", arrayOf(UPLOADED.toString()),
            null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0)
                if (c.getString(1) !in validFolders || !File(path).exists()) stale += path
            }
        }
        stale.forEach { writableDatabase.delete("files", "path=?", arrayOf(it)) }
    }
}
