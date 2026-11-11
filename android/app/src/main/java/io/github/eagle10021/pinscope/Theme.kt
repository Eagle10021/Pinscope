@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.eagle10021.pinscope

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Colours follow the Mega Kit Pinouts site: a blue board with gold pads, one colour per pin function and part type.

@Immutable
data class PinColors(
    val pcb: Color, val pcbEdge: Color, val silk: Color, val pad: Color, val padOn: Color, val header: Color,
    val function: Map<String, Color>,
    val category: Map<String, Color>,
    val ok: Color, val warn: Color,
) {
    fun forTag(tag: String) = function[tag] ?: pad
    fun forCategory(key: String) = category[key] ?: pad
}

private val LightPins = PinColors(
    pcb = Color(0xFF0F4F8C), pcbEdge = Color(0xFF0A3A68), silk = Color(0xFFF2F6FA),
    pad = Color(0xFFD9AB4F), padOn = Color(0xFFFFD36B), header = Color(0xFF1B1F24),
    function = mapOf(
        "pwm" to Color(0xFFC2410C), "interrupt" to Color(0xFF0E7490), "serial" to Color(0xFF8B4513),
        "i2c" to Color(0xFF2563EB), "spi" to Color(0xFF4D7C0F), "analog" to Color(0xFF15803D),
        "power" to Color(0xFFB91C1C), "digital" to Color(0xFF475569),
    ),
    category = mapOf(
        "display" to Color(0xFFC2410C), "sensor" to Color(0xFF0E7490), "input" to Color(0xFF2563EB),
        "motor" to Color(0xFFA16207), "chip" to Color(0xFF475569), "basic" to Color(0xFF15803D), "power" to Color(0xFFB91C1C),
    ),
    ok = Color(0xFF15803D), warn = Color(0xFFB45309),
)

private val DarkPins = LightPins.copy(
    pcb = Color(0xFF0D467D), pcbEdge = Color(0xFF072A4C), header = Color(0xFF050607),
    function = mapOf(
        "pwm" to Color(0xFFFB923C), "interrupt" to Color(0xFF22D3EE), "serial" to Color(0xFFD6A26B),
        "i2c" to Color(0xFF60A5FA), "spi" to Color(0xFFA3E635), "analog" to Color(0xFF4ADE80),
        "power" to Color(0xFFF87171), "digital" to Color(0xFF94A3B8),
    ),
    category = mapOf(
        "display" to Color(0xFFFB923C), "sensor" to Color(0xFF22D3EE), "input" to Color(0xFF60A5FA),
        "motor" to Color(0xFFFBBF24), "chip" to Color(0xFF94A3B8), "basic" to Color(0xFF4ADE80), "power" to Color(0xFFF87171),
    ),
    ok = Color(0xFF4ADE80), warn = Color(0xFFFBBF24),
)

val LocalPinColors = staticCompositionLocalOf { DarkPins }

// Window width classes: a navigation rail from 600 dp, side-by-side panes from 840 dp (tablets in landscape)
val LocalWide = staticCompositionLocalOf { false }
val LocalRail = staticCompositionLocalOf { false }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0F4F8C), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E4F5), onPrimaryContainer = Color(0xFF062B4E),
    secondary = Color(0xFF8A5A00), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFCE7B8), onSecondaryContainer = Color(0xFF2C1C00),
    tertiary = Color(0xFF0E7490), onTertiary = Color.White,
    background = Color(0xFFF3F5F8), onBackground = Color(0xFF18212B),
    surface = Color(0xFFF3F5F8), onSurface = Color(0xFF18212B),
    surfaceVariant = Color(0xFFE2E7EE), onSurfaceVariant = Color(0xFF4F5C6B),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9FAFC),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFECEFF4), surfaceContainerHighest = Color(0xFFE2E7EE),
    outline = Color(0xFFB9C3CF), outlineVariant = Color(0xFFD3DAE2),
    error = Color(0xFFB91C1C),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFC95C), onPrimary = Color(0xFF2A1B00),
    primaryContainer = Color(0xFF3A2C10), onPrimaryContainer = Color(0xFFFFE2A6),
    secondary = Color(0xFF8CC1F2), onSecondary = Color(0xFF062B4E),
    secondaryContainer = Color(0xFF173A5E), onSecondaryContainer = Color(0xFFD5E4F5),
    tertiary = Color(0xFF22D3EE), onTertiary = Color(0xFF00333D),
    background = Color(0xFF0B121A), onBackground = Color(0xFFE3E9EF),
    surface = Color(0xFF0B121A), onSurface = Color(0xFFE3E9EF),
    surfaceVariant = Color(0xFF1F2832), onSurfaceVariant = Color(0xFF95A3B2),
    surfaceContainerLowest = Color(0xFF070C12), surfaceContainerLow = Color(0xFF111922),
    surfaceContainer = Color(0xFF151E28), surfaceContainerHigh = Color(0xFF1B2530), surfaceContainerHighest = Color(0xFF232E3A),
    outline = Color(0xFF3A4654), outlineVariant = Color(0xFF2B3642),
    error = Color(0xFFF87171),
)

val Mono = FontFamily.Monospace

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

// Small caps-style section label used above groups
val EyebrowStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

@Composable
fun PinscopeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPinColors provides if (dark) DarkPins else LightPins) {
        MaterialExpressiveTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            motionScheme = MotionScheme.expressive(),
            typography = AppTypography,
            shapes = Shapes(
                small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(36.dp),
            ),
            content = content,
        )
    }
}
