package app.musikbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musikbox.data.local.PlaylistItem
import androidx.compose.material.icons.rounded.Bookmark

/** Large card for the "Jump back in" carousel, with a one-tap play button. */
@Composable
fun RecentPlaylistCard(
    item: PlaylistItem,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    isActive: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Column(modifier.width(164.dp).clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onOpen, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            CoverArt(
                item.playlist.coverArt,
                Modifier.fillMaxSize(),
                sizePx = 400,
                shape = RoundedCornerShape(14.dp),
                iconSize = 40.dp,
                seed = item.id,
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(40.dp)
                    .shadow(10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                if (isActive) {
                    Equalizer(MaterialTheme.colorScheme.onPrimary, isPlaying, size = 16.dp)
                } else {
                    Icon(Icons.Rounded.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            songsLabel(item.playlist.songCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        )
    }
}

/** Compact two-up tile for pinned favourites. */
@Composable
fun FavoriteTile(
    item: PlaylistItem,
    onClick: () -> Unit,
    isActive: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .height(60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(item.playlist.coverArt, Modifier.size(60.dp), sizePx = 160, shape = RoundedCornerShape(0.dp), seed = item.id)
        Text(
            item.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        if (isActive) {
            Equalizer(MaterialTheme.colorScheme.primary, isPlaying, Modifier.padding(end = 12.dp), size = 14.dp)
        }
    }
}

@Composable
fun PlaylistRow(
    item: PlaylistItem,
    onClick: () -> Unit,
    isActive: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** Non-null shows a ⋮ button (and long-press) for playlist actions. */
    onMore: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .padding(start = 20.dp, end = if (onMore != null) 4.dp else 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(item.playlist.coverArt, Modifier.size(58.dp), sizePx = 160, shape = RoundedCornerShape(8.dp), seed = item.id)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isFavorite) {
                    Icon(Icons.Rounded.Bookmark, "Saved", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                }
                if (item.offline) {
                    Icon(Icons.Rounded.DownloadForOffline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    buildString {
                        append(songsLabel(item.playlist.songCount))
                        if (item.playlist.durationSec > 0) append(" · ").append(formatLongDuration(item.playlist.durationSec))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (isActive) Equalizer(MaterialTheme.colorScheme.primary, isPlaying, Modifier.padding(start = 8.dp))
        if (onMore != null) {
            IconButton(onClick = onMore) {
                Icon(Icons.Rounded.MoreVert, "Playlist actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Vertical fade from a tint into the page background. */
fun tintFade(color: Color, bottom: Color, strength: Float = 0.55f) =
    Brush.verticalGradient(listOf(color.copy(alpha = strength), color.copy(alpha = strength * 0.35f), bottom))
