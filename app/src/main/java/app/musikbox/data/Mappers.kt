package app.musikbox.data

import app.musikbox.data.local.PlaylistEntity
import app.musikbox.data.local.SongEntity
import app.musikbox.data.remote.PlaylistDto
import app.musikbox.data.remote.SongDto

fun PlaylistDto.toEntity(tracksSyncedChanged: String?, serverPlays: Long? = null) = PlaylistEntity(
    id = id,
    name = name,
    comment = comment,
    owner = owner,
    isPublic = public,
    songCount = songCount,
    durationSec = duration,
    coverArt = coverArt,
    created = created,
    changed = changed,
    tracksSyncedChanged = tracksSyncedChanged,
    serverPlays = serverPlays,
)

fun SongDto.toEntity() = SongEntity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    artistId = artistId,
    coverArt = coverArt,
    track = track,
    discNumber = discNumber,
    year = year,
    genre = genre,
    durationSec = duration,
    bitRate = bitRate,
    suffix = suffix,
    sizeBytes = size,
    starred = starred != null,
)
