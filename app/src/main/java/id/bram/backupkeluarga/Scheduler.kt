package id.bram.backupkeluarga

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val PERIODIC = "sync-periodic"
    private const val NOW = "sync-now"

    private fun constraints(p: Prefs): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (p.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(p.chargingOnly)
            .build()

    /** Dipanggil dari layar aplikasi (posisi foreground). */
    fun start(context: Context) {
        schedulePeriodic(context, ExistingPeriodicWorkPolicy.UPDATE)
        startMonitor(context)
        syncNow(context)
    }

    /** Scan ulang tiap 30 menit sebagai jaring pengaman. */
    fun schedulePeriodic(context: Context, policy: ExistingPeriodicWorkPolicy) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints(Prefs(context)))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, policy, req)
    }

    fun syncNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(Prefs(context)))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }

    /** Mulai (atau segarkan) layanan pemantau folder. */
    fun startMonitor(context: Context) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        } catch (e: Exception) {
            // Android menolak memulai dari latar belakang; akan dicoba lagi saat aplikasi dibuka.
        }
    }

    fun stop(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(NOW)
        context.stopService(Intent(context, MonitorService::class.java))
    }
}
