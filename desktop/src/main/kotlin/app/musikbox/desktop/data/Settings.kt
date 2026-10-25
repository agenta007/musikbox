package app.musikbox.desktop.data

import app.musikbox.data.settings.Credentials
import app.musikbox.desktop.AppPaths
import app.musikbox.desktop.JsonFile
import app.musikbox.lyrics.LyricsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
data class DesktopSettings(
    val serverUrl: String? = null,
    val username: String? = null,
    val salt: String? = null,
    /** Salted Subsonic token — the password itself is never stored. */
    val token: String? = null,
    val streamBitrate: Int = 0,
    val autoDownloadAll: Boolean = false,
    val autoDownloadSaved: Boolean = false,
    val autoDownloadLiked: Boolean = false,
    val visualizerStyle: String = "Bars",
    /** Ask an LRCLIB server when Navidrome has no lyrics for a song. Off: nothing leaves the user's server. */
    val lyricsOnline: Boolean = false,
    val lyricsServer: String = LyricsService.DEFAULT_LRCLIB,
    /** Now Playing shows lyrics instead of the queue. */
    val showLyrics: Boolean = false,
    val pureBlack: Boolean = false,
    val cacheSizeMb: Int = 2048,
    val lastSyncAt: Long = 0L,
    val volume: Int = 80,
    val windowWidth: Int = 1320,
    val windowHeight: Int = 860,
    // ---- Device sync ----
    val deviceId: String? = null,
    val deviceName: String? = null,
    /** Hex PBKDF2 key derived from the password at sign-in; shared by the account's devices. */
    val syncKey: String? = null,
    val syncEnabled: Boolean = true,
    val shareDownloadList: Boolean = false,
    val manualPeers: List<String> = emptyList(),
) {
    val credentials: Credentials?
        get() = if (serverUrl != null && username != null && salt != null && token != null) {
            Credentials(serverUrl, username, salt, token)
        } else null
}

class SettingsRepo {
    private val store = JsonFile(AppPaths.settingsFile, DesktopSettings.serializer(), private = true)
    private val _state = MutableStateFlow(store.load() ?: DesktopSettings())
    val state: StateFlow<DesktopSettings> = _state.asStateFlow()
    val current: DesktopSettings get() = _state.value

    fun update(transform: (DesktopSettings) -> DesktopSettings) {
        _state.update(transform)
        store.saveBlocking(_state.value)
    }

    fun signIn(c: Credentials) = update {
        it.copy(serverUrl = c.serverUrl, username = c.username, salt = c.salt, token = c.token)
    }

    fun signOut() = update { it.copy(serverUrl = null, username = null, salt = null, token = null, lastSyncAt = 0, syncKey = null) }
}
