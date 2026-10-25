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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
            SwitchRow(Icons.Outlined.Summarize, "Resumo do dia", "Às 21h: quantas pessoas, animais e movimentos", summary) { on ->
                summary = on
                prefs.edit().putBoolean("summary", on).apply()
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
    var devices by remember { mutableStateOf(JSONArray()) }
    var pending by remember { mutableStateOf(JSONArray()) }
    var removing by remember { mutableStateOf<JSONObject?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    // The list, and who's asking to come in, every few seconds while open
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { hub.devices() to hub.pendingAccess() } }.onSuccess { (d, p) -> devices = d; pending = p }
            delay(4000)
        }
    }
    Page {
        TopBar("", null, onBack)
        PageTitle("Família e aparelhos", "Quem tem acesso às câmeras")
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
                    Button(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { hub.answerAccess(r.optString("id"), true) } }
                            note = "${r.optString("name")} entrou."
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Permitir") }
                }
            }
        }
        Column(Modifier.fillMaxWidth().card(palette)) {
            if (devices.length() == 0) Text("Nenhum aparelho entrou com a própria chave ainda. Os que entraram antes desta versão continuam com acesso.",
                color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            for (i in 0 until devices.length()) {
                val d = devices.optJSONObject(i) ?: continue
                ListRow(Icons.Outlined.Smartphone, d.optString("name"), "Entrou ${whenText(d.optLong("at"))} · visto ${whenText(d.optLong("lastSeen"))}",
                    trailing = {
                        Icon(Icons.Outlined.Delete, "Tirar acesso", tint = palette.muted,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { removing = d }.padding(8.dp))
                    })
            }
        }
        Text("Para dar acesso a alguém: instale o AI Safe Room no celular da pessoa, no Wi-Fi de casa. O pedido aparece aqui (e no computador) para você tocar em Permitir.",
            color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        note?.let { Text(it, color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(24.dp))
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
    val picture = rememberPicture(hub, hub.thumbUrl)
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
