package app.musikbox.data

/** Virtual playlist holding the account's starred (⭐) songs, kept in sync with the server. */
const val LIKED_PLAYLIST_ID = "musikbox:liked"
const val LIKED_PLAYLIST_NAME = "Liked songs"

/** Device-local "download automatically" choices, on top of per-playlist downloads. */
data class DownloadRules(
    /** Every server playlist (not the liked list — that has its own switch). */
    val all: Boolean = false,
    /** Every saved (bookmarked) playlist. */
    val saved: Boolean = false,
    /** The liked-songs list. */
    val liked: Boolean = false,
)

/** Whether a playlist's songs belong on disk: chosen explicitly, or covered by a rule. */
fun inDownloadList(id: String, offline: Boolean, saved: Boolean, rules: DownloadRules): Boolean =
    offline || autoReason(id, saved, rules) != null

/** Which rule downloads this playlist automatically, if any — shown instead of a remove button. */
fun autoReason(id: String, saved: Boolean, rules: DownloadRules): String? = when {
    id == LIKED_PLAYLIST_ID -> if (rules.liked) "Liked songs download automatically" else null
    rules.all -> "All playlists download automatically"
    rules.saved && saved -> "Saved playlists download automatically"
    else -> null
}
