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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MonitorHeart
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
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.offset
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
internal fun PageTitle(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
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
internal fun TopBar(title: String, subtitle: String? = null, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
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
internal fun IconBubble(icon: ImageVector, tint: Color = Blue, bg: Color = palette.soft, size: Int = 40) {
    Box(Modifier.size(size.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.5f).dp))
    }
}

/** A row of a list: icon, title, line under it, and an arrow (or what's given on the right). */
@Composable
internal fun ListRow(icon: ImageVector, title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null,
                    onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.press(onClick = onClick) else Modifier).padding(16.dp, 14.dp),
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
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        content = content)
}

internal val clock = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
/** "Hoje, 14:32", "Ontem, 22:17", "12/10, 09:05". */
internal fun whenText(at: Long): String {
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
    // Direct from the camera (no AI), unless away from home or the camera won't open direct: then via the computer
    var directFailed by remember(live.hub) { mutableStateOf(false) }
    if (!live.aiOn && !live.away && !directFailed) return DirectVideo(live, view, visible, modifier, fit, onGiveUp = { directFailed = true })
    AiVideo(live, view, visible, modifier, fit)
}

/** Through camera-ai (with the AI on): the video with what the AI sees drawn on it. */
@Composable
private fun AiVideo(live: Live, view: String, visible: Boolean, modifier: Modifier, fit: Boolean) {
    val hub = live.hub ?: return
    RoomSound(hub, visible && live.listen)
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
            // The camera's still while the video opens, so it isn't a black box
            rememberPicture(hub, hub.thumbUrl)?.let {
                Image(it, null, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.6f)
            }
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
    val hub = live.hub ?: return
    val scope = rememberCoroutineScope()
    var lens by remember { mutableStateOf(view) }
    var panels by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val flip = { c: JSONObject ->
        val id = c.optString("id"); val want = !c.optBoolean("value")
        c.put("value", want); live.controls = org.json.JSONArray(live.controls.toString())
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { hub.control(id, want) } }
                .onSuccess { live.controls = it }.onFailure { note = "Não deu: ${it.message}" }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(indication = null,
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { panels = !panels }) {
        LiveVideo(live, lens, visible, Modifier.fillMaxSize(), fit = true)
        androidx.compose.animation.AnimatedVisibility(panels, Modifier.align(Alignment.CenterStart),
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            // Left: what matters at a glance
            Column(Modifier.fillMaxHeight().width(220.dp)
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(20.dp)) {
                VideoChip("AO VIVO", live = true)
                Text(live.cameraName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
                Text(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale("pt", "BR")).format(java.util.Date(now)), color = Color(0xCCFFFFFF), fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                FullInfo(if (live.moving) "Movimento agora" else "Sem movimento", if (live.moving) Orange else Color(0x99FFFFFF))
                FullInfo(if (live.aiOn) "IA ligada" else "Câmera normal (sem IA)", Color(0x99FFFFFF))
                if (live.protectionOn) FullInfo("Proteção ativada", Blue)
                if (live.privacyOn) FullInfo("Privacidade: câmera coberta", Orange)
                live.activity.optJSONObject(0)?.let { a ->
                    Text("Última: ${a.optString("text")}", color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 10.dp))
                    Text(whenText(a.optLong("at")), color = Color(0x99FFFFFF), fontSize = 11.sp)
                }
                note?.let { Text(it, color = Orange, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
        androidx.compose.animation.AnimatedVisibility(panels, Modifier.align(Alignment.CenterEnd),
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            // Right: the quick things, one tap each
            Column(Modifier.fillMaxHeight().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                FullButton(Icons.Outlined.FullscreenExit, "Sair", false, onClose)
                ListenButton(live, Modifier.size(48.dp))
                TalkButton(live, onNote = { note = it }, modifier = Modifier.size(48.dp))
                (0 until live.controls.length()).mapNotNull { live.controls.optJSONObject(it) }.filter { it.optString("type") == "toggle" }.forEach { c ->
                    FullButton(ICONS[c.optString("id")] ?: Icons.Outlined.Settings, c.optJSONObject("label")?.optString("pt") ?: "", c.optBoolean("value")) { flip(c) }
                }
                if (live.twoLenses) FullButton(Icons.Outlined.Videocam, when (lens) { "top" -> "1/2"; "bottom" -> "2/2"; else -> "Ambas" }, lens != "whole") {
                    lens = when (lens) { "whole" -> "top"; "top" -> "bottom"; else -> "whole" }
                }
            }
        }
    }
}

@Composable
private fun FullInfo(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(text, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun FullButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(if (on) Blue else Color(0x99000000)).press(onClick = onClick),
            contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White) }
        if (label.isNotEmpty()) Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp).width(64.dp),
            textAlign = TextAlign.Center)
    }
}

// MARK: - Opening

@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(palette.background)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(110.dp)
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
fun Tabs(current: Screen, unseen: Int, onTab: (Screen) -> Unit, content: @Composable () -> Unit) {
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
                    Box {
                        Icon(if (on) icons.second else icons.first, label, tint = if (on) Blue else palette.muted, modifier = Modifier.size(24.dp))
                        if (screen == Screen.Activity && unseen > 0) Text(if (unseen > 9) "9+" else "$unseen", color = Color.White, fontSize = 9.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp)
                                .background(Orange, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                    Text(label, fontSize = 11.sp, color = if (on) Blue else palette.muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

// MARK: - Início

@Composable
fun HomeScreen(live: Live, visible: Boolean, onCamera: () -> Unit, onSystem: () -> Unit, onActivity: () -> Unit, onFullscreen: () -> Unit,
               onProtection: () -> Unit, onAi: () -> Unit) {
    val hub = live.hub ?: return
    val scope = rememberCoroutineScope()
    var askAi by remember { mutableStateOf(false) }
    if (askAi) androidx.compose.material3.AlertDialog(onDismissRequest = { askAi = false },
        title = { Text("A Proteção usa a IA") },
        text = { Text("Para avisar quando aparecer alguém, alguém precisa saber que é uma pessoa: a IA, ou a detecção de pessoa da própria câmera (Câmeras → a câmera → A própria câmera). Ligue uma das duas.") },
        confirmButton = { Button(onClick = { askAi = false; onAi() }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Ver a IA") } },
        dismissButton = { TextButton(onClick = { askAi = false }) { Text("Agora não", color = palette.muted) } })
    Page {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(29.dp)
            Spacer(Modifier.width(10.dp))
            Brand(20.sp, Modifier.weight(1f))
            Icon(Icons.Outlined.Settings, "Sistema", Modifier.clip(CircleShape).clickable(onClick = onSystem).padding(8.dp))
        }
        Box(Modifier.padding(top = 6.dp, bottom = 14.dp)) {
            StatusDot(live.connected, if (live.connected) "Sistema conectado · 1 câmera ativa" else live.problem ?: "Conectando…")
        }
        // Shortcut: how the connection is, and a tap goes to Ajustes → Conexão (reconnect there)
        ConnectionShortcut(hub, visible, onSystem)

        // The state of the house first: someone showed up, the camera isn't looking, or all calm
        val recent = (0 until live.activity.length()).mapNotNull { live.activity.optJSONObject(it) }
            .firstOrNull { it.optString("type") in setOf("person", "animal") && System.currentTimeMillis() - it.optLong("at") < 30 * 60_000 }
        when {
            live.privacyOn -> AlertCard(Icons.Outlined.VisibilityOff, palette.muted, palette.line, "A câmera não está olhando",
                "Modo privacidade ligado: a imagem fica coberta e nada é guardado.")
            recent != null -> AlertCard(look(recent.optString("type")).icon, look(recent.optString("type")).tint, look(recent.optString("type")).bg, recent.optString("text"),
                "${whenText(recent.optLong("at"))} · toque para ver", onClick = onActivity,
                thumb = recent.optString("thumb").takeIf { it.isNotEmpty() && it != "null" }?.let { hub.thumbUrl(it) }, hub = hub)
            else -> AlertCard(Icons.Outlined.Shield, Green, Color(0x2222C55E), "Tudo tranquilo",
                if (live.protectionOn) "Proteção ativada: avisamos se aparecer alguém." else "Ninguém apareceu na última meia hora.", onClick = onActivity)
        }
        Spacer(Modifier.height(14.dp))

        Box(Modifier.fillMaxWidth().aspectRatio(1.05f).clip(RoundedCornerShape(20.dp)).clickable(onClick = onCamera)) {
            LiveVideo(live, "whole", visible, Modifier.fillMaxSize())
            VideoChip("AO VIVO", live = true, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                .padding(14.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(live.cameraName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(if (live.moving) "Movimento agora" else if (live.connected) "Online" else "Offline", color = Color(0xCCFFFFFF), fontSize = 12.sp)
                }
                Icon(Icons.Outlined.Fullscreen, "Tela cheia", tint = Color.White,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onFullscreen).padding(4.dp))
            }
        }
        Spacer(Modifier.height(14.dp))

        // Away mode and privacy, one tap each
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeCard(Icons.Outlined.Shield, "Proteção", if (live.protectionOn) "Ativada" else "Desativada", live.protectionOn,
                Modifier.weight(1f), onLongClick = onProtection) { on ->
                if (on && !live.aiOn && !live.cameraPerson) { askAi = true; return@ModeCard }
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { hub.setProtection(on); hub.screen() } }
                        .onSuccess { live.info = it }.onFailure { live.problem = "Não deu: ${it.message}" }
                }
            }
            ModeCard(Icons.Outlined.VisibilityOff, "Privacidade", if (live.privacyOn) "Câmera coberta" else "Desligada", live.privacyOn,
                Modifier.weight(1f)) { on ->
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { hub.setPrivacy(on); hub.screen() } }
                        .onSuccess { live.info = it }.onFailure { live.problem = "Não deu: ${it.message}" }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** The big card at the top of Início: what's going on in the house. */
@Composable
private fun AlertCard(icon: ImageVector, tint: Color, bg: Color, title: String, text: String, onClick: (() -> Unit)? = null,
                      thumb: String? = null, hub: Hub? = null) {
    Row(Modifier.fillMaxWidth().card(palette).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, tint, bg, size = 46)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text, color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (thumb != null && hub != null) {
            val pic = rememberPicture(hub, thumb)
            Box(Modifier.size(width = 70.dp, height = 50.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0F17))) {
                pic?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        } else if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = palette.muted)
    }
}

/** A mode with its switch (Proteção, Privacidade); a long press opens its settings. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ModeCard(icon: ImageVector, title: String, state: String, on: Boolean, modifier: Modifier, onLongClick: (() -> Unit)? = null,
                     onChange: (Boolean) -> Unit) {
    Column(modifier.card(palette, selected = on).combinedClickable(onClick = { onChange(!on) }, onLongClick = onLongClick).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, if (on) Blue else palette.muted, if (on) palette.soft else palette.line, size = 36)
            Spacer(Modifier.weight(1f))
            Switch(on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Blue))
        }
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Text(state, fontSize = 12.sp, color = if (on) Blue else palette.muted)
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
            val thumb = rememberPicture(hub, hub.thumbUrl)
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

private val ICONS = mapOf("night" to Icons.Outlined.DarkMode, "light" to Icons.Outlined.Lightbulb, "follow" to Icons.AutoMirrored.Outlined.DirectionsWalk,
    "camTrack" to Icons.AutoMirrored.Outlined.DirectionsWalk)

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
            if (live.moving) VideoChip("Movimento", modifier = Modifier.align(Alignment.TopEnd).padding(start = 0.dp, top = if (view != "whole") 52.dp else 12.dp, end = 12.dp))
            Row(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ListenButton(live)
                TalkButton(live, onNote = { live.problem = it })
            }
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
                Column(Modifier.weight(1f).card(palette).press { flip(c, !on) }.padding(12.dp)) {
                    IconBubble(ICONS[id] ?: Icons.Outlined.Settings, size = 36)
                    Text(c.optJSONObject("label")?.optString("pt") ?: id, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp), maxLines = 2)
                    Text(if (on) (if (id == "follow") "Ativado" else "Ativada") else (if (id == "follow") "Desativado" else "Desativada"),
                        fontSize = 11.sp, color = if (on) Blue else palette.muted)
                    Switch(on, onCheckedChange = { flip(c, it) }, colors = SwitchDefaults.colors(checkedTrackColor = Blue), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (live.hasPtz) {
            Spacer(Modifier.height(14.dp))
            PtzPad(hub, onNote = { live.problem = it })
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
    "Pessoas" to setOf("person", "animal"),
    "Tudo" to emptySet(),
    "Movimento" to setOf("motion"),
    "Câmera" to setOf("online", "offline", "night", "light", "quality", "protection", "privacy"),
)

private fun dayOf(at: Long): Calendar = Calendar.getInstance().apply {
    timeInMillis = at; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}

/** "Hoje", "Ontem", "Sexta, 24/10". */
private fun dayTitle(at: Long): String {
    val days = ((dayOf(System.currentTimeMillis()).timeInMillis - dayOf(at).timeInMillis) / 86_400_000L).toInt()
    return when (days) {
        0 -> "Hoje"
        1 -> "Ontem"
        else -> SimpleDateFormat("EEEE, dd/MM", Locale("pt", "BR")).format(Date(at)).replaceFirstChar { it.uppercase() }
    }
}

@Composable
fun ActivityScreen(live: Live, onLive: () -> Unit, onSummaries: () -> Unit) {
    val hub = live.hub ?: return
    var filter by remember { mutableStateOf(0) }
    // What's open is looked up again in the list: when the visit changes (a name, "Quem é?"), the detail follows
    var openedKey by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val all = (0 until live.activity.length()).mapNotNull { live.activity.optJSONObject(it) }
    val opened = openedKey?.let { (at, type) -> all.find { it.optLong("at") == at && it.optString("type") == type } }
    val shown = FILTERS[filter].second.let { types -> if (types.isEmpty()) all else all.filter { it.optString("type") in types } }
    val today = all.filter { it.optLong("at") >= dayOf(System.currentTimeMillis()).timeInMillis }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        item {
            PageTitle("Atividades", "Histórico de eventos e detecções")
            DaySummary(today, onSummaries)
            Row(Modifier.padding(top = 14.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FILTERS.forEachIndexed { i, (label, _) ->
                    val on = i == filter
                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else palette.muted,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Blue else palette.card)
                            .clickable { filter = i }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
        }
        if (shown.isEmpty()) item {
            Text(when {
                all.isEmpty() -> "Nada por aqui ainda. Pessoas, animais, movimentos e mudanças da câmera aparecem aqui."
                filter == 0 -> "Ninguém apareceu. Em \"Tudo\" ficam os movimentos e as mudanças da câmera."
                else -> "Nada deste tipo ainda."
            }, color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp))
        }
        // By day, newest first
        shown.groupBy { dayOf(it.optLong("at")).timeInMillis }.forEach { (day, items) ->
            item(key = "d$day") {
                Text(dayTitle(day), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = palette.muted,
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
            }
            items(items, key = { "a" + it.optLong("at") + it.optString("type") }) { a ->
                ActivityRow(hub, a, isNew = a.optLong("at") > live.seenAt && a.optString("type") in setOf("person", "animal")) { openedKey = a.optLong("at") to a.optString("type") }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
    val scope = rememberCoroutineScope()
    opened?.let { a ->
        ActivityDetail(hub, a, onClose = { openedKey = null }, onLive = { openedKey = null; onLive() }, onChanged = { item ->
            live.activity = org.json.JSONArray().also { list ->
                for (i in 0 until live.activity.length()) live.activity.optJSONObject(i)
                    ?.let { list.put(if (it.optLong("at") == item.optLong("at") && it.optString("type") == item.optString("type")) item else it) }
            }
        }, onMistake = {
            openedKey = null
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { hub.forget(a.optLong("at"), a.optString("type")) } }.onSuccess {
                    live.activity = org.json.JSONArray().also { list ->
                        for (i in 0 until live.activity.length()) live.activity.optJSONObject(i)
                            ?.takeUnless { it.optLong("at") == a.optLong("at") && it.optString("type") == a.optString("type") }?.let(list::put)
                    }
                }
            }
        })
    }
}

/** Today in one line and a strip of the day (0h to 24h) with a dot where something happened. */
@Composable
private fun DaySummary(today: List<JSONObject>, onOpen: () -> Unit) {
    // Pessoas: quantas apareceram juntas no máximo (a IA não sabe quem é quem, mas sabe contar quantas vê ao mesmo tempo)
    val visits = today.filter { it.optString("type") == "person" }
    val people = visits.maxOfOrNull { it.optInt("people", 1) } ?: 0
    val animals = today.count { it.optString("type") == "animal" }
    val moves = today.filter { it.optString("type") == "motion" }.sumOf { it.optInt("count", 1) }
    Column(Modifier.fillMaxWidth().card(palette).clickable(onClick = onOpen).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hoje", fontSize = 12.sp, color = palette.muted, modifier = Modifier.weight(1f))
            Text("Outros dias ›", fontSize = 12.sp, color = Blue, fontWeight = FontWeight.SemiBold)
        }
        Text(listOfNotNull(
            if (people == 0) "Ninguém" else "$people ${if (people == 1) "pessoa" else "pessoas"}",
            animals.takeIf { it > 0 }?.let { "$it ${if (it == 1) "animal" else "animais"}" },
            "$moves ${if (moves == 1) "movimento" else "movimentos"}").joinToString(" · "), fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            modifier = Modifier.padding(top = 2.dp))
        if (people > 0) Text("${visits.size} ${if (visits.size == 1) "visita" else "visitas"} · no máximo $people ao mesmo tempo",
            fontSize = 12.sp, color = palette.muted)
        val start = dayOf(System.currentTimeMillis()).timeInMillis
        val line = palette.line
        Canvas(Modifier.fillMaxWidth().height(26.dp).padding(top = 10.dp)) {
            drawRoundRect(line, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f), size = Size(size.width, 8.dp.toPx()),
                topLeft = Offset(0f, (size.height - 8.dp.toPx()) / 2))
            val nowX = ((System.currentTimeMillis() - start) / 86_400_000f) * size.width
            drawLine(Blue.copy(alpha = 0.35f), Offset(nowX, 0f), Offset(nowX, size.height), strokeWidth = 2.dp.toPx())
            for (a in today) {
                val x = ((a.optLong("at") - start) / 86_400_000f) * size.width
                val color = when (a.optString("type")) { "person" -> Orange; "animal" -> Color(0xFFA855F7); "motion" -> Blue; else -> null } ?: continue
                drawCircle(color, radius = if (a.optString("type") == "motion") 3.dp.toPx() else 5.dp.toPx(), center = Offset(x, size.height / 2))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("0h", "6h", "12h", "18h", "24h").forEachIndexed { i, t ->
                Text(t, fontSize = 10.sp, color = palette.muted, modifier = Modifier.weight(1f),
                    textAlign = if (i == 0) TextAlign.Start else if (i == 4) TextAlign.End else TextAlign.Center)
            }
        }
    }
}

private data class Look(val icon: ImageVector, val tint: Color, val bg: Color)

@Composable
private fun look(type: String): Look = when (type) {
    "person" -> Look(Icons.AutoMirrored.Outlined.DirectionsWalk, Orange, Color(0x22F97316))
    "animal" -> Look(Icons.Outlined.Pets, Color(0xFFA855F7), Color(0x22A855F7))
    "motion" -> Look(Icons.Outlined.Waves, Blue, palette.soft)
    "online" -> Look(Icons.Outlined.Videocam, Green, Color(0x2222C55E))
    "offline" -> Look(Icons.Outlined.VideocamOff, palette.muted, palette.line)
    "night" -> Look(Icons.Outlined.DarkMode, Blue, palette.soft)
    "light" -> Look(Icons.Outlined.Lightbulb, Blue, palette.soft)
    "quality" -> Look(Icons.Outlined.Wifi, Orange, Color(0x22F97316))
    "privacy" -> Look(Icons.Outlined.VisibilityOff, palette.muted, palette.line)
    else -> Look(Icons.Outlined.Shield, Blue, palette.soft)
}

/** "Movimento detectado · 5 vezes até 14:45" for grouped movements. */
private fun detailLine(a: JSONObject): String {
    val at = clock.format(Date(a.optLong("at")))
    val until = if (a.has("until")) clock.format(Date(a.optLong("until"))) else null
    val count = a.optInt("count", 1)
    return when {
        a.optString("type") == "motion" && count > 1 && until != null -> "$at · ${a.optString("camera")} · $count vezes até $until"
        until != null && until != at -> "$at–$until · ${a.optString("camera")}"   // uma visita: de quando chegou até a última vez vista
        else -> "$at · ${a.optString("camera")}"
    }
}

@Composable
private fun ActivityRow(hub: Hub, a: JSONObject, isNew: Boolean, onOpen: () -> Unit) {
    val l = look(a.optString("type"))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconBubble(l.icon, l.tint, l.bg, size = 42)
            if (isNew) Box(Modifier.size(10.dp).align(Alignment.TopEnd).background(Orange, CircleShape))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(a.optString("text"), fontSize = 14.sp, fontWeight = if (isNew) FontWeight.Bold else FontWeight.Medium)
            Text(detailLine(a), fontSize = 12.sp, color = palette.muted)
        }
        a.optString("thumb").takeIf { it.isNotEmpty() && it != "null" }?.let { name ->
            val thumb = rememberPicture(hub, hub.thumbUrl(name))
            Box(Modifier.size(width = 64.dp, height = 42.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B0F17))) {
                thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}

/** The activity big: its picture with where the person was, when, and a way to see the camera now. */
@Composable
private fun ActivityDetail(hub: Hub, a: JSONObject, onClose: () -> Unit, onLive: () -> Unit, onChanged: (JSONObject) -> Unit, onMistake: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().card(palette, 20.dp).padding(14.dp)) {
            a.optString("thumb").takeIf { it.isNotEmpty() && it != "null" }?.let { name ->
                val pic = rememberPicture(hub, hub.thumbUrl(name))
                val box = a.optJSONArray("box")
                Box(Modifier.fillMaxWidth().aspectRatio(pic?.let { it.width.toFloat() / it.height } ?: 0.9f).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0B0F17))) {
                    pic?.let { Image(it, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
                    if (box != null) Canvas(Modifier.fillMaxSize()) {
                        val color = if (a.optString("type") == "animal") Color(0xFFA855F7) else Orange
                        drawRect(color, Offset(box.optDouble(0).toFloat() * size.width, box.optDouble(1).toFloat() * size.height),
                            Size(box.optDouble(2).toFloat() * size.width, box.optDouble(3).toFloat() * size.height), style = Stroke(2.5.dp.toPx()))
                    }
                }
            }
            Text(a.optString("text"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(top = 12.dp))
            Text("${dayTitle(a.optLong("at"))} · ${detailLine(a)}", color = palette.muted, fontSize = 13.sp)
            if (a.optBoolean("alert")) Text("Proteção estava ativada: o celular avisou.", color = palette.muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
            if (a.optString("guess").isNotEmpty() && a.optJSONArray("names") == null)
                Text("Pela roupa: o rosto não apareceu. Parecida com a de ${a.optString("guess")} hoje.", color = palette.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp))
            if (a.optString("type") == "person") WhoIsFaces(hub, a, onChanged)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                // A IA errou (um cobertor virou "Cachorro"): sai da lista e fica anotado para ajustar
                if (a.optString("type") in setOf("person", "animal", "motion")) TextButton(onClick = onMistake) { Text("Foi engano", color = Red) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Fechar", color = palette.muted) }
                Button(onClick = onLive, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Ao vivo") }
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
            ListRow(Icons.Outlined.Videocam, "Câmeras", "Nome, recursos, zonas e vistoria", onClick = { onOpen(Screen.Features) })
            ListRow(Icons.Outlined.AutoAwesome, "Inteligência (IA)", "Ligar a IA, os recursos e o rigor", onClick = { onOpen(Screen.Ai) })
            ListRow(Icons.Outlined.Notifications, "Proteção e avisos", "Avisos no celular e horário", onClick = { onOpen(Screen.Protection) })
            ListRow(Icons.Outlined.Face, "Pessoas conhecidas", "Reconhecer as pessoas da casa pelo rosto", onClick = { onOpen(Screen.People) })
            ListRow(Icons.Outlined.Group, "Família e aparelhos", "Quem tem acesso às câmeras", onClick = { onOpen(Screen.Family) })
            ListRow(Icons.Outlined.Inventory2, "Armazenamento", "Gravações e espaço em nuvem", onClick = { onOpen(Screen.Soon("Armazenamento")) })
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().card(palette)) {
            ListRow(Icons.Outlined.Palette, "Aparência", "Tema claro/escuro", onClick = { onOpen(Screen.Appearance) })
            ListRow(Icons.Outlined.Lock, "Privacidade", "Dados e permissões", onClick = { onOpen(Screen.Privacy) })
            ListRow(Icons.Outlined.Wifi, "Conexão", "Status, reconectar e ver de fora de casa", onClick = { onOpen(Screen.System) })
            ListRow(Icons.Outlined.MonitorHeart, "Diagnóstico", "Testar a câmera, o Wi-Fi dela e a IA", onClick = { onOpen(Screen.Diagnostics) })
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
                ListRow(Icons.Outlined.Palette, label, onClick = { setTheme(v) }, trailing = {
                    Box(Modifier.size(20.dp).background(if (theme == v) Blue else Color.Transparent, CircleShape)
                        .then(if (theme == v) Modifier else Modifier.background(palette.line, CircleShape)))
                })
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
fun SystemScreen(live: Live, prefs: android.content.SharedPreferences, onBack: () -> Unit, onLeave: () -> Unit) {
    Page {
        TopBar("", null, onBack)
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(73.dp)
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
        live.hub?.let { ConnectionCard(it) }
        // From outside the house: the computer's address on a private network that reaches it (Tailscale, a VPN)
        var remote by remember { mutableStateOf(prefs.getString("remote", "") ?: "") }
        var remoteNote by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        Text("Ver de fora de casa", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp))
        Text("Fora do Wi-Fi de casa, o app tenta este endereço do computador (por exemplo o do Tailscale, 100.x.x.x:8787).",
            color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(remote, { remote = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("100.117.117.73:8787") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue))
            TextButton(onClick = {
                val a = remote.trim().removePrefix("http://").trimEnd('/')
                if (a.isEmpty()) { prefs.edit().remove("remote").apply(); remoteNote = "Sem endereço de fora."; return@TextButton }
                val root = "http://" + if (':' in a) a else "$a:8787"
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { live.hub!!.at(root).screen() }.isSuccess }
                    prefs.edit().putString("remote", root).apply()
                    remoteNote = if (ok) "Salvo: o computador respondeu nesse endereço." else "Salvo, mas o computador não respondeu agora nesse endereço."
                }
            }) { Text("Salvar", color = Blue) }
        }
        remoteNote?.let { Text(it, color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
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
fun FeaturesScreen(live: Live, onBack: () -> Unit, onZones: () -> Unit) {
    val hub = live.hub ?: return
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var checking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf(live.cameraName) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) { profile = withContext(Dispatchers.IO) { runCatching { hub.profile() }.getOrNull() } ?: JSONObject() }
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
            Row(Modifier.fillMaxWidth().padding(top = 14.dp).card(palette).clickable(onClick = onZones).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Outlined.CropFree, size = 36)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Zonas para ignorar", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Marque a TV, o ventilador… o que se mexe ali não conta", color = palette.muted, fontSize = 12.sp)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = palette.muted)
            }
            // Sem animais em casa, a IA ignora animais (evita "Cachorro" que era um cobertor)
            var pets by remember(profile) { mutableStateOf(profile?.optBoolean("pets", true) ?: true) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).card(palette).clickable {
                pets = !pets; scope.launch { withContext(Dispatchers.IO) { runCatching { hub.setPets(pets) } } }
            }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Outlined.Pets, size = 36)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Tem animais em casa", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(if (pets) "A IA avisa de animais também" else "A IA ignora animais", color = palette.muted, fontSize = 12.sp)
                }
                Switch(pets, onCheckedChange = { pets = it; scope.launch { withContext(Dispatchers.IO) { runCatching { hub.setPets(it) } } } },
                    colors = SwitchDefaults.colors(checkedTrackColor = Blue))
            }
            CameraOwnSection(hub)
            if (profile == null) Loading(text = "Lendo a câmera…")
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
            Logo(82.dp)
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
            Logo(73.dp)
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
                withContext(Dispatchers.IO) { runCatching { Hub.login(root, password, "Celular " + Build.MODEL) } }
                    .onSuccess(onKey).onFailure { status = it.message ?: "não deu" }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = Blue), modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp)) {
            Text("Entrar")
        }
    }
}
