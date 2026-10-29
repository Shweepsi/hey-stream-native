package io.github.shweepsi.heystream.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Identifiants de l'abonnement Xtream. Jamais journalisés. */
class Credentials(val server: String, val username: String, val password: String) {
    override fun toString(): String = "Credentials(…)"
}

/**
 * Identifiants chiffrés sur l'appareil (AES-GCM, clé du Keystore Android,
 * non exportable). Ni sauvegarde ni transfert (manifeste) : une clé perdue
 * rend simplement l'app à l'écran de connexion.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    fun load(): Credentials? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val data = prefs.getString(KEY_DATA, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8))
            Credentials(json.getString("server"), json.getString("username"), json.getString("password"))
        }.getOrElse {
            clear()
            null
        }
    }

    fun save(credentials: Credentials) {
        val json = JSONObject()
            .put("server", credentials.server)
            .put("username", credentials.username)
            .put("password", credentials.password)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        // Écrit tout de suite (appelé hors du fil principal) : l'app peut être
        // tuée juste après la connexion.
        prefs.edit(commit = true) {
            putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString(KEY_DATA, Base64.encodeToString(data, Base64.NO_WRAP))
        }
    }

    fun clear() {
        prefs.edit(commit = true) { clear() }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "heystream-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_IV = "iv"
        const val KEY_DATA = "data"
    }
}
