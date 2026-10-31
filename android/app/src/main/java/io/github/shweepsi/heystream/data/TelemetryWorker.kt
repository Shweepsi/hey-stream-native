package io.github.shweepsi.heystream.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.shweepsi.heystream.BuildConfig
import io.github.shweepsi.heystream.HeyStreamApp
import io.github.shweepsi.heystream.core.TelemetryUpload
import io.github.shweepsi.heystream.core.uploadTelemetry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Envoi des données de l'APK testeur ([Telemetry]) : toutes les heures, et
 * une minute après le passage en arrière-plan. Sur tout réseau, mais jamais
 * pendant une lecture (choix du propriétaire) : l'envoi attend la suivante.
 */
class TelemetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Telemetry.available) return Result.success()
        val app = applicationContext as HeyStreamApp
        // Lecture en cours (pas un flux chargé en pause ou fermé en arrière-plan).
        val playing = withContext(Dispatchers.Main) { app.player.status.value.playing }
        if (playing) return Result.success()
        return withContext(Dispatchers.IO) {
            try {
                Telemetry.rotate()
                CoreSession.loadNativeLibrary()
                val outcome = uploadTelemetry(
                    BuildConfig.TELEMETRY_URL,
                    BuildConfig.TELEMETRY_KEY,
                    Telemetry.device,
                    BuildConfig.VERSION_NAME,
                    Telemetry.responsesDir.absolutePath,
                    Telemetry.eventsDir.absolutePath,
                )
                if (Telemetry.setPaused(outcome is TelemetryUpload.Paused)) {
                    // L'enregistrement complet des réponses suit la coupure ou la reprise.
                    runCatching {
                        app.session.withCore { core ->
                            core.setRecording(null, false)
                            DevTools.resumeRecording(applicationContext, core)
                        }
                    }
                }
                Result.success()
            } catch (_: Throwable) {
                // Hors ligne, service indisponible : les fichiers restent pour la prochaine fois.
                Result.success()
            }
        }
    }

    companion object {
        private const val PERIODIC = "telemetry-upload"
        private const val SOON = "telemetry-upload-soon"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            if (!Telemetry.available) return
            val request = PeriodicWorkRequestBuilder<TelemetryWorker>(1, TimeUnit.HOURS)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** App passée en arrière-plan : envoi dans une minute. */
        fun soon(context: Context) {
            if (!Telemetry.available) return
            val request = OneTimeWorkRequestBuilder<TelemetryWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
