package id.bram.backupkeluarga

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy

/** Menyalakan lagi pemantauan setelah HP restart atau aplikasi diperbarui. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs(context).isSignedIn) return
        Scheduler.startMonitor(context)
        Scheduler.schedulePeriodic(context, ExistingPeriodicWorkPolicy.KEEP)
        Scheduler.syncNow(context)
    }
}
