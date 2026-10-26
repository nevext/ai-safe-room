package io.nevext.aisaferoom

import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The camera's own control protocol (DVRIP / "Sofia", XM cameras: iCSee, XMEye), straight from the phone: what lets the
 * app work like the camera's original app, without the computer. Same as server/dvrip.mjs: a 20-byte header and JSON.
 */
class Dvrip private constructor(private val socket: Socket) {
    private val output = socket.getOutputStream()
    private var session = 0
    private var sequence = 0
    private val waiting = ConcurrentHashMap<Int, LinkedBlockingQueue<JSONObject>>()
    private val listeners = CopyOnWriteArraySet<(Int, Any) -> Unit>()
    private val lock = Object()
    @Volatile var closed = false; private set

    private fun start() {
        thread(isDaemon = true, name = "dvrip") {
            val input = DataInputStream(socket.getInputStream())
            val header = ByteArray(HEADER)
            try {
                while (!closed) {
                    input.readFully(header)
                    if (header[0] != 0xff.toByte()) continue
                    val s = le32(header, 4)
                    if (s != 0) session = s
                    val id = (header[14].toInt() and 0xff) or ((header[15].toInt() and 0xff) shl 8)
                    val size = le32(header, 16)
                    if (size < 0 || size > 1 shl 20) throw IOException("pacote grande demais")
                    val body = ByteArray(size).also { input.readFully(it) }
                    val queue = waiting[id]
                    val json = runCatching { JSONObject(String(body, Charsets.UTF_8).trimEnd('\u0000', '\n', '\r')) }.getOrNull()
                    if (queue != null && json != null) queue.offer(json)
                    else for (fn in listeners) runCatching { fn(id, json ?: body) }
                }
            } catch (_: Exception) {
            } finally { close() }
        }
        thread(isDaemon = true, name = "dvrip-keepalive") {
            while (!closed) {
                Thread.sleep(15_000)
                runCatching { request(1006, JSONObject().put("Name", "KeepAlive")) }
            }
        }
    }

    private fun write(id: Int, body: ByteArray) {
        val header = ByteArray(HEADER)
        header[0] = 0xff.toByte()
        put32(header, 4, session); put32(header, 8, sequence++)
        header[14] = (id and 0xff).toByte(); header[15] = ((id shr 8) and 0xff).toByte()
        put32(header, 16, body.size)
        output.write(header + body); output.flush()
    }

    /** One request and its answer (the answer comes as id + 1). One at a time; gives up after [timeoutMs]. */
    fun request(id: Int, payload: JSONObject, timeoutMs: Long = 10_000): JSONObject = synchronized(lock) {
        if (closed) throw IOException("sessão fechada")
        val queue = LinkedBlockingQueue<JSONObject>()
        waiting[id + 1] = queue
        try {
            payload.put("SessionID", "0x%08X".format(session))
            write(id, (payload.toString() + "\n\u0000").toByteArray(Charsets.UTF_8))
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw IOException("a câmera não respondeu a tempo")
        } finally { waiting.remove(id + 1) }
    }

    /** A message that gets no answer (the talk audio, Start/Stop of the talk). */
    fun send(id: Int, body: ByteArray) = synchronized(lock) { write(id, body) }
    fun sendJson(id: Int, payload: JSONObject) = send(id, (payload.put("SessionID", "0x%08X".format(session)).toString() + "\n\u0000").toByteArray())
    /** What the camera sends by itself (alarms: 1504; its microphone in a talk: 1433). */
    fun listen(fn: (Int, Any) -> Unit): () -> Unit { listeners.add(fn); return { listeners.remove(fn) } }
    val sessionId: String get() = "0x%08X".format(session)

    fun close() { if (!closed) { closed = true; runCatching { socket.close() } } }

    companion object {
        private const val HEADER = 20

        /** Connects and logs in (the camera's user and password). */
        fun open(host: String, user: String, password: String, port: Int = 34567): Dvrip {
            val socket = Socket().apply { connect(InetSocketAddress(host, port), 4000); soTimeout = 0; tcpNoDelay = true }
            val d = Dvrip(socket)
            d.start()
            val r = d.request(1000, JSONObject().put("EncryptType", "MD5").put("LoginType", "DVRIP-Web")
                .put("PassWord", hash(password)).put("UserName", user))
            if (r.optInt("Ret") != 100) { d.close(); throw IOException("a câmera recusou o login (${r.optInt("Ret")})") }
            r.optString("SessionID").removePrefix("0x").toLongOrNull(16)?.let { d.session = it.toInt() }
            return d
        }

        /** The XM password hash: MD5, folded to 8 characters. */
        fun hash(password: String): String {
            val d = MessageDigest.getInstance("MD5").digest(password.toByteArray())
            val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            return (0 until 8).map { chars[((d[it * 2].toInt() and 0xff) + (d[it * 2 + 1].toInt() and 0xff)) % 62] }.joinToString("")
        }

        private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        private fun put32(b: ByteArray, o: Int, v: Int) { for (i in 0..3) b[o + i] = ((v shr (8 * i)) and 0xff).toByte() }
    }
}
