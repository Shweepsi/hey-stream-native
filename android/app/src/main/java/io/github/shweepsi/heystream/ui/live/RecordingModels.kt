package io.github.shweepsi.heystream.ui.live

import androidx.compose.runtime.Immutable
import io.github.shweepsi.heystream.core.Recording
import io.github.shweepsi.heystream.core.RecordingMargins
import io.github.shweepsi.heystream.core.RecordingProblem
import io.github.shweepsi.heystream.core.RecordingState
import io.github.shweepsi.heystream.core.RecordingUsage
import io.github.shweepsi.heystream.core.SchedulePreview
import io.github.shweepsi.heystream.ui.UiText

/** Ligne de la catégorie Enregistrements. */
@Immutable
data class RecordingRow(
    val id: Long,
    val streamId: Long,
    /** Premier programme, sinon la chaîne. */
    val title: String,
    /** Programmes réunis après le premier (« et 2 autres »). */
    val more: Int,
    val channel: String,
    val state: RecordingState,
    val problem: RecordingProblem?,
    /** Fenêtre demandée (marges comprises), secondes Unix. */
    val start: Long,
    val stop: Long,
    val bytes: Long,
    val volumeMissing: Boolean,
    val watched: Boolean,
    /** Avancement de la lecture (fait), entre 0 et 1 ; `null` si pas commencé. */
    val progress: Float?,
) {
    /** Se regarde : fini avec un fichier, ou en cours (fichier qui grandit). */
    val playable: Boolean
        get() = !volumeMissing && (state == RecordingState.RECORDING || (bytes > 0 && state != RecordingState.SCHEDULED))
}

fun Recording.toRow(): RecordingRow = RecordingRow(
    id = id,
    streamId = streamId,
    title = programmes.firstOrNull()?.title ?: channel,
    more = (programmes.size - 1).coerceAtLeast(0),
    channel = channel,
    state = state,
    problem = problem,
    start = start,
    stop = stop,
    bytes = bytes.toLong(),
    volumeMissing = volumeMissing,
    watched = watched,
    progress = if (durationMs > 0 && positionMs > 0 && !watched) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null,
)

/** Catégorie Enregistrements : en cours et programmés d'abord, puis les faits. */
@Immutable
data class RecordingsUi(
    val rows: List<RecordingRow> = emptyList(),
    val usage: RecordingUsage? = null,
    /** Aucun disque utilisable (débranché, ou pas de dossier privé possible). */
    val noDisk: Boolean = false,
    val loaded: Boolean = false,
)

/** Pas d'une marge (télécommande : moins / plus). */
const val MARGIN_STEP_SECS = 60L

/** Marge maximale proposée, comme dans le cœur. */
const val MARGIN_MAX_SECS = 3600L

fun RecordingMargins.adjusted(before: Boolean, delta: Long): RecordingMargins =
    if (before) {
        copy(beforeSecs = (beforeSecs + delta).coerceIn(0, MARGIN_MAX_SECS))
    } else {
        copy(afterSecs = (afterSecs + delta).coerceIn(0, MARGIN_MAX_SECS))
    }

/** Programmation d'un programme du guide (ou annulation s'il est déjà programmé). */
@Immutable
data class RecordDialogUi(
    val channelId: Long,
    val channel: String,
    val programme: LiveProgramme,
    val margins: RecordingMargins,
    /** Enregistrement qui couvre déjà ce programme : le dialogue propose de l'annuler. */
    val scheduledId: Long? = null,
    /** Fusion, marges raccourcies, taille, place manquante ; `null` pendant le calcul. */
    val preview: SchedulePreview? = null,
    val error: UiText? = null,
    val busy: Boolean = false,
    /** Place manquante : enregistrements proposés à la suppression. */
    val cleanup: List<RecordingRow>? = null,
)

/**
 * Programmes déjà programmés de la chaîne [channelId] : leurs débuts, pour
 * marquer les lignes du guide.
 */
fun scheduledStarts(recordings: List<Recording>, channelId: Long): Set<Long> =
    recordings.asSequence()
        .filter { it.streamId == channelId && (it.state == RecordingState.SCHEDULED || it.state == RecordingState.RECORDING) }
        .flatMap { it.programmes.asSequence() }
        .map { it.start }
        .toSet()

/** Enregistrement programmé ou en cours qui couvre ce programme de la chaîne. */
fun recordingFor(recordings: List<Recording>, channelId: Long, programme: LiveProgramme): Recording? =
    recordings.firstOrNull { r ->
        r.streamId == channelId &&
            (r.state == RecordingState.SCHEDULED || r.state == RecordingState.RECORDING) &&
            r.programmes.any { it.start == programme.start }
    }
