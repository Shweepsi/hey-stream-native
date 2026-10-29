package io.github.shweepsi.heystream.data

import android.content.Context
import androidx.core.content.edit
import io.github.shweepsi.heystream.BuildConfig
import io.github.shweepsi.heystream.core.PanicObserver
import io.github.shweepsi.heystream.core.observePanics
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import kotlin.concurrent.thread

/**
 * Rapports de plantage anonymes, envoyés à GlitchTip (SDK Sentry) :
 * plantages, gels (ANR), paniques du cœur et lectures abandonnées.
 *
 * Seulement en release, avec la clé fournie par la CI (`GLITCHTIP_DSN`), et
 * tant que l'utilisateur ne les a pas coupés dans les réglages. Aucun
 * identifiant : ni compte, ni identifiant d'installation, et les URL, hôtes et
 * adresses IP sont retirés de tous les textes avant l'envoi.
 */
object CrashReports {
    private const val PREFS = "crash_reports"
    private const val KEY_ENABLED = "enabled"

    @Volatile private var enabled = false

    @Volatile private var started = false

    /** Rapports possibles dans cette version (release avec clé). */
    private val available: Boolean get() = !BuildConfig.DEBUG && BuildConfig.GLITCHTIP_DSN.isNotEmpty()

    /** Choix de l'utilisateur ; activé par défaut. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    /** Au démarrage de l'app. */
    fun start(context: Context) {
        enabled = isEnabled(context)
        if (enabled) init(context)
    }

    /** Coupé, plus rien n'est envoyé dès maintenant ; rallumé, dès maintenant aussi. */
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, value) }
        enabled = value
        if (value) init(context)
    }

    /** Lecture abandonnée après toutes les reconnexions ; [code] : nom du code d'erreur Media3. */
    fun playbackFailed(code: String, live: Boolean) {
        if (!started || !enabled) return
        Sentry.captureMessage("Lecture abandonnée : $code", SentryLevel.WARNING) { scope ->
            scope.setTag("source", "player")
            scope.setTag("playback_error", code)
            scope.setTag("live", live.toString())
            scope.fingerprint = listOf("playback", code)
        }
    }

    @Synchronized
    private fun init(context: Context) {
        if (started || !available) return
        started = true
        SentryAndroid.init(context.applicationContext) { options ->
            options.dsn = BuildConfig.GLITCHTIP_DSN
            options.release = "hey-stream@${BuildConfig.VERSION_NAME}"
            options.environment = "release"
            options.isSendDefaultPii = false
            // GlitchTip ne suit ni sessions ni performances ; rien d'autre ne part.
            options.isEnableAutoSessionTracking = false
            options.tracesSampleRate = null
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableNetworkEventBreadcrumbs = false
            options.isAnrEnabled = true
            options.setBeforeSend { event, _ -> if (enabled) anonymize(event) else null }
            options.setBeforeBreadcrumb { breadcrumb, _ -> anonymize(breadcrumb) }
        }
        // Charge la bibliothèque du cœur : hors du fil principal.
        thread(name = "crash-reports") { observePanics(RustPanics) }
    }

    private object RustPanics : PanicObserver {
        override fun onPanic(message: String, location: String) {
            // Une exception ici, dans le crochet de panique, arrêterait l'app.
            try {
                if (!enabled) return
                Sentry.captureException(RustPanic(message, location)) { scope ->
                    scope.setTag("source", "rust")
                    scope.fingerprint = listOf("rust-panic", location)
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** Panique du cœur ; la pile est celle de l'appel Kotlin qui l'a déclenchée. */
    class RustPanic(message: String, location: String) : RuntimeException("$message ($location)")

    private fun anonymize(event: SentryEvent): SentryEvent {
        event.user = null
        event.serverName = null
        event.message?.let { message ->
            message.formatted = message.formatted?.let(::scrub)
            message.message = message.message?.let(::scrub)
            message.params = null
        }
        event.exceptions?.forEach { exception -> exception.value = exception.value?.let(::scrub) }
        event.breadcrumbs?.forEach(::anonymize)
        return event
    }

    private fun anonymize(breadcrumb: Breadcrumb): Breadcrumb {
        breadcrumb.message = breadcrumb.message?.let(::scrub)
        for ((key, value) in breadcrumb.data.entries.toList()) {
            if (value is String) breadcrumb.setData(key, scrub(value))
        }
        return breadcrumb
    }

    private val url = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"'<>]+""")
    private val ipv4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b""")

    /**
     * Hôte cité par les erreurs réseau : `host "panel.example"`, `Hostname
     * panel.example not verified`, `connect to panel.example/…`.
     */
    private val host = Regex("""(?i)\b(host|hostname|connect to)\s+"?[a-z0-9-]+(?:\.[a-z0-9-]+)+"?""")

    /** Texte sans URL (identifiants compris), adresse IP ni nom d'hôte. */
    fun scrub(text: String): String = text
        .replace(url, "<url>")
        .replace(ipv4, "<ip>")
        .replace(host) { "${it.groupValues[1]} <host>" }
}
