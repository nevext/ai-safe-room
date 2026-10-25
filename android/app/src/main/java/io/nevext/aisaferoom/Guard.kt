package io.nevext.aisaferoom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.util.Calendar
import kotlin.concurrent.thread

/**
 * Keeps listening to the home computer while the app is closed and warns on the phone: someone (or an animal)
 * showed up while Protection is on, with the photo; the camera went offline; its connection got weak; and the
 * day's summary at 21:00. Inside the house it reaches the computer on the Wi-Fi; outside, at the address set in
 * Conexão (if any).
 */
class Guard : Service() {
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        val ongoing = Notification.Builder(this, STATUS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("AI Safe Room")
            .setContentText("De olho na casa: avisa quando aparecer alguém")
            .setContentIntent(openApp(this, null))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, ongoing)
        if (!running) {
            running = true
            thread(name = "guard", isDaemon = true) { listen() }
            thread(name = "summary", isDaemon = true) { summaries() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private val prefs get() = getSharedPreferences("aisaferoom", Context.MODE_PRIVATE)

    /** The computer, trying the home address and then the one from outside. */
    private fun reachable(): Hub? {
        val root = prefs.getString("root", null) ?: return null
        val key = prefs.getString("key", null)
        val remote = prefs.getString("remote", null)
        for (r in listOfNotNull(root, remote)) {
            val h = Hub(r, key)
            if (runCatching { h.screen() }.isSuccess) return h
        }
        return null
    }

    private fun listen() {
        while (running) {
            val hub = reachable()
            if (hub != null) {
                runCatching {
                    hub.events({ e ->
                        if (e.optString("type") == "activity" && !e.optBoolean("update")) e.optJSONObject("item")?.let { onActivity(hub, it) }
                    }, { running })
                }
            }
            if (running) Thread.sleep(10_000)
        }
    }

    private fun onActivity(hub: Hub, a: JSONObject) {
        val type = a.optString("type")
        when {
            (type == "person" || type == "animal") && a.optBoolean("alert") -> {
                val picture = a.optString("thumb").takeIf { it.isNotEmpty() && it != "null" }
                    ?.let { runCatching { hub.bytes(hub.thumbUrl(it)) }.getOrNull() }
                    ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                notify(ALERTS, a.optLong("at").toInt(), a.optString("text"), "Toque para ver o que aconteceu", picture)
            }
            type == "offline" -> notify(ALERTS, 2, "Câmera offline", "${a.optString("camera")}: o computador não acha a câmera", null)
            type == "quality" && a.optString("text").contains("fraca") ->
                notify(SUMMARY, 3, "Conexão da câmera fraca", "A imagem pode travar. Veja o Wi-Fi perto da câmera.", null)
        }
    }

    /** Once a day at 21:00: how the day went. */
    private fun summaries() {
        while (running) {
            val now = Calendar.getInstance()
            val today = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR)
            if (prefs.getBoolean("summary", true) && now.get(Calendar.HOUR_OF_DAY) >= 21 && prefs.getInt("summaryDay", 0) != today) {
                val hub = reachable()
                val list = hub?.let { runCatching { it.activity() }.getOrNull() }
                if (list != null) {
                    val start = now.clone() as Calendar
                    start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0)
                    val items = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optLong("at") >= start.timeInMillis }
                    val people = items.count { it.optString("type") == "person" }
                    val animals = items.count { it.optString("type") == "animal" }
                    val moves = items.filter { it.optString("type") == "motion" }.sumOf { it.optInt("count", 1) }
                    val text = when {
                        people == 0 && animals == 0 -> "Dia tranquilo: ninguém apareceu" + if (moves > 0) " ($moves movimentos)." else "."
                        else -> listOfNotNull(people.takeIf { it > 0 }?.let { "$it ${if (it == 1) "pessoa" else "pessoas"}" },
                            animals.takeIf { it > 0 }?.let { "$it ${if (it == 1) "animal" else "animais"}" },
                            moves.takeIf { it > 0 }?.let { "$it movimentos" }).joinToString(" · ")
                    }
                    notify(SUMMARY, 4, "Resumo do dia", text, null)
                    prefs.edit().putInt("summaryDay", today).apply()
                }
            }
            Thread.sleep(60_000)
        }
    }

    private fun notify(channel: String, id: Int, title: String, text: String, picture: android.graphics.Bitmap?) {
        val b = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(this, "activity"))
        if (picture != null) b.setLargeIcon(picture).setStyle(Notification.BigPictureStyle().bigPicture(picture))
        getSystemService(NotificationManager::class.java).notify(id, b.build())
    }

    companion object {
        const val ALERTS = "alerts"
        const val STATUS = "status"
        const val SUMMARY = "summary"

        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(ALERTS, "Alertas", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alguém apareceu, câmera offline"
            })
            nm.createNotificationChannel(NotificationChannel(SUMMARY, "Resumo e avisos", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(STATUS, "De olho na casa", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Fica enquanto os avisos estão ligados"
            })
        }

        fun openApp(c: Context, open: String?): PendingIntent = PendingIntent.getActivity(c, open?.hashCode() ?: 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open", open),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun start(c: Context) = c.startForegroundService(Intent(c, Guard::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, Guard::class.java))
    }
}
