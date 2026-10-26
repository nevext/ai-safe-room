package io.nevext.aisaferoom

import android.content.SharedPreferences
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// MARK: - A câmera normal, direto (sem IA)

/**
 * Whether this phone decodes the camera's big video in hardware. Without it (emulators, weak phones) the software
 * decoder falls behind, the camera waits on it and chokes (the picture freezes and its snapshot fails, 26/10).
 */
fun decodesBig(width: Int, height: Int): Boolean = runCatching {
    android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        if (info.isEncoder || "video/avc" !in info.supportedTypes.map { it.lowercase() }) return@any false
        val hardware = if (android.os.Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
            else !info.name.lowercase().let { it.startsWith("omx.google") || it.startsWith("c2.android") }
        hardware && info.getCapabilitiesForType("video/avc").videoCapabilities.isSizeSupported(width, height)
    }
}.getOrDefault(false)

/**
 * The camera's own video, straight from it over RTSP, like the TV does. This is how the app starts: no camera-ai in
 * the way, nothing extra on the camera's connection. [view] crops one lens of a two-lens camera.
 */
@OptIn(UnstableApi::class)
@Composable
fun DirectVideo(live: Live, view: String, visible: Boolean, modifier: Modifier = Modifier, fit: Boolean = false, onGiveUp: () -> Unit = {}) {
    val hub = live.hub ?: return
    val context = LocalContext.current
    var url by remember(hub) { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var aspect by remember { mutableStateOf(1.5f) }
    var light by remember(hub) { mutableStateOf<Boolean?>(null) }   // the light video (sub): the phone can't keep up with the big one
    var stalls by remember { mutableIntStateOf(0) }
    LaunchedEffect(hub, visible, attempt, light) {
        if (visible && url == null) url = withContext(Dispatchers.IO) {
            runCatching {
                val s = hub.directStream()
                val useLight = light ?: !decodesBig(s.optInt("width", 3072), s.optInt("height", 2048)).also { light = !it }
                s.optString(if (useLight) "sub" else "main").ifEmpty { null }
            }.getOrNull()
        }
        if (url == null && visible) { delay(3000); attempt++ }
    }
    val player = remember(url, visible, attempt) {
        val u = url
        // Little reserve to start (live, not 8 s behind); waits 15 s for the camera before giving up (a Wi-Fi burst
        // isn't a reason to reconnect)
        if (u == null || !visible) null else ExoPlayer.Builder(context).setLoadControl(
            androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(300, 3000, 100, 300).build()
        ).build().apply {
            setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(15_000).createMediaSource(MediaItem.fromUri(u)))
            volume = 0f
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) { playing = true; failed = false }
                    // Stopping to wait for video again and again: the big one is too much here, go light
                    if (state == Player.STATE_BUFFERING && playing && light == false && ++stalls >= 3) { light = true; url = null; attempt++ }
                }
                override fun onPlayerError(error: PlaybackException) { playing = false; failed = true; url = null }   // the password may have changed
                override fun onVideoSizeChanged(size: VideoSize) { if (size.width > 0 && size.height > 0) aspect = size.width.toFloat() / size.height }
            })
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player?.release(); playing = false } }
    // The room's sound comes in the camera's own stream: just turn it up
    LaunchedEffect(player, live.listen) { player?.volume = if (live.listen) 1f else 0f }
    // Stay live, like the TV: the reserve starts small and grows only when the camera's Wi-Fi stutters (each freeze adds
    // 300 ms, up to 2 s); when more than that piles up, play a little faster until caught up
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        var target = 300L
        var lastBuffering = false
        while (true) {
            delay(500)
            val buffering = p.playbackState == Player.STATE_BUFFERING
            if (buffering && !lastBuffering && playing) target = (target + 300).coerceAtMost(2000)
            lastBuffering = buffering
            val ahead = p.totalBufferedDuration
            val speed = when { ahead > target + 1500 -> 1.25f; ahead > target + 400 -> 1.1f; else -> 1f }
            if (p.playbackParameters.speed != speed) p.setPlaybackSpeed(speed)
        }
    }
    // The camera dropped: try again in a few seconds
    LaunchedEffect(failed) {
        if (!failed) return@LaunchedEffect
        if (attempt >= 3 && !playing) { onGiveUp(); return@LaunchedEffect }   // the camera won't open direct (another network)
        delay(3000); failed = false; attempt++
    }

    BoxWithConstraints(modifier.clipToBounds().background(Color(0xFF0B0F17)), contentAlignment = Alignment.Center) {
        // The part of the picture to show (one lens is half of it), fitted in the box or filling it
        val region = if (view == "whole") aspect else aspect * 2
        val wide = region > maxWidth / maxHeight
        val (rw, rh) = if (wide == fit) maxWidth to maxWidth / region else maxHeight * region to maxHeight
        val fullH = rw / aspect
        val dy = when (view) { "top" -> (fullH - rh) / 2; "bottom" -> -(fullH - rh) / 2; else -> 0.dp }
        if (player != null) AndroidView(factory = { TextureView(it) }, update = { player.setVideoTextureView(it) },
            modifier = Modifier.requiredSize(rw, fullH).offset(y = dy))
        if (!playing) {
            rememberPicture(hub, hub.thumbUrl)?.let {
                Image(it, null, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.6f)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                Text(if (attempt > 0) "Reconectando…" else "Abrindo a câmera…", color = Color(0xCCFFFFFF), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

// MARK: - Inteligência (IA)

private val RIGORS = listOf(
    Triple("certeza", "Só com certeza", "Anota só quando a IA tem certeza de que é uma pessoa ou um animal. Menos alarmes falsos; pode deixar passar alguém rápido ou longe."),
    Triple("equilibrado", "Equilibrado", "O meio-termo: confirma por um instante antes de anotar."),
    Triple("suspeita", "Qualquer suspeita", "Anota sempre que a IA suspeitar. Não deixa passar nada, mas anota mais coisas que não eram ninguém."),
)

/** Ajustes → Inteligência (IA): off by default, like the TV. On, each feature is chosen and stays as left. */
@Composable
fun AiScreen(live: Live, onBack: () -> Unit, onPeople: () -> Unit, onZones: () -> Unit) {
    val hub = live.hub ?: return
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { info = withContext(Dispatchers.IO) { runCatching { hub.ai() }.getOrNull() } }
    val change = { body: JSONObject ->
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { hub.setAi(body).also { live.info = hub.screen(); live.controls = hub.screen().optJSONArray("controls") ?: live.controls } } }
                .onSuccess { info = it; note = null }.onFailure { note = "Não deu: ${it.message}" }
        }
    }
    Page {
        TopBar("", null, onBack)
        PageTitle("Inteligência (IA)", "O computador olhando a câmera por você")
        val on = info?.optBoolean("enabled") == true
        if (info == null) { Loading(); return@Page }
        Column(Modifier.fillMaxWidth().card(palette, selected = on).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Outlined.AutoAwesome, size = 40)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Usar IA", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(if (on) "Ligada" else "Desligada: a câmera funciona normal", color = if (on) Blue else palette.muted, fontSize = 12.sp)
                }
                Switch(on, onCheckedChange = { change(JSONObject().put("enabled", it).put("detect", it)) }, enabled = info?.optBoolean("available") == true,
                    colors = SwitchDefaults.colors(checkedTrackColor = Blue))
            }
            Text("Com a IA, o computador de casa vê pessoas e animais, anota nas atividades, pode avisar no celular, seguir quem " +
                "aparece e reconhecer rostos. Desligada, o app mostra a câmera direto, como a TV.", color = palette.muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp))
            Text("A IA usa a conexão da câmera com o Wi-Fi: se a câmera estiver longe do roteador, a imagem pode travar mais.",
                color = Orange, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (info?.optBoolean("available") == false) Text("O computador ainda não tem a IA instalada.", color = Red, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
        if (on) {
            Text("Recursos", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
            Column(Modifier.fillMaxWidth().card(palette)) {
                FeatureSwitch(Icons.Outlined.PersonSearch, "Atividades de pessoas e animais",
                    "Anota quem apareceu (as visitas) e permite os avisos da Proteção", info?.optBoolean("detect") == true) {
                    change(JSONObject().put("detect", it))
                }
                FeatureSwitch(Icons.Outlined.CenterFocusStrong, "Seguir pessoa", "A lente que gira vai atrás de quem aparece",
                    info?.optBoolean("follow") == true) { change(JSONObject().put("follow", it)) }
                ListRow(Icons.Outlined.Face, "Pessoas conhecidas", "Reconhecer as pessoas da casa pelo rosto (${info?.optInt("people") ?: 0})",
                    onClick = onPeople)
                ListRow(Icons.Outlined.Videocam, "Zonas para ignorar", "Partes da imagem que a IA não olha (TV, ventilador)", onClick = onZones)
            }
            Text("Rigor da detecção", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
            Column(Modifier.fillMaxWidth().card(palette)) {
                val current = info?.optString("rigor") ?: "equilibrado"
                RIGORS.forEach { (id, title, text) ->
                    Row(Modifier.fillMaxWidth().clickable { change(JSONObject().put("rigor", id)) }.padding(16.dp, 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(if (id == current) Blue else palette.line),
                            contentAlignment = Alignment.Center) {
                            if (id == current) Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Color.White))
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(text, color = palette.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            info?.let {
                Text("Modelo ${it.optString("model")}, ${if (it.optString("device") == "gpu") "na placa de vídeo" else "no processador"} do computador.",
                    color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
        note?.let { Text(it, color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FeatureSwitch(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, on: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(icon, title, subtitle, onClick = { onChange(!on) }, trailing = {
        Switch(on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Blue))
    })
}

// MARK: - Diagnóstico

private val TESTS = listOf(
    Triple("wifi", "Wi-Fi da câmera", "A conexão da câmera com o roteador: resposta, perdas e quedas (10 s)"),
    Triple("ai", "Câmera e IA", "Se o vídeo chega bem até a IA e quantas análises ela faz (8 s)"),
    Triple("camera", "A câmera (vistoria completa)", "Confere cada recurso de verdade: a lente gira, a luz e a visão noturna acendem por alguns segundos"),
)

/** Ajustes → Diagnóstico: the camera, its Wi-Fi, and camera to AI, whenever the person wants. */
@Composable
fun DiagnosticsScreen(live: Live, onBack: () -> Unit) {
    val hub = live.hub ?: return
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateOf(mapOf<String, JSONObject>()) }
    var running by remember { mutableStateOf<String?>(null) }
    Page {
        TopBar("", null, onBack)
        PageTitle("Diagnóstico", "Descubra o que está atrapalhando")
        WifiTuneCard(hub)
        TESTS.forEach { (id, title, text) ->
            val r = results.value[id]
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).card(palette).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(when (id) { "wifi" -> Icons.Outlined.Wifi; "ai" -> Icons.Outlined.Psychology; else -> Icons.Outlined.Videocam }, size = 38)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(text, color = palette.muted, fontSize = 12.sp)
                    }
                }
                r?.let { res ->
                    if (!res.optBoolean("ok")) Text("Não deu: ${res.optString("error")}", color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                    else {
                        if (!res.isNull("grade")) {
                            val g = res.optInt("grade")
                            Text(res.optString("label"), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(50))
                                    .background(when (g) { 0 -> Red; 1 -> Orange; 2 -> Blue; else -> Green }).padding(horizontal = 10.dp, vertical = 3.dp))
                        }
                        val lines = res.optJSONArray("lines")
                        for (i in 0 until (lines?.length() ?: 0)) Text(lines!!.optString(i), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        val tips = res.optJSONArray("tips")
                        for (i in 0 until (tips?.length() ?: 0)) Text(tips!!.optString(i), color = Orange, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (running == id) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Blue)
                        Text("Testando…", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                    } else OutlinedButton(onClick = {
                        running = id
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { runCatching { hub.diagnose(id) }.getOrElse { JSONObject().put("ok", false).put("error", it.message) } }
                            results.value = results.value + (id to res)
                            running = null
                        }
                    }, enabled = running == null) { Text(if (r == null) "Testar" else "Testar de novo", color = Blue) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// MARK: - Resumo dos dias

private val dayName = SimpleDateFormat("EEEE, d 'de' MMMM", Locale.forLanguageTag("pt-BR"))
private val hourName = SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR"))

/** The history of days: who came, how many visits, animals, movement; and the 21:00 notification. */
@Composable
fun SummariesScreen(live: Live, prefs: SharedPreferences, onBack: () -> Unit) {
    val hub = live.hub ?: return
    var days by remember { mutableStateOf<List<JSONObject>?>(null) }
    var notify by remember { mutableStateOf(prefs.getBoolean("summary", true)) }
    LaunchedEffect(Unit) {
        days = withContext(Dispatchers.IO) {
            runCatching { hub.summaries().let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } }.getOrNull()
        } ?: emptyList()
    }
    Page {
        TopBar("", null, onBack)
        PageTitle("Resumo dos dias", "Como foi cada dia, nos últimos 30")
        Column(Modifier.fillMaxWidth().card(palette)) {
            ListRow(Icons.Outlined.Notifications, "Avisar o resumo às 21h", "No celular, com os avisos ligados", onClick = {
                notify = !notify; prefs.edit().putBoolean("summary", notify).apply()
            }, trailing = {
                Switch(notify, onCheckedChange = { notify = it; prefs.edit().putBoolean("summary", it).apply() },
                    colors = SwitchDefaults.colors(checkedTrackColor = Blue))
            })
        }
        Spacer(Modifier.height(12.dp))
        val list = days
        if (list == null) CircularProgressIndicator(Modifier.padding(24.dp).size(24.dp), color = Blue, strokeWidth = 2.dp)
        else if (list.isEmpty()) Text("Nada ainda.", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        list?.forEach { d ->
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).card(palette).padding(16.dp)) {
                Text(dayName.format(Date(d.optLong("at"))).replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                val people = d.optInt("people"); val visits = d.optInt("visits"); val animals = d.optInt("animals"); val moves = d.optInt("motions")
                Text(listOfNotNull(
                    if (people == 0) "Ninguém apareceu" else "$visits ${if (visits == 1) "visita" else "visitas"} · até $people ${if (people == 1) "pessoa" else "pessoas"} juntas",
                    animals.takeIf { it > 0 }?.let { "$it ${if (it == 1) "animal" else "animais"}" },
                    moves.takeIf { it > 0 }?.let { "$it ${if (it == 1) "movimento" else "movimentos"}" }).joinToString(" · "),
                    color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                val names = d.optJSONArray("names")
                if (names != null && names.length() > 0) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(Icons.Outlined.Face, null, tint = Blue, modifier = Modifier.size(16.dp))
                    Text((0 until names.length()).joinToString(", ") { names.optString(it) }, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                }
                if (d.optInt("unknown") > 0) Text("${d.optInt("unknown")} com alguém desconhecido", color = Orange, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                if (!d.isNull("first") && people > 0) Text("Primeira às ${hourName.format(Date(d.optLong("first")))}, última às ${hourName.format(Date(d.optLong("last")))}",
                    color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                if (d.optInt("alerts") > 0) Text("${d.optInt("alerts")} ${if (d.optInt("alerts") == 1) "aviso" else "avisos"} da Proteção",
                    color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** "Vídeo leve para o Wi-Fi": what's recommended for cameras on Wi-Fi, one tap, and undo. */
@Composable
private fun WifiTuneCard(hub: Hub) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { info = withContext(Dispatchers.IO) { runCatching { hub.wifiTune() }.getOrNull() } }
    val tuned = info?.optBoolean("tuned") == true
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).card(palette, selected = tuned).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.Wifi, size = 38)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Vídeo da câmera para Wi-Fi", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(if (tuned) "Ajustado: menos travadas e congelamentos" else "Se a imagem trava ou congela, deixe o vídeo mais leve para o Wi-Fi",
                    color = if (tuned) Blue else palette.muted, fontSize = 12.sp)
            }
        }
        Text("Taxa constante (não dispara quando algo se mexe), uma imagem inteira por segundo (se um pedaço se perde, volta logo) " +
            "e um pouco menos de dados. É o que se recomenda para câmera no Wi-Fi. Dá para desfazer.", color = palette.muted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp))
        info?.optJSONObject("main")?.let { m ->
            Text("Agora: ${m.optString("control")}, ${m.optInt("kbps")} kbps, ${m.optInt("fps")} quadros/s", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        note?.let { Text(it, color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Blue)
            else if (info != null) {
                val undo = tuned && info?.optBoolean("canUndo") == true
                if (!tuned || undo) OutlinedButton(onClick = {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { hub.setWifiTune(undo); hub.wifiTune() } }
                            .onSuccess { info = it; note = if (undo) "Voltou ao de antes." else "Pronto: a imagem pode piscar uma vez enquanto a câmera aplica." }
                            .onFailure { note = "Não deu: ${it.message}" }
                        busy = false
                    }
                }) { Text(if (undo) "Desfazer" else "Ajustar", color = Blue) }
            }
        }
    }
}
