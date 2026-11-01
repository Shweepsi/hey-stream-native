package io.github.shweepsi.heystream.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.shweepsi.heystream.MainActivity
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.Recording
import io.github.shweepsi.heystream.core.RecordingProblem
import io.github.shweepsi.heystream.core.RecordingState
import io.github.shweepsi.heystream.ui.Formats

/**
 * Notifications de l'enregistrement (`docs/DECISIONS.md`) : « en cours »
 * (exigée par Android pour le service : titre, fin, bouton Arrêter),
 * « terminé » (taille, bouton Regarder) et « problème » (manqué, incomplet,
 * arrêté faute de place, avec la raison). Le titre d'un enregistrement
 * adulte n'apparaît jamais : la notification se lit sans code PIN.
 */
object RecordingNotifications {
    private const val CHANNEL_ACTIVE = "recording_active"
    private const val CHANNEL_RESULT = "recording_result"
    const val ONGOING_ID = 7_301
    private const val RESULT_BASE_ID = 7_400

    /** Extra de [MainActivity] : enregistrement à ouvrir. */
    const val EXTRA_OPEN = "io.github.shweepsi.heystream.OPEN_RECORDING"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTIVE, context.getString(R.string.recording_channel_active), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, context.getString(R.string.recording_channel_result), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Titre montré : programme, sinon chaîne ; rien pour un contenu adulte. */
    fun title(context: Context, recording: Recording?): String = when {
        recording == null || recording.isAdult -> context.getString(R.string.recording_adult_title)
        else -> recording.programmes.firstOrNull()?.title ?: recording.channel
    }

    /** Service en attente du prochain enregistrement (TV, ou dernière minute). */
    fun waiting(context: Context, next: Recording?, startsAt: Long?): Notification =
        base(context, CHANNEL_ACTIVE)
            .setContentTitle(context.getString(R.string.recording_waiting_title))
            .setContentText(
                startsAt?.let { context.getString(R.string.recording_waiting_text, title(context, next), Formats.timeOfSeconds(it)) },
            )
            .setOngoing(true)
            .setSilent(true)
            .build()

    fun ongoing(context: Context, recording: Recording?, until: Long, bytes: Long): Notification =
        base(context, CHANNEL_ACTIVE)
            .setContentTitle(context.getString(R.string.recording_ongoing_title, title(context, recording)))
            .setContentText(
                context.getString(R.string.recording_ongoing_text, Formats.timeOfSeconds(until), Formatter.formatShortFileSize(context, bytes)),
            )
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.recording_stop), RecordingService.stopIntent(context))
            .build()

    /** Enregistrement terminé ou en échec ; [recording] tel que le cœur l'a fini. */
    fun result(context: Context, recording: Recording) {
        val problem = recording.problem
        val builder = base(context, CHANNEL_RESULT).setAutoCancel(true)
        // Adulte : ouvert depuis la liste, déverrouillée par le code PIN.
        val watchable = !recording.isAdult
        if (recording.state == RecordingState.DONE && problem == null) {
            builder
                .setContentTitle(context.getString(R.string.recording_done_title, title(context, recording)))
                .setContentText(Formatter.formatShortFileSize(context, recording.bytes.toLong()))
            if (watchable) {
                builder.setContentIntent(openIntent(context, recording.id))
                builder.addAction(0, context.getString(R.string.recording_watch), openIntent(context, recording.id))
            }
        } else {
            builder
                .setContentTitle(context.getString(R.string.recording_problem_title, title(context, recording)))
                .setContentText(context.getString(problemText(problem)))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(problemText(problem))))
            if (recording.bytes > 0u && watchable) {
                builder.setContentIntent(openIntent(context, recording.id))
                builder.addAction(0, context.getString(R.string.recording_watch), openIntent(context, recording.id))
            } else {
                builder.setContentIntent(appIntent(context))
            }
        }
        notify(context, RESULT_BASE_ID + (recording.id % 1000).toInt(), builder.build())
    }

    /** Peu d'espace : moins d'une heure d'enregistrement possible. */
    fun lowSpace(context: Context, remainingSecs: Long) {
        val (hours, minutes) = Formats.hoursMinutes(remainingSecs)
        val duration = if (hours > 0) context.getString(R.string.duration_hm, hours, minutes) else context.getString(R.string.duration_m, minutes)
        notify(
            context,
            RESULT_BASE_ID - 1,
            base(context, CHANNEL_RESULT)
                .setContentTitle(context.getString(R.string.recording_low_space_title))
                .setContentText(context.getString(R.string.recording_low_space_text, duration))
                .setContentIntent(appIntent(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun problemText(problem: RecordingProblem?): Int = when (problem) {
        RecordingProblem.GAPS -> R.string.recording_problem_gaps
        RecordingProblem.OUT_OF_SPACE -> R.string.recording_problem_out_of_space
        RecordingProblem.CONNECTION_LOST -> R.string.recording_problem_connection
        RecordingProblem.WRITE_FAILED -> R.string.recording_problem_write
        RecordingProblem.VOLUME_MISSING -> R.string.recording_problem_volume
        RecordingProblem.INTERRUPTED -> R.string.recording_problem_interrupted
        RecordingProblem.NOT_STARTED -> R.string.recording_problem_not_started
        null -> R.string.recording_problem_unknown
    }

    private fun base(context: Context, channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(appIntent(context))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    private fun notify(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }

    private fun appIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openIntent(context: Context, id: Long): PendingIntent = PendingIntent.getActivity(
        context,
        (id % Int.MAX_VALUE).toInt() + 1,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_OPEN, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
