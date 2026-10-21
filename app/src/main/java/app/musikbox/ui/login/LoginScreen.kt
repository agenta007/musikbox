package app.musikbox.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.musikbox.data.remote.friendlySignInMessage
import app.musikbox.data.remote.signIn
import app.musikbox.ui.LocalAppContainer
import app.musikbox.ui.theme.Palette
import kotlinx.coroutines.launch
import app.musikbox.sync.SyncCrypto
import app.musikbox.sync.SyncCrypto.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || server.isBlank() || user.isBlank()) return
        focus.clearFocus()
        busy = true
        error = null
        scope.launch {
            val result = container.api.signIn(server, user.trim(), password)
            result.onSuccess { creds ->
                container.settings.saveCredentials(creds)
                // Same password on every device → same sync key (PBKDF2; the password isn't stored).
                val key = withContext(Dispatchers.Default) { SyncCrypto.deriveKey(user.trim(), password).toHex() }
                container.settings.setSyncKey(key)
                container.sync.syncNow()
                container.cloud.requestSync(0)
                container.scope.launch { container.library.sync(force = true) }
                onSignedIn()
            }.onFailure { error = it.friendlySignInMessage() }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Soft accent glow behind the wordmark.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(
                    listOf(Palette.Accent.copy(alpha = 0.35f), Color.Transparent),
                    center = Offset(size.width * 0.15f, size.height * 0.12f),
                    radius = size.width * 0.9f,
                ),
                radius = size.width * 0.9f,
                center = Offset(size.width * 0.15f, size.height * 0.12f),
            )
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFF7B5CFF).copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(size.width * 0.95f, size.height * 0.45f),
                    radius = size.width * 0.7f,
                ),
                radius = size.width * 0.7f,
                center = Offset(size.width * 0.95f, size.height * 0.45f),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(72.dp))
            Text(
                buildAnnotatedString {
                    append("musikbox")
                    withStyle(SpanStyle(color = Palette.Accent)) { append(".") }
                },
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 46.sp),
            )
            Text(
                "Your playlists, everywhere.\nConnect to your Navidrome or Subsonic server.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            Field(server, { server = it }, "Server address", Icons.Rounded.Dns, KeyboardType.Uri, placeholder = "music.example.com")
            Field(user, { user = it }, "Username", Icons.Rounded.Person, KeyboardType.Text)
            Field(
                password, { password = it }, "Password", Icons.Rounded.Lock, KeyboardType.Password,
                imeAction = ImeAction.Go,
                onIme = ::submit,
                visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Toggle password")
                    }
                },
            )

            AnimatedVisibility(error != null) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(6.dp))
            Button(
                onClick = ::submit,
                enabled = !busy && server.isNotBlank() && user.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Connect", style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                "Your password is never stored — only a salted token.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    keyboard: KeyboardType,
    placeholder: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    onIme: (() -> Unit)? = null,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = trailing,
        singleLine = true,
        visualTransformation = visual,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onAny = { onIme?.invoke() ?: defaultKeyboardAction(imeAction) }),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
