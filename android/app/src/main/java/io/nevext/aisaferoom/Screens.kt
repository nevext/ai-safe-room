package io.nevext.aisaferoom

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// MARK: - Building blocks

/** The page title and its grey line under it ("Câmeras / Seus dispositivos conectados"). */
@Composable
private fun PageTitle(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            subtitle?.let { Text(it, fontSize = 13.sp, color = palette.muted, modifier = Modifier.padding(top = 2.dp)) }
        }
        action?.invoke()
    }
}

/** Back arrow, a title with its line under it, and an optional icon on the right. */
@Composable
private fun TopBar(title: String, subtitle: String? = null, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar", Modifier.clip(CircleShape).clickable(onClick = onBack).padding(8.dp))
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            if (title.isNotEmpty()) Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, fontSize = 12.sp, color = palette.muted) }
        }
        action?.invoke()
    }
}

/** An icon in a soft blue circle. */
@Composable
private fun IconBubble(icon: ImageVector, tint: Color = Blue, bg: Color = palette.soft, size: Int = 40) {
    Box(Modifier.size(size.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.5f).dp))
    }
}

/** A row of a list: icon, title, line under it, and an arrow (or what's given on the right). */
@Composable
private fun ListRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null,
                    trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp, 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = palette.text, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, fontSize = 12.sp, color = palette.muted, modifier = Modifier.padding(top = 2.dp)) }
        }
        trailing?.invoke() ?: if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = palette.muted) else Unit
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        content = content)
}

private val clock = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
/** "Hoje, 14:32", "Ontem, 22:17", "12/10, 09:05". */
private fun whenText(at: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance()
    val days = (today.get(Calendar.YEAR) - day.get(Calendar.YEAR)) * 366 + today.get(Calendar.DAY_OF_YEAR) - day.get(Calendar.DAY_OF_YEAR)
    val prefix = when (days) { 0 -> "Hoje"; 1 -> "Ontem"; else -> SimpleDateFormat("dd/MM", Locale("pt", "BR")).format(Date(at)) }
    return "$prefix, ${clock.format(Date(at))}"
}

// MARK: - The live picture

/**
 * camera-ai's live picture with the AI's boxes over it. view: whole (both lenses), top (lens 1) or bottom (lens 2);
 * the boxes come for the whole frame and are moved to the half shown.
 */
@Composable
private fun LiveVideo(live: Live, view: String, visible: Boolean, modifier: Modifier = Modifier, fit: Boolean = false) {
    val hub = live.hub ?: return
    var frame by remember(view) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(hub, view, visible) {
        if (!visible) return@DisposableEffect onDispose {}
        val video = Mjpeg(hub.videoUrl(view, 1280, 960), onFrame = { jpg ->
            BitmapFactory.decodeByteArray(jpg, 0, jpg.size)?.let { frame = it.asImageBitmap(); error = null }
        }, onError = { error = it })
        video.start()
        onDispose { video.stop() }
    }
    Box(modifier.background(Color(0xFF0B0F17)), contentAlignment = Alignment.Center) {
        val img = frame
        if (img == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                Text(if (error != null) "Reconectando…" else "Abrindo a câmera…", color = Color(0xCCFFFFFF), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            Image(img, "Câmera ao vivo", contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                // Where the picture sits in the box (cropped to fill, or fitted)
                val scale = if (fit) minOf(size.width / img.width, size.height / img.height) else maxOf(size.width / img.width, size.height / img.height)
                val w = img.width * scale
                val h = img.height * scale
                val left = (size.width - w) / 2
                val top = (size.height - h) / 2
                for (d in live.detections) {
                    val y = when (view) { "top" -> d.y * 2; "bottom" -> (d.y - 0.5f) * 2; else -> d.y }
                    val dh = if (view == "whole") d.h else d.h * 2
                    if (y + dh < 0 || y > 1) continue
                    val color = when { d.uncertain -> Red; d.kind == "person" -> Green; else -> Orange }
                    drawRect(color, Offset(left + d.x * w, top + y * h), Size(d.w * w, dh * h), style = Stroke(2.5.dp.toPx()))
                }
            }
        }
    }
}

@Composable
fun FullscreenVideo(live: Live, view: String, visible: Boolean, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LiveVideo(live, view, visible, Modifier.fillMaxSize(), fit = true)
        VideoChip("AO VIVO", live = true, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
        Icon(Icons.Outlined.FullscreenExit, "Sair da tela cheia", tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape).clickable(onClick = onClose).padding(8.dp))
    }
}

// MARK: - Opening

@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(palette.background)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Shield(96.dp)
            Spacer(Modifier.height(28.dp))
            Brand(30.sp)
            Text("Sua casa, sempre protegida.", color = palette.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator(Modifier.width(140.dp).clip(RoundedCornerShape(50)), color = Blue, trackColor = palette.line)
            Text("Conectando ao sistema...", color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

// MARK: - The bottom bar

@Composable
fun Tabs(current: Screen, onTab: (Screen) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { content() }
        Row(Modifier.fillMaxWidth().background(palette.card).navigationBarsPadding().padding(vertical = 6.dp)) {
            listOf(
                Triple(Screen.Home, "Início", Icons.Outlined.Home to Icons.Filled.Home),
                Triple(Screen.Cameras, "Câmeras", Icons.Outlined.Videocam to Icons.Filled.Videocam),
                Triple(Screen.Activity, "Atividades", Icons.Outlined.History to Icons.Filled.History),
                Triple(Screen.Settings, "Ajustes", Icons.Outlined.Settings to Icons.Filled.Settings),
            ).forEach { (screen, label, icons) ->
                val on = screen == current
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onTab(screen) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (on) icons.second else icons.first, label, tint = if (on) Blue else palette.muted, modifier = Modifier.size(24.dp))
                    Text(label, fontSize = 11.sp, color = if (on) Blue else palette.muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

// MARK: - Início

@Composable
fun HomeScreen(live: Live, visible: Boolean, onCamera: () -> Unit, onSystem: () -> Unit, onActivity: () -> Unit, onFullscreen: () -> Unit) {
    Page {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Shield(26.dp)
            Spacer(Modifier.width(10.dp))
            Brand(20.sp, Modifier.weight(1f))
            Icon(Icons.Outlined.Settings, "Sistema", Modifier.clip(CircleShape).clickable(onClick = onSystem).padding(8.dp))
        }
        Box(Modifier.padding(top = 6.dp, bottom = 16.dp)) {
            StatusDot(live.connected, if (live.connected) "Sistema conectado · 1 câmera ativa" else live.problem ?: "Conectando…")
        }
        Box(Modifier.fillMaxWidth().aspectRatio(0.98f).clip(RoundedCornerShape(20.dp)).clickable(onClick = onCamera)) {
            LiveVideo(live, "whole", visible, Modifier.fillMaxSize())
            VideoChip("AO VIVO", live = true, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
            if (live.twoLenses) VideoChip("2 lentes", modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                .padding(14.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(live.cameraName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(live.cameraKind, color = Color(0xCCFFFFFF), fontSize = 12.sp)
                }
                Icon(Icons.Outlined.Fullscreen, "Tela cheia", tint = Color.White,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onFullscreen).padding(4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().card(palette).clickable(onClick = onActivity).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, tint = Blue, modifier = Modifier.size(18.dp))
                Text("Atividade recente", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
            }
            // The newest movement or person in the last 3 hours, or "all calm"
            val recent = (0 until live.activity.length()).mapNotNull { live.activity.optJSONObject(it) }
                .firstOrNull { it.optString("type") in setOf("motion", "person") && System.currentTimeMillis() - it.optLong("at") < 3 * 3600_000 }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (recent == null) IconBubble(Icons.Outlined.Shield) else IconBubble(Icons.AutoMirrored.Outlined.DirectionsWalk,
                    tint = if (recent.optString("type") == "person") Orange else Blue)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(recent?.optString("text") ?: "Tudo tranquilo", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(recent?.let { "${whenText(it.optLong("at"))} · ${it.optString("camera")}" } ?: "Nenhum movimento relevante nas últimas horas.",
                        color = palette.muted, fontSize = 12.sp)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = palette.muted)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// MARK: - Câmeras

@Composable
fun CamerasScreen(live: Live, onCamera: () -> Unit, onAdd: () -> Unit) {
    val hub = live.hub ?: return
    Page {
        PageTitle("Câmeras", "Seus dispositivos conectados") {
            Icon(Icons.Outlined.Add, "Adicionar", Modifier.clip(CircleShape).clickable(onClick = onAdd).padding(8.dp))
        }
        Row(Modifier.fillMaxWidth().card(palette, selected = true).clickable(onClick = onCamera).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val thumb = rememberPicture(hub, hub.snapshotUrl)
            Box(Modifier.size(width = 84.dp, height = 64.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0F17))) {
                thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(live.cameraName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(live.cameraKind, color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                StatusDot(live.connected, if (live.connected) "Online" else "Offline")
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Blue)
        }
        Text("As câmeras entram pelo computador de casa: uma câmera nova ligada a ele aparece aqui.", color = palette.muted,
            fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
    }
}

// MARK: - A câmera

private val ICONS = mapOf("night" to Icons.Outlined.DarkMode, "light" to Icons.Outlined.Lightbulb, "follow" to Icons.AutoMirrored.Outlined.DirectionsWalk)

@Composable
fun CameraScreen(live: Live, visible: Boolean, onBack: () -> Unit, onFeatures: () -> Unit, onFullscreen: (String) -> Unit) {
    val hub = live.hub ?: return
    var view by remember { mutableStateOf("whole") }
    val scope = rememberCoroutineScope()
    Page {
        TopBar(live.cameraName, live.cameraKind, onBack) {
            Icon(Icons.Outlined.Settings, "O que a câmera faz", Modifier.clip(CircleShape).clickable(onClick = onFeatures).padding(8.dp))
        }
        Box(Modifier.fillMaxWidth().aspectRatio(if (view == "whole") 1.05f else 2.2f).clip(RoundedCornerShape(20.dp))) {
            LiveVideo(live, view, visible, Modifier.fillMaxSize())
            VideoChip("AO VIVO", live = true, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
            if (view != "whole") VideoChip(if (view == "top") "1/2" else "2/2", modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
            Text(live.cameraName, color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
            if (live.moving) VideoChip("Movimento", modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp))
        }
        // Which lens: both, the fixed one (1/2) or the one that turns (2/2)
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.background(palette.card, RoundedCornerShape(50)).padding(3.dp)) {
                val options = if (live.twoLenses) listOf("whole" to "Ambas", "top" to "1/2", "bottom" to "2/2") else listOf("whole" to "Inteira")
                options.forEach { (v, label) ->
                    val on = v == view
                    Text(label, color = if (on) Color.White else palette.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Blue else Color.Transparent)
                            .clickable { view = v }.padding(horizontal = 18.dp, vertical = 7.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.Fullscreen, "Tela cheia", Modifier.clip(CircleShape).clickable { onFullscreen(view) }.padding(8.dp))
        }
        // The camera's switches (night vision, white light, follow), as cards side by side
        val toggles = (0 until live.controls.length()).mapNotNull { live.controls.optJSONObject(it) }.filter { it.optString("type") == "toggle" }
        val flip = { c: JSONObject, want: Boolean ->
            val id = c.optString("id")
            c.put("value", want)
            live.controls = org.json.JSONArray(live.controls.toString())
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { hub.control(id, want) } }
                    .onSuccess { live.controls = it }
                    .onFailure { live.problem = "Não deu para mudar: ${it.message}" }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            toggles.forEach { c ->
                val on = c.optBoolean("value")
                val id = c.optString("id")
                Column(Modifier.weight(1f).card(palette).clickable { flip(c, !on) }.padding(12.dp)) {
                    IconBubble(ICONS[id] ?: Icons.Outlined.Settings, size = 36)
                    Text(c.optJSONObject("label")?.optString("pt") ?: id, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp), maxLines = 2)
                    Text(if (on) (if (id == "follow") "Ativado" else "Ativada") else (if (id == "follow") "Desativado" else "Desativada"),
                        fontSize = 11.sp, color = if (on) Blue else palette.muted)
                    Switch(on, onCheckedChange = { flip(c, it) }, colors = SwitchDefaults.colors(checkedTrackColor = Blue), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().card(palette).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(if (live.connected) Icons.Outlined.Shield else Icons.Outlined.VideocamOff)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(if (live.connected) "Câmera ativa" else "Câmera offline", color = if (live.connected) Blue else palette.muted,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(if (live.connected) "Transmitindo em tempo real" else "O computador não acha a câmera agora", color = palette.muted, fontSize = 12.sp)
            }
            if (live.connected) Icon(Icons.Outlined.GraphicEq, null, tint = Blue)
        }
        Spacer(Modifier.height(20.dp))
    }
}

// MARK: - Atividades

private val FILTERS = listOf(
    "Todas" to emptySet(),
    "Movimento" to setOf("motion", "person"),
    "Sistema" to setOf("night", "light"),
    "Câmera" to setOf("online", "offline"),
)

@Composable
fun ActivityScreen(live: Live) {
    val hub = live.hub ?: return
    var filter by remember { mutableStateOf(0) }
    var opened by remember { mutableStateOf<JSONObject?>(null) }
    val all = (0 until live.activity.length()).mapNotNull { live.activity.optJSONObject(it) }
    val shown = FILTERS[filter].second.let { types -> if (types.isEmpty()) all else all.filter { it.optString("type") in types } }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        item {
            PageTitle("Atividades", "Histórico de eventos e detecções")
            Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FILTERS.forEachIndexed { i, (label, _) ->
                    val on = i == filter
                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else palette.muted,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Blue else palette.card)
                            .clickable { filter = i }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
        }
        if (shown.isEmpty()) item {
            Text(if (all.isEmpty()) "Nada por aqui ainda. Movimentos, pessoas e mudanças da câmera aparecem aqui." else "Nada deste tipo ainda.",
                color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp))
        }
        items(shown) { a -> ActivityRow(hub, a) { opened = a } }
        item { Spacer(Modifier.height(20.dp)) }
    }
    // The activity big: its picture (if it has one), what and when
    opened?.let { a ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { opened = null }) {
            Column(Modifier.fillMaxWidth().card(palette, 20.dp).padding(14.dp)) {
                a.optString("thumb").takeIf { it.isNotEmpty() && it != "null" }?.let { name ->
                    val pic = rememberPicture(hub, hub.thumbUrl(name))
                    Box(Modifier.fillMaxWidth().aspectRatio(0.9f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0B0F17))) {
                        pic?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
                Text(a.optString("text"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
                Text("${whenText(a.optLong("at"))} · ${a.optString("camera")}", color = palette.muted, fontSize = 13.sp)
                TextButton(onClick = { opened = null }, modifier = Modifier.align(Alignment.End)) { Text("Fechar", color = Blue) }
            }
        }
    }
}

@Composable
private fun ActivityRow(hub: Hub, a: JSONObject, onOpen: () -> Unit) {
    val type = a.optString("type")
    val (icon, tint, bg) = when (type) {
        "motion" -> Triple(Icons.AutoMirrored.Outlined.DirectionsWalk, Blue, palette.soft)
        "person" -> Triple(Icons.AutoMirrored.Outlined.DirectionsWalk, Orange, Color(0x22F97316))
        "online" -> Triple(Icons.Outlined.Videocam, Green, Color(0x2222C55E))
        "offline" -> Triple(Icons.Outlined.VideocamOff, palette.muted, palette.line)
        "night" -> Triple(Icons.Outlined.DarkMode, Blue, palette.soft)
        "light" -> Triple(Icons.Outlined.Lightbulb, Blue, palette.soft)
        else -> Triple(Icons.Outlined.Shield, Blue, palette.soft)
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, tint, bg, size = 42)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(a.optString("text"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${whenText(a.optLong("at"))} · ${a.optString("camera")}", fontSize = 12.sp, color = palette.muted)
        }
        a.optString("thumb").takeIf { it.isNotEmpty() && it != "null" }?.let { name ->
            val thumb = rememberPicture(hub, hub.thumbUrl(name))
            Box(Modifier.size(width = 64.dp, height = 42.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B0F17))) {
                thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}

// MARK: - Ajustes

@Composable
fun SettingsScreen(onOpen: (Screen) -> Unit) {
    Page {
        PageTitle("Ajustes", "Personalize e gerencie seu sistema")
        Column(Modifier.fillMaxWidth().card(palette)) {
            ListRow(Icons.Outlined.Videocam, "Câmeras", "Nome, recursos e vistoria") { onOpen(Screen.Features) }
            ListRow(Icons.Outlined.Notifications, "Notificações", "Alertas e avisos do sistema") { onOpen(Screen.Soon("Notificações")) }
            ListRow(Icons.Outlined.Inventory2, "Armazenamento", "Gravações e espaço em nuvem") { onOpen(Screen.Soon("Armazenamento")) }
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().card(palette)) {
            ListRow(Icons.Outlined.Palette, "Aparência", "Tema claro/escuro") { onOpen(Screen.Appearance) }
            ListRow(Icons.Outlined.Lock, "Privacidade", "Dados e permissões") { onOpen(Screen.Privacy) }
            ListRow(Icons.Outlined.Wifi, "Conexão", "Status da rede e dispositivos") { onOpen(Screen.System) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun AppearanceScreen(theme: String, setTheme: (String) -> Unit, onBack: () -> Unit) {
    Page {
        TopBar("", null, onBack)
        PageTitle("Aparência", "Tema claro ou escuro")
        Column(Modifier.fillMaxWidth().card(palette)) {
            listOf("system" to "Igual ao celular", "light" to "Claro", "dark" to "Escuro").forEach { (v, label) ->
                ListRow(Icons.Outlined.Palette, label, onClick = { setTheme(v) }) {
                    Box(Modifier.size(20.dp).background(if (theme == v) Blue else Color.Transparent, CircleShape)
                        .then(if (theme == v) Modifier else Modifier.background(palette.line, CircleShape)))
                }
            }
        }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Page {
        TopBar("", null, onBack)
        PageTitle("Privacidade", "Dados e permissões")
        Column(Modifier.fillMaxWidth().card(palette).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tudo fica na sua casa.", fontWeight = FontWeight.SemiBold)
            Text("O vídeo vai da câmera para o computador de casa e dele para este celular, pelo seu Wi-Fi. Nada vai para a internet.",
                color = palette.muted, fontSize = 14.sp)
            Text("As miniaturas das atividades ficam 7 dias no computador e depois são apagadas.", color = palette.muted, fontSize = 14.sp)
            Text("Só entram aparelhos que alguém já de dentro permitiu, ou que sabem a senha.", color = palette.muted, fontSize = 14.sp)
        }
    }
}

@Composable
fun SoonScreen(title: String, onBack: () -> Unit) {
    Page {
        TopBar("", null, onBack)
        PageTitle(title)
        Column(Modifier.fillMaxWidth().card(palette).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBubble(Icons.Outlined.Shield, size = 52)
            Text("Em breve", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
            Text(if (title == "Notificações") "Avisos no celular quando aparecer alguém, mesmo fora de casa."
                else "Guardar as gravações na nuvem (Google Drive), por 30 dias.",
                color = palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// MARK: - Sistema

@Composable
fun SystemScreen(live: Live, onBack: () -> Unit, onLeave: () -> Unit) {
    Page {
        TopBar("", null, onBack)
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Shield(64.dp)
            Brand(24.sp, Modifier.padding(top = 16.dp))
            Text("Segurança e tranquilidade,\ncom inteligência.", color = palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp))
        }
        Column(Modifier.fillMaxWidth().card(palette)) {
            Column(Modifier.padding(16.dp)) {
                Text("Status do sistema", fontSize = 13.sp, color = palette.muted)
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(if (live.connected) Green else palette.muted, CircleShape))
                    Text(if (live.connected) "Online" else "Offline", fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                        color = if (live.connected) Green else palette.muted, modifier = Modifier.padding(start = 8.dp))
                }
                Text(if (live.connected) "1 câmera ativa" else live.problem ?: "Sem câmera agora", fontSize = 12.sp, color = palette.muted,
                    modifier = Modifier.padding(start = 18.dp, top = 2.dp))
            }
            ListRow(Icons.Outlined.Devices, "Dispositivos conectados", trailing = { Text(if (live.connected) "1" else "0", color = palette.muted, fontSize = 13.sp) })
            val last = live.activity.optJSONObject(0)
            ListRow(Icons.Outlined.History, "Última atividade", trailing = {
                Text(last?.let { whenText(it.optLong("at")) } ?: "—", color = palette.muted, fontSize = 13.sp)
            })
            ListRow(Icons.Outlined.Wifi, "Computador", trailing = {
                Text(live.hub?.root?.removePrefix("http://") ?: "—", color = palette.muted, fontSize = 13.sp)
            })
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().card(palette).clickable(onClick = onLeave).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.Logout, null)
            Text("Sair", fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp))
        }
        Spacer(Modifier.height(20.dp))
    }
}

// MARK: - O que a câmera faz (perfil) e o nome dela

private val STATES = mapOf(
    "ok" to "Funciona", "no" to "Não tem", "declared" to "A câmera diz que tem",
    "unverified" to "Ainda não deu para conferir", "model" to "Outra câmera igual tem",
)

@Composable
fun FeaturesScreen(live: Live, onBack: () -> Unit) {
    val hub = live.hub ?: return
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var checking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf(live.cameraName) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) { profile = withContext(Dispatchers.IO) { runCatching { hub.profile() }.getOrNull() } }
    val caps = profile?.optJSONArray("caps")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        .sortedBy { if (it.optString("status") == "no") 1 else 0 }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TopBar(live.cameraName, live.cameraKind, onBack)
            Text("Nome da câmera", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue))
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { hub.rename(name.trim()); hub.screen() } }
                            .onSuccess { live.info = it; note = "Nome salvo." }.onFailure { note = "Não deu: ${it.message}" }
                    }
                }) { Text("Salvar", color = Blue) }
            }
            PageTitle("O que a câmera faz", "${caps.count { it.optString("status") == "ok" }} recursos conferidos")
            Button(enabled = !checking, onClick = {
                checking = true
                note = "Conferindo… a luz pode acender e a lente girar (até 1 minuto)."
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { hub.checkAll() } }
                    checking = false
                    note = r.fold({ j ->
                        val res = j.optJSONArray("results")
                        if (!j.optBoolean("ok")) j.optString("error")
                        else if (res == null || res.length() == 0) "Nada novo para conferir."
                        else "Pronto: ${(0 until res.length()).count { res.optJSONObject(it)?.optString("status") == "ok" }} de ${res.length()} conferidos."
                    }, { "Não deu: ${it.message}" })
                    reload++
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Conferir o que falta") }
            note?.let { Text(it, color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
        items(caps) { c ->
            val status = c.optString("status")
            Column(Modifier.fillMaxWidth().card(palette, 14.dp).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.optJSONObject("label")?.optString("pt") ?: c.optString("id"), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f), color = if (status == "no") palette.muted else palette.text)
                    Text(STATES[status] ?: status, fontSize = 11.sp, color = if (status == "ok") Green else palette.muted,
                        modifier = Modifier.background(if (status == "ok") Color(0x2222C55E) else palette.line, RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 2.dp))
                }
                c.optString("desc").takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                val use = if (c.isNull("use")) null else c.optString("use")
                Text(if (use != null) "Onde usar: $use" else "Ainda não dá para usar.", color = palette.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// MARK: - Achar o computador e entrar

@Composable
fun FindScreen(onFound: (root: String, inside: Boolean) -> Unit, onBack: (() -> Unit)? = null) {
    var searching by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val tryRoot = { root: String ->
        scope.launch {
            error = null
            withContext(Dispatchers.IO) { runCatching { Hub(root, null).screen() } }
                .onSuccess { onFound(root, true) }
                .onFailure { if (it is Locked) onFound(root, false) else error = "Não achei o sistema em ${root.removePrefix("http://")}" }
        }
    }
    LaunchedEffect(searching) {
        if (!searching) return@LaunchedEffect
        found = withContext(Dispatchers.IO) { runCatching { Hub.discover() }.getOrDefault(emptyList()) }
        searching = false
        if (found.size == 1) tryRoot(found[0].root)
    }
    Page {
        onBack?.let { TopBar("", null, it) }
        Column(Modifier.fillMaxWidth().padding(top = if (onBack == null) 48.dp else 8.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Shield(72.dp)
            Brand(26.sp, Modifier.padding(top = 18.dp))
            Text("Conecte ao computador de casa que cuida das câmeras. O celular precisa estar no mesmo Wi-Fi.",
                color = palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
        if (searching) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Blue, strokeWidth = 2.dp)
                Text("Procurando na rede…", modifier = Modifier.padding(start = 12.dp))
            }
        } else {
            found.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).card(palette).clickable { tryRoot(f.root) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.Outlined.Devices)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Computador de casa", fontWeight = FontWeight.SemiBold)
                        Text(f.root.removePrefix("http://"), color = palette.muted, fontSize = 12.sp)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = palette.muted)
                }
            }
            if (found.isEmpty()) Text("Não achei sozinho. Digite o endereço que aparece no computador.", color = palette.muted, fontSize = 13.sp)
            TextButton(onClick = { searching = true }) { Text("Procurar de novo", color = Blue) }
        }
        OutlinedTextField(address, { address = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("Endereço (ex.: 192.168.0.34:8787)") }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue))
        Button(onClick = {
            val a = address.trim().removePrefix("http://").trimEnd('/')
            if (a.isNotEmpty()) tryRoot("http://" + if (':' in a) a else "$a:8787")
        }, colors = ButtonDefaults.buttonColors(containerColor = Blue), modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp)) {
            Text("Conectar")
        }
        error?.let { Text(it, color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
    }
}

@Composable
fun LetInScreen(root: String, onKey: (String) -> Unit, onBack: () -> Unit) {
    var status by remember { mutableStateOf("Esperando a permissão…") }
    var password by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler(onBack = onBack)
    LaunchedEffect(attempt) {
        withContext(Dispatchers.IO) {
            runCatching {
                val id = Hub.requestAccess(root, "Celular " + Build.MODEL)
                var key: String? = null
                repeat(300) {
                    if (key != null) return@repeat
                    key = Hub.accessKey(root, id)
                    if (key == null) Thread.sleep(2000)
                }
                key
            }
        }.onSuccess { key -> if (key != null) onKey(key) else status = "O pedido expirou." }
            .onFailure { status = it.message ?: "não deu" }
    }
    Page {
        TopBar("", null, onBack)
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Shield(64.dp)
            Text("Liberar este celular", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Text("No computador (${root.removePrefix("http://")}), toque em Permitir.", color = palette.muted, fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.fillMaxWidth().card(palette).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Blue, strokeWidth = 2.dp)
            Text(status, modifier = Modifier.weight(1f).padding(start = 12.dp), fontSize = 14.sp)
            TextButton(onClick = { status = "Esperando a permissão…"; attempt++ }) { Text("De novo", color = Blue) }
        }
        Text("Ou com a senha", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp))
        OutlinedTextField(password, { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("Senha") }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue))
        Button(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { Hub.login(root, password) } }
                    .onSuccess(onKey).onFailure { status = it.message ?: "não deu" }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = Blue), modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp)) {
            Text("Entrar")
        }
    }
}
