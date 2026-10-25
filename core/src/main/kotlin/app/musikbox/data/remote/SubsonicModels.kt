package app.musikbox.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

/** Some Subsonic implementations emit ids as numbers, Navidrome as strings. Accept both. */
object FlexString : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexString", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String =
        if (decoder is JsonDecoder) decoder.decodeJsonElement().jsonPrimitive.content else decoder.decodeString()
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

object FlexStringOrNull : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexStringOrNull", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String? {
        if (decoder !is JsonDecoder) return decoder.decodeString()
        val el = decoder.decodeJsonElement()
        return if (el is JsonNull) null else el.jsonPrimitive.content
    }
    override fun serialize(encoder: Encoder, value: String?) = encoder.encodeString(value ?: "")
}

@Serializable
data class SubsonicEnvelope(@SerialName("subsonic-response") val response: SubsonicResponse)

@Serializable
data class SubsonicResponse(
    val status: String,
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val error: SubsonicError? = null,
    val playlists: PlaylistsDto? = null,
    val playlist: PlaylistDto? = null,
    val searchResult3: SearchResult3Dto? = null,
    val albumList2: AlbumListDto? = null,
    val album: AlbumDto? = null,
    val artist: ArtistDto? = null,
    val randomSongs: SongListDto? = null,
    val similarSongs2: SongListDto? = null,
    val topSongs: SongListDto? = null,
    val starred2: Starred2Dto? = null,
    val lyricsList: LyricsListDto? = null,
    val lyrics: LegacyLyricsDto? = null,
)

@Serializable
data class SubsonicError(val code: Int = 0, val message: String? = null)

@Serializable
data class PlaylistsDto(val playlist: List<PlaylistDto> = emptyList())

@Serializable
data class PlaylistDto(
    @Serializable(with = FlexString::class) val id: String,
    val name: String = "",
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean = false,
    val songCount: Int = 0,
    val duration: Long = 0,
    val created: String? = null,
    val changed: String? = null,
    @Serializable(with = FlexStringOrNull::class) val coverArt: String? = null,
    val entry: List<SongDto> = emptyList(),
)

@Serializable
data class SongDto(
    @Serializable(with = FlexString::class) val id: String,
    val title: String = "",
    val album: String? = null,
    val artist: String? = null,
    @Serializable(with = FlexStringOrNull::class) val albumId: String? = null,
    @Serializable(with = FlexStringOrNull::class) val artistId: String? = null,
    @Serializable(with = FlexStringOrNull::class) val coverArt: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val duration: Int = 0,
    val bitRate: Int? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val size: Long? = null,
    val starred: String? = null,
    val isDir: Boolean = false,
    /** Navidrome/OpenSubsonic: how often this user played it, and when last. */
    val playCount: Long = 0,
    val played: String? = null,
)

/** OpenSubsonic `songLyrics`: one entry per language/kind, synced or plain. */
@Serializable
data class LyricsListDto(val structuredLyrics: List<StructuredLyricsDto> = emptyList())

@Serializable
data class StructuredLyricsDto(
    val kind: String? = null,
    val lang: String? = null,
    val synced: Boolean = false,
    /** Milliseconds; positive shows every line that much sooner. */
    val offset: Long = 0,
    val line: List<LyricLineDto> = emptyList(),
)

@Serializable
data class LyricLineDto(val start: Long? = null, val value: String = "")

/** Classic Subsonic `getLyrics`: plain text only. */
@Serializable
data class LegacyLyricsDto(val artist: String? = null, val title: String? = null, val value: String? = null)

@Serializable
data class SearchResult3Dto(val song: List<SongDto> = emptyList())

@Serializable
data class SongListDto(val song: List<SongDto> = emptyList())

@Serializable
data class AlbumDto(
    @Serializable(with = FlexString::class) val id: String,
    val name: String = "",
    val artist: String? = null,
    @Serializable(with = FlexStringOrNull::class) val artistId: String? = null,
    @Serializable(with = FlexStringOrNull::class) val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Long = 0,
    val year: Int? = null,
    val genre: String? = null,
    val created: String? = null,
    val starred: String? = null,
    val playCount: Long = 0,
    val played: String? = null,
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class AlbumListDto(val album: List<AlbumDto> = emptyList())

@Serializable
data class ArtistDto(
    @Serializable(with = FlexString::class) val id: String,
    val name: String = "",
    val album: List<AlbumDto> = emptyList(),
)

@Serializable
data class Starred2Dto(
    val song: List<SongDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val artist: List<ArtistDto> = emptyList(),
)
