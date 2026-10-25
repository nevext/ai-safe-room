package io.nevext.cameraai

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

private val Blue = Color(0xFF2563EB)
private val Card = Color(0xFF16191D)
private val Muted = Color(0xFF9AA0A6)
private val Ok = Color(0xFF22C55E)

/** Where the app is: finding camera-ai, getting let in, the camera, or what the camera does. */
private sealed interface Screen {
    data object Find : Screen
    data class LetIn(val root: String) : Screen
    data object Camera : Screen
    data object Features : Screen
}

class MainActivity : ComponentActivity() {
    /** Only while the app is on screen: the picture and the AI stop when it isn't. */
    private val visible = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("camera-ai", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, onPrimary = Color.White, background = Color(0xFF0D0F12),
                onBackground = Color(0xFFE8EAED), surface = Card, onSurface = Color(0xFFE8EAED))) {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    App(prefs, visible.value)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible.value = true
    }

    override fun onStop() {
        visible.value = false
        super.onStop()
    }
}

@Composable
private fun App(prefs: android.content.SharedPreferences, visible: Boolean) {
    var hub by remember {
        mutableStateOf(prefs.getString("root", null)?.let { Hub(it, prefs.getString("key", null)) })
    }
    var screen by remember { mutableStateOf<Screen>(if (hub == null) Screen.Find else Screen.Camera) }
    val remember = { h: Hub ->
        prefs.edit().putString("root", h.root).putString("key", h.key).apply()
        hub = h
        screen = Screen.Camera
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        when (val s = screen) {
            Screen.Find -> FindScreen(onFound = { root, open -> if (open) remember(Hub(root, null)) else screen = Screen.LetIn(root) })
            is Screen.LetIn -> LetInScreen(s.root, onKey = { remember(Hub(s.root, it)) }, onBack = { screen = Screen.Find })
            Screen.Camera -> hub?.let { h ->
                CameraScreen(h, visible,
                    onLocked = { screen = Screen.LetIn(h.root) },
                    onFeatures = { screen = Screen.Features },
                    onForget = {
                        prefs.edit().clear().apply()
                        hub = null
                        screen = Screen.Find
                    })
            }
            Screen.Features -> hub?.let { h ->
                BackHandler { screen = Screen.Camera }
                FeaturesScreen(h, onBack = { screen = Screen.Camera })
            }
        }
    }
}

// MARK: - Finding camera-ai

@Composable
private fun FindScreen(onFound: (root: String, open: Boolean) -> Unit) {
    var searching by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Tries camera-ai at this address: straight in (this computer, or no password), or it asks to be let in
    val tryRoot = { root: String ->
        scope.launch {
            error = null
            val result = withContext(Dispatchers.IO) { runCatching { Hub(root, null).screen() } }
            result.onSuccess { onFound(root, true) }.onFailure {
                if (it is Locked) onFound(root, false) else error = "Não achei o camera-ai em $root"
            }
        }
    }
    LaunchedEffect(searching) {
        if (!searching) return@LaunchedEffect
        found = withContext(Dispatchers.IO) { runCatching { Hub.discover() }.getOrDefault(emptyList()) }
        searching = false
        if (found.size == 1) tryRoot(found[0].root)
    }

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("camera-ai", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("O app fala com o camera-ai, o programa no computador de casa que cuida da câmera. O celular precisa estar no mesmo Wi-Fi.",
            color = Muted)
        if (searching) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Procurando na rede…")
            }
        } else {
            found.forEach { f ->
                Box(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).clickable { tryRoot(f.root) }.padding(16.dp)) {
                    Column {
                        Text("camera-ai", fontWeight = FontWeight.SemiBold)
                        Text(f.root.removePrefix("http://"), color = Muted, fontSize = 14.sp)
                    }
                }
            }
            if (found.isEmpty()) Text("Não achei sozinho. Digite o endereço que o camera-ai mostra no computador.", color = Muted)
            OutlinedButton(onClick = { searching = true }) { Text("Procurar de novo") }
        }
        OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Endereço (ex.: 192.168.0.34:8787)") })
        Button(onClick = {
            val a = address.trim().removePrefix("http://").trimEnd('/')
            if (a.isNotEmpty()) tryRoot("http://" + if (':' in a) a else "$a:8787")
        }) { Text("Conectar") }
        error?.let { Text(it, color = Color(0xFFFF8A80)) }
    }
}

// MARK: - Getting let in: Allow on camera-ai, or its password

@Composable
private fun LetInScreen(root: String, onKey: (String) -> Unit, onBack: () -> Unit) {
    var status by remember { mutableStateOf("Pedindo acesso…") }
    var password by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    // The request waits until someone already in taps Allow on camera-ai (the computer, or another phone)
    LaunchedEffect(attempt) {
        val result = withContext(Dispatchers.IO) {
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
        }
        result.onSuccess { key -> if (key != null) onKey(key) else status = "O pedido expirou." }
            .onFailure { status = "camera-ai: ${it.message}" }
    }

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Liberar este celular", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text("Abra o camera-ai no computador (${root.removePrefix("http://")}) e toque em Permitir.", color = Muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(status)
        }
        TextButton(onClick = { status = "Pedindo acesso…"; attempt++ }) { Text("Pedir de novo") }
        Spacer(Modifier.height(8.dp))
        Text("Ou com a senha do camera-ai", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Senha") })
        Button(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { Hub.login(root, password) } }
                    .onSuccess(onKey).onFailure { status = it.message ?: "não deu" }
            }
        }) { Text("Entrar") }
        TextButton(onClick = onBack) { Text("Voltar") }
    }
}

// MARK: - The camera

/** One AI detection: where (0–1 of the whole frame) and what. */
private data class Box4(val x: Float, val y: Float, val w: Float, val h: Float, val kind: String, val uncertain: Boolean)

@Composable
private fun CameraScreen(hub: Hub, visible: Boolean, onLocked: () -> Unit, onFeatures: () -> Unit, onForget: () -> Unit) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var boxes by remember { mutableStateOf<List<Box4>>(emptyList()) }
    var moving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(JSONArray()) }
    var name by remember { mutableStateOf("Câmera") }
    val scope = rememberCoroutineScope()

    // What the camera offers (and whether this phone is still let in)
    LaunchedEffect(hub) {
        withContext(Dispatchers.IO) { runCatching { hub.screen() } }
            .onSuccess {
                controls = it.optJSONArray("controls") ?: JSONArray()
                name = it.optJSONObject("camera")?.optString("name")?.ifEmpty { null } ?: "Câmera"
            }
            .onFailure { if (it is Locked) onLocked() else problem = "camera-ai não respondeu" }
    }

    // The picture and the AI only while the app is on screen
    DisposableEffect(hub, visible) {
        if (!visible) return@DisposableEffect onDispose {}
        val video = Mjpeg(hub.videoUrl(1280, 960), onFrame = { jpg ->
            BitmapFactory.decodeByteArray(jpg, 0, jpg.size)?.let { bmp -> frame = bmp.asImageBitmap(); problem = null }
        }, onError = { problem = if (it.contains("acesso")) "Este celular perdeu o acesso" else "Sem imagem: $it" })
        video.start()
        var running = true
        thread(name = "events", isDaemon = true) {
            while (running) {
                runCatching {
                    hub.events({ e ->
                        when (e.optString("type")) {
                            "detections" -> boxes = parseBoxes(e.optJSONArray("items"))
                            "motion" -> moving = e.optBoolean("active")
                        }
                    }, { running })
                }
                if (running) Thread.sleep(2000)
            }
        }
        onDispose {
            running = false
            video.stop()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (moving) Text("● movimento", color = Color(0xFFFFB020), fontSize = 13.sp)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(frame?.let { it.width.toFloat() / it.height } ?: 1.5f).background(Color.Black)) {
            frame?.let { img ->
                Image(img, contentDescription = "Câmera ao vivo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    for (b in boxes) {
                        val color = when {
                            b.uncertain -> Color(0xFFEF4444)
                            b.kind == "person" -> Ok
                            else -> Color(0xFFF59E0B)
                        }
                        drawRect(color, Offset(b.x * size.width, b.y * size.height), Size(b.w * size.width, b.h * size.height), style = Stroke(3.dp.toPx()))
                    }
                }
            } ?: Text(problem ?: "Abrindo a câmera…", color = Muted, modifier = Modifier.align(Alignment.Center).padding(16.dp))
        }
        problem?.takeIf { frame != null }?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 8.dp)) }

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in 0 until controls.length()) {
                val c = controls.optJSONObject(i) ?: continue
                if (c.optString("type") != "toggle") continue
                ToggleRow(c.optJSONObject("label")?.optString("pt") ?: c.optString("id"), c.optBoolean("value")) { on ->
                    c.put("value", on)
                    controls = JSONArray(controls.toString())
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { hub.control(c.optString("id"), on) } }
                            .onSuccess { controls = it }
                            .onFailure { problem = "Não deu para mudar: ${it.message}" }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).clickable(onClick = onFeatures).padding(16.dp)) {
                Column {
                    Text("O que a câmera faz", fontWeight = FontWeight.SemiBold)
                    Text("Cada recurso explicado, se funciona e onde usar", color = Muted, fontSize = 14.sp)
                }
            }
            TextButton(onClick = onForget) { Text("Trocar de camera-ai", color = Muted) }
        }
    }
}

private fun parseBoxes(items: JSONArray?): List<Box4> {
    if (items == null) return emptyList()
    return (0 until items.length()).mapNotNull { i ->
        val it = items.optJSONObject(i) ?: return@mapNotNull null
        val b = it.optJSONArray("box") ?: return@mapNotNull null
        Box4(b.optDouble(0).toFloat(), b.optDouble(1).toFloat(), b.optDouble(2).toFloat(), b.optDouble(3).toFloat(),
            it.optString("kind"), it.optBoolean("uncertain"))
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).clickable { onChange(!on) }.padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Blue))
    }
}

// MARK: - What the camera does (its profile)

private val STATES = mapOf(
    "ok" to "Funciona", "no" to "Não tem", "declared" to "A câmera diz que tem",
    "unverified" to "Ainda não deu para conferir", "model" to "Outra câmera igual tem",
)

@Composable
private fun FeaturesScreen(hub: Hub, onBack: () -> Unit) {
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var checking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) {
        profile = withContext(Dispatchers.IO) { runCatching { hub.profile() }.getOrNull() }
    }
    val caps = profile?.optJSONArray("caps")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        .sortedBy { if (it.optString("status") == "no") 1 else 0 }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Voltar") }
            }
            Text("O que a câmera faz", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            val works = caps.count { it.optString("status") == "ok" }
            Text("${profile?.optString("model") ?: ""} · $works recursos conferidos", color = Muted, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
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
            note?.let { Text(it, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
        items(caps) { c ->
            val status = c.optString("status")
            Column(Modifier.fillMaxWidth().border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp)).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.optJSONObject("label")?.optString("pt") ?: c.optString("id"), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f), color = if (status == "no") Muted else Color.White)
                    Text(STATES[status] ?: status, fontSize = 12.sp, color = if (status == "ok") Ok else Muted,
                        modifier = Modifier.background(if (status == "ok") Color(0x2E22C55E) else Color(0x22FFFFFF), RoundedCornerShape(50))
                            .padding(8.dp, 2.dp))
                }
                c.optString("desc").takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                val use = if (c.isNull("use")) null else c.optString("use")
                Text(if (use != null) "Onde usar: $use" else "Ainda não dá para usar no camera-ai.", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
