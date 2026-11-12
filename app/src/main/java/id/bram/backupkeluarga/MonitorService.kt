package id.bram.backupkeluarga

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import java.io.File

/**
 * Layanan yang terus memantau folder. Begitu ada file baru, backup dijadwalkan
 * ~20 detik kemudian (menunggu kalau ada beberapa file sekaligus).
 */
class MonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val observers = ArrayList<FileObserver>()
    private var mediaObserver: ContentObserver? = null
    private val kick = Runnable { Scheduler.syncNow(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        val folders = prefs.folders
        val notification = Notifier.monitor(this, folders.size)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifier.ID_MONITOR, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifier.ID_MONITOR, notification)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!prefs.isSignedIn) {
            stopSelf()
            return START_NOT_STICKY
        }
        registerWatchers(folders)
        return START_STICKY
    }

    private fun registerWatchers(folders: List<WatchedFolder>) {
        unregisterWatchers()
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
        for (folder in folders) {
            val root = File(folder.path)
            if (!root.isDirectory) continue
            root.walkTopDown()
                .onEnter { it == root || !it.name.startsWith(".") }
                .filter { it.isDirectory }
                .take(300)
                .forEach { dir ->
                    val obs = object : FileObserver(dir, mask) {
                        override fun onEvent(event: Int, path: String?) {
                            if (path != null && !path.startsWith(".")) schedule()
                        }
                    }
                    obs.startWatching()
                    observers += obs
                }
        }
        // Cadangan: perubahan galeri (foto/video baru) juga memicu backup.
        val co = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = schedule()
        }
        try {
            contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, co)
            mediaObserver = co
        } catch (e: Exception) {
            mediaObserver = null
        }
    }

    private fun unregisterWatchers() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
        mediaObserver = null
    }

    private fun schedule() {
        handler.removeCallbacks(kick)
        handler.postDelayed(kick, 20_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(kick)
        unregisterWatchers()
        super.onDestroy()
    }
}
