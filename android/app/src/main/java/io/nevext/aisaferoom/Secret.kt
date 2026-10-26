package io.nevext.aisaferoom

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets kept on the phone (the camera's password, the access key to camera-ai) encrypted with a key that lives in
 * the Android Keystore: it never leaves the phone, so the saved file alone (a backup, a rooted phone) reveals nothing.
 */
object Secret {
    private const val ALIAS = "aisaferoom-secrets"
    private const val PREFIX = "v1:"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** The secret back; a value saved before encryption existed comes as it was (and is sealed on the next save). */
    fun open(stored: String?): String? {
        if (stored == null || !stored.startsWith(PREFIX)) return stored
        return runCatching {
            val (iv, data) = stored.removePrefix(PREFIX).split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }
}

fun SharedPreferences.getSecret(name: String): String? = Secret.open(getString(name, null))
fun SharedPreferences.Editor.putSecret(name: String, value: String?): SharedPreferences.Editor =
    if (value == null) remove(name) else putString(name, Secret.seal(value))

/** Seals what was saved before encryption existed (opening the app once is enough). */
fun SharedPreferences.sealOld(vararg names: String) {
    val edit = edit()
    for (n in names) getString(n, null)?.takeIf { !it.startsWith("v1:") }?.let { edit.putSecret(n, it) }
    edit.apply()
}
