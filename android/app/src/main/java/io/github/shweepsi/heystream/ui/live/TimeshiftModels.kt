package io.github.shweepsi.heystream.ui.live

import androidx.compose.runtime.Immutable
import io.github.shweepsi.heystream.player.TimeshiftPosition
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Profondeur maximale du différé (comme le cœur) : échelle de la barre. */
const val TIMESHIFT_DEPTH_SECS = 3600L

/** En deçà de ce retard, on est « au direct ». */
const val TIMESHIFT_LIVE_TOLERANCE_SECS = 5L

/** Pause longue : prévenir une minute avant que son moment ne soit effacé. */
const val TIMESHIFT_WARN_SECS = 60L

/**
 * Barre du différé en plein écran (`docs/DECISIONS.md`, « Différé sur le
 * direct ») : retard sur le direct et partie disponible.
 */
@Immutable
data class TimeshiftUi(
    /** Retard sur le direct, en secondes. */
    val behindSecs: Long,
    /** Partie disponible (du plus ancien gardé au direct), en secondes. */
    val availableSecs: Long,
    val paused: Boolean,
    /** Saut cumulé en attente (appuis sur gauche / droite), en secondes. */
    val pendingSeekSecs: Long? = null,
    /** Pause longue : la lecture reprend seule dans ce délai (secondes). */
    val resumeInSecs: Long? = null,
) {
    val atLive: Boolean get() = behindSecs <= TIMESHIFT_LIVE_TOLERANCE_SECS

    /** Partie disponible sur la barre (1 h), depuis la droite. */
    val availableFraction: Float get() = (availableSecs.toFloat() / TIMESHIFT_DEPTH_SECS).coerceIn(0f, 1f)

    /** Curseur sur la barre, depuis la gauche (le direct est à droite). */
    val cursorFraction: Float get() = (1f - behindSecs.toFloat() / TIMESHIFT_DEPTH_SECS).coerceIn(0f, 1f)
}

/**
 * La barre d'après la position du lecteur. [eraseInSecs] : temps avant que
 * le moment affiché ne soit effacé du tampon (pause longue), `null` s'il
 * n'est pas connu.
 */
fun timeshiftUi(position: TimeshiftPosition, pendingSeekSecs: Long?, eraseInSecs: Double?): TimeshiftUi {
    val behind = (position.newest - position.at).coerceAtLeast(0.0)
    val resumeIn = eraseInSecs
        ?.takeIf { !position.playing && it <= TIMESHIFT_WARN_SECS }
        ?.let { ceil(it).toLong().coerceAtLeast(0) }
    return TimeshiftUi(
        behindSecs = behind.roundToLong(),
        availableSecs = (position.newest - position.oldest).coerceAtLeast(0.0).roundToLong(),
        paused = !position.playing,
        pendingSeekSecs = pendingSeekSecs,
        resumeInSecs = resumeIn,
    )
}

/** Cible d'un saut cumulé, bornée au tampon (le lecteur borne aussi au direct). */
fun timeshiftTarget(position: TimeshiftPosition, deltaSecs: Long): Double =
    (position.at + deltaSecs).coerceIn(position.oldest, position.newest)
