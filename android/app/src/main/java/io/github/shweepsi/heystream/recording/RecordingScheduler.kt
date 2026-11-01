package io.github.shweepsi.heystream.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.shweepsi.heystream.HeyStreamApp
import io.github.shweepsi.heystream.ui.theme.isTelevision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Alarme du prochain enregistrement : le service démarre une minute avant
 * (compte à rebours si une lecture est en cours), même app fermée, écran
 * éteint.
 *
 * Depuis Android 14, l'alarme exacte est refusée par défaut : sans elle,
 * l'alarme peut sonner en retard (le retard compte comme un trou dans
 * l'enregistrement). L'app la demande à la première programmation.
 */
object RecordingScheduler {
    /** Le service démarre tant de secondes avant le début réel. */
    const val LEAD_SECS = 60L

    /** Sur TV, la box reste éveillée si un enregistrement commence dans ce délai. */
    const val KEEP_AWAKE_SECS = 12 * 3600L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Alarme exacte autorisée (toujours avant Android 12). */
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /** Recalcule l'alarme d'après le planning (après chaque changement, au démarrage). */
    fun update(context: Context) {
        val app = context.applicationContext as HeyStreamApp
        scope.launch {
            val next = runCatching { app.recordings.withStore { it.nextStart() } }.getOrNull()
            schedule(app, next)
        }
    }

    private fun schedule(context: Context, nextStart: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RecordingAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val keepAwake = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, RecordingAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (nextStart == null) {
            alarms.cancel(intent)
            alarms.cancel(keepAwake)
            return
        }
        val nowSecs = System.currentTimeMillis() / 1000
        // Sur TV, la veille profonde est empêchée par le service (wake lock) dès
        // 12 h avant : tout de suite si on y est, sinon une alarme le démarre ;
        // ailleurs, le service ne tourne que pendant l'enregistrement.
        if (context.isTelevision()) {
            val wakeAt = nextStart - KEEP_AWAKE_SECS
            if (wakeAt <= nowSecs) {
                alarms.cancel(keepAwake)
                RecordingService.start(context)
            } else if (canScheduleExact(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt * 1000, keepAwake)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt * 1000, keepAwake)
            }
        }
        val at = ((nextStart - LEAD_SECS).coerceAtLeast(nowSecs)) * 1000
        if (canScheduleExact(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }
}

/** Alarme : le service prend le relais (attente du début, puis copie). */
class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RecordingService.start(context)
    }
}

/**
 * Redémarrage de l'appareil, mise à jour de l'app, heure changée ou
 * autorisation d'alarme exacte accordée : l'alarme est reprogrammée (Android
 * les efface au redémarrage).
 */
class RecordingRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RecordingScheduler.update(context)
    }
}
