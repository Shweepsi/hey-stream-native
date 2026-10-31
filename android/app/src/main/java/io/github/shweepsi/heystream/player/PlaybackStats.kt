package io.github.shweepsi.heystream.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.shweepsi.heystream.core.describeStreamUrl
import io.github.shweepsi.heystream.data.Telemetry

/**
 * Mesures d'une lecture pour l'APK testeur ([Telemetry]) : démarrage,
 * mises en mémoire tampon, erreurs, formats, décodeurs, débit, durée vue.
 * Le flux est désigné par sa sorte (`live`, `movie`, `series`) et son
 * numéro, jamais par son URL. Fil principal seulement.
 */
@OptIn(UnstableApi::class)
internal class PlaybackStats(item: MediaItem, private val live: Boolean, startMs: Long) {
    /** Ouverture réelle du flux (pas le choix, s'il a attendu en arrière-plan). */
    private var opened = SystemClock.elapsedRealtime()
    private var openedOnce = false

    /** Le prochain chargement vient d'une action (ouverture, déplacement). */
    private var expectedLoad = false
    private val kind: String?
    private val streamId: String?
    private val container: String?
    private var ready = false
    private var rebuffers = 0
    private var rebufferStart = 0L
    private var rebufferMs = 0L
    private var errors = 0
    private var playingSince = 0L
    private var watchedMs = 0L
    var dropped = 0
    var bandwidth = 0L

    init {
        // Le cœur lit la sorte et le numéro dans l'adresse ; elle n'est jamais gardée.
        val ref = item.localConfiguration?.uri?.toString()?.let { runCatching { describeStreamUrl(it) }.getOrNull() }
        kind = ref?.kind
        streamId = ref?.streamId?.toString()
        container = ref?.container
        Telemetry.event(
            "play_start",
            "live" to live,
            "kind" to kind,
            "stream_id" to streamId,
            "container" to container,
            "start_ms" to startMs,
        )
    }

    /** Le flux est réellement ouvert ; seule la première ouverture compte. */
    fun opening() {
        // Réouverture (retour au premier plan, reconnexion) : son chargement
        // n'est pas une coupure.
        expectedLoad = true
        if (openedOnce) return
        openedOnce = true
        opened = SystemClock.elapsedRealtime()
    }

    /** Déplacement demandé (avance, recul) : son chargement n'est pas une coupure. */
    fun seeking() {
        expectedLoad = true
    }

    private fun common(): Array<Pair<String, Any?>> = arrayOf("kind" to kind, "stream_id" to streamId)

    fun state(state: Int) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_READY -> {
                if (!ready) {
                    ready = true
                    Telemetry.event("play_ready", *common(), "startup_ms" to now - opened)
                }
                expectedLoad = false
                if (rebufferStart != 0L) {
                    val ms = now - rebufferStart
                    rebufferMs += ms
                    rebufferStart = 0
                    Telemetry.event("rebuffer", *common(), "ms" to ms)
                }
            }
            Player.STATE_BUFFERING -> if (ready && !expectedLoad && rebufferStart == 0L) {
                rebuffers += 1
                rebufferStart = now
            }
            else -> Unit
        }
    }

    fun playing(isPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (isPlaying) {
            playingSince = now
        } else if (playingSince != 0L) {
            watchedMs += now - playingSince
            playingSince = 0
        }
    }

    fun error(error: PlaybackException, attempt: Int) {
        errors += 1
        Telemetry.event("play_error", *common(), "code" to error.errorCodeName, "attempt" to attempt, "live" to live)
    }

    fun format(track: String, format: Format) {
        Telemetry.event(
            "play_format",
            *common(),
            "track" to track,
            "mime" to format.sampleMimeType,
            "codecs" to format.codecs,
            "width" to format.width.takeIf { it > 0 },
            "height" to format.height.takeIf { it > 0 },
            "fps" to format.frameRate.takeIf { it > 0 }?.toDouble(),
            "bitrate" to format.bitrate.takeIf { it > 0 },
            "channels" to format.channelCount.takeIf { it > 0 },
            "sample_rate" to format.sampleRate.takeIf { it > 0 },
            "language" to format.language,
        )
    }

    fun decoder(track: String, name: String, initMs: Long) {
        Telemetry.event("play_decoder", *common(), "track" to track, "decoder" to name, "init_ms" to initMs)
    }

    /** Fin de la lecture (arrêt, ou autre flux). */
    fun finish(player: Player?) {
        playing(false)
        val now = SystemClock.elapsedRealtime()
        Telemetry.event(
            "play_end",
            *common(),
            "live" to live,
            "ready" to ready,
            "open_ms" to now - opened,
            "watched_ms" to watchedMs,
            "rebuffers" to rebuffers,
            "rebuffer_ms" to rebufferMs + if (rebufferStart != 0L) now - rebufferStart else 0,
            "errors" to errors,
            "dropped_frames" to dropped,
            "bandwidth_bps" to bandwidth.takeIf { it > 0 },
            "position_ms" to player?.currentPosition?.takeIf { !live },
            "duration_ms" to player?.duration?.takeIf { !live && it > 0 },
        )
        Telemetry.memory("play_end")
    }

}
