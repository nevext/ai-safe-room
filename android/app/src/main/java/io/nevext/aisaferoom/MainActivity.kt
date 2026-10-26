package io.nevext.aisaferoom

import androidx.compose.animation.togetherWith
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** Where the app is. Tabs are the four of the bottom bar; the rest open over them. */
sealed interface Screen {
    data object Splash : Screen
    data object Find : Screen
    data class LetIn(val root: String) : Screen
    data object Home : Screen
    data object Cameras : Screen
    data object Activity : Screen
    data object Settings : Screen
    data object Camera : Screen
    data object Features : Screen
    data object System : Screen
    data object Appearance : Screen
    data object Privacy : Screen
    data object Protection : Screen
    data object Family : Screen
    data object Zones : Screen
    data object People : Screen
    data object Enroll : Screen
    data object Ai : Screen
    data object Diagnostics : Screen
    data object Summaries : Screen
    data object Notifications : Screen
    data object About : Screen
    data object Terms : Screen
    data object Placement : Screen
    data class Soon(val title: String) : Screen
}

/** One AI detection: where (0–1 of the whole frame) and what. */
data class Detection(val x: Float, val y: Float, val w: Float, val h: Float, val kind: String, val uncertain: Boolean)

/** Everything the screens show, kept up to date by App. */
class Live {
    var hub by mutableStateOf<Hub?>(null)
    var info by mutableStateOf<JSONObject?>(null)          // /api/screen
    var connected by mutableStateOf(false)
    var controls by mutableStateOf(JSONArray())
    var activity by mutableStateOf(JSONArray())
    var detections by mutableStateOf<List<Detection>>(emptyList())
    var moving by mutableStateOf(false)
    var problem by mutableStateOf<String?>(null)

    val cameraName: String get() = info?.optJSONObject("camera")?.optString("name")?.ifEmpty { null } ?: "Câmera"
    /** "XiongMai Dual Lens" from what camera-ai calls the camera. */
    val cameraKind: String get() = info?.optJSONObject("camera")?.optString("kind")?.substringBefore(" (")?.ifEmpty { null } ?: "Câmera"
    val twoLenses: Boolean get() = info?.optJSONObject("lenses")?.optInt("count") == 2
    val protectionOn: Boolean get() = info?.optJSONObject("protection")?.optBoolean("on") == true
    val privacyOn: Boolean get() = info?.optJSONObject("privacy")?.optBoolean("on") == true
    /** The AI is the person's choice (off by default): off, the video comes straight from the camera. */
    val aiOn: Boolean get() = info?.optJSONObject("ai")?.optBoolean("enabled") == true
    /** Away from home (using the address set in Conexão): the camera isn't reachable direct, the video comes via the computer. */
    var away by mutableStateOf(false)
    /** Hearing the room (the speaker on the video). */
    var listen by mutableStateOf(false)
    val hasPtz: Boolean get() = info?.optBoolean("ptz") == true
    /** The camera detects people by itself: the Protection works without the AI. */
    val cameraPerson: Boolean get() = info?.optBoolean("cameraPerson") == true
    var seenAt by mutableStateOf(0L)   // activities after this are new (the number on the Atividades tab)
    val unseen: Int get() = (0 until activity.length()).mapNotNull { activity.optJSONObject(it) }
        .count { it.optString("type") in setOf("person", "animal") && it.optLong("at") > seenAt }
}

class MainActivity : ComponentActivity() {
    /** Only while the app is on screen: the picture and the AI stop when it isn't. */
    private val visible = mutableStateOf(false)
    /** A notification asked for a screen ("activity"). */
    private val asked = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        asked.value = intent.getStringExtra("open")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("aisaferoom", Context.MODE_PRIVATE)
        prefs.sealOld("key", "camera", "directStream")   // secrets saved before they were encrypted
        CameraLink.get(this)   // the camera straight from the phone (without the computer)
        asked.value = intent?.getStringExtra("open")
        // The warnings keep going with the app closed, if they were on
        if (prefs.getBoolean("notify", false)) runCatching { Guard.start(this) }
        setContent {
            var theme by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
            val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            SafeRoomTheme(dark) {
                Surface(Modifier.fillMaxSize(), color = palette.background, contentColor = palette.text) {
                    App(prefs, visible.value, theme, asked) { theme = it; prefs.edit().putString("theme", it).apply() }
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
        SharedVideo.reset()   // out of the app: the camera's connection closes now
        super.onStop()
    }
}

@Composable
private fun App(prefs: SharedPreferences, visible: Boolean, theme: String, asked: androidx.compose.runtime.MutableState<String?>,
                setTheme: (String) -> Unit) {
    val live = remember {
        Live().apply {
            hub = prefs.getString("root", null)?.let { Hub(it, prefs.getSecret("key")) }
            seenAt = prefs.getLong("seenAt", 0)
        }
    }
    var screen by remember { mutableStateOf<Screen>(if (live.hub == null) Screen.Find else Screen.Splash) }
    var tab by remember { mutableStateOf<Screen>(Screen.Home) }
    var fullscreen by remember { mutableStateOf<String?>(null) }   // the view shown full screen, if any
    var previous by remember { mutableStateOf<Screen>(Screen.Home) }   // where back goes from a screen opened over another
    val activity = LocalContext.current as Activity

    val keep = { h: Hub ->
        prefs.edit().putString("root", h.root).putSecret("key", h.key).apply()
        live.hub = h
        screen = Screen.Splash
    }
    val forget = {
        prefs.edit().remove("root").remove("key").remove("remote").apply()
        Guard.stop(activity)
        live.hub = null
        live.info = null
        live.connected = false
        screen = Screen.Find
    }

    // What the camera offers, and whether this phone is still let in: on opening, then every 30 s
    LaunchedEffect(live.hub, visible) {
        val h = live.hub ?: return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        var first = true
        while (true) {
            val r = withContext(Dispatchers.IO) { runCatching { h.screen() to runCatching { h.activity() }.getOrNull() } }
            r.onSuccess { (info, act) ->
                live.info = info
                // Protection's state on the phone too: without the computer, the camera's own alerts follow it
                if (!info.optBoolean("phoneOnly")) info.optJSONObject("protection")?.let { CameraLink.current()?.protection = it.optBoolean("on") }
                live.controls = info.optJSONArray("controls") ?: JSONArray()
                live.connected = info.optJSONObject("camera")?.optBoolean("connected") == true
                if (act != null) live.activity = act
                live.problem = null
            }.onFailure {
                if (it is Locked) { screen = Screen.LetIn(h.root); return@LaunchedEffect }
                // At home it's the Wi-Fi address; away, the one set in Conexão (and back again)
                val home = prefs.getString("root", null)
                val other = if (h.root == home) prefs.getString("remote", null) else home
                if (other != null && withContext(Dispatchers.IO) { runCatching { h.at(other).screen() }.isSuccess }) {
                    live.hub = h.at(other)
                    live.away = other != home
                    return@LaunchedEffect
                }
                live.connected = false
                live.problem = "Sem conexão com o computador"
            }
            if (first) {
                first = false
                if (screen == Screen.Splash) { delay(600); screen = tab }
            }
            delay(30_000)
        }
    }

    // The AI's detections, the camera's motion and new activities, as they happen
    DisposableEffect(live.hub, visible) {
        val h = live.hub
        if (h == null || !visible) return@DisposableEffect onDispose {}
        var running = true
        thread(name = "events", isDaemon = true) {
            while (running) {
                runCatching {
                    h.events({ e ->
                        when (e.optString("type")) {
                            "detections" -> live.detections = parseDetections(e.optJSONArray("items"))
                            "motion" -> live.moving = e.optBoolean("active")
                            "activity" -> e.optJSONObject("item")?.let { item ->
                                live.activity = JSONArray().also { a ->
                                    // A grouped movement comes again with its new end and count: it takes its own place
                                    if (!e.optBoolean("update")) a.put(item)
                                    for (i in 0 until minOf(live.activity.length(), 199)) {
                                        val old = live.activity.optJSONObject(i)
                                        a.put(if (e.optBoolean("update") && old?.optLong("at") == item.optLong("at")) item else old)
                                    }
                                }
                            }
                        }
                    }, { running })
                }
                if (running) Thread.sleep(2000)
            }
        }
        onDispose { running = false }
    }

    // Full screen turns the phone sideways; back from it turns it back
    DisposableEffect(fullscreen) {
        activity.requestedOrientation = if (fullscreen != null) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {}
    }

    LaunchedEffect(asked.value, live.connected) {
        if (live.hub == null || screen == Screen.Splash) return@LaunchedEffect
        // From a notification: what happened, the camera live, or the days' summary
        when (asked.value) {
            "activity" -> { tab = Screen.Activity; screen = Screen.Activity }
            "live" -> { tab = Screen.Home; screen = Screen.Camera }
            "summaries" -> { tab = Screen.Activity; screen = Screen.Summaries }
            else -> return@LaunchedEffect
        }
        asked.value = null
    }
    LaunchedEffect(screen) {
        if (screen == Screen.Activity) { live.seenAt = System.currentTimeMillis(); prefs.edit().putLong("seenAt", live.seenAt).apply() }
    }

    val open = { s: Screen -> previous = screen; screen = s }
    val back: () -> Unit = { screen = tab }
    val h = live.hub
    Box(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedContent(screen, label = "screen",
            contentKey = { if (it == Screen.Home || it == Screen.Cameras || it == Screen.Activity || it == Screen.Settings) "tabs" else it },
            transitionSpec = {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
                    androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(220)) { it / 14 }) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
            }) { s ->
        when (s) {
            Screen.Splash -> SplashScreen()
            Screen.Find -> {
                // Opened from "+" with a computer already connected: back returns to the app
                if (h != null) BackHandler(onBack = back)
                FindScreen(onFound = { root, inside -> if (inside) keep(Hub(root, null)) else screen = Screen.LetIn(root) },
                    onBack = if (h != null) back else null)
            }
            is Screen.LetIn -> LetInScreen(s.root, onKey = { keep(Hub(s.root, it)) }, onBack = { screen = Screen.Find })
            Screen.Home, Screen.Cameras, Screen.Activity, Screen.Settings -> if (h != null) Tabs(s, live.unseen, onTab = { tab = it; screen = it }) {
                when (s) {
                    Screen.Home -> HomeScreen(live, visible && fullscreen == null, onCamera = { open(Screen.Camera) }, onSystem = { open(Screen.System) },
                        onActivity = { tab = Screen.Activity; screen = Screen.Activity }, onFullscreen = { fullscreen = "whole" },
                        onProtection = { open(Screen.Protection) }, onAi = { open(Screen.Ai) })
                    Screen.Cameras -> CamerasScreen(live, onCamera = { open(Screen.Camera) }, onAdd = { open(Screen.Find) })
                    Screen.Activity -> ActivityScreen(live, onLive = { open(Screen.Camera) }, onSummaries = { open(Screen.Summaries) })
                    else -> SettingsScreen(onOpen = open)
                }
            }
            Screen.Camera -> if (h != null) {
                BackHandler(onBack = back)
                CameraScreen(live, visible && fullscreen == null, onBack = back, onFeatures = { open(Screen.Features) }, onFullscreen = { fullscreen = it })
            }
            Screen.Features -> if (h != null) {
                val from = { screen = if (previous == Screen.Camera) Screen.Camera else tab }
                BackHandler(onBack = from)
                FeaturesScreen(live, onBack = from, onZones = { screen = Screen.Zones })
            }
            Screen.Zones -> if (h != null) {
                BackHandler { screen = Screen.Features }
                ZonesScreen(live, onBack = { screen = Screen.Features })
            }
            Screen.People -> if (h != null) {
                BackHandler(onBack = back)
                PeopleScreen(live, onBack = back, onEnroll = { screen = Screen.Enroll })
            }
            Screen.Enroll -> if (h != null) EnrollScreen(live, visible, onBack = { screen = Screen.People })
            Screen.Ai -> if (h != null) {
                BackHandler(onBack = back)
                AiScreen(live, onBack = back, onPeople = { open(Screen.People) }, onZones = { open(Screen.Zones) })
            }
            Screen.Diagnostics -> if (h != null) {
                BackHandler(onBack = back)
                DiagnosticsScreen(live, onBack = back)
            }
            Screen.About -> {
                BackHandler(onBack = back)
                AboutScreen(onBack = back, onTerms = { screen = Screen.Terms }, onPrivacy = { screen = Screen.Privacy },
                    onPlacement = { screen = Screen.Placement })
            }
            Screen.Terms -> {
                BackHandler { screen = Screen.About }
                TermsScreen(onBack = { screen = Screen.About })
            }
            Screen.Placement -> {
                BackHandler(onBack = back)
                PlacementScreen(onBack = back)
            }
            Screen.Notifications -> {
                BackHandler(onBack = back)
                NotificationsScreen(live, prefs, onBack = back)
            }
            Screen.Summaries -> if (h != null) {
                BackHandler(onBack = back)
                SummariesScreen(live, prefs, onBack = back)
            }
            Screen.Protection -> if (h != null) {
                BackHandler(onBack = back)
                ProtectionScreen(live, prefs, onBack = back)
            }
            Screen.Family -> if (h != null) {
                BackHandler(onBack = back)
                FamilyScreen(live, onBack = back)
            }
            Screen.System -> {
                BackHandler(onBack = back)
                SystemScreen(live, prefs, onBack = back, onLeave = forget)
            }
            Screen.Appearance -> {
                BackHandler(onBack = back)
                AppearanceScreen(theme, setTheme, onBack = back)
            }
            Screen.Privacy -> {
                BackHandler(onBack = back)
                PrivacyScreen(onBack = back)
            }
            is Screen.Soon -> {
                BackHandler(onBack = back)
                SoonScreen(s.title, onBack = back)
            }
        }
        }
        fullscreen?.let { view ->
            if (h != null) {
                BackHandler { fullscreen = null }
                FullscreenVideo(live, view, visible, onClose = { fullscreen = null })
            }
        }
    }
}

fun parseDetections(items: JSONArray?): List<Detection> {
    if (items == null) return emptyList()
    return (0 until items.length()).mapNotNull { i ->
        val it = items.optJSONObject(i) ?: return@mapNotNull null
        val b = it.optJSONArray("box") ?: return@mapNotNull null
        Detection(b.optDouble(0).toFloat(), b.optDouble(1).toFloat(), b.optDouble(2).toFloat(), b.optDouble(3).toFloat(),
            it.optString("kind"), it.optBoolean("uncertain"))
    }
}

/**
 * What the screens already fetched, kept while the app runs: coming back to a screen shows it at once (no "loading",
 * nothing jumping into place) and it refreshes behind. Pictures by address (activity thumbnails, faces), up to 120.
 */
object Kept {
    private val values = java.util.concurrent.ConcurrentHashMap<String, Any>()
    @Suppress("UNCHECKED_CAST") operator fun <T> get(key: String): T? = values[key] as T?
    operator fun set(key: String, value: Any?) { if (value == null) values.remove(key) else values[key] = value }
    val pictures = android.util.LruCache<String, ImageBitmap>(120)
}

/** A picture from the computer (snapshot, thumbnail), loaded off the main thread; the same address shows at once again. */
@Composable
fun rememberPicture(hub: Hub, url: String?): ImageBitmap? {
    var image by remember(url) { mutableStateOf(url?.let { Kept.pictures.get(it) }) }
    LaunchedEffect(url) {
        if (url == null || image != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { hub.bytes(url).let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull()
        }?.let { image = it; Kept.pictures.put(url, it) }
    }
    return image
}

/**
 * The camera's picture for the places that show it still (the camera list, while the video opens): the video's last
 * picture right away, then the camera's snapshot, tried again until it comes (the camera may be busy closing a video)
 * and refreshed every 30 s.
 */
@Composable
fun rememberCameraPicture(hub: Hub): ImageBitmap? {
    var image by remember(hub) { mutableStateOf(SharedVideo.lastFrame) }
    LaunchedEffect(hub) {
        while (true) {
            withContext(Dispatchers.IO) {
                runCatching { hub.bytes(hub.thumbUrl).let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull()
            }?.let { image = it }
            kotlinx.coroutines.delay(if (image == null) 3_000 else 30_000)
        }
    }
    return image
}
