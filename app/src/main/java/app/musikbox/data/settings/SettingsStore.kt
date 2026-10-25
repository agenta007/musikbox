package app.musikbox.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.musikbox.lyrics.LyricsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val credentials: Credentials? = null,
    /** 0 = original file, otherwise max kbps for transcoded streaming. */
    val streamBitrate: Int = 0,
    val wifiOnlyDownloads: Boolean = true,
    /** User paused all downloads (e.g. after deleting files but keeping the download list). */
    val downloadsPaused: Boolean = false,
    val autoDownloadAll: Boolean = false,
    val autoDownloadSaved: Boolean = false,
    val autoDownloadLiked: Boolean = false,
    val visualizerStyle: String = "Bars",
    /** Ask an LRCLIB server when Navidrome has no lyrics for a song. Off: nothing leaves the user's server. */
    val lyricsOnline: Boolean = false,
    val lyricsServer: String = LyricsService.DEFAULT_LRCLIB,
    val pureBlack: Boolean = false,
    val cacheSizeMb: Int = 2048,
    val lastSyncAt: Long = 0L,
    // ---- Device sync ----
    val deviceId: String? = null,
    val deviceName: String? = null,
    /** Hex PBKDF2 key derived from the password at sign-in; shared by the account's devices. */
    val syncKey: String? = null,
    val syncEnabled: Boolean = true,
    val shareDownloadList: Boolean = false,
    val manualPeers: List<String> = emptyList(),
)

class SettingsStore(private val context: Context, scope: CoroutineScope) {

    private object Keys {
        val server = stringPreferencesKey("server")
        val user = stringPreferencesKey("user")
        val salt = stringPreferencesKey("salt")
        val token = stringPreferencesKey("token")
        val bitrate = intPreferencesKey("stream_bitrate")
        val wifiOnly = booleanPreferencesKey("wifi_only_downloads")
        val downloadsPaused = booleanPreferencesKey("downloads_paused")
        val autoDownload = booleanPreferencesKey("auto_download_all")
        val autoDownloadSaved = booleanPreferencesKey("auto_download_saved")
        val autoDownloadLiked = booleanPreferencesKey("auto_download_liked")
        val visualizer = stringPreferencesKey("visualizer_style")
        val lyricsOnline = booleanPreferencesKey("lyrics_online")
        val lyricsServer = stringPreferencesKey("lyrics_server")
        val pureBlack = booleanPreferencesKey("pure_black")
        val cacheSize = intPreferencesKey("cache_size_mb")
        val lastSync = longPreferencesKey("last_sync")
        val deviceId = stringPreferencesKey("device_id")
        val deviceName = stringPreferencesKey("device_name")
        val syncKey = stringPreferencesKey("sync_key")
        val syncEnabled = booleanPreferencesKey("sync_enabled")
        val shareDownloads = booleanPreferencesKey("share_download_list")
        val manualPeers = stringSetPreferencesKey("manual_peers")
    }

    private val flow = context.settingsDataStore.data.map { it.toSettings() }

    /**
     * Read synchronously once at startup: the playback service, image loader and URL builders
     * all need credentials without suspending. DataStore reads are a few ms.
     */
    val settings: StateFlow<AppSettings> =
        flow.stateIn(scope, SharingStarted.Eagerly, runBlocking { flow.first() })

    val current: AppSettings get() = settings.value

    private fun Preferences.toSettings(): AppSettings {
        val server = this[Keys.server]
        val user = this[Keys.user]
        val salt = this[Keys.salt]
        val token = this[Keys.token]
        val creds = if (server != null && user != null && salt != null && token != null) {
            Credentials(server, user, salt, token)
        } else null
        return AppSettings(
            credentials = creds,
            streamBitrate = this[Keys.bitrate] ?: 0,
            wifiOnlyDownloads = this[Keys.wifiOnly] ?: true,
            downloadsPaused = this[Keys.downloadsPaused] ?: false,
            autoDownloadAll = this[Keys.autoDownload] ?: false,
            autoDownloadSaved = this[Keys.autoDownloadSaved] ?: false,
            autoDownloadLiked = this[Keys.autoDownloadLiked] ?: false,
            visualizerStyle = this[Keys.visualizer] ?: "Bars",
            lyricsOnline = this[Keys.lyricsOnline] ?: false,
            lyricsServer = this[Keys.lyricsServer] ?: LyricsService.DEFAULT_LRCLIB,
            pureBlack = this[Keys.pureBlack] ?: false,
            cacheSizeMb = this[Keys.cacheSize] ?: 2048,
            lastSyncAt = this[Keys.lastSync] ?: 0L,
            deviceId = this[Keys.deviceId],
            deviceName = this[Keys.deviceName],
            syncKey = this[Keys.syncKey],
            syncEnabled = this[Keys.syncEnabled] ?: true,
            shareDownloadList = this[Keys.shareDownloads] ?: false,
            manualPeers = this[Keys.manualPeers]?.sorted().orEmpty(),
        )
    }

    suspend fun saveCredentials(c: Credentials) = context.settingsDataStore.edit {
        it[Keys.server] = c.serverUrl
        it[Keys.user] = c.username
        it[Keys.salt] = c.salt
        it[Keys.token] = c.token
    }

    suspend fun clearCredentials() = context.settingsDataStore.edit {
        it.remove(Keys.server); it.remove(Keys.user); it.remove(Keys.salt); it.remove(Keys.token)
        it.remove(Keys.lastSync)
        it.remove(Keys.syncKey)
    }

    suspend fun setDeviceId(v: String) = context.settingsDataStore.edit { it[Keys.deviceId] = v }
    suspend fun setDeviceName(v: String) = context.settingsDataStore.edit { it[Keys.deviceName] = v }
    suspend fun setSyncKey(v: String) = context.settingsDataStore.edit { it[Keys.syncKey] = v }
    suspend fun setSyncEnabled(v: Boolean) = context.settingsDataStore.edit { it[Keys.syncEnabled] = v }
    suspend fun setShareDownloadList(v: Boolean) = context.settingsDataStore.edit { it[Keys.shareDownloads] = v }
    suspend fun setManualPeers(v: List<String>) = context.settingsDataStore.edit { it[Keys.manualPeers] = v.toSet() }
    suspend fun setStreamBitrate(v: Int) = context.settingsDataStore.edit { it[Keys.bitrate] = v }
    suspend fun setWifiOnlyDownloads(v: Boolean) = context.settingsDataStore.edit { it[Keys.wifiOnly] = v }
    suspend fun setDownloadsPaused(v: Boolean) = context.settingsDataStore.edit { it[Keys.downloadsPaused] = v }
    suspend fun setVisualizerStyle(v: String) = context.settingsDataStore.edit { it[Keys.visualizer] = v }
    suspend fun setLyricsOnline(v: Boolean) = context.settingsDataStore.edit { it[Keys.lyricsOnline] = v }
    suspend fun setLyricsServer(v: String) = context.settingsDataStore.edit { it[Keys.lyricsServer] = v.trim() }
    suspend fun setAutoDownloadSaved(v: Boolean) = context.settingsDataStore.edit { it[Keys.autoDownloadSaved] = v }
    suspend fun setAutoDownloadLiked(v: Boolean) = context.settingsDataStore.edit { it[Keys.autoDownloadLiked] = v }
    suspend fun setAutoDownloadAll(v: Boolean) = context.settingsDataStore.edit { it[Keys.autoDownload] = v }
    suspend fun setPureBlack(v: Boolean) = context.settingsDataStore.edit { it[Keys.pureBlack] = v }
    suspend fun setCacheSizeMb(v: Int) = context.settingsDataStore.edit { it[Keys.cacheSize] = v }
    suspend fun setLastSync(v: Long) = context.settingsDataStore.edit { it[Keys.lastSync] = v }
}

val AppSettings.downloadRules get() = app.musikbox.data.DownloadRules(all = autoDownloadAll, saved = autoDownloadSaved, liked = autoDownloadLiked)
