package io.github.shweepsi.heystream.recording

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.shweepsi.heystream.HeyStreamApp
import io.github.shweepsi.heystream.core.CaptureEnd
import io.github.shweepsi.heystream.core.CaptureReport
import io.github.shweepsi.heystream.core.DueRecording
import io.github.shweepsi.heystream.core.Recording
import io.github.shweepsi.heystream.core.RecordingControl
import io.github.shweepsi.heystream.core.RecordingStore
import io.github.shweepsi.heystream.data.Telemetry
import io.github.shweepsi.heystream.ui.theme.isTelevision
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Service au premier plan (type `specialUse`, `docs/DECISIONS.md`) : attend
 * le début d'un enregistrement, copie le flux, puis enchaîne le suivant.
 * Démarré par l'alarme une minute avant, et sur TV dès 12 h avant (la box
 * reste éveillée). La copie bloque un fil à part ; toute la logique
 * (fenêtres, reprise, bilan) est dans le cœur.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val copyThread = Executors.newSingleThreadExecutor { Thread(it, "recording-copy") }.asCoroutineDispatcher()
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val app get() = application as HeyStreamApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.recordings.active.value?.control?.stop()
            if (loop?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground(current ?: RecordingNotifications.waiting(this, null, null))) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        // Tué par le système : relancé, il reprend la copie dans le même fichier.
        return START_STICKY
    }

    override fun onDestroy() {
        app.recordings.active.value?.control?.stop()
        scope.cancel()
        copyThread.close()
        releaseLocks()
        super.onDestroy()
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(
            this,
            RecordingNotifications.ONGOING_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        true
    } catch (_: Exception) {
        // Démarrage au premier plan refusé (alarme inexacte, app en arrière-plan) :
        // l'enregistrement manqué sera signalé au prochain lancement.
        false
    }

    /** Dernière notification montrée : reprise si le service est redémarré. */
    private var current: Notification? = null

    private fun show(notification: Notification) {
        current = notification
        getSystemService(NotificationManager::class.java)?.notify(RecordingNotifications.ONGOING_ID, notification)
    }

    private suspend fun run() {
        val recordings = app.recordings
        acquireLocks()
        try {
            while (scope.isActive) {
                val store = recordings.withStore { it }
                // Sans copie en cours : manqués et interrompus d'avant, signalés.
                recordings.withStore { it.recover() }.forEach { RecordingNotifications.result(this, it) }
                val due = recordings.withStore { it.due() }
                if (due != null) {
                    if (!record(store, due)) break
                    recordings.changed()
                    continue
                }
                val next = recordings.withStore { it.nextStart() } ?: break
                val wait = next - nowSecs()
                val keepAwake = isTelevision() && wait <= RecordingScheduler.KEEP_AWAKE_SECS
                // Trop tôt : l'alarme reviendra une minute avant.
                if (wait > RecordingScheduler.LEAD_SECS + LATE_MARGIN_SECS && !keepAwake) break
                val upcoming = recordings.withStore { s -> s.scheduled(true).firstOrNull { it.state == io.github.shweepsi.heystream.core.RecordingState.SCHEDULED } }
                show(RecordingNotifications.waiting(this, upcoming, next))
                if (wait <= RecordingScheduler.LEAD_SECS + LATE_MARGIN_SECS) announce(upcoming, next)
                val until = if (wait > RecordingScheduler.LEAD_SECS) next - RecordingScheduler.LEAD_SECS else next
                // Attente interrompue si le planning change (annulation, ajout).
                withTimeoutOrNull(((until - nowSecs()).coerceAtLeast(1)) * 1000) { recordings.version.drop(1).first() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Telemetry.event("recording_service_error", "error" to error.javaClass.simpleName)
        } finally {
            recordings.setCountdown(null)
            if (recordings.active.value == null) app.player.recordingActive = false
            releaseLocks()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            RecordingScheduler.update(this)
        }
    }

    /** Compte à rebours si une lecture est en cours : elle s'arrêtera au début. */
    private fun announce(upcoming: Recording?, startsAt: Long) {
        val status = app.player.status.value
        if (upcoming == null || !status.active || status.blocked) {
            app.recordings.setCountdown(null)
            return
        }
        app.recordings.setCountdown(
            RecordingCountdown(upcoming.id, RecordingNotifications.title(this, upcoming), upcoming.isAdult, startsAt),
        )
    }

    /** Copie de [due] ; `false` si plus rien ne peut être copié (déconnexion). */
    private suspend fun record(store: RecordingStore, due: DueRecording): Boolean {
        val recordings = app.recordings
        recordings.setCountdown(null)
        val core = app.session.openedCore()
        if (core == null) {
            // Plus d'abonnement : rien ne peut être copié.
            withContext(Dispatchers.IO) { store.cancelAllScheduled() }
            return false
        }
        val job = try {
            withContext(Dispatchers.IO) { store.begin(due.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Disque absent ou plein : le cœur a marqué l'enregistrement raté.
            withContext(Dispatchers.IO) { store.recording(due.id) }?.let { RecordingNotifications.result(this, it) }
            // Connexion gardée pour cet enregistrement (voir plus bas) : libérée.
            app.player.recordingActive = false
            return true
        }
        val recording = withContext(Dispatchers.IO) { store.recording(due.id) }
        // La seule connexion va à l'enregistrement : la lecture d'un autre flux s'arrête.
        app.player.preemptForRecording()
        val control = RecordingControl()
        recordings.setActive(ActiveRecording(due.id, due.streamId, job.path, control))
        show(RecordingNotifications.ongoing(this, recording, job.until, 0))
        Telemetry.event("recording_start", "secs" to job.until - nowSecs())
        val follow = scope.launch {
            // Fenêtre changée (programme ajouté à la suite, marge raccourcie).
            launch {
                recordings.version.drop(1).collect {
                    withContext(Dispatchers.IO) { store.due() }?.takeIf { it.id == due.id }?.let { control.setUntil(it.stop) }
                }
            }
            while (isActive) {
                delay(PROGRESS_MS)
                val bytes = control.bytes()
                withContext(Dispatchers.IO) { runCatching { store.updateProgress(due.id, bytes) } }
                show(RecordingNotifications.ongoing(this@RecordingService, recording, control.until(), bytes.toLong()))
            }
        }
        var finished: Recording? = null
        var report: CaptureReport? = null
        try {
            report = try {
                withContext(copyThread) { core.record(job, control) }
            } catch (cancelled: CancellationException) {
                control.stop()
                throw cancelled
            } catch (_: Throwable) {
                // Abonnement fermé pendant la copie (déconnexion) : le fichier reste.
                val size = File(job.path).length().coerceAtLeast(0)
                CaptureReport(CaptureEnd.CONNECTION_LOST, size.toULong(), 0u, 0, 0)
            }
            follow.cancel()
            finished = withContext(Dispatchers.IO) { store.finish(due.id, report) }
        } finally {
            // Toujours, même si la copie est annulée ou si le bilan échoue :
            // sinon tout flux resterait refusé et le fichier « en cours » attendu.
            follow.cancel()
            // Un enregistrement suivant commence aussitôt (deux programmes à la
            // suite sur deux chaînes) : la connexion reste à lui, aucun flux du
            // fournisseur ne s'intercale. Sinon, libérée avant l'annonce.
            val next = withContext(NonCancellable + Dispatchers.IO) { runCatching { store.due() }.getOrNull() }
            if (next == null || next.id == due.id) app.player.recordingActive = false
            recordings.setActive(null)
        }
        val ended = finished ?: return true
        Telemetry.event("recording_end", "end" to (report?.end?.name ?: "?"), "gap" to (report?.gapSecs ?: 0))
        RecordingNotifications.result(this, ended)
        withContext(Dispatchers.IO) { runCatching { store.usage() }.getOrNull() }
            ?.takeIf { it.low }
            ?.let { RecordingNotifications.lowSpace(this, it.remainingSecs) }
        return true
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeyStream:recording")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HeyStream:recording")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    companion object {
        private const val ACTION_STOP = "io.github.shweepsi.heystream.STOP_RECORDING"

        /** Avancement enregistré et notification mise à jour. */
        private const val PROGRESS_MS = 30_000L

        /** Alarme en retard de si peu : on attend quand même le début. */
        private const val LATE_MARGIN_SECS = 30L

        private fun nowSecs() = System.currentTimeMillis() / 1000

        /** Démarre le service (alarme, programmation proche, reprise). */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java)) }
        }

        /** Bouton « Arrêter » de la notification. */
        fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Arrête la copie en cours (le fichier est gardé). */
        fun stopRecording(context: Context) {
            (context.applicationContext as HeyStreamApp).recordings.active.value?.control?.stop()
        }
    }
}
