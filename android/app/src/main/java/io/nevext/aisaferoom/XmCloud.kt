package io.nevext.aisaferoom

import android.content.Context
import android.content.pm.PackageManager
import android.view.ViewGroup
import com.lib.MsgContent
import com.manager.XMFunSDKManager
import com.manager.account.AccountManager
import com.manager.account.BaseAccountManager
import com.manager.db.XMDevInfo
import com.manager.device.DeviceManager
import com.manager.device.media.MediaManager
import com.manager.device.media.attribute.PlayerAttribute
import com.manager.device.media.monitor.MonitorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The XM / JFTech cloud (the iCSee and XMEye cameras): the camera by its serial number from anywhere, P2P, the way
 * its own app does it. No computer, no server of ours, nothing on the router. At home the app still goes straight to
 * the camera on the Wi-Fi (no delay); this is for when the camera isn't on this network.
 *
 * Needs the app's keys from the XM open platform (android/xm.properties, outside git). Without them, or on a phone
 * the SDK has no build for (x86), [ready] is false and the app says so instead of trying.
 */
object XmCloud {
    @Volatile private var started = false
    @Volatile private var broken: String? = null
    private val logged = mutableSetOf<String>()

    /** The keys are in this build (android/xm.properties). */
    fun hasKeys(c: Context): Boolean = runCatching {
        val m = c.packageManager.getApplicationInfo(c.packageName, PackageManager.GET_META_DATA).metaData
        listOf("APP_UUID", "APP_KEY", "APP_SECRET", "APP_MOVECARD").all { !m?.get(it)?.toString().isNullOrBlank() }
    }.getOrDefault(false)

    /** The cloud can be used on this phone (keys present and the SDK starts). */
    fun ready(c: Context): Boolean = hasKeys(c) && start(c)

    /** Why it isn't ready, for the screen. */
    fun whyNot(c: Context): String = when {
        !hasKeys(c) -> "Ver de fora pela nuvem da câmera ainda não está ligado nesta versão do app."
        broken != null -> "Este celular não consegue usar a nuvem da câmera ($broken)."
        else -> "A nuvem da câmera não respondeu."
    }

    @Synchronized
    private fun start(c: Context): Boolean {
        if (started) return true
        if (broken != null) return false
        return try {
            XMFunSDKManager.getInstance().initXMCloudPlatform(c.applicationContext)
            // Local mode: the camera's own user and password, no XM account for the family to create
            AccountManager.getInstance().localLogin(null, quiet)
            started = true
            true
        } catch (e: Throwable) {   // UnsatisfiedLinkError on x86, or the SDK refusing the keys
            broken = e.javaClass.simpleName
            false
        }
    }

    private val quiet = object : BaseAccountManager.OnAccountManagerListener {
        override fun onSuccess(msgId: Int) {}
        override fun onFailed(msgId: Int, errorId: Int) {}
        override fun onFunSDKResult(message: android.os.Message?, msgContent: MsgContent?) {}
    }

    /**
     * Logs in to the camera through the cloud. Null when it worked; otherwise what to tell the person (a wrong
     * password reads differently from a camera that's off or without internet).
     */
    suspend fun login(c: Context, a: CameraLink.Access): String? {
        if (a.serial.isEmpty()) return "O app ainda não sabe o número de série da câmera. Abra o app uma vez no Wi-Fi da câmera."
        if (!ready(c)) return whyNot(c)
        return withContext(Dispatchers.Main) {
            if (a.serial !in logged) addDevice(a)
            // 0 logged in, else XM's error code; null: no answer in time
            val r = withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine<Int> { k ->
                    DeviceManager.getInstance().loginDev(a.serial, a.user, a.pass, object : DeviceManager.OnDevManagerListener<Any?> {
                        override fun onSuccess(devId: String?, operationType: Int, result: Any?) { if (k.isActive) k.resume(0) }
                        override fun onFailed(devId: String?, msgId: Int, jsonName: String?, errorId: Int) { if (k.isActive) k.resume(errorId) }
                    })
                }
            }
            when (r) {
                0 -> { logged += a.serial; null }
                null -> "A câmera demorou demais para responder pela nuvem. Ela está ligada e com internet?"
                // -11301 is the wrong-password code in XM's SDK (to confirm against the real camera once the keys come)
                -11301 -> "A senha da câmera mudou. Abra o app no Wi-Fi da câmera para o app aprender a nova."
                else -> "A câmera não respondeu pela nuvem (código $r). Ela está ligada e com internet?"
            }
        }
    }

    private suspend fun addDevice(a: CameraLink.Access) {
        val info = XMDevInfo().apply { setDevId(a.serial); setDevName(a.name); setDevUserName(a.user) }
        withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Unit> { k ->
                AccountManager.getInstance().addDev(info, object : BaseAccountManager.OnAccountManagerListener {
                    override fun onSuccess(msgId: Int) { if (k.isActive) k.resume(Unit) }
                    override fun onFailed(msgId: Int, errorId: Int) { if (k.isActive) k.resume(Unit) }   // already there is fine
                    override fun onFunSDKResult(message: android.os.Message?, msgContent: MsgContent?) {}
                })
            }
        }
        DeviceManager.getInstance().setLocalDevPwd(a.serial, a.pass)
    }

    /** The live picture into [into], once [login] worked. onState: true playing, false stopped or failed. */
    fun play(into: ViewGroup, serial: String, onState: (Boolean) -> Unit): MonitorManager {
        val m = DeviceManager.getInstance().createMonitorPlayer(into, serial)
        m.setOnMediaManagerListener(object : MediaManager.OnMediaManagerListener {
            override fun onMediaPlayState(attribute: PlayerAttribute<*>?, state: Int) { onState(state == PlayerAttribute.E_STATE_PlAY) }
            override fun onFailed(attribute: PlayerAttribute<*>?, msgId: Int, errorId: Int) { onState(false) }
            override fun onShowRateAndTime(attribute: PlayerAttribute<*>?, isShowTime: Boolean, time: String?, rate: String?) {}
            override fun onVideoBufferEnd(attribute: PlayerAttribute<*>?, ex: MsgContent?) {}
        })
        m.startMonitor()
        return m
    }
}

/** The live picture through the camera's cloud (away from home, no computer). */
@androidx.compose.runtime.Composable
fun CloudVideo(live: Live, visible: Boolean, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val serial = CameraLink.current()?.access?.serial.orEmpty()
    var playing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var slow by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var player by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<MonitorManager?>(null) }
    androidx.compose.runtime.LaunchedEffect(playing, visible) {
        slow = false
        if (!visible || playing) return@LaunchedEffect
        kotlinx.coroutines.delay(15_000)
        slow = !playing
    }
    androidx.compose.runtime.DisposableEffect(visible, serial) {
        onDispose { player?.destroyPlay(); player = null; playing = false }
    }
    androidx.compose.foundation.layout.Box(modifier.background(androidx.compose.ui.graphics.Color.Black), contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (visible && serial.isNotEmpty()) androidx.compose.ui.viewinterop.AndroidView(
            factory = { c -> android.widget.FrameLayout(c).also { f -> player = XmCloud.play(f, serial) { playing = it } } },
            modifier = androidx.compose.ui.Modifier.matchParentSize(),
        )
        if (!playing) androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            modifier = androidx.compose.ui.Modifier.padding(horizontal = 24.dp)) {
            if (!slow) androidx.compose.material3.CircularProgressIndicator(androidx.compose.ui.Modifier.size(24.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
            androidx.compose.material3.Text(
                if (slow) "A imagem está demorando para chegar pela nuvem da câmera. A internet da casa pode estar lenta." else "Abrindo a câmera pela nuvem…",
                color = androidx.compose.ui.graphics.Color(0xCCFFFFFF), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = androidx.compose.ui.Modifier.padding(top = if (slow) 0.dp else 8.dp))
        }
    }
}
