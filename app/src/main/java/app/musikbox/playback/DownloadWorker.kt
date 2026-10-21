package app.musikbox.playback

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.musikbox.MusikboxApp
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Restarts downloads without the app on screen: WorkManager runs this once the network
 * constraint is met (Wi-Fi when "Wi-Fi only" is on), keeps the process alive while the
 * download manager works, and asks to be rescheduled if songs are still missing.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MusikboxApp).container
        if (container.settings.current.credentials == null) return Result.success()
        val offline = container.offline
        val log = container.log
        offline.awaitReady()
        if (offline.status.value.paused) {
            log.d("Background downloads skipped — paused")
            return Result.success()
        }
        log.i("Background download run #${runAttemptCount + 1}")
        container.library.refreshOfflineDownloads()
        offline.retryFailed("background run", reset = false)

        val deadline = System.currentTimeMillis() + MAX_RUN_MS
        while (System.currentTimeMillis() < deadline && offline.pending.value.isNotEmpty()) {
            delay(3_000)
        }

        val pending = offline.pending.value.size
        val failed = offline.failed.value.size
        return when {
            pending == 0 && failed == 0 -> {
                log.i("Background downloads complete")
                Result.success()
            }
            runAttemptCount >= MAX_ATTEMPTS -> {
                log.w("Background downloads stopped after ${runAttemptCount + 1} runs — $pending waiting, $failed failed")
                Result.success()
            }
            else -> {
                log.i("Background run ended — $pending waiting, $failed failed; will try again")
                Result.retry()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "offline-downloads"
        private const val MAX_RUN_MS = 8 * 60_000L
        private const val MAX_ATTEMPTS = 12

        fun enqueue(context: Context, wifiOnly: Boolean) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
