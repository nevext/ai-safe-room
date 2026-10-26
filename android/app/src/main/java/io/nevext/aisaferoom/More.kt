package io.nevext.aisaferoom

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
private fun SwitchRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, on: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(icon, title, subtitle, onClick = { onChange(!on) }, trailing = {
        Switch(on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Blue))
    })
}

// MARK: - Proteção e avisos

@Composable
fun ProtectionScreen(live: Live, prefs: SharedPreferences, onBack: () -> Unit) {
    val hub = live.hub ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notify by remember { mutableStateOf(prefs.getBoolean("notify", false)) }
    var summary by remember { mutableStateOf(prefs.getBoolean("summary", true)) }
    val schedule = live.info?.optJSONObject("protection")?.optJSONObject("schedule")
    var scheduled by remember { mutableStateOf(schedule?.optBoolean("enabled") == true) }
    var onAt by remember { mutableStateOf(schedule?.optString("on")?.ifEmpty { null } ?: "22:00") }
    var offAt by remember { mutableStateOf(schedule?.optString("off")?.ifEmpty { null } ?: "07:00") }
    var note by remember { mutableStateOf<String?>(null) }

    val setNotify = { on: Boolean ->
        notify = on
        prefs.edit().putBoolean("notify", on).apply()
        if (on) Guard.start(context) else Guard.stop(context)
    }
    // Android 13+ asks before an app can show notifications
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setNotify(true) else note = "Sem permissão de notificação: libere nas configurações do Android."
    }

    Page {
        TopBar("", null, onBack)
        PageTitle("Proteção e avisos", "Quando avisar e como")
        Column(Modifier.fillMaxWidth().card(palette)) {
            SwitchRow(Icons.Outlined.Shield, "Proteção", if (live.protectionOn) "Ativada: avisa quando aparecer alguém" else "Desativada: guarda as atividades sem avisar",
                live.protectionOn) { on ->
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { hub.setProtection(on); hub.screen() } }
                        .onSuccess { live.info = it }.onFailure { note = "Não deu: ${it.message}" }
                }
            }
            // Only unknowns: needs someone registered in Pessoas conhecidas
            val onlyUnknown = live.info?.optJSONObject("protection")?.optBoolean("onlyUnknown") == true
            SwitchRow(Icons.Outlined.Face, "Só pessoas desconhecidas",
                if (onlyUnknown) "Quem foi cadastrado em Pessoas conhecidas não avisa. Sem ver o rosto em 20 s, avisa do mesmo jeito."
                else "Avisa de qualquer pessoa", onlyUnknown) { on ->
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { hub.setOnlyUnknown(on); hub.screen() } }
                        .onSuccess { live.info = it }.onFailure { note = "Não deu: ${it.message}" }
                }
            }
            SwitchRow(Icons.Outlined.NotificationsActive, "Avisos neste celular", "Pessoa ou animal com a Proteção ativada, câmera offline", notify) { on ->
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else setNotify(on)
            }
        }
        Text("Os avisos chegam pelo Wi-Fi de casa ou, fora dela, pelo endereço de fora em Ajustes → Conexão.",
            color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))

        Text("Por horário", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().card(palette)) {
            SwitchRow(Icons.Outlined.Schedule, "Ligar e desligar sozinha", "Todo dia nos horários abaixo", scheduled) { scheduled = it }
            Row(Modifier.padding(16.dp, 4.dp, 16.dp, 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(onAt, { onAt = it.take(5) }, singleLine = true, label = { Text("Liga às") }, modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue))
                OutlinedTextField(offAt, { offAt = it.take(5) }, singleLine = true, label = { Text("Desliga às") }, modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue))
            }
        }
        Button(onClick = {
            val ok = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
            if (!ok.matches(onAt) || !ok.matches(offAt)) { note = "Use horas como 22:00 e 07:00."; return@Button }
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { hub.setProtectionSchedule(scheduled, onAt, offAt); hub.screen() } }
                    .onSuccess { live.info = it; note = if (scheduled) "Salvo: liga às $onAt e desliga às $offAt." else "Salvo: sem horário." }
                    .onFailure { note = "Não deu: ${it.message}" }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = Blue), modifier = Modifier.padding(top = 12.dp)) { Text("Salvar horário") }
        note?.let { Text(it, color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(24.dp))
    }
}

// MARK: - Família e aparelhos

@Composable
fun FamilyScreen(live: Live, onBack: () -> Unit) {
    val hub = live.hub ?: return
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(Kept.get<JSONArray>("devices") ?: JSONArray()) }
    var pending by remember { mutableStateOf(JSONArray()) }
    var removing by remember { mutableStateOf<JSONObject?>(null) }
    var changing by remember { mutableStateOf<JSONObject?>(null) }   // the device whose role is being changed
    var letting by remember { mutableStateOf<JSONObject?>(null) }    // the request being allowed, to pick its role
    val owners = (0 until devices.length()).count { devices.optJSONObject(it)?.optString("role") == "owner" }
    var note by remember { mutableStateOf<String?>(null) }
    // The list, and who's asking to come in, every few seconds while open
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { hub.devices() to hub.pendingAccess() } }.onSuccess { (d, p) -> devices = d; pending = p; Kept["devices"] = d }
            delay(4000)
        }
    }
    Page {
        TopBar("", null, onBack)
        PageTitle("Família e aparelhos", "Quem tem acesso e o que cada um pode")
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).card(palette).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ROLES.forEach { (_, name, what) ->
                Text(buildString { append(name); append(": "); append(what) }, color = palette.muted, fontSize = 12.sp)
            }
        }
        for (i in 0 until pending.length()) {
            val r = pending.optJSONObject(i) ?: continue
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).card(palette, selected = true).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.Outlined.PersonAdd)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("${r.optString("name")} quer entrar", fontWeight = FontWeight.SemiBold)
                        Text(r.optString("ip"), color = palette.muted, fontSize = 12.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { hub.answerAccess(r.optString("id"), false) } } } }) {
                        Text("Recusar", color = palette.muted)
                    }
                    Button(onClick = { letting = r }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Permitir") }
                }
            }
        }
        Column(Modifier.fillMaxWidth().card(palette)) {
            if (devices.length() == 0) Text("Nenhum aparelho entrou com a própria chave ainda. Os que entraram antes desta versão continuam com acesso.",
                color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            for (i in 0 until devices.length()) {
                val d = devices.optJSONObject(i) ?: continue
                val role = ROLES.firstOrNull { it.first == d.optString("role") } ?: ROLES[1]
                val until = d.optLong("until").takeIf { it > 0 }?.let { " até " + java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(it)) } ?: ""
                ListRow(Icons.Outlined.Smartphone, d.optString("name") + if (d.optBoolean("me")) " (este celular)" else "",
                    "${role.second}$until · visto ${whenText(d.optLong("lastSeen"))}", onClick = { changing = d },
                    trailing = {
                        Icon(Icons.Outlined.Delete, "Tirar acesso", tint = palette.muted,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { removing = d }.padding(8.dp))
                    })
            }
        }
        Text("Toque num aparelho para mudar o papel dele. Para dar acesso a alguém: instale o AI Safe Room no celular da pessoa, no Wi-Fi de casa. O pedido aparece aqui (e na notificação) para você escolher o papel e permitir.",
            color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        note?.let { Text(it, color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(24.dp))
    }
    // Allowing someone: as what
    letting?.let { r ->
        RolePicker("${r.optString("name")} entra como", owners, current = null, onDismiss = { letting = null }) { role, hours ->
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { hub.answerAccess(r.optString("id"), true, role, hours) } }
                    .onSuccess { note = if (it.optBoolean("ok")) "${r.optString("name")} entrou." else "Não deu: ${it.optString("error")}" }
                    .onFailure { note = "Não deu: ${it.message}" }
                letting = null
            }
        }
    }
    changing?.let { d ->
        RolePicker(d.optString("name"), owners, current = d.optString("role"), onDismiss = { changing = null }) { role, hours ->
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { hub.setRole(d.optString("id"), role, hours) } }.onSuccess {
                    if (it.optBoolean("ok")) { it.optJSONArray("devices")?.let { a -> devices = a }; note = null } else note = "Não deu: ${it.optString("error")}"
                }.onFailure { note = "Não deu: ${it.message}" }
                changing = null
            }
        }
    }
    removing?.let { d ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text("Tirar o acesso?") },
            text = { Text("${d.optString("name")} não vai mais ver as câmeras. Para voltar, precisa pedir de novo.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { hub.removeDevice(d.optString("id")) } }.onSuccess { devices = it }
                        removing = null
                    }
                }) { Text("Tirar", color = Red) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } })
    }
}

// MARK: - Zonas para ignorar

@Composable
fun ZonesScreen(live: Live, onBack: () -> Unit) {
    val hub = live.hub ?: return
    val scope = rememberCoroutineScope()
    val zones = remember { mutableStateListOf<FloatArray>() }
    var drawing by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { hub.profile().optJSONArray("zones") } }.getOrNull()?.let { a ->
            zones.clear()
            for (i in 0 until a.length()) a.optJSONArray(i)?.let { z -> zones.add(FloatArray(4) { z.optDouble(it).toFloat() }) }
        }
    }
    val picture = rememberCameraPicture(hub)
    Page {
        TopBar("", null, onBack)
        PageTitle("Zonas para ignorar", "Arraste o dedo na imagem para marcar uma área")
        Box(Modifier.fillMaxWidth().aspectRatio(picture?.let { it.width.toFloat() / it.height } ?: 0.9f).clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B0F17))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { drawing = it to it },
                    onDrag = { change, _ -> drawing = drawing?.let { it.first to change.position } },
                    onDragEnd = {
                        drawing?.let { (a, b) ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val x = minOf(a.x, b.x) / w
                            val y = minOf(a.y, b.y) / h
                            val zw = kotlin.math.abs(a.x - b.x) / w
                            val zh = kotlin.math.abs(a.y - b.y) / h
                            if (zw > 0.02f && zh > 0.02f && zones.size < 8) zones.add(floatArrayOf(x, y, zw, zh))
                        }
                        drawing = null
                    })
            }) {
            picture?.let { Image(it, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            Canvas(Modifier.fillMaxSize()) {
                for (z in zones) {
                    drawRect(Color(0x55EF4444), Offset(z[0] * size.width, z[1] * size.height), Size(z[2] * size.width, z[3] * size.height))
                    drawRect(Red, Offset(z[0] * size.width, z[1] * size.height), Size(z[2] * size.width, z[3] * size.height), style = Stroke(2.dp.toPx()))
                }
                drawing?.let { (a, b) ->
                    drawRect(Blue, Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)), style = Stroke(2.dp.toPx()))
                }
            }
        }
        Text("A IA não conta pessoas nem animais com o centro dentro das áreas vermelhas (uma TV ligada, um ventilador, uma janela).",
            color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { zones.clear() }) { Text("Limpar tudo", color = palette.muted) }
            if (zones.isNotEmpty()) TextButton(onClick = { zones.removeAt(zones.size - 1) }) { Text("Desfazer a última", color = palette.muted) }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { hub.setZones(zones.toList()) } }
                        .onSuccess { note = if (zones.isEmpty()) "Sem zonas: a imagem inteira conta." else "Salvo: ${zones.size} ${if (zones.size == 1) "zona" else "zonas"}." }
                        .onFailure { note = "Não deu: ${it.message}" }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Salvar") }
        }
        note?.let { Text(it, color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(24.dp))
    }
}

// MARK: - Conexão (Péssimo, Regular, Bom, Ótimo)

private fun gradeColor(grade: Int) = when (grade) { 0 -> Red; 1 -> Orange; 2 -> Blue; else -> Green }
/** The grade, or null while it's reconnecting (no grade yet). */
private fun JSONObject.grade(): Int? = if (isNull("grade")) null else optInt("grade")

/** The connection, asked again every few seconds while [active]. */
@Composable
private fun rememberConnection(hub: Hub, active: Boolean = true): Pair<JSONObject?, (JSONObject) -> Unit> {
    // Starts from the last one: on Início the shortcut is there from the first frame (it used to pop in and push the page)
    var info by remember { mutableStateOf(Kept.get<JSONObject>("connection")) }
    LaunchedEffect(active) {
        while (active) {
            withContext(Dispatchers.IO) { runCatching { hub.connection() } }.onSuccess { info = it; Kept["connection"] = it }
            delay(5000)
        }
    }
    return info to { it: JSONObject -> info = it; Kept["connection"] = it }
}

/** On Início: one line with both grades; a tap opens Ajustes → Conexão. */
@Composable
fun ConnectionShortcut(hub: Hub, visible: Boolean, onOpen: () -> Unit) {
    val (info, _) = rememberConnection(hub, visible)
    val camera = info?.optJSONObject("camera") ?: return
    val ai = info.optJSONObject("ai")
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).card(palette).clickable(onClick = onOpen).padding(14.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Wifi, null, tint = gradeColor(camera.optInt("grade")), modifier = Modifier.size(20.dp))
        Text("Câmera ", fontSize = 13.sp, color = palette.muted, modifier = Modifier.padding(start = 10.dp))
        Text(camera.optString("label"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = gradeColor(camera.optInt("grade")))
        Text("  ·  IA ", fontSize = 13.sp, color = palette.muted)
        if (ai?.optString("state") == "on") Text(ai.optString("label"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = ai.grade()?.let(::gradeColor) ?: palette.muted)
        else Text("desligada", fontSize = 13.sp, color = palette.muted)
        Spacer(Modifier.weight(1f))
        Text(if (camera.optInt("grade") <= 1) "Reconectar" else "Conexão", fontSize = 12.sp, color = Blue, fontWeight = FontWeight.SemiBold)
        Icon(Icons.Outlined.ChevronRight, null, tint = palette.muted, modifier = Modifier.size(18.dp))
    }
}

/** In Ajustes → Conexão: the camera and the AI, each with its grade, what it means, and "Reconectar". */
@Composable
fun ConnectionCard(hub: Hub) {
    val (info, set) = rememberConnection(hub)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val again = { what: String ->
        busy = what; note = null
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { hub.reconnect(what) } }
                .onSuccess { set(it); note = it.optString("error").takeIf { e -> e.isNotEmpty() }?.let { e -> "Não deu: $e" } ?: "Reconectado." }
                .onFailure { note = "Não deu: ${it.message}" }
            busy = null
        }
    }
    Text("Conexão", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().card(palette)) {
        val camera = info?.optJSONObject("camera")
        val ai = info?.optJSONObject("ai")
        ConnectionRow(Icons.Outlined.Videocam, "Câmera", camera?.optString("label"), camera?.optInt("grade"),
            camera?.optString("detail") ?: "Medindo…", camera?.optString("tip")?.takeIf { it.isNotEmpty() && it != "null" },
            busy == "camera") { again("camera") }
        val aiOn = ai?.optString("state") == "on"
        ConnectionRow(Icons.Outlined.AutoAwesome, "IA", if (aiOn) ai?.optString("label") else "Desligada",
            if (aiOn) ai?.grade() else null, ai?.optString("detail") ?: "Medindo…", null, busy == "ai",
            onReconnect = if (aiOn) ({ again("ai") }) else null)
    }
    Text("A câmera é medida pelo tempo que ela demora para responder e pelas quedas da última hora. A IA, pelas análises " +
        "por segundo e pelo vídeo que chega até ela.", color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    note?.let { Text(it, color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
}

@Composable
private fun ConnectionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, label: String?, grade: Int?,
                          detail: String, tip: String?, busy: Boolean, onReconnect: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, size = 38)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                label?.let {
                    Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50))
                            .background(grade?.let(::gradeColor) ?: palette.muted).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Text(detail, color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            tip?.let { Text(it, color = Orange, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
        if (onReconnect != null) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Blue)
            else TextButton(onClick = onReconnect) { Text("Reconectar", color = Blue) }
        }
    }
}

// MARK: - Notificações

private val NOTIFY_KINDS = listOf(
    Triple("notifyPerson", "Pessoa no cômodo", "Com a Proteção ligada. Com som e a foto; uma por visita, com o nome quando reconhecer o rosto"),
    Triple("notifyAnimal", "Animal", "Com a Proteção ligada, se a casa tem animais (precisa da IA)"),
    Triple("notifyOffline", "Câmera offline ou coberta", "Pode ser alguém mexendo nela"),
    Triple("notifyAccess", "Alguém pediu acesso", "Um aparelho novo querendo entrar: Permitir ou Recusar na própria notificação"),
    Triple("summary", "Resumo do dia", "Às 21h, sem som"),
    Triple("notifyWeak", "Conexão da câmera fraca", "Sem som, no máximo a cada 6 horas"),
    Triple("notifySchedule", "Proteção pelo horário", "Quando ela liga ou desliga sozinha, sem som"),
)

/** Ajustes → Notificações: which warnings reach the notification bar. */
@Composable
fun NotificationsScreen(live: Live, prefs: SharedPreferences, onBack: () -> Unit) {
    val context = LocalContext.current
    var master by remember { mutableStateOf(prefs.getBoolean("notify", false)) }
    val states = remember { NOTIFY_KINDS.associate { it.first to mutableStateOf(prefs.getBoolean(it.first, true)) } }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { master = true; prefs.edit().putBoolean("notify", true).apply(); Guard.start(context) }
    }
    Page {
        TopBar("", null, onBack)
        PageTitle("Notificações", "O que aparece na barra do celular")
        Column(Modifier.fillMaxWidth().card(palette, selected = master)) {
            SwitchRow(Icons.Outlined.NotificationsActive, "Avisos neste celular",
                if (master) "Ligados: o app fica de olho mesmo fechado" else "Desligados: nada chega na barra", master) { on ->
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    master = on; prefs.edit().putBoolean("notify", on).apply()
                    if (on) Guard.start(context) else Guard.stop(context)
                }
            }
        }
        Text("Alertas tocam e aparecem na tela bloqueada. Os outros chegam sem som, e de madrugada (23h às 7h) só os alertas. " +
            "Sem o computador, o celular ouve a própria câmera: a pessoa e a câmera offline continuam chegando.",
            color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
        Column(Modifier.fillMaxWidth().card(palette)) {
            NOTIFY_KINDS.forEach { (key, title, text) ->
                val state = states.getValue(key)
                ListRow(Icons.Outlined.Notifications, title, text, onClick = {
                    state.value = !state.value; prefs.edit().putBoolean(key, state.value).apply()
                }, trailing = {
                    Switch(state.value, onCheckedChange = { state.value = it; prefs.edit().putBoolean(key, it).apply() }, enabled = master,
                        colors = SwitchDefaults.colors(checkedTrackColor = Blue))
                })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The family roles: id, name, what they can do. */
private val ROLES = listOf(
    Triple("owner", "Dono", "mexe em tudo e aprova quem entra (no máximo 2)"),
    Triple("member", "Membro", "vê ao vivo, ouve, fala, recebe os avisos e mexe na luz, visão noturna e lente; não muda Proteção, Privacidade, IA, rostos nem acessos"),
    Triple("guest", "Convidado", "babá, diarista: só ao vivo, ouvir e falar, até a hora marcada"),
)

/** Pick a role (and, for a guest, for how long). */
@Composable
private fun RolePicker(title: String, owners: Int, current: String?, onDismiss: () -> Unit, onPick: (String, Int) -> Unit) {
    var role by remember { mutableStateOf(current ?: "member") }
    var hours by remember { mutableStateOf(8) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ROLES.forEach { (id, name, what) ->
                    val full = id == "owner" && current != "owner" && owners >= 2
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).card(palette, selected = role == id)
                        .clickable(enabled = !full) { role = id }.padding(12.dp)) {
                        Text(name, fontWeight = FontWeight.SemiBold, color = if (full) palette.muted else Color.Unspecified)
                        Text(if (full) "Já tem 2 donos" else what, color = palette.muted, fontSize = 12.sp)
                    }
                }
                if (role == "guest") Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(4 to "4 h", 8 to "8 h", 24 to "1 dia", 168 to "7 dias").forEach { (h, label) ->
                        Text(label, color = if (hours == h) Color.White else palette.muted, fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (hours == h) Blue else palette.card)
                                .clickable { hours = h }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(role, if (role == "guest") hours else 0) }) { Text("Pronto") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}
