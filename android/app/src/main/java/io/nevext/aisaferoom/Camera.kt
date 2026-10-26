package io.nevext.aisaferoom

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// MARK: - Girar a lente

/** Arrows to turn the lens (hold to move, let go to stop) and the saved position in the middle (hold it to save here). */
@Composable
fun PtzPad(hub: Hub, onNote: (String) -> Unit) {
    var saving by remember { mutableStateOf(false) }
    val send = { dir: String, action: String -> thread { runCatching { hub.ptz(dir, action) }.onFailure { onNote("Não deu: ${it.message}") } }; Unit }
    Column(Modifier.fillMaxWidth().card(palette).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Girar a lente", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
        Text("Segure a seta para girar. Toque no meio para voltar à posição salva; segure o meio para salvar onde está.",
            color = palette.muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        PadButton(Icons.Outlined.KeyboardArrowUp, "Para cima") { down -> send("up", if (down) "start" else "stop") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PadButton(Icons.Outlined.KeyboardArrowLeft, "Para a esquerda") { down -> send("left", if (down) "start" else "stop") }
            Box(Modifier.size(56.dp).clip(CircleShape).background(palette.soft).pointerInput(Unit) {
                detectTapGestures(onTap = { send("home", "start") }, onLongPress = { saving = true })
            }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Home, "Posição salva", tint = Blue) }
            PadButton(Icons.Outlined.KeyboardArrowRight, "Para a direita") { down -> send("right", if (down) "start" else "stop") }
        }
        PadButton(Icons.Outlined.KeyboardArrowDown, "Para baixo") { down -> send("down", if (down) "start" else "stop") }
    }
    if (saving) AlertDialog(onDismissRequest = { saving = false }, title = { Text("Salvar esta posição?") },
        text = { Text("A lente volta para cá quando você tocar no meio, e o seguir pessoa volta para cá quando não vê ninguém.") },
        confirmButton = { Button(onClick = { saving = false; send("sethome", "start"); onNote("Posição salva.") },
            colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancelar", color = palette.muted) } })
}

@Composable
private fun PadButton(icon: ImageVector, label: String, onPress: (down: Boolean) -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(palette.soft).pointerInput(Unit) {
        detectTapGestures(onPress = {
            onPress(true)
            tryAwaitRelease()
            onPress(false)
        })
    }, contentAlignment = Alignment.Center) { Icon(icon, label, tint = Blue, modifier = Modifier.size(30.dp)) }
}

// MARK: - Ouvir e falar

/** The sound of the room through camera-ai (PCM 16 bits, 8 kHz), for when the video comes via the computer. */
@Composable
fun RoomSound(hub: Hub, on: Boolean) {
    DisposableEffect(hub, on) {
        if (!on) return@DisposableEffect onDispose {}
        var running = true
        var connection: HttpURLConnection? = null
        thread {
            val size = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(size).build()
            track.play()
            while (running) {
                runCatching {
                    val c = URL(hub.audioUrl).openConnection() as HttpURLConnection
                    connection = c
                    c.readTimeout = 10_000
                    c.inputStream.use { input ->
                        val buf = ByteArray(1600)
                        while (running) {
                            val n = input.read(buf)
                            if (n < 0) break
                            track.write(buf, 0, n)
                        }
                    }
                }
                if (running) Thread.sleep(2000)
            }
            track.stop(); track.release()
        }
        onDispose { running = false; thread { runCatching { connection?.disconnect() } } }
    }
}

/** Speaker on the video: hear the room. */
@Composable
fun ListenButton(live: Live, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(CircleShape).background(Color(0x99000000)).clickable { live.listen = !live.listen },
        contentAlignment = Alignment.Center) {
        Icon(if (live.listen) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, if (live.listen) "Parar de ouvir" else "Ouvir", tint = Color.White)
    }
}

/** Hold to talk through the camera's speaker (the phone's microphone, 8 kHz, to camera-ai and from there to the camera). */
@SuppressLint("MissingPermission")
@Composable
fun TalkButton(live: Live, onNote: (String) -> Unit, modifier: Modifier = Modifier) {
    val hub = live.hub ?: return
    val context = LocalContext.current
    var talking by remember { mutableStateOf(false) }
    val allowed = { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        onNote(if (ok) "Pronto: segure o microfone para falar." else "Sem permissão do microfone: libere nas configurações do Android.")
    }
    var stop: (() -> Unit)? by remember { mutableStateOf(null) }
    Box(modifier.size(40.dp).clip(CircleShape).background(if (talking) Red else Color(0x99000000)).pointerInput(hub) {
        detectTapGestures(onPress = {
            if (!allowed()) { ask.launch(Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
            talking = true
            val wasListening = live.listen
            live.listen = false   // sem eco: o som do quarto volta pelo microfone
            var running = true
            val worker = thread {
                val size = maxOf(AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 1280)
                val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                runCatching {
                    // Without the AI the phone talks to the camera itself (the computer may be off)
                    val direct = CameraLink.current()?.takeIf { CameraLink.direct && it.access != null }
                    if (direct != null) {
                        rec.startRecording()
                        direct.talk({ b, o, n -> rec.read(b, o, n) }, { running })
                        return@runCatching
                    }
                    val c = URL(hub.talkUrl).openConnection() as HttpURLConnection
                    c.requestMethod = "POST"; c.doOutput = true; c.setChunkedStreamingMode(640)
                    c.setRequestProperty("content-type", "application/octet-stream")
                    rec.startRecording()
                    c.outputStream.use { out ->
                        val buf = ByteArray(640)
                        while (running) { val n = rec.read(buf, 0, buf.size); if (n > 0) out.write(buf, 0, n) }
                    }
                    if (c.responseCode >= 400) throw Exception(JSONObject(c.errorStream.bufferedReader().readText()).optString("error", "não deu"))
                }.onFailure { onNote("Não deu para falar: ${it.message}") }
                runCatching { rec.stop() }; rec.release()
            }
            stop = { running = false }
            tryAwaitRelease()
            running = false
            talking = false
            worker.join(300)
            live.listen = wasListening
        })
    }, contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Mic, if (talking) "Falando" else "Segure para falar", tint = Color.White)
    }
}

// MARK: - A câmera: o que ela faz sozinha

/** In the camera page: its own person detection and follow, the SD card, the clock, restart and password. */
@Composable
fun CameraOwnSection(hub: Hub) {
    val scope = rememberCoroutineScope()
    var own by remember { mutableStateOf<JSONObject?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { own = withContext(Dispatchers.IO) { runCatching { hub.cameraOwn() }.getOrNull() } }
    val run = { what: String, call: () -> JSONObject ->
        note = "$what…"
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(call) }.onSuccess {
                if (it.optBoolean("ok")) { note = "$what: pronto."; if (it.has("person") || it.has("track")) own = it }
                else note = "Não deu: ${it.optString("error")}"
            }.onFailure { note = "Não deu: ${it.message}" }
        }
    }
    Text("A própria câmera", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
    Text("Funciona sem a IA e não pesa na conexão.", color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
    if (own == null) Loading()
    Column(Modifier.fillMaxWidth().card(palette)) {
        own?.let { o ->
            if (o.has("person")) OwnSwitch(Icons.Outlined.PersonSearch, "Detectar pessoa (da câmera)",
                "A câmera mesma avisa quando vê uma pessoa: vira atividade e aviso da Proteção, mesmo sem a IA", o.optBoolean("person")) {
                run("Detecção de pessoa") { hub.setCameraOwn(JSONObject().put("person", it)) }
            }
            if (o.has("track")) OwnSwitch(Icons.Outlined.CenterFocusStrong, "Seguir pessoa (da câmera)",
                "O seguir de fábrica: a câmera gira sozinha atrás de quem aparece", o.optBoolean("track")) {
                run("Seguir da câmera") { hub.setCameraOwn(JSONObject().put("track", it)) }
            }
            ListRow(Icons.Outlined.SdCard, "Cartão de memória", if (o.isNull("sd") || !o.has("sd")) "Sem cartão: a câmera não grava sozinha"
                else "${o.optJSONObject("sd")?.optInt("mb")} MB")
        }
        ListRow(Icons.Outlined.AccessTime, "Acertar o relógio", "A hora da câmera igual à do computador", onClick = { run("Relógio") { hub.syncClock() } })
        ListRow(Icons.Outlined.Key, "Trocar a senha da câmera", "Só aqui: o app e as TVs continuam funcionando", onClick = { ask = "password" })
        ListRow(Icons.Outlined.RestartAlt, "Reiniciar a câmera", "Fica fora do ar por mais ou menos 1 minuto", onClick = { ask = "reboot" })
    }
    note?.let { Text(it, color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }

    if (ask == "reboot") AlertDialog(onDismissRequest = { ask = null }, title = { Text("Reiniciar a câmera?") },
        text = { Text("Ela fica fora do ar por mais ou menos 1 minuto e volta sozinha.") },
        confirmButton = { Button(onClick = { ask = null; run("Reiniciando") { hub.reboot() } }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Reiniciar") } },
        dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancelar", color = palette.muted) } })
    if (ask == "password") AlertDialog(onDismissRequest = { ask = null }, title = { Text("Senha nova da câmera") },
        text = {
            Column {
                Text("De 6 a 15 caracteres. O app troca na câmera e já passa a usar a nova; se algo der errado, volta a antiga.", fontSize = 13.sp)
                OutlinedTextField(password, { password = it.take(15) }, singleLine = true, label = { Text("Senha nova") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue))
                OutlinedTextField(again, { again = it.take(15) }, singleLine = true, label = { Text("Repita a senha") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue))
                if (again.isNotEmpty() && again != password) Text("As duas não são iguais.", color = Red, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = { ask = null; val pw = password; password = ""; again = ""; run("Senha da câmera") { hub.setCameraPassword(pw) } },
                enabled = password.length >= 6 && password == again, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Trocar") }
        },
        dismissButton = { TextButton(onClick = { ask = null; password = ""; again = "" }) { Text("Cancelar", color = palette.muted) } })
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun OwnSwitch(icon: ImageVector, title: String, subtitle: String, on: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(icon, title, subtitle, onClick = { onChange(!on) }, trailing = {
        Switch(on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Blue))
    })
}
