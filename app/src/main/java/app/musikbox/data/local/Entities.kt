package app.musikbox.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.musikbox.lyrics.LyricsQuery
import kotlinx.serialization.Serializable

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val comment: String?,
    val owner: String?,
    val isPublic: Boolean,
    val songCount: Int,
    val durationSec: Long,
    val coverArt: String?,
    val created: String?,
    val changed: String?,
    /** The server's `changed` stamp at the moment tracks were last cached; null = never cached. */
    val tracksSyncedChanged: String?,
)

/** Local-only state the Subsonic API has no notion of. Survives server re-syncs. */
@Entity(tableName = "playlist_prefs")
data class PlaylistPrefs(
    @PrimaryKey val playlistId: String,
    val isFavorite: Boolean = false,
    val favoritedAt: Long? = null,
    val lastPlayedAt: Long? = null,
    val offline: Boolean = false,
    /** When [isFavorite] / [offline] last changed — for last-writer-wins sync between devices. */
    @ColumnInfo(defaultValue = "0") val favoriteChangedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val offlineChangedAt: Long = 0,
)

@Serializable
@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: String?,
    val artistId: String?,
    val coverArt: String?,
    val track: Int?,
    val discNumber: Int?,
    val year: Int?,
    val genre: String?,
    val durationSec: Int,
    val bitRate: Int?,
    val suffix: String?,
    val sizeBytes: Long?,
    val starred: Boolean,
)

fun SongEntity.lyricsQuery() = LyricsQuery(id, title, artist, album, durationSec)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "position"],
    indices = [Index("songId")],
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val position: Int,
    val songId: String,
)

data class PlaylistItem(
    @Embedded val playlist: PlaylistEntity,
    val isFavorite: Boolean,
    val lastPlayedAt: Long?,
    val offline: Boolean,
) {
    val id get() = playlist.id
    val name get() = playlist.name
}

data class PlaylistTrack(
    @Embedded val song: SongEntity,
    val position: Int,
)

/** Slim (playlist, song) row for computing download progress without loading full songs. */
data class TrackRef(
    val playlistId: String,
    val songId: String,
    val sizeBytes: Long?,
)
