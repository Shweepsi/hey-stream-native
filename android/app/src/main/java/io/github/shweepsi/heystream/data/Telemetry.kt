package io.github.shweepsi.heystream.data

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Debug
import androidx.core.content.edit
import io.github.shweepsi.heystream.BuildConfig
import io.github.shweepsi.heystream.core.TelemetryLog
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Données d'usage de l'**APK testeur** (`docs/DECISIONS.md`, « Données des
 * testeurs ») : les testeurs ont donné leur accord au propriétaire, qui leur
 * remet cet APK. L'APK public n'en a pas : [active] y est toujours faux.
 *
 * Deux sortes de fichiers, écrits par le cœur, envoyés puis effacés par
 * [TelemetryWorker] :
 * - les réponses du panneau, nettoyées par le cœur (enregistrement complet,
 *   dossier [responsesDir]) ;
 * - les événements, une ligne JSON chacun (dossier [eventsDir]) : appareil,
 *   écrans, recherches, lectures, erreurs, plantages, mémoire.
 *
 * Jamais d'URL, d'identifiant ni d'adresse : un flux est désigné par son
 * numéro (`stream_id`), un texte d'erreur passe par [CrashReports.scrub].
 * L'appareil est désigné par un numéro aléatoire, sans lien avec la personne.
 * La collecte peut être coupée à distance (service de collecte) : rien
 * n'est plus écrit et tout ce qui attendait est effacé.
 */
object Telemetry {
    private const val PREFS = "telemetry"
    private const val KEY_DEVICE = "device"
    private const val KEY_PAUSED = "paused"

    /** APK testeur relié à un service de collecte. */
    val available: Boolean get() = BuildConfig.TESTER && BuildConfig.TELEMETRY_URL.isNotEmpty()

    @Volatile var active = false
        private set

    private lateinit var root: File
    private lateinit var prefs: android.content.SharedPreferences

    /** Numéro aléatoire de l'appareil. */
    lateinit var device: String
        private set

    /** Numéro de ce lancement de l'app. */
    private val launch = UUID.randomUUID().toString()

    /** Fil du journal : les écritures ne bloquent jamais l'interface. */
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "telemetry") }

    /** Journal du cœur, ouvert sur [writer] (chargement de la bibliothèque). */
    private var log: TelemetryLog? = null

    val responsesDir: File get() = File(root, "responses")
    val eventsDir: File get() = File(root, "events")

    /** Au démarrage de l'app. */
    fun start(context: Context) {
        if (!available) return
        root = File(context.filesDir, "telemetry")
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        device = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_DEVICE, it) }
        }
        active = !prefs.getBoolean(KEY_PAUSED, false)
        writer.execute {
            runCatching {
                CoreSession.loadNativeLibrary()
                log = TelemetryLog(eventsDir.absolutePath, launch)
            }
        }
        // Toujours installé : une reprise à distance en cours de lancement
        // compte aussi les plantages ([active] lu au moment du plantage).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { if (active) crash(thread, error) }
            previous?.uncaughtException(thread, error)
        }
        event("app_start", deviceInfo(context))
    }

    /**
     * Coupure (ou reprise) décidée à distance par le service de collecte.
     * Vrai si l'état a changé : l'enregistrement des réponses du cœur doit
     * alors suivre (voir [TelemetryWorker]).
     */
    fun setPaused(paused: Boolean): Boolean {
        if (!available) return false
        val changed = prefs.getBoolean(KEY_PAUSED, false) != paused
        if (changed) prefs.edit { putBoolean(KEY_PAUSED, paused) }
        active = !paused
        // Rien ne doit attendre : ni événements, ni réponses.
        if (paused) {
            writer.submit {
                log?.clear()
                responsesDir.deleteRecursively()
            }.get(10, TimeUnit.SECONDS)
        }
        return changed
    }

    /** Un événement ; les valeurs `null` sont omises. Jamais bloquant. */
    fun event(type: String, fields: Map<String, Any?> = emptyMap()) {
        if (!active) return
        val line = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("type", type)
            .put("launch", launch)
        for ((key, value) in fields) if (value != null) line.put(key, value)
        writer.execute { log?.append(line.toString()) }
    }

    fun event(type: String, vararg fields: Pair<String, Any?>) = event(type, fields.toMap())

    /** Ferme le fichier en cours : tout ce qui est écrit peut partir. */
    fun rotate() {
        if (!available) return
        writer.submit { log?.rotate() }.get(10, TimeUnit.SECONDS)
    }

    /** Mémoire de l'app, à des moments choisis (arrière-plan, fin de lecture). */
    fun memory(reason: String) {
        if (!active) return
        val runtime = Runtime.getRuntime()
        event(
            "memory",
            "reason" to reason,
            "java_used_kb" to (runtime.totalMemory() - runtime.freeMemory()) / 1024,
            "java_max_kb" to runtime.maxMemory() / 1024,
            "native_kb" to Debug.getNativeHeapAllocatedSize() / 1024,
        )
    }

    /** Plantage : écrit tout de suite, l'app s'arrête juste après. */
    private fun crash(thread: Thread, error: Throwable) {
        val stack = error.stackTrace.take(20).joinToString("\n") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        val line = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("type", "crash")
            .put("launch", launch)
            .put("thread", thread.name)
            .put("error", error.javaClass.name)
            .put("message", error.message?.let(CrashReports::scrub))
            .put("cause", error.cause?.javaClass?.name)
            .put("stack", stack)
        writer.submit {
            log?.append(line.toString())
            log?.rotate()
        }.get(2, TimeUnit.SECONDS)
    }

    private fun deviceInfo(context: Context): Map<String, Any?> {
        val metrics = context.resources.displayMetrics
        val activity = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activity?.getMemoryInfo(it) }
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return mapOf(
            "app_version" to BuildConfig.VERSION_NAME,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "abis" to Build.SUPPORTED_ABIS.joinToString(","),
            "screen_px" to "${metrics.widthPixels}x${metrics.heightPixels}",
            "density_dpi" to metrics.densityDpi,
            "tv" to (uiMode == Configuration.UI_MODE_TYPE_TELEVISION),
            "ram_mb" to memory.totalMem / (1024 * 1024),
            "low_ram" to activity?.isLowRamDevice,
            "memory_class_mb" to activity?.memoryClass,
            "language" to context.resources.configuration.locales[0]?.toLanguageTag(),
        )
    }
}
