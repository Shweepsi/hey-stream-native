package io.github.shweepsi.heystream.data

import android.security.keystore.KeyGenParameterSpec
import io.github.shweepsi.heystream.core.AccountInfo
import io.github.shweepsi.heystream.core.HeyStreamCore
import io.github.shweepsi.heystream.core.NoHandle
import io.github.shweepsi.heystream.core.SectionRefresh
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Point d'arrêt entre le test et un appel bloquant du faux cœur (fournisseur lent). */
class Gate {
    private val reached = CountDownLatch(1)
    private val opened = CountDownLatch(1)

    /** Appelé par le faux cœur : attend [open]. */
    fun pass() {
        reached.countDown()
        check(opened.await(10, TimeUnit.SECONDS)) { "porte jamais ouverte" }
    }

    fun awaitReached() = check(reached.await(10, TimeUnit.SECONDS)) { "appel jamais commencé" }

    fun open() = opened.countDown()
}

/**
 * Faux cœur, sans bibliothèque native ni fournisseur : seules les méthodes
 * qu'appelle la connexion sont simulées.
 */
class FakeCore(
    private val checking: Gate? = null,
    private val downloading: Gate? = null,
    private val sections: List<SectionRefresh> = emptyList(),
    /** Levée par [refresh] une fois [downloading] passé. */
    private val failure: Throwable? = null,
) : HeyStreamCore(NoHandle) {
    @Volatile
    var refreshed = false
        private set

    override fun authenticate(): AccountInfo {
        checking?.pass()
        return ACCOUNT
    }

    override fun refresh(force: Boolean): List<SectionRefresh> {
        downloading?.pass()
        refreshed = true
        failure?.let { throw it }
        return sections
    }

    companion object {
        val ACCOUNT = AccountInfo(expiresAt = 1_893_456_000, maxConnections = 1, activeConnections = 0, checkedAt = 1_800_000_000)
    }
}

/**
 * « AndroidKeyStore » de remplacement (Robolectric n'en a pas) : la clé AES
 * de [CredentialStore] reste en mémoire, le chiffrement est celui de la JVM.
 */
@Suppress("DEPRECATION") // Seul constructeur de l'API Android compilée pour les tests.
class FakeAndroidKeyStore : Provider("AndroidKeyStore", 1.0, "AndroidKeyStore des tests") {
    init {
        put("KeyStore.AndroidKeyStore", Store::class.java.name)
        put("KeyGenerator.AES", Generator::class.java.name)
    }

    class Store : KeyStoreSpi() {
        override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? =
            keys[alias]?.let { KeyStore.SecretKeyEntry(it) }

        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]

        override fun engineContainsAlias(alias: String) = keys.containsKey(alias)

        override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)

        override fun engineSize() = keys.size

        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }

        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit

        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key as SecretKey
        }

        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException()

        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw UnsupportedOperationException()

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String): Certificate? = null

        override fun engineGetCreationDate(alias: String): Date? = null

        override fun engineIsCertificateEntry(alias: String) = false

        override fun engineGetCertificateAlias(cert: Certificate): String? = null

        override fun engineStore(stream: OutputStream?, password: CharArray?) = throw UnsupportedOperationException()
    }

    class Generator : KeyGeneratorSpi() {
        private var alias: String? = null

        override fun engineInit(random: SecureRandom?) = Unit

        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            alias = (params as KeyGenParameterSpec).keystoreAlias
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit

        override fun engineGenerateKey(): SecretKey {
            val key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
            keys[checkNotNull(alias)] = key
            return key
        }
    }

    companion object {
        private val keys = ConcurrentHashMap<String, SecretKey>()

        fun install() {
            if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(FakeAndroidKeyStore())
        }
    }
}
