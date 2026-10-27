package io.nevext.aisaferoom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Ajustes → Armazenamento: the pictures and clips stay 30 days on the home computer and, if the owner connects it,
 * also in their Google Drive. Connecting is the Google "sign in with a code": no browser on the computer.
 */
@Composable
fun StorageScreen(live: Live, onBack: () -> Unit) {
    val hub = live.hub ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(Kept.get<JSONObject>("drive")) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // While a code is waiting (or something is being sent), ask again every few seconds
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { hub.drive() }.getOrNull() }?.let { info = it; Kept["drive"] = it }
            delay(if (info?.optJSONObject("pending") != null) 3000 else 15000)
        }
    }
    val change = { body: JSONObject ->
        busy = true; note = null
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { hub.setDrive(body) } }
                .onSuccess { info = it; Kept["drive"] = it; if (it.has("error") && !it.isNull("error") && it.optBoolean("ok", true).not()) note = it.optString("error") }
                .onFailure { note = it.message }
            busy = false
        }
    }
    Page {
        TopBar("", null, onBack)
        PageTitle("Armazenamento", "Onde ficam as fotos e os clipes")
        Row(Modifier.fillMaxWidth().card(palette).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.Computer)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("No computador de casa", fontWeight = FontWeight.SemiBold)
                Text("As fotos das atividades e os clipes ficam 30 dias e depois saem sozinhos.", color = palette.muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        val i = info
        Column(Modifier.fillMaxWidth().card(palette, selected = i?.optBoolean("connected") == true).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Outlined.Cloud)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Google Drive", fontWeight = FontWeight.SemiBold)
                    Text(when {
                        i == null -> "Vendo…"
                        i.optBoolean("connected") -> i.optString("email").takeIf { it.isNotEmpty() && it != "null" } ?: "Conectado"
                        else -> "Uma cópia no seu Drive, para não perder nada se o computador quebrar ou for levado"
                    }, color = palette.muted, fontSize = 13.sp)
                }
                if (i?.optBoolean("connected") == true) Switch(i.optBoolean("enabled"), enabled = live.owner && !busy,
                    onCheckedChange = { change(JSONObject().put("enabled", it)) }, colors = SwitchDefaults.colors(checkedTrackColor = Blue))
            }
            when {
                i == null -> Loading()
                !i.optBoolean("configured") -> Text("Ainda não dá: falta ligar o AI Safe Room ao Google neste computador (uma vez só, feito por quem instalou).",
                    color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                i.optJSONObject("pending") != null -> {
                    // The code to type in google.com/device, big, with a button that opens the page
                    val p = i.optJSONObject("pending")!!
                    val code = p.optString("userCode"); val url = p.optString("url")
                    Text("1. Toque em Abrir o Google e entre com a sua conta.\n2. Digite este código:", fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    Text(code, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("código", code)); note = "Código copiado."
                        })
                    Button(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("código", code))
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Blue), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Abrir o Google (o código já vai copiado)") }
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Blue, strokeWidth = 2.dp)
                        Text("Esperando você entrar no Google…", fontSize = 13.sp, color = palette.muted, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                !i.optBoolean("connected") -> {
                    Text("Vai para uma pasta \"AI Safe Room\" no seu Drive, separada por câmera e por dia. O app só enxerga o que ele mesmo enviou: o resto do seu Drive fica fechado para ele.",
                        color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    if (live.owner) Button(enabled = !busy, onClick = { change(JSONObject().put("connect", true)) }, colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
                        else Text("Conectar o Google Drive")
                    } else Text("Só os donos conectam o Drive.", color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                else -> {
                    Text(if (i.optBoolean("enabled")) "${i.optInt("uploaded")} arquivos no Drive · ficam ${i.optInt("keepDays", 30)} dias${if (i.optBoolean("busy")) " · enviando…" else ""}"
                        else "Pausado: nada novo vai para o Drive.", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                    // A shortcut to the folder in the Drive (the pictures and clips, by camera and day)
                    i.optString("folderUrl").takeIf { it.startsWith("https://") }?.let { url ->
                        Button(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                            colors = ButtonDefaults.buttonColors(containerColor = Blue), modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(48.dp)) {
                            Text("Abrir as fotos e os clipes no Drive")
                        }
                    }
                    if (live.owner) TextButton(onClick = { change(JSONObject().put("disconnect", true)) }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Desconectar (o que já foi fica no Drive)", color = palette.muted, fontSize = 13.sp)
                    }
                }
            }
            val err = i?.optString("error")?.takeIf { it.isNotEmpty() && it != "null" }
            (note ?: err)?.let { Text(it, color = Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}
