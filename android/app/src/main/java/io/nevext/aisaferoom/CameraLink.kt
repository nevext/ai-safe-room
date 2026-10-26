package io.nevext.aisaferoom

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The camera itself, from the phone (no computer): what the app does when the AI is off, or the computer is off or
 * busy. Video goes direct over RTSP (DirectVideo); this is the rest: night vision, light, turning, the camera's own
 * person detection and follow, clock, restart, password, snapshot, talk and its alerts. One per app.
 */
class CameraLink private constructor(private val context: Context) {
    data class Access(val addr: String, val user: String, val pass: String, val serial: String, val name: String, val twoLenses: Boolean)

    private val prefs get() = context.getSharedPreferences("aisaferoom", Context.MODE_PRIVATE)
    var access: Access? = load(); private set
    private var session: Dvrip? = null

    private fun load(): Access? = prefs.getSecret("camera")?.let { runCatching {
        val j = JSONObject(it)
        Access(j.getString("addr"), j.optString("user", "admin"), j.optString("pass"), j.optString("serial"), j.optString("name", "Câmera"),
            j.optBoolean("twoLenses"))
    }.getOrNull() }

    /** Keeps the camera's address and login on the phone (from camera-ai, once; or typed by the person). */
    fun remember(a: Access) {
        if (a == access) return
        access = a
        prefs.edit().putSecret("camera", JSONObject().put("addr", a.addr).put("user", a.user).put("pass", a.pass).put("serial", a.serial)
            .put("name", a.name).put("twoLenses", a.twoLenses).toString()).apply()
        synchronized(this) { session?.close(); session = null }
    }

    /** The control session, opened (or reopened) when needed; the camera's alerts come on it too. */
    @Synchronized
    fun dvr(): Dvrip {
        session?.takeIf { !it.closed }?.let { return it }
        val a = access ?: throw IOException("o app ainda não conhece a câmera")
        return Dvrip.open(a.addr, a.user, a.pass).also { session = it; if (alarmListeners.isNotEmpty()) watchAlarms(it) }
    }

    // The camera's own alerts (1504, after asking with 1500): person (its own detection), motion, covered
    private val alarmListeners = java.util.concurrent.CopyOnWriteArraySet<(String, Boolean) -> Unit>()
    private fun watchAlarms(d: Dvrip) {
        runCatching { d.request(1500, JSONObject().put("Name", "")) }
        d.listen { id, msg ->
            if (id != 1504 || msg !is JSONObject) return@listen
            val info = msg.optJSONObject("AlarmInfo") ?: msg
            val event = info.optString("Event")
            val start = info.optString("Status").equals("Start", ignoreCase = true)
            for (fn in alarmListeners) runCatching { fn(event, start) }
        }
    }
    /** Hears the camera's alerts (event name, started or stopped) while the returned function isn't called. */
    fun alarms(fn: (String, Boolean) -> Unit): () -> Unit {
        val first = alarmListeners.isEmpty()
        alarmListeners.add(fn)
        if (first) synchronized(this) { session?.takeIf { !it.closed }?.let { watchAlarms(it) } }
        return { alarmListeners.remove(fn) }
    }

    /** Protection without the computer: kept on the phone (the computer's own wins when it's there). */
    var protection: Boolean
        get() = prefs.getBoolean("protectionOn", false)
        set(on) { prefs.edit().putBoolean("protectionOn", on).apply() }
    private fun <T> withDvr(block: (Dvrip) -> T): T = try { block(dvr()) } catch (e: IOException) {
        synchronized(this) { session?.close(); session = null }
        block(dvr())   // the camera dropped the session: once more
    }

    private fun get(name: String): JSONObject? = withDvr { it.request(1042, JSONObject().put("Name", name)).optJSONObject(name) }
    private fun getArray(name: String): JSONArray? = withDvr { it.request(1042, JSONObject().put("Name", name)).optJSONArray(name) }
    private fun set(name: String, value: Any) = withDvr {
        val r = it.request(1040, JSONObject().put("Name", name).put(name, value))
        if (r.optInt("Ret") != 100 && r.optInt("Ret") != 603) throw IOException("a câmera recusou (${r.optInt("Ret")})")
    }

    // Night vision: auto (switches at night) or always in colour (Camera.ParamEx DayNightSwitch, like camera-ai)
    fun nightVision(): Boolean = get("Camera.ParamEx.[0]")?.optJSONObject("DayNightSwitch")
        ?.let { !(it.optInt("SwitchMode") == 1 && it.optString("KeepDayPeriod") == ALWAYS_DAY) } ?: true
    fun setNightVision(on: Boolean) {
        val cur = get("Camera.ParamEx.[0]") ?: throw IOException("a câmera não respondeu")
        cur.put("DayNightSwitch", if (on) JSONObject().put("KeepDayPeriod", "0 07:00:00-18:00:00").put("SwitchMode", 0)
            else JSONObject().put("KeepDayPeriod", ALWAYS_DAY).put("SwitchMode", 1))
        set("Camera.ParamEx.[0]", cur)
    }
    // White light: on all day, or infrared only at night
    fun whiteLight(): Boolean = get("Camera.WhiteLight")?.optString("WorkMode") == "KeepOpen"
    fun setWhiteLight(on: Boolean) {
        val cur = get("Camera.WhiteLight") ?: throw IOException("a câmera não respondeu")
        cur.put("WorkMode", if (on) "KeepOpen" else "Infrared")
        cur.put("WorkPeriod", if (on) JSONObject().put("EHour", 23).put("EMinute", 59).put("Enable", 1).put("SHour", 0).put("SMinute", 0)
            else JSONObject().put("EHour", 6).put("EMinute", 0).put("Enable", 1).put("SHour", 18).put("SMinute", 0))
        set("Camera.WhiteLight", cur)
    }
    // The camera's own follow and person detection
    fun track(): Boolean? = get("Detect.DetectTrack")?.optInt("Enable")?.let { it == 1 }
    fun setTrack(on: Boolean) { val c = get("Detect.DetectTrack") ?: return; set("Detect.DetectTrack", c.put("Enable", if (on) 1 else 0)) }
    fun person(): Boolean? = get("Detect.HumanDetection.[0]")?.optBoolean("Enable")
    fun setPerson(on: Boolean) { val c = get("Detect.HumanDetection.[0]") ?: return; set("Detect.HumanDetection.[0]", c.put("Enable", on)) }
    fun sdMb(): Int = withDvr { it.request(1020, JSONObject().put("Name", "StorageInfo")).optJSONArray("StorageInfo") }?.let { disks ->
        (0 until disks.length()).sumOf { i -> disks.optJSONObject(i)?.optJSONArray("Partition")?.let { p ->
            (0 until p.length()).sumOf { (p.optJSONObject(it)?.optString("TotalSpace") ?: "0").removePrefix("0x").toLongOrNull(16)?.toInt() ?: 0 } } ?: 0 }
    } ?: 0

    // Turning the lens (the lens's left is the picture's right on this camera, as in camera-ai)
    private var moving: String? = null
    fun ptz(dir: String, action: String) {
        val command = when {
            dir == "home" -> "GotoPreset"
            dir == "sethome" -> "SetPreset"
            action == "stop" -> moving ?: return
            else -> mapOf("left" to "DirectionRight", "right" to "DirectionLeft", "up" to "DirectionUp", "down" to "DirectionDown")[dir] ?: return
        }
        val preset = when { dir == "home" || dir == "sethome" -> 1; action == "stop" -> -1; else -> 65535 }
        if (dir == "home" || dir == "sethome") moving?.let { ptzCommand(it, -1) }
        moving = if (action == "start" && preset == 65535) command else null
        ptzCommand(command, preset)
    }
    private fun ptzCommand(command: String, preset: Int) = withDvr {
        it.request(1400, JSONObject().put("Name", "OPPTZControl").put("OPPTZControl", JSONObject().put("Channel", 0).put("Command", command)
            .put("Parameter", JSONObject().put("AUX", JSONObject().put("Number", 0).put("Status", "On")).put("Channel", 0).put("MenuOpts", "Enter")
                .put("Pattern", "Start").put("Preset", preset).put("Step", 5).put("Tour", 0))))
    }

    fun syncClock(): String {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val r = withDvr { it.request(1450, JSONObject().put("Name", "OPTimeSetting").put("OPTimeSetting", now)) }
        if (r.optInt("Ret") != 100) throw IOException("a câmera recusou (${r.optInt("Ret")})")
        return now
    }
    fun reboot() { runCatching { withDvr { it.request(1450, JSONObject().put("Name", "OPMachine").put("OPMachine", JSONObject().put("Action", "Reboot")), 3000) } } }
    fun changePassword(new: String) {
        val a = access ?: throw IOException("o app ainda não conhece a câmera")
        if (new.length !in 6..15) throw IOException("a senha da câmera precisa ter de 6 a 15 caracteres")
        val r = withDvr { it.request(1488, JSONObject().put("EncryptType", "MD5").put("UserName", a.user).put("PassWord", Dvrip.hash(a.pass)).put("NewPassWord", Dvrip.hash(new))) }
        if (r.optInt("Ret") != 100) throw IOException("a câmera recusou (${r.optInt("Ret")})")
        remember(a.copy(pass = new))
        context.getSharedPreferences("aisaferoom", Context.MODE_PRIVATE).edit().remove("directStream").apply()
    }

    /**
     * Talk through the camera's speaker, straight from the phone: a session of its own, Claim (1434), Start (1430, no
     * answer) and G.711 A-law in 40 ms frames (1432), as camera-ai does. [read] gives PCM 16 bits 8 kHz (buffer, offset, size).
     */
    fun talk(read: (ByteArray, Int, Int) -> Int, running: () -> Boolean) {
        val a = access ?: throw IOException("o app ainda não conhece a câmera")
        val d = Dvrip.open(a.addr, a.user, a.pass)
        val fmt = JSONObject().put("BitRate", 128).put("EncodeType", "G711_ALAW").put("SampleBit", 8).put("SampleRate", 8000)
        val command = { action: String -> d.sendJson(1430, JSONObject().put("Name", "OPTalk").put("OPTalk", JSONObject().put("Action", action).put("AudioFormat", fmt))) }
        try {
            val claim = d.request(1434, JSONObject().put("Name", "OPTalk").put("OPTalk", JSONObject().put("Action", "Claim").put("AudioFormat", fmt)))
            if (claim.optInt("Ret") != 100) throw IOException("a câmera não aceitou a conversa (${claim.optInt("Ret")})")
            command("Start")
            val pcm = ByteArray(640)
            while (running()) {
                var got = 0
                while (got < 640 && running()) { val n = read(pcm, got, 640 - got); if (n <= 0) break; got += n }
                if (got < 640) break
                val frame = ByteArray(8 + 320)
                byteArrayOf(0, 0, 1, 0xfa.toByte(), 0x0e, 0x02, 0x40, 0x01).copyInto(frame)
                for (i in 0 until 320) frame[8 + i] = alaw(((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xff)).toShort().toInt())
                d.send(1432, frame)
            }
        } finally {
            runCatching { command("Stop") }
            Thread.sleep(200); d.close()
        }
    }
    private fun alaw(sample: Int): Byte {
        var s = sample
        val sign = if (s < 0) { s = -s - 1; 0x00 } else 0x80
        if (s > 32635) s = 32635
        var exp = 7; var mask = 0x4000
        while ((s and mask) == 0 && exp > 0) { exp--; mask = mask shr 1 }
        val mant = (s shr (if (exp == 0) 4 else exp + 3)) and 0x0f
        return ((sign or (exp shl 4) or mant) xor 0x55).toByte()
    }

    /** A picture now, from the camera's own snapshot (fast, and no second video connection). */
    fun snapshot(): ByteArray {
        val a = access ?: throw IOException("o app ainda não conhece a câmera")
        val c = URL("http://${a.addr}/webcapture.jpg?command=snap&channel=0").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 8000
        c.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("${a.user}:${a.pass}".toByteArray(), Base64.NO_WRAP))
        try { if (c.responseCode != 200) throw IOException("a câmera respondeu ${c.responseCode}"); return c.inputStream.readBytes() } finally { c.disconnect() }
    }

    /** The camera's own direct video addresses (RTSP, with its login), as camera-ai's /api/screen/direct-stream gives. */
    fun directStream(): JSONObject {
        val a = access ?: throw IOException("o app ainda não conhece a câmera")
        val url = { stream: Int -> "rtsp://${a.addr}:554/user=${a.user}_password=${a.pass}_channel=0_stream=$stream&onvif=0.sdp?real_stream" }
        return JSONObject().put("ok", true).put("main", url(0)).put("sub", url(1)).put("width", 3072).put("height", 2048)
    }

    /** The controls the camera page shows (same shape as camera-ai's), read from the camera. */
    fun controls(): JSONArray = JSONArray().apply {
        runCatching { nightVision() }.onSuccess { put(toggle("night", "Visão noturna", it)) }
        runCatching { whiteLight() }.onSuccess { put(toggle("light", "Luz branca", it)) }
        runCatching { track() }.getOrNull()?.let { put(toggle("camTrack", "Seguir pessoa (da câmera)", it)) }
    }
    fun control(id: String, value: Boolean) = when (id) {
        "night" -> setNightVision(value)
        "light" -> setWhiteLight(value)
        "camTrack" -> setTrack(value)
        else -> throw IOException("isto precisa do computador (IA)")
    }
    private fun toggle(id: String, label: String, value: Boolean) =
        JSONObject().put("id", id).put("type", "toggle").put("label", JSONObject().put("pt", label)).put("value", value)

    /** What the screens show, made from the camera alone (the computer is off or busy). */
    fun screenInfo(): JSONObject {
        val a = access ?: throw IOException("o app ainda não conhece a câmera")
        val controls = controls()   // also proves the camera answers
        return JSONObject()
            .put("camera", JSONObject().put("connected", true).put("name", a.name).put("kind", "Câmera").put("serial", a.serial).put("blankPass", a.pass.isEmpty()))
            .put("lenses", JSONObject().put("count", if (a.twoLenses) 2 else 1))
            .put("ai", JSONObject().put("enabled", false).put("status", "off"))
            .put("controls", controls)
            .put("ptz", true)
            .put("cameraPerson", runCatching { person() }.getOrNull() == true)
            .put("protection", JSONObject().put("on", protection))
            .put("phoneOnly", true)
    }

    companion object {
        private const val ALWAYS_DAY = "0 00:00:00-23:59:59"
        @Volatile private var instance: CameraLink? = null
        fun get(context: Context): CameraLink = instance ?: synchronized(this) { instance ?: CameraLink(context.applicationContext).also { instance = it } }
        /** The camera goes direct (AI off, or the computer away): set by the app as the state changes. */
        @Volatile var direct = true
        fun current(): CameraLink? = instance
    }
}
