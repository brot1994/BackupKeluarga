package id.bram.backupkeluarga

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Prefs(ctx).isSignedIn) return Result.success()

        // Jadikan foreground supaya upload besar tidak dihentikan sistem.
        var foregroundOk = try {
            setForeground(Notifier.syncInfo(ctx, SyncState.Ui(running = true, message = "Memeriksa file baru…")))
            true
        } catch (e: Exception) {
            false
        }

        val outcome = coroutineScope {
            val updater = launch {
                SyncState.flow.collect { ui ->
                    if (foregroundOk && ui.running) {
                        foregroundOk = try {
                            setForeground(Notifier.syncInfo(ctx, ui))
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }
                    delay(1500)
                }
            }
            try {
                SyncEngine.run(ctx)
            } finally {
                updater.cancel()
            }
        }

        if (outcome.authError) {
            Notifier.authExpired(ctx)
        } else {
            try {
                Cleaner.autoRun(ctx)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // penghapusan otomatis dicoba lagi di putaran berikutnya
            }
        }
        return if (outcome.failed > 0 && runAttemptCount < 3) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Notifier.syncInfo(applicationContext, SyncState.Ui(running = true, message = "Menyiapkan backup…"))
}
