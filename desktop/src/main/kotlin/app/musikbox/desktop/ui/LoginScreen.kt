package app.musikbox.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.musikbox.data.remote.friendlySignInMessage
import app.musikbox.data.remote.signIn
import kotlinx.coroutines.launch
import app.musikbox.sync.SyncCrypto
import app.musikbox.sync.SyncCrypto.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LoginScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || server.isBlank() || user.isBlank()) return
        busy = true
        error = null
        scope.launch {
            c.api.signIn(server, user.trim(), password)
                .onSuccess { creds ->
                    c.settings.signIn(creds)
                    c.scope.launch { c.library.sync(force = true) }
                    // Same password on every device → same sync key; derive it now (never stored as-is).
                    val u = user.trim()
                    val pw = password
                    c.scope.launch(Dispatchers.IO) {
                        val key = SyncCrypto.deriveKey(u, pw).toHex()
                        withContext(Dispatchers.Main) {
                            c.settings.update { it.copy(syncKey = key) }
                            c.sync.syncNow()
                        }
                    }
                }
                .onFailure { error = it.friendlySignInMessage() }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.2f, size.height * 0.15f)
            drawCircle(Brush.radialGradient(listOf(Palette.Accent.copy(alpha = 0.3f), Color.Transparent), center, size.width * 0.6f), size.width * 0.6f, center)
            val c2 = Offset(size.width * 0.9f, size.height * 0.8f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF7B5CFF).copy(alpha = 0.16f), Color.Transparent), c2, size.width * 0.5f), size.width * 0.5f, c2)
        }
        Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                buildAnnotatedString {
                    append("musikbox")
                    withStyle(SpanStyle(color = Palette.Accent)) { append(".") }
                },
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp),
            )
            Text("Your playlists, everywhere.\nConnect to your Navidrome or Subsonic server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Field(server, { server = it }, "Server address", Icons.Rounded.Dns, placeholder = "music.example.com")
            Field(user, { user = it }, "Username", Icons.Rounded.Person)
            Field(password, { password = it }, "Password", Icons.Rounded.Lock, visual = PasswordVisualTransformation(), onDone = ::submit)
            AnimatedVisibility(error != null) { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = ::submit,
                enabled = !busy && server.isNotBlank() && user.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Connect", style = MaterialTheme.typography.titleMedium)
            }
            Text("Your password is never stored — only a salted token.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    placeholder: String? = null,
    visual: VisualTransformation = VisualTransformation.None,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = { Icon(icon, null) }, singleLine = true, visualTransformation = visual,
        keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Go else ImeAction.Next),
        keyboardActions = KeyboardActions(onGo = { onDone?.invoke() }),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
    )
}
