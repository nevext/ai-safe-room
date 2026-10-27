package io.nevext.aisaferoom

import android.app.Activity
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// MARK: - Entrar com o Google

/** The first screen: nothing works without the account (it says who's who in the family, and survives a reinstall). */
@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Page {
        Column(Modifier.fillMaxWidth().padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(90.dp)
            Brand(28.sp, Modifier.padding(top = 18.dp))
            Text("Suas câmeras, sua família, sua casa.", color = palette.muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(64.dp))
        Button(enabled = !busy, onClick = {
            busy = true; error = null
            scope.launch {
                runCatching { Account.signIn(activity) }.onSuccess { onSignedIn() }.onFailure { error = it.message ?: "Não deu para entrar." }
                busy = false
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = Blue), modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(52.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Text("Entrando…", modifier = Modifier.padding(start = 10.dp))
            } else Text("Entrar com o Google", fontSize = 16.sp)
        }
        error?.let { Text(it, color = Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
        Text("Ao entrar, você concorda com os Termos de uso e a Política de privacidade (em Ajustes → Sobre).",
            color = palette.muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

/** Invites waiting for this account: join the house with one tap (or say no). */
@Composable
fun InvitesScreen(invites: List<Map<String, Any?>>, onJoined: (Home) -> Unit, onSkip: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Page {
        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(64.dp)
            Text("Você foi convidado", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        }
        Spacer(Modifier.height(20.dp))
        invites.forEach { inv ->
            val id = inv["id"] as String
            val role = ROLE_TEXT[inv["role"] as? String] ?: "membro"
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).card(palette, selected = true).padding(16.dp)) {
                Text("${inv["byName"] ?: "Alguém"} chamou você para a casa \"${inv["homeName"] ?: "Casa"}\"", fontWeight = FontWeight.SemiBold)
                Text("Como $role${if (inv["role"] == "guest") " por ${(inv["hours"] as? Number)?.toInt() ?: 8} h" else ""}.", color = palette.muted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = busy == null, onClick = {
                        busy = id
                        scope.launch { runCatching { Account.cancelInvite(id) }; busy = null; onSkip() }
                    }) { Text("Recusar", color = palette.muted) }
                    Button(enabled = busy == null, onClick = {
                        busy = id; error = null
                        scope.launch {
                            runCatching { Account.accept(inv) }.onSuccess(onJoined).onFailure { error = "Não deu para entrar na casa: ${it.message}" }
                            busy = null
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                        if (busy == id) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp) else Text("Entrar na casa")
                    }
                }
            }
        }
        error?.let { Text(it, color = Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = onSkip, modifier = Modifier.padding(top = 8.dp)) { Text("Agora não: configurar a minha própria câmera", color = palette.muted, fontSize = 13.sp) }
    }
}

private val ROLE_TEXT = mapOf("owner" to "dono", "member" to "membro", "guest" to "convidado")

// MARK: - A casa e o computador

/**
 * Once connected to the computer: the house exists in the account and the computer belongs to it. The first owner
 * creates the house; a phone that isn't owner here (or a new phone) proves it once with the computer's password.
 */
@Composable
fun LinkHome(live: Live, prefs: SharedPreferences) {
    val hub = live.hub ?: return
    val user = Account.user ?: return
    if (!live.connected || live.info?.optBoolean("phoneOnly") == true) return
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) }   // the computer is someone else's house
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var home by remember { mutableStateOf<Home?>(null) }
    val link: suspend (String?) -> Unit = { pass ->
        var h = home!!
        // The camera goes into the house (then any phone opens it without the computer, and it proves the house owns it)
        if (h.roleOf(user.uid) == "owner") withContext(Dispatchers.IO) { runCatching { hub.cameraAccess() } }.getOrNull()?.let { a ->
            runCatching { Account.setCamera(h, a) }.onSuccess { h = h.copy(camera = a); home = h }
        }
        val r = withContext(Dispatchers.IO) { runCatching { hub.linkHome(h.id, pass) }.getOrElse { JSONObject().put("error", it.message) } }
        when {
            r.optBoolean("ok") -> {
                prefs.edit().putString("homeLinked", h.id).apply(); ask = false; error = null
                runCatching { Account.setHub(h, hub.root, live.info?.optJSONObject("camera")?.optString("serial")) }
                // The role from the account right away (not at the next 30 s refresh)
                withContext(Dispatchers.IO) { runCatching { hub.screen() }.getOrNull() }?.let { info ->
                    live.info = info
                    info.optJSONObject("me")?.optString("role")?.ifEmpty { null }?.let { live.role = it; prefs.edit().putString("role", it).apply() }
                }
            }
            r.optBoolean("needPassword") -> { ask = true; if (pass != null) error = "Senha errada. São os 8 números do computador." }
            else -> error = r.optString("error", "não deu")
        }
    }
    LaunchedEffect(hub, live.connected, user.uid) {
        val linked = withContext(Dispatchers.IO) { runCatching { hub.home().optString("homeId").takeIf { it.isNotEmpty() && it != "null" } }.getOrNull() }
        val mine = runCatching { Account.homes() }.getOrDefault(emptyList())
        when {
            linked != null && mine.any { it.id == linked } -> { prefs.edit().putString("homeLinked", linked).apply(); home = mine.first { it.id == linked } }
            linked != null -> other = true   // another account's house: ask its owner for an invite
            else -> {
                home = mine.firstOrNull { it.roleOf(user.uid) == "owner" } ?: runCatching {
                    Account.createHome("Casa", hub.root, live.info?.optJSONObject("camera")?.optString("serial"))
                }.getOrNull()
                if (home != null) link(null)
            }
        }
    }
    if (ask) AlertDialog(onDismissRequest = { if (!busy) ask = false },
        title = { Text("Ligar sua conta ao computador") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Uma vez só: digite a senha do computador (os 8 números criados quando o AI Safe Room foi instalado nele). " +
                    "Depois disso sua conta Google é a dona da casa, em qualquer celular.", fontSize = 13.sp)
                OutlinedTextField(password, { password = it; error = null }, singleLine = true, isError = error != null, label = { Text("Senha do computador") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue))
                error?.let { Text(it, color = Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && password.isNotBlank(), onClick = { busy = true; scope.launch { link(password.trim()); busy = false } },
                colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp) else Text("Ligar")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { ask = false }) { Text("Agora não", color = palette.muted) } })
}

// MARK: - Família (contas Google)

/** The house's people (Google accounts) and the e-mail invites: owners invite, change roles and take people out. */
@Composable
fun FamilyAccounts(live: Live) {
    val user = Account.user ?: return
    val scope = rememberCoroutineScope()
    var home by remember { mutableStateOf<Home?>(null) }
    var invites by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var email by remember { mutableStateOf("") }
    var inviting by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf<Member?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val reload: suspend () -> Unit = {
        val h = runCatching { Account.homes() }.getOrDefault(emptyList()).let { list -> list.firstOrNull { it.roleOf(user.uid) == "owner" } ?: list.firstOrNull() }
        home = h
        invites = if (h != null && h.roleOf(user.uid) == "owner") runCatching { Account.invitesOf(h) }.getOrDefault(emptyList()) else emptyList()
    }
    LaunchedEffect(Unit) { reload() }
    val h = home ?: return
    val owner = h.roleOf(user.uid) == "owner"
    Text("Pessoas (conta Google)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
    Column(Modifier.fillMaxWidth().card(palette)) {
        h.members.values.sortedBy { listOf("owner", "member", "guest").indexOf(it.role) }.forEach { m ->
            val role = ROLE_TEXT[m.role]?.replaceFirstChar { it.uppercase() } ?: "Membro"
            ListRow(Icons.Outlined.Person, m.name.ifEmpty { m.email } + if (m.uid == user.uid) " (você)" else "", "$role · ${m.email}",
                onClick = if (owner) ({ changing = m }) else null)
        }
        invites.forEach { inv ->
            ListRow(Icons.Outlined.Mail, inv["email"] as String, "Convidado como ${ROLE_TEXT[inv["role"] as? String] ?: "membro"} · esperando entrar", trailing = {
                if (owner) Icon(Icons.Outlined.Delete, "Cancelar convite", tint = palette.muted, modifier = Modifier.clickable {
                    scope.launch { runCatching { Account.cancelInvite(inv["id"] as String) }; reload() }
                }.padding(8.dp))
            })
        }
    }
    if (owner) {
        OutlinedTextField(email, { email = it; error = null }, singleLine = true, label = { Text("E-mail da pessoa (conta Google)") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue))
        Button(enabled = email.isNotBlank(), onClick = { inviting = true }, colors = ButtonDefaults.buttonColors(containerColor = Blue),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp)) { Text("Convidar") }
        Text("A pessoa instala o AI Safe Room, entra com esse Google e já cai na casa, com o papel que você escolher.",
            color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
    error?.let { Text(it, color = Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
    note?.let { Text(it, color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
    Spacer(Modifier.height(20.dp))
    if (inviting) RoleChooser("Convidar ${email.trim()} como", h.owners.size, null, onDismiss = { inviting = false }) { role, hours ->
        inviting = false
        scope.launch {
            runCatching { Account.invite(h, email, role, hours) }
                .onSuccess { note = "Convite mandado para ${email.trim()}. Quando a pessoa entrar com esse Google, ela já cai na casa."; email = ""; reload() }
                .onFailure { error = it.message }
        }
    }
    changing?.let { m ->
        RoleChooser(m.name.ifEmpty { m.email }, h.owners.size, m.role, onDismiss = { changing = null }, onRemove = if (m.uid != user.uid) ({
            changing = null
            scope.launch { runCatching { Account.remove(h, m.uid) }.onFailure { error = it.message }; reload() }
        }) else null) { role, hours ->
            changing = null
            scope.launch { runCatching { Account.setRole(h, m.uid, role, hours) }.onFailure { error = it.message }; reload() }
        }
    }
}

/** Pick dono / membro / convidado (and how long for a guest). */
@Composable
private fun RoleChooser(title: String, owners: Int, current: String?, onDismiss: () -> Unit, onRemove: (() -> Unit)? = null, onPick: (String, Int) -> Unit) {
    var role by remember { mutableStateOf(current ?: "member") }
    var hours by remember { mutableStateOf(8) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("owner" to "Dono: mexe em tudo e convida (no máximo 2)", "member" to "Membro: vê, ouve, fala, recebe avisos; não muda Proteção, IA nem a família",
                    "guest" to "Convidado: só ao vivo, ouvir e falar, até a hora marcada").forEach { (id, text) ->
                    val full = id == "owner" && current != "owner" && owners >= 2
                    Column(Modifier.fillMaxWidth().card(palette, selected = role == id).clickable(enabled = !full) { role = id }.padding(12.dp)) {
                        Text(if (full) "Dono: já tem 2 donos" else text, fontSize = 13.sp, color = if (full) palette.muted else Color.Unspecified)
                    }
                }
                if (role == "guest") Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(4 to "4 h", 8 to "8 h", 24 to "1 dia", 168 to "7 dias").forEach { (h, label) ->
                        Text(label, fontSize = 13.sp, color = if (hours == h) Color.White else palette.muted,
                            modifier = Modifier.card(palette, selected = hours == h).clickable { hours = h }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                onRemove?.let { TextButton(onClick = it) { Text("Tirar da casa", color = Red) } }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(role, if (role == "guest") hours else 0) }) { Text("Pronto") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}
