package io.nevext.aisaferoom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// MARK: - Pessoas conhecidas

@Composable
fun PeopleScreen(live: Live, onBack: () -> Unit, onEnroll: () -> Unit) {
    val hub = live.hub ?: return
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(Kept.get<JSONObject>("faces")) }
    var removing by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { hub.faces() }.getOrNull() }?.let { info = it; Kept["faces"] = it } }
    val people = info?.optJSONArray("people")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
    Page {
        TopBar("", null, onBack)
        PageTitle("Pessoas conhecidas", "Quem a câmera reconhece pelo rosto")
        if (info?.optBoolean("available") == false) {
            Text("O computador ainda não tem os modelos de rosto (server\\get-faces.ps1).", color = palette.muted, fontSize = 13.sp)
        }
        Column(Modifier.fillMaxWidth().card(palette)) {
            if (info == null) Loading() else if (people.isEmpty()) Text("Ninguém cadastrado ainda. Com as pessoas da casa cadastradas, as atividades mostram o nome de quem chegou, e a Proteção pode avisar só de desconhecidos.",
                color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            people.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val pic = p.optString("avatar").takeIf { it.isNotEmpty() && it != "null" }?.let { rememberPicture(hub, hub.base + it) }
                    Box(Modifier.size(46.dp).clip(CircleShape).background(palette.soft), contentAlignment = Alignment.Center) {
                        if (pic != null) Image(pic, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Outlined.Face, null, tint = Blue)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(p.optString("name"), fontWeight = FontWeight.SemiBold)
                        Text("${p.optInt("samples")} fotos do rosto" + (p.optLong("lastSeen").takeIf { it > 0 }?.let { " · visto ${whenText(it)}" } ?: ""),
                            color = palette.muted, fontSize = 12.sp)
                    }
                    Icon(Icons.Outlined.Delete, "Apagar", tint = palette.muted, modifier = Modifier.clip(CircleShape).clickable { removing = p }.padding(8.dp))
                }
            }
        }
        Button(onClick = onEnroll, enabled = info?.optBoolean("available") == true, colors = ButtonDefaults.buttonColors(containerColor = Blue),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(48.dp)) {
            Icon(Icons.Outlined.PersonAdd, null)
            Spacer(Modifier.width(8.dp))
            Text("Cadastrar pessoa")
        }
        Text("Os rostos ficam só no computador de casa. Cada pessoa precisa concordar, e dá para apagar quando quiser.",
            color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(24.dp))
    }
    removing?.let { p ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text("Apagar ${p.optString("name")}?") },
            text = { Text("O rosto sai do computador. As atividades antigas continuam com o nome.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { hub.removeFace(p.optString("id")) } }.onSuccess { info = it }
                        removing = null
                    }
                }) { Text("Apagar", color = Red) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } })
    }
}

// MARK: - Cadastro guiado (como o Face ID: passos curtos, a pessoa vê o rosto marcado e o progresso)

private val STEP_NAMES = listOf("front" to "De frente", "left" to "Esquerda", "right" to "Direita", "up" to "Queixo")

@Composable
fun EnrollScreen(live: Live, visible: Boolean, onBack: () -> Unit) {
    val hub = live.hub ?: return
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf("intro") }   // intro → capture → done
    var name by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<JSONObject?>(null) }

    // Saiu no meio: o cadastro para no computador
    val cancel = {
        if (stage == "capture") scope.launch { withContext(Dispatchers.IO) { runCatching { hub.cancelEnroll() } } }
        onBack()
    }
    androidx.activity.compose.BackHandler(onBack = cancel)

    when (stage) {
        "intro" -> Page {
            TopBar("", null, cancel)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(84.dp).background(palette.soft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Face, null, tint = Blue, modifier = Modifier.size(44.dp))
                }
                Text("Cadastrar uma pessoa", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                Text("Leva menos de 1 minuto. A câmera vai pedir para olhar de frente e virar o rosto devagar.",
                    color = palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
            Column(Modifier.fillMaxWidth().padding(top = 18.dp).card(palette).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Fique a uns 1 a 2 metros, de frente para a câmera",
                    "Com o rosto iluminado (luz acesa, sem contraluz)",
                    "Sem boné, óculos escuros ou máscara",
                    "Só a pessoa que está cadastrando na frente da câmera").forEachIndexed { i, t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            modifier = Modifier.size(22.dp).background(Blue, CircleShape).padding(top = 2.dp))
                        Text(t, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nome da pessoa") },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { consent = !consent }, verticalAlignment = Alignment.Top) {
                Checkbox(consent, { consent = it }, colors = CheckboxDefaults.colors(checkedColor = Blue))
                Text("Esta pessoa concorda em ter o rosto guardado no computador de casa para ser reconhecida pela câmera. Dá para apagar a qualquer hora.",
                    fontSize = 13.sp, color = palette.muted, modifier = Modifier.padding(top = 12.dp))
            }
            error?.let { Text(it, color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            Button(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { hub.startEnroll(name.trim(), consent) } }
                        .onSuccess { r -> if (r.optBoolean("ok")) { status = r; stage = "capture" } else error = r.optString("error") }
                        .onFailure { error = it.message }
                }
            }, enabled = name.isNotBlank() && consent, colors = ButtonDefaults.buttonColors(containerColor = Blue),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp)) { Text("Começar") }
            Spacer(Modifier.height(24.dp))
        }

        "capture" -> {
            // O que o computador está vendo, a cada 0,7 s: o rosto, o passo, a dica, quanto falta
            LaunchedEffect(Unit) {
                while (true) {
                    val s = withContext(Dispatchers.IO) { runCatching { hub.enrollStatus() }.getOrNull() }
                    if (s != null) {
                        status = s
                        if (s.optBoolean("done") || (!s.optBoolean("active") && !s.optBoolean("done"))) { stage = "done"; break }
                    }
                    delay(700)
                }
            }
            val s = status
            val progress = s?.optDouble("progress", 0.0)?.toFloat() ?: 0f
            val face = s?.optJSONArray("face")
            Page {
                TopBar("Cadastrando ${name.trim()}", null, cancel)
                Box(Modifier.fillMaxWidth().aspectRatio(2048f / 1366f).clip(RoundedCornerShape(18.dp))) {
                    EnrollVideo(live, visible)
                    if (face != null) Canvas(Modifier.fillMaxSize()) {
                        val pad = 0.25f
                        val w = face.optDouble(2).toFloat(); val h = face.optDouble(3).toFloat()
                        drawRoundRect(Green, Offset((face.optDouble(0).toFloat() - w * pad) * size.width, (face.optDouble(1).toFloat() - h * pad) * size.height),
                            Size(w * (1 + 2 * pad) * size.width, h * (1 + 2 * pad) * size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f), style = Stroke(3.dp.toPx()))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(72.dp), color = Blue, trackColor = palette.line, strokeWidth = 7.dp)
                        Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(s?.optString("prompt") ?: "Olhe para a câmera", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(s?.optString("hint") ?: "", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                // Os passos: o feito fica azul
                val step = s?.optString("step")
                val at = STEP_NAMES.indexOfFirst { it.first == step }.let { if (it < 0) STEP_NAMES.size else it }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    STEP_NAMES.forEachIndexed { i, (_, label) ->
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                            color = if (i <= at) Color.White else palette.muted,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (i < at) Blue else if (i == at) Blue.copy(alpha = 0.7f) else palette.card)
                                .padding(vertical = 7.dp))
                    }
                }
                if (s?.optString("hint")?.contains("escuro") == true) {
                    OutlinedButton(onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { hub.control("light", true) } } } },
                        modifier = Modifier.padding(top = 14.dp)) {
                        Icon(Icons.Outlined.Lightbulb, null, tint = Blue)
                        Spacer(Modifier.width(6.dp))
                        Text("Acender a luz branca da câmera", color = Blue)
                    }
                }
                TextButton(onClick = cancel, modifier = Modifier.padding(top = 8.dp)) { Text("Cancelar", color = palette.muted) }
                Spacer(Modifier.height(24.dp))
            }
        }

        else -> Page {
            TopBar("", null, onBack)
            val ok = status?.optBoolean("done") == true && status?.optJSONObject("person") != null
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).background(if (ok) Color(0x2222C55E) else palette.line, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Face, null, tint = if (ok) Green else palette.muted, modifier = Modifier.size(52.dp))
                }
                Text(if (ok) "Pronto!" else "Não terminou", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                Text(if (ok) "${name.trim()} foi cadastrado. A partir de agora as visitas mostram o nome quando a câmera vir o rosto."
                    else status?.optString("hint")?.ifEmpty { null } ?: "O cadastro foi interrompido. Tente de novo.",
                    color = palette.muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                Text("Ele melhora com o tempo: quando a câmera errar ou não souber, confirme nas atividades.",
                    color = palette.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            }
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Blue),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(48.dp)) { Text("Concluir") }
        }
    }
}

/** The whole picture for the enrollment (the face box comes for the whole frame). */
@Composable
private fun EnrollVideo(live: Live, visible: Boolean) {
    val hub = live.hub ?: return
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    DisposableEffect(hub, visible) {
        if (!visible) return@DisposableEffect onDispose {}
        val video = Mjpeg(hub.videoUrl("whole", 1280, 854), onFrame = { jpg ->
            android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.size)?.let { frame = it.asImageBitmap() }
        }, onError = {})
        video.start()
        onDispose { video.stop() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0F17)), contentAlignment = Alignment.Center) {
        frame?.let { Image(it, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            ?: CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
    }
}

// MARK: - "Quem é?" (nas atividades)

/**
 * The unknown faces the camera saw in a visit: tap one and say who it is. The app learns it (more photos for that
 * person, in other lights and angles) and gives the name to other visits with that face too.
 */
@Composable
fun WhoIsFaces(hub: Hub, a: JSONObject, onChanged: (JSONObject) -> Unit) {
    val unknown = a.optJSONArray("faces") ?: return
    if (unknown.length() == 0) return
    var asking by remember { mutableStateOf<JSONObject?>(null) }
    Text("Quem é?", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 14.dp))
    Text("Toque no rosto e diga quem é: o app aprende a reconhecer.", color = palette.muted, fontSize = 12.sp)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until unknown.length()) {
            val f = unknown.optJSONObject(i) ?: continue
            val pic = rememberPicture(hub, hub.thumbUrl(f.optString("img")))
            Box(Modifier.size(58.dp).clip(CircleShape).background(palette.soft).clickable { asking = f }, contentAlignment = Alignment.Center) {
                if (pic != null) Image(pic, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Outlined.Face, null, tint = Blue)
            }
        }
    }
    asking?.let { f -> WhoIsDialog(hub, a, f, onClose = { asking = null }, onDone = { asking = null; onChanged(it) }) }
}

@Composable
private fun WhoIsDialog(hub: Hub, a: JSONObject, face: JSONObject, onClose: () -> Unit, onDone: (JSONObject) -> Unit) {
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var newName by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { hub.faces() } }.onSuccess { info ->
            val list = info.optJSONArray("people")
            people = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }
        }
    }
    val answer = { call: () -> JSONObject ->
        busy = true; note = null
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { call() } }
                .onSuccess(onDone).onFailure { note = it.message; busy = false }
        }
    }
    val at = a.optLong("at")
    val i = face.optInt("i")
    AlertDialog(onDismissRequest = onClose, confirmButton = {}, dismissButton = {
        TextButton(onClick = onClose) { Text("Fechar", color = palette.muted) }
    }, title = { Text("Quem é?") }, text = {
        Column {
            val pic = rememberPicture(hub, hub.thumbUrl(face.optString("img")))
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(palette.soft).align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center) {
                if (pic != null) Image(pic, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            people.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(palette.soft)
                    .clickable(enabled = !busy) { answer { hub.whoIs(at, i, person = p.optString("id")) } }
                    .padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val avatar = p.optString("avatar").takeIf { it.isNotEmpty() && it != "null" }?.let { rememberPicture(hub, hub.base + it) }
                    Box(Modifier.size(32.dp).clip(CircleShape).background(palette.card), contentAlignment = Alignment.Center) {
                        if (avatar != null) Image(avatar, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Outlined.Face, null, tint = Blue, modifier = Modifier.size(18.dp))
                    }
                    Text(p.optString("name"), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
                }
            }
            Text("Outra pessoa", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
            OutlinedTextField(newName, { newName = it.take(40) }, singleLine = true, label = { Text("Nome") },
                modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue))
            Row(Modifier.clickable { consent = !consent }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(consent, { consent = it }, colors = CheckboxDefaults.colors(checkedColor = Blue))
                Text("A pessoa concorda em ter o rosto reconhecido", fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { answer { hub.whoIs(at, i, nobody = true) } }, enabled = !busy) { Text("Não conheço", color = Red) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { answer { hub.whoIs(at, i, name = newName.trim(), consent = true) } },
                    enabled = !busy && consent && newName.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Cadastrar") }
            }
            note?.let { Text("Não deu: $it", color = Red, fontSize = 12.sp) }
        }
    })
}
