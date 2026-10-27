package io.nevext.aisaferoom

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The person's account: "Entrar com o Google" into Firebase Auth, and their house in Firestore.
 *
 * homes/{id}: { name, owners: [uid] (até 2), uids: [uid] (todos), members: { uid: { role, name, email, until } },
 *               hub: { root, serial },     — the computer (only for the AI)
 *               camera: { addr, user, pass, serial, name, twoLenses } }   — the camera itself: any phone of the house
 *                                           talks to it straight, without the computer (only the house reads it)
 * invites/{homeId}_{email}: { homeId, homeName, email, role, hours, by, byName, at }   — the owner's invite by e-mail
 *
 * Who may do what is in firestore.rules (the owners change the house; an invited person only adds themselves, with
 * the role of the invite). camera-ai reads the same house with the person's token to know their role.
 */
object Account {
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    val user: FirebaseUser? get() = auth.currentUser

    /** Google's account picker, then Firebase. Throws with a message for the screen. */
    suspend fun signIn(activity: Activity) {
        val webClient = activity.getString(R.string.default_web_client_id)
        val option = GetGoogleIdOption.Builder().setServerClientId(webClient).setFilterByAuthorizedAccounts(false).setAutoSelectEnabled(false).build()
        val result = try {
            CredentialManager.create(activity).getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
        } catch (e: GetCredentialCancellationException) {
            throw IOException("Você fechou a escolha da conta. Toque em Entrar com o Google de novo.")
        } catch (e: NoCredentialException) {
            throw IOException("Nenhuma conta Google neste celular. Adicione uma em Configurações → Contas e tente de novo.")
        }
        val cred = result.credential
        if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            throw IOException("O Google não devolveu a conta. Tente de novo.")
        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
        val u = user ?: throw IOException("Não deu para entrar. Tente de novo.")
        db.collection("users").document(u.uid).set(mapOf("name" to u.displayName, "email" to u.email?.lowercase(), "at" to FieldValue.serverTimestamp())).await()
    }

    fun signOut() { auth.signOut() }

    /** The ID token for camera-ai (blocking: Hub calls are off the main thread). Cached by Firebase for ~1 h. */
    fun token(): String? = runCatching { user?.let { Tasks.await(it.getIdToken(false), 10, TimeUnit.SECONDS).token } }.getOrNull()

    private val email get() = user?.email?.lowercase() ?: ""

    /** The houses this person is in (owner, member or guest). */
    suspend fun homes(): List<Home> {
        val u = user ?: return emptyList()
        return db.collection("homes").whereArrayContains("uids", u.uid).get().await().documents.mapNotNull { Home.from(it.id, it.data) }
    }

    /** A new house with this person as its owner (the first time they set up a camera). */
    suspend fun createHome(name: String, root: String?, serial: String?): Home {
        val u = user ?: throw IOException("Entre com o Google primeiro.")
        val doc = db.collection("homes").document()
        val data = mapOf("name" to name, "owners" to listOf(u.uid), "uids" to listOf(u.uid),
            "members" to mapOf(u.uid to mapOf("role" to "owner", "name" to (u.displayName ?: email), "email" to email)),
            "hub" to mapOf("root" to root, "serial" to serial), "at" to FieldValue.serverTimestamp())
        doc.set(data).await()
        return Home.from(doc.id, data)!!
    }

    /** The camera in the house: every phone of the family opens it straight from here (no computer needed). */
    suspend fun setCamera(home: Home, a: CameraLink.Access) {
        db.collection("homes").document(home.id).update("camera", mapOf("addr" to a.addr, "user" to a.user, "pass" to a.pass,
            "serial" to a.serial, "name" to a.name, "twoLenses" to a.twoLenses)).await()
    }

    suspend fun setHub(home: Home, root: String?, serial: String?) {
        db.collection("homes").document(home.id).update("hub", mapOf("root" to root, "serial" to serial)).await()
    }

    /** The owner invites by e-mail: the person signs in with that Google account and lands in the house. */
    suspend fun invite(home: Home, to: String, role: String, hours: Int) {
        val u = user ?: throw IOException("Entre com o Google primeiro.")
        val e = to.trim().lowercase()
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(e)) throw IOException("Esse e-mail não parece certo.")
        if (home.members.values.any { it.email == e }) throw IOException("Essa pessoa já está na casa.")
        db.collection("invites").document("${home.id}_$e").set(mapOf("homeId" to home.id, "homeName" to home.name, "email" to e, "role" to role,
            "hours" to hours, "by" to u.uid, "byName" to (u.displayName ?: email), "at" to FieldValue.serverTimestamp())).await()
    }

    suspend fun invitesOf(home: Home): List<Map<String, Any?>> =
        db.collection("invites").whereEqualTo("homeId", home.id).get().await().documents.map { it.data.orEmpty() + ("id" to it.id) }

    suspend fun cancelInvite(id: String) { db.collection("invites").document(id).delete().await() }

    /** Invites waiting for this person (by their Google e-mail). */
    suspend fun myInvites(): List<Map<String, Any?>> {
        if (email.isEmpty()) return emptyList()
        return db.collection("invites").whereEqualTo("email", email).get().await().documents.map { it.data.orEmpty() + ("id" to it.id) }
    }

    /** Joins the house of the invite with its role (the rules only allow exactly this), then the invite goes. */
    suspend fun accept(invite: Map<String, Any?>): Home {
        val u = user ?: throw IOException("Entre com o Google primeiro.")
        val homeId = invite["homeId"] as String
        val role = invite["role"] as? String ?: "member"
        val member = mutableMapOf<String, Any?>("role" to role, "name" to (u.displayName ?: email), "email" to email)
        if (role == "guest") member["until"] = System.currentTimeMillis() + ((invite["hours"] as? Number)?.toLong() ?: 8L) * 3600_000
        val ref = db.collection("homes").document(homeId)
        val updates = mutableMapOf<String, Any>("uids" to FieldValue.arrayUnion(u.uid), "members.${u.uid}" to member)
        if (role == "owner") updates["owners"] = FieldValue.arrayUnion(u.uid)
        ref.update(updates).await()
        db.collection("invites").document(invite["id"] as String).delete().await()
        return Home.from(homeId, ref.get().await().data) ?: throw IOException("A casa não abriu.")
    }

    /** Owners: change someone's role (at most 2 owners, at least 1) or take them out. */
    suspend fun setRole(home: Home, uid: String, role: String, hours: Int = 0) {
        val owners = home.owners.toMutableSet()
        if (role == "owner") owners += uid else owners -= uid
        if (owners.size > 2) throw IOException("No máximo 2 donos.")
        if (owners.isEmpty()) throw IOException("Precisa ficar pelo menos 1 dono.")
        val m = home.members[uid] ?: throw IOException("Essa pessoa não está mais na casa.")
        val entry = mutableMapOf<String, Any?>("role" to role, "name" to m.name, "email" to m.email)
        if (role == "guest") entry["until"] = System.currentTimeMillis() + hours * 3600_000L
        db.collection("homes").document(home.id).update(mapOf("owners" to owners.toList(), "members.$uid" to entry)).await()
    }

    suspend fun remove(home: Home, uid: String) {
        if (home.owners == listOf(uid)) throw IOException("É o único dono: escolha outro dono antes.")
        db.collection("homes").document(home.id).update(mapOf("owners" to FieldValue.arrayRemove(uid), "uids" to FieldValue.arrayRemove(uid),
            "members.$uid" to FieldValue.delete())).await()
    }
}

data class Member(val uid: String, val role: String, val name: String, val email: String, val until: Long?)

data class Home(val id: String, val name: String, val owners: List<String>, val members: Map<String, Member>, val root: String?, val serial: String?,
                val camera: CameraLink.Access? = null) {
    fun roleOf(uid: String?): String? = uid?.let { members[it]?.role }
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun from(id: String, data: Map<String, Any?>?): Home? {
            data ?: return null
            val members = (data["members"] as? Map<String, Map<String, Any?>>).orEmpty().mapValues { (uid, m) ->
                Member(uid, m["role"] as? String ?: "member", m["name"] as? String ?: "", m["email"] as? String ?: "", (m["until"] as? Number)?.toLong())
            }
            val hub = data["hub"] as? Map<String, Any?>
            val c = data["camera"] as? Map<String, Any?>
            val camera = c?.get("addr")?.let { CameraLink.Access(it as String, c["user"] as? String ?: "admin", c["pass"] as? String ?: "",
                c["serial"] as? String ?: "", c["name"] as? String ?: "Câmera", c["twoLenses"] as? Boolean ?: false) }
            return Home(id, data["name"] as? String ?: "Casa", (data["owners"] as? List<String>).orEmpty(), members, hub?.get("root") as? String,
                hub?.get("serial") as? String, camera)
        }
    }
}
