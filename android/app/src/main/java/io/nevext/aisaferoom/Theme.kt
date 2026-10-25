package io.nevext.aisaferoom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The AI Safe Room look: light blue-white, one blue, soft cards; a navy version for dark mode. */
data class Palette(
    val dark: Boolean,
    val background: Color,
    val card: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val soft: Color,   // the light circle behind icons
)

val Blue = Color(0xFF1F6BFF)
val Green = Color(0xFF22C55E)
val Orange = Color(0xFFF97316)
val Red = Color(0xFFEF4444)

private val Light = Palette(false, Color(0xFFF5F8FD), Color.White, Color(0xFFE6ECF5), Color(0xFF0F172A), Color(0xFF64748B), Color(0xFFEAF1FF))
private val Dark = Palette(true, Color(0xFF0A1428), Color(0xFF121E36), Color(0xFF1E2B45), Color(0xFFE8EEF8), Color(0xFF8A98B3), Color(0xFF1A2A4A))

val LocalPalette = staticCompositionLocalOf { Light }
val palette: Palette @Composable get() = LocalPalette.current

@Composable
fun SafeRoomTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(primary = Blue, onPrimary = Color.White, background = p.background, surface = p.card,
        onBackground = p.text, onSurface = p.text)
    else lightColorScheme(primary = Blue, onPrimary = Color.White, background = p.background, surface = p.card,
        onBackground = p.text, onSurface = p.text)
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** A white card with a soft shadow (navy in dark mode). */
fun Modifier.card(p: Palette, radius: Dp = 18.dp, selected: Boolean = false): Modifier =
    this.shadow(if (p.dark) 0.dp else 6.dp, RoundedCornerShape(radius), ambientColor = Color(0x1A1F6BFF), spotColor = Color(0x1A1F6BFF))
        .background(p.card, RoundedCornerShape(radius))
        .border(1.dp, if (selected) Blue else p.line, RoundedCornerShape(radius))

/** The shield with the eye (the lens). */
@Composable
fun Shield(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val shield = Path().apply {
            moveTo(w * 0.5f, 0f)
            lineTo(w * 0.93f, h * 0.15f)
            cubicTo(w * 0.97f, h * 0.17f, w, h * 0.21f, w, h * 0.26f)
            lineTo(w, h * 0.47f)
            cubicTo(w, h * 0.72f, w * 0.78f, h * 0.9f, w * 0.5f, h)
            cubicTo(w * 0.22f, h * 0.9f, 0f, h * 0.72f, 0f, h * 0.47f)
            lineTo(0f, h * 0.26f)
            cubicTo(0f, h * 0.21f, w * 0.03f, h * 0.17f, w * 0.07f, h * 0.15f)
            close()
        }
        drawPath(shield, Brush.verticalGradient(listOf(Color(0xFF3B82FF), Color(0xFF1557E6))))
        val eye = Path().apply {
            moveTo(w * 0.17f, h * 0.5f)
            cubicTo(w * 0.3f, h * 0.3f, w * 0.7f, h * 0.3f, w * 0.83f, h * 0.5f)
            cubicTo(w * 0.7f, h * 0.7f, w * 0.3f, h * 0.7f, w * 0.17f, h * 0.5f)
            close()
        }
        drawPath(eye, Color.White)
        drawCircle(Blue, radius = w * 0.13f, center = Offset(w * 0.5f, h * 0.5f))
        drawCircle(Color.White, radius = w * 0.05f, center = Offset(w * 0.5f, h * 0.5f))
    }
}

/** "AI Safe Room" with "AI" in blue. */
@Composable
fun Brand(size: TextUnit, modifier: Modifier = Modifier) {
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = Blue)) { append("AI ") }
        withStyle(SpanStyle(color = palette.text)) { append("Safe Room") }
    }, fontSize = size, fontWeight = FontWeight.Bold, modifier = modifier)
}

/** A small status dot with its label (Online in green, Offline in grey). */
@Composable
fun StatusDot(on: Boolean, label: String, size: TextUnit = 12.sp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (on) Green else palette.muted, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = size, color = if (on) Green else palette.muted)
    }
}

/** A chip over the video: "● AO VIVO", "1/2". */
@Composable
fun VideoChip(text: String, live: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (live) {
            Box(Modifier.size(6.dp).background(Green, CircleShape))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}
