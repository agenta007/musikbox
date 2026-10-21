package app.musikbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val PLAYLIST_SELECT =
    "SELECT p.*, IFNULL(pr.isFavorite, 0) AS isFavorite, pr.lastPlayedAt AS lastPlayedAt, " +
        "IFNULL(pr.offline, 0) AS offline " +
        "FROM playlists p LEFT JOIN playlist_prefs pr ON pr.playlistId = p.id"

@Dao
interface PlaylistDao {
    @Query("$PLAYLIST_SELECT ORDER BY p.name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlaylistItem>>

    @Query("$PLAYLIST_SELECT WHERE pr.lastPlayedAt IS NOT NULL ORDER BY pr.lastPlayedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PlaylistItem>>

    @Query("$PLAYLIST_SELECT WHERE pr.isFavorite = 1 ORDER BY pr.favoritedAt DESC")
    fun observeFavorites(): Flow<List<PlaylistItem>>

    @Query("$PLAYLIST_SELECT WHERE p.id = :id")
    fun observe(id: String): Flow<PlaylistItem?>

    @Query("$PLAYLIST_SELECT WHERE p.name LIKE '%' || :q || '%' ORDER BY p.name COLLATE NOCASE LIMIT 20")
    suspend fun search(q: String): List<PlaylistItem>

    @Query("SELECT * FROM playlists")
    suspend fun getAll(): List<PlaylistEntity>

    @Query(PLAYLIST_SELECT)
    suspend fun getAllItems(): List<PlaylistItem>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: String): PlaylistEntity?

    @Upsert
    suspend fun upsert(playlists: List<PlaylistEntity>)

    @Query("DELETE FROM playlists WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT * FROM playlist_prefs WHERE playlistId = :id")
    suspend fun getPrefs(id: String): PlaylistPrefs?

    @Upsert
    suspend fun upsertPrefs(prefs: PlaylistPrefs)

    @Query("DELETE FROM playlist_prefs WHERE playlistId IN (:ids)")
    suspend fun deletePrefs(ids: List<String>)

    @Query("SELECT playlistId FROM playlist_prefs WHERE offline = 1")
    suspend fun offlineIds(): List<String>

    /** Empties the download list, stamping the change so other devices pick it up. */
    @Query("UPDATE playlist_prefs SET offline = 0, offlineChangedAt = :now WHERE offline = 1")
    suspend fun clearOffline(now: Long)

    @Query("SELECT * FROM playlist_prefs")
    suspend fun allPrefs(): List<PlaylistPrefs>

    @Query("SELECT * FROM playlist_prefs")
    fun observePrefs(): Flow<List<PlaylistPrefs>>

    @Upsert
    suspend fun upsertPrefs(prefs: List<PlaylistPrefs>)
}

@Dao
abstract class TrackDao {
    @Query(
        "SELECT s.*, t.position AS position FROM playlist_tracks t " +
            "INNER JOIN songs s ON s.id = t.songId WHERE t.playlistId = :playlistId ORDER BY t.position",
    )
    abstract fun observeTracks(playlistId: String): Flow<List<PlaylistTrack>>

    @Query(
        "SELECT s.*, t.position AS position FROM playlist_tracks t " +
            "INNER JOIN songs s ON s.id = t.songId WHERE t.playlistId = :playlistId ORDER BY t.position",
    )
    abstract suspend fun getTracks(playlistId: String): List<PlaylistTrack>

    @Query(
        "SELECT DISTINCT s.* FROM playlist_tracks t INNER JOIN songs s ON s.id = t.songId " +
            "WHERE t.playlistId IN (:playlistIds)",
    )
    abstract suspend fun songsInPlaylists(playlistIds: List<String>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    abstract fun observeSong(id: String): Flow<SongEntity?>

    @Query(
        "SELECT t.playlistId AS playlistId, t.songId AS songId, s.sizeBytes AS sizeBytes " +
            "FROM playlist_tracks t INNER JOIN songs s ON s.id = t.songId",
    )
    abstract fun observeTrackRefs(): Flow<List<TrackRef>>

    @Query(
        "SELECT * FROM songs WHERE title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' " +
            "OR album LIKE '%' || :q || '%' ORDER BY title COLLATE NOCASE LIMIT 60",
    )
    abstract suspend fun searchLocal(q: String): List<SongEntity>

    @Upsert
    abstract suspend fun upsertSongs(songs: List<SongEntity>)

    @Query("UPDATE songs SET starred = :starred WHERE id = :id")
    abstract suspend fun setStarred(id: String, starred: Boolean)

    @Query("UPDATE songs SET starred = 0 WHERE starred = 1")
    abstract suspend fun clearAllStarred()

    /** The server's starred songs become the liked list; every other song is un-starred locally. */
    @Transaction
    open suspend fun replaceLiked(playlistId: String, songs: List<SongEntity>) {
        clearAllStarred()
        replaceTracks(playlistId, songs.map { it.copy(starred = true) })
    }

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    abstract suspend fun clearTracks(playlistId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId IN (:playlistIds)")
    abstract suspend fun clearTracksFor(playlistIds: List<String>)

    @Insert
    abstract suspend fun insertTracks(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM songs WHERE id NOT IN (SELECT songId FROM playlist_tracks)")
    abstract suspend fun pruneOrphanSongs()

    @Transaction
    open suspend fun replaceTracks(playlistId: String, songs: List<SongEntity>) {
        upsertSongs(songs.distinctBy { it.id })
        clearTracks(playlistId)
        insertTracks(songs.mapIndexed { i, s -> PlaylistTrackEntity(playlistId, i, s.id) })
    }
}
