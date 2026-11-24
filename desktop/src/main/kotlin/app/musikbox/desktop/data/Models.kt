package app.musikbox.desktop.data

import app.musikbox.data.remote.PlaylistDto
import app.musikbox.data.remote.SongDto
import app.musikbox.lyrics.LyricsQuery
import kotlinx.serialization.Serializable

@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val durationSec: Int = 0,
    val bitRate: Int? = null,
    val suffix: String? = null,
    val sizeBytes: Long? = null,
    val starred: Boolean = false,
)

fun Song.lyricsQuery() = LyricsQuery(id, title, artist, album, durationSec)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val isPublic: Boolean = false,
    val songCount: Int = 0,
    val durationSec: Long = 0,
    val coverArt: String? = null,
    val created: String? = null,
    val changed: String? = null,
    /** Server `changed` stamp when [trackIds] were last fetched; null = never. */
    val tracksSyncedChanged: String? = null,
    val trackIds: List<String> = emptyList(),
    /** Its songs' play counts on the server, summed when [trackIds] were last fetched; null = not yet. */
    val serverPlays: Long? = null,
)

/** Local-only state Subsonic has no notion of. */
@Serializable
data class Prefs(
    val isFavorite: Boolean = false,
    val favoritedAt: Long? = null,
    val lastPlayedAt: Long? = null,
    val offline: Boolean = false,
    /** When [isFavorite] / [offline] last changed — for last-writer-wins sync between devices. */
    val favoriteChangedAt: Long = 0,
    val offlineChangedAt: Long = 0,
    /** Songs played from it on this computer since its tracks were last fetched (see playlistPlays). */
    val localPlays: Int = 0,
)

@Serializable
data class LibraryData(
    val playlists: Map<String, Playlist> = emptyMap(),
    val songs: Map<String, Song> = emptyMap(),
    val prefs: Map<String, Prefs> = emptyMap(),
)

data class PlaylistItem(val playlist: Playlist, val prefs: Prefs) {
    val id get() = playlist.id
    val name get() = playlist.name
    val isFavorite get() = prefs.isFavorite
    val offline get() = prefs.offline
    val lastPlayedAt get() = prefs.lastPlayedAt
    /** For "Most played". */
    val plays get() = app.musikbox.data.playlistPlays(playlist.serverPlays, prefs.localPlays)
}

fun SongDto.toSong() = Song(
    id = id, title = title, artist = artist, album = album, albumId = albumId, artistId = artistId,
    coverArt = coverArt, track = track, discNumber = discNumber, year = year, genre = genre,
    durationSec = duration, bitRate = bitRate, suffix = suffix, sizeBytes = size, starred = starred != null,
)

fun PlaylistDto.toPlaylist(tracksSyncedChanged: String?, trackIds: List<String>, serverPlays: Long? = null) = Playlist(
    id = id, name = name, comment = comment, owner = owner, isPublic = public, songCount = songCount,
    durationSec = duration, coverArt = coverArt, created = created, changed = changed,
    tracksSyncedChanged = tracksSyncedChanged, trackIds = trackIds, serverPlays = serverPlays,
)
