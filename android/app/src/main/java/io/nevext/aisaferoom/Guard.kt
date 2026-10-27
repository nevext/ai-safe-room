package io.nevext.aisaferoom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.Calendar
import kotlin.concurrent.thread

/**
 * Keeps an eye on the house while the app is closed and puts it in the notification bar.
 *
 * Alerts (sound, vibration, the photo; shown on the lock screen): someone in the room while Protection is on (with the
 * name when the face is known, one per visit), an animal (if the house has animals), the camera offline or covered,
 * a device asking to come in, to the computer or to the house's account (Permitir / Recusar right there). Quiet notices: the day's summary, a weak connection (at
 * most every 6 h), Protection turned on or off by its schedule. Fixed: "De olho na casa", with Protection's state.
 *
 * With the computer reachable (home Wi-Fi or the address from outside) it listens to it; with it away, straight to the
 * camera: its own person detection still warns, like the camera's original app. At night (23h–7h) only alerts sound.
 * Each kind can be turned off in Ajustes → Notificações.
 */
class Guard : Service() {
    @Volatile private var running = false
    private val prefs get() = getSharedPreferences("aisaferoom", Context.MODE_PRIVATE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        val ongoing = ongoing(this, CameraLink.get(this).protection)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ONGOING, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ONGOING, ongoing)
        if (!running) {
            running = true
            thread(name = "guard", isDaemon = true) { listen() }
            thread(name = "summary", isDaemon = true) { summaries() }
            thread(name = "joins", isDaemon = true) { watchJoins() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        joinRegs.forEach { it.remove() }
        super.onDestroy()
    }

    private fun listen() {
        while (running) {
            val hub = reachable(this)
            if (hub != null) {
                cameraAway = 0L
                runCatching {
                    hub.events({ e ->
                        when (e.optString("type")) {
                            // A visit that waited for the face ("só desconhecidos") comes again as an update with alertNow
                            "activity" -> if (!e.optBoolean("update") || e.optBoolean("alertNow")) e.optJSONObject("item")?.let { onActivity(hub, it) }
                            "access" -> e.optJSONObject("item")?.let { onAccess(it) }
                        }
                    }, { running })
                }
            } else directFromCamera()
            if (running) Thread.sleep(10_000)
        }
    }

    // MARK: - Without the computer: the camera's own alerts

    private var cameraAway = 0L
    private var lastPersonAt = 0L
    private var visitId = 0
    private fun directFromCamera() {
        val link = CameraLink.get(this)
        if (link.access == null) return
        val stop = link.alarms { event, start ->
            if (!start || !link.protection) return@alarms
            when {
                Regex("human|ped|person|people|body", RegexOption.IGNORE_CASE).containsMatchIn(event) -> {
                    val now = System.currentTimeMillis()
                    val newVisit = now - lastPersonAt > 10 * 60_000
                    lastPersonAt = now
                    if (newVisit) visitId = (now / 1000).toInt()
                    if (on("notifyPerson")) {
                        val picture = runCatching { link.snapshot() }.getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        // One notification per visit: later sightings only refresh the photo, silently
                        alert(visitId, "Pessoa no ${link.access?.name ?: "Quarto"}", "A câmera viu alguém agora", picture,
                            silent = !newVisit, actions = listOf(view(), pause()))
                    }
                }
                Regex("blind|cover|shelter", RegexOption.IGNORE_CASE).containsMatchIn(event) && on("notifyOffline") ->
                    alert(OFFLINE, "Câmera coberta", "${link.access?.name ?: "A câmera"}: a imagem foi tapada", null, actions = listOf(view()))
            }
        }
        // Stays on the camera until the computer is back; checks the camera answers (offline warning after 1 min)
        try {
            var tries = 0
            while (running && reachable(this) == null) {
                val ok = runCatching { link.dvr() }.isSuccess
                if (ok) cameraAway = 0L
                else if (cameraAway == 0L) cameraAway = System.currentTimeMillis()
                else if (System.currentTimeMillis() - cameraAway > 60_000 && on("notifyOffline")) {
                    alert(OFFLINE, "Câmera offline", "${link.access?.name ?: "A câmera"} não responde no Wi-Fi", null)
                    cameraAway = Long.MAX_VALUE / 2   // once per outage
                }
                Thread.sleep(if (++tries < 3) 10_000 else 30_000)
            }
        } finally { stop() }
    }

    // MARK: - From the computer

    private fun onActivity(hub: Hub, a: JSONObject) {
        val type = a.optString("type")
        when {
            (type == "person" || type == "animal") && a.optBoolean("alert") -> {
                if (!on(if (type == "person") "notifyPerson" else "notifyAnimal")) return
                val picture = a.optString("thumb").takeIf { it.isNotEmpty() && it != "null" }
                    ?.let { runCatching { hub.bytes(hub.thumbUrl(it)) }.getOrNull() }
                    ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                alert(a.optLong("at").toInt(), a.optString("text"), "Toque para ver o que aconteceu", picture,
                    actions = listOf(view(), mistake(a.optLong("at"), type), pause()))
            }
            type == "offline" && on("notifyOffline") -> alert(OFFLINE, "Câmera offline", "${a.optString("camera")}: a câmera não responde", null)
            type == "quality" && a.optString("text").contains("fraca") && on("notifyWeak") -> {
                // At most every 6 hours: a weak Wi-Fi tends to stay weak
                if (System.currentTimeMillis() - prefs.getLong("weakAt", 0) < 6 * 3600_000) return
                prefs.edit().putLong("weakAt", System.currentTimeMillis()).apply()
                quiet(WEAK, "Conexão da câmera fraca", "A imagem pode travar. Veja o Wi-Fi perto da câmera.")
            }
            type == "protection" -> {
                val on = a.optString("text").contains("ativada")
                CameraLink.get(this).protection = on
                getSystemService(NotificationManager::class.java).notify(ONGOING, ongoing(this, on))
                if (a.optString("label") in setOf("horário", "fim da pausa") && on("notifySchedule"))
                    quiet(SCHEDULE, if (on) "Proteção ativada" else "Proteção desativada",
                        if (a.optString("label") == "horário") "Pelo horário que você marcou" else "A pausa de 1 hora acabou")
            }
        }
    }

    private fun onAccess(r: JSONObject) {
        if (!on("notifyAccess") || prefs.getString("role", "owner") != "owner") return
        val id = r.optString("id")
        alert(id.hashCode(), "${r.optString("name")} quer entrar", "Pediu acesso às câmeras (${r.optString("ip")}). Só permita se for de casa.", null,
            actions = listOf(action("allow", "Permitir", id.hashCode(), "id" to id), action("deny", "Recusar", id.hashCode(), "id" to id)))
    }

    // MARK: - A new phone asking to come in (through the account: works without the computer)

    @Volatile private var joinRegs = emptyList<ListenerRegistration>()
    private val shownJoins = mutableSetOf<String>()

    private fun watchJoins() {
        val uid = Account.user?.uid ?: return
        val homes = runCatching { runBlocking { Account.homes() } }.getOrDefault(emptyList()).filter { it.roleOf(uid) == "owner" }
        joinRegs = homes.map { h ->
            Account.watchJoins(h) { list ->
                val nm = getSystemService(NotificationManager::class.java)
                // Answered (here or on another owner's phone): the notification goes
                shownJoins.filter { id -> id.startsWith(h.id + "_") && list.none { it.id == id } }.forEach { nm.cancel(it.hashCode()); shownJoins -= it }
                list.filter { it.id !in shownJoins }.forEach { j ->
                    shownJoins += j.id
                    if (!on("notifyAccess")) return@forEach
                    val who = j.name.ifEmpty { j.email }
                    val extras = arrayOf("homeId" to h.id, "joinId" to j.id, "uid" to j.uid, "name" to j.name, "email" to j.email, "device" to j.device)
                    alert(j.id.hashCode(), "$who quer entrar", "${j.email}, pelo ${j.device}, no Wi-Fi da câmera. Só permita se for de casa.", null,
                        actions = listOf(action("joinAllow", "Permitir", j.id.hashCode(), *extras), action("joinDeny", "Recusar", j.id.hashCode(), *extras)))
                }
            }
        }
    }

    /** Once a day (21:00): how the day went. */
    private fun summaries() {
        while (running) {
            val now = Calendar.getInstance()
            val today = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR)
            if (on("summary") && now.get(Calendar.HOUR_OF_DAY) >= 21 && prefs.getInt("summaryDay", 0) != today) {
                val list = reachable(this)?.let { runCatching { it.activity() }.getOrNull() }
                if (list != null) {
                    val start = now.clone() as Calendar
                    start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0)
                    val items = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optLong("at") >= start.timeInMillis }
                    val people = items.count { it.optString("type") == "person" }
                    val animals = items.count { it.optString("type") == "animal" }
                    val moves = items.filter { it.optString("type") == "motion" }.sumOf { it.optInt("count", 1) }
                    val text = when {
                        people == 0 && animals == 0 -> "Dia tranquilo: ninguém apareceu" + if (moves > 0) " ($moves movimentos)." else "."
                        else -> listOfNotNull(people.takeIf { it > 0 }?.let { "$it ${if (it == 1) "visita" else "visitas"}" },
                            animals.takeIf { it > 0 }?.let { "$it ${if (it == 1) "animal" else "animais"}" },
                            moves.takeIf { it > 0 }?.let { "$it movimentos" }).joinToString(" · ")
                    }
                    quiet(SUMMARY_ID, "Resumo do dia", text, open = "summaries")
                    prefs.edit().putInt("summaryDay", today).apply()
                }
            }
            Thread.sleep(60_000)
        }
    }

    // MARK: - Showing them

    private fun on(kind: String) = prefs.getBoolean(kind, true)
    private val night: Boolean get() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 23 || it < 7 }

    private fun alert(id: Int, title: String, text: String, picture: Bitmap?, silent: Boolean = false, actions: List<Notification.Action> = listOf(view())) {
        val b = Notification.Builder(this, ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(silent)
            .setContentIntent(openApp(this, "activity"))
        if (picture != null) b.setLargeIcon(picture).setStyle(Notification.BigPictureStyle().bigPicture(picture))
        actions.forEach { b.addAction(it) }
        getSystemService(NotificationManager::class.java).notify(id, b.build())
    }
    private fun quiet(id: Int, title: String, text: String, open: String? = null) {
        if (night) return   // at night only alerts
        val b = Notification.Builder(this, NOTICES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(this, open))
        getSystemService(NotificationManager::class.java).notify(id, b.build())
    }

    private fun view() = Notification.Action.Builder(null, "Ver ao vivo", openApp(this, "live")).build()
    private fun pause() = action("pause", "Pausar 1 h", PAUSE)
    private fun mistake(at: Long, type: String) = action("mistake", "Foi engano", at.toInt(), "at" to at.toString(), "type" to type)
    private fun action(what: String, label: String, id: Int, vararg extras: Pair<String, String>): Notification.Action {
        val intent = Intent(this, NotifyActions::class.java).setAction(what).putExtra("notification", id)
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
        val pending = PendingIntent.getBroadcast(this, (what + id).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, label, pending).build()
    }

    companion object {
        const val ALERTS = "alerts"
        const val STATUS = "status"
        const val NOTICES = "summary"
        private const val ONGOING = 1
        private const val OFFLINE = 2
        private const val WEAK = 3
        private const val SUMMARY_ID = 4
        private const val SCHEDULE = 5
        private const val PAUSE = 6

        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(ALERTS, "Alertas", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alguém no cômodo com a Proteção ligada, câmera offline ou coberta, pedido de acesso"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
            nm.createNotificationChannel(NotificationChannel(NOTICES, "Resumo e avisos", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Resumo do dia, conexão fraca, Proteção pelo horário (sem som)"
            })
            nm.createNotificationChannel(NotificationChannel(STATUS, "De olho na casa", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Fica enquanto os avisos estão ligados (o Android pede, para o app continuar ouvindo)"
            })
        }

        /** The fixed one: what Protection is doing, and the button to change it. */
        fun ongoing(c: Context, protection: Boolean): Notification {
            val toggle = PendingIntent.getBroadcast(c, 77, Intent(c, NotifyActions::class.java).setAction(if (protection) "off" else "on"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(c, STATUS)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("De olho na casa")
                .setContentText(if (protection) "Proteção ativada: avisa quando aparecer alguém" else "Proteção desativada: só guarda as atividades")
                .setContentIntent(openApp(c, null))
                .apply {
                    // Only owners switch the Protection
                    if (c.getSharedPreferences("aisaferoom", Context.MODE_PRIVATE).getString("role", "owner") == "owner")
                        addAction(Notification.Action.Builder(null, if (protection) "Desativar" else "Ativar", toggle).build())
                }
                .setOngoing(true)
                .build()
        }

        /** The computer, trying the home address and then the one from outside. */
        fun reachable(c: Context): Hub? {
            val prefs = c.getSharedPreferences("aisaferoom", Context.MODE_PRIVATE)
            val root = prefs.getString("root", null) ?: return null
            val key = prefs.getSecret("key")
            for (r in listOfNotNull(root, prefs.getString("remote", null))) {
                val h = Hub(r, key)
                if (runCatching { Hub.ping(h) }.getOrDefault(false)) return h
            }
            return null
        }

        fun openApp(c: Context, open: String?): PendingIntent = PendingIntent.getActivity(c, open?.hashCode() ?: 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open", open),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun start(c: Context) = c.startForegroundService(Intent(c, Guard::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, Guard::class.java))
    }
}

/** The buttons on the notifications: Permitir / Recusar, Foi engano, Pausar 1 h, Ativar / Desativar. */
class NotifyActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        val nm = context.getSystemService(NotificationManager::class.java)
        thread {
            try {
                val hub = Guard.reachable(context)
                when (intent.action) {
                    "allow", "deny" -> hub?.answerAccess(intent.getStringExtra("id") ?: "", intent.action == "allow")
                    // From the notification: as a member (the role changes later in Família)
                    "joinAllow", "joinDeny" -> runBlocking {
                        val x = { k: String -> intent.getStringExtra(k) ?: "" }
                        val h = Account.homes().first { it.id == x("homeId") }
                        Account.answerJoin(h, Join(x("joinId"), h.id, x("uid"), x("name"), x("email"), x("device")), intent.action == "joinAllow")
                    }
                    "mistake" -> hub?.forget(intent.getStringExtra("at")?.toLongOrNull() ?: 0, intent.getStringExtra("type") ?: "person")
                    "pause" -> hub?.pauseProtection(60) ?: run { CameraLink.get(context).protection = false }
                    "on", "off" -> {
                        val on = intent.action == "on"
                        runCatching { (hub ?: Hub("http://0.0.0.0", null)).setProtection(on) }
                        CameraLink.get(context).protection = on
                        nm.notify(1, Guard.ongoing(context, on))
                    }
                }
                val id = intent.getIntExtra("notification", 0)
                if (id != 0) nm.cancel(id)
            } catch (_: Exception) {
            } finally { done.finish() }
        }
    }
}
