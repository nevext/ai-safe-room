package io.nevext.aisaferoom

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.concurrent.thread

/** A camera-ai found on the home network. */
data class Found(val root: String, val serial: String?)

/** camera-ai said this phone has no key (or its password changed). */
class Locked : IOException("camera-ai pede acesso")

/**
 * Talking to the home computer's AI Safe Room (camera-ai), the program that holds the cameras: it finds it on the network, asks
 * it to let this phone in, and reads and changes what the camera offers. Everything here is blocking: call it off
 * the main thread.
 */
class Hub(val root: String, val key: String?) {
    /** Every address starts like this: root, plus /k/<key> once camera-ai let this phone in. */
    val base: String get() = if (key == null) root else "$root/k/$key"

    /** What the camera offers now: lenses, video, AI, controls (/api/screen). Locked without a valid key. */
    fun screen(): JSONObject = json(base + "/api/screen")

    /** How the camera and the AI are connected: grade 0–3 (Péssimo, Regular, Bom, Ótimo) and a line of detail. */
    fun connection(): JSONObject = json("$base/api/connection")

    /** The camera's RTSP addresses (with its login), to watch direct without the AI. */
    fun directStream(): JSONObject = json("$base/api/screen/direct-stream")
    /** The AI: on or off (off by default), its features and the rigor. */
    fun ai(): JSONObject = json("$base/api/ai")
    fun setAi(body: JSONObject): JSONObject = json("$base/api/ai", body.toString())
    /** One line per day, last 30 days. */
    fun summaries(): JSONArray = json("$base/api/summaries").optJSONArray("days") ?: JSONArray()
    /** "wifi", "ai" or "camera": takes some seconds. */
    fun diagnose(what: String): JSONObject = json("$base/api/diagnose", JSONObject().put("what", what).toString(), timeoutMs = 120_000)
    /** "Reconectar": [what] is "camera" or "ai". Returns the connection as it is after. */
    fun reconnect(what: String): JSONObject = json("$base/api/connection", JSONObject().put("reconnect", what).toString())

    /** Changes one control (night vision, white light, follow…); returns all controls as they are now. */
    fun control(id: String, value: Any): JSONArray {
        val reply = json(base + "/api/screen/control", JSONObject().put("id", id).put("value", value).toString())
        if (!reply.optBoolean("ok")) throw IOException(reply.optString("error", "não deu"))
        return reply.optJSONArray("controls") ?: JSONArray()
    }

    /** The camera's profile: what it has, what was checked, the history (/api/perfil). */
    fun profile(): JSONObject = json(base + "/api/perfil")

    /** Checks what's still unchecked (the light may turn on and the lens turn). Takes up to a minute. */
    fun checkAll(): JSONObject = json(base + "/api/perfil/vistoria", "{}", timeoutMs = 120_000)

    /** The live picture as JPEGs (MJPEG): view is whole, top (lens 1) or bottom (lens 2). */
    fun videoUrl(view: String, width: Int, height: Int) = "$base/api/screen/video?view=$view&width=$width&height=$height"

    /** A small still of the camera (320 px), ready at once: for lists and while the video opens. */
    val thumbUrl: String get() = "$base/api/screen/thumb"

    /** An activity's thumbnail. */
    fun thumbUrl(name: String) = "$base/api/activity/$name"

    /** What happened: motion, people, camera online/offline, night vision, light (newest first). */
    fun activity(): JSONArray = json("$base/api/activity").optJSONArray("activity") ?: JSONArray()

    /** Names the camera ("Quarto"). */
    fun rename(name: String): JSONObject = json("$base/api/perfil", JSONObject().put("name", name).toString())

    /** Areas the AI ignores (the TV, a fan): rectangles x, y, width, height from 0 to 1 of the whole picture. */
    fun setZones(zones: List<FloatArray>): JSONObject =
        json("$base/api/perfil", JSONObject().put("zones", JSONArray(zones.map { z -> JSONArray(z.map { it.toDouble() }) })).toString())

    /** "Foi engano": the activity leaves the list (camera-ai notes it to tune the AI). */
    fun forget(at: Long, type: String) {
        val reply = json("$base/api/activity/forget", JSONObject().put("at", at).put("type", type).toString())
        if (!reply.optBoolean("ok")) throw IOException(reply.optString("error", "não deu"))
    }

    /** Whether there are animals in the house: if not, the AI ignores animals. */
    fun setPets(on: Boolean): JSONObject = json("$base/api/perfil", JSONObject().put("pets", on).toString())

    /** Known people (faces): who's registered and whether the face models are there. */
    fun faces(): JSONObject = json("$base/api/faces")
    fun startEnroll(name: String, consent: Boolean): JSONObject =
        json("$base/api/faces/enroll", JSONObject().put("name", name).put("consent", consent).toString(), allowError = true)
    fun enrollStatus(): JSONObject = json("$base/api/faces/enroll")
    fun cancelEnroll() { json("$base/api/faces/enroll", JSONObject().put("cancel", true).toString()) }
    fun removeFace(id: String): JSONObject = json("$base/api/faces/remove", JSONObject().put("id", id).toString())

    /**
     * "Quem é?": whose is an unknown face of a visit. [person] (an id) learns it; [name] with [consent] registers someone
     * new; [nobody] says nobody from home. Returns the visit as it is now.
     */
    fun whoIs(at: Long, face: Int, person: String? = null, name: String? = null, consent: Boolean = false, nobody: Boolean = false): JSONObject {
        val body = JSONObject().put("at", at).put("face", face)
        person?.let { body.put("person", it) }
        name?.let { body.put("name", it).put("consent", consent) }
        if (nobody) body.put("nobody", true)
        val reply = json("$base/api/activity/who", body.toString())
        if (!reply.optBoolean("ok")) throw IOException(reply.optString("error", "não deu"))
        return reply.getJSONObject("item")
    }

    /** Protection (away mode): on, and its schedule. */
    fun setProtection(on: Boolean): JSONObject = json("$base/api/protection", JSONObject().put("on", on).toString())
    fun setOnlyUnknown(on: Boolean): JSONObject = json("$base/api/protection", JSONObject().put("onlyUnknown", on).toString())
    fun setProtectionSchedule(enabled: Boolean, on: String, off: String): JSONObject = json("$base/api/protection",
        JSONObject().put("schedule", JSONObject().put("enabled", enabled).put("on", on).put("off", off)).toString())

    /** Privacy mode: the camera covers its own picture and the AI stops looking. */
    fun setPrivacy(on: Boolean): JSONObject {
        val reply = json("$base/api/privacy", JSONObject().put("on", on).toString())
        if (!reply.optBoolean("ok")) throw IOException(reply.optString("error", "não deu"))
        return reply
    }

    /** The phones, TVs and browsers let in, each with its own access. */
    fun devices(): JSONArray = json("$base/api/devices").optJSONArray("devices") ?: JSONArray()
    fun removeDevice(id: String): JSONArray = json("$base/api/devices/remove", JSONObject().put("id", id).toString()).optJSONArray("devices") ?: JSONArray()

    /** Who asked to be let in (a new phone, a TV) and waits for someone to Allow. */
    fun pendingAccess(): JSONArray = json("$base/api/access/pending").optJSONArray("requests") ?: JSONArray()
    fun answerAccess(id: String, allow: Boolean) { json("$base/api/access/answer", JSONObject().put("id", id).put("allow", allow).toString()) }

    /** The same computer at another address (from outside the house), keeping this phone's access. */
    fun at(otherRoot: String) = Hub(otherRoot, key)

    /** Raw bytes of a picture (snapshot, thumbnail). */
    fun bytes(url: String): ByteArray {
        val c = open(url, null, 15_000)
        try {
            if (c.responseCode == 401) throw Locked()
            if (c.responseCode >= 400) throw IOException("camera-ai respondeu ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** The AI's detections and the camera's motion, one JSON per line, until stopped. */
    fun events(onEvent: (JSONObject) -> Unit, running: () -> Boolean) {
        val c = open("$base/api/screen/events", null, 12_000)
        try {
            c.inputStream.bufferedReader().use { r ->
                while (running()) {
                    val line = r.readLine() ?: break
                    runCatching { JSONObject(line) }.getOrNull()?.let(onEvent)
                }
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val DISCOVERY_PORT = 8788

        /** Asks the network "camera-ai?" and collects who answers for waitMs. */
        fun discover(waitMs: Long = 1500): List<Found> {
            val found = LinkedHashMap<String, Found>()
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 250
                val ask = "camera-ai?".toByteArray()
                s.send(DatagramPacket(ask, ask.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + waitMs
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val reply = runCatching { JSONObject(String(p.data, 0, p.length)) }.getOrNull() ?: continue
                    if (reply.optString("hub") != "camera-ai") continue
                    val root = "http://${p.address.hostAddress}:${reply.optInt("port", 8787)}"
                    found[root] = Found(root, reply.optString("serial").ifEmpty { null })
                }
            }
            return found.values.toList()
        }

        /** Signs in with camera-ai's password; returns the key (this phone's own, under its name). */
        fun login(root: String, password: String, name: String): String {
            val reply = json("$root/api/login", JSONObject().put("password", password).put("name", name).toString(), allowError = true)
            if (!reply.optBoolean("ok")) throw IOException(reply.optString("error", "senha errada"))
            return reply.getString("key")
        }

        /** Asks to be let in: someone already in taps Allow on camera-ai. Returns the request's id. */
        fun requestAccess(root: String, name: String): String {
            val reply = json("$root/api/access/request", JSONObject().put("name", name).toString())
            if (!reply.optBoolean("ok")) throw IOException(reply.optString("error", "não deu"))
            return reply.getString("id")
        }

        /** The key once someone allowed the request, null while it waits; IOException if refused or expired. */
        fun accessKey(root: String, id: String): String? {
            val reply = json("$root/api/access/status?id=$id")
            return when (reply.optString("status")) {
                "ok" -> reply.getString("key")
                "pending" -> null
                "no" -> throw IOException("recusado")
                else -> throw IOException("o pedido expirou")
            }
        }

        private fun open(url: String, body: String?, timeoutMs: Int): HttpURLConnection {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = timeoutMs
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            return c
        }

        private fun json(url: String, body: String? = null, timeoutMs: Int = 15_000, allowError: Boolean = false): JSONObject {
            val c = open(url, body, timeoutMs)
            try {
                val code = c.responseCode
                if (code == 401 && !allowError) throw Locked()
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (code >= 400 && !allowError) throw IOException("camera-ai respondeu $code")
                return JSONObject(text.ifEmpty { "{}" })
            } finally {
                c.disconnect()
            }
        }
    }
}

/**
 * The live picture: reads camera-ai's MJPEG and hands each JPEG over as soon as it's whole. JPEGs are found by their
 * start (FF D8) and end (FF D9) markers, so the multipart headers don't matter. Reconnects by itself.
 */
class Mjpeg(private val url: String, private val onFrame: (ByteArray) -> Unit, private val onError: (String) -> Unit) {
    @Volatile private var running = true
    @Volatile private var conn: HttpURLConnection? = null

    fun start() = thread(name = "mjpeg", isDaemon = true) {
        while (running) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 10_000
                conn = c
                if (c.responseCode == 401) throw Locked()
                BufferedInputStream(c.inputStream, 1 shl 16).use { read(it) }
            } catch (e: Exception) {
                if (!running) break
                onError(e.message ?: "sem imagem")
                Thread.sleep(1500)
            }
        }
    }

    private fun read(input: java.io.InputStream) {
        val chunk = ByteArray(1 shl 16)
        val frame = ByteArrayOutputStream(256 * 1024)
        var inJpeg = false
        var prev = 0
        while (running) {
            val n = input.read(chunk)
            if (n < 0) return
            var start = 0
            for (i in 0 until n) {
                val b = chunk[i].toInt() and 0xFF
                if (!inJpeg) {
                    if (prev == 0xFF && b == 0xD8) {
                        inJpeg = true
                        frame.reset()
                        frame.write(0xFF)
                        start = i
                    }
                } else if (prev == 0xFF && b == 0xD9) {
                    frame.write(chunk, start, i - start + 1)
                    inJpeg = false
                    onFrame(frame.toByteArray())
                }
                prev = b
            }
            if (inJpeg) frame.write(chunk, start, n - start)
        }
    }

    fun stop() {
        running = false
        runCatching { conn?.disconnect() }
    }
}
