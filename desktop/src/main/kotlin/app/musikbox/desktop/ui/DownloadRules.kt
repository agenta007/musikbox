package app.musikbox.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** "Download automatically": liked songs · saved playlists · all playlists. */
@Composable
fun DownloadRulesPanel(modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsState()
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 8.dp)) {
        Text(
            "DOWNLOAD AUTOMATICALLY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
        )
        Rule(Icons.Rounded.Star, "Liked songs", "Every song you star, as you star it", s.autoDownloadLiked) { c.library.setDownloadRule(liked = it) }
        Rule(Icons.Rounded.Bookmark, "Saved playlists", "Playlists you save, including ones saved on other devices", s.autoDownloadSaved) { c.library.setDownloadRule(saved = it) }
        Rule(Icons.Rounded.LibraryMusic, "All playlists", "Every playlist on the server, including new ones", s.autoDownloadAll) { c.library.setDownloadRule(all = it) }
    }
}

@Composable
private fun Rule(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
