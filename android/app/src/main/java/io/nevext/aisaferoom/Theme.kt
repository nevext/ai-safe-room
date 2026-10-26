package io.nevext.aisaferoom

import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.graphics.Color
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

/** The AI Safe Room logo (someone holding the camera close); the lighter version in dark mode. size is its height. */
@Composable
fun Logo(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(if (palette.dark) R.drawable.logo_dark else R.drawable.logo), contentDescription = "AI Safe Room",
        modifier = modifier.height(size).aspectRatio(700f / 800f))
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

/** A tap that gives: the thing sinks a little while pressed (and the ripple), light and quick. */
fun Modifier.press(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(110), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
}

/** The loading circle, while something is on its way. */
@Composable
fun Loading(modifier: Modifier = Modifier, text: String? = null) {
    Box(modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(28.dp), color = Blue, strokeWidth = 2.5.dp)
            text?.let { Text(it, color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}
