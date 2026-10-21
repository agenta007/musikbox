package app.musikbox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

object Palette {
    val Accent = Color(0xFFFF6A3D)
    val AccentSoft = Color(0xFFFFB199)
    val Background = Color(0xFF0B0B10)
    val Surface1 = Color(0xFF14141B)
    val Surface2 = Color(0xFF1C1C25)
    val Surface3 = Color(0xFF282833)
    val TextHigh = Color(0xFFF4F4F7)
    val TextMid = Color(0xFFA6A6B5)
    val TextLow = Color(0xFF6C6C7C)
    val Danger = Color(0xFFFF5C6C)
}

private fun scheme(pureBlack: Boolean) = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFF1A0A04),
    primaryContainer = Color(0xFF3A1D14),
    onPrimaryContainer = Palette.AccentSoft,
    secondary = Palette.AccentSoft,
    onSecondary = Color(0xFF1A0A04),
    background = if (pureBlack) Color.Black else Palette.Background,
    onBackground = Palette.TextHigh,
    surface = if (pureBlack) Color.Black else Palette.Background,
    onSurface = Palette.TextHigh,
    surfaceVariant = if (pureBlack) Color(0xFF111114) else Palette.Surface2,
    onSurfaceVariant = Palette.TextMid,
    surfaceContainerLowest = if (pureBlack) Color.Black else Color(0xFF09090D),
    surfaceContainerLow = if (pureBlack) Color(0xFF08080A) else Palette.Surface1,
    surfaceContainer = if (pureBlack) Color(0xFF0E0E11) else Palette.Surface1,
    surfaceContainerHigh = if (pureBlack) Color(0xFF151518) else Palette.Surface2,
    surfaceContainerHighest = if (pureBlack) Color(0xFF1C1C20) else Palette.Surface3,
    outline = Color(0xFF3A3A47),
    outlineVariant = Color(0xFF26262F),
    error = Palette.Danger,
    scrim = Color.Black,
)

private val base = FontFamily.Default

private val type = Typography(
    displaySmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.03).em),
    headlineLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.025).em),
    headlineMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.015).em),
    titleLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.01.em),
    labelMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.04.em),
    labelSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.06.em),
)

@Composable
fun MusikboxTheme(pureBlack: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme(pureBlack), typography = type, content = content)
}
