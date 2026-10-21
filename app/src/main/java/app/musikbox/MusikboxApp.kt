package app.musikbox

import android.app.Application
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.util.concurrent.TimeUnit

class MusikboxApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        schedulePeriodicSync()
    }

    /** Cover art is cached on disk aggressively so the library still looks right offline. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.okHttp)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("artwork"))
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(220)
        .build()

    private fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("playlist-sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Keeps the playlist mirror fresh in the background. */
class SyncWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MusikboxApp).container
        if (container.settings.current.credentials == null) return Result.success()
        return if (container.library.sync().isSuccess) Result.success() else Result.retry()
    }
}
