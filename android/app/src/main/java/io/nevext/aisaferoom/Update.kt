package io.nevext.aisaferoom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The app updates itself (while it isn't on the Play Store): the newest release on GitHub (tag app-vX.Y.Z, asset
 * AI-Safe-Room.apk, signed with the same key), downloaded here and handed to Android's installer.
 */
object Updates {
    data class Release(val version: String, val notes: String, val apk: String)

    /** Asked once per app run; "Depois" holds until the app opens again. */
    var checked = false
    var later = false

    private const val LATEST = "https://api.github.com/repos/nevext/ai-safe-room/releases/latest"

    fun current(c: Context): String = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "0"

    /** The newest release if it's newer than this app, else null (blocking). */
    fun newer(c: Context): Release? {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val j = try { JSONObject(conn.inputStream.bufferedReader().readText()) } finally { conn.disconnect() }
        val tag = j.optString("tag_name")
        if (!tag.startsWith("app-v")) return null
        val assets = j.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }
            ?.optString("browser_download_url") ?: return null
        val version = tag.removePrefix("app-v")
        return if (isNewer(version, current(c))) Release(version, j.optString("body"), apk) else null
    }

    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Downloads the APK into the cache (blocking), telling 0–1 as it goes. */
    fun download(c: Context, r: Release, progress: (Float) -> Unit): File {
        val dir = File(c.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "AI-Safe-Room-${r.version}.apk")
        val conn = URL(r.apk).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException("o GitHub respondeu ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
        } finally { conn.disconnect() }
        return file
    }

    /** Android may first ask to allow installing from this app (once); then its installer opens with the new version. */
    fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    fun allowInstalls(c: Context) {
        c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(c: Context, file: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.updates", file)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** On opening the app: a newer version pops up with Atualizar (download, then Android's installer) or Depois. */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Updates.Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var needsAllow by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var later by remember { mutableStateOf(Updates.later) }
    LaunchedEffect(Unit) {
        if (Updates.checked) return@LaunchedEffect
        Updates.checked = true
        release = withContext(Dispatchers.IO) { runCatching { Updates.newer(context) }.getOrNull() }
    }
    val r = release ?: return
    if (later) return
    val install = { f: File ->
        if (Updates.canInstall(context)) { needsAllow = false; Updates.install(context, f) } else { needsAllow = true; Updates.allowInstalls(context) }
    }
    AlertDialog(onDismissRequest = { if (progress == null) later = true; Updates.later = true },
        title = { Text("Versão nova: ${r.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Você está na ${Updates.current(context)}.", fontSize = 13.sp, color = palette.muted)
                if (r.notes.isNotBlank()) Text(r.notes.trim(), fontSize = 14.sp)
                progress?.let { p -> if (file == null) { LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Blue); Text("Baixando…", fontSize = 13.sp) } }
                if (needsAllow) Text("O Android pede uma vez: ligue \"Permitir desta fonte\" para o AI Safe Room, volte aqui e toque em Instalar.",
                    fontSize = 13.sp, color = palette.muted)
                error?.let { Text(it, color = Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        },
        confirmButton = {
            Button(enabled = progress == null || file != null, colors = ButtonDefaults.buttonColors(containerColor = Blue), onClick = {
                file?.let { install(it); return@Button }
                progress = 0f; error = null
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { Updates.download(context, r) { p -> progress = p } } }
                        .onSuccess { file = it; install(it) }
                        .onFailure { progress = null; error = "Não deu para baixar: ${it.message}. Confira a internet e tente de novo." }
                }
            }) { Text(if (file != null) "Instalar" else "Atualizar") }
        },
        dismissButton = { if (progress == null || file != null) TextButton(onClick = { later = true; Updates.later = true }) { Text("Depois", color = palette.muted) } })
}
