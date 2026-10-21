package app.musikbox.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musikbox.ui.LocalAppContainer
import kotlinx.coroutines.launch

/** "Download automatically": liked songs · saved playlists · all playlists. Device-local choices. */
@Composable
fun DownloadRulesCard(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val library = container.library
    fun set(all: Boolean? = null, saved: Boolean? = null, liked: Boolean? = null) =
        container.scope.launch { library.setDownloadRule(all, saved, liked) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 6.dp),
    ) {
        Text(
            "DOWNLOAD AUTOMATICALLY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
        )
        RuleRow(Icons.Rounded.Star, "Liked songs", "Every song you star, as you star it", settings.autoDownloadLiked) { set(liked = it) }
        RuleRow(Icons.Rounded.Bookmark, "Saved playlists", "Playlists you save, including ones saved on other devices", settings.autoDownloadSaved) { set(saved = it) }
        RuleRow(Icons.Rounded.LibraryMusic, "All playlists", "Every playlist on the server, including new ones", settings.autoDownloadAll) { set(all = it) }
    }
}

@Composable
private fun RuleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
