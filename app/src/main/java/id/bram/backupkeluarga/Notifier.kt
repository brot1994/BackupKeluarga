package id.bram.backupkeluarga

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import id.bram.backupkeluarga.ui.MainActivity

object Notifier {
    private const val CH_MONITOR = "monitor"
    private const val CH_SYNC = "sync"
    private const val CH_ALERT = "alert"
    const val ID_MONITOR = 1
    private const val ID_SYNC = 2
    private const val ID_ALERT = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_MONITOR, "Pemantauan folder", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Tanda bahwa backup otomatis sedang aktif"
                    setShowBadge(false)
                },
                NotificationChannel(CH_SYNC, "Proses upload", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
                NotificationChannel(CH_ALERT, "Pemberitahuan penting", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }

    const val EXTRA_OPEN = "open"
    const val OPEN_CLEAN = "clean"
    private const val ID_FREE = 4

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openClean(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN, OPEN_CLEAN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun sizeText(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        return if (gb >= 1) String.format(java.util.Locale("id"), "%.1f GB", gb)
        else String.format(java.util.Locale("id"), "%.0f MB", bytes / (1024.0 * 1024))
    }

    @SuppressLint("MissingPermission")
    fun readyToFree(context: Context, count: Int, bytes: Long) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle("$count file siap dihapus dari HP (${sizeText(bytes)})")
            .setContentText("Semua sudah aman di OneDrive. Ketuk untuk meninjau.")
            .setAutoCancel(true)
            .setContentIntent(openClean(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_FREE, n)
        } catch (e: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    fun freed(context: Context, count: Int, bytes: Long) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CH_SYNC)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle("$count file dipindah ke OneDrive")
            .setContentText("${sizeText(bytes)} ruang HP dibebaskan")
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_FREE, n)
        } catch (e: SecurityException) {
        }
    }

    fun monitor(context: Context, folderCount: Int): Notification =
        NotificationCompat.Builder(context, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle("Backup Keluarga aktif")
            .setContentText("Memantau $folderCount folder")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp(context))
            .build()

    fun syncInfo(context: Context, ui: SyncState.Ui): ForegroundInfo {
        val b = NotificationCompat.Builder(context, CH_SYNC)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
        val name = ui.currentName
        if (name != null) {
            val pct = if (ui.currentTotal > 0) (ui.currentSent * 100 / ui.currentTotal).toInt() else 0
            b.setContentTitle("Mengunggah ${ui.done + 1} dari ${ui.total}")
                .setContentText(name)
                .setProgress(100, pct, false)
        } else {
            b.setContentTitle("Backup Keluarga")
                .setContentText(ui.message ?: "Memeriksa file baru…")
                .setProgress(0, 0, true)
        }
        return ForegroundInfo(ID_SYNC, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun canNotify(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    @SuppressLint("MissingPermission")
    fun authExpired(context: Context) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle("Backup berhenti sementara")
            .setContentText("Login perlu disetujui ulang oleh Bram. Ketuk untuk membuka.")
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_ALERT, n)
        } catch (e: SecurityException) {
            // izin notifikasi dicabut; abaikan
        }
    }
}
