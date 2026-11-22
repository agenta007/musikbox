package app.musikbox.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.musikbox.data.PlaylistSort
import app.musikbox.data.PlaylistSortFields
import app.musikbox.data.sortedForPlaylists
import app.musikbox.desktop.data.DesktopSettings
import app.musikbox.desktop.data.PlaylistItem

/** The playlists in the order chosen in the sort menu, which Home and the sidebar share. */
fun List<PlaylistItem>.sortedLike(settings: DesktopSettings): List<PlaylistItem> =
    sortedForPlaylists(PlaylistSort.parse(settings.playlistSort), settings.playlistSortReversed) {
        PlaylistSortFields(
            name = it.name, owner = it.playlist.owner, created = it.playlist.created, changed = it.playlist.changed,
            lastPlayedAt = it.lastPlayedAt, songCount = it.playlist.songCount, durationSec = it.playlist.durationSec,
        )
    }

/**
 * Picks how playlists are ordered and saves it in the settings. [compact]: an icon alone, for
 * the sidebar, naming the current order in its tooltip; otherwise an icon and that name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistSortMenu(compact: Boolean = false, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val settings by c.settings.state.collectAsState()
    val current = PlaylistSort.parse(settings.playlistSort)
    val reversed = settings.playlistSortReversed
    val label = current.label(reversed)
    val choose = { s: PlaylistSort, r: Boolean -> c.settings.update { it.copy(playlistSort = s.name, playlistSortReversed = r) } }
    var open by remember { mutableStateOf(false) }

    Box(modifier) {
        if (compact) {
            TooltipArea(tooltip = {
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.inverseSurface) {
                    Text("Sort: $label", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }) {
                IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, "Sort playlists: $label",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        } else {
            TextButton(onClick = { open = true }) {
                Icon(Icons.AutoMirrored.Rounded.Sort, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(10.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
            PlaylistSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    leadingIcon = { if (s == current) Icon(Icons.Rounded.Check, "Selected") else Spacer(Modifier.size(24.dp)) },
                    // Picking another order starts in its normal direction.
                    onClick = { open = false; choose(s, false) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text("Reverse order") },
                leadingIcon = { Icon(Icons.Rounded.SwapVert, null) },
                trailingIcon = { if (reversed) Icon(Icons.Rounded.Check, "On") },
                onClick = { open = false; choose(current, !reversed) },
            )
        }
    }
}
