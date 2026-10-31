package io.github.shweepsi.heystream.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.edit
import io.github.shweepsi.heystream.core.HeyStreamCore
import io.github.shweepsi.heystream.core.RecordingSummary
import io.github.shweepsi.heystream.core.clearRecording
import io.github.shweepsi.heystream.core.exportRecording
import io.github.shweepsi.heystream.core.recordingSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Outils de test, cachés : sept appuis sur la version (Réglages › À propos)
 * les font apparaître. Pour l'instant, l'enregistrement des réponses du
 * fournisseur pendant l'usage normal (cœur : `set_recording`), nettoyées de
 * tout ce qui identifie le compte, qui deviennent les réponses simulées des
 * tests. Rien ne quitte l'appareil sans « Partager ».
 */
object DevTools {
    private const val PREFS = "dev_tools"
    private const val KEY_UNLOCKED = "unlocked"
    private const val KEY_RECORDING = "recording"

    /** Appuis sur la version qui font apparaître les outils. */
    const val UNLOCK_TAPS = 7

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isUnlocked(context: Context): Boolean = prefs(context).getBoolean(KEY_UNLOCKED, false)

    fun unlock(context: Context) = prefs(context).edit { putBoolean(KEY_UNLOCKED, true) }

    fun isRecording(context: Context): Boolean = prefs(context).getBoolean(KEY_RECORDING, false)

    private fun recordingDir(context: Context) = File(context.filesDir, "recording")

    /** À l'ouverture du cœur : reprend l'enregistrement s'il est activé. */
    fun resumeRecording(context: Context, core: HeyStreamCore) {
        when {
            // APK testeur : toutes les réponses, envoyées par TelemetryWorker.
            Telemetry.active -> runCatching { core.setRecording(Telemetry.responsesDir.absolutePath, true) }
            isRecording(context) -> runCatching { core.setRecording(recordingDir(context).absolutePath, false) }
        }
    }

    /** Active ou coupe l'enregistrement, retenu pour les prochains lancements. */
    suspend fun setRecording(context: Context, session: CoreSession, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_RECORDING, enabled) }
        // APK testeur : l'enregistrement complet continue, quoi qu'il arrive.
        if (Telemetry.active) return
        val dir = recordingDir(context).absolutePath
        session.withCore { it.setRecording(if (enabled) dir else null, false) }
    }

    suspend fun summary(context: Context): RecordingSummary = withContext(Dispatchers.IO) {
        CoreSession.loadNativeLibrary()
        recordingSummary(recordingDir(context).absolutePath)
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        CoreSession.loadNativeLibrary()
        clearRecording(recordingDir(context).absolutePath)
    }

    /** Réunit les réponses en un fichier et ouvre le partage d'Android (Drive, e-mail…). */
    suspend fun share(context: Context) {
        val file = withContext(Dispatchers.IO) {
            CoreSession.loadNativeLibrary()
            val dir = File(context.cacheDir, SHARED_DIR).apply {
                deleteRecursively()
                mkdirs()
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
            File(dir, "hey-stream-recording-$stamp.json").also {
                exportRecording(recordingDir(context).absolutePath, it.absolutePath)
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Dossier du cache partagé par `FileProvider` (`res/xml/shared_files.xml`). */
    private const val SHARED_DIR = "shared"
}
